# PATCHES.md — upstream divergence ledger

This fork (`streamify-feishin`) tracks `jeffvli/feishin`. Upstream moves fast (~180 commits/month); we track release **tags only** (monthly, stable) and **merge, never rebase**. This file is the complete, honest ledger of every change we carry on top of upstream, so future tag merges have a single place to look for expected conflict surfaces.

## Policy

- New Streamify work goes in **new directories only** (e.g. `src/renderer/api/streamify/`). Features never re-open upstream files.
- The only upstream files we carry changes in are listed in this ledger (plus the pre-approved future touch list below).
- Never touch: `src/renderer/features/login/**`, `src/renderer/features/servers/**` (beyond the frozen registration lines recorded below), `src/renderer/components/**`, themes.
- The fork stays GPL-3.0 with source available.
- Secrets live in deployment configuration, never in the tree.
- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `ci:`, …) are enforced by CI (commitlint) on PR commit ranges and pushes.

## Carried patches (P0 — forked from tag `v1.17.0`)

### 1. `src/shared/types/domain-types.ts` — `ServerType` enum

- **What:** added `STREAMIFY = 'streamify'` to the `ServerType` enum.
- **Why:** registers the Streamify server type in the shared domain model so the API controller map and the per-server-type UI gating can reference it.
- **Expected conflict surface:** low. The enum is rarely edited upstream; a new upstream server type would conflict trivially.

### 2. `src/renderer/api/controller.ts` — controller registration

- **What:** added `streamify: InternalControllerEndpoint` to the `ApiController` type and `streamify: StreamifyController` to the `endpoints` map (+ import).
- **Why:** routes API calls for the `streamify` server type to our controller.
- **Expected conflict surface:** low. Registration block is stable.

### 3. `src/renderer/api/streamify/` — new directory (ours)

- **What:** `streamify-controller.ts` implements `InternalControllerEndpoint` (all 53 required + 16 optional endpoints). Every method throws a single typed `StreamifyNotImplementedError` carrying the endpoint name. `streamify-error.ts` holds the error class. No logic.
- **Why:** the UI's per-server-type gating hides affordances behind missing endpoints, so a registered stub controller is the established pattern for a server type that is selectable before it is functional. P1 replaces stubs with real implementations, one endpoint at a time.
- **Expected conflict surface:** none — new directory upstream never touches.

### 4. `src/shared/types/types.ts` — UI-side `ServerType` enum + `toServerType` (forced by typecheck/selection)

- **What:** added `STREAMIFY = 'streamify'` to the second, UI-facing `ServerType` enum (this file declares its own copy, separate from `domain-types.ts`), and added `case ServerType.STREAMIFY: return ServerType.STREAMIFY;` to `toServerType`.
- **Why:** the UI (add-server form, login route) imports `ServerType` from this file, so both enums must carry the member or `typecheck:web` fails on the exhaustive records. Without the `toServerType` case, `SERVER_TYPE=streamify` (via `settings.js`) resolves to `null` and the app silently falls back to the default server type instead of selecting Streamify.
- **Expected conflict surface:** low. Purely additive.

### 5. `src/renderer/features/servers/components/add-server-form.tsx` — `SERVER_TYPES` record (forced by typecheck)

- **What:** added the `[ServerType.STREAMIFY]` entry (`icon`, `name`) and the `streamify.png` asset import.
- **Why:** `SERVER_TYPES` is typed `Record<ServerType, ServerDetails>`; a new enum member without a record entry fails `typecheck:web`. This line is **frozen** — it exists only to satisfy exhaustiveness and render the type in the add-server picker.
- **Expected conflict surface:** low. Only conflicts if upstream adds another server type (never so far).

### 6. `src/renderer/features/login/routes/login-route.tsx` — `SERVER_ICONS` / `SERVER_NAMES` records (forced by typecheck)

- **What:** added `[ServerType.STREAMIFY]` entries to both records and the `streamify.png` asset import.
- **Why:** both records are exhaustive over `ServerType`; same typecheck forcing as (5). These lines are **frozen**.
- **Expected conflict surface:** low.

### 7. `src/renderer/features/servers/assets/streamify.png` — new asset (ours)

- **What:** 256×256 Streamify logo (source: streamify monorepo `front-end/web/public/icon512.png`).
- **Why:** icon for the registration records above.
- **Expected conflict surface:** none — new file.

## Pre-approved future touch list (from F0)

These upstream files may be touched by later phases, and only for the stated purpose. Anything else goes in new directories:

- `src/renderer/app/app-outlet.tsx` / `src/renderer/app/routes.ts` — P1 auth gate (redirect Streamify sessions to our auth flow).
- `package.json` — adding our dependencies.
- `settings.js.template` — Streamify deployment variables.

## CI additions (ours)

- `.github/workflows/web-ci.yml` — web-scoped pipeline: `pnpm install` (Electron binary download skipped), `pnpm run lint` (typecheck node + web, eslint `--max-warnings=0`, stylelint), `pnpm run build:web`. Runs on push and pull_request. Upstream's own `test.yml` is left untouched and keeps running (it also commitlints our commit ranges).

## Upstream sync procedure (tags only, merge never rebase)

1. `git fetch upstream --tags`
2. `git merge v<next-tag>` on `main` (no rebase, no cherry-pick trains).
3. Resolve conflicts using this ledger as the map of our divergence.
4. `pnpm install && pnpm run lint && pnpm run build:web` must be green before the merge lands.
5. Vendored copy in the streamify monorepo is updated from this fork only (see `front-end/decisions/feishin-adoption/F1-vendoring-strategy.md`).
