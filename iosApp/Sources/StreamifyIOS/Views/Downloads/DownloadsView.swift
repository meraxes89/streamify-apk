import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — DownloadsView: the transfer manager surface.
//
// SwiftUI port of ui/screens/DownloadScreen.kt (its storage accounting card +
// transfer list, SW-ENG2-09 fixtures): storage summary with a thin capacity
// bar and legend chips, All / Downloading / Done / Failed filter chips, one
// row per DownloadTransfer — live progress, queued / paused / completed /
// failed states, pause-resume and remove actions.
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

public struct DownloadsView: View {
    public var transfers: [DownloadTransfer]
    public var onRetry: (String) -> Void
    public var onPauseResume: (String) -> Void
    public var onRemove: (String) -> Void

    @State private var activeFilter: DownloadFilter = .all
    /// Storage accounting fixtures (the live report is a host concern).
    private let usedGb = 18.2
    private let totalGb = 64.0
    private let legendQualities = ["FLAC 24/96", "AAC 256"]

    public init(
        transfers: [DownloadTransfer] = PreviewCatalog.downloads,
        onRetry: @escaping (String) -> Void = { _ in },
        onPauseResume: @escaping (String) -> Void = { _ in },
        onRemove: @escaping (String) -> Void = { _ in }
    ) {
        self.transfers = transfers
        self.onRetry = onRetry
        self.onPauseResume = onPauseResume
        self.onRemove = onRemove
    }

    private var visibleTransfers: [DownloadTransfer] {
        transfers.filter(activeFilter.matches)
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                Text("Downloads").font(StreamifyType.headlineLarge).foregroundStyle(StreamifyColors.textMain)
                storageCard
                filterChips
                if visibleTransfers.isEmpty {
                    EmptyStateView(symbol: StreamifyIcon.download, title: "No downloads here",
                                   message: "Switch the filter or start a transfer to fill this queue.")
                        .frame(maxHeight: 220)
                } else {
                    ForEach(visibleTransfers) { transfer in
                        TransferRow(transfer: transfer, onRetry: onRetry, onPauseResume: onPauseResume, onRemove: onRemove)
                    }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Storage summary (Android DownloadQualityCard)

    private var storageCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            Text("\(String(format: "%.1f", usedGb)) GB of \(Int(totalGb)) GB used")
                .font(StreamifyType.bodyMedium).monospacedDigit().foregroundStyle(StreamifyColors.textMain)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(StreamifyColors.divider)
                    Capsule().fill(StreamifyColors.primary)
                        .frame(width: geo.size.width * usedGb / totalGb)
                }
            }
            .frame(height: 3)
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
/// state projection, and pause/resume + remove actions.
private struct TransferRow: View {
    let transfer: DownloadTransfer
    let onRetry: (String) -> Void
    let onPauseResume: (String) -> Void
    let onRemove: (String) -> Void

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
                if showsPauseResume {
                    actionButton(icon: isPaused ? StreamifyIcon.play : StreamifyIcon.pause,
                                 tint: StreamifyColors.textMain, background: StreamifyColors.bgChipInactive) {
                        onPauseResume(transfer.id)
                    }
                }
                actionButton(icon: StreamifyIcon.trash, tint: StreamifyColors.errorRed,
                             background: StreamifyColors.errorBg) {
                    onRemove(transfer.id)
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
                ProgressView(value: progress).tint(StreamifyColors.primary)
                Text("\(Int((progress * 100).rounded())) % · \(String(format: "%.1f", transfer.sizeMb * progress)) MB/s")
                    .font(StreamifyType.caption).monospacedDigit().foregroundStyle(StreamifyColors.textSecondary)
            }
        case .queued:
            stateCaption(icon: StreamifyIcon.clock, text: "Queued")
        case .completed:
            HStack(spacing: 4) {
                Image(systemName: StreamifyIcon.checkmarkCircle).foregroundStyle(StreamifyColors.successGreen)
                Text("\(String(format: "%.1f", transfer.sizeMb)) MB").monospacedDigit()
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
                Button(action: { Haptics.play(.scrubTick); onRetry(transfer.id) }) {
                    Text("Retry").font(StreamifyType.chipText).foregroundStyle(StreamifyColors.textOnActiveChip)
                        .padding(.horizontal, StreamifyDimens.spaceMD).frame(height: 24)
                        .background(Capsule().fill(StreamifyColors.primary))
                }
                .buttonStyle(.pressableCard)
            }
        }
    }
}

#Preview("Downloads") {
    DownloadsView()
}
