# `ui/components/yt/` — YouTube Music Widget Kit

The 34-widget YTM design system: home shelves, player chrome, search
UI, and library surfaces styled after YouTube Music.

## Kit map

- **Home**: `YtHomeSkeleton`, `YtQuickPicksCarousel`, `YtListenAgainGrid`,
  `YtSupermixCard`, `YtMoodFilterRail`, `YtGenreCard`, `YtWrappedHeroCard`
- **Player**: `YtPlayerSeekBar`, `YtPlayerActionPills`, `YtPlayerBottomTabs`,
  `YtSongVideoSwitcher`, `YtStudioArcDial`, `YtVerticalEqSlider`
- **Lyrics**: `YtLyricsHeader`, `YtLyricLineItem`, `YtSyllableLine`,
  `YtTopAppBar` (chrome)
- **Search**: `YtSearchOmnibar`, `YtSearchFilterChips`, `YtTopResultCard`,
  `YtSectionHeader`
- **Library**: `YtLibraryFilterChips`, `YtPresetFilterChips`,
  `YtSortFilterBar`, `YtQueueHeader`, `YtQueueTrackItem`,
  `YtPlaylistHeroHeader`, `YtImportPlaylistSheet`
- **Chromatics**: `YtGenreDistributionBar`, `YtThumbnail`, `YtPersonaCard`,
  `YtBottomNavBar`, `YtLoginDialog`

Shared (non-Yt) components live one directory up; these widgets may
import from there (e.g. `SireenBrandingBadge`, context menu locals).
