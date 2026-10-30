import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI02 / Beta): the iOS audio-session adapter — AVAudioSession
// governance for the native SwiftUI shell.
//
// SW-ENG1-01 (multiplatform note): AVAudioSession exists on iOS/Catalyst
// only — macOS has no audio-session concept (CoreAudio output is
// configured by the HAL, not a session object). The REAL adapter below
// compiles behind `#if canImport(UIKit)`; macOS gets a same-API no-op
// fallback at the bottom of this file so the unified StreamifyIOS module
// compiles for BOTH platforms with zero call-site changes (SW-DEC-04).
//
// Directive contract:
//  * CONFIGURE — category `.playback`, mode `.default`, options `[]` (an
//    audiophile music player mixes with nothing, ducks for nothing, and
//    keeps playing with the silent switch and the screen off).
//  * INTERRUPTION — `AVAudioSession.interruptionNotification`:
//      `.began`                    → pause the engine;
//      `.ended` + `.shouldResume`  → resume;
//      `.ended` without the option → stay paused (the user/system decided).
//  * ROUTE CHANGE — `AVAudioSession.routeChangeNotification`: the
//    `.oldDeviceUnavailable` reason (wired headphones unplugged, AirPlay
//    sink dropped) pauses playback — never blast the speaker after a
//    private-listening route vanishes; AirPlay teardowns land here too,
//    and the adapter deactivates its session bookkeeping cleanly.
//  * CLEAN TEARDOWN — `deactivate()` removes every observer exactly once
//    and is idempotent; the adapter never leaks NotificationCenter tokens
//    across scene or engine re-lifecycles.
//
// DESIGN: the decision core is a pair of PURE functions over value enums —
// `decision(forInterruption:)` / `decision(forRouteChange:)` — so the full
// policy matrix is unit-testable without an audio route (XCTest drives the
// real AVAudioSession value types; no mocks, no device). The observer
// plumbing around the core is thin, main-actor isolated, and testable via
// synthetic notification payloads (`interruptionPayload(type:options:)` /
// `routeChangePayload(reason:)` build the exact userInfo shapes AVFoundation
// posts).
// ─────────────────────────────────────────────────────────────────────────────

/// The playback-side reaction the adapter asks the shell to perform.
enum AudioSessionDecision: Equatable, Sendable {
    /// Pause the engine (interruption began, or the active route vanished).
    case pauseEngine

    /// Resume the engine (interruption ended with the resume option).
    case resumeEngine

    /// Nothing to do (informational events, category tweaks, new-device
    /// arrivals without a pause-worthy departure).
    case none
}

/// Anything that can serve the adapter's playback reactions (the shell's
/// PlaybackViewModel conforms; tests install a recording stub).
@MainActor
protocol AudioSessionResponding: AnyObject {
    func audioSessionRequestsPause(reason: String)
    func audioSessionRequestsResume(reason: String)
}

#if canImport(UIKit)
import AVFoundation

@MainActor
final class IOSAudioSessionAdapter: ObservableObject {

    /// Lifecycle state the shell can render.
    enum Lifecycle: Equatable, Sendable {
        case configured
        case active
        case deactivated
    }

    // MARK: - Pure decision core (unit-tested)

    /// Interruption policy: `.began` pauses; `.ended` resumes ONLY when the
    /// system advises `.shouldResume`.
    nonisolated static func decision(
        forInterruption type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions
    ) -> AudioSessionDecision {
        switch type {
        case .began:
            return .pauseEngine
        case .ended:
            return options.contains(.shouldResume) ? .resumeEngine : .none
        @unknown default:
            return .none
        }
    }

    /// Route-change policy: `.oldDeviceUnavailable` (headphones unplugged,
    /// AirPlay sink dropped) pauses; every other reason is informational.
    /// Written as a guard (not a switch) so SDK case additions can never
    /// break compilation — unknown reasons degrade to `.none`.
    nonisolated static func decision(
        forRouteChange reason: AVAudioSession.RouteChangeReason
    ) -> AudioSessionDecision {
        if reason == .oldDeviceUnavailable {
            return .pauseEngine
        }
        return .none
    }

    // MARK: - Payload builders (test-shaped userInfo)

    nonisolated static func interruptionPayload(
        type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions = []
    ) -> [AnyHashable: Any] {
        [
            AVAudioSessionInterruptionTypeKey: type.rawValue,
            AVAudioSessionInterruptionOptionKey: options.rawValue,
        ]
    }

    nonisolated static func routeChangePayload(
        reason: AVAudioSession.RouteChangeReason
    ) -> [AnyHashable: Any] {
        [
            AVAudioSessionRouteChangeReasonKey: reason.rawValue,
        ]
    }

    nonisolated static func parseInterruption(
        _ userInfo: [AnyHashable: Any]?
    ) -> (type: AVAudioSession.InterruptionType, options: AVAudioSession.InterruptionOptions)? {
        guard let userInfo else { return nil }
        guard let typeRaw = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return nil }
        let optionsRaw = (userInfo[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
        let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
        return (type, options)
    }

    nonisolated static func parseRouteChange(
        _ userInfo: [AnyHashable: Any]?
    ) -> AVAudioSession.RouteChangeReason? {
        guard let userInfo else { return nil }
        guard let reasonRaw = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonRaw) else { return nil }
        return reason
    }

    // MARK: - Adapter lifecycle

    private let session: AVAudioSession
    private weak var responder: AudioSessionResponding?
    private var observers: [NSObjectProtocol] = []
    private(set) var lifecycle: Lifecycle = .deactivated

    /// Last observed interruption/route state (diagnostics surface).
    @Published private(set) var lastInterruptionDescription: String?
    @Published private(set) var lastRouteChangeDescription: String?

    init(
        session: AVAudioSession = AVAudioSession.sharedInstance(),
        responder: AudioSessionResponding
    ) {
        self.session = session
        self.responder = responder
    }

    deinit {
        // deinit is nonisolated: drop the tokens synchronously — removal is
        // thread-safe, and a deinit-ing main-actor object cannot hop.
        let tokens = observers
        observers = []
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
    }

    /// Applies the directive configuration and installs the observers.
    func activate() throws {
        // Directive configuration: category .playback, mode .default,
        // options [] — no mixing, no ducking; playback survives the silent
        // switch and the locked screen.
        try session.setCategory(.playback, mode: .default, options: [])
        try session.setActive(true)
        installObservers()
        lifecycle = .active
    }

    /// Clean teardown: removes every observer exactly once and releases the
    /// session (`.notifyOthersOnDeactivation` intentionally NOT set — a
    //  music player that got interrupted by a call does not unpause the
    //  other app's media when it goes away).
    func deactivate() {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        if lifecycle == .active {
            try? session.setActive(false, options: [])
        }
        lifecycle = .deactivated
    }

    /// Re-binds the playback responder (engine re-lifecycle).
    func updateResponder(_ responder: AudioSessionResponding) {
        self.responder = responder
    }

    // MARK: - Observation

    private func installObservers() {
        guard observers.isEmpty else { return }

        let center = NotificationCenter.default

        // Interruptions arrive on an arbitrary queue: hop to the main
        // actor before touching adapter state or the responder.
        let interruptionToken = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            let parsed = Self.parseInterruption(note.userInfo)
            Task { @MainActor [weak self] in
                self?.handleInterruption(parsed: parsed)
            }
        }

        let routeToken = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            let reason = Self.parseRouteChange(note.userInfo)
            let previousDescription = Self.previousRouteDescription(from: note.userInfo)
            Task { @MainActor [weak self] in
                self?.handleRouteChange(reason: reason, previous: previousDescription)
            }
        }

        observers = [interruptionToken, routeToken]
    }

    @MainActor private func handleInterruption(
        parsed: (type: AVAudioSession.InterruptionType, options: AVAudioSession.InterruptionOptions)?
    ) {
        guard let parsed else { return }
        let decision = Self.decision(forInterruption: parsed.type, options: parsed.options)
        lastInterruptionDescription =
            "interruption \(parsed.type.rawValue), options \(parsed.options.rawValue) -> \(decision)"
        switch decision {
        case .pauseEngine:
            responder?.audioSessionRequestsPause(reason: "interruption-began")
        case .resumeEngine:
            responder?.audioSessionRequestsResume(reason: "interruption-ended-shouldResume")
        case .none:
            break
        }
    }

    @MainActor private func handleRouteChange(
        reason: AVAudioSession.RouteChangeReason?,
        previous: String?
    ) {
        guard let reason else { return }
        let decision = Self.decision(forRouteChange: reason)
        lastRouteChangeDescription =
            "route \(reason.rawValue) (was \(previous ?? "unknown")) -> \(decision)"
        switch decision {
        case .pauseEngine:
            // Headphones unplugged or the AirPlay sink dropped: pause, and
            // (teardown discipline) mark the session bookkeeping inactive
            // so a stale route cannot leak into the next activation.
            responder?.audioSessionRequestsPause(
                reason: "route-old-device-unavailable\(previous.map { " (\($0))" } ?? "")"
            )
            if lifecycle == .active {
                try? session.setActive(false, options: [])
                lifecycle = .configured
            }
        case .resumeEngine, .none:
            break
        }
    }

    nonisolated private static func previousRouteDescription(
        from userInfo: [AnyHashable: Any]?
    ) -> String? {
        guard let userInfo,
              let route = userInfo[AVAudioSessionRouteChangePreviousRouteKey]
                  as? AVAudioSessionRouteDescription else { return nil }
        return route.outputs.first?.portName
    }
}

#else  // !canImport(UIKit): the macOS no-op lane (SW-ENG1-01)

/// macOS fallback adapter: AVAudioSession does not exist on macOS (the
/// HAL owns output configuration), so the session lifecycle is a no-op
/// with honest bookkeeping. The API mirrors the iOS adapter exactly, so
/// `PlaybackViewModel` and the tests compile for both platforms without
/// a single conditional at the call sites. `activate()` still `throws`
/// (and never fails) so `try adapter.activate()` stays legal everywhere.
@MainActor
final class IOSAudioSessionAdapter: ObservableObject {

    enum Lifecycle: Equatable, Sendable {
        case configured
        case active
        case deactivated
    }

    private weak var responder: AudioSessionResponding?
    private(set) var lifecycle: Lifecycle = .deactivated

    /// Intentionally inert on macOS (no session to observe).
    @Published private(set) var lastInterruptionDescription: String?
    @Published private(set) var lastRouteChangeDescription: String?

    init(responder: AudioSessionResponding) {
        self.responder = responder
    }

    func activate() throws {
        // No AVAudioSession on macOS — CoreAudio output needs no category
        // activation. Record the state transition and succeed.
        lifecycle = .active
    }

    func deactivate() {
        lifecycle = .deactivated
    }

    func updateResponder(_ responder: AudioSessionResponding) {
        self.responder = responder
    }
}

#endif  // canImport(UIKit)
