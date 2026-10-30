import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtDaylistHeroBanner — the morphing Daylist hero (Gap #20). Port of
// ui/components/yt/YtDaylistHeroBanner.kt: breathing model gradient (12 s
// linear drift), legibility scrim, bucket eyebrow, morphing title/subtitle,
// next-up chips + white play affordance, "N tracks" footer, Motion.reveal(i)
// staggered reveal. Tap anywhere opens (plays) the Daylist.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtDaylistHeroBanner: View {
    public var model: DaylistHeroModel
    public var onOpen: () -> Void

    public init(model: DaylistHeroModel, onOpen: @escaping () -> Void) {
        self.model = model
        self.onOpen = onOpen
    }

    /// Breathing gradient drift (Android: 12 000 ms linear, reverse repeat).
    @State private var drift: CGFloat = 0
    @State private var revealed = false

    public var body: some View {
        Button(action: onOpen) {
            ZStack(alignment: .topLeading) {
                morphingGradient
                LinearGradient(
                    colors: [Color.black.opacity(0.12), Color.black.opacity(0.45)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                content
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .animation(Motion.fluid, value: model.title)
        }
        .buttonStyle(.pressableCard)
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceSM)
        .onAppear {
            withAnimation(.linear(duration: 12).repeatForever(autoreverses: true)) { drift = 1 }
            revealed = true
        }
    }

    /// Three-stop traveling gradient [start, end, start] (Android brush).
    private var morphingGradient: LinearGradient {
        let colors = model.gradient
        let start = colors.first ?? StreamifyColors.accentBlue
        let end = colors.count > 1 ? colors[1] : start
        return LinearGradient(
            stops: [
                .init(color: start, location: 0),
                .init(color: end, location: 0.55),
                .init(color: start, location: 1),
            ],
            startPoint: UnitPoint(x: -0.35 + 0.7 * drift, y: 0),
            endPoint: UnitPoint(x: 0.15 + 0.7 * drift, y: 1.2)
        )
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            eyebrow.reveal(revealed, index: 0)
            Spacer(minLength: StreamifyDimens.spaceMD)
            title.reveal(revealed, index: 1)
            Spacer(minLength: 6)
            subtitle.reveal(revealed, index: 2)
            Spacer(minLength: StreamifyDimens.spaceLG)
            chipsAndPlay.reveal(revealed, index: 3)
            if model.trackCount > 0 {
                Spacer(minLength: 10)
                footer.reveal(revealed, index: 4)
            }
        }
        .padding(StreamifyDimens.spaceXL)
        .frame(minHeight: 178, alignment: .topLeading)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var eyebrow: some View {
        HStack(spacing: 6) {
            Image(systemName: StreamifyIcon.clock)
                .font(.system(size: 12)).foregroundStyle(Color.white.opacity(0.9))
            Text("DAYLIST • \(model.bucketLabel.uppercased()) • REFRESHES EVERY 4 HOURS")
                .font(StreamifyType.caption.weight(.bold)).tracking(1.2)
                .foregroundStyle(Color.white.opacity(0.85)).lineLimit(1)
        }
    }

    private var title: some View {
        Text(model.title)
            .font(StreamifyType.displayMedium).foregroundStyle(Color.white).lineLimit(2)
            .id(model.title)
            .transition(.asymmetric(
                insertion: .move(edge: .bottom).combined(with: .opacity),
                removal: .move(edge: .top).combined(with: .opacity)
            ))
    }

    private var subtitle: some View {
        Text(model.subtitle)
            .font(StreamifyType.songArtist).foregroundStyle(Color.white.opacity(0.85))
            .lineLimit(1).id(model.subtitle).transition(.opacity)
    }

    private var chipsAndPlay: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            ForEach(model.trackTitles.prefix(3), id: \.self) { chipTitle in
                Text(chipTitle)
                    .font(StreamifyType.bodySmall).foregroundStyle(Color.white.opacity(0.92))
                    .lineLimit(1).padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color.black.opacity(0.28), in: StreamifyShapes.chip)
            }
            Spacer(minLength: StreamifyDimens.spaceSM)
            ZStack {
                Circle().fill(Color.white)
                Image(systemName: StreamifyIcon.play)
                    .font(.system(size: 20, weight: .bold)).foregroundStyle(Color.black)
            }
            .frame(width: 44, height: 44)
        }
    }

    private var footer: some View {
        HStack(spacing: StreamifyDimens.spaceXS) {
            Image(systemName: StreamifyIcon.eq)
                .font(.system(size: 11)).foregroundStyle(Color.white.opacity(0.7))
            Text("\(model.trackCount) tracks tuned to your \(model.bucketLabel.lowercased())")
                .font(StreamifyType.bodySmall).foregroundStyle(Color.white.opacity(0.7)).lineLimit(1)
        }
    }
}

/// Staggered fade + slide for the banner's content pieces.
private struct BannerReveal: ViewModifier {
    var revealed: Bool
    var index: Int

    func body(content: Content) -> some View {
        content
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed ? 0 : 14)
            .animation(Motion.reveal(index), value: revealed)
    }
}

private extension View {
    func reveal(_ revealed: Bool, index: Int) -> some View {
        modifier(BannerReveal(revealed: revealed, index: index))
    }
}

#Preview("Daylist hero") {
    YtDaylistHeroBanner(model: PreviewCatalog.daylistHero, onOpen: {})
        .background(StreamifyColors.bgBase)
}
