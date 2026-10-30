import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-06 acceptance lane: the cross-process widget plane — the command
// mailbox (post/drain/aging/bounding), the snapshot store (latest-wins,
// fingerprint discipline), the volume latest-wins path, and the pure
// progress math. Isolated UserDefaults suite + virtual clock: fully
// deterministic, zero processes.
// ─────────────────────────────────────────────────────────────────────────────

final class WidgetPlaybackStoreTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!

    private var virtualNow = Date(timeIntervalSince1970: 1_700_000_000)

    override func setUpWithError() throws {
        suiteName = "test.widget.store.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        XCTAssertNotNil(defaults)
        virtualNow = Date(timeIntervalSince1970: 1_700_000_000)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
    }

    private func makeStore() -> WidgetPlaybackStore {
        // Escaping closure over the class property: explicit self so the
        // capture semantics are visible (mutations of `virtualNow` later in
        // a test remain observable through the store's virtual clock).
        WidgetPlaybackStore(defaults: defaults) { self.virtualNow }
    }

    private func snapshot(
        trackId: String = "t1",
        isPlaying: Bool = true,
        positionSeconds: Double = 61,
        volume: Double = 0.7
    ) -> WidgetPlaybackSnapshot {
        WidgetPlaybackSnapshot(
            trackId: trackId,
            title: "Nightcall",
            artist: "Kavinsky",
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            snapshotAt: virtualNow,
            durationSeconds: 252,
            volume: volume,
            artwork: nil
        )
    }

    // ── The command mailbox ───────────────────────────────────────────────

    func testPostAndDrainRoundTripInPostingOrder() {
        var store = makeStore()
        store.post(.togglePlayPause)
        store.post(.next)
        store.post(.previous)

        XCTAssertEqual(store.drain(), [.togglePlayPause, .next, .previous])
        // The queue is emptied by the drain.
        XCTAssertEqual(store.drain(), [])
    }

    func testSeekEntriesResolveWithTheirPosition() {
        var store = makeStore()
        store.postSeek(positionMs: 42_000)
        XCTAssertEqual(store.drain(), [.seek(positionMs: 42_000)])
    }

    func testStaleCommandsAgeOutAtDrainTime() {
        var store = makeStore()
        store.post(.next)
        // 31 s of un-drained mailbox: aged out, not applied late.
        virtualNow = virtualNow.addingTimeInterval(31)
        XCTAssertEqual(store.drain(), [])
    }

    func testFreshCommandsAtTheAgeBoundarySurvive() {
        var store = makeStore()
        store.post(.next)
        virtualNow = virtualNow.addingTimeInterval(29)
        XCTAssertEqual(store.drain(), [.next])
    }

    func testMailboxIsBounded() {
        var store = makeStore()
        for _ in 0..<20 {
            store.post(.next)
        }
        // The FIFO keeps only the newest 16.
        XCTAssertEqual(store.drain().count, WidgetPlaybackStore.maxQueuedCommands)
    }

    func testUnknownCommandKindsAreDroppedNeverCrash() {
        // Corrupt shared data must degrade to a skip, never a crash.
        let entry = WidgetCommandEntry(
            commandKind: "future-unknown",
            positionMs: nil,
            postedAt: virtualNow
        )
        XCTAssertNil(WidgetPlaybackStore.resolve(entry))
        let seekWithoutPosition = WidgetCommandEntry(
            commandKind: "seek",
            positionMs: nil,
            postedAt: virtualNow
        )
        XCTAssertNil(WidgetPlaybackStore.resolve(seekWithoutPosition))
    }

    // ── The snapshot store ────────────────────────────────────────────────

    func testSnapshotWriteAndReadRoundTrip() {
        var store = makeStore()
        XCTAssertNil(store.snapshot())

        XCTAssertTrue(store.refresh(snapshot: snapshot()))
        let read = store.snapshot()
        XCTAssertEqual(read?.trackId, "t1")
        XCTAssertEqual(read?.title, "Nightcall")
        XCTAssertEqual(read?.isPlaying, true)
        XCTAssertEqual(read?.positionSeconds ?? 0, 61, accuracy: 1e-9)
        XCTAssertEqual(read?.volume ?? 0, 0.7, accuracy: 1e-9)
    }

    func testIdenticalRenderedFingerprintSkipsTheWrite() {
        var store = makeStore()
        XCTAssertTrue(store.refresh(snapshot: snapshot(positionSeconds: 61.4)))
        // Same rendered second: no store churn (the 1 Hz discipline).
        XCTAssertFalse(store.refresh(snapshot: snapshot(positionSeconds: 61.9)))
        // The next whole second writes.
        XCTAssertTrue(store.refresh(snapshot: snapshot(positionSeconds: 62.0)))
    }

    // ── Volume rides the snapshot (latest-wins) ───────────────────────────

    func testVolumeRidesTheSnapshotNotTheFIFO() {
        var store = makeStore()
        store.postVolume(0.4)
        store.postVolume(0.9)

        // Latest volume wins in the SNAPSHOT...
        XCTAssertEqual(store.snapshot()?.volume ?? 0, 0.9, accuracy: 1e-9)
        // ...and the mailbox carries no volume entries at all.
        XCTAssertEqual(store.drain(), [])
    }

    func testVolumeClampsIntoTheUnitRange() {
        var store = makeStore()
        store.postVolume(2.0)
        XCTAssertEqual(store.snapshot()?.volume ?? 0, 1, accuracy: 1e-9)
        store.postVolume(-1.0)
        XCTAssertEqual(store.snapshot()?.volume ?? 1, 0, accuracy: 1e-9)
    }

    // ── The pure progress math (MP-DEC-03 discipline) ─────────────────────

    func testProgressAdvancesWithMonotonicDeltaWhilePlaying() {
        let snap = snapshot(positionSeconds: 30)
        let later = virtualNow.addingTimeInterval(10)
        XCTAssertEqual(
            WidgetPlaybackMath.progress(snapshot: snap, at: later) ?? -1,
            40.0 / 252.0,
            accuracy: 1e-9
        )
    }

    func testProgressFreezesWhenPaused() {
        let snap = snapshot(isPlaying: false, positionSeconds: 30)
        let later = virtualNow.addingTimeInterval(60)
        XCTAssertEqual(
            WidgetPlaybackMath.progress(snapshot: snap, at: later) ?? -1,
            30.0 / 252.0,
            accuracy: 1e-9
        )
    }

    func testProgressClampsAndReportsUnknownDurations() {
        XCTAssertNil(WidgetPlaybackMath.progress(snapshot: nil, at: virtualNow))
        // Unknown duration: indeterminate (never a fabricated bar).
        let unknown = WidgetPlaybackSnapshot(
            trackId: "t", title: "T", artist: "A", isPlaying: true,
            positionSeconds: 5, snapshotAt: virtualNow, durationSeconds: 0,
            volume: 0.5, artwork: nil
        )
        XCTAssertNil(WidgetPlaybackMath.progress(snapshot: unknown, at: virtualNow))
        // Past the end: clamps to 1.
        let long = snapshot(positionSeconds: 251)
        XCTAssertEqual(
            WidgetPlaybackMath.progress(
                snapshot: long,
                at: virtualNow.addingTimeInterval(60)
            ) ?? 0,
            1,
            accuracy: 1e-9
        )
    }

    func testReloadIntervalFollowsTransportState() {
        XCTAssertEqual(WidgetPlaybackMath.reloadInterval(snapshot: snapshot(isPlaying: true)), 2)
        XCTAssertEqual(WidgetPlaybackMath.reloadInterval(snapshot: snapshot(isPlaying: false)), 900)
        XCTAssertEqual(WidgetPlaybackMath.reloadInterval(snapshot: nil), 900)
    }

    // ── The app-side refresher ────────────────────────────────────────────

    @MainActor
    func testRefresherSkipsIdenticalRenderedStatesWithoutIO() async {
        let store = makeStore()
        let refresher = WidgetPlaybackRefresher(
            store: store,
            artworkProvider: LiveActivityArtworkProvider(fetch: { _ in nil })
        )

        func state(positionMs: Int) -> PlaybackUiState {
            let snapshot = PlaybackSnapshot(
                rev: 1,
                status: .playing,
                volume: 0.5,
                clock: DacClockSample(
                    framePosition: 48 * positionMs,
                    sampleRateHz: 48_000,
                    monotonicNs: positionMs * 1_000_000
                ),
                currentTrack: PresentationTrack(
                    id: "t1",
                    title: "Nightcall",
                    artist: "Kavinsky",
                    durationMs: 252_000,
                    artworkUrl: nil
                )
            )
            return PlaybackUiState(snapshot)
        }

        await refresher.refresh(state: state(positionMs: 61_400))
        // The snapshot landed.
        XCTAssertEqual(store.snapshot()?.positionSeconds ?? 0, 61.4, accuracy: 1e-9)

        // An identical rendered state (same whole second): the store is
        // untouched — the pre-write fingerprint check made the decision
        // with ZERO writes (observable through the unchanged snapshotAt).
        let firstAt = store.snapshot()?.snapshotAt
        virtualNow = virtualNow.addingTimeInterval(1)
        await refresher.refresh(state: state(positionMs: 61_900))
        XCTAssertEqual(store.snapshot()?.snapshotAt, firstAt)

        // The next whole second writes the fresh snapshot.
        virtualNow = virtualNow.addingTimeInterval(1)
        await refresher.refresh(state: state(positionMs: 62_000))
        XCTAssertNotEqual(store.snapshot()?.snapshotAt, firstAt)
    }

    @MainActor
    func testRefresherDrainsMailboxOntoTheCommandPlane() {
        var store = makeStore()
        store.post(.next)
        store.post(.previous)
        let refresher = WidgetPlaybackRefresher(store: store)

        var dispatched: [RemoteCommand] = []
        let drained = refresher.drainCommands { dispatched.append($0) }
        XCTAssertEqual(drained, [.next, .previous])
        XCTAssertEqual(dispatched, [.next, .previous])
    }
}
