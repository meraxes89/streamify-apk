import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B7 (Engineer 2) — DownloadsView: the transfer manager surface, WIRED.
//
// SwiftUI port of ui/screens/DownloadScreen.kt (its storage accounting card +
// transfer list, originally SW-ENG2-08): storage summary with a thin capacity
// bar and legend chips, All / Downloading / Done / Failed filter chips, one
// row per DownloadTransfer — live progress, queued / paused / completed /
// failed states, pause-resume, remove, retry and OFFLINE PLAY actions.
//
// The SW-ENG2-08 fixture feed is RETIRED: the screen now OWNS
// DownloadViewModel (the BackgroundDownloadService wiring — live progress
// aggregation, resume-data verification, real storage accounting, the
// on-disk track manifest) and renders model truth only. PreviewCatalog
// fixtures appear exclusively inside the #Preview lane (the internal
// fixture initializer); no production path touches them.
// ─────────────────────────────────────────────────────────────────────────────

/// Transfer filter chips (All shows every state).
private enum DownloadFilter: String, CaseIterable, Identifiable {
    case all
    case downloading
    case done
    case failed

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "All"
        case .downloading: return "Downloading"
        case .done: return "Done"
        case .failed: return "Failed"
        }
    }

    func matches(_ transfer: DownloadTransfer) -> Bool {
        switch self {
        case .all: return true
        case .downloading: if case .downloading = transfer.state { return true }; return false
        case .done: if case .completed = transfer.state { return true }; return false
        case .failed: if case .failed = transfer.state { return true }; return false
        }
    }
}

@MainActor
public struct DownloadsView: View {

    /// Offline play (WS-B B7): invoked with the manifest track AND the
    /// direct `file://` source URL of a completed transfer when its play
    /// affordance is tapped. The URL is NOT re-resolved — the engine's
    /// fetcher reads `file://` ranges straight off disk (airplane-mode
    /// safe). RootShellView wires this; nil (the default) hides the
    /// affordance entirely.
    public var onPlayOffline: ((PresentationTrack, String) -> Void)? = nil

    /// #Preview fixtures ONLY — nil in every production path (the view
    /// model drives; see `init(previewTransfers:)`).
    private let previewTransfers: [DownloadTransfer]?
    private let previewUsedGb: Double
    private let previewTotalGb: Double

    @State private var model = DownloadViewModel()
    @State private var activeFilter: DownloadFilter = .all

    public init(onPlayOffline: ((PresentationTrack, String) -> Void)? = nil) {
        self.onPlayOffline = onPlayOffline
        self.previewTransfers = nil
        self.previewUsedGb = 0
        self.previewTotalGb = 0
    }

    /// The #Preview lane: fixed fixtures, no view-model bring-up, no
    /// service, no start-up side effects. Internal by design — production
    /// call sites use `init(onPlayOffline:)`, so the bare `DownloadsView()`
    /// stays the model-driven path.
    init(
        previewTransfers: [DownloadTransfer],
        usedGb: Double = 0,
        totalGb: Double = 0
    ) {
        self.onPlayOffline = nil
        self.previewTransfers = previewTransfers
        self.previewUsedGb = usedGb
        self.previewTotalGb = totalGb
    }

    // MARK: - Model-driven data (fixtures never leak into these)

    private var transfers: [DownloadTransfer] {
        previewTransfers ?? model.transfers
    }

    private var usedGb: Double {
        previewTransfers == nil ? model.usedGb : previewUsedGb
    }

    private var totalGb: Double {
        previewTransfers == nil ? model.totalGb : previewTotalGb
    }

    private var visibleTransfers: [DownloadTransfer] {
        transfers.filter(activeFilter.matches)
    }

    /// Legend chips: the quality labels actually present in the list (the
    /// Android DownloadQualityCard fixture chips are gone).
    private var legendQualities: [String] {
        var seen: Set<String> = []
        return transfers.compactMap { seen.insert($0.quality).inserted ? $0.quality : nil }
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                Text("Downloads").font(StreamifyType.headlineLarge).foregroundStyle(StreamifyColors.textMain)
                recoveryBanner
                storageCard
                filterChips
                if transfers.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.download,
                        title: "No downloads yet",
                        message: "Download a track to take it with you — transfers resume automatically across restarts."
                    )
                    .frame(maxHeight: 220)
                } else if visibleTransfers.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.download,
                        title: "No downloads here",
                        message: "Switch the filter or start a transfer to fill this queue."
                    )
                    .frame(maxHeight: 220)
                } else {
                    ForEach(visibleTransfers) { transfer in
                        TransferRow(
                            transfer: transfer,
                            onRetry: { model.retry(transfer.id) },
                            onPauseResume: { model.pauseResume(transfer.id) },
                            onRemove: { model.remove(transfer.id) },
                            onPlayOffline: onPlayOffline,
                            offlineTrack: onPlayOffline == nil ? nil : model.offlineTrack(for: transfer)
                        )
                    }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .task {
            // The #Preview fixture lane never touches the service.
            if previewTransfers == nil { model.start() }
        }
        .refreshable {
            if previewTransfers == nil { await model.refresh() }
        }
    }

    // MARK: - Resume verification (B7's acceptance surface)

    /// "N downloads recovered" — rendered only when the cold-start
    /// verification actually re-armed persisted resume snapshots.
    @ViewBuilder
    private var recoveryBanner: some View {
        if case .verified(let count) = model.resumeVerification, count > 0 {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.checkmarkCircle)
                    .foregroundStyle(StreamifyColors.successGreen)
                Text("\(count) download\((count == 1 ? "" : "s")) recovered — picking up from saved progress")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                Spacer(minLength: 0)
            }
            .padding(StreamifyDimens.spaceMD)
            .cardSurface(hoverable: false)
        }
    }

    // MARK: - Storage summary (Android DownloadQualityCard)

    private var storageCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            if totalGb > 0 {
                Text("\(String(format: "%.1f", usedGb)) GB of \(Int(totalGb.rounded())) GB used")
                    .font(StreamifyType.bodyMedium).monospacedDigit().foregroundStyle(StreamifyColors.textMain)
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(StreamifyColors.divider)
                        Capsule().fill(StreamifyColors.primary)
                            .frame(width: geo.size.width * min(1, max(0, usedGb / totalGb)))
                    }
                }
                .frame(height: 3)
            } else {
                // Volume capacity unavailable: the honest used figure
                // alone — no fabricated denominator.
                Text(usedGb > 0 ? "\(String(format: "%.1f", usedGb)) GB used" : "No downloads stored")
                    .font(StreamifyType.bodyMedium).monospacedDigit().foregroundStyle(StreamifyColors.textMain)
            }
            if !legendQualities.isEmpty {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    ForEach(legendQualities, id: \.self) { quality in
                        Text(quality).font(StreamifyType.chipText).foregroundStyle(StreamifyColors.textSecondary)
                            .padding(.horizontal, StreamifyDimens.spaceSM)
                            .padding(.vertical, StreamifyDimens.spaceXS)
                            .chipSurface(active: false)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
    }

    private var filterChips: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            ForEach(DownloadFilter.allCases) { candidate in
                Button(action: {
                    Haptics.play(.scrubTick)
                    withAnimation(Motion.snappy) { activeFilter = candidate }
                }) {
                    Text(candidate.label).font(StreamifyType.chipText)
                        .foregroundStyle(activeFilter == candidate ? StreamifyColors.textOnActiveChip
                                         : StreamifyColors.textMain)
                        .padding(.horizontal, StreamifyDimens.spaceMD)
                        .frame(height: StreamifyDimens.filterChipHeight)
                        .chipSurface(active: activeFilter == candidate)
                }
                .buttonStyle(.pressableCard)
            }
            Spacer(minLength: 0)
        }
    }
}

/// One transfer row: seeded-gradient art, title/artist + quality chip, the
/// state projection, and offline-play / pause-resume / remove actions.
private struct TransferRow: View {
    let transfer: DownloadTransfer
    let onRetry: () -> Void
    let onPauseResume: () -> Void
    let onRemove: () -> Void
    var onPlayOffline: ((PresentationTrack, String) -> Void)? = nil
    /// The model's offline track for this row — non-nil only for a
    /// completed transfer whose vault file exists (the play affordance's
    /// gate, alongside `onPlayOffline` itself).
    var offlineTrack: PresentationTrack? = nil

    private var artGradient: LinearGradient {
        StreamifyGradients.linear(StreamifyGradients.preset(forSeed: transfer.title))
    }

    private var showsPauseResume: Bool {
        switch transfer.state {
        case .downloading, .queued, .paused: return true
        case .completed, .failed: return false
        }
    }

    private var isPaused: Bool { if case .paused = transfer.state { return true }; return false }

    /// The live row's fraction in [0, 1]; nil while the server has not
    /// advertised a total length (or the row is not downloading) — the
    /// indeterminate case, never a fabricated number.
    private var progress: Double? {
        if case .downloading(let fraction) = transfer.state { return fraction }
        return nil
    }

    private func actionButton(
        icon: String, tint: Color, background: Color, action: @escaping () -> Void
    ) -> some View {
        Button(action: { Haptics.play(.scrubTick); action() }) {
            Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 30, height: 30)
                .background(background, in: Circle())
        }
        .buttonStyle(.pressableCard)
    }

    private func stateCaption(icon: String, text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
            Text(text)
        }
        .font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
    }

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            RoundedRectangle(cornerRadius: 6, style: .continuous).fill(artGradient)
                .frame(width: 46, height: 46)
                .overlay {
                    Image(systemName: StreamifyIcon.musicNote).font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.white.opacity(0.92))
                }
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Text(transfer.title).font(StreamifyType.songTitle)
                        .foregroundStyle(StreamifyColors.textMain).lineLimit(1)
                    Text(transfer.quality).font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(StreamifyColors.bgChipInactive))
                }
                Text(transfer.artist).font(StreamifyType.songArtist)
                    .foregroundStyle(StreamifyColors.textSecondary).lineLimit(1)
                stateView
            }
            Spacer(minLength: StreamifyDimens.spaceSM)
            HStack(spacing: StreamifyDimens.spaceSM) {
                if let onPlayOffline, let offlineTrack, let playableUrl = transfer.playableUrl {
                    actionButton(
                        icon: StreamifyIcon.play,
                        tint: StreamifyColors.textOnActiveChip,
                        background: StreamifyColors.primary
                    ) {
                        onPlayOffline(offlineTrack, playableUrl)
                    }
                    .accessibilityLabel("Play offline")
                }
                if showsPauseResume {
                    actionButton(icon: isPaused ? StreamifyIcon.play : StreamifyIcon.pause,
                                 tint: StreamifyColors.textMain, background: StreamifyColors.bgChipInactive) {
                        onPauseResume()
                    }
                }
                actionButton(icon: StreamifyIcon.trash, tint: StreamifyColors.errorRed,
                             background: StreamifyColors.errorBg) {
                    onRemove()
                }
            }
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }

    @ViewBuilder
    private var stateView: some View {
        switch transfer.state {
        case .downloading(let progress):
            VStack(alignment: .leading, spacing: 4) {
                // Nil progress = the server has not advertised a length
                // yet: the indeterminate bar, never a fabricated fraction
                // (B7's honest-empty discipline).
                ProgressView(value: progress).tint(StreamifyColors.primary)
                Text(progressCaption)
                    .font(StreamifyType.caption).monospacedDigit()
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
        case .queued:
            stateCaption(icon: StreamifyIcon.clock, text: "Queued")
        case .completed:
            HStack(spacing: 4) {
                Image(systemName: StreamifyIcon.checkmarkCircle).foregroundStyle(StreamifyColors.successGreen)
                Text(transfer.sizeMb > 0 ? "\(String(format: "%.1f", transfer.sizeMb)) MB" : "Ready offline")
                    .monospacedDigit()
            }
            .font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
        case .paused:
            stateCaption(icon: StreamifyIcon.pause, text: "Paused")
        case .failed(let message):
            HStack(spacing: StreamifyDimens.spaceSM) {
                HStack(spacing: 4) {
                    Image(systemName: StreamifyIcon.warning).foregroundStyle(StreamifyColors.errorRed)
                    Text(message).foregroundStyle(StreamifyColors.errorRed).lineLimit(1)
                }
                .font(StreamifyType.caption)
                Button(action: { Haptics.play(.scrubTick); onRetry() }) {
                    Text("Retry").font(StreamifyType.chipText).foregroundStyle(StreamifyColors.textOnActiveChip)
                        .padding(.horizontal, StreamifyDimens.spaceMD).frame(height: 24)
                        .background(Capsule().fill(StreamifyColors.primary))
                }
                .buttonStyle(.pressableCard)
            }
        }
    }

    /// The live-progress caption: percent + transferred-of-total MB when
    /// the length is known, an honest plain "Downloading" otherwise.
    private var progressCaption: String {
        guard let progress else {
            return transfer.sizeMb > 0
                ? "Downloading · \(String(format: "%.1f", transfer.sizeMb)) MB"
                : "Downloading"
        }
        let percent = Int((progress * 100).rounded())
        guard transfer.sizeMb > 0 else { return "\(percent) %" }
        return "\(percent) % · \(String(format: "%.1f", transfer.sizeMb * progress)) of \(String(format: "%.1f", transfer.sizeMb)) MB"
    }
}

#Preview("Downloads") {
    DownloadsView(
        previewTransfers: PreviewCatalog.downloads,
        usedGb: 18.2,
        totalGb: 64.0
    )
}

#Preview("Empty") {
    DownloadsView(previewTransfers: [])
}
