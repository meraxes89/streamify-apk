# `media/` — The Audio Engine

Everything that owns the playing experience: the foreground service,
the DSP chain, cache/buffering policy, clock sync, and background
workers. Born in v1.2.0 from the old flat `service/` grab-bag.

```
media/
├── playback/  the foreground service + queue authority
├── audio/     DSP: processors, spatial, equalizer, devices
├── cache/     audio byte caches, pre-buffering, eviction, remux
├── sync/      PTP clock sync, PLL, scheduling, thermal
├── ingestion/ WorkManager background ingest/compute
└── lyrics/    lyric playback controllers + offset store
```

Layering: `media/` may consume `data/` and `util/`; nothing may
import `ui/` from here (notifications excepted — they use
`androidx.media3` session APIs, not Compose).
