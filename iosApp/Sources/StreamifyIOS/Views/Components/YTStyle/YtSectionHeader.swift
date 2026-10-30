import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSectionHeader — the shelf section header (Android
// ui/components/yt/YtSectionHeader.kt: kicker + bold title + TextButton
// action). The kicker keeps its Android semantics as an optional subtitle
// rendered as a small uppercase overline above the title; the trailing action
// is the native "More ›" link idiom.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSectionHeader: View {
    public var title: String
    public var subtitle: String?
    public var actionText: String = "More"
    public var onMore: (() -> Void)?

    public init(
        title: String,
        subtitle: String? = nil,
        actionText: String = "More",
        onMore: (() -> Void)? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.actionText = actionText
        self.onMore = onMore
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                if let subtitle {
                    Text(subtitle.uppercased())
                        .font(StreamifyType.caption)
                        .tracking(0.5)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
                Text(title)
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(2)
            }
            Spacer(minLength: StreamifyDimens.spaceLG)
            if let onMore {
                Button {
                    onMore()
                } label: {
                    HStack(spacing: 2) {
                        Text(actionText)
                        Image(systemName: StreamifyIcon.chevronRight)
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textMain)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(actionText) from \(title)")
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceXXL)
        .padding(.bottom, StreamifyDimens.spaceMD)
    }
}

#Preview("Section headers") {
    VStack(spacing: 8) {
        YtSectionHeader(
            title: PreviewCatalog.shelves[0].title,
            subtitle: PreviewCatalog.shelves[0].subtitle,
            onMore: {}
        )
        YtSectionHeader(title: PreviewCatalog.shelves[2].title)
    }
    .frame(maxWidth: 520)
    .background(StreamifyColors.bgBase)
}
