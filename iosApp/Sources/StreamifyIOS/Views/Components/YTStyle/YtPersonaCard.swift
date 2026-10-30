import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPersonaCard — the AI audio persona card. Port of
// ui/components/yt/YtPersonaCard.kt: the BPM-canvas pulse ring is lifted to the
// HIG idiom — an avatar circle carrying the first initial over the
// deterministic preset gradient (StreamifyGradients.preset(forSeed: name)) —
// with name + description and a pressable-card tap into the persona.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtPersonaCard: View {
    public var name: String
    public var subtitle: String
    public var onOpen: () -> Void

    public init(name: String, subtitle: String, onOpen: @escaping () -> Void) {
        self.name = name
        self.subtitle = subtitle
        self.onOpen = onOpen
    }

    private var initial: String {
        let first = name.trimmingCharacters(in: .whitespaces).first
        return first.map { String($0).uppercased() } ?? "?"
    }

    public var body: some View {
        Button(action: onOpen) {
            HStack(spacing: StreamifyDimens.spaceLG) {
                avatar
                VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
                    Text(name)
                        .font(StreamifyType.headlineMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(StreamifyType.bodySmall)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.chevronRight)
                    .font(.system(size: 13))
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .padding(18)
            .background(
                StreamifyColors.bgSurfaceElevated,
                in: RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous)
            )
        }
        .buttonStyle(.pressableCard)
    }

    private var avatar: some View {
        ZStack {
            Circle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: name)))
            Text(initial)
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(Color.white)
        }
        .frame(width: 56, height: 56)
    }
}

#Preview("Persona cards") {
    VStack(spacing: StreamifyDimens.spaceMD) {
        YtPersonaCard(
            name: "Obsidian Curator",
            subtitle: "Slow-burn ambient sets with a taste for tape hiss",
            onOpen: {}
        )
        YtPersonaCard(
            name: "Meridian Runner",
            subtitle: "High-BPM techno pacing at 128 steps per minute",
            onOpen: {}
        )
    }
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
