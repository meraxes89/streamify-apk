import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtGenreCard — a moods/genre gradient tile. Port of
// ui/components/yt/YtGenreCard.kt (52 dp accent-strip row) expressed the YTM
// way: the whole tile carries the genre's gradient, dark scrim under a big
// bold white title, pressable-card tap into the genre page.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtGenreCard: View {
    public var mood: MoodGenre
    public var onOpen: () -> Void

    public init(mood: MoodGenre, onOpen: @escaping () -> Void) {
        self.mood = mood
        self.onOpen = onOpen
    }

    private var fillGradient: LinearGradient {
        guard mood.gradient.count >= 2 else {
            return StreamifyGradients.linear(StreamifyGradients.brand)
        }
        return StreamifyGradients.linear(mood.gradient)
    }

    public var body: some View {
        Button(action: onOpen) {
            ZStack(alignment: .bottomLeading) {
                fillGradient
                LinearGradient(
                    colors: [Color.black.opacity(0.06), Color.black.opacity(0.38)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                Text(mood.name)
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .padding(StreamifyDimens.spaceMD)
            }
            .frame(height: 64)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Genre tiles") {
    LazyVGrid(
        columns: [
            GridItem(.adaptive(minimum: 160), spacing: StreamifyDimens.spaceSM)
        ],
        spacing: StreamifyDimens.spaceSM
    ) {
        ForEach(PreviewCatalog.moods) { mood in
            YtGenreCard(mood: mood, onOpen: {})
        }
    }
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
