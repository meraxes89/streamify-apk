import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtLoginDialog — the YouTube Music connect panel (Android
// ui/components/yt/YtLoginDialog.kt). The Android original drove an embedded
// WebView session; the native Apple port presents the same connect step as a
// sheet-content panel: a SecureField for the cookie/token, a primary connect
// button, a status hint and a privacy footnote. Sheet presentation stays with
// the parent; this view is the content (SW-DEC-06 — no in-app web view).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtLoginDialog: View {
    public var statusHint: String?
    public var onConnect: (String) -> Void

    @State private var token = ""

    public init(statusHint: String? = nil, onConnect: @escaping (String) -> Void) {
        self.statusHint = statusHint
        self.onConnect = onConnect
    }

    private var fieldShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                Image(systemName: StreamifyIcon.logo)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 40, height: 40)
                    .background(StreamifyColors.primary, in: StreamifyShapes.actionPill)
                Text("Connect YouTube Music")
                    .font(StreamifyType.headlineLarge)
                    .foregroundStyle(StreamifyColors.textMain)
            }

            Text(
                "Paste your YouTube Music session cookie or auth token to link your library, playlists and likes."
            )
            .font(StreamifyType.bodyMedium)
            .foregroundStyle(StreamifyColors.textSecondary)
            .fixedSize(horizontal: false, vertical: true)

            SecureField("Cookie or auth token", text: $token)
                .textFieldStyle(.plain)
                .font(StreamifyType.bodyLarge)
                .foregroundStyle(StreamifyColors.textMain)
                .padding(StreamifyDimens.spaceMD)
                .background(StreamifyColors.bgSurfaceElevated, in: fieldShape)
                .overlay(fieldShape.strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1))

            Button {
                onConnect(token)
            } label: {
                Text("Connect")
                    .font(StreamifyType.titleMedium)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, StreamifyDimens.spaceMD)
            }
            .foregroundStyle(StreamifyColors.textOnActiveChip)
            .background(StreamifyColors.bgChipActive, in: Capsule())
            .buttonStyle(.pressableCard)
            .opacity(token.isEmpty ? 0.55 : 1)
            .disabled(token.isEmpty)

            if let statusHint {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.info)
                        .font(.system(size: 13))
                    Text(statusHint)
                        .font(StreamifyType.caption)
                        .lineLimit(2)
                }
                .foregroundStyle(StreamifyColors.textSecondary)
            }

            Divider()
            Text(
                "The credential never leaves this device unencrypted and is only used to reach YouTube Music on your behalf."
            )
            .font(StreamifyType.caption)
            .foregroundStyle(StreamifyColors.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(StreamifyDimens.spaceXXL)
        .frame(maxWidth: 520, alignment: .leading)
        .background(StreamifyColors.bgBase)
    }
}

#Preview("Login dialog") {
    YtLoginDialog(
        statusHint: "Signed in as \(PreviewCatalog.profiles[1].email)",
        onConnect: { _ in }
    )
}
