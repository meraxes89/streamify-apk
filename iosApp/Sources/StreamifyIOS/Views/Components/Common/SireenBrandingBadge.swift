import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.1 — the "STREAMIFY · DEVELOPED BY SIREEN" brand badge.
//
// Port of ui/components/SireenBrandingBadge.kt: a compact capsule chip with
// the waveform logo glyph and letterspaced caption text. The Android original
// fires ONE chromatic glint sweep on first composition (a permanent
// rememberInfiniteTransition there kept a Choreographer frame loop alive on
// every host screen); the native expression keeps the one-shot discipline —
// a single animated offset sweep, then the badge rests in its static branded
// colors (SW-DEC-06: zero idle frame cost).
// ─────────────────────────────────────────────────────────────────────────────

public struct SireenBrandingBadge: View {

    /// Glint band position in badge widths; animates -0.6 → 1.4 once.
    @State private var glintX: Double = -0.6

    public init() {}

    public var body: some View {
        badgeContent
            .overlay {
                // One-shot chromatic glint, masked to the badge glyphs so the
                // sweep never bleeds outside the text/logo footprint.
                GeometryReader { geo in
                    LinearGradient(
                        colors: [
                            .clear,
                            .white.opacity(0.9),
                            StreamifyColors.primary,
                            StreamifyColors.accentBlue,
                            .white.opacity(0.9),
                            .clear,
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geo.size.width * 0.55)
                    .offset(x: geo.size.width * glintX)
                }
                .mask { badgeContent }
                .allowsHitTesting(false)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Capsule().fill(StreamifyColors.bgSurfaceElevated.opacity(0.65)))
            .overlay(Capsule().strokeBorder(StreamifyColors.borderChip.opacity(0.55), lineWidth: 0.8))
            .onAppear {
                withAnimation(.linear(duration: 2.8)) {
                    glintX = 1.4
                }
            }
    }

    /// Logo glyph + the two-tone letterspaced label (muted lead-in,
    /// obsidian-red brand name — the annotated-string resting state of the
    /// Compose twin).
    private var badgeContent: some View {
        HStack(spacing: 6) {
            Image(systemName: StreamifyIcon.logo)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(StreamifyColors.primary)

            Text(badgeLabel)
                .font(StreamifyType.caption)
                .tracking(1.6)
                .lineLimit(1)
        }
        .fixedSize()
    }

    private var badgeLabel: AttributedString {
        var text = AttributedString("STREAMIFY · DEVELOPED BY ")
        text.foregroundColor = StreamifyColors.textSecondary.opacity(0.75)
        var name = AttributedString("SIREEN")
        name.foregroundColor = StreamifyColors.primary
        text += name
        return text
    }
}

#Preview("Sireen branding badge") {
    VStack(spacing: 24) {
        SireenBrandingBadge()
        SireenBrandingBadge()
    }
    .padding(40)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
