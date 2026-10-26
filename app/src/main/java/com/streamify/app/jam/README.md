# `jam/` — Distributed Jam Sessions

The lockstep engine that lets multiple devices play the same queue in
sync. Deep-dive: `docs/06_JAM_DISTRIBUTED_ENGINE.md` and
`docs/JAM-ENGINE.md`.

## Files

| File | What it is |
|---|---|
| `JamEngine.kt` | Single-writer playback authority + lockstep FSM (1242 lines — the domain's core). |
| `FractionalIndexEngine.kt` | CRDT-safe fractional ordering for the shared queue. |
| `PlaybackReadyGate.kt` | Event-driven readiness FSM (pure Kotlin, zero JNI). |
| `JamTrackCodec.kt` | JSON codec for jam queue entries over the wire. |

Transport is the engine's own P2P mesh (see `Transport` in `JamEngine.kt`);
timing is `media/sync/`; the guest-side VM is `viewmodel/JamViewModel`
and the screen is `ui/screens/JamSessionScreen`. (The legacy v2/v3
`SupabaseJamClient` realtime stack was removed — it was a dead path
with no callers; the v4 mesh is authoritative.)
