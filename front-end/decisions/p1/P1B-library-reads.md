# P1B — Feishin library reads (Engineer B)

**Status: COMPLETE** — 2026-11-16. Fork PR [streamify-feishin#2](https://github.com/meraxes89/streamify-feishin/pull/2)
(delivered to the fork repo; orchestrator merged it into fork main as `5dea4561`,
resolving the conflicts against P1A, then vendored here). CI green on the PR
(commitlint on both commits, `Test` lint, `Web CI` install → lint → `build:web`).
Local merge verification on this tree: typecheck node+web, eslint
`--max-warnings=0`, stylelint, and the web bundle build (F1 fallback flags on a
constrained box) all pass.

## Correction applied (why this landed as a merge, not a fast-forward)

Engineer B delivered PR #2 to the **fork** (`streamify-feishin`) instead of the
monorepo (`streamify-apk`). The work itself was correct and is kept in full. The
fix: the PR branch was merged into fork `main` with conflicts resolved by the
orchestrator, then the fork head was vendored into the monorepo here per F1.
Rule going forward, recorded for P2+: **all PRs targeting this phase line open
against `meraxes89/streamify-apk`; the fork only receives work via its own
coordinated PRs when the change is fork-infrastructural** (PATCHES.md-level).
P1B's content lives in fork-tracked paths under `front-end/feishin/`, so the
monorepo PR is the vendoring commit — the same shape P0 and P1A used.

## Scope (as kicked off)

Library read endpoints made real in the Streamify controller, backed by the
Streamify engine bridge (local-first snapshot, zero new round-trips), plus
thumbnails and honest-empty semantics for data that does not exist yet.
Nothing plays — `getStreamUrl` stays a stub for P2.

## What landed (15 files, +2748/−95 in the vendored tree)

| Piece | Role |
|---|---|
| `api/streamify/engine.ts` | the bridge contract: loads one library snapshot per (server, credential); every read resolves in memory |
| `api/streamify/cloud.ts` | Supabase REST lanes mirroring the web app's existing queries exactly (songs, liked ids, play events, playlists) — no new round-trips |
| `api/streamify/ids.ts` | ID scheme byte-compatible with Android/Web `trk_<|javaHashCode|>` derivation, so favorites/stats stay consistent across platforms |
| `api/streamify/library.ts` | snapshot assembly: rows to maps, play-event joins (by track id — see below), bucket derivations |
| `api/streamify/normalize.ts` | snapshot rows → feishin domain entities (Song/Album/Artist/Playlist), `imageId` set on all entities (the dual-lane image approach Navidrome/Subsonic use) |
| `api/streamify/query.ts` | local pagination/sorting/filter/search engine (every sort enum member has a comparator) |
| `api/streamify/derive.ts` | top/similar/radio/random from real play stats, with documented honest fallbacks |
| `api/streamify/covers.ts` | cover URL resolution + cache keys for `getImageUrl`/`getImageRequest` (sync-typed, so crash-proof by construction) |
| `api/streamify/session.ts` | session contract + credential payload codec proposal for A's daily-interface review (dev fallback via `STREAMIFY_DEV_ACCESS_TOKEN`) |
| controller | 24 read endpoints real; honest-empty for genres/ratings/years/folders/radio; honest no-ops for `refreshItems`/`startLibraryScan`/`getScanStatus` |

## Merge resolution (orchestrator rulings, for the record)

B's branch predated P1A's merge, so the PR conflicted on the shared controller
and settings template. Rulings: A's auth seam wins (`authenticate`, `getUserInfo`
with auth-rejection classification + clean sign-out); B's read surface wins
outright; `getServerInfo` is a fusion (B's capability surface + A's request-lane
probe so the 401-refresh-retry lane still exercises on boot); settings template
is a union (A's comment block + all four STREAMIFY knobs including B's dev
token); unused imports from the losing sides dropped.

## Correctness notes worth keeping

- Play events join by **track id** (`cloudTrackId(title, artist)` is what the
  web AppShell records as `track_sig`), not the raw signature string.
- DESC recently-played sorts never-played rows **last** (caught in B's sanity
  run, kept).
- Album/artist grouping keys are order-robust (`max(created_at)` not row order).
- `getImageUrl`/`getImageRequest` stay sync-typed and null-safe: render paths
  call them synchronously and a throw blanks the app (P1A's lesson, preserved).

## Known gaps deferred (B's `decisions/p1/facade-gaps.md`)

- wasm library-query surface in the facade (currently REST-snapshot backed).
- jam queue read projection for playlists (P3 will need it).
- OPFS cover cache cannot ride feishin's sync `ImageRequest` contract without a
  service-worker path — outside the pre-approved touch list for P1.

## P1 exit status

Phase exit criteria met on the merged tree: sign in with Google (P1A), browse
the catalog in feishin's UI (P1B: albums, artists, songs, playlists, search,
top/similar with play-stat derivations, thumbnails via the imageId lane).
Playback remains stubbed — that is P2.
