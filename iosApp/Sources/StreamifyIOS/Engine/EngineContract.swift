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

// MARK: - The playback data plane (WS-B, A4 — mirrors of the UDL v2 records)

/// One playable stream format resolved for a video id (mirror of the UDL
/// `ResolvedStreamFormat`, issue #151's additive v2 surface). Ordering
/// contract: audio-only rows precede muxed rows, m4a before webm/opus,
/// then bitrate descending — `isAudioOnly` is a list-prefix property, so
/// consumers take element 0 without a scan.
struct EngineStreamFormat: Equatable, Sendable {
    /// Direct CDN URL (deciphered, de-throttled; PO-token-backed when a
    /// botguard binary is configured).
    let url: String
    /// Full MIME type incl. codec parameters ("audio/mp4; codecs=mp4a.40.2").
    let mimeType: String
    /// Sustained bitrate, bits/second.
    let bitrate: Int
    /// Stream length in seconds.
    let durationSec: Int
    /// Audio-only row (the prioritized class; always a list prefix).
    let isAudioOnly: Bool
}

/// The playback data plane's progress read (mirror of the UDL
/// `BufferProgress`, issue #151): producer-side telemetry plus the
/// transport/producer quantities that only exist once a sink is attached.
struct EngineBufferProgress: Equatable, Sendable {
    /// Ring occupancy in milliseconds of canonical audio.
    var bufferedMs: Int
    /// Ring fill ratio [0.0, 1.0].
    var fillRatio: Double
    /// Consumer-side underrun events (cumulative, this sink).
    var underrunEvents: Int
    /// Producer-side overrun events (cumulative, this sink).
    var overrunEvents: Int
    /// The transport's position target (A2: the last accepted Seek within
    /// the current row).
    var targetPositionMs: Int
    /// The rendered position, derived strictly from the DAC presentation
    /// clock since the session's anchor (never wall-clock interpolated);
    /// 0 until a clock sample exists.
    var renderedPositionMs: Int
    /// The producer session's track id; nil while no session ran.
    var trackId: String?
    /// True when a sink is attached and the producer thread is live.
    var producerLive: Bool
    /// True when the current session decoded and committed its final frame
    /// (the ring still drains afterwards).
    var eos: Bool

    init(
        bufferedMs: Int = 0,
        fillRatio: Double = 0,
        underrunEvents: Int = 0,
        overrunEvents: Int = 0,
        targetPositionMs: Int = 0,
        renderedPositionMs: Int = 0,
        trackId: String? = nil,
        producerLive: Bool = false,
        eos: Bool = false
    ) {
        self.bufferedMs = bufferedMs
        self.fillRatio = fillRatio
        self.underrunEvents = underrunEvents
        self.overrunEvents = overrunEvents
        self.targetPositionMs = targetPositionMs
        self.renderedPositionMs = renderedPositionMs
        self.trackId = trackId
        self.producerLive = producerLive
        self.eos = eos
    }
}
