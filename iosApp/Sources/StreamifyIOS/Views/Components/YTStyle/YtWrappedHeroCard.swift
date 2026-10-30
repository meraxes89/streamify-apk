import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtWrappedHeroCard — the Wrapped stats hero. Port of
// ui/components/yt/YtWrappedHeroCard.kt: the plain BgSurfaceElevated surface is
// lifted to the shared brand gradient with a legibility scrim; big headline
// value, a row of StatBlocks (label + value + optional #rank chip), and a
// trailing symbol glyph.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtWrappedHeroCard: View {
    public var headline: WrappedStat
    public var stats: [WrappedStat]
    public var symbol: String

    public init(
        headline: WrappedStat,
        stats: [WrappedStat],
        symbol: String = StreamifyIcon.wave
    ) {
        self.headline = headline
        self.stats = stats
        self.symbol = symbol
    }

    public var body: some View {
        ZStack(alignment: .topTrailing) {
            StreamifyGradients.linear(StreamifyGradients.brand)
            LinearGradient(
                colors: [Color.black.opacity(0.08), Color.black.opacity(0.3)],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                Text("YOUR SOUNDTRACK IN NUMBERS")
                    .font(StreamifyType.caption.weight(.bold))
                    .tracking(1)
                    .foregroundStyle(Color.white.opacity(0.75))
                Text(headline.value)
                    .font(StreamifyType.displayLarge)
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(headline.label)
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(Color.white.opacity(0.8))
                Spacer(minLength: StreamifyDimens.spaceLG)
                HStack(alignment: .top, spacing: StreamifyDimens.spaceLG) {
                    ForEach(stats) { stat in
                        StatBlock(stat: stat)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(StreamifyDimens.spaceXL)
            Image(systemName: symbol)
                .font(.system(size: 24))
                .foregroundStyle(Color.white.opacity(0.9))
                .padding(StreamifyDimens.spaceMD)
        }
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceSM)
    }
}

/// One wrapped stat (Android StatBlock): value in white bold, label below,
/// optional "#rank" eyebrow chip.
private struct StatBlock: View {
    let stat: WrappedStat

    var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
            if let rank = stat.rank {
                Text("#\(rank)")
                    .font(StreamifyType.caption.weight(.bold))
                    .foregroundStyle(Color.white.opacity(0.8))
            }
            Text(stat.value)
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(Color.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(stat.label)
                .font(StreamifyType.caption)
                .foregroundStyle(Color.white.opacity(0.7))
                .lineLimit(1)
        }
    }
}

#Preview("Wrapped hero") {
    YtWrappedHeroCard(
        headline: PreviewCatalog.wrappedStats[0],
        stats: Array(PreviewCatalog.wrappedStats.dropFirst())
    )
    .background(StreamifyColors.bgBase)
}
