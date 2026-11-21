# `ui/` — Jetpack Compose UI

```
ui/
├── animations/   reusable motion effects (press, burst, transitions)
├── components/   shared component library (app-wide)
│   └── yt/       the YouTube-Music-styled widget kit (34 widgets)
├── lyrics/       lyrics state engine (rendering brain)
├── models/       UI-layer models (pinned by ProGuard)
├── navigation/   the nav graph
├── screens/      one file per destination (23 screens)
├── theme/        colors, typography, shapes, dims, StreamifyTheme
```

State lives in `viewmodel/`; screens compose `ui/components` and
observe state. Screens must not talk to `data/` directly except via
repositories/managers exposed through ViewModels or AppGraph.
