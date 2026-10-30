import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-07 (Swift migration, Engineer 1): DaylistScheduleEngine — the Swift
// actor port of DaylistScheduler.kt (circadian mood time-bucket scheduling).
//
// The Kotlin original maps the local device clock into 4 dynamic time buckets
// and seeds YTM mood radios as [User Top Artist] x [Time Mood Keyword]:
//
//   Morning     (06:00 – 11:00)  Focus / Upbeat Acoustic / Morning Pop
//   Afternoon   (11:00 – 17:00)  Workout / Energy / High Tempo Hits
//   Evening     (17:00 – 22:00)  Chill R&B / Lo-Fi / Sunset Unwind
//   Late Night  (22:00 – 06:00)  Ambient / Deep Sleep / Dream Lo-Fi
//
// Every bucket edge, mood-query mapping, hero-title rotation and the
// round-robin interleave below is a faithful port of the pure functions in
// DaylistTimeBuckets (Kotlin) / DaylistScheduler.interleave — the full policy
// matrix is unit-testable without a network. The one deliberate structural
// change: track harvest is INJECTED (`trackSearcher`) instead of calling a
// network object directly — on Apple the mood radios resolve through the
// sovereign Rust resolver lane (EngineGateway.searchTracks), so the actor
// stays a pure orchestrator and tests stay hermetic.
//
// SWR CACHE: the same (bucket, slot) keying, 4-hour TTL, compact canonical
// JSON ({"tracks":[{v,t,a,d,th,q}],"at":<ms>}) and failure semantics as the
// Kotlin SWR cache — fresh cache serves instantly, a cold-cache + fetch
// failure degrades to the empty shelf (the circadian local lane takes over),
// and a stale cache still wins over a failed revalidation.
// ─────────────────────────────────────────────────────────────────────────────

/// The four circadian daypart buckets (port of the Kotlin `DaylistBucket`).
enum DaylistBucket: String, CaseIterable, Sendable {
    case morning
    case afternoon
    case evening
    case lateNight

    var label: String {
        switch self {
        case .morning: return "Morning"
        case .afternoon: return "Afternoon"
        case .evening: return "Evening"
        case .lateNight: return "Late Night"
        }
    }

    var titleCore: String { label }

    var moodKeywords: [String] {
        switch self {
        case .morning: return ["Focus", "Upbeat Acoustic", "Morning Pop"]
        case .afternoon: return ["Workout", "Energy", "High Tempo Hits"]
        case .evening: return ["Chill R&B", "Lo-Fi", "Sunset Unwind"]
        case .lateNight: return ["Ambient", "Deep Sleep", "Dream Lo-Fi"]
        }
    }

    /// The hero adjective rotates with the 4-hour refresh slot; index 1 is
    /// the bucket's first slot (06:00–07:59) → "Acoustic Morning Chill
    /// Tuesday" exactly (parity with the Kotlin fixture).
    var heroAdjectives: [String] {
        switch self {
        case .morning: return ["Golden", "Acoustic", "Fresh", "Sunlit"]
        case .afternoon: return ["Electric", "Kinetic", "Upbeat", "Turbo"]
        case .evening: return ["Sunset", "Velvet", "Amber", "Dusk"]
        case .lateNight: return ["Midnight", "Dreaming", "Nocturne", "Lunar"]
        }
    }

    var heroNoun: String {
        switch self {
        case .morning: return "Chill"
        case .afternoon: return "Drive"
        case .evening: return "Unwind"
        case .lateNight: return "Drift"
        }
    }

    /// Ambient gradient (ARGB hex pair) — carried as raw values so this
    /// service stays Foundation-only and multiplatform; the theme layer
    /// maps them to `Color`.
    var gradientColors: (start: UInt64, end: UInt64) {
        switch self {
        case .morning: return (0xFF667EEA, 0xFFF6D365)
        case .afternoon: return (0xFFF97316, 0xFFDC2626)
        case .evening: return (0xFF7C3AED, 0xFFEC4899)
        case .lateNight: return (0xFF0EA5E9, 0xFF1E1B4B)
        }
    }
}

/// ISO weekday with pinned English display names (parity with the Kotlin
/// `DayOfWeek.getDisplayName(FULL, Locale.ENGLISH)` — locale-INdependent so
/// hero titles render identically on every device language).
enum DaylistWeekday: Int, CaseIterable, Sendable {
    case monday = 1
    case tuesday
    case wednesday
    case thursday
    case friday
    case saturday
    case sunday

    var englishName: String {
        switch self {
        case .monday: return "Monday"
        case .tuesday: return "Tuesday"
        case .wednesday: return "Wednesday"
        case .thursday: return "Thursday"
        case .friday: return "Friday"
        case .saturday: return "Saturday"
        case .sunday: return "Sunday"
        }
    }

    /// Derives the weekday from a date through the ISO calendar.
    static func from(_ date: Date, calendar: Calendar = .current) -> DaylistWeekday? {
        let weekday = calendar.component(.weekday, from: date) // 1 = Sunday
        // ISO numbering: Monday = 1 … Sunday = 7.
        let iso = ((weekday + 5) % 7) + 1
        return DaylistWeekday(rawValue: iso)
    }
}

/// Pure circadian math (port of the Kotlin `DaylistTimeBuckets`).
enum DaylistTimeBuckets {

    /// Bucket containing `hour` (0…23). Late Night wraps midnight.
    static func bucket(forHour hour: Int) -> DaylistBucket {
        switch hour {
        case 6...10: return .morning
        case 11...16: return .afternoon
        case 17...21: return .evening
        default: return .lateNight
        }
    }

    /// The 4-hour refresh slot (0…5 per day): the Daylist hero and
    /// tracklist regenerate when the slot index advances.
    static func refreshSlot(forHour hour: Int) -> Int {
        min(max(hour, 0), 23) / 4
    }

    /// Mood-radio seed queries: [User Top Artist] + [Time Mood Keyword].
    /// Rotates keywords across artists so all three mood flavours of the
    /// bucket appear; falls back to pure mood keywords on a cold start
    /// (no top artists yet).
    static func moodQueries(for bucket: DaylistBucket, topArtists: [String]) -> [String] {
        let artists = topArtists
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .prefix(3)
        guard !artists.isEmpty else { return bucket.moodKeywords }
        return artists.enumerated().map { index, artist in
            "\(artist) \(bucket.moodKeywords[index % bucket.moodKeywords.count])"
        }
    }

    /// Hero banner title, e.g. "Acoustic Morning Chill Tuesday". The
    /// adjective rotates with the refresh slot so the title morphs every
    /// 4 hours within the same bucket.
    static func heroTitle(
        for bucket: DaylistBucket,
        slot: Int,
        weekday: DaylistWeekday
    ) -> String {
        let adjective = bucket.heroAdjectives[slot % bucket.heroAdjectives.count]
        return "\(adjective) \(bucket.titleCore) \(bucket.heroNoun) \(weekday.englishName)"
    }

    /// Descriptive sub-line under the hero title.
    static func heroSubtitle(for bucket: DaylistBucket, slot: Int) -> String {
        "\(bucket.moodKeywords[slot % bucket.moodKeywords.count]) • Refreshes every 4 hours"
    }
}

/// One Daylist track row (radio-scraped, playable).
struct DaylistTrack: Equatable, Sendable, Identifiable {
    let videoId: String
    let title: String
    let artist: String
    var durationSec: Int = 0
    var thumbnailUrl: String = ""
    var seedQuery: String = ""

    var id: String { videoId }
}

/// A generated Daylist shelf.
struct Daylist: Equatable, Sendable {
    let bucket: DaylistBucket
    let title: String
    let subtitle: String
    var tracks: [DaylistTrack] = []
    var generatedAtMs: Int64 = 0
    var fromCache: Bool = false
}

/// The circadian daylist orchestrator + SWR cache — SW-ENG1-07.
actor DaylistScheduleEngine {

    /// The 4-hour morph cadence (cache TTL, parity with the Kotlin lane).
    static let cacheTtlMs: Int64 = 4 * 60 * 60 * 1000
    /// Per mood-query harvest cap.
    static let maxPerQuery = 10
    /// Shelf size cap.
    static let maxTracks = 30

    /// Harvests the tracks for one mood-radio seed query (max rows). The
    /// production wiring resolves these through the engine's typed search
    /// lane; tests inject deterministic doubles.
    typealias TrackSearcher = @Sendable (String, Int) async -> [DaylistTrack]

    private let trackSearcher: TrackSearcher
    private let cacheDirectory: URL?
    private let clock: () -> Int64

    init(
        trackSearcher: @escaping TrackSearcher,
        cacheDirectory: URL? = DaylistScheduleEngine.defaultCacheDirectory(),
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.trackSearcher = trackSearcher
        self.cacheDirectory = cacheDirectory
        self.clock = clock
    }

    /// Default SWR cache home: Application Support/StreamifyDaylistCache.
    static func defaultCacheDirectory() -> URL? {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first
        return base?.appendingPathComponent("StreamifyDaylistCache", isDirectory: true)
    }

    /// Builds the Daylist for the current clock (SWR-cached per
    /// (bucket, slot)): a fresh cache serves instantly; a cold cache
    /// awaits the live harvest; a failed harvest with any cache on disk
    /// serves the stale shelf; total failure degrades to the empty shelf
    /// (the circadian local lane takes over) — never a throw.
    func currentDaylist(
        hour: Int,
        weekday: DaylistWeekday,
        topArtists: [String],
        maxTracks: Int = DaylistScheduleEngine.maxTracks
    ) async -> Daylist {
        let bucket = DaylistTimeBuckets.bucket(forHour: hour)
        let slot = DaylistTimeBuckets.refreshSlot(forHour: hour)
        let title = DaylistTimeBuckets.heroTitle(for: bucket, slot: slot, weekday: weekday)
        let subtitle = DaylistTimeBuckets.heroSubtitle(for: bucket, slot: slot)

        if let cached = readCache(bucket: bucket, slot: slot),
           clock() - cached.generatedAtMs < Self.cacheTtlMs,
           !cached.tracks.isEmpty {
            return Daylist(
                bucket: bucket,
                title: title,
                subtitle: subtitle,
                tracks: cached.tracks,
                generatedAtMs: cached.generatedAtMs,
                fromCache: true
            )
        }

        let tracks = await buildDaylistTracks(
            bucket: bucket,
            topArtists: topArtists,
            maxTracks: maxTracks
        )

        if !tracks.isEmpty {
            let fresh = Daylist(
                bucket: bucket,
                title: title,
                subtitle: subtitle,
                tracks: tracks,
                generatedAtMs: clock()
            )
            writeCache(fresh, bucket: bucket, slot: slot)
            return fresh
        }

        // Harvest failed: a stale shelf still beats the empty one.
        if let stale = readCache(bucket: bucket, slot: slot), !stale.tracks.isEmpty {
            return Daylist(
                bucket: bucket,
                title: title,
                subtitle: subtitle,
                tracks: stale.tracks,
                generatedAtMs: stale.generatedAtMs,
                fromCache: true
            )
        }

        // Cold cache + harvest failure: empty shelf, UI falls back to the
        // circadian local recommendations lane (Kotlin parity).
        return Daylist(
            bucket: bucket,
            title: title,
            subtitle: subtitle,
            generatedAtMs: clock()
        )
    }

    /// Live track harvest: concurrent mood-query radios, interleaved.
    func buildDaylistTracks(
        bucket: DaylistBucket,
        topArtists: [String],
        maxTracks: Int
    ) async -> [DaylistTrack] {
        let queries = DaylistTimeBuckets.moodQueries(for: bucket, topArtists: topArtists)
        // Concurrent harvest across the mood pools (structured fan-out).
        return await withTaskGroup(of: [DaylistTrack].self) { group in
            for query in queries {
                group.addTask { [trackSearcher] in
                    let rows = await trackSearcher(query, Self.maxPerQuery)
                    return rows.filter { !$0.title.isEmpty && !$0.artist.isEmpty }
                }
            }
            var pools: [[DaylistTrack]] = []
            for await pool in group {
                pools.append(pool)
            }
            return Self.interleave(pools, limit: maxTracks)
        }
    }

    /// Round-robin interleave across the mood-query pools with videoId +
    /// fuzzy title/artist dedup — pure and directly unit-testable (the
    /// faithful port of DaylistScheduler.interleave).
    static func interleave(_ pools: [[DaylistTrack]], limit: Int) -> [DaylistTrack] {
        guard !pools.isEmpty else { return [] }
        var seenVideo = Set<String>()
        var seenSong = Set<String>()
        var out: [DaylistTrack] = []
        out.reserveCapacity(min(limit, 64))
        let maxLength = pools.map(\.count).max() ?? 0
        for depth in 0..<maxLength {
            for pool in pools {
                guard depth < pool.count else { continue }
                let track = pool[depth]
                if !track.videoId.isEmpty {
                    guard seenVideo.insert(track.videoId).inserted else { continue }
                }
                let songKey = "\(track.title.trimmingCharacters(in: .whitespaces).lowercased())::\(track.artist.trimmingCharacters(in: .whitespaces).lowercased())"
                guard seenSong.insert(songKey).inserted else { continue }
                out.append(track)
                if out.count >= limit { return out }
            }
        }
        return out
    }

    // MARK: - Canonical cache serialization (Kotlin-parity JSON shape)

    private func cacheFile(bucket: DaylistBucket, slot: Int) -> URL? {
        guard let cacheDirectory else { return nil }
        try? FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
        return cacheDirectory.appendingPathComponent("daylist_\(bucket.rawValue)_\(slot).json")
    }

    /// Reads the cached shelf (tracks + stamp). The hero title/subtitle
    /// are rebuilt by the caller from the live clock (they are pure
    /// functions of bucket/slot/weekday, never cached text).
    private func readCache(bucket: DaylistBucket, slot: Int) -> Daylist? {
        guard let file = cacheFile(bucket: bucket, slot: slot),
              let data = try? Data(contentsOf: file),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let trackRows = json["tracks"] as? [[String: Any]] else {
            return nil
        }
        let tracks = trackRows.compactMap { row -> DaylistTrack? in
            guard let videoId = row["v"] as? String, videoId.count == 11 else { return nil }
            return DaylistTrack(
                videoId: videoId,
                title: (row["t"] as? String) ?? "",
                artist: (row["a"] as? String) ?? "",
                durationSec: (row["d"] as? Int) ?? 0,
                thumbnailUrl: (row["th"] as? String) ?? "",
                seedQuery: (row["q"] as? String) ?? ""
            )
        }
        return Daylist(
            bucket: bucket,
            title: "",
            subtitle: "",
            tracks: tracks,
            generatedAtMs: (json["at"] as? Int64) ?? 0,
            fromCache: true
        )
    }

    private func writeCache(_ daylist: Daylist, bucket: DaylistBucket, slot: Int) {
        guard let file = cacheFile(bucket: bucket, slot: slot) else { return }
        let rows: [[String: Any]] = daylist.tracks.map { track in
            [
                "v": track.videoId,
                "t": track.title,
                "a": track.artist,
                "d": track.durationSec,
                "th": track.thumbnailUrl,
                "q": track.seedQuery,
            ]
        }
        let payload: [String: Any] = [
            "tracks": rows,
            "at": daylist.generatedAtMs,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        try? data.write(to: file, options: .atomic)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-07 (companion): AntiDriftScoringEngine — the Swift port of
// AntiDriftScoringEngine.kt's PURE scoring core (the plan maps both
// discovery files onto this service): junk/compilation filtering, duplicate
// variation elimination, strict artist saturation capping, and composite
// acoustic affinity ranking (Gaussian BPM proximity + Camelot key distance +
// artist diversity damping).
//
// DELIBERATELY NOT PORTED:
//   * the JNI fast path (NativeBridge.rustScoreAndRankRadioCandidates) —
//     the Apple lane has no such export on the typed contract yet; the
//     pure path below IS the Kotlin fallback, verbatim semantics;
//   * FuzzyTitleMatcher.isSameSongVariation — injected as a hook so the
//     fuzzy matcher lands with the search surfaces that own it; nil skips
//     the fuzzy check (signature dedup still applies).
// ─────────────────────────────────────────────────────────────────────────────

/// One scorable radio candidate (the Kotlin Track's acoustic fields).
struct DriftScorableTrack: Equatable, Sendable {
    let id: String
    let title: String
    let artist: String
    let album: String
    var durationSec: Int = 0
    /// Beats per minute; 0 = un-analyzed (neutral baseline).
    var bpm: Float = 0
    /// Camelot/musical key, e.g. "8A", "12B"; blank = unknown.
    var key: String = ""

    init(
        id: String,
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
        bpm: Float = 0,
        key: String = ""
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.durationSec = durationSec
        self.bpm = bpm
        self.key = key
    }

    /// The Kotlin `signature()`: trimmed lowercase title:::artist.
    var signature: String {
        "\(title.trimmingCharacters(in: .whitespaces).lowercased()):::\(artist.trimmingCharacters(in: .whitespaces).lowercased())"
    }
}

/// Filters out non-music compilations, eliminates duplicate song
/// variations, caps artist saturation, and ranks candidates by acoustic
/// and harmonic affinity — SW-ENG1-07 (port of AntiDriftScoringEngine).
enum AntiDriftScoringEngine {

    static let maxTracksPerArtist = 2
    static let windowSize = 20

    static let junkKeywords = [
        "full album", "1 hour", "10 hours", "compilation", "greatest hits mix",
        "best songs mix", "non stop", "jukebox", "podcast", "audiobook", "asmr",
        "medley", "slowed + reverb mix", "workout mix",
    ]

    /// Fuzzy same-song-variation predicate over (titleA, artistA, titleB,
    /// artistB) — the FuzzyTitleMatcher port injects itself here.
    typealias VariationMatcher = @Sendable (String, String, String, String) -> Bool

    static func filterAndRankCandidates(
        _ candidates: [DriftScorableTrack],
        seedTrack: DriftScorableTrack,
        activeQueue: [DriftScorableTrack],
        isSameSongVariation: VariationMatcher? = nil
    ) -> [DriftScorableTrack] {
        guard !candidates.isEmpty else { return [] }

        var seenSignatures = Set<String>()
        var artistCounts: [String: Int] = [:]
        var ranked: [(track: DriftScorableTrack, score: Float)] = []

        // 1. Prime historical artist saturation + seen tracks from the
        // existing queue window.
        let queueWindow = Array(activeQueue.suffix(windowSize))
        for track in queueWindow {
            let normArtist = track.artist.trimmingCharacters(in: .whitespaces).lowercased()
            artistCounts[normArtist, default: 0] += 1
            seenSignatures.insert(track.signature)
        }
        seenSignatures.insert(seedTrack.signature)

        let seedBpm = seedTrack.bpm > 0 ? seedTrack.bpm : 120
        let seedKey = seedTrack.key.trimmingCharacters(in: .whitespaces).uppercased()

        // 2. Filter and score the candidate pool.
        for track in candidates {
            let titleLower = track.title.trimmingCharacters(in: .whitespaces).lowercased()
            let artistLower = track.artist.trimmingCharacters(in: .whitespaces).lowercased()

            // A. Junk & non-music compilation filter.
            if titleLower.isEmpty || artistLower.isEmpty { continue }
            if junkKeywords.contains(where: { titleLower.contains($0) }) { continue }

            // Duration sanity: filter 2-hour compilations and sub-35-second
            // clips unless the seed itself is long-form. Kotlin's candidate
            // pool is MediaStore rows that always carry a real duration;
            // Apple-lane stream candidates may be un-analyzed, where 0
            // means "no metadata yet" — an unknown duration falls through
            // to the neutral baseline instead of being rejected as a
            // 0-second clip.
            if track.durationSec > 0,
               (60...600).contains(seedTrack.durationSec),
               track.durationSec > 720 || track.durationSec < 35 {
                continue
            }

            // B. Root title & fuzzy duplicate check.
            let signature = track.signature
            if seenSignatures.contains(signature) { continue }
            if let isSameSongVariation,
               queueWindow.contains(where: {
                   isSameSongVariation($0.title, $0.artist, track.title, track.artist)
               }) {
                continue
            }

            // C. Strict artist saturation ceiling.
            let currentArtistCount = artistCounts[artistLower] ?? 0
            if currentArtistCount >= maxTracksPerArtist { continue }

            // D. Composite acoustic score.
            let score = compositeScore(
                track,
                seedBpm: seedBpm,
                seedKey: seedKey,
                artistFrequency: currentArtistCount
            )
            ranked.append((track, score))

            seenSignatures.insert(signature)
            artistCounts[artistLower] = currentArtistCount + 1
        }

        // 3. Highest affinity first.
        return ranked
            .sorted { $0.score > $1.score }
            .map(\.track)
    }

    private static func compositeScore(
        _ candidate: DriftScorableTrack,
        seedBpm: Float,
        seedKey: String,
        artistFrequency: Int
    ) -> Float {
        var score: Float = 100

        // 1. Gaussian BPM proximity (σ = 25 BPM).
        if candidate.bpm > 0, seedBpm > 0 {
            let bpmDiff = abs(candidate.bpm - seedBpm)
            let exponent = -(Double(bpmDiff * bpmDiff)) / (2.0 * 25.0 * 25.0)
            score += Float(exp(exponent)) * 30
        } else {
            // Neutral baseline affinity for un-analyzed or stream candidates.
            score += 25
        }

        // 2. Camelot key harmonic compatibility.
        if !candidate.key.isEmpty, !seedKey.isEmpty {
            let keyDistance = camelotDistance(
                candidate.key.trimmingCharacters(in: .whitespaces).uppercased(),
                seedKey
            )
            switch keyDistance {
            case 0: score += 25 // exact harmonic match
            case 1: score += 15 // harmonic neighbor
            default: score -= 5
            }
        }

        // 3. Artist diversity penalty (mild damping for a same-artist
        // follow-up).
        if artistFrequency > 0 {
            score -= 12 * Float(artistFrequency)
        }

        return score
    }

    private static func camelotDistance(_ keyA: String, _ keyB: String) -> Int {
        let numA = Int(keyA.filter(\.isNumber))
        let numB = Int(keyB.filter(\.isNumber))
        let letterA = keyA.filter(\.isLetter)
        let letterB = keyB.filter(\.isLetter)
        guard let numA, let numB else { return 2 }

        if letterA == letterB {
            let diff = abs(numA - numB)
            return diff > 6 ? 12 - diff : diff
        }
        if numA == numB {
            return 1 // relative major/minor modulation
        }
        return 2
    }
}
