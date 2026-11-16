# P2-A — App stream: the dual react-player engine wired

**Status: IN REVIEW** — 2026-11-17, Engineer A (P2 driver). Branch
`p2a/feishin-dual-engine` (stacked on `p2a/resolver-getstreamurl` — the
resolver stream PR; base `main`). Contract:
`P2A-contract-getstream-url.md`. Resolver record:
`P2A-resolver-getstream-url.md`.

## Scope (this PR — the app stream only)

Zero resolution logic. The fork imports `getStreamUrl` from a VENDORED
MIRROR of the resolver stack; what ships here is engine wiring:

- `src/renderer/api/streamify/playback/` (new directory, ours):
  - `resolver/` — the mirror: 10 files synced byte-identically from
    `front-end/web` by the monorepo's `scripts/sync_feishin_resolver.py`.
    CI (`web-build.yml → resolver-mirror-sync`) fails any PR where the
    mirror drifts from the resolver tree. **The two-stream rule, made
    mechanical**: resolution fixes land in `front-end/web` only; a
    hand-edit of the mirror goes red; nobody "fixes resolution behavior
    from the app side."
  - `resolver/lib/relayBase.ts` — the one fork-authored file inside the
    mirror (no upstream twin): replaces the web app's wasm-bridge import
    (`lib/engine.ts#relayBaseCandidate` must not vendor). Priority:
    `window.STREAMIFY_RELAY_BASE` (settings.js → Docker env) → `?relay=`
    → `VITE_RELAY_BASE_URL` → dev default.
  - `stream-source.ts` — the consumption layer: the controller seam's back
    end, the error-claim hook, the seek-strategy hook (below).
  - `__tests__/stream-source.test.ts` — the fork's first test lane (8/8):
    the DoD walk/seek lock.
- `StreamifyController.getStreamUrl` — THE seam (the one place the stub
  became real): feishin song id → snapshot row → `streamUrl` (the tracks
  table's `stream_url` IS the videoId — the web app's `libraryStore`
  maps it so) → `getStreamUrl(videoId)` → the URL string
  `useSongUrl`/react-query feed the dual slots.
- Two thin upstream seams (PATCHES.md 16-17): `web-player.tsx` (error
  claim + non-range seek claim) and `web-player-engine.tsx` (the
  `onSourceError` prop + one claim block). Unclaimed paths byte-identical.
- Fork deps (mirror's npm twins, same pins as the web app):
  `youtubei.js@18.3.0`, `googlevideo@^4.0.4`, `bgutils-js@^4.0.3`;
  `vitest`; script `test`. Relay knob in `settings.js.template` +
  Dockerfile ENV + `streamify-globals.d.ts`. eslint ignores the mirror
  (vendored); typecheck covers it.
- Monorepo CI: `feishin-ci.yml` (the F1-deferred monorepo-side gate for
  the fork tree) + the `resolver-mirror-sync` job in `web-build.yml`.

## What the upstream machinery already gave us (no code needed)

- **Dual slots + preload**: `getDualPlayerSongs` puts the next track in
  the idle slot the moment the queue advances — its URL resolves then,
  always ≥ the crossfade window, typically minutes early (safe for the
  ~6 h URL lifetime; the contract's "never later than ~T−15 s" is a
  floor, not the mechanism).
- **Crossfade/gapless**: upstream's `crossfadeHandler`/`gaplessHandler`
  drive both slots once they have URLs; `mediaSeekToTimestamp` seeks the
  active slot in place (Range requests — the contract's default path).
- **Seek bar duration**: the library snapshot carries per-track duration
  (`QueueSong.duration`) — the seek bar is driven before the player
  reports its own.

## The engine wiring (what lives in stream-source.ts)

- **Error claim** (`onStreamifySourceError`, wired by the seams): probe
  the failing URL — 403/410 → stale → invalidate + `forceRefresh`
  re-resolve; **mid-crossfade → the cached-ladder walk instead (never the
  pipeline — the contract rule), invalidation deferred**; healthy URL →
  decode error → walk the cached ladder (`cursor+1`), same offset, zero
  pipeline hits; ladder exhausted → honest failure, logged, the user's
  skip stands. Foreign sources are never claimed (stock retry/skip logic
  untouched).
- **Seek claim** (`applyStreamifySeek`): non-range progressive URLs get
  reload-from-zero + silent fast-forward (muted, rate 16, position
  restored at the target); range-capable + HLS + sub-second seek → stock
  in-place path.
- **Honest states**: SABR-only resolves throw `StreamifySabrError`
  (react-player cannot parse SABR — the daily corpus instrumentation
  counts the hit; the checkpoint decides what comes next). No-video
  tracks (ingestion hasn't mapped one) throw `StreamifyNoVideoError`.
  Never fake data.

## Verification

- `pnpm run lint` green (typecheck node+web, eslint `--max-warnings=0`,
  stylelint). `pnpm run test` **8/8**. `vite build` — 4,205 modules
  transformed; the final chunk render OOMs on this 4 GB box (F1's
  documented constrained-box case): CI runners handle the full bundle
  (`feishin-ci.yml` runs the untrimmed build).
- The mirror is byte-identical (`sync_feishin_resolver.py --check` green).
- Known constraints, recorded honestly: the Electron main process's CSP
  lacks `'unsafe-eval'` (youtubei.js decipher) — the WEB build (this
  phase's target) has no CSP meta and runs fine; an Electron-runtime
  playback pass needs a CSP ruling, deferred with the Electron target.
  The rare HLS rung rides react-player's stock hls.js handling.

## Fork repo sync (post-merge)

Per F1/P1B: after this merges to monorepo `main`, the fork head is pushed
to `meraxes89/streamify-feishin` (its own CI — `Web CI` + commitlint —
runs there), and the vendored copy stays the monorepo's source of truth.
