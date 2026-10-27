// ============================================================================
//  coreaudio_pull_adapter.cpp — Phase 4a Apple CoreAudio native pull adapter
//  (native/audio/coreaudio_pull_adapter.cpp — MP-HAL04)
// ============================================================================
//
//  macOS (AUHAL / kAudioUnitSubType_HALOutput) and iOS (RemoteIO) output
//  unit driven by an AURenderCallback that pulls interleaved IEEE-754 f32
//  stereo frames DIRECTLY from the hardened `AudioSink` SPSC ring — zero
//  FFI crossings, zero quantization, zero dynamic allocation, zero locks
//  and zero system calls on the audio thread (master plan invariants 5).
//
//  The RT-critical logic lives in the host-testable
//  `streamify::hal::CoreAudioRenderCore` (include/coreaudio_render_core.h);
//  this TU adds only the Apple lifecycle scaffolding:
//
//      start()   [control thread]
//        1. resolve the sink from the registry (shared_ptr keeps it alive)
//        2. cache mach_timebase_info ONCE (a mach trap — never in render)
//        3. open the output unit, set the canonical 48 kHz interleaved f32
//           client format on its input scope (the AUHAL performs the
//           device-side rate conversion; the ring stays canonical —
//           master plan invariant 6), install the render callback
//        4. Bind(core) + AudioUnitInitialize + AudioOutputUnitStart
//
//      render()  [audio thread — the AURenderCallback]
//        RenderCore::Render(nframes, ioData, mSampleTime,
//                            mach_to_ns(mHostTime, cached_timebase))
//        — see the render core header for the RT-safety audit points.
//
//      stop()    [control thread]
//        core_.Unbind() (atomic pointer retract — an in-flight callback
//        degrades to digital silence, never a use-after-free), THEN
//        AudioOutputUnitStop / Uninitialize / Dispose, and only then
//        release the sink shared_ptr.
//
//  This TU compiles ONLY on Apple targets (CMake guards it with
//  `if(APPLE)`; the contents are additionally fenced with __APPLE__).
// ============================================================================

#if defined(__APPLE__)

#include <AudioUnit/AudioUnit.h>
#include <CoreAudio/CoreAudioTypes.h>
#include <mach/mach_time.h>
#include <TargetConditionals.h>

#include <cstdint>
#include <memory>
#include <new>

#include "../include/coreaudio_render_core.h"
#include "../include/streamify_audio_sinks.h"

namespace streamify {
namespace hal {

class CoreAudioPullAdapter {
public:
    // Canonical client format: 48 kHz interleaved f32 stereo (master plan
    // invariant 5/6 — the ring is canonical; the AUHAL converts to the
    // device rate).
    static constexpr uint32_t kCanonicalRate = 48000;
    static constexpr uint32_t kCanonicalChannels = 2;

    // Start pulling for the sink registered under `sink_handle`. Returns
    // false on any setup failure (leaves the adapter stopped).
    bool Start(int64_t sink_handle) {
        if (running_) Stop();
        sink_ = sink::SinkRegistry::instance().resolve(sink_handle);
        if (sink_ == nullptr) return false;

        // The mach timebase is queried ONCE (control thread): the render
        // path only ever runs the pure-arithmetic conversion.
        if (mach_timebase_info(&timebase_) != KERN_SUCCESS) {
            timebase_.numer = 1;
            timebase_.denom = 1;
        }

        // ---- open the platform output unit --------------------------------
        AudioComponentDescription desc{};
        desc.componentType = kAudioUnitType_Output;
#if TARGET_OS_IPHONE
        desc.componentSubType = kAudioUnitSubType_RemoteIO;
#else
        desc.componentSubType = kAudioUnitSubType_HALOutput;
#endif
        desc.componentManufacturer = kAudioUnitManufacturer_Apple;
        AudioComponent comp = AudioComponentFindNext(nullptr, &desc);
        if (comp == nullptr) {
            sink_.reset();
            return false;
        }
        if (AudioComponentInstanceNew(comp, &unit_) != noErr) {
            unit_ = nullptr;
            sink_.reset();
            return false;
        }

        // ---- canonical interleaved f32 stereo client format ----------------
        AudioStreamBasicDescription fmt{};
        fmt.mSampleRate = static_cast<Float64>(kCanonicalRate);
        fmt.mFormatID = kAudioFormatLinearPCM;
        // IsFloat + IsPacked and NO IsNonInterleaved => one interleaved
        // buffer — exactly the layout ReadFramesF32 produces.
        fmt.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        fmt.mChannelsPerFrame = kCanonicalChannels;
        fmt.mBitsPerChannel = 32;
        fmt.mFramesPerPacket = 1;
        fmt.mBytesPerFrame = kCanonicalChannels * sizeof(Float32);
        fmt.mBytesPerPacket = fmt.mBytesPerFrame;
        if (AudioUnitSetProperty(unit_, kAudioUnitProperty_StreamFormat,
                                 kAudioUnitScope_Input, 0, &fmt,
                                 sizeof(fmt)) != noErr) {
            TeardownUnit_();
            return false;
        }

        // ---- install the render callback ------------------------------------
        AURenderCallbackStruct cb{};
        cb.inputProc = &CoreAudioPullAdapter::RenderProc;
        cb.inputProcRefCon = this;
        if (AudioUnitSetProperty(unit_, kAudioUnitProperty_SetRenderCallback,
                                 kAudioUnitScope_Input, 0, &cb,
                                 sizeof(cb)) != noErr) {
            TeardownUnit_();
            return false;
        }

        // ---- arm: bind the core, then start the hardware --------------------
        core_.Bind(sink_.get(), kCanonicalRate);
        if (AudioUnitInitialize(unit_) != noErr ||
            AudioOutputUnitStart(unit_) != noErr) {
            core_.Unbind();
            TeardownUnit_();
            return false;
        }
        running_ = true;
        return true;
    }

    // Ordered teardown (see the file header): retract the pointer BEFORE
    // the hardware stops so a late in-flight callback can only emit silence.
    void Stop() {
        if (!running_) return;
        core_.Unbind();
        AudioOutputUnitStop(unit_);
        TeardownUnit_();
        running_ = false;
        sink_.reset();  // last owner release — after Unbind, always safe
    }

    ~CoreAudioPullAdapter() { Stop(); }

    CoreAudioPullAdapter(const CoreAudioPullAdapter&) = delete;
    CoreAudioPullAdapter& operator=(const CoreAudioPullAdapter&) = delete;

    // Latest DAC presentation clock sample (control thread; feed it to
    // StreamifyEngine::record_dac_clock through the UniFFI contract).
    bool LatestClock(CoreAudioClockSample* out) const {
        return core_.LatestClock(out);
    }

    const CoreAudioRenderCore& core() const { return core_; }

private:
    static OSStatus RenderProc(void* inRefCon,
                               AudioUnitRenderActionFlags*,
                               const AudioTimeStamp* inTimeStamp, UInt32,
                               UInt32 inNumberFrames,
                               AudioBufferList* ioData) noexcept {
        auto* self = static_cast<CoreAudioPullAdapter*>(inRefCon);

        // Interleaved client format: the quantum is one packed buffer.
        float* dst = nullptr;
        if (ioData != nullptr && ioData->mNumberBuffers >= 1 &&
            ioData->mBuffers[0].mData != nullptr) {
            dst = static_cast<float*>(ioData->mBuffers[0].mData);
        }

        // ---- pull the clock pair DIRECTLY from the AudioTimeStamp ----------
        // mSampleTime = device frame counter of this quantum's first frame;
        // mHostTime = mach host time of the SAME instant. Publish only a
        // CONSISTENT pair: if either member is missing (never in practice
        // on the output units) skip publication rather than poison the
        // estimator with a half-valid sample.
        const bool sample_valid = inTimeStamp != nullptr &&
                                  (inTimeStamp->mFlags &
                                   kAudioTimeStampSampleTimeValid) != 0;
        const bool host_valid = inTimeStamp != nullptr &&
                                (inTimeStamp->mFlags &
                                 kAudioTimeStampHostTimeValid) != 0;
        uint64_t sample_frames = 0;
        uint64_t host_ns = 0;
        if (sample_valid && host_valid) {
            const Float64 st = inTimeStamp->mSampleTime;
            sample_frames = st > 0.0 ? static_cast<uint64_t>(st) : 0ull;
            host_ns = MachTimeToNs(inTimeStamp->mHostTime,
                                   self->timebase_.numer,
                                   self->timebase_.denom);
            self->core_.Render(inNumberFrames, dst, sample_frames, host_ns,
                               /*publish_clock=*/true);
        } else {
            // Degenerate timestamp: render the audio, publish nothing.
            self->core_.Render(inNumberFrames, dst, 0, 0,
                               /*publish_clock=*/false);
        }
        return noErr;
    }

    void TeardownUnit_() {
        if (unit_ != nullptr) {
            AudioUnitUninitialize(unit_);
            AudioComponentInstanceDispose(unit_);
            unit_ = nullptr;
        }
    }

    AudioUnit unit_ = nullptr;
    bool running_ = false;
    CoreAudioRenderCore core_;
    std::shared_ptr<sink::AudioSink> sink_;  // lifetime anchor (see Stop)
    mach_timebase_info_data_t timebase_{1, 1};
};

}  // namespace hal
}  // namespace streamify

// ---------------------------------------------------------------------------
// Minimal C facade — Swift shells interop with C, not C++ (Phase 5 will
// bind these directly; the UniFFI engine handle stays the control plane).
// ---------------------------------------------------------------------------

extern "C" {

// Start pulling for a sink registered in the SinkRegistry. Returns an
// opaque adapter handle (> 0) or 0 on failure. The handle is owned by the
// facade and must be released with streamify_coreaudio_stop.
int64_t streamify_coreaudio_start(int64_t sink_handle) {
    auto* adapter = new (std::nothrow) streamify::hal::CoreAudioPullAdapter();
    if (adapter == nullptr) return 0;
    if (!adapter->Start(sink_handle)) {
        delete adapter;
        return 0;
    }
    return reinterpret_cast<int64_t>(adapter);
}

// Stop and release the adapter (idempotent; 0/unknown handles are no-ops).
void streamify_coreaudio_stop(int64_t adapter_handle) {
    if (adapter_handle == 0) return;
    auto* adapter = reinterpret_cast<streamify::hal::CoreAudioPullAdapter*>(
        adapter_handle);
    delete adapter;
}

// Drain the latest DAC presentation clock sample. Writes the consistent
// (frames, rate, host_ns) triple; returns 1 on success, 0 when no render
// has published a sample yet (or the handle is unknown).
int32_t streamify_coreaudio_latest_clock(int64_t adapter_handle,
                                         uint64_t* out_frames,
                                         uint32_t* out_rate,
                                         uint64_t* out_host_ns) {
    if (adapter_handle == 0 || out_frames == nullptr || out_rate == nullptr ||
        out_host_ns == nullptr) {
        return 0;
    }
    auto* adapter = reinterpret_cast<streamify::hal::CoreAudioPullAdapter*>(
        adapter_handle);
    streamify::hal::CoreAudioClockSample sample;
    if (!adapter->LatestClock(&sample)) return 0;
    *out_frames = sample.frames;
    *out_rate = sample.rate;
    *out_host_ns = sample.host_ns;
    return 1;
}

}  // extern "C"

#endif  // __APPLE__
