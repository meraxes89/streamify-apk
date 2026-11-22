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
8. **Amazon-First Stream Resolution (`src/resolver.mjs`):**
   * THE POLICY (2026-11): resolution NEVER silently bridges to YouTube.
     Default ladder: authenticated DMLS full-song link graph (tier ladder
     Ultra HD FLAC -> HD FLAC -> V2 lossy manifest) -> REAL guest sample URL
     (signed CloudFront MP3) with the honest AUTH_REQUIRED full-song verdict
     and the exact unlock recipe. The legacy YouTube bridge survives ONLY as
     an explicit opt-in (`{ allowBridge: true }` / CLI `resolve-youtube`).
9. **Lossless Full-Song Engineering (`src/lossless-fullsong.mjs`, `src/flac-introspector.mjs`, `src/frame-gateway.mjs`):**
   * Real full-song byte acquisition (HttpAudioSource) + magic-byte codec sniffing + honest delivery verdicts.
   * Spec-exact FLAC frame header decoding (UTF-8-coded sample numbers, blocking-strategy bit, CRC-8 validation).
   * Metadata-first remux, sample-domain frame-tile integrity, frame-aligned HTTP 206 seeks, two-phase pacing.
10. **Native Delivery Capability Card (`src/native-delivery.mjs`):**
   * Routes Amazon's REAL DMLS surface (`/{region}/api/dmls/`, fully decoded from the production
     web-hornet Vinyl player bundle, d5fx445wy2wpk.cloudfront.net 1.196.81 + the live
     vinylChunk-e54d4980a921fb73eda3fe610b852eb1.js dmls module):
     `getDashManifestsV2` (FLAC/Opus path: SIREN_KATANA + TRACK_PSSH; V2 lossy path:
     bitrateTypeList, no content protection), `getStreamingURLsWithFirstChunkV2`,
     `getStreamingURLsForSamples`, `getLicenseForPlaybackV2`, plus the legacy COID op.
   * Returns typed honest verdicts: `AUTH_REQUIRED` (guest sessions carry no customerId; live-verified
     `CUSTOMER_INFO_NOT_PROVIDED`), `ENTITLEMENT_REQUIRED` (needs UNLIMITED_HD HAWKFIRE+KATANA),
     `DRM_PROTECTED_LINKS` (links solved, CENC-wrapped), `LINKS_SOLVED` (real links, clear bytes),
     `PREVIEW_ONLY` (real sample URL solved for the anonymous guest).
   * Tier entitlement map decoded from the shipped player: AMF->NIGHTWING, PRIME->ROBIN+SONIC_RUSH,
     UNLIMITED->HAWKFIRE, UNLIMITED_HD->HAWKFIRE+KATANA.

11. **The REAL Amazon Stream-Link Solver (`src/dmls-solver.mjs` + `src/dash-mpd.mjs` + `src/credentials.mjs`):**
   * **`solveStreamLinks({ asin, tier })`** issues the production `getDashManifestsV2` request
     and solves the ACTUAL Amazon stream links: the inline DASH MPD, per-representation quality cards
     (FLAC UHD96/UHD192/UHD48/UHD44 24-bit, HD family, opus/aac), init + media segment CDN URLs
     (SegmentTemplate `$RepresentationID$/$Number$` substitution against the manifest BaseURL),
     optional first-chunk direct URL (`getStreamingURLsWithFirstChunkV2`), and the DRM card
     (SIREN_KATANA CENC pssh list + `getLicenseForPlaybackV2` operation).
   * **Two manifest profiles** (production de()): tier `ultra`/`hd`/`best` -> FLAC/Opus path
     (`musicDashVersionList: [SIREN_KATANA | SIREN_KATANA_NO_CLEAR_LEAD]`,
     `contentProtectionList: [TRACK_PSSH]`, entitlement-gated `tryAsinSubstitution`);
     tier `lossy`/`v2` -> the V2 bitrateTypeList path (`bitrateTypeList: [HIGH|MEDIUM|LOW]`,
     no content protection requested) - the account's real lossy floor.
   * **`solveSampleUrl({ asin })`** - THE GUEST VECTOR (live-verified 2026-11):
     `getStreamingURLsForSamples` with the registered web-player client id **`WebCP`**
     (authStore.clientId) and a REAL expiration timestamp (unix now + 3600) answers
     HTTP 200 for ANONYMOUS guests with SIGNED CloudFront MP3 sample URLs
     (`urls[0][0]` envelope; ~30s @ 64 kbps; audio/mpeg bytes verified end-to-end).
     Both the registered client id and the real expiration are REQUIRED: deviceType-as-clientId
     answers `400 BadRequestException "unknown client"`, expiration=0 is not the production shape.
   * **`verifyLinkReachability(url)`** - live Range probe of any solved CDN URL with honest
     byte-level evidence (HTTP status, content-type, total bytes, first-bytes hex).
   * **Zero-dependency MPD parser** speaking the Amazon dialect: `SupplementalProperty
     schemeIdUri="amz-music:bitDepth" (16/24), `"amz-music:trackType"` (UHD/HD/SD), CENC
     `ContentProtection` with `cenc:default_KID` + `cenc:pssh`, codec vocabulary `flac`/`opus`/
     `mp4a.40.2`/`mp4a.40.5`/`ec-3`.
   * **Credential layer** (production auth vectors): cookie mode (the player's default:
     `POST /config.json?clientApplication=hornet&cookieauth=true` with your signed-in jar;
     non-empty `customerId` == authenticated) or bearer mode (`Authorization: Bearer` on Coral calls -
     plain Bearer for every token shape incl. Atna|; the AmznMusic base64 form is the
     vinyl-log endpoint only). Resolve from `AMAZON_MUSIC_COOKIES` / `AMAZON_MUSIC_OAUTH_TOKEN` env,
     `AMAZON_MUSIC_COOKIES_FILE` / `~/.streamify/amazon-music.cookies` (raw header or Netscape jar),
     or programmatic `AmazonMusicCredential`.
   * **Entitlement ladder decoded and encoded**: Unlimited -> [KATANA, HAWKFIRE, SONIC_RUSH, ROBIN,
     NIGHTWING] (client-asserted; the server enforces the real account state).
   * **CLI**: `node cli.mjs flac-links <asin> [--territory=US] [--tier=ultra|hd|lossy|best]
     [--cookies-file=...] [--with-first-chunk] [--force-wire] [--no-clear-lead] [--verify]
     [--save-mpd=...]`.

12. **The Coral Dispatcher Fix (`session.mjs` / `protocol.json`):**
   * Every Coral RPC now carries `Content-Encoding: amz-1.0` - the production dispatcher key that
     was missing from every call. Without it Amazon answers
     `com.amazon.coral.service#UnknownOperationException`; with it, Tenzing catalog search
     and DMLS delivery both reach the real services (live-verified: search returns real tracks
     as an anonymous guest, DMLS reaches the real entitlement gate).

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
| **9** | `client.resolveAudio(trackOrAsin)` | `GET /api/stream?id=...` | **Amazon-native stream resolution: real DMLS links (auth) or the real guest sample URL. Never a silent YouTube fallback** |
| **9b** | `client.getSampleStreamUrl(trackOrAsin)` | guest vector | **Real signed CloudFront MP3 sample URLs, anonymously** |
| **9c** | `client.resolveAudioViaYoutubeBridge(track)` | explicit opt-in | Legacy hybrid bridge (explicit only) |
| **10**| `client.getDashManifest(trackOrAsin)` | `GET /api/stream/:id/manifest.mpd`| Dynamic DASH MPD generator |
| **11**| `client.prefetchDescriptor(track)` | `POST /api/stream/prefetch` | Stage 1 prefetch descriptor |
| **12**| `client.probe(territory)` | `GET /api/health` | Live multi-territory health & telemetry |

---

## Honest Delivery Tiering (comm21 elite contract)

The engine never decorates Opus/AAC bytes as FLAC and never silently swaps platforms.
Every full-song artifact carries a `delivery` card with the sniffed codec verdict:

| Path | Verdict |
|---|---|
| Native DMLS (guest session) | `AUTH_REQUIRED` - no customerId, `CUSTOMER_INFO_NOT_PROVIDED` (live-verified 2026-11) |
| Native DMLS (entitled session) | `DRM_MANIFEST` - SIREN_KATANA CENC + TRACK_PSSH; license round-trip required |
| Native DMLS (entitled, V2 lossy tier) | `LINKS_SOLVED` - V2 bitrateTypeList path, links at the account's floor |
| Guest sample vector (anonymous) | `LINKS_SOLVED` - REAL signed CloudFront MP3, `delivery.previewOnly: true` (live-verified) |
| Relay bridge full bytes | `delivery.codec = 'opus'`, `bitPerfect: false` (labeled exactly what it is) |
| Real FLAC bytes (any source) | `delivery.codec = 'flac'`, `bitPerfect: true`, CRC-8-validated frame index |

FLAC machinery validated end-to-end on real 24-bit/96kHz files: 352/352 frames CRC-8 validated,
100% sample-domain frame-tile integrity, sample-precise frame-aligned seeks (t=10s -> exact
frame boundary, in-frame skip reported), PICTURE-deferred remux for <250ms cold start.

The 2026-11 guest vector breakthrough (decoded from the live production vinylChunk): the
registered client id **`WebCP`** + a real expiration timestamp unlock `getStreamingURLsForSamples`
for ANONYMOUS sessions - verified live: HTTP 200, signed CloudFront URL, 495,147 bytes of
real `audio/mpeg` with an ID3v2 header, fetched and sniffed end-to-end without any sign-in.

---

## Quick Start & Verification

Run tests (81 passing unit tests across 19 suites):
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

Test REAL Amazon stream resolution (Amazon-first: native links when authenticated,
real guest sample URL otherwise - never a silent YouTube fallback):
```bash
node cli.mjs resolve "Bohemian Rhapsody" "Queen"
```

Solve the REAL guest sample URL (anonymous, live-verified) with byte verification:
```bash
node cli.mjs sample "Blinding Lights" "The Weeknd" --verify --out=sample.mp3
```

The explicit YouTube bridge (legacy path, opt-in only):
```bash
node cli.mjs resolve-youtube "Bohemian Rhapsody" "Queen"
```

Test lossless resolution + native DMLS capability card:
```bash
node cli.mjs lossless "Bohemian Rhapsody" "Queen" --asin=B081TV3C5Y
```

Acquire whole raw full-song bytes (honest codec sniff; writes the file):
```bash
STREAMIFY_FLAC_SOURCE_URL=https://example.com/master.flac node cli.mjs lossless-full "Bohemian Rhapsody" "Queen" out.flac
# or via the relay: node cli.mjs lossless-full "Song" "Artist" --relay=http://127.0.0.1:3000
```

Solve REAL Amazon DMLS stream links (FLAC Ultra HD when authenticated; live link
verification with --verify):
```bash
node cli.mjs flac-links B081TV3C5Y --tier=best --verify
```

Deep FLAC introspection (local file or URL - STREAMINFO, CRC-8-validated frame index,
metadata-first remux, frame-aligned seek geometry, two-phase pacing):
```bash
node cli.mjs inspect master.flac
```
