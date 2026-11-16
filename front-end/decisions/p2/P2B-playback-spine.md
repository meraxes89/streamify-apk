# P2-B — Playback spine: queue semantics, persistence, gapless, scrobble

**Status: COMPLETE (B-side)** — 2026-11-16. Branch `ws-w/p2b-playback-spine`.
Local gates green (vitest 122/122, tsc `--noEmit`, vite build). CI carries the
rest (wasm compile gate, wasm-bindgen tests, the new vitest job, web build,
relay dry-run). The shared exit criterion — a full listening session through
the scraper resolver (multi-track queue, shuffle, seeks, mid-track reload, two
crossfades, scrobble log) — needs A's contract lanes live; see "Open items for
Engineer A".

## Scope (as kicked off)

Four subsystems, one state machine underneath: queue semantics
(play/next/prev/shuffle/repeat/seek as transitions), play-queue persistence
(never resolved URLs), gapless config (the T-15s preload + crossfade
scheduling), and scrobble (min(half-duration, 4 min), fire-once, offline
outbox). Everything lands in the app repo (`front-end/web`); the resolver repo
is untouched, and `audio/resolve.ts` carries ZERO diffs — the controller is a
pure consumer of the existing `port.resolveAndStage` (getStreamUrl) lane.

## What landed

| Piece | Role |
|---|---|
| `lib/playback/queueMachine.ts` | the PURE state machine: rows + playOrder + cursor + repeat + shuffle + finished; every transition returns `(state, intent, changed)` with intents `play`/`restart`/`stop`. 47 headless tests pin every kickoff edge case |
| `lib/playback/queueStore.ts` | the controller: executes machine intents on `WasmEnginePort`, owns the boundary hook, paused-advance handshake, restore dance, and the session runtime (scrobble accrual + gapless ticks + persistence throttle) off the snapshot push |
| `lib/playback/persistence.ts` | versioned queue persistence, size-routed storage, full-shape validation (corrupt ⇒ discard) |
| `lib/playback/scrobbler.ts` | the play-session tracker (threshold, fire-once, replay re-arm) + the bounded offline outbox (drain, backoff-free flaps, poison drop at 5 attempts) |
| `lib/playback/gapless.ts` | the scheduler: T-15s preload trigger, invalidation on order change, crossfade window arming, failed-resolve backoff |
| `lib/playerStore.ts` | slimmed to the TRANSPORT mirror (snapshot/send/volume/resolve verdicts); all queue fields moved to the machine |
| UI pass | FullPlayerSheet (shuffle/repeat live, repeat-one badge), MiniPlayerBar (machine now-playing, swipe verbs), QueueScreen + FullPlayerSheetPanes (machine slices), trackPlayer (delegation), AppShell (bind + scrobble install; the old transition-based play-event effect REMOVED), NavRail/UniversalHome/Lyrics/Debug/PlayerBar read-site swaps, Settings (crossfade default 3 s, live push) |
| CI | vitest job in `web-build.yml`; branch registered in both `web-build.yml` and `web-deploy.yml` push lists |

Tests: `front-end/web/src/lib/playback/*.test.ts` — 122 cases. The kickoff's
named edge cases each have a named test: shuffle keeps current + seeds history;
next draws from the un-played pool; prev walks actual play order; shuffle-off
restores original order with current in place; repeat-one beats repeat-all;
manual next in repeat-one advances; one-track queue (all four mode
combinations); shuffle mid-preload (invalidation + re-resolve); T-15s trigger
boundary; crossfade window arithmetic; threshold rule incl. the 4-min cap and
unknown duration; no re-scrobble on seek/restore; deliberate replay re-arms;
offline drain; poison drop; store routing by size; corruption discard.

## Architecture rulings

### One state machine, one transport

The machine owns SEMANTICS (order, cursor, shuffle, repeat); the engine owns
TRANSPORT REALITY (what is loaded/playing/paused, position — its snapshot).
UI players never hold canonical state: they read `useQueueStore` projections
(`queue: QueueRead` shape — a drop-in for the old projection, so the surfaces'
slice math is unchanged) and the derived `displayStatus` / `displayPositionMs`
/ `nowPlaying`, which layer the machine over the engine where the engine
briefly lags (paused advances, restores).

### The engine mirror

The wasm engine's queue is a STAGING AREA: it contains every machine row
(enforced at append/insert/replace/restore) in best-effort playOrder. Queue
movement NEVER uses the engine's contiguous advance — the machine computes
every target and `play_track(id)` latches the engine cursor wherever the row
sits. Order divergence (shuffle toggle, drag-reorder, removal) sets
`mirrorDirty`; the rebuild (`clearQueue + enqueue`) is deferred to the next
track boundary — the one moment it is silent (the P1 discipline, now a
general rule instead of the P1-specific override projection).

### Paused advance (documented semantics)

`next`/`prev` while paused moves the MACHINE cursor only; the engine stays
parked on the old row and catches up on resume (`resolveAndStage` +
`playTrack`). Consequences, deliberate: resuming a row you paused ON resumes
in place (the element still parks at its position); a row you advanced TO
starts at 0; prev-back to an abandoned row restarts at 0 (paused positions of
skipped rows are not retained — per-track parked positions would need engine
support). The old paused element is released only when a rebuild/replace
passes through `clearQueue`.

### Seek is a command we issue; A owns the mechanics

All seeks go through `port.send({type:'seek'})` (or the restore dance below).
The in-place-vs-reload decision stays in `dispatchIntent` (A's lane). A seek
targeting a staged-but-not-rolling row (paused advance, restore) is PARKED as
`pendingPositionMs` and lands when the row actually plays — one seek command,
issued once, never a retry loop.

### Restore (the dance)

Boot: persisted state rebuilds machine + engine mirror (current row FIRST),
parked at the saved position, autoplay-honest (paused, no gesture burned).
First play gesture: `resolveAndStage` (fresh getStreamUrl — never a persisted
URL) → `rebuildMirror(current-first)` → `send('next')` (selects row 0
silently: Idle + staged URL emits no intent) → `send('seek', pos)` (latches
`position_target`; the seek intent no-ops harmlessly on the unloaded element)
→ `send('play')` (the play intent carries `start = position_target`, so
`sink.load` fast-forwards to the position after metadata). Zero blip, zero
races, 100% through the existing command interface. A restore that finds the
user already started a queue during the load window stands down (live state
wins).

## The play event (picked once, documented)

A play session counts when it accumulates **min(half the track's duration,
4 minutes)** of actual played time (wall-clock while transport Playing;
unknown duration ⇒ the cap alone). No re-scrobble on seek (position is not
played time), reload/restore (the session — trackId + playedMs + fired flag —
persists with the queue and is CONTINUED), or pause (accrual stops). A
deliberate replay — fresh start of the same track — opens a FRESH session and
can scrobble again.

**Local tally semantics change (called out):** the P3a AppShell effect
counted every track TRANSITION as a play event with the (near-zero) position
at switch time. The scrobbler replaces it: `recordTally` now counts
threshold-crossing plays with real played-time. Wrapped/chronotype numbers
become stricter (real listens, not skips). The cloud event shape is unchanged
(`user_play_events` row, `trackSig` derivation identical), sent through the
new `recordPlayEventStrict` (a reporting variant; the old swallow-always
`recordPlayEvent` stays for any other callers).

Scrobble failure never blocks: events land in a persisted outbox
(`streamify.scrobble.queue.v1`, cap 200) and drain on online / visibility /
sign-in / a 60 s heartbeat, only while signed in AND online. A poison entry
drops after 5 failed sends; any successful drain resets attempt counters
(a success proves connectivity — earlier failures were offline flaps).

## Store choice (by size, not vibes)

Serialized payload ≤ 750k chars (~1.5 MB UTF-16, ~2.4k rows at ~300 chars/row)
⇒ localStorage (synchronous, universal, multiple-headroom under the ~5 MB
quota). Anything larger ⇒ IndexedDB (`streamify-playback` / `queue` /
`current`). Exactly one lane holds the state at a time (a write clears the
other). No IDB + quota overflow ⇒ honest degradation (not saved; playback
unaffected). Corruption of any kind ⇒ discard, never crash, never
half-restore. `finished` is intentionally not persisted: a restored queue is
always playable.

## Gapless + crossfade (the B-side of A's dual engine)

The preload trigger fires from the machine's state at T-15s (config const):
`resolveAndStage(N+1)` so the boundary switch is a staged-URL play — the
multi-second resolve wait between songs is gone. The prepared handoff plays
directly at the boundary (no re-resolve). Edge cases: repeat-one / one-track
repeat-all (N+1 is N — nothing to prepare, the warm stream IS next);
shuffle/reorder mid-preload (invalidation + fresh resolve of the new next;
the stale staged URL on the other row is harmless per-row metadata); failed
resolve (30 s backoff, no request storm).

Crossfade duration is the settings surface's `crossfadeSec` (default changed
0 → 3 s per the kickoff; user-adjustable 0–12 s, 0 = off, pushed live into
the scheduler). **The seam (Engineer A's contract):** inside the window the
scheduler ARMS the handoff and calls, duck-typed, optional:

```ts
WasmEnginePort.crossfadeTo?(trackId: string, crossfadeMs: number): boolean
```

— fade the prepared track in while the current fades out. Until the method
exists on the port, the arm event is inert and the boundary takes the
staged-URL fallback (near-gapless: the resolve wait is already eliminated;
only the element swap remains). The integration point when A lands the dual
engine: the arm call above, and A's `dispatchIntent` play path makes the
element swap seamless (the port knows a fade is in progress). No engine
change was required to ship B-side.

## Guardrails (verified)

- `audio/resolve.ts`: **zero diffs** (git-verified). The controller consumes
  `port.resolveAndStage` only; resolver misbehavior routes to Engineer A, not
  into workarounds here.
- All work in the app repo (`front-end/web` + workflows). The wasm engine
  crates and `wasmEngine.ts` are untouched — the controller needed nothing
  beyond the existing port surface (`onTrackEndedHook`, `resolveAndStage`,
  `playTrack`, `insertAfterCursor`, `clearQueue`/`enqueue`, `subscribe`,
  `lastResolved`).

## Live smoke verification (the shared relay, 2026-11-16)

A real-browser drill against the production relay before its shared IP
bot-gated (the documented G4/G5 egress burn — see below): boot clean (engine +
queue machine bound, no console errors); search real; **queue 3 tracks and
play one through the real resolver ladder (visionos-direct rung) — Playing,
position advancing**; shuffle toggled ON mid-track with playback uninterrupted
and `aria-pressed` live; repeat cycled off→all→one with the "1" badge
rendering; queue screen history/up-next slices correct; **reload mid-track:
the full state round-tripped** (3 rows, cursor, position 62 s, shuffle,
repeat-one) with the restore UI parked honestly; the scrobble session
persisted and accrued real played time (124 s) with no false fire (threshold
240 s for that track).

Two findings from the drill, both addressed:

1. **The restore dance must not rebuild the engine mirror** — the original
   dance rebuilt between `resolveAndStage` and the `next` command, which WIPED
   the just-staged URL off the re-enqueued row (and, from the engine's boot
   Buffering state, tripped a stop pipeline). Fixed: the boot-time restore
   already leaves the mirror current-first; the dance is `next → seek → play`
   only, guarded on a null engine cursor. (The engine boots into Buffering,
   not Idle — the restore's mirror rebuild is what parks it into Idle, which
   the dance then builds on.)
2. **Repeat-one × a flaky media lane**: a spurious element 'ended' during a
   network flail hits the repeat-one boundary and restarts from 0 (correct
   per repeat-one semantics — a loop is a fresh play — but surprising when
   the "end" was a lane failure, not a real end). The engine's rotation lane
   normally catches stalls before any 'ended' can fire; noted here for the
   shared-exit drill. The scrobble side handles it honestly either way (a
   restart re-arms a fresh session — verified live).

**The full exit drill remains blocked on the egress**: the shared relay IP
bot-gated partway through the smoke (every rung LOGIN_REQUIRED, `bot-gate=1`,
`gsf=off` — the resolver's documented honest verdict, surfaced through the
UI's error lane exactly as designed). The multi-track / crossfade-twice /
scrobble-log session needs the unburnable GSF lane (Engineer A) or a fresh
egress — flagged same-day per cadence.

## Open items for Engineer A (same-day flags per cadence)

1. **Seek contract note** (week-one deliverable): confirm the in-place seek
   vs reload-and-fast-forward split as implemented in `dispatchIntent` is the
   contract; B sends only `seek` intents and the restore dance's
   `next → seek → play` sequence (position_target latch → play intent start).
   A `playAt(trackId, positionMs)` engine verb would let the dance collapse
   to one call — B adapts the same week it lands.
2. **`crossfadeTo` seam** (above) — the dual-engine handoff.
3. The shared exit session (the risk-gate drill) once A's pipes are live.
