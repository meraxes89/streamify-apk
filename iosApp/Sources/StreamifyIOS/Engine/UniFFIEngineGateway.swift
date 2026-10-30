import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// The REAL engine lane: direct Swift UniFFI bindings from Alpha's
// StreamifyCore.xcframework (`uniffi_streamify` module). Compiled strictly
// behind `#if canImport(uniffi_streamify)` — until the xcframework is
// linked this whole type is absent, the shell falls back to the preview
// gateway, and the scaffold compiles standalone. The mapping below follows
// the frozen UDL contract (rust/crates/streamify-contract/src/streamify.udl);
// the moment the packaging automation ships the framework into
// iosApp/Frameworks/, this lane activates with zero edits anywhere else
// (same philosophy as the desktop CI's Windows probe-gate).
//
// ZERO Kotlin/Native double-bridge (MP-DEC-02): SwiftUI → uniffi_streamify
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

#if canImport(uniffi_streamify)
import uniffi_streamify

final class UniFFIEngineGateway: EngineGateway {

    private let engine: StreamifyEngine

    // ── SW-ENG1-04: the single push-event bridge ──────────────────────────

    /// One stream for the gateway's lifetime. `snapshots` hands out this
    /// instance so every consumer taps the SAME logical channel (an
    /// AsyncStream is single-consumer; ViewModels subscribe exactly once,
    /// and the buffering policy keeps the newest snapshot for a subscriber
    /// that arrives between pushes).
    private let snapshotsStream: AsyncStream<PlaybackSnapshot>
    private let snapshotsContinuation: AsyncStream<PlaybackSnapshot>.Continuation
    private var listenerBridge: ListenerBridge?

    /// Constructs and arms the engine (Idle → Buffering), then registers
    /// the push listener. Throws on initialization rejection — the factory
    /// (`EngineGatewayLoader`) degrades that to the preview lane instead
    /// of crashing.
    init() throws {
        engine = StreamifyEngine()
        try engine.initialize()

        var continuation: AsyncStream<PlaybackSnapshot>.Continuation!
        snapshotsStream = AsyncStream(bufferingPolicy: .bufferingNewest(1)) {
            continuation = $0
        }
        snapshotsContinuation = continuation

        // The bridge is retained by the gateway (and, once registered, by
        // the engine's listener list); events arrive on the engine's
        // dedicated dispatch thread and only ever yield — cheap, total,
        // never blocking the dispatcher.
        let bridge = ListenerBridge { snapshot in
            continuation.yield(Self.map(snapshot))
        }
        listenerBridge = bridge
        _ = try engine.addEventListener(listener: bridge)
    }

    deinit {
        // The stream must outlive the gateway's consumers deterministically:
        // finishing on deinit guarantees a parked `for await` wakes.
        snapshotsContinuation.finish()
    }

    var snapshots: AsyncStream<PlaybackSnapshot> { snapshotsStream }

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
        snapshotsContinuation.finish()
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

    /// Static scope on purpose: the generated namespace function
    /// `searchTracks(query:limit:)` and the gateway's instance method
    /// share a base name — inside a static function only the generated
    /// top-level function is in scope, so resolution is unambiguous.
    private static func performSearchTracks(query: String, limit: Int) throws -> SearchResult {
        try searchTracks(query: query, limit: UInt32(max(1, limit)))
    }

    private static func map(_ snapshot: EngineSnapshot) -> PlaybackSnapshot {
        let clock = snapshot.clock.map { clock in
            DacClockSample(
                framePosition: Int(clock.dacFramePosition),
                sampleRateHz: Int(clock.sampleRate),
                monotonicNs: Int(clock.monotonicNs)
            )
        }
        return PlaybackSnapshot(
            rev: Int(snapshot.rev),
            status: map(snapshot.state),
            volume: Double(snapshot.volume),
            buffer: BufferHealth(
                bufferedDurationMs: Int(snapshot.bufferStats.bufferedDurationMs),
                underrunCount: Int(snapshot.bufferStats.underrunCount),
                fillRatio: Double(snapshot.bufferStats.fillRatio)
            ),
            clock: clock,
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
