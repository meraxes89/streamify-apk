import AppIntents
import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-06 (Phase 5): the AppIntents powering iOS 17 interactive widgets,
// Lock Screen controls, and the Live Activity transport buttons.
//
// The intents execute INSIDE the widget/extension process: `perform()`
// posts onto the App Group command mailbox (WidgetPlaybackStore) — the
// app process (alive during playback: the active audio session keeps it
// running) drains the mailbox onto the EngineGateway plane on every engine
// push. The `AudioPlaybackIntent` marker routes the intents with the
// system's media-priority handling.
// ─────────────────────────────────────────────────────────────────────────────

/// Process-agnostic command dispatch for the intents (and the macOS menu
/// bar extra): one UserDefaults suite, thread-safe by construction.
enum WidgetCommandDispatcher {

    /// The production App Group id (nil = single-process standard suite;
    /// tests inject isolated suites directly through the store).
    static var appGroupName: String?

    private static func makeStore() -> WidgetPlaybackStore {
        WidgetPlaybackStore(suiteName: appGroupName)
    }

    static func post(_ command: WidgetTransportCommand) {
        var store = makeStore()
        store.post(command)
    }

    static func postSeek(positionMs: Int) {
        var store = makeStore()
        store.postSeek(positionMs: positionMs)
    }

    static func postVolume(_ volume: Double) {
        var store = makeStore()
        store.postVolume(volume)
    }
}

// MARK: - Transport intents (iOS 17 interactive surfaces)

/// Toggle play/pause — the primary interactive-widget button.
@available(iOS 17.0, *)
struct PlayPausePlaybackIntent: AppIntent, AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Play / Pause"
    static var description = IntentDescription(
        "Toggles Streamify playback from the widget."
    )

    func perform() async throws -> some IntentResult {
        WidgetCommandDispatcher.post(.togglePlayPause)
        return .result()
    }
}

/// Next track.
@available(iOS 17.0, *)
struct NextTrackPlaybackIntent: AppIntent, AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Next Track"
    static var description = IntentDescription(
        "Skips to the next Streamify track from the widget."
    )

    func perform() async throws -> some IntentResult {
        WidgetCommandDispatcher.post(.next)
        return .result()
    }
}

/// Previous track.
@available(iOS 17.0, *)
struct PreviousTrackPlaybackIntent: AppIntent, AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Previous Track"
    static var description = IntentDescription(
        "Returns to the previous Streamify track from the widget."
    )

    func perform() async throws -> some IntentResult {
        WidgetCommandDispatcher.post(.previous)
        return .result()
    }
}

/// Volume intent (the macOS menu-bar slider and the medium widget's
/// volume button).
@available(iOS 17.0, *)
struct SetVolumePlaybackIntent: AppIntent, AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Set Volume"

    @Parameter(title: "Volume", in: 0.0...1.0)
    var volume: Double

    init() {}

    init(volume: Double) {
        self.volume = volume
    }

    func perform() async throws -> some IntentResult {
        WidgetCommandDispatcher.postVolume(volume)
        return .result()
    }
}
