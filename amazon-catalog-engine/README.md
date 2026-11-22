# Amazon Catalog Engine (Streamify Laboratory)

An autonomous, anonymous, on-device Amazon Music metadata and streaming engine developed in isolation from `/server/src`.

## Architecture & Principles

1. **Anonymous On-Device Ingestion:**
   * Handshakes against `https://music.amazon.{tld}/config.json` to acquire guest session credentials (`sessionId`, `deviceId`, `deviceType`, `csrf`).
   * No user login, no credentials, and no developer API access token required.
2. **Decoupled Protocol Descriptor (`protocol.json`):**
   * Isolates volatile Amazon headers, target names, and endpoints from stable client logic.
   * Can be refreshed on-the-fly without rebuilding application code.
3. **Multi-Territory Support:**
   * Manages independent guest sessions across marketplaces: US (`NA`), GB (`ZAZ`), IN (`ZAZ`), DE (`ZAZ`), JP (`FE`).
4. **Governance & Anti-Ban Protections (`src/governance.mjs`):**
   * Single-flight session initialization (prevents concurrency stampedes).
   * Per-region circuit breaker that trips immediately on HTTP 429 with exponential backoff and cooldown quarantine.
   * Rate pacer ensuring non-bursty residential egress.
5. **Canonical Normalization (`src/mappers.mjs`):**
   * Standardizes raw ASINs into Streamify's canonical multiplatform schema (`amzn:track:{asin}`).
   * Extracts track title, artists, duration, scaled artwork, release year, and record label (℗ line).
6. **Graceful Degradation (`src/scraper.mjs`):**
   * Tier-1: Internal Coral RPC
   * Tier-2: SSR Hydration State (`__NEXT_DATA__`)
   * Tier-3: Schema.org `MusicRecording` HTML microdata
7. **Synchronized Lyrics (`src/lyrics.mjs`):**
   * LRCLIB integration providing millisecond-accurate timing cues.
8. **Hybrid Audio Resolution Bridge (`src/resolver.mjs`):**
   * Resolves Amazon ASINs and metadata to high-bitrate Opus streams with duration tolerance matching and zero DRM friction.

---

## The 12-API Parity Suite

| API # | Endpoint Method | Streamify YouTube Counterpart | Function |
|:---:|---|---|---|
| **1** | `client.getHome({ territory })` | `GET /api/yt/home` | Curated editorial showcase & charts |
| **2** | `client.search(query, options)` | `GET /api/yt/search` | Catalog search (tracks, albums, artists, playlists) |
| **3** | `client.instantSearch(query, options)` | `GET /api/yt/suggest` | Real-time search suggestions / auto-complete |
| **4** | `client.getBrowse(browseId, options)` | `GET /api/yt/browse` | Universal browse for albums, artists, playlists |
| **5** | `client.getNext(trackOrAsin, options)` | `GET /api/yt/next` | Autoplay queue generation |
| **6** | `client.getThumb(url, size)` | `GET /api/yt/thumb` | High-definition image scaling |
| **7** | `client.scrapePlaylist(url)` | `GET+POST /api/playlist/scrape` | Direct Amazon Music playlist link import |
| **8** | `client.getLyrics(track, artist, dur)` | `GET /api/yt/lyrics` | Millisecond-accurate synchronized lyrics |
| **9** | `client.resolveAudio(trackOrAsin)` | `GET /api/stream?id=...` | Hybrid Opus audio resolution bridge |
| **10**| `client.getDashManifest(trackOrAsin)` | `GET /api/stream/:id/manifest.mpd`| Dynamic DASH MPD generator |
| **11**| `client.prefetchDescriptor(track)` | `POST /api/stream/prefetch` | Stage 1 prefetch descriptor |
| **12**| `client.probe(territory)` | `GET /api/health` | Live multi-territory health & telemetry |

---

## Quick Start & Verification

Run tests (25 passing unit tests across 7 test suites):
```bash
cd amazon-catalog-engine
npm test
```

Run discovery probe across marketplaces:
```bash
node cli.mjs probe
```

Test Home Showcase & Editorial:
```bash
node cli.mjs home US
```

Test Synced Lyrics:
```bash
node cli.mjs lyrics "Bohemian Rhapsody" "Queen"
```

Test Hybrid Audio Stream Resolution:
```bash
node cli.mjs resolve "Bohemian Rhapsody" "Queen"
```
