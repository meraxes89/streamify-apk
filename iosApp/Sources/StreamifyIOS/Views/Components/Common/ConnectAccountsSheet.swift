import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the connected-accounts sheet content.
//
// Port of ui/components/ConnectAccountsSheet.kt: the "Connect Your Taste"
// panel — a Streamify local-account header, one row per external platform
// (Spotify, YouTube Music) with live connect/disconnect button states, and
// every intent routed out through the onConnect / onDisconnect callbacks
// (the Android sheet calls SpotifyAuthManager directly; the Swift shell owns
// its auth seams). Fixtures: PreviewCatalog.profiles.
// ─────────────────────────────────────────────────────────────────────────────

public struct ConnectAccountsSheet: View {
    /// Platform identifiers the sheet routes ("spotify" | "youtubeMusic").
    public static let spotifyPlatform = "spotify"
    public static let youtubeMusicPlatform = "youtubeMusic"

    public let localAccount: ProfileAccount
    public let connectedPlatforms: Set<String>
    public var onConnect: ((String) -> Void)? = nil
    public var onDisconnect: ((String) -> Void)? = nil

    public init(
        localAccount: ProfileAccount,
        connectedPlatforms: Set<String>,
        onConnect: ((String) -> Void)? = nil,
        onDisconnect: ((String) -> Void)? = nil
    ) {
        self.localAccount = localAccount
        self.connectedPlatforms = connectedPlatforms
        self.onConnect = onConnect
        self.onDisconnect = onDisconnect
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
                Text("Connect Your Taste")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(
                    "Sync playlists, liked songs and daily mixes from Spotify and YouTube Music into Streamify's algorithmic continuum."
                )
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            }

            localHeader

            platformRow(
                platform: Self.spotifyPlatform,
                name: "Spotify",
                icon: StreamifyIcon.musicNote,
                tint: StreamifyColors.successGreen,
                connectedCaption: "Connected · Daily Mixes active",
                idleCaption: "Daily Mixes, Blends & Saved Library"
            )
            platformRow(
                platform: Self.youtubeMusicPlatform,
                name: "YouTube Music",
                icon: StreamifyIcon.video,
                tint: StreamifyColors.primary,
                connectedCaption: "Connected · Supermix active",
                idleCaption: "Supermix, Quick Picks & Live Gems"
            )
        }
        .padding(StreamifyDimens.spaceLG)
        .frame(maxWidth: 480, alignment: .leading)
    }

    /// The always-on Streamify local account (the library that never syncs).
    private var localHeader: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(StreamifyColors.primary.opacity(0.16))
                Image(systemName: StreamifyIcon.logo)
                    .font(.system(size: 18))
                    .foregroundStyle(StreamifyColors.primary)
            }
            .frame(width: 42, height: 42)
            VStack(alignment: .leading, spacing: 2) {
                Text(localAccount.displayName)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(localAccount.email)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Text("LOCAL")
                .font(.system(size: 10, weight: .heavy))
                .kerning(1)
                .foregroundStyle(StreamifyColors.successGreen)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(StreamifyColors.successGreen.opacity(0.14), in: StreamifyShapes.chip)
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }

    private func platformRow(
        platform: String,
        name: String,
        icon: String,
        tint: Color,
        connectedCaption: String,
        idleCaption: String
    ) -> some View {
        let isConnected = connectedPlatforms.contains(platform)
        return HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(tint.opacity(0.15))
                Image(systemName: icon)
                    .font(.system(size: 17))
                    .foregroundStyle(tint)
            }
            .frame(width: 42, height: 42)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(name)
                        .font(StreamifyType.titleMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                    if isConnected {
                        Image(systemName: StreamifyIcon.checkmarkCircle)
                            .font(.system(size: 13))
                            .foregroundStyle(tint)
                    }
                }
                Text(isConnected ? connectedCaption : idleCaption)
                    .font(StreamifyType.caption)
                    .foregroundStyle(isConnected ? tint : StreamifyColors.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if isConnected {
                Button {
                    Haptics.play(.scrubTick)
                    onDisconnect?(platform)
                } label: {
                    Text("Disconnect")
                        .font(StreamifyType.chipText)
                        .foregroundStyle(StreamifyColors.errorRed)
                }
                .buttonStyle(.plain)
            } else {
                Button {
                    Haptics.play(.queueDrop)
                    onConnect?(platform)
                } label: {
                    Text("Connect")
                        .font(StreamifyType.chipText.weight(.bold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 7)
                        .background(tint, in: StreamifyShapes.card)
                }
                .buttonStyle(.pressableCard)
            }
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }
}

#Preview {
    ConnectAccountsSheet(
        localAccount: PreviewCatalog.profiles[0],
        connectedPlatforms: [ConnectAccountsSheet.spotifyPlatform],
        onConnect: { _ in },
        onDisconnect: { _ in }
    )
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
