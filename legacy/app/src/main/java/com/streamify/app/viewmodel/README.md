# `viewmodel/` — Presentation State

## Files

| File | What it is |
|---|---|
| `PlayerViewModel.kt` | The playback brain (split in v1.1.0 into focused companions below). |
| `PlayerModels.kt` / `PlayerHousekeeping.kt` / `PlayerTicker.kt` / `PlayerTrackLoader.kt` / `PlayerTrackMedia.kt` | PlayerViewModel's modules: state models, listener wiring, position ticking, track loading, MediaController glue. |
| `HomeViewModel.kt` | Home shelves. |
| `SearchViewModel.kt` | Search + results. |
| `LibraryViewModel.kt` | Library + import. |
| `JamViewModel.kt` | Guest-side jam PLL decisions. |
| `CommunityViewModel.kt` | Community hub. |
| `IngestionViewModel.kt` | Import progress. |
| `UiEventBus.kt` | One-shot UI event bus. |

ViewModels are created via `viewModel()` + `AppGraph` lookups; they
own coroutine scopes (cleaned up in v1.1.0's concurrency pass).
