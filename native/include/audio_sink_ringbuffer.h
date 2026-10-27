#ifndef STREAMIFY_AUDIO_SINK_RINGBUFFER_H
#define STREAMIFY_AUDIO_SINK_RINGBUFFER_H
// ============================================================================
//  audio_sink_ringbuffer.h — Phase-4 lock-free low-latency audio sink
//  (native/include/audio_sink_ringbuffer.h)
// ============================================================================
//
//  Deliverable 1 of the Phase-4 directive: a single-producer single-consumer
//  circular sample buffer that decouples the app audio pipeline (float PCM
//  producer) from the Android AudioTrack / companion-network consumer.
//
//  SIGNAL PATH (producer side, all stages zero-allocation after init):
//
//      float[-1,1] interleaved
//        -> optional stereo->mono downmix      (SIMD, (L+R)*0.5)
//        -> optional anti-aliased FIR decimation (48k -> 24k / 16k family)
//        -> hard clamp + Float32 -> PCM16      (NEON vqmovn_s32 / SSE2 packs)
//        -> SPSC int16 ring                    (acquire/release cursors)
//
//  CONCURRENCY MODEL
//  -----------------
//  * head_ (frames committed) is producer-owned, tail_ (frames consumed) is
//    consumer-owned; both are free-running uint64 counters — capacity is a
//    power of two, masked on access, so cursor arithmetic never wraps
//    ambiguously. Producer publishes with memory_order_release, consumer
//    observes with memory_order_acquire (and vice versa), giving the classic
//    Lamport SPSC queue without any locks on the hot path.
//  * Every producer/consumer-owned cursor AND telemetry counter sits on its
//    own 64-byte cache line (alignas(64)) — reader and writer states can
//    never share a line, eliminating false sharing across cores.
//  * WriteFloat is lossless-and-non-blocking: it only commits a block when
//    the ring provably has room for the whole converted block, returning the
//    number of input samples actually consumed so the caller can retry the
//    remainder (AudioTrack#write(NON_BLOCKING) parity). overrun_dropped_
//    exists as a safety net and stays 0 in correct operation.
//  * Flushing is CONSUMER-COOPERATIVE (AMP-10 hardening): any thread may
//    call RequestFlush() — it only publishes an atomic flag — and the
//    consumer thread performs the actual cursor reset inside its next
//    ReadFrames()/ReadBytes() call, where it already owns tail_. The
//    control thread therefore never mutates cursors the audio callback is
//    concurrently updating, and the real-time path stays 100% lock-free,
//    zero-allocation and sleep-free (one acquire exchange + one release
//    store + a bounded memset on the flush pass).
//
//  This unit is the sink-side counterpart of mix/AudioRingBuffer.h (Phase 1,
//  float mixing ring): it owns format conversion, downmix, decimation and
//  playback telemetry instead of multi-peer mixing.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace streamify {
namespace sink {

// ---- validation bounds -----------------------------------------------------
inline constexpr uint32_t kSinkMinSampleRate = 8000;    // Hz
inline constexpr uint32_t kSinkMaxSampleRate = 192000;  // Hz
inline constexpr size_t kSinkMinCapacityFrames = 64;
inline constexpr size_t kSinkMaxCapacityFrames = 1u << 22;  // 4M frames

// Telemetry snapshot (plain values; see streamify_audio_sinks.h for the
// DirectByteBuffer wire layout consumed by the JNI stats bridge).
struct SinkStats {
    uint32_t capacity_frames;          // power-of-two ring capacity (frames)
    uint32_t sample_rate_in;           // Hz, producer side
    uint32_t sample_rate_out;          // Hz, after decimation
    uint32_t channels_in;              // 1..2, producer side
    uint32_t channels_out;             // 1..2, ring side
    uint64_t frames_written;           // output frames committed by producer
    uint64_t frames_read;              // output frames consumed by reader
    uint64_t underrun_events;          // reads that returned < requested
    uint64_t overrun_events;           // writes that could not fully commit
    uint64_t overrun_dropped_frames;   // frames lost to a full ring (safety
                                       // net; 0 in correct operation)
    uint32_t high_watermark_frames;    // max occupancy observed (producer)
    uint32_t low_watermark_frames;     // min occupancy observed (consumer)
    uint32_t current_fullness_frames;  // live occupancy
    float fullness_percent;            // occupancy / capacity * 100
    float estimated_latency_ms;        // occupancy / output rate * 1000
};

// Integer decimation factors (input 48 kHz family -> lower output rates).
enum class Decimation : uint32_t {
    kNone = 1,  // passthrough
    kHalf = 2,  // 48k -> 24k, 44.1k -> 22.05k
    kThird = 3, // 48k -> 16k
};

// ---- SIMD sample conversion ------------------------------------------------

// Scalar reference: hard clamp to [-1, 1], scale by 32767, round to nearest.
// Non-finite input (NaN / +-Inf) maps to digital silence (0).
int16_t ConvertSampleF32ToI16(float sample);

// Vectorized Float32 -> signed PCM16 for `count` interleaved samples.
//   arm64-v8a   : NEON, vcvtnq_s32_f32 + vqmovn_s32 saturation
//   armeabi-v7a : NEON, sign-biased vcvtq_s32_f32 (within 1 LSB of the
//                 A64/SSE round-to-nearest result)
//   x86_64      : SSE2 _mm_cvtps_epi32 + _mm_packs_epi32 saturation
//   otherwise   : scalar reference
// Values outside [-1, 1] are hard-clamped to +-32767; non-finite inputs stay
// inside the int16 range on every path (path-dependent value, documented
// contract: callers routing untrusted floats should sanitize first — the
// fuzz harness asserts range safety only). `src` and `dst` must not overlap.
void ConvertF32ToI16(const float* src, int16_t* dst, size_t count);

// Stereo -> mono downmix, (L + R) * 0.5. `samples` is the INPUT sample count
// (frames * 2); writes samples/2 mono samples. `src`/`dst` must not overlap.
// Vectorized on NEON (vld2q deinterleave) and SSE2 (shuffle unpk).
void DownmixStereoToMono(const float* src, float* dst, size_t samples);

// ---- anti-aliasing FIR decimator -------------------------------------------

// Linear-phase windowed-sinc lowpass (Blackman window) sampled at the input
// rate with cutoff 0.45/D normalized, applied once per D input frames.
// Coefficients are computed in double precision at init(); process() never
// allocates and never reads/writes outside its buffers.
class Decimator {
public:
    Decimator() = default;

    // factor in {1,2,3}; channels in {1,2}. factor 1 = passthrough.
    // Returns false on bad arguments or coefficient degeneration.
    bool init(uint32_t factor, uint32_t channels);
    void reset();

    // Consume `frames` interleaved float frames; append decimated frames to
    // `out` (caller-provided, >= frames/factor + 1 frames). Returns the
    // number of output frames produced. `in`/`out` must not overlap.
    size_t process(const float* in, size_t frames, float* out);

    uint32_t factor() const { return factor_; }
    uint32_t taps() const { return taps_; }

private:
    uint32_t factor_ = 1;
    uint32_t channels_ = 1;
    size_t taps_ = 1;
    std::vector<float> coeffs_;   // taps_ normalized coefficients
    std::vector<float> history_;  // channels_ * taps_ sample ring
    size_t hist_pos_ = 0;
    uint32_t phase_ = 0;
};

// ---- the SPSC sink ----------------------------------------------------------

class AudioSink {
public:
    struct Config {
        uint32_t sample_rate = 48000;      // producer-side Hz
        uint32_t channels = 2;             // producer-side channels (1|2)
        size_t capacity_frames = 8192;     // ring size; rounded up to pow2
        bool downmix_to_mono = false;      // requires channels == 2
        Decimation decimation = Decimation::kNone;
    };

    explicit AudioSink(const Config& config);
    ~AudioSink();

    AudioSink(const AudioSink&) = delete;
    AudioSink& operator=(const AudioSink&) = delete;

    // Range validation for every field (see kSink* bounds). The constructor
    // double-checks this defensively; invalid configs yield !valid() sinks.
    static bool ValidateConfig(const Config& c);
    // Capacity rounded up to the next power of two (>= kSinkMinCapacity).
    static Config NormalizeConfig(const Config& c);

    bool valid() const { return ring_ != nullptr; }

    // ---- producer thread (single writer) -----------------------------------
    // Write up to `sample_count` interleaved float samples (whole frames
    // only; a trailing partial frame is left for the caller). Returns the
    // number of INPUT samples consumed and committed; when the ring cannot
    // take the next converted block the call returns short (non-blocking)
    // and the caller retries the remainder. Never blocks, never allocates.
    size_t WriteFloat(const float* samples, size_t sample_count);

    // ---- consumer thread (single reader) -----------------------------------
    // Read up to `max_frames` interleaved PCM16 output frames. Returns the
    // frame count actually read (0 == starved, counts one underrun event).
    size_t ReadFrames(int16_t* dst, size_t max_frames);
    // Byte-oriented, frame-aligned read for DirectByteBuffer handoffs.
    // Returns PCM16 bytes copied (multiple of frame bytes), 0 when starved.
    size_t ReadBytes(uint8_t* dst, size_t capacity_bytes);

    // ---- any thread ----------------------------------------------------------
    void GetStats(SinkStats* out) const;

    // Control-plane flush request: drop buffered audio without ever
    // touching a cursor from the caller's thread. Safe to call from ANY
    // thread (typically UI/control) WHILE the producer and consumer are
    // live: it only publishes the request flag (release). The consumer
    // thread consumes it at the top of its next ReadFrames()/ReadBytes()
    // call — resetting tail_ to the committed head_, zero-filling the
    // destination with digital silence and returning 0 for that call.
    // Cumulative counters are preserved; occupancy drops to whatever the
    // producer commits afterwards. The request is level-triggered and
    // coalescing: N requests before the next read collapse into one
    // flush pass. noexcept: an atomic store cannot throw (AMP-10).
    void RequestFlush() noexcept {
        flush_requested_.store(true, std::memory_order_release);
    }

    // ---- immutable accessors -------------------------------------------------
    uint32_t sample_rate_in() const { return sample_rate_in_; }
    uint32_t sample_rate_out() const { return sample_rate_out_; }
    uint32_t channels_in() const { return channels_in_; }
    uint32_t channels_out() const { return channels_out_; }
    size_t capacity_frames() const { return capacity_; }
    size_t frame_bytes_out() const { return frame_bytes_; }

private:
    // Raw ring primitives (frame granularity, two-segment memcpy).
    size_t writePcm_(const int16_t* src, size_t frames);
    size_t readPcm_(int16_t* dst, size_t frames);

    // ---- immutable configuration ---------------------------------------------
    uint32_t sample_rate_in_ = 48000;
    uint32_t channels_in_ = 2;
    uint32_t sample_rate_out_ = 48000;
    uint32_t channels_out_ = 2;
    uint32_t decimation_factor_ = 1;
    bool downmix_ = false;
    size_t capacity_ = 0;   // power-of-two output frames
    size_t mask_ = 0;
    size_t frame_bytes_ = 4;
    int16_t* ring_ = nullptr;  // posix_memalign'ed (NeonCompat mandate)

    static constexpr size_t kBlockFrames = 256;

    // ---- producer-owned state (one cache line each) ---------------------------
    alignas(64) std::atomic<uint64_t> head_{0};
    alignas(64) std::atomic<uint64_t> frames_written_{0};
    alignas(64) std::atomic<uint64_t> overrun_events_{0};
    alignas(64) std::atomic<uint64_t> overrun_dropped_{0};
    alignas(64) std::atomic<uint32_t> high_watermark_{0};

    // ---- consumer-owned state (one cache line each) ---------------------------
    alignas(64) std::atomic<uint64_t> tail_{0};
    alignas(64) std::atomic<uint64_t> frames_read_{0};
    alignas(64) std::atomic<uint64_t> underrun_events_{0};
    alignas(64) std::atomic<uint32_t> low_watermark_{0};

    // ---- cross-thread control plane (own cache line: the UI thread stores
    //      here while the audio callback runs — sharing a line with consumer
    //      state would reintroduce the false sharing AMP-10 removes) ---------
    alignas(64) std::atomic<bool> flush_requested_{false};

    // ---- producer-thread scratch (allocated once at construction) --------------
    std::vector<float> mix_scratch_;   // kBlockFrames mono floats
    std::vector<float> dec_scratch_;   // (kBlockFrames/D + 2) * ch_out floats
    std::vector<int16_t> pcm_scratch_; // same count, PCM16
    Decimator decimator_;
};

}  // namespace sink
}  // namespace streamify

#endif  // STREAMIFY_AUDIO_SINK_RINGBUFFER_H
