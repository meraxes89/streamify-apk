import Foundation

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — haptics: the Apple-native mapping of Android's
// util/HapticFeedbackManager HapticWaveform catalogue (playPauseClick,
// scrubTick, likeHeartbeat, queueGrab/boundaryTick/drop, jamUpvoteRising,
// skipTrigger — StreamifyHapticEngine.tokenImpactDetent included).
//
// HIG mapping: UIKit's impact/selection/notification generators replace the
// waveform composer on iOS; AppKit's NSHapticFeedbackManager performers
// cover the Mac trackpad. No-ops elsewhere by construction.
// ─────────────────────────────────────────────────────────────────────────────

/// The gesture-haptic vocabulary every interactive surface speaks.
public enum HapticWaveform: Sendable, CaseIterable {
    case playPauseClick
    case scrubTick
    case likeHeartbeat
    case queueGrab
    case queueBoundaryTick
    case queueDrop
    case jamUpvoteRising
    case skipTrigger
}

public enum Haptics {

    /// Fires one waveform. Safe on every platform (no-op where haptics are
    /// unavailable).
    public static func play(_ waveform: HapticWaveform) {
        #if os(iOS)
        switch waveform {
        case .playPauseClick:
            UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.8)
        case .scrubTick:
            UISelectionFeedbackGenerator().selectionChanged()
        case .likeHeartbeat:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .queueGrab:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .queueBoundaryTick:
            UISelectionFeedbackGenerator().selectionChanged()
        case .queueDrop:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .jamUpvoteRising:
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.9)
        case .skipTrigger:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
        #elseif os(macOS)
        switch waveform {
        case .playPauseClick:
            NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .default)
        case .scrubTick:
            NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .default)
        case .likeHeartbeat:
            NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
        case .queueGrab:
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .default)
        case .queueBoundaryTick:
            NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
        case .queueDrop:
            NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
        case .jamUpvoteRising:
            NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .default)
        case .skipTrigger:
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .default)
        }
        #endif
    }

    /// Pre-warms the generators for latency-critical paths (scrub rails fire
    /// `.scrubTick` at up to 120 Hz — prepared generators skip the
    /// lazy-activation hop).
    public static func prepare(_ waveform: HapticWaveform) {
        #if os(iOS)
        switch waveform {
        case .scrubTick, .queueBoundaryTick:
            UISelectionFeedbackGenerator().prepare()
        case .likeHeartbeat, .queueDrop:
            UINotificationFeedbackGenerator().prepare()
        case .playPauseClick:
            UIImpactFeedbackGenerator(style: .light).prepare()
        case .queueGrab, .skipTrigger:
            UIImpactFeedbackGenerator(style: .medium).prepare()
        case .jamUpvoteRising:
            UIImpactFeedbackGenerator(style: .rigid).prepare()
        }
        #endif
    }
}
