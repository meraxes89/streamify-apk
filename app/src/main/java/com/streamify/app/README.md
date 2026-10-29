# `com.streamify.app` — Package Root

Streamify's single Android module, organized by layer:

```
com.streamify.app/
├── MainActivity.kt / StreamifyApp.kt   entry points (manifest-rooted)
├── data/        data layer (repos, persistence, providers, network)
├── media/       the audio engine (playback, DSP, cache, sync, workers)
├── jam/         distributed jam sessions
├── radio/       radio queue construction
├── di/          AppGraph + dispatcher provider
├── navigation/  the Compose nav graph
├── ui/          Compose UI (screens, components, theme)
├── viewmodel/   presentation state
├── util/        cross-cutting utilities
└── worker/      foreground workers
```

Every folder above carries its own `README.md`. Start from
`docs/ARCHITECTURE.md` for the big picture, and
`docs/ADD_A_FEATURE.md` for the step-by-step playbook.

Dependency direction (enforced by convention):
`ui → viewmodel → data / media → di / util`.
