# AGENTS.md

Facts for AI agents working on jpm-publish. Reasons are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/adr](docs/adr).

## Commands

| What | Command |
|---|---|
| Checks (jpm-format's formatting and lint among them), then the tests, as CI runs them | `jpm check` |
| Tests | `jpm test` |
| Format | `jpm format` |
| Build | `jpm build` |
| Dry run on a project | `JPM_EXE=jpm JPM_PROJECT_DIR=<project> jpm start -- --dry-run` |

## Where things live

- `src/dev/jpm/publish/Main.java`: the command. `Project.java`: the project, read with jpm's client (`JpmClient`, `jpm api`).
- `Pom.java`, `GradleModule.java`: what is published. `Artifacts.java`: jars, javadoc, signing.
- `Repository.java`: Maven repositories and Maven Central. `test/dev/jpm/publish/PublishTest.java`: tests.

## Rules that must not be broken

- No dependencies besides jpm's client; no `//` or `/* */` comments (decisions go into `docs/adr`).
- Read projects through jpm only.
- Do not push, tag, or file issues without being asked.

## Done means

`jpm check` (which includes the comment lint) passes, the README describes user-facing changes, and the commit message
says what changed and why.
