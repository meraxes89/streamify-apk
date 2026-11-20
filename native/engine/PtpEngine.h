#ifndef STREAMIFY_PTP_ENGINE_H
#define STREAMIFY_PTP_ENGINE_H

#include <cstdint>
#include <atomic>
#include <mutex>
#include <type_traits>

namespace streamify {

// ============================================================================
//  PtpEngine — Nanosecond IEEE-1588-style clock synchronization kernel
// ============================================================================
//
//  MISSION
//  -------
//  Sub-200-us clock synchronization over local Wi-Fi/UDP, feeding a
//  sub-millisecond acoustic phase-lock between N Android devices playing in
//  the same physical room.
//
//  TIMESTAMP CONVENTION (identical to the legacy bridge, NTP ordering)
//  -------------------------------------------------------------------
//  One exchange = one request + one response:
//
//      t0 : LOCAL  clock, TX of request          (CLOCK_MONOTONIC_RAW)
//      t1 : MASTER clock, RX of request          (master timebase)
//      t2 : MASTER clock, TX of response         (master timebase)
//      t3 : LOCAL  clock, RX of response         (CLOCK_MONOTONIC_RAW)
//
//  Let d_up/d_down be the one-way propagation delays and
//  Theta = T_master - T_local (positive: master clock reads ahead):
//
//      t1 - t0 = d_up   + Theta
//      t3 - t2 = d_down - Theta
//
//  Hence (Cristian / NTP / IEEE 1588 algebra, symmetric paths):
//
//      RTT    = (t3 - t0) - (t2 - t1)          = d_up + d_down
//      offset z= ((t1 - t0) + (t2 - t3)) / 2   = Theta + (d_up - d_down)/2
//
//  The asymmetric-delay term is irreducible for any PTP implementation; we
//  suppress it statistically by exponentially down-weighting high-RTT
//  samples (queued Wi-Fi frames inflate one direction => large RTT), so the
//  filter's effective estimate converges towards the min-delay (least
//  queued, most symmetric) exchanges.
//
//  ESTIMATOR — two-state Kalman-Bucy (sampled) tracker
//  ---------------------------------------------------
//  State vector  x = [ Theta , Omega ]^T
//      Theta : master-minus-local offset            [ns]
//      Omega : d(Theta)/dt, i.e. local crystal drift [ns/s]
//              (1 us/s == 1 PPM; 1 ns/s == 1 PPB. getDriftPpm() and
//               Stats::driftPpm report the us/s (PPM) convention.)
//
//  Per exchange with inter-arrival gap dt (measured on the local RAW clock):
//
//      F = | 1  dt |        x' = F x
//          | 0  1 |
//
//      Q = qTheta*dt*| 1 0 |  +  qOmega * | dt^3/3  dt^2/2 |
//                    | 0 0 |             | dt^2/2  dt     |
//
//  (Q is the exact discretization of a continuous Wiener process acting on
//  the frequency state — the classical two-state OCXO/TCXO clock model.)
//
//  Measurement update uses the single scalar observation z with
//  H = [1 0] and a *dynamic* measurement variance that penalizes
//  queue-contaminated samples exponentially, per the mission brief:
//
//      R_k = sigma0^2 * exp( (RTT_k - RTT_min) / RTT_min )
//
//  Samples whose normalized innovation squared (chi^2, 1 dof) exceeds the
//  gate are rejected outright (burst congestion, AP retransmissions).
//
//  CLOCK SOURCE
//  ------------
//  All local timestamps and readouts use CLOCK_MONOTONIC_RAW: never slewed
//  or stepped by NTP/adjtime, unlike CLOCK_MONOTONIC — which is exactly why
//  the audio path (AudioTrack nanoTimestamps, MONOTONIC) must be bridged
//  through HardwareLatencyProfiler before it can be mixed with PTP time.
//
//  THREADING
//  ---------
//  processTimestamps()/reset(): network / control threads (mutex-protected).
//  getSynchronizedClockNanos(): ANY thread INCLUDING the audio callback —
//  wait-free via a seqlock snapshot (bounded retry, never blocks, never
//  allocates).
// ============================================================================

// ---- Kernel clock helpers (shared with HardwareLatencyProfiler) ------------
// CLOCK_MONOTONIC_RAW bypasses NTP slewing (adjtime/PI controller) entirely;
// it is the raw hardware tick counter. Falls back to CLOCK_MONOTONIC on
// non-Linux hosts so the standalone test harness builds everywhere.
inline int64_t nowRawNanos() {
#if defined(CLOCK_MONOTONIC_RAW)
    struct timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
#else
    struct timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
#endif
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL +
           static_cast<int64_t>(ts.tv_nsec);
}

class PtpEngine {
public:
    // ---- Tunables (see estimator derivation above) ------------------------
    struct Config {
        // Base measurement sigma for a min-RTT (clean) exchange, nanoseconds.
        // 100 us is the honest prior for a healthy 5 GHz Wi-Fi exchange; the
        // innovation adapter rescales it per link within ~15 exchanges.
        double sigma0Nanos = 100'000.0;
        // RTT floor used until the sliding window has its first sample.
        double rttFloorNanos = 250'000.0;
        // Sliding window over which the running RTT minimum is recomputed
        // (O(W) insert, control path only). 16 exchanges ~= 4 s at 4 Hz:
        // tracks path improvements instantly and heals a poisoned minimum
        // within one window — no exponential-relaxation hacks needed.
        int rttWindowSamples = 16;
        // Direct phase process noise, ns^2/s. Sized so the steady-state
        // posterior lands near 45 us against clean-exchange noise; it is
        // the admission ticket for the slowly-wandering asymmetric-queue
        // bias of the radio (10s-100s of us over seconds).
        double qThetaNanos2PerSec = 1.0e9;
        // Frequency random-walk intensity, ns^2/s^3. TCXO-grade crystals
        // wander ~1 PPB over 100 s; larger values make Omega chase
        // measurement noise (the classic over-tuned-clock-filter failure).
        double qOmegaNanos2PerSec3 = 2.0e-8;
        // chi^2 (1 dof) innovation gate. 11.0 ≈ 99.999% acceptance.
        double innovationChiSquareGate = 11.0;
        // Initial 1-sigma uncertainty of Theta (250 ms) and Omega (100 PPM).
        double initialOffsetSigmaNanos = 250.0e6;
        double initialDriftSigmaNanosPerSec = 100.0e3;
        // Cap on the RTT penalty exponent (avoids exp overflow; samples at
        // the cap are effectively skipped and their chi^2 is uninformative,
        // so the innovation adapter ignores them).
        double maxRttPenaltyExponent = 10.0;
    };

    // Runtime telemetry (all cheap, all lock-free snapshots).
    struct Stats {
        uint32_t exchangesSeen = 0;
        uint32_t exchangesAccepted = 0;
        uint32_t exchangesRejected = 0;
        int64_t  lastRttNanos = 0;
        int64_t  minRttNanos = 0;
        double   rttEmaNanos = 0.0;
        int64_t  offsetNanos = 0;         // filtered Theta (latest)
        double   driftPpm = 0.0;          // filtered Omega (ns/s == PPM)
        double   offsetSigmaNanos = 0.0;  // 1-sigma posterior uncertainty
        bool     locked = false;
    };

    // Default-configured process-wide instance (used by the legacy JNI
    // bridge). Tests and power users construct their own with a Config.
    static PtpEngine& getInstance();

    // ---- Core estimator ----------------------------------------------------
    // Feed one completed exchange (see convention above).
    // Returns the post-update filtered offset Theta [ns] (master - local).
    int64_t processTimestamps(int64_t t0, int64_t t1, int64_t t2, int64_t t3);

    // ---- Readouts ----------------------------------------------------------
    // Current instant on the MASTER timebase, in nanoseconds. Extrapolates
    // Theta + Omega*(elapsed) between exchanges. Audio-thread safe.
    int64_t getSynchronizedClockNanos() const;

    // Master-timebase milliseconds (legacy Kotlin API, unchanged semantics).
    int64_t getSynchronizedClockMs() const {
        return getSynchronizedClockNanos() / 1'000'000LL;
    }

    // Map an arbitrary CLOCK_MONOTONIC_RAW instant onto the master
    // timebase (Theta extrapolated to that instant). Used by
    // HardwareLatencyProfiler to timestamp future emission events.
    int64_t convertRawToMasterNanos(int64_t rawNanos) const;

    int64_t getClockOffsetNanos() const;    // extrapolated Theta [ns]
    double  getDriftPpm() const;            // Omega [PPM]
    double  getOffsetSigmaNanos() const;    // 1-sigma uncertainty
    int64_t getLastRttNanos() const { return lastRttAtomic_.load(std::memory_order_relaxed); }
    bool    isLocked() const;
    Stats   getStats() const;

    void    reset();   // full filter reset (RTT floor knowledge is kept).

    // Two-ctor split instead of `= Config()` default argument: an in-class
    // default argument would need the nested NSDMIs before the end of the
    // enclosing class (complete-class context) — rejected by GCC 9+/Clang.
    PtpEngine();                      // PtpEngine(Config())
    explicit PtpEngine(const Config& cfg);
    ~PtpEngine() = default;

private:

    // Seqlock-published snapshot consumed by the audio path.
    struct alignas(64) ClockSnapshot {
        uint64_t seq;        // even => stable, odd => write in flight
        double   thetaNanos;
        double   omegaNanosPerSec;
        double   sigmaThetaNanos;   // 1-sigma posterior uncertainty
        int64_t  tLocalRefNanos;    // local RAW instant of the snapshot
        uint32_t acceptedCount;     // accepted exchanges so far
    };

    // AMP-10 (Phase 2): compile-time proof of the zero-lock reader law.
    // The audio callback consumes snapshot_ via readSnapshot() with
    // bounded spinning only — that contract is only sound if (a) the
    // payload is trivially copyable (plain-memory reads after the
    // acquire fences; no destructor side effects, no padding hazards
    // beyond the explicit alignment) and (b) the sequence counter is
    // genuinely lock-free on every target ABI — a locking atomic would
    // silently defeat the wait-free guarantee under writer pressure.
    // These fire at build time on each of the four ABI lanes, so a
    // toolchain or platform regression breaks the build loudly instead
    // of degrading the audio path silently.
    static_assert(std::is_trivially_copyable_v<ClockSnapshot>,
                  "seqlock payload must stay trivially copyable");
    static_assert(std::atomic<uint64_t>::is_always_lock_free,
                  "seqlock sequence counter must be lock-free on all target ABIs");
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "relaxed telemetry atomics must be lock-free on all target ABIs");
    static_assert(alignof(ClockSnapshot) == 64,
                  "seqlock snapshot must stay cache-line isolated (false sharing)");

    // ---- Writer-side state (guarded by mutex_) ----------------------------
    mutable std::mutex mutex_;
    Config cfg_;
    double theta_ = 0.0;             // filtered offset [ns]
    double omega_ = 0.0;             // filtered drift [ns/s]
    double sigmaTheta_ = 0.0;        // sqrt(P00) cache for lock-free reads
    double p_[2][2] = {{0, 0}, {0, 0}};  // posterior covariance
    bool   initialized_ = false;     // bootstrap complete
    int64_t lastLocalTxNanos_ = 0;   // t0 of previous exchange (dt source)
    double  rttMinNanos_ = 0.0;      // learned minimum RTT
    double  rttEmaNanos_ = 0.0;
    uint32_t seen_ = 0;
    uint32_t accepted_ = 0;
    uint32_t rejected_ = 0;
    uint32_t consecutiveRejects_ = 0;

    // Innovation-adaptive measurement variance multiplier (IAE): the
    // brief's R_k assumes a known base sigma, but real Wi-Fi jitter varies
    // 5-10x between APs. lambda_ rescales R_k so the filter self-calibrates
    // to the observed innovation statistics instead of trusting a guess.
    // Update rule: ADDITIVE EMA on lambda towards chi^2 (a multiplicative
    // random walk in log space is a martingale that diffuses to the clamps
    // — the measured failure mode of the first cut).
    double measLambda_ = 1.0;

    // Sliding-window RTT-minimum state (rttWindowSamples_ sanitized copy).
    static constexpr uint32_t kMaxRttWindow = 32;
    double   rttHist_[kMaxRttWindow] = {0};
    uint32_t rttHistLen_ = 0;
    uint32_t rttHistPos_ = 0;
    uint32_t rttWindowSamples_ = 16;

    // Bootstrap: the first kBootstrapSamples observations are buffered and
    // committed as a weighted median, so one corrupted initial packet (Pareto
    // burst, duplicated datagram) cannot hijack the filter. 7: median-of-7
    // lands within ~0.5*sigma_z of truth while costing only 1.75 s at 4 Hz.
    static constexpr uint32_t kBootstrapSamples = 7;
    double bootZ_[kBootstrapSamples] = {0};
    double bootR_[kBootstrapSamples] = {0};
    uint32_t bootCount_ = 0;

    // ---- Reader-side state (seqlock, wait-free reads) ----------------------
    // seqAtomic_ is the seqlock sequence counter (even => stable); the
    // payload lives in the trivially-copyable POD snapshot_ and is only
    // touched by the writer under the seqlock, so readers can copy it as
    // plain memory after the acquire fence.
    mutable std::atomic<uint64_t> seqAtomic_{0};
    mutable ClockSnapshot snapshot_{};
    mutable std::atomic<uint32_t> lastRttAtomic_{0};
    mutable std::atomic<uint32_t> acceptedAtomic_{0};

    void publishSnapshot(int64_t tLocalRef);
    ClockSnapshot readSnapshot() const;
    void initialize();
};

} // namespace streamify

#endif // STREAMIFY_PTP_ENGINE_H
