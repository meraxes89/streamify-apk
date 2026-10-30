import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): Swift mirrors of the shared presentation models
// (shared/src/commonMain/.../model/PlaybackModels.kt is the source of truth;
// this file mirrors it 1:1 so the iOS shell speaks the same currency as the
// desktop CMP shell and the Android seam).
// ─────────────────────────────────────────────────────────────────────────────

/// Coarse playback lifecycle (mirror of the UDL `PlaybackState`).
public enum PlaybackStatus: Equatable, Sendable {
    case idle
    case buffering
    case playing
    case paused
    case error
}

/// Canonical descriptive metadata for one playable track.
public struct PresentationTrack: Equatable, Sendable {
    public let id: String
    public let title: String
    public let artist: String
    public var durationMs: Int?
    public var artworkUrl: String?
}

/// Live buffering health snapshot (mirror of the UDL `BufferStats`).
public struct BufferHealth: Equatable, Sendable {
    public let bufferedDurationMs: Int
    public let underrunCount: Int
    /// Ring fill ratio in [0.0, 1.0].
    public let fillRatio: Double
}

/// One hardware DAC presentation clock sample (mirror of the UDL
/// `DacPresentationClock`). The hardware HAL callback is the SOLE clock
/// master (MP-DEC-03): playback position derives strictly from these
/// hardware presentation timestamps — never from wall-clock interpolation.
public struct DacClockSample: Equatable, Sendable {
    public let framePosition: Int
    public let sampleRateHz: Int
    public let monotonicNs: Int
}

/// Reverse control plane: user / hardware intent lowered onto the engine
/// (mirror of the UDL `MediaCommand`; the engine applies its 50-150 ms
/// hardware debounce to identical duplicates).
public enum PlayerCommand: Equatable, Sendable {
    case play
    case pause
    case togglePlayPause
    case next
    case previous
    case seek(positionMs: Int)
    case setVolume(Double)
    case stop
}

/// Outcome of dispatching one command through the engine gateway.
public enum PlayerCommandResult: Equatable, Sendable {
    case applied
    case debounced
    case failed
}

/// One coalesced engine state snapshot with a strictly monotonic revision
/// (mirror of the UDL `EngineSnapshot`, MP-DEC-04: push-only).
public struct PlaybackSnapshot: Equatable, Sendable {
    public let rev: Int
    public let status: PlaybackStatus
    /// Output volume scalar in [0.0, 1.0].
    public let volume: Double
    public var buffer: BufferHealth?
    public var clock: DacClockSample?
    public var currentTrack: PresentationTrack?
    public var jamSessionName: String?

    public init(
        rev: Int,
        status: PlaybackStatus,
        volume: Double,
        buffer: BufferHealth? = nil,
        clock: DacClockSample? = nil,
        currentTrack: PresentationTrack? = nil,
        jamSessionName: String? = nil
    ) {
        self.rev = rev
        self.status = status
        self.volume = volume
        self.buffer = buffer
        self.clock = clock
        self.currentTrack = currentTrack
        self.jamSessionName = jamSessionName
    }

    public static let idle = PlaybackSnapshot(rev: 0, status: .idle, volume: 0)
}

/// UI-facing projection plus the derived values the views render.
public struct PlaybackUiState: Equatable, Sendable {
    public let snapshot: PlaybackSnapshot

    /// The pre-engine surface state (same shape as `PlaybackSnapshot.idle`,
    /// one UI-level constant so call sites never nest the raw default).
    public static let idle = PlaybackUiState()

    public init(_ snapshot: PlaybackSnapshot = .idle) {
        self.snapshot = snapshot
    }

    public var status: PlaybackStatus { snapshot.status }
    public var volume: Double { snapshot.volume }
    public var isPlaying: Bool { status == .playing }
    public var isBuffering: Bool { status == .buffering }
    public var currentTrack: PresentationTrack? { snapshot.currentTrack }

    /// Position derived from the DAC clock; nil until the first hardware
    /// sample arrives.
    public var positionMs: Int? { PlaybackFormatting.positionMs(from: snapshot.clock) }
    public var durationMs: Int? { currentTrack?.durationMs }

    /// The 1 Hz-quantized position the now-playing surfaces RENDER (the
    /// directive's "rate-limit now-playing updates to 1 Hz", applied to the
    /// in-app clock readouts): the displayed value advances at most once
    /// per second OF CONTENT, so higher-cadence DAC pushes repaint the
    /// transport without churning the elapsed-time text. The lockscreen
    /// surface gets the same discipline through NowPlayingInfoPublisher's
    /// admission gate.
    public var positionForDisplayMs: Int? { positionMs.map { ($0 / 1000) * 1000 } }

    public var progressFraction: Double? {
        PlaybackFormatting.progressFraction(positionMs: positionMs, durationMs: durationMs)
    }
}

// MARK: - Pure formatting / derivation math (JVM-parity, unit-tested)

public enum PlaybackFormatting {

    /// `m:ss` under one hour, `h:mm:ss` from one hour up; negatives clamp
    /// to zero — a position renderer must never show garbage for a missing
    /// clock sample. Truncation toward zero (59_999ms renders as 0:59).
    public static func formatPlaybackTime(_ positionMs: Int) -> String {
        let totalSeconds = max(0, positionMs) / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Progress fraction in [0, 1]; nil when either endpoint is unknown —
    /// callers render an indeterminate indicator instead of a fabricated
    /// value.
    public static func progressFraction(positionMs: Int?, durationMs: Int?) -> Double? {
        guard let positionMs, let durationMs, durationMs > 0 else { return nil }
        let fraction = Double(min(max(positionMs, 0), durationMs)) / Double(durationMs)
        return min(max(fraction, 0), 1)
    }

    /// Derives a playback position in milliseconds from one hardware DAC
    /// presentation clock sample: the DAC's own frame counter over the
    /// DAC's own nominal rate (MP-DEC-03 — no wall clock participates).
    public static func positionMs(from clock: DacClockSample?) -> Int? {
        guard let clock else { return nil }
        guard (8_000...768_000).contains(clock.sampleRateHz) else { return nil }
        guard clock.framePosition >= 0 else { return nil }
        return Int((Double(clock.framePosition) / Double(clock.sampleRateHz)) * 1000.0)
    }

    /// DAC drift between two successive hardware clock samples in ppm
    /// (positive = the device DAC presented MORE frames than its nominal
    /// rate predicts for the observed monotonic interval). Returns nil for
    /// non-usable measurement pairs.
    public static func dacDriftPpm(older: DacClockSample, newer: DacClockSample) -> Double? {
        guard older.sampleRateHz == newer.sampleRateHz else { return nil }
        guard (8_000...768_000).contains(older.sampleRateHz) else { return nil }
        let elapsedNs = newer.monotonicNs - older.monotonicNs
        guard elapsedNs > 0 else { return nil }
        let presentedFrames = newer.framePosition - older.framePosition
        guard presentedFrames >= 0 else { return nil }

        let expectedFrames = Double(elapsedNs) * Double(older.sampleRateHz) / 1_000_000_000.0
        guard abs(expectedFrames) >= 1.0 else { return nil }
        return (Double(presentedFrames) - expectedFrames) / expectedFrames * 1_000_000.0
    }
}
