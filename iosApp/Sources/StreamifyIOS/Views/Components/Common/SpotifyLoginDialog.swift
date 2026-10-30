import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.13 — SpotifyLoginDialog: the Spotify OAuth panel (sheet content).
//
// Port of ui/components/SpotifyLoginDialog.kt. The Android original embeds a
// WebView OAuth session plus a "Securing Spotify Session…" overlay; iOS has no
// in-app web login seam in this wave, so the panel expresses the SAME flow the
// native way — brand header, explanation, a paste field for the session token,
// connect/cancel actions and a live status hint (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct SpotifyLoginDialog: View {

    /// The user's pasted session token (sheet-ephemeral UI state).
    @State private var pastedToken = ""

    /// True while the host is exchanging/securing the session.
    public var isSecuring: Bool
    /// Idle-state hint under the actions (validation or error copy).
    public var statusHint: String?
    public let onConnect: (String) -> Void
    public let onCancel: () -> Void

    private static let spotifyGreen = Color(streamifyHex: 0x1DB954)

    public init(
        isSecuring: Bool = false,
        statusHint: String? = nil,
        onConnect: @escaping (String) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.isSecuring = isSecuring
        self.statusHint = statusHint
        self.onConnect = onConnect
        self.onCancel = onCancel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ZStack {
                    Circle()
                        .fill(Self.spotifyGreen)
                        .frame(width: 44, height: 44)
                    Image(systemName: StreamifyIcon.musicNote)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect Spotify")
                        .font(StreamifyType.headlineSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text("Link your Spotify catalog")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
            }

            Text(
                "Sign in to Spotify in your browser, then paste the session token below. "
                    + "Streamify syncs your playlists and liked songs; your credentials never leave this device."
            )
            .font(StreamifyType.bodyMedium)
            .foregroundStyle(StreamifyColors.textSecondary)
            .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                Text("Session token")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)

                TextField("Paste your Spotify session token", text: $pastedToken)
                    .font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .padding(StreamifyDimens.spaceMD)
                    .background(
                        RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                            .fill(StreamifyColors.bgSearchBar)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                            .strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1)
                    )
            }

            Button {
                onConnect(pastedToken)
            } label: {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    if isSecuring {
                        ProgressView()
                            .tint(.black)
                    } else {
                        Image(systemName: StreamifyIcon.paperplane)
                            .font(.system(size: 14, weight: .semibold))
                    }
                    Text(isSecuring ? "Securing Spotify Session…" : "Connect")
                        .font(StreamifyType.titleSmall)
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .padding(.vertical, StreamifyDimens.spaceMD)
                .background(
                    Capsule().fill(pastedToken.isEmpty ? StreamifyColors.bgChipInactive : Self.spotifyGreen)
                )
            }
            .buttonStyle(.pressableCard)
            .disabled(pastedToken.isEmpty || isSecuring)

            Button(action: onCancel) {
                Text("Cancel")
                    .font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)

            statusLine
                .frame(maxWidth: .infinity)
        }
        .padding(StreamifyDimens.spaceXXL)
    }

    @ViewBuilder
    private var statusLine: some View {
        if isSecuring {
            Text("Securing your session on this device…")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
        } else if let statusHint {
            Text(statusHint)
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
                .multilineTextAlignment(.center)
        }
    }
}

#Preview("Spotify login dialog") {
    VStack(spacing: 24) {
        SpotifyLoginDialog(
            statusHint: "Token looks valid — ready to connect.",
            onConnect: { _ in },
            onCancel: {}
        )
        .glassPanel(StreamifyMaterials.sheet, cornerRadius: StreamifyRadius.xxl)

        SpotifyLoginDialog(
            isSecuring: true,
            onConnect: { _ in },
            onCancel: {}
        )
        .glassPanel(StreamifyMaterials.sheet, cornerRadius: StreamifyRadius.xxl)
    }
    .padding(24)
    .frame(maxWidth: 480)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
