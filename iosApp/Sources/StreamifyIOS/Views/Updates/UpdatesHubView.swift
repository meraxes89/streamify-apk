import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — UpdatesHubView: the "Your Updates" release feed.
//
// SwiftUI port of ui/screens/UpdatesHubScreen.kt: one card per followed
// artist release — artist-seeded gradient strip, version chip, note
// bullets, a live 1 s-countdown TimelineView for embargoed drops with the
// pre-save action, and an "Out now" chip for releases already live.
// Pre-save state is local (seeded from the entry fixtures) and reported
// through onPreSave.
// ─────────────────────────────────────────────────────────────────────────────

public struct UpdatesHubView: View {
    public var updates: [UpdateEntry]
    public var onPreSave: (String) -> Void

    @State private var preSavedIds: Set<String>

    public init(
        updates: [UpdateEntry] = PreviewCatalog.updates,
        onPreSave: @escaping (String) -> Void = { _ in }
    ) {
        self.updates = updates
        self.onPreSave = onPreSave
        self._preSavedIds = State(
            initialValue: Set(updates.filter(\.isPreSaved).map(\.id))
        )
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                header
                if updates.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.related,
                        title: "No updates yet",
                        message: "Follow artists to see their new releases and countdowns here."
                    )
                    .frame(maxHeight: 280)
                } else {
                    ForEach(updates) { entry in
                        UpdateCard(
                            entry: entry,
                            isPreSaved: preSavedIds.contains(entry.id),
                            onPreSave: { handlePreSave(entry.id) }
                        )
                    }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
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

    private func handlePreSave(_ id: String) {
        if preSavedIds.contains(id) {
            Haptics.play(.scrubTick)
            preSavedIds.remove(id)
        } else {
            Haptics.play(.queueDrop)
            preSavedIds.insert(id)
            onPreSave(id)
        }
    }
}

/// One release card: gradient strip, artist + version chip, note bullets and
/// the countdown / pre-save row (or the "Out now" chip once released).
private struct UpdateCard: View {
    let entry: UpdateEntry
    let isPreSaved: Bool
    let onPreSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            UnevenRoundedRectangle(
                topLeadingRadius: 8, topTrailingRadius: 8, style: .continuous
            )
            .fill(
                StreamifyGradients.linear(
                    StreamifyGradients.preset(forSeed: entry.artist)
                )
            )
            .frame(height: 5)

            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(entry.artist.uppercased())
                        .font(StreamifyType.caption)
                        .tracking(1.0)
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Spacer(minLength: 0)
                    Text(entry.version)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textMain)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(StreamifyColors.bgChipInactive))
                }
                Text(entry.title)
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)

                ForEach(entry.notes, id: \.self) { note in
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Image(systemName: StreamifyIcon.checkmark)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(StreamifyColors.successGreen)
                        Text(note)
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
        if let releaseDate = entry.releaseDate {
            TimelineView(.periodic(from: .now, by: 1)) { timeline in
                if releaseDate > timeline.date {
                    HStack(spacing: StreamifyDimens.spaceMD) {
                        Text(Self.countdownLabel(to: releaseDate, from: timeline.date))
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

    /// "3d 04:22:11" — days over a zero-padded wall-clock remainder.
    private static func countdownLabel(to target: Date, from now: Date) -> String {
        let remaining = max(0, Int(target.timeIntervalSince(now).rounded(.up)))
        let days = remaining / 86_400
        let hours = (remaining % 86_400) / 3_600
        let minutes = (remaining % 3_600) / 60
        let seconds = remaining % 60
        return String(format: "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
    }
}

#Preview("Updates hub") {
    UpdatesHubView()
}
