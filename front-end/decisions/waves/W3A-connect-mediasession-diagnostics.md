# W3-A — Streamify Connect (cross-device handoff), OS media session & 3-plane health diagnostics

**Status:** COMPLETE. Branch `ws-w/w3a-connect-mediasession-diagnostics`,
PR against `main` (based on the W2-A head — #205's tree — so the branch
inherits `metaBase.ts`, the video-mode store and the chapter utils the
diagnostics and handoff build on; replayed onto `main` after the Splash/
OAuth restore + W2-A + W2-B merges). CI: Feishin CI, relay + ytm-meta
tests, Unified CI Pipeline, CI gate (blocking).

## The decision this record locks

Three features, one wave, all additive over frozen invariants (the 6-rung
resolver, `relayBase.ts`, `metaBase.ts` — zero lines touched in any of
them):

1. **Streamify Connect rides Supabase Realtime Presence + Broadcast on a
   user-scoped channel — zero stateful backend.** The protocol, the
   echo/conflict rules and the chunked handoff are locked in §A.
2. **The OS media-session bridge completes what upstream's stock hook
   left missing — additively.** Upstream `use-media-session.ts` already
   registers every action handler (play/pause/prev/next/seekto/stop/
   seekbackward/seekforward) for ALL server types; W3-A does NOT
   re-register them (a registration race between two components could
   wipe the stock registrations — this ruling is deliberate). What W3-A
   adds for Streamify sessions only: `setPositionState` (the lock-screen
   scrub), multi-resolution artwork (the YTM thumbnail ladder),
   chapter-aware metadata (§B), and the deep-link / share-target entry.
3. **The 3-plane diagnostics are user-triggered, lightweight, and
   redacted by construction.** The probe matrix, the quota rule and the
   redaction contract are locked in §C.

---

## A. The Connect protocol (`features/streamify/connect/`)

### The channel

`streamify:connect:<userId>` (supabase-js prefixes its `realtime:`
namespace) with `broadcast: { self: false }` + `presence: { key:
deviceId }` — the same configuration shape the jam lane (`jam-live`)
rides. Non-private channel (broadcast + presence need no
`realtime.messages` RLS), user-scoped by the channel name.

### Presence — the published state (one object per device, replaced atomically per `track()`)

```json
{
  "deviceId": "dev-…", "deviceName": "Chrome on Linux",
  "isPlaying": true,
  "currentTrack": { "videoId": "…", "title": "…", "artist": "…",
                    "thumbnailUrl": "…", "durationSec": 213 },
  "positionSec": 41.7, "mode": "audio", "updatedAt": 1700000000000
}
```

- The **sanitize gate** (`sanitizePresenceForPublish`) clamps every field
  and bounds string lengths — a buggy/hostile caller cannot smuggle an
  oversized blob into presence.
- **Publishing cadence**: track change, play/pause change, seek (each
  throttled ≥ 3 s apart) + a 15 s keepalive while playing.
- **Interpolation**: viewers compute the live position as
  `positionSec + (now − updatedAt)/1000` while `isPlaying`, clamped to
  the track duration (`interpolatePosition` — pure, locked by the suite).

### The command lane — echo + conflict prevention

Every message carries `fromDeviceId`, `targetDeviceId`, `nonce`:
- **self-echo** dies first (`fromDeviceId === own` — the transport's
  `self:false` already suppresses the sender's copy; this is the
  belt-and-braces layer and the documented semantic for two tabs of one
  browser profile, which share one deviceId);
- **foreign targets** die next (`targetDeviceId !== own` — a third
  device overhears but stays silent);
- the **nonce window** (last 32) dedups rebroadcast/reconnect replays —
  a command executes at most once.

Transport commands (`play | pause | next | prev | seek | volume`) execute
through the player store's OWN pipelines (`mediaPlay/mediaPause/
mediaNext/mediaPrevious/mediaSeekToTimestamp/setVolume`) — the same
surfaces the stock media-session hook drives, so remote control and
hardware-key control share one code path.

### The handoff ("Transfer playback here")

1. The requester broadcasts `handoff-request` (its interpolated position
   is informational).
2. The target **pauses itself first** (the task contract), then streams
   its queue back as `handoff-chunk` messages: the queue's videoIds
   chunked at 25 (max 500 tracks), with `{ currentIndex, positionSec
   (exact), mode }` riding chunk 0.
3. The requester reassembles (`HandoffAssembler` — per-transferId,
   hole-tolerant, 10 s assembly window swept every 5 s), maps the ids
   through **its own library snapshot** (same account; the sync engine
   converges the catalog — unknown ids are skipped honestly, never
   fabricated), `setQueue(songs, index, 0)` → `mediaSeekToTimestamp
   (exact)` → `mediaPlay()`.
4. `mode: 'video'` restores through the W2-A public pipeline
   (`requestVideoModeAtPosition`): counterpart → muxed stream → the
   overlay lands on the exact preserved timecode. A miss degrades to
   audio at that timecode (the W2-A honest-degrade rule); autoplay is
   safe (the handoff is a user gesture).

### Degradation

Unauthenticated / Supabase unconfigured → the controller stays dormant
(`offline`, zero reconnect storms — one connect attempt per mount, none
automatic). A channel error → `offline`; the next mount retries.

## B. The OS media-session bridge + deep links (`features/streamify/media-session/`)

- **Action handlers: upstream's.** The stock hook registers the full set
  for every server type; W3-A never re-registers (the wipe ruling in
  §2). `applyBridgeTick` is feature-detected end-to-end: an environment
  without `navigator.mediaSession` (older browsers, JSDOM) is a clean
  no-op.
- **positionState**: `{ duration, playbackRate, position }` per whole-
  second tick (the OS lock screen needs no sub-second state),
  clamped/guarded against the spec's `InvalidStateError`.
- **Chapter-aware metadata**: the active chapter (W2-A's
  `activeChapterAt` boundary rule) rides the album slot — `Album —
  Chapter` — rewriting only at track or chapter boundaries, never
  per-tick.
- **Multi-resolution artwork**: the YTM thumbnail ladder (default/
  mq/hq/sd/maxres, 120→1280) off the videoId — the OS picks its size.
- **Deep links** (read off `window.location.search` — the app is a
  HashRouter): `?play=<videoId>` resolves through the library snapshot
  (same-account catalog; an unknown id is an honest toast — never a
  fabricated song) and starts playback; `?import=<ytm url|id>` opens the
  W1-A importer modal pre-populated (the modal seeds from the
  `streamify.import.prefill` session key); the PWA share-target forms
  (`?text=`, `?url=`) route YouTube-ish payloads into the same importer
  parse. Consumed keys are scrubbed with `history.replaceState` BEFORE
  the resolve (a slow resolve must not leave the param behind for a
  refresh to re-trigger). The manifest `share_target` (GET, `params:
  { text: 'text' }`, action `/`) is stamped in `web.vite.config.ts`
  (A24's file — logged in PATCHES.md).
- **Boot timing**: the bridge mounts from the player bar (the A14 seam)
  — the player bar appears exactly when the app shell is up with a
  resolved server, which IS "after SERVER_LOCK / auth resolution".

## C. The 3-plane diagnostics (`features/streamify-settings/`)

**User-triggered ONLY**: one run when the Network tab mounts + the Run
button. Never a background loop (the Apps Script quota rule).

| plane | probe | quota cost |
| --- | --- | --- |
| Stream Solver | Apps-Script-shaped `relayBase` → ONE `?ping=1` (the script's own cheapest answer — no `UrlFetch` upstream); worker-shaped → ONE `GET /` (the capability echo — static JSON); unset → Unconfigured (no request) | ≤ 1 ping per user action |
| Edge Metadata Cache | `metaBase()` (the W2-A isolation knob — script.google.com bases read as edge-off by construction) → ONE `/ytm/meta?endpoint=chapters&id=<the licensed canary>`; the FIRST probe fills the 7-day KV entry, every later probe is a cache answer; `cache.state` maps to the badge (fill=MISS, fresh/stale=HIT, uncached=BYPASS) | zero Apps Script by construction; the worker's own upstream, once per 7 days |
| Cloud Sync & Realtime | the live `auth.getSession()` + ONE throwaway channel subscribe/unsubscribe (websocket readiness), fully cleaned up | one subscribe per user action |

**The redaction contract (P5-B)**: the diagnostic state and the copyable
report carry ONLY — plane label, classifier, a **masked hostname**
(`redactHostname` keeps the last two labels: `script.google.com` →
`…google.com`, `streamify-relay.zephyr4289.workers.dev` → `…workers.dev`;
the semantic identity rides the plane label/classifier), the HTTP
status, latency ms, and the derived badge. No protocol, no path, no
query, no token, no signed-stream material — `w3a-diagnostics.test.ts`
pins the report against the P5-B leak patterns (absolute URLs, auth
tokens, `pot=`/`signatureCipher`, and script.google.com deployment
paths).

## D. Verification matrix

| claim | lane |
| --- | --- |
| presence serialization (sanitize/clamp), command dispatch + self-echo/target filtering, nonce dedup, chunked handoff reassembly, timestamp interpolation | `connect/__tests__/w3a-connect.test.ts` |
| mediaSession metadata/positionState wiring (injected navigator double), safe no-op when absent, `?play=`/`?import=` parsing + URL cleanup | `media-session/__tests__/w3a-media-session.test.ts` |
| 3-plane state transitions (Healthy/Degraded/Unconfigured), the redaction contract (zero URL/token/path leaks) | `streamify-settings/__tests__/w3a-diagnostics.test.ts` |

## E. Risks & honest limits

- **Two tabs, one device identity** — the per-browser-profile deviceId
  means two tabs of one browser are ONE Connect device (commands between
  them are filtered as self-echo by design; the documented semantic).
- **Handoff library convergence** — the receiving device maps the
  sender's videoIds through its own library snapshot; a track the
  receiver's catalog lacks is skipped (with the queue index preserved as
  well as it can be). Deep-catalog drift = a shorter queue, never a
  fabricated one.
- **?play= requires the track in the library** — the deep link does not
  search/fabricate; an unknown videoId is an honest toast (search-driven
  resolution is future work).
- **The video-mode handoff restore** rides the same counterpart/stream
  resolution as the manual toggle — a 360p-muxed-only answer or a
  counterpart miss lands as audio at the exact timecode (the W2-A
  honest-degrade rule).
- **Channel privacy** — the Connect channel is user-scoped by NAME, not
  by Supabase private-channel RLS (matching the jam lane's posture);
  presence/broadcast carry no secrets (the redaction discipline applies
  to the published state too).
