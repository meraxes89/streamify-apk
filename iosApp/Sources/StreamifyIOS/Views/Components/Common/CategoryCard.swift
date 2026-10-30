import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the wide category / mood tile.
//
// Port of ui/components/CategoryCard.kt (the rectangular sibling of the YT
// wave's square YtGenreCard): the mood's preset gradient across a wide
// rounded rectangle, the bold title pinned bottom-left, and the pressable
// card feel + tap-through onOpen. Fixtures: PreviewCatalog.moods.
// ─────────────────────────────────────────────────────────────────────────────

public struct CategoryCard: View {
    public let mood: MoodGenre
    public var onOpen: (() -> Void)? = nil

    public init(mood: MoodGenre, onOpen: (() -> Void)? = nil) {
        self.mood = mood
        self.onOpen = onOpen
    }

    public var body: some View {
        Button {
            Haptics.play(.scrubTick)
            onOpen?()
        } label: {
            ZStack(alignment: .bottomLeading) {
                LinearGradient(
                    colors: mood.gradient,
                    startPoint: .top,
                    endPoint: .bottom
                )
                // Tilted sheen plate (Android's rotated category-art shadow).
                RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                    .fill(Color.black.opacity(0.18))
                    .frame(width: 56, height: 56)
                    .rotationEffect(.degrees(22))
                    .offset(x: 46, y: 18)
                Text(mood.name)
                    .font(StreamifyType.titleLarge.weight(.bold))
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(2)
                    .padding(StreamifyDimens.spaceMD)
            }
            .frame(height: 96)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview {
    VStack(spacing: StreamifyDimens.spaceMD) {
        CategoryCard(mood: PreviewCatalog.moods[0], onOpen: {})
        CategoryCard(mood: PreviewCatalog.moods[3], onOpen: {})
    }
    .padding(StreamifyDimens.spaceLG)
    .frame(width: 340)
    .background(StreamifyColors.bgBase)
}
