import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Onboarding wave) — OnboardingView: the 4-step page tour.
//
// Native port of ui/screens/YtOnboardingScreen.kt re-expressed as the paged
// onboarding continuum: (1) brand — the logo glyph in a gradient circle, the
// Streamify displayLarge title, the "sovereign music engine" caption and the
// SireenBrandingBadge; (2) "Connect your catalogs" — Spotify / YouTube Music
// rows with ConnectAccountsSheet-style Connect buttons; (3) "Audiophile by
// default" — the DSP bullet list; (4) "Ready" — waveform icon, "Let's listen"
// and the primary Start button. A progress-dot + Continue bar advances pages
// with Motion.fluid. Fixtures: PreviewCatalog constants.
// ─────────────────────────────────────────────────────────────────────────────

public struct OnboardingView: View {

    public var onFinished: () -> Void
    public var onConnect: (String) -> Void

    @State private var page = 0

    public init(
        onFinished: @escaping () -> Void = {},
        onConnect: @escaping (String) -> Void = { _ in }
    ) {
        self.onFinished = onFinished
        self.onConnect = onConnect
    }

    private static let dspBullets = [
        "Bit-perfect Rust core",
        "DAC hardware clock",
        "Gapless & LUFS-normalized",
    ]

    public var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                brandStep.tag(0)
                connectStep.tag(1)
                dspStep.tag(2)
                readyStep.tag(3)
            }
#if os(iOS)
            .tabViewStyle(.page(indexDisplayMode: .never))
#endif
            bottomBar
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Step 1 · Brand

    private var brandStep: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            ZStack {
                Circle()
                    .fill(StreamifyGradients.linear(StreamifyGradients.brand))
                    .frame(width: 120, height: 120)
                Image(systemName: StreamifyIcon.logo)
                    .font(.system(size: 64))
                    .foregroundStyle(Color.white)
            }
            Text("Streamify")
                .font(StreamifyType.displayLarge)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Your sovereign music engine")
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textSecondary)
            SireenBrandingBadge()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    // MARK: - Step 2 · Connect your catalogs

    private var connectStep: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            Text("Connect your catalogs")
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Bring Spotify and YouTube Music along — your library stays yours.")
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
            CatalogRow(
                name: "Spotify",
                caption: "Daily Mixes, Blends & saved library",
                icon: StreamifyIcon.musicNote,
                tint: StreamifyColors.successGreen
            ) {
                onConnect(ConnectAccountsSheet.spotifyPlatform)
            }
            CatalogRow(
                name: "YouTube Music",
                caption: "Supermix, Quick Picks & live gems",
                icon: StreamifyIcon.video,
                tint: StreamifyColors.primary
            ) {
                onConnect(ConnectAccountsSheet.youtubeMusicPlatform)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    // MARK: - Step 3 · Audiophile by default

    private var dspStep: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            Text("Audiophile by default")
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Text("The engine treats your DAC as the only clock that matters.")
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
            VStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(Self.dspBullets, id: \.self) { bullet in
                    HStack(spacing: StreamifyDimens.spaceMD) {
                        Image(systemName: StreamifyIcon.checkmarkCircle)
                            .font(.system(size: 16))
                            .foregroundStyle(StreamifyColors.successGreen)
                        Text(bullet)
                            .font(StreamifyType.bodyMedium)
                            .foregroundStyle(StreamifyColors.textMain)
                        Spacer(minLength: 0)
                    }
                    .padding(StreamifyDimens.spaceMD)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cardSurface(hoverable: false)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    // MARK: - Step 4 · Ready

    private var readyStep: some View {
        VStack(spacing: StreamifyDimens.spaceXL) {
            ZStack {
                Circle()
                    .fill(StreamifyGradients.linear(StreamifyGradients.brand))
                    .frame(width: 96, height: 96)
                Image(systemName: StreamifyIcon.wave)
                    .font(.system(size: 40))
                    .foregroundStyle(Color.white)
            }
            Text("Let's listen")
                .font(StreamifyType.displayLarge)
                .foregroundStyle(StreamifyColors.textMain)
            Button {
                Haptics.play(.queueDrop)
                onFinished()
            } label: {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.play)
                        .font(.system(size: 14, weight: .bold))
                    Text("Start listening")
                        .font(StreamifyType.chipText.weight(.bold))
                }
                .foregroundStyle(StreamifyColors.textOnActiveChip)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(StreamifyColors.activeControl, in: Capsule())
                .padding(.horizontal, StreamifyDimens.spaceXL)
            }
            .buttonStyle(.pressableCard)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    // MARK: - Bottom chrome (progress dots + Continue)

    private var bottomBar: some View {
        HStack {
            HStack(spacing: StreamifyDimens.spaceXS) {
                ForEach(0..<4, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? StreamifyColors.primary : StreamifyColors.borderChip)
                        .frame(width: index == page ? 20 : 6, height: 6)
                }
            }
            .animation(Motion.fluid, value: page)
            Spacer(minLength: 0)
            if page < 3 {
                Button {
                    Haptics.play(.scrubTick)
                    withAnimation(Motion.fluid) { page += 1 }
                } label: {
                    Text("Continue")
                        .font(StreamifyType.chipText.weight(.semibold))
                        .foregroundStyle(StreamifyColors.textOnActiveChip)
                        .padding(.horizontal, StreamifyDimens.spaceXL)
                        .padding(.vertical, 10)
                        .background(StreamifyColors.activeControl, in: Capsule())
                }
                .buttonStyle(.pressableCard)
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceMD)
        .padding(.bottom, StreamifyDimens.spaceLG)
    }
}

// MARK: - Catalog row (ConnectAccountsSheet's platform row, onboarding-sized)

private struct CatalogRow: View {

    let name: String
    let caption: String
    let icon: String
    let tint: Color
    let onConnect: () -> Void

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(tint.opacity(0.15))
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundStyle(tint)
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(caption)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Button {
                Haptics.play(.queueDrop)
                onConnect()
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
        .padding(StreamifyDimens.spaceMD)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(hoverable: false)
    }
}

#Preview("Onboarding") {
    OnboardingView()
}
