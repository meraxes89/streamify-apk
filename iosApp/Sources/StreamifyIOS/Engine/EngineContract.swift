import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-04 (Swift migration, Engineer 1): the typed data lane's Swift-native
// currency — mirrors of the SW-ENG1-03 UniFFI records (Track, SearchResult,
// QueueItem, EqualizerBand, JamRoomState) the engine gateway shuttles.
//
// WHY MIRRORS INSTEAD OF THE GENERATED TYPES DIRECTLY: the generated
// `uniffi_streamify.swift` bindings declare `Track` / `SearchResult` / … as
// top-level types of THIS module, but only when the real lane is linked
// (the probe-gate). Every consumer that must compile in BOTH lanes — the
// EngineGateway protocol, the preview gateway, Engineer 2's views,
// Engineer 4's ViewModels — speaks these lane-neutral `Engine*` mirrors
// instead; UniFFIEngineGateway maps generated → mirror at the seam
// (total by construction), PreviewEngineGateway synthesizes mirrors
// directly. The `Engine` prefix is deliberate: it can never collide with
// a generated record name.
// ─────────────────────────────────────────────────────────────────────────────

/// Fully typed playable track (mirror of the UDL `Track`, SW-ENG1-03).
struct EngineTrack: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let artist: String
    let album: String
    /// Total duration in milliseconds.
    let durationMs: Int
    var thumbnailUrl: String?
    /// False means "not flagged", not "provably clean".
    var isExplicit: Bool
    /// Playable CDN URL once resolved; nil on search rows (resolution is
    /// the explicit cache-backed step).
    var resolvedStreamUrl: String?

    init(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Int,
        thumbnailUrl: String? = nil,
        isExplicit: Bool = false,
        resolvedStreamUrl: String? = nil
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.thumbnailUrl = thumbnailUrl
        self.isExplicit = isExplicit
        self.resolvedStreamUrl = resolvedStreamUrl
    }
}

/// One playlist row of a search page (mirror of the UDL `PlaylistSummary`).
struct EnginePlaylistSummary: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    var thumbnailUrl: String?
    var trackCount: Int
}

/// One typed search response (mirror of the UDL `SearchResult`).
struct EngineSearchResult: Equatable, Sendable {
    var tracks: [EngineTrack]
    var playlists: [EnginePlaylistSummary]
    var continuationToken: String?

    init(
        tracks: [EngineTrack] = [],
        playlists: [EnginePlaylistSummary] = [],
        continuationToken: String? = nil
    ) {
        self.tracks = tracks
        self.playlists = playlists
        self.continuationToken = continuationToken
    }
}

/// One staged queue row (mirror of the UDL `QueueItem`).
struct EngineQueueItem: Equatable, Sendable, Identifiable {
    /// Stable position at read time (the whole page is one consistent
    /// generation).
    let index: Int
    let track: EngineTrack
    let enqueuedBy: String

    var id: String { "\(index)-\(track.id)" }
}

/// One parametric equalizer band (mirror of the UDL `EqualizerBand`).
/// The band plan mirrors the DSP crate's StudioEqualizer: ten
/// octave-spaced centers, low shelf on 0, high shelf on 9, Butterworth Q.
struct EngineEqualizerBand: Equatable, Sendable {
    let bandIndex: Int
    let frequencyHz: Float
    var gainDb: Float
    var qFactor: Float

    init(bandIndex: Int, frequencyHz: Float, gainDb: Float = 0, qFactor: Float = Self.butterworthQ) {
        self.bandIndex = bandIndex
        self.frequencyHz = frequencyHz
        self.gainDb = gainDb
        self.qFactor = qFactor
    }
}

extension EngineEqualizerBand {
    /// The octave-spaced band centers, 31.25 Hz … 16 kHz (the DSP
    /// crate's StudioEqualizer plan — parity with
    /// `streamify_audio::dsp::StudioEqualizer::FREQUENCIES`).
    static let centerFrequenciesHz: [Float] = [
        31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
    ]

    /// Butterworth reference Q (1.414), the studio default.
    static let butterworthQ: Float = 1.414

    /// The flat ten-band curve every surface starts from.
    static func defaultCurve() -> [EngineEqualizerBand] {
        centerFrequenciesHz.enumerated().map { index, hz in
            EngineEqualizerBand(bandIndex: index, frequencyHz: hz)
        }
    }
}

/// Live Jam room state (mirror of the UDL `JamRoomState`).
struct EngineJamRoomState: Equatable, Sendable {
    let roomCode: String
    let isHost: Bool
    /// PTP presentation offset in milliseconds (0 until the PLL lane
    /// crosses the contract).
    let syncOffsetMs: Double
    let participants: [String]
}

// MARK: - WS-B B3/B4: the drivetrain v2 mirrors (UDL CONTRACT_VERSION 2,
// PR #151). Same lane-neutral discipline as above: the generated types only
// exist when the real lane is linked, so every both-lane consumer speaks
// these mirrors and UniFFIEngineGateway maps at the seam.

/// One playable CDN format for a track id (mirror of the UDL
/// `ResolvedStreamFormat`). The resolver lists audio-only rows FIRST —
/// `bestAudio` encodes that selection rule once.
struct EngineResolvedStreamFormat: Equatable, Sendable {
    let url: String
    let mimeType: String
    let bitrate: Int
    let durationSec: Int
    let isAudioOnly: Bool

    /// The canonical playback pick: the first audio-only row, falling back
    /// to the first row (the A5 self-test's consumption discipline).
    static func bestAudio(of formats: [EngineResolvedStreamFormat]) -> EngineResolvedStreamFormat? {
        formats.first(where: \.isAudioOnly) ?? formats.first
    }
}

/// Live playback-plane progress (mirror of the UDL `BufferProgress`, A4):
/// ring occupancy, underrun/overrun accounting, the transport's seek target
/// and the DAC-clock-derived rendered position of the current session.
struct EngineBufferProgress: Equatable, Sendable {
    let bufferedMs: Int
    /// Ring fill ratio in [0.0, 1.0].
    let fillRatio: Double
    let underrunEvents: Int
    let overrunEvents: Int
    /// The last accepted seek target (A2 transport discipline).
    let targetPositionMs: Int
    /// DAC-clock-derived position since the session anchor; 0 until a
    /// hardware clock sample exists (MP-DEC-03 — never wall-interpolated).
    let renderedPositionMs: Int
    /// The current producer session's track id (queue correlation).
    let trackId: String?
    /// True while the sink is attached and the producer thread is live.
    let producerLive: Bool
    /// True once the final frame is decoded and committed (the ring still
    /// drains).
    let eos: Bool
}

// MARK: - WS-B B3/B4: the drivetrain v2 surface tuning (lane-neutral)

/// Playback-plane tuning shared by both lanes (A5 self-test parity).
enum EnginePlaybackPlane {
    /// The ring capacity the play path mounts with: 8192 frames ≈ 170 ms
    /// of canonical stereo — the tuning the `live_audio_selftest`
    /// harness proves zero-underrun at (clamped to [64, 1<<22] and
    /// power-of-two-rounded by the engine).
    static let capacityFrames = 8192
}

// MARK: - Presentation-layer bridging (Engineer 2's rendering currency)

extension PresentationTrack {
    /// Lossy-by-design bridge onto the shared presentation model (title /
    /// artist / duration / artwork are the surfaces the player renders).
    init(_ track: EngineTrack) {
        self.init(
            id: track.id,
            title: track.title,
            artist: track.artist,
            durationMs: track.durationMs,
            artworkUrl: track.thumbnailUrl
        )
    }
}
