package dev.jpm.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a module's POM.
 *
 * <p>Dependencies are published the way Gradle's {@code java-library} does it: {@code runtime} becomes Maven
 * scope compile (runtime with {@code "runtimeOnly": true}), {@code dev} and {@code test} are left out because
 * consumers never need them. Each dependency gets the version jpm resolved, so consumers build against what
 * was tested. Modules of the same workspace refer to each other by their coordinates.
 */
final class Pom {
    private Pom() {
    }

    private static final Map<String, String[]> LICENSES = Map.ofEntries(
            Map.entry("Apache-2.0", new String[]{"Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0"}),
            Map.entry("MIT", new String[]{"MIT License", "https://opensource.org/licenses/MIT"}),
            Map.entry("BSD-2-Clause", new String[]{"BSD 2-Clause License", "https://opensource.org/licenses/BSD-2-Clause"}),
            Map.entry("BSD-3-Clause", new String[]{"BSD 3-Clause License", "https://opensource.org/licenses/BSD-3-Clause"}),
            Map.entry("GPL-2.0-only", new String[]{"GNU General Public License v2.0 only", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"}),
            Map.entry("GPL-3.0-only", new String[]{"GNU General Public License v3.0 only", "https://www.gnu.org/licenses/gpl-3.0.html"}),
            Map.entry("GPL-3.0-or-later", new String[]{"GNU General Public License v3.0 or later", "https://www.gnu.org/licenses/gpl-3.0.html"}),
            Map.entry("LGPL-2.1-only", new String[]{"GNU Lesser General Public License v2.1 only", "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"}),
            Map.entry("LGPL-3.0-only", new String[]{"GNU Lesser General Public License v3.0 only", "https://www.gnu.org/licenses/lgpl-3.0.html"}),
            Map.entry("AGPL-3.0-only", new String[]{"GNU Affero General Public License v3.0 only", "https://www.gnu.org/licenses/agpl-3.0.html"}),
            Map.entry("MPL-2.0", new String[]{"Mozilla Public License 2.0", "https://www.mozilla.org/en-US/MPL/2.0/"}),
            Map.entry("EPL-2.0", new String[]{"Eclipse Public License 2.0", "https://www.eclipse.org/legal/epl-2.0/"}),
            Map.entry("Unlicense", new String[]{"The Unlicense", "https://unlicense.org/"}));

    static String[] license(String id) {
        String[] known = LICENSES.get(id);
        return known != null ? known : new String[]{id, "https://spdx.org/licenses/" + id + ".html"};
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Dependencies as they appear in the POM: {group, artifact, version, classifier, scope}, plus exclusions.
     */
    record Dep(String group, String artifact, String version, String classifier, String scope, List<String> exclusions) {}

    static List<Dep> dependencies(Project project, Project.Module m) {
        List<Dep> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Project.Dependency d : m.dependencies()) {
            boolean api = "dev".equals(d.scope()) && d.api();
            if (!"runtime".equals(d.scope()) && !api) continue;
            String scope = api ? "provided" : d.runtimeOnly() ? "runtime" : null;
            switch (d.kind()) {
                case "maven" -> {
                    String version = d.resolved() != null ? d.resolved() : d.spec();
                    out.add(new Dep(d.group(), d.artifact(), version, d.classifier(), scope, d.exclude()));
                }
                case "workspace" -> {
                    Project.Module sib = project.module(d.artifact() != null ? d.artifact() : d.key().substring("workspace:".length()));
                    if (sib == null || sib.group() == null) {
                        problems.add(d.key() + " has no \"group\", so it cannot be published or depended on");
                    } else {
                        out.add(new Dep(sib.group(), sib.name(), sib.version(), null, scope, d.exclude()));
                    }
                }
                case "git" -> problems.add(d.key() + " is a Git dependency, which Maven users cannot resolve. Publish it to a"
                        + " Maven repository and depend on those coordinates instead");
                default -> problems.add("unknown dependency kind " + d.kind() + " for " + d.key());
            }
        }
        if (!problems.isEmpty()) {
            throw new PublishException(m.name() + " cannot be published:\n  - " + String.join("\n  - ", problems));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String write(Project project, Project.Module m) {
        List<Dep> deps = dependencies(project, m);
        if (m.group() == null) {
            throw new PublishException(m.name() + " has no \"group\" in its project.jsonc; Maven coordinates need one");
        }
        Map<String, Object> s = m.settings();
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<!-- Generated by jpm-publish from project.jsonc -->\n");
        x.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        x.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        x.append("  <modelVersion>4.0.0</modelVersion>\n");
        x.append("  <!-- This module was also published with a richer model, Gradle metadata, which should be used instead. -->\n");
        x.append("  <!-- do_not_remove: published-with-gradle-metadata -->\n");
        tag(x, "  ", "groupId", m.group());
        tag(x, "  ", "artifactId", m.name());
        tag(x, "  ", "version", m.version());
        tag(x, "  ", "packaging", "jar");
        tag(x, "  ", "name", (String) s.getOrDefault("name", m.name()));
        if (m.description() != null) tag(x, "  ", "description", m.description());
        if (m.url() != null) tag(x, "  ", "url", m.url());
        if (m.license() != null) {
            String[] l = license(m.license());
            x.append("  <licenses>\n    <license>\n");
            tag(x, "      ", "name", l[0]);
            tag(x, "      ", "url", l[1]);
            x.append("    </license>\n  </licenses>\n");
        }
        Object devs = s.get("developers");
        if (devs instanceof List<?> list && !list.isEmpty()) {
            x.append("  <developers>\n");
            for (Object o : list) {
                Map<String, Object> d = (Map<String, Object>) o;
                x.append("    <developer>\n");
                for (String k : List.of("id", "name", "email", "organization", "url")) {
                    if (d.get(k) != null) tag(x, "      ", k.equals("organization") ? "organization" : k, d.get(k).toString());
                }
                x.append("    </developer>\n");
            }
            x.append("  </developers>\n");
        }
        String scm = (String) s.get("scm");
        if (scm == null && m.url() != null && m.url().matches("https://(github\\.com|gitlab\\.com|codeberg\\.org)/.+")) scm = m.url();
        if (scm != null) {
            String repo = scm.replaceAll("\\.git$", "");
            x.append("  <scm>\n");
            tag(x, "    ", "url", repo);
            tag(x, "    ", "connection", "scm:git:" + repo + ".git");
            tag(x, "    ", "developerConnection", "scm:git:" + repo + ".git");
            x.append("  </scm>\n");
        }
        if (!deps.isEmpty()) {
            x.append("  <dependencies>\n");
            for (Dep d : deps) {
                x.append("    <dependency>\n");
                tag(x, "      ", "groupId", d.group());
                tag(x, "      ", "artifactId", d.artifact());
                tag(x, "      ", "version", d.version());
                if (d.classifier() != null) tag(x, "      ", "classifier", d.classifier());
                if (d.scope() != null) tag(x, "      ", "scope", d.scope());
                if (!d.exclusions().isEmpty()) {
                    x.append("      <exclusions>\n");
                    for (String e : d.exclusions()) {
                        String[] ga = e.split(":", 2);
                        x.append("        <exclusion>\n");
                        tag(x, "          ", "groupId", ga[0]);
                        tag(x, "          ", "artifactId", ga.length > 1 ? ga[1] : "*");
                        x.append("        </exclusion>\n");
                    }
                    x.append("      </exclusions>\n");
                }
                x.append("    </dependency>\n");
            }
            x.append("  </dependencies>\n");
        }
        x.append("</project>\n");
        return x.toString();
    }

    private static void tag(StringBuilder x, String indent, String name, String value) {
        x.append(indent).append('<').append(name).append('>').append(xml(value)).append("</").append(name).append(">\n");
    }

    /**
     * What Maven Central requires, as a list of what is missing (empty when the POM qualifies).
     */
    @SuppressWarnings("unchecked")
    static List<String> centralProblems(Project.Module m) {
        List<String> p = new ArrayList<>();
        if (m.group() == null) p.add("\"group\" in project.jsonc");
        if (m.description() == null) p.add("\"description\" in project.jsonc");
        if (m.url() == null) p.add("\"url\" in project.jsonc (the project's home page)");
        if (m.license() == null) p.add("\"license\" in project.jsonc (an SPDX id such as Apache-2.0)");
        Object devs = m.settings().get("developers");
        if (!(devs instanceof List<?> l) || l.isEmpty()) p.add("\"developers\" in jpm-publish.jsonc, e.g. [{ \"name\": \"Your Name\" }]");
        boolean scm = m.settings().get("scm") != null
                || (m.url() != null && m.url().matches("https://(github\\.com|gitlab\\.com|codeberg\\.org)/.+"));
        if (!scm) p.add("\"scm\" in jpm-publish.jsonc (the repository URL)");
        if (m.snapshot()) p.add("a release version (Maven Central does not take -SNAPSHOT versions)");
        return p;
    }
}
