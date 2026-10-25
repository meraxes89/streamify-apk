#ifndef STREAMIFY_AUDIO_RING_BUFFER_H
#define STREAMIFY_AUDIO_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "../util/NeonCompat.h"

// ============================================================================
//  AudioRingBuffer — lock-free SPSC ring for interleaved float PCM (Gap #11)
// ============================================================================
//
//  * Single producer / single consumer, wait-free on both sides.
//    head_ (frames written) is producer-owned, tail_ (frames read) is
//    consumer-owned; each sits on its own cache line. Indices are
//    free-running uint64 — capacity is a power of two, masked on access,
//    so the arithmetic never wraps ambiguously.
//  * Capacity is measured in FRAMES; storage holds capacity * channels
//    interleaved floats, posix_memalign'ed (Bionic minSdk-26 mandate).
//  * ZERO allocation after init(); the copy/memcpy paths are branch-free
//    two-segment transfers across the wrap point.
//  * overflowEvents_ is a RELAXED atomic (audit #22 item 9): written by
//    the producer only, but read cross-thread by mix()/stats callers —
//    a plain uint64_t tore on 32-bit and was a data race by the letter.
// ============================================================================

namespace streamify::mix {

class AudioRingBuffer {
public:
    AudioRingBuffer() = default;
    ~AudioRingBuffer() { streamify::alignedFree(storage_); }

    AudioRingBuffer(const AudioRingBuffer&) = delete;
    AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

    // channels in [1, 8]; capacityFrames rounded UP to the next power of two
    // (min 16). Returns false on allocation failure or bad arguments.
    bool init(int channels, size_t capacityFrames) {
        if (channels < 1 || channels > 8 || capacityFrames == 0) return false;
        channels_ = channels;
        capacity_ = 1;
        while (capacity_ < capacityFrames || capacity_ < 16) capacity_ <<= 1;
        mask_ = capacity_ - 1;
        streamify::alignedFree(storage_);
        storage_ = static_cast<float*>(streamify::alignedAlloc(
            64, streamify::alignUp(capacity_ * channels_ * sizeof(float), 64)));
        if (storage_ == nullptr) return false;
        std::memset(storage_, 0, capacity_ * channels_ * sizeof(float));
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
        overflowEvents_.store(0, std::memory_order_relaxed);
        return true;
    }

    bool initialized() const { return storage_ != nullptr; }
    void reset() {   // requires quiesced producer AND consumer
        if (storage_ == nullptr) return;
        std::memset(storage_, 0, capacity_ * channels_ * sizeof(float));
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    int channels() const { return channels_; }
    size_t capacityFrames() const { return capacity_; }
    uint64_t overflowEvents() const {
        return overflowEvents_.load(std::memory_order_relaxed);
    }

    // ---- producer side (single writer) -------------------------------------
    size_t writable() const {
        const uint64_t h = head_.load(std::memory_order_relaxed);
        const uint64_t t = tail_.load(std::memory_order_acquire);
        return capacity_ - static_cast<size_t>(h - t);
    }

    // Full interleaved write; false (counting one overflow event) if the
    // available space is smaller than `frames`.
    bool write(const float* src, size_t frames) {
        if (src == nullptr || frames == 0) return true;
        if (frames > writable()) {
            overflowEvents_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        const uint64_t h = head_.load(std::memory_order_relaxed);
        const size_t pos = static_cast<size_t>(h) & mask_;
        const size_t first = std::min(frames, capacity_ - pos);
        std::memcpy(storage_ + pos * channels_, src, first * channels_ * sizeof(float));
        if (frames > first) {
            std::memcpy(storage_, src + first * channels_,
                        (frames - first) * channels_ * sizeof(float));
        }
        head_.store(h + frames, std::memory_order_release);
        return true;
    }

    // ---- consumer side (single reader) -------------------------------------
    size_t readable() const {
        const uint64_t t = tail_.load(std::memory_order_relaxed);
        const uint64_t h = head_.load(std::memory_order_acquire);
        return static_cast<size_t>(h - t);
    }

    // Read up to `frames`; returns the frame count actually read.
    size_t read(float* dst, size_t frames) {
        if (dst == nullptr || frames == 0) return 0;
        const size_t avail = readable();
        const size_t n = std::min(frames, avail);
        if (n == 0) return 0;
        const uint64_t t = tail_.load(std::memory_order_relaxed);
        const size_t pos = static_cast<size_t>(t) & mask_;
        const size_t first = std::min(n, capacity_ - pos);
        std::memcpy(dst, storage_ + pos * channels_, first * channels_ * sizeof(float));
        if (n > first) {
            std::memcpy(dst + first * channels_, storage_,
                        (n - first) * channels_ * sizeof(float));
        }
        tail_.store(t + n, std::memory_order_release);
        return n;
    }

    // Non-destructive copy of up to `frames` (mixer staging / tests).
    size_t peek(float* dst, size_t frames) const {
        if (dst == nullptr || frames == 0) return 0;
        const size_t avail = readable();
        const size_t n = std::min(frames, avail);
        if (n == 0) return 0;
        const uint64_t t = tail_.load(std::memory_order_relaxed);
        const size_t pos = static_cast<size_t>(t) & mask_;
        const size_t first = std::min(n, capacity_ - pos);
        std::memcpy(dst, storage_ + pos * channels_, first * channels_ * sizeof(float));
        if (n > first) {
            std::memcpy(dst + first * channels_, storage_,
                        (n - first) * channels_ * sizeof(float));
        }
        return n;
    }

    // ---- mixing helper (consumer side) -------------------------------------
    // dst[i] += gain * ring[i] over up to `frames`. Saturated accumulation is
    // the CALLER's job — PeerMixPool applies its guard after summing all
    // peers. Returns the frame count actually contributed.
    size_t accumulate(float* dst, size_t frames, float gain) {
        const size_t avail = readable();
        const size_t n = std::min(frames, avail);
        if (n == 0) return 0;
        const uint64_t t = tail_.load(std::memory_order_relaxed);
        size_t done = 0;
        while (done < n) {
            const size_t pos = static_cast<size_t>(t + done) & mask_;
            const size_t seg = std::min(n - done, capacity_ - pos);
            const float* src = storage_ + pos * channels_;
            float* d = dst + done * channels_;
            const size_t floats = seg * channels_;
#if STREAMIFY_HAVE_NEON
            if (gain == 1.0f) {
                for (size_t i = 0; i + 4 <= floats; i += 4) {
                    const float32x4_t a = vld1q_f32(d + i);
                    const float32x4_t b = vld1q_f32(src + i);
                    vst1q_f32(d + i, vaddq_f32(a, b));
                }
                for (size_t i = floats & ~size_t(3); i < floats; ++i) d[i] += src[i];
            } else {
                const float32x4_t g = vdupq_n_f32(gain);
                for (size_t i = 0; i + 4 <= floats; i += 4) {
                    const float32x4_t b = vld1q_f32(src + i);
                    vst1q_f32(d + i, streamify_fma_f32(vld1q_f32(d + i), g, b));
                }
                for (size_t i = floats & ~size_t(3); i < floats; ++i) d[i] += gain * src[i];
            }
#else
            if (gain == 1.0f) {
                for (size_t i = 0; i < floats; ++i) d[i] += src[i];
            } else {
                for (size_t i = 0; i < floats; ++i) d[i] += gain * src[i];
            }
#endif
            done += seg;
        }
        tail_.store(t + n, std::memory_order_release);
        return n;
    }

private:
    int channels_ = 0;
    size_t capacity_ = 0;      // power-of-two frames
    size_t mask_ = 0;
    float* storage_ = nullptr;

    alignas(64) std::atomic<uint64_t> head_{0};   // producer-owned
    alignas(64) std::atomic<uint64_t> tail_{0};   // consumer-owned
    std::atomic<uint64_t> overflowEvents_{0};     // producer-written, relaxed
};

}  // namespace streamify::mix

#endif  // STREAMIFY_AUDIO_RING_BUFFER_H