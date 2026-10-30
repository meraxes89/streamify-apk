import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the full-player transport cluster.
//
// Port of ui/components/PlayerControls.kt: shuffle / previous / play-pause
// (with buffering state) / next / repeat, at the StreamifyDimens metrics —
// the 64 pt primary-filled play circle, 32 pt skips, 24 pt shuffle & repeat —
// with a .playPauseClick haptic on every control. Repeat is a three-state
// mode (0 off · 1 all · 2 one) rendered as repeat / repeat.1.
// ─────────────────────────────────────────────────────────────────────────────

public struct PlayerControls: View {
    public var isPlaying: Bool
    public var isBuffering: Bool = false
    public var isShuffle: Bool = false
    /// 0 = off · 1 = repeat all · 2 = repeat one.
    public var repeatMode: Int
    public var onTogglePlayPause: () -> Void
    public var onPrevious: () -> Void
    public var onNext: () -> Void
    public var onToggleShuffle: () -> Void
    public var onToggleRepeat: () -> Void

    public init(
        isPlaying: Bool,
        isBuffering: Bool = false,
        isShuffle: Bool = false,
        repeatMode: Int,
        onTogglePlayPause: @escaping () -> Void,
        onPrevious: @escaping () -> Void,
        onNext: @escaping () -> Void,
        onToggleShuffle: @escaping () -> Void,
        onToggleRepeat: @escaping () -> Void
    ) {
        self.isPlaying = isPlaying
        self.isBuffering = isBuffering
        self.isShuffle = isShuffle
        self.repeatMode = repeatMode
        self.onTogglePlayPause = onTogglePlayPause
        self.onPrevious = onPrevious
        self.onNext = onNext
        self.onToggleShuffle = onToggleShuffle
        self.onToggleRepeat = onToggleRepeat
    }

    public var body: some View {
        HStack {
            sideButton(
                icon: StreamifyIcon.shuffle,
                size: StreamifyDimens.shuffleButtonSize,
                isActive: isShuffle,
                label: "Shuffle",
                action: {
                    Haptics.play(.playPauseClick)
                    onToggleShuffle()
                }
            )
            Spacer()
            sideButton(
                icon: StreamifyIcon.backward,
                size: StreamifyDimens.skipButtonSize,
                isActive: false,
                label: "Previous",
                action: {
                    Haptics.play(.playPauseClick)
                    onPrevious()
                }
            )
            Spacer()
            playPauseButton
            Spacer()
            sideButton(
                icon: StreamifyIcon.forward,
                size: StreamifyDimens.skipButtonSize,
                isActive: false,
                label: "Next",
                action: {
                    Haptics.play(.playPauseClick)
                    onNext()
                }
            )
            Spacer()
            sideButton(
                icon: repeatIcon,
                size: StreamifyDimens.shuffleButtonSize,
                isActive: repeatMode != 0,
                label: "Repeat",
                action: {
                    Haptics.play(.playPauseClick)
                    onToggleRepeat()
                }
            )
        }
        .frame(maxWidth: 460)
    }

    private var repeatIcon: String {
        switch repeatMode {
        case 0: return StreamifyIcon.repeatAll
        case 1: return StreamifyIcon.repeatAll
        case 2: return StreamifyIcon.repeatOne
        default: return StreamifyIcon.repeatAll
        }
    }

    /// THE 64 pt primary-filled transport circle; buffering swaps the glyph
    /// for a spinner (Android PlaybackButtonState.BUFFERING).
    private var playPauseButton: some View {
        Button(action: {
            Haptics.play(.playPauseClick)
            onTogglePlayPause()
        }) {
            ZStack {
                Circle()
                    .fill(StreamifyColors.primary)
                    .frame(width: StreamifyDimens.playButtonSize, height: StreamifyDimens.playButtonSize)
                if isBuffering {
                    ProgressView()
                        .tint(StreamifyColors.textOnActiveChip)
                } else {
                    Image(systemName: isPlaying ? StreamifyIcon.pause : StreamifyIcon.play)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(StreamifyColors.textOnActiveChip)
                }
            }
            .frame(width: StreamifyDimens.playButtonSize, height: StreamifyDimens.playButtonSize)
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel(isPlaying ? "Pause" : "Play")
    }

    /// The skips (32 pt) and mode glyphs (24 pt) inside 48 pt tap targets.
    private func sideButton(
        icon: String,
        size: CGFloat,
        isActive: Bool,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(
                    isActive
                        ? StreamifyColors.primary
                        : StreamifyColors.textMain.opacity(0.85)
                )
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel(label)
    }
}

#Preview {
    VStack(spacing: 48) {
        PlayerControls(
            isPlaying: true,
            isShuffle: true,
            repeatMode: 1,
            onTogglePlayPause: {},
            onPrevious: {},
            onNext: {},
            onToggleShuffle: {},
            onToggleRepeat: {}
        )
        PlayerControls(
            isPlaying: false,
            isBuffering: true,
            repeatMode: 2,
            onTogglePlayPause: {},
            onPrevious: {},
            onNext: {},
            onToggleShuffle: {},
            onToggleRepeat: {}
        )
    }
    .padding(40)
    .background(StreamifyColors.bgPlayer)
}
