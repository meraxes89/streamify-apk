#ifndef STREAMIFY_PEER_MIX_POOL_H
#define STREAMIFY_PEER_MIX_POOL_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "AudioRingBuffer.h"

// ============================================================================
//  PeerMixPool — 32-peer audio routing & mixing pool (Gap #11, Phase 1)
// ============================================================================
//
//  One SPSC ring per peer (producer = that peer's network/decoder thread,
//  consumer = the single mixer thread); the pool as a whole is an SPMC
//  fan-in with the mixer as the sole consumer. All storage is allocated at
//  init() — the mix() hot path performs ZERO heap allocation.
//
//  MIXING CONTRACT (per mix() call):
//    * every attached peer contributes min(readable, outFrames) frames
//      scaled by its per-peer gain (NEON FMA along the sample axis);
//    * starved peers contribute nothing for their remainder and count an
//      underrun (the sync layer, not the pool, is responsible for alignment);
//    * after summing, the result passes the saturation guard:
//          identity below kSatKnee (0.90),
//          C1 tanh shoulder saturating to +-1.0 — "saturation clipping
//          protection" for the 32-stream worst case (32 in-phase full-scale
//          peers would otherwise sum to 32.0).
//
//  THREADING: attach/detach/setPeerGain run on control threads (CAS /
//  relaxed atomics). peerWrite is per-peer single-producer. mix() is
//  single-consumer. All paths are allocation-free and lock-free.
// ============================================================================

namespace streamify::mix {

class PeerMixPool {
public:
    static constexpr int kMaxPeers = 32;

    struct MixStats {
        size_t framesMixed = 0;        // frames emitted into `out`
        int activePeers = 0;          // peers that contributed > 0 frames
        int underruns = 0;            // peers that provided < outFrames
        uint64_t totalUnderruns = 0;  // lifetime
        uint64_t overflowEvents = 0;  // lifetime producer-side overflows
    };

    PeerMixPool() = default;
    ~PeerMixPool() = default;

    PeerMixPool(const PeerMixPool&) = delete;
    PeerMixPool& operator=(const PeerMixPool&) = delete;

    bool init(int sampleRate, int channels, size_t ringFramesPerPeer) {
        sampleRate_ = sampleRate;
        channels_ = channels < 1 ? 1 : (channels > 8 ? 8 : channels);
        initialized_ = true;
        for (int i = 0; i < kMaxPeers; ++i) {
            if (!peers_[i].ring.init(channels_, ringFramesPerPeer)) {
                initialized_ = false;
            }
        }
        return initialized_;
    }

    // ---- membership (control threads) ---------------------------------------
    // Returns the slot index, or -1 when all 32 are taken.
    int attachPeer() {
        for (int i = 0; i < kMaxPeers; ++i) {
            bool expected = false;
            if (peers_[i].attached.load(std::memory_order_relaxed)) continue;
            if (peers_[i].attached.compare_exchange_strong(
                    expected, true, std::memory_order_acq_rel)) {
                peers_[i].ring.reset();
                peers_[i].gain.store(1.0f, std::memory_order_relaxed);
                peers_[i].underruns.store(0, std::memory_order_relaxed);
                return i;
            }
        }
        return -1;
    }

    void detachPeer(int slot) {
        if (slot < 0 || slot >= kMaxPeers) return;
        peers_[slot].attached.store(false, std::memory_order_release);
    }

    bool peerAttached(int slot) const {
        if (slot < 0 || slot >= kMaxPeers) return false;
        return peers_[slot].attached.load(std::memory_order_acquire);
    }

    int activePeers() const {
        int n = 0;
        for (int i = 0; i < kMaxPeers; ++i) {
            if (peers_[i].attached.load(std::memory_order_acquire)) ++n;
        }
        return n;
    }

    // ---- per-peer gain (control threads) -------------------------------------
    void setPeerGain(int slot, float gain) {
        if (slot < 0 || slot >= kMaxPeers) return;
        if (!(gain >= 0.0f)) gain = 0.0f;          // NaN / negative -> silence
        if (gain > 8.0f) gain = 8.0f;
        peers_[slot].gain.store(gain, std::memory_order_relaxed);
    }
    float peerGain(int slot) const {
        if (slot < 0 || slot >= kMaxPeers) return 0.0f;
        return peers_[slot].gain.load(std::memory_order_relaxed);
    }

    // ---- per-peer producer side ----------------------------------------------
    size_t peerWritable(int slot) const {
        if (slot < 0 || slot >= kMaxPeers) return 0;
        return peers_[slot].ring.writable();
    }
    bool peerWrite(int slot, const float* interleaved, size_t frames) {
        if (slot < 0 || slot >= kMaxPeers) return false;
        return peers_[slot].ring.write(interleaved, frames);
    }
    // Mix-thread / diagnostics view of a peer's fill state.
    size_t peerReadable(int slot) const {
        if (slot < 0 || slot >= kMaxPeers) return 0;
        return peers_[slot].ring.readable();
    }

    // ---- mixer side (single consumer) -----------------------------------------
    // Mixes exactly `outFrames` frames into `out` (overwrite semantics: the
    // whole output region is written — zero where no peer contributed).
    // Returns framesMixed (== outFrames) and fills `stats` when non-null.
    size_t mix(float* out, size_t outFrames, MixStats* stats) {
        if (out == nullptr || outFrames == 0) return 0;
        const size_t floats = outFrames * channels_;
        std::memset(out, 0, floats * sizeof(float));

        if (stats) {
            stats->framesMixed = 0;
            stats->activePeers = 0;
            stats->underruns = 0;
            stats->overflowEvents = 0;
        }

        for (int i = 0; i < kMaxPeers; ++i) {
            if (!peers_[i].attached.load(std::memory_order_acquire)) continue;
            const float g = peers_[i].gain.load(std::memory_order_relaxed);
            const size_t got = peers_[i].ring.accumulate(out, outFrames, g);
            if (stats) {
                if (got > 0) ++stats->activePeers;
                if (got < outFrames) {
                    ++stats->underruns;
                    peers_[i].underruns.fetch_add(1, std::memory_order_relaxed);
                    stats->totalUnderruns = lifetimeUnderruns();
                }
                stats->overflowEvents += peers_[i].ring.overflowEvents();
            } else if (got < outFrames) {
                peers_[i].underruns.fetch_add(1, std::memory_order_relaxed);
            }
        }

        saturate(out, floats);
        if (stats) stats->framesMixed = outFrames;
        return outFrames;
    }

    uint64_t peerUnderruns(int slot) const {
        if (slot < 0 || slot >= kMaxPeers) return 0;
        return peers_[slot].underruns.load(std::memory_order_relaxed);
    }

    // ---- saturation guard -----------------------------------------------------
    // Identity below kSatKnee; C1 tanh shoulder to +-1.0 above it.
    static constexpr float kSatKnee = 0.90f;
    static float saturate(float x) {
        if (!std::isfinite(x)) return 0.0f;
        const float ax = x < 0.0f ? -x : x;
        if (ax <= kSatKnee) return x;
        const float span = 1.0f - kSatKnee;
        const float sat = kSatKnee + span * std::tanh((ax - kSatKnee) / span);
        return x < 0.0f ? -sat : sat;
    }

    int channels() const { return channels_; }
    int sampleRate() const { return sampleRate_; }
    bool initialized() const { return initialized_; }

private:
    struct PeerSlot {
        AudioRingBuffer ring;                 // 64-B aligned, per-peer SPSC
        std::atomic<bool> attached{false};
        std::atomic<float> gain{1.0f};
        // Relaxed atomic (audit #22 item 9): written on the mix thread,
        // read cross-thread by peerUnderruns()/lifetimeUnderruns().
        std::atomic<uint64_t> underruns{0};   // consumer-side (mix thread)
    };

    uint64_t lifetimeUnderruns() const {
        uint64_t total = 0;
        for (int i = 0; i < kMaxPeers; ++i)
            total += peers_[i].underruns.load(std::memory_order_relaxed);
        return total;
    }

    void saturate(float* p, size_t floats) const {
        size_t i = 0;
#if STREAMIFY_HAVE_NEON
        // Fast path: if the whole mix stays below the knee AND carries no
        // NaN, skip the scalar pass entirely. (NEON fmax is maxNum: it
        // ignores NaNs, so the sweep is tracked separately via vceq(v,v),
        // which is all-ones for finite lanes and zero for NaN lanes.)
        float32x4_t vmax = vdupq_n_f32(0.0f);
        uint32x4_t finite = vdupq_n_u32(0);
        size_t j = 0;
        for (; j + 4 <= floats; j += 4) {
            const float32x4_t v = vld1q_f32(p + j);
            vmax = vmaxq_f32(vmax, vabsq_f32(v));
            finite = vorrq_u32(finite, vceqq_f32(v, v));
        }
        float m;
#if defined(__aarch64__) || defined(_M_ARM64)
        m = vmaxvq_f32(vmax);
#else   // armeabi-v7a: no horizontal-max instruction — extract lanes.
        m = std::max(std::max(vgetq_lane_f32(vmax, 0), vgetq_lane_f32(vmax, 1)),
                     std::max(vgetq_lane_f32(vmax, 2), vgetq_lane_f32(vmax, 3)));
#endif
        for (; j < floats; ++j) {
            const float v = p[j];
            m = std::max(m, std::fabs(v));
            if (v == v) finite = vorrq_u32(finite, vdupq_n_u32(~0u));
        }
        const uint32x4_t notFinite = vmvnq_u32(finite);
        if (m <= kSatKnee &&
            (notFinite[0] | notFinite[1] | notFinite[2] | notFinite[3]) == 0) {
            return;   // entire block finite and below the knee: identity
        }
#endif
        for (; i < floats; ++i) p[i] = saturate(p[i]);
    }

    PeerSlot peers_[kMaxPeers];
    int channels_ = 2;
    int sampleRate_ = 48000;
    bool initialized_ = false;
};

}  // namespace streamify::mix

#endif  // STREAMIFY_PEER_MIX_POOL_H