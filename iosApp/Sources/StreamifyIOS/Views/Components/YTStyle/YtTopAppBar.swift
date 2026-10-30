import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtTopAppBar — the top app bar chrome (Android ui/components/yt/YtTopAppBar.
// kt: brand mark + title on the left; cast, updates and the avatar circle on
// the right). The native port keeps the leading title and the trailing
// cast + avatar actions at the Android 64 pt bar height, with a
// transparent-or-glass background switch for scroll chrome (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtTopAppBar: View {
    public var title: String = "Streamify"
    public var avatarInitial: String = "S"
    public var usesGlass: Bool = false
    public var onCast: () -> Void = {}
    public var onAvatar: () -> Void = {}

    public init(
        title: String = "Streamify",
        avatarInitial: String = "S",
        usesGlass: Bool = false,
        onCast: @escaping () -> Void = {},
        onAvatar: @escaping () -> Void = {}
    ) {
        self.title = title
        self.avatarInitial = avatarInitial
        self.usesGlass = usesGlass
        self.onCast = onCast
        self.onAvatar = onAvatar
    }

    private var barContent: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            HStack(spacing: 6) {
                Image(systemName: StreamifyIcon.logo)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 28, height: 28)
                    .background(StreamifyColors.primary, in: StreamifyShapes.actionPill)
                Text(title)
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
            }

            Spacer(minLength: StreamifyDimens.spaceLG)

            Button(action: onCast) {
                Image(systemName: StreamifyIcon.cast)
                    .font(.system(size: 17))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cast")

            Button(action: onAvatar) {
                Text(String(avatarInitial.prefix(1)).uppercased())
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(StreamifyColors.primary)
                    .frame(width: 30, height: 30)
                    .background(StreamifyColors.bgSurfaceElevated, in: StreamifyShapes.actionPill)
                    .overlay(
                        StreamifyShapes.actionPill.strokeBorder(StreamifyColors.borderChip, lineWidth: 1)
                    )
            }
            .buttonStyle(.pressableCard)
            .accessibilityLabel("Profile")
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .frame(height: 64)
    }

    public var body: some View {
        Group {
            if usesGlass {
                barContent.glassPanel(StreamifyMaterials.chrome, cornerRadius: 0)
            } else {
                barContent
            }
        }
    }
}

#Preview("Top app bar") {
    VStack(spacing: 0) {
        YtTopAppBar(
            avatarInitial: String(PreviewCatalog.userProfile.displayName.prefix(1)),
            onCast: {},
            onAvatar: {}
        )
        YtTopAppBar(
            title: PreviewCatalog.userProfile.displayName,
            avatarInitial: String(PreviewCatalog.userProfile.displayName.prefix(1)),
            usesGlass: true,
            onCast: {},
            onAvatar: {}
        )
        Spacer()
    }
    .frame(maxWidth: 560)
    .background(StreamifyColors.bgBase)
}
