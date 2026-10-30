import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtHomeSkeleton — the home loading skeleton. Port of
// ui/components/yt/YtHomeSkeleton.kt (Android showed only a centered spinner —
// this native port expresses the loading structure instead): the redacted
// Daylist hero bar plus two rail placeholders (medium-card rail + quick-pick
// panel), all muted shapes under .redacted(reason: .placeholder).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtHomeSkeleton: View {
    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXL) {
            heroBar
            cardRailPlaceholder
            rowPanelPlaceholder
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.bottom, StreamifyDimens.spaceXL)
        .redacted(reason: .placeholder)
    }

    /// Morphing Daylist hero stand-in (Android 178 dp banner).
    private var heroBar: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(StreamifyColors.bgCard)
            Text("Daylist hero")
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(StreamifyColors.textTertiary.opacity(0.4))
                .padding(StreamifyDimens.spaceXL)
        }
        .frame(height: 178)
    }

    private func railTitle() -> some View {
        Text("Section title")
            .font(StreamifyType.titleMedium)
            .foregroundStyle(StreamifyColors.textTertiary.opacity(0.5))
    }

    /// "Listen again"-shaped rail: square medium cards.
    private var cardRailPlaceholder: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            railTitle()
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(0..<3, id: \.self) { _ in
                    mediumCardPlaceholder
                }
            }
        }
    }

    private var mediumCardPlaceholder: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
            RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                .fill(StreamifyColors.bgCard)
                .frame(width: StreamifyDimens.cardArtSize, height: StreamifyDimens.cardArtSize)
            Text("Track title")
                .font(StreamifyType.cardTitle)
                .foregroundStyle(StreamifyColors.textTertiary.opacity(0.5))
            Text("Artist")
                .font(StreamifyType.cardSubtitle)
                .foregroundStyle(StreamifyColors.textTertiary.opacity(0.5))
        }
        .frame(width: StreamifyDimens.cardWidth, alignment: .leading)
    }

    /// "Quick picks"-shaped rail: 320 dp panel of compact rows.
    private var rowPanelPlaceholder: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            railTitle()
            VStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(0..<3, id: \.self) { _ in
                    compactRowPlaceholder
                }
            }
            .padding(StreamifyDimens.spaceSM)
            .frame(width: 320, alignment: .leading)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
        }
    }

    private var compactRowPlaceholder: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                .fill(StreamifyColors.bgSurface)
                .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
                Text("Track title")
                    .font(StreamifyType.cardTitle)
                    .foregroundStyle(StreamifyColors.textTertiary.opacity(0.5))
                Text("Artist")
                    .font(StreamifyType.cardSubtitle)
                    .foregroundStyle(StreamifyColors.textTertiary.opacity(0.5))
            }
            Spacer(minLength: 0)
        }
        .frame(height: StreamifyDimens.quickPickRowHeight)
    }
}

#Preview("Home skeleton") {
    ScrollView {
        YtHomeSkeleton()
            .padding(.top, StreamifyDimens.spaceLG)
    }
    .background(StreamifyColors.bgBase)
}
