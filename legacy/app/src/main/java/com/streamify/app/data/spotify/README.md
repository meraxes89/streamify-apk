# `data/spotify/` — Spotify Integration

OAuth (PKCE) and library extraction for taste ingestion.

## Files

| File | What it is |
|---|---|
| `SpotifyAuthManager.kt` | PKCE flow, connection state flows (`isSpotifyConnectedFlow`). |
| `SpotifyConfig.kt` | Client id/redirect endpoints. |
| `SpotifySessionExtractor.kt` | Likes/playlist extraction into the local catalog. |

PKCE executes in a Custom Chrome Tab; tokens never touch a backend.
Ingested tracks flow through `data/repository/TrackRepository` and
native CAD-ID hashing for dedup against YouTube-sourced tracks.
