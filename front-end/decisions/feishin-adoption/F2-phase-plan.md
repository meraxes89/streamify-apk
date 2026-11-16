# F2 — Feishin adoption phase plan

Status: LIVING DOCUMENT — P0 complete, P1A (auth + session) complete, P1B (library browsing) next. P2–P5 are placeholders owned
by their future drivers; P0 filled in what the kickoff specified and marked
the rest TODO rather than inventing scope.
Owner: web UI track lead.

## P0 — Fork base (COMPLETE, 2026-11-15)

Deliverables (all landed on `meraxes89/streamify-feishin@main`, vendored to
`front-end/feishin/` on branch `feat/feishin-p0-fork-base`):

- Fork created from tag `v1.17.0`; `main` is our trunk; default branch set.
- One-time registration diff (ledgered in the fork's `PATCHES.md`):
  - `ServerType.STREAMIFY` in **both** enums (`domain-types.ts` API-side and
    `types.ts` UI-side — the duplication is upstream's, both had to carry the
    member).
  - `toServerType` learns `'streamify'` (else `SERVER_TYPE=streamify` falls
    back silently).
  - `streamify: StreamifyController` registered in the `ApiController` type +
    `endpoints` map.
  - `src/renderer/api/streamify/` — `StreamifyController` implements
    `InternalControllerEndpoint`: **all 53 required + 16 optional endpoints**
    throw a typed `StreamifyNotImplementedError` carrying the endpoint name.
    No logic.
  - Exhaustive `Record<ServerType, …>` entries forced three more touched
    files (`add-server-form.tsx` `SERVER_TYPES`; `login-route.tsx`
    `SERVER_ICONS` + `SERVER_NAMES`) plus a new `streamify.png` asset — all
    documented in PATCHES.md with conflict surfaces.
- `PATCHES.md` seeded at the fork root (carried patches, pre-approved future
  touch list, sync procedure).
- CI: fork carries upstream's `Test` (lint + commitlint) untouched, plus our
  `Web CI` (install with `ELECTRON_SKIP_BINARY_DOWNLOAD=1` → `pnpm run lint`
  → `pnpm run build:web`). Both green on `d12f99f6`.
- Stock web build verified end-to-end in-browser against a throwaway
  Navidrome: server add → login → browse → album → **play** (see
  `download/feishin-adoption-audit.md` for the evidence).
- `SERVER_TYPE=streamify` smoke test: app boots, selects Streamify, no crash.
- Vendoring: git subtree `--squash` (F1).

## P1A — Auth + session (COMPLETE, 2026-11-16; fork PR #1, monorepo PR #182)

- `/streamify-auth` route (our module) + thin app-outlet gate; Supabase Google
  OAuth via the SDK; the synthetic `ServerType.STREAMIFY` entry persisted
  through feishin's own server store; the auth endpoint trio real in the
  streamify controller; the 401 refresh-and-retry request lane; runtime knobs
  in settings.js.template + one Dockerfile ENV line.
- Design, gate truth table, verification evidence, and the P1B handoff notes:
  `front-end/decisions/p1/P1A-auth-and-session.md`. Upstream diffs: the fork's
  `PATCHES.md` patches 8–14.

## P1B — Library browsing (NEXT; consumes P1A's session + request lane)

- Make the Streamify controller endpoints real, starting with the auth pair
  (`authenticate`, `getUserInfo`, `getServerInfo`) and the library read set
  (`getMusicFolderList`, `getAlbumList`/`getAlbumListCount`,
  `getAlbumArtistList`/`Count`, `getSongList`/`getSongListCount`,
  `getAlbumDetail`, `getSongDetail`, `getGenreList`, `getRandomSongList`,
  `search`), backed by the Streamify engine facade
  (`front-end/web/src/lib/engine.ts` bridge contract).
- Auth gate: pre-approved touch of `app-outlet.tsx`/`routes.ts` to route
  Streamify sessions through our auth flow.
- Conventional commits; stub → real, one endpoint at a time; PATCHES.md
  entries only change when upstream files change.

## P2–P5 — TODO (owned by future drivers; do not invent scope here)

- P2: playback surface against the engine (streaming, queue semantics,
  `getStreamUrl`/`getDownloadUrl`, scrobble) — TODO.
- P3: playlists/favorites/radio mapping onto facade capabilities — TODO.
- P4: lyrics/EQ/visualizer features gated on facade breadth (EQ DSP golden
  vectors must stay green for any DSP path) — TODO.
- P5: cutover decision (feishin line replaces `front-end/web` as the shipped
  surface; web-deploy lane moves) — TODO.

## Cross-cutting rules

- Engine-level gaps land in `rust/` (shared by all four runtimes); the
  controller only exposes. Never work around engine gaps in the facade.
- All JS↔wasm calls go through `front-end/web/src/lib/engine.ts` — the
  feishin controller will use the same bridge module, moved/re-exported as
  needed in new directories only.
- No fake data: honest empty states until endpoints are real (P2 discipline,
  carried over from the ws-w line).
