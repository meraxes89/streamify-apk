// ============================================================================
//  wasapi_pull_adapter.cpp — Phase 4b Windows WASAPI native pull adapter
//  (native/audio/wasapi_pull_adapter.cpp — MP-HAL08)
// ============================================================================
//
//  Event-driven WASAPI render client pulling interleaved IEEE-754 f32
//  stereo frames DIRECTLY from the hardened `AudioSink` SPSC ring — zero
//  FFI crossings, zero quantization, zero dynamic allocation, zero locks
//  and zero system calls on the render thread's pull cycle (master plan
//  invariants 5).
//
//  The RT-critical logic lives in the host-testable
//  `streamify::hal::WasapiRenderCore` (include/wasapi_render_core.h);
//  this TU adds only the Windows COM/MMDevice scaffolding:
//
//      Start()   [control thread]
//        1. resolve the sink from the registry (shared_ptr keeps it alive)
//        2. CoCreateInstance the MMDeviceEnumerator, pick the default
//           eRender/eConsole endpoint, Activate(IAudioClient)
//        3. negotiate the client, in order of preference:
//             a. SHARED_LOW_LATENCY — IAudioClient3 (ISimpleAudioVolume era
//                API): GetSharedModeEnginePeriod for the smallest period in
//                [DefaultDevicePeriod .. MinDevicePeriod] and
//                InitializeSharedEnginePeriod — the canonical 48 kHz f32
//                stereo mix format path (no rate conversion when the
//                engine already runs canonical).
//             b. SHARED — standard IAudioClient::Initialize(AUDCLNT_SHAREMODE_
//                SHARED) on the canonical format with
//                AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
//                AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY_MATCH (Windows
//                converts at the engine boundary; the ring stays canonical
//                — master plan invariant 6) when the device mix format is
//                not natively canonical.
//             c. EXCLUSIVE — AUDCLNT_SHAREMODE_EXCLUSIVE with an explicit
//                WAVEFORMATEXTENSIBLE f32 descriptor and a period-aligned
//                buffer; when the endpoint rejects it we fall back to (b).
//        4. create the buffer-completion event, SetEventHandle, get the
//           IAudioRenderClient + IAudioClock services, Start()
//        5. spin the render thread (below)
//
//      render thread  [Pro Audio MMCSS]
//        AvSetMmThreadCharacteristicsW(L"Pro Audio", &task_index) at start
//        (AvRevertMmThreadCharacteristics on exit), then per cycle:
//          WaitForSingleObject(event)               — the driver paces us
//          GetCurrentPadding()                      — (1): variable quantum
//          frames = buffer_frame_count - padding
//          GetBuffer(frames)                        — the destination window
//          core_.PullFrames(frames, dst)            — pull from the ring
//          ReleaseBuffer(frames, 0)
//          IAudioClock::GetPosition(&pos, &qpc)     — CONSISTENT pair
//          core_.PublishClock(ScaleDevicePosition(pos, freq, rate),
//                            qpc -> ns)
//
//      Stop()    [control thread]
//        core_.Unbind() (atomic pointer retract — an in-flight pull
//        degrades to digital silence, never a use-after-free), signal the
//        stop event, join the render thread, IAudioClient::Stop/Reset,
//        release every COM interface and only then drop the sink
//        shared_ptr.
//
//  IAudioClock::GetPosition returns (device position in
//  IAudioClock::GetFrequency ticks, QueryPerformanceCounter capture in the
//  counter's own units). QPC units are 100 ns on every Windows 10+ box the
//  CI fleet images (QPF == 10 MHz); the adapter converts through the ACTUAL
//  QueryPerformanceFrequency when it differs, using the core's saturating
//  fast path only in the 10 MHz case.
//
//  This TU compiles ONLY on Windows targets (CMake guards it with
//  `if(WIN32)`; the contents are additionally fenced with _WIN32).
// ============================================================================

#if defined(_WIN32)

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <audioclient.h>
#include <avrt.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <thread>

#include "../include/wasapi_render_core.h"
#include "../include/streamify_audio_sinks.h"

// (audclnt streaming flags older SDKs miss)
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY_MATCH
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY_MATCH 0x08000000
#endif
#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM 0x80000000
#endif

// Link avrt only under MSVC via pragma (MinGW GCC warns on pragma
// comment; the CMake target links avrt explicitly there).
#if defined(_MSC_VER)
#pragma comment(lib, "avrt.lib")
#endif

namespace streamify {
namespace hal {

// Canonical stream geometry (master plan invariant 5/6): the ring is
// always 48 kHz interleaved f32 stereo; every WASAPI share mode is fed
// from the same canonical edge.
constexpr uint32_t kCanonicalRate = 48000;
constexpr WORD kCanonicalChannels = 2;
constexpr DWORD kCanonicalBits = 32;

// The negotiated client mode (diagnostic + teardown bookkeeping).
enum class WasapiMode {
    kSharedLowLatency,  // IAudioClient3 shared engine period
    kShared,            // IAudioClient shared + AUTOCONVERTPCM
    kExclusive,         // event-driven exclusive
    kFailed,            // never started
};

class WasapiPullAdapter {
public:
    // ---- control thread -------------------------------------------------------
    bool Start(int64_t sink_handle) {
        if (running_) Stop();
        sink_ = sink::SinkRegistry::instance().resolve(sink_handle);
        if (sink_ == nullptr) return false;

        if (!OpenClient_()) {
            TeardownClient_();
            sink_.reset();
            return false;
        }

        stop_event_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (stop_event_ == nullptr) {
            TeardownClient_();
            sink_.reset();
            return false;
        }

        // Arm: bind the core (the render thread pulls through it), THEN
        // start the hardware and the thread.
        core_.Bind(sink_.get(), kCanonicalRate);
        render_thread_ = std::thread(&WasapiPullAdapter::RenderLoop_, this);
        if (audio_client_->Start() != S_OK) {
            core_.Unbind();
            JoinRenderThread_();
            TeardownClient_();
            sink_.reset();
            return false;
        }
        running_ = true;
        return true;
    }

    // Ordered teardown (see the file header): retract the pointer, wake +
    // join the render thread, stop/reset the endpoint, release COM, then
    // drop the last sink owner.
    void Stop() {
        if (!running_) return;
        running_ = false;
        core_.Unbind();
        if (stop_event_ != nullptr) SetEvent(stop_event_);
        if (audio_client_ != nullptr) audio_client_->Stop();
        JoinRenderThread_();
        if (audio_client_ != nullptr) audio_client_->Reset();
        TeardownClient_();
        sink_.reset();  // last owner release — after Unbind, always safe
    }

    ~WasapiPullAdapter() { Stop(); }

    WasapiPullAdapter() = default;
    WasapiPullAdapter(const WasapiPullAdapter&) = delete;
    WasapiPullAdapter& operator=(const WasapiPullAdapter&) = delete;

    // Latest DAC presentation clock sample (control thread; feed it to
    // StreamifyEngine::record_dac_clock through the UniFFI contract).
    bool LatestClock(WasapiClockSample* out) const {
        return core_.LatestClock(out);
    }

    WasapiMode mode() const { return mode_; }
    const WasapiRenderCore& core() const { return core_; }

private:
    // --------------------------------------------------------------------------
    // Client negotiation. Tries IAudioClient3 low-latency shared first (only
    // when the endpoint's shared engine format IS canonical — the whole
    // point of the fast path), then plain shared with AUTOCONVERTPCM, then
    // exclusive, then shared again as the terminal fallback.
    // --------------------------------------------------------------------------
    bool OpenClient_() {
        mode_ = WasapiMode::kFailed;
        if (CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr,
                             CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                             reinterpret_cast<void**>(&enumerator_)) != S_OK) {
            return false;
        }
        IMMDevice* device = nullptr;
        if (enumerator_->GetDefaultAudioEndpoint(eRender, eConsole,
                                                 &device) != S_OK) {
            return false;
        }
        const HRESULT activated =
            device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                            reinterpret_cast<void**>(&audio_client_));
        device->Release();
        if (activated != S_OK) {
            audio_client_ = nullptr;
            return false;
        }

        WAVEFORMATEX* mix = nullptr;
        if (audio_client_->GetMixFormat(&mix) != S_OK || mix == nullptr) {
            return false;
        }
        const bool mix_is_canonical =
            mix->nSamplesPerSec == kCanonicalRate &&
            mix->wBitsPerSample == kCanonicalBits &&
            mix->nChannels == kCanonicalChannels &&
            (mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
             (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE && mix->cbSize >= 22 &&
              reinterpret_cast<WAVEFORMATEXTENSIBLE*>(mix)->SubFormat ==
                  KSDATAFORMAT_SUBTYPE_IEEE_FLOAT));

        if (mix_is_canonical && TrySharedLowLatency_(mix)) {
            mode_ = WasapiMode::kSharedLowLatency;
            CoTaskMemFree(mix);
            return ArmStream_();
        }
        if (TryExclusive_()) {
            mode_ = WasapiMode::kExclusive;
            CoTaskMemFree(mix);
            return ArmStream_();
        }
        const bool shared_ok = TrySharedFallback_(mix);
        CoTaskMemFree(mix);
        if (!shared_ok) return false;
        mode_ = WasapiMode::kShared;
        return ArmStream_();
    }

    // IAudioClient3: pick the smallest engine period the endpoint offers
    // and init the shared engine with that exact period. Requires a
    // canonical mix format (no conversion) and the interface actually being
    // present (Windows 10 TH1+; graceful standard-client fallback
    // otherwise).
    //
    // Signature note (cross-verified against the MinGW-w64 SDK headers):
    // GetSharedModeEnginePeriod takes FIVE out-params — the fundamental
    // period is the granularity every valid period must be a multiple of —
    // and the initializer is InitializeSharedAudioStream(flags, period,
    // format, guid) (the IDL name; "InitializeSharedEnginePeriod" is a
    // documentation-side misremembering that does not exist on the
    // interface).
    bool TrySharedLowLatency_(const WAVEFORMATEX* mix) {
        IAudioClient3* client3 = nullptr;
        if (audio_client_->QueryInterface(
                __uuidof(IAudioClient3),
                reinterpret_cast<void**>(&client3)) != S_OK) {
            return false;
        }
        UINT32 default_period = 0, fundamental_period = 0;
        UINT32 min_period = 0, max_period = 0;
        if (client3->GetSharedModeEnginePeriod(
                mix, &default_period, &fundamental_period, &min_period,
                &max_period) != S_OK) {
            client3->Release();
            return false;
        }
        // Smallest period the engine allows, rounded ONTO the fundamental
        // grid (valid IAudioClient3 periods are whole multiples of the
        // fundamental period; a misaligned period is rejected by the
        // engine). Bounded by the default period — low latency, but never
        // a slower-than-default cadence.
        period_frames_ = min_period;
        if (fundamental_period > 1) {
            period_frames_ =
                ((min_period + fundamental_period - 1) /
                 fundamental_period) *
                fundamental_period;
        }
        if (period_frames_ == 0 || period_frames_ > default_period) {
            period_frames_ = default_period;
        }
        const HRESULT hr = client3->InitializeSharedAudioStream(
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period_frames_, mix, nullptr);
        client3->Release();
        if (FAILED(hr)) {
            period_frames_ = 0;
            return false;
        }
        return audio_client_->GetBufferSize(&buffer_frames_) == S_OK;
    }

    // Plain shared client on the CANONICAL format: the engine converts at
    // its boundary (AUTOCONVERTPCM + default SRC quality) and the ring
    // stays canonical (master plan invariant 6). Event-driven, one
    // default-period-ish buffer of headroom.
    bool TrySharedFallback_(const WAVEFORMATEX* mix) {
        WAVEFORMATEX canonical;
        BuildCanonicalPcm_(&canonical);
        const REFERENCE_TIME duration =
            MixPeriod100Ns_(mix) * kSharedBufferPeriods;
        const HRESULT hr = audio_client_->Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
                AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY_MATCH,
            duration, 0, &canonical, nullptr);
        if (FAILED(hr)) return false;
        if (audio_client_->GetBufferSize(&buffer_frames_) != S_OK) {
            return false;
        }
        period_frames_ = buffer_frames_ / kSharedBufferPeriods;
        return true;
    }

    // Event-driven exclusive on an explicit WAVEFORMATEXTENSIBLE f32
    // descriptor at a period-aligned buffer size. Only endpoints that
    // natively accept the canonical format will take it; failure falls
    // back to shared. The classic exclusive shape: buffer duration ==
    // periodicity == one device period (the endpoint drives the cadence
    // through the event).
    bool TryExclusive_() {
        WAVEFORMATEXTENSIBLE ext;
        BuildCanonicalExtensible_(&ext);
        const REFERENCE_TIME period = kExclusivePeriod100Ns;
        period_frames_ = static_cast<UINT32>(
            kCanonicalRate * period / kHnsPerSecond);
        const HRESULT hr = audio_client_->Initialize(
            AUDCLNT_SHAREMODE_EXCLUSIVE,
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, &ext.Format,
            nullptr);
        if (FAILED(hr)) {
            period_frames_ = 0;
            return false;
        }
        if (audio_client_->GetBufferSize(&buffer_frames_) != S_OK) {
            return false;
        }
        return true;
    }

    // Event handle + render/clock services. Shared by every mode.
    bool ArmStream_() {
        buffer_event_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (buffer_event_ == nullptr) return false;
        if (audio_client_->SetEventHandle(buffer_event_) != S_OK) {
            return false;
        }
        if (audio_client_->GetService(
                __uuidof(IAudioRenderClient),
                reinterpret_cast<void**>(&render_client_)) != S_OK) {
            return false;
        }
        if (audio_client_->GetService(
                __uuidof(IAudioClock),
                reinterpret_cast<void**>(&audio_clock_)) != S_OK) {
            audio_clock_ = nullptr;  // clock becomes unavailable, audio OK
        }
        return true;
    }

    // --------------------------------------------------------------------------
    // The render thread (Pro Audio MMCSS). See the file header for the
    // cycle layout; everything RT-critical is WasapiRenderCore.
    // --------------------------------------------------------------------------
    void RenderLoop_() {
        DWORD task_index = 0;
        HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio",
                                                     &task_index);
        LARGE_INTEGER qpf{};
        QueryPerformanceFrequency(&qpf);
        const bool qpc_is_100ns = qpf.QuadPart == 10000000;

        HANDLE wake[2] = {stop_event_, buffer_event_};
        while (running_.load(std::memory_order_relaxed)) {
            const DWORD wait =
                WaitForMultipleObjects(2, wake, FALSE, kWakeTimeoutMs);
            if (wait != WAIT_OBJECT_0 + 1) continue;  // stop/timeout: re-poll

            UINT32 padding = 0;
            if (audio_client_->GetCurrentPadding(&padding) != S_OK) continue;
            const UINT32 frames =
                WasapiRenderCore::FramesToRender(buffer_frames_, padding);
            if (frames == 0) continue;

            BYTE* dst = nullptr;
            if (render_client_->GetBuffer(frames, &dst) != S_OK || dst == nullptr) {
                continue;
            }
            // The pull cycle: everything inside is allocation/lock/syscall
            // free by construction (audited on the host lanes through the
            // shared WasapiRenderCore).
            core_.PullFrames(frames, reinterpret_cast<float*>(dst));
            render_client_->ReleaseBuffer(frames, 0);

            // Clock ingestion: the CONSISTENT (position, qpc) pair.
            if (audio_clock_ != nullptr) {
                UINT64 position = 0, frequency = 0, qpc = 0;
                if (audio_clock_->GetFrequency(&frequency) == S_OK &&
                    audio_clock_->GetPosition(&position, &qpc) == S_OK) {
                    const uint64_t host_ns = qpc_is_100ns
                        ? WasapiRenderCore::Qpc100ToNs(qpc)
                        : ScaleQpc_(qpc, qpf.QuadPart);
                    core_.PublishClock(
                        WasapiRenderCore::ScaleDevicePosition(
                            position, frequency, kCanonicalRate),
                        host_ns);
                }
            }
        }

        if (mmcss != nullptr) AvRevertMmThreadCharacteristics(mmcss);
    }

    static uint64_t ScaleQpc_(uint64_t qpc, uint64_t qpf) {
        // General QPF path (ns = qpc * 1e9 / qpf); 128-bit when available.
#if defined(__SIZEOF_INT128__)
        const unsigned __int128 ns =
            (static_cast<unsigned __int128>(qpc) * 1000000000ull) / qpf;
        return static_cast<uint64_t>(ns);
#else
        return static_cast<uint64_t>(
            (static_cast<double>(qpc) * 1e9) / static_cast<double>(qpf));
#endif
    }

    static void BuildCanonicalPcm_(WAVEFORMATEX* fmt) {
        std::memset(fmt, 0, sizeof(*fmt));
        fmt->wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
        fmt->nChannels = kCanonicalChannels;
        fmt->nSamplesPerSec = kCanonicalRate;
        fmt->wBitsPerSample = kCanonicalBits;
        fmt->nBlockAlign =
            fmt->nChannels * fmt->wBitsPerSample / 8;  // 8 bytes
        fmt->nAvgBytesPerSec = fmt->nSamplesPerSec * fmt->nBlockAlign;
    }

    static void BuildCanonicalExtensible_(WAVEFORMATEXTENSIBLE* ext) {
        BuildCanonicalPcm_(&ext->Format);
        ext->Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
        ext->Format.cbSize = 22;
        ext->Samples.wValidBitsPerSample = kCanonicalBits;
        ext->dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
        ext->SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
    }

    static REFERENCE_TIME MixPeriod100Ns_(const WAVEFORMATEX* mix) {
        // One period of the mix format, in 100 ns units (used to size the
        // shared buffer). Defensive against a zero rate.
        if (mix->nSamplesPerSec == 0) return kDefaultPeriod100Ns;
        const uint64_t hns =
            (kHnsPerSecond + mix->nSamplesPerSec - 1) / mix->nSamplesPerSec;
        return static_cast<REFERENCE_TIME>(hns);
    }

    void JoinRenderThread_() {
        if (render_thread_.joinable()) render_thread_.join();
    }

    void TeardownClient_() {
        if (audio_clock_ != nullptr) {
            audio_clock_->Release();
            audio_clock_ = nullptr;
        }
        if (render_client_ != nullptr) {
            render_client_->Release();
            render_client_ = nullptr;
        }
        if (audio_client_ != nullptr) {
            audio_client_->Release();
            audio_client_ = nullptr;
        }
        if (enumerator_ != nullptr) {
            enumerator_->Release();
            enumerator_ = nullptr;
        }
        if (buffer_event_ != nullptr) {
            CloseHandle(buffer_event_);
            buffer_event_ = nullptr;
        }
        if (stop_event_ != nullptr) {
            CloseHandle(stop_event_);
            stop_event_ = nullptr;
        }
    }

    // ---- constants --------------------------------------------------------------
    static constexpr REFERENCE_TIME kHnsPerSecond = 10000000;  // 100 ns units
    static constexpr REFERENCE_TIME kExclusivePeriod100Ns =
        1000000;  // 1 ms — the classic exclusive target
    static constexpr REFERENCE_TIME kDefaultPeriod100Ns = 3000000;  // 3 ms
    static constexpr UINT32 kSharedBufferPeriods = 4;
    static constexpr DWORD kWakeTimeoutMs = 2000;

    // ---- platform state (control-thread owned; released in Teardown) ------------
    IMMDeviceEnumerator* enumerator_ = nullptr;
    IAudioClient* audio_client_ = nullptr;
    IAudioRenderClient* render_client_ = nullptr;
    IAudioClock* audio_clock_ = nullptr;
    HANDLE buffer_event_ = nullptr;
    HANDLE stop_event_ = nullptr;
    UINT32 buffer_frames_ = 0;
    UINT32 period_frames_ = 0;
    WasapiMode mode_ = WasapiMode::kFailed;

    // ---- driver state --------------------------------------------------------------
    std::atomic<bool> running_{false};
    std::thread render_thread_;
    WasapiRenderCore core_;
    std::shared_ptr<sink::AudioSink> sink_;  // lifetime anchor (see Stop)
};

}  // namespace hal
}  // namespace streamify

// ---------------------------------------------------------------------------
// Minimal C facade (mirrors the Phase 4a CoreAudio facade — the desktop
// shells/UniFFI control plane interop with C, not C++).
// ---------------------------------------------------------------------------

extern "C" {

// Start pulling for a sink registered in the SinkRegistry. Returns an
// opaque adapter handle (> 0) or 0 on failure. Release with
// streamify_wasapi_stop.
int64_t streamify_wasapi_start(int64_t sink_handle) {
    auto* adapter = new (std::nothrow) streamify::hal::WasapiPullAdapter();
    if (adapter == nullptr) return 0;
    if (!adapter->Start(sink_handle)) {
        delete adapter;
        return 0;
    }
    return reinterpret_cast<int64_t>(adapter);
}

// Stop and release the adapter (idempotent; 0/unknown handles are no-ops).
void streamify_wasapi_stop(int64_t adapter_handle) {
    if (adapter_handle == 0) return;
    delete reinterpret_cast<streamify::hal::WasapiPullAdapter*>(
        adapter_handle);
}

// Drain the latest DAC presentation clock sample. Writes the consistent
// (frames, rate, host_ns) triple; returns 1 on success, 0 when no sample
// has been published yet (or the handle is unknown).
int32_t streamify_wasapi_latest_clock(int64_t adapter_handle,
                                      uint64_t* out_frames,
                                      uint32_t* out_rate,
                                      uint64_t* out_host_ns) {
    if (adapter_handle == 0 || out_frames == nullptr || out_rate == nullptr ||
        out_host_ns == nullptr) {
        return 0;
    }
    auto* adapter = reinterpret_cast<streamify::hal::WasapiPullAdapter*>(
        adapter_handle);
    streamify::hal::WasapiClockSample sample;
    if (!adapter->LatestClock(&sample)) return 0;
    *out_frames = sample.frames;
    *out_rate = sample.rate;
    *out_host_ns = sample.host_ns;
    return 1;
}

}  // extern "C"

#endif  // _WIN32
