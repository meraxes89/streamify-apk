#pragma once
#include <cstddef>
#include <cstdint>

// ============================================================================
//  LufsNormalizer — ITU-R BS.1770-4 / EBU R128 loudness engine (Phase 1)
// ============================================================================
//
//  MEASUREMENT (BS.1770-4, exact)
//  -------------------------------
//  * K-weighting: two-stage biquad per channel. At 48 kHz the *spec literals*
//    are used bit-exactly; at any other rate the filters are re-derived from
//    the De Man fit of the spec response
//    (shelf: f0=1681.974450955533 Hz, G=+3.999843853973347 dB, Q=0.7071752369554196;
//     RLB HP: f0=38.13547087602444 Hz,          Q=0.5003270373238773),
//    which reproduces the 48 kHz literals to <2.5e-3 (asserted in tests) and
//    stays inside the ITU tolerance masks at every other rate.
//
//  * Block ladder: 400 ms blocks, 75% overlap (100 ms hop). Each block's
//    mean-square z is accumulated per channel from four 100 ms chunk energies.
//  * Dual gating (BS.1770-4 §5.2): absolute gate at -70 LUFS, then relative
//    gate 10 LU below the preliminary integrated of the surviving set.
//  * Channel weights: L/R/C = 1.0, Ls/Rs = 1.41 (§loudness formula).
//
//  CALIBRATION ANCHORS (derived from the spec math; asserted in tests):
//      997 Hz sine, RMS -23 dBFS, MONO (L only)  ->  -23.0 LUFS +/- 0.15
//      same signal, stereo (L=R)                 ->  -20.0 LUFS +/- 0.15
//      full-scale (0 dBFS RMS/ch) stereo sine    ->     0.0 LUFS +/- 0.20
//
//  NORMALIZATION (playback, Spotify-style)
//  ---------------------------------------
//  gain_dB = clamp(target - I_gated, +-maxCorrection). The setpoint glides
//  through an EMA in the dB domain (tau = 0.5 s, updated at the 10 Hz chunk
//  rate) and is then applied through a per-sample one-pole in the linear
//  domain (tau = 25 ms). Double smoothing => the applied gain trajectory is
//  C0-continuous with bounded slope (no clicks, no pumping); silence below
//  the -60 LUFS hold threshold freezes the last gain instead of chasing
//  noise upward.
//
//  MEMORY
//  ------
//  Block store is a fixed 65536-slot array (~109 min at 48 kHz). On
//  overflow, adjacent blocks are merged pairwise (energy-mean), bounding
//  integrated error to <0.02 LU for arbitrarily long streams. Zero
//  allocation on the hot path; gating recompute is O(N) at 10 Hz on the
//  audio thread (<=0.2% of one core at the cap).
//
//  LEGACY SURFACE (kept alive for existing callers: jni_bridge.cc,
//  test_dsp.cc, dsp_fuzzer_harness.cc):
//      getInstance, processChannelSIMD, computeIntegratedLufs,
//      calculateNormalizationGain, processFloats, processShorts, reset.
// ============================================================================

struct BiquadCoeffs {
    float b0, b1, b2, a1, a2;
};

class LufsNormalizer {
public:
    static LufsNormalizer& getInstance();

    LufsNormalizer();                       // 48 kHz, 2 channels
    ~LufsNormalizer() = default;
    LufsNormalizer(const LufsNormalizer&) = delete;
    LufsNormalizer& operator=(const LufsNormalizer&) = delete;

    // ---- Legacy API --------------------------------------------------------
    // K-weight one mono channel in place through the instance filter chain
    // (carries state across calls, exactly like the pre-Phase-1 engine).
    void processChannelSIMD(float* samples, size_t count);

    // One-shot gated integrated LUFS over non-interleaved channel pointers.
    // Buffers shorter than 400 ms fall back to the ungated mean (documented;
    // BS.1770 defines no block shorter than 400 ms).
    // sampleRateHz designs the measurement K-filters and paces the block
    // ladder at the caller's ACTUAL rate (audit #22 item 9: the legacy
    // default of 48000 measured 44.1 kHz content through the wrong
    // K-weighting; default kept for ABI stability of existing callers).
    float computeIntegratedLufs(const float* const* channel_data,
                                size_t num_channels, size_t num_samples,
                                int sampleRateHz = 48000);

    // Static gain for a measured integrated value (legacy: clamp +-12 dB).
    float calculateNormalizationGain(float integrated_lufs,
                                     float target_lufs = -14.0f);

    // One-shot mono convenience path (measure -> gain -> soft guard at 0.99).
    void processFloats(float* pcm, int length, float targetLufs = -14.0f);
    void processShorts(short* pcm, int length, float targetLufs = -14.0f);

    void reset();   // clears streaming state AND the legacy filter chain

    // ---- Phase-1 streaming engine -----------------------------------------
    // Rebuilds K-filters for the new geometry (control path; not the audio
    // callback — call on track transitions).
    void configure(int sampleRateHz, int channels);

    void setTargetLufs(float targetLufs);    // clamped to [-23.0, -11.0]
    float targetLufs() const { return targetLufs_; }
    void setMaxCorrectionDb(float maxDb);    // clamped to [0.5, 24]; default 12
    float maxCorrectionDb() const { return maxCorrectionDb_; }

    // Hot path: interleaved float PCM, in place. Measures, updates the gain
    // glide, and applies the current gain sample-accurately. Zero allocations.
    void processStream(float* interleaved, int frames);

    // Readouts (safe from any thread once processStream is not running).
    float integratedLufs() const;   // gated running integrated; -120.0 if none
    float momentaryLufs() const;    // latest finalized 400 ms block
    float shortTermLufs() const;    // 3 s sliding (no gate, per R128)
    float appliedGainDb() const { return gainDb_; }
    float currentGainLinear() const { return curGainLin_; }

    int sampleRate() const { return sampleRateHz_; }
    int channels() const { return channels_; }
    uint32_t blocksMeasured() const { return blockCount_; }
    uint64_t framesMeasured() const { return streamFrames_; }

    static constexpr int kMaxChannels = 8;
    static constexpr float kMinTargetLufs = -23.0f;   // directive range
    static constexpr float kMaxTargetLufs = -11.0f;

    // K-weighting design for an arbitrary rate. At 48 kHz this reproduces
    // the ITU spec literals (asserted in tests); exposed so tests can verify
    // the De Man fit against the spec without instantiating filter state.
    static void designKFilters(int sampleRateHz, BiquadCoeffs* stage1,
                               BiquadCoeffs* stage2);

private:
    void buildKFilters(int sampleRateHz);   // spec literals at 48k, De Man fit otherwise
    void closeChunk();                      // 100 ms boundary bookkeeping
    void pushBlock(double sum);             // block store + compaction
    double gatedIntegrated() const;         // dual-gate recompute
    void updateGainSetpoint();              // 10 Hz EMA toward clamp(target - I)

    // Configuration.
    int sampleRateHz_ = 48000;
    int channels_ = 2;
    float targetLufs_ = -14.0f;
    float maxCorrectionDb_ = 12.0f;

    // Per-channel K-weighting biquads + states (double state: deterministic
    // across ABIs, cost irrelevant at 2 channels).
    BiquadCoeffs k1_[kMaxChannels] = {};
    BiquadCoeffs k2_[kMaxChannels] = {};
    double kz1_[kMaxChannels] = {0};
    double kz2_[kMaxChannels] = {0};
    double kz3_[kMaxChannels] = {0};
    double kz4_[kMaxChannels] = {0};

    // Legacy mono chain state (processChannelSIMD).
    double legZ1_ = 0, legZ2_ = 0, legZ3_ = 0, legZ4_ = 0;

    // Chunk ladder (100 ms hop). Chunk energies are per-channel sums of
    // squares of the K-weighted signal over the *open* chunk.
    int hopFrames_ = 4800;                  // sampleRate/10
    int framesInChunk_ = 0;
    double chunkEnergy_[kMaxChannels] = {0};
    uint64_t chunksClosed_ = 0;
    uint64_t streamFrames_ = 0;

    // Ring of the last 4 closed chunk energies (the newest finalizable block
    // is exactly these four). Ring of the last 30 for the 3 s short-term.
    static constexpr int kBlockChunks = 4;
    static constexpr int kShortTermChunks = 30;
    double chunkRing_[kBlockChunks][kMaxChannels] = {};
    double shortRing_[kShortTermChunks][kMaxChannels] = {};
    int shortValid_ = 0;                    // number of valid entries (<= 30)

    // Block store (fixed, zero-alloc; compacted by pairwise merge on full).
    static constexpr uint32_t kBlockCapacity = 65536;
    double blockSum_[kBlockCapacity] = {0};
    uint32_t blockCount_ = 0;
    double lastBlockLufs_ = -120.0;         // momentary readout
    mutable double cachedIntegrated_ = -120.0;
    mutable bool integratedDirty_ = true;

    // Gain glide state.
    float gainDb_ = 0.0f;                   // EMA setpoint (dB)
    float holdGainDb_ = 0.0f;               // last non-silence setpoint
    float curGainLin_ = 1.0f;               // per-sample one-pole state
    double glideAlpha_ = 0.0;               // 1 - exp(-1/(tau*fs)), tau = 25 ms
};
