---
description: Evaluate or plan PowerSync for the S11 sync-to-Supabase subsystem
argument-hint: "[question or task]"
---

Load the `powersync` skill from `.claude/skills/powersync/`. Read its
`POWERSYNC_RULES.md`, `REPO_OVERLAY.md` and `VENDOR.md` before acting.

This repo's S11 still uses the hand-rolled `StreamifySyncEngine` → Supabase
pipeline. Default to read-only evaluation and an RFC/ADR; never run a mutating
PowerSync CLI command, add a dependency, or touch the `core/` submodule without
explicit approval. Keep issue #333 (P0 sync data plane) as the current fix track.
Do not run Gradle or cargo to verify.

Task: $ARGUMENTS
