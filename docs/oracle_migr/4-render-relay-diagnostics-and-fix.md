# Render Relay Diagnosis & Edge Gateway Implementation

## 1. Problem Diagnosis Summary
During live testing with `https://streamify-relay.onrender.com` against the frontend, the following symptoms occurred:
1. **Search & Metadata Failure:** Searching "11k" returned only tracks stored locally in Supabase. External search results and thumbnails never appeared.
2. **Playback Stalled:** Tracks could not be resolved or played.
3. **Render Live Logs Silent:** Live logs on Render.com showed no activity during user interactions.

## 2. Root Cause Analysis
- **Missing `/ytm/meta` Route:** The Streamify frontend routes all catalog, search (`endpoint=search`), and search suggestions (`endpoint=search-suggest`) to `/ytm/meta` against the configured relay (`metaBaseCandidate()`). In the initial deployment, `server/index.js` only defined `/api/search` and returned `404 Not Found` for `/ytm/meta`.
- **Missing `/r` Passthrough Proxy:** The frontend's fallback Innertube sessions (`ytFetch`), visitor token minters (`directBornVisitorData`), and SABR audio streaming (`sabrStreamPlayer.ts`) route cross-origin upstream calls to `${relayBase}/r?u=<target>`. The relay returned 404 for `/r`.
- **Express Logger Absence:** Standard Express does not log incoming HTTP requests to stdout. Without custom request logging middleware, Render's dashboard log remained empty even when requests arrived.
- **Datacenter Bot Wall & Architecture Separation:** YouTube's anti-bot system blocks datacenter IPs from resolving certain music tracks directly. Streamify's Architecture v3 cleanly separates this:
  - **Metadata & Catalog Plane:** Routed via `/ytm/meta` through YouTube Music `WEB_REMIX` client (which succeeds reliably from datacenter IPs).
  - **Direct Stream Relay:** Serves direct Opus/AAC streams with 8 MiB range chunking via `/api/stream/:videoId`.
  - **SABR Fallback:** When a track is SABR-only or bot-gated on datacenter IPs, the frontend's native client resolves SABR on the client device (residential IP) and relays chunk fetching through the relay's `/r` CORS proxy.

---

## 3. Implemented Fixes in `server/index.js`

### A. Live Request Logger Middleware
Logs method, URL, status code, and latency on every incoming request to stdout:
```javascript
app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
        console.log(`[${new Date().toISOString()}] ${req.method} ${req.originalUrl || req.url} ${res.statusCode} (${Date.now() - start}ms)`);
    });
    next();
});
```

### B. Universal CORS Passthrough Proxy (`/r`)
Handles `GET`, `POST`, and `OPTIONS` with raw buffer body forwarding and permissive CORS headers:
```javascript
app.all('/r', async (req, res) => {
    const targetUrl = req.query.u;
    // Forwards headers & body, strips hop-by-hop headers, applies Access-Control-Allow-Origin: *
});
```

### C. Metadata Edge Gateway (`/ytm/meta`)
- **`endpoint=search`:** Queries YouTube Music `WEB_REMIX`, normalizes top cards and shelf items, cleans prefixes (`Song • `, `Video • `), and returns conforming `{ cache: { state: "fresh" }, payload: { kind: "search", query, results } }`.
- **`endpoint=search-suggest`:** Queries `suggestqueries.google.com` and returns `{ cache: { state: "fresh" }, payload: { kind: "search-suggest", input, suggestions } }`.
- **Other endpoints (`chapters`, `lyrics`, `credits`, `counterpart`):** Returns `{ cache: { state: "fresh" }, missing: true }` so client fallbacks operate smoothly.

---

## 4. Live Verification Results on Render

| Test Case | Method & URL | Result | Verified Output |
| :--- | :--- | :--- | :--- |
| **Root Capabilities** | `GET https://streamify-relay.onrender.com/` | `200 OK` | `["8mib-range-stream", "mobile-vr-resolver", "prefetch", "ytm-meta", "neg-cache", "r-proxy"]` |
| **Live Search ("11k")** | `GET https://streamify-relay.onrender.com/ytm/meta?endpoint=search&q=11k` | `200 OK` | 12 results returned, top result `11K` by `Seedhe Maut` (`VEQ-XJWiQMM`) |
| **Search Suggestions** | `GET https://streamify-relay.onrender.com/ytm/meta?endpoint=search-suggest&q=11k` | `200 OK` | `["11k", "11k seedhe maut", "11kw", ...]` |
| **CORS Proxy (`/r`)** | `GET https://streamify-relay.onrender.com/r?u=...` | `200 OK` | Forwarded upstream with `access-control-allow-origin: *` |
| **Stream Relay (8 MiB)** | `GET https://streamify-relay.onrender.com/api/stream/dQw4w9WgXcQ` | `206 Partial Content` | `audio/webm; codecs="opus"`, Range bytes 0-1000/3433755 |
