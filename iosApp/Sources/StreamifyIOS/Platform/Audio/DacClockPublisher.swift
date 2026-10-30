import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-01 (Phase 5 / MP-HAL06): the DAC clock publisher — the Swift twin
// of Android's `DacClockBridge.kt` (the Beta subtree's clock-bridge lane).
//
// The hardware HAL callback is the SOLE clock master (MP-DEC-03): playback
// position, seek confirmation and UI progress derive strictly from the
// `DacPresentationClock` triple the native render core publishes. The UI
// NEVER runs a wall-clock position (`Date()` is banned from every position
// estimate): the estimator ANCHORS on the latest hardware sample and
// advances by the monotonic delta between the sample's `monotonicNs` and
// the query instant — both endpoints live on the same OS monotonic domain
// and the slope is the device DAC nominal rate. That is not wall-clock
// interpolation; the native acceptance suite pins the discipline under
// 5 ms across 60 s of drifted DAC playback
// (native/tests/test_coreaudio_render_core.cpp, section H).
//
// SIGNAL PATH (duty-cycle drain, latest-wins, zero audio-thread contact):
//
//   native render core (RT) ─ seqlock publication (frames, host_ns, rate)
//   DacClockPublisher      ─ 10 Hz drain of the driver's LatestClock
//                           ─ AsyncStream<DacClockSample> → UI
//                           ─ onSample closure            → engine
//                             (UDL `record_dac_clock`: republished inside
//                              every pushed EngineSnapshot; called from a
//                              non-audio thread per contract)
// ─────────────────────────────────────────────────────────────────────────────

/// Pure translation + interpolation math over `DacClockSample` — the Swift
/// mirror of Android's `DacClockTranslator`. Value semantics, zero mocks,
/// fully unit-testable (the <5 ms acceptance gate runs on exactly these
/// functions).
enum DacClockTranslator {

    /// The master-plan acceptance budget (MP-DEC-03): position estimation
    /// error versus the hardware presentation ground truth.
    static let interpolationErrorBudgetMs: Double = 5.0

    /// Anchors beyond this age are considered dead (the DAC stopped
    /// presenting — teardown, route change, or a paused engine): the
    /// estimator holds the anchor's position instead of extrapolating into
    /// the void.
    static let maxAnchorAgeNs: Int = 250_000_000

    /// Position AT the hardware sample (the mirror of
    /// `PlaybackFormatting.positionMs(from:)`, kept here so the clock
    /// subsystem is self-contained for its own acceptance tests).
    static func anchorPositionMs(from clock: DacClockSample) -> Int? {
        guard (8_000...768_000).contains(clock.sampleRateHz) else { return nil }
        guard clock.framePosition >= 0 else { return nil }
        return Int((Double(clock.framePosition) / Double(clock.sampleRateHz)) * 1000.0)
    }

    /// Position NOW, anchored on the hardware sample and advanced by the
    /// MONOTONIC clock delta (nominal-rate slope). This is the estimator
    /// the UI clock runs:
    ///
    ///   pos(now) = frames + (now - monotonicNs) * rate / 1e9
    ///
    /// A sample from "the future" (query instant slightly behind the
    /// publication instant — scheduler jitter) clamps its delta to zero.
    /// A STALE anchor (older than `maxAnchorAgeNs`) returns the anchor's
    /// own position: never extrapolate a dead DAC.
    static func interpolatedPositionMs(
        anchor: DacClockSample,
        nowNs: Int,
        maxAgeNs: Int = DacClockTranslator.maxAnchorAgeNs
    ) -> Int? {
        guard let anchorMs = anchorPositionMs(from: anchor) else { return nil }
        let deltaNs = max(0, nowNs - anchor.monotonicNs)
        if deltaNs > maxAgeNs {
            return anchorMs
        }
        // Content advanced over the monotonic delta at the NOMINAL slope:
        //   frames = deltaNs * rate / 1e9
        //   ms     = frames / rate * 1000  ==  deltaNs / 1e6
        // (the rate cancels — one millisecond of monotonic time is one
        // millisecond of content at the nominal rate; the DAC's actual
        // drift over the fresh-anchor window is the residual the <5 ms
        // budget governs).
        let advancedMs = Double(deltaNs) / 1_000_000.0
        return anchorMs + Int(advancedMs)
    }

    /// Whether an anchor is still fresh enough to extrapolate from.
    static func isFresh(
        anchor: DacClockSample,
        nowNs: Int,
        maxAgeNs: Int = DacClockTranslator.maxAnchorAgeNs
    ) -> Bool {
        let deltaNs = max(0, nowNs - anchor.monotonicNs)
        return deltaNs <= maxAgeNs
    }
}

/// Non-isolated constants home: default arguments evaluate in a
/// NONISOLATED context, so a @MainActor-isolated static cannot back the
/// initializer default below (Swift 6 makes that an error).
enum DacClockPublisherPolicy {
    /// Default drain cadence: 10 Hz. With anchor-and-extrapolate the
    /// position error grows only with DAC drift over the staleness window
    /// (27.4 ppm × 100 ms ≈ 3 µs — three orders of magnitude inside the
    /// 5 ms budget), so 10 Hz is far beyond sufficient while staying
    /// invisible on the CPU budget.
    static let defaultDrainIntervalNs: UInt64 = 100_000_000
}

/// Duty-cycle clock publisher: drains the driver's latest hardware clock
/// sample on a background cadence, republishes it to the UI as an
/// `AsyncStream`, and mirrors each sample into the engine (the
/// `record_dac_clock` UDL seam) through the `onSample` closure.
///
/// The drain is a CONTROL-PLANE poll of a seqlock reader — latest-wins,
/// allocation-light, and it never touches the audio thread (the seqlock
/// exists precisely so this reader is wait-free). It is NOT UI polling of
/// playback state (which MP-DEC-04 abolishes): the shell's state still
/// arrives exclusively through `EngineGateway.snapshots`; this publisher
/// exists so those snapshots carry FRESH hardware clock triples.
@MainActor
final class DacClockPublisher {

    private let driver: CoreAudioDriver
    private let clock: () -> UInt64
    private let drainIntervalNs: UInt64
    private var drainTask: Task<Void, Never>?
    private var continuation: AsyncStream<DacClockSample>.Continuation?
    private(set) var lastSample: DacClockSample?

    /// Engine mirror: invoked once per drained sample on the main actor —
    /// the shell wires this to `record_dac_clock` on the UniFFI lane.
    private let onSample: ((DacClockSample) -> Void)?

    init(
        driver: CoreAudioDriver,
        drainIntervalNs: UInt64 = DacClockPublisherPolicy.defaultDrainIntervalNs,
        clock: @escaping () -> UInt64 = { DispatchTime.now().uptimeNanoseconds },
        onSample: ((DacClockSample) -> Void)? = nil
    ) {
        self.driver = driver
        self.drainIntervalNs = drainIntervalNs
        self.clock = clock
        self.onSample = onSample
    }

    /// The UI-visible sample stream (latest-wins coalescing; the UI
    /// interpolates from each sample with `DacClockTranslator`).
    var samples: AsyncStream<DacClockSample> {
        AsyncStream { continuation in
            self.continuation = continuation
            if let lastSample {
                continuation.yield(lastSample)
            }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.continuation = nil
                }
            }
        }
    }

    /// Starts the duty-cycle drain. Idempotent.
    func start() {
        guard drainTask == nil else { return }
        let intervalNs = drainIntervalNs
        drainTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.drainOnce()
                try? await Task.sleep(nanoseconds: intervalNs)
            }
        }
    }

    /// Stops the drain (the sample stream stays alive for a future start).
    func stop() {
        drainTask?.cancel()
        drainTask = nil
    }

    /// One drain pass — the exact body the cadence task runs, exposed so
    /// the XCTest lane drives virtual time deterministically (no sleeps).
    @discardableResult
    func drainOnce() -> DacClockSample? {
        guard let sample = driver.latestClock() else { return nil }
        // Latest-wins: a fresh drain always supersedes (the seqlock
        // guarantees the pair is untorn).
        lastSample = sample
        continuation?.yield(sample)
        onSample?(sample)
        return sample
    }
}
