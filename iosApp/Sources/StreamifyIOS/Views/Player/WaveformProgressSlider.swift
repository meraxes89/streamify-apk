import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-05 — the WaveformProgressSlider: the audiophile scrub bar.
//
// The Android reference has no true waveform seekbar (YtPlayerSeekBar is a
// 4dp capsule); the directive upgrades the contract for Apple: a
// Canvas-rendered waveform whose PLAYED portion is lit by progress, scrubbed
// with microsecond-precision math.
//
// DAC PACING (MP-DEC-03): the rendered position derives strictly from the
// hardware DacPresentationClock samples pushed by the engine
// (PlaybackUiState.positionMs — DacClockSample.framePosition / sampleRateHz),
// never from a wall-clock timer; the 1 Hz-quantized READOUT discipline
// lives in PlaybackUiState.positionForDisplayMs.
//
// MICROSECOND PRECISION: the scrub pipeline carries μs end-to-end —
// positionUs / durationUs Doubles, drag targets computed and rounded to 1 μs
// (100 ns per pixel at typical widths) — and truncates to milliseconds ONLY
// at the PlayerCommand boundary, because the UDL's seek command speaks ms.
//
// HAPTICS: `.scrubTick` fires each time the drag crosses a 5-second
// boundary; `.queueDrop` lands the release.
// ─────────────────────────────────────────────────────────────────────────────

public struct WaveformProgressSlider: View {

    /// DAC-paced position in MICROSECONDS (ms × 1000 from the clock sample).
    public var positionUs: Double
    /// Track duration in μs; nil = indeterminate (scrub disabled).
    public var durationUs: Double?
    public var isPlaying: Bool = true
    public var onScrubChange: ((Double) -> Void)? = nil
    public var onScrubEnd: ((Double) -> Void)? = nil

    @State private var scrubProgress: Double?

    private let barCount = 72
    private let tickSeconds = 5.0

    public init(
        positionUs: Double,
        durationUs: Double?,
        isPlaying: Bool = true,
        onScrubChange: ((Double) -> Void)? = nil,
        onScrubEnd: ((Double) -> Void)? = nil
    ) {
        self.positionUs = positionUs
        self.durationUs = durationUs
        self.isPlaying = isPlaying
        self.onScrubChange = onScrubChange
        self.onScrubEnd = onScrubEnd
    }

    private var progress: Double {
        if let scrubProgress { return scrubProgress }
        guard let durationUs, durationUs > 0 else { return 0 }
        return min(max(positionUs / durationUs, 0), 1)
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceSM) {
            Canvas { context, size in
                let barSpacing = size.width / CGFloat(barCount)
                let barWidth = max(2, barSpacing * 0.52)
                let midY = size.height / 2

                for i in 0..<barCount {
                    let fraction = Double(i) / Double(barCount - 1)
                    let amplitude = Self.barAmplitude(fraction)
                    let barHeight = max(3, size.height * amplitude)
                    let x = CGFloat(i) * barSpacing + (barSpacing - barWidth) / 2
                    let rect = CGRect(
                        x: x,
                        y: midY - barHeight / 2,
                        width: barWidth,
                        height: barHeight
                    )
                    let played = fraction <= progress
                    // Rounded caps per bar: the played tail brightens
                    // toward the head; the unplayed tail sits muted.
                    let path = Path(roundedRect: rect, cornerRadius: barWidth / 2)
                    context.fill(
                        path,
                        with: .color(
                            played
                                ? StreamifyColors.activeControl.opacity(0.45 + 0.55 * fraction)
                                : StreamifyColors.borderSubtle
                        )
                    )
                }

                // The scrub head: a hairline tick at the head.
                let headX = size.width * progress
                var head = Path()
                head.move(to: CGPoint(x: headX, y: 0))
                head.addLine(to: CGPoint(x: headX, y: size.height))
                context.stroke(
                    head,
                    with: .color(StreamifyColors.activeControl),
                    style: PathStrokeStyle(lineWidth: 1.5, lineCap: .round)
                )
            }
            .frame(height: StreamifyDimens.miniPlayerHeight * 0.72)
            .contentShape(Rectangle())
            .gesture(scrubGesture)
            .background(widthProbe)
            .accessibilityLabel("Scrub bar")
            .accessibilityValue(headLabel)
            .accessibilityAdjustableAction { direction in
                guard let durationUs else { return }
                let stepUs = durationUs / 50
                let target: Double
                switch direction {
                case .increment: target = progress + stepUs / durationUs
                case .decrement: target = progress - stepUs / durationUs
                @unknown default: return
                }
                let clamped = min(max(target, 0), 1)
                onScrubEnd?(clamped * durationUs)
            }

            HStack {
                Text(positionLabel)
                    .font(StreamifyType.seekbarTime.monospacedDigit())
                    .foregroundStyle(StreamifyColors.textSecondary)
                Spacer()
                Text(durationLabel)
                    .font(StreamifyType.seekbarTime.monospacedDigit())
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
        }
    }

    // ─ Microsecond pipeline ──────────────────────────────────────────────

    /// μs → "m:ss" at rest, "m:ss.mmm" while scrubbing (the precision the
    /// scrub math actually carries, surfaced exactly when the finger owns it).
    private var positionLabel: String {
        if let scrubProgress {
            let us = scrubProgress * (durationUs ?? 0)
            return Self.formatMicroseconds(us)
        }
        return PlaybackFormatting.formatPlaybackTime(Int(positionUs / 1_000))
    }

    private var durationLabel: String {
        guard let durationUs else { return "--:--" }
        return PlaybackFormatting.formatPlaybackTime(Int(durationUs / 1_000))
    }

    private var headLabel: String {
        "\(Int((progress * 100).rounded())) percent"
    }

    private var scrubGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard let durationUs, durationUs > 0 else { return }
                guard let width = Self.trackedWidthHint, width > 0 else { return }
                let raw = Double(value.location.x)
                // Microsecond target: round to 1 μs — the drag resolution
                // floor at typical widths is ~100 ns per pixel.
                let targetUs = (min(max(raw / width, 0), 1) * durationUs).rounded()
                let next = min(max(targetUs / durationUs, 0), 1)

                let previousSeconds = progress * durationUs / 1_000_000
                let nextSeconds = next * durationUs / 1_000_000
                if Int(previousSeconds / tickSeconds) != Int(nextSeconds / tickSeconds) {
                    Haptics.play(.scrubTick)
                }
                scrubProgress = next
                onScrubChange?(targetUs)
            }
            .onEnded { _ in
                if let scrubProgress {
                    Haptics.play(.queueDrop)
                    onScrubEnd?(scrubProgress * (durationUs ?? 0))
                }
                scrubProgress = nil
            }
    }

    /// The drag needs the track's pixel width for 1:1 mapping. Captured on
    /// the gesture's container via onGeometryChange-free GeometryReader in
    /// the background (kept out of the draw path for 120 Hz cleanliness).
    @State private var trackedWidthHint: Double?

    private var widthProbe: some View {
        GeometryReader { proxy in
            Color.clear
                .onChange(of: proxy.size.width) { _, newWidth in
                    trackedWidthHint = Double(newWidth)
                }
                .onAppear { trackedWidthHint = Double(proxy.size.width) }
        }
    }

    // ─ Deterministic waveform profile ─────────────────────────────────────

    /// Seeded two-octave sine mix + per-bar jitter — the same shape for the
    /// same fractional position, every render (previews/tests identical).
    private static func barAmplitude(_ fraction: Double) -> CGFloat {
        let slow = 0.55 + 0.45 * sin(fraction * .pi * 2.2 + 0.7)
        let fast = 0.18 * sin(fraction * .pi * 17.3)
        let jitter = 0.08 * sin(fraction * 1_000.0)
        return CGFloat(min(max(slow + fast + jitter, 0.12), 1.0))
    }

    /// "m:ss.mmm" — the microsecond readout.
    private static func formatMicroseconds(_ us: Double) -> String {
        let totalMs = max(0, us) / 1_000
        let totalSeconds = Int(totalMs / 1_000)
        let ms = Int(totalMs.truncatingRemainder(dividingBy: 1_000))
        let hours = totalSeconds / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d.%03d", hours, minutes, seconds, ms)
        }
        return String(format: "%d:%02d.%03d", minutes, seconds, ms)
    }
}

#Preview("Scrubbing 48 kHz DAC clock") {
    WaveformProgressSlider(
        positionUs: 91_400_000,          // 1:31.400 — DAC-derived
        durationUs: 251_000_000,
        isPlaying: true,
        onScrubChange: { _ in },
        onScrubEnd: { _ in }
    )
    .padding(28)
    .background(StreamifyColors.bgBase)
}

#Preview("Indeterminate") {
    WaveformProgressSlider(positionUs: 0, durationUs: nil)
        .padding(28)
        .background(StreamifyColors.bgBase)
}
