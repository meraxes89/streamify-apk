# 3. Cloud Backend Deployment & Live Verification

## 1. Executive Summary & Status: LIVE

The **Architecture v3** Node.js 8 MiB Range Stream Relay and Multi-Profile Resolver has been successfully created, deployed, and live-verified on **Render.com** (Free Web Service tier, $0 cost, zero credit cards).

- **Service Name:** `streamify-relay`
- **Dashboard URL:** [Render Dashboard](https://dashboard.render.com/web/srv-dbulkdhsrm7s73f8u3o0)
- **Live Public Base URL:** `https://streamify-relay.onrender.com`
- **Commit:** `64fa10d25` on branch `feat/browser-sabr-player`
- **Region:** `oregon` (AWS us-west-2)

---

## 2. Verified Live Endpoints & Responses

### 2.1. Capability Echo (`GET /`)
```bash
curl -s https://streamify-relay.onrender.com/
```
```json
{
  "capabilities": [
    "8mib-range-stream",
    "mobile-vr-resolver",
    "prefetch",
    "ytm-meta",
    "neg-cache"
  ],
  "name": "streamify-relay",
  "status": "ok",
  "version": "3.0.0"
}
```

### 2.2. System Health (`GET /api/health`)
```bash
curl -s https://streamify-relay.onrender.com/api/health
```
```json
{
  "cache": { "prefetched": 0, "resolved": 1 },
  "memory": { "rss": 87494656, "heapUsed": 18456220 },
  "status": "ok",
  "uptime": 120,
  "version": "3.0.0"
}
```

### 2.3. Song Resolver (`GET /api/resolve?videoId=dQw4w9WgXcQ`)
- **Latency:** $\approx 1.2\text{ seconds}$
- **Response:**
```json
{
  "artist": "Rick Astley",
  "bitrate": 136544,
  "codecs": "opus",
  "contentLength": 3433755,
  "durationMs": 213000,
  "expiresAt": 1795008989260,
  "ok": true,
  "rangeSupport": true,
  "streamUrl": "https://streamify-relay.onrender.com/api/stream/dQw4w9WgXcQ",
  "title": "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
  "videoId": "dQw4w9WgXcQ"
}
```

### 2.4. 8 MiB Range Stream Relay (`GET /api/stream/:videoId`)
- **Request:** `Range: bytes=0-1000`
- **Response Headers:**
```http
HTTP/2 206
content-type: audio/webm; codecs="opus"
content-length: 1001
accept-ranges: bytes
access-control-allow-origin: *
access-control-expose-headers: Content-Range, Content-Length, Accept-Ranges
content-range: bytes 0-1000/3433755
```

### 2.5. Queue Prefetch (`POST /api/prefetch`)
- **Request:** `{"nextVideoId": "dQw4w9WgXcQ"}`
- **Response:** `{"ok": true, "prefetching": "dQw4w9WgXcQ"}`

### 2.6. Autocomplete Search (`GET /api/search?q=believer`)
- **Response:**
```json
{
  "ok": true,
  "suggestions": [
    "believer",
    "believer imagine dragons",
    "believer song",
    "believer lyrics"
  ]
}
```

---

## 3. How to Connect Frontend

### Method A: Streamify Settings Panel (Instant)
In the Streamify Web Player:
1. Open **Streamify Settings** $\rightarrow$ **Network & Relay**.
2. Set Relay Base URL to:
   ```
   https://streamify-relay.onrender.com
   ```
3. Click **Save** and **Test Relay** (Health badge will show green `ok`).

### Method B: Cloudflare Custom Domain CNAME
To mask Render behind your branded domain (`api.yourdomain.com`):
1. In Cloudflare DNS, add:
   - **Type:** `CNAME`
   - **Name:** `api`
   - **Target:** `streamify-relay.onrender.com`
   - **Proxy status:** Proxied (Orange cloud).
2. Set `window.STREAMIFY_RELAY_BASE = "https://api.yourdomain.com"`.
