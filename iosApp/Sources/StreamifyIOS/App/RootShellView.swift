import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-02 — the multiplatform responsive shell.
//
// ONE universal root, TWO native idioms (SW-DEC-04 — the same core SwiftUI
// screens seamlessly adapt):
//
//   • COMPACT (iPhone): a fluid native TabView — Home / Search / Library /
//     Jam / Settings — with the MiniPlayerBar docked above the tab bar and
//     value-based NavigationStack pushes per tab (state survives tab
//     switches, YTM-style).
//
//   • REGULAR (iPad / Mac): a native NavigationSplitView with the sidebar
//     navigation rail (primary destinations + the discover/system group),
//     top toolbar actions, and the detail canvas — the HIG Mac window.
//
// Value-based navigation: pushes are `AppRoute` VALUES, registered once per
// stack through `.navigationDestination(for: AppRoute.self)`.
//
// THE DOCK (both idioms): the MiniPlayerBar — tap expands the FullPlayerView
// interactive spring modal (SW-ENG2-05); horizontal swipe skips with the
// rubber-band resistance (LiquidMorphGeometry).
//
// STATE DISCIPLINE: the engine pushes session snapshots (MP-DEC-04); the
// shell owns NO engine truth — it lowers model state onto screens and routes
// intent back through the command plane. The play QUEUE is the ENGINE's
// queue (WS-B B2): QueueViewModel consumes the feature library's
// push-synthesized queue snapshots, and the view-level `QueueListState`
// below is a derived projection — mutations dispatch back through the
// view model, never local shell state.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct RootShellView: View {

    // Engineer 4's @Observable state holder (merged from main): consumed
    // through the environment (SW-DEC-02), not the ObservableObject plane.
    @Environment(PlaybackViewModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass

    // Compact: the selected tab. Regular: the sidebar rail selection.
    @State private var selectedTab = 0
    @State private var sidebarSection = SidebarSection.home

    // Per-destination push paths (state survives tab switches on compact).
    @State private var homePath = NavigationPath()
    @State private var searchPath = NavigationPath()
    @State private var libraryPath = NavigationPath()
    @State private var jamPath = NavigationPath()
    @State private var settingsPath = NavigationPath()
    @State private var detailPath = NavigationPath()

    // WS-B B2: queue truth — the ENGINE's queue, pushed through the feature
    // library's synthesized queue-event lane (no PreviewCatalog seeding, no
    // host-owned copy). The QueueListState handed to QueueView /
    // FullPlayerView is a derived projection below.
    @State private var queueModel = QueueViewModel()

    // Presentation surfaces.
    @State private var fullPlayerPresented = false
    @State private var queuePresented = false

    // Brand launch choreography (iPhone: the PrismaticSplashScreen port).
    @State private var splashVisible = true

    public init() {}

    public var body: some View {
        ZStack {
            shellRoot

            #if os(iOS)
            if splashVisible {
                SplashView {
                    withAnimation(.easeOut(duration: Motion.slowDuration)) {
                        splashVisible = false
                    }
                }
                .transition(.opacity)
                .zIndex(1)
            }
            #endif
        }
        .tint(StreamifyColors.primary)
        .task {
            // WS-B B2: the queue plane consumes the engine's synthesized
            // queue pushes for the shell's lifetime.
            queueModel.start()
            connectCarPlayRouter()
        }
        .fullPlayerSheet(isPresented: $fullPlayerPresented) {
            FullPlayerView(
                state: model.state,
                queueState: queueListBinding,
                queueAutoMix: queueAutoMixBinding,
                onDismiss: { fullPlayerPresented = false },
                onPrevious: { model.previous() },
                onToggle: { model.togglePlayPause() },
                onNext: { model.next() },
                onSeekUs: { us in model.seek(to: Int(us / 1_000)) },
                onVolumeChange: { model.setVolume($0) },
                onReorderQueue: { offsets, destination in
                    queueModel.move(fromOffsets: offsets, toOffset: destination)
                },
                onDeleteQueue: { sectionIndex in
                    queueModel.removeUpNext(at: sectionIndex)
                }
            )
        }
        .sheet(isPresented: $queuePresented) {
            queueSheet
        }
    }

    // MARK: - WS-B B2: the derived queue projection (engine truth → views)

    /// QueueView/FullPlayerView speak the view-level `QueueListState`
    /// currency; the truth behind it is the engine's queue via
    /// QueueViewModel. Mutations NEVER land in the binding's setter — they
    /// dispatch through the view model (the callbacks below).
    private var queueListBinding: Binding<QueueListState> {
        Binding(
            get: {
                QueueListState(
                    tracks: queueModel.tracks,
                    currentIndex: queueModel.currentIndex,
                    isPlaying: queueModel.isPlaying
                )
            },
            set: { _ in }
        )
    }

    private var queueAutoMixBinding: Binding<Bool> {
        Binding(
            get: { queueModel.autoMixEnabled },
            set: { queueModel.setAutoMix($0) }
        )
    }

    /// WS-B B6: routes the CarPlay scene (if the OS connected one) onto the
    /// same command plane as the in-app transport — the scene delegate's
    /// `router` is assigned the moment the shell comes up (and re-pointed
    /// here on every fresh shell instance).
    private func connectCarPlayRouter() {
        #if canImport(CarPlay) && canImport(UIKit)
        CarPlaySceneDelegate.active?.router = model
        #endif
    }

    // MARK: - Root idiom selection

    @ViewBuilder
    private var shellRoot: some View {
        #if os(macOS)
        splitShell
        #else
        if sizeClass == .compact {
            compactShell
        } else {
            splitShell
        }
        #endif
    }

    // MARK: - Compact shell (iPhone)

    private var compactShell: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                stack($homePath) {
                    homeScreen(path: $homePath)
                        .toolbar { playerToolbar }
                }
                    .tabItem { Label("Home", systemImage: StreamifyIcon.home) }
                    .tag(0)

                stack($searchPath) { searchScreen(path: $searchPath) }
                    .tabItem { Label("Search", systemImage: StreamifyIcon.search) }
                    .tag(1)

                stack($libraryPath) { libraryScreen(path: $libraryPath) }
                    .tabItem { Label("Library", systemImage: StreamifyIcon.library) }
                    .tag(2)

                stack($jamPath) { jamScreen }
                    .tabItem { Label("Jam", systemImage: StreamifyIcon.jam) }
                    .tag(3)

                stack($settingsPath) { settingsScreen(path: $settingsPath) }
                    .tabItem { Label("Settings", systemImage: StreamifyIcon.settings) }
                    .tag(4)
            }
            .onChange(of: selectedTab) { _, _ in
                Haptics.play(.queueBoundaryTick)
            }

            // The docked mini player rides ABOVE the tab bar (SW-ENG2-02).
            VStack(spacing: 0) {
                Spacer()
                miniPlayerDock
                    .padding(.horizontal, StreamifyDimens.spaceSM)
                    .padding(.bottom, 52)
            }
            .allowsHitTesting(true)
        }
        .background(StreamifyColors.bgBase.ignoresSafeArea())
    }

    // MARK: - Regular shell (iPad / Mac)

    public enum SidebarSection: String, Hashable, Identifiable, CaseIterable, Sendable {
        case home, search, library, jam, settings
        case nowPlaying, equalizer, dsp, downloads, updates
        case community, wrapped, blend, profileSelection, admin, diagnostics, dogfood, onboarding

        public var id: String { rawValue }

        var label: String {
            switch self {
            case .home: return "Home"
            case .search: return "Search"
            case .library: return "Library"
            case .jam: return "Jam Sessions"
            case .settings: return "Settings"
            case .nowPlaying: return "Now Playing"
            case .equalizer: return "Equalizer"
            case .dsp: return "Audio Quality & DSP"
            case .downloads: return "Downloads"
            case .updates: return "Your Updates"
            case .community: return "Community"
            case .wrapped: return "Wrapped"
            case .blend: return "Blend"
            case .profileSelection: return "Profiles"
            case .admin: return "Admin"
            case .diagnostics: return "Diagnostics"
            case .dogfood: return "Rust Audio Dogfood"
            case .onboarding: return "Connect & Onboarding"
            }
        }

        var icon: String {
            switch self {
            case .home: return StreamifyIcon.home
            case .search: return StreamifyIcon.search
            case .library: return StreamifyIcon.library
            case .jam: return StreamifyIcon.jam
            case .settings: return StreamifyIcon.settings
            case .nowPlaying: return StreamifyIcon.wave
            case .equalizer: return StreamifyIcon.eq
            case .dsp: return StreamifyIcon.dac
            case .downloads: return StreamifyIcon.cloudDownload
            case .updates: return StreamifyIcon.related
            case .community: return StreamifyIcon.friends
            case .wrapped: return StreamifyIcon.star
            case .blend: return StreamifyIcon.handshake
            case .profileSelection: return StreamifyIcon.avatar
            case .admin: return StreamifyIcon.crown
            case .diagnostics: return StreamifyIcon.terminal
            case .dogfood: return StreamifyIcon.bug
            case .onboarding: return StreamifyIcon.songVideo
            }
        }
    }

    private var splitShell: some View {
        NavigationSplitView {
            // iOS's List selection init takes an OPTIONAL Hashable binding
            // (the non-optional variant is macOS-only) — bridge it; the
            // tap-set value is never nil in practice.
            List(selection: Binding<SidebarSection?>(
                get: { sidebarSection },
                set: { tapped in
                    if let tapped { sidebarSection = tapped }
                }
            )) {
                Section("Listen") {
                    sidebarLabel(.home)
                    sidebarLabel(.search)
                    sidebarLabel(.library)
                    sidebarLabel(.jam)
                    sidebarLabel(.nowPlaying)
                }
                Section("Sound & system") {
                    sidebarLabel(.settings)
                    sidebarLabel(.equalizer)
                    sidebarLabel(.dsp)
                    sidebarLabel(.downloads)
                    sidebarLabel(.updates)
                    sidebarLabel(.diagnostics)
                    sidebarLabel(.dogfood)
                }
                Section("Discover") {
                    sidebarLabel(.community)
                    sidebarLabel(.wrapped)
                    sidebarLabel(.blend)
                    sidebarLabel(.profileSelection)
                    sidebarLabel(.onboarding)
                    sidebarLabel(.admin)
                }
            }
            .navigationSplitViewColumnWidth(
                min: 220, ideal: StreamifyDimens.sidebarRailWidth, max: 320
            )
            .navigationTitle("Streamify")
        } detail: {
            stack($detailPath) { sidebarDetail }
                .onChange(of: sidebarSection) { _, _ in
                    detailPath = NavigationPath()
                }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            miniPlayerDock
        }
    }

    @ViewBuilder
    private func sidebarLabel(_ section: SidebarSection) -> some View {
        Label(section.label, systemImage: section.icon)
            .tag(section)
    }

    @ViewBuilder
    private var sidebarDetail: some View {
        switch sidebarSection {
        case .home:
            homeScreen(path: $detailPath)
                .toolbar { playerToolbar }
        case .search: searchScreen(path: $detailPath)
        case .library: libraryScreen(path: $detailPath)
        case .jam: jamScreen
        case .nowPlaying: nowPlayingScreen
        case .equalizer: EqualizerView()
        case .dsp: DspSettingsView()
        case .downloads:
            downloadsScreen
        case .updates: UpdatesHubView()
        case .community: CommunityHubView()
        case .wrapped: StatsWrappedView()
        case .blend:
            BlendView(
            friendName: "Mira",
            friendSeeds: ["Obsidian Sunrise", "Quartz Bloom"]
        )
        case .profileSelection: ProfileSelectionView()
        case .admin: AdminDashboardView()
        case .diagnostics: DiagnosticsView()
        case .dogfood: DogfoodPanelView()
        case .onboarding: OnboardingView()
        case .settings: settingsScreen(path: $detailPath)
        }
    }

    // MARK: - Shared chrome

    @ToolbarContentBuilder
    private var playerToolbar: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Button {
                fullPlayerPresented = true
            } label: {
                Image(systemName: StreamifyIcon.wave)
            }
            .accessibilityLabel("Open full player")
        }
        ToolbarItem(placement: .primaryAction) {
            Button {
                queuePresented = true
            } label: {
                Image(systemName: StreamifyIcon.queue)
            }
            .accessibilityLabel("Open queue")
        }
    }

    private var miniPlayerDock: some View {
        MiniPlayerBar(
            state: model.state,
            nextTrackTitle: queueModel.upNext.first?.track.title,
            previousTrackTitle: queueModel.played.last?.track.title,
            onPrevious: { model.previous() },
            onToggle: { model.togglePlayPause() },
            onNext: { model.next() },
            onOpenPlayer: { fullPlayerPresented = true }
        )
    }

    private var queueSheet: some View {
        NavigationStack {
            QueueView(
                state: queueListBinding,
                autoMix: queueAutoMixBinding,
                onTrackClick: { index in
                    playQueueRow(at: index)
                },
                onReorder: { offsets, destination in
                    queueModel.move(fromOffsets: offsets, toOffset: destination)
                },
                onDelete: { sectionIndex in
                    queueModel.removeUpNext(at: sectionIndex)
                }
            )
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { queuePresented = false }
                }
            }
        }
    }

    // MARK: - Per-stack wiring

    private func stack(_ path: Binding<NavigationPath>, @ViewBuilder root: () -> some View) -> some View {
        NavigationStack(path: path) {
            root()
                .navigationDestination(for: AppRoute.self) { route in
                    routeDestination(route, path: path)
                }
        }
    }

    @ViewBuilder
    private func routeDestination(_ route: AppRoute, path: Binding<NavigationPath>) -> some View {
        switch route {
        case .nowPlaying:
            nowPlayingScreen

        case .lyrics(let title, let artist):
            RouteScreen { dismiss in
                LyricsView(
                    lyrics: .empty,
                    trackTitle: title,
                    trackArtist: artist,
                    positionMs: Double(model.state.positionForDisplayMs ?? 0),
                    onClose: { dismiss() }
                )
            }

        case .artist(let name):
            RouteScreen { dismiss in
                ArtistDetailView(
                    artistName: name,
                    onPlayTrack: { play(track: $0) },
                    onOpenAlbum: { path.wrappedValue.append(AppRoute.album(title: $0, artist: name)) },
                    onBack: { dismiss() }
                )
            }

        case .album(let title, let artist):
            RouteScreen { dismiss in
                AlbumDetailView(
                    title: title,
                    artist: artist,
                    onPlayTrack: { play(track: $0) },
                    onOpenArtist: { path.wrappedValue.append(AppRoute.artist(name: $0)) },
                    onBack: { dismiss() }
                )
            }

        case .mix(let id):
            AlbumDetailView(
                title: id,
                artist: "Streamify mix",
                kind: "MIX",
                onPlayTrack: { play(track: $0) }
            )

        case .mood:
            // Moods route into the community-hub filter rail (the Android
            // mood → community shelf seam).
            CommunityHubView()

        case .queue:
            queueRoute

        case .equalizer:
            EqualizerView()

        case .dsp:
            DspSettingsView()

        case .downloads:
            downloadsScreen

        case .updatesHub:
            UpdatesHubView()

        case .wrapped:
            StatsWrappedView()

        case .userProfile:
            UserProfileView()

        case .profileSelection:
            ProfileSelectionView()

        case .community:
            CommunityHubView()

        case .blend:
            BlendView(
            friendName: "Mira",
            friendSeeds: ["Obsidian Sunrise", "Quartz Bloom"]
        )

        case .jam:
            jamScreen

        case .adminDashboard:
            AdminDashboardView()

        case .diagnostics:
            DiagnosticsView()

        case .dogfood:
            DogfoodPanelView()

        case .onboarding:
            OnboardingView()
        }
    }

    /// The pushed-queue variant (dismisses via the environment pop).
    private var queueRoute: some View {
        QueueView(
            state: queueListBinding,
            autoMix: queueAutoMixBinding,
            onTrackClick: { index in
                playQueueRow(at: index)
            },
            onReorder: { offsets, destination in
                queueModel.move(fromOffsets: offsets, toOffset: destination)
            },
            onDelete: { sectionIndex in
                queueModel.removeUpNext(at: sectionIndex)
            }
        )
    }

    // MARK: - Screen builders (universal — the same code serves both idioms)

    /// Home (SW-ENG2-03). `path` is whichever stack is hosting the screen,
    /// so pushes land in the right stack.
    private func homeScreen(path: Binding<NavigationPath>) -> some View {
        HomeView(
            onOpenTrack: { play(track: $0) },
            onOpenAlbum: { path.wrappedValue.append(AppRoute.album(title: $0, artist: "")) },
            onOpenArtist: { path.wrappedValue.append(AppRoute.artist(name: $0)) },
            onOpenMood: { _ in
                path.wrappedValue.append(AppRoute.mood(name: "Community"))
            },
            onOpenDaylist: {
                path.wrappedValue.append(AppRoute.mix(id: "Daylist"))
            },
            onOpenMix: { path.wrappedValue.append(AppRoute.mix(id: $0)) },
            onJoinJam: { _ in
                path.wrappedValue.append(AppRoute.jam)
            },
            onOpenProfile: { _ in
                path.wrappedValue.append(AppRoute.userProfile)
            }
        )
    }

    /// Search (SW-ENG2-04 / WS-B B2): the 300 ms-debounced SearchViewModel
    /// inside SearchView drives live `searchTracks` over the FFI lane; the
    /// shell only routes result taps (play / push destinations).
    private func searchScreen(path: Binding<NavigationPath>) -> some View {
        SearchView(
            onPlayTrack: { row in
                play(track: row.asPresentationTrack)
            },
            onOpenResult: { result in
                switch result.type {
                case .artist:
                    path.wrappedValue.append(AppRoute.artist(name: result.title))
                case .album, .playlist:
                    path.wrappedValue.append(
                        AppRoute.album(title: result.title, artist: result.uploader)
                    )
                case .song, .video:
                    play(track: result.asPresentationTrack)
                }
            }
        )
    }

    /// Library (SW-ENG2-07): segmented picker + grid/list switcher.
    private func libraryScreen(path: Binding<NavigationPath>) -> some View {
        LibraryView(
            onPlayTrack: { play(track: $0) },
            onOpenPlaylist: { path.wrappedValue.append(AppRoute.album(title: $0, artist: "Community")) },
            onOpenAlbum: { path.wrappedValue.append(AppRoute.album(title: $0, artist: "")) },
            onOpenArtist: { path.wrappedValue.append(AppRoute.artist(name: $0)) },
            onCreatePlaylist: {},
            onSortTap: {},
            onFilterTap: {}
        )
    }

    /// Jam Sessions (the group-listening room). Engineer 4's VM-wired screen
    /// (merged from main) owns itself: the JamViewModel drives pairing, the
    /// roster, the queue voting and playback through the command plane.
    private var jamScreen: some View {
        JamSessionView()
    }

    /// Settings hub (SW-ENG2-08).
    private func settingsScreen(path: Binding<NavigationPath>) -> some View {
        SettingsView(
            onModeChange: { _ in },
            onOpenAccounts: { path.wrappedValue.append(AppRoute.profileSelection) },
            onOpenDsp: { path.wrappedValue.append(AppRoute.dsp) },
            onOpenEqualizer: { path.wrappedValue.append(AppRoute.equalizer) },
            onOpenDownloads: { path.wrappedValue.append(AppRoute.downloads) },
            onOpenUpdates: { path.wrappedValue.append(AppRoute.updatesHub) },
            onOpenDiagnostics: { path.wrappedValue.append(AppRoute.diagnostics) }
        )
    }

    /// The compact now-playing surface.
    private var nowPlayingScreen: some View {
        NowPlayingView(
            state: model.state,
            onPrevious: { model.previous() },
            onToggle: { model.togglePlayPause() },
            onNext: { model.next() },
            onSeek: { model.seek(to: $0) },
            onVolumeChange: { model.setVolume($0) },
            onOpenQueue: { queuePresented = true },
            onOpenFullPlayer: { fullPlayerPresented = true }
        )
    }

    /// Downloads (WS-B B7): offline rows play through the SAME engine
    /// path — the `file://` URL short-circuits resolve (airplane-mode
    /// safe), the CoreAudio plane is already mounted by the transport.
    private var downloadsScreen: some View {
        DownloadsView { track, playableUrl in
            model.play(track: track, resolvedStreamUrl: playableUrl)
        }
    }

    // MARK: - Intent lowering

    /// Plays the engine-queue row at `index` (a queue-list tap): the row is
    /// already staged — the engine selects it and emits the intent itself
    /// (no re-resolve, no duplicate row).
    private func playQueueRow(at index: Int) {
        guard queueModel.tracks.indices.contains(index) else { return }
        model.playStaged(trackId: queueModel.tracks[index].id)
    }

    /// WS-B B3: a tapped track routes straight into the engine's play path
    /// (resolve → stage → attach → play intent, with the CoreAudio plane
    /// mounted by the transport holder) — the pushed snapshot repaints.
    private func play(track: PresentationTrack) {
        model.play(track: track)
    }

    /// The fixture-currency overload (VirtualShelfTrack converts onto the
    /// shared presentation model — the engine's currency).
    private func play(track: VirtualShelfTrack) {
        play(track: track.presentationTrack)
    }
}

// MARK: - Route dismiss adapter

private struct RouteScreen<Content: View>: View {
    @Environment(\.dismiss) private var dismissAction

    private let build: (DismissAction) -> Content

    init(_ build: @escaping (DismissAction) -> Content) {
        self.build = build
    }

    var body: some View {
        build(dismissAction)
    }
}
