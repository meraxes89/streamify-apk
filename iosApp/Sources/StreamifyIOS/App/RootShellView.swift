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
// intent back through the command plane.
//
// WS-B B2 (ENG-2): the 9 orphaned ViewModels are now LIVE — the shell owns
// them and lowers their observable state onto the screens' props; the play
// QUEUE is the ENGINE's staged queue, mirrored by QueueViewModel over the
// library's push lane (read-after-action snapshots — never a poll). Zero
// PreviewCatalog references survive in production paths (the B2 lint gate).
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

    // ── WS-B B2: the 9 orphaned ViewModels, now owned by the shell ───────
    // (Engineer 4's VMs speak to AppServices' selected lane; previews/tests
    // keep injecting explicitly).
    @State private var homeModel = HomeViewModel()
    @State private var searchModel = SearchViewModel()
    @State private var queueModel = QueueViewModel()
    @State private var communityModel = CommunityViewModel()
    @State private var statsModel = StatsWrappedViewModel()
    @State private var updatesModel = UpdatesHubViewModel()
    @State private var profileModel = UserProfileViewModel()
    @State private var ingestionModel = IngestionViewModel()
    @State private var settingsModel = SettingsViewModel()
    // WS-B B7: the downloads transfer manager (BackgroundDownloadService).
    @State private var downloadsModel = DownloadsViewModel()

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
            // WS-B B2: the push collectors + one-shot loads.
            queueModel.start()
            homeModel.loadIfNeeded()
            ingestionModel.start()
            updatesModel.loadIfNeeded()
            communityModel.loadIfNeeded()
            statsModel.loadIfNeeded()
            profileModel.load(userId: nil)
            // WS-B B7: cold-launch resume-data recovery.
            downloadsModel.recoverPersistedTransfers()
            installSharedCarPlaySurfaces()
        }
        .fullPlayerSheet(isPresented: $fullPlayerPresented) {
            FullPlayerView(
                state: model.state,
                queueState: Binding(
                    get: { engineQueueListState },
                    set: { _ in /* mutations route through the model below */ }
                ),
                queueAutoMix: Binding(
                    get: { queueModel.autoMixEnabled },
                    set: { enabled in
                        if enabled != queueModel.autoMixEnabled {
                            queueModel.toggleAutoMix()
                        }
                    }
                ),
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
                    let fullIndex = sectionIndex + max(queueModel.currentIndex + 1, 0)
                    queueModel.remove(atOffsets: IndexSet([fullIndex]))
                }
            )
        }
        .sheet(isPresented: $queuePresented) {
            queueSheet
        }
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
        case .downloads: routeDestination(.downloads, path: $detailPath)
        case .updates: routeDestination(.updatesHub, path: $detailPath)
        case .community: routeDestination(.community, path: $detailPath)
        case .wrapped: routeDestination(.wrapped, path: $detailPath)
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
                state: Binding(
                    get: { engineQueueListState },
                    set: { _ in /* mutations route through the model below */ }
                ),
                autoMix: Binding(
                    get: { queueModel.autoMixEnabled },
                    set: { enabled in
                        if enabled != queueModel.autoMixEnabled {
                            queueModel.toggleAutoMix()
                        }
                    }
                ),
                onTrackClick: { index in
                    play(atQueueIndex: index)
                },
                onReorder: { offsets, destination in
                    queueModel.move(fromOffsets: offsets, toOffset: destination)
                },
                onDelete: { sectionIndex in
                    let fullIndex = sectionIndex + max(queueModel.currentIndex + 1, 0)
                    queueModel.remove(atOffsets: IndexSet([fullIndex]))
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
                    // The live lane fetches through the library when a
                    // lyrics surface crosses the FFI contract; until then
                    // the honest empty sheet renders (no fixture lyrics in
                    // production — the B2 lint gate).
                    lyrics: LyricsData.empty,
                    trackTitle: title,
                    trackArtist: artist,
                    positionMs: Double(model.state.positionForDisplayMs ?? 0),
                    onClose: { dismiss() }
                )
            }

        case .artist(let name):
            // The live catalog is the search lane's current page filtered
            // by artist (no fixture fallback — an empty page is the honest
            // state on a fresh shell).
            let catalog = searchModel.currentResults
                .filter { $0.uploader == name }
                .map { row in
                    VirtualShelfTrack(
                        id: row.id,
                        title: row.title,
                        artist: row.uploader,
                        artworkUrl: row.artworkUrl,
                        durationSec: (row.durationMs ?? 0) / 1000,
                        platformOrigin: "ytm"
                    )
                }
            RouteScreen { dismiss in
                ArtistDetailView(
                    artistName: name,
                    tracks: catalog,
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
            // WS-B B7: the live transfer manager over BackgroundDownloadService.
            DownloadsView(
                transfers: downloadsModel.transfers,
                onRetry: { id in
                    if let url = URL(string: id) { downloadsModel.resume(url: url) }
                },
                onPauseResume: { id in
                    guard let url = URL(string: id),
                          let transfer = downloadsModel.transfers.first(where: { $0.id == id })
                    else { return }
                    if case .downloading = transfer.state {
                        downloadsModel.pause(url: url)
                    } else {
                        downloadsModel.resume(url: url)
                    }
                },
                onRemove: { id in
                    if let url = URL(string: id) { downloadsModel.remove(url: url) }
                }
            )

        case .updatesHub:
            // WS-B B2: the live release-radar lane (library.releaseRadar()).
            UpdatesHubView(
                updates: Self.updateEntries(from: updatesModel),
                onPreSave: { releaseId in
                    if let candidate = updatesModel.feed.first(where: { $0.releaseId == releaseId }) {
                        updatesModel.togglePreSave(candidate)
                    }
                }
            )

        case .wrapped:
            // WS-B B2: the live stats lane (library.wrappedStats()).
            StatsWrappedView(
                wrappedStats: Self.wrappedStats(from: statsModel),
                genreStats: Self.genreStats(from: statsModel),
                topTracks: statsModel.topTracks.map(Self.shelfTrack),
                onShare: {},
                onPlayTrack: { play(track: $0) }
            )

        case .userProfile:
            // WS-B B2: the live profile lane (library.userProfile()). The
            // summary contract carries no plays/top-track yet — the lossy
            // bridge renders those as unknown until ENG-1 grows the record.
            UserProfileView(
                profile: Self.userProfileEntry(from: profileModel),
                onOpenWrapped: {},
                onPlayTopTrack: {}
            )

        case .profileSelection:
            ProfileSelectionView()

        case .community:
            // WS-B B2: the live community feed lane (library.communityFeed()).
            CommunityHubView(
                playlists: Self.communityRows(from: communityModel),
                onOpen: { playlist in
                    path.wrappedValue.append(
                        AppRoute.album(title: playlist.name, artist: playlist.creatorName)
                    )
                }
            )

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
            // WS-B B2: the ingestion lane's entry point — connect-platform
            // taps enqueue external playlist imports (Spotify / YTM); the
            // library degrades honestly until the FFI contract grows it.
            OnboardingView(
                onConnect: { platform in
                    Task { await ingestionModel.importPlaylist(named: platform, source: platform) }
                }
            )
        }
    }

    /// The pushed-queue variant (dismisses via the environment pop).
    private var queueRoute: some View {
        QueueView(
            state: Binding(
                get: { engineQueueListState },
                set: { _ in /* mutations route through the model below */ }
            ),
            autoMix: Binding(
                get: { queueModel.autoMixEnabled },
                set: { enabled in
                    if enabled != queueModel.autoMixEnabled {
                        queueModel.toggleAutoMix()
                    }
                }
            ),
            onReorder: { offsets, destination in
                queueModel.move(fromOffsets: offsets, toOffset: destination)
            },
            onDelete: { sectionIndex in
                let fullIndex = sectionIndex + max(queueModel.currentIndex + 1, 0)
                queueModel.remove(atOffsets: IndexSet([fullIndex]))
            }
        )
    }

    // MARK: - Screen builders (universal — the same code serves both idioms)

    /// Home (SW-ENG2-03). `path` is whichever stack is hosting the screen,
    /// so pushes land in the right stack.
    private func homeScreen(path: Binding<NavigationPath>) -> some View {
        HomeView(
            bannerMessage: nil,
            initialShelves: Self.shelves(from: homeModel),
            daylist: nil,  // the daylist engine lane lands with the FFI contract
            moods: MoodGenre.standard,
            dailyMixes: Self.dailyMixShelves(from: homeModel),
            friends: Self.friendActivity(from: communityModel),
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
            },
            onLoadMore: {}
        )
    }

    /// Search (SW-ENG2-04 + WS-B B2): native .searchable + debounced instant
    /// queries; results are the LIVE resolver lane — the view's 300 ms input
    /// settle feeds SearchViewModel.submit, whose own 300 ms debounce lowers
    /// the query onto searchTracks over the live FFI (the B3 mapping).
    private func searchScreen(path: Binding<NavigationPath>) -> some View {
        SearchView(
            recentSearches: searchModel.searchHistory.map {
                RecentSearchEntry(query: $0, timestampMs: 0)
            },
            localResults: [],
            onlineResults: searchModel.currentResults.map(Self.onlineResult),
            isSearching: searchModel.isLoading,
            onSearch: { query in
                searchModel.submit(query: query)
            },
            onSubmit: { query in
                searchModel.submit(query: query)
            },
            onPlayTrack: { play(track: $0) },
            onOpenResult: { result in
                switch result.type {
                case .artist:
                    path.wrappedValue.append(AppRoute.artist(name: result.title))
                case .album, .playlist:
                    path.wrappedValue.append(
                        AppRoute.album(title: result.title, artist: result.uploader)
                    )
                case .song, .video:
                    if let row = searchModel.currentResults.first(
                        where: { $0.title == result.title && $0.uploader == result.uploader }
                    ) {
                        play(track: VirtualShelfTrack(
                            id: row.id,
                            title: row.title,
                            artist: row.uploader,
                            artworkUrl: row.artworkUrl,
                            durationSec: (row.durationMs ?? 0) / 1000,
                            platformOrigin: "ytm"
                        ))
                    }
                }
            },
            onRemoveRecent: { query in
                searchModel.removeSearch(query: query)
            },
            onClearRecents: {
                searchModel.clearHistory()
            }
        )
    }

    /// Library (SW-ENG2-07): segmented picker + grid/list switcher.
    private func libraryScreen(path: Binding<NavigationPath>) -> some View {
        LibraryView(
            likedTracks: [],
            transfers: downloadsModel.transfers,
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

    /// Settings hub (SW-ENG2-08). The mode chip is UI furniture (no engine
    /// surface); every navigation route stays explicit.
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

    // MARK: - Intent lowering (WS-B B3: the engine play transaction)

    /// Plays a tapped track through the LIVE lane: the transport VM presents
    /// the track optimistically (lockscreen/NowPlaying/CarPlay metadata —
    /// the engine's UDL snapshot carries no track row), then the queue VM
    /// lowers the play onto the library (resolve -> stage -> play_track on
    /// the real lane; the preview double's scripted flow otherwise). The
    /// engine's next push repaints the transport.
    private func play(track: VirtualShelfTrack) {
        model.present(track: track.presentationTrack)
        Task { await queueModel.play(track: track.presentationTrack) }
    }

    /// Queue-row tap: same transaction, addressed by engine queue index.
    private func play(atQueueIndex index: Int) {
        guard queueModel.tracks.indices.contains(index) else { return }
        let track = queueModel.tracks[index]
        model.present(track: track)
        Task { await queueModel.play(track: track) }
    }

    /// The engine queue lowered onto the QueueView/FullPlayerView currency.
    private var engineQueueListState: QueueListState {
        QueueListState(
            tracks: queueModel.tracks,
            currentIndex: queueModel.currentIndex,
            isPlaying: queueModel.isPlaying
        )
    }

    // MARK: - Shared platform surfaces (WS-B B6)

    /// Installs the CarPlay router + live queue content provider on the
    /// scene delegate's shared slots (the delegate is system-instantiated
    /// from the host's scene manifest — see CarPaySceneDelegate's wiring
    /// note).
    private func installSharedCarPlaySurfaces() {
        #if canImport(CarPlay) && canImport(UIKit)
        CarPlaySceneDelegate.sharedRouter = model
        CarPlaySceneDelegate.sharedContentProvider = QueueCarPlayContentProvider(
            queueModel: queueModel
        )
        #endif
    }

    // MARK: - Live-lane lowering helpers (presentation currency)

    /// The engine-track currency onto the home shelf vocabulary.
    private static func shelfTrack(_ track: PresentationTrack) -> VirtualShelfTrack {
        VirtualShelfTrack(
            id: track.id,
            title: track.title,
            artist: track.artist,
            artworkUrl: track.artworkUrl,
            durationSec: (track.durationMs ?? 0) / 1000,
            platformOrigin: "ytm"
        )
    }

    private static func shelves(from model: HomeViewModel) -> [VirtualShelf] {
        var shelves: [VirtualShelf] = []
        if !model.sessionRecommendations.isEmpty {
            shelves.append(VirtualShelf(
                id: "quick-picks", title: "Quick picks",
                subtitle: "Restart something you loved", kind: .quickPicks,
                tracks: model.sessionRecommendations.map(shelfTrack)
            ))
        }
        if !model.circadianRecommendations.isEmpty {
            let slot = model.circadianSlot
            shelves.append(VirtualShelf(
                id: "circadian", title: slot.title,
                subtitle: nil, kind: .listenAgain,
                tracks: model.circadianRecommendations.map(shelfTrack)
            ))
        }
        if !model.trending.isEmpty {
            shelves.append(VirtualShelf(
                id: "trending", title: "Trending",
                subtitle: nil, kind: .trending,
                tracks: model.trending.map(shelfTrack)
            ))
        }
        if !model.topPlayed.isEmpty {
            shelves.append(VirtualShelf(
                id: "top-played", title: "Top played",
                subtitle: nil, kind: .supermix,
                tracks: model.topPlayed.map(shelfTrack)
            ))
        }
        return shelves
    }

    private static func dailyMixShelves(from model: HomeViewModel) -> [VirtualShelf] {
        model.dailyMixes.map { mix in
            VirtualShelf(
                id: "mix-\(mix.number)",
                title: "Mix \(mix.number)",
                subtitle: mix.seedArtist,
                kind: .dailyMix,
                tracks: mix.tracks.map(shelfTrack)
            )
        }
    }

    private static func friendActivity(from model: CommunityViewModel) -> [FriendActivityEntry] {
        model.friendsActivity.map { friend in
            FriendActivityEntry(
                userId: friend.userId,
                displayName: friend.displayName,
                avatarUrl: friend.avatarUrl,
                trackTitle: friend.trackTitle,
                trackArtist: friend.trackArtist,
                coverUrl: friend.coverUrl,
                lastActiveLabel: friend.lastActiveAt,
                sessionCode: friend.sessionCode
            )
        } ?? []
    }

    /// ReleaseCandidate (the radar lane) -> UpdateEntry (the view's row).
    private static func updateEntries(from model: UpdatesHubViewModel) -> [UpdateEntry] {
        model.feed.map { candidate in
            UpdateEntry(
                id: candidate.releaseId,
                artist: candidate.artistName,
                title: candidate.title,
                version: candidate.releaseType,
                notes: [],
                releaseDate: candidate.expectedAt,
                isPreSaved: model.savedReleaseIds.contains(candidate.releaseId)
            )
        }
    }

    /// WrappedStats (the stats lane) -> the Wrapped card rows.
    private static func wrappedStats(from model: StatsWrappedViewModel) -> [WrappedStat] {
        [
            WrappedStat(id: "minutes", label: "Minutes listened", value: model.totalMinutes.formatted(), symbol: "clock"),
            WrappedStat(id: "tracks", label: "Tracks played", value: model.totalTracks.formatted(), symbol: "music.note"),
            WrappedStat(id: "liked", label: "Liked songs", value: model.likedSongs.formatted(), symbol: "heart"),
            WrappedStat(id: "bpm", label: "Average BPM", value: model.averageBpm.formatted(), symbol: "waveform"),
        ]
    }

    /// StatsGenre (fraction shares) -> GenreStat rows (percentage points —
    /// the view's bar currency; a projection, not a fabricated count).
    private static func genreStats(from model: StatsWrappedViewModel) -> [GenreStat] {
        model.topGenres.map { genre in
            GenreStat(
                genre: genre.name,
                count: Int((genre.fraction * 100).rounded()),
                gradient: StreamifyGradients.pop
            )
        }
    }

    /// CommunityPlaylist (the feed lane) -> CommunityPlaylistModel rows.
    private static func communityRows(from model: CommunityViewModel) -> [CommunityPlaylistModel] {
        model.playlists.map { playlist in
            CommunityPlaylistModel(
                id: playlist.id,
                creatorName: playlist.creatorName,
                name: playlist.name,
                description: playlist.description,
                coverUrl: playlist.coverUrl,
                isCollaborative: false,  // not on the presentation contract yet
                likesCount: playlist.likesCount,
                trackCount: playlist.trackCount
            )
        }
    }

    /// UserProfileSummary (the profile lane) -> UserProfileEntry. Lossy by
    /// design: plays / top-track are not on the summary contract yet.
    private static func userProfileEntry(from model: UserProfileViewModel) -> UserProfileEntry {
        let summary = model.profile
        return UserProfileEntry(
            id: summary.userId,
            displayName: summary.displayName,
            bio: summary.bio,
            totalPlays: 0,  // not on the summary contract — unknown, rendered as such
            listeningMinutes: summary.listeningMinutes,
            favoriteGenre: summary.favoriteGenre,
            topTrack: "—",   // not on the summary contract — unknown, rendered as such
            isPrivate: summary.isPrivate
        )
    }

    private static func onlineResult(from row: SearchRow) -> OnlineSearchResult {
        OnlineSearchResult(
            title: row.title,
            uploader: row.uploader,
            url: "https://music.youtube.com/watch?v=\(row.id)",
            durationSec: (row.durationMs ?? 0) / 1000,
            thumbnailUrl: row.artworkUrl,
            type: .song,
            isVerified: row.isVerified
        )
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
