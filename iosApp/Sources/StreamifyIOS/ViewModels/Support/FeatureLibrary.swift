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
// `#if canImport(uniffi_streamify)`) lights up without a single edit in any
// view model.
//
// Every requirement has a default (empty/idle) implementation below, so test
// doubles conform and override only the methods they script.
// ─────────────────────────────────────────────────────────────────────────────

/// The application feature surface consumed by the view models (push-first).
protocol FeatureLibrary: AnyObject {

    // MARK: Search (StreamResolver::search_tracks lane)

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchResult]
    func searchSuggestions(query: String) async -> [String]

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

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchResult] { [] }
    func searchSuggestions(query: String) async -> [String] { [] }
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

    var searchResults: [SearchResult] = []
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

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchResult] {
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
                Task { @MainActor in
                    self?.queueContinuation = nil
                }
            }
        }
    }

    var jamRoomEvents: AsyncStream<JamRoomEvent> {
        AsyncStream { continuation in
            lock.withLock { roomContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.roomContinuation = nil
                }
            }
        }
    }

    var ingestionEvents: AsyncStream<IngestionEvent> {
        AsyncStream { continuation in
            lock.withLock { ingestionContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.ingestionContinuation = nil
                }
            }
        }
    }

    // ── Test/preview push helpers (the `push` counterparts of the gateway) ─

    func push(queueEvent: QueueEvent) {
        lock.withLock { queueContinuation?.yield(queueEvent) }
    }

    func push(roomEvent: JamRoomEvent) {
        lock.withLock { roomContinuation?.yield(roomEvent) }
    }

    func push(ingestionEvent: IngestionEvent) {
        lock.withLock { ingestionContinuation?.yield(ingestionEvent) }
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
/// Views construct view models bare (`SearchViewModel()`) and get the preview
/// lane until the real UniFFI lane is linked; previews and tests inject
/// explicitly.
@MainActor
enum AppServices {
    static let library: any FeatureLibrary = PreviewFeatureLibrary()
    static let events = UiEventBus()
    static let settings = SettingsStore.live
}
