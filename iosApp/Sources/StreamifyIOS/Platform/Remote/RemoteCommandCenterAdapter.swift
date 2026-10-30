import Foundation
import MediaPlayer

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-03 (Phase 5 / MP-CAP03 / MP-DEC-07): the system media remote
// command center adapter — lock screen, Control Center, headset, AirPlay
// and CarPlay steering-wheel transport controls lowered onto the shell's
// `PlayerCommand` plane.
//
// The reverse control plane is BI-DIRECTIONAL with hardware debounce
// (MP-DEC-07): headset toggles, car-knob presses and lock screen taps can
// double-fire (Bluetooth HID repeats, bumpy-road Siri remote bounces) —
// every DISCRETE command passes through a 50-150 ms leading-edge admission
// gate (`MediaCommandGate`) that swallows an identical re-arrival inside
// the window while a DIFFERENT intent never waits. Seek events (scrubbing
// fires a stream of positions) get a TRAILING-edge debounce: only the LAST
// position inside the window reaches the engine — the same discipline
// Android's `MediaCommandDispatcher` (the reference implementation) calls
// `DEFAULT_DEBOUNCE_WINDOW_MS = 100`.
//
// The command plane is shared with the in-app UI and the audio-session
// reactions: a lockscreen pause, a space-bar pause and an
// interruption-began pause are the same `PlayerCommand.pause` by the time
// they reach the engine (EngineGateway.send — the engine re-applies its
// own 50-150 ms duplicate debounce as the final gate).
//
// Like/dislike (MPFeedbackCommand) surface as `RemoteFeedback` — they are
// shell-level concerns (library curation), not engine transport commands.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - The command plane (moved from NowPlayingInfoPublisher.swift —
// the lockscreen transport intents, on the same plane as in-app intent)

/// The lockscreen / system transport intents, on the same plane as
/// in-app intent.
enum RemoteCommand: Equatable, Sendable {
    case play
    case pause
    case togglePlayPause
    case next
    case previous
    case seek(positionMs: Int)
}

/// Feedback intents from the system surfaces (likeCommand / dislikeCommand).
enum RemoteFeedback: Equatable, Sendable {
    case like
    case dislike
}

/// Anything the system transport controls can drive (the shell's
/// PlaybackViewModel conforms; tests install a recording stub).
@MainActor
protocol RemoteCommandRouting: AnyObject {
    func remoteCommandReceived(_ command: RemoteCommand)
}

extension RemoteCommandRouting {
    /// Feedback (like/dislike) defaults to a no-op: only the shell surfaces
    /// that consume curation intents implement it.
    func remoteFeedbackReceived(_ feedback: RemoteFeedback) {}
}

// MARK: - Debounce policy (MP-DEC-07's approved envelope)

/// The approved hardware debounce envelope, mirrored from Android's
/// `MediaCommandDispatcher` (the reference implementation).
enum MediaCommandDebouncePolicy {
    /// Lower bound of the MP-DEC-07 debounce envelope (ms).
    static let minWindowMs: Int = 50
    /// Upper bound of the MP-DEC-07 debounce envelope (ms).
    static let maxWindowMs: Int = 150
    /// Default window (ms) — the reference implementation's value.
    static let defaultWindowMs: Int = 100

    static let minWindowNs: UInt64 = 50_000_000
    static let maxWindowNs: UInt64 = 150_000_000
    static let defaultWindowNs: UInt64 = 100_000_000

    /// Clamps an arbitrary window request into the approved envelope.
    static func clampedWindowNs(_ ns: UInt64) -> UInt64 {
        min(max(ns, minWindowNs), maxWindowNs)
    }
}

// MARK: - The pure admission gate (unit-tested, zero timers)

/// Leading-edge admission gate with duplicate suppression (MP-DEC-07):
///
///  * the FIRST arrival of any command admits immediately — genuine user
///    intent never waits behind a window;
///  * an IDENTICAL command re-arriving inside the window is swallowed —
///    the double-fire of a Bluetooth headset toggle, a bumpy steering-wheel
///    knob, or a lock screen tap handled by two subsystems;
///  * a DIFFERENT command admits immediately (windows are per-command,
///    not global — a pause right after a play is a real intent).
///
/// Value type over an injectable clock: the full window semantics are
/// unit-testable without timers, threads, or hardware.
struct MediaCommandGate<Command: Equatable> {

    let windowNs: UInt64
    private let lastCommand: Command?
    private let lastAdmittedNs: UInt64?

    init(
        windowNs: UInt64 = MediaCommandDebouncePolicy.defaultWindowNs,
        lastCommand: Command? = nil,
        lastAdmittedNs: UInt64? = nil
    ) {
        self.windowNs = windowNs
        self.lastCommand = lastCommand
        self.lastAdmittedNs = lastAdmittedNs
    }

    /// The admission decision for `command` arriving at `nowNs` (pure).
    func admits(_ command: Command, nowNs: UInt64) -> Bool {
        guard let lastCommand, let lastAdmittedNs else { return true }
        if command != lastCommand { return true }
        guard nowNs >= lastAdmittedNs else { return true }  // clock rewind
        return nowNs - lastAdmittedNs >= windowNs
    }

    /// The state after ADMITTING `command` at `nowNs`.
    func admitting(_ command: Command, nowNs: UInt64) -> MediaCommandGate {
        MediaCommandGate(
            windowNs: windowNs,
            lastCommand: command,
            lastAdmittedNs: nowNs
        )
    }
}

// MARK: - The adapter

/// Installs the `MPRemoteCommandCenter` transport handlers and routes them
/// through the MP-DEC-07 debounce onto [RemoteCommandRouting]. Activation
/// and deactivation are idempotent; command targets are removed exactly
/// once on teardown. MainActor-isolated (MediaPlayer's command center is
/// main-queue machinery); the synchronous `.success` return never blocks —
/// the routed dispatch hops through the routing protocol synchronously on
/// the main actor.
@MainActor
final class RemoteCommandCenterAdapter {

    private let router: RemoteCommandRouting
    /// The MP-DEC-07 debounce window, clamped into the approved envelope.
    let windowNs: UInt64
    private let clock: () -> UInt64
    private var gate: MediaCommandGate<RemoteCommand>
    /// Trailing-edge seek task: cancelled-and-replaced on every scrub event
    /// so only the LAST position inside the window reaches the engine.
    private var seekTask: Task<Void, Never>?
    private var targets: [(command: MPRemoteCommand, target: Any)] = []
    private(set) var isActive = false

    init(
        router: RemoteCommandRouting,
        windowNs: UInt64 = MediaCommandDebouncePolicy.defaultWindowNs,
        clock: @escaping () -> UInt64 = { DispatchTime.now().uptimeNanoseconds }
    ) {
        self.router = router
        self.windowNs = MediaCommandDebouncePolicy.clampedWindowNs(windowNs)
        self.clock = clock
        self.gate = MediaCommandGate<RemoteCommand>(windowNs: self.windowNs)
    }

    // MARK: - Lifecycle

    func activate() {
        guard targets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()

        func bind(
            _ command: MPRemoteCommand,
            _ action: @escaping () -> RemoteCommand
        ) {
            let target = command.addTarget { [weak self] _ in
                guard let self else { return .commandFailed }
                _ = self.receive(action())
                // A swallowed duplicate still acknowledges success — the
                // user pressed a real button; the engine simply already
                // has the intent (MP-DEC-07's "debounced is a success
                // path" contract).
                return .success
            }
            targets.append((command, target))
        }

        bind(center.playCommand) { .play }
        bind(center.pauseCommand) { .pause }
        bind(center.togglePlayPauseCommand) { .togglePlayPause }
        bind(center.nextTrackCommand) { .next }
        bind(center.previousTrackCommand) { .previous }

        // Seeking: trailing-edge debounce — rapid scrub events coalesce to
        // the LAST position; the gate additionally swallows identical
        // re-fires of the same position.
        let seekTarget = center.changePlaybackPositionCommand.addTarget {
            [weak self] event in
            guard let self,
                  let positionEvent = event as? MPChangePlaybackPositionCommandEvent
            else { return .commandFailed }
            let positionMs = Int(positionEvent.positionTime * 1000.0)
            self.dispatchSeek(positionMs: positionMs)
            return .success
        }
        targets.append((center.changePlaybackPositionCommand, seekTarget))

        // Feedback (like / dislike): MPFeedbackCommand must be MARKED ACTIVE
        // or the system surfaces grey the buttons out entirely.
        let likeCommand = center.likeCommand
        likeCommand.isActive = true
        likeCommand.localizedTitle = "Like"
        let likeTarget = likeCommand.addTarget { [weak self] _ in
            self?.router.remoteFeedbackReceived(.like)
            return .success
        }
        targets.append((likeCommand, likeTarget))

        let dislikeCommand = center.dislikeCommand
        dislikeCommand.isActive = true
        dislikeCommand.localizedTitle = "Dislike"
        let dislikeTarget = dislikeCommand.addTarget { [weak self] _ in
            self?.router.remoteFeedbackReceived(.dislike)
            return .success
        }
        targets.append((dislikeCommand, dislikeTarget))

        isActive = true
    }

    func deactivate() {
        seekTask?.cancel()
        seekTask = nil
        for (command, target) in targets {
            command.removeTarget(target)
        }
        targets.removeAll()
        isActive = false
    }

    // MARK: - Debounce seams (exposed for the white-box test lane)

    /// The single funnel every MPRemoteCommandCenter target drives: the
    /// leading-edge admission gate over the injectable clock; routes on
    /// admission. Returns false when the command was swallowed as a
    /// duplicate inside the window.
    @discardableResult
    func receive(_ command: RemoteCommand) -> Bool {
        let now = clock()
        guard gate.admits(command, nowNs: now) else { return false }
        gate = gate.admitting(command, nowNs: now)
        router.remoteCommandReceived(command)
        return true
    }

    /// The leading-edge admission decision for a discrete command (pure —
    /// no admission is recorded).
    func admits(_ command: RemoteCommand, nowNs: UInt64) -> Bool {
        gate.admits(command, nowNs: nowNs)
    }

    /// Trailing-edge seek debounce: rapid scrub events coalesce — only the
    /// LAST position inside the window reaches the engine. An IDENTICAL
    /// position re-firing inside the window is swallowed entirely (the
    /// pending trailing task carries exactly this intent, or it already
    /// landed).
    func dispatchSeek(positionMs: Int) {
        let intent = RemoteCommand.seek(positionMs: positionMs)
        let now = clock()
        guard gate.admits(intent, nowNs: now) else { return }
        gate = gate.admitting(intent, nowNs: now)
        seekTask?.cancel()
        let windowNs = self.windowNs
        seekTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: windowNs)
            guard !Task.isCancelled, let self else { return }
            self.seekTask = nil
            self.router.remoteCommandReceived(intent)
        }
    }

    /// Flushes any pending trailing-edge seek immediately (state
    /// transitions want the seek landed NOW, not one window later).
    func flushPendingSeek() {
        guard let task = seekTask else { return }
        task.cancel()
        seekTask = nil
    }
}
