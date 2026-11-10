import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// The REAL engine lane: direct Swift UniFFI bindings from Alpha's
// StreamifyCore.xcframework (`streamifyFFI` module). Compiled strictly
// behind `#if canImport(streamifyFFI)` — until the xcframework is
// linked this whole type is absent, the shell falls back to the preview
// gateway, and the scaffold compiles standalone. The mapping below follows
// the frozen UDL contract (rust/crates/streamify-contract/src/streamify.udl);
// the moment the packaging automation ships the framework into
// iosApp/Frameworks/, this lane activates with zero edits anywhere else
// (same philosophy as the desktop CI's Windows probe-gate).
//
// ZERO Kotlin/Native double-bridge (MP-DEC-02): SwiftUI → streamifyFFI
// Swift proxies → the staticlib's uniffi_* C ABI. Nothing in between.
//
// SW-ENG1-04 (this revision):
//   * The push-event bridge is now a SINGLE stream + SINGLE listener,
//     registered once at construction: repeated `snapshots` accesses share
//     one logical stream (no listener leak, no double registration), the
//     newest snapshot is buffered for late subscribers, and `shutdown()` /
//     `deinit` finish the stream cleanly.
//   * The typed data lane (SW-ENG1-03 records) is bridged through the
//     lane-neutral Engine* mirrors: the one genuinely blocking network
//     operation (`searchTracks`) hops off the cooperative pool through a
//     detached task; every other engine call is an in-memory control-plane
//     op in the house direct-call style.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(streamifyFFI)
import streamifyFFI

final class UniFFIEngineGateway: EngineGateway {

    private let engine: StreamifyEngine

    // ── WS-B B3: the resolver cache + staged-session plane ────────────────

    /// In-memory resolve memo (video id → formats): a tap on an
    /// already-resolved row must never pay the network twice. Bounded —
    /// a long listening session cannot grow it without limit.
    private let resolveMemoLock = NSLock()
    private var resolveMemo: [String: [EngineResolvedStreamFormat]] = [:]
    private static let resolveMemoCapacity = 64

    /// The last row the play path staged (the engine's pushed snapshots
    /// carry no track identity — the UDL `EngineSnapshot` is transport
    /// truth only — so the gateway enriches mapped snapshots with the
    /// session's track for the now-playing surfaces). Lock-guarded: the
    /// push bridge reads it on the engine's dispatch thread.
    private let stagedLock = NSLock()
    private var stagedTrack: EngineTrack?

    /// Whether the playback plane is currently attached (attach has
    /// replace semantics in the engine — the gateway attaches once per
    /// plane lifetime and re-attaches only after a detach).
    private var sinkAttached = false

    /// The ring capacity the play path mounts with — see
    /// `EnginePlaybackPlane.capacityFrames` (the lane-neutral tuning).
    static let playbackCapacityFrames = EnginePlaybackPlane.capacityFrames

    /// Retains the engine's push listener bridge (see `init`).
    private var listenerBridge: ListenerBridge?

    // ── SW-ENG1-04: the push-event bridge (multi-subscriber fan-out) ─────

    /// The engine keeps ONE `EngineEventListener` registration (no listener
    /// leak); the Swift side fans that single push lane out to every
    /// subscriber — PlaybackViewModel's transport ingest AND the feature
    /// library's queue-plane resync each subscribe exactly once, and an
    /// `AsyncStream` is single-consumer, so one shared stored instance
    /// would make them COMPETE for elements. Each `snapshots` access
    /// registers a fresh continuation; the newest snapshot is buffered for
    /// late subscribers (the bufferingNewest(1) discipline, preserved).
    private let subscribersLock = NSLock()
    private var subscribers: [UUID: AsyncStream<PlaybackSnapshot>.Continuation] = [:]
    private var newestSnapshot: PlaybackSnapshot?

    /// Constructs and arms the engine (Idle → Buffering), registers the
    /// push listener, and points the resolver's persistent cache at the
    /// app-support database (`set_resolver_cache_db`, UDL). Throws on
    /// initialization rejection — the factory (`EngineGatewayLoader`)
    /// degrades that to the preview lane instead of crashing.
    init() throws {
        engine = StreamifyEngine()
        try engine.initialize()

        // The bridge is retained by the gateway (and, once registered, by
        // the engine's listener list); events arrive on the engine's
        // dedicated dispatch thread and only ever yield — cheap, total,
        // never blocking the dispatcher. The staged-track enrichment is
        // read under the staged lock (see `stagedTrack`).
        let bridge = ListenerBridge { [weak self] snapshot in
            self?.fanOut(snapshot)
        }
        listenerBridge = bridge
        _ = try engine.addEventListener(listener: bridge)

        Self.wireResolverCache()
    }

    /// One push, delivered to every live subscriber (dispatch thread;
    /// lock-guarded registry, yield-only bodies).
    private func fanOut(_ snapshot: EngineSnapshot) {
        let mapped = Self.map(snapshot, stagedTrack: currentStagedTrack())
        subscribersLock.withLock {
            newestSnapshot = mapped
            subscribers.values.forEach { $0.yield(mapped) }
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribersLock.withLock {
            subscribers[id] = nil
        }
    }

    /// The staged-session track (dispatch-thread-safe read).
    private func currentStagedTrack() -> EngineTrack? {
        stagedLock.withLock { stagedTrack }
    }

    /// Points the Rust resolver's persistent cache at the app-support
    /// SQLite database, creating the directory if needed. A failure here
    /// is survivable by design — resolution falls back to the network
    /// every time (the cache is an optimization, never a dependency).
    /// (Namespace-level UDL function — module-qualified like the other
    /// globals.)
    private static func wireResolverCache() {
        guard let dbURL = resolverCacheURL() else { return }
        try? StreamifyIOS.setResolverCacheDb(dbPath: dbURL.path)
    }

    /// `<AppSupport>/Streamify/resolver-cache.sqlite3` (created on demand).
    nonisolated private static func resolverCacheURL() -> URL? {
        let fm = FileManager.default
        guard let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("Streamify", isDirectory: true)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            return nil
        }
        return dir.appendingPathComponent("resolver-cache.sqlite3")
    }

    deinit {
        // Every parked `for await` must wake deterministically when the
        // gateway goes away.
        subscribersLock.withLock {
            subscribers.values.forEach { $0.finish() }
            subscribers.removeAll()
        }
    }

    var snapshots: AsyncStream<PlaybackSnapshot> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            // AsyncStream.Continuation is a VALUE type — key the registry
            // by a fresh UUID instead of an ObjectIdentifier.
            let id = UUID()
            subscribersLock.withLock {
                subscribers[id] = continuation
                // Late-subscriber hydration: the newest push replays once.
                if let newest = newestSnapshot {
                    continuation.yield(newest)
                }
            }
            continuation.onTermination = { [weak self] _ in
                self?.removeSubscriber(id)
            }
        }
    }

    func send(_ command: PlayerCommand) async -> PlayerCommandResult {
        do {
            switch command {
            case .setVolume(let volume):
                try engine.setVolume(volume: Float(min(max(volume, 0), 1)))
                return .applied
            default:
                let applied = try engine.onMediaCommand(command: Self.map(command))
                return applied ? .applied : .debounced
            }
        } catch {
            // Failures across the seam (including marshaled Rust panics,
            // MP-C06) degrade to a failed dispatch — never an exception
            // into UI code.
            return .failed
        }
    }

    func currentSnapshot() async -> PlaybackSnapshot? {
        guard let snapshot = try? engine.getEngineSnapshot() else { return nil }
        return Self.map(snapshot)
    }

    func createJamSession(name: String) async -> String? {
        guard let handle = try? engine.createJamSession(sessionName: name) else { return nil }
        return handle.sessionName
    }

    func jamStateHash() async -> String? {
        try? engine.getJamStateHash()
    }

    func shutdown() {
        let bridges: [AsyncStream<PlaybackSnapshot>.Continuation] =
            subscribersLock.withLock {
                let all = Array(subscribers.values)
                subscribers.removeAll()
                newestSnapshot = nil
                return all
            }
        bridges.forEach { $0.finish() }
        listenerBridge = nil
    }

    // ── SW-ENG1-04: the typed data lane ───────────────────────────────────

    func searchTracks(_ query: String, limit: Int) async throws -> EngineSearchResult {
        // The resolver BLOCKS the calling thread on the engine's shared
        // runtime until network resolution completes — hop off the
        // cooperative pool so a cold query never starves the main actor.
        let result = try await Task.detached(priority: .userInitiated) {
            try Self.performSearchTracks(query: query, limit: limit)
        }.value
        return Self.map(result)
    }

    func enqueue(track: EngineTrack, enqueuedBy: String) async -> Int? {
        guard let index = try? engine.enqueueTrack(
            track: Self.unmap(track),
            enqueuedBy: enqueuedBy
        ) else { return nil }
        return Int(index)
    }

    func queue() async -> [EngineQueueItem] {
        guard let items = try? engine.getQueue() else { return [] }
        return items.map(Self.map)
    }

    func clearQueue() async {
        try? engine.clearQueue()
    }

    func equalizerBands() async -> [EngineEqualizerBand] {
        guard let bands = try? engine.getEqualizerBands() else { return [] }
        return bands.map(Self.map)
    }

    func setEqualizerBand(_ band: EngineEqualizerBand) async {
        // A rejected band (out-of-range index, non-finite floats) stays
        // rejected at the Rust boundary; the next `equalizerBands()` read
        // reflects the unchanged curve — never an exception into UI code.
        try? engine.setEqualizerBand(band: Self.unmap(band))
    }

    func jamRoomState() async -> EngineJamRoomState? {
        guard let room = try? engine.getJamRoomState() else { return nil }
        return Self.map(room)
    }

    // ── WS-B B3: resolve → stage → attach → intent (the full play path) ───

    func resolveStreamFormats(videoId: String) async throws -> [EngineResolvedStreamFormat] {
        // The memo first: a tapped row that already resolved must not pay
        // the network twice (the resolver's own persistent cache covers
        // cold starts; this covers the session).
        if let cached = resolveMemoLock.withLock({ resolveMemo[videoId] }) {
            return cached
        }
        // The resolver BLOCKS the calling thread on the engine's shared
        // runtime — hop off the cooperative pool (same discipline as
        // searchTracks).
        let formats = try await Task.detached(priority: .userInitiated) {
            try Self.performResolveStreamFormats(videoId: videoId)
        }.value
        let mapped = formats.map(Self.map)
        resolveMemoLock.withLock {
            if resolveMemo.count >= Self.resolveMemoCapacity {
                resolveMemo.removeAll(keepingCapacity: true)
            }
            resolveMemo[videoId] = mapped
        }
        return mapped
    }

    func play(track: EngineTrack) async -> Int? {
        // 1. Resolve unless the row already carries a playable URL
        //    (re-plays of a staged row and offline file URLs skip the
        //    network entirely).
        var staged = track
        if staged.resolvedStreamUrl == nil {
            guard
                let formats = try? await resolveStreamFormats(videoId: track.id),
                let best = EngineResolvedStreamFormat.bestAudio(of: formats)
            else { return nil }
            staged.resolvedStreamUrl = best.url
        }

        // 2. Stage the row WITH the resolved URL (play_track requires it).
        guard let index = await enqueue(track: staged, enqueuedBy: "local") else { return nil }

        // 3. Mount the plane (attach has replace semantics in the engine;
        //    the gateway's once-per-lifetime guard keeps the producer
        //    thread stable across skips).
        await attachSink(capacityFrames: Self.playbackCapacityFrames)

        // 4. The play intent. The producer coalesces over any in-flight
        //    session (the skip path); the next push repaints.
        do {
            try engine.playTrack(trackId: staged.id)
            stagedLock.withLock { stagedTrack = staged }
            return index
        } catch {
            return nil
        }
    }

    func playStagedTrack(id: String) async -> Bool {
        // The engine's own row lookup: unknown ids / URL-less rows fail as
        // Internal at the boundary — degrade to false, never surface.
        do {
            try engine.playTrack(trackId: id)
            if let staged = await queue().first(where: { $0.track.id == id })?.track {
                stagedLock.withLock { stagedTrack = staged }
            }
            return true
        } catch {
            return false
        }
    }

    func attachSink(capacityFrames: Int) async {
        guard !sinkAttached else { return }
        do {
            try engine.attachSink(capacityFrames: UInt32(max(64, min(1 << 22, capacityFrames))))
            sinkAttached = true
        } catch {
            // A refused mount degrades to transport-only playback — the
            // control plane stays honest (nothing renders until a plane
            // exists), exactly like the pre-A3 posture.
        }
    }

    func detachSink() async {
        try? engine.detachSink()
        sinkAttached = false
    }

    func bufferProgress() async -> EngineBufferProgress? {
        guard let progress = try? engine.bufferProgress() else { return nil }
        return Self.map(progress)
    }

    func recordDacClock(_ sample: DacClockSample) async {
        let clock = DacPresentationClock(
            dacFramePosition: UInt64(max(0, sample.framePosition)),
            sampleRate: UInt32(max(8_000, min(192_000, sample.sampleRateHz))),
            monotonicNs: UInt64(max(0, sample.monotonicNs))
        )
        // An invalid sample is rejected at the Rust boundary
        // (AudioErrorKind::InvalidClock) — swallowed here, never surfaced.
        try? engine.recordDacClock(clock: clock)
    }

    // ── UDL → shared presentation mapping (total by construction) ──────────

    private final class ListenerBridge: EngineEventListener {
        private let sink: (EngineSnapshot) -> Void

        init(sink: @escaping (EngineSnapshot) -> Void) {
            self.sink = sink
        }

        func onEngineEvent(snapshot: EngineSnapshot) {
            // Engine dispatch thread: cheap total mapping only.
            sink(snapshot)
        }
    }

    /// Module-qualified on purpose: the generated global function
    /// `searchTracks(query:limit:)` and the gateway protocol's instance
    /// method share a base name — the unqualified call resolved to the
    /// instance method (and failed in this static context). Qualify with
    /// the module to reach the generated top-level function.
    private static func performSearchTracks(query: String, limit: Int) throws -> SearchResult {
        try StreamifyIOS.searchTracks(query: query, limit: UInt32(max(1, limit)))
    }

    /// Same module-qualification discipline for the v2 resolver surface.
    private static func performResolveStreamFormats(videoId: String) throws -> [ResolvedStreamFormat] {
        try StreamifyIOS.resolveStreamFormats(videoId: videoId)
    }

    private static func map(_ snapshot: EngineSnapshot, stagedTrack: EngineTrack? = nil) -> PlaybackSnapshot {
        let clock = snapshot.clock.map { clock in
            DacClockSample(
                framePosition: Int(clamping: clock.dacFramePosition),
                sampleRateHz: Int(clamping: clock.sampleRate),
                monotonicNs: Int(clamping: clock.monotonicNs)
            )
        }
        return PlaybackSnapshot(
            rev: Int(snapshot.rev),
            status: map(snapshot.state),
            volume: Double(snapshot.volume),
            buffer: BufferHealth(
                bufferedDurationMs: Int(clamping: snapshot.bufferStats.bufferedDurationMs),
                underrunCount: Int(clamping: snapshot.bufferStats.underrunCount),
                fillRatio: Double(snapshot.bufferStats.fillRatio)
            ),
            clock: clock,
            currentTrack: stagedTrack.map(PresentationTrack.init),
            jamSessionName: nil
        )
    }

    private static func map(_ state: PlaybackState) -> PlaybackStatus {
        switch state {
        case .idle: return .idle
        case .buffering: return .buffering
        case .playing: return .playing
        case .paused: return .paused
        case .error: return .error
        @unknown default: return .idle
        }
    }

    private static func map(_ command: PlayerCommand) -> MediaCommand {
        switch command {
        case .play: return .play
        case .pause: return .pause
        case .togglePlayPause: return .togglePlayPause
        case .next: return .next
        case .previous: return .previous
        case .seek(let positionMs): return .seek(positionMs: UInt64(positionMs))
        case .setVolume(let volume): return .setVolume(volume: Float(volume))
        case .stop: return .stop
        }
    }

    // ── SW-ENG1-03 record mappings (generated → lane-neutral mirror) ──────

    private static func map(_ track: Track) -> EngineTrack {
        EngineTrack(
            id: track.id,
            title: track.title,
            artist: track.artist,
            album: track.album,
            durationMs: Int(track.durationMs),
            thumbnailUrl: track.thumbnailUrl,
            isExplicit: track.isExplicit,
            resolvedStreamUrl: track.resolvedStreamUrl
        )
    }

    private static func map(_ result: SearchResult) -> EngineSearchResult {
        EngineSearchResult(
            tracks: result.tracks.map(map),
            playlists: result.playlists.map(map),
            continuationToken: result.continuationToken
        )
    }

    private static func map(_ playlist: PlaylistSummary) -> EnginePlaylistSummary {
        EnginePlaylistSummary(
            id: playlist.id,
            title: playlist.title,
            thumbnailUrl: playlist.thumbnailUrl,
            trackCount: Int(playlist.trackCount)
        )
    }

    private static func map(_ item: QueueItem) -> EngineQueueItem {
        EngineQueueItem(
            index: Int(item.index),
            track: map(item.track),
            enqueuedBy: item.enqueuedBy
        )
    }

    private static func map(_ band: EqualizerBand) -> EngineEqualizerBand {
        EngineEqualizerBand(
            bandIndex: Int(band.bandIndex),
            frequencyHz: band.frequencyHz,
            gainDb: band.gainDb,
            qFactor: band.qFactor
        )
    }

    private static func map(_ room: JamRoomState) -> EngineJamRoomState {
        EngineJamRoomState(
            roomCode: room.roomCode,
            isHost: room.isHost,
            syncOffsetMs: room.syncOffsetMs,
            participants: room.participants
        )
    }

    private static func map(_ format: ResolvedStreamFormat) -> EngineResolvedStreamFormat {
        EngineResolvedStreamFormat(
            url: format.url,
            mimeType: format.mimeType,
            bitrate: Int(clamping: format.bitrate),
            durationSec: Int(clamping: format.durationSec),
            isAudioOnly: format.isAudioOnly
        )
    }

    private static func map(_ progress: BufferProgress) -> EngineBufferProgress {
        EngineBufferProgress(
            bufferedMs: Int(clamping: progress.bufferedMs),
            fillRatio: Double(progress.fillRatio),
            underrunEvents: Int(clamping: progress.underrunEvents),
            overrunEvents: Int(clamping: progress.overrunEvents),
            targetPositionMs: Int(clamping: progress.targetPositionMs),
            renderedPositionMs: Int(clamping: progress.renderedPositionMs),
            trackId: progress.trackId,
            producerLive: progress.producerLive,
            eos: progress.eos
        )
    }

    // ── lane-neutral mirror → generated (for the write paths) ─────────────

    private static func unmap(_ track: EngineTrack) -> Track {
        Track(
            id: track.id,
            title: track.title,
            artist: track.artist,
            album: track.album,
            durationMs: UInt64(max(0, track.durationMs)),
            thumbnailUrl: track.thumbnailUrl,
            isExplicit: track.isExplicit,
            resolvedStreamUrl: track.resolvedStreamUrl
        )
    }

    private static func unmap(_ band: EngineEqualizerBand) -> EqualizerBand {
        EqualizerBand(
            bandIndex: UInt8(max(0, min(255, band.bandIndex))),
            frequencyHz: band.frequencyHz,
            gainDb: band.gainDb,
            qFactor: band.qFactor
        )
    }
}
#endif
