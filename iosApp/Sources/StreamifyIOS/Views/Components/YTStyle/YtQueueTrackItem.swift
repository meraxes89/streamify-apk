import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtQueueTrackItem — one queue row (Android ui/components/yt/YtQueueTrackItem.
// kt: drag handle, art, title/artist, long-press reorder). The Swift port
// keeps the drag handle, the row index (which becomes the live 3-bar
// equalizer for the current track), the gradient-fallback art, the formatted
// duration and a trailing swipe hint. The equalizer is an inlined private
// TimelineView+Canvas twin of YtActiveEqualizer (wave isolation: no
// cross-file reference), frozen while paused.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtQueueTrackItem: View {
    public var track: VirtualShelfTrack
    public var index: Int
    public var isCurrent: Bool
    public var isPlaying: Bool = true
    public var onTap: () -> Void = {}

    public init(
        track: VirtualShelfTrack,
        index: Int,
        isCurrent: Bool,
        isPlaying: Bool = true,
        onTap: @escaping () -> Void = {}
    ) {
        self.track = track
        self.index = index
        self.isCurrent = isCurrent
        self.isPlaying = isPlaying
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                Image(systemName: StreamifyIcon.list)
                    .font(.system(size: 14))
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .frame(width: 18)
                    .accessibilityHidden(true)

                Group {
                    if isCurrent {
                        QueueEqualizerBars(isActive: isPlaying)
                            .frame(width: 18, height: 16)
                    } else {
                        Text("\(index)")
                            .font(.system(size: 12, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(StreamifyColors.textTertiary)
                    }
                }
                .frame(width: 18)

                ZStack {
                    StreamifyGradients.linear(StreamifyGradients.preset(forSeed: track.id))
                    Image(systemName: StreamifyIcon.musicNote)
                        .font(.system(size: 18))
                        .foregroundStyle(Color.white.opacity(0.85))
                }
                .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)
                .clipShape(StreamifyShapes.thumbnailSmall)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(StreamifyType.songTitle)
                        .foregroundStyle(isCurrent ? StreamifyColors.activeControl : StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: StreamifyDimens.spaceSM)

                Text(PlaybackFormatting.formatPlaybackTime(track.durationSec * 1_000))
                    .font(StreamifyType.caption)
                    .monospacedDigit()
                    .foregroundStyle(StreamifyColors.textSecondary)

                HStack(spacing: 0) {
                    Image(systemName: StreamifyIcon.chevronLeft)
                        .font(.system(size: 8, weight: .bold))
                    Image(systemName: StreamifyIcon.chevronRight)
                        .font(.system(size: 8, weight: .bold))
                }
                .foregroundStyle(StreamifyColors.textTertiary.opacity(0.7))
                .frame(width: 20)
                .accessibilityLabel("Swipe for actions")
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .frame(height: StreamifyDimens.trackRowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel("\(track.title) by \(track.artist)")
    }
}

// MARK: - Inlined YtActiveEqualizer twin (3 bars, frozen while paused)

private struct QueueEqualizerBars: View {
    var isActive: Bool

    private static let frozenHeights: [Double] = [0.55, 0.82, 0.45]
    private static let speeds: [Double] = [5.4, 4.3, 6.1]
    private static let phases: [Double] = [0.0, 1.2, 2.4]

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !isActive)) { timeline in
            Canvas { context, size in
                let barWidth = size.width / 5
                let gap = barWidth / 2
                let t = timeline.date.timeIntervalSinceReferenceDate
                for bar in 0..<3 {
                    let fraction = isActive
                        ? min(max(0.5 + 0.45 * sin(t * Self.speeds[bar] + Self.phases[bar]), 0.15), 1)
                        : Self.frozenHeights[bar]
                    let barHeight = size.height * CGFloat(fraction)
                    let rect = CGRect(
                        x: CGFloat(bar) * (barWidth + gap),
                        y: size.height - barHeight,
                        width: barWidth,
                        height: barHeight
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: barWidth / 2),
                        with: .color(StreamifyColors.activeControl)
                    )
                }
            }
        }
    }
}

#Preview("Queue track items") {
    VStack(spacing: 0) {
        ForEach(Array(PreviewCatalog.tracks.prefix(5).enumerated()), id: \.element.id) { index, track in
            YtQueueTrackItem(
                track: track,
                index: index,
                isCurrent: index == PreviewCatalog.queueState.currentIndex,
                isPlaying: true
            )
        }
    }
    .frame(maxWidth: 560)
    .background(StreamifyColors.bgBase)
}
