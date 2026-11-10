import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// WS-B (B1/B3) tests: the feature seam's play transaction + the real lane's
// mappings, over a scripted gateway (deterministic — no network, no engine).
//
// LANE NOTES
//   * The PreviewFeatureLibrary / protocol-default suites run on EVERY lane.
//   * The `UniFFIFeatureLibrary` suites run where the real-lane type is
//     compiled (`#if canImport(streamifyFFI)` — the CI stages the framework,
//     so they execute there; the scaffold lane compiles them out).
// ─────────────────────────────────────────────────────────────────────────────

/// A deterministic EngineGateway double: the preview gateway's behavior for
/// the v1 typed lane, plus recording + scripting for the v2 data-plane
/// surface (the library maps onto exactly that seam).
final class RecordingGateway: EngineGateway {

    let base = PreviewEngineGateway()

    private let lock = NSLock()
    private var _attached: [Int] = []
    private var _detached = 0
    private var _played: [String] = []
    private var _enqueued: [EngineTrack] = []
    private var _cleared = 0
    private var _resolved: [String] = []
    private var _recordedClocks: [DacClockSample] = []
    private var _scriptedFormats: [String: [EngineStreamFormat]] = [:]
    private var _scriptedQueue: [EngineQueueItem] = []

    // Recording accessors
    var attachCalls: [Int] { lock.withLock { _attached } }
    var detachCalls: Int { lock.withLock { _detached } }
    var playedTrackIds: [String] { lock.withLock { _played } }
    var enqueuedTracks: [EngineTrack] { lock.withLock { _enqueued } }
    var clearCalls: Int { lock.withLock { _cleared } }
    var resolvedIds: [String] { lock.withLock { _resolved } }
    var recordedClocks: [DacClockSample] { lock.withLock { _recordedClocks } }

    // Scripting
    func scriptFormats(_ formats: [EngineStreamFormat], for videoId: String) {
        lock.withLock { _scriptedFormats[videoId] = formats }
    }
    func scriptQueue(_ items: [EngineQueueItem]) {
        lock.withLock { _scriptedQueue = items }
    }

    // ── v1: forwarded to the preview double ────────────────────────────────

    var snapshots: AsyncStream<PlaybackSnapshot> { base.snapshots }
    func send(_ command: PlayerCommand) async -> PlayerCommandResult { await base.send(command) }
    func currentSnapshot() async -> PlaybackSnapshot? { await base.currentSnapshot() }
    func createJamSession(name: String) async -> String? { await base.createJamSession(name: name) }
    func jamStateHash() async -> String? { await base.jamStateHash() }
    func shutdown() { base.shutdown() }
    func searchTracks(_ query: String, limit: Int) async throws -> EngineSearchResult {
        try await base.searchTracks(query, limit: limit)
    }
    func enqueue(track: EngineTrack, enqueuedBy: String) async -> Int? {
        lock.withLock { _enqueued.append(track) }
        return await base.enqueue(track: track, enqueuedBy: enqueuedBy)
    }
    func queue() async -> [EngineQueueItem] {
        let scripted = lock.withLock { _scriptedQueue }
        return scripted.isEmpty ? await base.queue() : scripted
    }
    func clearQueue() async {
        lock.withLock { _cleared += 1 }
        await base.clearQueue()
    }
    func equalizerBands() async -> [EngineEqualizerBand] { await base.equalizerBands() }
    func setEqualizerBand(_ band: EngineEqualizerBand) async { await base.setEqualizerBand(band) }
    func jamRoomState() async -> EngineJamRoomState? { await base.jamRoomState() }

    // Push helper (the preview double's hand-push).
    func push(_ snapshot: PlaybackSnapshot) {
        base.push(snapshot)
    }

    // ── v2: the recorded data-plane surface ────────────────────────────────

    func attachSink(capacityFrames: Int) async {
        lock.withLock { _attached.append(capacityFrames) }
    }
    func detachSink() async {
        lock.withLock { _detached += 1 }
    }
    func playTrack(trackId: String) async -> Bool {
        lock.withLock {
            _played.append(trackId)
            return !_played.isEmpty
        }
    }
    func bufferProgress() async -> EngineBufferProgress? {
        nil  // no producer on the double — the honest degrade
    }
    func recordDacClock(_ clock: DacClockSample) {
        lock.withLock { _recordedClocks.append(clock) }
    }
    func resolveStreamFormats(videoId: String) async -> [EngineStreamFormat] {
        lock.withLock {
            _resolved.append(videoId)
            return _scriptedFormats[videoId] ?? []
        }
    }
}

// MARK: - The preview double's play (every lane)

final class PreviewLibraryPlayTests: XCTestCase {

    @MainActor
    func testPreviewPlayPushesAQueueSnapshotAndReturnsTrue() async {
        let library = PreviewFeatureLibrary()
        // Arm the push continuation BEFORE the play (AsyncStream buffers
        // whatever arrives after arming — the collector below drains it).
        let events = library.queueEvents
        let track = PresentationTrack(id: "pv1", title: "Nightcall", artist: "Kavinsky", durationMs: 252_000, artworkUrl: nil)
        let accepted = await library.play(track: track)
        XCTAssertTrue(accepted, "the preview lane accepts the play and scripts the queue")

        for await event in events {
            if case .snapshot(let snapshot) = event {
                XCTAssertEqual(snapshot.tracks.first?.id, "pv1")
                XCTAssertEqual(snapshot.currentIndex, 0)
                XCTAssertTrue(snapshot.isPlaying)
                break
            }
        }
    }

    func testProtocolDefaultRefusesThePlay() async {
        // A conformer that overrides nothing: `play` stays refused (the
        // honest degrade — the AHEAD B1 rule, defaults never fabricate).
        let bare = BareFeatureLibrary()
        let accepted = await bare.play(track: PresentationTrack(id: "x", title: "t", artist: "a", durationMs: nil, artworkUrl: nil))
        XCTAssertFalse(accepted)
    }

    /// A conformer with zero overrides — the defaults must cover it.
    private final class BareFeatureLibrary: FeatureLibrary {}
}

// MARK: - The real lane's mappings (framework-linked lanes)

#if canImport(streamifyFFI)

final class UniFFIFeatureLibraryTests: XCTestCase {

    @MainActor
    private func makeLibrary(gateway: RecordingGateway) -> UniFFIFeatureLibrary {
        UniFFIFeatureLibrary(gateway: gateway)
    }

    @MainActor
    func testSearchMapsTypedRowsOntoSearchRow() async {
        let gateway = RecordingGateway()
        let library = makeLibrary(gateway: gateway)
        let rows = await library.searchTracks(query: "Nightcall", filter: nil)
        // The preview double's fixture catalog filters the same phrase —
        // the MAPPING is under test (uploader from artist, kind .song).
        XCTAssertFalse(rows.isEmpty)
        for row in rows {
            XCTAssertEqual(row.kind, .song)
            XCTAssertFalse(row.uploader.isEmpty)
        }
    }

    @MainActor
    func testPlayResolvesStagesAndPlays_WithCache() async {
        let gateway = RecordingGateway()
        let library = makeLibrary(gateway: gateway)
        let formats = [
            EngineStreamFormat(url: "https://cdn.example/audio.m4a", mimeType: "audio/mp4; codecs=mp4a.40.2", bitrate: 128_000, durationSec: 252, isAudioOnly: true),
            EngineStreamFormat(url: "https://cdn.example/muxed.webm", mimeType: "audio/webm", bitrate: 96_000, durationSec: 252, isAudioOnly: false),
        ]
        gateway.scriptFormats(formats, for: "trackA")
        let track = PresentationTrack(id: "trackA", title: "Nightcall", artist: "Kavinsky", durationMs: 252_000, artworkUrl: nil)

        let first = await library.play(track: track)
        XCTAssertTrue(first)
        // Element 0 of the resolve set (audio-only head) is the staged URL.
        XCTAssertEqual(gateway.enqueuedTracks.count, 1)
        XCTAssertEqual(gateway.enqueuedTracks.first?.resolvedStreamUrl, "https://cdn.example/audio.m4a")
        XCTAssertEqual(gateway.playedTrackIds, ["trackA"])

        // Second play of the same id: no duplicate row (reuses the staged
        // row), still plays, resolve served from the in-memory cache (no
        // second resolve for the same id).
        let second = await library.play(track: track)
        XCTAssertTrue(second)
        XCTAssertEqual(gateway.enqueuedTracks.count, 1, "the staged row is reused, not duplicated")
        XCTAssertEqual(gateway.playedTrackIds, ["trackA", "trackA"])
        XCTAssertEqual(gateway.resolvedIds.filter { $0 == "trackA" }.count, 1, "cache-first: one resolve per id")
    }

    @MainActor
    func testPlayRefusesWhenResolveFails() async {
        let gateway = RecordingGateway()
        let library = makeLibrary(gateway: gateway)
        let track = PresentationTrack(id: "unresolvable", title: "t", artist: "a", durationMs: nil, artworkUrl: nil)
        let accepted = await library.play(track: track)
        XCTAssertFalse(accepted, "an honest refuse — never a fabricated URL")
        XCTAssertTrue(gateway.enqueuedTracks.isEmpty)
        XCTAssertTrue(gateway.playedTrackIds.isEmpty)
    }

    @MainActor
    func testRemoveQueueItemsClearsOnlyWhenItCoversEveryRow() async {
        let gateway = RecordingGateway()
        gateway.scriptQueue([
            EngineQueueItem(index: 0, track: EngineTrack(id: "a", title: "A", artist: "x", album: "", durationMs: 1), enqueuedBy: "local"),
            EngineQueueItem(index: 1, track: EngineTrack(id: "b", title: "B", artist: "x", album: "", durationMs: 1), enqueuedBy: "local"),
        ])
        let library = makeLibrary(gateway: gateway)

        // Partial removal: no UDL v1 surface — an honest no-op.
        await library.removeQueueItems(at: [0])
        XCTAssertEqual(gateway.clearCalls, 0)

        // Full-cover removal lowers onto clear_queue.
        await library.removeQueueItems(at: [0, 1])
        XCTAssertEqual(gateway.clearCalls, 1)
    }

    @MainActor
    func testQueueEventsSeedsAConsistentSnapshot() async {
        let gateway = RecordingGateway()
        gateway.scriptQueue([
            EngineQueueItem(index: 0, track: EngineTrack(id: "a", title: "A", artist: "x", album: "", durationMs: 1), enqueuedBy: "local"),
        ])
        let library = makeLibrary(gateway: gateway)

        for await event in library.queueEvents {
            if case .snapshot(let snapshot) = event {
                XCTAssertEqual(snapshot.tracks.count, 1)
                XCTAssertEqual(snapshot.tracks.first?.id, "a")
                break
            }
        }
    }

    @MainActor
    func testEqualizerProfileMapsOntoTheTenBands() async {
        let gateway = RecordingGateway()
        let library = makeLibrary(gateway: gateway)
        var profile = EqualizerProfile.flat
        profile.bands[0].gainDb = 6
        profile.bands[9].gainDb = -3
        await library.applyEqualizerProfile(profile)
        let curve = await gateway.equalizerBands()
        XCTAssertEqual(curve[0].gainDb, 6, accuracy: 0.001)
        XCTAssertEqual(curve[9].gainDb, -3, accuracy: 0.001)
    }
}

#endif  // canImport(streamifyFFI)
