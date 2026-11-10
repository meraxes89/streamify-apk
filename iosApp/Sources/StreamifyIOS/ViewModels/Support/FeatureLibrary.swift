import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): the feature seam for the 14-view-model suite.
//
// The playback plane already crosses `EngineGateway` (Engine/). Everything
// else the view models need — search, feeds, queue mutations, lyrics, the
// EQ DSP plane, blend, community, stats, release radar, profiles, ingestion
// and jam-room state — is ALSO sovereign in the Rust core (SW-DEC-05), but
// the expanded UniFFI contract (SW-ENG1-03) has not landed yet. Exactly like
// `PreviewEngineGateway` for the playback lane, this ONE protocol is the
// probe-gated seam: the preview library keeps the shell fully navigable
// today, and the real lane (`UniFFIFeatureLibrary` behind
// `#if canImport(streamifyFFI)`) lights up without a single edit in any
// view model.
//
// Every requirement has a default (empty/idle) implementation below, so test
// doubles conform and override only the methods they script.
// ─────────────────────────────────────────────────────────────────────────────

/// The application feature surface consumed by the view models (push-first).
protocol FeatureLibrary: AnyObject {

    // MARK: Search (StreamResolver::search_tracks lane)

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchRow]
    func searchSuggestions(query: String) async -> [String]

    // MARK: Play (WS-B B3: resolve -> stage -> play_track, one transaction)

    /// Plays one track end-to-end on the lane: resolve the playable
    /// stream formats (cache-first), stage the row on the engine queue
    /// carrying the resolved URL, then lower `play_track` (A4) — the
    /// transport's single play/skip transaction. Returns false when the
    /// lane refused (resolve failure or engine rejection) — never throws.
    /// The pushed queue/transport state repaints the shell.
    func play(track: PresentationTrack) async -> Bool

    // MARK: Home feed

    func homeFeed(hourOfDay: Int) async -> HomeFeed

    // MARK: Queue plane (state arrives via push; mutations dispatch here)

    var queueEvents: AsyncStream<QueueEvent> { get }
    func moveQueueItem(from: Int, to: Int) async
    func removeQueueItems(at: [Int]) async
    func clearPlayedHistory() async
    func setAutoMix(enabled: Bool) async

    // MARK: Lyrics

    func fetchLyrics(trackId: String, title: String, artist: String, durationMs: Int) async -> String?

    // MARK: Equalizer (streamify-dsp plane)

    func applyEqualizerProfile(_ profile: EqualizerProfile) async

    // MARK: Blend

    func fetchBlend(friendName: String, friendSeeds: [String]) async -> BlendResult

    // MARK: Community

    func communityFeed() async -> CommunityFeed

    // MARK: Wrapped stats (SQLite metrics plane)

    func wrappedStats() async -> WrappedStats

    // MARK: Release radar

    func releaseRadar() async -> [ReleaseWatch]

    // MARK: Profile

    func userProfile(userId: String?) async -> UserProfileSummary

    // MARK: Ingestion (external playlist imports)

    func enqueuePlaylistImport(named name: String, source: String) async -> Bool
    var ingestionEvents: AsyncStream<IngestionEvent> { get }
    func cancelIngestion(taskId: String) async

    // MARK: Jam rooms (the CRDT/PTP engine stays sovereign in Rust)

    var jamRoomEvents: AsyncStream<JamRoomEvent> { get }
    func hostJamRoom(name: String) async -> JamRoomSnapshot?
    func joinJamRoom(code: String) async -> Bool
    func leaveJamRoom(endForEveryone: Bool) async
    func setJamTopology(_ topology: JamTopology) async
    func cycleJamControlPolicy() async
    func setMemberAcl(nonce: String, allowControl: Bool?, allowVolume: Bool?) async
    func setMemberRole(nonce: String, role: JamRole) async
    func kickMember(nonce: String) async
    func addToJamQueue(track: PresentationTrack) async
    func removeFromJamQueue(trackId: String) async
    func moveInJamQueue(trackId: String, toPosition: Int) async
    func castJamVote(trackId: String) async
}

// MARK: - Defaults: conformers override only what they script

extension FeatureLibrary {

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchRow] { [] }
    func searchSuggestions(query: String) async -> [String] { [] }
    func play(track: PresentationTrack) async -> Bool { false }
    func homeFeed(hourOfDay: Int) async -> HomeFeed { HomeFeed() }
    func moveQueueItem(from: Int, to: Int) async {}
    func removeQueueItems(at: [Int]) async {}
    func clearPlayedHistory() async {}
    func setAutoMix(enabled: Bool) async {}
    func fetchLyrics(trackId: String, title: String, artist: String, durationMs: Int) async -> String? { nil }
    func applyEqualizerProfile(_ profile: EqualizerProfile) async {}
    func fetchBlend(friendName: String, friendSeeds: [String]) async -> BlendResult { BlendResult() }
    func communityFeed() async -> CommunityFeed { CommunityFeed() }
    func wrappedStats() async -> WrappedStats { WrappedStats.empty }
    func releaseRadar() async -> [ReleaseWatch] { [] }
    func userProfile(userId: String?) async -> UserProfileSummary { .default }
    func enqueuePlaylistImport(named name: String, source: String) async -> Bool { false }

    var queueEvents: AsyncStream<QueueEvent> {
        AsyncStream { $0.finish() }
    }

    var ingestionEvents: AsyncStream<IngestionEvent> {
        AsyncStream { $0.finish() }
    }

    var jamRoomEvents: AsyncStream<JamRoomEvent> {
        AsyncStream { $0.finish() }
    }

    func cancelIngestion(taskId: String) async {}
    func hostJamRoom(name: String) async -> JamRoomSnapshot? { nil }
    func joinJamRoom(code: String) async -> Bool { false }
    func leaveJamRoom(endForEveryone: Bool) async {}
    func setJamTopology(_ topology: JamTopology) async {}
    func cycleJamControlPolicy() async {}
    func setMemberAcl(nonce: String, allowControl: Bool?, allowVolume: Bool?) async {}
    func setMemberRole(nonce: String, role: JamRole) async {}
    func kickMember(nonce: String) async {}
    func addToJamQueue(track: PresentationTrack) async {}
    func removeFromJamQueue(trackId: String) async {}
    func moveInJamQueue(trackId: String, toPosition: Int) async {}
    func castJamVote(trackId: String) async {}
}

// MARK: - Ingestion events

/// Ingestion progress events (the WorkManager-flow mirror, push-only).
enum IngestionEvent: Sendable {
    case taskUpdated(IngestionTask)
    case taskRemoved(String)
}

// MARK: - The preview lane

/// The always-available feature lane: deterministic canned content for
/// SwiftUI previews, the pre-xcframework scaffold period, and tests (which
/// also subclass to script specific behavior or push room/queue/ingestion
/// events by hand).
final class PreviewFeatureLibrary: FeatureLibrary, @unchecked Sendable {

    private let lock = NSLock()

    // ── Scriptable storage (tests read/seed these directly) ────────────────

    var searchResults: [SearchRow] = []
    var suggestions: [String] = []
    var homeFeedContent: HomeFeed = HomeFeed()
    var blendResult: BlendResult = BlendResult()
    var communityFeedContent: CommunityFeed = CommunityFeed()
    var statsContent: WrappedStats = WrappedStats.empty
    var radarContent: [ReleaseWatch] = []
    var profileContent: UserProfileSummary = .default
    var lyricsLrc: String? = nil
    var joinSucceeds = true
    var hostSucceeds = true

    private var recordedMoveRequests: [(from: Int, to: Int)] = []
    private var recordedRemovals: [Int] = []
    private var recordedQueueTracks: [PresentationTrack] = []
    private var recordedVotes: [String] = []
    private var recordedEqProfiles: [EqualizerProfile] = []
    private var recordedImports: [(name: String, source: String)] = []

    var moveRequests: [(from: Int, to: Int)] { lock.withLock { recordedMoveRequests } }
    var removals: [Int] { lock.withLock { recordedRemovals } }
    var queuedTracks: [PresentationTrack] { lock.withLock { recordedQueueTracks } }
    var castVotes: [String] { lock.withLock { recordedVotes } }
    var appliedEqProfiles: [EqualizerProfile] { lock.withLock { recordedEqProfiles } }
    var enqueuedImports: [(name: String, source: String)] { lock.withLock { recordedImports } }

    // ── Push-stream continuations (single collector per lane) ──────────────

    private var queueContinuation: AsyncStream<QueueEvent>.Continuation?
    private var roomContinuation: AsyncStream<JamRoomEvent>.Continuation?
    private var ingestionContinuation: AsyncStream<IngestionEvent>.Continuation?

    // ── FeatureLibrary conformance ─────────────────────────────────────────

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchRow] {
        searchResults.filter { $0.title.localizedCaseInsensitiveContains(query) || query.isEmpty }
    }

    func searchSuggestions(query: String) async -> [String] {
        suggestions
    }

    func homeFeed(hourOfDay: Int) async -> HomeFeed {
        homeFeedContent
    }

    func fetchLyrics(trackId: String, title: String, artist: String, durationMs: Int) async -> String? {
        lyricsLrc
    }

    func applyEqualizerProfile(_ profile: EqualizerProfile) async {
        lock.withLock { recordedEqProfiles.append(profile) }
    }

    func fetchBlend(friendName: String, friendSeeds: [String]) async -> BlendResult {
        var result = blendResult
        if result.memberNames.isEmpty {
            result.memberNames = ["You", friendName]
        }
        return result
    }

    func communityFeed() async -> CommunityFeed { communityFeedContent }
    func wrappedStats() async -> WrappedStats { statsContent }
    func releaseRadar() async -> [ReleaseWatch] { radarContent }
    func userProfile(userId: String?) async -> UserProfileSummary { profileContent }

    func enqueuePlaylistImport(named name: String, source: String) async -> Bool {
        lock.withLock { recordedImports.append((name, source)) }
        return true
    }

    func moveQueueItem(from: Int, to: Int) async {
        lock.withLock { recordedMoveRequests.append((from, to)) }
    }

    func removeQueueItems(at offsets: [Int]) async {
        lock.withLock { recordedRemovals.append(contentsOf: offsets) }
    }

    func setAutoMix(enabled: Bool) async {
        lock.withLock { autoMixEnabled = enabled }
    }

    private var autoMixEnabled = true

    // ── Play (WS-B B3) ─────────────────────────────────────────────────────

    private var playSnapshotRev = 0

    func play(track: PresentationTrack) async -> Bool {
        // The preview lane stages the tapped track as a one-row queue and
        // pushes the snapshot: previews/tests see queue truth move without
        // any engine lane (the shell renders the same honest state shape
        // the real lane pushes).
        let snapshot = lock.withLock {
            playSnapshotRev += 1
            return QueueSnapshot(
                rev: playSnapshotRev,
                tracks: [track],
                currentIndex: 0,
                isPlaying: true,
                autoMixEnabled: autoMixEnabled
            )
        }
        push(queueEvent: .snapshot(snapshot))
        return true
    }

    var lastAutoMixEnabled: Bool { lock.withLock { autoMixEnabled } }

    func addToJamQueue(track: PresentationTrack) async {
        lock.withLock { recordedQueueTracks.append(track) }
    }

    func castJamVote(trackId: String) async {
        lock.withLock { recordedVotes.append(trackId) }
    }

    func hostJamRoom(name: String) async -> JamRoomSnapshot? {
        guard hostSucceeds else { return nil }
        let snapshot = JamRoomSnapshot(
            rev: 1,
            sessionCode: "AB2K9M",
            sessionId: "preview-room",
            isHost: true,
            topology: .multiRender,
            controlPolicy: .everyone,
            connStatus: .live,
            participants: [
                JamParticipant(nonce: "host", name: name, isHost: true, role: .host, rttMs: 2.1),
            ],
            queue: []
        )
        push(roomEvent: .snapshot(snapshot))
        return snapshot
    }

    func joinJamRoom(code: String) async -> Bool {
        guard joinSucceeds else { return false }
        let snapshot = JamRoomSnapshot(
            rev: 1,
            sessionCode: code,
            sessionId: "preview-joined",
            isHost: false,
            topology: .multiRender,
            controlPolicy: .everyone,
            connStatus: .live,
            participants: [
                JamParticipant(nonce: "host", name: "Host Listener", isHost: true, role: .host, rttMs: 3.4),
                JamParticipant(nonce: "me", name: "You", rttMs: 0),
            ],
            queue: []
        )
        push(roomEvent: .snapshot(snapshot))
        return true
    }

    // ── Push lanes ─────────────────────────────────────────────────────────

    var queueEvents: AsyncStream<QueueEvent> {
        AsyncStream { continuation in
            lock.withLock { queueContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearQueueContinuation()
            }
        }
    }

    /// Termination cleanup — synchronous and lock-protected (the class is
    /// @unchecked Sendable; no actor hop, so the slot frees immediately).
    private func clearQueueContinuation() {
        lock.withLock { queueContinuation = nil }
    }

    var jamRoomEvents: AsyncStream<JamRoomEvent> {
        AsyncStream { continuation in
            lock.withLock { roomContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearRoomContinuation()
            }
        }
    }

    /// Termination cleanup — synchronous and lock-protected.
    private func clearRoomContinuation() {
        lock.withLock { roomContinuation = nil }
    }

    var ingestionEvents: AsyncStream<IngestionEvent> {
        AsyncStream { continuation in
            lock.withLock { ingestionContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearIngestionContinuation()
            }
        }
    }

    /// Termination cleanup — synchronous and lock-protected.
    private func clearIngestionContinuation() {
        lock.withLock { ingestionContinuation = nil }
    }

    // ── Test/preview push helpers (the `push` counterparts of the gateway) ─

    func push(queueEvent: QueueEvent) {
        _ = lock.withLock { queueContinuation?.yield(queueEvent) }
    }

    func push(roomEvent: JamRoomEvent) {
        _ = lock.withLock { roomContinuation?.yield(roomEvent) }
    }

    func push(ingestionEvent: IngestionEvent) {
        _ = lock.withLock { ingestionContinuation?.yield(ingestionEvent) }
    }

    func finishAllStreams() {
        lock.withLock {
            queueContinuation?.finish()
            roomContinuation?.finish()
            ingestionContinuation?.finish()
            queueContinuation = nil
            roomContinuation = nil
            ingestionContinuation = nil
        }
    }
}

// MARK: - Composition root

/// The shared, lazily-constructed instances the 14 view models default to.
/// Views construct view models bare (`SearchViewModel()`) and land on the
/// lane selected HERE, in ONE place; previews and tests inject explicitly
/// (PreviewFeatureLibrary stays strictly a preview/test double — B1's
/// hard-truth rule).
@MainActor
enum AppServices {

    /// The ONE engine gateway the whole process shares (WS-B B1/B3): the
    /// shell's transport holder AND the feature library MUST speak to the
    /// same engine instance — queue truth is process-wide, and the play
    /// flow (resolve -> stage -> play_track) crosses both seams.
    static let engine: EngineGateway = EngineGatewayLoader.load()

    /// The feature seam's lane selection (WS-B B1): the REAL UniFFI lane
    /// whenever the loader armed the real gateway; the preview lane
    /// otherwise (SwiftUI previews, tests, and the pre-xcframework
    /// scaffold period). The selection follows the ENGINE lane, not the
    /// compile-time probe — a real-lane init failure degrades BOTH seams
    /// to the preview double together, never splits them.
    static let library: any FeatureLibrary = {
        #if canImport(streamifyFFI)
        if AppServices.engine is UniFFIEngineGateway {
            return UniFFIFeatureLibrary(gateway: AppServices.engine)
        }
        #endif
        return PreviewFeatureLibrary()
    }()

    static let events = UiEventBus()
    static let settings = SettingsStore.live
}

// MARK: - The real feature lane (WS-B B1/B3)

#if canImport(streamifyFFI)

/// The REAL feature library: the 14-view-model surface mapped onto the
/// live UniFFI engine gateway (the documented-but-missing type behind
/// `#if canImport(streamifyFFI)` — AHEAD WS-B B1).
///
/// MAPPING POLICY (the additive v1 story, mirrored in the PR):
/// * searchTracks       — the typed search lane (`search_tracks`, UDL v1).
/// * play(track:)       — resolve (cache-first) -> stage -> `play_track`
///                         (B3, the first-note flow).
/// * queueEvents        — read-after-action pushes: the queue is a
///                         read-after-action surface by contract (A1 RFC
///                         SW-ENG1-03 note); the library re-reads
///                         `get_queue` exactly when the shell's own action
///                         could have changed it, never a poll.
/// * applyEqualizerProfile — the ten-band `set_equalizer_band` surface.
/// * removeQueueItems(all) — `clear_queue` when the removal covers every
///                         staged row (the UDL v1 queue surface has no
///                         per-row removal or reorder — those degrade to
///                         honest no-ops until the engine grows them).
/// * Everything without a UDL surface (home feed, lyrics, blend,
///   community, stats, radar, profile, ingestion, jam rooms) keeps the
///   protocol defaults — honest empty states, exactly like the preview
///   lane, until ENG-1 grows the contract additively.
final class UniFFIFeatureLibrary: FeatureLibrary, @unchecked Sendable {

    private let lock = NSLock()
    private let gateway: EngineGateway

    // ── The resolve cache (B3): bounded, insertion-ordered ───────────────
    private var resolveCache: [String: [EngineStreamFormat]] = [:]
    private var resolveCacheOrder: [String] = []
    private let resolveCacheLimit = 128

    // ── The queue push lane (read-after-action) ─────────────────────────
    private var queueContinuation: AsyncStream<QueueEvent>.Continuation?
    private var queueRev = 0

    init(gateway: EngineGateway) {
        self.gateway = gateway
        Self.bindResolverMemoDb()
    }

    /// The resolver's persistent memo database (resolved formats, search
    /// pages, deobfuscation transforms). The FIRST call wins; without an
    /// explicit bind the resolver picks a default location — binding keeps
    /// the cache identity stable across launches under Application
    /// Support (the same discipline the downloads vault uses).
    private static func bindResolverMemoDb() {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("StreamifyResolverCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Module-qualified (the generated namespace function); a refused
        // bind (unwritable path) degrades silently — the resolver's own
        /// default-location fallback covers it.
        _ = try? StreamifyIOS.setResolverCacheDb(
            dbPath: directory.appendingPathComponent("resolver.db").path
        )
    }

    // ── Search ────────────────────────────────────────────────────────────

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchRow] {
        // The one genuinely failable network operation on the typed lane —
        // failures degrade to an honest empty page, never an exception
        // into UI code (the gateway seam's rule).
        guard let result = try? await gateway.searchTracks(query, limit: 24) else { return [] }
        return result.tracks.map { track in
            SearchRow(
                id: track.id,
                title: track.title,
                uploader: track.artist,
                durationMs: track.durationMs,
                artworkUrl: track.thumbnailUrl,
                kind: .song,
                isVerified: false
            )
        }
    }

    // ── Play (B3): resolve -> stage -> play_track ─────────────────────────

    func play(track: PresentationTrack) async -> Bool {
        guard let formats = await resolveFormats(videoId: track.id),
              let best = formats.first else { return false }
        let engineTrack = EngineTrack(
            id: track.id,
            title: track.title,
            artist: track.artist,
            album: "",
            durationMs: track.durationMs ?? Int(best.durationSec) * 1000,
            thumbnailUrl: track.artworkUrl,
            isExplicit: false,
            resolvedStreamUrl: best.url
        )
        // Reuse an already-staged row for this id when one exists
        // (`play_track` selects the FIRST matching row); otherwise stage
        // a fresh row carrying the resolved URL. Repeated taps of the
        // same track replay its staged row — no duplicate rows.
        let staged = await gateway.queue()
        let alreadyStaged = staged.contains { $0.track.id == track.id }
        if !alreadyStaged {
            guard await gateway.enqueue(track: engineTrack, enqueuedBy: "local") != nil else {
                return false
            }
        }
        let accepted = await gateway.playTrack(trackId: track.id)
        if accepted {
            await pushQueueSnapshot()
        }
        return accepted
    }

    /// Cache-first resolve; nil on a resolve failure (the honest degrade —
    /// the caller surfaces a non-play, never a fabricated URL).
    private func resolveFormats(videoId: String) async -> [EngineStreamFormat]? {
        if let cached = lock.withLock({ resolveCache[videoId] }) {
            return cached
        }
        let formats = await gateway.resolveStreamFormats(videoId: videoId)
        guard !formats.isEmpty else { return nil }
        lock.withLock {
            resolveCache[videoId] = formats
            resolveCacheOrder.append(videoId)
            while resolveCacheOrder.count > resolveCacheLimit {
                let evicted = resolveCacheOrder.removeFirst()
                resolveCache[evicted] = nil
            }
        }
        return formats
    }

    // ── The queue push lane (read-after-action) ───────────────────────────

    var queueEvents: AsyncStream<QueueEvent> {
        AsyncStream { continuation in
            lock.withLock { queueContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearQueueContinuation()
            }
            // Seed the collector with the current consistent page (the
            // engine queue is a read-after-action surface; the seed is the
            // read, never a poll).
            let library = self
            Task { await library.pushQueueSnapshot() }
        }
    }

    private func clearQueueContinuation() {
        lock.withLock { queueContinuation = nil }
    }

    /// Re-reads the engine queue + progress and yields one consistent
    /// snapshot. Called exactly when the shell's own action could have
    /// changed the queue (play / clear) — the read-after-action contract.
    private func pushQueueSnapshot() async {
        let items = await gateway.queue()
        let progress = await gateway.bufferProgress()
        let transport = await gateway.currentSnapshot()
        let tracks = items.map { PresentationTrack($0.track) }
        let currentId = progress?.trackId
        let currentIndex = currentId
            .flatMap { id in tracks.firstIndex(where: { $0.id == id }) }
            ?? -1
        let playing = transport?.status == .playing
        lock.withLock {
            queueRev += 1
            queueContinuation?.yield(.snapshot(QueueSnapshot(
                rev: queueRev,
                tracks: tracks,
                currentIndex: currentIndex,
                isPlaying: playing,
                autoMixEnabled: true
            )))
        }
    }

    // ── Queue mutations (the UDL v1 surface) ──────────────────────────────

    func removeQueueItems(at offsets: [Int]) async {
        // A removal covering EVERY staged row lowers onto `clear_queue`;
        // partial removal / reorder have no UDL v1 surface and degrade to
        // honest no-ops (the queue re-reads unchanged — documented in the
        /// PR's "Known v1 boundaries").
        let items = await gateway.queue()
        guard !items.isEmpty, offsets.count >= items.count else { return }
        await gateway.clearQueue()
        await pushQueueSnapshot()
    }

    // ── Equalizer (the ten-band `set_equalizer_band` surface) ─────────────

    func applyEqualizerProfile(_ profile: EqualizerProfile) async {
        guard profile.isEnabled else { return }
        for band in profile.bands {
            await gateway.setEqualizerBand(EngineEqualizerBand(
                bandIndex: band.index,
                frequencyHz: Float(band.centerHz),
                gainDb: Float(band.gainDb),
                qFactor: Float(band.q)
            ))
        }
        // The UDL band surface carries gain/Q per band only; preamp and
        // the true-peak limiter ceiling have no engine surface yet — the
        // lossy bridge is deliberate (documented in the PR).
    }
}

#endif  // canImport(streamifyFFI)
