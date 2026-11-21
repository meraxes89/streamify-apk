# `data/lyrics/` — Lyric Cache

## Files

| File | What it is |
|---|---|
| `LyricsCacheManager.kt` | High-performance Service-Tier SLYR & LRC binary cache manager. |

Distinguishes this package from its lyrics siblings:

- `data/network/LyricsResolver` — *fetches* lyrics from providers
- `data/lyrics/LyricsCacheManager` — *stores* the compiled `.slyr`
  binaries (this package)
- `media/lyrics/` — *plays back* against the clock (offsets, sync)
- `ui/lyrics/` — *renders* karaoke text
- `ui/components/LyricsCanvas` — the draw-phase GPU surface
