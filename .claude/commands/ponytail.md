---
description: Apply the Ponytail full-mode minimal-change contract to the current task
argument-hint: "[task]"
---

Load the `ponytail` skill and apply it to the current task: $ARGUMENTS

The repository default is full mode. Follow `AGENTS.md` and
`docs/playbooks/PONYTAIL.md`. Make the smallest complete change that fits the
existing architecture; reuse before adding; do not add a dependency or module
for a few lines; write the test for non-trivial logic but do not run Gradle or
cargo unless asked. End with `Skipped:`, `Not checked:`, and `Risk:` lines.
