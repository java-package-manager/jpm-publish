package dev.jpm.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Where artifacts go. */
abstract class Repository {
    /** One module's files: path inside the repository (group/artifact/version/file) to local file. */
    record Upload(Project.Module module, Map<String, Path> files) {}

    final String id;

    Repository(String id) {
        this.id = id;
    }

    abstract void publish(List<Upload> uploads);

    abstract String describe();

    static String groupPath(Project.Module m) {
        return m.group().replace('.', '/') + "/" + m.name();
    }

    /** Credentials from JPM_PUBLISH_<ID>_USERNAME/PASSWORD, falling back to JPM_PUBLISH_USERNAME/PASSWORD. */
    static String[] credentials(String id) {
        String key = id.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        String user = env("JPM_PUBLISH_" + key + "_USERNAME");
        String pass = env("JPM_PUBLISH_" + key + "_PASSWORD");
        if (user == null) user = System.getProperty("jpm.publish." + id + ".username");
        if (pass == null) pass = System.getProperty("jpm.publish." + id + ".password");
        if (user == null && pass == null) {
            user = env("JPM_PUBLISH_USERNAME");
            pass = env("JPM_PUBLISH_PASSWORD");
        }
        if (user == null && pass == null) return null;
        return new String[]{user == null ? "" : user, pass == null ? "" : pass};
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? null : v;
    }

    static String credentialHint(String id) {
        String key = id.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return "JPM_PUBLISH_" + key + "_USERNAME and JPM_PUBLISH_" + key + "_PASSWORD";
    }

    static HttpClient http() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** A Maven 2 layout repository: Reposilite, Nexus, Artifactory, GitHub Packages, or a file:// folder. */
    static final class Maven extends Repository {
        final String url;
        private final HttpClient http;
        private final String auth;
        /** Whether a published version may be overwritten (only the local repository). */
        private final boolean replaceable;

        Maven(String id, String url) {
            this(id, url, false);
        }

        Maven(String id, String url, boolean replaceable) {
            super(id);
            this.replaceable = replaceable;
            this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            this.http = this.url.startsWith("file:") ? null : http();
            String[] c = credentials(id);
            this.auth = c == null ? null : "Basic " + Base64.getEncoder().encodeToString((c[0] + ":" + c[1]).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        String describe() {
            return url;
        }

        @Override
        void publish(List<Upload> uploads) {
            for (Upload u : uploads) {
                Project.Module m = u.module();
                Snapshot snap = m.snapshot() && !replaceable ? Snapshot.next(m, get(versionPath(m) + "/maven-metadata.xml"), ZonedDateTime.now(ZoneOffset.UTC)) : null;
                for (Map.Entry<String, Path> f : u.files().entrySet()) {
                    byte[] data = Artifacts.read(f.getValue());
                    String path = f.getKey();
                    if (snap != null) {
                        path = snap.rename(path);
                        if (path.endsWith(".module")) data = snap.rewriteModule(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                    }
                    put(path, data, true);
                    if (!f.getKey().endsWith(".asc")) {
                        for (Map.Entry<String, String> c : Artifacts.checksums(data).entrySet()) {
                            put(path + "." + c.getKey(), c.getValue().getBytes(StandardCharsets.UTF_8), false);
                        }
                    }
                }
                if (snap != null) {
                    byte[] xml = snap.metadata(u.files().keySet()).getBytes(StandardCharsets.UTF_8);
                    String mp = versionPath(m) + "/maven-metadata.xml";
                    put(mp, xml, false);
                    for (Map.Entry<String, String> c : Artifacts.checksums(xml).entrySet()) put(mp + "." + c.getKey(), c.getValue().getBytes(StandardCharsets.UTF_8), false);
                }
                updateMetadata(m);
                Main.step("Published", m.coordinates() + " to " + id);
            }
        }

        /** Adds the version to the artifact's maven-metadata.xml. */
        void updateMetadata(Project.Module m) {
            String path = groupPath(m) + "/maven-metadata.xml";
            byte[] existing = get(path);
            String xml = Metadata.merge(existing == null ? null : new String(existing, StandardCharsets.UTF_8), m.group(), m.name(),
                    m.version(), ZonedDateTime.now(ZoneOffset.UTC));
            byte[] data = xml.getBytes(StandardCharsets.UTF_8);
            put(path, data, false);
            for (Map.Entry<String, String> c : Artifacts.checksums(data).entrySet()) {
                put(path + "." + c.getKey(), c.getValue().getBytes(StandardCharsets.UTF_8), false);
            }
        }

        byte[] get(String path) {
            if (http == null) {
                Path p = Path.of(URI.create(url + "/" + path));
                return Files.exists(p) ? Artifacts.read(p) : null;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/" + path)).timeout(Duration.ofMinutes(2)).GET();
            if (auth != null) b.header("Authorization", auth);
            try {
                HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 404) return null;
                if (r.statusCode() / 100 != 2) throw new PublishException("GET " + url + "/" + path + " failed: HTTP " + r.statusCode() + hint(r.statusCode()));
                return r.body();
            } catch (IOException e) {
                throw new PublishException("GET " + url + "/" + path + " failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("Interrupted");
            }
        }

        void put(String path, byte[] data, boolean immutable) {
            if (http == null) {
                Path p = Path.of(URI.create(url + "/" + path));
                if (immutable && !replaceable && Files.exists(p) && !path.contains("-SNAPSHOT")) {
                    throw new PublishException(url + "/" + path + " already exists; published versions cannot be replaced");
                }
                Artifacts.write(p, data);
                return;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/" + path)).timeout(Duration.ofMinutes(10))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(data));
            if (auth != null) b.header("Authorization", auth);
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    throw new PublishException("Upload of " + path + " to " + url + " failed: HTTP " + r.statusCode() + hint(r.statusCode())
                            + (r.body().isBlank() ? "" : "\n" + r.body().strip()));
                }
            } catch (IOException e) {
                throw new PublishException("Upload of " + path + " to " + url + " failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("Interrupted");
            }
        }

        private String hint(int code) {
            return switch (code) {
                case 401 -> auth == null ? " (no credentials: set " + credentialHint(id) + ")" : " (the credentials were refused)";
                case 403 -> " (these credentials may not publish here)";
                case 409 -> " (the version may already be published; release versions cannot be replaced)";
                default -> "";
            };
        }
    }

    /** The local Maven repository (~/.m2/repository), so other builds on this machine can use the artifacts. */
    static Maven local() {
        String custom = System.getenv("JPM_PUBLISH_M2");
        Path repo = custom != null && !custom.isBlank() ? Path.of(custom)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
        return new Maven("local", repo.toUri().toString(), true);
    }

    /**
     * Maven Central through the Sonatype Central Portal publisher API: every module goes into one bundle
     * (files, checksums and signatures in the Maven layout), uploaded as one deployment.
     */
    static final class Central extends Repository {
        static final String DEFAULT_URL = "https://central.sonatype.com";
        final String url;
        final String publishingType;
        final boolean waitForIt;
        Path bundle;

        Central(String id, String url, String publishingType, boolean waitForIt) {
            super(id);
            this.url = url == null ? DEFAULT_URL : url.replaceAll("/+$", "");
            this.publishingType = publishingType == null ? "AUTOMATIC" : publishingType.toUpperCase();
            this.waitForIt = waitForIt;
        }

        @Override
        String describe() {
            return "Maven Central (" + url + ", " + publishingType.toLowerCase() + ")";
        }

        static void zip(List<Upload> uploads, Path out) {
            try {
                Files.createDirectories(out.getParent());
                try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
                    Set<String> written = new LinkedHashSet<>();
                    for (Upload u : uploads) {
                        for (Map.Entry<String, Path> f : u.files().entrySet()) {
                            byte[] data = Artifacts.read(f.getValue());
                            entry(z, written, f.getKey(), data);
                            if (!f.getKey().endsWith(".asc")) {
                                Map<String, String> sums = Artifacts.checksums(data);
                                entry(z, written, f.getKey() + ".md5", sums.get("md5").getBytes(StandardCharsets.UTF_8));
                                entry(z, written, f.getKey() + ".sha1", sums.get("sha1").getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new PublishException("Could not write the bundle " + out + ": " + e.getMessage());
            }
        }

        private static void entry(ZipOutputStream z, Set<String> written, String name, byte[] data) throws IOException {
            if (!written.add(name)) return;
            z.putNextEntry(new ZipEntry(name));
            z.write(data);
            z.closeEntry();
        }

        @Override
        void publish(List<Upload> uploads) {
            String[] c = credentials(id);
            if (c == null) {
                throw new PublishException("Publishing to Maven Central needs a Central Portal user token: set " + credentialHint(id)
                        + " (generate one at " + url + "/account)");
            }
            String token = Base64.getEncoder().encodeToString((c[0] + ":" + c[1]).getBytes(StandardCharsets.UTF_8));
            if (bundle == null) throw new IllegalStateException("bundle not built");
            String name = uploads.get(0).module().coordinates() + (uploads.size() > 1 ? " (+" + (uploads.size() - 1) + ")" : "");
            String boundary = "jpm-" + UUID.randomUUID();
            byte[] body = multipart(boundary, "bundle", bundle.getFileName().toString(), Artifacts.read(bundle));
            String uploadUrl = url + "/api/v1/publisher/upload?publishingType=" + publishingType
                    + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpClient http = http();
            String deployment;
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofMinutes(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    throw new PublishException("Maven Central refused the upload: HTTP " + r.statusCode()
                            + (r.statusCode() == 401 ? " (check the Central Portal token in " + credentialHint(id) + ")" : "")
                            + (r.body().isBlank() ? "" : "\n" + r.body().strip()));
                }
                deployment = r.body().strip();
            } catch (IOException e) {
                throw new PublishException("Upload to Maven Central failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("Interrupted");
            }
            Main.step("Uploaded", "deployment " + deployment + " (" + url + "/publishing/deployments)");
            if (!waitForIt) return;
            String state = "";
            for (int i = 0; i < 180; i++) {
                try {
                    Thread.sleep(i == 0 ? 3000 : 10000);
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/publisher/status?id="
                                    + URLEncoder.encode(deployment, StandardCharsets.UTF_8))).timeout(Duration.ofMinutes(1))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 != 2) continue;
                    Map<String, Object> s = Project.map(dev.jpm.api.json.Jsonc.parse(r.body()));
                    String now = String.valueOf(s.get("deploymentState"));
                    if (!now.equals(state)) Main.step("Central", now.toLowerCase());
                    state = now;
                    switch (now) {
                        case "FAILED" -> throw new PublishException("Maven Central rejected the deployment:\n" + errors(s.get("errors")));
                        case "PUBLISHED" -> {
                            return;
                        }
                        case "VALIDATED" -> {
                            if (publishingType.equals("USER_MANAGED")) {
                                Main.info("Validated. Publish it at " + url + "/publishing/deployments");
                                return;
                            }
                        }
                        default -> {
                        }
                    }
                } catch (IOException e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Main.info("Still " + state.toLowerCase() + "; see " + url + "/publishing/deployments");
        }

        private static String errors(Object e) {
            if (e == null) return "(no details)";
            return e.toString();
        }

        static byte[] multipart(String boundary, String field, String filename, byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
            out.writeBytes(data);
            out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }
    }

    static String versionPath(Project.Module m) {
        return groupPath(m) + "/" + m.version();
    }

    /**
     * A unique snapshot upload, as {@code mvn deploy} makes: files are named {@code <artifact>-<base>-<timestamp>-<build>}
     * and the version's maven-metadata.xml lists them, so repositories that refuse redeploys take every build.
     */
    record Snapshot(String artifact, String group, String version, String value, String timestamp, int build, String updated) {
        static Snapshot next(Project.Module m, byte[] existing, ZonedDateTime now) {
            int build = 0;
            if (existing != null) {
                Matcher b = Pattern.compile("<buildNumber>\\s*(\\d+)\\s*</buildNumber>").matcher(new String(existing, StandardCharsets.UTF_8));
                if (b.find()) build = Integer.parseInt(b.group(1));
            }
            String ts = now.format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"));
            String base = m.version().substring(0, m.version().length() - "-SNAPSHOT".length());
            return new Snapshot(m.name(), m.group(), m.version(), base + "-" + ts + "-" + (build + 1), ts, build + 1,
                    now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        }

        String rename(String path) {
            int slash = path.lastIndexOf('/');
            String file = path.substring(slash + 1), prefix = artifact + "-" + version;
            return file.startsWith(prefix) ? path.substring(0, slash + 1) + artifact + "-" + value + file.substring(prefix.length()) : path;
        }

        String rewriteModule(String json) {
            return json.replace("\"" + artifact + "-" + version, "\"" + artifact + "-" + value);
        }

        String metadata(java.util.Collection<String> paths) {
            StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata modelVersion=\"1.1.0\">\n");
            x.append("  <groupId>").append(Pom.xml(group)).append("</groupId>\n");
            x.append("  <artifactId>").append(Pom.xml(artifact)).append("</artifactId>\n");
            x.append("  <version>").append(Pom.xml(version)).append("</version>\n");
            x.append("  <versioning>\n    <snapshot>\n      <timestamp>").append(timestamp).append("</timestamp>\n      <buildNumber>")
                    .append(build).append("</buildNumber>\n    </snapshot>\n");
            x.append("    <lastUpdated>").append(updated).append("</lastUpdated>\n    <snapshotVersions>\n");
            String prefix = artifact + "-" + version;
            for (String p : paths) {
                String file = p.substring(p.lastIndexOf('/') + 1);
                if (!file.startsWith(prefix) || file.endsWith(".asc")) continue;
                String rest = file.substring(prefix.length());
                String classifier = null, ext;
                if (rest.startsWith("-")) {
                    int dot = rest.indexOf('.');
                    classifier = rest.substring(1, dot);
                    ext = rest.substring(dot + 1);
                } else {
                    ext = rest.substring(1);
                }
                x.append("      <snapshotVersion>\n");
                if (classifier != null) x.append("        <classifier>").append(Pom.xml(classifier)).append("</classifier>\n");
                x.append("        <extension>").append(Pom.xml(ext)).append("</extension>\n");
                x.append("        <value>").append(Pom.xml(value)).append("</value>\n");
                x.append("        <updated>").append(updated).append("</updated>\n      </snapshotVersion>\n");
            }
            x.append("    </snapshotVersions>\n  </versioning>\n</metadata>\n");
            return x.toString();
        }
    }

    static final class Metadata {
        private Metadata() {}

        private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

        /** The artifact-level metadata with the version added (versions keep their order; release/latest updated). */
        static String merge(String existing, String group, String artifact, String version, ZonedDateTime now) {
            List<String> versions = new ArrayList<>();
            if (existing != null) {
                int start = existing.indexOf("<versions>"), end = existing.indexOf("</versions>");
                if (start >= 0 && end > start) {
                    Matcher m = VERSION.matcher(existing.substring(start, end));
                    while (m.find()) if (!versions.contains(m.group(1))) versions.add(m.group(1));
                }
            }
            if (!versions.contains(version)) versions.add(version);
            String release = null;
            for (String v : versions) if (!v.endsWith("-SNAPSHOT")) release = v;
            StringBuilder x = new StringBuilder();
            x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
            x.append("  <groupId>").append(Pom.xml(group)).append("</groupId>\n");
            x.append("  <artifactId>").append(Pom.xml(artifact)).append("</artifactId>\n");
            x.append("  <versioning>\n");
            x.append("    <latest>").append(Pom.xml(versions.get(versions.size() - 1))).append("</latest>\n");
            if (release != null) x.append("    <release>").append(Pom.xml(release)).append("</release>\n");
            x.append("    <versions>\n");
            for (String v : versions) x.append("      <version>").append(Pom.xml(v)).append("</version>\n");
            x.append("    </versions>\n");
            x.append("    <lastUpdated>").append(now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))).append("</lastUpdated>\n");
            x.append("  </versioning>\n</metadata>\n");
            return x.toString();
        }
    }
}
