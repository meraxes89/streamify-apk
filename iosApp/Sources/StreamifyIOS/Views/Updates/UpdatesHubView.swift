import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — UpdatesHubView: the "Your Updates" release feed.
//
// SwiftUI port of ui/screens/UpdatesHubScreen.kt: one card per followed
// artist release — artist-seeded gradient strip, release-type chip, track
// count, a live 1 s-countdown TimelineView for embargoed drops with the
// pre-save action, and an "Out now" chip for releases already live.
//
// WS-B B2 (de-fixturing): the screen OWNS its UpdatesHubViewModel and rides
// the live feature lane (`AppServices.library` → UniFFIFeatureLibrary when
// the engine is linked). The feed renders the VM's ordered projection
// (upcoming first, pre-saves pinned); pre-save toggles ROUTE THROUGH THE VM
// (persisted in the real SettingsStore) and are merely reported through
// onPreSave. The empty state is the HONEST engine state — no PreviewCatalog
// seeding on the production path; previews inject a scripted
// PreviewFeatureLibrary.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct UpdatesHubView: View {

    /// WS-B B2: the screen's own state holder — constructed bare, it rides
    /// the live feature lane automatically (one harvest per appearance,
    /// pull-to-refresh; per-artist failures swallow silently).
    @State private var model = UpdatesHubViewModel()

    public var onPreSave: (String) -> Void

    public init(onPreSave: @escaping (String) -> Void = { _ in }) {
        self.onPreSave = onPreSave
    }

    /// Preview/test lane: inject a scripted view model (the bare init above
    /// rides `AppServices.library`).
    init(
        model: UpdatesHubViewModel,
        onPreSave: @escaping (String) -> Void = { _ in }
    ) {
        self._model = State(initialValue: model)
        self.onPreSave = onPreSave
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                header
                if model.isLoading {
                    loadingFeed
                } else if model.feed.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.related,
                        title: "No updates yet",
                        message: "Follow artists to see their new releases and countdowns here."
                    )
                    .frame(maxHeight: 280)
                } else {
                    ForEach(model.feed) { release in
                        UpdateCard(release: release, model: model) {
                            handlePreSave(release)
                        }
                    }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .task { model.loadIfNeeded() }
        .refreshable { await model.refresh() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Your Updates")
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(StreamifyColors.textMain)
            Text("New releases from artists you follow — countdowns tick live.")
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }

    /// Pre-save intent: the VM owns the persisted toggle (SettingsStore);
    /// the host callback is a save notification only.
    private func handlePreSave(_ release: ReleaseCandidate) {
        let wasPreSaved = model.isPreSaved(release)
        Haptics.play(wasPreSaved ? .scrubTick : .queueDrop)
        model.togglePreSave(release)
        if !wasPreSaved {
            onPreSave(release.releaseId)
        }
    }

    /// The harvest-in-flight state — redacted release cards.
    private var loadingFeed: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            ForEach(0..<2, id: \.self) { _ in
                RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                    .fill(StreamifyColors.bgCard)
                    .frame(height: 148)
            }
        }
        .redacted(reason: .placeholder)
        .accessibilityLabel("Loading updates")
    }
}

/// One release card: gradient strip, artist + release-type chip, track count
/// and the countdown / pre-save row (or the "Out now" chip once released).
private struct UpdateCard: View {
    let release: ReleaseCandidate
    let model: UpdatesHubViewModel
    let onPreSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            UnevenRoundedRectangle(
                topLeadingRadius: 8, topTrailingRadius: 8, style: .continuous
            )
            .fill(
                StreamifyGradients.linear(
                    StreamifyGradients.preset(forSeed: release.artistName)
                )
            )
            .frame(height: 5)

            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(release.artistName.uppercased())
                        .font(StreamifyType.caption)
                        .tracking(1.0)
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Spacer(minLength: 0)
                    Text(release.releaseType)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textMain)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(StreamifyColors.bgChipInactive))
                }
                Text(release.title)
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)

                if release.trackCount > 0 {
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Image(systemName: StreamifyIcon.musicNoteList)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(StreamifyColors.successGreen)
                        Text("\(release.trackCount) tracks")
                            .font(StreamifyType.bodySmall)
                            .foregroundStyle(StreamifyColors.textSecondary)
                    }
                }

                releaseRow
            }
            .padding(StreamifyDimens.spaceLG)
        }
        .cardSurface(hoverable: false)
    }

    // MARK: - Countdown / pre-save row

    @ViewBuilder
    private var releaseRow: some View {
        if let expectedAt = release.expectedAt {
            TimelineView(.periodic(from: .now, by: 1)) { timeline in
                if expectedAt > timeline.date {
                    HStack(spacing: StreamifyDimens.spaceMD) {
                        // Countdown chips recompute per display frame from
                        // the stored ETA — the VM holds static data, never a
                        // ticking clock.
                        Text(model.countdownLabel(for: release, at: timeline.date))
                            .font(StreamifyType.bodySmall)
                            .monospacedDigit()
                            .foregroundStyle(StreamifyColors.textMain)
                        Spacer(minLength: 0)
                        preSaveButton
                    }
                } else {
                    outNowRow
                }
            }
        } else {
            outNowRow
        }
    }

    private var outNowRow: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Text("Out now")
                .font(StreamifyType.chipText)
                .foregroundStyle(StreamifyColors.successGreen)
                .padding(.horizontal, StreamifyDimens.spaceMD)
                .padding(.vertical, StreamifyDimens.spaceXS)
                .background(Capsule().fill(StreamifyColors.successGreen.opacity(0.14)))
            Spacer(minLength: 0)
        }
    }

    private var isPreSaved: Bool {
        model.isPreSaved(release)
    }

    private var preSaveButton: some View {
        Button(action: onPreSave) {
            HStack(spacing: 6) {
                Image(systemName: isPreSaved ? StreamifyIcon.checkmark : StreamifyIcon.downloadFill)
                    .font(.system(size: 11, weight: .bold))
                Text(isPreSaved ? "Pre-saved" : "Pre-save")
                    .font(StreamifyType.chipText)
            }
            .foregroundStyle(
                isPreSaved
                    ? StreamifyColors.successGreen
                    : StreamifyColors.textOnActiveChip
            )
            .padding(.horizontal, StreamifyDimens.spaceMD)
            .frame(height: 30)
            .background(
                Capsule().fill(
                    isPreSaved
                        ? StreamifyColors.successGreen.opacity(0.16)
                        : StreamifyColors.primary
                )
            )
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Updates hub") {
    let scripted = PreviewFeatureLibrary()
    scripted.radarContent = [
        ReleaseWatch(
            artistName: "Aurora Nine",
            releases: [
                ReleaseCandidate(
                    releaseId: "u1",
                    artistName: "Aurora Nine",
                    title: "Meridian (Deluxe)",
                    releaseType: "2025 reissue",
                    expectedAt: Calendar.current.date(byAdding: .day, value: 3, to: .now),
                    trackCount: 15
                ),
            ]
        ),
        ReleaseWatch(
            artistName: "Vetra",
            releases: [
                ReleaseCandidate(
                    releaseId: "u2",
                    artistName: "Vetra",
                    title: "Aphelion Drift — Single",
                    releaseType: "Single",
                    trackCount: 1
                ),
            ]
        ),
    ]
    return UpdatesHubView(
        model: UpdatesHubViewModel(library: scripted, settings: SettingsStore.ephemeral())
    )
}

#Preview("Updates hub — empty") {
    UpdatesHubView()
}
