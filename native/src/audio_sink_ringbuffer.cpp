// ============================================================================
//  audio_sink_ringbuffer.cpp — Phase-4 lock-free SPSC audio sink
//  (native/src/audio_sink_ringbuffer.cpp)
// ============================================================================
//
//  Implementation notes
//  --------------------
//  * Storage comes from streamify::alignedAlloc (posix_memalign, Bionic
//    minSdk-26 mandate from util/NeonCompat.h) and is 64-byte sized up so
//    vectorized tails never touch the next page.
//  * All hot paths (WriteFloat / ReadFrames / ReadBytes / converter /
//    downmix / decimator) are allocation-free and lock-free; the unit tests
//    prove this with the shared tests/AllocGuard.h audit.
//  * The vectorized converter keeps a per-path rounding contract: A64 NEON
//    and SSE2 round to nearest (vcvtnq / cvtps), armv7 NEON biases by
//    +-0.5 before the truncating vcvtq (round-half-away), the scalar tail
//    uses lrintf (nearest). Tests accept a +-1 LSB delta between paths.
// ============================================================================

#include "../include/audio_sink_ringbuffer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "../util/NeonCompat.h"

#if defined(__SSE2__)
#include <emmintrin.h>
#endif

namespace streamify {
namespace sink {

// ============================================================================
//  Scalar reference + SIMD Float32 -> PCM16
// ============================================================================

int16_t ConvertSampleF32ToI16(float sample) {
    // Bit-pattern finite check (NOT std::isfinite): the production .so is
    // built with -ffast-math, which folds isfinite() to true and would
    // smuggle NaN/Inf into the quantizer. Integer bit inspection cannot be
    // folded, so the "non-finite -> digital silence" contract holds under
    // every floating-point mode.
    uint32_t bits = 0;
    std::memcpy(&bits, &sample, sizeof(bits));
    if ((bits & 0x7F800000u) == 0x7F800000u) {
        return 0;  // NaN / +-Inf -> digital silence (documented contract)
    }
    if (sample > 1.0f) sample = 1.0f;
    else if (sample < -1.0f) sample = -1.0f;
    return static_cast<int16_t>(std::lrintf(sample * 32767.0f));
}

#if STREAMIFY_HAVE_NEON

#if defined(__aarch64__) || defined(_M_ARM64)
// arm64: native round-to-nearest conversion.
static inline int32x4_t RoundToI32Neon(float32x4_t v) {
    return vcvtnq_s32_f32(v);
}
#else
// armv7: vcvtq_s32_f32 truncates toward zero — bias by +0.5 / -0.5 first
// (sign bit OR'd into 0.5f) to reach round-half-away-from-zero, which stays
// within 1 LSB of the A64/SSE round-to-nearest contract.
static inline int32x4_t RoundToI32Neon(float32x4_t v) {
    const uint32x4_t sign = vshrq_n_u32(vreinterpretq_u32_f32(v), 31);
    const uint32x4_t signBit = vshlq_n_u32(sign, 31);
    const uint32x4_t biasBits =
        vorrq_u32(vreinterpretq_u32_f32(vdupq_n_f32(0.5f)), signBit);
    const float32x4_t bias = vreinterpretq_f32_u32(biasBits);
    return vcvtq_s32_f32(vaddq_f32(v, bias));
}
#endif

static void ConvertF32ToI16Neon(const float* src, int16_t* dst, size_t count) {
    const float32x4_t kHi = vdupq_n_f32(1.0f);
    const float32x4_t kLo = vdupq_n_f32(-1.0f);
    const float32x4_t kScale = vdupq_n_f32(32767.0f);
    size_t i = 0;
    for (; i + 8 <= count; i += 8) {
        float32x4_t a = vminq_f32(vmaxq_f32(vld1q_f32(src + i), kLo), kHi);
        float32x4_t b = vminq_f32(vmaxq_f32(vld1q_f32(src + i + 4), kLo), kHi);
        a = vmulq_f32(a, kScale);
        b = vmulq_f32(b, kScale);
        const int32x4_t ia = RoundToI32Neon(a);
        const int32x4_t ib = RoundToI32Neon(b);
        vst1q_s16(dst + i, vcombine_s16(vqmovn_s32(ia), vqmovn_s32(ib)));
    }
    for (; i + 4 <= count; i += 4) {
        float32x4_t a = vminq_f32(vmaxq_f32(vld1q_f32(src + i), kLo), kHi);
        a = vmulq_f32(a, kScale);
        vst1_s16(dst + i, vqmovn_s32(RoundToI32Neon(a)));
    }
    for (; i < count; ++i) dst[i] = ConvertSampleF32ToI16(src[i]);
}

static void DownmixNeon(const float* src, float* dst, size_t frames) {
    const float32x4_t half = vdupq_n_f32(0.5f);
    size_t i = 0;
    for (; i + 4 <= frames; i += 4) {
        const float32x4x2_t lr = vld2q_f32(src + 2 * i);
        vst1q_f32(dst + i, vmulq_f32(vaddq_f32(lr.val[0], lr.val[1]), half));
    }
    for (; i < frames; ++i) {
        dst[i] = (src[2 * i] + src[2 * i + 1]) * 0.5f;
    }
}

#elif defined(__SSE2__)

static void ConvertF32ToI16Sse2(const float* src, int16_t* dst, size_t count) {
    const __m128 kHi = _mm_set1_ps(1.0f);
    const __m128 kLo = _mm_set1_ps(-1.0f);
    const __m128 kScale = _mm_set1_ps(32767.0f);
    size_t i = 0;
    for (; i + 8 <= count; i += 8) {
        __m128 a = _mm_loadu_ps(src + i);
        __m128 b = _mm_loadu_ps(src + i + 4);
        a = _mm_min_ps(_mm_max_ps(a, kLo), kHi);
        b = _mm_min_ps(_mm_max_ps(b, kLo), kHi);
        a = _mm_mul_ps(a, kScale);
        b = _mm_mul_ps(b, kScale);
        const __m128i ia = _mm_cvtps_epi32(a);  // round to nearest even
        const __m128i ib = _mm_cvtps_epi32(b);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(dst + i),
                         _mm_packs_epi32(ia, ib));  // saturate to int16
    }
    for (; i + 4 <= count; i += 4) {
        __m128 a = _mm_loadu_ps(src + i);
        a = _mm_mul_ps(_mm_min_ps(_mm_max_ps(a, kLo), kHi), kScale);
        const __m128i ia = _mm_cvtps_epi32(a);
        _mm_storel_epi64(reinterpret_cast<__m128i*>(dst + i),
                         _mm_packs_epi32(ia, ia));
    }
    for (; i < count; ++i) dst[i] = ConvertSampleF32ToI16(src[i]);
}

static void DownmixSse2(const float* src, float* dst, size_t frames) {
    const __m128 half = _mm_set1_ps(0.5f);
    size_t i = 0;
    for (; i + 4 <= frames; i += 4) {
        const __m128 a = _mm_loadu_ps(src + 2 * i);      // L0 R0 L1 R1
        const __m128 b = _mm_loadu_ps(src + 2 * i + 4);  // L2 R2 L3 R3
        const __m128 l = _mm_shuffle_ps(a, b, _MM_SHUFFLE(2, 0, 2, 0));
        const __m128 r = _mm_shuffle_ps(a, b, _MM_SHUFFLE(3, 1, 3, 1));
        _mm_storeu_ps(dst + i, _mm_mul_ps(_mm_add_ps(l, r), half));
    }
    for (; i < frames; ++i) {
        dst[i] = (src[2 * i] + src[2 * i + 1]) * 0.5f;
    }
}

#endif  // SIMD dispatch

void ConvertF32ToI16(const float* src, int16_t* dst, size_t count) {
    if (src == nullptr || dst == nullptr || count == 0) return;
#if STREAMIFY_HAVE_NEON
    ConvertF32ToI16Neon(src, dst, count);
#elif defined(__SSE2__)
    ConvertF32ToI16Sse2(src, dst, count);
#else
    for (size_t i = 0; i < count; ++i) dst[i] = ConvertSampleF32ToI16(src[i]);
#endif
}

void DownmixStereoToMono(const float* src, float* dst, size_t samples) {
    if (src == nullptr || dst == nullptr || samples < 2) return;
    const size_t frames = samples / 2;  // ignore a dangling odd sample
#if STREAMIFY_HAVE_NEON
    DownmixNeon(src, dst, frames);
#elif defined(__SSE2__)
    DownmixSse2(src, dst, frames);
#else
    for (size_t i = 0; i < frames; ++i) {
        dst[i] = (src[2 * i] + src[2 * i + 1]) * 0.5f;
    }
#endif
}

// ============================================================================
//  Decimator — linear-phase windowed-sinc FIR with integer decimation
// ============================================================================

bool Decimator::init(uint32_t factor, uint32_t channels) {
    if (factor == 0 || factor > 3 || channels == 0 || channels > 2) {
        return false;
    }
    factor_ = factor;
    channels_ = channels;
    hist_pos_ = 0;
    phase_ = 0;
    if (factor_ == 1) {
        taps_ = 1;
        coeffs_.assign(1, 1.0f);
        history_.clear();
        return true;
    }
    taps_ = 16u * factor_ + 15u;  // 47 (D=2) / 63 (D=3), odd -> symmetric
    coeffs_.assign(taps_, 0.0f);
    constexpr double kPi = 3.14159265358979323846;
    const double fc = 0.45 / factor_;  // cycles per input sample
    const double m = static_cast<double>(taps_ - 1) / 2.0;
    double sum = 0.0;
    for (size_t k = 0; k < taps_; ++k) {
        const double x = static_cast<double>(k) - m;
        const double sinc = (std::fabs(x) < 1e-12)
                                ? 2.0 * fc
                                : std::sin(kPi * 2.0 * fc * x) / (kPi * x);
        const double w = 0.42 - 0.5 * std::cos(2.0 * kPi * k / (taps_ - 1)) +
                         0.08 * std::cos(4.0 * kPi * k / (taps_ - 1));
        const double c = sinc * w;
        coeffs_[k] = static_cast<float>(c);
        sum += c;
    }
    if (std::fabs(sum) < 1e-9) return false;  // degenerate design
    for (auto& c : coeffs_) c = static_cast<float>(c / sum);  // unity DC gain
    history_.assign(static_cast<size_t>(channels_) * taps_, 0.0f);
    return true;
}

void Decimator::reset() {
    std::fill(history_.begin(), history_.end(), 0.0f);
    hist_pos_ = 0;
    phase_ = 0;
}

size_t Decimator::process(const float* in, size_t frames, float* out) {
    if (in == nullptr || out == nullptr || frames == 0) return 0;
    const size_t ch = channels_;
    if (factor_ <= 1 || history_.empty()) {
        if (in != out) std::memcpy(out, in, frames * ch * sizeof(float));
        return frames;
    }
    const size_t t = taps_;
    size_t produced = 0;
    for (size_t f = 0; f < frames; ++f) {
        for (size_t c = 0; c < ch; ++c) {
            history_[c * t + hist_pos_] = in[f * ch + c];
        }
        hist_pos_ = (hist_pos_ + 1 == t) ? 0 : hist_pos_ + 1;
        if (++phase_ >= factor_) {
            phase_ = 0;
            const size_t newest = (hist_pos_ == 0) ? t - 1 : hist_pos_ - 1;
            for (size_t c = 0; c < ch; ++c) {
                const float* h = &history_[c * t];
                float acc = 0.0f;
                size_t pos = newest;
                for (size_t k = 0; k < t; ++k) {
                    acc += coeffs_[k] * h[pos];
                    pos = (pos == 0) ? t - 1 : pos - 1;
                }
                out[produced * ch + c] = acc;
            }
            ++produced;
        }
    }
    return produced;
}

// ============================================================================
//  AudioSink — configuration, SPSC ring, telemetry
// ============================================================================

namespace {

size_t NextPowerOfTwo(size_t v) {
    size_t p = 1;
    while (p < v && p < (size_t{1} << 31)) p <<= 1;
    return p;
}

}  // namespace

bool AudioSink::ValidateConfig(const Config& c) {
    if (c.sample_rate < kSinkMinSampleRate || c.sample_rate > kSinkMaxSampleRate) {
        return false;
    }
    if (c.channels != 1 && c.channels != 2) return false;
    if (c.capacity_frames < kSinkMinCapacityFrames ||
        c.capacity_frames > kSinkMaxCapacityFrames) {
        return false;
    }
    const uint32_t f = static_cast<uint32_t>(c.decimation);
    if (f != 1 && f != 2 && f != 3) return false;
    if (c.sample_rate / f < 4000) return false;  // keep the output rate sane
    if (c.downmix_to_mono && c.channels != 2) return false;
    return true;
}

AudioSink::Config AudioSink::NormalizeConfig(const Config& c) {
    Config n = c;
    size_t cap = NextPowerOfTwo(n.capacity_frames);
    if (cap < kSinkMinCapacityFrames) cap = kSinkMinCapacityFrames;
    if (cap > kSinkMaxCapacityFrames) cap = kSinkMaxCapacityFrames;
    n.capacity_frames = cap;
    return n;
}

AudioSink::AudioSink(const Config& config) {
    if (!ValidateConfig(config)) return;  // stays invalid; all ops no-op
    const Config cfg = NormalizeConfig(config);

    sample_rate_in_ = cfg.sample_rate;
    channels_in_ = cfg.channels;
    decimation_factor_ = static_cast<uint32_t>(cfg.decimation);
    downmix_ = cfg.downmix_to_mono && channels_in_ == 2;
    channels_out_ = downmix_ ? 1 : channels_in_;
    sample_rate_out_ = sample_rate_in_ / decimation_factor_;
    capacity_ = cfg.capacity_frames;
    mask_ = capacity_ - 1;
    frame_bytes_ = static_cast<size_t>(channels_out_) * sizeof(int16_t);

    const size_t ring_bytes =
        streamify::alignUp(capacity_ * channels_out_ * sizeof(int16_t), 64);
    ring_ = static_cast<int16_t*>(streamify::alignedAlloc(64, ring_bytes));
    if (ring_ == nullptr) return;
    std::memset(ring_, 0, ring_bytes);

    if (!decimator_.init(decimation_factor_ > 1 ? decimation_factor_ : 1u,
                         channels_out_)) {
        streamify::alignedFree(ring_);
        ring_ = nullptr;
        return;
    }

    const size_t dec_frames = kBlockFrames / decimation_factor_ + 2;
    mix_scratch_.assign(kBlockFrames, 0.0f);
    dec_scratch_.assign(dec_frames * channels_out_, 0.0f);
    pcm_scratch_.assign(dec_frames * channels_out_, 0);

    low_watermark_.store(static_cast<uint32_t>(capacity_),
                         std::memory_order_relaxed);
}

AudioSink::~AudioSink() {
    if (ring_ != nullptr) {
        streamify::alignedFree(ring_);
        ring_ = nullptr;
    }
}

// ---- producer side ----------------------------------------------------------

size_t AudioSink::writePcm_(const int16_t* src, size_t frames) {
    if (src == nullptr || frames == 0) return 0;
    const uint64_t h = head_.load(std::memory_order_relaxed);
    const uint64_t t = tail_.load(std::memory_order_acquire);
    const size_t used = static_cast<size_t>(h - t);
    const size_t n = frames < capacity_ - used ? frames : capacity_ - used;
    if (n == 0) return 0;
    const size_t pos = static_cast<size_t>(h) & mask_;
    const size_t ch = channels_out_;
    const size_t first = n < capacity_ - pos ? n : capacity_ - pos;
    std::memcpy(ring_ + pos * ch, src, first * ch * sizeof(int16_t));
    if (n > first) {
        std::memcpy(ring_, src + first * ch, (n - first) * ch * sizeof(int16_t));
    }
    head_.store(h + n, std::memory_order_release);
    frames_written_.fetch_add(n, std::memory_order_relaxed);

    // High watermark: occupancy right after this commit (producer-owned).
    const uint64_t t2 = tail_.load(std::memory_order_acquire);
    const size_t occ = static_cast<size_t>(h + n - t2);
    uint32_t hw = high_watermark_.load(std::memory_order_relaxed);
    while (occ > hw &&
           !high_watermark_.compare_exchange_weak(
               hw, static_cast<uint32_t>(occ), std::memory_order_relaxed)) {
    }
    return n;
}

size_t AudioSink::WriteFloat(const float* samples, size_t sample_count) {
    if (ring_ == nullptr || samples == nullptr || sample_count == 0) return 0;
    const size_t chIn = channels_in_;
    const size_t frames_total = sample_count / chIn;  // whole frames only
    const uint32_t d = decimation_factor_;
    size_t consumed = 0;  // input SAMPLES committed
    size_t f = 0;
    bool blocked = false;

    while (f < frames_total) {
        size_t block = frames_total - f;
        if (block > kBlockFrames) block = kBlockFrames;
        if (block > capacity_) block = capacity_;  // a block always fits an
                                                   // empty ring (livelock guard)
        // Lossless early-out: require room for the worst-case output of this
        // block so a partial ring write never has to drop samples.
        const size_t need = (d > 1) ? (block / d + 2) : block;
        {
            const uint64_t h = head_.load(std::memory_order_relaxed);
            const uint64_t t = tail_.load(std::memory_order_acquire);
            const size_t used = static_cast<size_t>(h - t);
            if (capacity_ - used < need) {
                blocked = true;
                break;
            }
        }

        const float* stage = samples + f * chIn;
        if (downmix_) {
            DownmixStereoToMono(samples + f * chIn, mix_scratch_.data(),
                                block * chIn);
            stage = mix_scratch_.data();
        }
        const float* pcm_f = stage;
        size_t produced = block;
        if (d > 1) {
            produced = decimator_.process(stage, block, dec_scratch_.data());
            pcm_f = dec_scratch_.data();
        }
        if (produced > 0) {
            ConvertF32ToI16(pcm_f, pcm_scratch_.data(),
                            produced * channels_out_);
            const size_t n = writePcm_(pcm_scratch_.data(), produced);
            if (n < produced) {
                // Unreachable given the `need` precheck; kept as a hardened
                // safety net so even a future regression can never corrupt
                // the stream — the unfitted tail is dropped and accounted.
                overrun_dropped_.fetch_add(produced - n,
                                           std::memory_order_relaxed);
                consumed += block * chIn;
                blocked = true;
                break;
            }
        }
        consumed += block * chIn;
        f += block;
    }
    if (blocked) {
        overrun_events_.fetch_add(1, std::memory_order_relaxed);
    }
    return consumed;
}

// ---- consumer side ----------------------------------------------------------

size_t AudioSink::readPcm_(int16_t* dst, size_t frames) {
    // ---- AMP-10: consumer-cooperative flush ------------------------------
    // A pending RequestFlush() (any thread — typically UI/control) is
    // consumed HERE, on the single consumer thread that owns tail_.
    // Performing the reset on the consumer side means no thread ever
    // mutates a cursor the audio callback is concurrently updating: the
    // pre-hardening Flush() wrote tail_ from the control thread while
    // this very function was mid-flight, risking a torn tail_ > head_
    // (uint64 underflow in the occupancy math) and reading purged ring
    // slots (memory corruption).
    //
    // Ordering: the release store in RequestFlush() pairs with this
    // acquire exchange, so the request is fully visible before the
    // reset runs; the tail_ release store pairs with the producer's
    // acquire load in writePcm_'s free-space check, so a concurrent
    // WriteFloat immediately sees the freed capacity. tail_ can only
    // move FORWARD to a committed head_, so the SPSC invariant
    // 0 <= head_ - tail_ <= capacity_ holds throughout.
    //
    // The flushed call emits digital silence (the whole destination,
    // bounded memset — this ring's consumer edge is PCM16, 2 bytes per
    // sample; the protocol is identical for a float-domain ring with
    // sizeof(float)) and returns 0. It deliberately does NOT count an
    // underrun: a requested flush is a control decision, not buffer
    // starvation. Real-time safety: one lock-free atomic exchange, one
    // lock-free store, one bounded memset — no locks, no allocation,
    // no sleeping.
    if (flush_requested_.exchange(false, std::memory_order_acquire)) {
        tail_.store(head_.load(std::memory_order_acquire),
                    std::memory_order_release);
        if (dst != nullptr && frames > 0) {
            std::memset(dst, 0, frames * channels_out_ * sizeof(int16_t));
        }
        return 0;
    }
    if (dst == nullptr || frames == 0) return 0;
    const uint64_t t = tail_.load(std::memory_order_relaxed);
    const uint64_t h = head_.load(std::memory_order_acquire);
    const size_t avail = static_cast<size_t>(h - t);
    const size_t n = frames < avail ? frames : avail;
    if (n < frames) {
        underrun_events_.fetch_add(1, std::memory_order_relaxed);
    }
    if (n == 0) {
        // We observed an empty ring — record the floor watermark.
        uint32_t lw = low_watermark_.load(std::memory_order_relaxed);
        while (lw != 0 &&
               !low_watermark_.compare_exchange_weak(
                   lw, 0u, std::memory_order_relaxed)) {
        }
        return 0;
    }
    const size_t pos = static_cast<size_t>(t) & mask_;
    const size_t ch = channels_out_;
    const size_t first = n < capacity_ - pos ? n : capacity_ - pos;
    std::memcpy(dst, ring_ + pos * ch, first * ch * sizeof(int16_t));
    if (n > first) {
        std::memcpy(dst + first * ch, ring_,
                    (n - first) * ch * sizeof(int16_t));
    }
    tail_.store(t + n, std::memory_order_release);
    frames_read_.fetch_add(n, std::memory_order_relaxed);

    // Low watermark: occupancy after this read, w.r.t. a conservative
    // (possibly stale-low) producer cursor — never over-reports.
    const uint64_t h2 = head_.load(std::memory_order_acquire);
    const size_t occ = static_cast<size_t>(h2 - (t + n));
    uint32_t lw = low_watermark_.load(std::memory_order_relaxed);
    while (occ < lw &&
           !low_watermark_.compare_exchange_weak(
               lw, static_cast<uint32_t>(occ), std::memory_order_relaxed)) {
    }
    return n;
}

size_t AudioSink::ReadFrames(int16_t* dst, size_t max_frames) {
    if (ring_ == nullptr) return 0;
    return readPcm_(dst, max_frames);
}

size_t AudioSink::ReadBytes(uint8_t* dst, size_t capacity_bytes) {
    if (ring_ == nullptr || dst == nullptr || capacity_bytes < frame_bytes_) {
        return 0;
    }
    const size_t frames = capacity_bytes / frame_bytes_;
    return readPcm_(reinterpret_cast<int16_t*>(dst), frames) * frame_bytes_;
}

// ---- any-thread telemetry -------------------------------------------------

void AudioSink::GetStats(SinkStats* out) const {
    if (out == nullptr) return;
    *out = SinkStats{};
    if (ring_ == nullptr) return;
    const uint64_t h = head_.load(std::memory_order_acquire);
    const uint64_t t = tail_.load(std::memory_order_acquire);
    const size_t occ = static_cast<size_t>(h - t);  // <= capacity by invariant

    out->capacity_frames = static_cast<uint32_t>(capacity_);
    out->sample_rate_in = sample_rate_in_;
    out->sample_rate_out = sample_rate_out_;
    out->channels_in = channels_in_;
    out->channels_out = channels_out_;
    out->frames_written = frames_written_.load(std::memory_order_relaxed);
    out->frames_read = frames_read_.load(std::memory_order_relaxed);
    out->underrun_events = underrun_events_.load(std::memory_order_relaxed);
    out->overrun_events = overrun_events_.load(std::memory_order_relaxed);
    out->overrun_dropped_frames =
        overrun_dropped_.load(std::memory_order_relaxed);
    out->high_watermark_frames =
        high_watermark_.load(std::memory_order_relaxed);
    out->low_watermark_frames = low_watermark_.load(std::memory_order_relaxed);
    out->current_fullness_frames =
        occ < capacity_ ? static_cast<uint32_t>(occ)
                        : static_cast<uint32_t>(capacity_);
    out->fullness_percent =
        100.0f * static_cast<float>(out->current_fullness_frames) /
        static_cast<float>(capacity_);
    out->estimated_latency_ms =
        1000.0f * static_cast<float>(out->current_fullness_frames) /
        static_cast<float>(sample_rate_out_);
}

}  // namespace sink
}  // namespace streamify
