import AVFoundation
import MediaPlayer
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

    @MainActor
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

    @MainActor
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

    @MainActor
    private final class RecordingResponder: AudioSessionResponding {
        var pauseCount = 0
        var resumeCount = 0
        /// SW-ENG3-02: the pre-interruption memory source — the engine was
        /// playing when the interruption began (the legacy test's scenario).
        var playbackIsActive = true

        var audioSessionPlaybackIsActive: Bool { playbackIsActive }

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
        XCTAssertGreaterThan(fastDrift ?? -1, 900)
        XCTAssertLessThan(fastDrift ?? -1, 1_100)

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
        await model.flushDispatches()

        XCTAssertEqual(
            gateway.dispatchedCommands,
            [.togglePlayPause, .next, .setVolume(1)]
        )

        model.stop()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01): the 1 Hz lockscreen/now-playing discipline + the queue
// projection (same hermetic style — pure gates, recording stubs, zero
// devices).
// ─────────────────────────────────────────────────────────────────────────────

final class OneHertzGateTests: XCTestCase {

    func testFirstValueAlwaysAdmits() {
        let gate = OneHertzGate()
        XCTAssertTrue(gate.admits(nowNs: 0))
        XCTAssertTrue(gate.admits(nowNs: 5_000_000_000))
    }

    func testValuesInsideTheWindowDefer() {
        var gate = OneHertzGate()
        gate = gate.admitting(nowNs: 1_000_000_000)
        // 999 ms later: inside the 1 s window.
        XCTAssertFalse(gate.admits(nowNs: 1_999_999_999))
        // Exactly 1 s later: the boundary re-opens (>= semantics).
        XCTAssertTrue(gate.admits(nowNs: 2_000_000_000))
    }

    func testAdmissionStateAdvances() {
        var gate = OneHertzGate()
        gate = gate.admitting(nowNs: 100)
        gate = gate.admitting(nowNs: 2_000_000_100)
        // The second emission is the new reference point.
        XCTAssertFalse(gate.admits(nowNs: 2_500_000_000))
        XCTAssertTrue(gate.admits(nowNs: 3_000_000_100))
    }

    func testCustomIntervalHonored() {
        var gate = OneHertzGate(intervalNs: 50_000_000)
        gate = gate.admitting(nowNs: 1_000)
        XCTAssertFalse(gate.admits(nowNs: 40_000_000))
        XCTAssertTrue(gate.admits(nowNs: 51_000_000))
    }
}

final class NowPlayingInfoPublisherTests: XCTestCase {

    /// Recording stub over the apply seam.
    @MainActor
    private final class RecordingCenter: NowPlayingInfoApplying {
        var applied: [[String: Any]] = []
        func applyNowPlayingInfo(_ info: [String: Any]) {
            applied.append(info)
        }
    }

    private func playingState(
        positionMs: Int,
        title: String? = "Nightcall",
        durationMs: Int? = 252_000
    ) -> PlaybackUiState {
        let snapshot = PlaybackSnapshot(
            rev: 1,
            status: .playing,
            volume: 0.5,
            clock: DacClockSample(
                framePosition: 48 * positionMs,
                sampleRateHz: 48_000,
                monotonicNs: positionMs * 1_000_000
            ),
            currentTrack: title.map {
                PresentationTrack(id: "t1", title: $0, artist: "Kavinsky", durationMs: durationMs)
            }
        )
        return PlaybackUiState(snapshot)
    }

    @MainActor
    func testFirstPublishAppliesImmediately() {
        let center = RecordingCenter()
        let publisher = NowPlayingInfoPublisher(center: center)
        publisher.publish(state: playingState(positionMs: 61_000))
        XCTAssertEqual(center.applied.count, 1)
        publisher.clear()
    }

    @MainActor
    func testBurstWithinWindowDefersThenFlushesTheTrailingEdge() async {
        let center = RecordingCenter()
        // Short window (50 ms) so the flush is verifiable without a full
        // second of wall time; production uses the 1 Hz default.
        let publisher = NowPlayingInfoPublisher(center: center, intervalNs: 50_000_000)

        publisher.publish(state: playingState(positionMs: 61_000))
        publisher.publish(state: playingState(positionMs: 62_000))
        publisher.publish(state: playingState(positionMs: 63_000))

        // Leading edge applied; the rest deferred into the pending slot.
        XCTAssertEqual(center.applied.count, 1)
        XCTAssertEqual(
            center.applied.first?[MPMediaItemPropertyTitle] as? String,
            "Nightcall"
        )

        // The trailing edge lands after the window passes.
        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(center.applied.count, 2)
        let flushed = center.applied.last
        XCTAssertEqual(
            flushed?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double,
            63.0
        )

        publisher.clear()
    }

    @MainActor
    func testClearWipesTheSurface() {
        let center = RecordingCenter()
        let publisher = NowPlayingInfoPublisher(center: center)
        publisher.publish(state: playingState(positionMs: 10_000))
        publisher.clear()
        XCTAssertEqual(center.applied.count, 2)
        XCTAssertEqual(center.applied.last?.isEmpty, true)
    }

    // ── Pure info-dict derivation ────────────────────────────────────────

    func testInfoDictCarriesTrackPositionAndRate() {
        let info = NowPlayingInfoPublisher.infoDict(
            for: playingState(positionMs: 90_000)
        )
        XCTAssertEqual(info[MPMediaItemPropertyTitle] as? String, "Nightcall")
        XCTAssertEqual(info[MPMediaItemPropertyArtist] as? String, "Kavinsky")
        XCTAssertEqual(info[MPMediaItemPropertyPlaybackDuration] as? Double, 252.0)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 90.0)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 1.0)
    }

    func testInfoDictOmitsAbsentValuesAndMarksPause() {
        // No track, no clock: only the playback rate is knowable.
        let idle = NowPlayingInfoPublisher.infoDict(for: PlaybackUiState.idle)
        XCTAssertNil(idle[MPMediaItemPropertyTitle])
        XCTAssertNil(idle[MPNowPlayingInfoPropertyElapsedPlaybackTime])
        XCTAssertEqual(idle[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0.0)

        // Paused with a clock: rate flips to 0, elapsed stays.
        let pausedSnapshot = PlaybackSnapshot(
            rev: 2,
            status: .paused,
            volume: 0.5,
            clock: DacClockSample(framePosition: 48_000 * 30, sampleRateHz: 48_000, monotonicNs: 30_000_000_000)
        )
        let paused = NowPlayingInfoPublisher.infoDict(for: PlaybackUiState(pausedSnapshot))
        XCTAssertEqual(paused[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0.0)
        XCTAssertEqual(paused[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 30.0)
    }
}

final class RemoteCommandPlaneTests: XCTestCase {

    @MainActor
    func testLockscreenCommandsRouteOntoTheEngineCommandPlane() async {
        let gateway = PreviewEngineGateway()
        let model = PlaybackViewModel(gateway: gateway)

        model.remoteCommandReceived(.togglePlayPause)
        model.remoteCommandReceived(.next)
        model.remoteCommandReceived(.previous)
        model.remoteCommandReceived(.seek(positionMs: 42_000))
        await model.flushDispatches()

        XCTAssertEqual(
            gateway.dispatchedCommands,
            [.togglePlayPause, .next, .previous, .seek(positionMs: 42_000)]
        )

        model.stop()
    }
}

final class QueueListStateTests: XCTestCase {

    private let tracks = [
        PresentationTrack(id: "a", title: "Alpha", artist: "A"),
        PresentationTrack(id: "b", title: "Beta", artist: "B"),
        PresentationTrack(id: "c", title: "Gamma", artist: "C"),
        PresentationTrack(id: "d", title: "Delta", artist: "D"),
    ]

    func testEmptyStateDegradesHonestly() {
        let state = QueueListState.empty
        XCTAssertNil(state.current)
        XCTAssertTrue(state.upNext.isEmpty)
        XCTAssertTrue(state.played.isEmpty)
    }

    func testPlayingInTheMiddleSplitsCurrentUpNextAndPlayed() {
        let state = QueueListState(tracks: tracks, currentIndex: 1, isPlaying: true)
        XCTAssertEqual(state.current?.id, "b")
        XCTAssertEqual(state.upNext.map(\.track.id), ["c", "d"])
        XCTAssertEqual(state.upNext.map(\.index), [2, 3])
        XCTAssertEqual(state.played.map(\.track.id), ["a"])
    }

    func testNegativeIndexRendersTheWholeQueueAsUpNext() {
        let state = QueueListState(tracks: tracks, currentIndex: -1)
        XCTAssertNil(state.current)
        XCTAssertEqual(state.upNext.count, 4)
        XCTAssertTrue(state.played.isEmpty)
    }

    func testDisplayPositionQuantizesToWholeSeconds() {
        // 1 Hz display discipline: 61_234 ms renders as 61_000 ms.
        let snapshot = PlaybackSnapshot(
            rev: 3,
            status: .playing,
            volume: 0.5,
            clock: DacClockSample(framePosition: 48_000 * 61 + 234 * 48, sampleRateHz: 48_000, monotonicNs: 0)
        )
        let ui = PlaybackUiState(snapshot)
        XCTAssertNotNil(ui.positionMs)
        XCTAssertEqual(ui.positionForDisplayMs, (ui.positionMs! / 1000) * 1000)
        XCTAssertEqual(ui.positionForDisplayMs! % 1000, 0)
    }
}
