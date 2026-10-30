import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtDailyMixRail — the "Daily Mix 1–6" home rail. Port of
// ui/components/yt/YtDailyMixRail.kt (Gap #26): numbered mixes over the
// deterministic preset gradient (Android MIX_GRADIENTS table →
// StreamifyGradients.preset(forSeed: title)), number chip on the artwork, title
// + seed-artist/track-count subtitle, pressable-card tap, staggered reveal.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtDailyMixRail: View {
    public var mixes: [VirtualShelf]
    public var onOpen: (Int) -> Void

    public init(mixes: [VirtualShelf], onOpen: @escaping (Int) -> Void) {
        self.mixes = mixes
        self.onOpen = onOpen
    }

    @State private var revealed = false

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(Array(mixes.enumerated()), id: \.element.id) { index, mix in
                    YtDailyMixCard(mix: mix, number: index + 1) {
                        onOpen(index)
                    }
                    .opacity(revealed ? 1 : 0)
                    .offset(y: revealed ? 0 : 18)
                    .animation(Motion.reveal(index), value: revealed)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .onAppear { revealed = true }
    }
}

public struct YtDailyMixCard: View {
    public var mix: VirtualShelf
    public var number: Int
    public var onOpen: () -> Void

    public init(mix: VirtualShelf, number: Int, onOpen: @escaping () -> Void) {
        self.mix = mix
        self.number = number
        self.onOpen = onOpen
    }

    public var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                artwork
                Text(mix.title)
                    .font(StreamifyType.titleSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(subtitleLine)
                    .font(StreamifyType.cardSubtitle)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            .frame(width: StreamifyDimens.cardWidth, alignment: .leading)
        }
        .buttonStyle(.pressableCard)
    }

    private var subtitleLine: String {
        let seed = mix.subtitle ?? "Your daily artists"
        return "\(seed) • \(mix.tracks.count) tracks"
    }

    private var artwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: mix.title)))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 40))
                .foregroundStyle(Color.white.opacity(0.55))
        }
        .frame(width: StreamifyDimens.cardArtSize, height: StreamifyDimens.cardArtSize)
        .overlay(alignment: .topLeading) { numberChip }
    }

    /// The numbered badge — the Daily Mix identity (Android: 55 % black chip).
    private var numberChip: some View {
        Text("\(number)")
            .font(StreamifyType.headlineSmall)
            .foregroundStyle(Color.white)
            .padding(.horizontal, StreamifyDimens.spaceSM)
            .padding(.vertical, StreamifyDimens.spaceXXS)
            .background(
                Color.black.opacity(0.55),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .padding(StreamifyDimens.spaceSM)
    }
}

#Preview("Daily mixes") {
    YtDailyMixRail(mixes: PreviewCatalog.dailyMixes, onOpen: { _ in })
        .padding(.vertical, StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
