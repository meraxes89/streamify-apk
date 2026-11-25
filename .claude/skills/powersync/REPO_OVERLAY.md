# Streamify repo overlay for the PowerSync skill

This copy is vendored in `meraxes89/streamify-apk`. The upstream
`POWERSYNC_RULES.md` applies; the rules below are **additional** and win on
conflict for this repository.

## Current state

S11 still uses the hand-rolled `StreamifySyncEngine` → Supabase pipeline
(`composeApp/src/commonMain/kotlin/com/streamify/sync/`, `streamify-modules/supabase/`,
`supabase/migrations/`). PowerSync is **not** a dependency, not deployed, and not
wired into the app. Use this skill to evaluate and, only with approval, plan a
migration — never as permission to start one.

Open work on the existing pipeline: issue #333 (P0 sync data plane) and issue #336
(KMP UI design proposals). Do not use this skill to overwrite those tracks.

## Hard stops

1. **Read-only by default.** Never run a mutating PowerSync command
   (`powersync deploy`, `deploy service-config`, `deploy sync-config`, `destroy`,
   `stop`, `compact`, `link cloud --create`, `pull instance`,
   `docker stop --remove-volumes`) without explicit, in-conversation approval
   that names the target instance and environment. One approval = one command.
   Production is off-limits unless the operator names it explicitly.
2. **No credentials through the model or the repo.** Never paste tokens, PATs,
   JWT secrets, connection strings, or `.env` contents into chat, issues, code,
   or tracked files. Use environment variables / a secret store
   (`!env` in config). `powersync login` stores a Cloud PAT and is not needed for
   this repo today. Root `AGENTS.md` already forbids secrets and
   `local.properties` in commits — that rule still wins.
3. **No architecture change without a decision record.** Adding
   `com.powersync:core` / `connector-supabase`, a PowerSync service, or a sync
   config requires a documented dependency rationale in the PR description and
   an RFC or ADR (per root `AGENTS.md` / `CONTRIBUTING.md`). Do not edit the
   `core/` submodule; it is external/upstream.
4. **KMP scope.** The relevant SDK reference is
   `references/sdks/powersync-kotlin.md`; Android/JVM/Apple are supported by that
   SDK, Web/WASM is experimental. This repo targets `wasmJs` too — any proposal
   must say how that target is handled.
5. **No build to verify.** Per root `AGENTS.md`, do not run Gradle, cargo, or a
   full build unless explicitly asked. Say "not run" for anything unverified.
   Skill wiring itself is checked with `python3 scripts/check_agent_skills.py`.

## If the operator asks to evaluate adoption

Produce an RFC/ADR first: current S11 contract vs. PowerSync sync streams and
upload queue, the `core/` + Room migration boundary, RLS/auth implications,
wasmJs parity, and the security-audit caveats in `VENDOR.md`. Implement only
after the operator approves that decision.
