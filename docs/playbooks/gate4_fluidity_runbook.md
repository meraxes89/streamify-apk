# Gate 4 — UI fluidity runbook (Instruments, 120 Hz ProMotion)

**Status: RUNBOOK ONLY — Gate 4 has still never been executed.** Nothing
in CI measures frame pacing yet (`SWIFT_MIGRATION_PLAN.MD:215` remains the
truth). This document is the WS-B B6 deliverable: the exact procedure and
the evidence format the gate requires, so the first execution on ProMotion
hardware produces a comparable, attachable report. It is deliberately NOT
a checklist of fabricated numbers.

## Scope

SW-ENG2-10: "Ensure 120Hz ProMotion render performance across all
scrollable lists" — sustained 120 fps scrolling with zero main-thread
hitches on ProMotion devices, measured with Instruments, not inferred.

Surfaces in scope (the shell's scrollables): Home shelves
(`UniversalShelfFeedView`), Search results, the queue list, Library grid,
Community/Stats/Updates hubs, the full player's panes.

## Equipment

- ProMotion hardware (iPhone 13 Pro / 14 Pro / 15 Pro / 16 Pro class).
- Xcode 16+ with the Instruments app; a release-configuration build of the
  host app (`iosApp/Host`, Release, with the real engine lane staged —
  `scripts/build_ios_xcframework.sh --stage-only` first, so playback is
  the CoreAudio path, not the preview lane).
- The screen locked to 120 Hz (Settings → Accessibility → Motion →
  Limit Frame Rate OFF) and Low Power Mode OFF.

## Procedure

1. **Record** (per surface, ≥ 60 s each, device unplugged from heat sinks
   of prior runs — thermal state `kPSThermalStateNominal` required):
   ```bash
   xctrace record \
     --template 'Time Profiler' \
     --device <UDID> \
     --launch -- <bundle-id-of-host-app> \
     --output gate4-home.timeprofile   # then repeat per surface
   ```
   Alongside it, capture one **Hitches** template run per surface
   (`--template 'Hitches'`) — hitch duration/ratio is the acceptance
   metric, the Time Profiler trace is the attribution evidence.

2. **Exercise** the golden path during the capture: scroll each surface
   with continuous flings for the full window; on Home and the full
   player, do it WHILE A TRACK IS PLAYING (the WS-B posture: the CoreAudio
   render callback, the 10 Hz DAC-clock drain and the widget mirror all
   run concurrently — Gate 4 exists precisely to prove they don't starve
   the main thread).

3. **Extract the frame-pacing numbers** from the Hitches trace: hitch
   ratio (%) and worst hitch duration (ms) per surface; from Time
   Profiler: main-thread busy % during scroll, and the top symbols (they
   should be SwiftUI layout/draw — NOT `recordDacClock`, not the render
   path, not WidgetPlaybackStore I/O).

4. **Attach to the PR** (this is the AC): a table of
   `surface | duration | avg fps | hitch ratio | worst hitch (ms)` plus
   the two `.trace` bundles (or their exported summaries), and the device
   model + OS + thermal state.

## Acceptance

- Sustained ~120 fps (or the panel's current cap) on every scrollable
  surface while playing.
- Hitch ratio < 0.1 % and no hitch > 100 ms attributable to app code
  (system-caused hitches called out separately).
- Zero main-thread time in the audio/clock planes (they are, by
  construction, off-main; the measurement proves it stayed that way).

## Why not automate now

A CI lane for frame pacing needs a ProMotion *simulator or device farm*
with deterministic scroll injection (XCUITest perf metrics are 60 Hz-bound
on most runners). Until that infrastructure exists, the honest state is:
manual runbook, executed on hardware, evidence attached to the PR that
claims the gate. Faking a number here is exactly what TRUTH-02 exists to
prevent.
