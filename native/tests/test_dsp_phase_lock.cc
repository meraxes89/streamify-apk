// ============================================================================
//  test_dsp_phase_lock.cc — standalone native test harness & fuzzing
// ============================================================================
//
//  Builds and runs WITHOUT Android (host C++20), per mission brief section 7.
//  No dependencies beyond libm/libpthread.
//
//    cd native && mkdir -p build && cd build
//    cmake .. && make && ./test_dsp
//
//  Coverage matrix:
//    [PTP]   Gaussian-jitter convergence (< 15 iterations)
//            Pareto-burst (5 GHz Wi-Fi congestion) convergence
//            Asymmetric-path robustness, outlier rejection, re-acquisition
//            Frequency-drift tracking (two-state Kalman observability)
//            Monotonic, bounded synchronized-clock readout
//    [DSP]   Bit-exact identity passthrough at 0 PPM
//            Sinc-resampler SNR vs double-precision oracle @ 1 kHz / 15 kHz
//            Block-size invariance (bit-exact across random blockings)
//            Drift accuracy via exact fixed-point frame accounting
//            Click/discontinuity hunting under randomized drift schedules
//    [HW]    Latency-profiler WLS regression vs ground truth (+ outliers)
//            Effective-rate (PPM) recovery, prior fallback, route switching
//    [PERF]  Zero-allocation proof (global operator new guard)
//            Throughput micro-benchmark (scalar path on host; NEON on ARM)
//    [FUZZ]  200-seed randomized soak across all three engines
// ============================================================================

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <random>
#include <vector>
#include <ctime>

#include "../engine/PtpEngine.h"
#include "../engine/HardwareLatencyProfiler.h"
#include "../dsp/AcousticPhaseResampler.h"

using streamify::PtpEngine;
using streamify::dsp::AcousticPhaseResampler;
using streamify::engine::HardwareLatencyProfiler;

// ---------------------------------------------------------------------------
// Micro test framework
// ---------------------------------------------------------------------------
static int g_checksPassed = 0;
static int g_checksFailed = 0;

#define CHECK(cond, ...)                                                     \
    do {                                                                     \
        if (cond) { ++g_checksPassed; }                                      \
        else {                                                               \
            ++g_checksFailed;                                                \
            std::printf("    FAIL %s:%d  %s  -- ", __FILE__, __LINE__, #cond); \
            std::printf(__VA_ARGS__);                                        \
            std::printf("\n");                                               \
        }                                                                    \
    } while (0)

static void banner(const char* name) {
    std::printf("  [%s]\n", name);
}

// ---------------------------------------------------------------------------
// Zero-allocation guard: counts operator new/delete traffic while armed.
// ---------------------------------------------------------------------------
static std::atomic<bool> g_allocGuardArmed{false};
static std::atomic<int64_t> g_allocCount{0};

void* operator new(size_t sz) {
    if (g_allocGuardArmed.load(std::memory_order_relaxed))
        g_allocCount.fetch_add(1, std::memory_order_relaxed);
    void* p = std::malloc(sz ? sz : 1);
    if (!p) throw std::bad_alloc();
    return p;
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, size_t) noexcept { std::free(p); }
void* operator new[](size_t sz) { return ::operator new(sz); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete[](void* p, size_t) noexcept { std::free(p); }

// ---------------------------------------------------------------------------
// Synthetic clock & network models
// ---------------------------------------------------------------------------
namespace sim {

struct Clocks {
    // Master = true time. Local crystal runs F PPM fast and starts Theta0
    // behind: L(t) = t*(1+F) - Theta0  =>  Theta(t) = M(t)-L(t) = Theta0 - F*t.
    // The synthetic epoch is anchored to the REAL host RAW clock so that
    // readout extrapolation (now - tRef) stays in a sane numeric range.
    double freqPpm = 0.0;     // local crystal error (PPM)
    double theta0Nanos = 0.0; // master - local at t = 0 (ns)
    int64_t bootOffsetNanos = streamify::nowRawNanos();

    double masterNanos(double tSec) const {
        return static_cast<double>(bootOffsetNanos) + tSec * 1e9;
    }
    double localNanos(double tSec) const {
        return static_cast<double>(bootOffsetNanos) +
               tSec * 1e9 * (1.0 + freqPpm * 1e-6) - theta0Nanos;
    }
    double trueThetaNanos(double tSec) const {
        // MUST match localNanos(): a crystal F PPM fast (ratio, us/s) gains
        // F*1000 ns per second, so Theta(t) = Theta0 - F*1000*t. (An earlier
        // cut dropped the x1000 and quietly contradicted localNanos() by
        // three orders of magnitude.)
        return theta0Nanos - freqPpm * 1000.0 * tSec;   // ns (PPM == us/s)
    }
};

struct Exchange {
    int64_t t0, t1, t2, t3;   // t0/t3 local RAW, t1/t2 master
};

// One NTP-style exchange at local request instant tSend (seconds),
// base delays in ms, Gaussian timestamp jitter in us, Pareto burst
// contamination probability + tail index applied to the UPLINK only.
template <class Rng>
Exchange makeExchange(const Clocks& c, Rng& rng, double tSendSec,
                      double baseDelayMs, double jitterSigmaUs,
                      double paretoProb, double paretoXmMs,
                      double respDelayMs) {
    std::normal_distribution<double> gauss(0.0, jitterSigmaUs * 1e3);   // ns
    std::uniform_real_distribution<double> uni(0.0, 1.0);

    double upMs = baseDelayMs;
    if (uni(rng) < paretoProb) {
        // Pareto tail: xm * u^(-1/alpha), alpha = 2.2 -> heavy Wi-Fi bursts.
        const double u = std::max(1e-9, uni(rng));
        upMs += paretoXmMs * std::pow(u, -1.0 / 2.2);
    }

    const double tUp = tSendSec + upMs * 1e-3;
    const double tResp = tUp + respDelayMs * 1e-3;
    const double tDown = tResp + baseDelayMs * 1e-3;

    Exchange e;
    e.t0 = static_cast<int64_t>(c.localNanos(tSendSec) + gauss(rng));
    e.t1 = static_cast<int64_t>(c.masterNanos(tUp) + gauss(rng));
    e.t2 = static_cast<int64_t>(c.masterNanos(tResp) + gauss(rng));
    e.t3 = static_cast<int64_t>(c.localNanos(tDown) + gauss(rng));
    return e;
}

} // namespace sim

// ---------------------------------------------------------------------------
// [PTP] Convergence under Gaussian jitter
// ---------------------------------------------------------------------------
static void testPtpGaussianConvergence() {
    banner("PTP: Gaussian jitter convergence (sigma = 150 us)");
    const int kSeeds = 400;
    // Track |error| after exactly 15 and after 100 exchanges.
    double err15[kSeeds], err100[kSeeds];
    for (int s = 0; s < kSeeds; ++s) {
        std::mt19937_64 rng(0xC0FFEE + s);
        sim::Clocks c;
        c.freqPpm = 12.0;                  // local crystal 12 PPM fast
        c.theta0Nanos = 37.5e6;            // 37.5 ms initial offset

        PtpEngine::Config cfg;
        PtpEngine ptp(cfg);
        double errAt15 = 0, errAt100 = 0;
        const double dtSec = 0.25;         // 4 Hz sync cadence
        for (int k = 1; k <= 100; ++k) {
            const double t = k * dtSec;
            const auto e = sim::makeExchange(c, rng, t, 2.0, 150.0, 0.0, 0.0, 1.5);
            // The return value is the post-update filtered Theta AT the
            // exchange instant — no readout extrapolation involved.
            const double thetaEst = static_cast<double>(
                ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3));
            const double err = std::abs(thetaEst - c.trueThetaNanos(t));
            if (k == 15) errAt15 = err;
            if (k == 100) errAt100 = err;
        }
        err15[s] = errAt15;
        err100[s] = errAt100;
    }
    auto pctl = [&](double* v, double p) {
        std::sort(v, v + kSeeds);
        return v[static_cast<int>(p * (kSeeds - 1))];
    };
    const double p50_15 = pctl(err15, 0.50), p95_15 = pctl(err15, 0.95);
    const double p50_100 = pctl(err100, 0.50), p95_100 = pctl(err100, 0.95);
    std::printf("    after 15 exchanges : p50 = %7.1f us  p95 = %7.1f us\n",
                p50_15 / 1e3, p95_15 / 1e3);
    std::printf("    after 100 exchanges: p50 = %7.1f us  p95 = %7.1f us\n",
                p50_100 / 1e3, p95_100 / 1e3);
    // Mission targets: < 200 us over local Wi-Fi; convergence within 15 iters.
    CHECK(p95_15 < 200'000.0, "p95@15 %.1f us >= 200 us", p95_15 / 1e3);
    CHECK(p95_100 < 100'000.0, "p95@100 %.1f us >= 100 us", p95_100 / 1e3);
}

// ---------------------------------------------------------------------------
// [PTP] Pareto burst congestion (5 GHz Wi-Fi, uplink-queued AP traffic)
// ---------------------------------------------------------------------------
static void testPtpParetoBursts() {
    banner("PTP: Pareto burst congestion (15% uplink bursts, xm = 5 ms)");
    const int kSeeds = 300;
    double err40[kSeeds];
    int rejectsTotal = 0;
    for (int s = 0; s < kSeeds; ++s) {
        std::mt19937_64 rng(0xBEEF + s);
        sim::Clocks c;
        c.freqPpm = -20.0;
        c.theta0Nanos = -8.3e6;
        PtpEngine ptp;
        double errAt40 = 0;
        for (int k = 1; k <= 40; ++k) {
            const double t = k * 0.25;
            const auto e = sim::makeExchange(c, rng, t, 1.5, 120.0, 0.15, 5.0, 1.0);
            const double thetaEst = static_cast<double>(
                ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3));
            if (k == 40)
                errAt40 = std::abs(thetaEst - c.trueThetaNanos(t));
        }
        err40[s] = errAt40;
        rejectsTotal += static_cast<int>(ptp.getStats().exchangesRejected);
    }
    std::sort(err40, err40 + kSeeds);
    const double p50 = err40[kSeeds / 2], p95 = err40[(kSeeds * 95) / 100];
    std::printf("    after 40 exchanges : p50 = %7.1f us  p95 = %7.1f us  (rejected %d/%d)\n",
                p50 / 1e3, p95 / 1e3, rejectsTotal, kSeeds * 40);
    CHECK(p95 < 400'000.0, "p95 %.1f us >= 400 us", p95 / 1e3);
    CHECK(p50 < 150'000.0, "p50 %.1f us >= 150 us", p50 / 1e3);
}

// ---------------------------------------------------------------------------
// [PTP] Frequency-drift tracking + asymmetric-delay bias accounting
// ---------------------------------------------------------------------------
static void testPtpDriftTracking() {
    banner("PTP: two-state drift tracking (local crystal +18 PPM)");
    std::mt19937_64 rng(0x5EED);
    sim::Clocks c;
    c.freqPpm = 18.0;
    c.theta0Nanos = 4.2e6;
    PtpEngine ptp;
    const double dtSec = 0.25;
    double thetaLast = 0.0;
    for (int k = 1; k <= 400; ++k) {   // 100 seconds of sync
        const double t = k * dtSec;
        const auto e = sim::makeExchange(c, rng, t, 2.0, 100.0, 0.0, 0.0, 1.0);
        thetaLast = static_cast<double>(ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3));
    }
    // Omega tracks d(Theta)/dt = -F. Expect within a few PPM after 100 s.
    const double drift = ptp.getDriftPpm();
    const double thetaErrUs = std::abs(thetaLast - c.trueThetaNanos(100.0)) / 1e3;
    std::printf("    estimated drift = %.2f PPM (truth -18.00), theta err = %.1f us\n",
                drift, thetaErrUs);
    CHECK(drift < -10.0 && drift > -26.0, "drift %.2f PPM outside [-26, -10]", drift);
    CHECK(thetaErrUs < 150.0, "theta error %.1f us too large after 100 s", thetaErrUs);

    // Asymmetric path: uplink 3 ms, downlink 1 ms -> constant bias +1 ms in
    // every raw observation; the min-RTT-weighted Kalman cannot remove a
    // true constant asymmetry (nobody can), but must stay STABLE around it.
    std::mt19937_64 rng2(0xA5A5);
    PtpEngine ptp2;
    double thetaLast2 = 0.0;
    for (int k = 1; k <= 200; ++k) {
        const double t = k * 0.25;
        std::normal_distribution<double> g(0.0, 80'000.0);
        const double up = 3'000'000.0, down = 1'000'000.0;
        const int64_t t0 = static_cast<int64_t>(c.localNanos(t)) ;
        const int64_t t1 = static_cast<int64_t>(c.masterNanos(t) + up + g(rng2));
        const int64_t t2 = t1 + 1'000'000;
        const int64_t t3 = static_cast<int64_t>(
            c.localNanos(t + (up + down + 1'000'000.0) * 1e-9) + g(rng2));
        thetaLast2 = ptp2.processTimestamps(t0, t1, t2, t3);
    }
    const double bias = thetaLast2 - c.trueThetaNanos(50.0);
    std::printf("    asymmetric-path bias = %.1f us (theoretical +1000 us)\n", bias / 1e3);
    CHECK(std::abs(bias - 1'000'000.0) < 300'000.0,
          "asymmetry bias %.1f us not near the theoretical 1000 us", bias / 1e3);
}

// ---------------------------------------------------------------------------
// [PTP] Outlier storm, rejection and re-acquisition
// ---------------------------------------------------------------------------
static void testPtpOutlierStorm() {
    banner("PTP: outlier storm + re-acquisition");
    std::mt19937_64 rng(0xD00D);
    sim::Clocks c;
    c.freqPpm = 5.0;
    c.theta0Nanos = 11.0e6;
    PtpEngine ptp;
    double thetaClean = 0.0;
    for (int k = 1; k <= 30; ++k) {
        const auto e = sim::makeExchange(c, rng, k * 0.25, 2.0, 100.0, 0.0, 0.0, 1.0);
        thetaClean = static_cast<double>(ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3));
    }
    const double before = std::abs(thetaClean - c.trueThetaNanos(7.5));
    // Storm: 12 consecutive duplicated-datagram-style samples, +-80 ms off.
    double thetaPost = thetaClean;
    for (int k = 0; k < 12; ++k) {
        const auto e = sim::makeExchange(c, rng, 8.0 + k * 0.25, 2.0, 100.0, 0.0, 0.0, 1.0);
        const int64_t poison = (k & 1) ? 80'000'000LL : -80'000'000LL;
        thetaPost = static_cast<double>(
            ptp.processTimestamps(e.t0, e.t1 + poison, e.t2 + poison, e.t3));
    }
    const auto st = ptp.getStats();
    const double after = std::abs(thetaPost - c.trueThetaNanos(11.0));
    std::printf("    clean err = %.1f us, post-storm err = %.1f us, rejected = %u\n",
                before / 1e3, after / 1e3, st.exchangesRejected);
    CHECK(before < 200'000.0, "clean error %.1f us too large", before / 1e3);
    CHECK(st.exchangesRejected >= 8, "gate rejected only %u/12", st.exchangesRejected);
    CHECK(after < 400'000.0, "post-storm error %.1f us too large", after / 1e3);
    // Re-acquire cleanly after the storm.
    double thetaRec = thetaPost;
    for (int k = 1; k <= 20; ++k) {
        const auto e = sim::makeExchange(c, rng, 12.0 + k * 0.25, 2.0, 100.0, 0.0, 0.0, 1.0);
        thetaRec = static_cast<double>(ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3));
    }
    const double recovered = std::abs(thetaRec - c.trueThetaNanos(17.0));
    std::printf("    recovered err = %.1f us\n", recovered / 1e3);
    CHECK(recovered < 200'000.0, "recovery error %.1f us too large", recovered / 1e3);
}

// ---------------------------------------------------------------------------
// [PTP] Synchronized clock readout sanity
// ---------------------------------------------------------------------------
static void testPtpReadoutSanity() {
    banner("PTP: synchronized-clock readout sanity");
    std::mt19937_64 rng(0xFEED);
    sim::Clocks c;
    c.freqPpm = 8.0;
    c.theta0Nanos = -21.0e6;
    PtpEngine ptp;
    int64_t prev = INT64_MIN;
    double worstBackstepNanos = 0.0;
    int64_t firstSynced = 0;
    for (int k = 1; k <= 120; ++k) {
        const double t = k * 0.25;
        const auto e = sim::makeExchange(c, rng, t, 2.0, 100.0, 0.0, 0.0, 1.0);
        ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3);
        const int64_t synced = ptp.getSynchronizedClockNanos();
        if (k == 25) firstSynced = synced;   // after lock
        if (k > 25 && prev != INT64_MIN && synced < prev)
            worstBackstepNanos = std::max(worstBackstepNanos,
                                          static_cast<double>(prev - synced));
        prev = synced;
    }
    // Ground truth: the engine reads the REAL host clock; the synthetic
    // master time at that real instant is realNow + Theta(realNow). Over the
    // test's few real milliseconds of wall time, Theta barely moves, so the
    // initial Theta0 dominates: truth ~= realNow + Theta0.
    const int64_t realNow = streamify::nowRawNanos();
    const int64_t truth = realNow + static_cast<int64_t>(c.theta0Nanos);
    const int64_t synced = ptp.getSynchronizedClockNanos();
    const double errUs = std::abs(static_cast<double>(synced - truth)) / 1e3;
    std::printf("    readout error = %.1f us, worst backstep = %.1f us, sigma = %.1f us\n",
                errUs, worstBackstepNanos / 1e3, ptp.getOffsetSigmaNanos() / 1e3);
    // A synchronized clock must never step backwards by more than the
    // estimation noise scale (micro-steps are expected for any PTP client).
    CHECK(worstBackstepNanos < 500'000.0,
          "synchronized clock back-stepped %.1f us", worstBackstepNanos / 1e3);
    CHECK(synced > firstSynced, "synchronized clock did not advance");
    CHECK(errUs < 400.0, "readout error %.1f us >= 400 us", errUs);
    CHECK(ptp.isLocked(), "engine did not reach locked state");
}

// ---------------------------------------------------------------------------
// [DSP] Oracle: double-precision reference implementing the exact same
// recurrence (slew + fixed-point-equivalent positions in double).
// ---------------------------------------------------------------------------
namespace oracle {

struct SlowOracle {
    AcousticPhaseResampler::Config cfg;
    std::vector<double> table;   // (P+1) x T
    int T, L, P;

    explicit SlowOracle(const AcousticPhaseResampler& ref)
        : cfg{}, T(ref.tapsPerPhase()), L(ref.tapsPerPhase() / 2), P(ref.phaseCount()) {
        cfg.channels = ref.channels();
        cfg.sampleRateHz = ref.sampleRateHz();
        cfg.tapsPerPhase = T;
        cfg.phaseCount = P;
        cfg.kaiserBeta = 9.0;
        cfg.slewTauSeconds = 0.25;
        table.resize(static_cast<size_t>(P + 1) * T);
        build();
    }

    static double besselI0(double x) {
        const double h = 0.5 * x;
        double term = 1.0, sum = 1.0;
        for (int k = 1; k < 64; ++k) {
            term *= (h * h) / static_cast<double>(k * k);
            sum += term;
            if (term < 1e-19 * sum) break;
        }
        return sum;
    }

    void build() {
        const double beta = 9.0, i0b = besselI0(beta);
        for (int phi = 0; phi <= P; ++phi) {
            const double frac = static_cast<double>(phi) / P;
            for (int k = 0; k < T; ++k) {
                const double u = static_cast<double>(k + 1 - L) - frac;
                const double tArg = u / static_cast<double>(L);
                double w = 0.0;
                if (std::abs(tArg) < 1.0)
                    w = besselI0(beta * std::sqrt(1.0 - tArg * tArg)) / i0b;
                const double px = M_PI * u;
                const double s = (std::abs(px) < 1e-14) ? 1.0 : std::sin(px) / px;
                table[static_cast<size_t>(phi) * T + k] = s * w;
            }
        }
    }

    // Reference output frame j given the whole input signal, mirroring the
    // engine's deterministic drift-slew recurrence in double precision.
    // Returns frames produced (windows clipped at stream end like engine).
    int run(const std::vector<float>& in, int inFrames, std::vector<float>& out,
            double driftPpmTarget, int maxOut) {
        const int C = cfg.channels;
        const double alpha = 1.0 - std::exp(-1.0 / (cfg.sampleRateHz * cfg.slewTauSeconds));
        double drift = 0.0, pos = 0.0;
        int produced = 0;
        out.clear();
        while (produced < maxOut) {
            const long double pFloorL = std::floor(pos);
            const int64_t pFloor = static_cast<int64_t>(pFloorL);
            if (pFloor + L > inFrames - 1) break;
            const double frac = pos - static_cast<double>(pFloor);
            const double phiD = frac * P;
            const int phi0 = static_cast<int>(phiD);
            const double mu = phiD - phi0;
            for (int cch = 0; cch < C; ++cch) {
                double acc = 0.0;
                for (int k = 0; k < T; ++k) {
                    const int64_t idx = pFloor - L + 1 + k;
                    const double tap = table[static_cast<size_t>(phi0) * T + k] +
                                       mu * (table[static_cast<size_t>(phi0 + 1) * T + k] -
                                             table[static_cast<size_t>(phi0) * T + k]);
                    const double s = (idx < 0) ? 0.0
                        : static_cast<double>(in[static_cast<size_t>(idx) * C + cch]);
                    acc += tap * s;
                }
                out.push_back(static_cast<float>(acc));
            }
            ++produced;
            drift += (driftPpmTarget - drift) * alpha;
            pos += 1.0 + drift * 1e-6;
        }
        return produced;
    }
};

} // namespace oracle

// SNR of engine output vs reference, ignoring the first `skip` frames
// (pre-roll truncation + slew transient region).
static double computeSnr(const std::vector<float>& engine,
                         const std::vector<float>& ref, int skipFrames, int channels) {
    const size_t n = std::min(engine.size(), ref.size());
    double sig = 0.0, noise = 0.0;
    size_t used = 0;
    for (size_t i = static_cast<size_t>(skipFrames) * channels; i < n; ++i) {
        sig += static_cast<double>(ref[i]) * ref[i];
        const double d = static_cast<double>(engine[i]) - ref[i];
        noise += d * d;
        ++used;
    }
    if (used == 0 || noise <= 0.0) return 400.0;
    return 10.0 * std::log10(sig / noise);
}

// ---------------------------------------------------------------------------
// [DSP] Identity passthrough (0 PPM must be bit-exact)
// ---------------------------------------------------------------------------
static void testResamplerIdentity() {
    banner("RESAMPLER: 0 PPM identity is bit-exact");
    AcousticPhaseResampler engine;   // 48k stereo defaults
    const int N = 8192;
    std::vector<float> in(N * 2), out(N * 2 + 64);
    std::mt19937_64 rng(1);
    std::uniform_real_distribution<float> noise(-0.9f, 0.9f);
    for (auto& v : in) v = noise(rng);

    int produced = 0;
    for (int off = 0; off < N; off += 2048) {
        const int frames = std::min(2048, N - off);
        produced += engine.process(in.data() + off * 2, frames,
                                   out.data() + produced * 2,
                                   static_cast<int>(out.size()) / 2 - produced);
    }
    const int L = engine.tapsPerPhase() / 2;
    int mismatches = 0;
    for (int j = L - 1; j < produced; ++j) {
        if (out[j * 2] != in[j * 2] || out[j * 2 + 1] != in[j * 2 + 1]) ++mismatches;
    }
    std::printf("    produced %d frames, bit-exact mismatches after pre-roll: %d\n",
                produced, mismatches);
    CHECK(produced >= N - 2 * L, "produced %d < %d", produced, N - 2 * L);
    CHECK(mismatches == 0, "%d frames not bit-exact at 0 PPM", mismatches);
}

// ---------------------------------------------------------------------------
// [DSP] SNR vs double-precision oracle under steady drift
// ---------------------------------------------------------------------------
static void testResamplerSnr() {
    banner("RESAMPLER: SNR vs double-precision oracle (+300 PPM)");
    struct Case { const char* name; double freqHz; double driftPpm; double minSnrDb; };
    const Case cases[] = {
        {"1 kHz @ +300 PPM",    1000.0,  300.0, 78.0},
        {"1 kHz @ -500 PPM",    1000.0, -500.0, 78.0},
        {"15 kHz @ +300 PPM",  15000.0,  300.0, 62.0},
        {"17 kHz @ +500 PPM",  17000.0,  500.0, 55.0},
    };
    for (const auto& tc : cases) {
        AcousticPhaseResampler engine;
        oracle::SlowOracle orc(engine);
        const int N = 24000;   // 0.5 s
        std::vector<float> in(N * 2);
        for (int i = 0; i < N; ++i) {
            const double t = static_cast<double>(i) / 48000.0;
            const float v = 0.8f * static_cast<float>(
                std::sin(2.0 * M_PI * tc.freqHz * t));
            in[i * 2] = v;
            in[i * 2 + 1] = v;
        }
        engine.setTargetDriftPpm(tc.driftPpm);
        std::vector<float> out((N + 64) * 2);
        int produced = 0;
        for (int off = 0; off < N; off += 2048) {
            const int frames = std::min(2048, N - off);
            produced += engine.process(in.data() + off * 2, frames,
                                       out.data() + produced * 2,
                                       static_cast<int>(out.size()) / 2 - produced);
        }
        std::vector<float> ref;
        orc.run(in, N, ref, tc.driftPpm, produced);
        const double snr = computeSnr(out, ref, 1200, 2);   // skip slew transient
        std::printf("    %-20s SNR = %6.2f dB (min %.1f)\n", tc.name, snr, tc.minSnrDb);
        CHECK(snr >= tc.minSnrDb, "SNR %.2f < %.1f dB", snr, tc.minSnrDb);
    }
}

// ---------------------------------------------------------------------------
// [DSP] Block-size invariance (determinism proof)
// ---------------------------------------------------------------------------
static void testResamplerBlockInvariance() {
    banner("RESAMPLER: block-size invariance (bit-exact)");
    const int N = 48000;
    std::vector<float> in(N * 2);
    std::mt19937_64 rng(0xABC);
    std::uniform_real_distribution<float> noise(-0.8f, 0.8f);
    for (auto& v : in) v = noise(rng);

    // Run A: uniform 4096-frame blocks.
    AcousticPhaseResampler a;
    a.setTargetDriftPpm(217.3);
    std::vector<float> outA((N + 4096) * 2);
    int producedA = 0;
    for (int off = 0; off < N; off += 4096) {
        const int frames = std::min(4096, N - off);
        producedA += a.process(in.data() + off * 2, frames,
                               outA.data() + producedA * 2,
                               static_cast<int>(outA.size()) / 2 - producedA);
    }
    // Run B: pseudo-random block sizes 17..1500.
    AcousticPhaseResampler b;
    b.setTargetDriftPpm(217.3);
    std::vector<float> outB((N + 4096) * 2);
    int producedB = 0;
    int off = 0;
    while (off < N) {
        const int frames = std::min(N - off, 17 + static_cast<int>(rng() % 1484));
        producedB += b.process(in.data() + off * 2, frames,
                               outB.data() + producedB * 2,
                               static_cast<int>(outB.size()) / 2 - producedB);
        off += frames;
    }
    std::printf("    A: %d frames, B: %d frames\n", producedA, producedB);
    CHECK(producedA == producedB, "frame counts differ: %d vs %d", producedA, producedB);
    int mismatch = 0;
    for (int j = 0; j < producedA * 2; ++j)
        if (outA[j] != outB[j]) ++mismatch;
    std::printf("    sample mismatches: %d\n", mismatch);
    CHECK(mismatch == 0, "%d samples differ across blockings", mismatch);
}

// ---------------------------------------------------------------------------
// [DSP] Drift accuracy via exact fixed-point accounting
// ---------------------------------------------------------------------------
static void testResamplerDriftAccuracy() {
    banner("RESAMPLER: long-run drift accuracy (fixed-point accounting)");
    AcousticPhaseResampler engine;
    engine.setTargetDriftPpm(250.0);
    std::vector<float> in(2048 * 2), out(4096 * 2);
    for (auto& v : in) v = 0.25f;

    // Two-segment differencing. The engine holds back a constant L-frame
    // lookahead and the slew ramp integrates a constant position deficit —
    // both are ADDITIVE constants in the (consumed, produced) counters, so
    // differencing two on-stream checkpoints cancels them exactly and the
    // ratio of the deltas is the true applied step, free of edge effects.
    auto feed = [&](int frames) {
        int off = 0;
        while (off < frames) {
            const int n = std::min(2048, frames - off);
            engine.process(in.data(), n, out.data(),
                           static_cast<int>(out.size()) / 2);
            off += n;
        }
    };
    feed(48'000);   // 1 s warm-up: slew (tau = 250 ms) settles to e^-16.
    const int64_t c1 = engine.framesConsumed(), p1 = engine.framesProduced();
    feed(432'000);  // 9 s measurement segment, fully settled.
    const int64_t c2 = engine.framesConsumed(), p2 = engine.framesProduced();

    // step = input advance per output frame = 1 + delta; delta = 250 PPM
    // (positive: play faster => fewer output frames per input frame).
    const double achievedPpm =
        (1.0 - static_cast<double>(p2 - p1) / static_cast<double>(c2 - c1)) * 1e6;
    std::printf("    segment: consumed %lld, produced %lld -> %.3f PPM (target 250)\n",
                static_cast<long long>(c2 - c1),
                static_cast<long long>(p2 - p1), achievedPpm);
    // Residual: fixed-point rounding of the step (< 1 ulp of 2^32) plus at
    // most one boundary frame across the segment -> a few PPM worst case.
    CHECK(std::abs(achievedPpm - 250.0) < 4.0,
          "achieved %.3f PPM vs 250 target", achievedPpm);
}

// ---------------------------------------------------------------------------
// [DSP] Click/discontinuity hunting under randomized drift schedules
// ---------------------------------------------------------------------------
static void testResamplerNoClicks() {
    banner("RESAMPLER: discontinuity hunting (random drift flips)");
    AcousticPhaseResampler engine;
    std::mt19937_64 rng(0xC11C4E5AULL);
    const int blocks = 1500;      // 1500 * 2048 frames ~= 64 s
    std::vector<float> in(2048 * 2), out(4096 * 2);
    double maxJump = 0.0;
    float prevL = 0.0f;
    long long frame = 0;
    int producedTotal = 0;
    for (int blk = 0; blk < blocks; ++blk) {
        if (blk % 10 == 0) {
            const double target = (static_cast<double>(rng() % 1001) - 500.0);
            engine.setTargetDriftPpm(target);
        }
        for (int i = 0; i < 2048; ++i) {
            const float v = 0.9f * static_cast<float>(
                std::sin(2.0 * M_PI * 997.0 * static_cast<double>(frame) / 48000.0));
            in[i * 2] = v;
            in[i * 2 + 1] = v;
            ++frame;
        }
        const int produced = engine.process(in.data(), 2048, out.data(),
                                            static_cast<int>(out.size()) / 2);
        for (int j = 0; j < produced; ++j) {
            const float d = std::abs(out[j * 2] - prevL);
            if (d > maxJump) maxJump = d;
            prevL = out[j * 2];
            if (!std::isfinite(out[j * 2]) || !std::isfinite(out[j * 2 + 1])) {
                CHECK(false, "non-finite sample at block %d frame %d", blk, j);
                return;
            }
        }
        producedTotal += produced;
    }
    // 997 Hz @ 0.9 amplitude, fs 48k: max inter-sample step = 0.9*2*pi*997/48k
    const double bound = 0.9 * 2.0 * M_PI * 997.0 / 48000.0;
    std::printf("    max inter-sample step = %.5f (input slope bound %.5f, %d frames)\n",
                maxJump, bound, producedTotal);
    CHECK(maxJump <= bound * 1.05 + 1e-4,
          "max step %.5f exceeds slope bound %.5f (click!)", maxJump, bound);
}

// ---------------------------------------------------------------------------
// [HW] Latency profiler regression vs ground truth
// ---------------------------------------------------------------------------
static void testProfilerRegression() {
    banner("PROFILER: WLS latency regression vs ground truth");
    HardwareLatencyProfiler prof;
    prof.setOutputRoute(HardwareLatencyProfiler::Route::BuiltInSpeaker,
                        HardwareLatencyProfiler::Codec::Pcm);
    // Ground truth model (all in the RAW domain):
    //   presented frame f at RAW time  A + f / r   (r = 48000 * (1 + 120 PPM))
    //   write head keeps `queue` frames queued (jittering 1600..2400)
    //   residual transducer latency = prior(speaker) = 12 ms (model matches)
    // The sink REPORTS in CLOCK_MONOTONIC (AudioTrack.nanoTime); the
    // profiler bridges reports onto RAW via its live (mono - raw) EMA. A
    // genuine MONOTONIC-domain report therefore carries the platform's
    // (mono - raw) offset: inject it here, and the bridge must cancel it
    // back out — this simultaneously exercises the bridge machinery.
    const double r = 48000.0 * (1.0 + 120.0e-6);
    // Anchor the synthetic session to the HOST's RAW clock: the profiler's
    // staleness gate compares anchor submit times (real CLOCK_MONOTONIC_RAW
    // reads taken inside submitTrackTimestamp) against the caller-supplied
    // nowRaw, so a detached epoch (the old fixed 1e12 = ~16.7 min of
    // uptime) reads as hours-stale on any host booted more recently than
    // that — a freshly-provisioned CI runner — and the regression never
    // goes live. Pin the session to END at the host's now instead: the
    // synthetic 20 s of audio is still consumed in microseconds of wall
    // time, so the age-decay weights and the staleness window both see
    // ~zero age, exactly what this compressed simulation wants.
    int64_t totalFrames = 0;
    for (int k = 0; k < 400; ++k)
        totalFrames += static_cast<int64_t>(0.05 * r);
    const int64_t t0Raw = streamify::nowRawNanos() -
        static_cast<int64_t>(static_cast<double>(totalFrames) / r * 1e9);
    struct timespec tsM;
    ::clock_gettime(CLOCK_MONOTONIC, &tsM);
    const int64_t dHostMonoMinusRaw =
        static_cast<int64_t>(tsM.tv_sec) * 1'000'000'000LL + tsM.tv_nsec -
        streamify::nowRawNanos();
    std::mt19937_64 rng(0x5EEDC0DEULL);
    std::normal_distribution<double> tsNoise(0.0, 250'000.0);   // 250 us sigma
    std::uniform_real_distribution<double> uni(0.0, 1.0);
    int64_t frame = 0;
    for (int k = 0; k < 400; ++k) {
        frame += static_cast<int64_t>(0.05 * r);   // 50 ms of audio per anchor
        const double queue = 2000.0 + 400.0 * std::sin(k * 0.37);
        const int64_t written = frame + static_cast<int64_t>(queue);
        const int64_t tauMonoRaw = t0Raw + dHostMonoMinusRaw + static_cast<int64_t>(
            static_cast<double>(frame) / r * 1e9 + tsNoise(rng));
        // 12% of anchors are stack-garbage outliers (+-30 ms).
        const int64_t tauReport = (uni(rng) < 0.12)
            ? tauMonoRaw + (uni(rng) < 0.5 ? -30'000'000 : 30'000'000)
            : tauMonoRaw;
        prof.submitTrackTimestamp(frame, tauReport, written, written - frame);
    }
    const int64_t nowRaw = t0Raw + static_cast<int64_t>(
        static_cast<double>(frame) / r * 1e9);
    const auto est = prof.estimatePlayoutDelay(nowRaw);
    // Truth: queue(=2000..2400 frames)/r + 12 ms prior.
    const double queueFrames = 2000.0;   // central value
    const double truth = (queueFrames / r) * 1e9 +
                         static_cast<double>(est.priorNanos);
    const double errUs = std::abs(static_cast<double>(est.delayNanos) - truth) / 1e3;
    std::printf("    live=%d delay=%.1f ms truth=%.1f ms err=%.1f us, rate=%+.1f PPM, sigma=%.1f us\n",
                est.live, est.delayNanos / 1e6, truth / 1e6, errUs,
                est.effectiveRatePpm, est.residualSigmaNanos / 1e3);
    CHECK(est.live, "regression did not go live");
    CHECK(errUs < 3000.0, "delay error %.1f us >= 3000 us", errUs);
    CHECK(std::abs(est.effectiveRatePpm - 120.0) < 25.0,
          "effective rate %.1f PPM vs 120", est.effectiveRatePpm);
}

// ---------------------------------------------------------------------------
// [HW] Prior fallback + route switching
// ---------------------------------------------------------------------------
static void testProfilerPriorFallback() {
    banner("PROFILER: prior fallback and route switching");
    HardwareLatencyProfiler prof;
    const int64_t unknown = prof.priorLatencyNanos(
        HardwareLatencyProfiler::Route::Unknown,
        HardwareLatencyProfiler::Codec::Unknown);
    const int64_t sbc = prof.priorLatencyNanos(
        HardwareLatencyProfiler::Route::BluetoothA2dp,
        HardwareLatencyProfiler::Codec::Sbc);
    const int64_t ldac = prof.priorLatencyNanos(
        HardwareLatencyProfiler::Route::BluetoothA2dp,
        HardwareLatencyProfiler::Codec::Ldac);
    const int64_t speaker = prof.priorLatencyNanos(
        HardwareLatencyProfiler::Route::BuiltInSpeaker,
        HardwareLatencyProfiler::Codec::Pcm);
    std::printf("    unknown=%lld ms, speaker=%lld ms, sbc=%lld ms, ldac=%lld ms\n",
                static_cast<long long>(unknown / 1'000'000),
                static_cast<long long>(speaker / 1'000'000),
                static_cast<long long>(sbc / 1'000'000),
                static_cast<long long>(ldac / 1'000'000));
    CHECK(speaker < sbc, "speaker prior not below SBC");
    CHECK(ldac < sbc, "LDAC prior not below SBC");
    CHECK(unknown > 0 && sbc >= 100'000'000 && ldac < sbc, "prior table sanity");

    // No anchors submitted -> readout falls back to the current prior.
    prof.setOutputRoute(HardwareLatencyProfiler::Route::BluetoothA2dp,
                        HardwareLatencyProfiler::Codec::Ldac);
    const auto est = prof.estimatePlayoutDelay(streamify::nowRawNanos());
    CHECK(!est.live, "went live without anchors");
    CHECK(est.delayNanos == ldac, "fallback did not use LDAC prior");

    // Route switch must clear anchors (tested via anchor count).
    prof.submitTrackTimestamp(1000, 1'000'000'000LL, 3000, 2000);
    CHECK(prof.anchorCount() == 1, "anchor not stored");
    prof.setOutputRoute(HardwareLatencyProfiler::Route::WiredUsbDac,
                        HardwareLatencyProfiler::Codec::Pcm);
    CHECK(prof.anchorCount() == 0, "route switch did not clear anchors");
}

// ---------------------------------------------------------------------------
// [PERF] Zero-allocation proof on the steady-state hot path
// ---------------------------------------------------------------------------
static void testZeroAllocationHotPath() {
    banner("PERF: zero-allocation proof (resampler + PTP + profiler)");
    // Construct OUTSIDE the guarded region: tables, ring buffers and the
    // profiler window are pre-allocated at construction/config time.
    AcousticPhaseResampler engine;
    PtpEngine ptp;
    HardwareLatencyProfiler prof;
    prof.setOutputRoute(HardwareLatencyProfiler::Route::BuiltInSpeaker,
                        HardwareLatencyProfiler::Codec::Pcm);
    engine.setTargetDriftPpm(133.7);

    std::vector<float> in(2048 * 2), out(4096 * 2);
    for (auto& v : in) v = 0.1f;
    std::mt19937_64 rng(0xA110C);
    sim::Clocks c;
    c.freqPpm = 10.0;
    c.theta0Nanos = 5.0e6;

    // Warm-up (fills profiler window to steady state, no fit allocations
    // expected afterwards either, but be conservative).
    for (int k = 1; k <= 100; ++k) {
        const auto e = sim::makeExchange(c, rng, k * 0.25, 2.0, 100.0, 0.0, 0.0, 1.0);
        ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3);
    }
    for (int k = 0; k < 80; ++k)
        prof.submitTrackTimestamp(k * 2400, 1'000'000'000LL + k * 50'000'000LL,
                                  k * 2400 + 2000, 2000);

    g_allocCount.store(0, std::memory_order_relaxed);
    g_allocGuardArmed.store(true, std::memory_order_relaxed);

    const int64_t syncedBefore = ptp.getSynchronizedClockNanos();
    for (int iter = 0; iter < 2000; ++iter) {
        engine.process(in.data(), 2048, out.data(),
                       static_cast<int>(out.size()) / 2);
    }
    for (int k = 101; k <= 400; ++k) {
        const auto e = sim::makeExchange(c, rng, k * 0.25, 2.0, 100.0, 0.0, 0.0, 1.0);
        ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3);
    }
    for (int k = 80; k < 200; ++k)
        prof.submitTrackTimestamp(k * 2400, 1'000'000'000LL + k * 50'000'000LL,
                                  k * 2400 + 2000, 2000);
    const auto est = prof.estimatePlayoutDelay(streamify::nowRawNanos());
    const int64_t syncedAfter = ptp.getSynchronizedClockNanos();
    (void)est; (void)syncedBefore; (void)syncedAfter;

    g_allocGuardArmed.store(false, std::memory_order_relaxed);
    const int64_t allocs = g_allocCount.load(std::memory_order_relaxed);
    std::printf("    allocations during 2000 blocks + 300 PTP updates + 120 anchors: %lld\n",
                static_cast<long long>(allocs));
    CHECK(allocs == 0, "%lld allocations leaked into the hot path",
          static_cast<long long>(allocs));
}

// ---------------------------------------------------------------------------
// [PERF] Throughput micro-benchmark (scalar on x86 host; NEON on device)
// ---------------------------------------------------------------------------
static void benchmarkResampler(const char* label) {
    AcousticPhaseResampler engine;
    engine.setTargetDriftPpm(250.0);
    const int frames = 2048;
    std::vector<float> in(frames * 2), out((frames + 256) * 2);
    for (auto& v : in) v = 0.25f;
    // Warm-up.
    for (int i = 0; i < 50; ++i)
        engine.process(in.data(), frames, out.data(), static_cast<int>(out.size()) / 2);
    engine.reset();
    const int iters = 500;
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; ++i)
        engine.process(in.data(), frames, out.data(), static_cast<int>(out.size()) / 2);
    const auto t1 = std::chrono::steady_clock::now();
    const double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / iters;
    const double nsPerFrame = ns / frames;
    const double cpuPctA55 = nsPerFrame * 48000.0 / 1.4e9 * 100.0 * 2.0; // stereo scalar-ish, 1.4 GHz A55, conservative 2x scalar factor
    std::printf("    %-26s %8.1f ns/block  %6.2f ns/frame  (~%.2f%% of an A55 core, est.)\n",
                label, ns, nsPerFrame, cpuPctA55);
}

// ---------------------------------------------------------------------------
// [FUZZ] Randomized soak across all engines
// ---------------------------------------------------------------------------
static void testFuzzSoak() {
    banner("FUZZ: 200-seed randomized soak");
    int failures = 0;
    for (int seed = 0; seed < 200; ++seed) {
        std::mt19937_64 rng(0xF00D + seed);
        try {
            // PTP: random jitter / burst / garbage mixes.
            sim::Clocks c;
            c.freqPpm = -30.0 + 60.0 * (static_cast<double>(rng() % 1000) / 1000.0);
            c.theta0Nanos = (static_cast<double>(rng() % 200'000'000) - 100'000'000.0);
            PtpEngine ptp;
            const double jit = 20.0 + 400.0 * (static_cast<double>(rng() % 100) / 100.0);
            const double burstP = 0.30 * (static_cast<double>(rng() % 100) / 100.0);
            for (int k = 1; k <= 150; ++k) {
                const auto e = sim::makeExchange(c, rng, k * 0.25, 2.0, jit, burstP, 8.0, 1.0);
                int64_t r = ptp.processTimestamps(e.t0, e.t1, e.t2, e.t3);
                if (r == INT64_MIN) ++failures;
            }
            const int64_t s = ptp.getSynchronizedClockNanos();
            if (s < c.bootOffsetNanos - 3600'000'000'000LL ||
                s > c.bootOffsetNanos + 7200'000'000'000LL) ++failures;

            // Resampler: random drift schedule, random blocks.
            AcousticPhaseResampler engine;
            engine.setTargetDriftPpm(-500.0 + 1000.0 * (static_cast<double>(rng() % 1000) / 1000.0));
            std::vector<float> in(1024 * 2), out(4096 * 2);
            for (int blk = 0; blk < 200; ++blk) {
                if (blk % 7 == 0)
                    engine.setTargetDriftPpm(-500.0 + 1000.0 * (static_cast<double>(rng() % 1000) / 1000.0));
                const float f = static_cast<float>(rng() % 1000) / 1000.0f - 0.5f;
                for (auto& v : in) v = f;
                const int n = engine.process(in.data(), 1024, out.data(),
                                             static_cast<int>(out.size()) / 2);
                if (n < 0 || n > 4096) { ++failures; break; }
                for (int j = 0; j < n * 2; ++j)
                    if (!std::isfinite(out[j])) { ++failures; break; }
            }
            if (std::abs(engine.framesProduced() - engine.framesConsumed()) >
                4096 + 200 * 1024 / 500) {
                // production/consumption ratio must stay within drift bounds
                const double ratio = static_cast<double>(engine.framesConsumed()) /
                                     static_cast<double>(std::max<int64_t>(1, engine.framesProduced()));
                if (ratio < 0.9994 || ratio > 1.0006) ++failures;
            }

            // Profiler: random anchors.
            HardwareLatencyProfiler prof;
            prof.setOutputRoute(
                static_cast<HardwareLatencyProfiler::Route>(rng() % 6),
                static_cast<HardwareLatencyProfiler::Codec>(rng() % 8));
            for (int k = 0; k < 100; ++k) {
                const int64_t fr = k * 2400;
                const int64_t tau = 1'000'000'000LL + fr * 20833 +
                                    static_cast<int64_t>(rng() % 10'000'000) - 5'000'000;
                prof.submitTrackTimestamp(fr, tau, fr + 1000 + static_cast<int64_t>(rng() % 500), -1);
            }
            const auto est = prof.estimatePlayoutDelay(streamify::nowRawNanos());
            if (est.delayNanos < 0 || est.delayNanos > 1'000'000'000LL) ++failures;
        } catch (...) {
            ++failures;
        }
    }
    std::printf("    200 seeds x (PTP + resampler + profiler): %d failures\n", failures);
    CHECK(failures == 0, "%d fuzz failures", failures);
}

// ---------------------------------------------------------------------------
int main(int argc, char** argv) {
    std::printf("============================================================\n");
    std::printf(" Streamify DSP / PTP / Latency — native phase-lock test suite\n");
    std::printf(" build: %s\n",
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
                "ARM NEON kernel"
#else
                "portable scalar kernel (host)"
#endif
    );
    std::printf("============================================================\n");

    const bool perfOnly = (argc > 1 && 0 == std::strcmp(argv[1], "--perf"));

    if (!perfOnly) {
        testPtpGaussianConvergence();
        testPtpParetoBursts();
        testPtpDriftTracking();
        testPtpOutlierStorm();
        testPtpReadoutSanity();
        testResamplerIdentity();
        testResamplerSnr();
        testResamplerBlockInvariance();
        testResamplerDriftAccuracy();
        testResamplerNoClicks();
        testProfilerRegression();
        testProfilerPriorFallback();
        testZeroAllocationHotPath();
        testFuzzSoak();
    }

    banner("PERF: throughput micro-benchmark");
    benchmarkResampler("stereo 48k, 2048-frame blocks");

    std::printf("============================================================\n");
    std::printf(" %d checks passed, %d failed\n", g_checksPassed, g_checksFailed);
    std::printf("============================================================\n");
    return g_checksFailed == 0 ? 0 : 1;
}
