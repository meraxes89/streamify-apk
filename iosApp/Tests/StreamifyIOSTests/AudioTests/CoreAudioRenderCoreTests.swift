import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-01 acceptance lane: the Swift half of the CoreAudio pull adapter —
// the driver seam, the simulated (VirtualHAL-parity) DAC clock, and the
// <5 ms anchor-and-extrapolate discipline (the native twin runs as
// test_coreaudio_render_core section H: 41/41 host-lane gates).
// ─────────────────────────────────────────────────────────────────────────────

final class CoreAudioRenderCoreTests: XCTestCase {

    // ── Native driver: probe-gate degradation ─────────────────────────────

    /// The test host links NO streamify_coreaudio_* object: the dlsym
    /// probe must refuse cleanly (never crash, never half-start) — exactly
    /// the pre-XCFramework scaffold story.
    func testNativeDriverDegradesCleanlyWhenFacadeNotLinked() {
        let driver = NativeCoreAudioDriver()
        XCTAssertFalse(driver.isRunning)
        XCTAssertEqual(driver.start(sinkHandle: 1), .nativeFacadeUnavailable)
        XCTAssertFalse(driver.isRunning)
        XCTAssertNil(driver.latestClock())
        XCTAssertNil(driver.telemetry())
        // Idempotent stop after a refused start.
        driver.stop()
        XCTAssertFalse(driver.isRunning)
    }

    // ── Simulated driver: deterministic VirtualHAL clock ─────────────────

    func testSimulatedDriverAdvancesAtNominalRate() {
        let driver = SimulatedCoreAudioDriver()
        XCTAssertEqual(driver.start(sinkHandle: 7), .started)
        XCTAssertTrue(driver.isRunning)

        driver.advance(byFrames: 48_000)  // one second of 48 kHz
        let clock = driver.latestClock()
        XCTAssertNotNil(clock)
        XCTAssertEqual(clock?.framePosition, 48_000)
        XCTAssertEqual(clock?.sampleRateHz, 48_000)
        // Exactly 1 s of monotonic advancement at the nominal rate.
        XCTAssertEqual(clock?.monotonicNs, 10_000_000_000 + 1_000_000_000)
        XCTAssertEqual(driver.telemetry()?.underrunCallbacks, 0)

        driver.stop()
        XCTAssertFalse(driver.isRunning)
        XCTAssertNil(driver.latestClock())
    }

    func testSimulatedDriverDriftAdvancesFramesBeyondNominal() {
        // 27.4 ppm fast oscillator: 100 s of wall time presents ~274 extra
        // frames — the drift regime the <5 ms gate must survive.
        let driver = SimulatedCoreAudioDriver(driftPpm: 27.4)
        driver.start(sinkHandle: 1)
        driver.advance(byDurationNs: 100_000_000_000)
        let clock = driver.latestClock()
        XCTAssertEqual(clock?.monotonicNs, 10_000_000_000 + 100_000_000_000)
        // 4_800_000 nominal + ~274 drifted frames.
        XCTAssertEqual(clock!.framePosition, 4_800_274)
        driver.stop()
    }

    // ── The facade ────────────────────────────────────────────────────────

    func testRenderCoreFacadeDelegatesToItsDriver() {
        let driver = SimulatedCoreAudioDriver()
        let core = CoreAudioRenderCore(driver: driver)

        XCTAssertEqual(core.start(sinkHandle: 42), .started)
        XCTAssertTrue(core.isRunning)
        XCTAssertEqual(core.lastStartStatus, .started)

        driver.advance(byFrames: 512)
        XCTAssertEqual(core.latestClock()?.framePosition, 512)
        XCTAssertEqual(core.telemetry()?.callbackCount, 0)

        core.stop()
        XCTAssertFalse(core.isRunning)
        XCTAssertNil(core.latestClock())
    }

    func testRenderCoreFacadeRejectsMissingSinkHandle() {
        let core = CoreAudioRenderCore(driver: SimulatedCoreAudioDriver())
        XCTAssertEqual(core.start(sinkHandle: nil), .invalidSinkHandle)
        XCTAssertEqual(core.lastStartStatus, .invalidSinkHandle)
        XCTAssertFalse(core.isRunning)
    }

    // ── The <5 ms acceptance gate (the Swift twin of native section H) ────

    /// 60 s of drifted DAC playback with anchors refreshed only every
    /// 250 ms (the publisher's staleness horizon): the estimator's worst
    /// error must stay under the MP-DEC-03 budget.
    func testInterpolationErrorStaysUnderFiveMilliseconds() {
        let driftPpm = 27.4
        let driver = SimulatedCoreAudioDriver(driftPpm: driftPpm)
        driver.start(sinkHandle: 1)

        // Virtual time driven entirely by the test: 100 ms steps.
        let stepNs = 100_000_000
        let steps = 600  // 60 s
        var anchor: DacClockSample?
        var worstErrorMs = 0.0
        var queries = 0

        for step in 1...steps {
            driver.advance(byDurationNs: stepNs)
            if step % 2 == 0 || step == 1 {  // ~5 Hz drain cadence
                anchor = driver.latestClock()
            }
            guard let anchor else { continue }
            // The UI queries at an offset phase inside the step (60%).
            let nowNs = driver.monotonicNs - Int(0.4 * Double(stepNs))
            queries += 1
            guard let estimated = DacClockTranslator.interpolatedPositionMs(
                anchor: anchor,
                nowNs: nowNs
            ) else {
                XCTFail("estimator must answer with a fresh anchor")
                continue
            }
            // Ground truth: the true frame counter at `now`, converted at
            // the nominal rate (what a screen renders).
            let truthMs = Int(
                Double(driver.framePosition - Int(0.4 * Double(stepNs)
                    * Double(driver.sampleRateHz) / 1_000_000_000.0))
                    / Double(driver.sampleRateHz) * 1000.0
            )
            let errorMs = abs(Double(estimated - truthMs))
            worstErrorMs = max(worstErrorMs, errorMs)
        }

        XCTAssertGreaterThan(queries, 500)
        XCTAssertLessThan(
            worstErrorMs,
            DacClockTranslator.interpolationErrorBudgetMs,
            "worst interpolation error \(worstErrorMs) ms must stay under 5 ms"
        )
        driver.stop()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DacClockPublisher: the pure translator + the duty-cycle drain publisher.
// ─────────────────────────────────────────────────────────────────────────────

final class DacClockPublisherTests: XCTestCase {

    // ── Pure translator ───────────────────────────────────────────────────

    func testAnchorPositionAtTheSample() {
        let clock = DacClockSample(
            framePosition: 48_000 * 61,
            sampleRateHz: 48_000,
            monotonicNs: 61_000_000_000
        )
        XCTAssertEqual(DacClockTranslator.anchorPositionMs(from: clock), 61_000)
        // Degenerate rate / negative frames are rejected, never fabricated.
        XCTAssertNil(
            DacClockTranslator.anchorPositionMs(
                from: DacClockSample(framePosition: 0, sampleRateHz: 0, monotonicNs: 0)
            )
        )
    }

    func testInterpolatedPositionAdvancesWithTheMonotonicDelta() {
        let anchor = DacClockSample(
            framePosition: 48_000 * 10,
            sampleRateHz: 48_000,
            monotonicNs: 10_000_000_000
        )
        // 250 ms past the anchor, still fresh: +250 ms of content.
        XCTAssertEqual(
            DacClockTranslator.interpolatedPositionMs(
                anchor: anchor,
                nowNs: 10_250_000_000
            ),
            10_250
        )
    }

    func testFutureSampleClampsItsDeltaToZero() {
        let anchor = DacClockSample(
            framePosition: 48_000 * 10,
            sampleRateHz: 48_000,
            monotonicNs: 10_000_000_000
        )
        // The query instant 5 ms BEHIND the publication (scheduler jitter):
        // never a negative position.
        XCTAssertEqual(
            DacClockTranslator.interpolatedPositionMs(
                anchor: anchor,
                nowNs: 9_995_000_000
            ),
            10_000
        )
    }

    func testStaleAnchorHoldsItsPosition() {
        let anchor = DacClockSample(
            framePosition: 48_000 * 10,
            sampleRateHz: 48_000,
            monotonicNs: 10_000_000_000
        )
        // A dead DAC (teardown / route change): beyond the staleness
        // horizon the estimator HOLDS rather than extrapolating into void.
        XCTAssertEqual(
            DacClockTranslator.interpolatedPositionMs(
                anchor: anchor,
                nowNs: 10_000_000_000 + 10 * DacClockTranslator.maxAnchorAgeNs
            ),
            10_000
        )
        XCTAssertFalse(
            DacClockTranslator.isFresh(
                anchor: anchor,
                nowNs: 10_000_000_000 + DacClockTranslator.maxAnchorAgeNs + 1
            )
        )
        XCTAssertTrue(
            DacClockTranslator.isFresh(
                anchor: anchor,
                nowNs: 10_000_000_000 + DacClockTranslator.maxAnchorAgeNs
            )
        )
    }

    // ── The publisher ─────────────────────────────────────────────────────

    @MainActor
    func testDrainOncePublishesStreamAndMirror() async {
        let driver = SimulatedCoreAudioDriver()
        var mirrored: [DacClockSample] = []
        let publisher = DacClockPublisher(
            driver: driver,
            onSample: { sample in
                mirrored.append(sample)
            }
        )

        var iterator = publisher.samples.makeAsyncIterator()
        // No renders yet: the drain returns nil, nothing published.
        XCTAssertNil(publisher.drainOnce())

        driver.start(sinkHandle: 1)
        driver.advance(byFrames: 480)

        let drained = publisher.drainOnce()
        XCTAssertEqual(drained?.framePosition, 480)
        XCTAssertEqual(publisher.lastSample?.framePosition, 480)

        let streamed = await iterator.next()
        XCTAssertEqual(streamed?.framePosition, 480)
        XCTAssertEqual(mirrored.map(\.framePosition), [480])

        // Latest-wins: a second drain of unchanged state republishes the
        // same sample (the stream consumer re-anchors, the mirror re-records).
        _ = publisher.drainOnce()
        let second = await iterator.next()
        XCTAssertEqual(second?.framePosition, 480)
        XCTAssertEqual(mirrored.count, 2)

        driver.stop()
    }

    @MainActor
    func testStartStopCadenceIsIdempotent() {
        let publisher = DacClockPublisher(driver: SimulatedCoreAudioDriver())
        publisher.start()
        publisher.start()  // idempotent
        publisher.stop()
        publisher.stop()  // idempotent
    }
}
