# `ui/components/` — Shared Component Library

Cross-screen building blocks. 120 FPS rules apply: stable keys,
explicit decode sizes, gated animations (see CHANGELOG v1.1.0).

## Highlights

| File | What it is |
|---|---|
| `TrackCard.kt` / `TrackListItem.kt` | The two track surfaces used everywhere. |
| `MiniPlayerBar.kt` / `PlayerControls.kt` | Persistent player surfaces. |
| `CommentsSheet.kt` | Comment sheet with position-tick confinement. |
| `ContextMenuSheet.kt` | `TrackContextMenuController` + `MenuOrigin` + `LocalContextMenuController` — the right-click-style context menu system (used by yt/ widgets too). |
| `LyricsCanvas.kt` | Offscreen-composited karaoke draw surface. |
| `ConnectAccountsSheet.kt` | Spotify/YTM connection flows. |
| `DynamicMeshBackground.kt` | Animated mesh backdrop. |
| `StreamifyPullToRefreshContainer.kt` | Pull-to-refresh wrapper. |

The YTM-styled kit is in `yt/` (34 widgets). If a widget is only used
by one screen, keep it in that screen's file instead of here.
