package dev.jpm.publish;

import dev.jpm.api.model.Classpaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project as jpm's protocol describes it (project/load and project/classpaths).
 */
record Project(Path dir, String name, boolean workspace, Map<String, Object> settings, List<Module> modules) {

    record Dependency(String key, String kind, String scope, String spec, String resolved, String group,
                      String artifact, String classifier, String version, boolean runtimeOnly, List<String> exclude,
                      boolean api) {}

    record Module(String name, String group, String version, Path dir, String visibility, String description,
                  String license, String url, Path sourceDir, Path resourceDir, Path jar, Path javaHome, Integer release,
                  List<Dependency> dependencies, Map<String, Object> settings, List<Path> compileClasspath,
                  List<String> compileModuleOptions) {

        boolean snapshot() {
            return version.endsWith("-SNAPSHOT");
        }

        String coordinates() {
            return group + ":" + name + ":" + version;
        }
    }

    static Project from(dev.jpm.api.model.Project p, Map<String, Classpaths> classpaths) {
        Map<String, Object> rootSettings = map(p.extensions().get("publish"));
        List<Module> modules = new ArrayList<>();
        for (dev.jpm.api.model.Module m : p.modules()) {
            List<Dependency> deps = new ArrayList<>();
            for (dev.jpm.api.model.Dependency d : m.dependencies()) {
                String version = null, group = d.group(), artifact = d.artifact();
                if (d.kind().equals("workspace")) {
                    dev.jpm.api.model.Module sib = p.module(d.module());
                    if (sib != null) {
                        version = sib.version();
                        group = sib.group();
                        artifact = sib.name();
                    }
                }
                deps.add(new Dependency(d.key(), d.kind(), d.scope(), d.spec(), d.resolved(), group, artifact, d.classifier(), version,
                        d.runtimeOnly(), d.exclude(), d.api()));
            }
            Map<String, Object> settings = new LinkedHashMap<>(rootSettings);
            settings.putAll(map(m.extensions().get("publish")));
            List<Path> cp = new ArrayList<>();
            Classpaths c = classpaths.get(m.name());
            if (c != null) {
                for (dev.jpm.api.model.Package pkg : c.compile()) {
                    if (pkg.classes() != null) cp.add(pkg.classes());
                    cp.addAll(pkg.jars());
                }
            }
            List<String> moduleOptions = m.modulePath() == null ? null : m.modulePath().options();
            modules.add(new Module(m.name(), m.group(), m.version(), m.dir(), m.visibility(), m.description(), m.license(), m.url(),
                    first(m.folders().sources()), first(m.folders().resources()), m.jar(), m.jdk().home(), m.jdk().release(), deps,
                    settings, cp, moduleOptions));
        }
        return new Project(p.dir(), p.name(), p.workspace(), rootSettings, modules);
    }

    private static Path first(List<Path> paths) {
        return paths.isEmpty() ? null : paths.get(0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /**
     * A module's dependency on another module of this workspace, by name.
     */
    Module module(String name) {
        for (Module m : modules) if (m.name().equals(name)) return m;
        return null;
    }
}
