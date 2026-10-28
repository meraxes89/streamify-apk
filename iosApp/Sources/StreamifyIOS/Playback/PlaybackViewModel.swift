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
final class PlaybackViewModel: ObservableObject, AudioSessionResponding {

    @Published private(set) var state = PlaybackUiState.idle

    /// Diagnostics: command dispatch results, newest last (bounded).
    @Published private(set) var lastResult: PlayerCommandResult?

    let gateway: EngineGateway
    let audioSession: IOSAudioSessionAdapter

    private var collectTask: Task<Void, Never>?

    init(
        gateway: EngineGateway,
        audioSession: IOSAudioSessionAdapter? = nil
    ) {
        self.gateway = gateway
        // Default wiring: the adapter drives THIS holder's pause/resume.
        self.audioSession = audioSession ?? IOSAudioSessionAdapter(responder: self)
    }

    /// Cold-start hydration + push-flow collection. Idempotent.
    func start() {
        guard collectTask == nil else { return }
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
            }
            for await snapshot in self.gateway.snapshots {
                self.state = PlaybackUiState(snapshot)
            }
        }
    }

    /// Clean teardown: stops collection, deactivates the audio session
    /// (observer removal included), releases the gateway bridge.
    func stop() {
        collectTask?.cancel()
        collectTask = nil
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
        Task { [weak self] in
            guard let self else { return }
            self.lastResult = await self.gateway.send(command)
        }
    }

    // MARK: - AudioSessionResponding (MP-UI02 reactions)

    func audioSessionRequestsPause(reason: String) {
        dispatch(.pause)
    }

    func audioSessionRequestsResume(reason: String) {
        dispatch(.play)
    }
}
