package dev.jpm.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Builds the files that go next to the jar: POM, sources and javadoc jars, checksums and signatures. */
final class Artifacts {
    private Artifacts() {}

    /** One file to publish, named the Maven way: artifact-version[-classifier].extension. */
    record File(String name, Path path) {}

    /** The 1980 timestamp jars use for reproducible builds. */
    private static final long EPOCH = 315532800000L + 31L * 24 * 3600 * 1000;

    static String base(Project.Module m) {
        return m.name() + "-" + m.version();
    }

    /** A jar of a module's sources and resources. */
    static Path sourcesJar(Project.Module m, Path out) {
        Map<String, Path> entries = new TreeMap<>();
        for (Path root : new Path[]{m.sourceDir(), m.resourceDir()}) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p)) entries.putIfAbsent(root.relativize(p).toString().replace('\\', '/'), p);
                }
            } catch (IOException e) {
                throw new PublishException("Could not read " + root + ": " + e.getMessage());
            }
        }
        writeJar(out, entries);
        return out;
    }

    /** Runs the module JDK's javadoc and jars the result. */
    static Path javadocJar(Project.Module m, Path out, Path work) {
        if (m.javaHome() == null) throw new PublishException(m.name() + ": no JDK for javadoc (jpm info gave no javaHome)");
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path javadoc = m.javaHome().resolve("bin").resolve(win ? "javadoc.exe" : "javadoc");
        List<Path> sources = new ArrayList<>();
        if (m.sourceDir() != null && Files.isDirectory(m.sourceDir())) {
            try (Stream<Path> s = Files.walk(m.sourceDir())) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            } catch (IOException e) {
                throw new PublishException("Could not read " + m.sourceDir() + ": " + e.getMessage());
            }
        }
        Path docs = work.resolve("javadoc");
        deleteTree(docs);
        Map<String, Path> entries = new TreeMap<>();
        if (!sources.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("-d");
            args.add(docs.toString());
            args.add("-encoding");
            args.add("UTF-8");
            args.add("-quiet");
            args.add("-Xdoclint:none");
            args.add("-notimestamp");
            if (m.release() != null) {
                args.add("--release");
                args.add(m.release().toString());
            }
            if (!m.compileClasspath().isEmpty()) {
                String cp = String.join(java.io.File.pathSeparator, m.compileClasspath().stream().map(Path::toString).toList());
                // named modules take their dependencies from the module path
                boolean modular = Files.exists(m.sourceDir().resolve("module-info.java"));
                args.add(modular ? "--module-path" : "-classpath");
                args.add(cp);
            }
            for (Path s : sources) args.add(s.toString());
            Path argFile = work.resolve("javadoc.args");
            write(argFile, String.join("\n", args.stream().map(Artifacts::quote).toList()) + "\n");
            Proc.Result r = Proc.run(m.dir(), List.of(javadoc.toString(), "@" + argFile));
            if (!r.ok()) throw new PublishException(m.name() + ": javadoc failed:\n" + r.output().strip()
                    + "\nFix the comments, or publish without javadoc: --no-javadoc");
            try (Stream<Path> s = Files.walk(docs)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p)) entries.put(docs.relativize(p).toString().replace('\\', '/'), p);
                }
            } catch (IOException e) {
                throw new PublishException("Could not read " + docs + ": " + e.getMessage());
            }
        }
        writeJar(out, entries);
        return out;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void writeJar(Path out, Map<String, Path> entries) {
        try {
            Files.createDirectories(out.getParent());
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Created-By", "jpm-publish");
            try (OutputStream os = Files.newOutputStream(out); JarOutputStream jar = new JarOutputStream(os)) {
                JarEntry me = new JarEntry("META-INF/MANIFEST.MF");
                me.setTime(EPOCH);
                jar.putNextEntry(me);
                mf.write(jar);
                jar.closeEntry();
                for (Map.Entry<String, Path> e : entries.entrySet()) {
                    if (e.getKey().equals("META-INF/MANIFEST.MF")) continue;
                    JarEntry je = new JarEntry(e.getKey());
                    je.setTime(EPOCH);
                    jar.putNextEntry(je);
                    Files.copy(e.getValue(), jar);
                    jar.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new PublishException("Could not write " + out + ": " + e.getMessage());
        }
    }

    /** Checksums Maven repositories expect next to each file. */
    static final List<String[]> CHECKSUMS = List.of(
            new String[]{"md5", "MD5"}, new String[]{"sha1", "SHA-1"}, new String[]{"sha256", "SHA-256"}, new String[]{"sha512", "SHA-512"});

    static Map<String, String> checksums(byte[] data) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] c : CHECKSUMS) {
            try {
                out.put(c[0], HexFormat.of().formatHex(MessageDigest.getInstance(c[1]).digest(data)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new PublishException("Could not read " + p + ": " + e.getMessage());
        }
    }

    static void write(Path p, String text) {
        write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    static void write(Path p, byte[] data) {
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new PublishException("Could not write " + p + ": " + e.getMessage());
        }
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new PublishException("Could not delete " + root + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- signing

    /**
     * Signs files with gpg, writing an ASCII-armored .asc next to each. JPM_SIGNING_KEY picks the key (id or
     * fingerprint) and JPM_SIGNING_PASSWORD unlocks it; JPM_SIGNING_KEY_FILE imports an armored private key
     * into a throwaway keyring first, which is how CI usually signs.
     */
    static List<Path> sign(List<Path> files, Path work) {
        String keyFile = System.getenv("JPM_SIGNING_KEY_FILE");
        String key = System.getenv("JPM_SIGNING_KEY");
        String pass = System.getenv("JPM_SIGNING_PASSWORD");
        Map<String, String> env = new LinkedHashMap<>();
        Path home = null;
        try {
            if (keyFile != null && !keyFile.isBlank()) {
                // A throwaway keyring. It lives in a short temp path: gpg-agent's socket must fit in ~100 bytes.
                try {
                    home = Files.createTempDirectory("jpmgpg");
                    try {
                        Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                    } catch (UnsupportedOperationException ignored) {
                        // Windows: the temp folder is private to the user already
                    }
                } catch (IOException e) {
                    throw new PublishException("Could not create a keyring folder: " + e.getMessage());
                }
                env.put("GNUPGHOME", home.toString());
                List<String> imp = new ArrayList<>(List.of("gpg", "--batch", "--yes"));
                if (pass != null) imp.addAll(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0"));
                imp.addAll(List.of("--import", keyFile));
                Proc.Result r = Proc.run(work, imp, env, pass);
                if (!r.ok()) throw new PublishException("Could not import the signing key from " + keyFile + ":\n" + r.output().strip());
            }
            List<Path> out = new ArrayList<>();
            for (Path f : files) {
                List<String> cmd = new ArrayList<>(List.of("gpg", "--batch", "--yes", "--armor", "--detach-sign"));
                if (key != null && !key.isBlank()) cmd.addAll(List.of("--local-user", key));
                if (pass != null) cmd.addAll(List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0"));
                Path asc = f.resolveSibling(f.getFileName() + ".asc");
                cmd.addAll(List.of("--output", asc.toString(), f.toString()));
                Proc.Result r = Proc.run(work, cmd, env, pass);
                if (!r.ok()) {
                    throw new PublishException("gpg could not sign " + f.getFileName() + ":\n" + r.output().strip()
                            + "\nSet JPM_SIGNING_KEY (and JPM_SIGNING_PASSWORD), or JPM_SIGNING_KEY_FILE, or publish with --no-sign.");
                }
                out.add(asc);
            }
            return out;
        } finally {
            if (home != null) {
                Proc.run(work, List.of("gpgconf", "--kill", "gpg-agent"), env, null);
                try {
                    deleteTree(home);
                } catch (PublishException ignored) {
                    // the agent may still hold a socket for a moment; it is a temp folder either way
                }
            }
        }
    }

    /** Small process helper. */
    static final class Proc {
        record Result(int exit, String output) {
            boolean ok() { return exit == 0; }
        }

        static Result run(Path dir, List<String> cmd) {
            return run(dir, cmd, Map.of(), null);
        }

        static Result run(Path dir, List<String> cmd, Map<String, String> env, String stdin) {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null) pb.directory(dir.toFile());
            pb.environment().putAll(env);
            try {
                Process p = pb.start();
                try (OutputStream in = p.getOutputStream()) {
                    if (stdin != null) in.write((stdin + "\n").getBytes(StandardCharsets.UTF_8));
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream is = p.getInputStream()) {
                    is.transferTo(buf);
                }
                return new Result(p.waitFor(), buf.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new PublishException("Could not run " + cmd.get(0) + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("Interrupted");
            }
        }
    }
}
