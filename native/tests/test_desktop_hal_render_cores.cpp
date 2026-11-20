// ============================================================================
//  test_desktop_hal_render_cores.cpp — Phase 4b MP-HAL08 / MP-HAL09 host
//  lanes (native/tests/test_desktop_hal_render_cores.cpp)
// ============================================================================
//
//  Standalone CTest binary (desktop_hal_render_core_test_suite). The
//  Windows WASAPI adapter (native/audio/wasapi_pull_adapter.cpp) compiles
//  only on Windows and the Linux PipeWire/ALSA driver
//  (native/audio/pipewire_driver.cpp) only on Linux, but their ENTIRE
//  real-time paths are the platform-independent render cores — this suite
//  compiles and audits that exact code on every host:
//
//    W. WasapiRenderCore — event-driven variable-quantum pulls from the
//       hardened ring, IAudioClock-style clock publication, QPC/position
//       conversion helpers, zero-allocation audit, concurrent drain.
//    P. PipeWireRenderCore — process-callback buffer fills, pw_time-style
//       clock publication (tick-period fraction semantics), zero-allocation
//       audit, concurrent drain.
//    A. AlsaRenderCore — fallback write-window pulls, presented-frames
//       estimate helpers, clock publication.
//    V. VirtualHAL contract drive (the Phase 4a test double — MP-HAL 4b
//       deliverable 3): the Phase 4a VirtualHalSink emulates the hardware
//       DAC presentation clock under adversarial conditions; every desktop
//       core is driven callback-for-callback against it — the double's
//       CONSISTENT (dac_frame_position, monotonic_ns) pairs stand in for
//       the platform timestamp sources (IAudioClock / pw_time /
//       snd_pcm_delay), the cores pull the same producer stream through
//       their own ring, and the DacPresentationClock interpolation must
//       stay < 5 ms of the simulated presentation time — the MP-HAL06
//       acceptance bound — for EVERY desktop flavor, with bit-exact data
//       parity between the core pulls and the simulated DAC output.
// ============================================================================

#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#include "../include/alsa_render_core.h"
#include "../include/pipewire_render_core.h"
#include "../include/virtual_hal.h"
#include "../include/wasapi_render_core.h"

#include "AllocGuard.h"

using streamify::hal::AlsaRenderCore;
using streamify::hal::DacClockInterpolator;
using streamify::hal::PipeWireRenderCore;
using streamify::hal::VirtualHalSink;
using streamify::hal::WasapiRenderCore;
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

// Fill `frames` interleaved stereo frames of the deterministic ramp
// starting at absolute frame `first`.
void fill_ramp(std::vector<float>& buf, uint64_t first, size_t frames) {
    buf.resize(frames * 2);
    for (size_t f = 0; f < frames; ++f) {
        buf[f * 2] = ramp_left(first + f);
        buf[f * 2 + 1] = -ramp_left(first + f);
    }
}

// ---------------------------------------------------------------------------
// W. WasapiRenderCore
// ---------------------------------------------------------------------------
void test_wasapi_unbound() {
    std::printf("  [W1] WASAPI unbound pull (teardown race)\n");
    WasapiRenderCore core;
    std::vector<float> dst(512 * 2, 1.0f);
    const uint32_t got = core.PullFrames(512, dst.data());
    bool silent = true;
    for (size_t i = 0; i < dst.size(); ++i) {
        if (dst[i] != 0.0f) silent = false;
    }
    check(silent, "unbound pull emits digital silence");
    check(got == 0, "unbound pull supplies zero frames");
    check(core.pull_count() == 1, "pull counted");
    check(core.underrun_pulls() == 1, "unbound counts as underrun");
    check(!core.bound(), "core reports unbound");
    streamify::hal::WasapiClockSample sample;
    check(!core.LatestClock(&sample), "no clock before publication");
    // Null destination must not crash (degenerate GetBuffer path).
    core.PullFrames(64, nullptr);
    check(core.pull_count() == 2, "degenerate pull counted, no crash");
}

void test_wasapi_bound_and_underrun() {
    std::printf("  [W2] WASAPI bound pull: exact ramp + starved tail\n");
    AudioSink sink(canonical_config());
    WasapiRenderCore core;
    core.Bind(&sink, 48000);

    std::vector<float> feed;
    fill_ramp(feed, 0, 4096);
    check(sink.WriteFloat(feed.data(), feed.size()) == feed.size(),
          "feed fully consumed");

    // Variable quanta — the WASAPI contract: the event fires and the
    // thread renders (buffer - padding) frames, never a fixed period.
    const uint32_t quanta[4] = {835, 512, 960, 271};
    std::vector<float> dst(1024 * 2, 1.0f);
    uint64_t consumed = 0;
    for (int q = 0; q < 4; ++q) {
        const uint32_t got = core.PullFrames(quanta[q], dst.data());
        check(got == quanta[q], "full quantum pulled (variable size)");
        // Verify the exact ramp slice for this quantum.
        bool intact = true;
        for (uint32_t f = 0; f < quanta[q]; ++f) {
            if (std::fabs(dst[f * 2] - ramp_left(consumed + f)) > 1e-6f ||
                std::fabs(dst[f * 2 + 1] - (-ramp_left(consumed + f))) > 1e-6f) {
                intact = false;
            }
        }
        check(intact, "variable-quantum pull is bit-exact");
        consumed += quanta[q];
    }
    check(core.underrun_pulls() == 0, "no underruns with a full ring");
    check(core.pull_count() == 4, "four pulls counted");
}

void test_wasapi_starved_tail() {
    std::printf("  [W3] WASAPI starved tail zero-fill\n");
    AudioSink sink(canonical_config());
    WasapiRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(100 * 2, 0.25f);
    sink.WriteFloat(feed.data(), feed.size());
    std::vector<float> dst(512 * 2, 1.0f);
    const uint32_t got = core.PullFrames(512, dst.data());
    check(got == 100, "starved pull returns the available frames");
    bool head_ok = true, tail_silent = true;
    for (size_t i = 0; i < 100 * 2; ++i) {
        if (dst[i] != 0.25f) head_ok = false;
    }
    for (size_t i = 100 * 2; i < 512 * 2; ++i) {
        if (dst[i] != 0.0f) tail_silent = false;
    }
    check(head_ok, "available frames rendered intact");
    check(tail_silent, "starved tail zero-filled");
    check(core.underrun_pulls() == 1, "underrun counted");
    check(core.starved_frames() == 412, "starved frame accounting");
}

void test_wasapi_helpers() {
    std::printf("  [W4] WASAPI pure helpers (quantum/QPC/tick scaling)\n");
    // The event-driven quantum (1): buffer - padding, saturating.
    check(WasapiRenderCore::FramesToRender(960, 0) == 960, "empty endpoint");
    check(WasapiRenderCore::FramesToRender(480, 200) == 280, "partial padding");
    check(WasapiRenderCore::FramesToRender(480, 480) == 0, "full padding");
    check(WasapiRenderCore::FramesToRender(480, 600) == 0,
          "lying padding saturates to a no-op cycle");
    // IAudioClock tick scaling: identity when frequency == stream rate.
    check(WasapiRenderCore::ScaleDevicePosition(48000ull, 48000ull, 48000) ==
              48000ull,
          "identity when ticks are frames");
    // A device clock at 2 ticks/frame (freq = 2 * rate).
    check(WasapiRenderCore::ScaleDevicePosition(96000ull, 96000ull, 48000) ==
              48000ull,
          "2x tick frequency halves to frames");
    check(WasapiRenderCore::ScaleDevicePosition(96768ull, 96000ull, 48000) ==
              48384ull,
          "fractional tick scaling truncates");
    check(WasapiRenderCore::ScaleDevicePosition(123ull, 0, 48000) == 123ull,
          "degenerate frequency fails safe");
    // QPC (100 ns units) -> ns: linear + saturating.
    check(WasapiRenderCore::Qpc100ToNs(1) == 100, "1 qpc unit is 100 ns");
    check(WasapiRenderCore::Qpc100ToNs(123456789ull) == 12345678900ull,
          "linear scaling");
    check(WasapiRenderCore::Qpc100ToNs(~0ull) == ~0ull,
          "overflow saturates instead of wrapping");
    check(WasapiRenderCore::Qpc100ToNs(~0ull / 100ull) == (~0ull / 100ull) * 100ull,
          "last representable value converts exactly");
}

void test_wasapi_clock_publication() {
    std::printf("  [W5] WASAPI clock publication (IAudioClock pair)\n");
    AudioSink sink(canonical_config());
    WasapiRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(256 * 2, 0.1f), dst(256 * 2);
    sink.WriteFloat(feed.data(), feed.size());

    // IAudioClock::GetPosition(position, qpc) — device position in
    // IAudioClock::GetFrequency ticks, QPC capture in 100 ns units.
    const uint64_t pos_ticks = 96768ull;      // 2x-frequency device
    const uint64_t frequency = 96000ull;      // ticks/second
    const uint64_t qpc = 104857600ull;        // 10.48576 s in 100 ns
    core.PublishClock(
        WasapiRenderCore::ScaleDevicePosition(pos_ticks, frequency, 48000),
        WasapiRenderCore::Qpc100ToNs(qpc));

    streamify::hal::WasapiClockSample sample;
    check(core.LatestClock(&sample), "sample published");
    check(sample.frames == 48384ull, "ticks scaled to stream frames");
    check(sample.host_ns == 10485760000ull, "QPC converted to ns");
    check(sample.rate == 48000, "stream rate cached at bind");
    check(sample.sequence == 1, "first publication sequence");
    check(core.clock_publications() == 1, "publication counted");

    core.PublishClock(48384ull + 512ull, 10485760000ull + 10666666ull);
    check(core.LatestClock(&sample), "second sample drained");
    check(sample.frames == 48896, "position advances");
    check(sample.sequence == 2, "sequence increments per publication");
}

void test_wasapi_zero_alloc() {
    std::printf("  [W6] WASAPI zero-allocation pull audit\n");
    AudioSink sink(canonical_config());
    WasapiRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(4096 * 2, 0.5f), dst(1024 * 2);
    sink.WriteFloat(feed.data(), feed.size());
    {
        streamify_test::AllocGuard guard;
        // Variable quanta + clock ingestion: one full render cycle's worth.
        const uint32_t quanta[4] = {960, 835, 512, 271};
        for (int i = 0; i < 4; ++i) {
            core.PullFrames(quanta[i], dst.data());
        }
        core.PublishClock(1920ull, 1000000000ull);
        char label[96];
        std::snprintf(label, sizeof(label),
                      "4 pulls + publication allocate nothing (got %llu)",
                      static_cast<unsigned long long>(guard.count()));
        check(guard.count() == 0, label);
    }
}

void test_wasapi_concurrent() {
    std::printf("  [W7] WASAPI concurrent render vs. drain\n");
    AudioSink sink(canonical_config(16384));
    WasapiRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(1024 * 2, 0.25f);

    std::atomic<bool> stop{false};
    const uint64_t cycles = 20000;
    std::thread render([&] {
        std::vector<float> dst(256 * 2);
        uint64_t frames = 0;
        for (uint64_t c = 0; c < cycles && !stop.load(std::memory_order_relaxed);
             ++c) {
            sink.WriteFloat(feed.data(), feed.size());
            const uint32_t got = core.PullFrames(256, dst.data());
            frames += got;
            // IAudioClock-style pair: device position + QPC, consistent.
            const uint64_t now_ns = 1000000000ull + frames * 1000000000ull / 48000ull;
            core.PublishClock(frames, now_ns);
        }
    });

    uint64_t last_frames = 0, last_seq = 0;
    bool monotone = true, untorn = true;
    int drained = 0;
    while (drained < 1000) {
        streamify::hal::WasapiClockSample s;
        if (core.LatestClock(&s)) {
            if (s.sequence < last_seq || s.frames < last_frames) monotone = false;
            // Writer publishes frames*2 as ticks and the estimator scale is
            // fixed here: host_ns == 1s + frames * 1e9 / 48000.
            const uint64_t expect_ns =
                1000000000ull + s.frames * 1000000000ull / 48000ull;
            if (s.host_ns != expect_ns) untorn = false;
            last_frames = s.frames;
            last_seq = s.sequence;
            ++drained;
            if (s.sequence == cycles) break;
        } else {
            std::this_thread::yield();
        }
    }
    stop.store(true, std::memory_order_relaxed);
    render.join();
    check(monotone, "drained samples monotone in frames/sequence");
    check(untorn, "no torn (mixed) clock pairs under concurrent publication");
    check(drained >= 1000 || last_seq >= cycles - 1, "drain observed the end");
}

// ---------------------------------------------------------------------------
// P. PipeWireRenderCore
// ---------------------------------------------------------------------------
void test_pipewire_unbound() {
    std::printf("  [P1] PipeWire unbound process callback\n");
    PipeWireRenderCore core;
    std::vector<float> dst(512 * 2, 1.0f);
    const uint32_t got = core.FillProcessBuffer(512, dst.data());
    bool silent = true;
    for (float f : dst) {
        if (f != 0.0f) silent = false;
    }
    check(silent, "unbound callback emits digital silence");
    check(got == 0, "unbound callback supplies zero frames");
    check(core.underrun_callbacks() == 1, "unbound counts as underrun");
    streamify::hal::PipeWireClockSample s;
    check(!core.LatestClock(&s), "no clock before publication");
}

void test_pipewire_bound_and_underrun() {
    std::printf("  [P2] PipeWire bound fill: exact ramp + starved tail\n");
    AudioSink sink(canonical_config());
    PipeWireRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed;
    fill_ramp(feed, 0, 2048);
    sink.WriteFloat(feed.data(), feed.size());

    // Graph quanta (fixed period per epoch — the negotiated cycle).
    std::vector<float> dst(512 * 2, 1.0f);
    uint64_t consumed = 0;
    for (int q = 0; q < 4; ++q) {
        const uint32_t got = core.FillProcessBuffer(512, dst.data());
        check(got == 512, "full quantum filled");
        bool intact = true;
        for (uint32_t f = 0; f < 512; ++f) {
            if (std::fabs(dst[f * 2] - ramp_left(consumed + f)) > 1e-6f ||
                std::fabs(dst[f * 2 + 1] - (-ramp_left(consumed + f))) > 1e-6f) {
                intact = false;
            }
        }
        check(intact, "fill is bit-exact interleaved f32");
        consumed += 512;
    }
    // Starved tail.
    std::vector<float> starve(256 * 2, 1.0f);
    const uint32_t got = core.FillProcessBuffer(256, starve.data());
    check(got == 0, "empty ring supplies zero frames");
    bool all_silent = true;
    for (float f : starve) {
        if (f != 0.0f) all_silent = false;
    }
    check(all_silent, "fully starved callback is digital silence");
    check(core.underrun_callbacks() == 1, "starved callback counted");
    check(core.starved_frames() == 256, "starved frames accounted");
}

void test_pipewire_ticks_to_frames() {
    std::printf("  [P3] PipeWire TicksToFrames (pw_time.rate semantics)\n");
    // Canonical negotiated case: rate = {1, 48000} — the tick PERIOD is
    // one sample; ticks already count stream frames.
    check(PipeWireRenderCore::TicksToFrames(48000ull, 1, 48000, 48000) ==
              48000ull,
          "identity for the canonical {1, rate} fraction");
    // Foreign graph clock: {1, 96000} behind a 48k stream (ticks at 96k
    // granularity must HALVE).
    check(PipeWireRenderCore::TicksToFrames(96000ull, 1, 96000, 48000) ==
              48000ull,
          "2x graph clock rescales to stream frames");
    check(PipeWireRenderCore::TicksToFrames(48001ull, 1, 96000, 48000) ==
              24000ull,
          "odd tick count truncates toward zero");
    // 44.1k driver behind a 48k stream.
    check(PipeWireRenderCore::TicksToFrames(96000ull, 1, 44100, 48000) ==
              104489ull,
          "44.1k graph clock rescales up (128-bit path)");
    // Degenerate fractions fail safe.
    check(PipeWireRenderCore::TicksToFrames(777ull, 0, 48000, 48000) == 777ull,
          "zero numerator passes ticks through");
    check(PipeWireRenderCore::TicksToFrames(777ull, 1, 0, 48000) == 777ull,
          "zero denominator passes ticks through");
}

void test_pipewire_clock_publication() {
    std::printf("  [P4] PipeWire clock publication (pw_time triple)\n");
    AudioSink sink(canonical_config());
    PipeWireRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(256 * 2, 0.1f), dst(256 * 2);
    sink.WriteFloat(feed.data(), feed.size());
    core.FillProcessBuffer(256, dst.data());

    // pw_time: rate {1, 48000}, ticks 12345, now 9876543210 ns.
    core.PublishClockFromPwTime(12345, 1, 48000, 9876543210ull);
    streamify::hal::PipeWireClockSample s;
    check(core.LatestClock(&s), "sample published");
    check(s.frames == 12345, "ticks pass through (canonical fraction)");
    check(s.host_ns == 9876543210ull, "now passthrough");
    check(s.rate == 48000, "stream rate cached at bind");
    check(s.sequence == 1, "first publication sequence");

    // Foreign-rate fraction rescales INSIDE the publication.
    core.PublishClockFromPwTime(24000, 1, 96000, 9876543210ull + 250000000ull);
    check(core.LatestClock(&s), "second sample drained");
    check(s.frames == 12000, "foreign-rate ticks rescaled on ingest");
    check(s.sequence == 2, "sequence increments");
    check(core.clock_publications() == 2, "publications counted");
}

void test_pipewire_zero_alloc() {
    std::printf("  [P5] PipeWire zero-allocation fill audit\n");
    AudioSink sink(canonical_config());
    PipeWireRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(4096 * 2, 0.5f), dst(512 * 2);
    sink.WriteFloat(feed.data(), feed.size());
    {
        streamify_test::AllocGuard guard;
        for (int i = 0; i < 8; ++i) {
            core.FillProcessBuffer(512, dst.data());
        }
        core.PublishClockFromPwTime(4096, 1, 48000, 1000000000ull);
        char label[96];
        std::snprintf(label, sizeof(label),
                      "8 fills + publication allocate nothing (got %llu)",
                      static_cast<unsigned long long>(guard.count()));
        check(guard.count() == 0, label);
    }
}

void test_pipewire_concurrent() {
    std::printf("  [P6] PipeWire concurrent fill vs. drain\n");
    AudioSink sink(canonical_config(16384));
    PipeWireRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(1024 * 2, 0.25f);
    std::atomic<bool> stop{false};
    const uint64_t cycles = 20000;
    std::thread filler([&] {
        std::vector<float> dst(256 * 2);
        for (uint64_t c = 0; c < cycles && !stop.load(std::memory_order_relaxed);
             ++c) {
            sink.WriteFloat(feed.data(), feed.size());
            core.FillProcessBuffer(256, dst.data());
            core.PublishClockFromPwTime(c * 256, 1, 48000,
                                        1000000000ull +
                                            c * 256 * 1000000000ull / 48000ull);
        }
    });
    uint64_t last_frames = 0, last_seq = 0;
    bool monotone = true, untorn = true;
    int drained = 0;
    while (drained < 1000) {
        streamify::hal::PipeWireClockSample s;
        if (core.LatestClock(&s)) {
            if (s.sequence < last_seq || s.frames < last_frames) monotone = false;
            const uint64_t expect_ns =
                1000000000ull + s.frames * 1000000000ull / 48000ull;
            if (s.host_ns != expect_ns) untorn = false;
            last_frames = s.frames;
            last_seq = s.sequence;
            ++drained;
            if (s.sequence == cycles) break;
        } else {
            std::this_thread::yield();
        }
    }
    stop.store(true, std::memory_order_relaxed);
    filler.join();
    check(monotone, "drained samples monotone");
    check(untorn, "no torn clock pairs under concurrent publication");
}

// ---------------------------------------------------------------------------
// A. AlsaRenderCore
// ---------------------------------------------------------------------------
void test_alsa_window_pull() {
    std::printf("  [A1] ALSA write-window pulls\n");
    AudioSink sink(canonical_config());
    AlsaRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed;
    fill_ramp(feed, 0, 1024);
    sink.WriteFloat(feed.data(), feed.size());

    std::vector<float> dst(256 * 2, 1.0f);
    uint64_t consumed = 0;
    for (int w = 0; w < 4; ++w) {
        const uint32_t got = core.PullWindow(256, dst.data());
        check(got == 256, "full window pulled");
        bool intact = true;
        for (uint32_t f = 0; f < 256; ++f) {
            if (std::fabs(dst[f * 2] - ramp_left(consumed + f)) > 1e-6f) intact = false;
        }
        check(intact, "window pull is bit-exact");
        consumed += 256;
    }
    check(core.underrun_windows() == 0, "no underruns with a fed ring");

    // Starved window (producer exhausted).
    std::vector<float> starve(256 * 2, 1.0f);
    check(core.PullWindow(256, starve.data()) == 0, "starved window empty");
    bool silent = true;
    for (float f : starve) {
        if (std::fabs(f) > 1e-6f) silent = false;
    }
    check(silent, "starved window zero-filled");
    check(core.underrun_windows() == 1, "underrun window counted");
}

void test_alsa_presented_frames() {
    std::printf("  [A2] ALSA PresentedFrames estimate\n");
    check(AlsaRenderCore::PresentedFrames(10000, 2500) == 7500,
          "submitted minus delay");
    check(AlsaRenderCore::PresentedFrames(100, 100) == 0,
          "fully buffered clamps to zero");
    check(AlsaRenderCore::PresentedFrames(100, 1000) == 0,
          "corrupt delay saturates (never underflows)");
}

void test_alsa_clock_publication() {
    std::printf("  [A3] ALSA clock publication (delay pair)\n");
    AudioSink sink(canonical_config());
    AlsaRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(256 * 2, 0.1f), dst(256 * 2);
    sink.WriteFloat(feed.data(), feed.size());
    core.PullWindow(256, dst.data());

    core.PublishClock(
        AlsaRenderCore::PresentedFrames(10000ull, 2500ull), 5000000000ull);
    streamify::hal::AlsaClockSample s;
    check(core.LatestClock(&s), "sample published");
    check(s.frames == 7500, "presented frames estimate");
    check(s.host_ns == 5000000000ull, "monotonic ns passthrough");
    check(s.rate == 48000, "rate cached at bind");
    check(s.sequence == 1, "sequence");

    core.PublishClock(8000, 5000000000ull + 10416666ull);
    check(core.LatestClock(&s), "second drained");
    check(s.sequence == 2, "sequence increments");
    check(core.clock_publications() == 2, "publications counted");
}

void test_alsa_zero_alloc() {
    std::printf("  [A4] ALSA zero-allocation window audit\n");
    AudioSink sink(canonical_config());
    AlsaRenderCore core;
    core.Bind(&sink, 48000);
    std::vector<float> feed(4096 * 2, 0.5f), dst(256 * 2);
    sink.WriteFloat(feed.data(), feed.size());
    {
        streamify_test::AllocGuard guard;
        for (int i = 0; i < 16; ++i) {
            core.PullWindow(256, dst.data());
        }
        core.PublishClock(2048, 1000000000ull);
        char label[96];
        std::snprintf(label, sizeof(label),
                      "16 windows + publication allocate nothing (got %llu)",
                      static_cast<unsigned long long>(guard.count()));
        check(guard.count() == 0, label);
    }
}

// ---------------------------------------------------------------------------
// V. VirtualHAL contract drive (deliverable 3): every desktop core against
//    the Phase 4a adversarial DAC clock double.
// ---------------------------------------------------------------------------
//
// The VirtualHalSink models the hardware: it owns the TRUE DAC timeline
// (frame F presents at epoch_origin + F * 1e9 / rate_true) and reports the
// CONSISTENT pair (first frame of the quantum, its presentation instant)
// per callback. The desktop cores are driven callback-for-callback:
//
//   * the DATA plane: the same producer stream is written to BOTH the
//     double's ring and a plain AudioSink bound to the core; each
//     simulated callback pulls one quantum through the core — the core's
//     output must be BIT-EXACT with the double's rendered quantum (same
//     SPSC discipline, same starvation semantics).
//   * the CLOCK plane: the double's reported pair stands in for the
//     platform timestamp source — WASAPI publishes it through
//     ScaleDevicePosition/Qpc100ToNs semantics (ticks + QPC units),
//     PipeWire through the pw_time tick-fraction semantics, ALSA through
//     the presented-estimate semantics — and the drained samples feed
//     DacClockInterpolator, whose extrapolation must stay < 5 ms from the
//     double's ground-truth presentation time (MP-HAL06 acceptance).

// One contract drive over one core flavor. `flavor` picks the clock-plane
// conversion path:
//   0 = WASAPI  (device ticks at 2x frequency + QPC in 100 ns units)
//   1 = PipeWire (pw_time rate {1, 96000} — foreign graph clock)
//   2 = ALSA    (presented = submitted - delay estimate)
struct ContractReport {
    size_t callbacks = 0;
    size_t data_mismatches = 0;
    double max_interpolation_error_ms = 0.0;
    uint64_t frames_pulled = 0;
};

ContractReport drive_contract(int flavor) {
    ContractReport report;

    // The adversarial double: ±10% fire-time jitter, 1 ms reported-ts
    // grid, +200 ppm DAC drift (the Phase 4a full-clock profile).
    VirtualHalSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 8192;
    cfg.callback_frames = 512;
    // The double's clock model alone carries the adversarial timing here
    // (jitter/quantization/drift below); underruns/overruns come from the
    // scripted producer below.
    cfg.jitter_fraction = 0.10;
    cfg.timestamp_quantum_ns = 1000000;  // 1 ms reported-ts grid
    cfg.drift_ppm = 200;                 // DAC-vs-monotonic drift
    cfg.rng_seed = 0x53545245414D4946ull;
    VirtualHalSink vhal(cfg);

    AudioSink core_sink(canonical_config(8192));
    WasapiRenderCore wcore;
    PipeWireRenderCore pcore;
    AlsaRenderCore acore;
    switch (flavor) {
        case 0: wcore.Bind(&core_sink, 48000); break;
        case 1: pcore.Bind(&core_sink, 48000); break;
        default: acore.Bind(&core_sink, 48000); break;
    }

    DacClockInterpolator estimator;
    std::vector<float> feed(512 * 2), vhal_dst(512 * 2), core_dst(512 * 2);

    // Producer schedule: feed bursts, starve windows, feed again — the
    // clock must track through starvation (position advances through
    // silence on both planes).
    const size_t kCallbacks = 400;
    const size_t kFeedEvery = 2;   // feed two quanta every 2 callbacks
    const size_t kStarveAfter = 300;
    uint64_t next_frame = 0;
    uint64_t alsa_submitted = 0;

    for (size_t cb = 0; cb < kCallbacks; ++cb) {
        if (cb % kFeedEvery == 0 && cb < kStarveAfter) {
            fill_ramp(feed, next_frame, 1024);
            // The SAME stream into both planes.
            vhal.WriteProducerFrames(feed.data(), feed.size());
            core_sink.WriteFloat(feed.data(), feed.size());
            next_frame += 1024;
        }

        // One simulated hardware callback: the double renders its quantum
        // (ground truth) and reports the CONSISTENT pair.
        const streamify::hal::VirtualHalCallback truth =
            vhal.RunCallback(vhal_dst.data(), 512);

        // The desktop core pulls the SAME quantum through its own ring.
        uint32_t got = 0;
        switch (flavor) {
            case 0: got = wcore.PullFrames(512, core_dst.data()); break;
            case 1: got = pcore.FillProcessBuffer(512, core_dst.data()); break;
            default: got = acore.PullWindow(512, core_dst.data()); break;
        }
        report.frames_pulled += got;

        // Data-plane parity: bit-exact against the simulated DAC output.
        if (std::memcmp(vhal_dst.data(), core_dst.data(),
                        512 * 2 * sizeof(float)) != 0) {
            ++report.data_mismatches;
        }

        // Clock-plane publication — flavor semantics over the double's
        // CONSISTENT pair.
        const uint64_t now_ns = vhal.SimTimeNs();
        switch (flavor) {
            case 0: {
                // IAudioClock model: position ticks at 2x frequency,
                // captured in QPC 100 ns units.
                const uint64_t ticks =
                    truth.clock.dac_frame_position * 2ull;
                const uint64_t qpc100 = truth.clock.monotonic_ns / 100ull;
                wcore.PublishClock(
                    WasapiRenderCore::ScaleDevicePosition(ticks, 96000ull,
                                                          48000),
                    WasapiRenderCore::Qpc100ToNs(qpc100));
                break;
            }
            case 1: {
                // pw_time model: a foreign {1, 96000} graph clock.
                const uint64_t graph_ticks =
                    truth.clock.dac_frame_position * 2ull;
                pcore.PublishClockFromPwTime(graph_ticks, 1, 96000,
                                             truth.clock.monotonic_ns);
                break;
            }
            default: {
                // ALSA model: submitted minus device delay; the delay is
                // whatever keeps presented == the double's reported
                // position (a realistic, slowly varying buffer level).
                alsa_submitted += 512;
                const uint64_t delay =
                    alsa_submitted - truth.clock.dac_frame_position;
                acore.PublishClock(
                    AlsaRenderCore::PresentedFrames(alsa_submitted, delay),
                    truth.clock.monotonic_ns);
                break;
            }
        }

        // Drain through the core's seqlock (the control plane) and feed
        // the canonical estimator.
        uint64_t frames = 0, ns = 0;
        uint32_t rate = 0;
        bool have = false;
        switch (flavor) {
            case 0: {
                streamify::hal::WasapiClockSample s;
                if (wcore.LatestClock(&s)) {
                    frames = s.frames;
                    ns = s.host_ns;
                    rate = s.rate;
                    have = true;
                }
                break;
            }
            case 1: {
                streamify::hal::PipeWireClockSample s;
                if (pcore.LatestClock(&s)) {
                    frames = s.frames;
                    ns = s.host_ns;
                    rate = s.rate;
                    have = true;
                }
                break;
            }
            default: {
                streamify::hal::AlsaClockSample s;
                if (acore.LatestClock(&s)) {
                    frames = s.frames;
                    ns = s.host_ns;
                    rate = s.rate;
                    have = true;
                }
                break;
            }
        }
        if (have) {
            streamify::hal::VirtualDacClock c{};
            c.dac_frame_position = frames;
            c.sample_rate = rate;
            c.monotonic_ns = ns;
            estimator.Update(c);

            // MP-HAL06 acceptance: interpolated presentation position vs
            // the double's ground-truth timeline, at the double's NOW.
            const double true_ms =
                vhal.TruePositionFramesAt(now_ns) * 1000.0 / 48000.0;
            double est_ms = 0.0;
            if (estimator.PositionMsAt(now_ns, &est_ms)) {
                const double err = std::fabs(est_ms - true_ms);
                if (err > report.max_interpolation_error_ms) {
                    report.max_interpolation_error_ms = err;
                }
            }
        }
        ++report.callbacks;
    }
    return report;
}

void test_virtualhal_contract() {
    const char* names[3] = {"WASAPI", "PipeWire", "ALSA"};
    for (int flavor = 0; flavor < 3; ++flavor) {
        std::printf("  [V%d] VirtualHAL contract drive: %s\n", flavor + 1,
                    names[flavor]);
        const ContractReport r = drive_contract(flavor);
        char label[160];
        std::snprintf(label, sizeof(label),
                      "%s: 400 callbacks, %zu data mismatches",
                      names[flavor], r.data_mismatches);
        check(r.data_mismatches == 0, label);
        std::snprintf(label, sizeof(label),
                      "%s: interpolation error %.3f ms < 5 ms (got %.3f)",
                      names[flavor], r.max_interpolation_error_ms,
                      r.max_interpolation_error_ms);
        check(r.max_interpolation_error_ms < 5.0, label);
        check(r.frames_pulled > 0, "core pulled real audio");
    }
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify Desktop HAL Render Core Suite\n");
    std::printf("  (MP-HAL08 WASAPI / MP-HAL09 PipeWire+ALSA)\n");
    std::printf("==================================================\n");
    test_wasapi_unbound();
    test_wasapi_bound_and_underrun();
    test_wasapi_starved_tail();
    test_wasapi_helpers();
    test_wasapi_clock_publication();
    test_wasapi_zero_alloc();
    test_wasapi_concurrent();
    test_pipewire_unbound();
    test_pipewire_bound_and_underrun();
    test_pipewire_ticks_to_frames();
    test_pipewire_clock_publication();
    test_pipewire_zero_alloc();
    test_pipewire_concurrent();
    test_alsa_window_pull();
    test_alsa_presented_frames();
    test_alsa_clock_publication();
    test_alsa_zero_alloc();
    test_virtualhal_contract();
    std::printf("--------------------------------------------------\n");
    std::printf("  desktop_hal_render_core_test_suite: %d passed, %d failed\n",
                g_passed, g_failed);
    std::printf("==================================================\n");
    return g_failed == 0 ? 0 : 1;
}
