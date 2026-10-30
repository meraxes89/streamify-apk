import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — SettingsView: the root settings hub.
//
// SwiftUI port of ui/screens/SettingsScreen.kt (its navigable body: profile
// header, app-mode picker, the Library / Sound / System / About section
// cards). Every destination is a host callback — the view never routes; the
// About strip carries the Sireen branding badge, the build stamp and the
// sovereign Rust-core line (SW-ENG2-09 deterministic preview fixtures).
// ─────────────────────────────────────────────────────────────────────────────

/// Uppercase, letterspaced section label (Android SectionHeader, 1:1).
private struct SettingsSectionLabel: View {
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(StreamifyType.caption).tracking(1.2)
            .foregroundStyle(StreamifyColors.primary)
            .padding(.bottom, StreamifyDimens.spaceSM)
    }
}

/// One tappable settings row: rounded-square icon plate, title + caption,
/// trailing chevron — the Android SettingsScreen card row (SW-ENG2-08).
private struct SettingsRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let onTap: () -> Void

    var body: some View {
        Button {
            Haptics.play(.scrubTick)
            onTap()
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 34, height: 34)
                    .background(
                        StreamifyColors.bgChipInactive,
                        in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                    )
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(StreamifyType.bodyMedium).foregroundStyle(StreamifyColors.textMain)
                    Text(subtitle).font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
                }
                Spacer(minLength: StreamifyDimens.spaceSM)
                Image(systemName: StreamifyIcon.chevronRight)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
    }
}

public struct SettingsView: View {
    public var profile: ProfileAccount
    public var selectedMode: AppMode
    public var onModeChange: (AppMode) -> Void
    public var onOpenAccounts: () -> Void
    public var onOpenDsp: () -> Void
    public var onOpenEqualizer: () -> Void
    public var onOpenDownloads: () -> Void
    public var onOpenUpdates: () -> Void
    public var onOpenDiagnostics: () -> Void

    public init(
        profile: ProfileAccount = PreviewCatalog.profiles[0],
        selectedMode: AppMode = .streamify,
        onModeChange: @escaping (AppMode) -> Void = { _ in },
        onOpenAccounts: @escaping () -> Void = {},
        onOpenDsp: @escaping () -> Void = {},
        onOpenEqualizer: @escaping () -> Void = {},
        onOpenDownloads: @escaping () -> Void = {},
        onOpenUpdates: @escaping () -> Void = {},
        onOpenDiagnostics: @escaping () -> Void = {}
    ) {
        self.profile = profile
        self.selectedMode = selectedMode
        self.onModeChange = onModeChange
        self.onOpenAccounts = onOpenAccounts
        self.onOpenDsp = onOpenDsp
        self.onOpenEqualizer = onOpenEqualizer
        self.onOpenDownloads = onOpenDownloads
        self.onOpenUpdates = onOpenUpdates
        self.onOpenDiagnostics = onOpenDiagnostics
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                Text("Settings").font(StreamifyType.headlineLarge).foregroundStyle(StreamifyColors.textMain)
                profileHeader
                section("Library") {
                    modePicker
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.avatar, title: "Accounts",
                                subtitle: "Connected services & taste sync", onTap: onOpenAccounts)
                }
                section("Sound") {
                    SettingsRow(icon: StreamifyIcon.eq, title: "Audio Quality & DSP",
                                subtitle: "Loudness, gapless, crossfade, limiter", onTap: onOpenDsp)
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.sliders, title: "Equalizer",
                                subtitle: "Tune bass, treble, and surround sound", onTap: onOpenEqualizer)
                }
                section("System") {
                    SettingsRow(icon: StreamifyIcon.download, title: "Downloads",
                                subtitle: "Storage & transfer queue", onTap: onOpenDownloads)
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.related, title: "Your updates",
                                subtitle: "New releases from artists you follow", onTap: onOpenUpdates)
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.terminal, title: "Diagnostics",
                                subtitle: "Live SLog stream, copy & share", onTap: onOpenDiagnostics)
                }
                section("About") { aboutCard }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Section chrome

    private func section<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsSectionLabel(title: title)
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(StreamifyDimens.spaceLG)
            .cardSurface(hoverable: false)
        }
    }

    private var rowDivider: some View {
        Rectangle().fill(StreamifyColors.divider).frame(height: 1)
    }

    // MARK: - Profile header

    private var initials: String {
        let letters = profile.displayName.split(separator: " ").compactMap { $0.first }
        return letters.isEmpty ? "·" : String(letters.prefix(2))
    }

    private var profileHeader: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(StreamifyColors.primary.opacity(0.14))
                Text(initials).font(StreamifyType.headlineMedium).foregroundStyle(StreamifyColors.primary)
            }
            .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(profile.displayName).font(StreamifyType.headlineSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text(profile.mode.label).font(StreamifyType.caption).foregroundStyle(StreamifyColors.primary)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Capsule().fill(StreamifyColors.primary.opacity(0.12)))
                }
                Text(profile.email).font(StreamifyType.bodySmall).foregroundStyle(StreamifyColors.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
    }

    // MARK: - App mode picker

    private var modePicker: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text("App mode").font(StreamifyType.bodyMedium).foregroundStyle(StreamifyColors.textMain)
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(AppMode.allCases) { mode in
                    Button {
                        Haptics.play(.scrubTick)
                        onModeChange(mode)
                    } label: {
                        Text(mode.label).font(StreamifyType.chipText).lineLimit(1)
                            .foregroundStyle(
                                selectedMode == mode
                                    ? StreamifyColors.textOnActiveChip
                                    : StreamifyColors.textMain
                            )
                            .padding(.horizontal, StreamifyDimens.spaceMD)
                            .frame(height: StreamifyDimens.filterChipHeight)
                            .chipSurface(active: selectedMode == mode)
                    }
                    .buttonStyle(.pressableCard)
                }
            }
        }
    }

    // MARK: - About (SireenBrandingBadge + build stamp)

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            SireenBrandingBadge()
            Text("Version 2.4.0 (2b2e52f)").font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
            Text("Made with the sovereign Rust core").font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview("Settings") {
    SettingsView()
}
