// ============================================================================
//  apple_ring_mount.cpp — WS-B B4 (ENG-2): the Apple mounting bracket's
//  native half — the pump that bridges ENG-1's playback data plane onto
//  the CoreAudio pull edge.
//  (native/audio/apple_ring_mount.cpp — A1 RFC §7, the "Apple" row)
// ============================================================================
//
//  WHY THIS FILE EXISTS
//  --------------------
//  Two rings exist by design, and they never merge:
//
//    * the RUST ring (`streamify_playback::SpscAudioRing`, WS-A A3/A4):
//      the producer's write edge. `attach_sink` builds it; the consumer
//      handle leaves the engine ONLY through the frozen C edge
//      (`streamify_ring_*`, published by consumer_lease.rs). It is NOT a
//      UDL surface on purpose (A1 RFC §6.3/§7 — "platform lanes mount
//      through their own brackets against the frozen ABI").
//
//    * the C++ ring (`streamify::sink::AudioSink`, Phase-4a): what the
//      CoreAudio AURenderCallback pulls from through
//      coreaudio_pull_adapter.cpp / CoreAudioRenderCore — the audited,
//      host-tested, RT-safe render path ("zero FFI crossings, zero locks,
//      zero allocation on the audio thread").
//
//  The audio thread must stay pure C++ (the adapter's own invariant, and
//  the RT audit in test_coreaudio_render_core.cpp pins it). So the bridge
//  is a CONTROL-PLANE PUMP on its own thread: it drains the Rust ring and
//  refills the C++ sink, both through their published single-consumer
//  edges, never on the audio thread. The C++ sink is kept near its high
//  watermark; the added latency is bounded by the C++ ring's capacity
//  (4096 frames ≈ 85 ms at 48 kHz) while the bulk buffering stays in the
//  much larger Rust ring (attach_sink capacity), exactly the split the
//      A1 RFC §4 buffering budget describes.
//
//  SKIP PROPAGATION
//  -----------------
//  The producer requests a cooperative flush on the RUST ring whenever it
//  re-anchors (skip/seek). The pump polls the ring's generation handshake
//  (`streamify_ring_flush_generations`) and FORWARDS a matching
//  `RequestFlush()` onto the C++ sink, so the stale tail never plays out
//  of the car speakers. The flush call itself is the frozen cooperative
//  protocol (AMP-10): safe while the audio callback is live; the consumer
//  resets its cursor at the top of its next read.
//
//  LIFECYCLE
//  ---------
//  start: create the C++ sink in the frozen `SinkRegistry` (opaque int64
//         handle — never a raw pointer), spawn the pump thread, return
//         the handle to the Swift shell (`CoreAudioRenderCore.start`).
//         The handle stays owned by the registry; the mount keeps it for
//         teardown. Starting while mounted replaces cleanly (the same
//         semantics as the engine's attach_sink).
//  stop:  stop flag -> join the pump (deterministic), destroy the registry
//         entry. Idempotent.
//
//  This TU compiles ONLY on Apple targets (`__APPLE__`), links nothing on
//  its own (the symbols come from the Rust staticlib in the same
//  XCFramework slice; the SDK frameworks resolve via the adapter TU's
//  autolink entries + -fmodules), and has zero exports beyond the four
//  `streamify_apple_mount_*` C symbols.
// ============================================================================

#if defined(__APPLE__)

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include "../include/streamify_audio_sinks.h"

// ---- the frozen Rust-side C edge (consumer_lease.rs, WS-B B4) ----------
// Symbols live in the Rust staticlib linked into the same XCFramework
// slice; declared here so this TU sees the exact published shapes.
extern "C" {
size_t streamify_ring_read_f32(float* dst, size_t samples);
void streamify_ring_request_flush(void);
int32_t streamify_ring_flush_generations(uint64_t* requested, uint64_t* acknowledged);
int32_t streamify_ring_stats_wire(uint8_t* dst, size_t capacity);
int32_t streamify_ring_is_attached(void);
}

namespace streamify {
namespace mount {

// Canonical geometry (master plan invariants 5/6): the ring is canonical
// 48 kHz interleaved f32 stereo; the AUHAL/RemoteIO performs device-side
// rate conversion. The C++ bridge sink is DELIBERATELY small — it exists
// to decouple the pump cadence from the DAC, not to buffer the session.
constexpr uint32_t kCanonicalRate = 48000;
constexpr uint32_t kCanonicalChannels = 2;
constexpr size_t kBridgeCapacityFrames = 4096;  // pow2, ≈85 ms
constexpr size_t kPumpQuantumSamples = 2048;    // 1024 frames per pass
constexpr auto kDrainIdleSleep = std::chrono::milliseconds(2);
constexpr auto kSinkFullSleep = std::chrono::milliseconds(4);

/// One mounted bridge. Plain struct under `g_state`'s mutex; the pump
/// thread reads `stop` (atomic) and the sink pointer (set before the
/// thread starts, joined before it is cleared — no torn state).
struct MountState {
    std::thread pump;
    int64_t handle = 0;             // registry handle; 0 = not mounted
    std::shared_ptr<AudioSink> sink;  // keeps the registry entry alive
    std::atomic<bool> stop{false};
    /// A shell-requested flush (seek/skip intent), applied by the pump on
    /// its next pass — see the flush contract note below.
    std::atomic<bool> flush_requested{false};

    bool mounted() const { return handle != 0; }
};

namespace {
std::mutex g_state_mutex;
MountState g_state;

void join_and_destroy_locked(MountState* state) {
    if (state->pump.joinable()) {
        state->pump.join();
    }
    if (state->handle != 0) {
        sink::SinkRegistry::instance().destroy(state->handle);
    }
    state->sink.reset();
    state->handle = 0;
    state->stop.store(false, std::memory_order_relaxed);
}

void pump_loop(std::shared_ptr<AudioSink> sink, std::atomic<bool>* stop,
               std::atomic<bool>* flush_requested) {
    // Allocated ONCE, before the loop: the steady-state path performs zero
    // allocation (the same discipline the producer and the render core
    // run; the pump is control-plane but stays quiet on purpose).
    std::vector<float> staging(kPumpQuantumSamples, 0.0f);

    // The flush generation we last observed on the Rust ring. Seeded on
    // the first poll so a pre-existing request from before the mount is
    // not mistaken for a NEW skip.
    bool have_generation = false;
    uint64_t last_requested = 0;

    while (!stop->load(std::memory_order_relaxed)) {
        // 1. Shell-requested flush (seek/skip intent). The pump owns the
        //    write edge of the bridge sink, so IT must perform the
        //    cooperative RequestFlush — at a pass boundary where no
        //    WriteFloat is in flight (the frozen protocol's requirement).
        //    Applied within one pass (<= 4 ms) of the request.
        if (flush_requested->exchange(false, std::memory_order_acq_rel)) {
            sink->RequestFlush();
        }

        // 2. Forward engine-side skips (the producer's re-anchors).
        uint64_t requested = 0, acknowledged = 0;
        if (streamify_ring_flush_generations(&requested, &acknowledged) == 1) {
            if (!have_generation) {
                last_requested = requested;
                have_generation = true;
            } else if (requested != last_requested) {
                sink->RequestFlush();
                last_requested = requested;
            }
        }

        // 3. Measure the bridge sink's free space (any-thread read; the
        //    DAC only ever drains, so the measured free floor is a
        //    conservative lower bound until we write).
        sink::SinkStats stats;
        sink->GetStats(&stats);
        const size_t free_frames =
            stats.capacity_frames > stats.current_fullness_frames
                ? static_cast<size_t>(stats.capacity_frames - stats.current_fullness_frames)
                : 0;

        // 4. Pull from the Rust ring — never more than the bridge can
        //    take, so a committed read can never go short on the write.
        const size_t want_samples =
            std::min(kPumpQuantumSamples, free_frames * kCanonicalChannels);
        if (want_samples > 0) {
            const size_t served = streamify_ring_read_f32(staging.data(), want_samples);
            if (served > 0) {
                size_t written = sink->WriteFloat(staging.data(), served);
                // Unreachable by construction (free was measured while the
                // DAC only drains). Bounded defensive retry, then drop the
                // remainder rather than spin: a drop here can only be a
                // broken invariant, never a steady-state behavior.
                int retries = 0;
                while (written < served && retries < 3) {
                    ++retries;
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    written += sink->WriteFloat(staging.data() + written, served - written);
                }
            }
            if (served == want_samples) {
                // The Rust ring may hold more; run the next pass now.
                std::this_thread::yield();
                continue;
            }
        }

        // 5. Source drained or bridge full: park off the CPU.
        const bool bridge_full = (want_samples == 0);
        std::this_thread::sleep_for(bridge_full ? kSinkFullSleep : kDrainIdleSleep);
    }
}
}  // namespace
}  // namespace mount
}  // namespace streamify

// ---- the C facade the Swift shell drives (dlsym late binding) ----------
//
//  int64_t streamify_apple_mount_start(void);
//      > 0 : the registry handle feeding CoreAudioRenderCore.start
//      - 1 : the engine's playback data plane is not attached yet (the
//            shell's ordering: attach_sink -> mount.start -> render core)
//       0 : the mount refused (registry/geometry failure)
//  void    streamify_apple_mount_stop(void);
//  void    streamify_apple_mount_flush(void);
//  int32_t streamify_apple_mount_stats(uint8_t* dst, size_t capacity);

extern "C" int64_t streamify_apple_mount_start(void) {
    std::lock_guard<std::mutex> lock(streamify::mount::g_state_mutex);
    // Replace semantics (mirrors the engine's attach_sink): a live mount
    // is torn down first, deterministically, before the new one arms.
    if (streamify::mount::g_state.mounted()) {
        streamify::mount::g_state.stop.store(true, std::memory_order_relaxed);
        streamify::mount::join_and_destroy_locked(&streamify::mount::g_state);
    }

    // The Rust plane must be attached first (the shell's ordering:
    // engine.attach_sink -> mount.start -> renderCore.start).
    if (streamify_ring_is_attached() != 1) {
        return -1;
    }

    sink::AudioSink::Config config{};
    config.sample_rate = streamify::mount::kCanonicalRate;
    config.channels = streamify::mount::kCanonicalChannels;
    config.capacity_frames = streamify::mount::kBridgeCapacityFrames;
    config.downmix_to_mono = false;
    const int64_t handle = sink::SinkRegistry::instance().create(config);
    if (handle == 0) {
        return 0;
    }
    std::shared_ptr<AudioSink> sink = sink::SinkRegistry::instance().resolve(handle);
    if (sink == nullptr) {
        sink::SinkRegistry::instance().destroy(handle);
        return 0;
    }

    streamify::mount::g_state.handle = handle;
    streamify::mount::g_state.sink = sink;
    streamify::mount::g_state.stop.store(false, std::memory_order_relaxed);
    streamify::mount::g_state.flush_requested.store(false, std::memory_order_relaxed);
    streamify::mount::g_state.pump = std::thread(
        streamify::mount::pump_loop,
        std::move(sink),
        &streamify::mount::g_state.stop,
        &streamify::mount::g_state.flush_requested
    );
    return handle;
}

extern "C" void streamify_apple_mount_stop(void) {
    std::lock_guard<std::mutex> lock(streamify::mount::g_state_mutex);
    if (!streamify::mount::g_state.mounted()) {
        return;
    }
    streamify::mount::g_state.stop.store(true, std::memory_order_relaxed);
    streamify::mount::join_and_destroy_locked(&streamify::mount::g_state);
}

extern "C" void streamify_apple_mount_flush(void) {
    // Hand the request to the pump (it owns the bridge sink's write edge,
    // and the frozen protocol forbids a RequestFlush concurrent with an
    // in-flight WriteFloat). Applied on the pump's next pass (<= 4 ms).
    std::lock_guard<std::mutex> lock(streamify::mount::g_state_mutex);
    if (!streamify::mount::g_state.mounted()) {
        return;
    }
    streamify::mount::g_state.flush_requested.store(true, std::memory_order_release);
}

extern "C" int32_t streamify_apple_mount_stats(uint8_t* dst, size_t capacity) {
    // The producer-side truth (the Rust ring's accounting) in the frozen
    // 96-byte wire layout — parity with the JNI DirectByteBuffer lane.
    return streamify_ring_stats_wire(dst, capacity) ? 1 : 0;
}

#endif  // __APPLE__
