import AVFoundation
import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI02 / Beta) — hardened SW-ENG3-02 (Engineer 3): the iOS
// audio-session adapter — AVAudioSession governance for the native
// SwiftUI shell.
//
// Directive contract (SW-ENG3-02 / MP-UI02):
//  * CONFIGURE — category `.playback`, mode `.default`, options
//    `[.allowAirPlay]` (AirPlay sinks are first-class routes for an
//    audiophile player; the option is belt-and-suspenders over the
//    playback category's implicit default).
//  * INTERRUPTION — `AVAudioSession.interruptionNotification`:
//      `.began`                    → save the pre-interruption playback
//                                   state, then pause the engine;
//      `.ended` + `.shouldResume`  → resume ONLY if the engine was
//                                   actually playing when the interruption
//                                   began (phone call finished, Siri
//                                   dismissed);
//      `.ended` without the option → stay paused (the user/system decided).
//  * ROUTE CHANGE — `AVAudioSession.routeChangeNotification`:
//      `.oldDeviceUnavailable` (wired headphones unplugged, AirPods
//      disconnected, Bluetooth car turned off) → pause IMMEDIATELY —
//      never blast loud audio out of the suddenly-public phone speaker;
//      the adapter also deactivates its session bookkeeping cleanly.
//      `.newDeviceAvailable`       → adapt the session's preferred IO
//                                   buffer duration to the new output
//                                   device's latency (OutputBufferPolicy —
//                                   latency-proportional, clamped into the
//                                   OS-reported window).
//  * CLEAN TEARDOWN — `deactivate()` removes every observer exactly once
//    and is idempotent; the adapter never leaks NotificationCenter tokens
//    across scene or engine re-lifecycles.
//
// DESIGN: the decision core is PURE functions over value enums —
// `decision(forInterruption:)` / `decision(forRouteChange:)` /
// `reaction(forRouteChange:)` / `OutputBufferPolicy` — so the full policy
// matrix is unit-testable without an audio route (XCTest drives the real
// AVAudioSession value types; no mocks, no device). The observer plumbing
// around the core is thin, main-actor isolated, and testable via synthetic
// notification payloads (`interruptionPayload(type:options:)` /
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

/// One route change's full effect on the session + engine (SW-ENG3-02):
/// the engine reaction and the (independent) IO-buffer adaptation flag.
struct RouteChangeReaction: Equatable, Sendable {
    let engineDecision: AudioSessionDecision
    let adaptsBufferDuration: Bool
}

/// Anything that can serve the adapter's playback reactions (the shell's
/// PlaybackViewModel conforms; tests install a recording stub).
@MainActor
protocol AudioSessionResponding: AnyObject {
    func audioSessionRequestsPause(reason: String)
    func audioSessionRequestsResume(reason: String)
}

extension AudioSessionResponding {
    /// Whether the engine is currently presenting audio — consulted when
    /// an interruption BEGINS so the `.ended` resume decision knows what
    /// the pre-interruption state actually was (SW-ENG3-02: "save
    /// pre-interruption state"). Defaults to false: a responder that
    /// cannot answer was (as far as the policy is concerned) not playing.
    var audioSessionPlaybackIsActive: Bool { false }
}

/// Pre-interruption playback state, recorded when an interruption begins
/// and consumed by the `.ended` decision (SW-ENG3-02): auto-resume is only
/// correct when the ENGINE was actually playing when the phone call / Siri
/// stole the route — resuming a track the user had already paused would be
/// a state fabrication, not a reaction.
struct InterruptionMemory: Equatable, Sendable {
    let wasPlaying: Bool
}

/// IO-buffer adaptation policy for a newly-arrived output route
/// (SW-ENG3-02 / MP-UI02: "adapt buffer duration to the new output device
/// latency"). Pure math over reported device facts — unit-tested; the
/// application is a single `setPreferredIOBufferDuration` call.
enum OutputBufferPolicy {

    /// Headroom added on top of the device's output latency: one nominal
    /// 48 kHz quantum (5 ms ≈ 240 frames) — enough that a latency-
    /// proportional buffer still absorbs one scheduler hiccup without an
    /// underrun, small enough to stay in the low-latency regime.
    static let headroomSeconds: TimeInterval = 0.005

    /// Fallback clamp window when the OS reports no IO bounds (older
    /// systems report 0): Apple's documented typical range.
    static let defaultMinimumSeconds: TimeInterval = 0.005
    static let defaultMaximumSeconds: TimeInterval = 0.093

    /// Latency-proportional target: `outputLatency + headroom`, clamped
    /// into `[minimum, maximum]`. Degenerate bounds (non-positive or
    /// inverted) fall back to the documented defaults for BOTH ends; a
    /// negative reported latency clamps to zero first (defensive — never
    /// fabricate).
    static func preferredIOBufferDuration(
        outputLatency: TimeInterval,
        minimum: TimeInterval,
        maximum: TimeInterval
    ) -> TimeInterval {
        let minBound: TimeInterval
        let maxBound: TimeInterval
        if minimum > 0 && maximum >= minimum {
            minBound = minimum
            maxBound = maximum
        } else {
            minBound = defaultMinimumSeconds
            maxBound = defaultMaximumSeconds
        }
        let target = max(0, outputLatency) + headroomSeconds
        return min(max(target, minBound), maxBound)
    }
}

@MainActor
final class IOSAudioSessionAdapter: ObservableObject {

    /// Lifecycle state the shell can render.
    enum Lifecycle: Equatable, Sendable {
        case configured
        case active
        case deactivated
    }

    // MARK: - Pure decision core (unit-tested)

    /// Interruption policy (flag-only view): `.began` pauses; `.ended`
    /// resumes ONLY when the system advises `.shouldResume`.
    nonisolated static func decision(
        forInterruption type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions
    ) -> AudioSessionDecision {
        decision(
            forInterruption: type,
            options: options,
            preInterruptionPlaying: nil
        )
    }

    /// Interruption policy with pre-interruption memory (the SW-ENG3-02
    /// directive's full contract): `.began` pauses; `.ended` resumes ONLY
    /// when the OS advises `.shouldResume` AND the engine was playing when
    /// the interruption began (`nil` memory — the began event was never
    /// observed — falls back to the flag alone, matching pre-hardening
    /// behavior).
    nonisolated static func decision(
        forInterruption type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions,
        preInterruptionPlaying: Bool?
    ) -> AudioSessionDecision {
        switch type {
        case .began:
            return .pauseEngine
        case .ended:
            guard options.contains(.shouldResume) else { return .none }
            guard let preInterruptionPlaying else { return .resumeEngine }
            return preInterruptionPlaying ? .resumeEngine : .none
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

    /// The FULL route-change reaction (SW-ENG3-02): the engine decision
    /// PLUS whether the session's IO buffer duration should be re-adapted
    /// to the newly-arrived output device.
    nonisolated static func reaction(
        forRouteChange reason: AVAudioSession.RouteChangeReason
    ) -> RouteChangeReaction {
        RouteChangeReaction(
            engineDecision: reason == .oldDeviceUnavailable ? .pauseEngine : .none,
            adaptsBufferDuration: reason == .newDeviceAvailable
        )
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

    /// Pre-interruption playback state (SW-ENG3-02): recorded when an
    /// interruption begins, consumed (and cleared) by the `.ended` decision.
    private(set) var interruptionMemory: InterruptionMemory?

    /// Last observed interruption/route state (diagnostics surface).
    @Published private(set) var lastInterruptionDescription: String?
    @Published private(set) var lastRouteChangeDescription: String?
    /// Last IO-buffer adaptation (diagnostics surface for the new-device
    /// policy: target duration + the device facts it derived from).
    @Published private(set) var lastBufferAdaptationDescription: String?

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
        // Directive configuration (SW-ENG3-02): category .playback,
        // mode .default, options [.allowAirPlay] — AirPlay sinks are
        // first-class output routes; no mixing, no ducking; playback
        // survives the silent switch and the locked screen.
        try session.setCategory(.playback, mode: .default, options: [.allowAirPlay])
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
        // SW-ENG3-02: record the pre-interruption state BEFORE reacting to
        // `.began` (the pause itself flips `audioSessionPlaybackIsActive`).
        let preInterruptionPlaying: Bool?
        if parsed.type == .began {
            let wasPlaying = responder?.audioSessionPlaybackIsActive ?? false
            interruptionMemory = InterruptionMemory(wasPlaying: wasPlaying)
            preInterruptionPlaying = wasPlaying
        } else {
            preInterruptionPlaying = interruptionMemory?.wasPlaying
        }
        let decision = Self.decision(
            forInterruption: parsed.type,
            options: parsed.options,
            preInterruptionPlaying: preInterruptionPlaying
        )
        lastInterruptionDescription =
            "interruption \(parsed.type.rawValue), options \(parsed.options.rawValue), wasPlaying \(preInterruptionPlaying.map(String.init(describing:)) ?? "unknown") -> \(decision)"
        if parsed.type == .ended {
            // The memory describes ONE interruption cycle: consume it.
            interruptionMemory = nil
        }
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
        let reaction = Self.reaction(forRouteChange: reason)
        lastRouteChangeDescription =
            "route \(reason.rawValue) (was \(previous ?? "unknown")) -> \(reaction.engineDecision)\(reaction.adaptsBufferDuration ? ", adapting IO buffer" : "")"
        switch reaction.engineDecision {
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
        if reaction.adaptsBufferDuration {
            // SW-ENG3-02: a new output device just took the route — adapt
            // the session's preferred IO buffer duration to ITS latency.
            adaptBufferDurationToCurrentRoute()
        }
    }

    /// Applies the new-device buffer policy (SW-ENG3-02): reads the CURRENT
    /// route's output latency + the OS IO bounds, derives the
    /// latency-proportional target through the pure `OutputBufferPolicy`,
    /// and applies it with `setPreferredIOBufferDuration`. Never throws —
    /// a refused preference is a diagnostic, not an error path.
    @MainActor private func adaptBufferDurationToCurrentRoute() {
        let outputLatency = session.outputLatency
        let reportedMin = session.minimumIOBufferDuration
        let reportedMax = session.maximumIOBufferDuration
        let target = OutputBufferPolicy.preferredIOBufferDuration(
            outputLatency: outputLatency,
            minimum: reportedMin,
            maximum: reportedMax
        )
        do {
            try session.setPreferredIOBufferDuration(target)
            lastBufferAdaptationDescription = String(
                format: "IO buffer %.1f ms (latency %.1f ms, bounds [%.1f, %.1f] ms)",
                target * 1000.0,
                outputLatency * 1000.0,
                (reportedMin > 0 ? reportedMin : OutputBufferPolicy.defaultMinimumSeconds) * 1000.0,
                (reportedMax > 0 ? reportedMax : OutputBufferPolicy.defaultMaximumSeconds) * 1000.0
            )
        } catch {
            lastBufferAdaptationDescription = String(
                format: "IO buffer adaptation refused at %.1f ms", target * 1000.0
            )
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
