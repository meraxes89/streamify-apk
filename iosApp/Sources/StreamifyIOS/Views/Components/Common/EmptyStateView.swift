import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.4 — EmptyStateView: the generic empty state.
//
// Port of ui/components/EmptyStateView.kt (title + subtitle + optional action
// button, centered). The native reading adds the muted symbol medallion the
// Android screens always drew ad-hoc — one canonical empty state, HIG-styled
// (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct EmptyStateView: View {

    public var symbol: String
    public var title: String
    public var message: String
    public var actionTitle: String?
    public var onAction: (() -> Void)?

    public init(
        symbol: String,
        title: String,
        message: String,
        actionTitle: String? = nil,
        onAction: (() -> Void)? = nil
    ) {
        self.symbol = symbol
        self.title = title
        self.message = message
        self.actionTitle = actionTitle
        self.onAction = onAction
    }

    public var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle()
                    .fill(StreamifyColors.bgChipInactive)
                    .frame(width: 96, height: 96)
                Circle()
                    .strokeBorder(StreamifyColors.borderChip, lineWidth: 1)
                    .frame(width: 96, height: 96)
                Image(systemName: symbol)
                    .font(.system(size: 36, weight: .medium))
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .accessibilityHidden(true)
            .padding(.bottom, StreamifyDimens.spaceXL)

            Text(title)
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(StreamifyColors.textMain)
                .multilineTextAlignment(.center)
                .padding(.bottom, StreamifyDimens.spaceSM)

            Text(message)
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, StreamifyDimens.spaceLG)

            if let actionTitle, let onAction {
                Button(action: onAction) {
                    Text(actionTitle)
                        .font(StreamifyType.titleSmall)
                        .foregroundStyle(StreamifyColors.textOnActiveChip)
                        .padding(.horizontal, StreamifyDimens.spaceXXL)
                        .padding(.vertical, StreamifyDimens.spaceMD)
                        .background(Capsule().fill(StreamifyColors.textMain))
                }
                .buttonStyle(.pressableCard)
                .padding(.top, StreamifyDimens.spaceXL)
            }
        }
        .padding(StreamifyDimens.spaceGiant)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview("Empty state") {
    EmptyStateView(
        symbol: StreamifyIcon.musicNoteList,
        title: "Nothing here yet",
        message: "Tracks you save will land on this shelf.",
        actionTitle: "Import library",
        onAction: {}
    )
    .background(StreamifyColors.bgBase)
}
