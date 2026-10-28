#ifndef STREAMIFY_ALSA_RENDER_CORE_H
#define STREAMIFY_ALSA_RENDER_CORE_H
// ============================================================================
//  alsa_render_core.h — Phase 4b Linux ALSA fallback driver: the
//  real-time write-window core (native/include/alsa_render_core.h —
//  MP-HAL09 fallback lane)
// ============================================================================
//
//  The RT-critical half of the ALSA fallback writer thread
//  (native/audio/pipewire_driver.cpp — the `snd_pcm_writei` fallback used
//  when the PipeWire daemon/libpipewire is unavailable), factored so it is:
//
//    * HOST-TESTABLE — no ALSA handles/types cross this header: the driver
//      passes plain integers in (frame counts, destination pointer,
//      delay/position counts, monotonic ns), so every CI lane (including
//      the Windows one) compiles and audits the exact code the Linux
//      fallback runs (same discipline as the Phase 4a CoreAudioRenderCore
//      and the Phase 4b WASAPI/PipeWire cores).
//    * REAL-TIME SAFE BY CONSTRUCTION — the per-window path performs ZERO
//      dynamic allocations, takes ZERO locks and dereferences ONE
//      atomically-published raw sink pointer. It pulls interleaved f32
//      stereo frames straight out of the hardened `AudioSink` SPSC ring
//      (ReadFramesF32 — the canonical Phase-3 pull edge) and zero-fills any
//      starved tail; the DAC clock sample is published through a
//      single-writer seqlock.
//
//  ALSA PUSH MODEL vs the callback HALs (why a separate core exists)
//  ------------------------------------------------------------------
//  ALSA `snd_pcm_writei` is a PUSH edge, not a callback: a dedicated writer
//  thread fills period-sized windows and hands them to the device (blocking
//      write; MMAP transfer reuses the same window/pull discipline with a
//      user-space window instead). The data plane is therefore the same
//      pull-from-sink discipline as the callback HALs — the writer thread
//      IS the simulated callback:
//
//      PullWindow(frames, dst) -> snd_pcm_writei(pcm, dst, frames)
//
//  The DAC presentation clock comes from snd_pcm_delay: at a monotonic
//  instant `now` the device reports `delay_frames` still buffered in the
//  device+driver pipeline. The CONSISTENT presentation pair is
//
//      presented = total_submitted - delay     (frames the DAC has
//                                              already played at `now`)
//
//  — the classic ALSA position estimate. `presented` advances at exactly
//  the stream rate per monotonic second (device position minus a slowly
//  varying buffer level), which is what the DacClockInterpolator needs:
//  its extrapolation est(t) = F_last + (t - T_last) * rate / 1e9 only
//  depends on the (F, T) ADVANCE RATE, so the residual buffer-level noise
//  (µs-scale) is invisible at the <5 ms acceptance bound.
//
//  CLOCK SEMANTICS (drives DacPresentationClock — master plan invariant 3)
//  ------------------------------------------------------------------
//      dac_frame_position = PresentedFrames(total_submitted, delay)
//      sample_rate        = stream rate (Hz, cached at bind)
//      monotonic_ns       = CLOCK_MONOTONIC ns captured with the delay read
//
//  and a control thread drains it (`LatestClock`) to feed
//  `StreamifyEngine::record_dac_clock` through the UniFFI contract —
//  never from the writer thread's window loop.
//
//  LIFETIME CONTRACT (identical to the other cores)
//  ---------------------------------------------
//  `Bind` stores a RAW pointer the writer thread dereferences. `Unbind`
//  MUST be called (nulling the atomic) BEFORE the sink's last owner is
//  released, and the driver must join the writer thread after Unbind. An
//  in-flight window after Unbind observes the null pointer and renders
//  silence into the window (the device still gets its period).
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
struct AlsaClockSample {
    uint64_t frames = 0;   // total_submitted - device delay (played frames)
    uint32_t rate = 48000; // stream rate (Hz), cached at bind
    uint64_t host_ns = 0;  // CLOCK_MONOTONIC ns paired with the delay read
    uint64_t sequence = 0; // publication sequence (increments per sample)
};

// The real-time write-window core for the ALSA fallback driver. One
// instance per live audio stream; all methods are noexcept and the window
// path is wait-free.
class AlsaRenderCore {
public:
    // ---- control thread ------------------------------------------------------
    void Bind(sink::AudioSink* sink, uint32_t stream_rate) noexcept {
        rate_.store(stream_rate, std::memory_order_relaxed);
        sink_.store(sink, std::memory_order_release);
    }

    void Unbind() noexcept { sink_.store(nullptr, std::memory_order_release); }

    // Drain the latest clock sample (retry-on-tear seqlock read).
    // Returns false when nothing has been published yet.
    bool LatestClock(AlsaClockSample* out) const noexcept {
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
        return false;  // writer saturating the reader: back off
    }

    // ---- writer thread (one period window per iteration) -----------------------
    // Pull `frames` INTERLEAVED f32 stereo frames into `dst` (the window
    // handed to snd_pcm_writei — interleaved f32 is exactly the packed
    // layout ReadFramesF32 produces). Zero-fills any starved tail (digital
    // silence; the device clock keeps advancing through it). Returns the
    // number of REAL frames pulled. No allocation, no locks, no syscalls —
    // audited by AllocGuard on the host lanes.
    uint32_t PullWindow(uint32_t frames, float* dst) noexcept {
        window_count_.fetch_add(1, std::memory_order_relaxed);
        sink::AudioSink* s = sink_.load(std::memory_order_acquire);
        uint32_t got = 0;
        if (s != nullptr && dst != nullptr && frames > 0) {
            const size_t pulled = s->ReadFramesF32(dst, frames);
            got = static_cast<uint32_t>(pulled);
            if (got < frames) {
                // Underrun: zero-fill the starved tail of the window.
                underrun_windows_.fetch_add(1, std::memory_order_relaxed);
                starved_frames_.fetch_add(frames - got,
                                          std::memory_order_relaxed);
                const size_t floats =
                    static_cast<size_t>(frames - got) * kChannels;
                std::memset(dst + got * kChannels, 0,
                            floats * sizeof(float));
            }
        } else if (frames > 0) {
            // Unbound (teardown raced a live window) or degenerate args:
            // digital silence for the whole window.
            underrun_windows_.fetch_add(1, std::memory_order_relaxed);
            starved_frames_.fetch_add(frames, std::memory_order_relaxed);
            if (dst != nullptr) {
                std::memset(dst, 0,
                            static_cast<size_t>(frames) * kChannels *
                                sizeof(float));
            }
        }
        return got;
    }

    // Publish one CONSISTENT (presented, now) sample: single-writer
    // seqlock (the writer thread is the only writer; it samples
    // snd_pcm_delay + clock_gettime back-to-back and calls this with the
    // raw pair already converted — see PresentedFrames).
    void PublishClock(uint64_t presented_frames,
                      uint64_t now_ns) noexcept {
        const uint64_t seq = seq_.load(std::memory_order_relaxed);
        seq_.store(seq + 1, std::memory_order_relaxed);  // odd: writing
        std::atomic_thread_fence(std::memory_order_release);
        clock_frames_.store(presented_frames,
                            std::memory_order_relaxed);
        clock_host_ns_.store(now_ns, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        seq_.store(seq + 2, std::memory_order_relaxed);  // even: stable
        clock_publications_.fetch_add(1, std::memory_order_relaxed);
    }

    // ---- pure helper (host-testable; no state) ---------------------------------
    // The classic ALSA presentation estimate: frames actually played by
    // the DAC at the instant the delay was sampled. Saturating: a corrupt
    // delay larger than the submitted total clamps to zero (never a
    // position regression into underflow).
    static uint64_t PresentedFrames(uint64_t total_submitted,
                                    uint64_t device_delay_frames) noexcept {
        if (device_delay_frames >= total_submitted) return 0;
        return total_submitted - device_delay_frames;
    }

    // ---- telemetry (any thread; relaxed counters) ------------------------------
    uint64_t window_count() const noexcept {
        return window_count_.load(std::memory_order_relaxed);
    }
    uint64_t underrun_windows() const noexcept {
        return underrun_windows_.load(std::memory_order_relaxed);
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

private:
    // ---- published state --------------------------------------------------------
    std::atomic<sink::AudioSink*> sink_{nullptr};
    std::atomic<uint32_t> rate_{48000};

    // Seqlock-protected clock sample (single writer = writer thread).
    alignas(64) std::atomic<uint64_t> seq_{0};
    alignas(64) std::atomic<uint64_t> clock_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_host_ns_{0};

    // Telemetry counters.
    alignas(64) std::atomic<uint64_t> window_count_{0};
    alignas(64) std::atomic<uint64_t> underrun_windows_{0};
    alignas(64) std::atomic<uint64_t> starved_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_publications_{0};
};

}  // namespace hal
}  // namespace streamify

#endif  // STREAMIFY_ALSA_RENDER_CORE_H
