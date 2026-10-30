import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — THE docked 64 pt mini player (upgrades the old
// Views/MiniPlayerView.swift).
//
// Native port of ui/components/MiniPlayerBar.kt: 48 pt art (deterministic
// gradient fallback), title/artist single-line marquee text, transport
// (previous · play/pause with buffering spinner · next), a 2 pt micro-progress
// strip flush at the bottom edge, and the horizontal swipe-to-skip gesture —
// 1:1 finger tracking through LiquidMorphGeometry.swipeResistanceOffset past
// the 80 pt trigger, a peek preview of the destination title via
// swipePeekFraction, the .skipTrigger haptic exactly at arming, and a
// Motion.settle spring-back on release. VolumeSliderView (the old
// MiniPlayerView.swift volume row) lives here until that file is replaced.
// Fixtures: PreviewCatalog.playingSnapshot / tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct MiniPlayerBar: View {
    // PlaybackUiState is module-internal (Playback/PlaybackModels.swift), so
    // the state property + initializer stay internal; the type itself is the
    // module's public mini-player surface once the engine models go public.
    let state: PlaybackUiState
    var nextTrackTitle: String?
    var previousTrackTitle: String?
    var onPrevious: () -> Void
    var onToggle: () -> Void
    var onNext: () -> Void
    var onOpenPlayer: () -> Void

    @State private var rawDrag: CGFloat = 0
    @State private var armed = false

    init(
        state: PlaybackUiState,
        nextTrackTitle: String? = nil,
        previousTrackTitle: String? = nil,
        onPrevious: @escaping () -> Void,
        onToggle: @escaping () -> Void,
        onNext: @escaping () -> Void,
        onOpenPlayer: @escaping () -> Void
    ) {
        self.state = state
        self.nextTrackTitle = nextTrackTitle
        self.previousTrackTitle = previousTrackTitle
        self.onPrevious = onPrevious
        self.onToggle = onToggle
        self.onNext = onNext
        self.onOpenPlayer = onOpenPlayer
    }

    public var body: some View {
        let resisted = LiquidMorphGeometry.swipeResistanceOffset(
            rawDragPt: Double(rawDrag),
            thresholdPt: LiquidMorphGeometry.skipTrigger
        )
        HStack(spacing: StreamifyDimens.spaceSM + 2) {
            MiniArtView(
                artworkUrl: state.currentTrack?.artworkUrl,
                seed: state.currentTrack?.title ?? "streamify"
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(state.currentTrack?.title ?? "Nothing playing")
                    .font(StreamifyType.songTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(subtitle)
                    .font(StreamifyType.songArtist)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Button {
                onPrevious()
            } label: {
                Image(systemName: StreamifyIcon.backward)
                    .font(.system(size: 16))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
            Button {
                Haptics.play(.playPauseClick)
                onToggle()
            } label: {
                ZStack {
                    if state.isBuffering {
                        ProgressView()
                            .tint(StreamifyColors.textMain)
                    } else {
                        Image(systemName: state.isPlaying ? StreamifyIcon.pause : StreamifyIcon.play)
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(StreamifyColors.textMain)
                    }
                }
                .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
            Button {
                onNext()
            } label: {
                Image(systemName: StreamifyIcon.forward)
                    .font(.system(size: 16))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity)
        .frame(height: StreamifyDimens.miniPlayerHeight)
        .background(StreamifyColors.bgMiniPlayer)
        .overlay(alignment: .bottom) { microProgressStrip }
        .offset(x: resisted)
        .overlay {
            // Peek preview of the destination track (counter-parallax reveal).
            SwipePeekOverlay(
                rawDrag: rawDrag,
                threshold: LiquidMorphGeometry.skipTrigger,
                nextTitle: nextTrackTitle,
                previousTitle: previousTrackTitle
            )
            .allowsHitTesting(false)
        }
        .contentShape(Rectangle())
        .onTapGesture { onOpenPlayer() }
        .gesture(swipeToSkipGesture)
    }

    private var subtitle: String {
        guard let track = state.currentTrack else {
            return "Rust core engine · snapshots pushed on change"
        }
        var parts = [track.artist]
        if let position = state.positionForDisplayMs {
            parts.append(PlaybackFormatting.formatPlaybackTime(position))
        }
        if let duration = state.durationMs {
            parts.append(PlaybackFormatting.formatPlaybackTime(duration))
        }
        return parts.joined(separator: " · ")
    }

    /// The 2 pt determinate strip: DAC-clock position over duration.
    private var microProgressStrip: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Rectangle().fill(StreamifyColors.divider)
                Rectangle()
                    .fill(StreamifyColors.activeControl)
                    .frame(width: proxy.size.width * (state.progressFraction ?? 0))
            }
        }
        .frame(height: StreamifyDimens.miniProgressBarHeight)
    }

    /// Swipe-to-skip: raw finger displacement drives the resisted visual
    /// offset; the peek fraction arms the .skipTrigger haptic exactly when it
    /// saturates (85 % of the 80 pt trigger), and release beyond the
    /// threshold commits next()/previous().
    private var swipeToSkipGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                rawDrag = value.translation.width
                let peek = LiquidMorphGeometry.swipePeekFraction(
                    rawDragPt: Double(rawDrag),
                    thresholdPt: LiquidMorphGeometry.skipTrigger
                )
                if peek >= 1.0, !armed {
                    armed = true
                    Haptics.play(.skipTrigger)
                } else if armed, peek < 0.75 {
                    // Finger retreated most of the way back: disarm the skip.
                    armed = false
                }
            }
            .onEnded { value in
                if armed, abs(value.translation.width) > LiquidMorphGeometry.skipTrigger {
                    if value.translation.width < 0 { onNext() } else { onPrevious() }
                }
                armed = false
                withAnimation(Motion.settle) { rawDrag = 0 }
            }
    }
}

/// 48 pt cover: AsyncImage when artwork resolves, else the deterministic
/// inline gradient plate + note glyph (no cross-component import by design).
private struct MiniArtView: View {
    let artworkUrl: String?
    let seed: String

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
        .frame(width: StreamifyDimens.miniPlayerArt, height: StreamifyDimens.miniPlayerArt)
        .clipShape(
            RoundedRectangle(
                cornerRadius: LiquidMorphGeometry.miniCoverCorner,
                style: .continuous
            )
        )
    }

    private var gradientPlate: some View {
        ZStack {
            Rectangle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed)))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.85))
        }
    }
}

/// The destination-title peek: fades in via swipePeekFraction while the bar
/// content slides away with the resisted drag (counter-translated parallax).
private struct SwipePeekOverlay: View {
    var rawDrag: CGFloat
    var threshold: Double
    var nextTitle: String?
    var previousTitle: String?

    var body: some View {
        let raw = Double(rawDrag)
        if abs(raw) > 1 {
            let towardNext = raw < 0
            let peek = LiquidMorphGeometry.swipePeekFraction(rawDragPt: raw, thresholdPt: threshold)
            if peek > 0.01, let title = towardNext ? nextTitle : previousTitle {
                HStack(spacing: 6) {
                    if !towardNext {
                        Image(systemName: StreamifyIcon.backward)
                            .font(.system(size: 12))
                            .foregroundStyle(StreamifyColors.textSecondary)
                    }
                    Text(title)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                    if towardNext {
                        Image(systemName: StreamifyIcon.forward)
                            .font(.system(size: 12))
                            .foregroundStyle(StreamifyColors.textSecondary)
                    }
                }
                .frame(maxWidth: 220)
                .offset(x: -rawDrag * 0.5)
                .opacity(peek)
            }
        }
    }
}

/// The volume row (ported from the shared CMP VolumeSlider + the Phase-5
/// MiniPlayerView volume control): a native iOS/macOS Slider bound to
/// callbacks — mute toggle round-trips through the engine, never local echo.
public struct VolumeSliderView: View {
    public let volume: Double
    public let onVolumeChange: (Double) -> Void

    public init(volume: Double, onVolumeChange: @escaping (Double) -> Void) {
        self.volume = volume
        self.onVolumeChange = onVolumeChange
    }

    public var body: some View {
        let level = min(max(volume, 0), 1)
        return HStack(spacing: StreamifyDimens.spaceMD) {
            Button {
                onVolumeChange(level > 0 ? 0 : 0.8)
            } label: {
                Image(systemName: level <= 0 ? StreamifyIcon.speakerMuted : StreamifyIcon.speaker)
                    .font(.system(size: 15))
                    .foregroundStyle(
                        level <= 0 ? StreamifyColors.primary : StreamifyColors.textSecondary
                    )
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            Slider(
                value: Binding(
                    get: { level },
                    set: { onVolumeChange($0) }
                )
            )
            .tint(StreamifyColors.activeControl)
            Text("\(Int((level * 100).rounded()))%")
                .font(StreamifyType.caption.monospacedDigit())
                .foregroundStyle(StreamifyColors.textTertiary)
                .frame(width: 38, alignment: .trailing)
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        MiniPlayerBar(
            state: PlaybackUiState(PreviewCatalog.playingSnapshot),
            nextTrackTitle: PreviewCatalog.tracks[1].title,
            previousTrackTitle: PreviewCatalog.tracks[11].title,
            onPrevious: {},
            onToggle: {},
            onNext: {},
            onOpenPlayer: {}
        )
        VolumeSliderView(volume: 0.7, onVolumeChange: { _ in })
    }
    .padding()
    .background(StreamifyColors.bgBase)
}
