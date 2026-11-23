# 0001. Decisions live in ADRs, not in code comments

Status: accepted

The same policy as jpm's ADR 0001: the Java sources have no `//` or `/* */` comments, a non-obvious
decision goes into an ADR here, and Javadoc is not a comment. jpm-format's
`NonJavadocComment` rule, turned on as an error in `project.jsonc`, is the lint: `jpm check` (in CI) fails on
a comment. It replaced `checks/Comments.java` and its baseline.
