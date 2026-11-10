import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// WS-B (B4/B6) tests: the audio plane's lifecycle around the transport —
// with a scripted gateway (RecordingGateway, FeatureLibraryTests) and the
// simulated DAC driver, so everything is deterministic on every lane.
//
// The mount deliberately exercises the REAL AppleRingMount: the Rust lease
// is never attached in the test process (no test attaches a real engine
// plane), so `mount.start()` degrades to `.planeNotAttached` exactly as it
// does on a mis-ordered production flow — the assertions are
// lane-agnostic on purpose.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
final class PlaybackViewModelAudioPlaneTests: XCTestCase {

    private func makeModel(
        gateway: RecordingGateway,
        renderCore: CoreAudioRenderCore? = nil
    ) -> PlaybackViewModel {
        PlaybackViewModel(
            gateway: gateway,
            nowPlayingInfoPublisher: NowPlayingInfoPublisher(),
            widgetRefresher: nil,  // single-process test mode
            renderCore: renderCore
        )
    }

    /// One buffered snapshot must arm the plane in order; one idle push
    /// tears it back down (in the exact reverse order).
    func testBufferingArmsAndIdleTearsDownThePlane() async {
        let gateway = RecordingGateway()
        let simulated = CoreAudioRenderCore(driver: SimulatedCoreAudioDriver())
        let model = makeModel(gateway: gateway, renderCore: simulated)
        model.start()

        // A buffering push arms the plane (attach first — the mount refuses
        // to bridge an unattached plane by contract, which is exactly the
        // degrade asserted below).
        gateway.push(PlaybackSnapshot(
            rev: 1, status: .buffering, volume: 0.8
        ))
        try? await Task.sleep(nanoseconds: 200_000_000)  // the arm task is fire-and-forget

        XCTAssertEqual(gateway.attachCalls, [8192], "attach_sink precedes the mount")
        XCTAssertNotEqual(model.lastAudioPlaneStatus, "idle")
        XCTAssertFalse(model.renderCore.isRunning, "no plane, no render core — the honest degrade")

        // Paused keeps the plane mounted (gapless resume by design).
        gateway.push(PlaybackSnapshot(rev: 2, status: .paused, volume: 0.8))
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(gateway.detachCalls, 0, "pause never tears the plane down")

        // Idle tears it down.
        gateway.push(PlaybackSnapshot(rev: 3, status: .idle, volume: 0))
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(gateway.detachCalls, 1)
        XCTAssertEqual(model.lastAudioPlaneStatus, "idle")

        model.stop()
    }

    /// present(track:) admits the play-flow metadata optimistically and a
    /// later nil-track snapshot never wipes it (the engine's UDL snapshot
    /// carries no track row — WS-B B3's core presentation rule).
    func testPresentAdmitsTrackAndSurvivesNilTrackSnapshots() async {
        let gateway = RecordingGateway()
        let model = makeModel(gateway: gateway)
        model.start()

        let track = PresentationTrack(id: "t1", title: "Nightcall", artist: "Kavinsky", durationMs: 252_000, artworkUrl: nil)
        model.present(track: track)
        XCTAssertEqual(model.currentTrack?.id, "t1")
        XCTAssertEqual(model.state.currentTrack?.title, "Nightcall")

        // An engine push without a track row: the presented identity stays.
        gateway.push(PlaybackSnapshot(
            rev: 1, status: .playing, volume: 0.9,
            clock: DacClockSample(framePosition: 48_000, sampleRateHz: 48_000, monotonicNs: 1_000_000_000)
        ))
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(model.currentTrack?.id, "t1", "a nil-track snapshot never wipes the presented track")
        XCTAssertEqual(model.status, .playing)

        // A DIFFERENT pushed identity updates it (the preview lane's
        // enqueue does exactly this).
        gateway.push(PlaybackSnapshot(
            rev: 2, status: .playing, volume: 0.9,
            currentTrack: PresentationTrack(id: "t2", title: "Innerbloom", artist: "RÜFÜS DU SOL", durationMs: 582_000, artworkUrl: nil)
        ))
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(model.currentTrack?.id, "t2")

        model.stop()
    }

    /// The duplicated-play present is a no-op (same id).
    func testPresentIsIdempotentPerTrack() {
        let gateway = RecordingGateway()
        let model = makeModel(gateway: gateway)
        let track = PresentationTrack(id: "same", title: "t", artist: "a", durationMs: nil, artworkUrl: nil)
        model.present(track: track)
        model.present(track: track)
        XCTAssertEqual(model.currentTrack?.id, "same")
    }
}

// MARK: - DownloadsViewModel (WS-B B7) — the deterministic legs

@MainActor
final class DownloadsViewModelTests: XCTestCase {

    /// Cold-launch recovery over a clean vault: nothing to re-arm, the
    /// counters read the honest zero.
    func testRecoverPersistedTransfersOnACleanVault() {
        let service = BackgroundDownloadService.shared(
            identifier: "wsb-downloads-test-\(UUID().uuidString)",
            destinationDirectory: BackgroundDownloadService.defaultDestinationDirectory()
        )
        let model = DownloadsViewModel(service: service)
        model.recoverPersistedTransfers()
        XCTAssertEqual(model.resumedFromResumeData, 0)
        XCTAssertEqual(model.persistedResumeCount, 0)
        XCTAssertTrue(model.transfers.isEmpty)
    }

    func testQualityLabelsMirrorTheTransferStates() {
        XCTAssertEqual(DownloadsViewModel.qualityLabel(for: .paused), "Paused (resume data kept)")
        XCTAssertEqual(DownloadsViewModel.qualityLabel(for: .completed), "AAC · saved")
        XCTAssertEqual(DownloadsViewModel.qualityLabel(for: .downloading(progress: 0.42)), "Resolving stream…")
    }

    func testSizeMbPrefersTheVaultFileThenTheEstimate() throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("wsb-size-\(UUID().uuidString).bin")
        try Data(count: 2 * 1_048_576).write(to: scratch)
        defer { try? FileManager.default.removeItem(at: scratch) }

        XCTAssertEqual(DownloadsViewModel.sizeMb(totalBytes: nil, fileUrl: scratch), 2.0, accuracy: 0.01)
        XCTAssertEqual(DownloadsViewModel.sizeMb(totalBytes: 4_194_304, fileUrl: nil), 4.0, accuracy: 0.01)
        XCTAssertEqual(DownloadsViewModel.sizeMb(totalBytes: nil, fileUrl: nil), 0)
    }
}
