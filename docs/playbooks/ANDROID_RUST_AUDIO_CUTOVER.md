# Android Rust-Audio Cutover Runbook (MP-CUT01 → MP-CUT02)

> **Owner:** ENG-3 (platform lane) with ENG-6 (dogfood fleet, F2). **Status:** ARMED —
> C1 landed the JNI sink bridge (`native/src/jni_bridge_audio_sinks.cpp`), so the
> `USE_RUST_AUDIO` dogfood path is live on debug builds. This runbook is the
> execution contract for AHEAD **C2**: the 2-week dogfood window, the evidence
> thresholds, and the MP-CUT02 deletion milestone (legacy Media3 fallback).
>
> **Related:** `AHEAD.md` §4 (C1/C2), `docs/architecture/engines/13_playback_dataplane.md`
> (the drivetrain), `app/src/main/java/com/streamify/app/media/audio/AudioRoutePolicy.kt`
> (the routing ladder), `shared/src/androidMain/.../RustAudioDogfoodPanel.kt` (the
> observability surface).

---

## 0. What is being cut over

| | Legacy (default today) | Rust dogfood lane |
|---|---|---|
| Decoder | Media3 `DefaultAudioSink` chain (MediaCodec → AudioTrack PCM16) | Media3 decodes → `RustMedia3AudioSink` writes f32 into the C++ SPSC registry ring (JNI, C1) |
| Output | AudioTrack PCM16 | Urgent-priority feeder pulls f32 (`nativeReadFramesF32`) → AudioTrack `ENCODING_PCM_FLOAT` low-latency |
| Position truth | Media3 player position | DAC presentation clock → `record_dac_clock` (MP-HAL06) |
| Failure mode | — | Zero-crash ladder: probe → route → pipeline init → runtime; ANY failure degrades to the byte-identical legacy chain (`DogfoodTelemetry.recordLegacyFallback`) |

The dogfood lane is **debug-build + flag gated** (MP-HAL07): `BuildConfig.USE_RUST_AUDIO`
(opt-in via `-Pstreamify.useRustAudio=true`) or the debug-only runtime override in
developer settings. Release builds structurally never install the binding.

## 1. Arming the dogfood window (T-day)

1. Build the fleet slice: `./gradlew :app:assembleDebug -Pstreamify.useRustAudio=true`
   (arm64-v8a primary; x86_64 for emulator sanity).
2. Confirm the route engages on at least one physical device: the dogfood panel
   (`Settings → Developer → Rust audio pipeline`) must read
   `Route: RUST_AUDIO_PULL`, `Pipeline: live (f32 SPSC pull)`, and `Frames pulled`
   must start ticking within one track. `Auto fallbacks: none` is the zero-crash
   guarantee holding.
3. Register the dogfood cohort with ENG-6 (F2): ≥ 5 daily-driver devices,
   ≥ 2 hours listening/day, 14 consecutive days.
4. Snapshot the baseline week BEFORE T-day (same devices, legacy route):
   battery-per-listening-hour, crash-free rate, cold-start to first-audio.

## 2. The telemetry contract (what we watch, where it lives)

All surfaces already exist — this window only READS them:

| Signal | Source | Cadence |
|---|---|---|
| Route decisions + reasons | `AudioRoutePolicy.lastDecision` (recorded per service generation into `DogfoodTelemetry`) | every session |
| Underrun rate (% of bursts) | ring stats wire (`nativeGetStats`, 96-byte 0x51AB0001 image, decoded by `RustAudioSinkJni`) → dogfood panel | rolling |
| Legacy fallbacks (count + reason) | `DogfoodTelemetry.recordLegacyFallback` | every event |
| DAC drift (ppm) | `record_dac_clock` samples → `DacClockEstimation` | rolling |
| Cold start (route → first DAC sample) | dogfood panel cold-start metric | per session |
| Frames pulled / clock samples | dogfood panel counters | cumulative |
| Battery delta vs baseline | ENG-6 fleet harness (batterystats + usagestats dump per charge cycle) | daily |
| Crash-free rate | Play-adjacent crash reporting (SLog spool + ENG-6 collector) | daily |

**Panel truth:** `RustAudioDogfoodPanel` renders exactly these fields — a screenshot
of the panel at T+1d, T+7d and T+14d is the review's primary evidence.

## 3. Go / no-go thresholds (T+14d review)

The cutover review (ENG-3 + ENG-6 + the lead) publishes the dogfood report and
decides against these gates:

| Gate | Threshold | Notes |
|---|---|---|
| Crash-free rate | ≥ baseline − 0.05 pp | ANY native crash attributed to the ring/feeder is an automatic no-go; the zero-crash ladder should make this structurally unreachable |
| Underrun rate | ≤ 0.5% of bursts sustained over any rolling hour | transient spikes on cold track starts are acceptable; sustained > 0.5% means feeder scheduling needs the urgent-audio audit |
| Legacy fallbacks | 0 unexplained | every fallback must map to a logged reason; "unknown" is a defect |
| Battery delta | ≤ +3% per listening-hour vs baseline | the f32 pull path replaces PCM16 conversion; anything worse needs the feeder wake-cadence audit |
| DAC drift | within ± 100 ppm sustained | beyond that, position smoothing is chasing hardware that is lying |
| Cold start | ≤ baseline + 50 ms p50 | the ring adds one hop; more means the feeder spawn is on the wrong thread |

**A no-go does not roll back the fleet** — the flag is opt-in already. It re-arms
a second window after the fix lands.

## 4. MP-CUT02 — deleting the legacy Media3 fallback (T+30d at the earliest)

Preconditions (ALL must hold):

1. The T+14d review passed every gate, published as
   `docs/playbooks/cutover-reports/<date>-android-rust-audio.md`.
2. 30 additional days on the dogfood lane with the gates still green
   (the AHEAD C2 AC: "30 days post-cutover").
3. `jni_parity_check.py --nm` green on the shipped ABI set (the --source gate
   already runs per PR; --nm runs from `build-ref.yml` against the built .so).

The deletion PR (one PR, mechanical, reviewable):

- `AudioRoutePolicy`: the `LEGACY_MEDIA3` arm and the probe ladder collapse to
  always-`RUST_AUDIO_PULL`; the degradation ladder itself STAYS (a torn library
  set must still degrade, now to silence-with-telemetry rather than Media3 —
  see the review note below).
- `PlaybackService`: drop the `DefaultAudioSink` chain and the `rustAudioPipeline`
  null-fallback arm; the belt-and-braces catch-all remains (zero-crash is a
  law, not a phase).
- `RustMedia3AudioSink` becomes the only `AudioSink` implementation.
- `USE_RUST_AUDIO` BuildConfig field, the `streamify.useRustAudio` property and
  the debug runtime override are deleted (MP-CUT02's "flag removed" AC).
- The dogfood panel's "legacy Media3" strings are updated to the post-cutover
  degradation vocabulary.

**Review note (decide at the T+14d review, record in the report):** after
MP-CUT02 a probe failure can no longer fall back to Media3 — the ladder's
terminal state becomes "no audio + loud telemetry" instead of "legacy audio".
That is the intended end state (a torn .set is a build/distribution defect and
must be VISIBLE), but it must be an explicit, recorded decision.

## 5. Post-cutover verification checklist

- [ ] `jni_parity_check.py --source` green (it now guards the ONLY sink ABI).
- [ ] `jni_sink_bridge_test_suite` green in the sanitizer matrix (C3 lane).
- [ ] A 48-hour soak on the fleet with `Auto fallbacks: none` and zero crashes.
- [ ] `AHEAD.md` C2 row marked ✅ with the report link; `BEHIND.md`/`ROADMAP_PHASES.md`
      cross-references reconciled in the same commit (footer rule).
