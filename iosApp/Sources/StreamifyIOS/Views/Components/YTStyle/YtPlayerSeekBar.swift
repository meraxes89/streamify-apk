import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPlayerSeekBar — the full player's scrub/seek bar (Android
// ui/components/yt/YtPlayerSeekBar.kt, "Universal Tap & Drag Scrubbing").
// The Compose drawWithCache playhead maps onto a plain SwiftUI track: a 4 pt
// BorderSubtle capsule, a TextMain progress fill and a 12 pt thumb that
// magnifies while scrubbing. One DragGesture(minimumDistance: 0) gives both
// tap-to-seek and 1:1 finger scrubbing; a .scrubTick haptic fires each time
// the scrubbed position crosses a 5-second boundary (the Android bucketed
// onScrubPositionChanged contract). Time labels use PlaybackFormatting.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtPlayerSeekBar: View {
    public var positionMs: Double
    public var durationMs: Double?
    public var onScrub: ((Double) -> Void)? = nil
    public var onScrubEnd: ((Double) -> Void)? = nil

    @State private var scrubFraction: Double?
    @State private var lastTickBoundary = -1

    public init(
        positionMs: Double,
        durationMs: Double?,
        onScrub: ((Double) -> Void)? = nil,
        onScrubEnd: ((Double) -> Void)? = nil
    ) {
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.onScrub = onScrub
        self.onScrubEnd = onScrubEnd
    }

    private var isScrubbing: Bool { scrubFraction != nil }

    private var effectiveDuration: Double? {
        guard let durationMs, durationMs > 0 else { return nil }
        return durationMs
    }

    private var fillFraction: Double {
        if let scrubFraction { return scrubFraction }
        guard let effectiveDuration else { return 0 }
        return min(max(positionMs / effectiveDuration, 0), 1)
    }

    private var displayMs: Double {
        if let scrubFraction, let effectiveDuration {
            return scrubFraction * effectiveDuration
        }
        if let effectiveDuration {
            return min(max(positionMs, 0), effectiveDuration)
        }
        return max(positionMs, 0)
    }

    public var body: some View {
        VStack(spacing: 2) {
            GeometryReader { proxy in
                let width = proxy.size.width
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(StreamifyColors.borderSubtle)
                        .frame(width: width, height: StreamifyDimens.seekBarHeight)
                    Capsule()
                        .fill(StreamifyColors.textMain)
                        .frame(width: width * CGFloat(fillFraction), height: StreamifyDimens.seekBarHeight)
                    Circle()
                        .fill(StreamifyColors.textMain.opacity(0.2))
                        .frame(width: 26, height: 26)
                        .offset(x: width * CGFloat(fillFraction) - 13)
                        .opacity(isScrubbing ? 1 : 0)
                    Circle()
                        .fill(StreamifyColors.textMain)
                        .frame(
                            width: StreamifyDimens.seekBarThumb,
                            height: StreamifyDimens.seekBarThumb
                        )
                        .scaleEffect(isScrubbing ? 1.4 : 1)
                        .offset(x: width * CGFloat(fillFraction) - StreamifyDimens.seekBarThumb / 2)
                }
                .frame(width: width, height: proxy.size.height)
                .contentShape(Rectangle())
                .animation(Motion.snappy, value: isScrubbing)
                .gesture(scrubGesture(width: width))
            }
            .frame(height: 32)
            SeekTimeLabels(displayMs: displayMs, durationMs: effectiveDuration)
        }
    }

    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard let effectiveDuration, width > 0 else { return }
                let fraction = clampedFraction(Double(value.location.x / width))
                scrubFraction = fraction
                fireBoundaryTick(at: fraction * effectiveDuration)
                onScrub?(fraction * effectiveDuration)
            }
            .onEnded { value in
                guard let effectiveDuration, width > 0 else { return }
                let fraction = clampedFraction(Double(value.location.x / width))
                scrubFraction = nil
                lastTickBoundary = -1
                onScrubEnd?(fraction * effectiveDuration)
            }
    }

    private func clampedFraction(_ raw: Double) -> Double {
        min(max(raw, 0), 1)
    }

    /// One .scrubTick per crossed 5-second boundary of scrubbed audio.
    private func fireBoundaryTick(at ms: Double) {
        let boundary = Int(ms / 5000)
        if boundary != lastTickBoundary {
            lastTickBoundary = boundary
            Haptics.play(.scrubTick)
        }
    }
}

// MARK: - Leaf time labels (recompose at most 1 Hz during playback)

private struct SeekTimeLabels: View {
    var displayMs: Double
    var durationMs: Double?

    var body: some View {
        HStack {
            Text(PlaybackFormatting.formatPlaybackTime(Int(displayMs)))
            Spacer()
            Text(
                durationMs.map { PlaybackFormatting.formatPlaybackTime(Int($0)) }
                    ?? "--:--"
            )
        }
        .font(StreamifyType.seekbarTime)
        .foregroundStyle(StreamifyColors.textSecondary)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Player seek bar") {
    SeekBarDemo()
        .padding(.horizontal, 32)
        .frame(maxWidth: 560)
        .background(StreamifyColors.bgPlayer)
}

private struct SeekBarDemo: View {
    @State private var position: Double = 92_000

    var body: some View {
        VStack(spacing: 32) {
            YtPlayerSeekBar(
                positionMs: position,
                durationMs: Double(PreviewCatalog.tracks[0].durationSec * 1_000),
                onScrub: { _ in },
                onScrubEnd: { position = $0 }
            )
            YtPlayerSeekBar(positionMs: position, durationMs: nil)
        }
    }
}
