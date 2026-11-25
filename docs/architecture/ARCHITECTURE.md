# Streamify architecture

This is the authoritative system map. Statements of fact reference the
source files they were derived from; all numbers below were read out of the
tree at the pinned commit. Speculation is confined to the final section and
labeled as interpretation.

Layer map:

```
Compose UI (composeApp, KMP: Android/JVM/wasmJs)
  |  Koin DI: core loadAllModules -> viewModelModule -> streamifyAllModules
  v
Native bridge: JNI (System.loadLibrary: streamify_core_rs, streamify_native_core)
               + UniFFI contract v2 (package uniffi.streamify)
  v
Sovereign Rust core (rust/, 10 crates, libstreamify_core_rs)
  Tokio resolver / playback producer thread / jam consensus
  |  f32 interleaved stereo, canonical 48 kHz, SPSC write edge
  v
C++20 real-time layer (native/)
  AudioSink SPSC ringbuffer -> MasterChain (LUFS -> balance/mono -> limiter)
  v
Platform HAL
  Android: Media3 renderer -> JNI -> AudioSink -> feeder -> AudioTrack (PCM float)
  Windows: WASAPI (IAudioClient3, event-driven)   Linux: PipeWire (dlopen), ALSA fallback
  macOS/iOS: CoreAudio AUHAL / RemoteIO

Side planes: Room SQLite (WAL, core submodule) <-> Supabase Postgres (RLS, LWW)
             server/ Fastify relay + content-addressed web bundle
```

## 1. UI layer

`composeApp` is the Kotlin Multiplatform UI module (namespace
`com.maxrave.simpmusic`): Compose screens, about 28 typed navigation
destinations, ViewModels, and the Koin graph. `androidApp` is the thin
Android shell hosting `App(viewModel)` from `MainActivity`; the Media3
`MediaSessionService` (`com.maxrave.media3.service.SimpleMediaService`,
`foregroundServiceType="mediaPlayback"`) is declared in the manifest and
implemented in the `core` submodule. `desktopApp` contributes `main()` and
delegates to `runDesktopApp` in `composeApp/src/jvmMain`. The browser target
is `composeApp` wasmJs backed by the `room-wasm-stub` shims. See
[composeApp/README.md](../../composeApp/README.md) and
[androidApp/README.md](../../androidApp/README.md).

Koin loading order (observed in `SimpMusicApplication.kt` and
`DesktopApp.kt`): upstream `loadAllModules(...)` from the core submodule,
then `viewModelModule`, then `loadKoinModules(streamifyAllModules)` from
`streamify-modules`. The extension suite registers only new
`com.streamify.*` types; it does not redefine upstream beans (ADR-004).

## 2. Native bridge

Two parallel contracts cross the JVM boundary:

- **UniFFI contract v2** (`rust/crates/streamify-contract/src/streamify.udl`,
  956 lines; `contract_version()` pinned to 2). Kotlin package
  `uniffi.streamify`, cdylib `streamify_core_rs`. Exposes `StreamifyEngine`,
  `TasteEngine`, `OsMediaIntegration`, callback interfaces
  `EngineEventListener` (monotonic `rev` coalescing on a dedicated
  dispatcher thread) and `SecureStoreDelegate`, and
  `[Error] interface EngineError`. Every method is panic-shielded
  (`catch_unwind` to `EngineError::Internal`).
- **Frozen JNI surface** (C++ `native/jni/jni_bridge.cc`, 86
  `Java_com_streamify_*` entry points, every export inside `JNI_SHIELD`) and
  the 7-symbol audio sink bridge
  (`nativeCreateSink/WriteFloat/ReadDirect/ReadFramesF32/GetStats/Flush/Destroy`).
  Kotlin declares these in `streamify-modules/core-bridge`
  (`NativeBridge.kt`, `NativeAudioSink.kt`); package names are ABI, enforced
  by `scripts/jni_parity_check.py` in `--source` and `--nm` CI gates.

## 3. Sovereign Rust core

Ten workspace crates under `rust/crates/` (about 45.7 kLOC of source):
`streamify-core`, `-audio`, `-playback`, `-storage`, `-jam`, `-jam-crdt`,
`-radio-scorer`, `-resolver`, `-contract`, `-ffi`. The async runtime is a
shared multi-thread Tokio runtime, `worker_threads =
available_parallelism().clamp(4, 8)`, held in a `OnceLock`
(`streamify-resolver/src/resolver.rs`); the P2P mesh lane builds a dedicated
2-worker runtime on `nativeP2pStart` (documented exception). The audio
render path uses no Tokio: the producer runs on a plain OS thread
(`streamify-playback/src/producer.rs`): fetch (64 KiB ranges, 3 retries) ->
decode (symphonia: AAC, ISO-MP4, OGG, PCM, WAV; no Opus) -> resample
(`rubato::SincFixedOut`, 256-tap sinc, canonical 48 kHz) -> LUFS -> SPSC
write. (Historical note: the `rust/` workspace was removed in favor of the pure-Kotlin/Media3 and Desktop `libmpv` pipelines.)

The resolver is a multi-tier YouTube Innertube client: anonymous
`ANDROID_VR` 1.65.10 primary, authenticated `WEB_REMIX` fallback, legacy
client cascade, with RustyPipe as a parallel tier. Signature and
n-parameter transforms run in an embedded QuickJS; PO tokens come from an
optional botguard companion binary; deobfuscation transforms persist in a
SQLite cache; CDN format TTL is 60 s to 6 h.

Jam: PTP estimation with `theta = ((t1-t0)+(t2-t3))/2` and
`delta = ((t3-t0)-(t2-t1))/2`, adaptive EMA filtering, a 1D Kalman PLL
(Q_POS 4.0, Q_VEL 0.05, R_MEAS 36.0; closure within ±2 % speed for errors
<= 150 ms, hard seek above), voting CRDT with floor(n/2)+1 promotion quorum,
Ed25519 governance with epoch fencing, BLAKE3 state hashing, 48-byte
little-endian op wire.

## 4. C++20 real-time layer

`AudioSink` (`native/include/audio_sink_ringbuffer.h`,
`native/src/audio_sink_ringbuffer.cpp`) is an f32 SPSC ringbuffer:
power-of-two capacity (default 8192 frames, bounds 64 to 2^22), free-running
`uint64_t` cursors, per-cursor `alignas(kCacheLineSize)` isolation (64 bytes
x86, 128 bytes Apple Silicon), acquire/release ordering on the head/tail
hand-off, cooperative generational flush (100 ms ack timeout) that never
blocks the consumer, zero heap allocation on hot paths machine-checked by
`AllocGuard` (ADR-002).

`MasterChain` order: LUFS glide gain (ITU-R BS.1770-4: 400 ms blocks, 75 %
overlap, -70 LUFS absolute gate, -10 LU relative gate; default target
-14 LUFS, correction clamp ±12 dB) -> mono downmix / constant-power balance
-> true-peak soft-knee limiter (ceiling -1.0 dBFS, 4 dB knee, C1-continuous
tanh curve, 4x-oversampled sidechain, 3 ms lookahead schedule, 80 ms
release). (Historical note: the `native/` C++20 DSP layer was removed in favor of the pure-Kotlin Media3 `AudioProcessor` pipeline.)

Presentation clock: HAL render cores publish
`(dac_frame_position, sample_rate, monotonic_ns)` through single-writer
seqlocks; `DacPresentationClock` interpolation must stay within 5 ms of
simulated presentation time, enforced by `native/tests/test_virtual_hal.cpp`
including a negative control that fails a deliberately broken estimator.

## 5. Platform HAL

Android audio output has no in-tree AAudio C++ code: the Media3 renderer
writes f32 through JNI into the C++ `AudioSink`, and a feeder thread pulls
into `AudioTrack` (ENCODING_PCM_FLOAT); `AudioTrack.getTimestamp()` / AAudio
timestamps feed `HardwareLatencyProfiler` (128-anchor sliding window, 6 s
half-life, MAD 3-sigma rejection, 5 ms clock-bridge gate). Desktop HALs are
in-tree: WASAPI (IAudioClient3, event-driven, MMCSS "Pro Audio"), PipeWire
via `dlopen("libpipewire-0.3.so.0")` with an ALSA fallback, CoreAudio
AUHAL/RemoteIO. Every render core documents and tests the same invariant:
zero allocations, zero locks, zero system calls on the pull path; underruns
are silence-filled.

## 6. Hybrid storage

Local truth is Room SQLite (entities and DAOs in the `core` submodule, KMP
Room with `BundledSQLiteDriver`; `core.patch` adds index callbacks).
`StreamifySyncEngine` (`composeApp/src/commonMain/kotlin/com/streamify/sync/`)
syncs liked songs, followed artists, and playlists against Supabase Postgres:
local-first writes, echo suppression via `isSyncActive`, deterministic
last-write-wins, batched transactions. The SQL side keeps aggregates
monotonic with `GREATEST()` upserts; RLS policies and the realtime
publications for `listening_sessions` and `jam_ops` are defined in
[supabase/](../../supabase/README.md). Decision record:
[ADR-003](../adr/ADR-003-hybrid-sqlite-supabase-sync.md).

## 7. Server

`server/` is a Fastify 5 relay ("streamify-relay" v3.0.0, Node 20+):
YouTube catalog/search/lyrics/thumbnail endpoints, a stream gateway that
only proxies hosts ending in `.googlevideo.com` with HTTP 206 range
support, admission control and single-flight request coalescing, and
content-addressed delivery of the Compose-wasmJs web bundle (SHA-256
addressed `bundles/<sha256>/` behind an atomic `public` symlink, per-file
Merkle manifest, boot gate refuses to start on an unverified bundle).
Benchmark (2026-11-20, Android tablet host): 792.8 req/s loopback peak,
P50 1.22 ms loopback, cold upstream resolve 128 ms versus a 30.7x warm
speedup. See [server/README.md](../../server/README.md).

## Threading model

| Thread | Responsibility | Constraint |
|---|---|---|
| HAL render callback (per platform) | Pull frames from `AudioSink`, publish DAC clock | Zero alloc/lock/syscall; seqlock clock publish |
| Rust producer thread | Fetch, decode, resample, LUFS, SPSC write | Not a Tokio worker; bounded retries (3) |
| Tokio workers (4-8) | Resolver HTTP, search, Innertube | 30 s total request timeout |
| Engine event dispatcher | `EngineEventListener` snapshots | Never the audio thread; monotonic `rev` coalescing |
| Control threads | Koin graph, stats, flush requests, clock samplers | Never the sink's consumer thread |

## Measured invariants

- DAC clock interpolation error < 5 ms under adversarial callbacks
  (`native/include/virtual_hal.h`, `test_virtual_hal.cpp`).
- Zero heap allocations on ringbuffer hot paths, machine-checked
  (`native/tests/AllocGuard.h`; Rust counting-allocator gate in
  `rust/crates/streamify-playback/tests/test_ring.rs`).
- SPSC stress: 10,000,000 frames (`test_audio_sink_ringbuffer.cpp`).
- LUFS parity between Rust `lufs.rs` and C++ `LufsNormalizer.cc` pinned by
  shared golden vectors (`scripts/generate_lufs_golden_vectors.py`).
- Jam op encoding pinned byte-for-byte Kotlin to Rust
  (`scripts/generate_jam_golden_vectors.py`).
- JNI parity: every Kotlin `external fun` has a `Java_*` export and vice
  versa (`scripts/jni_parity_check.py`, `--source` and `--nm` CI gates).

## Interpretation (not measured)

The dual JNI + UniFFI bridge exists for migration reasons: the frozen JNI
surface predates the UniFFI contract, and the sink bridge is kept because
its 7-symbol ABI is cheaper to freeze than to move under UniFFI callbacks.
The jam feature currently has two implementations — the Kotlin
`StreamifyJamCoordinator` state machine used by the UI, and the full Rust
jam engine exposed through UniFFI; consolidating them is an open
architectural question recorded in ADR-004's consequences.

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
