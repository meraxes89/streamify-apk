---
description: Review a change for bugs, security, load, tests, speed, and bloat
agent: plan
---

Load the `ponytail-review` skill and review $ARGUMENTS.
If no target is named, review the uncommitted changes, or the last commit if
there are none. Read `docs/SYSTEMS.md`, the relevant `Sxx` page, the changed
code's callers, tests, and docs. Do not edit files. Do not run Gradle or cargo.
Use the skill's output format and end with `Not checked` for anything not run.
