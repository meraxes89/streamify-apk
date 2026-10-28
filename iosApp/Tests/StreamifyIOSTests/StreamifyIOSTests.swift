import AVFoundation
import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01/MP-UI02 / Beta): the iOS scaffold's headless suites.
//
// Runs via `xcodebuild test -scheme StreamifyIOS -destination 'platform=iOS
// Simulator,...'` (the package is iOS-only; see iosApp/README.md). The
// MP-UI02 policy matrix is verified against the REAL AVAudioSession value
// types — no mocks, no device, no audio route required.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
final class IOSAudioSessionAdapterTests: XCTestCase {

    // ── Pure decision core: interruption policy ───────────────────────────

    func testInterruptionBeganPausesTheEngine() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forInterruption: .began, options: []),
            .pauseEngine
        )
    }

    func testInterruptionEndedWithShouldResumeResumes() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forInterruption: .ended, options: [.shouldResume]),
            .resumeEngine
        )
    }

    func testInterruptionEndedWithoutShouldResumeStaysPaused() {
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forInterruption: .ended, options: []),
            .none
        )
    }

    // ── Pure decision core: route-change policy ───────────────────────────

    func testOldDeviceUnavailablePausesTheEngine() {
        // Headphones unplugged AND AirPlay sink dropped — both land here.
        XCTAssertEqual(
            IOSAudioSessionAdapter.decision(forRouteChange: .oldDeviceUnavailable),
            .pauseEngine
        )
    }

    func testInformationalRouteChangesDoNothing() {
        XCTAssertEqual(IOSAudioSessionAdapter.decision(forRouteChange: .newDeviceAvailable), .none)
        XCTAssertEqual(IOSAudioSessionAdapter.decision(forRouteChange: .categoryChange), .none)
        XCTAssertEqual(IOSAudioSessionAdapter.decision(forRouteChange: .routeConfigurationChange), .none)
    }

    // ── userInfo extraction (the shapes AVFoundation actually posts) ──────

    func testParsesInterruptionPayloadBuiltFromRealValueTypes() {
        let payload = IOSAudioSessionAdapter.interruptionPayload(
            type: .began,
            options: []
        )
        let parsed = IOSAudioSessionAdapter.parseInterruption(payload)
        XCTAssertEqual(parsed?.type, .began)
        XCTAssertEqual(parsed?.options, [])
    }

    func testParsesRouteChangePayloadAndRejectsGarbage() {
        let payload = IOSAudioSessionAdapter.routeChangePayload(reason: .oldDeviceUnavailable)
        XCTAssertEqual(IOSAudioSessionAdapter.parseRouteChange(payload), .oldDeviceUnavailable)

        XCTAssertNil(IOSAudioSessionAdapter.parseRouteChange(nil))
        XCTAssertNil(
            IOSAudioSessionAdapter.parseRouteChange(["nonsense": 42 as UInt])
        )
        XCTAssertNil(IOSAudioSessionAdapter.parseInterruption(["nonsense": 42 as UInt]))
    }

    // ── End-to-end observer plumbing (notification post → responder) ──────

    func testPostedInterruptionNotificationDrivesTheResponder() async throws {
        let responder = RecordingResponder()
        let adapter = IOSAudioSessionAdapter(responder: responder)

        // activate() configures .playback/.default/[] and installs the
        // observers. On the simulator this is a real session — allowed.
        try adapter.activate()

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(type: .began)
        )

        // The observer hops to the main actor through a Task — give the
        // hop a generous moment to land.
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.pauseCount, 1)
        XCTAssertEqual(responder.resumeCount, 0)

        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.interruptionPayload(
                type: .ended,
                options: [.shouldResume]
            )
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.pauseCount, 1)
        XCTAssertEqual(responder.resumeCount, 1)

        // Clean teardown: idempotent, removes observers exactly once.
        adapter.deactivate()
        adapter.deactivate()
    }

    func testPostedRouteChangeWithOldDeviceUnavailablePauses() async throws {
        let responder = RecordingResponder()
        let adapter = IOSAudioSessionAdapter(responder: responder)
        try adapter.activate()

        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification,
            object: nil,
            userInfo: IOSAudioSessionAdapter.routeChangePayload(reason: .oldDeviceUnavailable)
        )
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(responder.pauseCount, 1)
        XCTAssertEqual(responder.resumeCount, 0)

        adapter.deactivate()
    }

    // ── Responder double ─────────────────────────────────────────────────

    private final class RecordingResponder: AudioSessionResponding {
        var pauseCount = 0
        var resumeCount = 0

        func audioSessionRequestsPause(reason: String) { pauseCount += 1 }
        func audioSessionRequestsResume(reason: String) { resumeCount += 1 }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Presentation-model parity with the shared Kotlin suite (same edge cases).
// ─────────────────────────────────────────────────────────────────────────────

final class PlaybackModelsTests: XCTestCase {

    func testTimeFormatting() {
        XCTAssertEqual(PlaybackFormatting.formatPlaybackTime(0), "0:00")
        XCTAssertEqual(PlaybackFormatting.formatPlaybackTime(59_999), "0:59")
        XCTAssertEqual(PlaybackFormatting.formatPlaybackTime(61_000), "1:01")
        XCTAssertEqual(PlaybackFormatting.formatPlaybackTime(3_600_000), "1:00:00")
        XCTAssertEqual(PlaybackFormatting.formatPlaybackTime(-5_000), "0:00")
    }

    func testProgressFractionTotality() {
        XCTAssertNil(PlaybackFormatting.progressFraction(positionMs: nil, durationMs: 100_000))
        XCTAssertNil(PlaybackFormatting.progressFraction(positionMs: 1_000, durationMs: nil))
        XCTAssertNil(PlaybackFormatting.progressFraction(positionMs: 1_000, durationMs: 0))
        XCTAssertEqual(PlaybackFormatting.progressFraction(positionMs: 50_000, durationMs: 100_000), 0.5)
        XCTAssertEqual(PlaybackFormatting.progressFraction(positionMs: -5_000, durationMs: 100_000), 0)
        XCTAssertEqual(PlaybackFormatting.progressFraction(positionMs: 500_000, durationMs: 100_000), 1)
    }

    func testPositionDerivesFromDacFrameCounter() {
        let clock = DacClockSample(
            framePosition: 48_000 * 90,
            sampleRateHz: 48_000,
            monotonicNs: 90_000_000_000
        )
        XCTAssertEqual(PlaybackFormatting.positionMs(from: clock), 90_000)
        XCTAssertNil(
            PlaybackFormatting.positionMs(
                from: DacClockSample(framePosition: 0, sampleRateHz: 0, monotonicNs: 0)
            )
        )
    }

    func testDacDriftMath() {
        let older = DacClockSample(framePosition: 0, sampleRateHz: 48_000, monotonicNs: 0)
        let nominal = DacClockSample(
            framePosition: 48_000 * 10,
            sampleRateHz: 48_000,
            monotonicNs: 10_000_000_000
        )
        let drift = PlaybackFormatting.dacDriftPpm(older: older, newer: nominal)
        XCTAssertNotNil(drift)
        XCTAssertLessThan(abs(drift ?? 1), 1.0)

        let fast = DacClockSample(
            framePosition: 48_000 * 10 + 481,
            sampleRateHz: 48_000,
            monotonicNs: 10_000_000_000
        )
        let fastDrift = PlaybackFormatting.dacDriftPpm(older: older, newer: fast)
        XCTAssertNotNil(fastDrift)
        XCTAssertGreaterThan(fastDrift ?? -1, 9_000)
        XCTAssertLessThan(fastDrift ?? -1, 11_000)

        // Non-monotonic pairs are rejected.
        XCTAssertNil(
            PlaybackFormatting.dacDriftPpm(older: nominal, newer: older)
        )
    }

    func testUiStateDerivations() {
        var snapshot = PlaybackSnapshot(
            rev: 2,
            status: .playing,
            volume: 0.5,
            clock: DacClockSample(framePosition: 48_000 * 61, sampleRateHz: 48_000, monotonicNs: 61_000_000_000)
        )
        var ui = PlaybackUiState(snapshot)
        XCTAssertTrue(ui.isPlaying)
        XCTAssertEqual(ui.positionMs, 61_000)
        XCTAssertNil(ui.progressFraction) // no duration — indeterminate, never fabricated

        snapshot.currentTrack = PresentationTrack(
            id: "t1",
            title: "Nightcall",
            artist: "Kavinsky",
            durationMs: 252_000
        )
        ui = PlaybackUiState(snapshot)
        XCTAssertEqual(ui.progressFraction ?? -1, 61_000.0 / 252_000.0, accuracy: 1e-9)
    }

    @MainActor
    func testPreviewGatewayCommandPlane() async {
        let gateway = PreviewEngineGateway()
        let model = PlaybackViewModel(gateway: gateway)

        model.togglePlayPause()
        model.next()
        model.setVolume(1.5) // clamps into [0, 1]
        // Let the fire-and-forget dispatch tasks land.
        try? await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(
            gateway.dispatchedCommands,
            [.togglePlayPause, .next, .setVolume(1)]
        )

        model.stop()
    }
}
