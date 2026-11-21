# `worker/` — Foreground Workers

## Files

| File | What it is |
|---|---|
| `DownloadWorker.kt` | Foreground download worker (notification progress, waits on `PlaybackService.isBuffering`). |

Engine-side maintenance workers live in `media/ingestion/`; this one
stays top-level because the Download screen drives it directly.
