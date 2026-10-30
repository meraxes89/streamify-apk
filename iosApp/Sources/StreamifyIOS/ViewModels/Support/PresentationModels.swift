import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): the shared presentation-model vocabulary spoken by
// the 14 `@Observable` view models and their views.
//
// These are SWIFT MIRRORS of the Android presentation surface (the Kotlin
// `viewmodel` / `ui` models are the source of truth; see
// `app/src/main/java/com/streamify/app/viewmodel/*`). State LOGIC stays
// sovereign in the Rust core (SW-DEC-05): every model below is either a
// value-projection of an engine push or a pure client-side preference — never
// a re-implementation of core state machinery.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Search (mirror of SearchViewModel.kt / OnlineSearchResult)

/// Search result kind (mirror of the Kotlin `SearchResultType`).
enum SearchResultKind: String, Equatable, Sendable, CaseIterable, Identifiable {
    case song
    case video
    case artist
    case album
    case playlist

    var id: String { rawValue }

    var label: String {
        switch self {
        case .song: return "Songs"
        case .video: return "Videos"
        case .artist: return "Artists"
        case .album: return "Albums"
        case .playlist: return "Playlists"
        }
    }
}

/// One resolved search row (local library or online resolver hit).
struct SearchResult: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let uploader: String
    var durationMs: Int? = nil
    var artworkUrl: String? = nil
    var kind: SearchResultKind = .song
    var isVerified: Bool = false

    /// The playable engine-track projection of the row.
    var asPresentationTrack: PresentationTrack {
        PresentationTrack(
            id: id,
            title: title,
            artist: uploader,
            durationMs: durationMs,
            artworkUrl: artworkUrl
        )
    }
}

// MARK: - Home feed (mirror of HomeViewModel.kt / HomeUiState)

/// Circadian recommendation slot (mirror of `DaylistTimeBuckets`).
enum CircadianSlot: String, Equatable, Sendable, CaseIterable {
    case morning    // 6–10
    case afternoon  // 11–16
    case evening    // 17–21
    case lateNight  // else

    /// Slot titles mirror `HomeViewModel`'s slot-title map verbatim.
    var title: String {
        switch self {
        case .morning: return "Morning Energy • Wake & Move"
        case .afternoon: return "Afternoon Flow • Focus & Lo-Fi"
        case .evening: return "Evening Horizon • Golden Hour Unwind"
        case .lateNight: return "Late Night Drift • Deep Chill"
        }
    }

    static func slot(forHour hour: Int) -> CircadianSlot {
        switch hour {
        case 6...10: return .morning
        case 11...16: return .afternoon
        case 17...21: return .evening
        default: return .lateNight
        }
    }
}

/// One "Made for you"-style daily mix (mirror of `DailyMixModel`).
struct DailyMix: Equatable, Sendable, Identifiable {
    let number: Int
    let seedArtist: String
    let tracks: [PresentationTrack]

    var id: Int { number }
}

/// The assembled home feed (mirror of `HomeUiState.Success`, trimmed to the
/// sections the Apple shell renders).
struct HomeFeed: Equatable, Sendable {
    var sessionRecommendations: [PresentationTrack] = []
    var circadianRecommendations: [PresentationTrack] = []
    var madeForYou: [PresentationTrack] = []
    var recent: [PresentationTrack] = []
    var topPlayed: [PresentationTrack] = []
    var trending: [PresentationTrack] = []
    var dailyMixes: [DailyMix] = []

    var isEmpty: Bool {
        sessionRecommendations.isEmpty && circadianRecommendations.isEmpty
            && madeForYou.isEmpty && recent.isEmpty && topPlayed.isEmpty
            && trending.isEmpty && dailyMixes.isEmpty
    }
}

// MARK: - Blend (mirror of BlendEngine.kt)

/// One blended track with provenance (mirror of `BlendTrack`).
struct BlendTrack: Equatable, Sendable, Identifiable {
    let videoId: String
    let title: String
    let artist: String
    var durationMs: Int? = nil
    var thumbnailUrl: String? = nil
    var mutual: Bool = false
    var score: Int = 60
    var memberIndex: Int = 0

    var id: String { videoId }

    var asPresentationTrack: PresentationTrack {
        PresentationTrack(
            id: videoId,
            title: title,
            artist: artist,
            durationMs: durationMs,
            artworkUrl: thumbnailUrl
        )
    }
}

/// A 2-user taste overlap result (mirror of `BlendResult`).
struct BlendResult: Equatable, Sendable {
    var tracks: [BlendTrack] = []
    /// Taste-match percentage. The engine computes it exactly like the Kotlin
    /// `BlendEngine.tasteMatchPercent` — Jaccard over distinct artist sets:
    /// 35 + jaccard × 64, clamped to 35...99 (never reads as 0%).
    var matchPercent: Int = 0
    var mutualArtists: [String] = []
    var memberNames: [String] = []

    /// Shared-playlist bootstrap: the playable queue behind "Play Blend".
    var playableQueue: [PresentationTrack] {
        tracks.map(\.asPresentationTrack)
    }
}

// MARK: - Community (mirror of CommunityViewModel.kt / SupabaseModels.kt)

struct CommunityPlaylist: Equatable, Sendable, Identifiable {
    let id: String
    let creatorName: String
    let name: String
    var description: String = ""
    var coverUrl: String? = nil
    var likesCount: Int = 0
    var trackCount: Int = 0
}

struct FriendActivity: Equatable, Sendable, Identifiable {
    let userId: String
    let displayName: String
    var avatarUrl: String? = nil
    let trackTitle: String
    let trackArtist: String
    var coverUrl: String? = nil
    var lastActiveAt: String = ""
    /// Non-nil → the friend is hosting a Jam room right now.
    var sessionCode: String? = nil
}

struct CommunityFeed: Equatable, Sendable {
    var playlists: [CommunityPlaylist] = []
    var friendsActivity: [FriendActivity] = []
    var activeBroadcasts: [String] = []
}

// MARK: - Wrapped stats (mirror of WrappedStats / YtStatsTelemetryEngine)

struct StatsGenre: Equatable, Sendable, Identifiable {
    let name: String
    /// Share of total genre matches in [0, 1].
    let fraction: Double
    var id: String { name }
}

struct MonthlyListening: Equatable, Sendable, Identifiable {
    /// Short month label, e.g. "Jan".
    let monthLabel: String
    let minutes: Int
    var id: String { monthLabel }
}

struct StatsMilestone: Equatable, Sendable, Identifiable {
    let title: String
    let detail: String
    let emoji: String
    var id: String { title }
}

struct WrappedStats: Equatable, Sendable {
    var totalMinutes: Int = 0
    var totalTracks: Int = 0
    var likedSongs: Int = 0
    var topPlayedCount: Int = 0
    var averageBpm: Int = 124
    var personaName: String = "Midnight Lofi Dreamer"
    var personaEmoji: String = "🌙"
    var personaDescription: String = "Slow tempos, warm textures, late-night calm."
    var topGenres: [StatsGenre] = []
    var topTracks: [PresentationTrack] = []
    var topArtists: [(name: String, plays: Int)] = []
    /// Monthly listening minutes feeding the Wrapped bar chart.
    var monthlyMinutes: [MonthlyListening] = []
    var milestones: [StatsMilestone] = []

    static let empty = WrappedStats()

    /// BPM persona bands mirror the Kotlin engine verbatim.
    static func persona(forAverageBpm bpm: Int) -> (name: String, emoji: String, description: String) {
        if bpm >= 130 {
            return ("Kinetic Pulse Runner", "⚡", "High-tempo sets that never sit still.")
        }
        if bpm >= 110 {
            return ("Harmonic Groove Weaver", "🌌", "Groove-locked rhythms with wide-open spaces.")
        }
        return ("Midnight Lofi Dreamer", "🌙", "Slow tempos, warm textures, late-night calm.")
    }
}

// MARK: - Release radar (mirror of ReleaseWatcherApi.kt)

struct ReleaseCandidate: Equatable, Sendable, Identifiable {
    let releaseId: String
    let artistName: String
    let title: String
    var releaseType: String = "Single"
    var expectedAt: Date? = nil
    var coverUrl: String? = nil
    var trackCount: Int = 0

    var id: String { releaseId }
}

struct ReleaseWatch: Equatable, Sendable, Identifiable {
    let artistName: String
    let releases: [ReleaseCandidate]
    var id: String { artistName }
}

// MARK: - User profile (mirror of UserProfile / UserProfileScreen)

struct UserProfileSummary: Equatable, Sendable {
    var userId: String = ""
    var displayName: String = "Listener"
    var avatarUrl: String? = nil
    var bio: String = "Music lover on Streamify 🎧"
    var followerCount: Int = 0
    var followingCount: Int = 0
    var favoriteGenre: String = "All"
    var listeningMinutes: Int = 0
    var topGenre: String = "—"
    var isPrivate: Bool = false

    static let `default` = UserProfileSummary()

    /// Follower-count compaction (mirror of `formatFollowerCount`).
    static func formatFollowerCount(_ count: Int) -> String {
        switch count {
        case 1_000_000...:
            return String(format: "%.1fM", Double(count) / 1_000_000)
        case 10_000...:
            return String(format: "%.0fK", Double(count) / 1_000)
        case 1_000...:
            return String(format: "%.1fK", Double(count) / 1_000)
        default:
            return "\(count)"
        }
    }
}

/// Listening chronotype (mirror of the UserProfileScreen chronotype card).
struct Chronotype: Equatable, Sendable {
    let name: String
    let emoji: String
    let detail: String

    static func chronotype(forHour hour: Int) -> Chronotype {
        switch hour {
        case 21...23, 0...4:
            return Chronotype(name: "The Night Explorer", emoji: "🦉", detail: "Peak nocturnal listening")
        case 5...11:
            return Chronotype(name: "The Morning Motor", emoji: "🌅", detail: "High-energy daybreak kickstart")
        case 12...16:
            return Chronotype(name: "The Afternoon Groover", emoji: "☀️", detail: "Steady focus & rhythm cadence")
        default:
            return Chronotype(name: "The Evening Unwinder", emoji: "🌆", detail: "Mellow dusk transitions")
        }
    }
}

// MARK: - Ingestion (mirror of IngestionViewModel.kt DownloadTask)

enum IngestionState: Equatable, Sendable {
    case queued
    case running
    case completed
    case failed(String)
}

struct IngestionTask: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    var progressFraction: Double = 0
    var state: IngestionState = .queued

    var label: String {
        switch state {
        case .queued: return "Queued"
        case .running: return "\(Int((progressFraction * 100).rounded()))%"
        case .completed: return "Done"
        case .failed: return "Failed"
        }
    }
}

// MARK: - Equalizer (10-band, mirror of the Rust streamify-dsp EQ plane)

struct EqualizerBand: Equatable, Sendable, Identifiable {
    let index: Int
    let centerHz: Double
    var gainDb: Double
    /// Filter Q factor in [0.4, 4.0].
    var q: Double

    var id: Int { index }

    var frequencyLabel: String {
        centerHz >= 1000 ? "\(Int(centerHz / 1000))k" : "\(Int(centerHz))"
    }
}

/// The full 10-band parametric profile the DSP plane receives.
struct EqualizerProfile: Equatable, Sendable {
    var isEnabled: Bool
    var preampDb: Double
    var bands: [EqualizerBand]
    var limiterEnabled: Bool
    /// True-peak limiter ceiling (dBFS); mirrors TRUE_PEAK_CEILING_DBFS.
    var limiterCeilingDb: Double

    static let bandFrequencies: [Double] = [
        31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
    ]

    static let gainRange: ClosedRange<Double> = -12...12
    static let qRange: ClosedRange<Double> = 0.4...4.0
    static let truePeakCeilingDb = -1.0

    static func flatBands(q: Double = 1.0) -> [EqualizerBand] {
        bandFrequencies.enumerated().map {
            EqualizerBand(index: $0.offset, centerHz: $0.element, gainDb: 0, q: q)
        }
    }

    static let flat = EqualizerProfile(
        isEnabled: true,
        preampDb: 0,
        bands: flatBands(),
        limiterEnabled: true,
        limiterCeilingDb: truePeakCeilingDb
    )
}

// MARK: - Jam rooms (push snapshots; the CRDT/PTP machinery stays in Rust)

enum JamRole: Equatable, Sendable {
    case host, coHost, member
}

enum JamTopology: Equatable, Sendable {
    case multiRender
    case singleRender

    var isPartyMode: Bool { self == .singleRender }
}

enum JamControlPolicy: Equatable, Sendable {
    case everyone
    case hostOnly
}

enum JamConnStatus: Equatable, Sendable {
    case live, degraded, offline
}

/// One room member (mirror of `JamEngine.Member`, flattened for rendering).
struct JamParticipant: Equatable, Sendable, Identifiable {
    let nonce: String
    var name: String
    var avatarUrl: String? = nil
    var isHost: Bool = false
    var role: JamRole = .member
    var allowControl: Bool = true
    var allowVolume: Bool = true
    /// Last measured round-trip in ms; negative = unknown.
    var rttMs: Double = -1
    /// Live output level in [0, 1] for the volume-meter pill.
    var level: Double = 0

    var id: String { nonce }

    var isCoHost: Bool { role == .coHost }
}

/// One shared-queue row (mirror of the CRDT queue projection).
struct JamQueueEntry: Equatable, Sendable, Identifiable {
    let track: PresentationTrack
    var addedBy: String = ""
    var votes: Int = 0
    var hasMyVote: Bool = false

    var id: String { track.id }
}

/// One coalesced room-state push. Full-state snapshots keep the view model a
/// dumb projector of the sovereign Rust jam engine — exactly the MP-DEC-04
/// discipline applied to rooms.
struct JamRoomSnapshot: Equatable, Sendable {
    var rev: Int = 0
    var sessionCode: String = ""
    var sessionId: String = ""
    var isHost: Bool = false
    var topology: JamTopology = .multiRender
    var controlPolicy: JamControlPolicy = .everyone
    var connStatus: JamConnStatus = .offline
    var participants: [JamParticipant] = []
    var queue: [JamQueueEntry] = []
}

/// Room events crossing the jam transport seam (push-only).
enum JamRoomEvent: Sendable {
    case snapshot(JamRoomSnapshot)
    case closed(reason: String)
}

/// PTP sync badge grade (SW-ENG4-06): green < 5 ms, yellow < 20 ms,
/// orange < 50 ms, red at or beyond 50 ms.
enum SyncGrade: Equatable, Sendable {
    case locked      // < 5 ms
    case tight       // < 20 ms
    case drifting    // < 50 ms
    case outOfSync   // >= 50 ms

    static func grade(forLatencyMs latencyMs: Double) -> SyncGrade {
        switch latencyMs {
        case ..<5: return .locked
        case ..<20: return .tight
        case ..<50: return .drifting
        default: return .outOfSync
        }
    }

    var label: String {
        switch self {
        case .locked: return "LOCKSTEP"
        case .tight: return "IN SYNC"
        case .drifting: return "SLEWING"
        case .outOfSync: return "RECOVERING"
        }
    }
}

// MARK: - Queue plane (engine queue projection, push-only)

struct QueueSnapshot: Equatable, Sendable {
    var rev: Int = 0
    var tracks: [PresentationTrack] = []
    var currentIndex: Int = -1
    var isPlaying: Bool = false
    /// Infinite auto-mix (radio continuation) toggle state, engine-owned.
    var autoMixEnabled: Bool = true
}

enum QueueEvent: Sendable {
    case snapshot(QueueSnapshot)
}

// MARK: - Small shared formatters

enum PresentationFormatting {

    /// Countdown chip text for a release ETA (mirror of `countdownLabel`).
    static func countdownLabel(to expectedAt: Date, from now: Date) -> String {
        let remaining = expectedAt.timeIntervalSince(now)
        guard remaining > 0 else { return "Out now" }
        let totalSeconds = Int(remaining.rounded())
        let days = totalSeconds / 86_400
        let hours = (totalSeconds % 86_400) / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        if days > 0 { return "\(days)d \(hours)h" }
        if hours > 0 { return "\(hours)h \(minutes)m" }
        return "\(minutes)m"
    }
}
