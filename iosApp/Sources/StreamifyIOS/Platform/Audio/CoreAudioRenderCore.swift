import Darwin
import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-01 (Phase 5 / MP-HAL04): the Swift half of the CoreAudio pull
// adapter — the driver seam over the native C facade
// (`native/audio/coreaudio_pull_adapter.cpp`, the `streamify_coreaudio_*`
// symbols).
//
// ARCHITECTURE (mirrors the EngineGateway probe-gate philosophy exactly):
//
//   native AURenderCallback (C++, RT thread)
//     └─ CoreAudioRenderCore::Render()      [seqlock clock publication]
//   control thread (this file's driver)
//     └─ streamify_coreaudio_latest_clock() [dlsym-resolved C facade]
//     └─ DacClockSample                     [shared presentation model]
//     └─ DacClockPublisher                  [anchor + extrapolate → UI/engine]
//
// The native object ships inside Alpha's StreamifyCore.xcframework bundle;
// until it is linked, `NativeCoreAudioDriver.start` resolves nothing via
// dlsym(RTLD_DEFAULT) and degrades to an explicit `.unavailable` failure —
// never a crash, exactly like the Rust side's dlsym bridge for
// `ios_audio_session` (see native/include/ios_audio_session.h). The
// `SimulatedCoreAudioDriver` provides the VirtualHAL-parity deterministic
// DAC clock for previews and the XCTest lane (same role as
// PreviewEngineGateway on the engine seam).
//
// ZERO Swift runtime state is reachable from the audio thread: this file
// only ever touches the control-plane facade (start/stop/clock/telemetry).
// The RT invariants (no allocation, no locks, no Swift, no I/O in the
// callback) are enforced and audited on the native side
// (test_coreaudio_render_core.cpp sections E/H/I — 41/41 host-lane gates).
// ─────────────────────────────────────────────────────────────────────────────

/// Cumulative real-time telemetry drained from the native render core
/// (control plane only — relaxed counters, safe to read from any non-audio
/// thread).
struct CoreAudioTelemetry: Equatable, Sendable {
    /// Total AURenderCallback invocations since adapter start.
    let callbackCount: Int
    /// Quanta that had to zero-fill a starved tail (target: 0 — the
    /// acceptance suite's steady-state leg).
    let underrunCallbacks: Int

    var isHealthy: Bool { underrunCallbacks == 0 }
}

/// Why a native driver start attempt refused (diagnostics surface).
enum CoreAudioDriverStartStatus: Equatable, Sendable {
    case started
    /// The `streamify_coreaudio_*` symbols are not linked into this process
    /// yet (pre-XCFramework scaffold period, or a non-Apple host).
    case nativeFacadeUnavailable
    /// The facade answered but the sink handle is unknown / not provided.
    case invalidSinkHandle
    /// The output unit failed to open/initialize (device busy, no route).
    case deviceOpenFailed
}

/// The native-adapter seam. `NativeCoreAudioDriver` binds the real facade;
/// `SimulatedCoreAudioDriver` feeds a deterministic clock for previews and
/// tests (the VirtualHAL discipline: callback cadence and clock drift
/// WITHOUT any audio hardware).
protocol CoreAudioDriver: AnyObject {
    /// Arms the pull adapter for the sink registered under `sinkHandle`.
    func start(sinkHandle: Int64) -> CoreAudioDriverStartStatus
    /// Ordered teardown (pointer retract BEFORE hardware stop — see the
    /// native header's lifetime contract). Idempotent.
    func stop()
    /// Drains the latest CONSISTENT (frames, rate, host_ns) triple; nil
    /// before the first render publishes.
    func latestClock() -> DacClockSample?
    /// Cumulative RT counters, nil when the driver is not running.
    func telemetry() -> CoreAudioTelemetry?
    var isRunning: Bool { get }
}

// MARK: - Native driver (dlsym late binding — compiles standalone, lights
// up the moment the XCFramework's object is linked)

/// Binds the frozen C facade from `coreaudio_pull_adapter.cpp`:
///
///   int64_t streamify_coreaudio_start(int64_t sink_handle);
///   void    streamify_coreaudio_stop(int64_t adapter_handle);
///   int32_t streamify_coreaudio_latest_clock(int64_t, uint64_t*,
///                                            uint32_t*, uint64_t*);
///   int32_t streamify_coreaudio_telemetry(int64_t, uint64_t*, uint64_t*);
///
/// The symbols are resolved ONCE at `start` (control thread); a missing
/// symbol is a clean `.nativeFacadeUnavailable`, never a link error — the
/// package keeps compiling without the native object (the scaffold period)
/// exactly like `#if canImport(uniffi_streamify)` gates the engine lane.
final class NativeCoreAudioDriver: CoreAudioDriver {

    private var adapterHandle: Int64 = 0
    private var startFn: StartFn?
    private var stopFn: StopFn?
    private var latestClockFn: LatestClockFn?
    private var telemetryFn: TelemetryFn?

    private typealias StartFn = @convention(c) (Int64) -> Int64
    private typealias StopFn = @convention(c) (Int64) -> Void
    private typealias LatestClockFn = @convention(c) (
        Int64, UnsafeMutablePointer<UInt64>?, UnsafeMutablePointer<UInt32>?,
        UnsafeMutablePointer<UInt64>?
    ) -> Int32
    private typealias TelemetryFn = @convention(c) (
        Int64, UnsafeMutablePointer<UInt64>?, UnsafeMutablePointer<UInt64>?
    ) -> Int32

    var isRunning: Bool { adapterHandle != 0 }

    init() {}

    func start(sinkHandle: Int64) -> CoreAudioDriverStartStatus {
        stop()
        guard resolveFacade() else { return .nativeFacadeUnavailable }
        guard let startFn else { return .nativeFacadeUnavailable }
        let handle = startFn(sinkHandle)
        guard handle != 0 else { return .deviceOpenFailed }
        adapterHandle = handle
        return .started
    }

    func stop() {
        guard adapterHandle != 0 else { return }
        stopFn?(adapterHandle)
        adapterHandle = 0
    }

    func latestClock() -> DacClockSample? {
        guard adapterHandle != 0, let latestClockFn else { return nil }
        var frames: UInt64 = 0
        var rate: UInt32 = 0
        var hostNs: UInt64 = 0
        guard latestClockFn(adapterHandle, &frames, &rate, &hostNs) == 1 else {
            return nil
        }
        return DacClockSample(
            framePosition: Int(clamping: frames),
            sampleRateHz: Int(clamping: rate),
            monotonicNs: Int(clamping: hostNs)
        )
    }

    func telemetry() -> CoreAudioTelemetry? {
        guard adapterHandle != 0, let telemetryFn else { return nil }
        var callbacks: UInt64 = 0
        var underruns: UInt64 = 0
        guard telemetryFn(adapterHandle, &callbacks, &underruns) == 1 else {
            return nil
        }
        return CoreAudioTelemetry(
            callbackCount: Int(clamping: callbacks),
            underrunCallbacks: Int(clamping: underruns)
        )
    }

    deinit { stop() }

    /// Resolves the four facade symbols from the process' global scope
    /// (RTLD_DEFAULT) once per start attempt.
    private func resolveFacade() -> Bool {
        guard startFn == nil || latestClockFn == nil || stopFn == nil
            || telemetryFn == nil else { return true }
        let rtldDefault = UnsafeMutableRawPointer(bitPattern: -2)
        guard
            let startSym = dlsym(rtldDefault, "streamify_coreaudio_start"),
            let stopSym = dlsym(rtldDefault, "streamify_coreaudio_stop"),
            let clockSym = dlsym(rtldDefault, "streamify_coreaudio_latest_clock"),
            let telemetrySym = dlsym(rtldDefault, "streamify_coreaudio_telemetry")
        else {
            return false
        }
        startFn = unsafeBitCast(startSym, to: StartFn.self)
        stopFn = unsafeBitCast(stopSym, to: StopFn.self)
        latestClockFn = unsafeBitCast(clockSym, to: LatestClockFn.self)
        telemetryFn = unsafeBitCast(telemetrySym, to: TelemetryFn.self)
        return true
    }
}

// MARK: - Simulated driver (VirtualHAL parity — previews & the test lane)

/// Deterministic 48 kHz DAC clock WITHOUT hardware: the Swift twin of the
/// native VirtualHAL (callback cadence + a configurable oscillator drift),
/// used by SwiftUI previews, the pre-XCFramework scaffold period, and the
/// XCTest acceptance lane (the `<5 ms` interpolation-error proof runs on
/// exactly this driver's math on the native side as well).
///
/// The clock advances ONLY through `advance(byFrames:)` /
/// `advance(byDuration:)` (virtual time — no wall clock, no timers), so
/// tests are fully deterministic.
final class SimulatedCoreAudioDriver: CoreAudioDriver {

    private(set) var framePosition: Int = 0
    /// Exact (fractional) frame counter: the DAC's oscillator error
    /// accumulates CONTINUOUSLY — truncating each quantum's contribution
    /// would erase the drift entirely (27.4 ppm × 100 ms ≈ 0.13 frames,
    /// truncated away every step). The integer `framePosition` is the
    /// presented value; the fraction carries between quanta like a real
    /// PLL's phase accumulator.
    private var exactFramePosition: Double = 0
    let sampleRateHz: Int
    /// Oscillator error applied by `advance(byDuration:)` (ppm; positive =
    /// the simulated DAC presents MORE frames than nominal).
    let driftPpm: Double
    private(set) var monotonicNs: Int
    private var running = false

    init(
        sampleRateHz: Int = 48_000,
        startMonotonicNs: Int = 10_000_000_000,
        driftPpm: Double = 27.4
    ) {
        self.sampleRateHz = sampleRateHz
        self.monotonicNs = startMonotonicNs
        self.driftPpm = driftPpm
    }

    var isRunning: Bool { running }

    func start(sinkHandle: Int64) -> CoreAudioDriverStartStatus {
        running = true
        return .started
    }

    func stop() { running = false }

    /// Presents exactly `frames` more frames at the NOMINAL rate.
    func advance(byFrames frames: Int) {
        exactFramePosition += Double(frames)
        framePosition = Int(exactFramePosition)
        monotonicNs += frames * 1_000_000_000 / sampleRateHz
    }

    /// Advances virtual wall time by `ns`; the frame counter advances with
    /// the DRIFTED oscillator (frames = ns * rate * (1 + ppm/1e6)), the
    /// fractional part carrying across quanta.
    func advance(byDurationNs ns: Int) {
        monotonicNs += ns
        exactFramePosition += Double(ns) * Double(sampleRateHz)
            * (1.0 + driftPpm / 1_000_000.0) / 1_000_000_000.0
        framePosition = Int(exactFramePosition)
    }

    func latestClock() -> DacClockSample? {
        guard running else { return nil }
        return DacClockSample(
            framePosition: framePosition,
            sampleRateHz: sampleRateHz,
            monotonicNs: monotonicNs
        )
    }

    func telemetry() -> CoreAudioTelemetry? {
        guard running else { return nil }
        // The simulated DAC never starves: the acceptance narrative's
        // "0 underruns" steady state.
        return CoreAudioTelemetry(callbackCount: 0, underrunCallbacks: 0)
    }
}

// MARK: - The render-core facade the shell drives

/// The shell-facing CoreAudio render core: owns ONE driver (native when the
/// facade is linked, simulated for previews/tests), drains its clock, and
/// hands `DacClockSample`s to a `DacClockPublisher` for the `<5 ms`
/// anchor-and-extrapolate position discipline (MP-DEC-03).
@MainActor
final class CoreAudioRenderCore {

    let driver: CoreAudioDriver
    private(set) var lastStartStatus: CoreAudioDriverStartStatus?

    init(driver: CoreAudioDriver = NativeCoreAudioDriver()) {
        self.driver = driver
    }

    /// Arms the pull adapter for the engine-registered sink. A nil handle
    /// (pre-engine scaffold) fails cleanly — never a crash.
    @discardableResult
    func start(sinkHandle: Int64?) -> CoreAudioDriverStartStatus {
        guard let sinkHandle else {
            lastStartStatus = .invalidSinkHandle
            return .invalidSinkHandle
        }
        let status = driver.start(sinkHandle: sinkHandle)
        lastStartStatus = status
        return status
    }

    func stop() {
        driver.stop()
    }

    var isRunning: Bool { driver.isRunning }

    func latestClock() -> DacClockSample? { driver.latestClock() }

    func telemetry() -> CoreAudioTelemetry? { driver.telemetry() }
}
