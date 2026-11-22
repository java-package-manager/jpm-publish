package dev.jpm.publish;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.LoadOptions;
import dev.jpm.api.client.ProtocolMismatch;
import dev.jpm.api.model.Classpaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jpm publish}: publishes jpm modules to Maven repositories and Maven Central.
 *
 * <p>jpm runs it as an extension (any unknown command `jpm foo` runs `jpm-foo`). It reads the project with
 * jpm's protocol ({@code jpm api}), builds it with {@code jpm build}, and publishes each module with a group.
 */
public final class Main {
    static final String VERSION = "0.1.0";

    static final String HELP = """
            jpm publish - publish jpm modules to Maven repositories and Maven Central

            Usage: jpm publish [repository] [options]

              repository        A name from "extensions.publish.repositories" in project.jsonc, or a URL.
                                Without one: the only configured repository.
              --local           Publish to the local Maven repository (~/.m2/repository)
              --module <name>   Publish only this module (repeatable); default: every module with a "group"
              --dry-run         Build everything into .cache/publish/ and show what would be uploaded
              --no-build        Use the jars already in dist/
              --no-sources      Leave out the sources jar
              --no-javadoc      Leave out the javadoc jar
              --sign / --no-sign  Sign with gpg (default: on for Maven Central, off elsewhere)
              --no-wait         Maven Central: do not wait for validation

            Configuration (project.jsonc):
              "description", "license" (SPDX id), "url" and "group" go into the POM.
              "extensions": { "publish": {
                  "repositories": {
                    "faststats": { "url": "https://repo.faststats.dev/releases",
                                   "snapshots": "https://repo.faststats.dev/snapshots" },
                    "central": { "type": "central" }        // publishingType: "automatic" or "user_managed"
                  },
                  "developers": [ { "id": "me", "name": "My Name", "email": "me@example.com" } ],
                  "scm": "https://github.com/me/project",    // defaults to "url" on GitHub/GitLab/Codeberg
                  "sign": true, "javadoc": true, "skip": false
              } }

            Credentials (environment):
              JPM_PUBLISH_<REPOSITORY>_USERNAME / _PASSWORD   e.g. JPM_PUBLISH_FASTSTATS_USERNAME
              JPM_PUBLISH_USERNAME / JPM_PUBLISH_PASSWORD     for any repository
              JPM_SIGNING_KEY, JPM_SIGNING_PASSWORD           gpg key id and passphrase
              JPM_SIGNING_KEY_FILE                            armored private key to import (CI)
            """;

    private Main() {}

    static void step(String verb, String msg) {
        System.err.println(String.format("%12s", verb) + " " + msg);
    }

    static void info(String msg) {
        System.err.println(msg);
    }

    public static void main(String[] argv) {
        int code;
        try {
            code = run(new ArrayList<>(Arrays.asList(argv)));
        } catch (PublishException e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        } catch (RuntimeException e) {
            System.err.println("error: unexpected failure: " + e);
            if (System.getenv("JPM_VERBOSE") != null) e.printStackTrace();
            code = 70;
        }
        System.exit(code);
    }

    /** Options of one run. */
    record Options(String repository, boolean local, List<String> modules, boolean dryRun, boolean build,
                   boolean sources, Boolean javadoc, Boolean sign, boolean waitForCentral) {}

    /** The project, from the jpm that runs this extension, over jpm's protocol ({@code jpm api}). */
    static Project load(String jpm, Path dir, boolean withClasspaths) {
        try (JpmClient client = JpmClient.start(List.of(jpm, "api"), dir, Map.of(), "jpm-publish", VERSION)) {
            dev.jpm.api.model.Project p = client.load(dir, LoadOptions.defaults());
            Map<String, Classpaths> cps = withClasspaths ? client.classpaths(dir, List.of()) : Map.of();
            return Project.from(p, cps);
        } catch (ProtocolMismatch e) {
            throw new PublishException(e.getMessage());
        } catch (JpmError e) {
            throw new PublishException(e.getMessage().contains("stopped") || e.getMessage().contains("could not be reached")
                    ? "jpm-publish needs jpm 0.12.0 or newer (`jpm api`); run `jpm upgrade`" : e.getMessage());
        }
    }

    static Options parse(List<String> args) {
        String repo = null;
        boolean local = false, dry = false, build = true, sources = true, wait = true;
        Boolean javadoc = null, sign = null;
        List<String> modules = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--local" -> local = true;
                case "--dry-run", "-n" -> dry = true;
                case "--no-build" -> build = false;
                case "--no-sources" -> sources = false;
                case "--javadoc" -> javadoc = true;
                case "--no-javadoc" -> javadoc = false;
                case "--sign" -> sign = true;
                case "--no-sign" -> sign = false;
                case "--no-wait" -> wait = false;
                case "--module", "-m" -> {
                    if (i + 1 >= args.size()) throw new PublishException("--module needs a module name");
                    modules.add(args.get(++i));
                }
                default -> {
                    if (a.startsWith("--module=")) modules.add(a.substring(9));
                    else if (a.startsWith("-")) throw new PublishException("Unknown option " + a + ". Run `jpm publish --help`.");
                    else if (repo != null) throw new PublishException("Give one repository, not " + repo + " and " + a);
                    else repo = a;
                }
            }
        }
        if (local && repo != null) throw new PublishException("Choose a repository or --local, not both");
        return new Options(repo, local, modules, dry, build, sources, javadoc, sign, wait);
    }

    static int run(List<String> args) {
        if (args.contains("--help") || args.contains("-h") || args.contains("help")) {
            System.out.print(HELP);
            return 0;
        }
        if (args.contains("--version")) {
            System.out.println("jpm-publish " + VERSION);
            return 0;
        }
        Options o = parse(args);
        Path dir = Path.of(env("JPM_PROJECT_DIR", Path.of("").toAbsolutePath().toString()));
        String jpm = env("JPM_EXE", "jpm");

        if (o.build()) {
            step("Building", "with jpm build");
            Artifacts.Proc.Result b = Artifacts.Proc.run(dir, List.of(jpm, "build"));
            if (!b.ok()) throw new PublishException("jpm build failed:\n" + b.output().strip());
        }
        boolean wantJavadocAnywhere = o.javadoc() == null || o.javadoc();
        Project project = load(jpm, dir, wantJavadocAnywhere);

        Repository repo = repository(project, o);
        boolean central = repo instanceof Repository.Central;
        List<Project.Module> modules = select(project, o);
        Path staging = project.dir().resolve(".cache").resolve("publish").resolve(repo.id);
        Artifacts.deleteTree(staging);

        List<Repository.Upload> uploads = new ArrayList<>();
        for (Project.Module m : modules) {
            if (central) {
                List<String> missing = Pom.centralProblems(m);
                if (!missing.isEmpty()) {
                    throw new PublishException(m.name() + " is missing what Maven Central requires:\n  - " + String.join("\n  - ", missing));
                }
            }
            uploads.add(stage(project, m, o, central, staging));
        }
        if (central) {
            Repository.Central c = (Repository.Central) repo;
            c.bundle = staging.resolve("central-bundle.zip");
            Repository.Central.zip(uploads, c.bundle);
        }

        if (o.dryRun()) {
            step("Dry run", "nothing uploaded; this would go to " + repo.describe() + ":");
            for (Repository.Upload u : uploads) {
                for (String p : u.files().keySet()) System.out.println("  " + p);
            }
            info("Files are in " + staging + (central ? " (bundle: central-bundle.zip)" : ""));
            return 0;
        }
        step("Publishing", modules.size() + " module" + (modules.size() == 1 ? "" : "s") + " to " + repo.describe());
        repo.publish(uploads);
        return 0;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    /** Builds a module's files in the staging folder, keyed by their path in the repository. */
    static Repository.Upload stage(Project project, Project.Module m, Options o, boolean central, Path staging) {
        Path work = staging.resolve(m.name());
        String base = Artifacts.base(m);
        String dirInRepo = Repository.groupPath(m) + "/" + m.version() + "/";
        Map<String, Path> files = new LinkedHashMap<>();

        if (m.jar() == null || !Files.isRegularFile(m.jar())) {
            throw new PublishException(m.name() + ": " + m.jar() + " does not exist; run `jpm build` (or drop --no-build)");
        }
        Path jar = work.resolve(base + ".jar");
        copy(m.jar(), jar);
        files.put(dirInRepo + base + ".jar", jar);

        Path pom = work.resolve(base + ".pom");
        Artifacts.write(pom, Pom.write(project, m).getBytes(StandardCharsets.UTF_8));
        files.put(dirInRepo + base + ".pom", pom);

        Path src = null;
        if (o.sources() && !Boolean.FALSE.equals(m.settings().get("sources"))) {
            src = Artifacts.sourcesJar(m, work.resolve(base + "-sources.jar"));
            files.put(dirInRepo + base + "-sources.jar", src);
        }
        boolean javadoc = o.javadoc() != null ? o.javadoc()
                : m.settings().get("javadoc") instanceof Boolean b ? b : central;
        Path doc = null;
        if (javadoc) {
            doc = Artifacts.javadocJar(m, work.resolve(base + "-javadoc.jar"), work);
            files.put(dirInRepo + base + "-javadoc.jar", doc);
        }
        Path module = work.resolve(base + ".module");
        Artifacts.write(module, GradleModule.write(m, Pom.dependencies(project, m), new GradleModule.Files(jar, src, doc))
                .getBytes(StandardCharsets.UTF_8));
        files.put(dirInRepo + base + ".module", module);
        boolean sign = o.sign() != null ? o.sign() : m.settings().get("sign") instanceof Boolean b ? b : central;
        if (central && !sign) throw new PublishException("Maven Central requires signatures; drop --no-sign");
        if (sign) {
            List<Path> signed = new ArrayList<>(files.values());
            List<Path> ascs = Artifacts.sign(signed, staging);
            List<String> keys = new ArrayList<>(files.keySet());
            for (int i = 0; i < ascs.size(); i++) files.put(keys.get(i) + ".asc", ascs.get(i));
        }
        step("Staged", m.coordinates() + " (" + files.size() + " files)");
        return new Repository.Upload(m, files);
    }

    private static void copy(Path from, Path to) {
        Artifacts.write(to, Artifacts.read(from));
    }

    /** The repository to publish to. */
    @SuppressWarnings("unchecked")
    static Repository repository(Project project, Options o) {
        if (o.local()) return Repository.local();
        Map<String, Object> repos = Project.map(project.settings().get("repositories"));
        String name = o.repository();
        if (name != null && name.matches("(?i)(https?|file)://.+")) return new Repository.Maven("custom", name);
        if (name == null) {
            if (repos.size() == 1) name = repos.keySet().iterator().next();
            else if (repos.isEmpty()) {
                throw new PublishException("No repository to publish to. Add one to project.jsonc:\n"
                        + "  \"extensions\": { \"publish\": { \"repositories\": { \"myrepo\": { \"url\": \"https://...\" } } } }\n"
                        + "or run `jpm publish --local` or `jpm publish <url>`.");
            } else {
                throw new PublishException("Several repositories are configured; choose one: jpm publish <"
                        + String.join("|", repos.keySet()) + ">");
            }
        }
        Object cfg0 = repos.get(name);
        if (cfg0 == null) {
            if (name.equals("central")) return new Repository.Central("central", null, null, o.waitForCentral());
            throw new PublishException("No repository named \"" + name + "\""
                    + (repos.isEmpty() ? "" : "; configured: " + String.join(", ", repos.keySet())));
        }
        Map<String, Object> cfg = (Map<String, Object>) cfg0;
        String type = String.valueOf(cfg.getOrDefault("type", "maven"));
        if (type.equals("central")) {
            return new Repository.Central(name, (String) cfg.get("url"), (String) cfg.get("publishingType"), o.waitForCentral());
        }
        String url = (String) cfg.get("url");
        if (url == null) throw new PublishException("Repository \"" + name + "\" has no \"url\"");
        boolean snapshots = select(project, o).stream().anyMatch(Project.Module::snapshot);
        if (snapshots && cfg.get("snapshots") instanceof String s) url = s;
        return new Repository.Maven(name, url);
    }

    /** The modules to publish: those named with --module, else every module with a group that is not skipped. */
    static List<Project.Module> select(Project project, Options o) {
        List<Project.Module> out = new ArrayList<>();
        if (!o.modules().isEmpty()) {
            for (String n : o.modules()) {
                Project.Module m = project.module(n);
                if (m == null) throw new PublishException("No module named \"" + n + "\"");
                if (m.group() == null) throw new PublishException(n + " has no \"group\" in its project.jsonc; Maven coordinates need one");
                out.add(m);
            }
            return out;
        }
        for (Project.Module m : project.modules()) {
            if (Boolean.TRUE.equals(m.settings().get("skip"))) continue;
            if (m.group() == null) {
                if (project.modules().size() == 1) {
                    throw new PublishException(m.name() + " has no \"group\" in project.jsonc; Maven coordinates need one, e.g. \"group\": \"com.example\"");
                }
                info("skipping " + m.name() + ": it has no \"group\"");
                continue;
            }
            out.add(m);
        }
        if (out.isEmpty()) throw new PublishException("Nothing to publish: no module has a \"group\"");
        return out;
    }
}
