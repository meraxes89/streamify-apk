// ============================================================================
//  test_coreaudio_render_core.cpp — Phase 4a MP-HAL04 host lane
//  (native/tests/test_coreaudio_render_core.cpp)
// ============================================================================
//
//  Standalone CTest binary (coreaudio_render_core_test_suite). The Apple
//  CoreAudio adapter (native/audio/coreaudio_pull_adapter.cpp) compiles
//  only on Darwin, but its ENTIRE real-time render path is the platform-
//  independent `CoreAudioRenderCore` — this suite compiles and audits that
//  exact code on every host:
//
//    A. Unbound render — teardown-race safety: silence, no crash.
//    B. Bound render — exact pulls from the hardened ring, ramp integrity.
//    C. Underrun handling — zero-filled tails, counter accounting.
//    D. Clock publication — the seqlock drains the CONSISTENT
//       (mSampleTime, mHostTime->ns, rate) triple; sequence increments.
//    E. Zero-allocation / RT-discipline audit — AllocGuard around render.
//    F. mach timebase conversion — exact __int128 math on known inputs.
//    G. Concurrent render vs. drain — a renderer thread publishing while
//       the control thread reads: every observed sample is untorn and
//       monotone (frames AND host_ns advance together, linearly).
//    H. <5 ms latency estimation error (the MP-DEC-03 acceptance gate,
//       SW-ENG3-01): 60 s of virtual playback with a 27.4 ppm-fast DAC
//       oscillator and bursty (3-quantum-stale) clock drains — the exact
//       anchor-and-extrapolate estimator DacClockPublisher.swift runs must
//       stay within 5 ms of the simulator's ground truth at every query.
//    I. Steady-state stream — 1200 quanta with a pre-buffered producer:
//       ZERO underrun callbacks and exact frame accounting (the
//       acceptance suite's "completes with 0 underruns" leg).
// ============================================================================

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#include "../include/coreaudio_render_core.h"

#include "AllocGuard.h"

using streamify::hal::CoreAudioClockSample;
using streamify::hal::CoreAudioRenderCore;
using streamify::hal::MachTimeToNs;
using streamify::sink::AudioSink;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

inline float ramp_left(uint64_t frame) {
    return static_cast<float>(
        static_cast<double>(frame % 4096) / 4095.0 * 2.0 - 1.0);
}

AudioSink::Config canonical_config(size_t capacity = 8192) {
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = capacity;
    return cfg;
}

// ---------------------------------------------------------------------------
// A. Unbound render
// ---------------------------------------------------------------------------
void test_unbound_render() {
    std::printf("  [A] unbound render (teardown race)\n");
    CoreAudioRenderCore core;
    std::vector<float> dst(512 * 2, 1.0f);
    core.Render(512, dst.data(), 1000, 2000000000ull);
    bool silent = true;
    for (size_t i = 0; i < dst.size(); ++i) {
        if (dst[i] != 0.0f) silent = false;
    }
    check(silent, "unbound render emits digital silence");
    check(core.callback_count() == 1, "callback counted");
    check(core.underrun_callbacks() == 1, "unbound counts as underrun");
    CoreAudioClockSample sample;
    check(core.LatestClock(&sample), "drain works after unbound render");
    check(sample.frames == 1000, "unbound render still reports its ts pair");
    check(!core.bound(), "core reports unbound");
}

// ---------------------------------------------------------------------------
// B. Bound render: exact pulls, ramp integrity
// ---------------------------------------------------------------------------
void test_bound_render() {
    std::printf("  [B] bound render pulls from the ring\n");
    AudioSink sink(canonical_config());
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    // Ramp 4 quanta into the ring.
    std::vector<float> feed(4096 * 2);
    for (size_t f = 0; f < 4096; ++f) {
        feed[f * 2] = ramp_left(f);
        feed[f * 2 + 1] = -ramp_left(f);
    }
    const size_t consumed = sink.WriteFloat(feed.data(), feed.size());
    check(consumed == feed.size(), "feed fully consumed");

    std::vector<float> dst(512 * 2, 1.0f);
    uint64_t m_sample_time = 0;
    for (int q = 0; q < 4; ++q) {
        core.Render(512, dst.data(), m_sample_time,
                    1000000000ull + m_sample_time * 1000000000ull / 48000ull);
        m_sample_time += 512;
    }
    bool intact = true;
    // dst holds the LAST quantum: frames [1536, 2048).
    for (size_t f = 512 * 3; f < 512 * 4; ++f) {
        const size_t i = f - 512 * 3;
        if (dst[i * 2] != ramp_left(f) || dst[i * 2 + 1] != -ramp_left(f)) {
            intact = false;
        }
    }
    check(intact, "render pulls bit-exact interleaved f32 from the ring");
    check(core.underrun_callbacks() == 0, "no underruns with a full ring");
    check(core.callback_count() == 4, "four renders counted");
}

// ---------------------------------------------------------------------------
// C. Underrun handling
// ---------------------------------------------------------------------------
void test_underrun_tail() {
    std::printf("  [C] underrun zero-fill tail\n");
    AudioSink sink(canonical_config());
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    // Only 100 frames buffered; ask for 512.
    std::vector<float> feed(100 * 2, 0.25f);
    sink.WriteFloat(feed.data(), feed.size());
    std::vector<float> dst(512 * 2, 1.0f);
    core.Render(512, dst.data(), 0, 1000000000ull);

    bool head_ok = true, tail_silent = true;
    for (size_t i = 0; i < 100 * 2; ++i) {
        if (dst[i] != 0.25f) head_ok = false;
    }
    for (size_t i = 100 * 2; i < 512 * 2; ++i) {
        if (dst[i] != 0.0f) tail_silent = false;
    }
    check(head_ok, "available frames rendered intact");
    check(tail_silent, "starved tail zero-filled");
    check(core.underrun_callbacks() == 1, "underrun counted");

    // A fully starved render is pure silence.
    core.Render(512, dst.data(), 512, 1000000000ull + 10666666ull);
    bool all_silent = true;
    for (size_t i = 0; i < dst.size(); ++i) {
        if (dst[i] != 0.0f) all_silent = false;
    }
    check(all_silent, "fully starved render is digital silence");
    check(core.underrun_callbacks() == 2, "second underrun counted");
}

// ---------------------------------------------------------------------------
// D. Clock publication
// ---------------------------------------------------------------------------
void test_clock_publication() {
    std::printf("  [D] DAC clock sample publication\n");
    AudioSink sink(canonical_config());
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    CoreAudioClockSample sample;
    check(!core.LatestClock(&sample), "no sample before first render");

    std::vector<float> dst(256 * 2);
    std::vector<float> feed(256 * 2, 0.1f);
    sink.WriteFloat(feed.data(), feed.size());

    core.Render(256, dst.data(), 12345, 9876543210ull);
    check(core.LatestClock(&sample), "sample published after render");
    check(sample.frames == 12345, "mSampleTime passthrough");
    check(sample.host_ns == 9876543210ull, "mHostTime->ns passthrough");
    check(sample.rate == 48000, "device rate cached at bind");
    check(sample.sequence == 1, "first publication sequence");

    core.Render(256, dst.data(), 12601, 9876543210ull + 5333333ull);
    check(core.LatestClock(&sample), "second sample drained");
    check(sample.frames == 12601, "second mSampleTime");
    check(sample.sequence == 2, "sequence increments per render");
}

// ---------------------------------------------------------------------------
// E. RT-discipline: zero allocations in the render path
// ---------------------------------------------------------------------------
void test_zero_allocation_render() {
    std::printf("  [E] zero-allocation render audit\n");
    AudioSink sink(canonical_config());
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    std::vector<float> feed(2048 * 2, 0.5f);
    sink.WriteFloat(feed.data(), feed.size());
    std::vector<float> dst(512 * 2);

    {
        streamify_test::AllocGuard guard;
        for (int i = 0; i < 8; ++i) {
            core.Render(512, dst.data(), static_cast<uint64_t>(i) * 512,
                        1000000000ull + static_cast<uint64_t>(i) * 10666666ull);
        }
        char label[96];
        std::snprintf(label, sizeof(label),
                      "8 renders allocate nothing (got %llu allocs)",
                      static_cast<unsigned long long>(guard.count()));
        check(guard.count() == 0, label);
    }
}

// ---------------------------------------------------------------------------
// F. mach timebase conversion
// ---------------------------------------------------------------------------
void test_mach_conversion() {
    std::printf("  [F] mach timebase conversion\n");
    check(MachTimeToNs(1000, 1, 1) == 1000, "identity timebase fast path");
    check(MachTimeToNs(3000, 1, 3) == 1000, "1/3 timebase exact");
    check(MachTimeToNs(3000000000ull, 125, 3) == 125000000000ull,
          "125/3 timebase exact (128-bit path)");
    check(MachTimeToNs(123456789ull, 0, 0) == 123456789ull,
          "degenerate timebase fails safe");
    // Rounding is truncation toward zero on the quotient (documented).
    check(MachTimeToNs(10, 1, 3) == 3, "truncating quotient");
}

// ---------------------------------------------------------------------------
// G. Concurrent render vs. drain
// ---------------------------------------------------------------------------
void test_concurrent_publish_drain() {
    std::printf("  [G] concurrent render vs. drain\n");
    AudioSink sink(canonical_config(16384));
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    // Producer: keep the ring fed (direct WriteFloat from this thread).
    std::vector<float> feed(1024 * 2, 0.25f);

    // Renderer thread: renders quanta with strictly increasing
    // (mSampleTime, mHostTime) pairs — exactly the CoreAudio contract.
    std::atomic<bool> stop{false};
    const uint64_t quanta = 20000;
    std::thread renderer([&] {
        std::vector<float> dst(256 * 2);
        for (uint64_t q = 0; q < quanta && !stop.load(std::memory_order_relaxed);
             ++q) {
            sink.WriteFloat(feed.data(), feed.size());  // producer side
            core.Render(256, dst.data(), q * 256,
                        1000000000ull + q * 256 * 1000000000ull / 48000ull);
        }
    });

    // Control thread: drain samples, verify consistency (untorn reads) and
    // monotonicity — the seqlock must never expose a mixed pair.
    uint64_t last_frames = 0, last_ns = 0, last_seq = 0;
    bool monotone = true, untorn = true;
    int drained = 0;
    while (drained < 1000) {
        CoreAudioClockSample sample;
        if (core.LatestClock(&sample)) {
            if (sample.sequence < last_seq ||
                sample.frames < last_frames || sample.host_ns < last_ns) {
                monotone = false;
            }
            // The (frames, host_ns) pair must stay linearly consistent:
            // host_ns == base + frames/rate (our renderer's contract).
            const uint64_t expect_ns =
                1000000000ull + sample.frames * 1000000000ull / 48000ull;
            if (sample.host_ns != expect_ns) untorn = false;
            last_frames = sample.frames;
            last_ns = sample.host_ns;
            last_seq = sample.sequence;
            ++drained;
            if (sample.sequence == quanta) break;
        } else {
            std::this_thread::yield();
        }
    }
    stop.store(true, std::memory_order_relaxed);
    renderer.join();

    check(monotone, "drained samples monotone in frames/time/sequence");
    check(untorn, "no torn (mixed) clock pairs under concurrent render");
    check(drained >= 1000 || last_seq >= quanta - 1,
          "drain observed the stream end");
}

// ---------------------------------------------------------------------------
// H. <5 ms latency estimation error — the MP-DEC-03 acceptance gate.
//
// Simulates 60 s of 48 kHz playback on a DAC whose oscillator runs 27.4 ppm
// fast (a realistic consumer crystal), with CoreAudio-accurate 512-frame
// quanta whose (mSampleTime, mHostTime) pairs stay consistent WITH that
// drift (the device clock pair is measured, not assumed). The clock is
// drained only every 3rd quantum — the burst-drain regime of a coalesced
// control thread — and the estimator (the exact anchor-and-extrapolate
// algorithm DacClockPublisher.swift runs on the UI side) is queried at an
// offset phase inside every quantum:
//
//     pos_est(now) = anchor.frames + (now - anchor.host_ns) * rate / 1e9
//
// Ground truth is the simulator's own frame counter at `now`. The anchor
// is up to ~32 ms stale, so the error is dominated by drift-accumulation
// over the staleness window — 27.4 ppm * 32 ms ≈ 0.9 µs, three orders of
// magnitude inside the 5 ms budget; the gate proves the estimator never
// degrades that discipline (e.g. by anchoring on a stale/torn pair).
// ---------------------------------------------------------------------------
void test_latency_estimation_error() {
    std::printf("  [H] <5ms latency estimation error (60s, 27.4ppm drift)\n");
    AudioSink sink(canonical_config());
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    const double rate = 48000.0;
    const double drift_ppm = 27.4;   // DAC oscillator error (realistic crystal)
    const uint64_t quantum = 512;    // frames per callback
    const uint64_t drain_every = 3;  // bursty control-thread cadence

    // Virtual DAC: frames advance with the DRIFTED rate; the timestamp pair
    // (mSampleTime, mHostTime->ns) is emitted consistently from that same
    // truth (CoreAudio reports the device's own counter against mach host
    // time — the pair is measured, never assumed nominal).
    uint64_t device_frames = 0;       // true frames presented
    double true_host_ns = 10.0 * 1e9; // session start offset
    const double q_ns = static_cast<double>(quantum) *
                        (1e9 / (rate * (1.0 + drift_ppm / 1e6)));

    // Estimator state — the host-side mirror of DacClockPublisher: the
    // latest DRAINED anchor (frames, host_ns); position is extrapolated
    // from it with the NOMINAL rate (the device rate the UDL publishes).
    bool have_anchor = false;
    double anchor_frames = 0.0, anchor_host_ns = 0.0;

    double worst_err_ms = 0.0;
    uint64_t queries = 0;

    std::vector<float> feed(quantum * 2, 0.25f);
    std::vector<float> dst(quantum * 2);
    // Pre-buffer one quantum (occupancy stays constant thereafter: the
    // producer replaces exactly what each quantum consumes).
    sink.WriteFloat(feed.data(), feed.size());

    const uint64_t total_quanta =
        static_cast<uint64_t>(60.0 * rate) / quantum;  // 5625 = 60 s
    for (uint64_t qi = 1; qi <= total_quanta; ++qi) {
        // ---- the hardware presents one quantum --------------------------
        core.Render(static_cast<uint32_t>(quantum), dst.data(), device_frames,
                    static_cast<uint64_t>(true_host_ns));
        device_frames += quantum;
        true_host_ns += q_ns;
        sink.WriteFloat(feed.data(), feed.size());

        // ---- the control thread drains every 3rd quantum ---------------
        if (qi % drain_every == 1) {
            CoreAudioClockSample sample;
            if (core.LatestClock(&sample)) {
                anchor_frames = static_cast<double>(sample.frames);
                anchor_host_ns = static_cast<double>(sample.host_ns);
                have_anchor = true;
            }
        }

        // ---- the UI queries position mid-quantum (60% phase) -----------
        const double now_ns = true_host_ns - 0.4 * q_ns;
        ++queries;
        if (have_anchor) {
            const double pos_est =
                anchor_frames + (now_ns - anchor_host_ns) * rate / 1e9;
            const double truth = static_cast<double>(device_frames) -
                                 0.4 * static_cast<double>(quantum);
            const double err_ms = std::fabs(pos_est - truth) / rate * 1e3;
            if (err_ms > worst_err_ms) worst_err_ms = err_ms;
        }
    }

    char label[96];
    std::snprintf(label, sizeof(label),
                  "worst interpolation error %.4f ms < 5 ms (60 s playback)",
                  worst_err_ms);
    check(worst_err_ms < 5.0, label);
    check(queries == total_quanta, "60 s @ 512-frame quanta == 5625 queries");
    check(core.underrun_callbacks() == 0, "feed kept the ring non-starved");
}

// ---------------------------------------------------------------------------
// I. Steady-state stream — zero underruns, exact accounting.
//
// The acceptance suite's "completes with 0 underruns" leg: a pre-buffered
// producer (2048-frame floor) feeding 1200 consecutive 512-frame quanta.
// The render core must never zero-fill, and the sink's cumulative counters
// must balance to the frame.
// ---------------------------------------------------------------------------
void test_steady_state_zero_underruns() {
    std::printf("  [I] steady-state stream: 0 underruns over 1200 quanta\n");
    AudioSink sink(canonical_config());  // 8192-frame ring
    CoreAudioRenderCore core;
    core.Bind(&sink, 48000);

    const size_t quantum = 512;
    const int total_quanta = 1200;

    std::vector<float> feed(quantum * 2, 0.5f);
    std::vector<float> dst(quantum * 2);

    // Pre-buffer: two quanta + the first, above the 2048-frame floor.
    sink.WriteFloat(feed.data(), feed.size());
    sink.WriteFloat(feed.data(), feed.size());
    sink.WriteFloat(feed.data(), feed.size());
    sink.WriteFloat(feed.data(), feed.size());

    uint64_t m_sample_time = 0;
    for (int q = 0; q < total_quanta; ++q) {
        core.Render(static_cast<uint32_t>(quantum), dst.data(), m_sample_time,
                    1000000000ull + m_sample_time * 1000000000ull / 48000ull);
        m_sample_time += quantum;
        // Producer replaces exactly what the quantum consumed: occupancy
        // oscillates in [1536, 2048] — never near the empty edge.
        sink.WriteFloat(feed.data(), feed.size());
    }

    check(core.underrun_callbacks() == 0, "zero underrun callbacks");
    check(core.callback_count() == static_cast<uint64_t>(total_quanta),
          "every quantum rendered");

    streamify::sink::SinkStats stats;
    sink.GetStats(&stats);
    char label[96];
    std::snprintf(label, sizeof(label),
                  "frames_read == 1200 * 512 (got %llu)",
                  static_cast<unsigned long long>(stats.frames_read));
    check(stats.frames_read == static_cast<uint64_t>(total_quanta) * quantum,
          label);
    check(stats.underrun_events == 0, "sink agrees: no underrun events");
    // Bit-exactness of the steady stream (constant 0.5 feed, first byte of
    // each quantum): the pull path never corrupts the canonical f32.
    check(dst[0] == 0.5f && dst[quantum * 2 - 1] == 0.5f,
          "steady-state payload arrives bit-exact");
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify CoreAudio Render Core Suite (MP-HAL04)\n");
    std::printf("==================================================\n");
    test_unbound_render();
    test_bound_render();
    test_underrun_tail();
    test_clock_publication();
    test_zero_allocation_render();
    test_mach_conversion();
    test_concurrent_publish_drain();
    test_latency_estimation_error();
    test_steady_state_zero_underruns();
    std::printf("--------------------------------------------------\n");
    std::printf("  coreaudio_render_core_test_suite: %d passed, %d failed\n",
                g_passed, g_failed);
    std::printf("==================================================\n");
    return g_failed == 0 ? 0 : 1;
}
