import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-02 (Engineer 4): DAC clock interpolation WITHOUT polling.
//
// The Rust engine pushes coalesced snapshots carrying a `DacPresentationClock`
// sample (hardware consumed frames, the DAC's nominal rate, and the OS
// monotonic presentation timestamp — the SOLE clock master, MP-DEC-03).
// Between pushes the playback position is INTERPOLATED from the hardware
// timestamps, never polled back from the engine:
//
//              (currentTime − monotonic_ns) × sample_rate
//   position = dac_frame_position + ────────────────────────────
//                                    1_000_000_000
//
// FEED DISCIPLINE (120 Hz without full-view redraws): the scrub bar leaf
// re-evaluates `positionMs(atUptimeNs:)` inside SwiftUI's display-synced
// `TimelineView(.animation)` — ProMotion drives it at up to 120 fps — while
// every other view keeps reading the coarse 1 Hz-quantized projections. The
// clock is a SEPARATE `@Observable` object from the view model so per-frame
// reads invalidate ONLY the scrub bar leaves, never the whole transport view.
// There is no `Timer`, no `while(true)` loop, and no engine getter call
// anywhere in this file — the sole inputs are pushed snapshots and the host
// monotonic clock the caller already holds.
// ─────────────────────────────────────────────────────────────────────────────

/// Hardware-anchored playback position interpolator (push-fed, display-read).
@Observable
@MainActor
final class PlaybackProgressClock {

    /// One pushed anchor: the engine's hardware clock sample plus the host
    /// monotonic reading captured at ingest time.
    struct Anchor: Equatable, Sendable {
        let sample: DacClockSample
        /// Host uptime (ns) when the snapshot landed in this clock.
        let ingestedAtUptimeNs: Int
        /// `sample.monotonicNs − ingestedAtUptimeNs`: the (tiny) offset that
        /// maps host uptime onto the engine's monotonic domain. When the two
        /// clocks share the Apple monotonic epoch — they do; both are
        /// nanoseconds since boot — this is just the push latency, and the
        /// interpolation below degenerates to the directive's formula.
        let domainDeltaNs: Int
    }

    // ── Observable state (only scrub-bar leaves read the anchor) ───────────

    private(set) var anchor: Anchor?
    private(set) var isPlaying = false
    private(set) var durationMs: Int?
    /// Optimistic seek target (Android-parity: jumps the scrub bar the
    /// instant the user releases a seek, cleared once the engine's pushed
    /// hardware position agrees within the unlatch window).
    private(set) var optimisticSeekMs: Int?

    /// Ingest latency diagnostics (push-to-anchor distance, ns).
    private(set) var lastIngestLatencyNs = 0

    // ── Constants (Android parity) ──────────────────────────────────────────

    /// Optimistic-seek unlatch: the engine position must land within this
    /// window of the optimistic target (250 ms on Android).
    static let seekUnlatchWindowMs = 250
    /// An optimistic seek survives at most this long without confirmation.
    static let seekTimeoutMs = 1_200

    private var seekDeadlineUptimeNs: Int = 0

    // MARK: - Ingest (push only)

    /// Feeds one pushed snapshot's clock sample into the interpolator.
    ///
    /// - Parameters:
    ///   - sample: the snapshot's `DacPresentationClock` mirror (nil when the
    ///     engine has no usable hardware clock yet — e.g. before the first
    ///     HAL callback).
    ///   - isPlaying: whether the transport was playing in that snapshot.
    ///   - hostUptimeNs: the caller's host monotonic reading (DispatchTime
    ///     uptime) at ingest.
    ///   - durationMs: the active track's duration, when known.
    func ingest(
        _ sample: DacClockSample?,
        isPlaying: Bool,
        durationMs: Int?,
        hostUptimeNs: Int
    ) {
        self.durationMs = durationMs

        // Seek-timeout admission: an unconfirmed optimistic seek expires.
        if optimisticSeekMs != nil, hostUptimeNs >= seekDeadlineUptimeNs {
            self.optimisticSeekMs = nil
        }

        guard let sample, (8_000...768_000).contains(sample.sampleRateHz) else { return }

        let anchor = Anchor(
            sample: sample,
            ingestedAtUptimeNs: hostUptimeNs,
            domainDeltaNs: sample.monotonicNs - hostUptimeNs
        )
        lastIngestLatencyNs = max(0, hostUptimeNs - sample.monotonicNs)

        // A hardware PAUSE freezes the anchor: only swap it while playing or
        // when the pushed frame counter actually moved (a seek while paused).
        let wasPlaying = self.isPlaying
        let shouldSwap = isPlaying
            || anchor.sample.framePosition != (self.anchor?.sample.framePosition ?? -1)
        if shouldSwap {
            self.anchor = anchor
        }
        self.isPlaying = isPlaying

        // Optimistic-seek unlatch: the hardware position agrees with the
        // target once interpolation lands inside the window.
        if optimisticSeekMs != nil, wasPlaying || isPlaying {
            if let position = Self.interpolatedPositionMs(anchor: anchor, nowUptimeNs: hostUptimeNs),
               abs(position - optimisticSeekMs!) <= Self.seekUnlatchWindowMs {
                self.optimisticSeekMs = nil
            }
        }
    }

    /// Registers an optimistic seek target (the engine's confirming push is
    /// expected to unlatch it).
    func registerSeek(positionMs: Int, hostUptimeNs: Int) {
        optimisticSeekMs = max(0, positionMs)
        seekDeadlineUptimeNs = hostUptimeNs + Self.seekTimeoutMs * 1_000_000
    }

    /// Clears any optimistic seek (e.g. the track changed).
    func reset() {
        anchor = nil
        optimisticSeekMs = nil
        durationMs = nil
        lastIngestLatencyNs = 0
    }

    // MARK: - Read (display-synced; pure math over pushed anchors)

    /// The interpolated playback position in ms at host uptime `nowUptimeNs`,
    /// or nil while no usable hardware anchor exists.
    ///
    /// Optimistic-seek admission: while an unconfirmed seek is outstanding,
    /// its target wins over the (stale) interpolation — the scrub bar must
    /// never snap backwards for the ~1 RTT the seek takes to reach the DAC.
    func positionMs(atUptimeNs nowUptimeNs: Int) -> Int? {
        if let optimisticSeekMs { return optimisticSeekMs }
        guard let anchor else { return nil }
        guard isPlaying else {
            return Self.anchorPositionMs(anchor)
        }
        return Self.interpolatedPositionMs(anchor: anchor, nowUptimeNs: nowUptimeNs)
    }

    /// Progress fraction in [0, 1] at `nowUptimeNs`; nil when indeterminate.
    func progressFraction(atUptimeNs nowUptimeNs: Int) -> Double? {
        PlaybackFormatting.progressFraction(
            positionMs: positionMs(atUptimeNs: nowUptimeNs),
            durationMs: durationMs
        )
    }

    /// The anchor's own frame-derived position (no interpolation).
    var anchoredPositionMs: Int? {
        anchor.map(Self.anchorPositionMs)
    }

    // MARK: - Pure math (unit-tested against the directive formula)

    /// Frame-derived position of an anchor sample, in ms.
    static func anchorPositionMs(_ anchor: Anchor) -> Int {
        let frames = Double(anchor.sample.framePosition)
        return Int((frames / Double(anchor.sample.sampleRateHz)) * 1000.0)
    }

    /// The directive's interpolation formula, verbatim:
    ///
    ///   frames(now) = dac_frame_position
    ///                 + (engineNow(now) − monotonic_ns) × sample_rate / 1e9
    ///
    /// where `engineNow(now)` maps the caller's host uptime onto the engine's
    /// monotonic domain via the anchor's measured domain delta. Negative
    /// elapsed time (a racing push landing after a newer anchor) clamps to
    /// zero — interpolation must never rewind the playhead between pushes.
    static func interpolatedPositionMs(anchor: Anchor, nowUptimeNs: Int) -> Int? {
        let engineNowNs = nowUptimeNs + anchor.domainDeltaNs
        let elapsedNs = engineNowNs - anchor.sample.monotonicNs
        guard elapsedNs > 0 else {
            return anchorPositionMs(anchor)
        }
        let advancedFrames = Double(elapsedNs) * Double(anchor.sample.sampleRateHz) / 1_000_000_000.0
        let frames = Double(anchor.sample.framePosition) + advancedFrames
        let positionMs = (frames / Double(anchor.sample.sampleRateHz)) * 1000.0
        if positionMs.isNaN || positionMs < 0 || positionMs > 86_400_000_000 {
            return nil
        }
        return Int(positionMs)
    }
}
