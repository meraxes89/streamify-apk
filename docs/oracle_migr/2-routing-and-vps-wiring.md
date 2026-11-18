# 2. Routing Map and Oracle VPS Wiring Specification

## 1. Overview & Objective

This document specifies the routing architecture, HTTP contracts, and frontend wiring for **Architecture v3** (Cloudflare Edge Router + Oracle VPS Sole Resolver & Stream Relay).

All client playback requests, search queries, queue prefetching, and health checks are unified and routed through the Cloudflare Edge endpoint (`https://api.yourdomain.com`), which terminates TLS and forwards miss/relay traffic to the hardened Oracle Cloud VPS.

```
+-------------------------------------------------------------------------+
|                  Streamify Frontend (Browser / PWA)                    |
+-------------------------------------------------------------------------+
       │                      │                      │              │
       │ GET /api/search      │ GET /api/resolve     │ GET /api/stream/:id
       │                      │ POST /api/prefetch   │              │
       ▼                      ▼                      ▼              ▼
+-------------------------------------------------------------------------+
|                Cloudflare Global Edge (api.yourdomain.com)             |
|  - Edge Caching (Search: 10m TTL, Metadata: short TTL)                  |
|  - Origin Shielding (Drops direct scan traffic to Oracle VPS)           |
|  - Zero Direct Upstream to YouTube (Cloudflare ASN 13335 never queries) |
+-------------------------------------------------------------------------+
                                      │
                         Strict TLS (Cloudflare IPs only)
                                      ▼
+-------------------------------------------------------------------------+
|                  Oracle Cloud Always-Free VPS Origin                    |
|                                                                         |
|  [Caddy Reverse Proxy]                                                  |
|    - SSRF Guard: Restricts stream targets to *.googlevideo.com only     |
|    - Rate limiting per API Key / client token                           |
|                                                                         |
|  [Node.js Resolver & Relay Service]                                     |
|    - Multi-Profile Resolver (Android VR / VisionOS / iOS)               |
|    - 8 MiB Bounded Chunker (Prevents 32 KiB/s YouTube throttle)         |
|    - Predictive Queue Prefetch Engine (Buffers Track N+1 first 8 MiB)   |
|    - Single-Flight Request Deduplication                                |
+-------------------------------------------------------------------------+
```

---

## 2. Comprehensive Endpoint Routing Matrix

| Route | Method | Downstream (Browser <-> Edge) | Upstream (Edge <-> VPS <-> YouTube) | Caching / Performance Target |
| :--- | :--- | :--- | :--- | :--- |
| `/api/search` | `GET` | Autocomplete & catalog search | Forwarded to VPS on cache miss | Cloudflare Edge Cache (10m TTL) |
| `/api/resolve` | `GET` | Query `?videoId=...` returns stream descriptor & metadata | VPS resolves via mobile/VR InnerTube client | Cloudflare Edge (30s) + VPS LRU cache |
| `/api/stream/:videoId` | `GET` | Accepts HTTP `Range` headers; answers with HTTP 206 Partial Content | VPS fetches 8 MiB bounded chunks from `googlevideo.com` | No edge cache; Full line speed (6-10 MB/s) |
| `/api/prefetch` | `POST` | Body: `{ currentVideoId, nextVideoId, clientCodecHint }` | VPS resolves `nextVideoId` and buffers first 8 MiB | In-memory buffer on VPS; Sub-100ms TTFB on next track |
| `/api/health` | `GET` | Health check diagnostics payload | VPS system status, profile scores, and memory stats | Real-time (`no-cache`) |
| `/ytm/meta` | `GET` | Backward compatibility with W1-A Edge KV metadata calls | Handled by Edge Worker KV or forwarded to VPS | Compatible with existing provider |

---

## 3. Detailed Route Contracts

### 3.1. Stream Relay (`GET /api/stream/:videoId`)
- **Downstream Contract:**
  - Browser HTML5 `<audio>` element sends standard `Range: bytes=START-END` or `Range: bytes=0-`.
  - Relay returns `HTTP 206 Partial Content` with `Content-Range: bytes START-END/TOTAL` and `Content-Type: audio/webm` (or `audio/mp4`).
- **Upstream 8 MiB Bounded Chunking Invariant:**
  - When fetching media bytes from `*.googlevideo.com`, the VPS **NEVER** issues unbounded requests (`Range: bytes=0-`) or ranges $\ge 12$ MiB, which trigger YouTube's bandwidth throttle ($32$ KiB/s).
  - Upstream requests are strictly bounded to slices of 8 MiB ($8{,}388{,}608$ bytes):
    - Chunk 0: `bytes=0-8388607`
    - Chunk 1: `bytes=8388608-16777215`
  - Chunks stream into the downstream response pipe immediately without waiting for the full 8 MiB to download.

### 3.2. Resolver (`GET /api/resolve?videoId=:videoId`)
- **Request Parameters:**
  - `videoId`: 11-character YouTube video ID.
- **Success Response (`200 OK`):**
  ```json
  {
    "ok": true,
    "videoId": "4NRXx6U8ABQ",
    "title": "Song Title",
    "artist": "Artist Name",
    "durationMs": 214000,
    "streamUrl": "https://api.yourdomain.com/api/stream/4NRXx6U8ABQ",
    "codecs": "opus",
    "bitrate": 160000,
    "contentLength": 4288000,
    "rangeSupport": true,
    "expiresAt": 1740000000000
  }
  ```
- **Fallback Guarantee:**
  - If the VPS resolver endpoint is unavailable or returns an error, the frontend falls back to the client-side `getStreamUrl` pipeline.

### 3.3. Predictive Queue Prefetch (`POST /api/prefetch`)
- **Request Body:**
  ```json
  {
    "currentVideoId": "4NRXx6U8ABQ",
    "nextVideoId": "dQw4w9WgXcQ",
    "clientCodecHint": "opus"
  }
  ```
- **Trigger Condition:**
  - When the playing track has $\le 20$ seconds remaining (`timeLeft <= 20`), the player engine fires this fire-and-forget request.
- **Server Action:**
  - Resolves `nextVideoId` immediately if not in cache.
  - Asynchronously warms the first 8 MiB chunk into the VPS LRU memory buffer.
  - When the user transitions to `nextVideoId`, the initial audio chunk is served with $<100$ ms TTFB.

### 3.4. System Health Check (`GET /api/health`)
- **Success Response (`200 OK`):**
  ```json
  {
    "status": "ok",
    "uptime": 86400,
    "version": "3.0.0",
    "profiles": {
      "android_vr": { "score": 98, "state": "active" },
      "visionos": { "score": 95, "state": "standby" },
      "ios": { "score": 90, "state": "standby" }
    },
    "cache": {
      "items": 142,
      "memoryBytes": 118489088
    }
  }
  ```

---

## 4. Frontend Integration Points

1. **`front-end/feishin/src/renderer/api/streamify/playback/stream-source.ts`**:
   - `resolveStreamifyTrack`: checks if `relayBaseCandidate()` is set. If set, invokes `${relayBase}/api/resolve?videoId=...`.
   - On valid resolve, constructs progressive `StreamUrlResolution` pointing to `${relayBase}/api/stream/${videoId}` with `rangeSupport: true`.
   - On network error or 404, gracefully falls back to canonical `getStreamUrl(videoId, { relayBase })`.
   - Exports `prefetchTrack(currentVideoId, nextVideoId)` for queue warming.

2. **`front-end/feishin/src/renderer/features/player/audio-player/web-player.tsx`**:
   - In `onProgressPlayer1` / `onProgressPlayer2`:
     - When `timeLeft <= 20 && timeLeft > 0`, retrieves the upcoming queue track ID.
     - Calls `prefetchTrack(currentId, nextId)` once per track transition.

3. **`front-end/feishin/src/renderer/features/streamify-settings/connection-diagnostics.ts`**:
   - Probes `${relayBase}/api/health` first. If healthy, marks the stream solver plane as `healthy` with live latency.
   - Falls back to `${relayBase}/` for legacy worker relays.

4. **`front-end/feishin/src/renderer/store/streamify-settings.store.ts`**:
   - Probes `${base}/api/health` in `checkRelayHealth` with fallback to `HEAD base`.
