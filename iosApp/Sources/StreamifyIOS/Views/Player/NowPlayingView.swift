import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-05 — the compact NowPlayingView: the PlayerScreen/NowPlaying surface.
//
// The Home-tab-sized now-playing canvas (the Phase-5 surface, upgraded into
// the SW-ENG2 design system): transport, DAC-derived progress, volume and
// the honest engine-health readouts (revision, buffer fill, hardware DAC
// clock sample) — plus the queue sheet entry point and the expand affordance
// into the FullPlayerView modal.
//
// STATE DISCIPLINE: all playback truth arrives as pushed engine snapshots
// (MP-DEC-04); intent goes out through the command plane the shell wires.
// ─────────────────────────────────────────────────────────────────────────────

public struct NowPlayingView: View {

    public var state: PlaybackUiState
    public var onPrevious: () -> Void
    public var onToggle: () -> Void
    public var onNext: () -> Void
    public var onSeek: (Int) -> Void
    public var onVolumeChange: (Double) -> Void
    public var onOpenQueue: () -> Void
    public var onOpenFullPlayer: () -> Void

    public init(
        state: PlaybackUiState,
        onPrevious: @escaping () -> Void,
        onToggle: @escaping () -> Void,
        onNext: @escaping () -> Void,
        onSeek: @escaping (Int) -> Void,
        onVolumeChange: @escaping (Double) -> Void,
        onOpenQueue: @escaping () -> Void,
        onOpenFullPlayer: @escaping () -> Void
    ) {
        self.state = state
        self.onPrevious = onPrevious
        self.onToggle = onToggle
        self.onNext = onNext
        self.onSeek = onSeek
        self.onVolumeChange = onVolumeChange
        self.onOpenQueue = onOpenQueue
        self.onOpenFullPlayer = onOpenFullPlayer
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: StreamifyDimens.spaceLG) {
                header
                transportCard
                volumeCard
                engineHealthCard
            }
            .padding(StreamifyDimens.spaceXL)
            .centeredInLargeScreen()
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Now Playing")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    onOpenQueue()
                } label: {
                    Image(systemName: StreamifyIcon.queue)
                }
                .accessibilityLabel("Open queue")
            }
        }
    }

    private var header: some View {
        Button {
            onOpenFullPlayer()
        } label: {
            VStack(spacing: StreamifyDimens.spaceXS) {
                TrackCoverArt(
                    title: state.currentTrack?.title ?? "No track loaded",
                    artist: state.currentTrack?.artist ?? "",
                    artworkUrl: state.currentTrack?.artworkUrl,
                    size: 220,
                    cornerRadius: StreamifyRadius.xxl,
                    palette: nil
                )
                Text(state.currentTrack?.title ?? "Control plane live — no track loaded")
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(state.status.displayName)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
        }
        .buttonStyle(.pressableCard)
    }

    private var transportCard: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            HStack(spacing: 0) {
                // 1 Hz-quantized display position: the readout advances at
                // most once per second of content (positionForDisplayMs).
                Text(state.positionForDisplayMs.map(PlaybackFormatting.formatPlaybackTime) ?? "--:--")
                    .font(StreamifyType.caption.monospacedDigit())
                    .foregroundStyle(StreamifyColors.textTertiary)
                ProgressView(value: state.progressFraction ?? 0)
                    .tint(StreamifyColors.primary)
                    .padding(.horizontal, StreamifyDimens.spaceSM)
                Text(state.durationMs.map(PlaybackFormatting.formatPlaybackTime) ?? "--:--")
                    .font(StreamifyType.caption.monospacedDigit())
                    .foregroundStyle(StreamifyColors.textTertiary)
            }

            PlayerControls(
                isPlaying: state.isPlaying,
                isBuffering: state.isBuffering,
                isShuffle: false,
                repeatMode: 0,
                onTogglePlayPause: onToggle,
                onPrevious: onPrevious,
                onNext: onNext,
                onToggleShuffle: {},
                onToggleRepeat: {}
            )
        }
        .padding(StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
    }

    private var volumeCard: some View {
        VolumeSliderView(
            volume: state.volume,
            onVolumeChange: onVolumeChange
        )
        .padding(StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
    }

    private var engineHealthCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text("Engine health")
                .font(StreamifyType.titleMedium)
                .foregroundStyle(StreamifyColors.textMain)
            metric("revision", "rev \(state.snapshot.rev)")
            metric(
                "buffer",
                state.snapshot.buffer.map {
                    "\($0.bufferedDurationMs / 1000)s buffered · \($0.underrunCount) underruns · \(Int($0.fillRatio * 100))% fill"
                } ?? "no buffer stats yet"
            )
            metric(
                "dac clock",
                state.snapshot.clock.map {
                    "\($0.framePosition) frames @ \($0.sampleRateHz) Hz"
                } ?? "no hardware sample yet"
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
    }

    private func metric(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
                .frame(width: 90, alignment: .leading)
            Text(value)
                .font(StreamifyType.caption.monospaced())
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }
}

#Preview {
    NavigationStack {
        NowPlayingView(
            state: PlaybackUiState(PreviewCatalog.playingSnapshot),
            onPrevious: {},
            onToggle: {},
            onNext: {},
            onSeek: { _ in },
            onVolumeChange: { _ in },
            onOpenQueue: {},
            onOpenFullPlayer: {}
        )
    }
    .preferredColorScheme(.dark)
}
