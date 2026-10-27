#ifndef STREAMIFY_COREAUDIO_RENDER_CORE_H
#define STREAMIFY_COREAUDIO_RENDER_CORE_H
// ============================================================================
//  coreaudio_render_core.h — Phase 4a Apple CoreAudio pull adapter: the
//  real-time render core (native/include/coreaudio_render_core.h — MP-HAL04)
// ============================================================================
//
//  The RT-critical half of the CoreAudio (macOS AUHAL / iOS RemoteIO)
//  AURenderCallback, factored so it is:
//
//    * HOST-TESTABLE — no AudioUnit / CoreAudio / mach types cross this
//      header: the adapter passes plain integers in (sample-frame count,
//      destination pointer, mSampleTime, mHostTime-already-in-ns), so the
//      Linux CI lanes compile and audit the exact code the Apple HAL runs.
//    * REAL-TIME SAFE BY CONSTRUCTION — the render path performs ZERO
//      dynamic allocations, takes ZERO locks, makes ZERO system calls and
//      touches ZERO Objective-C/Swift runtime state. It dereferences one
//      atomically-published raw sink pointer, pulls interleaved f32 stereo
//      frames straight out of the hardened `AudioSink` SPSC ring
//      (ReadFramesF32 — the canonical Phase-3 pull edge), zero-fills any
//      starved tail, and publishes the DAC clock sample through a
//      single-writer seqlock (two relaxed atomic stores + one release
//      store; control-thread readers retry on torn reads).
//
//  CLOCK SEMANTICS (drives DacPresentationClock — master plan invariant 3)
//  --------------------------------------------------------------------
//  CoreAudio hands the render callback an `AudioTimeStamp` whose
//  `mSampleTime` is the device sample-frame counter of the FIRST frame of
//  the quantum being rendered and whose `mHostTime` is the mach host time
//  associated with that same instant. The pair is CONSISTENT (both refer
//  to one presentation moment), which is exactly what the VirtualHAL
//  model (virtual_hal.h) proves interpolable to <5 ms. This core records:
//
//      frames  = (uint64_t)mSampleTime       // device frame counter
//      host_ns = mach_to_ns(mHostTime)       // via the timebase cached
//                                            // at adapter START (never in
//                                            // the audio thread)
//      rate    = device nominal rate         // cached at bind time
//
//  and a control thread drains it (`LatestClock`) to feed
//  `StreamifyEngine::record_dac_clock` through the UniFFI contract —
//  never from the audio thread.
//
//  LIFETIME CONTRACT
//  -----------------
//  `Bind` stores a RAW pointer the render callback dereferences. The sink
//  is kept alive by the adapter's shared_ptr; `Unbind` MUST be called
//  (nulling the atomic) BEFORE the sink's last owner is released, and the
//  adapter must stop/dispose the AudioUnit after Unbind. An in-flight
//  render after Unbind observes the null pointer and emits silence.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "audio_sink_ringbuffer.h"

namespace streamify {
namespace hal {

// One drained DAC clock sample (the native mirror of the UniFFI
// DacPresentationClock wire image + a change-detection sequence).
struct CoreAudioClockSample {
    uint64_t frames = 0;   // AudioTimeStamp.mSampleTime (device frames)
    uint32_t rate = 48000; // device nominal rate (Hz)
    uint64_t host_ns = 0;  // mHostTime converted to monotonic nanoseconds
    uint64_t sequence = 0; // publication sequence (increments per render)
};

// mach_absolute_time ticks -> nanoseconds. The timebase info is queried
// ONCE at adapter start (a mach trap — control thread only); the audio
// thread only ever runs this pure-arithmetic conversion. Uses 128-bit
// intermediate math when available (Apple clang always provides it); the
// double fallback is exact for every realistic boot-uptime magnitude.
inline uint64_t MachTimeToNs(uint64_t mach_ticks, uint32_t timebase_numer,
                             uint32_t timebase_denom) {
    if (timebase_denom == 0) return mach_ticks;  // defensive: bad timebase
    if (timebase_numer == timebase_denom) return mach_ticks;  // fast path
#if defined(__SIZEOF_INT128__)
    const unsigned __int128 ns =
        (static_cast<unsigned __int128>(mach_ticks) * timebase_numer) /
        timebase_denom;
    return static_cast<uint64_t>(ns);
#else
    return static_cast<uint64_t>(
        (static_cast<double>(mach_ticks) * static_cast<double>(timebase_numer)) /
        static_cast<double>(timebase_denom));
#endif
}

// The real-time render core. One instance per live audio stream; all
// methods are noexcept and the render path is wait-free.
class CoreAudioRenderCore {
public:
    // ---- control thread ------------------------------------------------------
    // Publish the sink the callback pulls from and cache the device rate.
    // `sink` must outlive every render until Unbind() (see header contract).
    void Bind(sink::AudioSink* sink, uint32_t device_rate) noexcept {
        rate_.store(device_rate, std::memory_order_relaxed);
        sink_.store(sink, std::memory_order_release);
    }

    // Retract the sink pointer BEFORE the sink's last owner goes away.
    void Unbind() noexcept { sink_.store(nullptr, std::memory_order_release); }

    // Drain the latest clock sample (retry-on-tear seqlock read).
    // Returns false when no render has published a sample yet.
    bool LatestClock(CoreAudioClockSample* out) const noexcept {
        if (out == nullptr) return false;
        for (int spins = 0; spins < 64; ++spins) {
            const uint64_t seq_before =
                seq_.load(std::memory_order_acquire);
            if (seq_before & 1u) continue;  // writer mid-publication
            const uint64_t frames =
                clock_frames_.load(std::memory_order_relaxed);
            const uint64_t host_ns =
                clock_host_ns_.load(std::memory_order_relaxed);
            const uint32_t rate = rate_.load(std::memory_order_relaxed);
            const uint64_t seq_after =
                seq_.load(std::memory_order_acquire);
            if (seq_before != seq_after) continue;  // torn: retry
            if (seq_before == 0) return false;      // never published
            out->frames = frames;
            out->host_ns = host_ns;
            out->rate = rate;
            out->sequence = seq_before / 2u;
            return true;
        }
        return false;  // renderer saturating the reader: back off
    }

    // ---- audio thread (AURenderCallback body) ---------------------------------
    // Render `nframes` INTERLEAVED f32 stereo frames into `dst` (the
    // ioData buffer — CoreAudio with a packed interleaved client format),
    // pulling straight from the hardened ring and zero-filling starved
    // tails. The bound sink MUST be the canonical 2-channel geometry
    // (kChannels). Records the clock sample derived from the render
    // timestamp (`publish_clock` is false only on the adapter's
    // degenerate-timestamp path: audio keeps flowing, the clock sample
    // is simply not advanced). No allocation, no locks, no syscalls —
    // audited by AllocGuard on the host test lane.
    void Render(uint32_t nframes, float* dst, uint64_t sample_time_frames,
                uint64_t host_time_ns,
                bool publish_clock = true) noexcept {
        callback_count_.fetch_add(1, std::memory_order_relaxed);
        sink::AudioSink* s = sink_.load(std::memory_order_acquire);
        size_t got = 0;
        if (s != nullptr && dst != nullptr && nframes > 0) {
            got = s->ReadFramesF32(dst, nframes);
            if (got < nframes) {
                // Underrun: the DAC presents digital silence for the
                // starved tail (zero-fill the remainder of the quantum).
                underrun_callbacks_.fetch_add(1, std::memory_order_relaxed);
                const size_t floats =
                    static_cast<size_t>(nframes - got) * kChannels;
                std::memset(dst + got * kChannels, 0, floats * sizeof(float));
            }
        } else if (dst != nullptr && nframes > 0) {
            // Unbound (teardown raced a live callback): digital silence.
            underrun_callbacks_.fetch_add(1, std::memory_order_relaxed);
            std::memset(dst, 0,
                        static_cast<size_t>(nframes) * kChannels *
                            sizeof(float));
        }

        // Publish the consistent clock sample: single-writer seqlock.
        // (Skipped only on the adapter's degenerate-timestamp path.)
        if (!publish_clock) return;
        const uint64_t seq = seq_.load(std::memory_order_relaxed);
        seq_.store(seq + 1, std::memory_order_relaxed);  // odd: writing
        // Release fence pairs with the reader's acquire loads so the
        // payload stores below cannot be reordered before the odd mark.
        std::atomic_thread_fence(std::memory_order_release);
        clock_frames_.store(sample_time_frames, std::memory_order_relaxed);
        clock_host_ns_.store(host_time_ns, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        seq_.store(seq + 2, std::memory_order_relaxed);  // even: stable
    }

    // ---- telemetry (any thread; relaxed counters) ------------------------------
    uint64_t callback_count() const noexcept {
        return callback_count_.load(std::memory_order_relaxed);
    }
    uint64_t underrun_callbacks() const noexcept {
        return underrun_callbacks_.load(std::memory_order_relaxed);
    }
    bool bound() const noexcept {
        return sink_.load(std::memory_order_acquire) != nullptr;
    }

    static constexpr uint32_t kChannels = 2;  // canonical stereo pipeline

private:
    // ---- published state --------------------------------------------------------
    std::atomic<sink::AudioSink*> sink_{nullptr};
    std::atomic<uint32_t> rate_{48000};

    // Seqlock-protected clock sample (single writer = audio thread).
    alignas(64) std::atomic<uint64_t> seq_{0};
    alignas(64) std::atomic<uint64_t> clock_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_host_ns_{0};

    // Telemetry counters.
    alignas(64) std::atomic<uint64_t> callback_count_{0};
    alignas(64) std::atomic<uint64_t> underrun_callbacks_{0};
};

}  // namespace hal
}  // namespace streamify

#endif  // STREAMIFY_COREAUDIO_RENDER_CORE_H
