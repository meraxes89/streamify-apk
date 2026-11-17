# W2-A — `STREAMIFY_META_BASE` / script.google.com hard isolation, the Audio ↔ Official Music Video toggle & timestamped video chapters

**Status:** COMPLETE. Branch `ws-w/w2a-video-chapters-meta-isolation`,
PR against `main` (based on the W1-A head — #198's tree — so the branch
inherits `metadata/provider.ts`, the `/ytm/meta` worker and the importer;
replayed onto `main` after #198 + #200 landed). CI: Feishin CI, relay +
ytm-meta tests, Unified CI Pipeline, CI gate (blocking).

## The decision this record locks

Three related decisions, one wave:

1. **`STREAMIFY_META_BASE` is a first-class deployment knob, HARD-isolated
   from the script.google.com solver.** The 6-rung audio resolver
   (`playback/resolver/**` + `relayBase.ts`) is frozen — zero rung,
   timeout, header, or fallback-order changes. But `STREAMIFY_RELAY_BASE`
   may legally point at a deployed Apps Script stream-link solver
   (`script.google.com/macros/s/…/exec` — the GSF lane's egress surface),
   and appending `/ytm/meta` to such a base would mint nonsense URLs
   against the Apps Script host. The metadata seam therefore resolves its
   base through a NEW `api/streamify/metadata/metaBase.ts` — never
   `relayBaseCandidate()` directly — with a locked priority shape
   (§A).
2. **The Audio ↔ Video toggle is a strictly additive resolver branch.**
   `getStreamUrl(songId)` audio behavior is byte-identical to pre-W2-A; an
   explicit opt-in `{ mode: 'video' }` parameter (default `'audio'`)
   dispatches to a separate branch that runs the SAME
   `resolveAudioStream` pipeline (same rungs, same headers, same fallback
   orders — the digest seam hands it every format the winning rung saw)
   and re-ranks into a video-first ladder: muxed mp4/webm (video+audio
   codecs in ONE URL — the only class a single `<video>` element plays
   without MSE), highest quality first. Misses are typed
   (`StreamifyVideoModeMissError`) and degrade cleanly: the toggle hides,
   audio keeps playing (§C).
3. **Chapters ride the W1-A Edge KV worker with the locked 7-day TTL**
   (`/ytm/meta?endpoint=chapters&id=<videoId>&hl=<hl>`), normalized to
   seconds-space boundaries (`{ title, startSeconds, endSeconds,
   thumbnailUrl }[]`), and a NEW `counterpart` endpoint (24 h TTL — the
   artist/album class) resolves the official music video server-side,
   deterministically, from server-derived data only (§B).

---

## A. The metaBase priority shape (the isolation invariant)

`metaBaseCandidate()` (`api/streamify/metadata/metaBase.ts`) resolves the
origin `/ytm/meta` is fetched against, EXCLUSIVELY:

| priority | source | rule |
| --- | --- | --- |
| 1 | `window.STREAMIFY_META_BASE` | the explicit deployment knob (the deployed Cloudflare Worker `…workers.dev`). Placeholder-guarded (`${STREAMIFY_META_BASE}` literals read as unset — the P5-A rule) and URL-validated. |
| 2 | `relayBaseCandidate()` | reused ONLY when set AND its hostname is NOT `script.google.com` (a worker-shaped relay carries `/ytm/meta` by construction — the W1-A deployments). |
| 3 | — | **Apps Script protection:** relay base points at `script.google.com` and `STREAMIFY_META_BASE` is unset → **null**. Never append `/ytm/meta` to script.google.com. The provider maps the absent base to edge-unavailable and serves from the client-side InnerTube lane (the W1-A contract). |

The memoized candidate busts together with the relay cache
(`streamify-settings.store.ts` calls `resetMetaBaseCache()` beside
`resetRelayBaseCache()` — the settings override feeds priority 2
transitively).

**Wiring (all four deployment surfaces carry the knob, all default
empty):**

- `settings.js.template` — `window.STREAMIFY_META_BASE = "${STREAMIFY_META_BASE}";`
- `Dockerfile` — `ENV STREAMIFY_META_BASE=""` (the nginx envsubst line)
- `docker-compose.yaml` — `- STREAMIFY_META_BASE=${STREAMIFY_META_BASE:-}`
- `.github/workflows/feishin-pages.yml` — the env + export pair; the
  envsubst `VARS` list derives from the template's own `${NAME}`
  references, so the template edit is the single point of truth.

### The optional worker proxy (`front-end/relay/`, `APPS_SCRIPT_SOLVER_URL`)

A deployment that points `STREAMIFY_RELAY_BASE` at the Cloudflare Worker
AND sets the Worker env var `APPS_SCRIPT_SOLVER_URL` (an Apps Script
`/exec` URL, validated against the same shape `gsfLane.ts` accepts) gets
its `/r` **control-plane** legs forwarded through the Apps Script
envelope (the GSF wire: `?q=<JSON {u,m,h,b,e}>` → `{ok,s,h,b,e}`), so
stream-solve egress rides Google's ASN 15169 instead of the shared
Cloudflare edge — the same bet the client-side GSF lane makes, server
side. The rules:

- `/ytm/meta` and `/bg-session` stay worker-local (the KV cache and the
  homepage challenge cache ARE this worker's duties).
- googlevideo media bytes NEVER ride the solver (UrlFetchApp quota and
  latency are for tiny JSON verdicts; the media CDN is CORS-open and
  cross-IP by design — the W2.0 measurement).
- Manual-redirect hops stay direct (UrlFetchApp follows redirects itself
  and cannot surface the hop protocol).
- A solver-lane failure (network, not-ok envelope, oversized body/URL)
  degrades to the direct worker fetch — strictly additive, never worse.

## B. The chapters + counterpart endpoints (worker)

### Chapters (the locked 7-day TTL — 604 800 s)

`GET /ytm/meta?endpoint=chapters&id=<videoId>&hl=<hl>` walks the WEB
`/next` response's `multiMarkersPlayerBarRenderer.markersMap`
(`DESCRIPTION_CHAPTERS` / `AUTO_CHAPTERS` — the W1-A leg plan, unchanged)
and emits:

```json
{
  "kind": "chapters",
  "videoId": "…",
  "hl": "en",
  "chapters": [
    { "title": "Intro", "startSeconds": 0, "endSeconds": 42,
      "thumbnailUrl": "https://i.ytimg.com/…", "atMs": 0 }
  ]
}
```

- `endSeconds` is the NEXT marker's start (the last chapter's is null —
  the client closes it with the element's duration).
- `atMs` is kept (`== startSeconds * 1000`) so entries cached by a
  W1-A-era worker (the `{ atMs, title }`-only v1 shape) stay readable;
  the client-side `validateChaptersPayload` coerces both forms and
  closes open boundaries the same way.
- The marker's own thumbnail set supplies `thumbnailUrl` (the largest
  entry — arrays ascend by size); null when the wire carried none.
- The W1-A SWR policy is unchanged: a non-empty chapter payload is
  terminal (never background-revalidated inside its 7-day TTL).

### Counterpart (the 24 h TTL — the artist/album class)

`GET /ytm/meta?endpoint=counterpart&id=<audioVideoId>&gl=…&hl=…` is a
two-leg flow: leg 1 = YTMUSIC `/next(videoId)` (the queue panel entry
whose videoId matches the seed carries its title/artist/duration); leg 2
(conditional on a title) = the videos-filtered search
(`<title> <artist>`). Matching runs server-side, deterministically, from
server-derived data ONLY (the cache key stays
`(counterpart, id, gl, hl)` — no client-influenced inputs):

- candidates must be DISTINCT from the seed videoId (a song whose only
  "video" is itself has no counterpart),
- normalized title equality (parenthetical decorations like
  "(Official Music Video)" are stripped before comparison),
- `confidence: 'strong'` requires the artist line in the result subtitle
  AND duration proximity (15 s absolute or 12 % relative, whichever is
  larger — official videos often add intro/outro); everything else that
  matches the title is `'weak'`; the best strong match wins.
- The payload `videoId: null` is a VALID answer ("no distinct official
  video") — cached as a positive (24 h), surfaced as the toggle's hidden
  state; a failed seed extraction (no queue panel) is the `missing:true`
  form.

## C. The `{ mode: 'video' }` resolver contract (additive)

`audio/get-stream-url.ts` (the canonical vendored tree — the P5-A
integrity check's existence/header/import/contract rules all hold):

- `GetStreamUrlOptions.mode?: 'audio' | 'video'` — defaults `'audio'`.
- The dispatch happens at the TOP of `getStreamUrl`; every audio line
  below the guard is byte-identical to pre-W2-A.
- Video mode runs the same `resolveAudioStream` call (same
  `elementCompat` posture, same digest seam), then builds the video
  ladder from the winning rung's formats: tier-2 muxed formats only
  (`classifyMime` already demands an audio codec inside the video
  container), ranked by bitrate (quality) descending, `&pot=`-pinned,
  range-probed, instrumented (`audioOnly: false`).
- The video cache namespaces the SAME Map under `<videoId>\u0000video`
  (audio and video resolutions for one videoId are different answers and
  must never alias).
- Misses throw the typed `StreamifyVideoModeMissError`: no muxed format
  on the winning rung, or an hls/sabr win (no element-consumable single
  video URL exists). Callers degrade cleanly — they never guess at error
  strings.

## D. The toggle + overlay (`features/streamify/video/`)

All components, stores and hooks live under the nested namespace
(`streamify/video/{components,hooks}` + the store + pure chapter math —
no top-level feature directory, the W1-A importer rule). Two upstream
seams only, both logged in PATCHES.md:

- `right-controls.tsx` renders `<StreamifyVideoButton />` (the P4B
  `<StreamifyJamButton />` precedent) — the toggle + the stable video
  overlay mount.
- `full-screen-player.tsx` renders `<StreamifyChapterStrip />` (renders
  null when the active media has no chapters).

**The timecode-synced switch contract:**

- Audio → Video: resolve counterpart (24 h edge TTL) → resolve the muxed
  stream (`{ mode: 'video' }`) → capture `currentTime` + `isPlaying` →
  pause the audio element through the player store's OWN status pipeline
  (`mediaPause` — the WebPlayer reacts, scrobble pauses honestly) → the
  overlay seeks to the preserved timecode and resumes when `wasPlaying`.
- Video → Audio: capture the `<video>` element's currentTime →
  `mediaSeekToTimestamp(preserved)` + `mediaPlay()` when the video was
  playing — the audio element (still loaded, merely paused) resumes at
  the exact second.
- Track change while in video mode ⇒ exit to audio first (the WebPlayer
  owns cross-track transitions; the overlay never races the queue).
- Scrobble honesty: the video-mode window counts as a pause/resume pair
  (the store pipeline's own semantics) — documented, accepted.

**The WebAudio discipline (P4-B's CORS-safe watchdog stays intact):**

- ONE stable `<video>` element while video mode is live (the container
  repositions between the fullscreen surface and the dock via CSS; the
  element — and its position — never remount).
- `crossOrigin='anonymous'`: a CORS-unclean video rung fails LOUDLY at
  load, never silently mutes through a tainted
  `MediaElementAudioSourceNode` (the audio elements' own posture).
- ONE `createMediaElementSource` per element instance (a ref guard —
  the `InvalidStateError` on a second call can never fire).
- The source routes through a dedicated `videoGain` into the SAME DSP
  chain the audio slots use (`dsp.preampGain`) so EQ/limiter/visualizer
  keep working on video audio; while the EQ watchdog latched a hard
  bypass, the gain rewires straight to `context.destination` (the
  attach-source discipline, reactive to the latch).
- Unmount disconnects source + gain — switching back never mutes or
  throws.

**The timestamp discipline:** while video mode is live the overlay is the
SOLE timestamp-store writer (every WebPlayer writer gates on the paused
status the toggle set), so the app scrubber, chapter strip and lyrics all
track the video position.

**The chapter UI:** the overlay's own scrubber carries chapter tick
marks + click-to-seek + the active-chapter highlight; the fullscreen
surface adds the chapter chips row (click seeks immediately); the audio
path gets the same chips through `StreamifyChapterStrip` (a track's own
chapters — DJ sets / mixes / long uploads).

## E. Verification matrix

| claim | lane |
| --- | --- |
| metaBase priority shape: Apps-Script-shaped relay + unset META_BASE → null; explicit META_BASE wins; worker-shaped relay reuses | `metadata/__tests__/meta-base.test.ts` |
| audio rungs byte-identical (default `getStreamUrl` ignores the mode flag) | `__tests__/resolver-video-mode.test.ts` |
| `{ mode: 'video' }` builds the muxed-first ladder; hls/sabr and no-muxed digests throw the typed miss; video cache never aliases the audio cache | `__tests__/resolver-video-mode.test.ts` |
| chapters: extraction (DESCRIPTION_CHAPTERS + thumbnails), 7 d TTL, endSeconds derivation, legacy atMs coercion, boundary selection, tick fractions, timecode capture/restore | `features/streamify/video/__tests__/video-chapters.test.ts` + relay `ytm-meta.test.ts` |
| counterpart: seed extraction, the matcher (distinct videoId, title normalization, duration tolerance, strong/weak), 24 h TTL, `videoId: null` as a cached positive | relay `ytm-meta.test.ts` |
| APPS_SCRIPT_SOLVER_URL: control-plane-only gating, envelope round-trip, clean degrade | relay `ytm-meta.test.ts` |

## F. Risks & honest limits

- **Counterpart matching is heuristic** — a same-titled live performance
  or lyric video can outrank the official video when durations collide.
  The confidence field is carried to the client for future ranking work;
  a wrong pick is user-visible but never fatal (toggle back is one click,
  the audio path was never touched).
- **Muxed-format availability** — modern innertube answers often carry
  only itag 18 (360p) as muxed; higher resolutions are adaptive-only and
  need MSE (out of scope — the honest miss). 360p video on a music
  player is the deliverable; the ladder walks down, never up, per the
  element contract.
- **The `music.getInfo` fallback shapes** (chapters/counterpart on the
  client-side InnerTube lane) are structural reads over youtubei.js 18.3
  objects; on shape drift they degrade to null (the edge path serves
  both endpoints fine — the same known-degradation discipline as the
  W1-A suggestions fallback).
- **The fullscreen video surface** covers the full player's hero area
  with a centered 16:9 box rather than replacing the album-art node
  in-place (no upstream layout surgery — the one-seam rule); it is
  deliberately conservative CSS.
