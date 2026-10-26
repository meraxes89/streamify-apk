# Streamify — Codebase Guide

> The complete folder-by-folder, file-by-file map of the app module,
> generated from the live tree. Folder purposes are authored; file
> lists are real. See each folder's own `README.md` for depth, and
> [ARCHITECTURE.md](ARCHITECTURE.md) for the system view.

## Contents

| # | Package | Files | Purpose |
|---|---|---|---|
| 1 | `com.streamify.app.data` | 1 | Data layer — repositories, persistence, discovery, providers, network engines, the JNI ... |
| 2 | `com.streamify.app.data.discovery` | 3 | Identity matching, dedup, candidate ranking (fuzzy gates). |
| 3 | `com.streamify.app.data.ingestion` | 3 | Import pipelines: feed bootstrap, Exportify, neuro queues. |
| 4 | `com.streamify.app.data.lyrics` | 1 | SLYR/LRC binary lyric cache manager. |
| 5 | `com.streamify.app.data.models` | 5 | Immutable value models shared by every layer (ProGuard-pinned). |
| 6 | `com.streamify.app.data.native` | 1 | Kotlin surface for native (Rust/Lofty) metadata tagging. |
| 7 | `com.streamify.app.data.network` | 19 | Stream resolution engine + search/AI providers + circuit breaking. |
| 8 | `com.streamify.app.data.persistence` | 5 | SQLite lifecycle, storage accounting, backup, reset, offline vault. |
| 9 | `com.streamify.app.data.repository` | 4 | Single source of truth for track/playlist state. |
| 10 | `com.streamify.app.data.spotify` | 3 | Spotify PKCE OAuth + library extraction. |
| 11 | `com.streamify.app.data.supabase` | 11 | Supabase backend clients (auth, jam, stats, sync, realtime, community, mesh). |
| 12 | `com.streamify.app.data.telemetry` | 1 | Listening stats / Wrapped analytics engine. |
| 13 | `com.streamify.app.data.update` | 1 | Self-update checker. |
| 14 | `com.streamify.app.data.youtube` | 3 | YTM session, batch resolve, playlist scraping. |
| 15 | `com.streamify.app.di` | 2 | AppGraph (hand-rolled DI) + DispatcherProvider. |
| 16 | `com.streamify.app.jam` | 4 | Distributed jam sessions: lockstep engine, CRDT ordering, readiness FSM, codec. |
| 17 | `com.streamify.app.media.audio` | 6 | DSP chain, spatial, equalizer, audio devices. |
| 18 | `com.streamify.app.media.cache` | 5 | Audio byte cache, pre-buffering, eviction, remux. |
| 19 | `com.streamify.app.media.ingestion` | 4 | Background WorkManager jobs (ingest, sync, compute, embeddings). |
| 20 | `com.streamify.app.media.lyrics` | 2 | Lyric playback controllers + offset store. |
| 21 | `com.streamify.app.media.playback` | 4 | Foreground MediaSessionService + queue authority. |
| 22 | `com.streamify.app.media.sync` | 4 | PTP clock sync, PLL, scheduling, thermal governor. |
| 23 | `com.streamify.app.navigation` | 1 | The Compose navigation graph. |
| 24 | `com.streamify.app.radio` | 3 | Radio queue construction (Online, Continuum, candidate broker). |
| 25 | `com.streamify.app.ui.animations` | 3 | Reusable motion effects. |
| 26 | `com.streamify.app.ui.components` | 28 | Shared component library. |
| 27 | `com.streamify.app.ui.components.yt` | 34 | YouTube-Music-styled widget kit (34 widgets). |
| 28 | `com.streamify.app.ui.lyrics` | 1 | Lyrics rendering engine. |
| 29 | `com.streamify.app.ui.models` | 1 | UI presentation models (ProGuard-pinned). |
| 30 | `com.streamify.app.ui.screens` | 23 | One file per destination (23 screens). |
| 31 | `com.streamify.app.ui.theme` | 6 | Design tokens: color, type, shape, dimens, screen classes. |
| 32 | `com.streamify.app.util` | 10 | Cross-cutting utilities + the newpipe PO-token pipeline. |
| 33 | `com.streamify.app.util.newpipe` | 5 | NewPipe-Extractor bootstrap, BotGuard WebView, PO-token generation. |
| 34 | `com.streamify.app.viewmodel` | 13 | Presentation state (PlayerViewModel + companions + feature VMs). |
| 35 | `com.streamify.app.worker` | 1 | Foreground download worker. |

---

## `com.streamify.app.data/`

Data layer — repositories, persistence, discovery, providers, network engines, the JNI bridge.

| File | Lines |
|---|---|
| `NativeBridge.kt` | 1197 |

## `com.streamify.app.data.discovery/`

Identity matching, dedup, candidate ranking (fuzzy gates).

| File | Lines |
|---|---|
| `AntiDriftScoringEngine.kt` | 209 |
| `FuzzyTitleMatcher.kt` | 236 |
| `ReRanker.kt` | 264 |

## `com.streamify.app.data.ingestion/`

Import pipelines: feed bootstrap, Exportify, neuro queues.

| File | Lines |
|---|---|
| `ExportifyParser.kt` | 376 |
| `FeedBootstrapManager.kt` | 101 |
| `NeuroQueueManager.kt` | 270 |

## `com.streamify.app.data.lyrics/`

SLYR/LRC binary lyric cache manager.

| File | Lines |
|---|---|
| `LyricsCacheManager.kt` | 411 |

## `com.streamify.app.data.models/`

Immutable value models shared by every layer (ProGuard-pinned).

| File | Lines |
|---|---|
| `AppMode.kt` | 57 |
| `LyricsData.kt` | 211 |
| `OrchestratorStatus.kt` | 32 |
| `Recommendation.kt` | 9 |
| `Track.kt` | 56 |

## `com.streamify.app.data.native/`

Kotlin surface for native (Rust/Lofty) metadata tagging.

| File | Lines |
|---|---|
| `NativeMetadataTagger.kt` | 66 |

## `com.streamify.app.data.network/`

Stream resolution engine + search/AI providers + circuit breaking.

| File | Lines |
|---|---|
| `AntiJarringTransitionEngine.kt` | 70 |
| `ArtworkResolutionPipeline.kt` | 34 |
| `CandidateAggregator.kt` | 245 |
| `CanonicalSeedResolver.kt` | 77 |
| `HybridGraphFetcher.kt` | 285 |
| `LyricsResolver.kt` | 794 |
| `MeshDiscoveryEngine.kt` | 282 |
| `NegativeResultCache.kt` | 31 |
| `NetworkEngine.kt` | 124 |
| `ParallelStreamDownloader.kt` | 210 |
| `PersonaEngine.kt` | 94 |
| `ResilientMediaRouter.kt` | 46 |
| `SemanticSearchEngine.kt` | 86 |
| `SmartAcousticEngine.kt` | 83 |
| `StreamifyCircuitBreaker.kt` | 25 |
| `YouTubeMusicSearchApi.kt` | 574 |
| `YouTubeStreamResolver.kt` | 1082 |
| `ZhipuAiEngine.kt` | 101 |
| `iTunesSearchApi.kt` | 130 |

## `com.streamify.app.data.persistence/`

SQLite lifecycle, storage accounting, backup, reset, offline vault.

| File | Lines |
|---|---|
| `BackupManager.kt` | 144 |
| `DatabaseInitializer.kt` | 24 |
| `NuclearResetManager.kt` | 126 |
| `SmartOfflineVaultEngine.kt` | 193 |
| `StorageManager.kt` | 70 |

## `com.streamify.app.data.repository/`

Single source of truth for track/playlist state.

| File | Lines |
|---|---|
| `EdgeMeshRepository.kt` | 375 |
| `PlaylistRepository.kt` | 484 |
| `TrackRepository.kt` | 544 |
| `TrackRepositoryApi.kt` | 58 |

## `com.streamify.app.data.spotify/`

Spotify PKCE OAuth + library extraction.

| File | Lines |
|---|---|
| `SpotifyAuthManager.kt` | 305 |
| `SpotifyConfig.kt` | 8 |
| `SpotifySessionExtractor.kt` | 149 |

## `com.streamify.app.data.supabase/`

Supabase backend clients (auth, jam, stats, sync, realtime, community, mesh).

| File | Lines |
|---|---|
| `AuthManager.kt` | 117 |
| `SupabaseAdminClient.kt` | 268 |
| `SupabaseClient.kt` | 517 |
| `SupabaseCommunityClient.kt` | 206 |
| `SupabaseEdgeMeshClient.kt` | 248 |
| `SupabaseJamClient.kt` | 539 |
| `SupabaseModels.kt` | 205 |
| `SupabasePlaylistSyncClient.kt` | 123 |
| `SupabaseRealtimeClient.kt` | 361 |
| `SupabaseStatsClient.kt` | 164 |
| `SupabaseTracksClient.kt` | 218 |

## `com.streamify.app.data.telemetry/`

Listening stats / Wrapped analytics engine.

| File | Lines |
|---|---|
| `YtStatsTelemetryEngine.kt` | 740 |

## `com.streamify.app.data.update/`

Self-update checker.

| File | Lines |
|---|---|
| `StreamifyUpdateManager.kt` | 321 |

## `com.streamify.app.data.youtube/`

YTM session, batch resolve, playlist scraping.

| File | Lines |
|---|---|
| `BatchTrackResolver.kt` | 169 |
| `PlaylistLinkScraper.kt` | 638 |
| `YtSessionExtractor.kt` | 131 |

## `com.streamify.app.di/`

AppGraph (hand-rolled DI) + DispatcherProvider.

| File | Lines |
|---|---|
| `AppGraph.kt` | 166 |
| `DispatcherProvider.kt` | 29 |

## `com.streamify.app.jam/`

Distributed jam sessions: lockstep engine, CRDT ordering, readiness FSM, codec.

| File | Lines |
|---|---|
| `FractionalIndexEngine.kt` | 81 |
| `JamEngine.kt` | 1242 |
| `JamTrackCodec.kt` | 65 |
| `PlaybackReadyGate.kt` | 80 |

## `com.streamify.app.media.audio/`

DSP chain, spatial, equalizer, audio devices.

| File | Lines |
|---|---|
| `AudioDeviceManager.kt` | 207 |
| `CrossfadeAudioProcessor.kt` | 123 |
| `DolbySpatialManager.kt` | 90 |
| `EqualizerManager.kt` | 231 |
| `StreamifyAudioProcessor.kt` | 343 |
| `SyncAudioProcessor.kt` | 126 |

## `com.streamify.app.media.cache/`

Audio byte cache, pre-buffering, eviction, remux.

| File | Lines |
|---|---|
| `AudioCacheManager.kt` | 36 |
| `ElasticStorageAllocator.kt` | 27 |
| `LosslessRemuxer.kt` | 52 |
| `PredictivePreBufferManager.kt` | 126 |
| `PriorityWeightedEvictor.kt` | 74 |

## `com.streamify.app.media.ingestion/`

Background WorkManager jobs (ingest, sync, compute, embeddings).

| File | Lines |
|---|---|
| `IngestionWorker.kt` | 113 |
| `LibrarySyncWorker.kt` | 52 |
| `TextEmbeddingEngine.kt` | 89 |
| `TitanComputeWorker.kt` | 140 |

## `com.streamify.app.media.lyrics/`

Lyric playback controllers + offset store.

| File | Lines |
|---|---|
| `LyricOffsetStore.kt` | 37 |
| `LyricPlaybackController.kt` | 110 |

## `com.streamify.app.media.playback/`

Foreground MediaSessionService + queue authority.

| File | Lines |
|---|---|
| `DynamicQueueManager.kt` | 106 |
| `OnlineTrackProcessor.kt` | 191 |
| `PlaybackService.kt` | 482 |
| `QueueEngine.kt` | 174 |

## `com.streamify.app.media.sync/`

PTP clock sync, PLL, scheduling, thermal governor.

| File | Lines |
|---|---|
| `PhaseLockedLoopController.kt` | 112 |
| `PrecisionTimeProtocol.kt` | 123 |
| `ScheduledAudioScheduler.kt` | 84 |
| `ThermalGovernorManager.kt` | 62 |

## `com.streamify.app.navigation/`

The Compose navigation graph.

| File | Lines |
|---|---|
| `AppNavGraph.kt` | 304 |

## `com.streamify.app.radio/`

Radio queue construction (Online, Continuum, candidate broker).

| File | Lines |
|---|---|
| `ContinuumRadioEngine.kt` | 429 |
| `OnlineRadioEngine.kt` | 315 |
| `UniversalCandidateBroker.kt` | 91 |

## `com.streamify.app.ui.animations/`

Reusable motion effects.

| File | Lines |
|---|---|
| `CardPressEffect.kt` | 48 |
| `HeartBurstEffect.kt` | 25 |
| `PlayerTransition.kt` | 11 |

## `com.streamify.app.ui.components/`

Shared component library.

| File | Lines |
|---|---|
| `ArtistCircleCard.kt` | 50 |
| `BottomNavBar.kt` | 38 |
| `BroadcastBanner.kt` | 79 |
| `CategoryCard.kt` | 59 |
| `CommentsSheet.kt` | 260 |
| `ConnectAccountsSheet.kt` | 261 |
| `ContextMenuSheet.kt` | 538 |
| `DynamicMeshBackground.kt` | 80 |
| `EmptyStateView.kt` | 64 |
| `FluidSyllableLine.kt` | 83 |
| `FluidSyllableText.kt` | 112 |
| `FriendActivityCard.kt` | 109 |
| `HeartButton.kt` | 133 |
| `LyricsCanvas.kt` | 102 |
| `LyricsEditorDialog.kt` | 112 |
| `MiniPlayerBar.kt` | 314 |
| `NowPlayingIndicator.kt` | 115 |
| `PlayerControls.kt` | 176 |
| `QuantumSonicTokenController.kt` | 435 |
| `QuantumSonicTokenOverlay.kt` | 201 |
| `RelatedDiscoverSheet.kt` | 299 |
| `SireenBrandingBadge.kt` | 129 |
| `SpotifyLoginDialog.kt` | 148 |
| `StreamifyPullToRefreshContainer.kt` | 200 |
| `TrackCard.kt` | 52 |
| `TrackCoverArt.kt` | 142 |
| `TrackListItem.kt` | 229 |
| `UpdateAvailableCard.kt` | 162 |

## `com.streamify.app.ui.components.yt/`

YouTube-Music-styled widget kit (34 widgets).

| File | Lines |
|---|---|
| `YtActiveEqualizer.kt` | 107 |
| `YtBottomNavBar.kt` | 197 |
| `YtGenreCard.kt` | 54 |
| `YtGenreDistributionBar.kt` | 83 |
| `YtHomeSkeleton.kt` | 17 |
| `YtImportPlaylistSheet.kt` | 269 |
| `YtLibraryFilterChips.kt` | 52 |
| `YtListenAgainGrid.kt` | 98 |
| `YtLoginDialog.kt` | 147 |
| `YtLyricLineItem.kt` | 76 |
| `YtLyricsHeader.kt` | 165 |
| `YtMoodFilterRail.kt` | 59 |
| `YtPersonaCard.kt` | 92 |
| `YtPlayerActionPills.kt` | 187 |
| `YtPlayerBottomTabs.kt` | 81 |
| `YtPlayerSeekBar.kt` | 250 |
| `YtPlaylistHeroHeader.kt` | 252 |
| `YtPresetFilterChips.kt` | 53 |
| `YtQueueHeader.kt` | 95 |
| `YtQueueTrackItem.kt` | 166 |
| `YtQuickPicksCarousel.kt` | 132 |
| `YtSearchFilterChips.kt` | 52 |
| `YtSearchOmnibar.kt` | 95 |
| `YtSectionHeader.kt` | 64 |
| `YtSongVideoSwitcher.kt` | 110 |
| `YtSortFilterBar.kt` | 60 |
| `YtStudioArcDial.kt` | 97 |
| `YtSupermixCard.kt` | 111 |
| `YtSyllableLine.kt` | 201 |
| `YtThumbnail.kt` | 137 |
| `YtTopAppBar.kt` | 140 |
| `YtTopResultCard.kt` | 94 |
| `YtVerticalEqSlider.kt` | 115 |
| `YtWrappedHeroCard.kt` | 83 |

## `com.streamify.app.ui.lyrics/`

Lyrics rendering engine.

| File | Lines |
|---|---|
| `LyricsEngine.kt` | 73 |

## `com.streamify.app.ui.models/`

UI presentation models (ProGuard-pinned).

| File | Lines |
|---|---|
| `UiModels.kt` | 33 |

## `com.streamify.app.ui.screens/`

One file per destination (23 screens).

| File | Lines |
|---|---|
| `AdminDashboardScreen.kt` | 1366 |
| `AdminTerminalScreen.kt` | 293 |
| `AlbumScreen.kt` | 338 |
| `ArtistScreen.kt` | 202 |
| `CommunityHubScreen.kt` | 232 |
| `DownloadScreen.kt` | 366 |
| `EqualizerScreen.kt` | 287 |
| `FullPlayerSheet.kt` | 1074 |
| `FullPlayerSheetPanes.kt` | 477 |
| `HomeScreen.kt` | 323 |
| `JamSessionScreen.kt` | 945 |
| `LibraryScreen.kt` | 990 |
| `LyricsScreen.kt` | 293 |
| `PlayerScreen.kt` | 30 |
| `PrismaticSplashScreen.kt` | 495 |
| `ProfileSelectionScreen.kt` | 262 |
| `QueueScreen.kt` | 315 |
| `SearchScreen.kt` | 614 |
| `SettingsScreen.kt` | 1185 |
| `StatsWrappedScreen.kt` | 338 |
| `UniversalHomeScreen.kt` | 144 |
| `UserProfileScreen.kt` | 654 |
| `YtOnboardingScreen.kt` | 167 |

## `com.streamify.app.ui.theme/`

Design tokens: color, type, shape, dimens, screen classes.

| File | Lines |
|---|---|
| `Color.kt` | 100 |
| `Dimens.kt` | 105 |
| `ScreenConfiguration.kt` | 48 |
| `Shape.kt` | 46 |
| `Theme.kt` | 92 |
| `Type.kt` | 195 |

## `com.streamify.app.util/`

Cross-cutting utilities + the newpipe PO-token pipeline.

| File | Lines |
|---|---|
| `ApkInstaller.kt` | 104 |
| `DurationFormatter.kt` | 18 |
| `FleetConfig.kt` | 207 |
| `MediaStoreScanner.kt` | 96 |
| `PermissionHelper.kt` | 22 |
| `SLog.kt` | 369 |
| `StreamifyHapticEngine.kt` | 136 |
| `TimeGreeting.kt` | 33 |
| `Trace.kt` | 31 |
| `TrackShareCard.kt` | 41 |

## `com.streamify.app.util.newpipe/`

NewPipe-Extractor bootstrap, BotGuard WebView, PO-token generation.

| File | Lines |
|---|---|
| `JavascriptUtil.kt` | 122 |
| `NewPipeBootstrap.kt` | 37 |
| `NewPipeDownloaderImpl.kt` | 63 |
| `PoTokenWebView.kt` | 252 |
| `StreamifyPoTokenGenerator.kt` | 118 |

## `com.streamify.app.viewmodel/`

Presentation state (PlayerViewModel + companions + feature VMs).

| File | Lines |
|---|---|
| `CommunityViewModel.kt` | 92 |
| `HomeViewModel.kt` | 350 |
| `IngestionViewModel.kt` | 128 |
| `JamViewModel.kt` | 356 |
| `LibraryViewModel.kt` | 45 |
| `PlayerHousekeeping.kt` | 199 |
| `PlayerModels.kt` | 64 |
| `PlayerTicker.kt` | 129 |
| `PlayerTrackLoader.kt` | 386 |
| `PlayerTrackMedia.kt` | 86 |
| `PlayerViewModel.kt` | 1146 |
| `SearchViewModel.kt` | 555 |
| `UiEventBus.kt` | 26 |

## `com.streamify.app.worker/`

Foreground download worker.

| File | Lines |
|---|---|
| `DownloadWorker.kt` | 186 |

---

**App module: 223 Kotlin files** across 35 packages, plus `MainActivity.kt` / `StreamifyApp.kt` at the root.
