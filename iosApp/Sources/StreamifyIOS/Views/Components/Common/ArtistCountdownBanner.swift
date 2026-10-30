import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the upcoming-release countdown banner.
//
// Port of ui/components/ArtistCountdownBanner.kt: the ArtistScreen promo
// strip — seeded gradient plate, artist + release title, a LIVE countdown
// (days / hours / minutes to the release Date, re-evaluated by a TimelineView
// every 60 s — the native counterpart of the Android caller-fed 1 Hz clock,
// coarser because the banner's smallest unit is a minute), and the Pre-Save
// button state. Fixtures: PreviewCatalog.updates / tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct ArtistCountdownBanner: View {
    public let artistName: String
    public let releaseTitle: String
    public let releaseDate: Date
    public var isPreSaved: Bool = false
    public var onTogglePreSave: (() -> Void)? = nil

    public init(
        artistName: String,
        releaseTitle: String,
        releaseDate: Date,
        isPreSaved: Bool = false,
        onTogglePreSave: (() -> Void)? = nil
    ) {
        self.artistName = artistName
        self.releaseTitle = releaseTitle
        self.releaseDate = releaseDate
        self.isPreSaved = isPreSaved
        self.onTogglePreSave = onTogglePreSave
    }

    public var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { timeline in
            banner(countdownLabel(from: timeline.date))
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(StreamifyColors.primary.opacity(0.35), lineWidth: 1)
        )
    }

    private func banner(_ countdown: String) -> some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Rectangle()
                    .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: artistName)))
                Image(systemName: StreamifyIcon.clock)
                    .font(.system(size: 22))
                    .foregroundStyle(Color.white.opacity(0.9))
            }
            .frame(width: 64, height: 64)
            .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Image(systemName: StreamifyIcon.broadcast)
                        .font(.system(size: 10, weight: .bold))
                    Text("UPCOMING RELEASE")
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.8)
                }
                .foregroundStyle(StreamifyColors.primary)
                Text(releaseTitle)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text("\(countdown) • \(artistName)")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)

            preSaveButton
        }
        .padding(14)
        .background(
            LinearGradient(
                colors: [
                    StreamifyColors.primary.opacity(0.10),
                    StreamifyColors.bgCard
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        )
    }

    private var preSaveButton: some View {
        Button {
            Haptics.play(.likeHeartbeat)
            onTogglePreSave?()
        } label: {
            HStack(spacing: 5) {
                Image(systemName: isPreSaved ? StreamifyIcon.checkmark : StreamifyIcon.plus)
                    .font(.system(size: 12, weight: .bold))
                Text(isPreSaved ? "Saved" : "Pre-Save")
                    .font(.system(size: 12, weight: .bold))
                    .lineLimit(1)
            }
            .foregroundStyle(isPreSaved ? StreamifyColors.textOnActiveChip : StreamifyColors.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                isPreSaved ? StreamifyColors.primary : StreamifyColors.primary.opacity(0.16),
                in: StreamifyShapes.actionPill
            )
            .overlay(
                StreamifyShapes.actionPill.strokeBorder(
                    isPreSaved ? StreamifyColors.primary : StreamifyColors.primary.opacity(0.5),
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.pressableCard)
    }

    /// "3d 4h 12m" — Calendar-based, clamped at zero once the release lands.
    private func countdownLabel(from now: Date) -> String {
        let remaining = releaseDate.timeIntervalSince(now)
        guard remaining > 0 else { return "Out now" }
        let components = Calendar.current.dateComponents(
            [.day, .hour, .minute],
            from: now,
            to: releaseDate
        )
        let days = max(components.day ?? 0, 0)
        let hours = max(components.hour ?? 0, 0)
        let minutes = max(components.minute ?? 0, 0)
        return "\(days)d \(hours)h \(minutes)m"
    }
}

#Preview {
    ArtistCountdownBanner(
        artistName: PreviewCatalog.updates[0].artist,
        releaseTitle: PreviewCatalog.updates[0].title,
        releaseDate: Calendar.current.date(byAdding: .day, value: 2, to: .now) ?? .now,
        isPreSaved: false,
        onTogglePreSave: {}
    )
    .padding(.horizontal, StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
