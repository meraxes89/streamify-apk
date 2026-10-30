import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// The always-available engine lane: a deterministic preview stand-in for
// SwiftUI previews, XCTest, and the pre-xcframework scaffold period.
// Emits scripted snapshots on a timer-free cadence (a background Task with
// a one-second tick — visible state movement for previews; tests drive
// pushes by hand via `push`).
//
// SW-ENG1-04 (this revision): the preview gateway speaks the FULL typed
// data lane (search / queue / equalizer / jam room) over a deterministic
// fixture catalog — Engineer 2 can exercise every SwiftUI surface in
// Xcode previews without compiling the Rust dylib, and Engineer 4's
// ViewModels run unchanged against this double in tests.
// ─────────────────────────────────────────────────────────────────────────────

final class PreviewEngineGateway: EngineGateway {

    /// The error surfaced by the failable surface when `failAll` is armed
    /// (tests script the failure path).
    struct UnavailableError: LocalizedError {
        var errorDescription: String? { "preview engine lane is scripted to fail" }
    }

    /// Deterministic preview catalog — every search filters THIS list, so
    /// previews are reproducible frame-for-frame.
    static let fixtureCatalog: [EngineTrack] = [
        EngineTrack(id: "dQw4w9WgXcQ", title: "Nightcall", artist: "Kavinsky", album: "OutRun", durationMs: 252_000, thumbnailUrl: "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"),
        EngineTrack(id: "preview01_aaa", title: "Midnight City", artist: "M83", album: "Hurry Up, We're Dreaming", durationMs: 244_000, thumbnailUrl: "https://i.ytimg.com/vi/preview01/mqdefault.jpg"),
        EngineTrack(id: "preview02_aaa", title: "Sunset Lover", artist: "Petit Biscuit", album: "Presence", durationMs: 234_000),
        EngineTrack(id: "preview03_aaa", title: "Innerbloom", artist: "RÜFÜS DU SOL", album: "Bloom", durationMs: 582_000),
        EngineTrack(id: "preview04_aaa", title: "Crave You", artist: "Flight Facilities", album: "Down to Earth", durationMs: 229_000, isExplicit: false),
        EngineTrack(id: "preview05_aaa", title: "Genesis", artist: "Grimes", album: "Visions", durationMs: 280_000, isExplicit: true),
        EngineTrack(id: "preview06_aaa", title: "Windowlicker", artist: "Aphex Twin", album: "Windowlicker", durationMs: 366_000),
        EngineTrack(id: "preview07_aaa", title: "Teardrop", artist: "Massive Attack", album: "Mezzanine", durationMs: 331_000),
        EngineTrack(id: "preview08_aaa", title: "Alpha Beta Gaga", artist: "Air", album: "Talkie Walkie", durationMs: 254_000),
        EngineTrack(id: "preview09_aaa", title: "Hold On, We're Going Home", artist: "Drake", album: "Nothing Was the Same", durationMs: 237_000, isExplicit: true),
        EngineTrack(id: "preview10_aaa", title: "Electric Feel", artist: "MGMT", album: "Oracular Spectacular", durationMs: 229_000),
        EngineTrack(id: "preview11_aaa", title: "Breathe", artist: "Télépopmusik", album: "Genetic World", durationMs: 254_000),
    ]

    /// The scripted playlist rail every non-empty search returns.
    static let fixturePlaylist = EnginePlaylistSummary(
        id: "preview_playlist_aaa",
        title: "Preview Lane Mixtape",
        thumbnailUrl: "https://i.ytimg.com/vi/preview_playlist/mqdefault.jpg",
        trackCount: 12
    )

    // ── snapshot push plane (unchanged shape) ─────────────────────────────

    private let lock = NSLock()
    private var state = PlaybackSnapshot.idle
    private var continuation: AsyncStream<PlaybackSnapshot>.Continuation?
    private var cachedSnapshots: AsyncStream<PlaybackSnapshot>?
    private var demoTask: Task<Void, Never>?

    private var _dispatchedCommands: [PlayerCommand] = []
    var dispatchedCommands: [PlayerCommand] {
        lock.withLock { _dispatchedCommands }
    }
    var nextResult: PlayerCommandResult = .applied
    var failAll = false

    var snapshots: AsyncStream<PlaybackSnapshot> {
        // One shared stream until shutdown finishes it (mirrors the real
        // gateway's single-bridge discipline); a shutdown → start cycle
        // rebuilds on the next access.
        lock.withLock {
            if let cached = cachedSnapshots {
                return cached
            }
            var continuation: AsyncStream<PlaybackSnapshot>.Continuation!
            let stream = AsyncStream(bufferingPolicy: .bufferingNewest(1)) {
                continuation = $0
            }
            self.continuation = continuation
            self.cachedSnapshots = stream
            continuation.yield(state)
            return stream
        }
    }

    private func recordCommand(_ command: PlayerCommand) {
        lock.withLock {
            _dispatchedCommands.append(command)
        }
    }

    func send(_ command: PlayerCommand) async -> PlayerCommandResult {
        recordCommand(command)
        return failAll ? .failed : nextResult
    }

    func currentSnapshot() async -> PlaybackSnapshot? {
        guard !failAll else { return nil }
        return lock.withLock { state }
    }

    func createJamSession(name: String) async -> String? {
        guard !failAll else { return nil }
        lock.withLock { jamRoomCode = name }
        return name
    }

    func jamStateHash() async -> String? {
        failAll
            ? nil
            : "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    func shutdown() {
        demoTask?.cancel()
        lock.withLock {
            continuation?.finish()
            continuation = nil
            cachedSnapshots = nil
        }
    }

    deinit {
        // A parked `for await` on a de-allocated preview gateway must wake.
        continuation?.finish()
    }

    /// Test/preview helper: hand-push a snapshot.
    func push(_ snapshot: PlaybackSnapshot) {
        let continuation = lock.withLock {
            state = snapshot
            return self.continuation
        }
        continuation?.yield(snapshot)
    }

    // ── SW-ENG1-04: the typed data lane (deterministic fixtures) ──────────

    /// Scripted Jam room (created on demand via `createJamSession`).
    private var jamRoomCode: String?

    /// The staged queue mirror (indices assigned on append).
    private var stagedQueue: [EngineQueueItem] = []

    /// The mutable EQ curve (flat preset until a test/preview edits it).
    private var eqCurve: [EngineEqualizerBand] = EngineEqualizerBand.defaultCurve()

    func searchTracks(_ query: String, limit: Int) async throws -> EngineSearchResult {
        guard !failAll else { throw UnavailableError() }
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return EngineSearchResult() }
        let lowered = clean.lowercased()
        let matches = Self.fixtureCatalog.filter { track in
            track.title.lowercased().contains(lowered)
                || track.artist.lowercased().contains(lowered)
                || track.album.lowercased().contains(lowered)
        }
        let capped = Array(matches.prefix(max(1, limit)))
        return EngineSearchResult(
            tracks: capped,
            playlists: capped.isEmpty ? [] : [Self.fixturePlaylist],
            continuationToken: capped.isEmpty ? nil : "preview-continuation-\(lowered)"
        )
    }

    func enqueue(track: EngineTrack, enqueuedBy: String) async -> Int? {
        guard !failAll else { return nil }
        return lock.withLock {
            let index = stagedQueue.count
            stagedQueue.append(
                EngineQueueItem(
                    index: index,
                    track: track,
                    enqueuedBy: enqueuedBy.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? "local"
                        : enqueuedBy
                )
            )
            // Visible preview movement: the freshly enqueued track becomes
            // the now-playing surface's track on the next push.
            state.currentTrack = PresentationTrack(track)
            let continuation = self.continuation
            continuation?.yield(state)
            return index
        }
    }

    func queue() async -> [EngineQueueItem] {
        guard !failAll else { return [] }
        return lock.withLock { stagedQueue }
    }

    func clearQueue() async {
        lock.withLock {
            stagedQueue.removeAll()
        }
    }

    func equalizerBands() async -> [EngineEqualizerBand] {
        guard !failAll else { return [] }
        return lock.withLock { eqCurve }
    }

    func setEqualizerBand(_ band: EngineEqualizerBand) async {
        lock.withLock {
            guard band.bandIndex >= 0, band.bandIndex < eqCurve.count else { return }
            eqCurve[band.bandIndex] = band
        }
    }

    func jamRoomState() async -> EngineJamRoomState? {
        guard !failAll else { return nil }
        return lock.withLock {
            guard let code = jamRoomCode else { return nil }
            return EngineJamRoomState(
                roomCode: code,
                isHost: true,
                syncOffsetMs: 0,
                participants: ["local:preview", "preview-peer:1"]
            )
        }
    }

    // ── scripted demo ─────────────────────────────────────────────────────

    /// Preview helper: starts a scripted 1 Hz demo (status/volume/position
    /// movement — pure UI exercise, clearly not engine truth).
    func startDemo() {
        guard demoTask == nil else { return }
        demoTask = Task { [weak self] in
            // Local to the task's closure: a captured-and-mutated outer var
            // is a data race in concurrently-executing code (compile error
            // under strict concurrency; a local is exactly as deterministic).
            var rev = 1
            while !Task.isCancelled {
                guard let self else { return }
                let playing = rev % 8 != 0
                let snapshot = PlaybackSnapshot(
                    rev: rev,
                    status: playing ? .playing : .buffering,
                    volume: 0.7,
                    buffer: BufferHealth(
                        bufferedDurationMs: 12_000,
                        underrunCount: rev / 20,
                        fillRatio: 0.9
                    ),
                    clock: DacClockSample(
                        framePosition: 48_000 * rev,
                        sampleRateHz: 48_000,
                        monotonicNs: rev * 1_000_000_000
                    )
                )
                self.push(snapshot)
                rev += 1
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}
