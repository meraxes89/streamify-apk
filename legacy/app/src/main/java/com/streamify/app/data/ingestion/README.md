# `data/ingestion/` — Import & Bootstrap Pipelines

First-run and import machinery: turning external sources into a
populated local library.

## Files

| File | What it is |
|---|---|
| `FeedBootstrapManager.kt` | First-launch shelf bootstrap (personalized home feed warm-up). |
| `ExportifyParser.kt` | Parses Exportify CSV exports into `ParsedTrackItem`s. |
| `NeuroQueueManager.kt` | Psychological neuro-acoustic queue builder (tempo/energy arcs). |

## Flow

```
Spotify Exportify CSV ──▶ ExportifyParser ──▶ PlaylistRepository
first launch ──────────▶ FeedBootstrapManager ──▶ TrackRepository
NeuroQueueManager ◀──── viewmodel (queue intent) ──── media/playback
```

Background ingest *workers* (WorkManager) live in `media/ingestion/`;
this package holds the pure pipeline logic they drive.
