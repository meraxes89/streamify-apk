import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — THE standard track row.
//
// Native port of ui/components/TrackListItem.kt: 48 pt art (AsyncImage with a
// deterministic inline gradient fallback), title + artist, mono-digit
// duration, an "E" explicit chip when flagged, the optional like, and the
// now-playing state (a 3-bar mini equalizer over the art + title in primary).
// SwipeableTrackListItem wraps the row in a Button + context-menu modifiers
// (and swipe-actions for List hosts: leading = queue, trailing = like/queue).
// Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct TrackListItem: View {
    public let track: VirtualShelfTrack
    public var isCurrent: Bool = false
    public var isPlaying: Bool = true
    public var isExplicit: Bool = false
    public var onPlay: () -> Void
    public var onLike: (() -> Void)? = nil

    public init(
        track: VirtualShelfTrack,
        isCurrent: Bool = false,
        isPlaying: Bool = true,
        isExplicit: Bool = false,
        onPlay: @escaping () -> Void,
        onLike: (() -> Void)? = nil
    ) {
        self.track = track
        self.isCurrent = isCurrent
        self.isPlaying = isPlaying
        self.isExplicit = isExplicit
        self.onPlay = onPlay
        self.onLike = onLike
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                RowArt(seed: track.title, artworkUrl: track.artworkUrl)
                if isCurrent {
                    Rectangle()
                        .fill(StreamifyColors.bgBase.opacity(0.6))
                        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
                    NowPlayingBars(isActive: isPlaying)
                }
            }
            .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Text(track.title)
                        .font(StreamifyType.songTitle)
                        .foregroundStyle(isCurrent ? StreamifyColors.primary : StreamifyColors.textMain)
                        .lineLimit(1)
                    if isExplicit {
                        Text("E")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(StreamifyColors.explicit)
                            .frame(width: 14, height: 14)
                            .background(StreamifyColors.bgChipInactive, in: StreamifyShapes.chip)
                    }
                }
                Text(track.artist)
                    .font(StreamifyType.songArtist)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)

            Text(PlaybackFormatting.formatPlaybackTime(track.durationSec * 1_000))
                .font(StreamifyType.seekbarTime.monospacedDigit())
                .foregroundStyle(StreamifyColors.textTertiary)

            if let onLike {
                Button {
                    Haptics.play(.likeHeartbeat)
                    onLike()
                } label: {
                    Image(systemName: track.isLiked ? StreamifyIcon.heartFill : StreamifyIcon.heart)
                        .font(.system(size: 14))
                        .foregroundStyle(track.isLiked ? StreamifyColors.primary : StreamifyColors.textSecondary)
                        .heartPop(isLiked: track.isLiked)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: StreamifyDimens.trackRowHeight)
        .contentShape(Rectangle())
        .onTapGesture { onPlay() }
    }
}

/// The List/swipe-enabled wrapper: Button semantics + the native context menu
/// (Android's combinedClickable long-press) + swipeActions for List hosts —
/// leading = add to queue, trailing = like / queue.
public struct SwipeableTrackListItem: View {
    public let track: VirtualShelfTrack
    public var isCurrent: Bool = false
    public var isPlaying: Bool = true
    public var isExplicit: Bool = false
    public var onPlay: () -> Void
    public var onLike: (() -> Void)? = nil
    public var onQueue: (() -> Void)? = nil
    public var onContextMenu: ((String) -> Void)? = nil

    public init(
        track: VirtualShelfTrack,
        isCurrent: Bool = false,
        isPlaying: Bool = true,
        isExplicit: Bool = false,
        onPlay: @escaping () -> Void,
        onLike: (() -> Void)? = nil,
        onQueue: (() -> Void)? = nil,
        onContextMenu: ((String) -> Void)? = nil
    ) {
        self.track = track
        self.isCurrent = isCurrent
        self.isPlaying = isPlaying
        self.isExplicit = isExplicit
        self.onPlay = onPlay
        self.onLike = onLike
        self.onQueue = onQueue
        self.onContextMenu = onContextMenu
    }

    public var body: some View {
        Button {
            onPlay()
        } label: {
            TrackListItem(
                track: track,
                isCurrent: isCurrent,
                isPlaying: isPlaying,
                isExplicit: isExplicit,
                onPlay: onPlay,
                onLike: onLike
            )
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button {
                onContextMenu?("Play Next")
            } label: {
                Label("Play Next", systemImage: "play.rectangle.on.rectangle")
            }
            Button {
                onContextMenu?("Add to Queue")
            } label: {
                Label("Add to Queue", systemImage: StreamifyIcon.queue)
            }
            if let onLike {
                Button {
                    onContextMenu?(track.isLiked ? "Unlike" : "Like")
                    onLike()
                } label: {
                    Label(
                        track.isLiked ? "Unlike" : "Like",
                        systemImage: track.isLiked ? StreamifyIcon.heartFill : StreamifyIcon.heart
                    )
                }
            }
            Button {
                onContextMenu?("Go to Artist")
            } label: {
                Label("Go to Artist", systemImage: StreamifyIcon.avatar)
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if let onQueue {
                Button {
                    Haptics.play(.queueGrab)
                    onQueue()
                } label: {
                    Label("Queue", systemImage: StreamifyIcon.queue)
                }
                .tint(StreamifyColors.primary)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if let onLike {
                Button {
                    Haptics.play(.likeHeartbeat)
                    onContextMenu?("Like")
                    onLike()
                } label: {
                    Label("Like", systemImage: StreamifyIcon.heart)
                }
                .tint(StreamifyColors.primaryAccent)
            }
            if let onQueue {
                Button {
                    Haptics.play(.queueGrab)
                    onContextMenu?("Queue")
                    onQueue()
                } label: {
                    Label("Queue", systemImage: StreamifyIcon.queue)
                }
                .tint(StreamifyColors.accentBlue)
            }
        }
    }
}

/// 48 pt row art: AsyncImage with the seeded preset-gradient fallback.
private struct RowArt: View {
    let seed: String
    let artworkUrl: String?

    var body: some View {
        ZStack {
            if let artworkUrl, let url = URL(string: artworkUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        gradientPlate
                    }
                }
            } else {
                gradientPlate
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
    }

    private var gradientPlate: some View {
        ZStack {
            Rectangle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed)))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.85))
        }
    }
}

/// Inline 3-bar now-playing equalizer (Android's NowPlayingIndicator): a pure
/// function of time drawn on Canvas — paused when the transport stops.
private struct NowPlayingBars: View {
    var isActive: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !isActive)) { timeline in
            Canvas { context, size in
                let t = timeline.date.timeIntervalSinceReferenceDate
                let barWidth = size.width / 5
                for i in 0..<3 {
                    let phase = t * 5.2 + Double(i) * 0.9
                    let amp = 0.45 + 0.55 * abs(sin(phase))
                    let height = size.height * amp
                    let x = size.width / 2 + (CGFloat(i) - 1) * barWidth * 1.4 - barWidth / 2
                    let rect = CGRect(
                        x: x,
                        y: (size.height - height) / 2,
                        width: barWidth,
                        height: height
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: barWidth / 2),
                        with: .color(StreamifyColors.primary)
                    )
                }
            }
        }
        .frame(width: 22, height: 16)
    }
}

#Preview("Plain + swipeable") {
    VStack(spacing: 0) {
        ForEach(Array(PreviewCatalog.tracks.prefix(3).enumerated()), id: \.element.id) { index, track in
            SwipeableTrackListItem(
                track: track,
                isCurrent: index == 0,
                isPlaying: true,
                isExplicit: index == 1,
                onPlay: {},
                onLike: {},
                onQueue: {},
                onContextMenu: { _ in }
            )
        }
    }
    .padding(.horizontal)
    .background(StreamifyColors.bgBase)
}
