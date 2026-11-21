// ============================================================================
//  desktop_hal_c_api.cpp — the plain-C sink registry surface of the C5
//  desktop HAL module (WS-C / AHEAD C5)
//  (native/src/desktop_hal_c_api.cpp)
// ============================================================================
//
//  Implements the sink half of native/include/desktop_hal.h over the SAME
//  jni.h-free support layer the Android bridge mounts (SinkRegistry +
//  WriteSinkStatsBuffer from streamify_audio_sinks.h). The platform pull
//  adapters (WASAPI / PipeWire+ALSA / CoreAudio) bring their own facade
//  definitions into the module; this TU adds only the registry surface.
//
//  FIDELITY TO THE FROZEN CONTRACT: semantics are field-for-field the
//  C1 JNI bridge's (native/src/jni_bridge_audio_sinks.cpp) — same
//  geometry bounds, same negative create sentinels, same closed-ring
//  degradation on stale handles, same 96-byte stats wire — so the
//  desktop JNA lane and the Android JNI lane are two mounts of ONE
//  contract, and the host suites (test_audio_sink_ringbuffer,
//  jni_sink_bridge_test_suite) plus DesktopJnaEngineSmokeTest's C5
//  round-trip keep all three honest.
//
//  SHIELD MODEL: every entry point wraps its body in catch(...) → the
//  function's own sentinel (the C-ABI twin of the JNI_SHIELD /
//  catch_unwind house model — a C++ exception must never unwind across
//  an extern "C" edge).
// ============================================================================

#include "../include/desktop_hal.h"

#include <algorithm>
#include <cstddef>
#include <cstring>

#include "../include/streamify_audio_sinks.h"

namespace {

using streamify::sink::AudioSink;
using streamify::sink::SinkRegistry;
using streamify::sink::WriteSinkStatsBuffer;
using streamify::sink::kSinkStatsWireBytes;

// Create-failure sentinels (the frozen contract's negative spelling).
constexpr int64_t kCreateRejectedConfig = -1;
constexpr int64_t kCreateRejectedRegistry = -2;

std::shared_ptr<AudioSink> resolveSink(int64_t handle) {
    return SinkRegistry::instance().resolve(handle);
}

}  // namespace

extern "C" {

int64_t streamify_desktop_sink_create(uint32_t sample_rate, uint32_t channels,
                                      uint32_t capacity_frames) {
    try {
        AudioSink::Config cfg;
        cfg.sample_rate = sample_rate;
        cfg.channels = channels;
        cfg.capacity_frames = static_cast<size_t>(capacity_frames);
        if (!AudioSink::ValidateConfig(cfg)) {
            return kCreateRejectedConfig;
        }
        const int64_t handle = SinkRegistry::instance().create(cfg);
        if (handle == 0) {
            return kCreateRejectedRegistry;
        }
        return handle;
    } catch (...) {
        return kCreateRejectedRegistry;
    }
}

int32_t streamify_desktop_sink_write_f32(int64_t handle, const float* samples,
                                         uint32_t sample_count) {
    try {
        auto sink = resolveSink(handle);
        if (!sink || samples == nullptr || sample_count == 0) {
            return 0;
        }
        const size_t consumed =
            sink->WriteFloat(samples, static_cast<size_t>(sample_count));
        return static_cast<int32_t>(
            std::min<size_t>(consumed, 0x7FFFFFFFu));
    } catch (...) {
        return 0;
    }
}

int32_t streamify_desktop_sink_read_f32(int64_t handle, float* dst,
                                        uint32_t max_frames) {
    try {
        auto sink = resolveSink(handle);
        if (!sink || dst == nullptr || max_frames == 0) {
            return 0;
        }
        const uint32_t channels = sink->channels_out();
        if (channels == 0) {
            return 0;
        }
        const uint32_t frames =
            std::min<uint32_t>(max_frames, 0x7FFFFFFFu / channels);
        const size_t got = sink->ReadFramesF32(dst, frames);
        return static_cast<int32_t>(std::min<size_t>(got, 0x7FFFFFFFu));
    } catch (...) {
        return 0;
    }
}

int32_t streamify_desktop_sink_read_pcm16(int64_t handle, uint8_t* dst,
                                          uint32_t capacity_bytes) {
    try {
        auto sink = resolveSink(handle);
        if (!sink || dst == nullptr || capacity_bytes == 0) {
            return 0;
        }
        // Frame-align down to the PCM16 output frame (never split a frame
        // across calls) — the same clamp the JNI DirectByteBuffer edge
        // performs against the caller's declared capacity.
        const size_t frame_bytes = sink->frame_bytes_out();
        uint32_t want = capacity_bytes;
        if (frame_bytes > 0) {
            want -= want % static_cast<uint32_t>(frame_bytes);
        }
        if (want == 0) {
            return 0;
        }
        const size_t got = sink->ReadBytes(dst, want);
        return static_cast<int32_t>(std::min<size_t>(got, 0x7FFFFFFFu));
    } catch (...) {
        return 0;
    }
}

int32_t streamify_desktop_sink_stats(int64_t handle, uint8_t* out_wire) {
    try {
        auto sink = resolveSink(handle);
        if (!sink || out_wire == nullptr) {
            return 0;
        }
        return WriteSinkStatsBuffer(*sink, out_wire, kSinkStatsWireBytes) ? 1
                                                                          : 0;
    } catch (...) {
        return 0;
    }
}

void streamify_desktop_sink_flush(int64_t handle) {
    try {
        auto sink = resolveSink(handle);
        if (sink) {
            sink->RequestFlush();  // noexcept by contract
        }
    } catch (...) {
        // Swallowed: the flush request stays pending; the consumer
        // applies it on its next read (the protocol's lazy fallback).
    }
}

void streamify_desktop_sink_destroy(int64_t handle) {
    try {
        (void)SinkRegistry::instance().destroy(handle);
    } catch (...) {
        // Swallowed: destroy is idempotent by contract.
    }
}

int64_t streamify_desktop_sink_live_count(void) {
    try {
        return static_cast<int64_t>(SinkRegistry::instance().liveCount());
    } catch (...) {
        return -1;
    }
}

}  // extern "C"
