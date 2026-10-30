import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSongVideoSwitcher — the song/video segmented toggle. Port of
// ui/components/yt/YtSongVideoSwitcher.kt: a 140×32 capsule well with a sliding
// BgChipActive capsule behind the active half (Android animateDpAsState with a
// bouncy spring → Motion.snappy here), tap-only segments, and the magnetic
// detent haptic on the actual flip.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSongVideoSwitcher: View {
    public var isVideo: Bool
    public var onToggle: (Bool) -> Void

    public init(isVideo: Bool, onToggle: @escaping (Bool) -> Void) {
        self.isVideo = isVideo
        self.onToggle = onToggle
    }

    public var body: some View {
        ZStack(alignment: .leading) {
            Capsule()
                .fill(StreamifyColors.bgSurfaceElevated)
                .overlay(Capsule().strokeBorder(StreamifyColors.borderChip, lineWidth: 1))
            Capsule()
                .fill(StreamifyColors.bgChipActive)
                .frame(width: 64, height: 26)
                .offset(x: isVideo ? 70 : 0)
                .padding(.leading, 3)
            HStack(spacing: 0) {
                segment(
                    title: "Song",
                    icon: StreamifyIcon.musicNote,
                    isActive: !isVideo
                ) {
                    if isVideo {
                        Haptics.play(.scrubTick)
                        onToggle(false)
                    }
                }
                segment(
                    title: "Video",
                    icon: StreamifyIcon.video,
                    isActive: isVideo
                ) {
                    if !isVideo {
                        Haptics.play(.scrubTick)
                        onToggle(true)
                    }
                }
            }
        }
        .frame(width: 140, height: 32)
        .animation(Motion.snappy, value: isVideo)
    }

    private func segment(
        title: String,
        icon: String,
        isActive: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: StreamifyDimens.spaceXXS) {
                Image(systemName: icon)
                    .font(.system(size: 11))
                Text(title)
                    .font(StreamifyType.chipText)
            }
            .foregroundStyle(isActive ? StreamifyColors.textOnActiveChip : StreamifyColors.textMain)
            .frame(maxWidth: .infinity)
            .frame(height: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SongVideoDemo: View {
    @State private var isVideo = false

    var body: some View {
        YtSongVideoSwitcher(isVideo: isVideo) { isVideo = $0 }
    }
}

#Preview("Song / Video switcher") {
    SongVideoDemo()
        .padding(StreamifyDimens.spaceXL)
        .background(StreamifyColors.bgBase)
}
