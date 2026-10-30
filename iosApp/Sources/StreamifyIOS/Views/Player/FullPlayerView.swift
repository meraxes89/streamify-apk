import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-05 — the FullPlayerView: the audiophile now-playing surface.
//
// The FullPlayerSheet port, presented as an expandable interactive spring
// modal (MiniPlayerBar → InteractivePlayerSheet → this content): the
// mini-player's tap opens the player with Motion.playerTransition's
// grow-from-the-deck entrance, and the rubber-band drag dismissal lives in
// the sheet container — this view owns the CONTENT.
//
// AMBIENT BACKGROUND: DynamicMeshBackground renders the multi-stop blurred
// gradient generated from the album art palette (MeshGradient on iOS 18+ /
// the animated layered-gradient fallback on iOS 17 & macOS 14), washed with
// the obsidian playerScrim so typography keeps HIG contrast.
//
// ADAPTIVE (SW-DEC-04): compact widths stack player + swipeable pane strip
// (YtPlayerBottomTabs); regular widths (iPad split, Mac windows) dock the
// selected pane beside the player canvas (FullPlayerPanes).
//
// DAC PACING: the waveform scrub bar is fed by the hardware clock — the
// seek command truncates to ms only at the PlayerCommand boundary.
// ─────────────────────────────────────────────────────────────────────────────

public struct FullPlayerView: View {

    public var state: PlaybackUiState
    public var palette: AmbientPalette?
    public var lyrics: LyricsData

    @Binding public var queueState: QueueListState
    @Binding public var queueAutoMix: Bool

    public var onDismiss: () -> Void
    public var onPrevious: () -> Void = {}
    public var onToggle: () -> Void = {}
    public var onNext: () -> Void = {}
    /// Microsecond-precision seek (the μs → ms truncation happens at the
    /// PlayerCommand boundary).
    public var onSeekUs: (Double) -> Void = { _ in }
    public var onVolumeChange: (Double) -> Void = { _ in }
    public var onReorderQueue: ((IndexSet, Int) -> Void)? = nil
    public var onDeleteQueue: ((Int) -> Void)? = nil

    @State private var selectedPane: PlayerPane = .queue
    @Environment(\.horizontalSizeClass) private var sizeClass

    public init(
        state: PlaybackUiState,
        palette: AmbientPalette? = nil,
        lyrics: LyricsData = PreviewCatalog.lyrics,
        queueState: Binding<QueueListState>,
        queueAutoMix: Binding<Bool>,
        onDismiss: @escaping () -> Void,
        onPrevious: @escaping () -> Void = {},
        onToggle: @escaping () -> Void = {},
        onNext: @escaping () -> Void = {},
        onSeekUs: @escaping (Double) -> Void = { _ in },
        onVolumeChange: @escaping (Double) -> Void = { _ in },
        onReorderQueue: ((IndexSet, Int) -> Void)? = nil,
        onDeleteQueue: ((Int) -> Void)? = nil
    ) {
        self.state = state
        self.palette = palette
        self.lyrics = lyrics
        _queueState = queueState
        _queueAutoMix = queueAutoMix
        self.onDismiss = onDismiss
        self.onPrevious = onPrevious
        self.onToggle = onToggle
        self.onNext = onNext
        self.onSeekUs = onSeekUs
        self.onVolumeChange = onVolumeChange
        self.onReorderQueue = onReorderQueue
        self.onDeleteQueue = onDeleteQueue
    }

    private var ambient: AmbientPalette {
        palette ?? PreviewCatalog.palette(forTitle: state.currentTrack?.title ?? "streamify")
    }

    public var body: some View {
        ZStack {
            DynamicMeshBackground(palette: ambient, intensity: 0.8)
            StreamifyColors.playerScrim.ignoresSafeArea()

            if sizeClass == .regular {
                regularLayout
            } else {
                compactLayout
            }
        }
    }

    // MARK: - Compact (iPhone)

    private var compactLayout: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            chromeBar
            playerCore
            paneStrip
            YtPlayerBottomTabs(
                tabs: PlayerPane.allCases.map { YtPlayerTab(label: $0.label, icon: $0.icon) },
                selectedIndex: Binding(
                    get: { PlayerPane.allCases.firstIndex(of: selectedPane) ?? 0 },
                    set: { selectedPane = PlayerPane.allCases[$0] }
                ),
                onSelect: { _ in }
            )
        }
        .padding(.horizontal, StreamifyDimens.spaceXL)
        .padding(.bottom, StreamifyDimens.spaceLG)
    }

    private var paneStrip: some View {
        Group {
            switch selectedPane {
            case .queue:
                LandscapeQueuePane(
                    state: $queueState,
                    autoMix: $queueAutoMix,
                    onReorder: onReorderQueue,
                    onDelete: onDeleteQueue
                )
                .frame(maxHeight: 260)
            case .lyrics:
                LandscapeLyricsPane(
                    lyrics: lyrics,
                    positionMs: Double(state.positionForDisplayMs ?? 0)
                )
                .frame(maxHeight: 260)
            case .related:
                LandscapeRelatedPane()
                    .frame(maxHeight: 260)
            }
        }
        .clipShape(StreamifyShapes.card)
        .animation(Motion.fluid, value: selectedPane)
    }

    // MARK: - Regular (iPad / Mac)

    private var regularLayout: some View {
        HStack(spacing: 0) {
            VStack(spacing: StreamifyDimens.spaceLG) {
                chromeBar
                playerCore
            }
            .padding(.horizontal, StreamifyDimens.spaceHuge)
            .frame(maxWidth: .infinity)

            Divider().overlay(StreamifyColors.borderSubtle)

            VStack(spacing: 0) {
                YtPlayerBottomTabs(
                    tabs: PlayerPane.allCases.map { YtPlayerTab(label: $0.label, icon: $0.icon) },
                    selectedIndex: Binding(
                        get: { PlayerPane.allCases.firstIndex(of: selectedPane) ?? 0 },
                        set: { selectedPane = PlayerPane.allCases[$0] }
                    ),
                    onSelect: { _ in }
                )
                .padding(.top, StreamifyDimens.spaceMD)

                pane(for: selectedPane)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: 420)
        }
    }

    @ViewBuilder
    private func pane(for pane: PlayerPane) -> some View {
        switch pane {
        case .queue:
            LandscapeQueuePane(
                state: $queueState,
                autoMix: $queueAutoMix,
                onReorder: onReorderQueue,
                onDelete: onDeleteQueue
            )
        case .lyrics:
            LandscapeLyricsPane(
                lyrics: lyrics,
                positionMs: Double(state.positionForDisplayMs ?? 0)
            )
        case .related:
            LandscapeRelatedPane()
        }
    }

    // MARK: - Shared chrome & core

    private var chromeBar: some View {
        HStack {
            Button {
                Haptics.play(.playPauseClick)
                onDismiss()
            } label: {
                Image(systemName: StreamifyIcon.chevronDown)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 40, height: 40)
                    .background(.ultraThinMaterial, in: StreamifyShapes.actionPill)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close player")

            Spacer()

            if let jam = state.snapshot.jamSessionName {
                Label(jam, systemImage: StreamifyIcon.jam)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.ultraThinMaterial, in: StreamifyShapes.chip)
            }
        }
    }

    private var playerCore: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            TrackCoverArt(
                title: state.currentTrack?.title ?? "Nothing playing",
                artist: state.currentTrack?.artist ?? "",
                artworkUrl: state.currentTrack?.artworkUrl,
                size: min(StreamifyDimens.fullPlayerArtSize, 320),
                cornerRadius: StreamifyRadius.xxl,
                palette: ambient
            )

            VStack(spacing: StreamifyDimens.spaceXS) {
                Text(state.currentTrack?.title ?? "Control plane live")
                    .font(StreamifyType.playerTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                Text(state.currentTrack?.artist ?? state.status.displayName)
                    .font(StreamifyType.playerArtist)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }

            WaveformProgressSlider(
                positionUs: Double(state.positionMs ?? 0) * 1_000,
                durationUs: state.durationMs.map { Double($0) * 1_000 },
                isPlaying: state.isPlaying,
                onScrubChange: { _ in },
                onScrubEnd: { us in onSeekUs(us) }
            )

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

            YtPlayerActionPills(
                isCasting: false,
                isVideoMode: false,
                onCast: {},
                onToggleVideoMode: {},
                onSleepTimer: {},
                onComments: {},
                onShare: {}
            )

            VolumeSliderView(
                volume: state.volume,
                onVolumeChange: onVolumeChange
            )
            .frame(maxWidth: 360)
        }
    }
}

// MARK: - Previews

#Preview("Compact — playing") {
    FullPlayerView(
        state: PlaybackUiState(PreviewCatalog.playingSnapshot),
        queueState: .constant(PreviewCatalog.queueState),
        queueAutoMix: .constant(true),
        onDismiss: {}
    )
    .preferredColorScheme(.dark)
}

#Preview("Regular — ambient obsidian") {
    FullPlayerView(
        state: PlaybackUiState(PreviewCatalog.playingSnapshot),
        palette: AmbientPalette(dominantHex: 0x1E3264, accentHex: 0xFF0033, darkMutedHex: 0x0A0A0A),
        queueState: .constant(PreviewCatalog.queueState),
        queueAutoMix: .constant(true),
        onDismiss: {}
    )
    .environment(\.horizontalSizeClass, .regular)
    .preferredColorScheme(.dark)
}
