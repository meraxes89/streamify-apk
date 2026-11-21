#ifndef STREAMIFY_FRAME_PACER_MONITOR_H
#define STREAMIFY_FRAME_PACER_MONITOR_H
// ============================================================================
//  frame_pacer_monitor.h — Phase-5 lock-free 120Hz frame pacing & jank
//  anomaly monitor (native/include/frame_pacer_monitor.h)
// ============================================================================
//
//  Deliverable 2 of the Phase-5 directive: a zero-allocation SPSC telemetry
//  ring that captures nanosecond-precise frame render intervals (stamped
//  with CLOCK_MONOTONIC_RAW) and choreographer VSYNC alignments, flags jank
//  against the 120Hz (8.33ms) and 60Hz (16.66ms) display budgets, and lets
//  the Compose layer query frame health in real time without ever touching
//  the render hot path.
//
//  CONCURRENCY MODEL (single-producer / single-consumer, lock-free)
//  ----------------------------------------------------------------
//  * Writer (one thread, typically the Choreographer/UI thread) calls
//    RecordFrame(). Each record is written into a 512-slot ring, published
//    through a per-slot seqlock (odd = write in progress) and a free-running
//    release-store write cursor — no locks, no allocation, no GC impact.
//  * Reader (one thread, the Compose telemetry poller) calls GetStats().
//    It acquire-loads the write cursor, copies the latest <= 512 records
//    with per-slot seqlock validation (bounded retries, stale accounting on
//    retry exhaustion) and computes windowed percentiles from a
//    pre-allocated reader-owned scratch buffer. Cumulative counters are
//    plain atomics published by the writer.
//  * The ring is a "latest 512" telemetry buffer: the writer NEVER blocks
//    and never drops — it overwrites the oldest slot on wrap. A reader that
//    lags a full lap sees the seqlock flip and re-reads (or marks the
//    snapshot stale); torn records can never enter a published snapshot.
//  * Cache-line discipline: the writer block (write cursor + cumulative
//    counters) and the reader block (stats sequence + stale counter) each
//    sit on their own alignas(64) line, so reader and writer states never
//    share a line (no false sharing across cores).
//  * 64-bit atomics are lock-free on every target ABI (AArch64 LP64;
//    ARMv7 LDREXD/STREXD; x86-64) — asserted at runtime by the test suite.
//  * Reset() is a control-plane op (AudioTrack#flush parity): quiesce the
//    writer and reader threads before calling it.
//
//  JANK & VSYNC DETECTION
//  ----------------------
//  * A frame is janky @120Hz when duration_ns > kFrameBudget120HzNs
//    (8,333,333ns = 1e9/120) and janky @60Hz when duration_ns >
//    kFrameBudget60HzNs (16,666,667ns = ceil(1e9/60)). Both budgets are
//    tracked simultaneously so a single feed serves 120Hz and 60Hz
//    displays.
//  * vsync_offset_ns is the signed distance between the frame's completion
//    and the nearest VSYNC boundary (from Choreographer). A frame is
//    misaligned when |offset| > kVsyncMisalignToleranceNs (25% of the
//    120Hz budget). Offsets are accumulated in 128-bit intermediates so
//    fuzzed i64 extremes cannot overflow the stats.
//  * Durations outside (0, kFrameMaxPlausibleNs] (e.g. device sleep,
//    garbage jlongs) are counted as invalid samples and never enter the
//    ring — they must not poison percentile health.
//  * Consecutive-jank streaks are maintained on the writer side (current
//    + max), so the reader never has to reconstruct ordering.
//
//  STATS SNAPSHOT (FramePacerStats / 192-byte wire layout)
//  -------------------------------------------------------
//  Windowed fields describe the latest min(512, total) valid frames;
//  cumulative fields count every valid frame since construction/Reset.
//  Percentiles use nearest-rank on the ascending duration sort (p50 is
//  durations[ceil(0.50 * n) - 1], etc.), exact integer statistics.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace streamify {
namespace pacer {

// ---- frozen constants (directive-mandated) -----------------------------------
inline constexpr size_t kFrameRingSlots = 512;             // SPSC ring size
inline constexpr uint64_t kFrameBudget120HzNs = 8333333ull;   // 1e9 / 120
inline constexpr uint64_t kFrameBudget60HzNs = 16666667ull;   // ceil(1e9 / 60)
inline constexpr uint64_t kFrameMaxPlausibleNs = 600000000000ull;  // 10 min
inline constexpr int64_t kVsyncMisalignToleranceNs = 2083333;  // 25% of 120Hz

// ---- stats snapshot (see also the 192-byte wire layout in the JNI header) ----
// Wire identity of this struct (the little-endian serializer lives in
// streamify_frame_palette.h; the constants live here so the monitor owns
// its own layout version).
inline constexpr uint32_t kFramePacerStatsWireMagic = 0x5FAC0001u;
inline constexpr uint32_t kFramePacerStatsWireBytes = 192;

struct FramePacerStats {
    // Header
    uint32_t layout_magic = 0;   // kFramePacerStatsWireMagic
    uint32_t layout_bytes = 0;   // kFramePacerStatsWireBytes (192)
    // Cumulative counters (writer-published point-in-time values)
    uint64_t total_frames = 0;        // valid frames recorded
    uint64_t janky_frames_120 = 0;    // duration > 120Hz budget
    uint64_t janky_frames_60 = 0;     // duration > 60Hz budget
    uint64_t invalid_samples = 0;     // rejected durations (never in ring)
    uint64_t vsync_misaligned = 0;    // |offset| > tolerance (cumulative)
    uint64_t max_streak_janky = 0;    // longest consecutive 120Hz-jank run
    uint64_t total_max_frame_ns = 0;  // cumulative worst frame
    // Window (latest <= 512 valid frames, snapshot-consistent)
    uint32_t window_frames = 0;       // frames actually in this snapshot
    uint32_t current_streak = 0;      // live 120Hz jank streak (writer)
    uint32_t flags = 0;               // bit0: stale snapshot (reader lagged)
    uint32_t reserved = 0;
    uint64_t window_p50_ns = 0;
    uint64_t window_p90_ns = 0;
    uint64_t window_p95_ns = 0;
    uint64_t window_p99_ns = 0;
    uint64_t window_max_ns = 0;
    uint64_t window_mean_ns = 0;      // exact rational, truncated
    uint64_t window_stddev_ns = 0;    // population stddev, rounded
    double estimated_fps = 0.0;       // 1e9 / window mean (0 when empty)
    // VSYNC alignment (window)
    int64_t vsync_mean_offset_ns = 0;     // signed mean (saturating i128 math)
    uint64_t vsync_abs_mean_ns = 0;       // mean of |offset|
    uint64_t vsync_max_abs_ns = 0;        // max |offset| (INT64_MIN-safe)
    // RAW clock
    uint64_t window_last_ts_ns = 0;       // CLOCK_MONOTONIC_RAW stamp of the
                                          // newest frame in the window
    // Reader-side telemetry
    uint64_t stale_reads = 0;     // cumulative slots skipped after seqlock
                                  // retry exhaustion (reader-owned)
    uint32_t sequence = 0;        // increments on every stats serialization
    uint32_t reserved2 = 0;
};

// Nanosecond CLOCK_MONOTONIC_RAW reading (no NTP slew, monotonic across
// suspend on Linux/Android). Every RecordFrame() call stamps the RAW clock.
uint64_t NowNs();

// ---- the monitor (SPSC; one writer thread, one reader thread) -----------------
class FramePacerMonitor {
public:
    FramePacerMonitor() = default;
    ~FramePacerMonitor() = default;

    FramePacerMonitor(const FramePacerMonitor&) = delete;
    FramePacerMonitor& operator=(const FramePacerMonitor&) = delete;

    // ---- writer thread (single producer) ------------------------------------
    // Record one rendered frame. duration_ns <= 0 or > kFrameMaxPlausibleNs
    // counts as an invalid sample (ring untouched). vsync_offset_ns accepts
    // the full signed i64 range (|INT64_MIN| handled without overflow).
    // Zero allocation, zero locks, ~25ns including the RAW clock stamp.
    void RecordFrame(uint64_t duration_ns, int64_t vsync_offset_ns);
    // ---- reader thread (single consumer) ------------------------------------
    // Snapshot the latest window + cumulative counters into `out`.
    // Returns false only for a null argument. The window is
    // snapshot-consistent (seqlock-validated); cumulative counters are
    // point-in-time (may lead the window by a few frames — documented,
    // benign for telemetry).
    bool GetStats(FramePacerStats* out) const;

    // ---- control plane (quiesce writer AND reader first) --------------------
    // Drops ring contents and zeroes every counter (AudioTrack#flush parity).
    void Reset();

    // ---- immutable config ----------------------------------------------------
    static constexpr size_t kSlots = kFrameRingSlots;

private:
    // Seqlock'd slot: every data field is an atomic with relaxed ordering so
    // the structure is data-race-free per the C++ memory model (TSan-clean;
    // relaxed atomics compile to plain MOVs on every target ABI). The seq
    // counter still provides the torn-read detection: a reader that sees an
    // unchanged even seq has a consistent record.
    struct Slot {
        std::atomic<uint32_t> seq{0};
        std::atomic<uint64_t> duration_ns{0};
        std::atomic<int64_t> vsync_offset_ns{0};
        std::atomic<uint64_t> timestamp_ns{0};  // CLOCK_MONOTONIC_RAW stamp
    };

    // Cumulative counters as ONE seqlock-protected group: the writer bumps
    // counter_gen odd -> updates relaxed-atomic fields -> bumps even
    // (release); the reader validates the generation on both sides. Every
    // published snapshot therefore sees a mutually-consistent counter group
    // (a transient janky > total can never be observed).
    struct CounterGroup {
        std::atomic<uint64_t> total_frames{0};
        std::atomic<uint64_t> janky_120{0};
        std::atomic<uint64_t> janky_60{0};
        std::atomic<uint64_t> invalid{0};
        std::atomic<uint64_t> misaligned{0};
        std::atomic<uint64_t> max_streak{0};
        std::atomic<uint64_t> total_max{0};
        std::atomic<uint32_t> current_streak{0};
    };

    Slot slots_[kFrameRingSlots];

    // Writer-owned cache line: ring write cursor.
    alignas(64) std::atomic<uint64_t> write_cursor_{0};
    // Writer-owned cache line: counter-group seqlock + the plain counters.
    alignas(64) std::atomic<uint64_t> counter_gen_{0};
    CounterGroup counters_{};
    // Reader-owned cache line: serialization sequence + stale accounting.
    alignas(64) mutable std::atomic<uint32_t> sequence_{0};
    alignas(64) mutable std::atomic<uint64_t> stale_reads_{0};

    // Reader-owned scratch (single-reader contract): filled by GetStats,
    // never visible to the writer. Mutable because GetStats is const.
    mutable uint64_t durations_scratch_[kFrameRingSlots];
    mutable int64_t offsets_scratch_[kFrameRingSlots];
};

}  // namespace pacer
}  // namespace streamify

#endif  // STREAMIFY_FRAME_PACER_MONITOR_H
