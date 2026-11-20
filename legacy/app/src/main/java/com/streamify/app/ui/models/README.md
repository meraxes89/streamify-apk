# `ui/models/` — UI Models

## Files

| File | What it is |
|---|---|
| `UiModels.kt` | UI-layer presentation models. **Pinned by ProGuard** (`-keep class com.streamify.app.ui.models.**`). |

Keep this package import-clean of stateful machinery; it exists so
screens can accept immutable, render-ready payloads.
