import MediaPlayer
import UIKit
import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-04 acceptance lane: the async artwork pipeline — download seam,
// cache discipline, MPMediaItemArtwork wrapping, and the track-transition
// application through the publisher.
// ─────────────────────────────────────────────────────────────────────────────

final class NowPlayingArtworkTests: XCTestCase {

    // ── Test image helpers ────────────────────────────────────────────────

    /// Thread-safe counter for the @Sendable fetch seams (a captured `var`
    /// cannot mutate inside a concurrently-executing closure).
    private final class FetchCounter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var value: Int { lock.withLock { count } }
        func increment() { lock.withLock { count += 1 } }
    }

    private func solidImageData(
        size: CGSize = CGSize(width: 320, height: 320),
        color: UIColor = .systemPurple
    ) -> Data {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { context in
            color.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        return image.pngData()!
    }

    // ── The loader ────────────────────────────────────────────────────────

    @MainActor
    func testArtworkLoaderDownloadsWrapsAndCaches() async {
        let data = solidImageData()
        let counter = FetchCounter()
        let loader = ArtworkLoader(fetch: { _ in
            counter.increment()
            return data
        })

        let first = await loader.artwork(for: "https://example.com/art.jpg")
        XCTAssertNotNil(first)
        // The wrap answers EVERY requested size with the same bitmap.
        let rendered = first?.image(at: CGSize(width: 100, height: 100))
        XCTAssertNotNil(rendered)
        XCTAssertEqual(rendered?.size.width ?? 0, 320, accuracy: 1)

        // Second request: served from the NSCache — no second fetch.
        _ = await loader.artwork(for: "https://example.com/art.jpg")
        XCTAssertEqual(counter.value, 1)
    }

    @MainActor
    func testArtworkLoaderRejectsNilUrlAndGarbageData() async {
        let garbage = ArtworkLoader(fetch: { _ in Data([0x00, 0x01, 0x02]) })
        XCTAssertNil(await garbage.artwork(for: nil))
        XCTAssertNil(await garbage.artwork(for: "not-a-url"))
        // Decodable URL, undecodable payload: nil, never a crash.
        XCTAssertNil(await garbage.artwork(for: "https://example.com/art.jpg"))
    }

    // ── The publisher's track-transition application ──────────────────────

    @MainActor
    private final class RecordingCenter: NowPlayingInfoApplying {
        var applied: [[String: Any]] = []
        func applyNowPlayingInfo(_ info: [String: Any]) {
            applied.append(info)
        }
    }

    private func playingState(
        title: String,
        artworkUrl: String?
    ) -> PlaybackUiState {
        let snapshot = PlaybackSnapshot(
            rev: 1,
            status: .playing,
            volume: 0.5,
            clock: DacClockSample(
                framePosition: 48_000 * 61,
                sampleRateHz: 48_000,
                monotonicNs: 61_000_000_000
            ),
            currentTrack: PresentationTrack(
                id: "t1",
                title: title,
                artist: "Kavinsky",
                durationMs: 252_000,
                artworkUrl: artworkUrl
            )
        )
        return PlaybackUiState(snapshot)
    }

    @MainActor
    func testPublisherAppliesArtworkAsynchronouslyOnTrackTransition() async throws {
        let center = RecordingCenter()
        let artworkData = solidImageData()
        let artwork = ArtworkLoader(fetch: { _ in artworkData })
        let publisher = NowPlayingInfoPublisher(center: center, artworkProvider: artwork)

        publisher.publish(state: playingState(title: "Nightcall", artworkUrl: "https://example.com/a.jpg"))

        // The info dict lands immediately (leading edge) WITHOUT artwork...
        XCTAssertEqual(center.applied.count, 1)
        XCTAssertNil(center.applied.first?[MPMediaItemPropertyArtwork])

        // ...then the async pipeline merges the artwork into the surface.
        try await Task.sleep(nanoseconds: 400_000_000)
        let withArtwork = center.applied.last
        XCTAssertNotNil(withArtwork?[MPMediaItemPropertyArtwork])
        // The base fields survived the merge.
        XCTAssertEqual(withArtwork?[MPMediaItemPropertyTitle] as? String, "Nightcall")

        publisher.clear()
    }

    @MainActor
    func testPublisherSkipsArtworkWhenProviderIsAbsent() async throws {
        let center = RecordingCenter()
        // The pure-gate lane: no artwork provider installed.
        let publisher = NowPlayingInfoPublisher(center: center, artworkProvider: nil)

        publisher.publish(state: playingState(title: "Nightcall", artworkUrl: "https://example.com/a.jpg"))
        try await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(center.applied.count, 1)
        XCTAssertNil(center.applied.first?[MPMediaItemPropertyArtwork])

        publisher.clear()
    }

    @MainActor
    func testPublisherDoesNotRefetchArtworkForTheSameTrack() async throws {
        let center = RecordingCenter()
        let counter = FetchCounter()
        let artwork = ArtworkLoader(fetch: { _ in
            counter.increment()
            return solidImageData()
        })
        let publisher = NowPlayingInfoPublisher(center: center, artworkProvider: artwork)

        // Same artwork URL, two pushes (the 1 Hz cadence's common case):
        // ONE fetch, ONE artwork application.
        publisher.publish(state: playingState(title: "Nightcall", artworkUrl: "https://example.com/a.jpg"))
        publisher.publish(state: playingState(title: "Nightcall", artworkUrl: "https://example.com/a.jpg"))
        try await Task.sleep(nanoseconds: 400_000_000)

        XCTAssertEqual(counter.value, 1)
        let artworkApplies = center.applied.filter {
            $0[MPMediaItemPropertyArtwork] != nil
        }
        XCTAssertEqual(artworkApplies.count, 1)

        publisher.clear()
    }

    // ── The publishNow state-transition escape hatch ──────────────────────

    @MainActor
    func testPublishNowBypassesTheGate() {
        let center = RecordingCenter()
        var virtualNow: UInt64 = 1_000_000_000
        let publisher = NowPlayingInfoPublisher(
            center: center,
            clock: { virtualNow }
        )

        publisher.publish(info: ["k": "leading"])
        // 10 ms later — deep inside the 1 s window — publishNow lands.
        virtualNow += 10_000_000
        publisher.publishNow(info: ["k": "transition"])

        XCTAssertEqual(center.applied.count, 2)
        XCTAssertEqual(center.applied.last?["k"] as? String, "transition")

        publisher.clear()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-05 acceptance lane: the Live Activity policy / waveform /
// scrubber math — the pure pieces behind the ActivityKit surface.
// ─────────────────────────────────────────────────────────────────────────────

final class LiveActivityStateTests: XCTestCase {

    func testUpdatePolicyAdmitsOnlyRenderedValueChanges() {
        // The initial admission.
        XCTAssertTrue(
            LiveActivityUpdatePolicy.shouldUpdate(
                previous: nil,
                title: "Nightcall",
                artist: "Kavinsky",
                isPlaying: true,
                positionSeconds: 61.4,
                durationSeconds: 252
            )
        )
        // Fractional position movement does NOT repaint (the scrubber
        // interpolates locally): same rendered second.
        XCTAssertFalse(
            LiveActivityUpdatePolicy.shouldUpdate(
                previous: "Nightcall|Kavinsky|1|61|252",
                title: "Nightcall",
                artist: "Kavinsky",
                isPlaying: true,
                positionSeconds: 61.9,
                durationSeconds: 252
            )
        )
        // The next whole second repaints.
        XCTAssertTrue(
            LiveActivityUpdatePolicy.shouldUpdate(
                previous: "Nightcall|Kavinsky|1|61|252",
                title: "Nightcall",
                artist: "Kavinsky",
                isPlaying: true,
                positionSeconds: 62.0,
                durationSeconds: 252
            )
        )
        // A transport-state flip repaints immediately.
        XCTAssertTrue(
            LiveActivityUpdatePolicy.shouldUpdate(
                previous: "Nightcall|Kavinsky|1|61|252",
                title: "Nightcall",
                artist: "Kavinsky",
                isPlaying: false,
                positionSeconds: 61.4,
                durationSeconds: 252
            )
        )
    }

    func testFingerprintQuantizesToWholeSeconds() {
        XCTAssertEqual(
            LiveActivityUpdatePolicy.renderedFingerprint(
                title: "T", artist: "A", isPlaying: true,
                positionSeconds: 61.999, durationSeconds: 252
            ),
            LiveActivityUpdatePolicy.renderedFingerprint(
                title: "T", artist: "A", isPlaying: true,
                positionSeconds: 61.001, durationSeconds: 252
            )
        )
    }

    func testWaveformHeightsAreBoundedAndDeterministic() {
        let playing = LiveWaveformModel.heights(phase: 1234, isPlaying: true)
        XCTAssertEqual(playing.count, 4)
        for height in playing {
            XCTAssertGreaterThanOrEqual(height, 0)
            XCTAssertLessThanOrEqual(height, 1)
        }
        // Deterministic: the same phase reproduces exactly.
        XCTAssertEqual(playing, LiveWaveformModel.heights(phase: 1234, isPlaying: true))
        // The animation moves: adjacent phases differ somewhere.
        let next = LiveWaveformModel.heights(phase: 1235, isPlaying: true)
        XCTAssertNotEqual(playing, next)
        // Paused: the static low-key profile.
        XCTAssertEqual(
            LiveWaveformModel.heights(phase: 1234, isPlaying: false),
            [0.18, 0.18, 0.18, 0.18]
        )
    }

    func testScrubberInterpolationAdvancesAndFreezes() {
        let anchor = Date(timeIntervalSince1970: 100)
        // Playing: position advances with the elapsed time.
        XCTAssertEqual(
            LiveActivityMath.interpolatedPositionSeconds(
                positionSeconds: 30,
                positionUpdatedAt: anchor,
                now: anchor.addingTimeInterval(2),
                isPlaying: true
            ),
            32,
            accuracy: 1e-9
        )
        // Paused: frozen at the anchor.
        XCTAssertEqual(
            LiveActivityMath.interpolatedPositionSeconds(
                positionSeconds: 30,
                positionUpdatedAt: anchor,
                now: anchor.addingTimeInterval(2),
                isPlaying: false
            ),
            30,
            accuracy: 1e-9
        )
    }

    func testScrubberProgressClampsAndReportsUnknownDurations() {
        let anchor = Date(timeIntervalSince1970: 100)
        // Unknown duration: indeterminate.
        XCTAssertNil(
            LiveActivityMath.progress(
                positionSeconds: 30,
                positionUpdatedAt: anchor,
                durationSeconds: 0,
                now: anchor,
                isPlaying: true
            )
        )
        // Beyond the end: clamps to 1.
        XCTAssertEqual(
            LiveActivityMath.progress(
                positionSeconds: 250,
                positionUpdatedAt: anchor,
                durationSeconds: 252,
                now: anchor.addingTimeInterval(10),
                isPlaying: true
            ),
            1,
            accuracy: 1e-9
        )
        // Halfway through.
        XCTAssertEqual(
            LiveActivityMath.progress(
                positionSeconds: 126,
                positionUpdatedAt: anchor,
                durationSeconds: 252,
                now: anchor,
                isPlaying: true
            ),
            0.5,
            accuracy: 1e-9
        )
    }

    func testContentStateRoundTripsThroughCodable() throws {
        let state = StreamifyPlaybackAttributes.ContentState(
            title: "Nightcall",
            artist: "Kavinsky",
            isPlaying: true,
            positionSeconds: 61.5,
            positionUpdatedAt: Date(timeIntervalSince1970: 1_700_000_000),
            durationSeconds: 252,
            artwork: Data([1, 2, 3])
        )
        let decoded = try JSONDecoder().decode(
            StreamifyPlaybackAttributes.ContentState.self,
            from: JSONEncoder().encode(state)
        )
        XCTAssertEqual(decoded.title, state.title)
        XCTAssertEqual(decoded.isPlaying, state.isPlaying)
        XCTAssertEqual(decoded.positionSeconds, state.positionSeconds, accuracy: 1e-9)
        XCTAssertEqual(decoded.artwork, state.artwork)
        XCTAssertEqual(decoded.positionUpdatedAt, state.positionUpdatedAt)
    }

    func testThumbnailDownsampleBoundsTheDecodedSize() {
        let big = UIGraphicsImageRenderer(
            size: CGSize(width: 1024, height: 1024)
        ).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
        }
        let jpeg = big.jpegData(compressionQuality: 0.9)!
        let thumbnail = LiveActivityArtworkProvider.downsample(
            jpeg,
            maxPixelSize: 192,
            jpegQuality: 0.75
        )
        XCTAssertNotNil(thumbnail)
        let decoded = thumbnail.flatMap(UIImage.init(data:))
        XCTAssertNotNil(decoded)
        XCTAssertLessThanOrEqual(max(decoded!.size.width, decoded!.size.height), 192)
        // A small image passes through untouched (still re-encoded JPEG).
        let small = solidImageData(size: CGSize(width: 64, height: 64))
        let smallThumb = LiveActivityArtworkProvider.downsample(small)
        XCTAssertNotNil(smallThumb)
    }

    private func solidImageData(size: CGSize) -> Data {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { context in
            UIColor.systemPurple.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        return image.pngData()!
    }
}
