# `ui/screens/` — Destinations

One file per route; heavy screens may keep private composables in the
same file or a `*Panes.kt` sibling (see `FullPlayerSheet` +
`FullPlayerSheetPanes`).

## Screen index

| Screen | Route for |
|---|---|
| `HomeScreen` / `UniversalHomeScreen` | Main feed shelves. |
| `SearchScreen` | Omnibar search. |
| `LibraryScreen` | User library + import. |
| `PlayerScreen` / `FullPlayerSheet` (+Panes) | Now-playing surfaces. |
| `QueueScreen` | Queue editing. |
| `LyricsScreen` | Full-screen lyrics. |
| `AlbumScreen` / `ArtistScreen` | Catalog drill-downs. |
| `JamSessionScreen` | Distributed jam. |
| `CommunityHubScreen` / `UserProfileScreen` / `ProfileSelectionScreen` | Social. |
| `StatsWrappedScreen` | Wrapped analytics. |
| `EqualizerScreen` | DSP equalizer UI. |
| `DownloadScreen` | Downloads. |
| `SettingsScreen` | Settings (1185 lines — split pending). |
| `AdminDashboardScreen` / `AdminTerminalScreen` | Admin tooling. |
| `YtOnboardingScreen` | YTM connect onboarding. |
| `PrismaticSplashScreen` | Splash. |

State comes from `viewmodel/` — never talk to `data/` directly.
