import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 preview currency — the catalog counterpart of PreviewEngineGateway.
//
// PreviewEngineGateway (Engine/) scripts PLAYBACK state; this catalog
// provides the deterministic CATALOG fixtures every screen/component
// #Preview renders with: fixed fixtures, seeded gradients, no randomness —
// the same input always produces the same pixel output (SW-ENG2-09:
// "every component has an isolated #Preview using mock data").
// ─────────────────────────────────────────────────────────────────────────────

public enum PreviewCatalog {

    // MARK: Tracks

    public static let tracks: [VirtualShelfTrack] = [
        .init(id: "t1", title: "Obsidian Sunrise", artist: "Sireen", durationSec: 251, isLiked: true, platformOrigin: "local"),
        .init(id: "t2", title: "Bit-Perfect Rain", artist: "Aurora Nine", durationSec: 198, platformOrigin: "ytm"),
        .init(id: "t3", title: "Vellum Static", artist: "Low Orbit Choir", durationSec: 312, isLiked: true, platformOrigin: "ytm"),
        .init(id: "t4", title: "48k Meridian", artist: "DAC Kingsley", durationSec: 224, platformOrigin: "local"),
        .init(id: "t5", title: "Neon Hallway", artist: "Vetra", durationSec: 186, platformOrigin: "spotify"),
        .init(id: "t6", title: "Sable Meadow", artist: "Ines Wilder", durationSec: 275, isLiked: true, platformOrigin: "local"),
        .init(id: "t7", title: "Quartz Bloom", artist: "Mirabel", durationSec: 243, platformOrigin: "ytm"),
        .init(id: "t8", title: "Delta Nights", artist: "Cormorant", durationSec: 331, platformOrigin: "spotify"),
        .init(id: "t9", title: "Silk Circuitry", artist: "Aurora Nine", durationSec: 209, isLiked: true, platformOrigin: "ytm"),
        .init(id: "t10", title: "Glasshouse Dubs", artist: "Komorebi Sound", durationSec: 288, platformOrigin: "local"),
        .init(id: "t11", title: "Aphelion Drift", artist: "Vetra", durationSec: 264, platformOrigin: "ytm"),
        .init(id: "t12", title: "Tape Saturation", artist: "DAC Kingsley", durationSec: 222, platformOrigin: "local"),
    ]

    public static let presentationTracks: [PresentationTrack] = tracks.map(\.presentationTrack)

    /// A deterministic 200-track library (the ProMotion scroll gate fixture).
    public static let performanceTracks: [VirtualShelfTrack] = (0..<200).map { i in
        VirtualShelfTrack(
            id: "perf-\(i)",
            title: "\(tracks[i % tracks.count].title) — Mix \(i / tracks.count + 1)",
            artist: tracks[i % tracks.count].artist,
            durationSec: 150 + (i * 7) % 200,
            isLiked: i % 5 == 0,
            platformOrigin: i % 3 == 0 ? "local" : "ytm"
        )
    }

    // MARK: Shelves

    public static let shelves: [VirtualShelf] = [
        .init(
            id: "quick",
            title: "Quick picks",
            subtitle: "Start the day with what you know",
            kind: .quickPicks,
            tracks: Array(tracks[0..<4])
        ),
        .init(
            id: "listen",
            title: "Listen again",
            subtitle: "Mixed for Sireen",
            kind: .listenAgain,
            tracks: Array(tracks[4..<10])
        ),
        .init(
            id: "trend",
            title: "Trending in your orbit",
            kind: .trending,
            tracks: Array(tracks[2..<8])
        ),
        .init(
            id: "discover",
            title: "Fresh from YouTube Music",
            subtitle: "Online discovery shelf",
            kind: .onlineDiscovery,
            tracks: Array(tracks[6..<12])
        ),
    ]

    // MARK: Daylist hero

    public static let daylistHero = DaylistHeroModel(
        title: "Tuesday morning",
        subtitle: "Slow starts, wide horizons",
        bucketLabel: "Morning drive",
        trackTitles: ["Obsidian Sunrise", "Bit-Perfect Rain", "Quartz Bloom"],
        trackCount: 25,
        gradientStartHex: 0xFF667EEA,
        gradientEndHex: 0xFFF6D365,
        isFromCache: false
    )

    // MARK: Moods

    public static let moods: [MoodGenre] = [
        .init(id: "pop", name: "Pop", gradient: StreamifyGradients.pop),
        .init(id: "hiphop", name: "Hip-Hop", gradient: StreamifyGradients.hipHop),
        .init(id: "rock", name: "Rock", gradient: StreamifyGradients.rock),
        .init(id: "indie", name: "Indie", gradient: StreamifyGradients.indie),
        .init(id: "workout", name: "Workout", gradient: StreamifyGradients.workout),
        .init(id: "focus", name: "Focus", gradient: StreamifyGradients.focus),
        .init(id: "chill", name: "Chill", gradient: StreamifyGradients.chill),
        .init(id: "sleep", name: "Sleep", gradient: StreamifyGradients.sleep),
    ]

    // MARK: Daily mixes

    public static let dailyMixes: [VirtualShelf] = (1...6).map { mix in
        VirtualShelf(
            id: "daily-\(mix)",
            title: "Daily Mix \(mix)",
            subtitle: "Sireen, Aurora Nine, Vetra and more",
            kind: .dailyMix,
            tracks: Array(tracks[(mix - 1)..<((mix - 1) + 4)])
        )
    }

    // MARK: Search

    public static let recentSearches: [RecentSearchEntry] = [
        .init(query: "aurora nine", timestampMs: 1_725_000_000_000),
        .init(query: "48k", timestampMs: 1_724_000_000_000),
        .init(query: "obsidian", timestampMs: 1_723_000_000_000),
        .init(query: "dac kingsley", timestampMs: 1_722_000_000_000),
    ]

    public static let onlineResults: [OnlineSearchResult] = [
        .init(
            title: "Aurora Nine",
            uploader: "Aurora Nine — Official Artist",
            url: "https://music.example/artist/aurora-nine",
            durationSec: 0,
            type: .artist,
            subtitle: "1.2M monthly listeners",
            isVerified: true
        ),
        .init(
            title: "Bit-Perfect Rain",
            uploader: "Aurora Nine",
            url: "https://music.example/watch/bpr",
            durationSec: 198,
            type: .song,
            year: "2025"
        ),
        .init(
            title: "Meridian",
            uploader: "Aurora Nine",
            url: "https://music.example/album/meridian",
            durationSec: 0,
            type: .album,
            subtitle: "2025 · 12 tracks",
            year: "2025"
        ),
        .init(
            title: "Aurora Nine — Live at the Obsidian",
            uploader: "Aurora Nine",
            url: "https://music.example/watch/live-obsidian",
            durationSec: 3_924,
            type: .video,
            subtitle: "Live performance"
        ),
    ]

    // MARK: Lyrics

    public static let lyrics: LyricsData = {
        let lines: [(Int, String)] = [
            (0, "Obsidian sunrise over the delta"),
            (4_200, "Static in the vellum light"),
            (9_100, "Forty-eight thousand reasons to stay"),
            (13_800, "Meridian holding the night"),
            (18_500, "Slow start, wide horizon"),
            (23_200, "Bit-perfect rain on the wire"),
        ]
        return LyricsData(
            lines: lines.map { LyricsLine(timeMs: $0.0, text: $0.1, durationMs: 4_500) },
            isSynced: true
        )
    }()

    public static let syllableLine: LyricsLine = {
        let text = "Bit-perfect rain"
        let syllables = ["Bit-", "per-", "fect", " rain"]
        var cursor = 12_000
        return LyricsLine(
            timeMs: 12_000,
            text: text,
            syllables: syllables.map { s in
                let start = cursor
                cursor += 380
                return Syllable(text: s, startMs: start, endMs: cursor)
            },
            durationMs: 1_700
        )
    }()

    // MARK: Friends / social

    public static let friends: [FriendActivityEntry] = [
        .init(
            userId: "f1", displayName: "Mira", trackTitle: "Glasshouse Dubs",
            trackArtist: "Komorebi Sound", lastActiveLabel: "2 min ago",
            sessionCode: "JAM-4471"
        ),
        .init(
            userId: "f2", displayName: "Theo", trackTitle: "Quartz Bloom",
            trackArtist: "Mirabel", lastActiveLabel: "18 min ago"
        ),
        .init(
            userId: "f3", displayName: "Juno", trackTitle: "Aphelion Drift",
            trackArtist: "Vetra", lastActiveLabel: "1 h ago",
            sessionCode: "JAM-8802"
        ),
    ]

    public static let communityPlaylists: [CommunityPlaylistModel] = [
        .init(
            id: "cp1", creatorName: "Mira", name: "Obsidian Night Drive",
            description: "Late-night bit-perfect cruise",
            isCollaborative: true, likesCount: 412, trackCount: 64
        ),
        .init(
            id: "cp2", creatorName: "Theo", name: "Focus Ladder",
            description: "Deep work in twelve rungs",
            isCollaborative: false, likesCount: 189, trackCount: 48
        ),
        .init(
            id: "cp3", creatorName: "Juno", name: "Sunday Hardware",
            description: "Tape saturation & hardware dub",
            isCollaborative: true, likesCount: 933, trackCount: 81
        ),
    ]

    public static let comments: [CommentModel] = [
        .init(id: "c1", author: "Mira", text: "The drop at 2:12 is unreal on cans.", timeMs: 132_000, likes: 42),
        .init(id: "c2", author: "Theo", text: "This one mastered so clean.", timeMs: 244_500, likes: 17),
        .init(id: "c3", author: "Juno", text: "Playing this at the jam room tonight.", timeMs: 261_800, likes: 9),
    ]

    // MARK: Profiles / blend

    public static let profiles: [ProfileAccount] = [
        .init(id: "p1", displayName: "Sireen", email: "sireen@streamify.dev", mode: .streamify, isAdmin: true),
        .init(id: "p2", displayName: "Sireen (YTM)", email: "sireen@yt.example", mode: .youtubeMusic),
        .init(id: "p3", displayName: "Sireen (Spotify)", email: "sireen@sp.example", mode: .spotify),
    ]

    public static let userProfile = UserProfileEntry(
        id: "u1",
        displayName: "Sireen",
        bio: "Developer by day, audiophile by night.",
        totalPlays: 18_432,
        listeningMinutes: 92_640,
        favoriteGenre: "Ambient Techno",
        topTrack: "Obsidian Sunrise",
        isAdmin: true
    )

    public static let blendProfiles = [
        BlendProfile(id: "b1", name: "Mira", accentHex: 0xFF3EA6FF),
        BlendProfile(id: "b2", name: "Theo", accentHex: 0xFFE13300),
    ]
    public static let blendMatch = BlendMatch(matchPercent: 72, sharedTrackCount: 31)

    // MARK: Jam

    public static let jamPeers: [JamPeer] = [
        .init(id: "j1", name: "Mira", isHost: true, linkType: .lan, rttMs: 6),
        .init(id: "j2", name: "Theo", isCoHost: true, linkType: .ble, rttMs: 24),
        .init(id: "j3", name: "Juno", linkType: .mesh, rttMs: 48),
        .init(id: "j4", name: "Wren", linkType: .lan, rttMs: 11),
    ]

    // MARK: Equalizer / DSP

    public static let eqPresets: [EqualizerPreset] = [
        .init(id: "flat", name: "Flat", gainsDb: [0, 0, 0, 0, 0]),
        .init(id: "bass", name: "Bass lift", gainsDb: [5, 3.5, 2, 0, 0]),
        .init(id: "vocal", name: "Vocal presence", gainsDb: [-1, 0, 2.5, 3.5, 2]),
        .init(id: "obsidian", name: "Obsidian", gainsDb: [4, 2, -1, 2, 4]),
        .init(id: "air", name: "Air", gainsDb: [0, 0, 0, 2, 4.5]),
    ]

    /// EQ band fixtures (frequency label + gain) — the slider takes
    /// primitives; the canonical EqualizerBand is Eng 4's
    /// (ViewModels/Support/PresentationModels.swift).
    public static let eqBandsDb: [(label: String, gainDb: Double)] = [
        ("60 Hz", 3), ("250 Hz", 1), ("1 kHz", -0.5), ("4 kHz", 2), ("16 kHz", 4),
    ]

    public static let dsp = DspPreferences(
        loudnessNormalization: true,
        targetLufs: -14.0,
        gaplessPlayback: true,
        crossfadeSeconds: 4,
        monoOutput: false,
        stereoBalance: 0,
        truePeakDb: -1.0
    )

    // MARK: Downloads

    public static let downloads: [DownloadTransfer] = [
        .init(id: "d1", title: "Obsidian Sunrise", artist: "Sireen", state: .downloading(progress: 0.62), quality: "FLAC 24/96", sizeMb: 48.2),
        .init(id: "d2", title: "Vellum Static", artist: "Low Orbit Choir", state: .completed, quality: "FLAC 24/48", sizeMb: 31.8),
        .init(id: "d3", title: "Neon Hallway", artist: "Vetra", state: .queued, quality: "AAC 256", sizeMb: 6.9),
        .init(id: "d4", title: "Delta Nights", artist: "Cormorant", state: .failed(message: "Source returned 409"), quality: "FLAC 16/44.1", sizeMb: 22.4),
        .init(id: "d5", title: "Silk Circuitry", artist: "Aurora Nine", state: .paused, quality: "FLAC 24/96", sizeMb: 40.1),
    ]

    // MARK: Updates hub

    public static let updates: [UpdateEntry] = [
        .init(
            id: "u1", artist: "Aurora Nine", title: "Meridian (Deluxe)",
            version: "2025 reissue",
            notes: ["3 new tracks", "Remastered at 24/96", "Liner notes by DAC Kingsley"],
            releaseDate: Calendar.current.date(byAdding: .day, value: 3, to: .now),
            isPreSaved: true
        ),
        .init(
            id: "u2", artist: "Vetra", title: "Aphelion Drift — Single",
            version: "Single",
            notes: ["First single from the next LP"],
            isPreSaved: false
        ),
    ]

    // MARK: Wrapped / stats / admin

    public static let wrappedStats: [WrappedStat] = [
        .init(id: "w1", label: "Minutes listened", value: "92,640", symbol: StreamifyIcon.clock),
        .init(id: "w2", label: "Top genre", value: "Ambient Techno", symbol: StreamifyIcon.eq),
        .init(id: "w3", label: "Top track", value: "Obsidian Sunrise", symbol: StreamifyIcon.wave, rank: 1),
        .init(id: "w4", label: "Top artist", value: "Aurora Nine", symbol: StreamifyIcon.personPlus, rank: 1),
        .init(id: "w5", label: "Jam sessions joined", value: "47", symbol: StreamifyIcon.jam),
    ]

    public static let genreStats: [GenreStat] = [
        .init(genre: "Ambient Techno", count: 412, gradient: StreamifyGradients.chill),
        .init(genre: "Indie Rock", count: 366, gradient: StreamifyGradients.rock),
        .init(genre: "Hip-Hop", count: 298, gradient: StreamifyGradients.hipHop),
        .init(genre: "Pop", count: 244, gradient: StreamifyGradients.pop),
        .init(genre: "Focus", count: 201, gradient: StreamifyGradients.focus),
    ]

    public static let adminMetrics: [MetricTile] = [
        .init(id: "m1", label: "Active listeners", value: "12,842", symbol: "person.2.fill", trendPercent: 4.2),
        .init(id: "m2", label: "Jam rooms live", value: "318", symbol: "person.3.fill", trendPercent: 11.4),
        .init(id: "m3", label: "Stream success", value: "99.96%", symbol: "checkmark.circle.fill", trendPercent: 0.1),
        .init(id: "m4", label: "DAC drift (median)", value: "3.1 ppm", symbol: "waveform", trendPercent: -0.4),
        .init(id: "m5", label: "Edge tasks queued", value: "57", symbol: "cpu", trendPercent: -2.2),
        .init(id: "m6", label: "Cache hit rate", value: "91.3%", symbol: "internaldrive.fill", trendPercent: 1.9),
    ]

    public static let slogTail: [LogLine] = [
        .init(id: 41, timestampMs: 1_725_000_000_000, level: .info, tag: "Engine", message: "snapshot rev=88 status=playing buffer=0.94"),
        .init(id: 42, timestampMs: 1_725_000_001_240, level: .debug, tag: "DacClock", message: "sample 48_000Hz frames=4_176_000 drift=2.4ppm"),
        .init(id: 43, timestampMs: 1_725_000_003_010, level: .warn, tag: "Merkle", message: "jam recon latency 214ms (window 500ms)"),
        .init(id: 44, timestampMs: 1_725_000_004_988, level: .info, tag: "RustyPipe", message: "resolver cache hit for RDAMVM_88"),
        .init(id: 45, timestampMs: 1_725_000_006_512, level: .error, tag: "Ingest", message: "transfer d4 stalled: source 409"),
    ]

    // MARK: Clips

    public static let artistClips: [ArtistClipModel] = [
        .init(id: "ac1", title: "Obsidian Sunrise (Live)", artistName: "Sireen", durationSec: 15),
        .init(id: "ac2", title: "Meridian teaser", artistName: "Aurora Nine", durationSec: 12),
        .init(id: "ac3", title: "In the studio", artistName: "DAC Kingsley", durationSec: 9),
        .init(id: "ac4", title: "Aphelion visual", artistName: "Vetra", durationSec: 14),
    ]

    // MARK: Palettes

    /// Deterministic ambient palette for a title (artwork stand-in).
    public static func palette(forTitle title: String) -> AmbientPalette {
        let dominant: UInt32 = 0x212121
        let accents: [UInt32] = [0xFF0033, 0x3EA6FF, 0x8D67AB, 0xE13300, 0x509BF5, 0x1E3264]
        var hash: UInt64 = 5381
        for scalar in title.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        let accent = accents[Int(hash % UInt64(accents.count))]
        return AmbientPalette(dominantHex: dominant, accentHex: accent, darkMutedHex: 0x0A0A0A)
    }

    // MARK: Playback (bridge to the engine-lane preview)

    /// A snapshot with a live track — for surfaces that render engine truth.
    public static var playingSnapshot: PlaybackSnapshot {
        PlaybackSnapshot(
            rev: 88,
            status: .playing,
            volume: 0.7,
            buffer: BufferHealth(bufferedDurationMs: 18_000, underrunCount: 0, fillRatio: 0.94),
            clock: DacClockSample(framePosition: 4_176_000, sampleRateHz: 48_000, monotonicNs: 87_000_000_000),
            currentTrack: PresentationTrack(
                id: "t1",
                title: "Obsidian Sunrise",
                artist: "Sireen",
                durationMs: 251_000,
                artworkUrl: nil
            )
        )
    }

    public static var queueState: QueueListState {
        QueueListState(
            tracks: presentationTracks,
            currentIndex: 2,
            isPlaying: true
        )
    }
}
