# jpm-publish

`jpm publish` for [jpm](https://github.com/java-package-manager/jpm): publishes your modules to any Maven repository
(Reposilite, Nexus, Artifactory, GitHub Packages, a folder), to your local `~/.m2`, or to **Maven Central**
through the Sonatype Central Portal.

It is a jpm extension: jpm runs `jpm publish` as `jpm-publish`. It reads the project through jpm's
protocol (`jpm api`), so it needs jpm 0.12.0 or newer; its one dependency is jpm's client,
`dev.jpm:jpm-api` (from `https://repo.thenextlvl.net`).

```sh
jpm add -g github:java-package-manager/jpm-publish
jpm publish --help
```

## What it publishes

For each module with a `"group"` (in a workspace, every such module unless it sets `"skip": true`):

- the jar from `jpm build`, which `jpm publish` runs first;
- a POM generated from `project.jsonc`. Runtime dependencies are listed with the versions jpm resolved,
  with their exclusions and classifiers; `"runtimeOnly": true` becomes Maven's runtime scope. `dev`
  dependencies with `"api": true` (Gradle's `compileOnlyApi`) become provided; other `dev` and `test`
  dependencies are left out, since consumers never need them. Workspace siblings are referred to by
  their coordinates. Git dependencies are refused, because Maven users cannot resolve them;
- Gradle Module Metadata (`.module`), which says what a POM cannot: `"api": true` dev dependencies are
  compiled against but not run with. Gradle and jpm read it (consumers get them on the compile classpath
  only); Maven reads the POM, where provided is not passed on;
- a sources jar, and a javadoc jar (always for Maven Central, otherwise with `--javadoc` or
  `"javadoc": true`), built with the module's own JDK and release;
- `.md5`, `.sha1`, `.sha256` and `.sha512` checksums, and the artifact's `maven-metadata.xml`, updated
  rather than replaced;
- `.asc` signatures from gpg, when signing is on (always for Maven Central).

Release versions are never overwritten. `-SNAPSHOT` versions go to the repository's `"snapshots"` URL
when one is set.

## Configuration

Everything lives in `project.jsonc`. Settings on the workspace root apply to every module; a module's own
`"extensions": { "publish": ... }` overrides them key by key.

```jsonc
{
  "name": "my-lib",
  "group": "com.example",
  "version": "1.0.0",
  "description": "What it does",
  "license": "Apache-2.0",                    // an SPDX id
  "url": "https://github.com/me/my-lib",
  "extensions": {
    "publish": {
      "repositories": {
        "faststats": {
          "url": "https://repo.faststats.dev/releases",
          "snapshots": "https://repo.faststats.dev/snapshots"
        },
        "central": { "type": "central" }         // "publishingType": "automatic" (default) or "user_managed"
      },
      "developers": [ { "id": "me", "name": "My Name", "email": "me@example.com" } ],
      "scm": "https://github.com/me/my-lib",     // defaults to "url" on GitHub, GitLab and Codeberg
      "javadoc": true,                            // also for repositories other than Central
      "sign": false                               // defaults to true for Central only
    }
  }
}
```

`jpm publish` with one repository configured publishes there; with several, name one
(`jpm publish faststats`). `jpm publish https://repo.example.com/releases` publishes to a URL directly, and
`jpm publish --local` to `~/.m2/repository`.

## Credentials

Credentials never go in `project.jsonc`. They come from the environment:

| Variable | |
|---|---|
| `JPM_PUBLISH_<REPOSITORY>_USERNAME`, `_PASSWORD` | For one repository, e.g. `JPM_PUBLISH_FASTSTATS_USERNAME` (the name upper-cased, other characters as `_`) |
| `JPM_PUBLISH_USERNAME`, `JPM_PUBLISH_PASSWORD` | For any repository |
| `JPM_PUBLISH_CENTRAL_USERNAME`, `_PASSWORD` | The user token from the Central Portal (Account, Generate User Token) |
| `JPM_SIGNING_KEY`, `JPM_SIGNING_PASSWORD` | The gpg key to sign with, and its passphrase |
| `JPM_SIGNING_KEY_FILE` | An ASCII-armored private key to import into a throwaway keyring (for CI) |

## Maven Central

Maven Central requires a description, url, license, developers, scm, sources, javadoc and signatures, and
release versions. `jpm publish central` checks all of them before building anything and says what is
missing. It uploads every module as one deployment, then waits for the Portal to validate and publish it
(`--no-wait` returns right after the upload).

## Options

```
--local           publish to ~/.m2/repository
--module <name>   publish only this module (repeatable)
--dry-run         build everything into .cache/publish/ and list what would be uploaded
--no-build        use the jars already in dist/
--no-sources, --no-javadoc, --javadoc
--sign, --no-sign
--no-wait         Maven Central: do not wait for validation
```

## Developing

```sh
jpm check     # the checks (jpm-format's formatting and lint among them), then the tests, as CI runs them
jpm format    # format the sources with jpm-format
jpm build
JPM_EXE=jpm JPM_PROJECT_DIR=/path/to/a/project jpm start -- --dry-run
```

## Releasing

Change `"version"` in `project.jsonc` and push to `main`: once the tests pass, CI tags that commit with
the version (plain, no `v` prefix). jpm builds extensions from their source, so the tag is the release:
`jpm add -g github:java-package-manager/jpm-publish` installs the newest tag, and `jpm update -g` moves to newer ones
(`jpm update -g --latest` past the installed range, e.g. from 0.x to 1.0).

## License

MIT, see [LICENSE](LICENSE).
