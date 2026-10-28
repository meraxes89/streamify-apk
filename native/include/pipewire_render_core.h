#ifndef STREAMIFY_PIPEWIRE_RENDER_CORE_H
#define STREAMIFY_PIPEWIRE_RENDER_CORE_H
// ============================================================================
//  pipewire_render_core.h — Phase 4b Linux PipeWire pull driver: the
//  real-time process-callback core (native/include/pipewire_render_core.h
//  — MP-HAL09)
// ============================================================================
//
//  The RT-critical half of the Linux PipeWire driver
//  (native/audio/pipewire_driver.cpp), factored so it is:
//
//    * HOST-TESTABLE — no pipewire/spa types cross this header: the
//      driver passes plain integers in (frame counts, destination
//      pointer, pw_time fields), so every CI lane (including the
//      Windows one) compiles and audits the exact code the Linux
//      PipeWire process callback runs (same discipline as the Phase 4a
//      CoreAudioRenderCore and the Phase 4b WasapiRenderCore).
//    * REAL-TIME SAFE BY CONSTRUCTION — the process-callback path
//      performs ZERO dynamic allocations, takes ZERO locks (the
//      pw_thread_loop lock is NEVER held in the process callback), and
//      dereferences ONE atomically-published raw sink pointer. It pulls
//      interleaved f32 stereo frames straight out of the hardened
//      `AudioSink` SPSC ring (ReadFramesF32 — the canonical Phase-3
//      pull edge) and zero-fills any starved tail; the DAC clock sample
//      is published through a single-writer seqlock.
//
//  PIPEWIRE PULL MODEL vs CoreAudio (why a separate core exists)
//  ------------------------------------------------------------------
//  A pw_stream playback callback is handed pre-allocated buffers by the
//  graph: `pw_stream_dequeue_buffer` returns a pw_buffer whose
//  spa_data[0] (SPA_DATA_MemPtr) window is up to `maxsize` bytes with a
//  negotiated `stride` (frame bytes). The callback fills the WHOLE
//  window (PW_STREAM_FLAG_RT_PROCESS: the call happens on the graph's
//  real-time thread; drivers expect one full quantum per cycle) and
//  re-queues it. The quantum varies with the graph cycle — the driver
//  must treat the offered window size as authoritative, not assume a
//  fixed period. Presentation timing is sampled separately via
//  pw_stream_get_time:
//
//      time.rate     — SpaFraction {num, denom}: the tick rate, normally
//                      the negotiated stream rate
//      time.ticks    — graph position in ticks at `now` (monotonic
//                      increasing; ticks == frames whenever rate == the
//                      stream rate)
//      time.now      — CLOCK_MONOTONIC ns at which the (ticks, delay)
//                      snapshot was captured
//
//  a CONSISTENT (now-referenced) pair, exactly like the WASAPI
//  IAudioClock pair. The core factors the two halves:
//
//      FillProcessBuffer(frames, dst)              — the data plane
//      PublishClockFromPwTime(ticks, rate, now_ns) — the clock plane
//
//  CLOCK SEMANTICS (drives DacPresentationClock — master plan invariant 3)
//  ------------------------------------------------------------------
//  The driver converts the pw_time triple to the canonical tuple:
//
//      dac_frame_position = TicksToFrames(ticks, rate.num, rate.denom,
//                                         stream_rate)
//      sample_rate        = stream rate (Hz, cached at bind; equal to
//                           rate.denom/rate.num in every negotiated stream —
//                           the scaling is defensive only)
//      monotonic_ns       = time.now (already ns; passed through)
//
//  pw_time.rate is the tick PERIOD as a fraction — canonically
//  {num:1, denom:stream_rate} ("the rate of ticks and delay is usually
//  expressed in 1/<samplerate>") — so ticks already count stream frames
//  and the conversion is the identity; a foreign graph clock rate is
//  rescaled defensively. ticks advances at exactly the graph rate per
//  monotonic second and `now` is the CLOCK_MONOTONIC ns snapshot paired
//  with it (updated every graph cycle under a seqlock in
//  pw_stream_get_time_n) — the CONSISTENT pair the VirtualHAL model
//  proves interpolable to <5 ms. A control-plane sampler thread drains
//  it (`LatestClock`) to feed `StreamifyEngine::record_dac_clock` through
//  the UniFFI contract — never from the process callback.
//
//  LIFETIME CONTRACT (identical to CoreAudioRenderCore)
//  ----------------------------------------------------
//  `Bind` stores a RAW pointer the process callback dereferences. The
//  sink is kept alive by the driver's shared_ptr; `Unbind` MUST be
//  called (nulling the atomic) BEFORE the sink's last owner is
//  released, and the driver must destroy the pw_stream after Unbind
//  (stream destruction guarantees no further process callbacks). An
//  in-flight callback after Unbind observes the null pointer and
//  renders silence.
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
struct PipeWireClockSample {
    uint64_t frames = 0;   // pw_time.ticks scaled to stream frames
    uint32_t rate = 48000; // stream rate (Hz), cached at bind
    uint64_t host_ns = 0;  // pw_time.now (CLOCK_MONOTONIC nanoseconds)
    uint64_t sequence = 0; // publication sequence (increments per sample)
};

// The real-time process-callback core for the PipeWire driver. One
// instance per live audio stream; all methods are noexcept and the
// process path is wait-free.
class PipeWireRenderCore {
public:
    // ---- control thread ------------------------------------------------------
    void Bind(sink::AudioSink* sink, uint32_t stream_rate) noexcept {
        rate_.store(stream_rate, std::memory_order_relaxed);
        sink_.store(sink, std::memory_order_release);
    }

    void Unbind() noexcept { sink_.store(nullptr, std::memory_order_release); }

    // Drain the latest clock sample (retry-on-tear seqlock read).
    // Returns false when nothing has been published yet.
    bool LatestClock(PipeWireClockSample* out) const noexcept {
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
        return false;  // callback saturating the reader: back off
    }

    // ---- process callback (pw_stream_events.process body) ----------------------
    // Fill the dequeued buffer window: `frames` INTERLEAVED f32 stereo
    // frames into `dst` (spa_data[0].data — a PipeWire MemPtr window is
    // exactly the packed layout ReadFramesF32 produces when the
    // negotiated stride is channels * sizeof(float)). Zero-fills any
    // starved tail (digital silence; a starving consumer never stalls
    // the graph). Returns the number of REAL frames pulled. No
    // allocation, no locks, no syscalls — audited by AllocGuard on the
    // host lanes.
    uint32_t FillProcessBuffer(uint32_t frames, float* dst) noexcept {
        callback_count_.fetch_add(1, std::memory_order_relaxed);
        sink::AudioSink* s = sink_.load(std::memory_order_acquire);
        uint32_t got = 0;
        if (s != nullptr && dst != nullptr && frames > 0) {
            const size_t pulled = s->ReadFramesF32(dst, frames);
            got = static_cast<uint32_t>(pulled);
            if (got < frames) {
                // Underrun: the graph presents digital silence for the
                // starved tail (AudioTrack/AUGraph semantics).
                underrun_callbacks_.fetch_add(1, std::memory_order_relaxed);
                starved_frames_.fetch_add(frames - got,
                                          std::memory_order_relaxed);
                const size_t floats =
                    static_cast<size_t>(frames - got) * kChannels;
                std::memset(dst + got * kChannels, 0,
                            floats * sizeof(float));
            }
        } else if (frames > 0) {
            // Unbound (teardown raced a live callback) or degenerate
            // args: digital silence for the whole window.
            underrun_callbacks_.fetch_add(1, std::memory_order_relaxed);
            starved_frames_.fetch_add(frames, std::memory_order_relaxed);
            if (dst != nullptr) {
                std::memset(dst, 0,
                            static_cast<size_t>(frames) * kChannels *
                                sizeof(float));
            }
        }
        return got;
    }

    // Publish one CONSISTENT pw_time-derived sample: single-writer
    // seqlock (the clock sampler is the only writer — see the driver TU;
    // the process callback NEVER calls pw_stream_get_time_n, keeping the
    // graph RT thread free of even seqlock-read spins). The driver passes
    // the raw pw_time triple; ticks are defensively scaled from the graph
    // tick rate to stream frames (identity in every negotiated stream:
    // pw_time.rate is {num:1, denom:stream_rate} — the tick PERIOD — so
    // ticks already count stream frames; see TicksToFrames).
    void PublishClockFromPwTime(uint64_t ticks, uint32_t tick_rate_num,
                                uint32_t tick_rate_denom,
                                uint64_t now_ns) noexcept {
        const uint32_t stream_rate = rate_.load(std::memory_order_relaxed);
        const uint64_t frames =
            TicksToFrames(ticks, tick_rate_num, tick_rate_denom,
                          stream_rate);
        const uint64_t seq = seq_.load(std::memory_order_relaxed);
        seq_.store(seq + 1, std::memory_order_relaxed);  // odd: writing
        std::atomic_thread_fence(std::memory_order_release);
        clock_frames_.store(frames, std::memory_order_relaxed);
        clock_host_ns_.store(now_ns, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        seq_.store(seq + 2, std::memory_order_relaxed);  // even: stable
        clock_publications_.fetch_add(1, std::memory_order_relaxed);
    }

    // ---- pure helper (host-testable; no state) ---------------------------------
    // pw_time.ticks -> stream FRAMES. `tick_rate_num/tick_rate_denom` is
    // the pw_time.rate fraction — the tick PERIOD in seconds, canonically
    // {1, stream_rate} for an audio stream ("the rate of ticks and delay
    // is usually expressed in 1/<samplerate>"): ticks then already count
    // stream frames and this is the identity. Defensively it also handles
    // a graph clock running at a foreign rate (e.g. a 44.1 kHz driver
    // behind a 48 kHz stream: ticks count driver samples and must be
    // rescaled). frames = ticks * num * stream_rate / denom (truncating
    // toward zero; 128-bit intermediate math when available). Degenerate
    // fractions (0 numerator/denominator) pass ticks through.
    static uint64_t TicksToFrames(uint64_t ticks, uint32_t rate_num,
                                  uint32_t rate_denom,
                                  uint32_t stream_rate) noexcept {
        if (rate_num == 0 || rate_denom == 0) {
            return ticks;  // defensive: degenerate fraction
        }
        // Identity fast path: tick period * stream_rate == 1 second-ish
        // divisor — covers {1, R} with stream_rate == R (the negotiated
        // case) and any aliased spelling of it.
        if (static_cast<uint64_t>(rate_num) * stream_rate == rate_denom) {
            return ticks;
        }
#if defined(__SIZEOF_INT128__)
        const unsigned __int128 frames =
            (static_cast<unsigned __int128>(ticks) * rate_num *
             stream_rate) /
            rate_denom;
        return static_cast<uint64_t>(frames);
#else
        return static_cast<uint64_t>(
            (static_cast<double>(ticks) *
             static_cast<double>(rate_num) *
             static_cast<double>(stream_rate)) /
            static_cast<double>(rate_denom));
#endif
    }

    // ---- telemetry (any thread; relaxed counters) ------------------------------
    uint64_t callback_count() const noexcept {
        return callback_count_.load(std::memory_order_relaxed);
    }
    uint64_t underrun_callbacks() const noexcept {
        return underrun_callbacks_.load(std::memory_order_relaxed);
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

    // Seqlock-protected clock sample (single writer = process callback).
    alignas(64) std::atomic<uint64_t> seq_{0};
    alignas(64) std::atomic<uint64_t> clock_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_host_ns_{0};

    // Telemetry counters.
    alignas(64) std::atomic<uint64_t> callback_count_{0};
    alignas(64) std::atomic<uint64_t> underrun_callbacks_{0};
    alignas(64) std::atomic<uint64_t> starved_frames_{0};
    alignas(64) std::atomic<uint64_t> clock_publications_{0};
};

}  // namespace hal
}  // namespace streamify

#endif  // STREAMIFY_PIPEWIRE_RENDER_CORE_H
