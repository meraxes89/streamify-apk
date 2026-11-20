#ifndef STREAMIFY_AUDIO_SINK_RINGBUFFER_H
#define STREAMIFY_AUDIO_SINK_RINGBUFFER_H
// ============================================================================
//  audio_sink_ringbuffer.h — Phase-4 lock-free low-latency audio sink,
//  hardened in Phase 3 (multiplatform MP-C08/09/10)
//  (native/include/audio_sink_ringbuffer.h)
// ============================================================================
//
//  Deliverable 1 of the Phase-4 directive: a single-producer single-consumer
//  circular sample buffer that decouples the app audio pipeline (float PCM
//  producer) from the platform HAL / companion-network consumer.
//
//  SIGNAL PATH (producer side, all stages zero-allocation after init):
//
//      float[-1,1] interleaved
//        -> optional stereo->mono downmix      (SIMD, (L+R)*0.5)
//        -> optional anti-aliased FIR decimation (48k -> 24k / 16k family)
//        -> SPSC float ring                    (f32 storage, acquire/release)
//
//  PURE f32 STORAGE (Phase 3 / MP-C08, master plan invariant 5):
//  The ring stores IEEE 754 32-bit floats end-to-end — samples are NEVER
//  quantized to PCM16 inside the buffer. The canonical consumer edge is
//  ReadFramesF32() (the pull path the Phase-4a HAL adapters — AAudio
//  dataCallback / CoreAudio AURenderCallback — ingest directly, full
//  32-bit dynamic range, no FFI on the audio thread). ReadFrames() and
//  ReadBytes() are the LEGACY PCM16 JNI edge (jni_bridge_audio_sinks'
//  frozen DirectByteBuffer contract): they quantize f32 -> PCM16 at their
//  own consumer edge, byte-identical to the pre-Phase-3 ring output —
//  quantization moved from "before the ring" to "at the legacy consumer",
//  exactly where the platform PCM16 HAL ingestion happens.
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
//    own cache line — alignas(kCacheLineSize) — so reader and writer states
//    can never share a line, eliminating L1 false sharing across cores.
//    kCacheLineSize is TARGET-DEPENDENT (Phase 3 / MP-C08): Apple Silicon
//    (aarch64 Darwin) has 128-byte L1 lines, everything else (x86_64,
//    Windows, generic ARM incl. Android) uses the classic 64.
//  * WriteFloat is lossless-and-non-blocking: it only commits a block when
//    the ring provably has room for the whole converted block, returning the
//    number of input samples actually consumed so the caller can retry the
//    remainder (AudioTrack#write(NON_BLOCKING) parity). overrun_dropped_
//    exists as a safety net and stays 0 in correct operation.
//  * Flushing is GENERATIONAL and CONSUMER-COOPERATIVE (Phase 3 / MP-C10,
//    master plan invariant 7):
//      - Requester (producer/control thread): bumps flush_generation_
//        (fetch_add, Relaxed) and then waits — bounded by
//        kFlushAckTimeoutMs — until flush_ack_generation_ reaches its
//        generation.
//      - Consumer: at the top of its next read it detects the generation
//        change, resets tail_ to the committed head_ (it owns tail_), and
//        publishes the ack (release store of the observed generation).
//    The control thread therefore never mutates cursors the audio callback
//    is concurrently updating; the real-time consumer path stays 100%
//    lock-free, zero-allocation and sleep-free (one acquire load + one
//    release tail_ store + one release ack store + a bounded memset on the
//    flush pass). Requests are level-triggered and coalescing by
//    construction: N bumps before the next read collapse into one flush
//    pass; a late reader always acks the NEWEST observed generation, so
//    every waiter is released at once.
//    CONTRACT: RequestFlush() must be called from the PRODUCER (or a
//    control thread coordinating with it — no in-flight WriteFloat during
//    an un-acked generation) and NEVER from the consumer thread itself
//    (self-ack is impossible; the bounded wait would burn the caller's
//    audio deadline for nothing and the flush would apply only on the next
//    read anyway). The bounded timeout also degrades gracefully when the
//    consumer is not reading at all (paused / torn-down device): the
//    request stays pending and applies lazily on the next read.
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

// ---- target-dependent cache line size (Phase 3 / MP-C08) -------------------
// Apple Silicon L1 uses 128-byte lines: two 64-byte-separated hot variables
// would share one physical line on an M-series core and reintroduce the
// false sharing this class exists to eliminate. Everything else in the
// fleet (x86_64 incl. Windows MSVC, generic ARM/AArch64 incl. Android,
// and any future RISC-V lane) stays on the classic 64-byte line.
#if defined(__APPLE__) && defined(__aarch64__)
inline constexpr size_t kCacheLineSize = 128;  // Apple Silicon L1 cache line
#else
inline constexpr size_t kCacheLineSize = 64;   // x86_64 / ARM standard line
#endif

// ---- generational flush protocol (Phase 3 / MP-C10) ------------------------
// Bounded wait for the consumer's ack, in milliseconds. 100 ms covers every
// realistic consumer cadence (an AudioTrack/AAudio callback chunk of 4096
// frames @ 48 kHz is ~85 ms) while keeping a consumerless (paused) sink
// from parking the requester thread indefinitely. After the timeout the
// request simply stays pending: the consumer applies it on its next read.
inline constexpr uint64_t kFlushAckTimeoutMs = 100;

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
//
// Phase 3 note: this is now exclusively the LEGACY PCM16 consumer-edge
// converter (ReadFrames / ReadBytes). The ring itself stores f32; the
// producer path never quantizes (MP-C08).
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
    // Samples are stored as raw f32 — full dynamic range preserved (the
    // producer never quantizes; MP-C08).
    size_t WriteFloat(const float* samples, size_t sample_count);

    // ---- consumer thread (single reader) -----------------------------------
    // CANONICAL f32 pull path (Phase 3): read up to `max_frames` interleaved
    // f32 output frames straight out of the float ring. Returns the frame
    // count actually read (0 == starved, counts one underrun event). This
    // is the edge the Phase-4a HAL adapters (AAudio / CoreAudio / WASAPI /
    // PipeWire) ingest: zero quantization between producer and DAC.
    size_t ReadFramesF32(float* dst, size_t max_frames);

    // LEGACY PCM16 edge (frozen JNI DirectByteBuffer contract): read up to
    // `max_frames` interleaved PCM16 output frames — the f32 ring contents
    // quantized AT THIS EDGE (bit-identical to the pre-Phase-3 output).
    // Returns the frame count actually read (0 == starved / flushed).
    size_t ReadFrames(int16_t* dst, size_t max_frames);
    // Byte-oriented, frame-aligned PCM16 read for DirectByteBuffer handoffs.
    // Returns PCM16 bytes copied (multiple of frame bytes), 0 when starved.
    size_t ReadBytes(uint8_t* dst, size_t capacity_bytes);

    // ---- any thread ----------------------------------------------------------
    void GetStats(SinkStats* out) const;

    // Generational flush request (Phase 3 / MP-C10): drop buffered audio.
    // Bumps the flush generation and then BOUNDED-WAITS (<=
    // kFlushAckTimeoutMs) for the consumer thread to acknowledge by
    // resetting its read cursor; the consumer performs the actual reset at
    // the top of its next Read* call, where it already owns tail_. See the
    // protocol contract in the file header — call from the producer or a
    // control thread coordinating with it, NEVER from the consumer thread.
    // Cumulative counters are preserved; occupancy drops to whatever the
    // producer commits afterwards. The flushed call emits digital silence
    // (whole destination memset) and returns 0; it deliberately does NOT
    // count an underrun (a flush is a control decision, not starvation).
    // noexcept: atomics, clock reads and yield cannot throw (AMP-10).
    void RequestFlush() noexcept;

    // ---- immutable accessors -------------------------------------------------
    uint32_t sample_rate_in() const { return sample_rate_in_; }
    uint32_t sample_rate_out() const { return sample_rate_out_; }
    uint32_t channels_in() const { return channels_in_; }
    uint32_t channels_out() const { return channels_out_; }
    size_t capacity_frames() const { return capacity_; }
    // PCM16 bytes per output frame — the LEGACY JNI edge unit. The f32
    // pull path moves channels_out_ * sizeof(float) bytes per frame.
    size_t frame_bytes_out() const { return frame_bytes_; }
    // Flush generation currently published (monotonic; 0 = never flushed).
    uint64_t flush_generation() const {
        return flush_generation_.load(std::memory_order_relaxed);
    }
    // Generation the consumer last acknowledged (== flush_generation()
    // once the latest request has been serviced on the consumer thread).
    uint64_t flush_ack_generation() const {
        return flush_ack_generation_.load(std::memory_order_acquire);
    }

private:
    // Raw ring primitives (frame granularity, two-segment memcpy) on the
    // f32 storage. readFloats_ also advances tail_/frames_read_.
    size_t writeFloats_(const float* src, size_t frames);
    size_t readFloats_(float* dst, size_t frames);

    // Generational flush pass: if a request is pending, reset tail_ to the
    // committed head_ (consumer-owned cursor move only), publish the ack
    // and return true — the caller then silences its whole destination and
    // returns 0 for this call (legacy AMP-10 semantics, generation-based).
    bool consumePendingFlush_();

    // Shared post-read telemetry: underrun accounting + low-watermark floor.
    void recordReadTelemetry_(size_t got, size_t requested);

    // ---- immutable configuration ---------------------------------------------
    uint32_t sample_rate_in_ = 48000;
    uint32_t channels_in_ = 2;
    uint32_t sample_rate_out_ = 48000;
    uint32_t channels_out_ = 2;
    uint32_t decimation_factor_ = 1;
    bool downmix_ = false;
    size_t capacity_ = 0;   // power-of-two output frames
    size_t mask_ = 0;
    size_t frame_bytes_ = 4;  // channels_out_ * sizeof(int16_t) — legacy
                              // PCM16 JNI edge unit (ReadBytes)
    float* ring_ = nullptr;   // f32 storage, C++17 over-aligned new[]
                              // (kCacheLineSize; MP-C09 — no posix_memalign)

    static constexpr size_t kBlockFrames = 256;

    // ---- producer-owned state (one cache line each) ---------------------------
    alignas(kCacheLineSize) std::atomic<uint64_t> head_{0};
    alignas(kCacheLineSize) std::atomic<uint64_t> frames_written_{0};
    alignas(kCacheLineSize) std::atomic<uint64_t> overrun_events_{0};
    alignas(kCacheLineSize) std::atomic<uint64_t> overrun_dropped_{0};
    alignas(kCacheLineSize) std::atomic<uint32_t> high_watermark_{0};

    // ---- consumer-owned state (one cache line each) ---------------------------
    alignas(kCacheLineSize) std::atomic<uint64_t> tail_{0};
    alignas(kCacheLineSize) std::atomic<uint64_t> frames_read_{0};
    alignas(kCacheLineSize) std::atomic<uint64_t> underrun_events_{0};
    alignas(kCacheLineSize) std::atomic<uint32_t> low_watermark_{0};
    // Ack of the generation the consumer has actually serviced. Consumer
    // writes it (release) after resetting tail_; requesters acquire-load it
    // in their bounded wait. Lives on the CONSUMER side of the line split
    // so a spinning requester never bounces a line the audio callback owns.
    alignas(kCacheLineSize) std::atomic<uint64_t> flush_ack_generation_{0};

    // ---- requester-owned flush control plane (own cache line: a control
    //      thread stores here while the audio callback runs — sharing a
    //      line with consumer state would reintroduce the false sharing
    //      AMP-10 removes; the ack stays on the consumer side so the two
    //      directions of the handshake never share either) --------------------
    alignas(kCacheLineSize) std::atomic<uint64_t> flush_generation_{0};

    // ---- producer-thread scratch (allocated once at construction) --------------
    std::vector<float> mix_scratch_;   // kBlockFrames mono floats
    std::vector<float> dec_scratch_;   // (kBlockFrames/D + 2) * ch_out floats
    Decimator decimator_;

    // ---- consumer-thread scratch (allocated once at construction) --------------
    // f32 staging for the legacy PCM16 edge (ReadFrames / ReadBytes
    // quantize through here, kBlockFrames frames at a time).
    std::vector<float> pcm_read_scratch_;  // kBlockFrames * ch_out floats
};

}  // namespace sink
}  // namespace streamify

#endif  // STREAMIFY_AUDIO_SINK_RINGBUFFER_H
