# 0004. Repositories and Maven Central

Status: accepted

## Decision

- Maven repositories are Maven 2 layout repositories over HTTP (Reposilite, Nexus, Artifactory, GitHub
  Packages) or `file:` folders. Credentials come from `JPM_PUBLISH_<ID>_USERNAME`/`_PASSWORD`, then
  `JPM_PUBLISH_USERNAME`/`_PASSWORD`, then the system properties `jpm.publish.<id>.username`/`.password`
  for scripts and tests (`Repository`).
- A release version that is already published is not replaced (an HTTP 409 says so), except in the local
  repository (`~/.m2`, or `JPM_PUBLISH_M2`), where publishing again replaces it. `-SNAPSHOT` versions
  are always replaced.
- Maven Central goes through the Central Portal. Validation takes a while, so jpm-publish polls until it
  ends and reports how, and keeps polling through brief network trouble.
