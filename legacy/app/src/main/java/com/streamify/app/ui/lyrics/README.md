# `ui/lyrics/` — Lyric Rendering Engine

## Files

| File | What it is |
|---|---|
| `LyricsEngine.kt` | Turns cached lyric data + playback position into per-line/per-syllable render state. |

Consumers: `ui/screens/LyricsScreen`, `ui/components/LyricsCanvas`,
`YtSyllableLine`. Timing inputs come from `media/lyrics/`.
