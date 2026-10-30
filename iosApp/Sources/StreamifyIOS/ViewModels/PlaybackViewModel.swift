import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-02 (Engineer 4): the playback transport state holder —
// the `@Observable @MainActor` successor of the Phase 5 `ObservableObject`
// scaffold (SW-DEC-02: no Combine, no @Published, no AnyCancellable bags).
//
// PUSH EVENT-DRIVEN STATE (MP-DEC-04): the holder consumes the gateway's
// snapshot stream — every snapshot carries a strictly monotonic `rev`, and
// stale revisions are DROPPED at this boundary, so out-of-order pushes (a
// burst coalesced across the FFI) can never rewind the UI. User intent goes
// out as serialized fire-and-forget commands; the engine's next push repaints
// whatever the command changed.
//
// 120 Hz PROGRESS (SW-ENG4-02): the fine-grained playhead lives in the
// SEPARATE `PlaybackProgressClock` observable (hardware DAC interpolation),
// so display-synced per-frame reads invalidate only scrub-bar leaves. This
// holder itself changes only when a pushed snapshot actually changes state.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class PlaybackViewModel: AudioSessionResponding, RemoteCommandRouting {

    // ── Push-fed transport state (snapshots only — never a getter poll) ────

    private(set) var status: PlaybackStatus = .idle
    private(set) var volume: Double = 0
    private(set) var buffer: BufferHealth?
    private(set) var currentTrack: PresentationTrack?
    private(set) var jamSessionName: String?
    private(set) var lastResult: PlayerCommandResult?

    /// The last raw hardware DAC sample pushed by the engine (diagnostics).
    private(set) var lastDacSample: DacClockSample?

    /// The highest engine revision ingested so far (stale drops happen below).
    private(set) var lastRev = 0

    /// The hardware-anchored interpolator feeding the scrub bar (SW-ENG4-02).
    let progress = PlaybackProgressClock()

    // ── Wiring (identical shape to the Phase 5 scaffold) ────────────────────

    let gateway: EngineGateway
    private(set) var audioSession: IOSAudioSessionAdapter!

    /// MP-UI01: the lockscreen / now-playing surface, rate-limited to 1 Hz
    /// (leading-edge admission + trailing-edge flush).
    private let nowPlayingInfo: NowPlayingInfoPublisher

    /// MP-UI01: the lockscreen / Control Center transport controls, routed
    /// onto the same command plane as the in-app UI.
    private(set) var remoteCommands: RemoteCommandAdapter!

    private var collectTask: Task<Void, Never>?
    private var dispatchChain: Task<Void, Never>?

    // MARK: - Init (two-phase: properties before self references)

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
        self.audioSession = audioSession ?? IOSAudioSessionAdapter(responder: self)
        self.remoteCommands = RemoteCommandAdapter(router: self)
    }

    // MARK: - Snapshot ingestion (rev-guarded push consumer)

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
                self.ingest(seeded)
            }
            for await snapshot in self.gateway.snapshots {
                guard let self else { return }
                self.ingest(snapshot)
            }
        }
    }

    /// Clean teardown (stops collection, tears the lockscreen surfaces down,
    /// deactivates the audio session, releases the gateway bridge).
    func stop() {
        collectTask?.cancel()
        collectTask = nil
        dispatchChain?.cancel()
        dispatchChain = nil
        remoteCommands.deactivate()
        nowPlayingInfo.clear()
        audioSession.deactivate()
        gateway.shutdown()
        progress.reset()
        status = .idle
        volume = 0
        buffer = nil
        currentTrack = nil
        jamSessionName = nil
        lastDacSample = nil
        lastRev = 0
    }

    /// One pushed snapshot applied atomically on the main actor — the single
    /// mutation point, which is what makes the transition race-free: bursts
    /// are serialized here in rev order, and anything out of order dies.
    private func ingest(_ snapshot: PlaybackSnapshot) {
        // Monotonic revision guard: a stale (or equal, re-delivered) push
        // can never repaint the UI — MP-DEC-04's `rev: u64` discipline.
        guard snapshot.rev > lastRev || lastRev == 0 else { return }
        let trackChanged = snapshot.currentTrack?.id != currentTrack?.id
        lastRev = snapshot.rev

        status = snapshot.status
        volume = snapshot.volume
        buffer = snapshot.buffer
        jamSessionName = snapshot.jamSessionName
        if snapshot.currentTrack != nil || trackChanged {
            currentTrack = snapshot.currentTrack
        }
        if trackChanged {
            progress.reset()
        }

        // The hardware clock anchor swap + optimistic-seek unlatch.
        lastDacSample = snapshot.clock
        progress.ingest(
            snapshot.clock,
            isPlaying: snapshot.status == .playing,
            durationMs: snapshot.currentTrack?.durationMs,
            hostUptimeNs: Self.hostUptimeNs
        )

        nowPlayingInfo.publish(state: state)
    }

    /// Host monotonic uptime in ns (the DAC interpolation time base; matches
    /// the engine's CLOCK_MONOTONIC epoch on Apple — ns since boot).
    static var hostUptimeNs: Int {
        Int(DispatchTime.now().uptimeNanoseconds)
    }

    // MARK: - Legacy projection (compat with the Phase 5 views/publishers)

    /// The flat `PlaybackUiState` projection (kept for the now-playing
    /// publisher and the scaffold's views; new views read the individual
    /// observable properties for fine-grained dependency tracking).
    var state: PlaybackUiState {
        PlaybackUiState(
            PlaybackSnapshot(
                rev: lastRev,
                status: status,
                volume: volume,
                buffer: buffer,
                clock: lastDacSample,
                currentTrack: currentTrack,
                jamSessionName: jamSessionName
            )
        )
    }

    // MARK: - Intent (fire-and-forget; the next push repaints)

    func togglePlayPause() { dispatch(.togglePlayPause) }
    func next() { dispatch(.next) }
    func previous() { dispatch(.previous) }

    func seek(to positionMs: Int) {
        // Optimistic seek admission (Android parity): the scrub bar jumps
        // immediately; the engine's pushed hardware position unlatches it.
        progress.registerSeek(positionMs: positionMs, hostUptimeNs: Self.hostUptimeNs)
        dispatch(.seek(positionMs: positionMs))
    }

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
