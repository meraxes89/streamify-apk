#pragma once
// ============================================================================
//  SharedInstances.h — process-wide engine singletons shared by the JNI
//  bridges (jni_bridge.cc + jni_bridge_dsp.cc; C9: the stale reference to
//  the never-landed jni_bridge_native_dsp_engine.cc removed — the sink
//  bridge deliberately owns NO cross-bridge state, its sinks live in the
//  SinkRegistry behind opaque handles).
//
//  Function-local statics give thread-safe init and destruction after
//  main() returns — never during a live audio session. Keeping ONE
//  resampler/profiler instance across bridges is what lets the Phase-1
//  party-mode toggle (NativeDspEngine.setSilentBypass) reach the SAME
//  AcousticPhaseResampler the PLL controller drives via NativeBridge.
// ============================================================================

#include "../dsp/AcousticPhaseResampler.h"
#include "../engine/HardwareLatencyProfiler.h"

namespace streamify::jni {

inline dsp::AcousticPhaseResampler& sharedResampler() {
    static dsp::AcousticPhaseResampler engine;   // 48 kHz stereo defaults
    return engine;
}

inline engine::HardwareLatencyProfiler& sharedProfiler() {
    static engine::HardwareLatencyProfiler profiler;
    return profiler;
}

}  // namespace streamify::jni
