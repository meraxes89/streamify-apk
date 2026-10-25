// ============================================================================
//  test_dsp_phase1.cc — Phase 1 verification: audiophile DSP + 32-peer mix
// ============================================================================
//
//  Covers BEHIND.md gaps #11 (32-peer mixing), #13 (silent PLL), #39
//  (calibrated LUFS + limiter), #41 (mono/balance/ceiling).
//
//  Linked into dsp_test_suite (native-dsp CI shard); entry point
//  run_dsp_phase1_tests() is called from test_dsp.cc's main().
//
//  [PERF] Zero-allocation proof: global operator new guard (armed only
//  around the audited hot-path calls — see AllocGuard below).
// ============================================================================

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <memory>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "../dsp/AcousticPhaseResampler.h"
#include "../dsp/ChannelOps.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/MasterChain.h"
#include "../dsp/SoftKneeLimiter.h"
#include "../engine/PtpEngine.h"
#include "../mix/AudioRingBuffer.h"
#include "../mix/PeerMixPool.h"

using streamify::dsp::AcousticPhaseResampler;
using streamify::dsp::ChannelOps;
using streamify::dsp::MasterChain;
namespace mix = streamify::mix;

// ---------------------------------------------------------------------------
// Allocation guard (zero-alloc proof for the hot paths). Since Phase 2 the
// counters + guard struct live in AllocGuard.h (shared with
// test_harmonic_math.cc and the Phase-3 suites); THIS TU still owns the
// single definition of the global operator new/delete replacements.
// Since Phase 3 the counters are inline function-local thread_locals
// (see AllocGuard.h for the GCC-14 -O2 extern-TLS rationale).
// ---------------------------------------------------------------------------
#include "AllocGuard.h"

void* operator new(std::size_t sz) {
    if (streamify_test::guardDepthRef() > 0) {
        ++streamify_test::allocCountRef();
    }
    void* p = std::malloc(sz ? sz : 1);
    if (p == nullptr) throw std::bad_alloc();
    return p;
}
void* operator new[](std::size_t sz) { return ::operator new(sz); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

using streamify_test::AllocGuard;

// ---------------------------------------------------------------------------
// Deterministic RNG (SplitMix64) + signal helpers
// ---------------------------------------------------------------------------
namespace {
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
    float unit() { return static_cast<float>(next() >> 11) * (1.0f / 9007199254740992.0f); }
    float sym() { return unit() * 2.0f - 1.0f; }
    double gauss() {   // Irwin-Hall sum of 12 uniforms (mean 0, var 1)
        double a = 0.0;
        for (int i = 0; i < 12; ++i) a += static_cast<double>(next() >> 11) / 9007199254740992.0;
        return a - 6.0;
    }
};

void fillSineStereo(std::vector<float>& buf, int frames, float amp,
                    double freqHz, double phase = 0.0, bool bothChannels = true) {
    buf.assign(static_cast<size_t>(frames) * 2, 0.0f);
    for (int i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / 48000.0;
        const float v = static_cast<float>(amp * std::sin(2.0 * M_PI * freqHz * t + phase));
        buf[static_cast<size_t>(i) * 2] = v;
        if (bothChannels) buf[static_cast<size_t>(i) * 2 + 1] = v;
    }
}

void feedInBlocks(LufsNormalizer& n, const std::vector<float>& data, int totalFrames,
                  int blockFrames) {
    int fed = 0;
    while (fed < totalFrames) {
        const int n2 = std::min(blockFrames, totalFrames - fed);
        n.processStream(const_cast<float*>(data.data()) + static_cast<size_t>(fed) * 2, n2);
        fed += n2;
    }
}

int g_passed = 0;
void check(bool ok, const std::string& what) {
    if (!ok) {
        std::fprintf(stderr, "[PHASE1] FAILED: %s\n", what.c_str());
        std::fflush(stderr);
        assert(false);
    }
    ++g_passed;
}
}  // namespace

// ---------------------------------------------------------------------------
// Section 1 — BS.1770-4 calibration anchors
// ---------------------------------------------------------------------------
static void test_lufs_calibration() {
    std::cout << "  - Phase1/BS.1770: calibration anchors" << std::endl;

    // K-filter fit self-check: the De Man design at 48 kHz reproduces the
    // ITU spec literals to < 2.5e-3.
    {
        BiquadCoeffs s1, s2;
        LufsNormalizer::designKFilters(48000, &s1, &s2);
        const float specS1[5] = {1.53512485958697f, -2.69169618940638f,
                                  1.19839251015039f, -1.69065929318241f,
                                  0.73248077421585f};
        const float specS2[5] = {1.0f, -2.0f, 1.0f, -1.99004745483398f,
                                  0.99007225036621f};
        const float f1[5] = {s1.b0, s1.b1, s1.b2, s1.a1, s1.a2};
        const float f2[5] = {s2.b0, s2.b1, s2.b2, s2.a1, s2.a2};
        for (int i = 0; i < 5; ++i) {
            check(std::fabs(f1[i] - specS1[i]) < 2.5e-3f, "shelf fit vs ITU literal");
            check(std::fabs(f2[i] - specS2[i]) < 2.5e-3f, "RLB fit vs ITU literal");
        }
    }

    // 997 Hz stereo sine, RMS -23 dBFS/channel -> -20.0 LUFS.
    {
        LufsNormalizer n;
        const float amp = std::sqrt(2.0f) * std::pow(10.0f, -23.0f / 20.0f);  // 0.10012
        std::vector<float> data;
        fillSineStereo(data, 48000 * 12, amp, 997.0);
        feedInBlocks(n, data, 48000 * 12, 960);
        const float I = n.integratedLufs();
        check(std::fabs(I - -20.0f) < 0.15f, "stereo -23 dBFS sine -> -20 LUFS");
        check(std::fabs(n.momentaryLufs() - -20.0f) < 0.15f, "momentary anchor");
        check(std::fabs(n.shortTermLufs() - -20.0f) < 0.15f, "short-term anchor");
    }
    // Same signal MONO (L only) -> -23.0 LUFS (the R128 alignment anchor).
    {
        LufsNormalizer n;
        const float amp = std::sqrt(2.0f) * std::pow(10.0f, -23.0f / 20.0f);
        std::vector<float> data;
        fillSineStereo(data, 48000 * 12, amp, 997.0, 0.0, false);
        feedInBlocks(n, data, 48000 * 12, 960);
        check(std::fabs(n.integratedLufs() - -23.0f) < 0.15f, "mono -23 dBFS sine -> -23 LUFS");
    }
    // Full-scale (0 dBFS peak/ch) stereo sine -> ~0.0 LUFS.
    {
        LufsNormalizer n;
        std::vector<float> data;
        fillSineStereo(data, 48000 * 8, 1.0f, 997.0);
        feedInBlocks(n, data, 48000 * 8, 960);
        check(std::fabs(n.integratedLufs() - 0.0f) < 0.25f, "full-scale stereo -> 0 LUFS");
    }
    // 44.1 kHz geometry: K-weight at 1 kHz stays within the ITU mask.
    {
        BiquadCoeffs s1, s2;
        LufsNormalizer::designKFilters(44100, &s1, &s2);
        const double w = 2.0 * M_PI * 1000.0 / 44100.0;
        auto resp = [&](const BiquadCoeffs& c) {
            const double nr = c.b0 + c.b1 * std::cos(w) + c.b2 * std::cos(2.0 * w);
            const double ni = -(c.b1 * std::sin(w)) - (c.b2 * std::sin(2.0 * w));
            const double dr = 1.0 + c.a1 * std::cos(w) + c.a2 * std::cos(2.0 * w);
            const double di = -(c.a1 * std::sin(w)) - (c.a2 * std::sin(2.0 * w));
            return std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
        };
        const double kDb = 20.0 * std::log10(resp(s1) * resp(s2));
        check(kDb > 0.55 && kDb < 0.85, "44.1k K-weight at 1 kHz inside ITU mask");
    }
}

// ---------------------------------------------------------------------------
// Section 2 — dual gating
// ---------------------------------------------------------------------------
static void test_lufs_gating() {
    std::cout << "  - Phase1/BS.1770: dual gating" << std::endl;
    LufsNormalizer n;
    const float amp = std::sqrt(2.0f) * std::pow(10.0f, -23.0f / 20.0f);
    const float quiet = std::sqrt(2.0f) * std::pow(10.0f, -80.0f / 20.0f);
    std::vector<float> data;
    // 3 s @ -80 dBFS | 6 s @ -23 dBFS RMS (== -20 LUFS) | 3 s @ -80 dBFS
    for (int seg = 0; seg < 3; ++seg) {
        std::vector<float> part;
        fillSineStereo(part, 48000 * (seg == 1 ? 6 : 3),
                       seg == 1 ? amp : quiet, 997.0);
        data.insert(data.end(), part.begin(), part.end());
    }
    feedInBlocks(n, data, 48000 * 12, 960);
    const float I = n.integratedLufs();
    // Gated: -20.0 (loud stretch). Ungated would read ~-23.0.
    check(I > -21.0f && I < -19.5f, "gating excludes below-threshold material");
}

// ---------------------------------------------------------------------------
// Section 3 — normalization convergence + glide continuity
// ---------------------------------------------------------------------------
static void test_normalization_convergence() {
    std::cout << "  - Phase1/normalize: -8 LUFS -> -14 target" << std::endl;
    // -8 LUFS stereo anchor: sum(z) = 10^((-8+0.691)/10) = 0.1857
    // -> per-channel mean-square 0.0791 (K^2 @997 Hz = 1.1733) -> amp 0.3979.
    const float amp = 0.3979f;
    std::vector<float> in;
    fillSineStereo(in, 48000 * 20, amp, 997.0);

    LufsNormalizer a, b;
    a.setTargetLufs(-14.0f);
    b.setTargetLufs(-14.0f);
    std::vector<float> outA(static_cast<size_t>(48000 * 20) * 2);
    std::vector<float> outB(static_cast<size_t>(48000 * 20) * 2);

    float maxSlope = 0.0f;
    float prevGain = 1.0f;
    int fed = 0;
    while (fed < 48000 * 20) {
        const int n2 = std::min(960, 48000 * 20 - fed);
        std::memcpy(outA.data() + static_cast<size_t>(fed) * 2,
                    in.data() + static_cast<size_t>(fed) * 2,
                    static_cast<size_t>(n2) * 2 * sizeof(float));
        // Feed one frame per call for the glide-slope audit: this exposes
        // the TRUE per-sample gain trajectory (a per-block read would only
        // see the block-end state).
        for (int i = 0; i < n2; ++i) {
            a.processStream(outA.data() + static_cast<size_t>(fed + i) * 2, 1);
            const float g = a.currentGainLinear();
            const float slope = std::fabs(g - prevGain);
            if (slope > maxSlope) maxSlope = slope;
            prevGain = g;
        }
        fed += n2;
    }
    // Determinism AND block invariance: engine `a` was fed one frame per
    // call, engine `b` 960 frames per call — identical output proves the
    // gain trajectory is call-blocking independent (no block-edge steps).
    {
        std::memcpy(outB.data(), in.data(), outB.size() * sizeof(float));
        feedInBlocks(b, outB, 48000 * 20, 960);
        check(std::memcmp(outA.data(), outB.data(), outA.size() * sizeof(float)) == 0,
              "bit-exact + block-invariant normalization");
    }
    // After 20 s the setpoint has fully converged to target - input.
    const float expected = -14.0f - a.integratedLufs();
    check(std::fabs(a.appliedGainDb() - expected) < 0.05f, "setpoint == target - I");
    // A -8 LUFS track is 6 LU HOTTER than the -14 target -> -6 dB gain.
    check(std::fabs(a.appliedGainDb() - -6.0f) < 0.4f, "gain converges to -6 dB");
    check(maxSlope < 1e-3f, "glide slope bounded (no clicks)");

    // Output loudness lands on the target.
    LufsNormalizer meter;
    meter.setTargetLufs(-14.0f);
    feedInBlocks(meter, outA, 48000 * 20, 960);
    check(std::fabs(meter.integratedLufs() - -14.0f) < 0.6f, "output measures -14 LUFS");
}

// ---------------------------------------------------------------------------
// Section 4 — true-peak limiter
// ---------------------------------------------------------------------------
static void test_limiter() {
    std::cout << "  - Phase1/limiter: true-peak ceiling + inter-sample" << std::endl;

    // (a) Quiet material: bit-exact identity (sat stage + unity gain).
    {
        SoftKneeLimiter lim(-1.0f, 4.0f);
        std::vector<float> in, ref;
        fillSineStereo(in, 4800, 0.3f, 997.0);
        ref = in;
        lim.processFrames(in.data(), 4800, 2);
        check(std::memcmp(in.data(), ref.data(), in.size() * sizeof(float)) == 0,
              "below-knee signal is bit-exact passthrough");
    }

    // (b) Inter-sample clipping: 12 kHz sine at pi/4 phase, A = 1.26.
    //     Samples read +-0.891 (== -1 dBFS ceiling) while the TRUE peak is
    //     +2.0 dBFS — only the 4x sidechain can see it.
    {
        SoftKneeLimiter lim(-1.0f, 4.0f);          // ceiling -1 dBFS
        SoftKneeLimiter measurer;                   // fs-independent kernels
        const float ceilLin = std::pow(10.0f, -1.0f / 20.0f);
        const float A = 1.26f;
        std::vector<float> in;
        fillSineStereo(in, 48000, 0.0f, 0.0);       // sized, then rewritten
        for (int i = 0; i < 48000; ++i) {
            const double ph = M_PI * 0.25 + M_PI * 0.5 * i;   // 12 kHz @ 48k
            const float v = static_cast<float>(A * std::cos(ph));
            in[static_cast<size_t>(i) * 2] = v;
            in[static_cast<size_t>(i) * 2 + 1] = v;
        }
        const float inTp = measurer.measureTruePeak(in.data(), 48000, 2);
        check(inTp > ceilLin * 1.1f, "test vector is genuinely inter-sample hot");
        lim.processFrames(in.data(), 48000, 2);
        const float outTp = measurer.measureTruePeak(in.data(), 48000, 2);
        check(outTp <= ceilLin * 1.007f, "output true-peak <= ceiling + 0.06 dB");
        check(outTp < inTp * 0.85f, "limiting actually engaged");
        float mx = 0.0f;
        for (float v : in) mx = std::max(mx, std::fabs(v));
        check(mx <= ceilLin * 1.005f, "sample bound <= ceiling*1.004");
    }

    // (c) Ordinary hot sine: 997 Hz at +6 dBFS peak, ceiling -1 dBFS.
    {
        SoftKneeLimiter lim(-1.0f, 4.0f);
        SoftKneeLimiter measurer;
        const float ceilLin = std::pow(10.0f, -1.0f / 20.0f);
        std::vector<float> in;
        fillSineStereo(in, 48000, 2.0f, 997.0);
        const float inTp = measurer.measureTruePeak(in.data(), 48000, 2);
        check(inTp > 1.9f, "input is hot");
        lim.processFrames(in.data(), 48000, 2);
        const float outTp = measurer.measureTruePeak(in.data(), 48000, 2);
        check(outTp <= ceilLin * 1.007f, "hot sine: output true-peak <= ceiling");
        check(outTp < inTp * 0.8f, "hot sine: strong reduction");
    }

    // (d) Never amplifies + NaN/Inf hardening.
    {
        SoftKneeLimiter lim(-1.0f, 4.0f);
        Rng rng(0xC0FFEE11);
        std::vector<float> in(4096);
        float inMax = 0.0f;
        for (int i = 0; i < 4096; ++i) {
            in[i] = rng.sym() * 1.5f;
            inMax = std::max(inMax, std::fabs(in[i]));
        }
        in[100] = std::numeric_limits<float>::quiet_NaN();
        in[101] = std::numeric_limits<float>::infinity();
        in[102] = -std::numeric_limits<float>::infinity();
        lim.processFrames(in.data(), 2048, 2);   // 4096 floats == 2048 frames
        float outMax = 0.0f;
        bool allFinite = true;
        for (float v : in) {
            if (!std::isfinite(v)) allFinite = false;
            outMax = std::max(outMax, std::fabs(v));
        }
        check(allFinite, "NaN/Inf inputs produce finite output");
        check(outMax <= inMax + 1e-6f, "limiter never amplifies");
    }

    // (e) Steady-state with the legacy test's parameters (0.8 dB, 0.1 dB
    //     knee): thresholds above 0 dBFS clamp to full scale (a ceiling above
    //     full scale is meaningless), so the sustained square lands at 1.0 —
    //     within the pre-Phase-1 contract (<= 1.12, matching test_dsp.cc).
    {
        SoftKneeLimiter lim(0.80f, 0.10f);
        check(lim.ceilingDb() == 0.0f, "ceiling above 0 dBFS clamps to 0");
        std::vector<float> sq(4096);
        for (int i = 0; i < 4096; ++i) sq[i] = (i % 2) ? -1.5f : 1.5f;
        lim.processFrames(sq.data(), 4096, 1);
        float tail = 0.0f;
        for (int i = 4096 - 480; i < 4096; ++i) tail = std::max(tail, std::fabs(sq[i]));
        check(tail <= 1.12f, "legacy params: sustained square within contract");
        check(tail > 0.9f, "sustained square actually limited (not muted)");
    }
}

// ---------------------------------------------------------------------------
// Section 5 — channel balance + mono downmix
// ---------------------------------------------------------------------------
static void test_channel_ops() {
    std::cout << "  - Phase1/channels: constant-power balance + mono" << std::endl;
    ChannelOps ops;

    // Audit #22 item 4 regression lock: a freshly-constructed instance
    // (no setBalance call) must be a BIT-EXACT no-op at center — the
    // member gains used to initialize to kInvSqrt2 (-3.01 dB) and any
    // standalone use silently attenuated. MasterChain masked it by
    // folding setBalance() into every block.
    {
        ChannelOps fresh;
        float gL = -1.0f, gR = -1.0f;
        fresh.gains(&gL, &gR);
        check(gL == 1.0f && gR == 1.0f, "ChannelOps default gains are unity");
        std::vector<float> f = {0.5f, -0.25f};
        fresh.processInterleaved(f.data(), 1);
        check(f[0] == 0.5f && f[1] == -0.25f,
              "ChannelOps default is a bit-exact no-op (stereo)");
    }

    // Constant power across the sweep for mono content (L == R == 0.7):
    // with the sqrt(2)-scaled law the OUTPUT total equals the DUAL-CHANNEL
    // input total (2*0.7^2) at EVERY balance point — zero volume dips —
    // and the CENTER is exactly unity (balance 0 is a no-op).
    const float pIn = 0.7f;
    const double dualPow = 2.0 * static_cast<double>(pIn) * pIn;
    for (float b : {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f}) {
        ops.setBalance(b);
        ops.setMonoDownmix(false);
        std::vector<float> f = {pIn, pIn};
        ops.processInterleaved(f.data(), 1);
        const double totStereo = static_cast<double>(f[0]) * f[0] + static_cast<double>(f[1]) * f[1];
        check(std::fabs(totStereo - dualPow) < 1e-6, "balance: constant power (stereo)");

        ops.setMonoDownmix(true);
        std::vector<float> g = {pIn, pIn};
        ops.processInterleaved(g.data(), 1);
        // Mono path: out = (M*gL, M*gR) with gL^2+gR^2 == 2, so the total
        // is 2*M^2 at every balance — constant across the sweep. At center
        // the per-channel output equals M (the +3 dB the (L+R)/sqrt(2)
        // convention gives correlated content; the limiter owns the peaks).
        const double m = 2.0 * static_cast<double>(pIn) * ChannelOps::kInvSqrt2;
        const double monoPow = 2.0 * m * m;
        const double totMono = static_cast<double>(g[0]) * g[0] + static_cast<double>(g[1]) * g[1];
        check(std::fabs(totMono - monoPow) < 1e-6, "balance: constant power (mono path)");
    }

    // Extremes: kept channel at +3.01 dB (sqrt2), far channel exactly 0.
    ops.setMonoDownmix(false);
    ops.setBalance(-1.0f);
    {
        std::vector<float> f = {0.5f, 0.25f};
        ops.processInterleaved(f.data(), 1);
        check(std::fabs(f[0] - 0.5f * 1.41421356f) < 1e-6, "full left: L at +3 dB");
        check(std::fabs(f[1]) < 1e-9, "full left mutes R");
        // Center after the extreme must be a bit-exact no-op.
        ops.setBalance(0.0f);
        std::vector<float> g0 = {0.5f, 0.25f};
        ops.processInterleaved(g0.data(), 1);
        check(g0[0] == 0.5f && g0[1] == 0.25f, "center balance is bit-exact unity");
    }
    ops.setBalance(1.0f);
    {
        std::vector<float> f = {0.5f, 0.25f};
        ops.processInterleaved(f.data(), 1);
        check(std::fabs(f[1] - 0.25f * 1.41421356f) < 1e-6, "full right: R at +3 dB");
        check(std::fabs(f[0]) < 1e-9, "full right mutes L");
    }

    // Directive formula at the visible extreme: balance +1, mono on:
    // outR == (L + R)/sqrt(2) exactly.
    ops.setMonoDownmix(true);
    ops.setBalance(1.0f);
    {
        std::vector<float> f = {1.0f, 0.5f};
        ops.processInterleaved(f.data(), 1);
        // gR = sqrt(2) at the extreme cancels the /sqrt(2) in M:
        // outR == L + R exactly.
        const float expect = 1.0f + 0.5f;
        check(std::fabs(f[1] - expect) < 1e-6, "mono at extreme == raw (L+R) sum");
        check(std::fabs(f[0]) < 1e-9, "mono extreme mutes the far channel");
    }
    // Phase cancellation: L = 1, R = -1 -> mono 0.
    ops.setBalance(0.0f);
    {
        std::vector<float> f = {1.0f, -1.0f};
        ops.processInterleaved(f.data(), 1);
        check(std::fabs(f[0]) < 1e-9 && std::fabs(f[1]) < 1e-9, "antiphase mono cancels");
    }
}

// ---------------------------------------------------------------------------
// Section 6 — SPSC ring buffer
// ---------------------------------------------------------------------------
static void test_ring_buffer() {
    std::cout << "  - Phase1/ring: SPSC correctness" << std::endl;

    // Single-thread roundtrip + edge cases.
    {
        mix::AudioRingBuffer rb;
        check(rb.init(2, 1024), "ring init");
        check(rb.capacityFrames() == 1024, "pow2 capacity exact");
        Rng rng(0x5eed1);
        std::vector<float> data(1024 * 2), back(512 * 2);
        for (auto& v : data) v = rng.sym();
        check(rb.write(data.data(), 512), "write 512");
        check(rb.readable() == 512 && rb.writable() == 512, "readable/writable");
        check(rb.read(back.data(), 600) == 512, "read partial capped");
        check(std::memcmp(back.data(), data.data(), 512 * 2 * sizeof(float)) == 0,
              "roundtrip bit-exact");
        // Overflow rejection leaves data intact.
        check(rb.write(data.data(), 600) == true, "fits after drain");
        check(rb.write(data.data(), 600) == false, "overflow rejected");
        check(rb.overflowEvents() == 1, "overflow counted once");
        rb.reset();
        check(rb.readable() == 0, "reset clears");
        // accumulate with gain vs scalar oracle.
        check(rb.write(data.data(), 512), "rewritable after reset");
        std::vector<float> oracle(512 * 2);
        for (int i = 0; i < 512 * 2; ++i) oracle[i] = 0.25f * data[i];
        std::vector<float> acc(512 * 2, 0.0f);
        check(rb.accumulate(acc.data(), 512, 0.25f) == 512, "accumulate consumed");
        bool accOk = true;
        for (int i = 0; i < 512 * 2; ++i) accOk = accOk && (std::fabs(acc[i] - oracle[i]) < 1e-6f);
        check(accOk, "accumulate gain matches oracle");
    }

    // Two-thread stress: 2M frames, random chunk sizes, checksum equality.
    {
        mix::AudioRingBuffer rb;
        check(rb.init(2, 4096), "stress ring init");
        constexpr int kTotal = 2'000'000;
        std::vector<float> stream(static_cast<size_t>(kTotal) * 2);
        Rng rng(0xABCD1234);
        for (auto& v : stream) v = rng.sym();

        std::atomic<bool> done{false};
        std::atomic<size_t> written{0};
        std::atomic<uint64_t> mismatches{0};

        std::thread producer([&]() {
            Rng prng(777);
            int off = 0;
            while (off < kTotal) {
                const int n = static_cast<int>(prng.next() % 97) + 1;
                const int chunk = std::min(n, kTotal - off);
                while (rb.writable() < static_cast<size_t>(chunk)) {
                    std::this_thread::yield();
                }
                if (!rb.write(stream.data() + static_cast<size_t>(off) * 2, chunk)) {
                    ++mismatches;
                }
                written.fetch_add(chunk);
                off += chunk;
            }
            done.store(true);
        });
        std::thread consumer([&]() {
            Rng crng(777);   // identical chunk sequence
            int off = 0;
            std::vector<float> buf(128 * 2);
            while (off < kTotal) {
                const int n = static_cast<int>(crng.next() % 97) + 1;
                const int chunk = std::min(n, kTotal - off);
                size_t got = 0;
                while (got < static_cast<size_t>(chunk)) {
                    const size_t r = rb.read(buf.data(), chunk - got);
                    if (r == 0) { std::this_thread::yield(); continue; }
                    if (std::memcmp(buf.data(),
                                    stream.data() + static_cast<size_t>(off + got) * 2,
                                    r * 2 * sizeof(float)) != 0) {
                        ++mismatches;
                    }
                    got += r;
                }
                off += chunk;
            }
        });
        producer.join();
        consumer.join();
        check(mismatches.load() == 0, "2M-frame stress: zero corruption");
        check(rb.overflowEvents() == 0, "2M-frame stress: zero overflows");
        check(written.load() == kTotal, "all frames transferred");
    }
}

// ---------------------------------------------------------------------------
// Section 7 — 32-peer mixing pool
// ---------------------------------------------------------------------------
static void test_peer_mix_pool() {
    std::cout << "  - Phase1/mix: 32-peer pool + saturation" << std::endl;

    // Membership.
    {
        mix::PeerMixPool pool;
        check(pool.init(48000, 2, 2048), "pool init");
        int slots[35];
        for (int i = 0; i < 32; ++i) {
            slots[i] = pool.attachPeer();
            check(slots[i] == i, "attach returns sequential slots");
        }
        check(pool.attachPeer() == -1, "33rd peer rejected");
        check(pool.activePeers() == 32, "32 active");
        pool.detachPeer(5);
        check(pool.activePeers() == 31, "detach works");
        check(pool.attachPeer() == 5, "slot reuse");
    }

    // Sum correctness vs scalar oracle (4 peers, distinct signals + gains).
    {
        mix::PeerMixPool pool;
        check(pool.init(48000, 2, 8192), "oracle pool init");
        const int frames = 2048;
        std::vector<float> sig[4];
        const float gains[4] = {1.0f, 0.5f, 0.25f, 0.75f};
        const double freqs[4] = {997.0, 617.0, 1553.0, 439.0};
        for (int p = 0; p < 4; ++p) {
            const int slot = pool.attachPeer();
            pool.setPeerGain(slot, gains[p]);
            std::vector<float> tmp;
            // 0.25/peer: worst-case aligned sum 0.25*2.5 = 0.625 < kSatKnee,
            // so this section exercises PURE accumulation (the 32-peer test
            // below covers the saturation guard).
            fillSineStereo(tmp, frames, 0.25f, freqs[p]);
            sig[p] = tmp;
            check(pool.peerWrite(slot, sig[p].data(), frames), "peer write");
        }
        std::vector<float> out(static_cast<size_t>(frames) * 2);
        mix::PeerMixPool::MixStats st;
        pool.mix(out.data(), frames, &st);
        check(st.framesMixed == static_cast<size_t>(frames), "stats frames");
        check(st.activePeers == 4 && st.underruns == 0, "stats peers/underruns");
        bool ok = true;
        double maxErr = 0.0;
        for (int i = 0; i < frames * 2; ++i) {
            double oracle = 0.0;
            for (int p = 0; p < 4; ++p) oracle += gains[p] * sig[p][i];
            const double err = std::fabs(static_cast<double>(out[i]) - oracle);
            maxErr = std::max(maxErr, err);
            ok = ok && (err < 1e-5);
        }
        check(ok, "mix == scalar oracle (max err " + std::to_string(maxErr) + ")");
    }

    // 32 in-phase full-scale peers -> sum 32.0 -> saturation guard <= 1.0.
    {
        mix::PeerMixPool pool;
        check(pool.init(48000, 2, 8192), "sat pool init");
        const int frames = 4096;
        std::vector<float> sig;
        fillSineStereo(sig, frames, 1.0f, 997.0);
        for (int i = 0; i < 32; ++i) {
            const int slot = pool.attachPeer();
            pool.setPeerGain(slot, 1.0f);
            check(pool.peerWrite(slot, sig.data(), frames), "sat write");
        }
        std::vector<float> out(static_cast<size_t>(frames) * 2);
        pool.mix(out.data(), frames, nullptr);
        float mx = 0.0f;
        bool finite = true;
        for (float v : out) {
            if (!std::isfinite(v)) finite = false;
            mx = std::max(mx, std::fabs(v));
        }
        check(finite, "saturation: output finite");
        check(mx <= 1.0000001f, "saturation: bounded by 1.0");
        check(mx > 0.99f, "saturation: actually engaged (not muted)");
        check(mix::PeerMixPool::saturate(32.0f) <= 1.0f, "saturate(32) bounded by 1");
        check(mix::PeerMixPool::saturate(0.5f) == 0.5f, "saturate identity below knee");
    }

    // Underrun accounting with one starved peer.
    {
        mix::PeerMixPool pool;
        check(pool.init(48000, 2, 8192), "underrun pool init");
        const int frames = 1024;
        const int s0 = pool.attachPeer();
        const int s1 = pool.attachPeer();
        std::vector<float> a, b;
        fillSineStereo(a, frames, 0.5f, 997.0);
        fillSineStereo(b, 300, 0.5f, 1300.0);   // starved: 300 of 1024
        pool.peerWrite(s0, a.data(), frames);
        pool.peerWrite(s1, b.data(), 300);
        std::vector<float> out(static_cast<size_t>(frames) * 2);
        mix::PeerMixPool::MixStats st;
        pool.mix(out.data(), frames, &st);
        check(st.underruns >= 1, "starved peer counted");
        check(pool.peerUnderruns(s1) >= 1, "per-peer underrun counter");
        // Tail region (beyond frame 300) must contain ONLY peer s0.
        bool tailOk = true;
        for (int i = 320; i < frames; ++i) {
            const double expect = a[static_cast<size_t>(i) * 2];
            const double gotL = out[static_cast<size_t>(i) * 2];
            const double gotR = out[static_cast<size_t>(i) * 2 + 1];
            tailOk = tailOk && std::fabs(gotL - expect) < 1e-6 && std::fabs(gotR - expect) < 1e-6;
        }
        check(tailOk, "starved tail carries the fed peer only");
    }

    // High-jitter threaded stability + exact frame conservation.
    {
        mix::PeerMixPool pool;
        check(pool.init(48000, 2, 16384), "jitter pool init");
        constexpr int kPerPeer = 96'000;    // 2 s of audio per peer
        constexpr int kMixBlock = 960;
        // Attach the four peers FIRST (mix() only consumes attached slots).
        for (int p = 0; p < 4; ++p) {
            check(pool.attachPeer() == p, "jitter: attach slot");
        }
        std::atomic<size_t> written[4];
        std::thread producers[4];
        for (int p = 0; p < 4; ++p) {
            written[p].store(0);
            producers[p] = std::thread([&, p]() {
                Rng prng(1000 + p);
                const double freq = 400.0 + 111.0 * p;
                int off = 0;
                std::vector<float> chunk(512 * 2);
                while (off < kPerPeer) {
                    const int n = std::min<int>(static_cast<int>(prng.next() % 448) + 64,
                                                kPerPeer - off);
                    for (int i = 0; i < n; ++i) {
                        const double t = static_cast<double>(off + i) / 48000.0;
                        const float v = static_cast<float>(0.5f * std::sin(2.0 * M_PI * freq * t));
                        chunk[static_cast<size_t>(i) * 2] = v;
                        chunk[static_cast<size_t>(i) * 2 + 1] = v;
                    }
                    while (pool.peerWritable(p) < static_cast<size_t>(n)) {
                        std::this_thread::yield();
                    }
                    if (pool.peerWrite(p, chunk.data(), n)) {
                        written[p].fetch_add(n);
                    }
                    off += n;
                    if ((prng.next() & 15) == 0) std::this_thread::yield();
                }
            });
        }

        std::vector<float> out(kMixBlock * 2);
        size_t mixed = 0;
        for (;;) {
            pool.mix(out.data(), kMixBlock, nullptr);
            mixed += kMixBlock;
            std::this_thread::yield();   // let producers progress under contention
            for (float v : out) {
                if (!std::isfinite(v)) { check(false, "jitter: non-finite sample"); }
                if (std::fabs(v) > 1.0000001f) { check(false, "jitter: bound violated"); }
            }
            bool producersDone = true;
            for (int p = 0; p < 4; ++p) {
                if (written[p].load() < kPerPeer) producersDone = false;
            }
            if (producersDone) {
                size_t left = 0;
                for (int p = 0; p < 4; ++p) left += pool.peerReadable(p);
                if (left == 0) break;   // rings drained: nothing in flight
            }
            if (mixed > 20 * static_cast<size_t>(kPerPeer)) {   // safety valve
                check(false, "jitter: safety valve tripped");
                break;
            }
        }
        for (auto& t : producers) t.join();
        size_t totalWritten = 0;
        for (int p = 0; p < 4; ++p) totalWritten += written[p].load();
        check(totalWritten == 4 * kPerPeer, "jitter: all frames written");
        // Conservation is structural: after the producers joined, mix() is
        // the sole consumer; the loop only exits when every ring reads empty.
        // leftAfter == 0 below therefore proves consumed == written exactly.
        // (`mixed` counts OUTPUT slots — each iteration pulls up to 960
        // frames from EACH peer, so it is not comparable to totalWritten.)
        check(mixed > 0, "jitter: mixer ran");
        size_t leftAfter = 0;
        for (int p = 0; p < 4; ++p) leftAfter += pool.peerReadable(p);
        check(leftAfter == 0, "jitter: rings fully drained at exit");
    }
}

// ---------------------------------------------------------------------------
// Section 8 — silent PLL (Gap #13)
// ---------------------------------------------------------------------------
static void test_silent_pll() {
    std::cout << "  - Phase1/silentPLL: bypass + PTP lock + takeover" << std::endl;

    // (a) PTP keeps locking while the audio path is bypassed.
    AcousticPhaseResampler resampler;
    resampler.setTargetDriftPpm(-250.0);
    resampler.SetSilentBypass(true);           // directive-named alias
    check(resampler.silentBypass(), "bypass flag visible");

    streamify::PtpEngine& ptp = streamify::PtpEngine::getInstance();
    ptp.reset();
    const int64_t trueOffset = 5'000'000;     // +5 ms master-ahead
    Rng rng(0x5113AB);
    // Anchor the synthetic epoch to the REAL host RAW clock so the
    // readout extrapolation (now - tRef) stays in a sane numeric range
    // (same rationale as the phase-lock suite's sim).
    int64_t t0 = streamify::nowRawNanos() + 2'000'000;
    for (int i = 0; i < 40; ++i) {
        const int64_t rtt = 1'800'000 + static_cast<int64_t>(std::fabs(rng.gauss()) * 900'000.0);
        const int64_t dUp = rtt / 2 + static_cast<int64_t>(rng.gauss() * 120'000.0);
        const int64_t t1 = t0 + trueOffset + dUp;
        const int64_t t2 = t1 + 120'000;
        const int64_t t3 = t0 + rtt;
        ptp.processTimestamps(t0, t1, t2, t3);
        t0 += 250'000'000ll;                   // 4 Hz cadence
    }
    check(ptp.isLocked(), "PTP locked during bypass");
    check(std::llabs(ptp.getClockOffsetNanos() - trueOffset) < 200'000,
          "PTP offset within 200 us while audio is bypassed (got " +
              std::to_string(ptp.getClockOffsetNanos() - trueOffset) + " ns, sigma " +
              std::to_string(static_cast<long long>(ptp.getOffsetSigmaNanos())) + ")");

    // (b) Bypass passthrough is bit-exact; the drift keeps tracking.
    std::vector<float> in, out;
    fillSineStereo(in, 4800, 0.5f, 997.0);
    out = in;
    const int64_t consumedBefore = resampler.framesConsumed();
    // tau = 250 ms = 12,000 frames; 30 x 2400 frames = 72,000 == 6 tau
    // -> 99.75% converged, comfortably inside the +-5 PPM window.
    for (int b = 0; b < 30; ++b) {
        const int r = resampler.process(in.data(), 2400, out.data(), 2400);
        check(r == 2400, "bypass returns passthrough count");
    }
    check(std::memcmp(out.data(), in.data(), out.size() * sizeof(float)) == 0,
          "bypass output is bit-exact passthrough");
    check(resampler.framesConsumed() == consumedBefore,
          "bypass does not advance stream accounting");
    check(resampler.currentDriftPpm() > -255.0 && resampler.currentDriftPpm() < -245.0,
          "drift slews to command during bypass");

    // (c) Instant takeover: un-bypass produces fresh resampled audio with
    // no stale replay (stream restarted at the live position).
    resampler.SetSilentBypass(false);
    fillSineStereo(in, 960, 0.5f, 997.0);
    out.assign(960 * 2, 0.0f);
    const int r = resampler.process(in.data(), 960, out.data(), 1000);
    check(r >= 930 && r <= 960, "takeover produces frames at ~lock rate");
    check(resampler.framesConsumed() == 960, "takeover stream is fresh");
    bool finite = true;
    for (float v : out) finite = finite && std::isfinite(v);
    check(finite, "takeover output finite");
}

// ---------------------------------------------------------------------------
// Section 9 — MasterChain end-to-end + silent bypass
// ---------------------------------------------------------------------------
static void test_master_chain() {
    std::cout << "  - Phase1/chain: end-to-end -14 LUFS @ -1 dBTP" << std::endl;
    MasterChain chain;
    chain.configure(48000, 2);
    chain.setLufsTarget(-14.0f);
    chain.setLimiterCeiling(-1.0f);

    // "Music": 997 Hz + 3.1 kHz + noise, around -8 LUFS.
    const int total = 48000 * 10;
    std::vector<float> in(static_cast<size_t>(total) * 2);
    Rng rng(0xE7);
    for (int i = 0; i < total; ++i) {
        const double t = static_cast<double>(i) / 48000.0;
        const double v = 0.126 * std::sin(2.0 * M_PI * 997.0 * t) +
                         0.04 * std::sin(2.0 * M_PI * 3100.0 * t + 0.3) +
                         0.008 * rng.sym();
        in[static_cast<size_t>(i) * 2] = static_cast<float>(v);
        in[static_cast<size_t>(i) * 2 + 1] = static_cast<float>(v * 0.95);
    }
    // NOTE: processStream applies its glide gain IN PLACE — measure the
    // input on a dedicated meter and snapshot the pristine copy FIRST.
    std::vector<float> work = in;                  // pristine copy, before any meter runs
    LufsNormalizer inMeter;
    feedInBlocks(inMeter, in, total, 960);          // (may mutate `in`; `work` is safe)
    const float inLufs = inMeter.integratedLufs();
    check(inLufs > -20.0f && inLufs < -5.0f, "input is a loud-ish track");

    for (int fed = 0; fed < total; fed += 960) {
        chain.process(work.data() + static_cast<size_t>(fed) * 2, 960);
    }
    const float wantDb = -14.0f - chain.integratedLufs();
    check(std::fabs(chain.appliedGainDb() - wantDb) < 0.05f, "chain setpoint converged");
    check(std::fabs(chain.appliedGainDb() - (-14.0f - inLufs)) < 0.5f,
          "gain == target - input");

    // True-peak BEFORE the out-meter mutates the buffer in place.
    SoftKneeLimiter measurer;
    const float outTp = measurer.measureTruePeak(work.data(), total, 2);
    check(outTp <= std::pow(10.0f, -1.0f / 20.0f) * 1.007f, "output true-peak <= -1 dBTP");
    bool finite = true;
    for (float v : work) finite = finite && std::isfinite(v);
    check(finite, "chain output finite");

    LufsNormalizer outMeter;
    feedInBlocks(outMeter, work, total, 960);      // (mutates `work` in place)
    check(std::fabs(outMeter.integratedLufs() - -14.0f) < 0.7f, "output lands on -14 LUFS");

    // Silent bypass: untouched buffer, no measurement movement.
    std::vector<float> before = work;
    const float integratedBefore = chain.integratedLufs();
    chain.setSilentBypass(true);
    chain.process(work.data(), 4800);
    check(std::memcmp(work.data(), before.data(), before.size() * sizeof(float)) == 0,
          "silent bypass leaves the buffer bit-identical");
    check(chain.integratedLufs() == integratedBefore, "silent bypass performs no measurement");
    chain.setSilentBypass(false);
    chain.process(work.data(), 4800);
    bool changed = false;
    for (size_t i = 0; i < work.size(); ++i) changed = changed || (work[i] != before[i]);
    check(changed, "un-bypassed chain processes again");
}

// ---------------------------------------------------------------------------
// Section 10 — hot-path zero-allocation audit
// ---------------------------------------------------------------------------
static void test_zero_allocation() {
    std::cout << "  - Phase1/perf: zero-alloc hot paths" << std::endl;

    LufsNormalizer lufs;
    lufs.configure(48000, 2);
    std::vector<float> buf(960 * 2);
    Rng rng(0x2A2A);
    for (auto& v : buf) v = rng.sym() * 0.5f;
    { AllocGuard g; lufs.processStream(buf.data(), 960); check(g.count() == 0, "LufsNormalizer"); }

    SoftKneeLimiter lim;
    { AllocGuard g; lim.processFrames(buf.data(), 960, 2); check(g.count() == 0, "SoftKneeLimiter"); }

    ChannelOps ch;
    { AllocGuard g; ch.processInterleaved(buf.data(), 960); check(g.count() == 0, "ChannelOps"); }

    MasterChain chain;
    chain.configure(48000, 2);
    { AllocGuard g; chain.process(buf.data(), 960); check(g.count() == 0, "MasterChain"); }

    mix::PeerMixPool pool;
    pool.init(48000, 2, 4096);
    for (int i = 0; i < 4; ++i) {
        const int slot = pool.attachPeer();
        pool.setPeerGain(slot, 0.5f);
        pool.peerWrite(slot, buf.data(), 960);
    }
    std::vector<float> out(960 * 2);
    { AllocGuard g; pool.mix(out.data(), 960, nullptr); check(g.count() == 0, "PeerMixPool"); }

    { AllocGuard g; AcousticPhaseResampler r; (void)g; }   // ctor is control-path
    std::cout << "  - Phase1/perf: zero-alloc audit PASSED" << std::endl;
}


// ---------------------------------------------------------------------------
// Section 9.5 — rate-aware legacy surface (audit #22 item 9)
// ---------------------------------------------------------------------------
// The legacy one-shot path measured EVERYTHING at a hardcoded 48 kHz: the
// meter inside computeIntegratedLufs, the processFloats convenience path,
// and (via the JNI bridge) every float-DSP singleton. 44.1 kHz content —
// the most common Android output rate — ran through K-filters designed
// for the wrong rate and a mis-paced 100 ms block ladder. These tests
// lock the rate-aware behavior.
static void test_rate_configuration() {
    std::cout << "  - Phase1/rate: legacy paths honor the configured fs" << std::endl;

    const float amp = std::sqrt(2.0f) * std::pow(10.0f, -23.0f / 20.0f);

    // 997 Hz sine generated natively at fs (fillSineStereo fixes its time
    // base at 48 kHz, so generate inline for other rates).
    auto fillSineAt = [](std::vector<float>& buf, int frames, double fs,
                         float a, bool stereo) {
        buf.assign(static_cast<size_t>(frames) * (stereo ? 2 : 1), 0.0f);
        for (int i = 0; i < frames; ++i) {
            const double t = static_cast<double>(i) / fs;
            const float v = static_cast<float>(a * std::sin(2.0 * M_PI * 997.0 * t));
            if (stereo) {
                buf[static_cast<size_t>(i) * 2] = v;
                buf[static_cast<size_t>(i) * 2 + 1] = v;
            } else {
                buf[static_cast<size_t>(i)] = v;
            }
        }
    };

    // (a) Streaming anchor at 44.1 kHz: stereo -23 dBFS sine -> -20 LUFS.
    {
        LufsNormalizer n;
        n.configure(44100, 2);
        std::vector<float> data;
        fillSineAt(data, 44100 * 12, 44100.0, amp, true);
        feedInBlocks(n, data, 44100 * 12, 441);
        check(std::fabs(n.integratedLufs() - -20.0f) < 0.15f,
              "stereo -23 dBFS sine @44.1k -> -20 LUFS");
    }
    // (b) Streaming mono anchor at 44.1 kHz -> -23 LUFS.
    {
        LufsNormalizer n;
        n.configure(44100, 1);
        std::vector<float> mono;
        fillSineAt(mono, 44100 * 12, 44100.0, amp, false);
        int fed = 0;
        while (fed < 44100 * 12) {
            const int n2 = std::min(441, 44100 * 12 - fed);
            n.processStream(mono.data() + fed, n2);
            fed += n2;
        }
        check(std::fabs(n.integratedLufs() - -23.0f) < 0.15f,
              "mono -23 dBFS sine @44.1k -> -23 LUFS");
    }
    // (c) The one-shot surface takes the rate: explicit 44.1 kHz on the
    //     mono anchor hits -23 LUFS, while the legacy 48 kHz default on
    //     the same bytes measurably disagrees.
    {
        std::vector<float> mono;
        fillSineAt(mono, 44100 * 12, 44100.0, amp, false);
        const float* chans[1] = {mono.data()};
        LufsNormalizer n;
        const float one44 = n.computeIntegratedLufs(chans, 1, mono.size(), 44100);
        check(std::fabs(one44 - -23.0f) < 0.15f,
              "one-shot mono @44.1k (rate param) -> -23 LUFS");
        const float one48 = n.computeIntegratedLufs(chans, 1, mono.size());
        check(std::fabs(one44 - one48) > 0.005f,
              "rate parameter changes the one-shot measurement");
    }
    // (d) processFloats threads the INSTANCE rate: two copies of the same
    //     broadband buffer, instances configured at 44.1k vs 48k. The
    //     applied gains must differ (the rate flows), and the 44.1k gain
    //     must match the explicit-44.1k one-shot derivation. Broadband
    //     noise integrates the K-filter differences across the spectrum,
    //     where the 48k-vs-44.1k designs clearly diverge.
    {
        std::vector<float> pristine;
        Rng rng(0x9E37);
        pristine.assign(static_cast<size_t>(44100 * 6), 0.0f);
        for (auto& v : pristine) v = rng.sym() * 0.05f;

        std::vector<float> a = pristine, b = pristine;
        LufsNormalizer n44;
        n44.configure(44100, 1);
        LufsNormalizer n48;
        n48.configure(48000, 1);
        // Target -20 (not -14): this noise sits near -30 LUFS, so -14
        // would clamp BOTH rates to the same +12 dB ceiling and mask the
        // rate difference entirely.
        n44.processFloats(a.data(), static_cast<int>(a.size()), -20.0f);
        n48.processFloats(b.data(), static_cast<int>(b.size()), -20.0f);

        // Average output/input ratio over spread points; the soft guard
        // is identity here (|gain * 0.05| stays far below 0.99).
        double ra = 0.0, rb = 0.0;
        int m = 0;
        for (size_t i = 3; i < pristine.size(); i += 97) {
            if (std::fabs(pristine[i]) < 1e-4f) continue;
            ra += static_cast<double>(a[i]) / pristine[i];
            rb += static_cast<double>(b[i]) / pristine[i];
            ++m;
        }
        check(m > 100, "ratio sampling points");
        const double gain44 = ra / m;
        const double gain48 = rb / m;
        check(std::fabs(gain44 - gain48) > 1e-4,
              "instance rate flows into processFloats gain");
        const float* chans[1] = {pristine.data()};
        LufsNormalizer probe;
        const float lufs44 =
            probe.computeIntegratedLufs(chans, 1, pristine.size(), 44100);
        const double want = probe.calculateNormalizationGain(lufs44, -20.0f);
        check(std::fabs(gain44 - static_cast<double>(want)) < 1e-3,
              "processFloats gain == explicit-44.1k derivation");
    }
    std::cout << "  - Phase1/rate: rate-aware legacy surface PASSED" << std::endl;
}

// ---------------------------------------------------------------------------
// Section 9.6 — [PERF] audio-RT hot-path micro-benchmarks (audit #22 item 9 /
// #23 B3 measurement gate)
// ---------------------------------------------------------------------------
// The standing measurement harness for the limiter/saturate hot paths —
// the gate infrastructure B3's "audio-thread us per 10ms buffer" budget
// and any future pow/tanh change (precompute, approximation, NEON lane)
// must be measured against. Wall-clock NUMBERS ONLY (no timing asserts —
// host-flaky per item 10's own triage): best-of-5 rounds, 200 iters each,
// ns/frame + the phase_lock benchmark's 1.4 GHz little-core A55 estimate.
//
// Baseline (libm paths, this host, x86-64 glibc — see issue #23 for the
// full table and the item-9c swap attempt it blocked):
//   SoftKneeLimiter 2048f stereo hot   ~123 ns/frame  (plain -O3)
//   PeerMixPool::saturate 4096 hot     ~7 ns/sample   (std::tanh)
//   std::tanh / std::pow(10,*) refs    ~5 ns/call each
static double g_benchSink = 0.0;   // defeats dead-code elimination

static void benchPrint(const char* label, double ns, int frames) {
    const double nsPerFrame = ns / frames;
    const double cpuA55 = nsPerFrame * 48000.0 / 1.4e9 * 100.0;
    std::printf("    %-34s %8.1f ns/block  %6.2f ns/frame  (~%.2f%% of an A55 core, est.)\n",
                label, ns, nsPerFrame, cpuA55);
}

static void benchmarkLimiterHotPath() {
    std::cout << "  - Phase1/perf: limiter hot-path benchmark" << std::endl;
    // +6 dBFS 997 Hz stereo: the true-peak sidechain engages curveLin (tanh)
    // every frame, the ballistics drive the per-frame pow10, and satStage
    // guards the residual — the worst-case libm load of the shipped chain.
    const int frames = 2048;
    SoftKneeLimiter lim(-1.0f, 4.0f);
    std::vector<float> buf;
    double best = 1e30;
    for (int round = 0; round < 5; ++round) {
        fillSineStereo(buf, frames, 2.0f, 997.0);
        for (int i = 0; i < 20; ++i)   // warm-up to steady-state limiting
            lim.processFrames(buf.data(), frames, 2);
        fillSineStereo(buf, frames, 2.0f, 997.0);
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < 200; ++i)
            lim.processFrames(buf.data(), frames, 2);
        const auto t1 = std::chrono::steady_clock::now();
        const double ns =
            std::chrono::duration<double, std::nano>(t1 - t0).count() / 200;
        best = std::min(best, ns);
    }
    benchPrint("SoftKneeLimiter 2048f stereo hot", best, frames);
}

static void benchmarkMixSaturate() {
    // PeerMixPool::saturate on above-knee material (the 32-peer worst case:
    // in-phase full-scale contributions push every summed sample past the
    // knee into tanh).
    const int n = 4096;
    std::vector<float> buf(n);
    for (int i = 0; i < n; ++i)
        buf[i] = 0.95f + 0.05f * std::sin(static_cast<double>(i));
    double best = 1e30;
    double sink = 0.0;
    for (int round = 0; round < 5; ++round) {
        const auto t0 = std::chrono::steady_clock::now();
        double acc = 0.0;
        for (int i = 0; i < 200; ++i)
            for (int j = 0; j < n; ++j)
                acc += mix::PeerMixPool::saturate(buf[j]);
        const auto t1 = std::chrono::steady_clock::now();
        sink = acc;
        best = std::min(best,
                        std::chrono::duration<double, std::nano>(t1 - t0).count() / 200);
    }
    g_benchSink += sink;
    benchPrint("PeerMixPool::saturate 4096 hot", best, n);
}

static void benchmarkLibmScale() {
    // Raw per-call cost reference for the two libm calls under test (same
    // argument distributions the audio paths produce).
    const int n = 4096;
    std::vector<float> args(n), dbs(n);
    for (int i = 0; i < n; ++i) {
        args[i] = 9.0f * static_cast<float>(i) / static_cast<float>(n);
        dbs[i] = -24.0f + 24.0f * static_cast<float>(i) / static_cast<float>(n);
    }
    double bestTanh = 1e30, bestPow = 1e30;
    double sink = 0.0;
    for (int round = 0; round < 5; ++round) {
        auto t0 = std::chrono::steady_clock::now();
        double acc = 0.0;
        for (int i = 0; i < 200; ++i)
            for (int j = 0; j < n; ++j) acc += std::tanh(args[j]);
        auto t1 = std::chrono::steady_clock::now();
        sink = acc;
        bestTanh = std::min(bestTanh,
                            std::chrono::duration<double, std::nano>(t1 - t0).count() / 200);

        t0 = std::chrono::steady_clock::now();
        acc = 0.0;
        for (int i = 0; i < 200; ++i)
            for (int j = 0; j < n; ++j) acc += std::pow(10.0f, dbs[j] * 0.05f);
        t1 = std::chrono::steady_clock::now();
        sink += acc;
        bestPow = std::min(bestPow,
                           std::chrono::duration<double, std::nano>(t1 - t0).count() / 200);
    }
    g_benchSink += sink;
    benchPrint("std::tanh x4096 (arg 0..9)", bestTanh, n);
    benchPrint("std::pow(10,x*0.05) x4096", bestPow, n);
}

static void test_pow_tanh_benchmarks() {
    benchmarkLimiterHotPath();
    benchmarkMixSaturate();
    benchmarkLibmScale();
    // The sink must stay finite and huge — proves the loops above did real
    // work (and nothing got dead-code-eliminated).
    check(std::isfinite(g_benchSink) && g_benchSink > 1e6,
          "benchmark sinks did real work");
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------
int run_dsp_phase1_tests() {
    std::cout << "[TEST] Phase 1: Native DSP, Audiophile Fidelity & 32-Peer Jam"
              << std::endl;
    test_lufs_calibration();
    test_lufs_gating();
    test_normalization_convergence();
    test_limiter();
    test_channel_ops();
    test_ring_buffer();
    test_peer_mix_pool();
    test_silent_pll();
    test_master_chain();
    test_rate_configuration();
    test_pow_tanh_benchmarks();
    test_zero_allocation();
    std::cout << "[TEST] Phase 1: all " << g_passed << " checks passed" << std::endl;
    return 0;
}
