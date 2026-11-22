package dev.jpm.publish;

import dev.jpm.api.client.Results;
import dev.jpm.api.json.Jsonc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class PublishTest {
    static final String INFO = """
            { "dir": "/p", "name": "ws", "workspace": true,
              "extensions": { "publish": { "developers": [ { "name": "Dev" } ], "repositories": { "r": { "url": "https://r.example/releases" } } } },
              "modules": [
                { "name": "api", "group": "com.acme", "version": "1.2.0", "dir": "/p/api", "url": "https://github.com/acme/ws",
                  "description": "API", "license": "MIT", "jar": "/p/api/dist/api-1.2.0.jar",
                  "dependencies": [
                    { "key": "org.slf4j:slf4j-api", "kind": "maven", "scope": "runtime", "spec": "[2.0,3.0)", "resolved": "2.0.16",
                      "group": "org.slf4j", "artifact": "slf4j-api" },
                    { "key": "org.slf4j:slf4j-simple", "kind": "maven", "scope": "runtime", "spec": "2.0.16", "resolved": "2.0.16",
                      "group": "org.slf4j", "artifact": "slf4j-simple", "runtimeOnly": true, "exclude": ["org.slf4j:slf4j-api"] },
                    { "key": "org.lwjgl:lwjgl:natives-linux", "kind": "maven", "scope": "runtime", "spec": "3.3.4", "resolved": "3.3.4",
                      "group": "org.lwjgl", "artifact": "lwjgl", "classifier": "natives-linux" },
                    { "key": "workspace:impl", "kind": "workspace", "scope": "runtime", "spec": "*", "module": "impl",
                      "group": "com.acme", "artifact": "impl", "version": "1.2.0" },
                    { "key": "org.jspecify:jspecify", "kind": "maven", "scope": "dev", "spec": "1.0.0", "resolved": "1.0.0",
                      "group": "org.jspecify", "artifact": "jspecify", "api": true },
                    { "key": "org.projectlombok:lombok", "kind": "maven", "scope": "dev", "spec": "1.18.34", "group": "org.projectlombok", "artifact": "lombok" },
                    { "key": "org.junit.jupiter:junit-jupiter", "kind": "maven", "scope": "test", "spec": "5.11.4", "group": "org.junit.jupiter", "artifact": "junit-jupiter" }
                  ] },
                { "name": "impl", "group": "com.acme", "version": "1.2.0", "dir": "/p/impl", "dependencies": [],
                  "extensions": { "publish": { "skip": true } } },
                { "name": "app", "version": "1.2.0", "dir": "/p/app", "dependencies": [
                    { "key": "github:acme/tool", "kind": "git", "scope": "runtime", "spec": "^1.0", "repository": "github:acme/tool" } ] }
              ] }
            """;

    static Project project() {
        return Project.from(Results.project((Map<String, Object>) Jsonc.parse(INFO)), Map.of());
    }

    @Test
    void writesPomsLikeAJavaLibrary() {
        Project p = project();
        String pom = Pom.write(p, p.module("api"));
        assertTrue(pom.contains("<groupId>com.acme</groupId>\n  <artifactId>api</artifactId>\n  <version>1.2.0</version>"), pom);
        assertTrue(pom.contains("<artifactId>slf4j-api</artifactId>\n      <version>2.0.16</version>"), "resolved, not the range");
        assertTrue(pom.contains("<artifactId>slf4j-simple</artifactId>\n      <version>2.0.16</version>\n      <scope>runtime</scope>"), pom);
        assertTrue(pom.contains("<exclusion>\n          <groupId>org.slf4j</groupId>\n          <artifactId>slf4j-api</artifactId>"), pom);
        assertTrue(pom.contains("<classifier>natives-linux</classifier>"), pom);
        assertTrue(pom.contains("<artifactId>impl</artifactId>\n      <version>1.2.0</version>"), "workspace module by coordinates");
        assertFalse(pom.contains("lombok"), "dev dependencies are not published");
        assertTrue(pom.contains("<artifactId>jspecify</artifactId>\n      <version>1.0.0</version>\n      <scope>provided</scope>"),
                "\"api\": true dev dependencies are provided");
        assertTrue(pom.contains("<!-- do_not_remove: published-with-gradle-metadata -->"), "points Gradle at the .module");
        assertFalse(pom.contains("junit"), "test dependencies are not published");
        assertTrue(pom.contains("<name>MIT License</name>"));
        assertTrue(pom.contains("<connection>scm:git:https://github.com/acme/ws.git</connection>"), "scm from the GitHub url");
        assertTrue(pom.contains("<developer>\n      <name>Dev</name>"), "developers from the root settings");
        assertTrue(Pom.centralProblems(p.module("api")).isEmpty(), Pom.centralProblems(p.module("api")).toString());
    }

    @Test
    void refusesWhatMavenUsersCannotResolve() {
        Project p = project();
        PublishException e = assertThrows(PublishException.class, () -> Pom.write(p, p.module("app")));
        assertTrue(e.getMessage().contains("github:acme/tool is a Git dependency"), e.getMessage());
        assertTrue(Pom.centralProblems(p.module("app")).stream().anyMatch(x -> x.contains("\"group\"")));
    }

    @Test
    void selectsModulesWithAGroup() {
        Project p = project();
        Main.Options all = Main.parse(new ArrayList<>());
        assertEquals(List.of("api"), Main.select(p, all).stream().map(Project.Module::name).toList(), "impl is skipped, app has no group");
        Main.Options one = Main.parse(new ArrayList<>(List.of("--module", "impl")));
        assertEquals(List.of("impl"), Main.select(p, one).stream().map(Project.Module::name).toList());
        Repository r = Main.repository(p, all);
        assertEquals("https://r.example/releases", ((Repository.Maven) r).url);
        assertTrue(Main.repository(p, Main.parse(new ArrayList<>(List.of("central")))) instanceof Repository.Central);
    }

    @Test
    void mergesMetadata() {
        ZonedDateTime t = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        String first = Repository.Metadata.merge(null, "g", "a", "1.0.0", t);
        String second = Repository.Metadata.merge(first, "g", "a", "1.1.0-SNAPSHOT", t);
        String third = Repository.Metadata.merge(second, "g", "a", "1.0.0", t);
        assertTrue(third.contains("<latest>1.1.0-SNAPSHOT</latest>"));
        assertTrue(third.contains("<release>1.0.0</release>"), "snapshots are never the release");
        assertEquals(2, third.split("<version>", -1).length - 1, "no duplicates");
        assertTrue(third.contains("<lastUpdated>20260102030405</lastUpdated>"));
    }

    @Test
    void writesGradleModuleMetadata(@TempDir Path dir) {
        Project p = project();
        Project.Module m = p.module("api");
        Path jar = dir.resolve("api-1.2.0.jar");
        Artifacts.write(jar, "jar".getBytes(StandardCharsets.UTF_8));
        Path src = dir.resolve("api-1.2.0-sources.jar");
        Artifacts.write(src, "src".getBytes(StandardCharsets.UTF_8));
        String text = GradleModule.write(m, Pom.dependencies(p, m), new GradleModule.Files(jar, src, null));
        Map<String, Object> json = (Map<String, Object>) Jsonc.parse(text);
        assertEquals("1.1", json.get("formatVersion"));
        assertEquals(Map.of("group", "com.acme", "module", "api", "version", "1.2.0",
                "attributes", Map.of("org.gradle.status", "release")), json.get("component"));
        Map<String, List<String>> deps = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Object o : (List<Object>) json.get("variants")) {
            Map<String, Object> v = (Map<String, Object>) o;
            byName.put((String) v.get("name"), v);
            List<String> names = new ArrayList<>();
            for (Object d : (List<Object>) v.getOrDefault("dependencies", List.of())) names.add((String) ((Map<String, Object>) d).get("module"));
            deps.put((String) v.get("name"), names);
        }
        assertEquals(List.of("apiElements", "runtimeElements", "sourcesElements"), List.copyOf(byName.keySet()));
        assertEquals(List.of("slf4j-api", "lwjgl", "impl", "jspecify"), deps.get("apiElements"), "compiled against, not run with");
        assertEquals(List.of("slf4j-api", "slf4j-simple", "lwjgl", "impl"), deps.get("runtimeElements"), "run with");
        Map<String, Object> attrs = (Map<String, Object>) byName.get("apiElements").get("attributes");
        assertEquals("java-api", attrs.get("org.gradle.usage"));
        Map<String, Object> file = (Map<String, Object>) ((List<Object>) byName.get("runtimeElements").get("files")).get(0);
        assertEquals("api-1.2.0.jar", file.get("url"));
        assertEquals(new java.math.BigDecimal(3), file.get("size"));
        assertEquals(Artifacts.checksums("jar".getBytes(StandardCharsets.UTF_8)).get("sha256"), file.get("sha256"));
        Map<String, Object> simple = (Map<String, Object>) ((List<Object>) byName.get("runtimeElements").get("dependencies")).get(1);
        assertEquals(Map.of("requires", "2.0.16"), simple.get("version"));
        assertEquals(List.of(Map.of("group", "org.slf4j", "module", "slf4j-api")), simple.get("excludes"));
        Map<String, Object> lwjgl = (Map<String, Object>) ((List<Object>) byName.get("apiElements").get("dependencies")).get(1);
        assertEquals("natives-linux", ((Map<String, Object>) ((Map<String, Object>) lwjgl.get("thirdPartyCompatibility")).get("artifactSelector")).get("classifier"));
    }

    private static Repository.Upload upload(Path dir, Project.Module m) {
        Map<String, Path> files = new LinkedHashMap<>();
        String base = Repository.groupPath(m) + "/" + m.version() + "/" + m.name() + "-" + m.version();
        for (String ext : List.of(".jar", ".pom")) {
            Path f = dir.resolve(m.name() + ext);
            Artifacts.write(f, ("content of " + ext).getBytes(StandardCharsets.UTF_8));
            files.put(base + ext, f);
        }
        return new Repository.Upload(m, files);
    }

    @Test
    void uploadsToAMavenRepositoryOverHttp(@TempDir Path dir) throws IOException {
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        List<String> auth = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repo/", ex -> {
            String path = ex.getRequestURI().getPath().substring("/repo/".length());
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (ex.getRequestMethod().equals("PUT")) {
                stored.put(path, ex.getRequestBody().readAllBytes());
                ex.sendResponseHeaders(201, -1);
            } else {
                byte[] b = stored.get(path);
                if (b == null) ex.sendResponseHeaders(404, -1);
                else {
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                }
            }
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo";
            Project.Module api = project().module("api");
            new Repository.Maven("test", url).publish(List.of(upload(dir, api)));
            assertTrue(stored.containsKey("com/acme/api/1.2.0/api-1.2.0.jar"));
            assertTrue(stored.containsKey("com/acme/api/1.2.0/api-1.2.0.jar.sha512"));
            assertTrue(new String(stored.get("com/acme/api/maven-metadata.xml"), StandardCharsets.UTF_8).contains("<release>1.2.0</release>"));
            assertEquals(Artifacts.checksums("content of .jar".getBytes(StandardCharsets.UTF_8)).get("sha1"),
                    new String(stored.get("com/acme/api/1.2.0/api-1.2.0.jar.sha1"), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsEachSnapshotUnderItsOwnName(@TempDir Path dir) {
        Project.Module a = project().module("api");
        Project.Module m = new Project.Module(a.name(), a.group(), "1.3.0-SNAPSHOT", a.dir(), a.visibility(), a.description(), a.license(), a.url(),
                a.sourceDir(), a.resourceDir(), a.jar(), a.javaHome(), a.release(), a.dependencies(), a.settings(), a.compileClasspath(),
                a.compileModuleOptions());
        Path repo = dir.resolve("repo");
        String base = Repository.groupPath(m) + "/1.3.0-SNAPSHOT/api-1.3.0-SNAPSHOT";
        Map<String, Path> files = new LinkedHashMap<>();
        for (String ext : List.of(".jar", "-sources.jar", ".pom", ".module")) {
            Path f = dir.resolve("f" + ext);
            Artifacts.write(f, (ext.equals(".module") ? "{ \"url\": \"api-1.3.0-SNAPSHOT.jar\", \"version\": \"1.3.0-SNAPSHOT\" }" : "x")
                    .getBytes(StandardCharsets.UTF_8));
            files.put(base + ext, f);
        }
        Repository.Maven maven = new Repository.Maven("test", repo.toUri().toString());
        maven.publish(List.of(new Repository.Upload(m, files)));
        maven.publish(List.of(new Repository.Upload(m, files)));
        Path version = repo.resolve("com/acme/api/1.3.0-SNAPSHOT");
        String meta = new String(Artifacts.read(version.resolve("maven-metadata.xml")), StandardCharsets.UTF_8);
        assertTrue(meta.contains("<buildNumber>2</buildNumber>"), meta);
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("<value>(1\\.3\\.0-\\d{8}\\.\\d{6}-2)</value>").matcher(meta);
        assertTrue(v.find(), meta);
        String unique = v.group(1);
        assertTrue(meta.contains("<classifier>sources</classifier>"), meta);
        assertTrue(meta.contains("<extension>module</extension>"), meta);
        assertTrue(java.nio.file.Files.exists(version.resolve("api-" + unique + ".jar")));
        assertTrue(java.nio.file.Files.exists(version.resolve("api-" + unique + "-sources.jar.sha1")));
        assertTrue(java.nio.file.Files.notExists(version.resolve("api-1.3.0-SNAPSHOT.jar")), "no file under the plain snapshot name");
        String module = new String(Artifacts.read(version.resolve("api-" + unique + ".module")), StandardCharsets.UTF_8);
        assertTrue(module.contains("\"api-" + unique + ".jar\"") && module.contains("\"version\": \"1.3.0-SNAPSHOT\""), module);
        assertTrue(new String(Artifacts.read(repo.resolve("com/acme/api/maven-metadata.xml")), StandardCharsets.UTF_8)
                .contains("<version>1.3.0-SNAPSHOT</version>"));
    }

    @Test
    void explainsRefusedCredentials(@TempDir Path dir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo";
            PublishException e = assertThrows(PublishException.class,
                    () -> new Repository.Maven("myrepo", url).publish(List.of(upload(dir, project().module("api")))));
            assertTrue(e.getMessage().contains("JPM_PUBLISH_MYREPO_USERNAME"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bundlesForMavenCentral(@TempDir Path dir) throws IOException {
        Project.Module api = project().module("api");
        Path zip = dir.resolve("bundle.zip");
        Repository.Central.zip(List.of(upload(dir, api)), zip);
        List<String> names = new ArrayList<>();
        try (var z = new java.util.zip.ZipFile(zip.toFile())) {
            z.stream().forEach(e -> names.add(e.getName()));
        }
        assertTrue(names.contains("com/acme/api/1.2.0/api-1.2.0.jar"));
        assertTrue(names.contains("com/acme/api/1.2.0/api-1.2.0.jar.md5"));
        assertTrue(names.contains("com/acme/api/1.2.0/api-1.2.0.pom.sha1"));
        byte[] body = Repository.Central.multipart("B", "bundle", "b.zip", new byte[]{1, 2});
        String head = new String(body, 0, 120, StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("--B\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"b.zip\""), head);
    }

    @Test
    void uploadsToTheCentralPortal(@TempDir Path dir) throws IOException {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/publisher/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.add(ex.getRequestURI().getPath() + " " + auth + " " + new String(body, StandardCharsets.ISO_8859_1).contains("name=\"bundle\""));
            byte[] out = (ex.getRequestURI().getPath().endsWith("/upload") ? "dep-123"
                    : "{\"deploymentId\": \"dep-123\", \"deploymentState\": \"PUBLISHED\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().endsWith("/upload") ? 201 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        System.setProperty("jpm.publish.c.username", "user");
        System.setProperty("jpm.publish.c.password", "token");
        try {
            Repository.Central c = new Repository.Central("c", "http://127.0.0.1:" + server.getAddress().getPort(), "automatic", true);
            List<Repository.Upload> uploads = List.of(upload(dir, project().module("api")));
            c.bundle = dir.resolve("bundle.zip");
            Repository.Central.zip(uploads, c.bundle);
            c.publish(uploads);
            String bearer = "Bearer " + Base64.getEncoder().encodeToString("user:token".getBytes(StandardCharsets.UTF_8));
            assertEquals("/api/v1/publisher/upload " + bearer + " true", seen.get(0));
            assertTrue(seen.get(1).startsWith("/api/v1/publisher/status " + bearer), seen.toString());
        } finally {
            System.clearProperty("jpm.publish.c.username");
            System.clearProperty("jpm.publish.c.password");
            server.stop(0);
        }
    }

    @Test
    void writesToFolderRepositoriesAndRefusesReplacingReleases(@TempDir Path dir) {
        Path repo = dir.resolve("repo");
        Project.Module api = project().module("api");
        Repository.Maven folder = new Repository.Maven("folder", repo.toUri().toString());
        folder.publish(List.of(upload(dir, api)));
        assertTrue(Files.exists(repo.resolve("com/acme/api/1.2.0/api-1.2.0.pom.sha256")));
        assertThrows(PublishException.class, () -> folder.publish(List.of(upload(dir, api))));
        Repository.Maven local = new Repository.Maven("local", repo.toUri().toString(), true);
        local.publish(List.of(upload(dir, api)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDescriptorListsEveryOptionOfTheHelp() throws IOException {
        Map<String, Object> manifest = (Map<String, Object>) Jsonc.parse(java.nio.file.Files.readString(Path.of("project.jsonc")));
        Map<String, Object> commands = (Map<String, Object>) ((Map<String, Object>) manifest.get("extension")).get("commands");
        Map<String, Object> options = (Map<String, Object>) ((Map<String, Object>) commands.get("publish")).get("options");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s+(--[a-z-]+)").matcher(Main.HELP);
        List<String> help = new ArrayList<>();
        while (m.find()) help.add(m.group(1));
        java.util.regex.Matcher pair = java.util.regex.Pattern.compile("/ (--[a-z-]+)").matcher(Main.HELP);
        while (pair.find()) help.add(pair.group(1));
        assertFalse(help.isEmpty());
        assertEquals(new java.util.TreeSet<>(help), new java.util.TreeSet<>(options.keySet()));
    }
}
