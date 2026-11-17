# W2-B — Song Credits & Liner Notes, 500 MB OPFS Smart Sync & the playTally-Seeded Discover Mix

**Status:** Proposed (Wave 2-B)
**Branch:** `ws-w/w2b-credits-smartsync-discover`
**Date:** 2026-12 (Wave 1 in merge review: #198 [W1-A], #200 [W1-B])
**Owner:** Engineer B (Wave 2-B)

## Context

Wave 1 lands the Edge KV MetadataProvider spine (#198) and the deep catalog
wiring (#200); Wave 2 splits along the zero-overlap boundary — Engineer A
owns the video lane (W2-A: `metaBase.ts`, `{ mode: 'video' }` resolution,
`features/streamify/video/`, `getChapters`), W2-B (this ADR) owns the
audio-side features that consume the Wave 1 seam:

1. **Song Credits & Liner Notes** — `provider.getCredits(videoId, hl?)`
   over the relay's `/ytm/meta?endpoint=credits` normalizer (7-day Edge KV
   TTL, shipped by W1-A), surfaced as a Liner Notes drawer.
2. **500 MB OPFS Smart Sync** — N+1/N+2 predictive pre-fetch into P4-A's
   XChaCha20-Poly1305 encrypted OPFS store (#192), under a strict LRU
   ceiling, a session circuit breaker, and metered-connection awareness.
3. **playTally-Seeded Discover Mix** — a personalized, refreshable Home
   shelf generated from the #191/#192 listening ledger through
   `getUpNext` / `getRelated` continuations.

## A. Song Credits & Liner Notes (`features/streamify/credits/`)

### The data lane

- **Relay:** zero new code. W1-A's `ytm-meta.ts` already normalizes the
  WEB `/next` structured-description engagement panel into
  `CreditsPayload.roleGroups` (`Performed by` / `Written by` /
  `Produced by` / `Source`…) with the locked **7-day TTL (604800 s)** and
  the SWR rule "background-revalidate only when the payload is
  empty/missing" (a complete credits shelf is terminal). This wave keeps
  its relay footprint at zero so it merges cleanly alongside Engineer A's
  chapters normalizer (W2-A).
- **Provider:** `getCredits(videoId: string, hl?: string)` is exposed on
  the merged `api/streamify/metadata/provider.ts` (the post-rebase commit
  — see "The merge-order bridge" below) following the established
  pipeline: snapshot → Edge KV → client-side InnerTube fallback →
  validate → snapshot upsert, with the same failure separation
  (not-found / rate-limited / parse-error) every other method carries.
- **Consumer seam:** `api/streamify/meta-bridge.ts` (fork-owned, this
  wave) adapts whichever provider shape is installed into the flat
  `MetaCredits` DTO the UI consumes — the W1-A `MetadataResult<T>`
  envelope AND the W1-B direct `null | payload` form, discriminated by
  the RESULT kinds only (`ok` / `not-found` / `rate-limited` /
  `parse-error`; the payload's own `kind: 'credits'` never collides).

### The UI

- `ViewCreditsAction` (song context menu — the A19 upstream seam, one
  line + import) resolves the selection's videoId (`ytm:` prefix decode
  or the snapshot row) and opens the drawer. Self-hides on non-Streamify
  servers (the `StreamifyDownloadAction` pattern).
- `SongCreditsModal` renders the grouped contributor roles with an
  **honest empty state** ("YouTube Music has no credits shelf for this
  track") when the provider answers empty, and never fabricates groups:
  every rendered role survived the bridge's structural validation.
- `useSongCredits` session-caches per videoId (errors are not cached —
  retry stays possible).

## B. 500 MB OPFS Smart Sync (`features/streamify/smart-sync/`)

### The resolver guardrail (non-negotiable, honored)

Smart Sync touches **zero** resolver files: no edits to
`playback/resolver/**` or `relayBase.ts`. The engine is a *consumer* of
the existing pipeline: `DownloadManager.startDownload(spec)` with
`source: 'smart-sync'`, which internally rides the same
`resolveOnline → fetch → seal → OPFS` path the manual downloads ride.

### The concurrency discipline

- **Single worker, sequential** (`syncing` flag; concurrency 1).
- **Post-buffer trigger only:** the runtime hook (`use-smart-sync.ts`,
  mounted once via `StreamifyGlobalRuntime`) arms a
  `SMART_SYNC_CLEAN_PLAY_MS` (5 s) window when the transport enters
  PLAYING and fires one sync pass only if the window holds; every song
  change re-arms it, pause cancels it. The engine re-checks
  `isPlaying()` itself — a late timer can never fire while paused.
- Background pre-fetch therefore never competes with active playback
  bandwidth.

### The circuit breaker (the script.google.com shield)

`startDownload` now resolves a structured `DownloadOutcome` carrying the
failure **stage** (`resolve` | `fetch` | `seal`) and the HTTP **status**
when one exists. The engine trips a `SMART_SYNC_BREAKER_MS`
(**15-minute**) session cooldown and **halts the whole pass** (N+2 is
never attempted after N+1 burns) when a pre-fetch:

- answers **403** or **429** (the bot-gate / rate-limit answers), or
- fails at the **resolve stage** (the solver ladder itself errored — the
  "solver error" class), or
- matches the bot-gate vocabulary in the failure text
  (`/bot[- ]?gate|po[- ]?token|sign.?in to confirm|…/`).

Quota (OPFS), abort, and plain network failures do **not** trip the
breaker — they stop the pass quietly (no retry storm either way).
While the breaker is open, `sync()` short-circuits before any call, so
background jobs can never burn the Apps Script daily URLFetch quota or
trigger upstream rate limits.

### The strict 500 MB LRU ceiling

- The cap (`SMART_SYNC_CAP_BYTES = 524,288,000`) applies to the summed
  `ciphertextBytes` of **`source: 'smart-sync'` entries only**.
- `DownloadMeta.source` / `lastPlayedAt` are the new sidecar fields;
  **P4-A sidecars without them read as `source: 'manual'`** — every
  pre-W2B download is a user download, and **manual downloads are never
  evicted** (the source tag is the hard boundary; pinned by tests).
- Eviction is LRU by `lastPlayedAt ?? completedAt ?? createdAt` ascending
  (the engine stamps a track's sidecar when it becomes the current
  queue item via `manager.touchPlayed`), run **before** each write with
  a duration-derived incoming-bytes estimate, so the cap is never
  exceeded transiently.
- **Origin-pressure guard:** `navigator.storage.estimate()` — when
  `usage / quota ≥ 0.9` the pass refuses to start (Smart Sync never
  pushes browser origin storage into critical pressure).

### Metered / save-data connections

`sync()` skips while `navigator.connection.saveData === true` **unless**
the user explicitly opted in: turning the Smart Sync toggle on in
`/streamify-settings` sets `smartSyncSaveDataOverride` (default false —
the untouched default state keeps the save-data skip). The toggle itself
(`smartSyncEnabled`) defaults to true.

### The settings surface

The `/streamify-settings` **Offline** tab (fork-owned route — no
upstream conflict): the toggle, the honest storage meter
(`X MB / 500 MB used`, computed from the actual sidecars — never a
guess), "Clear Smart Sync Cache" (removes **only** smart-sync entries),
and the breaker cooldown note when active.

## C. playTally-Seeded Discover Mix (`features/streamify/discover/`)

### The canonical invariant (cross-reference)

Seed weighting reads **exclusively** the #191/#192 playTally ledger,
whose counts are produced only by the canonical
`min(half-duration, 4 min)` actual-played-time rule
(`front-end/decisions/p2/P2B-playback-spine.md`). The engine never
counts a skip, never re-derives play state — it ranks
`tally.topTracks` by counted plays and resolves the winners to videoIds
through the live snapshot.

### The algorithm

1. **Seeds:** top `DISCOVER_MAX_SEEDS` (3) tally tracks with a
   resolvable snapshot videoId.
2. **Cold start:** fewer than `DISCOVER_MIN_SEEDS` (3) resolvable seeds
   falls back cleanly to the regional provider recommendations —
   `getExplore(gl, hl)`, else `getHomeFeed(gl, hl)` — honestly
   attributed (no "because you listened to…" line).
3. **Candidates:** `provider.getUpNext(seed)` + `provider.getRelated(seed)`
   per seed (the Edge KV 12 h TTL class).
4. **Filters:** the seeds themselves, and anything played inside the
   recent-history window (`DISCOVER_RECENT_WINDOW_MS` = 7 d, read from
   the snapshot's `lastPlayedAt` + `playEvents` timestamps — the tally
   carries no per-track timestamps, the events do).
5. **Dedup + interleave:** by videoId AND by `(title, artist)`
   signature, round-robin across seeds so one radio cannot dominate the
   mix; bounded at `DISCOVER_TARGET` (25).
6. **Honest terminal:** every feed missing → `null` → the shelf keeps
   its empty state; nothing is ever fabricated.

### The playable mapping

`discoverTrackToSong` (mirrors W1-B's `ytmToSong` conventions; a
post-W2B consolidation may unify them): tracks the snapshot knows (by
videoId or signature) keep their cloud track ids — likes and play stats
stay joined; unknown tracks map to the `ytm:<videoId>` namespace the
W1-B controller's `getStreamUrl` decodes straight to the resolver.

### The shelf

`DiscoverMixShelf` on the Home route (the A26 upstream seam — one
conditional line): seed attribution subtitle, "Play Mix"
(`player.addToQueueByData(songs, Play.NOW)`), "Refresh Mix", honest
loading/empty/error states, self-hides on non-Streamify servers.

## The merge-order bridge (`api/streamify/meta-bridge.ts`)

W2-B branched before L2 reconciled the W1 provider (#198's
`MetadataResult<T>` singleton vs #200's `getMetadataProvider()` /
`null | YtmXxx` installer API). The bridge is the ONE module that knows
both shapes: it lazily imports the provider module (specifier behind a
variable + `@vite-ignore`, so the pre-rebase tree still typechecks),
resolves whichever export shape exists, unwraps the result forms, and
normalizes into the flat DTOs the features consume. Before the provider
exists (this branch pre-rebase, stock deployments) it resolves null and
every consumer renders its honest empty — the W1-B stub doctrine.
**The `getCredits` provider exposure lands as the post-rebase commit**
against the merged `provider.ts`, when its final shape is observable.

## Tests (network-free)

- `w2b-credits.test.ts` — the bridge's credits lane over both W1
  shapes, every failure envelope → the honest null, the pre-provider
  state, Liner Notes grouping (malformed groups/credits dropped,
  untitled groups kept, hl default), the drawer store lifecycle.
- `w2b-smart-sync.test.ts` — N+1/N+2 sequential pre-fetch tagged
  `smart-sync`; the tripping halt (N+2 never attempted after a 403);
  breaker classification (403/429/bot-gate/resolve trip; quota/abort/
  network do not) + the 15-minute cooldown; the 500 MB LRU eviction
  proving **manual downloads are never evicted** (P4-A sidecars without
  the source field included); usage metering; save-data skip + the
  explicit-override escape; the queue lookahead (player store mocked at
  the module boundary — the `offline-bypass.test.ts` idiom).
- `w2b-discover-mix.test.ts` — tally seed ranking (count desc, videoId
  resolution, cap), the recent-history window (videoId + signature, by
  `lastPlayedAt` and `playEvents`), round-robin interleaving,
  videoId + signature dedup, seed/recent exclusion, the 25-track bound,
  the cold-start Explore → Home → null ladder, and the `ytm:` mapping
  (cloud ids preserved for known tracks; honest Unknown Artist).

## Upstream hygiene

- All new UI strings live in `src/i18n/locales/en.json` ONLY
  (`streamifyCredits`, `streamifySmartSync`, `streamifyDiscover`
  namespaces + `streamify.settings.tabOffline`); the 38 Weblate-managed
  catalogs are untouched — `scripts/check-i18n.mjs` stays green.
- Upstream seams (PATCHES.md A-entries): `song-context-menu.tsx` (A19
  extension), `home-route.tsx` (A26), `app.tsx` (A27). Everything else
  lives in fork-owned directories (`features/streamify/{credits,
  smart-sync,discover}/`, `api/streamify/meta-bridge.ts`,
  `store/streamify-smart-sync.store.ts`) or is an additive extension of
  fork-owned files (downloads `meta.ts` / `download-manager.ts` /
  `instance.ts`, the settings store + route).
