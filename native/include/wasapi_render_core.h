#ifndef STREAMIFY_WASAPI_RENDER_CORE_H
#define STREAMIFY_WASAPI_RENDER_CORE_H
// ============================================================================
//  wasapi_render_core.h — Phase 4b Windows WASAPI pull adapter: the
//  real-time render core (native/include/wasapi_render_core.h — MP-HAL08)
// ============================================================================
//
//  The RT-critical half of the Windows WASAPI event-driven render thread
//  (native/audio/wasapi_pull_adapter.cpp), factored so it is:
//
//    * HOST-TESTABLE — no COM / IAudioClient / HANDLE / REFERENCE_TIME
//      types cross this header: the adapter passes plain integers in
//      (frame counts, destination pointer, IAudioClock ticks, QPC
//      100-nanosecond units), so the Linux/macOS CI lanes compile and
//      audit the exact code the Windows HAL runs (same discipline as
//      the Phase 4a CoreAudioRenderCore).
//    * REAL-TIME SAFE BY CONSTRUCTION — the pull path performs ZERO
//      dynamic allocations, takes ZERO locks, makes ZERO system calls
//      and dereferences ONE atomically-published raw sink pointer. It
//      pulls interleaved f32 stereo frames straight out of the hardened
//      `AudioSink` SPSC ring (ReadFramesF32 — the canonical Phase-3 pull
//      edge), zero-fills any starved tail, and the DAC clock sample is
//      published through a single-writer seqlock (two relaxed atomic
//      stores + one release store; control-thread readers retry on torn
//      reads).
//
//  WASAPI PULL MODEL vs CoreAudio (why a separate core exists)
//  ------------------------------------------------------------------
//  CoreAudio pushes a FIXED quantum per callback and hands the callback
//  the (mSampleTime, mHostTime) pair for that quantum. WASAPI is
//  event-driven with a VARIABLE quantum: the render thread wakes on the
//  buffer event, asks the endpoint how much room is left
//  (IAudioClient::GetCurrentPadding) and renders
//
//      frames = buffer_frame_count - current_padding          (1)
//
//  frames — anywhere between 0 and the whole endpoint buffer. The DAC
//  presentation clock is NOT part of the render timestamp: it is sampled
//  separately from IAudioClock::GetPosition, which reports
//
//      position  — device position in ticks (IAudioClock::
//                  GetFrequency ticks/second; ticks == frames whenever
//                  frequency == the stream rate — the common case)
//      qpc       — QueryPerformanceCounter value, in 100 ns units,
//                  captured at the same instant as `position`
//
//  a CONSISTENT (now-referenced) pair. This core therefore factors the
//  pull edge (1) + the clock ingestion as two independently testable
//  halves:
//
//      PullFrames(frames, dst)              — the data plane
//      PublishClock(device_frames, host_ns) — the clock plane (seqlock)
//
//  CLOCK SEMANTICS (drives DacPresentationClock — master plan invariant 3)
//  ------------------------------------------------------------------
//  The adapter converts the IAudioClock pair to the canonical triple:
//
//      dac_frame_position = ScaleDevicePosition(position, frequency,
//                                               stream_rate)
//      sample_rate        = stream_rate (cached at bind)
//      monotonic_ns       = Qpc100ToNs(qpc)     (QPC * 100, saturating)
//
//  and a control thread drains it (`LatestClock`) to feed
//  `StreamifyEngine::record_dac_clock` through the UniFFI contract —
//  never from the render thread.
//
//  LIFETIME CONTRACT (identical to CoreAudioRenderCore)
//  ----------------------------------------------------
//  `Bind` stores a RAW pointer the render thread dereferences. The sink
//  is kept alive by the adapter's shared_ptr; `Unbind` MUST be called
//  (nulling the atomic) BEFORE the sink's last owner is released, and
//  the adapter must stop the render thread after Unbind. An in-flight
//  pull after Unbind observes the null pointer and renders silence.
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
struct WasapiClockSample {
    uint64_t frames = 0;   // IAudioClock position scaled to stream frames
    uint32_t rate = 48000; // stream rate (Hz), cached at bind
    uint64_t host_ns = 0;  // QPC (100 ns units) converted to monotonic ns
    uint64_t sequence = 0; // publication sequence (increments per sample)
};

// The real-time render core for the WASAPI event-driven pull thread.
// One instance per live audio stream; all methods are noexcept and the
// pull path is wait-free.
class WasapiRenderCore {
public:
    // ---- control thread ------------------------------------------------------
    // Publish the sink the render thread pulls from and cache the stream
    // rate. `sink` must outlive every pull until Unbind() (see above).
    void Bind(sink::AudioSink* sink, uint32_t stream_rate) noexcept {
        rate_.store(stream_rate, std::memory_order_relaxed);
        sink_.store(sink, std::memory_order_release);
    }

    // Retract the sink pointer BEFORE the sink's last owner goes away.
    void Unbind() noexcept { sink_.store(nullptr, std::memory_order_release); }

    // Drain the latest clock sample (retry-on-tear seqlock read).
    // Returns false when nothing has been published yet.
    bool LatestClock(WasapiClockSample* out) const noexcept {
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
        return false;  // render thread saturating the reader: back off
    }

    // ---- render thread (event-driven pull cycle body) -------------------------
    // Pull `frames` INTERLEAVED f32 stereo frames into `dst` (the
    // IAudioRenderClient::GetBuffer window — WASAPI interleaved f32 is
    // exactly the packed layout ReadFramesF32 produces). Zero-fills any
    // starved tail (the DAC presents digital silence; the starving
    // consumer never stalls the hardware clock). Returns the number of
    // REAL frames pulled (frames minus the starved tail). No allocation,
    // no locks, no syscalls — audited by AllocGuard on the host lanes.
    uint32_t PullFrames(uint32_t frames, float* dst) noexcept {
        pull_count_.fetch_add(1, std::memory_order_relaxed);
        sink::AudioSink* s = sink_.load(std::memory_order_acquire);
        uint32_t got = 0;
        if (s != nullptr && dst != nullptr && frames > 0) {
            const size_t pulled = s->ReadFramesF32(dst, frames);
            got = static_cast<uint32_t>(pulled);
            if (got < frames) {
                // Underrun: zero-fill the starved tail of the window.
                underrun_pulls_.fetch_add(1, std::memory_order_relaxed);
                starved_frames_.fetch_add(frames - got,
                                          std::memory_order_relaxed);
                const size_t floats =
                    static_cast<size_t>(frames - got) * kChannels;
                std::memset(dst + got * kChannels, 0,
                            floats * sizeof(float));
            }
        } else if (frames > 0) {
            // Unbound (teardown raced a live pull) or degenerate args:
            // digital silence for the whole window.
            underrun_pulls_.fetch_add(1, std::memory_order_relaxed);
            starved_frames_.fetch_add(frames, std::memory_order_relaxed);
            if (dst != nullptr) {
                std::memset(dst, 0,
                            static_cast<size_t>(frames) * kChannels *
                                sizeof(float));
            }
        }
        return got;
    }

    // Publish one CONSISTENT IAudioClock-derived sample: single-writer
    // seqlock (the render thread is the only writer). Callers convert
    // the raw (position ticks, frequency, QPC) triple first via
    // ScaleDevicePosition / Qpc100ToNs below.
    void PublishClock(uint64_t device_position_frames,
                      uint64_t host_ns) noexcept {
        const uint64_t seq = seq_.load(std::memory_order_relaxed);
        seq_.store(seq + 1, std::memory_order_relaxed);  // odd: writing
        // Release fence pairs with the reader's acquire loads so the
        // payload stores below cannot be reordered before the odd mark.
        std::atomic_thread_fence(std::memory_order_release);
        clock_frames_.store(device_position_frames,
                            std::memory_order_relaxed);
        clock_host_ns_.store(host_ns, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        seq_.store(seq + 2, std::memory_order_relaxed);  // even: stable
        clock_publications_.fetch_add(1, std::memory_order_relaxed);
    }

    // ---- pure helpers (host-testable; no state) -------------------------------
    // The event-driven quantum (1): how many frames the endpoint can
    // accept this cycle. Saturating: a lying padding (>= buffer) yields
    // a no-op cycle rather than an underflow.
    static uint32_t FramesToRender(uint32_t buffer_frames,
                                   uint32_t current_padding) noexcept {
        if (current_padding >= buffer_frames) return 0;
        return buffer_frames - current_padding;
    }

    // IAudioClock::GetPosition ticks -> stream FRAMES. Ticks advance at
    // `ticks_per_second` (IAudioClock::GetFrequency); whenever the
    // frequency equals the stream rate (the common case — the position
    // counter tracks the stream timeline) this is the identity. 128-bit
    // intermediate math when available; truncation toward zero (matches
    // the documented CoreAudioRenderCore::MachTimeToNs discipline).
    static uint64_t ScaleDevicePosition(uint64_t position_ticks,
                                        uint64_t ticks_per_second,
                                        uint32_t stream_rate) noexcept {
        if (ticks_per_second == 0) return position_ticks;  // defensive
        if (ticks_per_second == stream_rate) return position_ticks;
#if defined(__SIZEOF_INT128__)
        const unsigned __int128 frames =
            (static_cast<unsigned __int128>(position_ticks) *
             stream_rate) /
            ticks_per_second;
        return static_cast<uint64_t>(frames);
#else
        return static_cast<uint64_t>(
            (static_cast<double>(position_ticks) *
             static_cast<double>(stream_rate)) /
            static_cast<double>(ticks_per_second));
#endif
    }

    // QueryPerformanceCounter is guaranteed monotonic and its raw value
    // is expressed in 100-nanosecond units (IAudioClock::GetPosition
    // documents qpcPosition as performance-counter time in 100 ns).
    // Saturating multiply: a QPC that would overflow ns clamps instead
    // of wrapping (the estimator re-baselines on the next sample).
    static uint64_t Qpc100ToNs(uint64_t qpc_100ns) noexcept {
        if (qpc_100ns > kMaxQpc100) return ~0ull;
        return qpc_100ns * 100ull;
    }

    // ---- telemetry (any thread; relaxed counters) ------------------------------
    uint64_t pull_count() const noexcept {
        return pull_count_.load(std::memory_order_relaxed);
    }
    uint64_t underrun_pulls() const noexcept {
        return underrun_pulls_.load(std::memory_order_relaxed);
    }
    uint64_t starved_frames() const noexcept {
        return starved_frames_.load(std::memory_order_relaxed);
    }
    uint64_t clock_publications() const noexcept {
        return clock_publications_.load(std::memory_order_relaxed);
    }
    bool bound() const noexcept {
        return sink_.load(std::memory_order_acquire) != nullptr;
    }

    static constexpr uint32_t kChannels = 2;  // canonical stereo pipeline
    static constexpr uint64_t kMaxQpc100 = ~0ull / 100ull;

private:
    // ---- published state --------------------------------------------------------
    std::atomic<sink::AudioSink*> sink_{nullptr};
    std::atomic<uint32_t> rate_{48000};

    // Seqlock-protected clock sample (single writer = render thread).
    alignas(64) std::atomic<uint64_t> seq_{0};
    alignas(64) std::atomic<uint64_t> clock_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_host_ns_{0};

    // Telemetry counters.
    alignas(64) std::atomic<uint64_t> pull_count_{0};
    alignas(64) std::atomic<uint64_t> underrun_pulls_{0};
    alignas(64) std::atomic<uint64_t> starved_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_publications_{0};
};

}  // namespace hal
}  // namespace streamify

#endif  // STREAMIFY_WASAPI_RENDER_CORE_H
