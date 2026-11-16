# PATCHES.md — the upstream divergence ledger (per-file conflict map)

This fork (`streamify-feishin`, vendored at `front-end/feishin/`) tracks
`jeffvli/feishin`. Upstream moves fast (~180 commits/month); we track release
**tags only** (monthly, stable) and **merge, never rebase**. This file is the
complete, honest ledger of every change we carry on top of upstream, organized
**per file** — one entry per upstream file we touch, plus the fork-owned
directory inventory — so the next tag merge has a single authoritative map of
the expected conflict surfaces.

**P5-A consolidation note (2026-11):** this file was rewritten from the
append-only per-patch history into the per-file map. Three historical
discrepancies found by the P5 audit are corrected here, not papered over:

1. **Patch 3a phasing** — the `settings.js.template` Streamify knobs were
   attributed to P0; they actually landed in P1 (#182: `STREAMIFY_AUTH`,
   `STREAMIFY_SUPABASE_URL`, `STREAMIFY_SUPABASE_ANON_KEY`) and P1B (#184:
   `STREAMIFY_DEV_ACCESS_TOKEN`). P0 vendored the template untouched.
2. **Patch 20 vs Dockerfile:28** — the ledger described the P2-A
   `STREAMIFY_RELAY_BASE` append as a clean one-line extension. The actual
   #186 edit dropped the closing quote on `STREAMIFY_SUPABASE_ANON_KEY`,
   collapsing the whole ENV line into one envsubst token; the bug hid until
   the P5 audit (the session seam's placeholder filter masked it as "unset").
   Fixed in P5-A, together with the never-defined `STREAMIFY_DEV_ACCESS_TOKEN`
   ENV the template has referenced since P1B.
3. **Missing P3-A entries** — #190 (jam CRDT playlist backing) touched zero
   upstream files and never appended its fork-owned additions to this ledger.
   They are inventoried in section B below.

## Policy

- New Streamify work goes in **new directories only** (e.g.
  `src/renderer/api/streamify/`, `src/renderer/features/streamify-*/`). New
  features never re-open upstream files except through the minimal seams
  recorded in section A.
- Never touch: `src/renderer/features/login/**`,
  `src/renderer/features/servers/**` (beyond the frozen registration lines
  recorded below), `src/renderer/components/**`, themes.
- The fork stays GPL-3.0 with source available.
- Secrets live in deployment configuration, never in the tree.
- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `ci:`, …) are
  enforced by CI (commitlint) on PR commit ranges and pushes.

## Upstream baselines

| ref | SHA | note |
| --- | --- | --- |
| `v1.17.0` (tag) | `5c169aca8d55` | the P0 fork point |
| `development` (branch) | `e36c5a95` | last observed upstream head |
| `v1.18.0` | — | **not tagged yet** as of P5-A; the next merge target |

---

## A. Upstream files we carry changes in

Every edit to an upstream file is listed here — the exact conflict map for the
next tag merge. Phase attributions: P0 (#181), P1 (#182), P1B (#184), P2-A
(#185/#186), P2-B (#187), P3-A (#190), P3-B (#191), P4-A (#192), P4-B (#193),
P5-A (#195), W1-A (this PR — the post-cutover waves begin).

### A1. `package.json`

- **What:** dependencies `@supabase/supabase-js ^2.119.0` (P1),
  `youtubei.js 18.3.0` (exact pin — the resolver requires the same pin the
  web app used), `googlevideo ^4.0.4`, `bgutils-js ^4.0.3` (P2-A);
  devDependency `vitest ^3.2.0` (P2-A); scripts `test` (P2-A) and
  `test:corpus` (P5-A — the daily SABR corpus lane, config
  `vitest.corpus.config.ts`).
- **Conflict surface:** low — dependency lines + two scripts.

### A2. `pnpm-lock.yaml`

- **What:** regenerated with every dependency change (P1, P2-A).
- **Conflict surface:** mechanical — regenerate on merge, never hand-edit.

### A3. `vitest.config.ts` (fork-added file, upstream has none)

- **What:** the fork's network-free test lane (`environment: node`, include
  `src/**/*.test.ts`, the `/@/` aliases). P5-A added the
  `@streamify/canary` alias + `resolve.dedupe ['react', 'react-dom']`;
  P5-C retargeted the alias to the vendored in-tree copy
  (`src/renderer/features/streamify-canary/`) and retired the dedupe +
  react pinning (in-tree sources resolve the ordinary way — the
  outside-the-root machinery existed only for the retired
  `front-end/canary` sibling).
- **Conflict surface:** none — fork-owned file, but listed because its
  aliases must mirror `web.vite.config.ts` (A24).

### A4. `settings.js.template`

- **What:** appended the Streamify runtime knobs —
  `window.STREAMIFY_AUTH` / `STREAMIFY_SUPABASE_URL` /
  `STREAMIFY_SUPABASE_ANON_KEY` (P1, #182), `STREAMIFY_DEV_ACCESS_TOKEN`
  (P1B, #184), `STREAMIFY_RELAY_BASE` (P2-A, #186). Values arrive via
  deployment configuration (Docker env → nginx envsubst); unsubstituted
  `${...}` placeholders read as unset (session.ts + relayBase.ts filters).
- **Conflict surface:** low — append-only block at file end.

### A5. `Dockerfile`

- **What:** one ENV line so nginx envsubst substitutes the Streamify
  settings.js knobs even when the deployment leaves them empty. Added clean
  in P1 (#182); **broken between #186 and P5-A** (the P2-A RELAY_BASE append
  dropped the `STREAMIFY_SUPABASE_ANON_KEY` closing quote — see the P5-A
  note at the top); P5-A repaired it and added the missing
  `STREAMIFY_DEV_ACCESS_TOKEN=""`. Current line:
  `ENV STREAMIFY_AUTH="" STREAMIFY_SUPABASE_URL="" STREAMIFY_SUPABASE_ANON_KEY="" STREAMIFY_RELAY_BASE="" STREAMIFY_DEV_ACCESS_TOKEN=""`
- **Conflict surface:** low — one additive ENV line under the stock ENV
  block.

### A6. `docker-compose.yaml`

- **What:** P5-A cut the compose over from stock
  (`ghcr.io/jeffvli/feishin:latest`, zero Streamify env) to the fork:
  builds the local Dockerfile as `streamify-feishin:latest`, locks
  `SERVER_LOCK=true` / `SERVER_TYPE=streamify` / `SERVER_NAME=streamify`,
  sets `ANALYTICS_DISABLED=true`, and passes the five `STREAMIFY_*` knobs
  through from the host environment. Production runs via Docker since the
  Pages deployment was retired (P5-A).
- **Conflict surface:** low-medium — upstream rarely edits compose; ours is
  a full rewrite of the `environment:` block.

### A7. `eslint.config.mjs`

- **What:** added `src/renderer/api/streamify/playback/resolver` to the
  global ignores (P2-A). The resolver tree keeps the web app's 2-space
  style — lint the fork's own code; typecheck still covers it. P5-A:
  comment updated (the tree is canonical since the archive; integrity is
  enforced by `scripts/check_feishin_resolver.py` in ci.yml, replacing the
  retired mirror-sync gate).
- **Conflict surface:** low — one ignore entry.

### A8. `src/i18n/locales/en.json`

- **What:** additive sections: `error.streamify*` keys (P3-B, #191);
  `streamifyAuth.*` (P3-B — the auth route i18n landed with #191's edit of
  the auth route); `streamifyDownloads.*` / `streamifyWrapped.*` /
  `streamifyDebug.*` + `page.sidebar.{downloads,wrapped}` (P4-A, #192);
  `streamifyDebug.{panelCanary,canaryHint}` (P5-A). P4-B (#193) adds the
  `streamify` top-level group (jam/eq/settings strings). W1-A adds the
  `streamifyImport.*` group (the 1-Click YTM importer modal). Other
  locales fall back to `en` (the fork's i18n policy — W1-A keeps it
  en.json-only; check-i18n.mjs stays green by construction).
- **Conflict surface:** none — append-only at file end (plus keys inside
  the alphabetically-adjacent `page.sidebar` block).

### A9. `src/renderer/router/routes.ts`

- **What:** `AppRoute` enum members: `STREAMIFY_AUTH` (P1),
  `STREAMIFY_DOWNLOADS` / `STREAMIFY_WRAPPED` / `STREAMIFY_DEBUG` (P4-A),
  `STREAMIFY_SETTINGS` (P4-B, #193).
- **Conflict surface:** low — additive enum members.

### A10. `src/renderer/router/app-router.tsx`

- **What:** route registrations: one lazy import + one `<Route>` for the
  auth page (P1, mounted in the shell group outside `AppOutlet` so the gate
  never blocks it); three lazy imports + `<Route>`s for downloads / wrapped
  / debug inside the authenticated `ResponsiveLayout` group (P4-A); one for
  the Streamify settings page (P4-B).
- **Conflict surface:** low — additive lines at stable anchors; conflicts
  only if upstream restructures the route tree wholesale.

### A11. `src/renderer/router/app-outlet.tsx`

- **What:** the thin auth gate (P1): one import of our
  `useStreamifyAuthGate` hook + two early-return branches (REDIRECT →
  `Navigate` to `/streamify-auth`, CHECKING → spinner) placed before the
  stock `isActionsRequired` check. Stock deployments short-circuit to PASS.
- **Conflict surface:** low — the stock body below the gate is
  byte-identical to upstream.

### A12. `src/renderer/features/player/audio-player/web-player.tsx`

- **What:** three thin seams. (P2-A) the error claim — one
  `handleOnSourceError` callback + `onSourceError` prop pass-through, and
  one block inside `onPlayerSeekToTimestamp` letting the playback layer
  claim non-range seeks; (P4-B, #193) the EQ attach seam —
  `source.connect(gains[i])` became `connectStreamifySource(webAudio,
  source, i)` in both player-start handlers (the EQ watchdog's hard-bypass
  guardrail). Unclaimed paths stay byte-identical.
- **Conflict surface:** low — additive lines inside three handlers.

### A13. `src/renderer/features/player/audio-player/engine/web-player-engine.tsx`

- **What:** (P2-A) one optional `onSourceError?: (err, audio) => boolean`
  prop and one claim block at the head of `handleOnError`'s inner handler;
  claimed errors return early, unclaimed run the stock retry/skip logic.
- **Conflict surface:** low.

### A14. `src/renderer/features/player/components/right-controls.tsx` (P4-B, #193)

- **What:** one import + one `<StreamifyJamButton />` element at the head of
  the right-controls button row (the Jam drawer / EQ panel trigger;
  renders `null` on stock servers).
- **Conflict surface:** low — additive element in a stable row.

### A15. `src/renderer/features/player/utils/get-visualizer-audio-nodes.ts` (P4-B, #193)

- **What:** for WEB playback the tap returns `[webAudio.dsp.compressor]`
  (the EQ DSP chain output) when a chain is attached, falling back to
  `webAudio.gains` otherwise; the LOCAL (mpv loopback) path is untouched.
- **Conflict surface:** low — additive branch before the stock return.

### A16. `src/renderer/features/shared/mutations/favorite-optimistic-updates.ts`

- **What:** (P3-B, #191) three fixes to the favorite optimistic-update
  helpers: the SONG-case immediate path also updates the
  `albumArtists.favoriteSongs` cache (an unfavorite removes the song
  instantly instead of waiting for the refetch); fixed the pre-existing
  `homeQueries.concat(homeQueries)` double-registration bug; the deferred
  variant gains `favorite-songs` and `home-songs` collection lanes for
  parity. Behavior for other server types is strictly additive.
- **Conflict surface:** low — additive blocks inside the existing SONG-case
  switch arms; upstream rarely edits this file.

### A17. `src/renderer/store/settings.store.ts` (P4-A, #192)

- **What:** two entries in the default `sidebarItems` array (`Downloads`,
  `Wrapped`), a `version: 35` bump, and an `if (version < 35)` migration
  pushing the same two entries into existing users' persisted sidebar (the
  fork's own v10 Favorites precedent, verbatim).
- **Conflict surface:** low-medium — migration ladder is append-at-end;
  upstream sidebar churn lands in the default array.

### A18. `src/renderer/features/sidebar/components/{sidebar,collapsed-sidebar,mobile-sidebar}.tsx` + `sidebar-icon.tsx` (P4-A, #192)

- **What:** `Downloads:` / `Wrapped:` keys in the three
  `translatedSidebarItemMap` copies (the established triplication), plus
  `RiDownload2*` / `RiSparkling*` icon imports and two
  `case AppRoute.STREAMIFY_*` entries in `sidebar-icon.tsx`.
- **Conflict surface:** low — one line per map; icon cases append at a
  stable switch.

### A19. `src/renderer/features/context-menu/menus/{song,album,playlist-song,playlist}-context-menu.tsx` (P4-A, #192)

- **What:** one `<StreamifyDownloadAction ids={ids} itemType={…} />` line +
  one import per menu. The action component lives in our tree
  (`features/streamify-downloads/components/download-action.tsx`) and
  self-hides on non-Streamify servers — the stock `DownloadAction` is
  untouched.
- **Conflict surface:** low — single lines at stable anchors.

### A20. `src/shared/types/domain-types.ts`

- **What:** (P0) added `STREAMIFY = 'streamify'` to the `ServerType` enum.
- **Conflict surface:** low.

### A21. `src/shared/types/types.ts`

- **What:** (P0) added `STREAMIFY = 'streamify'` to the UI-facing
  `ServerType` enum (this file declares its own copy) and the
  `case ServerType.STREAMIFY: return ServerType.STREAMIFY;` arm in
  `toServerType` — without it `SERVER_TYPE=streamify` resolves to `null`.
- **Conflict surface:** low — purely additive.

### A22. `src/renderer/api/controller.ts`

- **What:** (P0) `streamify: InternalControllerEndpoint` on the
  `ApiController` type + `streamify: StreamifyController` in the
  `endpoints` map (+ import).
- **Conflict surface:** low — registration block is stable.

### A23. `src/renderer/features/servers/components/add-server-form.tsx` + `src/renderer/features/login/routes/login-route.tsx`

- **What:** (P0) the `[ServerType.STREAMIFY]` entries in the exhaustive
  records (`SERVER_TYPES` in the form; `SERVER_ICONS` / `SERVER_NAMES` in
  the login route) + the `streamify.png` asset import. These lines are
  **frozen** — they exist only to satisfy typecheck exhaustiveness and
  render the type in the pickers.
- **Conflict surface:** low.

### A24. `web.vite.config.ts` (P5-A)

- **What:** `resolve.alias['@streamify/canary']` → the vendored in-tree
  copy `src/renderer/features/streamify-canary/` (P5-C; the P5-A wiring
  pointed at the `../canary/src` workspace sibling, which broke the fork
  repo and the Docker build context — both ship `front-end/feishin`
  alone). The P5-A `resolve.dedupe` + per-subpath react pinning are
  retired with the sibling (in-tree resolution needs neither).
  W1: `base: process.env.VITE_BASE_PATH || './'` — the GitHub Pages
  workflow (feishin-pages.yml) sets `VITE_BASE_PATH=/streamify-apk/`
  (plus the `--base` CLI flag) for the project-site subpath; every other
  consumer (Docker at `/`, electron) keeps the relative default.
- **Conflict surface:** low — additive entries in the resolve block; the
  base line swaps a literal for an env-conditional with the same default.

### A25. `tsconfig.web.json` (P5-A)

- **What:** `paths` entry: `@streamify/canary/*` →
  `src/renderer/features/streamify-canary/*` (P5-C retarget; the P5-A
  `../canary/src/*` mapping and the `react` / `react/jsx-runtime` @types
  pinning existed only for the retired outside-the-root sibling — the
  vendored tree sits under `src/renderer/**` and is covered by the stock
  include set).
- **Conflict surface:** low — additive paths entries.

### A26. `src/renderer/features/playlists/components/playlist-list-header.tsx` (W1-A)

- **What:** the 1-Click YTM importer trigger — one import line
  (`StreamifyYtmImportButton` from the fork's
  `features/streamify/import/components/ytm-import-button.tsx`) + one
  `<StreamifyYtmImportButton />` element inside the existing search-input
  `Group`. The component self-hides on non-Streamify servers (the
  StreamifyDownloadAction pattern, verbatim); the modal, progress state
  and abort wiring all live in the fork-owned import tree.
- **Conflict surface:** low — two additive lines at stable anchors;
  upstream rarely edits this header.

---

## B. Fork-owned additions (no upstream conflict)

Upstream never touches these; they are inventoried here so the merge ritual
can ignore them and audits can hold us to the directory-isolation rule.

### `src/renderer/api/streamify/` — the controller + domain layer

- `streamify-controller.ts` — `InternalControllerEndpoint` implementation.
  P0 stub (every method throwing `StreamifyNotImplementedError`) → P1
  library reads (24 endpoints, local-first snapshot bridge) → P1 auth trio
  → P2-B/P3-B write lanes + bridged endpoints (favorites drain, play-queue
  drain, scrobble with `resolveStreamifySessionAsync` — the OAuth glue
  commit) → P4-A download endpoints → P4-B (#193) the scrobble toggle gate
  (the cloud-send adapter's `canSend` also requires the persisted
  `scrobbleEnabled` setting; local bookkeeping keeps running and the
  outbox drains on re-enable).
- `session.ts` — the credential codec + dev session seam + the
  placeholder-filtering window reads. `streamify-supabase.ts` — the SDK
  client singleton (PKCE, persisted sessions, synthetic server entry).
  `streamify-request.ts` — the authenticated request lane (Bearer + apikey,
  refresh-and-retry on 401). `streamify-globals.d.ts`, `ids.ts`,
  `streamify-server-id.ts`.
- `cloud.ts` / `library.ts` / `engine.ts` / `query.ts` / `derive.ts` /
  `normalize.ts` / `covers.ts` / `favorites.ts` — the snapshot model,
  cloud lanes, local pagination/sorting/filter/search, derivations, the
  favorites (cloud likes) flow with the offline journal. W1-A adds
  `upsertCatalogTracks` to cloud.ts (the importer's catalog lane: the
  shared `tracks` rows the CRDT sync pass's `playlist_tracks` inserts are
  FK-bound to — merge-duplicates, NO like edge).
- **`playback/` — the playback layer.** `stream-source.ts` (the consumption
  layer: cached-ladder walk, stale re-resolve, seek strategy, the P4-A
  zero-egress OPFS bypass), `scrobbler.ts` + `scrobble-bridge.ts` +
  `play-tally.ts` + `queue-persistence.ts` (the #187 playback-spine ports).
  **`playback/resolver/` — THE CANONICAL RESOLVER TREE since P5-A** (10
  files originally vendored byte-identically from front-end/web + the
  fork-authored `lib/relayBase.ts`). relayBase carries the P4-B priority-0
  user override (`streamify.settings.v1` localStorage read + the exported
  `resetRelayBaseCache()` the settings panel calls) and the P5-A
  unexpanded-placeholder guard on every source. The sync script and mirror
  gate are retired; integrity is enforced by
  `scripts/check_feishin_resolver.py` (ci.yml preflight) and the tree is
  exercised live by the daily SABR corpus
  (`scripts/sabr-corpus.test.ts`).
- **`jam/` + `playlist-sync/` (P3-A, #190 — the entries the old ledger
  missed):** `jam/{jam-op,playlist-crdt,fractional-index,cad}.ts` — the
  CRDT codec (UUID v4/variant-masked packed row ids), the fractional-index
  ordering, the conflict-aware cadence hasher; `playlist-sync/{sync,wire,
  postgrest,realtime,persistence,outbox,element-id,invalidate}.ts` — the
  dual-replica convergence engine, the PostgREST/realtime transports, the
  synchronous-persistence ordering, the offline op-log. Store:
  `store/streamify-playlists.store.ts`.
- **`jam-live/` (P4-B, #193):** the listening-room lane — thin re-exports
  of P3-A's `jam/` codec + fractional index + cad hasher (one frozen
  semantic, one implementation, pinned by golden-vector tests) plus the
  listening-room additions: the `jam_ops` journal-row normalizer, the queue
  CRDT fold, the host/follower sync protocol (pure), the supabase-js
  transport, the session controller, the player-store bridge.
- **`downloads/` + `wasm/` + `wrapped/` (P4-A, #192):** the OPFS chunk-
  cipher download pipeline (`cipher`/`format`/`meta`/`opfs`/
  `download-manager`/`instance`), the vendored cipher artifact
  (`streamify_cipher_bg.wasm` + `artifact.json` manifest + the hand-written
  `cipher-loader.ts`), and the wrapped-stats ledger + aggregates.
- **`metadata/` (W1-A):** the provider seam — `types.ts` (the typed
  payload family + `MetadataProvider` interface, mirrored from the relay
  worker's schemas), `edge.ts` (the /ytm/meta client with abort-timeout
  composition), `innertube-fallback.ts` (the client-side youtubei.js lane
  riding `getYt()`; token-bridge continuation registry), `normalize.ts`
  (the per-payload schema validators), `snapshot.ts` (the in-memory
  last-known-good + 15-min negative cache), `provider.ts` (the pipeline:
  snapshot → edge (backoff 250 ms doubling ×3, 60 s circuit) → fallback →
  snapshot upsert; 404/429/parse-error typed separation),
  `lyrics-text.ts` (the LRC-line parser, mirrored from the worker).
- **`features/streamify/import/` (W1-A):** the 1-Click importer — the
  nested-namespace rule honored (the first non-flat `features/streamify/`
  entry): `url-parser.ts` (pure; PL… / OLAK5uy_… / RDCLAK5uy_… / VL… raw
  ids + the youtube.com list=/browse forms), `ingest.ts` (batched
  continuation ingestion: catalog upsert FIRST, then CRDT ADD ops, live
  progress, AbortSignal between pages; landed batches stay on cancel),
  `components/ytm-import-modal.tsx` + `ytm-import-button.tsx`,
  `hooks/use-ytm-import.ts`.
- `__tests__/` — the network-free vitest suites (queue round-trip, scrobble
  semantics, favorites, curation, playlist convergence, downloads
  cipher/lifecycle, offline bypass, wrapped aggregation, the /debug gate,
  the P5-A locks: relayBase placeholders, the corpus runner integrity,
  the canary panel mount; the W1-A locks: `metadata-provider.test.ts`
  (404/429/parse separation, snapshot upsert, synced preservation) and
  `ytm-importer.test.ts` (URL parser table, batched continuations, abort
  semantics, CRDT materialization against the real sync engine).

### `src/renderer/features/streamify-*/` — the UI surfaces

- `streamify-auth/` (P1) — the Google OAuth route + the auth gate hook.
- `streamify-downloads/` / `streamify-wrapped/` / `streamify-debug/`
  (P4-A) — the downloads manager, the wrapped stats page, the gated /debug
  diagnostics console. P5-A added `streamify-debug/components/
  canary-panel.tsx` — the residential canary console mount (wired to the
  active Supabase session + the resolver's relay knob).
- `streamify-canary/` (P5-C vendoring) — the W0.1b residential canary
  console source, previously the `front-end/canary` workspace sibling and
  now in-tree (`matrix.ts`, `probeMatrix.ts`, `postResults.ts`,
  `relayFetch.ts`, `types.ts`, `ui/CanaryConsole.tsx` + `ui/canary.css`);
  the monorepo workspace is retired. Brought to the fork's lint style
  (eslint/stylelint `--fix` + apostrophe escapes); the SQL migration's
  source of truth stays `supabase/migrations/`.
- `streamify-jam/` / `streamify-eq/` / `streamify-settings/` (P4-B, #193) —
  the Jam live-queue drawer, the EQ DSP panel, the Streamify settings pages
  + brand theme.

### `src/renderer/store/streamify-*.store.ts`

- `streamify-playlists.store.ts` (P3-A), `streamify-downloads.store.ts` /
  `streamify-debug.store.ts` (P4-A), `streamify-jam.store.ts` /
  `streamify-eq.store.ts` / `streamify-settings.store.ts` (P4-B) — the
  deliberately-dumb state slices with hand-rolled localStorage lanes (no
  store-barrel exports; node-testable).

### Other fork-owned files

- `src/renderer/features/servers/assets/streamify.png` (P0 — the
  registration icon).
- `vitest.corpus.config.ts` + `scripts/sabr-corpus.test.ts` (P5-A — the
  live corpus lane, CI-only via `pnpm run test:corpus`).
- `decisions/` (in the fork tree: `decisions/p1/`; in the monorepo:
  `front-end/decisions/p2..p5/`) — the ADRs.

---

## C. CI (monorepo side, ours)

- `feishin-ci.yml` — the F1-deferred monorepo gate for the vendored fork
  tree: pnpm install (Electron binary skipped) → lint (typecheck node+web,
  eslint `--max-warnings=0`, stylelint) → tests → `build:web`. Runs on
  `front-end/feishin/**` PRs and pushes.
- `ci.yml` preflight — `scripts/check_feishin_resolver.py` (P5-A): the
  canonical resolver integrity check (existence, provenance headers,
  self-contained imports, the getStreamUrl/recentResolveLog contract).
  Replaces the retired `resolver-mirror-sync` job.
- `web-build.yml` — wasm / wasm-tests (headless Chrome wasm-bindgen-test) /
  relay jobs only (P5-A): the legacy web Vite build job and the
  `front-end/web/**` triggers are retired with the archive.
- `resolver-canary.yml` — the daily SABR corpus runs from the feishin fork
  (`pnpm run test:corpus` in `front-end/feishin`, P5-A) against the
  canonical resolver tree.
- `web-build.yml` relay job (W1-A): runs the relay's network-free vitest
  lane (`npm test` in `front-end/relay`) before the wrangler dry-run —
  the ytm-meta cache lock (key encoding, TTL matrix, synced-flag
  preservation, single-flight, KV-absent fallback).
- **Retired with P5-A:** `web-deploy.yml` (GitHub Pages — production runs
  via Docker), `scripts/sync_feishin_resolver.py` + the
  `resolver-mirror-sync` job (the mirror became canonical), the `web` job
  in `web-build.yml`, and the `web` member of `front-end/package.json`
  workspaces (archived to `legacy/front-end-web`, excluded from linters,
  TS projects and codeql paths).

---

## D. Upstream merge ritual (two-level, fork-first)

The vendored tree is updated from the fork repo only; summary here, full
procedure in
`front-end/decisions/p5/P5A-cutover-legacy-archive-upstream-ritual.md`:

1. **Level 1 — the fork repo (`meraxes89/streamify-feishin`):** three-way
   `git merge v<next-tag>` on the fork's `main` (merge, never rebase);
   resolve conflicts using section A of this file as the map; the fork's
   own CI (lint + tests + `build:web`) must be green before the merge
   lands.
2. **Level 2 — this monorepo (`streamify-apk`):** a single-commit vendor
   replace — `git read-tree --prefix=front-end/feishin/ -u <fork-merge-
   commit>` (or the equivalent subtree pull), so the vendored tree advances
   one squash commit per upstream tag with no interleaved history. Update
   this ledger in the same commit.

Upstream `v1.18.0` is not tagged yet (baselines above); when it lands, run
the ritual against it.
