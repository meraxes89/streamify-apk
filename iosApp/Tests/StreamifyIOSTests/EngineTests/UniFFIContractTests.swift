import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-03 / SW-ENG1-04: the UniFFI contract tests.
//
// TWO LANES, ONE FILE:
//   * The REAL-LANE suite (gated on `#if canImport(uniffi_streamify)`)
//     runs only when StreamifyCore.xcframework is linked (the conditional
//     binary target in Package.swift). It proves Swift correctly
//     instantiates and deserializes Track, SearchResult and EqualizerBand
//     ACROSS the FFI boundary — real records crossing real trampolines,
//     including the push-event bridge (SW-ENG1-04: EngineEventListener
//     dispatch from the Rust worker thread into Swift AsyncStream).
//   * The ALWAYS-ON suites exercise the lane-neutral typed surface (the
//     Engine* mirrors, the preview gateway double, the daylist circadian
//     math, the anti-drift scorer and the canvas/clips parsers) — they run
//     in every configuration, including the standalone CI lane.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(uniffi_streamify)

final class UniFFIContractTests: XCTestCase {

    private func makeEngine() throws -> StreamifyEngine {
        let engine = StreamifyEngine()
        try engine.initialize()
        return engine
    }

    /// SearchResult deserialization: every field of every record crosses
    /// the FFI boundary populated (the SW-ENG1-03 fixture is
    /// deterministic and network-free).
    func testSearchResultDeserializesAcrossTheBoundary() throws {
        let page = try selfTestSearchResult(trackCount: 5)

        XCTAssertEqual(page.tracks.count, 5)
        XCTAssertEqual(page.playlists.count, 1)
        XCTAssertEqual(page.continuationToken, "selftest-continuation-token")

        let first = try XCTUnwrap(page.tracks.first)
        XCTAssertEqual(first.id, "selftest_00_abcdefghij")
        XCTAssertEqual(first.title, "Self Test Track 00")
        XCTAssertEqual(first.album, "UniFFI Contract Round-Trip")
        XCTAssertEqual(first.durationMs, 60_000)
        XCTAssertEqual(first.thumbnailUrl, "https://i.ytimg.com/vi/selftest_00/mqdefault.jpg")
        XCTAssertTrue(first.isExplicit) // every 5th row (0, 5, 10…)
        XCTAssertNil(first.resolvedStreamUrl) // search rows resolve lazily

        XCTAssertFalse(page.tracks[1].isExplicit)
        XCTAssertNil(page.tracks[2].resolvedStreamUrl)

        let playlist = try XCTUnwrap(page.playlists.first)
        XCTAssertEqual(playlist.id, "selftest_playlist_abcdefghij")
        XCTAssertEqual(playlist.trackCount, 12)
        XCTAssertNotNil(playlist.thumbnailUrl)
    }

    /// Track instantiation + round-trip: a Swift-constructed Track
    /// crosses INTO Rust (enqueue) and back OUT (get_queue) field-for-field.
    func testTrackInstantiatesAndRoundTripsThroughTheQueue() throws {
        let engine = try makeEngine()
        defer { engine.clearQueue() }

        let track = Track(
            id: "swiftlane_01_abcdefghij",
            title: "Swift Lane Round-Trip",
            artist: "XCTest",
            album: "UniFFI Contract",
            durationMs: 252_000,
            thumbnailUrl: "https://i.ytimg.com/vi/swiftlane/mqdefault.jpg",
            isExplicit: true,
            resolvedStreamUrl: nil
        )
        let index = try engine.enqueueTrack(track: track, enqueuedBy: "xctest")
        XCTAssertEqual(index, 0)

        let queue = try engine.getQueue()
        XCTAssertEqual(queue.count, 1)
        let item = try XCTUnwrap(queue.first)
        XCTAssertEqual(item.index, 0)
        XCTAssertEqual(item.enqueuedBy, "xctest")
        XCTAssertEqual(item.track.id, track.id)
        XCTAssertEqual(item.track.title, track.title)
        XCTAssertEqual(item.track.artist, track.artist)
        XCTAssertEqual(item.track.album, track.album)
        XCTAssertEqual(item.track.durationMs, track.durationMs)
        XCTAssertEqual(item.track.thumbnailUrl, track.thumbnailUrl)
        XCTAssertEqual(item.track.isExplicit, track.isExplicit)
        XCTAssertEqual(item.track.resolvedStreamUrl, track.resolvedStreamUrl)

        engine.clearQueue()
        XCTAssertTrue(try engine.getQueue().isEmpty) // idempotent clear
    }

    /// EqualizerBand deserialization + the ten-band DSP-parity curve.
    func testEqualizerBandsDeserializeAndRoundTrip() throws {
        let engine = try makeEngine()

        let curve = try engine.getEqualizerBands()
        XCTAssertEqual(curve.count, 10)
        XCTAssertEqual(curve[0].frequencyHz, 31.25)  // low shelf
        XCTAssertEqual(curve[9].frequencyHz, 16_000) // high shelf
        XCTAssertEqual(curve[3].frequencyHz, 250)
        XCTAssertTrue(curve.allSatisfy { $0.gainDb == 0 })

        let boosted = EqualizerBand(
            bandIndex: 4,
            frequencyHz: 500,
            gainDb: -3.5,
            qFactor: 0.9
        )
        try engine.setEqualizerBand(band: boosted)
        let after = try engine.getEqualizerBands()
        XCTAssertEqual(after[4].bandIndex, boosted.bandIndex)
        XCTAssertEqual(after[4].gainDb, boosted.gainDb)
        XCTAssertEqual(after[4].qFactor, boosted.qFactor)
        XCTAssertEqual(after[2].gainDb, 0) // untouched bands stay flat

        // Hostile input is rejected at the Rust boundary as a typed error.
        XCTAssertThrowsError(
            try engine.setEqualizerBand(
                band: EqualizerBand(bandIndex: 99, frequencyHz: 500, gainDb: 0, qFactor: 1)
            )
        )
        XCTAssertThrowsError(
            try engine.setEqualizerBand(
                band: EqualizerBand(bandIndex: 1, frequencyHz: 500, gainDb: .infinity, qFactor: 1)
            )
        )
    }

    /// JamRoomState mirrors the active session; no session is a typed error.
    func testJamRoomStateDeserializes() throws {
        let engine = try makeEngine()
        XCTAssertThrowsError(try engine.getJamRoomState()) // no live session

        let handle = try engine.createJamSession(sessionName: "Contract Room")
        let room = try engine.getJamRoomState()
        XCTAssertEqual(room.roomCode, "Contract Room")
        XCTAssertTrue(room.isHost)
        XCTAssertEqual(room.syncOffsetMs, 0)
        XCTAssertEqual(room.participants.first, "local:\(handle.sessionId)")
    }

    /// SW-ENG1-04: the push-event bridge — a control-plane mutation on the
    /// engine surfaces as an AsyncStream element through the gateway's
    /// EngineEventListener bridge (Rust worker thread → Swift stream).
    func testPushEventBridgeDeliversSnapshotsIntoTheAsyncStream() throws {
        let gateway = try UniFFIEngineGateway()
        defer { gateway.shutdown() }

        let pushed = expectation(description: "volume mutation pushed into the stream")
        let collector = Task {
            for await snapshot in gateway.snapshots {
                if abs(snapshot.volume - 0.25) < 0.001 {
                    pushed.fulfill()
                    break
                }
            }
        }
        // Give the collector a beat to subscribe before mutating (the
        // registration push itself also proves delivery).
        try? await Task.sleep(nanoseconds: 200_000_000)
        let result = await gateway.send(.setVolume(0.25))
        XCTAssertEqual(result, .applied)

        await fulfillment(of: [pushed], timeout: 10)
        collector.cancel()
    }
}

#endif  // canImport(uniffi_streamify)

// ─────────────────────────────────────────────────────────────────────────────
// ALWAYS-ON: the lane-neutral typed surface (runs in every configuration).
// ─────────────────────────────────────────────────────────────────────────────

final class TypedDataLanePreviewTests: XCTestCase {

    func testSearchFiltersTheFixtureCatalog() async throws {
        let gateway = PreviewEngineGateway()
        let page = try await gateway.searchTracks("kavinsky", limit: 5)
        XCTAssertEqual(page.tracks.count, 1)
        XCTAssertEqual(page.tracks.first?.title, "Nightcall")
        XCTAssertEqual(page.playlists.count, 1)
        XCTAssertEqual(page.continuationToken, "preview-continuation-kavinsky")

        // Blank queries return an empty page, not a throw.
        let blank = try await gateway.searchTracks("   ", limit: 5)
        XCTAssertTrue(blank.tracks.isEmpty)
        XCTAssertTrue(blank.playlists.isEmpty)
    }

    func testQueueLifecycleOnThePreviewDouble() async {
        let gateway = PreviewEngineGateway()
        let track = PreviewEngineGateway.fixtureCatalog[0]
        let index = await gateway.enqueue(track: track, enqueuedBy: "")
        XCTAssertEqual(index, 0)
        let second = await gateway.enqueue(track: PreviewEngineGateway.fixtureCatalog[1], enqueuedBy: "peer")
        XCTAssertEqual(second, 1)

        let queue = await gateway.queue()
        XCTAssertEqual(queue.count, 2)
        XCTAssertEqual(queue[0].enqueuedBy, "local") // blank attribution normalized
        XCTAssertEqual(queue[1].enqueuedBy, "peer")
        XCTAssertEqual(queue[1].track.id, PreviewEngineGateway.fixtureCatalog[1].id)

        await gateway.clearQueue()
        let emptied = await gateway.queue()
        XCTAssertTrue(emptied.isEmpty)
    }

    func testEqualizerCurveOnThePreviewDouble() async {
        let gateway = PreviewEngineGateway()
        let curve = await gateway.equalizerBands()
        XCTAssertEqual(curve, EngineEqualizerBand.defaultCurve())

        var band = curve[7]
        band.gainDb = 4.5
        await gateway.setEqualizerBand(band)
        let after = await gateway.equalizerBands()
        XCTAssertEqual(after[7].gainDb, 4.5)
        XCTAssertEqual(after[6].gainDb, 0)

        // Out-of-range band writes are swallowed (boundary rejection).
        await gateway.setEqualizerBand(EngineEqualizerBand(bandIndex: 99, frequencyHz: 1000))
        let unchanged = await gateway.equalizerBands()
        XCTAssertEqual(unchanged, after)
    }

    func testJamRoomStateRequiresACreatedSessionOnThePreviewDouble() async {
        let gateway = PreviewEngineGateway()
        let absent = await gateway.jamRoomState()
        XCTAssertNil(absent)

        _ = await gateway.createJamSession(name: "Preview Room")
        let room = await gateway.jamRoomState()
        XCTAssertEqual(room?.roomCode, "Preview Room")
        XCTAssertEqual(room?.isHost, true)
        XCTAssertGreaterThanOrEqual(room?.participants.count ?? 0, 2)
    }

    func testFailAllArmsTheFailurePaths() async {
        let gateway = PreviewEngineGateway()
        gateway.failAll = true
        do {
            _ = try await gateway.searchTracks("anything", limit: 3)
            XCTFail("failAll must make search throw")
        } catch {
            // expected
        }
        let queue = await gateway.queue()
        XCTAssertTrue(queue.isEmpty)
        let bands = await gateway.equalizerBands()
        XCTAssertTrue(bands.isEmpty)
        let room = await gateway.jamRoomState()
        XCTAssertNil(room)
    }

    func testPresentationTrackBridging() {
        let engine = EngineTrack(
            id: "bridge",
            title: "Bridged",
            artist: "Engineer Two",
            album: "Mirrors",
            durationMs: 200_000,
            thumbnailUrl: "https://example.com/a.jpg"
        )
        let presentation = PresentationTrack(engine)
        XCTAssertEqual(presentation.id, "bridge")
        XCTAssertEqual(presentation.title, "Bridged")
        XCTAssertEqual(presentation.durationMs, 200_000)
        XCTAssertEqual(presentation.artworkUrl, "https://example.com/a.jpg")
    }
}

final class DaylistScheduleEngineTests: XCTestCase {

    func testBucketEdgesFollowTheCircadianPlan() {
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 5), .lateNight)  // 05:59 wrap
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 6), .morning)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 10), .morning)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 11), .afternoon)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 16), .afternoon)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 17), .evening)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 21), .evening)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 22), .lateNight)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 23), .lateNight)
        XCTAssertEqual(DaylistTimeBuckets.bucket(forHour: 0), .lateNight)
    }

    func testRefreshSlotsAreFourHourBands() {
        XCTAssertEqual(DaylistTimeBuckets.refreshSlot(forHour: 0), 0)
        XCTAssertEqual(DaylistTimeBuckets.refreshSlot(forHour: 6), 1) // 06:00 → slot 1
        XCTAssertEqual(DaylistTimeBuckets.refreshSlot(forHour: 15), 3)
        XCTAssertEqual(DaylistTimeBuckets.refreshSlot(forHour: 23), 5)
    }

    func testMoodQueriesRotateArtistsAcrossKeywords() {
        // Cold start: pure mood keywords.
        XCTAssertEqual(
            DaylistTimeBuckets.moodQueries(for: .morning, topArtists: []),
            ["Focus", "Upbeat Acoustic", "Morning Pop"]
        )
        // Artist × keyword rotation (the Kotlin parity shape).
        XCTAssertEqual(
            DaylistTimeBuckets.moodQueries(for: .evening, topArtists: ["  Kavinsky  ", "M83", "Air", "Grimes"]),
            ["Kavinsky Chill R&B", "M83 Lo-Fi", "Air Sunset Unwind"]
        )
    }

    func testHeroTitleMatchesTheKotlinFixture() {
        // The directive's exact hero: slot 1 morning Tuesday.
        XCTAssertEqual(
            DaylistTimeBuckets.heroTitle(for: .morning, slot: 1, weekday: .tuesday),
            "Acoustic Morning Chill Tuesday"
        )
        // The adjective rotates with the slot.
        XCTAssertEqual(
            DaylistTimeBuckets.heroTitle(for: .morning, slot: 0, weekday: .tuesday),
            "Golden Morning Chill Tuesday"
        )
        XCTAssertEqual(
            DaylistTimeBuckets.heroSubtitle(for: .afternoon, slot: 2),
            "High Tempo Hits • Refreshes every 4 hours"
        )
    }

    func testInterleaveRoundRobbinsAndDedups() {
        let a = [
            DaylistTrack(videoId: "aaaaaaaaaa1", title: "A1", artist: "X"),
            DaylistTrack(videoId: "aaaaaaaaaa2", title: "A2", artist: "X"),
        ]
        let b = [
            DaylistTrack(videoId: "bbbbbbbbbb1", title: "A1", artist: "X"),  // fuzzy dup of A1
            DaylistTrack(videoId: "bbbbbbbbbb2", title: "B2", artist: "Y"),
        ]
        let interleaved = DaylistScheduleEngine.interleave([a, b], limit: 10)
        // Round-robin by depth: A1 (d0), then d1 in pool order — A2, then
        // B2 (B1 was deduped at depth 0).
        XCTAssertEqual(interleaved.map(\.videoId), ["aaaaaaaaaa1", "aaaaaaaaaa2", "bbbbbbbbbb2"])

        // The limit caps the shelf.
        let capped = DaylistScheduleEngine.interleave([a, b], limit: 2)
        XCTAssertEqual(capped.count, 2)
    }

    func testSWRCacheServesFreshThenSurvivesAFailedHarvest() async {
        // Scripted clock: t0, then t0 + 1h (fresh), then t0 + 5h (stale)
        // with a failing searcher — the stale shelf must win.
        var now: Int64 = 1_000_000
        let clock = { now }

        let freshTrack = DaylistTrack(videoId: "cccccccccc1", title: "Cached", artist: "C")
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("daylist-tests-\(UUID().uuidString)", isDirectory: true)

        let engine = DaylistScheduleEngine(
            trackSearcher: { _, _ in [freshTrack] },
            cacheDirectory: dir,
            clock: clock
        )
        defer { try? FileManager.default.removeItem(at: dir) }

        // Cold: harvests and caches.
        let fresh = await engine.currentDaylist(hour: 8, weekday: .tuesday, topArtists: [])
        XCTAssertEqual(fresh.tracks.map(\.videoId), ["cccccccccc1"])
        XCTAssertFalse(fresh.fromCache)

        // Fresh window: serves from cache without a harvest.
        now += 60 * 60 * 1000
        let cached = await engine.currentDaylist(hour: 8, weekday: .tuesday, topArtists: [])
        XCTAssertEqual(cached.tracks.map(\.videoId), ["cccccccccc1"])
        XCTAssertTrue(cached.fromCache)

        // Stale window + failed harvest: the stale shelf still serves.
        now += 5 * 60 * 60 * 1000
        let failing = DaylistScheduleEngine(
            trackSearcher: { _, _ in [] },
            cacheDirectory: dir,
            clock: clock
        )
        let survived = await failing.currentDaylist(hour: 8, weekday: .tuesday, topArtists: [])
        XCTAssertEqual(survived.tracks.map(\.videoId), ["cccccccccc1"])
        XCTAssertTrue(survived.fromCache)

        // Total failure (no cache): empty shelf with the hero intact.
        let virgin = DaylistScheduleEngine(
            trackSearcher: { _, _ in [] },
            cacheDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("daylist-empty-\(UUID().uuidString)", isDirectory: true),
            clock: clock
        )
        let empty = await virgin.currentDaylist(hour: 22, weekday: .friday, topArtists: [])
        XCTAssertTrue(empty.tracks.isEmpty)
        XCTAssertEqual(empty.bucket, .lateNight)
        XCTAssertTrue(empty.title.contains("Friday"))
    }
}

final class AntiDriftScoringTests: XCTestCase {

    private func track(
        _ id: String,
        _ title: String,
        _ artist: String,
        bpm: Float = 120,
        key: String = "8A",
        durationSec: Int = 200
    ) -> DriftScorableTrack {
        DriftScorableTrack(
            id: id,
            title: title,
            artist: artist,
            durationSec: durationSec,
            bpm: bpm,
            key: key
        )
    }

    func testJunkAndDurationFilters() {
        let seed = track("seed", "Seed Song", "Seeder", durationSec: 200)
        let candidates = [
            track("ok", "Good Song", "Artist A"),
            track("junk", "Chill Hop 1 Hour Mix", "Artist B"),
            track("junk2", "Best Songs Mix 2026", "Artist C"),
            track("long", "Two Hour Rave", "Artist D", durationSec: 7300),
            track("short", "Tiny", "Artist E", durationSec: 20),
        ]
        let ranked = AntiDriftScoringEngine.filterAndRankCandidates(
            candidates,
            seedTrack: seed,
            activeQueue: []
        )
        XCTAssertEqual(ranked.map(\.id), ["ok"])
    }

    func testArtistSaturationCapAndQueuePriming() {
        let seed = track("seed", "Seed Song", "Seeder")
        // Queue already holds two tracks by Saturated Sam.
        let queue = [
            track("q1", "Sam One", "Saturated Sam"),
            track("q2", "Sam Two", "Saturated Sam"),
        ]
        let candidates = [
            track("s3", "Sam Three", "Saturated Sam"), // capped out
            track("fresh", "Fresh Track", "New Artist"),
        ]
        let ranked = AntiDriftScoringEngine.filterAndRankCandidates(
            candidates,
            seedTrack: seed,
            activeQueue: queue
        )
        XCTAssertEqual(ranked.map(\.id), ["fresh"])
    }

    func testSignatureDedupAndAffinityOrdering() {
        let seed = track("seed", "Seed Song", "Seeder", bpm: 120, key: "8A")
        let duplicate = track("dup", "  seed song  ", "  seeder  ") // signature dup
        let harmonic = track("h", "Harmonic", "Alpha", bpm: 120, key: "8A")
        let far = track("f", "Far", "Beta", bpm: 40, key: "5B")
        let ranked = AntiDriftScoringEngine.filterAndRankCandidates(
            [far, duplicate, harmonic],
            seedTrack: seed,
            activeQueue: []
        )
        XCTAssertEqual(ranked.first?.id, "h") // BPM + key affinity wins
        XCTAssertFalse(ranked.contains { $0.id == "dup" })
    }

    func testUnanalyzedTracksGetTheNeutralBaseline() {
        let seed = track("seed", "Seed Song", "Seeder", bpm: 120, key: "8A")
        let stream = DriftScorableTrack(id: "s", title: "Stream Track", artist: "Gamma")
        let ranked = AntiDriftScoringEngine.filterAndRankCandidates(
            [stream],
            seedTrack: seed,
            activeQueue: []
        )
        XCTAssertEqual(ranked.map(\.id), ["s"]) // neutral baseline, still ranked
    }
}

final class CanvasScraperParserTests: XCTestCase {

    func testParsePlayerAuxiliaryAssetsFastPath() {
        let root: [String: Any] = [
            "videoId": "aaaaaaaaaaa",
            "auxiliaryAssets": [
                [
                    "auxiliaryAssetRenderer": [
                        "type": "CANVAS",
                        "url": "https://rr1---sn-1.googlevideo.com/canvas/loop.mp4",
                        "mimeType": "video/mp4",
                        "durationMs": 8000,
                        "width": 720,
                        "height": 1280,
                    ] as [String: Any],
                ] as [String: Any],
            ],
        ]
        let loop = CanvasResponseParser.parsePlayer(root: root, videoId: "aaaaaaaaaaa")
        XCTAssertEqual(loop?.loopUrl, "https://rr1---sn-1.googlevideo.com/canvas/loop.mp4")
        XCTAssertEqual(loop?.mimeType, "video/mp4")
        XCTAssertEqual(loop?.durationMs, 8000)
        XCTAssertEqual(loop?.width, 720)
        XCTAssertEqual(loop?.height, 1280)
        XCTAssertEqual(loop?.isVertical, true)
        XCTAssertEqual(loop?.fromCache, false)
    }

    func testParsePlayerWalksUndocumentedPaths() {
        let root: [String: Any] = [
            "playerOverlays": [
                "decoratedPlayerBarRenderer": [
                    "canvasMediaRenderer": [
                        "loopUrl": "https://googlevideo.com/videoplayback/loop.webm",
                        "durationMs": 9000,
                    ] as [String: Any],
                ] as [String: Any],
            ] as [String: Any],
        ]
        let loop = CanvasResponseParser.parsePlayer(root: root, videoId: "aaaaaaaaaaa")
        XCTAssertEqual(loop?.loopUrl, "https://googlevideo.com/videoplayback/loop.webm")
        XCTAssertEqual(loop?.durationMs, 9000)
        XCTAssertEqual(loop?.mimeType, "video/mp4") // default when absent
    }

    func testFirstSongVideoIdRequiresSongishParent() {
        let root: [String: Any] = [
            "contents": [
                "musicResponsiveListItemRenderer": ["videoId": "bbbbbbbbbbb"],
            ],
        ]
        XCTAssertEqual(CanvasResponseParser.firstSongVideoId(root: root), "bbbbbbbbbbb")

        let noParent: [String: Any] = ["videoId": "ccccccccccc"]
        XCTAssertNil(CanvasResponseParser.firstSongVideoId(root: noParent))
    }

    func testDurationBadgeDecoding() {
        XCTAssertEqual(ClipsResponseParser.parseDurationBadge("3:12"), 192)
        XCTAssertEqual(ClipsResponseParser.parseDurationBadge("0:45"), 45)
        XCTAssertEqual(ClipsResponseParser.parseDurationBadge("45s"), 45)
        XCTAssertEqual(ClipsResponseParser.parseDurationBadge("30 seconds"), 30)
        XCTAssertEqual(ClipsResponseParser.parseDurationBadge("0:32 • 1.2M views"), 32)
        XCTAssertNil(ClipsResponseParser.parseDurationBadge("1.2M views"))
        XCTAssertNil(ClipsResponseParser.parseDurationBadge(""))
    }

    func testStripDurationFromBadge() {
        XCTAssertEqual(ClipsResponseParser.stripDurationFromBadge("0:32 • 1.2M views"), "1.2M views")
        XCTAssertEqual(ClipsResponseParser.stripDurationFromBadge("45s · Hot"), "Hot")
    }

    func testClipsRendererSignatures() {
        // reelItemRenderer (legacy Shorts shelf).
        let reel: [String: Any] = [
            "reelItemRenderer": [
                "videoId": "ddddddddddd",
                "headline": ["simpleText": "Reel Title"],
                "thumbnail": ["thumbnails": [
                    ["url": "https://i.ytimg.com/vi/d/mqdefault.jpg"],
                    ["url": "https://i.ytimg.com/vi/d/maxresdefault.jpg"],
                ]] as [String: Any],
            ] as [String: Any],
        ]
        let reelClips = ClipsResponseParser.collectClips(reel, maxClips: 5)
        XCTAssertEqual(reelClips.count, 1)
        XCTAssertEqual(reelClips.first?.videoId, "ddddddddddd")
        XCTAssertEqual(reelClips.first?.title, "Reel Title")
        // Longest URL wins — Kotlin maxByOrNull { length } parity (ties
        // would fall to the first, so the fixture uses distinct lengths).
        XCTAssertEqual(reelClips.first?.thumbnailUrl, "https://i.ytimg.com/vi/d/maxresdefault.jpg")

        // clipRenderer (curated watch-next clip) with accessibility label.
        let curated: [String: Any] = [
            "clipRenderer": [
                "videoId": "eeeeeeeeeee",
                "title": [
                    "accessibility": ["accessibilityData": ["label": "Clip Label"]] as [String: Any],
                ] as [String: Any],
                "durationText": "0:41",
            ] as [String: Any],
        ]
        let curatedClips = ClipsResponseParser.collectClips(curated, maxClips: 5)
        XCTAssertEqual(curatedClips.first?.title, "Clip Label")
        XCTAssertEqual(curatedClips.first?.durationSec, 41)
        XCTAssertEqual(curatedClips.first?.isVertical30s, true)

        // shortVideoRenderer WITHOUT a shelf header stays out (stray filter).
        let stray: [String: Any] = [
            "shortVideoRenderer": [
                "videoId": "ffffffffff",
                "title": ["runs": [["text": "Stray"]]] as [String: Any],
                "lengthText": ["simpleText": "0:30"] as [String: Any],
            ] as [String: Any],
        ]
        XCTAssertTrue(ClipsResponseParser.collectClips(stray, maxClips: 5).isEmpty)

        // …but WITH a Clips shelf header anywhere in the tree, it counts.
        let shelved: [String: Any] = [
            "shelfRenderer": ["title": "Popular clips"] as [String: Any],
            "shortVideoRenderer": [
                "videoId": "ffffffffff",
                "title": ["runs": [["text": "Stray"]]] as [String: Any],
                "lengthText": ["simpleText": "0:30"] as [String: Any],
            ] as [String: Any],
        ]
        XCTAssertEqual(ClipsResponseParser.collectClips(shelved, maxClips: 5).count, 1)
    }

    func testFirstArtistChannelId() {
        let root: [String: Any] = [
            "musicResponsiveListItemRenderer": [
                "navigationEndpoint": ["browseEndpoint": ["browseId": "UCabcdefghij123456789012"]] as [String: Any],
            ] as [String: Any],
        ]
        XCTAssertEqual(ClipsResponseParser.firstArtistChannelId(root), "UCabcdefghij123456789012")
    }
}
