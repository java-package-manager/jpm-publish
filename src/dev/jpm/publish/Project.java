package dev.jpm.publish;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The project as `jpm info --json` describes it. */
record Project(Path dir, String name, boolean workspace, Map<String, Object> settings, List<Module> modules) {

    record Dependency(String key, String kind, String scope, String spec, String resolved, String group,
                      String artifact, String classifier, String version, boolean runtimeOnly, List<String> exclude,
                      boolean api) {}

    record Module(String name, String group, String version, Path dir, String visibility, String description,
                  String license, String url, Path sourceDir, Path resourceDir, Path jar, Path javaHome, Integer release,
                  List<Dependency> dependencies, Map<String, Object> settings, List<Path> compileClasspath) {

        boolean snapshot() {
            return version.endsWith("-SNAPSHOT");
        }

        String coordinates() {
            return group + ":" + name + ":" + version;
        }
    }

    @SuppressWarnings("unchecked")
    static Project from(Map<String, Object> info) {
        Object v = info.get("infoVersion");
        if (v == null) throw new PublishException("`jpm info --json` gave no infoVersion; jpm-publish needs jpm 0.4.0 or newer");
        Map<String, Object> rootExt = map(info.get("extensions"));
        Map<String, Object> rootSettings = map(rootExt.get("publish"));
        List<Module> modules = new ArrayList<>();
        for (Object o : (List<Object>) info.getOrDefault("modules", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Dependency> deps = new ArrayList<>();
            for (Object d0 : (List<Object>) m.getOrDefault("dependencies", List.of())) {
                Map<String, Object> d = (Map<String, Object>) d0;
                deps.add(new Dependency(str(d, "key"), str(d, "kind"), str(d, "scope"), str(d, "spec"), str(d, "resolved"),
                        str(d, "group"), str(d, "artifact"), str(d, "classifier"), str(d, "version"),
                        Boolean.TRUE.equals(d.get("runtimeOnly")), (List<String>) d.getOrDefault("exclude", List.of()),
                        Boolean.TRUE.equals(d.get("api"))));
            }
            // module settings override the root's, key by key
            Map<String, Object> settings = new LinkedHashMap<>(rootSettings);
            settings.putAll(map(map(m.get("extensions")).get("publish")));
            List<Path> cp = new ArrayList<>();
            for (Object p : (List<Object>) m.getOrDefault("compileClasspath", List.of())) cp.add(Path.of((String) p));
            Object rel = m.get("release");
            modules.add(new Module(str(m, "name"), str(m, "group"), str(m, "version"), path(m, "dir"), str(m, "visibility"),
                    str(m, "description"), str(m, "license"), str(m, "url"), path(m, "sourceDir"), path(m, "resourceDir"),
                    path(m, "jar"), path(m, "javaHome"), rel == null ? null : Integer.valueOf(rel.toString()), deps, settings, cp));
        }
        return new Project(Path.of(str(info, "projectDir")), str(info, "name"), Boolean.TRUE.equals(info.get("workspace")),
                rootSettings, modules);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Path path(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : Path.of(s);
    }

    /** A module's dependency on another module of this workspace, by name. */
    Module module(String name) {
        for (Module m : modules) if (m.name().equals(name)) return m;
        return null;
    }
}
