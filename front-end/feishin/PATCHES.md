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

- **What:** `streamify-controller.ts` implements `InternalControllerEndpoint`. P0 shipped every method (53 required + 16 optional) throwing a typed `StreamifyNotImplementedError` carrying the endpoint name. P1 replaces the library read set with real implementations (song/album/artist/genre lists + counts, details, search, top/similar/radio/random, playlist reads, thumbnail URL lookups, getServerInfo/getUserInfo/getScanStatus/getRoles/getMusicFolderList/getInternetRadioStations/refreshItems) backed by the in-memory library snapshot; auth and mutations stay stubs. Sibling modules (all ours, all new files): `session.ts` (the credential codec + dev session seam shared with Engineer A), `cloud.ts` (the existing Supabase REST lanes), `engine.ts` (the bridge: snapshot load/memoize), `library.ts` (the snapshot model + derivations), `ids.ts` (the stable ID scheme), `normalize.ts` (feishin entity shaping), `query.ts` (local pagination/sorting/filter/search), `derive.ts` (top/similar/radio/random), `covers.ts` (the image URL lane). Decisions: `decisions/p1/`.
- **Why:** the UI's per-server-type gating hides affordances behind missing endpoints, so a registered controller with real reads lights up the browse surface one endpoint set at a time. Reads are local-first against the snapshot; the load uses only the cloud lanes the shipping web app already issues (no new Supabase round-trips).
- **Expected conflict surface:** none - new directory upstream never touches. The controller file is shared with A (auth seam); per-method edits keep conflicts small.

### 3a. `settings.js.template` - Streamify deployment variables (pre-approved touch, F0 list)

- **What:** appended `STREAMIFY_SUPABASE_URL`, `STREAMIFY_SUPABASE_ANON_KEY`, `STREAMIFY_DEV_ACCESS_TOKEN` (all default to the unsubstituted `${...}` placeholder, which the session seam reads as unset).
- **Why:** the P1 session seam's dev fallback (the "mock session" the reads are built against until A's Google flow lands); documented in `decisions/p1/session-and-request-interface.md`. The Dockerfile is untouched: nginx envsubst substitutes what the environment defines, and unset placeholders read as unset (same behavior as upstream's `FS_*` variables).
- **Expected conflict surface:** low - append-only at file end.

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

- `src/renderer/router/app-outlet.tsx` / `src/renderer/router/routes.ts` — P1 auth gate (redirect Streamify sessions to our auth flow). **Exercised in P1** (see carried patches 8–9).
- `package.json` — adding our dependencies. **Exercised in P1** (`@supabase/supabase-js`, patch 11).
- `settings.js.template` — Streamify deployment variables. **Exercised in P1** (patch 12).

## Carried patches (P1 — auth + session)

### 8. `src/renderer/router/routes.ts` — `AppRoute` enum (pre-approved)

- **What:** added `STREAMIFY_AUTH = '/streamify-auth'` to the `AppRoute` enum.
- **Why:** route constant for the Streamify auth page (our module, patch 10).
- **Expected conflict surface:** low — one additive enum member.

### 9. `src/renderer/router/app-outlet.tsx` — thin auth gate (pre-approved)

- **What:** one import of our `useStreamifyAuthGate` hook + two early-return branches (REDIRECT → `Navigate` to `/streamify-auth`, CHECKING → spinner) placed before the stock `isActionsRequired` check.
- **Why:** unauthenticated Streamify visitors are redirected to the auth route; authenticated ones pass straight into the app. All logic lives in the hook's own module (`src/renderer/features/streamify-auth/hooks/use-streamify-auth-gate.ts`); stock deployments short-circuit to PASS with no side effects, so upstream changes to the outlet body merge against an untouched stock path.
- **Expected conflict surface:** low — the stock body below the gate is byte-identical to upstream.

### 10. `src/renderer/router/app-router.tsx` — route registration (not pre-approved; minimal)

- **What:** one lazy import + one `<Route element={<StreamifyAuthRoute />} path={AppRoute.STREAMIFY_AUTH} />` mounted next to `LOGIN` in the shell group (outside `AppOutlet`, so the gate never blocks the auth page itself).
- **Why:** a route cannot exist without registration; the page component itself is entirely ours (`src/renderer/features/streamify-auth/routes/streamify-auth-route.tsx`).
- **Expected conflict surface:** low — two additive lines; conflicts only if upstream restructures the route tree wholesale.

### 11. `package.json` — `@supabase/supabase-js` (pre-approved)

- **What:** added `"@supabase/supabase-js": "^2.119.0"` to dependencies (lockfile regenerated).
- **Why:** the Supabase JS SDK backs the Streamify session (Google OAuth, token refresh, persisted sessions).
- **Expected conflict surface:** low — one dependency line.

### 12. `settings.js.template` — Streamify runtime knobs (pre-approved)

- **What:** appended `window.STREAMIFY_AUTH`, `window.STREAMIFY_SUPABASE_URL`, `window.STREAMIFY_SUPABASE_ANON_KEY` assignments.
- **Why:** runtime configuration for the auth flow — values live in deployment configuration (Docker env → nginx envsubst), never in the tree. `STREAMIFY_AUTH=false` is the explicit opt-out for hybrid deployments.
- **Expected conflict surface:** low — appended block at end of file.

### 13. `Dockerfile` — ENV defaults for the new knobs (not pre-approved; minimal)

- **What:** one additive `ENV STREAMIFY_AUTH="" STREAMIFY_SUPABASE_URL="" STREAMIFY_SUPABASE_ANON_KEY=""` line.
- **Why:** nginx's envsubst only substitutes variables that are defined in the container environment; without the defaults, unsubstituted `${STREAMIFY_…}` literals would leak into settings.js as garbage strings.
- **Expected conflict surface:** low — one additive ENV line.

### 14. New directories (ours, no upstream conflict)

- `src/renderer/api/streamify/streamify-supabase.ts` — SDK client singleton (PKCE, persisted sessions, auto-refresh), the synthetic server entry upsert into feishin's auth store, clean sign-out.
- `src/renderer/api/streamify/streamify-request.ts` — authenticated request lane: Bearer + apikey headers, one refresh-and-retry on 401, then clean sign-out back to `/streamify-auth`.
- `src/renderer/api/streamify/streamify-globals.d.ts` — window typings for the runtime knobs.
- `src/renderer/api/streamify/streamify-controller.ts` — the P0 stub, now with a real auth trio (`getUserInfo` via the SDK, `getServerInfo` through the request lane, `authenticate` pointing stock form attempts at the auth page). The remaining stubs follow crash-proof semantics: Promise-typed endpoints reject asynchronously with `StreamifyNotImplementedError` (react-query surfaces honest error states), and the three sync-typed URL builders (`getImageUrl`, `getImageRequest`, `getDownloadUrl`) return empty values — render paths call those synchronously (e.g. the player-bar artwork while a queue from another server is active), and a synchronous throw there blanks the app (found in P1 verification).
- `src/renderer/features/streamify-auth/` — the auth route (Google sign-in, session restore, own-server escape hatch into the stock flow) and the gate hook.

## CI additions (ours)

- `.github/workflows/web-ci.yml` — web-scoped pipeline: `pnpm install` (Electron binary download skipped), `pnpm run lint` (typecheck node + web, eslint `--max-warnings=0`, stylelint), `pnpm run build:web`. Runs on push and pull_request. Upstream's own `test.yml` is left untouched and keeps running (it also commitlints our commit ranges).

## Upstream sync procedure (tags only, merge never rebase)

1. `git fetch upstream --tags`
2. `git merge v<next-tag>` on `main` (no rebase, no cherry-pick trains).
3. Resolve conflicts using this ledger as the map of our divergence.
4. `pnpm install && pnpm run lint && pnpm run build:web` must be green before the merge lands.
5. Vendored copy in the streamify monorepo is updated from this fork only (see `front-end/decisions/feishin-adoption/F1-vendoring-strategy.md`).
