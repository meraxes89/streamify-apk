import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtGenreDistributionBar — the wrapped-stats genre distribution bar (Android
// ui/components/yt/YtGenreDistributionBar.kt). Where the Android original
// animated ONE rank row with a GPU scaleX fill, the wrapped-stats surface
// renders the whole distribution at once: a stacked horizontal bar where each
// genre occupies a share segment colored by GenreStat.gradient.first, over a
// staggered legend of counts. The reveal uses Motion.reveal(i) instead of the
// Compose spring+graphicsLayer trick (SW-DEC-06 native expression).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtGenreDistributionBar: View {
    public var genres: [GenreStat]

    @State private var appeared = false

    public init(genres: [GenreStat]) {
        self.genres = genres
    }

    private var totalPlays: Int {
        max(genres.reduce(0) { $0 + $1.count }, 1)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            GeometryReader { proxy in
                HStack(spacing: 2) {
                    ForEach(Array(genres.enumerated()), id: \.element.id) { index, genre in
                        let share = Double(genre.count) / Double(totalPlays)
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                            .fill(genre.gradient.first ?? StreamifyColors.textSecondary)
                            .frame(width: appeared ? proxy.size.width * share : 0)
                            .animation(Motion.reveal(index), value: appeared)
                    }
                }
            }
            .frame(height: 8)
            .clipShape(Capsule())
            .background(StreamifyColors.bgCard, in: Capsule())

            VStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(Array(genres.enumerated()), id: \.element.id) { index, genre in
                    legendRow(genre, share: Double(genre.count) / Double(totalPlays))
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 6)
                        .animation(Motion.reveal(index), value: appeared)
                }
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, 6)
        .onAppear { appeared = true }
    }

    private func legendRow(_ genre: GenreStat, share: Double) -> some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            Circle()
                .fill(
                    LinearGradient(
                        colors: genre.gradient,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 10, height: 10)
            Text(genre.genre)
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textMain)
                .lineLimit(1)
            Spacer(minLength: StreamifyDimens.spaceMD)
            Text("\(genre.count) plays · \(Int((share * 100).rounded()))%")
                .font(StreamifyType.caption)
                .monospacedDigit()
                .foregroundStyle(StreamifyColors.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview("Genre distribution") {
    YtGenreDistributionBar(genres: PreviewCatalog.genreStats)
        .padding(.vertical, 24)
        .frame(maxWidth: 480)
        .background(StreamifyColors.bgBase)
}
