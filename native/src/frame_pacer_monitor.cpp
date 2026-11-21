// ============================================================================
//  frame_pacer_monitor.cpp — Phase-5 lock-free frame pacing & jank monitor
//  implementation (native/src/frame_pacer_monitor.cpp)
// ============================================================================
//
//  See frame_pacer_monitor.h for the SPSC/seqlock concurrency contract and
//  the stats semantics. This TU is deliberately free of Android headers so
//  the host test suites can link it directly (the JNI bridge layer owns the
//  Android-side glue).
//
//  Portability note: accumulation of fuzzed i64 VSYNC offsets (up to
//  512 x 2^63) and u64 variance sums (up to 512 x (6e11)^2 < 2^88) uses the
//  exact 128-bit software accumulator below — armeabi-v7a's 32-bit Clang
//  has no __int128. Saturation happens only at the final narrowing, exactly
//  like the original __int128 formulation (bit-for-bit, including the
//  INT64_MIN/INT64_MAX cancellation case pinned by the unit tests).
// ============================================================================

#include "../include/frame_pacer_monitor.h"

#include <algorithm>
#include <cmath>

#if defined(__linux__) || defined(__ANDROID__)
#include <ctime>
#else
#include <ctime>
#endif

namespace streamify {
namespace pacer {

uint64_t NowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
           static_cast<uint64_t>(ts.tv_nsec);
}

namespace {

// ---- exact 128-bit unsigned accumulator (no __int128) ------------------------
// All sums in this TU fit with room to spare (< 2^89), so every operation
// below is EXACT; only the final narrowing saturates.
struct U128 {
    uint64_t hi;
    uint64_t lo;
};

constexpr U128 kU128Zero{0, 0};

inline U128 U128Add64(const U128& a, uint64_t b) {
    U128 r{a.hi, a.lo + b};
    if (r.lo < a.lo) ++r.hi;  // carry
    return r;
}

inline U128 U128Add(const U128& a, const U128& b) {
    U128 r{a.hi + b.hi, a.lo + b.lo};
    if (r.lo < a.lo) ++r.hi;
    return r;
}

inline U128 U128Sub(const U128& a, const U128& b) {  // requires a >= b
    U128 r{a.hi - b.hi, a.lo - b.lo};
    if (a.lo < b.lo) --r.hi;  // borrow
    return r;
}

inline int U128Cmp(const U128& a, const U128& b) {
    if (a.hi != b.hi) return a.hi < b.hi ? -1 : 1;
    if (a.lo != b.lo) return a.lo < b.lo ? -1 : 1;
    return 0;
}

// Exact 64x64 -> 128 multiply (schoolbook, 32-bit limbs).
inline U128 U128Mul64(uint64_t a, uint64_t b) {
    const uint64_t aL = a & 0xffffffffull, aH = a >> 32;
    const uint64_t bL = b & 0xffffffffull, bH = b >> 32;
    const uint64_t ll = aL * bL;
    const uint64_t lh = aL * bH;
    const uint64_t hl = aH * bL;
    const uint64_t hh = aH * bH;
    const uint64_t mid = lh + hl;
    const uint64_t midCarry = (mid < lh) ? 1u : 0u;
    U128 r;
    r.lo = ll + (mid << 32);
    const uint64_t loCarry = (r.lo < ll) ? 1u : 0u;
    r.hi = hh + midCarry + (mid >> 32) + loCarry;
    return r;
}

// Truncated division by a small nonzero divisor (n <= 512 here; the limb
// math holds for any d in [1, 2^32)).
inline U128 U128DivSmall(const U128& a, uint32_t d) {
    U128 q{kU128Zero};
    q.hi = a.hi / d;
    uint64_t rem = a.hi % d;  // < d < 2^32
    const uint64_t t1 = (rem << 32) | (a.lo >> 32);
    const uint64_t qMid = t1 / d;
    rem = t1 % d;
    const uint64_t t2 = (rem << 32) | (a.lo & 0xffffffffull);
    const uint64_t qLow = t2 / d;
    q.hi += qMid >> 32;
    q.lo = (qMid << 32) + qLow;
    if (q.lo < qLow) ++q.hi;
    return q;
}

// |x| for the full i64 range as u64 (INT64_MIN -> 2^63), well-defined.
inline uint64_t AbsU64(int64_t x) {
    return x >= 0 ? static_cast<uint64_t>(x)
                  : ~static_cast<uint64_t>(x) + 1ull;
}

// ---- saturating narrowings -----------------------------------------------------
inline uint64_t U128ToU64Saturate(const U128& v) {
    return v.hi != 0 ? UINT64_MAX : v.lo;
}

inline int64_t U128ToI64Saturate(const U128& v) {  // positive magnitude
    if (v.hi != 0 || v.lo > static_cast<uint64_t>(INT64_MAX)) return INT64_MAX;
    return static_cast<int64_t>(v.lo);
}

inline int64_t U128ToI64NegSaturate(const U128& v) {  // negated magnitude
    // magnitude == 2^63 maps exactly onto INT64_MIN (C++20 two's complement).
    if (v.hi != 0 || v.lo > 0x8000000000000000ull) return INT64_MIN;
    return static_cast<int64_t>(0ull - v.lo);
}

// hi == 0 (every unit-tested window keeps var < 2^64) is bit-identical to a
// native 128 -> double conversion; hi != 0 differs by at most 1 ulp, deep in
// territory no assertion inspects.
inline double U128ToDouble(const U128& v) {
    return static_cast<double>(v.hi) * 18446744073709551616.0 +
           static_cast<double>(v.lo);
}

// Nearest-rank percentile index for n samples (1-based rank = ceil(p*n/100),
// computed in exact integer arithmetic).
inline size_t PercentileIndex(uint32_t n, uint32_t p100) {
    const size_t rank = (static_cast<size_t>(p100) * n + 99) / 100;
    return rank - 1;
}

// Plain-value view of the seqlock'd counter group (copied out via relaxed
// atomic loads under a validated generation).
struct CounterView {
    uint64_t total_frames;
    uint64_t janky_120;
    uint64_t janky_60;
    uint64_t invalid;
    uint64_t misaligned;
    uint64_t max_streak;
    uint64_t total_max;
    uint32_t current_streak;
};

}  // namespace

void FramePacerMonitor::RecordFrame(uint64_t duration_ns,
                                    int64_t vsync_offset_ns) {
    // Hostile / degenerate durations never enter the ring: they would poison
    // the percentile health the Compose layer polls.
    if (duration_ns == 0 || duration_ns > kFrameMaxPlausibleNs) {
        const uint32_t g = static_cast<uint32_t>(counter_gen_.load(std::memory_order_relaxed));
        counter_gen_.store(g + 1, std::memory_order_relaxed);
        counters_.invalid.fetch_add(1, std::memory_order_relaxed);
        counter_gen_.store(g + 2, std::memory_order_release);
        return;
    }

    const uint64_t now = NowNs();
    const uint64_t cursor = write_cursor_.load(std::memory_order_relaxed);
    Slot& s = slots_[cursor & (kFrameRingSlots - 1)];

    // Seqlock write: bump odd, store the record (relaxed atomics: plain MOVs
    // that are data-race-free by the memory model), bump even with release
    // so the acquire-load on the reader side orders the field stores.
    const uint32_t seq = s.seq.load(std::memory_order_relaxed);
    s.seq.store(seq + 1, std::memory_order_relaxed);
    s.duration_ns.store(duration_ns, std::memory_order_relaxed);
    s.vsync_offset_ns.store(vsync_offset_ns, std::memory_order_relaxed);
    s.timestamp_ns.store(now, std::memory_order_relaxed);
    s.seq.store(seq + 2, std::memory_order_release);
    write_cursor_.store(cursor + 1, std::memory_order_release);

    // Counter group under its own seqlock (single producer: relaxed-atomic
    // updates between the odd/even generation bumps; the reader gets a
    // mutually consistent group). Streaks are maintained here so the reader
    // never has to reconstruct ordering.
    const uint32_t g = static_cast<uint32_t>(counter_gen_.load(std::memory_order_relaxed));
    counter_gen_.store(g + 1, std::memory_order_relaxed);
    counters_.total_frames.fetch_add(1, std::memory_order_relaxed);
    if (duration_ns > kFrameBudget120HzNs) {
        counters_.janky_120.fetch_add(1, std::memory_order_relaxed);
        const uint32_t streak =
            counters_.current_streak.fetch_add(1, std::memory_order_relaxed) + 1;
        uint64_t mx = counters_.max_streak.load(std::memory_order_relaxed);
        if (streak > mx) {
            counters_.max_streak.store(streak, std::memory_order_relaxed);
        }
    } else {
        counters_.current_streak.store(0, std::memory_order_relaxed);
    }
    if (duration_ns > kFrameBudget60HzNs) {
        counters_.janky_60.fetch_add(1, std::memory_order_relaxed);
    }
    if (AbsU64(vsync_offset_ns) >
        static_cast<uint64_t>(kVsyncMisalignToleranceNs)) {
        counters_.misaligned.fetch_add(1, std::memory_order_relaxed);
    }
    const uint64_t tm = counters_.total_max.load(std::memory_order_relaxed);
    if (duration_ns > tm) {
        counters_.total_max.store(duration_ns, std::memory_order_relaxed);
    }
    counter_gen_.store(g + 2, std::memory_order_release);
}

bool FramePacerMonitor::GetStats(FramePacerStats* out) const {
    if (out == nullptr) return false;

    const uint64_t w = write_cursor_.load(std::memory_order_acquire);
    const uint64_t start = (w >= kFrameRingSlots) ? w - kFrameRingSlots : 0;

    // Snapshot the window with per-slot seqlock validation. Torn slots (the
    // writer wrapped onto a slot mid-copy) retry a bounded number of times
    // and are then skipped + accounted as stale — a telemetry probe must
    // never spin on the render thread.
    uint32_t n = 0;
    bool stale_snapshot = false;
    uint64_t last_ts = 0;
    for (uint64_t i = start; i < w; ++i) {
        const Slot& s = slots_[i & (kFrameRingSlots - 1)];
        bool captured = false;
        for (int attempt = 0; attempt < 4 && !captured; ++attempt) {
            const uint32_t seq1 = s.seq.load(std::memory_order_acquire);
            if (seq1 & 1u) continue;  // write in flight
            const uint64_t d = s.duration_ns.load(std::memory_order_relaxed);
            const int64_t o = s.vsync_offset_ns.load(std::memory_order_relaxed);
            const uint64_t ts = s.timestamp_ns.load(std::memory_order_relaxed);
            const uint32_t seq2 = s.seq.load(std::memory_order_acquire);
            if (seq1 != seq2) continue;  // overwritten mid-read
            durations_scratch_[n] = d;
            offsets_scratch_[n] = o;
            last_ts = ts;
            ++n;
            captured = true;
        }
        if (!captured) {
            stale_snapshot = true;
            stale_reads_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    FramePacerStats st{};
    st.layout_magic = kFramePacerStatsWireMagic;
    st.layout_bytes = kFramePacerStatsWireBytes;
    st.window_frames = n;
    st.flags = stale_snapshot ? 1u : 0u;
    st.window_last_ts_ns = last_ts;

    if (n > 0) {
        std::sort(durations_scratch_, durations_scratch_ + n);
        st.window_p50_ns = durations_scratch_[PercentileIndex(n, 50)];
        st.window_p90_ns = durations_scratch_[PercentileIndex(n, 90)];
        st.window_p95_ns = durations_scratch_[PercentileIndex(n, 95)];
        st.window_p99_ns = durations_scratch_[PercentileIndex(n, 99)];
        st.window_max_ns = durations_scratch_[n - 1];

        U128 sum = kU128Zero;
        for (uint32_t i = 0; i < n; ++i) {
            sum = U128Add64(sum, durations_scratch_[i]);
        }
        const uint64_t mean = U128ToU64Saturate(U128DivSmall(sum, n));
        st.window_mean_ns = mean;
        st.estimated_fps = mean > 0 ? 1000000000.0 / static_cast<double>(mean) : 0.0;

        U128 var = kU128Zero;
        for (uint32_t i = 0; i < n; ++i) {
            const uint64_t d = durations_scratch_[i];
            const uint64_t diff = d >= mean ? d - mean : mean - d;
            var = U128Add(var, U128Mul64(diff, diff));
        }
        const double sd =
            std::sqrt(U128ToDouble(var) / static_cast<double>(n)) + 0.5;
        st.window_stddev_ns =
            sd >= 18446744073709551616.0 ? UINT64_MAX
                                         : static_cast<uint64_t>(sd);

        U128 pos = kU128Zero;  // sum of positive offsets
        U128 neg = kU128Zero;  // sum of |negative offsets|
        U128 abs_sum = kU128Zero;
        uint64_t abs_max = 0;
        for (uint32_t i = 0; i < n; ++i) {
            const uint64_t a = AbsU64(offsets_scratch_[i]);
            if (offsets_scratch_[i] >= 0) {
                pos = U128Add64(pos, a);
            } else {
                neg = U128Add64(neg, a);
            }
            abs_sum = U128Add64(abs_sum, a);
            if (a > abs_max) abs_max = a;
        }
        st.vsync_abs_mean_ns = U128ToU64Saturate(U128DivSmall(abs_sum, n));
        st.vsync_max_abs_ns = abs_max;  // <= 2^63: always fits u64
        // Exact signed mean: magnitude of (pos - neg), truncated division by
        // n, sign reapplied, saturating narrow (INT64_MIN-safe).
        const int sign = U128Cmp(pos, neg);
        const U128 mag = sign >= 0 ? U128Sub(pos, neg) : U128Sub(neg, pos);
        const U128 magq = U128DivSmall(mag, n);
        st.vsync_mean_offset_ns = sign >= 0 ? U128ToI64Saturate(magq)
                                            : U128ToI64NegSaturate(magq);
    }

    // Cumulative counters: one seqlock-validated group read (relaxed atomic
    // loads under a stable generation), so every published snapshot is
    // mutually consistent (documented to lead the ring-derived window by a
    // few frames — the window and the counter group are two different
    // snapshot points by design).
    CounterView cg{0, 0, 0, 0, 0, 0, 0, 0};
    bool have_counters = false;
    for (int attempt = 0; attempt < 4 && !have_counters; ++attempt) {
        const uint64_t g1 = counter_gen_.load(std::memory_order_acquire);
        if (g1 & 1u) continue;  // update in flight
        CounterView view;
        view.total_frames = counters_.total_frames.load(std::memory_order_relaxed);
        view.janky_120 = counters_.janky_120.load(std::memory_order_relaxed);
        view.janky_60 = counters_.janky_60.load(std::memory_order_relaxed);
        view.invalid = counters_.invalid.load(std::memory_order_relaxed);
        view.misaligned = counters_.misaligned.load(std::memory_order_relaxed);
        view.max_streak = counters_.max_streak.load(std::memory_order_relaxed);
        view.total_max = counters_.total_max.load(std::memory_order_relaxed);
        view.current_streak = counters_.current_streak.load(std::memory_order_relaxed);
        const uint64_t g2 = counter_gen_.load(std::memory_order_acquire);
        if (g1 == g2) {
            cg = view;
            have_counters = true;
        }
    }
    if (!have_counters) {
        // Bounded-retry fallback (writer never stops between generations in
        // practice): one last unvalidated load — monotonic, worst case a
        // few frames of group skew.
        cg.total_frames = counters_.total_frames.load(std::memory_order_relaxed);
        cg.janky_120 = counters_.janky_120.load(std::memory_order_relaxed);
        cg.janky_60 = counters_.janky_60.load(std::memory_order_relaxed);
        cg.invalid = counters_.invalid.load(std::memory_order_relaxed);
        cg.misaligned = counters_.misaligned.load(std::memory_order_relaxed);
        cg.max_streak = counters_.max_streak.load(std::memory_order_relaxed);
        cg.total_max = counters_.total_max.load(std::memory_order_relaxed);
        cg.current_streak = counters_.current_streak.load(std::memory_order_relaxed);
    }
    st.total_frames = cg.total_frames;
    st.janky_frames_120 = cg.janky_120;
    st.janky_frames_60 = cg.janky_60;
    st.invalid_samples = cg.invalid;
    st.vsync_misaligned = cg.misaligned;
    st.max_streak_janky = cg.max_streak;
    st.total_max_frame_ns = cg.total_max;
    st.current_streak = cg.current_streak;
    st.stale_reads = stale_reads_.load(std::memory_order_relaxed);
    st.sequence = sequence_.fetch_add(1, std::memory_order_relaxed) + 1;

    *out = st;
    return true;
}

void FramePacerMonitor::Reset() {
    // Control-plane op: writer and reader threads are quiesced by contract
    // (AudioTrack#flush parity), so plain stores are sufficient.
    for (size_t i = 0; i < kFrameRingSlots; ++i) {
        slots_[i].seq.store(0, std::memory_order_relaxed);
        slots_[i].duration_ns.store(0, std::memory_order_relaxed);
        slots_[i].vsync_offset_ns.store(0, std::memory_order_relaxed);
        slots_[i].timestamp_ns.store(0, std::memory_order_relaxed);
    }
    write_cursor_.store(0, std::memory_order_relaxed);
    counter_gen_.store(0, std::memory_order_relaxed);
    counters_.total_frames.store(0, std::memory_order_relaxed);
    counters_.janky_120.store(0, std::memory_order_relaxed);
    counters_.janky_60.store(0, std::memory_order_relaxed);
    counters_.invalid.store(0, std::memory_order_relaxed);
    counters_.misaligned.store(0, std::memory_order_relaxed);
    counters_.max_streak.store(0, std::memory_order_relaxed);
    counters_.total_max.store(0, std::memory_order_relaxed);
    counters_.current_streak.store(0, std::memory_order_relaxed);
    stale_reads_.store(0, std::memory_order_relaxed);
    // sequence_ intentionally survives Reset(): it is a serialization
    // change-detector, not a frame counter.
}

}  // namespace pacer
}  // namespace streamify
