# 1. Oracle VPS + Cloudflare Edge Migration Plan (Architecture v3)

## 1. Executive Summary

We have evaluated and retired previous client-side and serverless workarounds for licensed music streaming on mobile web:
- **Cloudflare Workers Direct Fetching:** Discarded because Cloudflare's shared datacenter ASN (AS13335) is heavily bot-walled by YouTube InnerTube (`LOGIN_REQUIRED: Sign in to confirm you're not a bot`).
- **Google Apps Script (GSF):** Discarded from the solving path because Google's `UrlFetchApp` enforces a hardcoded `User-Agent: Google-Apps-Script` that cannot be overridden, immediately triggering YouTube's scraper defenses.
- **Client-Side SAPISID Injection:** Discarded due to fragile session handling, CORS blocks on direct InnerTube endpoints, and ciphered stream requirements in mobile browser environments.

**The Solution:** We are migrating to **Architecture v3**, pairing a self-hosted **Oracle Cloud Always-Free VPS** (ARM A1: 4 OCPU, 24 GB RAM, 10 TB/month egress) as the sole stream resolver and relay, placed behind **Cloudflare's Global Edge** for TLS termination, edge caching, and origin shielding.

---

## 2. High-Level System Architecture

```
[ Browser / Mobile Web (GitHub Pages) ]
                │
                │ HTTPS / CORS (<30ms)
                ▼
[ Cloudflare Global Edge (Orange Cloud Proxied) ]
   ├── TLS Termination & CORS allowlist
   ├── Edge Cache (5-15ms TTFB):
   │     ├── /api/search (autocomplete & queries)
   │     └── /api/resolve (metadata only, short-lived)
   ├── Origin Shield: Hides Oracle VPS real IP
   └── Route Proxy: Passes stream/miss requests to VPS origin
                │
                │ Direct TLS (Firewall locked: Cloudflare IP ranges only)
                ▼
[ Oracle Cloud VPS (Primary ARM A1) ]
   ├── Caddy Reverse Proxy:
   │     ├── TLS via Let's Encrypt / Cloudflare Origin CA
   │     ├── CF-Connecting-IP restoration
   │     └── Strict SSRF Guard (*.googlevideo.com only)
   ├── Resolver API Service (Node.js/Fastify):
   │     ├── Mobile/VR Client Impersonation (ANDROID_VR → VISIONOS → IOS)
   │     ├── Profile Scorer & Circuit Breaker (SQLite persisted)
   │     └── Single-Flight Request Coalescing (In-memory LRU + SQLite)
   ├── Stream Relay Engine (Range-Capable):
   │     ├── 8 MiB Bounded Chunks upstream to googlevideo.com
   │     │   (Avoids YouTube's 32 KiB/s throttle on open-ended ranges)
   │     └── Standard HTTP 206 Partial Content downstream to browser
   └── Predictive Queue Prefetch Worker:
         └── Resolves & buffers first 8 MiB chunk of track N+1 while track N plays
                │
                ▼ (Optional Failover)
[ Oracle Cloud VPS (Standby AMD Micro) ]
   └── Passive replica with health watcher & failover DNS
```

---

## 3. Core Technical Principles & Rules

### Principle 1: Cloudflare Never Touches YouTube Directly
Cloudflare acts strictly as a reverse proxy, router, and edge cache. It **never** invokes InnerTube API calls or touches `googlevideo.com`. This guarantees Cloudflare's flagged datacenter IP range never burns playback requests.

### Principle 2: Relay is Primary (IP-Binding Reality)
YouTube frequently binds GoogleVideo stream authorizations to the client/IP that issued the resolution call. Therefore, the Oracle VPS serves as the primary stream relay (`/api/stream/:videoId`), accepting standard HTTP `Range` requests from `<audio>` and proxying media bytes.

### Principle 3: Upstream 8 MiB Range Chunking
GoogleVideo throttles open-ended requests (`Range: bytes=0-`) or unbounded requests $\ge 12$ MiB to $\sim 32$ KiB/s. Upstream fetches from Oracle VPS to GoogleVideo MUST use **bounded 8 MiB chunks** (e.g. `bytes=0-8388607`, then `bytes=8388608-16777215`), which stream at full line speed (6–10 MB/s).

### Principle 4: Predictive Prefetch for Sub-100ms TTFB
When the current playing track has 15–20 seconds remaining, the frontend fires:
`POST /api/prefetch { currentVideoId, nextVideoId, clientCodecHint }`
The VPS resolves the track and buffers the first 8 MiB chunk. When the user skips or the track naturally finishes, Track $N+1$ begins playing immediately from the pre-warmed memory buffer.

### Principle 5: Client-Aware Codec Negotiation
- **Safari / iOS:** Prefers AAC (`itag 140`, `audio/mp4`, 128 kbps).
- **Chrome / Firefox / Android:** Prefers Opus (`itag 251`, `audio/webm`, 160 kbps).

---

## 4. API Contract

| Endpoint | Method | Role | Caching Strategy |
| :--- | :--- | :--- | :--- |
| `/api/search` | `GET` | Autocomplete & song search | Cloudflare Edge Cache (10 min TTL) |
| `/api/resolve` | `GET` | Song metadata & stream URL descriptor | Cloudflare Edge Cache for metadata (short TTL); VPS single-flight |
| `/api/stream/:videoId` | `GET` | Audio stream relay with Range support | Never cached at edge; 8 MiB chunked upstream fetch |
| `/api/prefetch` | `POST` | Pre-warm next queue item | Fire-and-forget; warms 8 MiB in VPS buffer |
| `/api/health` | `GET` | System health & profile scores | Real-time diagnostics |

---

## 5. Security & Account Protection (Oracle Cloud)

1. **SSRF Hard Lock in Caddy:**
   The stream relay endpoint strictly verifies that upstream target URLs belong to `*.googlevideo.com`. Any other host is rejected with HTTP 403 before leaving the VPS. This prevents the server from ever functioning as an open proxy.
2. **Firewall Restriction:**
   The Oracle VPS only accepts incoming traffic on ports 80 and 443 from Cloudflare's published IP ranges. Direct access to the VPS IP is dropped at the firewall.
3. **Per-Friend API Keys:**
   Requests carry an `X-API-Key` header with per-key rate limits and daily bandwidth quotas.

---

## 6. Migration Roadmap

- [x] Revert non-functional client-side SAPISID hacks from the frontend.
- [ ] Initialize Oracle Cloud ARM A1 instance with Docker Compose and Caddy.
- [ ] Configure Cloudflare DNS (`api.yourdomain.com`) with Orange Cloud proxying.
- [ ] Implement the VPS Node.js Resolver + 8 MiB Range Relay service.
- [ ] Point Streamify frontend to the Cloudflare-proxied VPS endpoints.
- [ ] Enable queue prefetch hooks in `web-player-engine.tsx`.
