package dev.jpm.publish;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a module's Gradle Module Metadata (the {@code .module} file next to the POM).
 *
 * <p>A POM cannot say "consumers compile against this, but do not run with it": Maven's provided scope is not
 * passed on, and Gradle ignores it. Gradle's metadata can, with one variant to compile against
 * ({@code apiElements}) and one to run with ({@code runtimeElements}):
 * <ul>
 *   <li>runtime dependencies are in both;</li>
 *   <li>{@code "runtimeOnly": true} ones only in runtimeElements;</li>
 *   <li>dev dependencies with {@code "api": true} only in apiElements.</li>
 * </ul>
 * Gradle and jpm read it; Maven reads the POM.
 */
final class GradleModule {
    private GradleModule() {}

    /** The published jar, sources jar and javadoc jar, each null when not published. */
    record Files(Path jar, Path sources, Path javadoc) {}

    static String write(Project.Module m, List<Pom.Dep> deps, Files files) {
        List<Pom.Dep> api = new ArrayList<>();
        List<Pom.Dep> runtime = new ArrayList<>();
        for (Pom.Dep d : deps) {
            if (!"runtime".equals(d.scope())) api.add(d);
            if (!"provided".equals(d.scope())) runtime.add(d);
        }
        StringBuilder x = new StringBuilder();
        x.append("{\n");
        x.append("  \"formatVersion\": \"1.1\",\n");
        x.append("  \"component\": {\n");
        x.append("    \"group\": ").append(str(m.group())).append(",\n");
        x.append("    \"module\": ").append(str(m.name())).append(",\n");
        x.append("    \"version\": ").append(str(m.version())).append(",\n");
        x.append("    \"attributes\": {\n");
        x.append("      \"org.gradle.status\": ").append(str(m.snapshot() ? "integration" : "release")).append("\n");
        x.append("    }\n");
        x.append("  },\n");
        x.append("  \"variants\": [\n");
        List<String> variants = new ArrayList<>();
        variants.add(variant("apiElements", library(m, "java-api"), api, files.jar()));
        variants.add(variant("runtimeElements", library(m, "java-runtime"), runtime, files.jar()));
        if (files.sources() != null) variants.add(variant("sourcesElements", docs("sources"), List.of(), files.sources()));
        if (files.javadoc() != null) variants.add(variant("javadocElements", docs("javadoc"), List.of(), files.javadoc()));
        x.append(String.join(",\n", variants)).append("\n");
        x.append("  ]\n");
        x.append("}\n");
        return x.toString();
    }

    private static List<String[]> library(Project.Module m, String usage) {
        List<String[]> a = new ArrayList<>();
        a.add(new String[]{"org.gradle.category", "library"});
        a.add(new String[]{"org.gradle.dependency.bundling", "external"});
        if (m.release() != null) a.add(new String[]{"org.gradle.jvm.version", m.release().toString()});
        a.add(new String[]{"org.gradle.libraryelements", "jar"});
        a.add(new String[]{"org.gradle.usage", usage});
        return a;
    }

    private static List<String[]> docs(String type) {
        return List.of(new String[]{"org.gradle.category", "documentation"},
                new String[]{"org.gradle.dependency.bundling", "external"},
                new String[]{"org.gradle.docstype", type},
                new String[]{"org.gradle.usage", "java-runtime"});
    }

    private static String variant(String name, List<String[]> attributes, List<Pom.Dep> deps, Path file) {
        StringBuilder x = new StringBuilder();
        x.append("    {\n");
        x.append("      \"name\": ").append(str(name)).append(",\n");
        x.append("      \"attributes\": {\n");
        List<String> attrs = new ArrayList<>();
        for (String[] a : attributes) {
            // org.gradle.jvm.version is a number in Gradle's own files
            String value = a[0].equals("org.gradle.jvm.version") ? a[1] : str(a[1]);
            attrs.add("        " + str(a[0]) + ": " + value);
        }
        x.append(String.join(",\n", attrs)).append("\n      },\n");
        if (!deps.isEmpty()) {
            x.append("      \"dependencies\": [\n");
            List<String> ds = new ArrayList<>();
            for (Pom.Dep d : deps) ds.add(dependency(d));
            x.append(String.join(",\n", ds)).append("\n      ],\n");
        }
        byte[] data = Artifacts.read(file);
        Map<String, String> sums = Artifacts.checksums(data);
        String fileName = file.getFileName().toString();
        x.append("      \"files\": [\n        {\n");
        x.append("          \"name\": ").append(str(fileName)).append(",\n");
        x.append("          \"url\": ").append(str(fileName)).append(",\n");
        x.append("          \"size\": ").append(data.length).append(",\n");
        x.append("          \"sha512\": ").append(str(sums.get("sha512"))).append(",\n");
        x.append("          \"sha256\": ").append(str(sums.get("sha256"))).append(",\n");
        x.append("          \"sha1\": ").append(str(sums.get("sha1"))).append(",\n");
        x.append("          \"md5\": ").append(str(sums.get("md5"))).append("\n");
        x.append("        }\n      ]\n");
        x.append("    }");
        return x.toString();
    }

    private static String dependency(Pom.Dep d) {
        StringBuilder x = new StringBuilder();
        x.append("        {\n");
        x.append("          \"group\": ").append(str(d.group())).append(",\n");
        x.append("          \"module\": ").append(str(d.artifact())).append(",\n");
        x.append("          \"version\": {\n            \"requires\": ").append(str(d.version())).append("\n          }");
        if (!d.exclusions().isEmpty()) {
            List<String> ex = new ArrayList<>();
            for (String e : d.exclusions()) {
                String[] ga = e.split(":", 2);
                ex.add("            {\n              \"group\": " + str(ga[0]) + ",\n              \"module\": "
                        + str(ga.length > 1 ? ga[1] : "*") + "\n            }");
            }
            x.append(",\n          \"excludes\": [\n").append(String.join(",\n", ex)).append("\n          ]");
        }
        if (d.classifier() != null) {
            // how Gradle itself refers to a classified jar of a module
            x.append(",\n          \"thirdPartyCompatibility\": {\n            \"artifactSelector\": {\n")
                    .append("              \"name\": ").append(str(d.artifact())).append(",\n")
                    .append("              \"type\": \"jar\",\n")
                    .append("              \"extension\": \"jar\",\n")
                    .append("              \"classifier\": ").append(str(d.classifier())).append("\n")
                    .append("            }\n          }");
        }
        x.append("\n        }");
        return x.toString();
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
