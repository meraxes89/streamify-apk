# `media/sync/` — Clock Sync & Scheduling

Distributed timing machinery for multi-device jam sync and scheduled
playback.

## Files

| File | What it is |
|---|---|
| `PrecisionTimeProtocol.kt` | IEEE-1588-style PTP clock offset estimation. |
| `PhaseLockedLoopController.kt` | PLL that converges playback position to the PTP anchor. |
| `ScheduledAudioScheduler.kt` | Sleep-timer / scheduled playback. |
| `ThermalGovernorManager.kt` | Thermal-aware rendering/work throttling. |

The Rust PTP math is in `rust/src/ptp.rs`; these are the Android-side
controllers. Jam-level consensus is `jam/JamEngine`.
