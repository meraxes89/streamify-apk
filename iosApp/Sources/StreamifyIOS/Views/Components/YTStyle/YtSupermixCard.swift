import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSupermixCard — the supermix shelf card (Android
// ui/components/yt/YtSupermixCard.kt: 150 dp art over a legibility scrim with
// a circular play badge). The Swift port renders the synthetic gradient art
// deterministically from a seed (no image dependency), keeps the small logo
// chip and expresses the combinedClickable press via .pressableCard.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSupermixCard: View {
    public var title: String
    public var subtitle: String
    public var seed: String
    public var onTap: () -> Void = {}

    public init(
        title: String,
        subtitle: String,
        seed: String,
        onTap: @escaping () -> Void = {}
    ) {
        self.title = title
        self.subtitle = subtitle
        self.seed = seed
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .topLeading) {
                    StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed))
                    Image(systemName: StreamifyIcon.musicNoteList)
                        .font(.system(size: 52))
                        .foregroundStyle(Color.white.opacity(0.35))
                    ZStack {
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                            .fill(StreamifyColors.bgCard)
                            .frame(width: 26, height: 26)
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                            .strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1)
                            .frame(width: 26, height: 26)
                        Image(systemName: StreamifyIcon.logo)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(StreamifyColors.primary)
                    }
                    .padding(StreamifyDimens.spaceSM)
                }
                .frame(width: StreamifyDimens.cardArtSize, height: StreamifyDimens.cardArtSize)
                .clipShape(StreamifyShapes.card)

                Text(title)
                    .font(StreamifyType.songTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(subtitle)
                    .font(StreamifyType.cardSubtitle)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(2)
            }
            .frame(width: StreamifyDimens.cardWidth, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel("\(title), \(subtitle)")
    }
}

#Preview("Supermix card") {
    VStack(spacing: 32) {
        YtSupermixCard(
            title: PreviewCatalog.dailyMixes[0].title,
            subtitle: PreviewCatalog.dailyMixes[0].subtitle ?? "",
            seed: PreviewCatalog.dailyMixes[0].id
        )
        YtSupermixCard(
            title: PreviewCatalog.dailyMixes[3].title,
            subtitle: PreviewCatalog.dailyMixes[3].subtitle ?? "",
            seed: PreviewCatalog.dailyMixes[3].id
        )
    }
    .padding(24)
    .background(StreamifyColors.bgBase)
}
