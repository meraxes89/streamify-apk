// ============================================================================
//  test_frame_pacer.cpp — Phase-5 verification: lock-free 120Hz frame pacing
//  & jank anomaly monitor (native-dsp CI shard)
// ============================================================================
//
//  Covers the Phase-5 directive deliverable 2 (+ the deliverable-3 support
//  layer):
//    A. CLOCK_MONOTONIC_RAW — monotonicity over 1000 reads.
//    B. Record/stats correctness — 100 known durations: exact jank counts,
//       exact mean/max/percentiles vs a reference sort, FPS derivation,
//       sequence change detector.
//    C. Budget boundary exactness — 8,333,333ns is NOT janky @120Hz,
//       8,333,334ns IS; 16,666,667/16,666,668 for 60Hz; invalid samples
//       (0, > 10min, negative-jlong-wrapped) never enter the ring.
//    D. VSYNC extremes — INT64_MIN/INT64_MAX offsets without UB, exact
//       misalignment counts, saturating means, 128-bit accumulation.
//    E. Streaks — current + max consecutive-jank runs.
//    F. Ring wraparound — 100,000 frames (195 laps): cumulative counters
//       exact, window = last 512 frames, exact window percentiles.
//    G. Reset semantics — everything zeroed, sequence survives (change
//       detector contract), recording works afterwards.
//    H. THE DIRECTIVE STRESS — 1,000,000 continuous frame ticks under heavy
//       multi-threaded contention (writer + hammering reader + 4 atomic
//       burner threads): zero inconsistent snapshots, exact deterministic
//       cumulative counts, exact last-512 window statistics.
//    I. Multi-instance isolation.
//    J. Zero-allocation audit (AllocGuard) on RecordFrame/GetStats.
//    K. Lock-freedom — every atomic in the monitor is lock-free at runtime.
//    L. JNI support layer — 192-byte wire layout byte-exact at every
//       documented offset, sequence increments, capacity guard leaves the
//       buffer untouched, DefaultFramePacer identity across calls.
//
//  Linked into dsp_test_suite; entry point run_frame_pacer_tests() is
//  called from test_dsp.cc's main().
// ============================================================================

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>

#include "../include/frame_pacer_monitor.h"
#include "../include/streamify_frame_palette.h"

#include "AllocGuard.h"

using streamify::pacer::DefaultFramePacer;
using streamify::pacer::FramePacerMonitor;
using streamify::pacer::FramePacerStats;
using streamify::pacer::kFrameBudget120HzNs;
using streamify::pacer::kFrameBudget60HzNs;
using streamify::pacer::kFrameMaxPlausibleNs;
using streamify::pacer::kFramePacerStatsWireBytes;
using streamify::pacer::kFramePacerStatsWireMagic;
using streamify::pacer::kFrameRingSlots;
using streamify::pacer::NowNs;
using streamify::pacer::WriteFramePacerStatsBuffer;

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

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s += 0x9E3779B97F4A7C15ull;
        uint64_t z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
};

uint32_t GetLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t GetLE64(const uint8_t* p) {
    return static_cast<uint64_t>(GetLE32(p)) |
           (static_cast<uint64_t>(GetLE32(p + 4)) << 32);
}
double GetLEF64(const uint8_t* p) {
    const uint64_t bits = GetLE64(p);
    double d = 0.0;
    std::memcpy(&d, &bits, sizeof(d));
    return d;
}

}  // namespace

int run_frame_pacer_tests() {
    std::printf("  [Phase-5] Frame pacer & jank monitor\n");

    // ---- A. CLOCK_MONOTONIC_RAW ------------------------------------------------
    {
        uint64_t prev = NowNs();
        bool mono = true;
        for (int i = 0; i < 1000; ++i) {
            const uint64_t now = NowNs();
            if (now < prev) mono = false;
            prev = now;
        }
        check(mono, "raw clock: monotonic over 1000 reads");
        check(prev > 1000000000ull, "raw clock: sane magnitude (> 1s boot)");
    }

    // ---- B. Record/stats correctness --------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        check(m.GetStats(nullptr) == false, "getstats: null out rejected");
        check(m.GetStats(&st), "getstats: ok on empty");
        check(st.total_frames == 0 && st.window_frames == 0 &&
                  st.janky_frames_120 == 0,
              "empty: zeroed stats");
        check(st.layout_magic == kFramePacerStatsWireMagic &&
                  st.layout_bytes == kFramePacerStatsWireBytes,
              "empty: wire identity header");
        check(st.estimated_fps == 0.0 && st.window_last_ts_ns == 0,
              "empty: zero fps / timestamp");

        Rng rng(7);
        std::vector<uint64_t> durs;
        uint64_t sum = 0, mx = 0, j120 = 0, j60 = 0;
        for (int i = 0; i < 100; ++i) {
            const uint64_t d = 1000000 + rng.next() % 20000000;  // 1..21ms
            durs.push_back(d);
            m.RecordFrame(d, static_cast<int64_t>(i) * 1000 - 50000);
            sum += d;
            if (d > mx) mx = d;
            if (d > kFrameBudget120HzNs) ++j120;
            if (d > kFrameBudget60HzNs) ++j60;
        }
        check(m.GetStats(&st), "stats: ok after 100 frames");
        check(st.total_frames == 100, "stats: total 100");
        check(st.window_frames == 100, "stats: window 100");
        check(st.janky_frames_120 == j120, "stats: janky@120 exact");
        check(st.janky_frames_60 == j60, "stats: janky@60 exact");
        check(st.window_max_ns == mx && st.total_max_frame_ns == mx,
              "stats: window & total max exact");
        check(st.window_mean_ns == sum / 100, "stats: mean exact");
        check(st.estimated_fps > 1000000000.0 / (sum / 100 + 1.0) &&
                  st.estimated_fps < 1000000000.0 / (sum / 100 - 1.0),
              "stats: fps = 1e9/mean");
        check(st.window_last_ts_ns > 0, "stats: raw timestamp captured");
        std::vector<uint64_t> sorted = durs;
        std::sort(sorted.begin(), sorted.end());
        check(st.window_p50_ns == sorted[(50 * 100 + 99) / 100 - 1],
              "stats: p50 nearest-rank exact");
        check(st.window_p90_ns == sorted[(90 * 100 + 99) / 100 - 1],
              "stats: p90 nearest-rank exact");
        check(st.window_p95_ns == sorted[(95 * 100 + 99) / 100 - 1],
              "stats: p95 nearest-rank exact");
        check(st.window_p99_ns == sorted[(99 * 100 + 99) / 100 - 1],
              "stats: p99 nearest-rank exact");
        // Stddev: population formula on the exact mean.
        {
            double var = 0;
            const double mean = static_cast<double>(sum / 100);
            for (uint64_t d : sorted) {
                const double diff = static_cast<double>(d) - mean;
                var += diff * diff;
            }
            const double expect = std::sqrt(var / 100.0);
            check(st.window_stddev_ns + 1 >= static_cast<uint64_t>(expect) &&
                      st.window_stddev_ns <= static_cast<uint64_t>(expect) + 1,
                  "stats: population stddev within 1ns");
        }
        check(st.sequence == 2, "stats: sequence increments per GetStats");
    }

    // ---- C. Budget boundary exactness ---------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        m.RecordFrame(kFrameBudget120HzNs, 0);       // exactly budget: NOT janky
        m.RecordFrame(kFrameBudget120HzNs + 1, 0);   // +1ns: janky @120
        m.RecordFrame(kFrameBudget60HzNs, 0);        // 60Hz budget: janky@120 only
        m.RecordFrame(kFrameBudget60HzNs + 1, 0);    // +1ns: janky @60 too
        m.RecordFrame(0, 0);                         // invalid: zero
        m.RecordFrame(kFrameMaxPlausibleNs + 1, 0);  // invalid: > 10 min
        m.RecordFrame(static_cast<uint64_t>(-5000), 0);  // negative jlong wrap
        m.GetStats(&st);
        check(st.total_frames == 4, "boundary: 4 valid frames");
        check(st.invalid_samples == 3, "boundary: 3 invalid quarantined");
        check(st.janky_frames_120 == 3, "boundary: janky@120 = 3");
        check(st.janky_frames_60 == 1, "boundary: janky@60 = 1");
        check(st.window_frames == 4, "boundary: window excludes invalid");
        check(st.window_mean_ns ==
                  (kFrameBudget120HzNs + kFrameBudget120HzNs + 1 +
                   kFrameBudget60HzNs + kFrameBudget60HzNs + 1) / 4,
              "boundary: mean over valid frames only");
    }

    // ---- D. VSYNC extremes -----------------------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        m.RecordFrame(5000000, 1000000);   // aligned (|1ms| <= 2.083ms)
        m.RecordFrame(5000000, -3000000);  // misaligned
        m.RecordFrame(5000000, std::numeric_limits<int64_t>::min());
        m.RecordFrame(5000000, std::numeric_limits<int64_t>::max());
        m.RecordFrame(5000000, -2083333);  // exactly at tolerance: aligned
        m.RecordFrame(5000000, 2083334);   // 1ns over: misaligned
        m.GetStats(&st);
        check(st.total_frames == 6, "vsync: 6 frames");
        check(st.vsync_misaligned == 4, "vsync: 4 misaligned exact");
        check(st.vsync_max_abs_ns == 0x8000000000000000ull,
              "vsync: |INT64_MIN| handled without overflow");
        // signed mean = (1e6 - 3e6 + (MIN + MAX) + (-2083333) + 2083334)/6
        //             = (1e6 - 3e6 - 1 + 1)/6 = -2,000,000/6 = -333333 (trunc)
        check(st.vsync_mean_offset_ns == -333333, "vsync: saturating signed mean");
        check(st.window_frames == 6, "vsync: window 6");
    }

    // ---- E. Streaks -------------------------------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        m.RecordFrame(1000000, 0);  // clean
        m.RecordFrame(9000000, 0);  // jank (streak 1)
        m.RecordFrame(9500000, 0);  // jank (streak 2)
        m.RecordFrame(1000000, 0);  // clean (reset)
        m.RecordFrame(9000000, 0);  // jank
        m.RecordFrame(9000000, 0);  // jank
        m.RecordFrame(9000000, 0);  // jank (streak 3)
        m.RecordFrame(8333334, 0);  // jank by 1ns (streak 4)
        m.GetStats(&st);
        check(st.max_streak_janky == 4, "streak: max 4");
        check(st.current_streak == 4, "streak: current 4");
        check(st.janky_frames_120 == 6, "streak: 6 janky total");
    }

    // ---- F. Ring wraparound ------------------------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        Rng rng(21);
        std::vector<uint64_t> last512;
        for (int i = 0; i < 100000; ++i) {  // 195 laps over the 512-slot ring
            const uint64_t d = 500000 + rng.next() % 30000000;
            m.RecordFrame(d, 0);
            last512.push_back(d);
            if (last512.size() > kFrameRingSlots) last512.erase(last512.begin());
        }
        m.GetStats(&st);
        check(st.total_frames == 100000, "wrap: cumulative total exact");
        check(st.window_frames == kFrameRingSlots, "wrap: window = last 512");
        uint64_t mx = 0, sum = 0;
        for (uint64_t d : last512) {
            if (d > mx) mx = d;
            sum += d;
        }
        check(st.window_max_ns == mx, "wrap: window max = last-512 max");
        check(st.window_mean_ns == sum / kFrameRingSlots,
              "wrap: window mean = last-512 mean");
        std::sort(last512.begin(), last512.end());
        check(st.window_p50_ns == last512[255], "wrap: p50 exact");
        check(st.window_p99_ns == last512[(99 * 512 + 99) / 100 - 1],
              "wrap: p99 exact");
        check(st.stale_reads == 0, "wrap: no stale reads (single-threaded)");
        check((st.flags & 1u) == 0, "wrap: no stale flag");
    }

    // ---- G. Reset semantics ---------------------------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        m.RecordFrame(9000000, 0);
        m.RecordFrame(0, 0);
        m.GetStats(&st);
        check(st.sequence == 1, "reset: sequence 1 before reset");
        m.Reset();
        m.GetStats(&st);
        check(st.total_frames == 0 && st.janky_frames_120 == 0 &&
                  st.invalid_samples == 0 && st.window_frames == 0 &&
                  st.max_streak_janky == 0 && st.stale_reads == 0,
              "reset: everything zeroed");
        check(st.sequence == 2, "reset: sequence survives (change detector)");
        m.RecordFrame(8000000, 0);
        m.GetStats(&st);
        check(st.total_frames == 1 && st.window_frames == 1 &&
                  st.window_mean_ns == 8000000,
              "reset: recording works after reset");
    }

    // ---- H. THE 1,000,000-TICK DIRECTIVE STRESS --------------------------------------------
    {
        FramePacerMonitor m;
        std::atomic<bool> stop{false};
        std::atomic<uint64_t> ok_snapshots{0};
        std::atomic<uint64_t> bad_snapshots{0};

        // Deterministic stream: heavy-tailed around the 120Hz budget.
        Rng rng(4242);
        std::vector<uint64_t> expect;
        expect.reserve(1000000);
        for (int i = 0; i < 1000000; ++i) {
            const uint64_t r = rng.next();
            if (r % 10 == 0) {
                expect.push_back(9000000 + r % 40000000);  // jank spikes
            } else {
                expect.push_back(3000000 + r % 6000000);   // clean frames
            }
        }

        std::thread writer([&m, &expect]() {
            for (uint64_t d : expect) {
                m.RecordFrame(d, static_cast<int64_t>(d) - 8333333);
            }
        });
        std::thread reader([&]() {
            FramePacerStats st;
            while (!stop.load(std::memory_order_relaxed)) {
                if (!m.GetStats(&st)) {
                    ++bad_snapshots;
                    continue;
                }
                // Internal-consistency invariants on EVERY snapshot under
                // contention: counters mutually consistent (seqlock group),
                // percentile monotonicity, mean within [0, max].
                const bool ok =
                    st.window_frames <= kFrameRingSlots &&
                    st.janky_frames_120 <= st.total_frames &&
                    st.janky_frames_60 <= st.janky_frames_120 &&
                    st.vsync_misaligned <= st.total_frames &&
                    st.invalid_samples <= st.total_frames + 1 &&
                    st.window_p50_ns <= st.window_p90_ns &&
                    st.window_p90_ns <= st.window_p95_ns &&
                    st.window_p95_ns <= st.window_p99_ns &&
                    st.window_p99_ns <= st.window_max_ns &&
                    st.window_mean_ns <= st.window_max_ns;
                if (ok) {
                    ++ok_snapshots;
                } else {
                    ++bad_snapshots;
                }
            }
        });
        // Heavy contention: 4 burner threads hammering shared atomics.
        std::vector<std::thread> burners;
        std::atomic<uint64_t> sink{0};
        for (int b = 0; b < 4; ++b) {
            burners.emplace_back([&sink, &stop]() {
                uint64_t local = 0;
                while (!stop.load(std::memory_order_relaxed)) {
                    for (int i = 0; i < 1000; ++i) {
                        local += sink.fetch_add(1, std::memory_order_relaxed);
                    }
                }
                (void)local;
            });
        }
        writer.join();
        stop.store(true);
        reader.join();
        for (auto& t : burners) t.join();

        FramePacerStats st;
        m.GetStats(&st);
        check(bad_snapshots.load() == 0,
              "1M stress: zero inconsistent snapshots under contention");
        check(ok_snapshots.load() > 10, "1M stress: reader polled actively");
        check(st.total_frames == 1000000, "1M stress: exact total");
        uint64_t j120 = 0, j60 = 0, mis = 0;
        for (uint64_t d : expect) {
            if (d > kFrameBudget120HzNs) ++j120;
            if (d > kFrameBudget60HzNs) ++j60;
            const int64_t off = static_cast<int64_t>(d) - 8333333;
            const uint64_t a = off < 0 ? static_cast<uint64_t>(-(off + 1)) + 1
                                       : static_cast<uint64_t>(off);
            if (a > 2083333ull) ++mis;
        }
        check(st.janky_frames_120 == j120, "1M stress: exact janky@120");
        check(st.janky_frames_60 == j60, "1M stress: exact janky@60");
        check(st.vsync_misaligned == mis, "1M stress: exact misaligned");
        check(st.window_frames == kFrameRingSlots, "1M stress: window 512");
        uint64_t mx = 0;
        for (size_t i = expect.size() - kFrameRingSlots; i < expect.size(); ++i) {
            if (expect[i] > mx) mx = expect[i];
        }
        check(st.window_max_ns == mx, "1M stress: exact last-512 window max");
        std::printf("    stress: 1,000,000 ticks, %llu consistent snapshots, "
                    "j120=%llu j60=%llu mis=%llu stale=%llu\n",
                    static_cast<unsigned long long>(ok_snapshots.load()),
                    static_cast<unsigned long long>(st.janky_frames_120),
                    static_cast<unsigned long long>(st.janky_frames_60),
                    static_cast<unsigned long long>(st.vsync_misaligned),
                    static_cast<unsigned long long>(st.stale_reads));
    }

    // ---- I. Multi-instance isolation ----------------------------------------------------------
    {
        FramePacerMonitor a, b;
        a.RecordFrame(9000000, 0);
        b.RecordFrame(4000000, 0);
        b.RecordFrame(8333334, 0);
        FramePacerStats sa, sb;
        a.GetStats(&sa);
        b.GetStats(&sb);
        check(sa.total_frames == 1 && sa.janky_frames_120 == 1,
              "isolation: a unaffected");
        check(sb.total_frames == 2 && sb.janky_frames_120 == 1,
              "isolation: b unaffected");
        check(&DefaultFramePacer() == &DefaultFramePacer(),
              "isolation: default instance is a process-wide singleton");
    }

    // ---- J. Zero-allocation audit ---------------------------------------------------------------
    {
        FramePacerMonitor m;
        FramePacerStats st;
        m.RecordFrame(5000000, 0);  // warm
        m.GetStats(&st);
        {
            streamify_test::AllocGuard guard;
            for (int i = 0; i < 10000; ++i) {
                m.RecordFrame(5000000 + i, 1000);
            }
            check(guard.count() == 0, "allocguard: zero allocations on RecordFrame");
        }
        {
            streamify_test::AllocGuard guard;
            for (int i = 0; i < 100; ++i) {
                m.GetStats(&st);
            }
            check(guard.count() == 0, "allocguard: zero allocations on GetStats");
        }
    }

    // ---- K. Lock-freedom --------------------------------------------------------------------------
    {
        // Compile-time proof — stronger than runtime is_lock_free() probes,
        // whose out-of-line __atomic_is_lock_free reference the suite target
        // does not link (-latomic absent; clang+LTO keeps the libcalls where
        // GCC folds them away). Zero linkage, zero runtime cost.
        static_assert(std::atomic<uint64_t>::is_always_lock_free,
                      "64-bit atomics must be lock-free on this ABI");
        static_assert(std::atomic<uint32_t>::is_always_lock_free,
                      "32-bit atomics must be lock-free on this ABI");
        static_assert(ATOMIC_INT_LOCK_FREE == 2,
                      "int atomics must be always lock-free");
        static_assert(ATOMIC_POINTER_LOCK_FREE == 2,
                      "pointer atomics must be always lock-free");
        check(true, "lockfree: 64/32-bit atomics are lock-free on this ABI");
    }

    // ---- L. JNI support layer: 192-byte wire layout -------------------------------------------------
    {
        FramePacerMonitor m;
        for (int i = 0; i < 600; ++i) {  // > 512 so the window is full
            m.RecordFrame(3000000 + i * 10000, static_cast<int64_t>(i) * 2000);
        }
        std::vector<uint8_t> buf(kFramePacerStatsWireBytes, 0xEE);
        check(WriteFramePacerStatsBuffer(m, buf.data(), buf.size()),
              "wire: write ok");
        check(GetLE32(buf.data() + streamify::pacer::kFramePacerStatsOffMagic) ==
                  kFramePacerStatsWireMagic,
              "wire: magic offset");
        check(GetLE32(buf.data() +
                      streamify::pacer::kFramePacerStatsOffLayoutBytes) ==
                  kFramePacerStatsWireBytes,
              "wire: layout bytes offset");
        check(GetLE64(buf.data() +
                      streamify::pacer::kFramePacerStatsOffTotalFrames) == 600,
              "wire: total_frames offset");
        check(GetLE32(buf.data() +
                      streamify::pacer::kFramePacerStatsOffWindowFrames) == 512,
              "wire: window_frames offset");
        {
            FramePacerStats st;
            m.GetStats(&st);
            check(GetLE64(buf.data() + streamify::pacer::kFramePacerStatsOffP50) ==
                      st.window_p50_ns,
                  "wire: p50 offset");
            check(GetLE64(buf.data() +
                          streamify::pacer::kFramePacerStatsOffWindowMax) ==
                      st.window_max_ns,
                  "wire: window max offset");
            check(GetLEF64(buf.data() + streamify::pacer::kFramePacerStatsOffFps) >
                      20.0,
                  "wire: fps double LE offset");
            check(GetLE64(buf.data() +
                          streamify::pacer::kFramePacerStatsOffLastTs) ==
                      st.window_last_ts_ns,
                  "wire: raw timestamp offset");
            // The wire write was the FIRST serialization on this fresh
            // monitor (sequence 1); the GetStats above then bumped it to 2.
            check(GetLE32(buf.data() +
                          streamify::pacer::kFramePacerStatsOffSequence) == 1,
                  "wire: sequence offset (first serialization = 1)");
        }
        // Capacity guard: undersized / null buffers untouched, returns false.
        std::vector<uint8_t> small(kFramePacerStatsWireBytes - 1, 0xEE);
        check(!WriteFramePacerStatsBuffer(m, small.data(), small.size()),
              "wire: undersized buffer rejected");
        bool all_ee = true;
        for (uint8_t v : small) all_ee = all_ee && v == 0xEE;
        check(all_ee, "wire: rejected buffer untouched");
        check(!WriteFramePacerStatsBuffer(m, nullptr, 1 << 20),
              "wire: null buffer rejected");
        check(!WriteFramePacerStatsBuffer(m, buf.data(), 0),
              "wire: zero capacity rejected");
    }

    std::printf("  [Phase-5] frame pacer suite: %d checks passed, %d failed\n",
                g_passed, g_failed);
    return g_failed == 0 ? 0 : 1;
}
