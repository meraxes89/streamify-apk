# `data/youtube/` — YouTube Music Integration

Session management and bulk import from YouTube Music.

## Files

| File | What it is |
|---|---|
| `YtSessionExtractor.kt` | YTM session/cookie extraction. |
| `BatchTrackResolver.kt` | Bulk resolves scraped/imported items to canonical tracks (fast-path direct videoIds). |
| `PlaylistLinkScraper.kt` | Scrapes playlist pages into `ScrapedPlaylist`/`ScrapedTrack`. |

The heavy stream machinery (InnerTube racing, PO tokens, CDN decode)
lives in `data/network/` (with pure-Rust RustyPipe extraction in the Rust core); this package is only the
YTM account/import surface.
