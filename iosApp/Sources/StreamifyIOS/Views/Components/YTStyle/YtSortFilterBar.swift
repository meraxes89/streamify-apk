import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSortFilterBar — the library list chrome row. Port of
// ui/components/yt/YtSortFilterBar.kt: leading sort selector (label + glyph)
// and trailing view/filter toggle. Native port keeps the trailing icon as the
// HIG filter affordance with an optional inline count label.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSortFilterBar: View {
    public var sortLabel: String
    public var countLabel: String?
    public var onSortTap: () -> Void
    public var onFilterTap: () -> Void

    public init(
        sortLabel: String,
        countLabel: String? = nil,
        onSortTap: @escaping () -> Void,
        onFilterTap: @escaping () -> Void
    ) {
        self.sortLabel = sortLabel
        self.countLabel = countLabel
        self.onSortTap = onSortTap
        self.onFilterTap = onFilterTap
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Button(action: onSortTap) {
                HStack(spacing: StreamifyDimens.spaceXXS) {
                    Text(sortLabel)
                        .font(StreamifyType.titleMedium)
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Image(systemName: StreamifyIcon.sort)
                        .font(.system(size: 14))
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
            }
            .buttonStyle(.plain)

            Spacer(minLength: StreamifyDimens.spaceMD)

            if let countLabel {
                Text(countLabel)
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }

            Button(action: onFilterTap) {
                Image(systemName: StreamifyIcon.filter)
                    .font(.system(size: 18))
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .padding(StreamifyDimens.spaceXS)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceSM)
    }
}

#Preview("Sort & filter bar") {
    VStack(spacing: StreamifyDimens.spaceLG) {
        YtSortFilterBar(
            sortLabel: "Recent activity",
            countLabel: "128 songs",
            onSortTap: {},
            onFilterTap: {}
        )
        YtSortFilterBar(sortLabel: "Recently played", onSortTap: {}, onFilterTap: {})
    }
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
