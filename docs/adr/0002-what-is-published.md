# 0002. What the POM and the Gradle module metadata say

Status: accepted

## Decision

- `dev` and `test` dependencies are not for consumers and are left out of the POM, except dev
  dependencies with `"api": true`, which consumers compile against (`Pom`).
- The POM lists dependencies first: they explain everything that would make it unusable.
- A Gradle module metadata file (`.module`) is published next to the POM for what a POM cannot say,
  such as `"api"` dev dependencies (`Main`). The POM carries the marker Gradle looks for before it reads
  the `.module` file.
- In the `.module` file, `org.gradle.jvm.version` is a number, as in Gradle's own files, and a classified
  jar is referred to the way Gradle refers to it (`thirdPartyCompatibility.artifactSelector`)
  (`GradleModule`).
- Settings live in `jpm-publish.jsonc`, not in `project.jsonc`: jpm's manifest declares extensions but
  holds none of their settings. A module's file overrides the workspace root's, key by key (`Project`).
