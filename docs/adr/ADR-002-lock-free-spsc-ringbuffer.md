# ADR-002: Lock-free SPSC ringbuffer over the JVM heap

* Status: Accepted
* Date: 2026-11-22
* Verified against: 8b858b1c (staleness protocol in docs/adr/README.md)

## Context

The audio data plane crosses three thread domains: the Rust producer thread
(fetch/decode/resample), the C++/HAL consumer (platform audio callback), and
the JVM (UI, Media3 renderer). Any JVM-heap structure on this path is exposed
to garbage-collection pauses, and any shared cache line between producer and
consumer cores causes coherence traffic that shows up as callback jitter.

Measured budget: DAC presentation-clock interpolation must stay within 5 ms
(`native/include/virtual_hal.h`); underruns are silence-filled, so the ring
must absorb producer hiccups without blocking the consumer. Capacity bounds
observed in `native/include/audio_sink_ringbuffer.h`: sample rates
8,000-192,000 Hz, capacity 64 to 2^22 frames, default 8,192 frames,
256-frame block granularity.

## Decision

Audio crosses the boundary through `AudioSink`, a C++20 single-producer /
single-consumer ringbuffer allocated once in its constructor with
C++17 over-aligned `::operator new[](bytes, std::align_val_t{kCacheLineSize})`.
Key invariants, all verifiable in
`native/include/audio_sink_ringbuffer.h` and
`native/src/audio_sink_ringbuffer.cpp`:

- **Cacheline isolation.** `kCacheLineSize` is 64 bytes, except 128 on
  Apple Silicon aarch64. Producer-owned cursors (`head_`, `frames_written_`,
  overrun counters, high watermark) and consumer-owned cursors (`tail_`,
  `frames_read_`, underrun counters, low watermark) each sit on their own
  `alignas(kCacheLineSize)` line; the flush ack counter deliberately lives on
  the consumer side so a spinning requester never bounces the audio
  callback's line.
- **Acquire/release ordering.** Producer loads `tail_` with acquire, stores
  `head_` with release; consumer loads `head_` with acquire, stores `tail_`
  with release; telemetry counters are relaxed. Free-running `uint64_t`
  cursors masked by `capacity_ - 1` (power-of-two capacity).
- **Generational flush.** `RequestFlush()` bumps a relaxed generation counter
  and waits (100 ms timeout, then lazy apply) for the consumer to ack by
  moving `tail_` to `head_` under acquire/release; flushed reads return
  digital silence and are not counted as underruns. Never called from the
  consumer thread.
- **Zero allocation.** Hot paths are two-segment `memcpy` over
  constructor-allocated storage and scratch. Enforced by
  `native/tests/AllocGuard.h` (global new/delete counters linked into the
  suites) and mirrored on the Rust side by a counting-allocator gate in
  `rust/crates/streamify-playback/tests/test_ring.rs`.

The same protocol is mirrored in Rust (`SpcsAudioRing` in
`rust/crates/streamify-playback/src/sink.rs`: 64-byte aligned cursors,
96-byte little-endian stats wire, magic `0x51AB0001`) and exposed to C/C++
consumers through the frozen 4-symbol C ABI in
`rust/crates/streamify-playback/src/ring_export.rs`
(`streamify_ring_read_f32`, `_occupancy_frames`, `_channels`, `_stats_wire`),
which the CoreAudio render callback consumes.

## Alternatives considered

- **`java.util.concurrent` queues or a JVM-heap ring.** Rejected: GC pauses
  on the render path (ADR-001 context) and no control over false sharing.
- **Mutex-guarded buffer.** Rejected: a blocked consumer means an underrun by
  construction; priority inversion on the audio thread is unbounded.
- **Cross-process shared memory.** Rejected: no requirement for
  cross-process audio; would add mapping/fault complexity for no gain.
- **Rust-only ring, no C++ copy.** Rejected: the desktop HAL render cores are
  C++ and pull directly; keeping one C++ implementation plus a frozen C ABI
  from the Rust ring avoids a second copy on the Android JNI edge.

## Consequences

**Positive.** The consumer path is non-blocking, allocation-free, and
lock-free by construction; SPSC stress covers 10,000,000 frames
(`native/tests/test_audio_sink_ringbuffer.cpp`); telemetry (watermarks,
overrun/underrun counters) is observable without stopping either side.

**Negative.** The protocol requires exactly one producer and one consumer
thread; misuse (concurrent producers) is a data race. The generational flush
adds a cooperative handshake that callers must respect (producer or control
threads only). Two ring implementations (C++ and Rust) must stay
protocol-compatible; the stats wire magic and the golden-vector tests pin
this.

## Compliance

`test_audio_sink_ringbuffer` (SPSC stress, flush protocol, AllocGuard),
`test_ring.rs` (allocation gate), and the JNI sink bridge test suite run in
CI; the 96-byte stats wire layout is pinned by
`native/include/streamify_audio_sinks.h` and the legacy smoke lane.

---
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
