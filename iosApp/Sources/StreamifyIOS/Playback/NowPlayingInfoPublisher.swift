import Foundation
import MediaPlayer

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): lockscreen / now-playing publishing with the
// directive's 1 Hz rate limit.
//
// MPNowPlayingInfoCenter updates are the most expensive per-frame surface on
// iOS (each apply re-renders the system lockscreen over MediaPlayer IPC), so
// the directive mandates: "Rate-limit lockscreen and now-playing updates to
// 1 Hz." This file implements that discipline as three small pieces:
//
//  * `OneHertzGate` — a PURE admission gate over an injectable clock: the
//    first value always admits, everything inside the trailing 1 s window
//    defers, the boundary re-opens. Value type, zero mocks, fully testable.
//  * `NowPlayingInfoPublisher` — coalesces engine pushes into
//    MPNowPlayingInfoCenter applies THROUGH the gate, with a trailing-edge
//    flush so the LAST state of a burst (e.g. the paused snapshot) always
//    lands — a leading-edge-only limiter would freeze the lockscreen on a
//    stale elapsed time forever.
//  * `RemoteCommandAdapter` — routes the lockscreen/control-center
//    transport controls onto the SAME PlayerCommand plane as the in-app UI
//    and the audio-session reactions (MP-UI02): a lockscreen pause and a
//    space-bar pause are indistinguishable by the time they reach the engine.
// ─────────────────────────────────────────────────────────────────────────────

/// Pure 1 Hz admission gate. `nowNs` comes from an injectable clock so the
/// full window semantics are unit-testable without timers.
struct OneHertzGate: Sendable {

    /// The directive's rate limit: one emission per second.
    static let intervalNs: UInt64 = 1_000_000_000

    private let intervalNs: UInt64
    private let lastEmitNs: UInt64?

    init(intervalNs: UInt64 = OneHertzGate.intervalNs, lastEmitNs: UInt64? = nil) {
        self.intervalNs = intervalNs
        self.lastEmitNs = lastEmitNs
    }

    /// The admission decision for a value arriving at `nowNs` (pure).
    func admits(nowNs: UInt64) -> Bool {
        guard let lastEmitNs else { return true }
        return nowNs - lastEmitNs >= intervalNs
    }

    /// The state after admitting an emission at `nowNs`.
    func admitting(nowNs: UInt64) -> OneHertzGate {
        OneHertzGate(intervalNs: intervalNs, lastEmitNs: nowNs)
    }
}

/// The lockscreen info surface the publisher applies to (the real center is
/// `MPNowPlayingInfoCenter.default()`; tests install a recording stub).
@MainActor
protocol NowPlayingInfoApplying: AnyObject {
    func applyNowPlayingInfo(_ info: [String: Any])
}

/// Production sink: one line over the system center.
@MainActor
final class SystemNowPlayingInfoCenter: NowPlayingInfoApplying {
    func applyNowPlayingInfo(_ info: [String: Any]) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

/// Coalescing 1 Hz publisher onto the lockscreen / now-playing surface.
@MainActor
final class NowPlayingInfoPublisher {

    private let center: NowPlayingInfoApplying
    private let clock: () -> UInt64
    private var gate: OneHertzGate
    private var pendingInfo: [String: Any]?
    private var flushTask: Task<Void, Never>?

    /// The rate-limit window in nanoseconds (1 Hz in production; tests
    /// inject a short window so the trailing-edge flush is verifiable
    /// without sleeping a full second).
    private let intervalNs: UInt64

    init(
        center: NowPlayingInfoApplying = SystemNowPlayingInfoCenter(),
        clock: @escaping () -> UInt64 = { DispatchTime.now().uptimeNanoseconds },
        intervalNs: UInt64 = OneHertzGate.intervalNs
    ) {
        self.center = center
        self.clock = clock
        self.intervalNs = intervalNs
        self.gate = OneHertzGate(intervalNs: intervalNs)
    }

    /// Offers the latest UI state to the lockscreen; applies immediately
    /// when the gate admits, otherwise stores the value as the pending
    /// trailing edge and schedules the boundary flush.
    func publish(state: PlaybackUiState) {
        publish(info: NowPlayingInfoPublisher.infoDict(for: state))
    }

    func publish(info: [String: Any]) {
        let now = clock()
        if gate.admits(nowNs: now) {
            pendingInfo = nil
            flushTask?.cancel()
            flushTask = nil
            gate = gate.admitting(nowNs: now)
            center.applyNowPlayingInfo(info)
        } else {
            pendingInfo = info
            schedulePendingFlush()
        }
    }

    /// Clears the lockscreen surface (playback stopped / teardown).
    func clear() {
        pendingInfo = nil
        flushTask?.cancel()
        flushTask = nil
        center.applyNowPlayingInfo([:])
    }

    private func schedulePendingFlush() {
        guard flushTask == nil else { return }
        let windowNs = intervalNs
        flushTask = Task { [weak self] in
            // Sleep to just past the boundary; the gate re-opens there.
            try? await Task.sleep(nanoseconds: windowNs)
            guard let self, !Task.isCancelled else { return }
            self.flushTask = nil
            if let pending = self.pendingInfo {
                self.publish(info: pending)
            }
        }
    }

    // MARK: - Pure info-dict derivation (unit-tested)

    /// Builds the MPNowPlayingInfoCenter dictionary from one UI state.
    /// Absent values are OMITTED, never fabricated (no track -> no title
    /// key; no clock -> no elapsed key; the system renders its own
    /// placeholders for missing keys).
    nonisolated static func infoDict(for state: PlaybackUiState) -> [String: Any] {
        var info: [String: Any] = [:]
        if let track = state.currentTrack {
            info[MPMediaItemPropertyTitle] = track.title
            info[MPMediaItemPropertyArtist] = track.artist
            if let durationMs = track.durationMs {
                info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000.0
            }
        }
        if let positionMs = state.positionMs {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
                Double(positionMs) / 1000.0
        }
        info[MPNowPlayingInfoPropertyPlaybackRate] = state.isPlaying ? 1.0 : 0.0
        return info
    }
}

/// The lockscreen transport intents, on the same plane as in-app intent.
enum RemoteCommand: Equatable, Sendable {
    case play
    case pause
    case togglePlayPause
    case next
    case previous
    case seek(positionMs: Int)
}

/// Anything the lockscreen transport controls can drive (the shell's
/// PlaybackViewModel conforms; tests install a recording stub).
@MainActor
protocol RemoteCommandRouting: AnyObject {
    func remoteCommandReceived(_ command: RemoteCommand)
}

/// Installs the lockscreen / Control Center transport handlers
/// (MPRemoteCommandCenter) and routes them onto [RemoteCommandRouting].
/// Activation and deactivation are idempotent; observer tokens are removed
/// exactly once on teardown. MainActor-isolated (MediaPlayer's command
/// center is main-queue machinery); the handlers hop through a
/// `Task { @MainActor }` so the synchronous .success return never blocks.
@MainActor
final class RemoteCommandAdapter {

    private let router: RemoteCommandRouting
    private var targets: [(command: MPRemoteCommand, target: Any)] = []

    init(router: RemoteCommandRouting) {
        self.router = router
    }

    func activate() {
        guard targets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()

        func bind(
            _ command: MPRemoteCommand,
            _ action: @escaping () -> RemoteCommand
        ) {
            let target = command.addTarget { [router] _ in
                let intent = action()
                Task { @MainActor in router.remoteCommandReceived(intent) }
                return .success
            }
            targets.append((command, target))
        }

        bind(center.playCommand) { .play }
        bind(center.pauseCommand) { .pause }
        bind(center.togglePlayPauseCommand) { .togglePlayPause }
        bind(center.nextTrackCommand) { .next }
        bind(center.previousTrackCommand) { .previous }

        let seekTarget = center.changePlaybackPositionCommand.addTarget { [router] event in
            guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            let positionMs = Int(positionEvent.positionTime * 1000.0)
            Task { @MainActor in router.remoteCommandReceived(.seek(positionMs: positionMs)) }
            return .success
        }
        targets.append((center.changePlaybackPositionCommand, seekTarget))
    }

    func deactivate() {
        for (command, target) in targets {
            command.removeTarget(target)
        }
        targets.removeAll()
    }
}
