# `media/lyrics/` — Lyric Playback Control

Bridges lyric data to the playback clock.

## Files

| File | What it is |
|---|---|
| `LyricPlaybackController.kt` | Drives the active-line state machine against player position. |
| `LyricOffsetStore.kt` | Per-track user lyric offset persistence. |

Lyrics pipeline map: fetched by `data/network/LyricsResolver`, cached
by `data/lyrics/LyricsCacheManager`, timed here, rendered by
`ui/lyrics/LyricsEngine` + `ui/components/LyricsCanvas`.
