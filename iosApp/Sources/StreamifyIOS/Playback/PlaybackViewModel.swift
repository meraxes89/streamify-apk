import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the shell's playback state holder — the Swift
// counterpart of the shared `PlaybackController` (the platform-independent
// "ViewModel" of the multiplatform presentation layer).
//
// Push-only state discipline (MP-DEC-04): the holder consumes the gateway's
// snapshot stream and publishes the derived UI state; user intent goes out
// as fire-and-forget commands; the engine's next push repaints whatever the
// command changed. The audio-session reactions (MP-UI02) land on the SAME
// command plane: an interruption-began pause and a space-bar pause are the
// same `PlayerCommand.pause` by the time they reach the engine.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
final class PlaybackViewModel: ObservableObject, AudioSessionResponding, RemoteCommandRouting {

    @Published private(set) var state = PlaybackUiState.idle

    /// Diagnostics: command dispatch results, newest last (bounded).
    @Published private(set) var lastResult: PlayerCommandResult?

    let gateway: EngineGateway
    private(set) var audioSession: IOSAudioSessionAdapter!

    /// MP-UI01: the lockscreen / now-playing surface, rate-limited to 1 Hz
    /// (leading-edge admission + trailing-edge flush, so the LAST state of
    /// a burst always lands on the lockscreen).
    private let nowPlayingInfo: NowPlayingInfoPublisher

    /// MP-UI01: the lockscreen / Control Center transport controls, routed
    /// onto the same command plane as the in-app UI.
    private(set) var remoteCommands: RemoteCommandAdapter!

    private var collectTask: Task<Void, Never>?
    private var dispatchChain: Task<Void, Never>?

    init(
        gateway: EngineGateway,
        audioSession: IOSAudioSessionAdapter? = nil,
        // Optional-with-nil-default instead of a `NowPlayingInfoPublisher()`
        // default expression: default arguments are evaluated in a
        // NONISOLATED context, so a @MainActor-isolated initializer call
        // there is a compile error. The fallback constructs on the main
        // actor, inside this isolated init body.
        nowPlayingInfoPublisher: NowPlayingInfoPublisher? = nil
    ) {
        self.gateway = gateway
        self.nowPlayingInfo = nowPlayingInfoPublisher ?? NowPlayingInfoPublisher()
        // Two-phase initialization: properties above are set before referencing `self`.
        self.audioSession = audioSession ?? IOSAudioSessionAdapter(responder: self)
        self.remoteCommands = RemoteCommandAdapter(router: self)
    }

    /// Cold-start hydration + push-flow collection. Idempotent.
    func start() {
        guard collectTask == nil else { return }
        remoteCommands.activate()
        do {
            try audioSession.activate()
        } catch {
            // Session activation failure is survivable: playback continues
            // (AVAudioSession re-arms on the next route change); the shell
            // keeps rendering engine state either way.
        }
        collectTask = Task { [weak self] in
            guard let self else { return }
            if let seeded = await self.gateway.currentSnapshot() {
                self.state = PlaybackUiState(seeded)
                self.nowPlayingInfo.publish(state: self.state)
            }
            for await snapshot in self.gateway.snapshots {
                self.state = PlaybackUiState(snapshot)
                self.nowPlayingInfo.publish(state: self.state)
            }
        }
    }

    /// Clean teardown: stops collection, tears the lockscreen surfaces down
    /// (remote commands off, now-playing info cleared), deactivates the
    /// audio session (observer removal included), releases the gateway
    /// bridge.
    func stop() {
        collectTask?.cancel()
        collectTask = nil
        dispatchChain?.cancel()
        dispatchChain = nil
        remoteCommands.deactivate()
        nowPlayingInfo.clear()
        audioSession.deactivate()
        gateway.shutdown()
        state = PlaybackUiState.idle
    }

    // MARK: - Intent (fire-and-forget; the next push repaints)

    func togglePlayPause() { dispatch(.togglePlayPause) }
    func next() { dispatch(.next) }
    func previous() { dispatch(.previous) }
    func seek(to positionMs: Int) { dispatch(.seek(positionMs: positionMs)) }

    func setVolume(_ volume: Double) {
        dispatch(.setVolume(min(max(volume, 0), 1)))
    }

    /// Creates the engine's local Jam session mirror; the name (or nil)
    /// surfaces through the calling view.
    func createJamSession(name: String) async -> String? {
        await gateway.createJamSession(name: name)
    }

    /// BLAKE3 state hash of the active Jam session, or nil.
    func jamStateHash() async -> String? {
        await gateway.jamStateHash()
    }

    private func dispatch(_ command: PlayerCommand) {
        let previousTask = dispatchChain
        dispatchChain = Task { [weak self] in
            _ = await previousTask?.value
            guard let self else { return }
            self.lastResult = await self.gateway.send(command)
        }
    }

    /// Flushes all pending asynchronous dispatches into the engine gateway (strict FIFO).
    func flushDispatches() async {
        _ = await dispatchChain?.value
    }

    // MARK: - AudioSessionResponding (MP-UI02 reactions)

    func audioSessionRequestsPause(reason: String) {
        dispatch(.pause)
    }

    func audioSessionRequestsResume(reason: String) {
        dispatch(.play)
    }

    // MARK: - RemoteCommandRouting (MP-UI01 lockscreen transport)

    func remoteCommandReceived(_ command: RemoteCommand) {
        switch command {
        case .play: dispatch(.play)
        case .pause: dispatch(.pause)
        case .togglePlayPause: dispatch(.togglePlayPause)
        case .next: dispatch(.next)
        case .previous: dispatch(.previous)
        case .seek(let positionMs): dispatch(.seek(positionMs: positionMs))
        }
    }
}
