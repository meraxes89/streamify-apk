---
name: powersync
description: "Best practices for building and maintaining applications with PowerSync: Cloud and self-hosted setup, sync configuration, client SDK usage, backend integration (Supabase, custom Postgres, MongoDB, Azure DocumentDB, MySQL, MSSQL), schema changes, watch and reactive queries (useQuery), attachments, the upload queue, and debugging. Use this skill whenever the user mentions PowerSync, a @powersync/* package (@powersync/web, @powersync/react-native, @powersync/node), the powersync Flutter/Dart package, the powersync Rust crate, wa-sqlite, offline-first sync, local-first architecture, sync rules, sync streams, uploadData, resolveCredentials, disconnectAndClear, logout or user switching in a synced app, real-time data replication, Electric Cloud migration, Electric Shapes, an app stuck on Syncing, data not syncing, or wants to add offline-capable sync to a mobile or web app, even if they do not explicitly name PowerSync."
when_to_use: "Load before any data, schema, sync, or auth change in a project that already uses PowerSync, even if the request never mentions sync. Examples: adding tables or columns, watch or useQuery results not updating, rows missing on a device, uploads stuck in the queue, a spinner stuck on Syncing, editing sync-config.yaml or service.yaml, running powersync CLI commands, migrating legacy sync configuration to Sync Streams, or implementing logout and account switching with disconnectAndClear."
license: MIT
compatibility: Works with any skills-compatible agent. Some references include CLI commands requiring the @powersync/cli package.
metadata:
  author: powersync
  version: "1.6.6" # x-release-please-version
  organization: PowerSync
  tags: powersync, offline-first, local-first, sync-streams, sqlite, replication, uploadData, fetchCredentials, resolveCredentials, Authenticator, MutationUploader, service-config, sync-config, cloud, cli, debugging, supabase, postgres, mongodb, mysql, electric, electric-migration, watch-queries, useQuery, attachments, upload-queue, disconnectAndClear, schema, react-native, flutter, node, web, wa-sqlite, rust, cargo, powersync-rust
---

# PowerSync Skills

Use this skill to onboard a project onto PowerSync and to keep it healthy afterwards, without trial-and-error. Treat this as a guided workflow first and a reference library second.

**Agents: Read [POWERSYNC_RULES.md](POWERSYNC_RULES.md) and [REPO_OVERLAY.md](REPO_OVERLAY.md) before proceeding.** They contain the upstream compliance rules and this repo's additional guardrails. The Quick Rules below are a reminder, not a substitute. **`powersync login`** is **PowerSync Cloud only** (PAT); self-hosted does not use it.

## Terminology

- **Operator** — the human directing this agent (whose request you are fulfilling).
- **User** — an end-user of the operator's PowerSync app (JWT subjects, the row a sync stream filters by, the person calling `disconnectAndClear()`).

If a sentence is ambiguous, default to the operator interpretation. Full legend in `POWERSYNC_RULES.md`.

## Quick Rules

- **CLI-first.** Use the [PowerSync CLI](https://docs.powersync.com/tools/cli.md) for all operations. Do not hand-write config files. See `references/powersync-cli.md`.
- **Ask, don't assume.** Ask the operator: Cloud vs self-hosted, and which backend (Supabase, Postgres, MongoDB, Azure DocumentDB, MySQL, MSSQL). Do not default to Supabase.
- **Backend before frontend.** Deploy sync config and verify the service before writing app code.
- **Sync Streams for new projects.** Sync Rules are legacy.
- **Keep credentials in `.env`, never hardcoded.** Record URLs and keys in `.env` as they become available; config (`!env`) and app code read from there.
- **Default scope on existing projects: sync-config only.** Do not edit `service.yaml` or `cli.yaml` unless the operator explicitly authorized service/infra changes in this conversation.
- **Confirm the target instance before any mutating command** (`deploy`, `destroy`, `stop`, `link --create`, `pull instance`). Never deploy to an instance not authorized by the operator. Treat production as off-limits unless explicitly approved.
- **Use project memory.** If your harness supports it, persist the CLI invocation, sync-config path, authorized instance ids + environment (dev/staging/prod), and allowed scope of changes. Verify saved values still match reality before acting on them. See `POWERSYNC_RULES.md` § "Continuous Use & Guardrails".

## Vendored copy in this repo

This is a **tracked, vendored copy** under `.claude/skills/powersync/`, not an `npx skills` install. A fresh clone gets it automatically; do not install it globally or run `npx skills update` to replace this copy. See `VENDOR.md` for the pinned upstream commit and licence.

The full upstream reference set is kept deliberately: the upstream trim script found no `com.powersync` dependency in this repo yet and declined to trim. Kotlin is the relevant SDK here (`references/sdks/powersync-kotlin.md`), with Supabase as the source database; `wasmJs` has only experimental PowerSync support. Once an SDK is actually adopted, re-run the upstream `scripts/trim.mjs` with operator approval. Never deploy, destroy, or change cloud infrastructure from this skill without explicit human approval — see `REPO_OVERLAY.md`.

## What to Load for Your Task

| Task | Start with | Load on demand |
|------|-----------|----------------|
| Supabase + PowerSync | `references/onboarding-supabase.md` | `references/supabase-auth.md`, `references/sync-config.md`, SDK files |
| Custom backend (non-Supabase) | `references/onboarding-custom.md` | `references/custom-backend.md`, `references/sync-config.md`, SDK files |
| New project setup | `references/powersync-cli.md` + `references/powersync-service.md` | `references/sync-config.md`, SDK files |
| Migrating from Electric Cloud | `references/migration-from-electric.md` | `references/sync-config.md`, SDK files |
| Self-hosting / service config | `references/powersync-service.md` + `references/powersync-cli.md` | `references/sync-config.md` |
| Terraform / IaC provisioning | `references/terraform.md` | `references/sync-config.md`, `references/supabase-auth.md` (if Supabase source) |
| Writing sync config | `references/sync-config.md` | — |
| Debugging sync issues | `references/powersync-debug.md` | — |
| Raw Tables (advanced) | `references/raw-tables.md` | — |
| Attachments | `references/attachments.md` | — |
| Architecture overview | `references/powersync-overview.md` | — |
| SQLite extensions (vector search, FTS5, custom tokenizers) | `references/sqlite-extensions.md` | — |

## SDK Reference Files

### JavaScript / TypeScript

Always load `references/sdks/powersync-js.md` for any JS/TS project, then load the applicable framework file.

| Framework | File | Load early if… |
|-----------|------|----------------|
| React / Next.js | `references/sdks/powersync-js-react.md` | Vite + React project — contains the required `vite.config.ts` setup (`optimizeDeps.exclude`, `worker.format: 'es'`) needed before installing packages |
| React Native / Expo | `references/sdks/powersync-js-react-native.md` | |
| Vue / Nuxt | `references/sdks/powersync-js-vue.md` | |
| Node.js / Electron | `references/sdks/powersync-js-node.md` | |
| TanStack | `references/sdks/powersync-js-tanstack.md` | |
| Drizzle / Kysely ORM | `references/sdks/powersync-js-orm.md` | Project uses Drizzle or Kysely |

### Other SDKs

| Platform | File |
|----------|
| Dart / Flutter | `references/sdks/powersync-dart.md` |
| .NET | `references/sdks/powersync-dotnet.md` |
| Kotlin | `references/sdks/powersync-kotlin.md` |
| Swift | `references/sdks/powersync-swift.md` |
| Rust | `references/sdks/powersync-rust.md` |

### Live debugging

Some PowerSync SDKs offer ways for agents to connect to a running app, which can be used to
inspect sync state or run queries against the actual app database.

For the Dart SDK, load `references/sdks/powersync-dart-debugging.md` for instructions.

## Key Rules to Apply Without Being Asked

- Never define the `id` column in a PowerSync table schema; it is created automatically.
- Use `column.integer` for booleans and `column.text` for ISO date strings.
- `connect()` is fire-and-forget. Use `waitForFirstSync()` if you need readiness.
- Use separate `Authenticator` and mutation uploader implementations with an explicit PowerSync endpoint on supported SDKs; load the platform SDK reference and backend reference before implementing them.
- Configure downloads and uploads together in one `connect()` call for full sync. Calls replace the previous connection configuration; separate download-only and upload-only calls do not combine. Let the SDK handle reconnects and token refreshes rather than reconnecting on focus or network changes.
- `transaction.complete()` is mandatory or the upload queue stalls permanently.
- On user sign-out, choose based on what data may remain on the device: if the same user will return and keeping data is acceptable, use `disconnect()` so the next session resumes without re-downloading. If visible rows must be hidden but PowerSync's internal copy may stay, use `disconnectAndClear(soft: true)`, which deletes synced rows but keeps the internal copy, so reconnecting as the same user skips a full re-download in most cases. If no synced data may remain, use the default `disconnectAndClear()`. Never retain one user's data for a different user.
- A 4xx response from `uploadData` blocks the upload queue permanently; return 2xx for validation errors.
