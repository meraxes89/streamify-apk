# `data/models/` — Core Value Models

The immutable data vocabulary every layer speaks. **Pinned by
ProGuard keep rules** (`app/proguard-rules.pro`):
`-keep class com.streamify.app.data.models.** { *; }` — the package
path is a serialization contract; do not move or rename.

## Files

| File | What it is |
|---|---|
| `Track.kt` | The universal track model (title/artist/cover/filepath/cadId...). |
| `Recommendation.kt` | Home-shelf recommendation models. |
| `LyricsData.kt` | Lyric payload models. |
| `OrchestratorStatus.kt` | Playback orchestrator status enums. |
| `AppMode.kt` | App mode constants. |

Rules: models stay dumb (no behavior beyond mapping), and everything
that crosses a layer boundary does it as one of these types.
