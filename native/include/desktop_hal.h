#ifndef STREAMIFY_DESKTOP_HAL_H
#define STREAMIFY_DESKTOP_HAL_H
// ============================================================================
//  desktop_hal.h — the plain-C desktop HAL ABI: the C5 wiring slice
//  (WS-C / AHEAD C5; native/include/desktop_hal.h)
// ============================================================================
//
//  THE LOADABLE MODULE. `streamify_desktop_hal` (CMake target, built for
//  Linux and Windows hosts) is the module that finally gives the Phase 4b
//  C facades a production compilation home AND a loadable artifact:
//
//      streamify_desktop_hal.{so,dll}
//        ├─ the sink registry surface (below) — the SAME frozen contract
//        │  native/src/jni_bridge_audio_sinks.cpp binds on Android over
//        │  jni.h (C1): opaque registry handles, SPSC f32 ring, the
//        │  96-byte stats wire (magic 0x51AB0001), cooperative flush.
//        └─ the platform pull adapters, whose C facades this header also
//           declares: streamify_wasapi_start (Windows), streamify_
//           linuxhal_start (Linux: PipeWire with the ALSA fallback),
//           streamify_coreaudio_start (Apple lane, ENG-2's bracket).
//
//  MOUNTING GEOMETRY (the point of the drivetrain): every platform mounts
//  brackets, not pipelines. The desktop JVM shell binds THIS ABI through
//  JNA (desktop/.../DesktopHalBridge.kt — soft-probe degraded, the same
//  MP-HAL07 ladder the Android seam uses); the engine-side relay that
//  feeds the Rust producer's playback_ring() output into a registry sink
//  is ENG-1's follow-up on the engine lane.
//
//  CONSUMERS
//   * JVM desktop shell: JNA direct mapping (DesktopHalBridge).
//   * DesktopJnaEngineSmokeTest: the C5 acceptance round-trip — create →
//     write → read f32 → stats → flush → destroy through the REAL .so.
//   * The host sanitizer lanes pin the ring underneath (C3's suites);
//     the windows-native rot lane (C4) compiles this module with MSVC.
//
//  CONTRACT NOTES (mirroring the frozen sink ABI field-for-field)
//   * Handles are opaque monotonic int64 registry ids — NEVER pointers.
//     A stale, garbage or post-destroy handle degrades to the "closed
//     ring" returns (0 / no-op), never a crash.
//   * streamify_desktop_sink_create returns a NEGATIVE sentinel on
//     rejection (-1 invalid geometry, -2 registry exhaustion) — the same
//     spelling the frozen JNI edge documents for the Kotlin caller's
//     `handle >= 0` check.
//   * The stats wire written by streamify_desktop_sink_stats is the
//     96-byte little-endian layout v1 documented in
//     native/include/streamify_audio_sinks.h (magic 0x51AB0001 at +0,
//     layout bytes 96 at +4, …, monotonic sequence at +88).
//   * Every entry point is exception-shielded (catch-all → sentinel) —
//     a C++ exception never unwinds across this C ABI.
// ============================================================================

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Export spelling: default visibility on GCC/Clang builds (the CMake
// lane compiles with -fvisibility=hidden); MSVC builds auto-export via
// the WINDOWS_EXPORT_ALL_SYMBOLS target property.
#if defined(_WIN32)
#define STREAMIFY_HAL_API
#else
#define STREAMIFY_HAL_API __attribute__((visibility("default")))
#endif

// ---- the sink registry surface (the frozen contract, plain C) ---------------

// One SPSC f32 sink ring. geometry bounds: rate 8000..192000 Hz,
// channels 1|2, capacity 64..(1<<22) frames (rounded up to a power of
// two). Returns the registry handle (> 0) or a negative sentinel.
STREAMIFY_HAL_API int64_t streamify_desktop_sink_create(
    uint32_t sample_rate, uint32_t channels, uint32_t capacity_frames);

// Producer edge: up to sample_count interleaved f32 SAMPLES (whole
// frames only). Returns the samples consumed — a short return is ring
// backpressure; retry the remainder. 0 on a stale handle / bad args.
STREAMIFY_HAL_API int32_t streamify_desktop_sink_write_f32(
    int64_t handle, const float* samples, uint32_t sample_count);

// CANONICAL f32 pull edge: up to max_frames interleaved frames into dst
// (needs max_frames * channels_out floats of room; the callee clamps).
// Returns the frames read; 0 == starved (one underrun event inside).
STREAMIFY_HAL_API int32_t streamify_desktop_sink_read_f32(
    int64_t handle, float* dst, uint32_t max_frames);

// LEGACY PCM16 edge: frame-aligned PCM16 bytes (the f32 ring quantized
// at this edge). Returns bytes copied; 0 when starved / stale.
STREAMIFY_HAL_API int32_t streamify_desktop_sink_read_pcm16(
    int64_t handle, uint8_t* dst, uint32_t capacity_bytes);

// Serializes the 96-byte LE stats wire (layout v1) into out_wire.
// Returns 1 on success; 0 on a stale handle or a null/short buffer.
// The sequence field at +88 increments on every call — the change
// detector foreign pollers use.
STREAMIFY_HAL_API int32_t streamify_desktop_sink_stats(
    int64_t handle, uint8_t* out_wire /* >= 96 bytes */);

// Cooperative generational flush (AMP-10 / MP-C10): safe while a
// consumer pulls; call from a producer/control thread, never the
// consumer thread itself. No-op on a stale handle.
STREAMIFY_HAL_API void streamify_desktop_sink_flush(int64_t handle);

// Reference-counted destroy: drops the registry entry; a destroy racing
// an in-flight call just unmaps the handle. Idempotent for stale handles.
STREAMIFY_HAL_API void streamify_desktop_sink_destroy(int64_t handle);

// Live registry entries (diagnostics / leak audits in the smoke suite).
STREAMIFY_HAL_API int64_t streamify_desktop_sink_live_count(void);

// ---- the Windows WASAPI pull-adapter facade (MP-HAL08) ------------------------
// Start pulling for a sink created above. Returns an opaque adapter
// handle (> 0) or 0 on failure (no audio endpoint / COM failure).
STREAMIFY_HAL_API int64_t streamify_wasapi_start(int64_t sink_handle);
// Stop and release the adapter (idempotent; 0/unknown handles are no-ops).
STREAMIFY_HAL_API void streamify_wasapi_stop(int64_t adapter_handle);
// Drain the latest DAC presentation clock sample: writes the consistent
// (frames, rate, host_ns) triple; 1 on success, 0 when none yet.
STREAMIFY_HAL_API int32_t streamify_wasapi_latest_clock(
    int64_t adapter_handle, uint64_t* out_frames, uint32_t* out_rate,
    uint64_t* out_host_ns);

// ---- the Linux PipeWire + ALSA-fallback driver facade (MP-HAL09) -------------
// Start pulling: tries PipeWire, falls back to ALSA (the dlopen'd
// "null" PCM is the CI double). Returns a driver handle (> 0) or 0 when
// neither backend is loadable.
STREAMIFY_HAL_API int64_t streamify_linuxhal_start(int64_t sink_handle);
STREAMIFY_HAL_API void streamify_linuxhal_stop(int64_t driver_handle);
// 0 = PipeWire, 1 = ALSA fallback, -1 = not started / unknown handle.
STREAMIFY_HAL_API int32_t streamify_linuxhal_backend(int64_t driver_handle);
STREAMIFY_HAL_API int32_t streamify_linuxhal_latest_clock(
    int64_t driver_handle, uint64_t* out_frames, uint32_t* out_rate,
    uint64_t* out_host_ns);

// ---- the Apple CoreAudio adapter facade (MP-HAL04, ENG-2's lane) -------------
// Declared for ABI completeness; the TU builds only in the Apple lanes
// (the XCFramework script / the macOS native compile spoke). Non-Apple
// modules simply never reference these symbols.
STREAMIFY_HAL_API int64_t streamify_coreaudio_start(int64_t sink_handle);
STREAMIFY_HAL_API void streamify_coreaudio_stop(int64_t adapter_handle);
STREAMIFY_HAL_API int32_t streamify_coreaudio_latest_clock(
    int64_t adapter_handle, uint64_t* out_frames, uint32_t* out_rate,
    uint64_t* out_host_ns);
STREAMIFY_HAL_API int32_t streamify_coreaudio_telemetry(
    int64_t adapter_handle, uint64_t* out_pulls, uint64_t* out_starved);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // STREAMIFY_DESKTOP_HAL_H
