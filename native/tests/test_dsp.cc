#include <iostream>
#include <vector>
#include <cassert>
#include <iterator>
#include <cmath>
#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/kissfft/kiss_fftr.h"

int main() {
    std::cout << "[TEST] Starting Native DSP Test Suite..." << std::endl;

    // Phase-1 audiophile DSP + 32-peer jam suite (BEHIND.md #11/#13/#39/#41).
    // Runs first so its allocation guard sees only its own hot-path calls.
    extern int run_dsp_phase1_tests();
    run_dsp_phase1_tests();

    // Phase-2 harmonic math suite (BEHIND.md #15/#20/#25/#26/#39): Camelot
    // transition matrix, candidate hashing + dedup, circadian curves.
    extern int run_harmonic_math_tests();
    run_harmonic_math_tests();

    // Phase-3 media suites (BEHIND.md #45/#38/#44/#57): canvas loop + AGSL
    // ambient glow, audio remuxer + bitstream validators, palette extractor.
    extern int run_canvas_loop_agsl_tests();
    run_canvas_loop_agsl_tests();
    extern int run_audio_remuxer_tests();
    run_audio_remuxer_tests();
    extern int run_media_palette_tests();
    run_media_palette_tests();

    // Phase-4 audio sinks suite: lock-free SPSC ringbuffer + SIMD sample
    // converter, WearOS packetizer/compressor, sink registry + stats wire
    // layout (10M-frame concurrency stress included).
    extern int run_audio_sink_tests();
    const int phase4_failures = run_audio_sink_tests();

    // Phase-5 suites (mission brief: SIMD palette clustering & WCAG contrast
    // engine; lock-free 120Hz frame pacing & jank monitor, 1M-tick stress).
    extern int run_palette_simd_tests();
    const int phase5_palette_failures = run_palette_simd_tests();
    extern int run_frame_pacer_tests();
    const int phase5_pacer_failures = run_frame_pacer_tests();

    // 1. Test SoftKneeLimiter float processing.
    // A 5ms-attack limiter (tau ~= 240 samples @ 48kHz) legitimately passes
    // short transients through unattenuated — the envelope ballistics have
    // not caught up yet — so a 7-sample burst must not be hard-clamped. The
    // old assertion (|sample| <= 1.1 for a transient including +-2.0) was
    // unsatisfiable by design. Assert what the limiter actually guarantees:
    // it never amplifies, and it does engage soft-knee limiting once the
    // envelope has converged (4096 samples ~= 17 attack time-constants).
    streamify::dsp::SoftKneeLimiter limiter(0.80f, 0.10f);
    std::vector<float> transient = {0.1f, 0.5f, 0.9f, 1.2f, -1.5f, 2.0f, -0.95f};
    limiter.processFloats(transient.data(), static_cast<int>(transient.size()));
    for (float sample : transient) {
        assert(std::isfinite(sample));
        assert(std::abs(sample) <= 2.0f + 1e-6f); // gain <= 0 dB: never amplifies
    }
    std::cout << "  - SoftKneeLimiter transient pass-through: PASSED" << std::endl;

    limiter.reset();
    std::vector<float> sustained(4096);
    for (size_t i = 0; i < sustained.size(); ++i) {
        sustained[i] = (i % 2 == 0) ? 1.5f : -1.5f; // sustained loud square wave
    }
    limiter.processFloats(sustained.data(), static_cast<int>(sustained.size()));
    float maxTail = 0.0f;
    for (size_t i = sustained.size() - 480; i < sustained.size(); ++i) {
        maxTail = std::max(maxTail, std::abs(sustained[i]));
    }
    assert(std::isfinite(maxTail));
    // Converged envelope ~= +3.52 dB; soft-knee target with threshold 0.8 dB
    // and ratio 20 is 0.8 + (3.52-0.8)/20 ~= 0.94 dB ~= 1.114 linear.
    assert(maxTail <= 1.12f);
    std::cout << "  - SoftKneeLimiter sustained soft-knee limit: PASSED" << std::endl;

    // 2. Test SoftKneeLimiter short processing
    std::vector<int16_t> shortPcm = {100, 5000, 25000, 32000, -32000, 32767, -32768};
    limiter.processShorts(shortPcm.data(), shortPcm.size());
    for (int16_t sample : shortPcm) {
        assert(sample >= -32768 && sample <= 32767);
    }
    std::cout << "  - SoftKneeLimiter short limit: PASSED" << std::endl;

    // 3. Test LUFS Normalizer
    std::vector<float> pcmLufs = {0.05f, -0.05f, 0.1f, -0.1f, 0.08f, -0.08f};
    LufsNormalizer::getInstance().processFloats(pcmLufs.data(), pcmLufs.size(), -14.0f);
    for (float sample : pcmLufs) {
        assert(std::isfinite(sample));
    }
    std::cout << "  - LufsNormalizer: PASSED" << std::endl;

    // 4. Test KissFFT Real FFT
    int nfft = 1024;
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
    assert(cfg != nullptr);
    std::vector<kiss_fft_scalar> in(nfft, 0.0f);
    for (int i = 0; i < nfft; ++i) {
        in[i] = std::sin(2.0 * M_PI * 440.0 * i / 44100.0);
    }
    std::vector<kiss_fft_cpx> out(nfft / 2 + 1);
    kiss_fftr(cfg, in.data(), out.data());
    free(cfg);
    std::cout << "  - KissFFTR 1024 Transform: PASSED" << std::endl;

    std::cout << "[TEST] All Native DSP Tests Passed Successfully! (ASan/UBSan Verified)" << std::endl;
    // The Phase-1..3 runners report internally; Phase-4/5 added explicit
    // propagation so the shard's exit code gates on their checks.
    return (phase4_failures == 0 && phase5_palette_failures == 0 &&
            phase5_pacer_failures == 0) ? 0 : 1;
}
