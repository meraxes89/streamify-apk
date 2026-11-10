import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — SettingsView: the root settings hub.
//
// SwiftUI port of ui/screens/SettingsScreen.kt (its navigable body: app-mode
// picker, the Library / Sound / Storage / System / About section cards).
// Every destination is a host callback — the view never routes; the About
// strip carries the Sireen branding badge, the build stamp and the
// sovereign Rust-core line.
//
// WS-B B2 (de-fixturing): the screen OWNS its SettingsViewModel and rides
// the real settings plane — streaming quality (128k/256k Opus), the offline
// cache budget, cache clear and the SLog diagnostic switches all route
// through the VM (persisted in the real SettingsStore; the limiter rides
// the same VM on the DSP screen). The fixture profile header left with its
// PreviewCatalog source — account management is the Accounts row. No load
// call needed: the store is synchronous. Previews inject a scripted store.
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

@MainActor
public struct SettingsView: View {

    /// WS-B B2: the screen's own state holder — constructed bare, it reads
    /// and writes the real SettingsStore (AppServices.settings); the store
    /// is synchronous, so there is no load/refresh lifecycle.
    @State private var model = SettingsViewModel()

    public var selectedMode: AppMode
    public var onModeChange: (AppMode) -> Void
    public var onOpenAccounts: () -> Void
    public var onOpenDsp: () -> Void
    public var onOpenEqualizer: () -> Void
    public var onOpenDownloads: () -> Void
    public var onOpenUpdates: () -> Void
    public var onOpenDiagnostics: () -> Void

    public init(
        selectedMode: AppMode = .streamify,
        onModeChange: @escaping (AppMode) -> Void = { _ in },
        onOpenAccounts: @escaping () -> Void = {},
        onOpenDsp: @escaping () -> Void = {},
        onOpenEqualizer: @escaping () -> Void = {},
        onOpenDownloads: @escaping () -> Void = {},
        onOpenUpdates: @escaping () -> Void = {},
        onOpenDiagnostics: @escaping () -> Void = {}
    ) {
        self.selectedMode = selectedMode
        self.onModeChange = onModeChange
        self.onOpenAccounts = onOpenAccounts
        self.onOpenDsp = onOpenDsp
        self.onOpenEqualizer = onOpenEqualizer
        self.onOpenDownloads = onOpenDownloads
        self.onOpenUpdates = onOpenUpdates
        self.onOpenDiagnostics = onOpenDiagnostics
    }

    /// Preview/test lane: inject a scripted view model (the bare init above
    /// reads the live store).
    init(
        model: SettingsViewModel,
        selectedMode: AppMode = .streamify,
        onModeChange: @escaping (AppMode) -> Void = { _ in },
        onOpenAccounts: @escaping () -> Void = {},
        onOpenDsp: @escaping () -> Void = {},
        onOpenEqualizer: @escaping () -> Void = {},
        onOpenDownloads: @escaping () -> Void = {},
        onOpenUpdates: @escaping () -> Void = {},
        onOpenDiagnostics: @escaping () -> Void = {}
    ) {
        self._model = State(initialValue: model)
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
                section("Library") {
                    modePicker
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.avatar, title: "Accounts",
                                subtitle: "Connected services & taste sync", onTap: onOpenAccounts)
                }
                section("Sound") {
                    bitratePicker
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.dac, title: "Audio Quality & DSP",
                                subtitle: "Loudness, gapless, crossfade, limiter", onTap: onOpenDsp)
                    rowDivider
                    SettingsRow(icon: StreamifyIcon.sliders, title: "Equalizer",
                                subtitle: "Tune bass, treble, and surround sound", onTap: onOpenEqualizer)
                }
                section("Storage") {
                    cacheBudgetRow
                    rowDivider
                    clearCacheRow
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
                section("Diagnostics logging") {
                    slogRows
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

    // MARK: - Streaming quality (WS-B B2: routed through SettingsViewModel)

    private var bitratePicker: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text("Streaming quality").font(StreamifyType.bodyMedium).foregroundStyle(StreamifyColors.textMain)
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(model.bitrateOptions) { bitrate in
                    Button {
                        Haptics.play(.scrubTick)
                        model.setStreamBitrate(bitrate)
                    } label: {
                        Text(bitrate.rawValue).font(StreamifyType.chipText).lineLimit(1)
                            .foregroundStyle(
                                model.streamBitrate == bitrate
                                    ? StreamifyColors.textOnActiveChip
                                    : StreamifyColors.textMain
                            )
                            .padding(.horizontal, StreamifyDimens.spaceMD)
                            .frame(height: StreamifyDimens.filterChipHeight)
                            .chipSurface(active: model.streamBitrate == bitrate)
                    }
                    .buttonStyle(.pressableCard)
                }
            }
            Text(model.streamBitrate.detail)
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }

    // MARK: - Offline cache (budget + clear — routed through the VM)

    private var cacheBudgetRow: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Offline cache budget").font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text("Space the offline vault may use for audio and artwork")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            Spacer(minLength: StreamifyDimens.spaceSM)
            Stepper(
                onIncrement: { model.setCacheBudget(model.cacheBudgetGb + 1) },
                onDecrement: { model.setCacheBudget(model.cacheBudgetGb - 1) },
                label: {
                    Text("\(model.cacheBudgetGb) GB")
                        .font(StreamifyType.bodyMedium)
                        .monospacedDigit()
                        .foregroundStyle(StreamifyColors.textMain)
                }
            )
            .fixedSize()
        }
    }

    private var clearCacheRow: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text(
                "Cache usage \(String(format: "%.1f", model.cacheUsageGb)) GB of \(model.cacheBudgetGb) GB — engine-reported usage lights up on the live lane."
            )
            .font(StreamifyType.caption)
            .foregroundStyle(StreamifyColors.textSecondary)
            Button {
                Haptics.play(.queueDrop)
                Task { await model.clearCache() }
            } label: {
                Label("Clear offline cache", systemImage: StreamifyIcon.trash)
                    .font(StreamifyType.chipText.weight(.semibold))
                    .foregroundStyle(StreamifyColors.errorRed)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(StreamifyColors.errorRed.opacity(0.12), in: Capsule())
            }
            .buttonStyle(.pressableCard)
        }
    }

    // MARK: - SLog switches (routed through the VM)

    private var slogRows: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            ForEach(Array(model.slogRows.enumerated()), id: \.element.id) { index, logSwitch in
                if index > 0 { rowDivider }
                Toggle(isOn: Binding(
                    get: { model.slogSwitches[logSwitch] ?? false },
                    set: { model.setSLogEnabled(logSwitch, $0) }
                )) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(logSwitch.label)
                            .font(StreamifyType.bodyMedium)
                            .foregroundStyle(StreamifyColors.textMain)
                        Text("Stream \(logSwitch.rawValue) events into the live SLog tail")
                            .font(StreamifyType.caption)
                            .foregroundStyle(StreamifyColors.textSecondary)
                    }
                }
                .tint(StreamifyColors.primary)
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
    SettingsView(model: SettingsViewModel(settings: SettingsStore.ephemeral()))
}
