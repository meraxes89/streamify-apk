# composeApp/ — Compose Multiplatform UI

The shared UI module: screens, navigation, ViewModels, and the Koin graph,
compiled for Android, JVM (desktop), and wasmJs (browser). Namespace
`com.maxrave.simpmusic`. The Android shell is
[androidApp](../androidApp/README.md); the desktop entry is
[desktopApp](../desktopApp/README.md).

## Source sets and entries

- `commonMain` — all screens, ViewModels, navigation, repositories glue.
  Entry: `App(viewModel: SharedViewModel)` (`App.kt`).
- `androidMain` — Android actuals; hosted by `MainActivity`.
- `jvmMain` — `DesktopApp.kt` with `runDesktopApp(...)`: Sentry init,
  single-instance guard, Koin startup, 1500x860 window, system tray,
  MiniPlayer window, macOS event handling.
- `wasmJsMain` — `main.kt` runs `initWebKoin()` and mounts `ComposeViewport`.
- iOS targets are currently commented out of the build (the `iosMain`
  expect/actuals remain).
- `room-wasm-stub` is a wasmJs-only dependency providing no-op shims
  (Room, ktor CIO, kmpalette, java.* classes) so common code compiles for
  the browser.

## UI structure

- `ui/screen/`: home (HomeScreen, settings, mood, notifications, listen
  together, analytics, wrapped), library (LibraryScreen, local playlists,
  dynamic playlists, mix-for-you), login (YouTube/Spotify/Last.fm/Discord/
  Google onboarding), other (album, artist, browse, search, playlist,
  podcasts, admin dashboard, splash), player (NowPlaying with AppleMusic,
  M3Expressive, SoundCloud, Spotify style variants), plus `MiniPlayer.kt`.
- `ui/navigation/`: ~28 typed destinations (kotlinx.serialization) and the
  graph split (App, Home, Library, List, Login).
- `viewModel/`: SharedViewModel (app-wide state and service bridge),
  SearchViewModel, LibraryViewModel, LocalPlaylistViewModel, PlaylistViewModel,
  HomeViewModel, SettingsViewModel, MoodViewModel, AnalyticsViewModel,
  WrappedViewModel, TasteViewModel, ListenTogether pair, login-sync host and
  sender pair, and more — all registered in `di/ViewModelModule.kt`.

## Data and sync

- Ktor 3.6.0 client (CIO on Android/JVM, JS engine on wasm); Coil3 images;
  YouTube scraping via the `kotlinYtmusicScraper` service module.
- Room SQLite lives in the `core` submodule (see
  [ARCHITECTURE.md](../docs/architecture/ARCHITECTURE.md) section 6);
  `StreamifySyncEngine` (`com/streamify/sync/StreamifySyncEngine.kt`)
  coordinates it with Supabase: local-first writes, `isSyncActive` echo
  suppression, timestamp LWW, staged batched sync (liked songs, followed
  artists, playlists) with a sealed `SyncStatus` flow.
- Helpers: `data/RemoteGreetingManager.kt` (remote greeting slots with
  stale-while-revalidate from the repo's raw CDN config and bundled
  fallback), `data/scraper/ExternalPlaylistScraper.kt` +
  `data/scraper/PlaylistImportManager.kt` (Spotify/Apple Music playlist
  import, YouTube import; both Koin singles).

## Behavior contracts from recent PRs

- **Play queue source of truth (PR #252)**: queue construction reads the
  repository (`getFullPlaylistTracks`), never the UI paging snapshot. Every
  play event is single-flight (`playJob?.cancel()`), and handles three
  states: tracks loading (await first non-empty emission), empty (toast and
  abort), loaded (set queue data and load media item).
- **Home header (PR #251)**: a single-row 56dp glassmorphic header with
  `ExpandableChip` mood filters (replacing the former 64dp bar + 50dp chip
  row).
- **Mini player physics (PR #249)**: 1:1 drag tracking with
  `offsetY.snapTo`, 56dp dismiss threshold, settle/release springs
  (`DampingRatioMediumBouncy`, `StiffnessMediumLow`), appearance springs
  no-bouncy.
- **Library carousel (PR #250)**: "Your Playlists" `LazyRow` section in
  LibraryScreen; playlist import from external URLs via the scraper pair.

## Build and test

```bash
./gradlew :composeApp:assembleDebug        # Android UI
./gradlew :composeApp:jvmTest              # desktop unit tests
./gradlew :composeApp:wasmJsBrowserDistribution   # web bundle (server/ consumes it)
```

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
