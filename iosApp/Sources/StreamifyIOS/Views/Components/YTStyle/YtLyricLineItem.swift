import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtLyricLineItem — one synced-lyrics line row. Port of
// ui/components/yt/YtLyricLineItem.kt: active = LyricsActive type + TextMain,
// inactive = LyricsInactive + TextTertiary, GPU-side color + leading-anchored
// spring scale (Android animateColorAsState + 1.05 bouncy scale), tap to seek.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtLyricLineItem: View {
    public var text: String
    public var isActive: Bool
    public var onSelect: () -> Void

    public init(text: String, isActive: Bool, onSelect: @escaping () -> Void) {
        self.text = text
        self.isActive = isActive
        self.onSelect = onSelect
    }

    public var body: some View {
        Button(action: onSelect) {
            Text(text)
                .font(isActive ? StreamifyType.lyricsActive : StreamifyType.lyricsInactive)
                .foregroundStyle(isActive ? StreamifyColors.textMain : StreamifyColors.textTertiary)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.vertical, StreamifyDimens.spaceMD)
        .padding(.horizontal, StreamifyDimens.spaceXXL)
        .scaleEffect(isActive ? 1.02 : 1, anchor: .leading)
        .animation(Motion.bouncy, value: isActive)
    }
}

private struct LyricListDemo: View {
    @State private var activeIndex = 1

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(PreviewCatalog.lyrics.lines.enumerated()), id: \.element.id) { index, line in
                YtLyricLineItem(text: line.text, isActive: index == activeIndex) {
                    activeIndex = index
                }
            }
        }
        .background(StreamifyColors.bgBase)
    }
}

#Preview("Lyric lines") {
    LyricListDemo()
}
