// ============================================================================
//  test_ring_abi_conformance.cc — Sovereign HAL Phase 0 (D3): the C++ twin
//  of rust/crates/streamify-playback/tests/test_ring_abi_conformance.rs.
//
//  Both suites pin the SAME frozen contract from opposite sides of the
//  edge, so a drift in either implementation fails CI on the lane that
//  catches it first:
//
//    1. COMPILE-TIME ABI PINS — static_asserts on the 96-byte stats wire
//       (magic, offsets, sizes) and the alignment discipline
//       (alignas(kCacheLineSize) == 64 on the non-Apple-Silicon targets
//       this suite compiles for, including armv7-a where 64-byte
//       alignment is the FALSE-SHARING GUARANTEE, not a hint).
//    2. WIRE ROUND-TRIP — WriteSinkStatsBuffer at the frozen offsets,
//       byte-for-byte against hand-decoded expectations, matching the
//       Rust to_wire image (the JNI/JNA consumers' layout).
//    3. FLUSH / UNDERRUN / OVERRUN SEMANTICS — the AMP-10 cooperative
//       protocol: one event per short read/write, flush is never an
//       underrun, generation ack, watermarks monotone.
//    4. ZERO-ALLOCATION EDGE — AllocGuard over WriteFloat /
//       ReadFramesF32 / RequestFlush / GetStats / WriteSinkStatsBuffer
//       (the C++ counting-allocator discipline from AllocGuard.h).
//    5. 32-BIT SAFETY WITNESS — the geometry bounds that keep the ring
//       safe on ILP32 (armv7-a): capacity*channels*f32 fits u32; size_t
//       round-trips the frame counts the Rust edge speaks.
//
//  Standalone main() (registered as its own CTest target
//  ring_abi_conformance_test_suite so it can run in the cpp-sanitizers
//  matrix AND cross-compiled for armv7 QEMU runs in the Sovereign HAL
//  workflow's Gate 2).
// ============================================================================

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

#include "../include/audio_sink_ringbuffer.h"
#include "../include/streamify_audio_sinks.h"

#include "AllocGuard.h"

using streamify::sink::AudioSink;
using streamify::sink::SinkStats;
using streamify::sink::WriteSinkStatsBuffer;
// The wire-offset constants (single standalone TU — plain usings keep the
// static_asserts readable, mirroring the C++ header's own names).
using streamify::sink::kSinkStatsOffCapacity;
using streamify::sink::kSinkStatsOffChannelsIn;
using streamify::sink::kSinkStatsOffChannelsOut;
using streamify::sink::kSinkStatsOffFramesRead;
using streamify::sink::kSinkStatsOffFramesWritten;
using streamify::sink::kSinkStatsOffFullness;
using streamify::sink::kSinkStatsOffFullnessPct;
using streamify::sink::kSinkStatsOffHighWatermark;
using streamify::sink::kSinkStatsOffLatencyMs;
using streamify::sink::kSinkStatsOffLayoutBytes;
using streamify::sink::kSinkStatsOffLowWatermark;
using streamify::sink::kSinkStatsOffMagic;
using streamify::sink::kSinkStatsOffOverrunDropped;
using streamify::sink::kSinkStatsOffOverruns;
using streamify::sink::kSinkStatsOffRateIn;
using streamify::sink::kSinkStatsOffRateOut;
using streamify::sink::kSinkStatsOffReserved;
using streamify::sink::kSinkStatsOffSequence;
using streamify::sink::kSinkStatsOffUnderruns;
using streamify::sink::kSinkStatsWireBytes;
using streamify::sink::kSinkStatsWireMagic;

static int g_failures = 0;

#define CHECK(cond, msg)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            std::printf("    FAIL %s:%d: %s\n", __FILE__, __LINE__, msg);   \
            ++g_failures;                                                   \
        }                                                                   \
    } while (0)

// ─────────────────────────────────────────────────────────────────────────────
// 1. Compile-time ABI pins (the C++ side of sink.rs's const asserts).
//    These fire at BUILD time on every lane that compiles this file —
//    x86_64 host and the armv7-a cross build alike.
// ─────────────────────────────────────────────────────────────────────────────
static_assert(streamify::sink::kSinkStatsWireMagic == 0x51AB0001u,
              "frozen wire magic");
static_assert(streamify::sink::kSinkStatsWireBytes == 96,
              "frozen wire size");
static_assert(kSinkStatsOffLayoutBytes == kSinkStatsOffMagic + 4, "+4");
static_assert(kSinkStatsOffCapacity == kSinkStatsOffLayoutBytes + 4, "+8");
static_assert(kSinkStatsOffRateIn == kSinkStatsOffCapacity + 4, "+12");
static_assert(kSinkStatsOffRateOut == kSinkStatsOffRateIn + 4, "+16");
static_assert(kSinkStatsOffChannelsIn == kSinkStatsOffRateOut + 4, "+20");
static_assert(kSinkStatsOffChannelsOut == kSinkStatsOffChannelsIn + 4, "+24");
static_assert(kSinkStatsOffFullness == kSinkStatsOffChannelsOut + 4, "+28");
static_assert(kSinkStatsOffFramesWritten == kSinkStatsOffFullness + 4, "+32");
static_assert(kSinkStatsOffFramesRead == kSinkStatsOffFramesWritten + 8, "+40");
static_assert(kSinkStatsOffUnderruns == kSinkStatsOffFramesRead + 8, "+48");
static_assert(kSinkStatsOffOverruns == kSinkStatsOffUnderruns + 8, "+56");
static_assert(kSinkStatsOffOverrunDropped == kSinkStatsOffOverruns + 8, "+64");
static_assert(kSinkStatsOffHighWatermark == kSinkStatsOffOverrunDropped + 8, "+72");
static_assert(kSinkStatsOffLowWatermark == kSinkStatsOffHighWatermark + 4, "+76");
static_assert(kSinkStatsOffFullnessPct == kSinkStatsOffLowWatermark + 4, "+80");
static_assert(kSinkStatsOffLatencyMs == kSinkStatsOffFullnessPct + 4, "+84");
static_assert(kSinkStatsOffSequence == kSinkStatsOffLatencyMs + 4, "+88");
static_assert(kSinkStatsOffReserved == kSinkStatsOffSequence + 4, "+92");
static_assert(kSinkStatsOffReserved + 4 == kSinkStatsWireBytes, "96 bytes total");

// The u64 cursor atomics must be lock-free on every HAL target — the same
// pin the Rust side enforces with AtomicU64::is_lock_free() at compile
// time. On armv7-a with NEON (the Phase 0 target) this holds; a toolchain
// where it wouldn't must fail the build, not the user.
static_assert(std::atomic<uint64_t>::is_always_lock_free,
              "SPSC u64 cursors must be lock-free (RT contract)");

// The cacheline alignment discipline: this suite compiles for the classic
// 64-byte line targets (x86_64 Linux/Windows, generic ARM incl. armv7-a
// Android). Apple Silicon's 128 uses kCacheLineSize==128 via the header's
// target gate — both must be a multiple of 64 and at least 64.
static_assert(streamify::sink::kCacheLineSize >= 64,
              "cacheline size must be >= 64 on every HAL target");
static_assert(streamify::sink::kCacheLineSize % 64 == 0,
              "cacheline size must be a multiple of the classic 64-byte line");

// The alignment the ring's storage is over-allocated with (C++17 aligned
// new, MP-C09) must be honored by the compiler's alignof — the armv7-a
// audit's core question, answered at compile time.
struct AlignWitness {
    alignas(streamify::sink::kCacheLineSize) std::atomic<uint64_t> cursor;
};
static_assert(alignof(AlignWitness) >= 64,
              "alignas(kCacheLineSize) must yield >= 64 on ILP32 too");

// 32-bit safety witness: the maximum geometry fits u32 and (on ILP32)
// inside a size_t without truncation — the Rust edge's checked-multiply
// guards the hostile-caller case; this pins the legitimate-geometry case.
static_assert((streamify::sink::kSinkMaxCapacityFrames * 2 * sizeof(float)) < UINT32_MAX,
              "max ring geometry must fit 32-bit size_t");

// ─────────────────────────────────────────────────────────────────────────────
// Wire decode helpers (little-endian, mirroring the Rust test's rd_* fns).
// ─────────────────────────────────────────────────────────────────────────────
static uint32_t rd_u32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
static uint64_t rd_u64(const uint8_t* p) {
    return static_cast<uint64_t>(rd_u32(p)) | (static_cast<uint64_t>(rd_u32(p + 4)) << 32);
}
static float rd_f32(const uint8_t* p) {
    uint32_t bits = rd_u32(p);
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Wire round-trip at the frozen offsets.
// ─────────────────────────────────────────────────────────────────────────────
static void test_wire_roundtrip() {
    std::printf("  [ring-abi] wire round-trip at the frozen offsets...\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 1024;
    AudioSink sink(cfg);
    CHECK(sink.valid(), "sink constructs");

    // Some occupancy: 64 frames in, none out.
    std::vector<float> batch(64 * 2, 0.25f);
    sink.WriteFloat(batch.data(), batch.size());
    CHECK(sink.capacity_frames() >= 1024, "capacity pow2-rounded");

    uint8_t wire[96];
    CHECK(WriteSinkStatsBuffer(sink, wire, sizeof(wire)), "wire write ok");

    CHECK(rd_u32(wire + kSinkStatsOffMagic) == 0x51AB0001u, "magic +0");
    CHECK(rd_u32(wire + kSinkStatsOffLayoutBytes) == 96, "layout bytes +4");
    CHECK(rd_u32(wire + kSinkStatsOffCapacity) == sink.capacity_frames(), "capacity +8");
    CHECK(rd_u32(wire + kSinkStatsOffRateIn) == 48000, "rate in +12");
    CHECK(rd_u32(wire + kSinkStatsOffRateOut) == 48000, "rate out +16");
    CHECK(rd_u32(wire + kSinkStatsOffChannelsIn) == 2, "ch in +20");
    CHECK(rd_u32(wire + kSinkStatsOffChannelsOut) == 2, "ch out +24");
    CHECK(rd_u32(wire + kSinkStatsOffFullness) == 64, "fullness +28");
    CHECK(rd_u64(wire + kSinkStatsOffFramesWritten) == 64, "frames written +32");
    CHECK(rd_u64(wire + kSinkStatsOffFramesRead) == 0, "frames read +40");
    CHECK(rd_u64(wire + kSinkStatsOffUnderruns) == 0, "underruns +48");
    CHECK(rd_u64(wire + kSinkStatsOffOverruns) == 0, "overruns +56");
    CHECK(rd_u64(wire + kSinkStatsOffOverrunDropped) == 0, "dropped +64");
    CHECK(rd_u32(wire + kSinkStatsOffHighWatermark) == 64, "high watermark +72");
    CHECK(rd_u32(wire + kSinkStatsOffLowWatermark) == sink.capacity_frames(),
          "low watermark starts at capacity +76");
    // Reserved tail zeroed.
    bool reserved_zero = true;
    for (size_t i = kSinkStatsOffReserved; i < 96; ++i) reserved_zero = reserved_zero && (wire[i] == 0);
    CHECK(reserved_zero, "reserved +92 zeroed");
    // Sequence bumps across calls.
    uint32_t seq1 = rd_u32(wire + kSinkStatsOffSequence);
    uint8_t wire2[96];
    WriteSinkStatsBuffer(sink, wire2, sizeof(wire2));
    CHECK(rd_u32(wire2 + kSinkStatsOffSequence) == seq1 + 1, "sequence +88 bumps");
    // Short buffer refused.
    uint8_t shortbuf[95];
    CHECK(!WriteSinkStatsBuffer(sink, shortbuf, sizeof(shortbuf)), "95-byte buffer refused");
    // Null refused.
    CHECK(!WriteSinkStatsBuffer(sink, nullptr, 96), "null dst refused");

    // Latency derivation: 64 frames @ 48 kHz == 1.333 ms.
    const float lat = rd_f32(wire + kSinkStatsOffLatencyMs);
    CHECK(std::fabs(lat - (64.0f * 1000.0f / 48000.0f)) < 0.01f, "latency +84 derivation");
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Flush / underrun / overrun semantics (AMP-10 protocol parity with Rust).
// ─────────────────────────────────────────────────────────────────────────────
static void test_counter_semantics() {
    std::printf("  [ring-abi] flush/underrun/overrun counter semantics...\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 256;
    AudioSink sink(cfg);
    SinkStats st;

    // Fill to capacity. WriteFloat is lossless-and-non-blocking: it commits
    // only whole blocks that fit (AudioTrack#write(NON_BLOCKING) parity) —
    // refused frames are NOT consumed (the caller retries), so
    // overrun_dropped_frames stays 0 (a safety net, by contract).
    std::vector<float> fill(256 * 2, 0.5f);
    CHECK(sink.WriteFloat(fill.data(), fill.size()) == fill.size(),
          "full write accepted");
    // One more partial-block write: block cannot fit → short return, ONE
    // overrun event, ZERO dropped (the refused frames were not consumed).
    std::vector<float> extra(8 * 2, 0.5f);
    const size_t consumed = sink.WriteFloat(extra.data(), extra.size());
    CHECK(consumed == 0, "refused block consumes nothing (retry contract)");
    sink.GetStats(&st);
    CHECK(st.overrun_events == 1, "one overrun per refused block");
    CHECK(st.overrun_dropped_frames == 0,
          "dropped stays 0 — refusal is retry, not loss");

    // Drain fully: a 128-frame read is fully served, leaving 128.
    std::vector<float> out(128 * 2);
    CHECK(sink.ReadFramesF32(out.data(), 128) == 128, "first 128 served");
    sink.GetStats(&st);
    CHECK(st.underrun_events == 0, "fully-served read is not an underrun");
    // Drain the remaining 128.
    CHECK(sink.ReadFramesF32(out.data(), 128) == 128, "second 128 served");
    // NOW a read against an empty ring → one underrun, 0 served.
    std::vector<float> out2(16 * 2);
    CHECK(sink.ReadFramesF32(out2.data(), 16) == 0, "starved read returns 0");
    sink.GetStats(&st);
    CHECK(st.underrun_events == 1, "one underrun per short read");

    // Flush: never an underrun; the consumer acks on the next read.
    sink.WriteFloat(fill.data(), 32 * 2);
    sink.RequestFlush();
    std::vector<float> out3(8 * 2);
    CHECK(sink.ReadFramesF32(out3.data(), 8) == 0, "flush pass serves 0");
    sink.GetStats(&st);
    CHECK(st.underrun_events == 1, "flush is not starvation (count unchanged)");
    CHECK(sink.flush_ack_generation() == sink.flush_generation(), "generation acked");
    // Watermarks monotone.
    CHECK(st.high_watermark_frames >= 256, "high watermark tracked");
    CHECK(st.low_watermark_frames == 0, "low watermark hit empty");
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Zero allocation on the edge (AllocGuard; the counting TU is
//    tests/alloc_guard_override.cc — the house pattern, one definition
//    per binary, exactly like test_audio_sink_ringbuffer).
// ─────────────────────────────────────────────────────────────────────────────
static void test_zero_allocation_edge() {
    std::printf("  [ring-abi] zero-allocation edge under AllocGuard...\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 512;
    AudioSink sink(cfg);

    std::vector<float> batch(128 * 2, 0.5f);
    std::vector<float> out(64 * 2);
    uint8_t wire[96];

    // Warmup every path once (lazy internals, first-touch).
    for (int i = 0; i < 4; ++i) {
        sink.WriteFloat(batch.data(), batch.size());
        sink.ReadFramesF32(out.data(), 64);
        sink.RequestFlush();
        sink.ReadFramesF32(out.data(), 64);  // service the flush
        WriteSinkStatsBuffer(sink, wire, sizeof(wire));
    }

    {
        streamify_test::AllocGuard guard;
        for (int i = 0; i < 64; ++i) {
            sink.WriteFloat(batch.data(), batch.size());
            sink.ReadFramesF32(out.data(), 64);
            WriteSinkStatsBuffer(sink, wire, sizeof(wire));
        }
        sink.RequestFlush();
        sink.ReadFramesF32(out.data(), 64);
        CHECK(guard.count() == 0, "edge paths must not allocate");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Address-level cacheline separation (the 64-bit runtime witness; on
//    ILP32/QEMU the compile-time alignment pins above carry the guarantee).
// ─────────────────────────────────────────────────────────────────────────────
static void test_cacheline_separation_runtime() {
    std::printf("  [ring-abi] runtime alignment witness...\n");
    // Two rings must not share a cacheline with their first cursor: this is
    // a smoke witness, not the layout proof (the class's own alignas
    // members carry that). Verifying the allocator honors over-aligned
    // storage: C++17 aligned new must return storage aligned >= 64.
    auto* probe = new AlignWitness{};
    const auto addr = reinterpret_cast<uintptr_t>(probe);
    CHECK(addr % 64 == 0, "aligned new honors 64-byte alignment");
    delete probe;

    // The lock-free witness at runtime (32-bit targets: the is_always_lock_free
    // static_assert already fired at compile time; this prints the state).
    std::atomic<uint64_t> probe_cursor{0};
    std::printf("    atomic<uint64_t> lock-free: %s\n",
                probe_cursor.is_lock_free() ? "yes" : "NO");
    CHECK(probe_cursor.is_lock_free(), "runtime lock-free witness");
}

int run_ring_abi_conformance_tests() {
    std::printf("[ring-abi] Sovereign HAL Phase 0 conformance suite\n");
    test_wire_roundtrip();
    test_counter_semantics();
    test_zero_allocation_edge();
    test_cacheline_separation_runtime();
    if (g_failures == 0) {
        std::printf("[ring-abi] ALL PASSED\n");
        return 0;
    }
    std::printf("[ring-abi] %d FAILURE(S)\n", g_failures);
    return 1;
}

int main() {
    return run_ring_abi_conformance_tests();
}
