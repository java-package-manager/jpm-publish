# Contributing to jpm-publish

jpm-publish is the `jpm publish` extension. [AGENTS.md](AGENTS.md) has the same facts for AI agents; the
plan for jpm and its extensions is jpm's [ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

## Build, test, run

It needs jpm and a JDK 21.

```sh
jpm test
jpm build
JPM_EXE=jpm JPM_PROJECT_DIR=/path/to/a/project jpm start -- --dry-run
java checks/Comments.java check src test     # the comment lint CI runs
```

## Rules for the code

- No dependencies besides jpm's client (`github:java-package-manager/jpm/jpm-api`).
- No `//` or `/* */` comments ([docs/adr/0001](docs/adr/0001-decisions-live-in-adrs.md)). Decisions go
  into [docs/adr](docs/adr); Javadoc is fine.
- jpm-publish reads projects through jpm's protocol, with the client from `github:java-package-manager/jpm/jpm-api`, never
  by parsing `project.jsonc` or sources itself.
- No stability before 1.0: options and settings may change in any release, with no deprecation period.

## Workflow and releases

- One local branch per change; the tests pass before it is merged into `main`; `main` is pushed once.
- Change `"version"` in `project.jsonc` and push to `main`: once the tests pass, CI tags that commit with
  the version. jpm builds extensions from source, so the tag is the release.
