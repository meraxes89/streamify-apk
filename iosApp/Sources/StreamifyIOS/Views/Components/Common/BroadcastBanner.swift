import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.2 — BroadcastBanner: the announcement strip.
//
// Port of ui/components/BroadcastBanner.kt (the campaign banner strip that
// hosts the admin broadcast feed). Android showed the latest broadcast in a
// rounded primary-tinted surface with a dismiss chevron; the native reading
// renders it as a brand-gradient capsule chip — announcement semantics, Apple
// idiom (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct BroadcastBanner: View {

    public var message: String
    /// Trailing X action; nil hides the dismiss affordance.
    public var onDismiss: (() -> Void)?
    /// Leading announcement glyph.
    public var icon: String

    public init(
        message: String,
        onDismiss: (() -> Void)? = nil,
        icon: String = StreamifyIcon.broadcast
    ) {
        self.message = message
        self.onDismiss = onDismiss
        self.icon = icon
    }

    public var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white.opacity(0.95))
                .accessibilityHidden(true)

            Text(message)
                .font(.system(size: 12, weight: .bold))
                .lineLimit(2)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let onDismiss {
                Button {
                    onDismiss()
                } label: {
                    Image(systemName: StreamifyIcon.close)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(.white.opacity(0.18)))
                        .contentShape(Circle())
                }
                .buttonStyle(.pressableCard)
                .accessibilityLabel("Dismiss announcement")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            Capsule().fill(
                LinearGradient(
                    colors: StreamifyGradients.brand,
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
        )
        .overlay(
            Capsule().strokeBorder(.white.opacity(0.15), lineWidth: 1)
        )
        .shadow(color: StreamifyColors.shadowSoft, radius: 6, y: 2)
    }
}

#Preview("Broadcast banner") {
    VStack(spacing: 16) {
        BroadcastBanner(message: "New Obsidian Radio episodes land every Friday.")
        BroadcastBanner(
            message: "Jam rooms now support mesh beacons — 1.2M listeners joined this week.",
            onDismiss: {}
        )
    }
    .padding(20)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
