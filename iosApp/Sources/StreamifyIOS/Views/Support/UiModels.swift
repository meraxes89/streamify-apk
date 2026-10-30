import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 UI currency — the value types every screen and component renders.
//
// The Swift mirrors of the Android UI models (ui/models/UiModels.kt +
// the screen-level data classes: VirtualShelf, DaylistHeroModel,
// OnlineSearchResult, LyricsData, FriendActivity, JamPeer, DspPreferences,
// AppMode, WrappedStats…). Playback-currency types (PresentationTrack,
// PlaybackSnapshot…) live in Playback/PlaybackModels.swift and are NOT
// duplicated here.
//
// Views receive these as plain values — Engineer 4's ViewModels lower the
// engine/catalog truth onto them; previews use PreviewCatalog fixtures.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Home shelves

public enum ShelfKind: Hashable, Sendable {
    case hero
    case dailyMix
    case quickPicks
    case listenAgain
    case supermix
    case moods
    case friendActivity
    case trending
    case onlineDiscovery
    case recentSearches
}

public struct VirtualShelfTrack: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artist: String
    public let artworkUrl: String?
    public let durationSec: Int
    public let isLiked: Bool
    /// "local" | "ytm" | "spotify" — the multi-catalog origin badge.
    public let platformOrigin: String

    public init(
        id: String,
        title: String,
        artist: String,
        artworkUrl: String? = nil,
        durationSec: Int,
        isLiked: Bool = false,
        platformOrigin: String = "local"
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.artworkUrl = artworkUrl
        self.durationSec = durationSec
        self.isLiked = isLiked
        self.platformOrigin = platformOrigin
    }

    public var presentationTrack: PresentationTrack {
        PresentationTrack(
            id: id,
            title: title,
            artist: artist,
            durationMs: durationSec * 1_000,
            artworkUrl: artworkUrl
        )
    }
}

public struct VirtualShelf: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let subtitle: String?
    public let kind: ShelfKind
    public let tracks: [VirtualShelfTrack]

    public init(
        id: String,
        title: String,
        subtitle: String? = nil,
        kind: ShelfKind,
        tracks: [VirtualShelfTrack]
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.kind = kind
        self.tracks = tracks
    }
}

// MARK: - Ambient palette (artwork-derived mesh colors)

public struct AmbientPalette: Hashable, Sendable {
    public let dominantHex: UInt32
    public let accentHex: UInt32
    public let darkMutedHex: UInt32

    public init(dominantHex: UInt32, accentHex: UInt32, darkMutedHex: UInt32) {
        self.dominantHex = dominantHex
        self.accentHex = accentHex
        self.darkMutedHex = darkMutedHex
    }

    public var dominant: Color { Color(streamifyHex: dominantHex) }
    public var accent: Color { Color(streamifyHex: accentHex) }
    public var darkMuted: Color { Color(streamifyHex: darkMutedHex) }

    public static let obsidian = AmbientPalette(
        dominantHex: 0x212121, accentHex: 0xFF0033, darkMutedHex: 0x0A0A0A
    )
}

// MARK: - Daylist hero (YtDaylistHeroBanner)

public struct DaylistHeroModel: Hashable, Sendable {
    public let title: String
    public let subtitle: String
    public let bucketLabel: String
    public let trackTitles: [String]
    public let trackCount: Int
    public let gradientStartHex: UInt32
    public let gradientEndHex: UInt32
    public let isFromCache: Bool

    public init(
        title: String,
        subtitle: String,
        bucketLabel: String,
        trackTitles: [String],
        trackCount: Int,
        gradientStartHex: UInt32 = 0xFF667EEA,
        gradientEndHex: UInt32 = 0xFFF6D365,
        isFromCache: Bool = false
    ) {
        self.title = title
        self.subtitle = subtitle
        self.bucketLabel = bucketLabel
        self.trackTitles = trackTitles
        self.trackCount = trackCount
        self.gradientStartHex = gradientStartHex
        self.gradientEndHex = gradientEndHex
        self.isFromCache = isFromCache
    }

    public var gradient: [Color] {
        [Color(streamifyHex: gradientStartHex), Color(streamifyHex: gradientEndHex)]
    }
}

// MARK: - Moods / genres

public struct MoodGenre: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let gradient: [Color]

    public init(id: String, name: String, gradient: [Color]) {
        self.id = id
        self.name = name
        self.gradient = gradient
    }
}

// MARK: - Search

public enum SearchResultType: String, Hashable, CaseIterable, Sendable {
    case song
    case video
    case artist
    case album
    case playlist

    public var label: String {
        switch self {
        case .song: return "Songs"
        case .video: return "Videos"
        case .artist: return "Artists"
        case .album: return "Albums"
        case .playlist: return "Playlists"
        }
    }
}

public struct OnlineSearchResult: Identifiable, Hashable, Sendable {
    public let title: String
    public let uploader: String
    public let url: String
    public let durationSec: Int
    public let thumbnailUrl: String?
    public let type: SearchResultType
    public let subtitle: String?
    public let year: String?
    public let isExplicit: Bool
    public let isVerified: Bool

    public var id: String { url }

    public init(
        title: String,
        uploader: String,
        url: String,
        durationSec: Int,
        thumbnailUrl: String? = nil,
        type: SearchResultType,
        subtitle: String? = nil,
        year: String? = nil,
        isExplicit: Bool = false,
        isVerified: Bool = false
    ) {
        self.title = title
        self.uploader = uploader
        self.url = url
        self.durationSec = durationSec
        self.thumbnailUrl = thumbnailUrl
        self.type = type
        self.subtitle = subtitle
        self.year = year
        self.isExplicit = isExplicit
        self.isVerified = isVerified
    }
}

public struct RecentSearchEntry: Identifiable, Hashable, Sendable {
    public let query: String
    public let timestampMs: Int

    public var id: String { query }
}

// MARK: - Lyrics (karaoke engine)

public struct Syllable: Hashable, Sendable {
    public let text: String
    public let startMs: Int
    public let endMs: Int

    public init(text: String, startMs: Int, endMs: Int) {
        self.text = text
        self.startMs = startMs
        self.endMs = endMs
    }
}

public struct LyricsLine: Identifiable, Hashable, Sendable {
    public let timeMs: Int
    public let text: String
    public let syllables: [Syllable]
    public let durationMs: Int

    public var id: Int { timeMs }

    public init(timeMs: Int, text: String, syllables: [Syllable] = [], durationMs: Int) {
        self.timeMs = timeMs
        self.text = text
        self.syllables = syllables
        self.durationMs = durationMs
    }

    public var isSyllableTimed: Bool { !syllables.isEmpty }
}

public struct LyricsData: Hashable, Sendable {
    public let lines: [LyricsLine]
    public let isSynced: Bool
    public let globalOffsetMs: Int

    public init(lines: [LyricsLine], isSynced: Bool, globalOffsetMs: Int = 0) {
        self.lines = lines
        self.isSynced = isSynced
        self.globalOffsetMs = globalOffsetMs
    }

    public static let empty = LyricsData(lines: [], isSynced: false)
}

// MARK: - Social (friends, community, blend, comments, profiles)

public struct FriendActivityEntry: Identifiable, Hashable, Sendable {
    public let userId: String
    public let displayName: String
    public let avatarUrl: String?
    public let trackTitle: String
    public let trackArtist: String
    public let coverUrl: String?
    public let lastActiveLabel: String
    /// Non-nil = this friend hosts a joinable Jam room.
    public let sessionCode: String?

    public var id: String { userId }

    public init(
        userId: String,
        displayName: String,
        avatarUrl: String? = nil,
        trackTitle: String,
        trackArtist: String,
        coverUrl: String? = nil,
        lastActiveLabel: String,
        sessionCode: String? = nil
    ) {
        self.userId = userId
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.trackTitle = trackTitle
        self.trackArtist = trackArtist
        self.coverUrl = coverUrl
        self.lastActiveLabel = lastActiveLabel
        self.sessionCode = sessionCode
    }
}

public struct CommunityPlaylistModel: Identifiable, Hashable, Sendable {
    public let id: String
    public let creatorName: String
    public let name: String
    public let description: String
    public let coverUrl: String?
    public let isCollaborative: Bool
    public let likesCount: Int
    public let trackCount: Int

    public init(
        id: String,
        creatorName: String,
        name: String,
        description: String,
        coverUrl: String? = nil,
        isCollaborative: Bool,
        likesCount: Int,
        trackCount: Int
    ) {
        self.id = id
        self.creatorName = creatorName
        self.name = name
        self.description = description
        self.coverUrl = coverUrl
        self.isCollaborative = isCollaborative
        self.likesCount = likesCount
        self.trackCount = trackCount
    }
}

public struct CommentModel: Identifiable, Hashable, Sendable {
    public let id: String
    public let author: String
    public let avatarUrl: String?
    public let text: String
    public let timeMs: Int
    public let likes: Int

    public init(
        id: String,
        author: String,
        avatarUrl: String? = nil,
        text: String,
        timeMs: Int,
        likes: Int
    ) {
        self.id = id
        self.author = author
        self.avatarUrl = avatarUrl
        self.text = text
        self.timeMs = timeMs
        self.likes = likes
    }
}

public enum AppMode: String, CaseIterable, Identifiable, Hashable, Sendable {
    case streamify
    case spotify
    case youtubeMusic

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .streamify: return "Streamify"
        case .spotify: return "Spotify"
        case .youtubeMusic: return "YouTube Music"
        }
    }
}

public struct ProfileAccount: Identifiable, Hashable, Sendable {
    public let id: String
    public let displayName: String
    public let email: String
    public let avatarUrl: String?
    public let mode: AppMode
    public let isAdmin: Bool

    public init(
        id: String,
        displayName: String,
        email: String,
        avatarUrl: String? = nil,
        mode: AppMode,
        isAdmin: Bool = false
    ) {
        self.id = id
        self.displayName = displayName
        self.email = email
        self.avatarUrl = avatarUrl
        self.mode = mode
        self.isAdmin = isAdmin
    }
}

public struct UserProfileEntry: Identifiable, Hashable, Sendable {
    public let id: String
    public let displayName: String
    public let bio: String
    public let totalPlays: Int
    public let listeningMinutes: Int
    public let favoriteGenre: String
    public let topTrack: String
    public let isPrivate: Bool
    public let isAdmin: Bool

    public init(
        id: String,
        displayName: String,
        bio: String,
        totalPlays: Int,
        listeningMinutes: Int,
        favoriteGenre: String,
        topTrack: String,
        isPrivate: Bool = false,
        isAdmin: Bool = false
    ) {
        self.id = id
        self.displayName = displayName
        self.bio = bio
        self.totalPlays = totalPlays
        self.listeningMinutes = listeningMinutes
        self.favoriteGenre = favoriteGenre
        self.topTrack = topTrack
        self.isPrivate = isPrivate
        self.isAdmin = isAdmin
    }
}

// MARK: - Blend (shared playlists)

public struct BlendProfile: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let accentHex: UInt32

    public init(id: String, name: String, accentHex: UInt32) {
        self.id = id
        self.name = name
        self.accentHex = accentHex
    }
}

public struct BlendMatch: Hashable, Sendable {
    public let matchPercent: Int
    public let sharedTrackCount: Int

    public init(matchPercent: Int, sharedTrackCount: Int) {
        self.matchPercent = matchPercent
        self.sharedTrackCount = sharedTrackCount
    }
}

// MARK: - Jam sessions

public enum JamLinkType: String, Hashable, Sendable, CaseIterable {
    case ble
    case lan
    case mesh
    case speaker

    public var label: String {
        switch self {
        case .ble: return "Bluetooth LE"
        case .lan: return "LAN room"
        case .mesh: return "Mesh beacon"
        case .speaker: return "Speaker"
        }
    }
}

public struct JamPeer: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let avatarUrl: String?
    public let isHost: Bool
    public let isCoHost: Bool
    public let linkType: JamLinkType
    public let rttMs: Int

    public init(
        id: String,
        name: String,
        avatarUrl: String? = nil,
        isHost: Bool = false,
        isCoHost: Bool = false,
        linkType: JamLinkType,
        rttMs: Int
    ) {
        self.id = id
        self.name = name
        self.avatarUrl = avatarUrl
        self.isHost = isHost
        self.isCoHost = isCoHost
        self.linkType = linkType
        self.rttMs = rttMs
    }
}

// MARK: - Equalizer / DSP (audiophile surfaces)
//
// NOTE: EqualizerBand lives in ViewModels/Support/PresentationModels.swift
// (Engineer 4's lane, merged from main) — the single canonical band type.

// MARK: - Equalizer / DSP (audiophile surfaces)

public struct EqualizerPreset: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let gainsDb: [Double]

    public init(id: String, name: String, gainsDb: [Double]) {
        self.id = id
        self.name = name
        self.gainsDb = gainsDb
    }
}

/// "Audio Quality & DSP" settings model (Android DspPreferences):
/// LUFS normalization, gapless, crossfade, mono/balance, true-peak limiter.
public struct DspPreferences: Hashable, Sendable {
    public var loudnessNormalization: Bool
    /// EBU R128 target: −23.0 … −11.0 LUFS (default −14.0).
    public var targetLufs: Double
    public var gaplessPlayback: Bool
    /// 0 … 12 s.
    public var crossfadeSeconds: Double
    public var monoOutput: Bool
    /// −100 … +100 %.
    public var stereoBalance: Double
    /// −1.0 … 0.0 dBFS ceiling.
    public var truePeakDb: Double

    public init(
        loudnessNormalization: Bool = true,
        targetLufs: Double = -14.0,
        gaplessPlayback: Bool = true,
        crossfadeSeconds: Double = 0,
        monoOutput: Bool = false,
        stereoBalance: Double = 0,
        truePeakDb: Double = -1.0
    ) {
        self.loudnessNormalization = loudnessNormalization
        self.targetLufs = targetLufs
        self.gaplessPlayback = gaplessPlayback
        self.crossfadeSeconds = crossfadeSeconds
        self.monoOutput = monoOutput
        self.stereoBalance = stereoBalance
        self.truePeakDb = truePeakDb
    }

    public static let `default` = DspPreferences()
}


// MARK: - Downloads

public enum DownloadState: Hashable, Sendable {
    case queued
    case downloading(progress: Double)
    case completed
    case paused
    case failed(message: String)
}

public struct DownloadTransfer: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artist: String
    public let state: DownloadState
    public let quality: String
    public let sizeMb: Double

    public init(
        id: String,
        title: String,
        artist: String,
        state: DownloadState,
        quality: String,
        sizeMb: Double
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.state = state
        self.quality = quality
        self.sizeMb = sizeMb
    }
}

// MARK: - Updates hub

public struct UpdateEntry: Identifiable, Hashable, Sendable {
    public let id: String
    public let artist: String
    public let title: String
    public let version: String
    public let notes: [String]
    /// Live countdown target for embargoed releases, if any.
    public let releaseDate: Date?
    public let isPreSaved: Bool

    public init(
        id: String,
        artist: String,
        title: String,
        version: String,
        notes: [String],
        releaseDate: Date? = nil,
        isPreSaved: Bool = false
    ) {
        self.id = id
        self.artist = artist
        self.title = title
        self.version = version
        self.notes = notes
        self.releaseDate = releaseDate
        self.isPreSaved = isPreSaved
    }
}

// MARK: - Wrapped stats

public struct WrappedStat: Identifiable, Hashable, Sendable {
    public let id: String
    public let label: String
    public let value: String
    public let symbol: String
    public let rank: Int?

    public init(id: String, label: String, value: String, symbol: String, rank: Int? = nil) {
        self.id = id
        self.label = label
        self.value = value
        self.symbol = symbol
        self.rank = rank
    }
}

public struct GenreStat: Identifiable, Hashable, Sendable {
    public let genre: String
    public let count: Int
    public let gradient: [Color]

    public var id: String { genre }
}

// MARK: - Admin / diagnostics

public struct MetricTile: Identifiable, Hashable, Sendable {
    public let id: String
    public let label: String
    public let value: String
    public let symbol: String
    public let trendPercent: Double?

    public init(
        id: String,
        label: String,
        value: String,
        symbol: String,
        trendPercent: Double? = nil
    ) {
        self.id = id
        self.label = label
        self.value = value
        self.symbol = symbol
        self.trendPercent = trendPercent
    }
}

public enum SlogLevel: String, Hashable, Sendable {
    case debug
    case info
    case warn
    case error
}

public struct LogLine: Identifiable, Hashable, Sendable {
    public let id: Int
    public let timestampMs: Int
    public let level: SlogLevel
    public let tag: String
    public let message: String

    public init(id: Int, timestampMs: Int, level: SlogLevel, tag: String, message: String) {
        self.id = id
        self.timestampMs = timestampMs
        self.level = level
        self.tag = tag
        self.message = message
    }
}

// MARK: - Clips (canvas surfaces)

public struct ArtistClipModel: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artistName: String
    public let thumbnailUrl: String?
    public let durationSec: Int

    public init(
        id: String,
        title: String,
        artistName: String,
        thumbnailUrl: String? = nil,
        durationSec: Int
    ) {
        self.id = id
        self.title = title
        self.artistName = artistName
        self.thumbnailUrl = thumbnailUrl
        self.durationSec = durationSec
    }
}
