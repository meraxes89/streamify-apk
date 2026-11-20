# Streamify `/server` — Cross-Platform Modularization Plan

**Status:** implementation plan / draft.
**Scope:** `/server` only. The Compose Multiplatform `wasmJs` UI remains the only frontend. Feishin, the whole `front-end/` tree, the Cloudflare relay lane, and the GitHub Pages static-demo lane are rejected.

---

## 1. Executive decision

Build a **single-process, cross-platform modular monolith**:

- Node.js 20+ (Node 24 preferred).
- Fastify 5 for HTTP.
- Pure-JavaScript dependencies only — no native addons, no `sharp`, no `better-sqlite3`, no platform-specific binaries.
- Four vertical slices: `catalog`, `stream`, `lyrics`, `thumb`.
- A lean `lib/` for cache, single-flight, HTTP, concurrency, metrics, logging, range parsing.
- A `platform/` layer for data directories, profiles, wake-lock, and disk checks.
- One launcher/supervisor that runs on Termux, Linux, Windows, and macOS.

Hard rules:

- No microservices.
- No Redis.
- No Node cluster on day one.
- No Go rewrite.
- Zero media bytes in the JS heap.
- Disk-backed write-through media cache, relying on the OS page cache for hot bytes.
- Strict audio-only playback. No video bytes over room Wi-Fi.
- The existing `/api/yt/*` and `/api/stream` contract stays stable for the Compose UI.

---

## 2. Current baseline

`server/index.js` is currently **1,557 lines** and is the god object:

- process bootstrap and `app.listen`
- CORS / Private Network Access headers
- static hosting for `server/public`
- `/skin` mount and legacy `/streamify-apk` redirect
- SPA fallback
- YouTube Music home/search/suggest/browse/next
- LRCLIB lyrics
- thumbnail proxy
- stream resolution across client profiles
- range-capable byte relay
- descriptor cache and single-flight resolve
- prefetch buffer
- generic `/r` proxy
- `/ytm/meta` edge metadata
- `/api/resolve`, `/api/prefetch`, `/api/search`
- health/capabilities JSON

At analysis time it was running as `node server/index.js` from the repo root on `0.0.0.0:3000`.

### 2.1 Live frontend contract

The Compose wasm UI is served from `server/public/` and calls same-origin relative paths:

| Frontend call | Server route |
|---|---|
| Home | `GET /api/yt/home` |
| Search | `GET /api/yt/search?q=` |
| Suggestions | `GET /api/yt/suggest?q=` |
| Album / playlist / artist | `GET /api/yt/browse?id=` |
| Queue / radio | `GET /api/yt/next` |
| Lyrics | `GET /api/yt/lyrics?track=&artist=&duration=` |
| Images | `GET /api/yt/thumb?url=` |
| Audio | `GET /api/stream?id=` / `/api/stream/:videoId` |
| Static UI | `/`, `/composeApp.js`, `*.wasm`, `/composeResources/*` |

`server/public-feishin/` is an orphaned Feishin build. The server has no route that serves it.

### 2.2 New queue API

The latest commit added:

```http
GET /api/yt/next?videoId=<id>
GET /api/yt/next?playlistId=<id>&continuation=<token>
GET /api/yt/next?videoId=<id>&playlistId=<id>&continuation=<token>
```

Response:

```json
{
  "tracks": [
    {
      "videoId": "…",
      "title": "…",
      "artists": [{ "id": "…", "name": "…" }],
      "album": { "id": "…", "name": "…" },
      "duration": "3:42",
      "durationSeconds": 0,
      "thumbnails": [{ "url": "…", "width": 120, "height": 120 }],
      "year": "2024"
    }
  ],
  "continuation": "…"
}
```

Consumers in the Compose wasm frontend:

- `FakeSongRepository.getContinueTrack` — playlist/radio continuation.
- `FakeSongRepository.getRelatedData` — related/radio queue from `videoId`.
- `FakeSongRepository.getRadioFromEndpoint` — endpoint-driven radio.

Current implementation issues:

- Thumbnails are made absolute with `http://${req.headers.host}` — wrong scheme behind TLS, trusts the Host header, and breaks non-HTTP deployments.
- No validation of `videoId`, `playlistId`, or `continuation`.
- No cache, no single-flight, no negative cache.
- No timeout or retry policy.
- No tests for root queue, continuation, related, or malformed inputs.
- Error response shape is ad hoc.
- Queue parsing is inline in the god object.

### 2.3 Build / deploy path

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
# output:
composeApp/build/dist/wasmJs/productionExecutable/
# serve:
copy that tree into server/public/   # gitignored
./start-termux.sh
```

There is currently no script that performs the build-and-copy step. `start-termux.sh` only warns when `server/public/index.html` is missing.

---

## 3. Goals

1. Run the same server package on:
   - Android/Termux arm64, low/mid/high RAM
   - Linux x64/arm64
   - Windows x64/arm64
   - macOS x64/arm64
2. Keep the Compose wasm API contract stable.
3. Make the server fast and responsive on a room LAN:
   - cached metadata p95 < 50 ms
   - cold metadata p95 < 800 ms
   - stream TTFB p95 < 1.5 s cold, < 200 ms warm
   - 25–30 concurrent audio streams comfortable on mid Android
   - 50 concurrent audio streams as the hard design ceiling for high-end hardware
4. Make the server safe:
   - no open proxy
   - no video bytes for audio
   - no media bytes in the JS heap
   - bounded concurrency
   - optional room PIN
5. Make it operationally boring:
   - one command to start on every platform
   - one build artifact
   - no native toolchain on the target device
   - clear runbooks for Termux, Linux, Windows, macOS.

## 4. Non-goals

- No Feishin or `front-end/` revival.
- No Cloudflare Worker dependency.
- No microservices, Redis, Kubernetes, or cluster.
- No Go or Rust rewrite.
- No whole-track buffering.
- No muxed video fallback for audio playback.
- No SABR descriptor handed to a progressive range gateway.
- No `tsc` build on a phone.
- No hardcoded `/data/data/com.termux/...` paths.

---

## 5. Portability matrix

| Platform | Launch | Data dir | Profile | Notes |
|---|---|---|---|---|
| Termux/Android arm64 | `bin/streamify-server.mjs` or `start-termux.sh` | `$HOME/.cache/streamify/server` | low / balanced / high by RAM | optional `termux-wake-lock`; phantom-process runbook; battery unrestricted |
| Linux x64/arm64 | `npm start` or systemd | `$XDG_CACHE_HOME/streamify/server` or `~/.cache/streamify/server` | auto by RAM/CPU | `ulimit -n` in service; optional Docker |
| Windows x64/arm64 | `npm start` / `start.ps1` | `%LOCALAPPDATA%\Streamify\server` | auto by RAM/CPU | firewall prompt; file locking; short hashed paths |
| macOS x64/arm64 | `npm start` or launchd | `~/Library/Caches/Streamify/server` | auto by RAM/CPU | no native deps |
| Low-end Android (2–3 GB) | Termux | app-private cache | low | 8–12 streams, 256 MB disk, 192 MB heap |
| Mid Android (4–6 GB) | Termux | app-private cache | balanced | 16–32 streams, 512 MB–1 GB disk, 256–384 MB heap |
| High-end Android/desktop (8 GB+) | native/Termux | platform cache | high | 32–64 streams, 1–2 GB disk, 512–768 MB heap |

Profiles are selected by:

- `STREAMIFY_PROFILE=auto|low|balanced|high`
- total RAM
- CPU count
- free disk
- `process.platform`
- Termux detection via `TERMUX_VERSION` or `PREFIX`

Profile values are ceilings, not promises. The launcher can lower them further if free disk or memory is insufficient.

---

## 6. Architecture

```text
server/
  bin/
    streamify-server.mjs        # cross-platform launcher/supervisor
  src/
    index.ts                    # process bootstrap
    app.ts                      # createApp(deps)
    config.ts                   # env + CLI validation
    platform/
      profile.ts                # RAM/CPU/disk -> limits
      data-dir.ts               # platform data/cache paths
      wake-lock.ts              # optional Termux wake-lock, no-op elsewhere
      disk.ts                   # free-space checks, atomic writes
    lib/
      http.ts                   # undici pools, timeouts, retries
      cache.ts                  # bounded metadata LRU
      single-flight.ts
      semaphore.ts
      metrics.ts
      logger.ts
      range.ts                  # single-range parsing, 206/416/HEAD
      errors.ts
    slices/
      catalog/
        routes.ts               # home, search, suggest, browse, next
        service.ts
        ytm-client.ts
        mappers.ts
        queue.ts                # /api/yt/next
        cache.ts
      stream/
        routes.ts
        service.ts
        resolver.ts
        profiles/               # android, ios, visionos, vr, web
        format-selector.ts
        write-through-cache.ts  # disk-backed coalesced stream files
        gateway.ts              # range relay / reader
      lyrics/
        routes.ts
        service.ts
        lrclib-client.ts
      thumb/
        routes.ts
        service.ts
        allowlist.ts
  test/
    unit/
    integration/
    cross-platform/
    load/
  public/                       # generated Compose wasm build, gitignored
  package.json
  README.md
  ARCHITECTURE_PLAN.md
```

Boundaries:

- Routes do HTTP only.
- Services orchestrate use cases.
- Clients own upstream I/O and timeouts.
- Mappers are pure.
- `lib/` has no domain knowledge.
- `createApp(deps)` is the only composition root.
- No global singletons.

---

## 7. Cross-platform runtime

### 7.1 Launcher / supervisor

`bin/streamify-server.mjs` is the single entrypoint on every platform:

1. Resolve profile and data directory.
2. Compute `--max-old-space-size` from the profile.
3. Acquire `termux-wake-lock` only when running under Termux and the binary exists.
4. Spawn `dist/index.js` with `process.execPath`.
5. Forward `SIGINT`/`SIGTERM`.
6. Restart on crash with capped exponential backoff.
7. Release the wake lock on exit.

This avoids shell-specific logic and works from `npm start`, systemd, launchd, Task Scheduler, or Termux.

### 7.2 Data directory rules

- `STREAMIFY_DATA_DIR` override wins.
- Windows: `%LOCALAPPDATA%\Streamify\server`.
- macOS: `~/Library/Caches/Streamify/server`.
- Linux: `$XDG_CACHE_HOME/streamify/server` or `~/.cache/streamify/server`.
- Termux: `$HOME/.cache/streamify/server` (app-private).
- Create with `0o700`.
- Never write inside the repo or next to `dist/`.

### 7.3 File-system portability

- Use `path.join`, never string-concatenated separators.
- Hash cache keys with SHA-256; use lowercase hex filenames.
- Keep paths short and flat; avoid Windows `MAX_PATH`.
- Write to a temp file in the same directory, then `rename`.
- Windows cannot delete open files: close handles before GC; retry `EBUSY`/`EPERM` with backoff.
- Case-insensitive file systems: never rely on case to distinguish cache keys.
- Handle `ENOSPC` by disabling new disk cache writes and serving through.
- Check free disk before enabling the disk cache; shrink or disable under pressure.

### 7.4 Network

- Default bind: `127.0.0.1`.
- Room mode: `--lan` / `STREAMIFY_LAN=1` -> `0.0.0.0`.
- Print the LAN URL and optional QR code.
- Windows: document the firewall prompt.
- Termux: document Wi-Fi double-hop; recommend USB-C Gigabit Ethernet above ~20 devices.
- Never expose to the public internet.

### 7.5 Android/Termux runbook

- `termux-wake-lock`.
- Battery optimization disabled for Termux.
- Android 12+ Phantom Process Killer: document `max_phantom_processes` / battery-unrestricted setup.
- Foreground notification recommended.
- Use app-private storage, not `/sdcard`, unless the user grants access.
- Low-RAM profile automatically reduces streams, caches, and heap.

---

## 8. API contract freeze

Freeze these before refactoring:

| Route | Contract |
|---|---|
| `GET /api/health` | liveness, version, profile, uptime |
| `GET /api/capabilities` | capability list |
| `GET /api/yt/home` | `{ items, continuation? }` |
| `GET /api/yt/search?q=` | `Song[]` |
| `GET /api/yt/suggest?q=` | `string[]` |
| `GET /api/yt/browse?id=` | artist/album/playlist object |
| `GET /api/yt/next` | `{ tracks, continuation }` |
| `GET /api/yt/lyrics` | LRCLIB synced/plain shape |
| `GET /api/yt/thumb?url=` | `image/*` bytes |
| `GET /api/stream` | 206/200/416/HEAD, `Content-Range`, `Content-Length` |

Contract tests:

- Snapshot JSON with volatile fields removed.
- Queue root, continuation, related, and malformed inputs.
- Safari `Range: bytes=0-1`.
- `HEAD`.
- Multi-range behavior: serve first range or 200, never misuse 416.
- Thumbnail content type and size cap.
- Error shape for upstream failure.

Legacy routes to remove after tests pass:

- `ALL /r`
- `GET /ytm/meta`
- `GET /api/resolve`
- `POST /api/prefetch`
- `GET /api/search`
- `/skin`
- `/streamify-apk`
- `express.raw({ type: '*/*' })`

---

## 9. Queue module (`/api/yt/next`)

Create `slices/catalog/queue.ts`:

- `QueueService.getQueue({ videoId, playlistId, continuation, gl })`
- `YtmNextClient` — POST to `music.youtube.com/youtubei/v1/next`.
- `QueueMapper` — `playlistPanelVideoRenderer` -> `Track`.
- `QueueCache` — TTL + single-flight.

Rules:

- If `playlistId` is absent and `videoId` is present, derive `RDAMVM${videoId}`.
- Validate `videoId` as 11 chars `[A-Za-z0-9_-]`.
- Validate `playlistId` length/prefix.
- Cap continuation length.
- Timeout 8 s.
- Retry once on 5xx/network; never retry 4xx.
- Cache root queue 5 min; continuation 10 min.
- Cache key includes `gl`, `hl`, and the continuation token.
- Negative cache 30 s.
- Single-flight per key.
- Return relative thumbnail URLs, not Host-derived absolute URLs.
- If an absolute URL is required, use `PUBLIC_BASE_URL` config, never `req.headers.host`.
- Normalize track fields to match search/browse:
  `videoId`, `title`, `artists[]`, `album`, `duration`, `durationSeconds`, `thumbnails[]`, `year`.
- Metrics: `queue_requests_total`, `queue_cache_hits_total`, `queue_upstream_seconds`, `queue_errors_total`.
- Tests with recorded YTM fixtures for root, continuation, related, empty, and malformed responses.

Future optional parameters (do not break the current contract):

- `limit`
- `radio=0|1`
- `gl`/`hl` overrides

---

## 10. Stream engine

### 10.1 Resolver

Typed descriptor:

```ts
type StreamDescriptor =
  | { kind: 'progressive'; url: string; itag: number; mime: string; contentLength: number; expiresAt: number; profile: string }
  | { kind: 'sabr'; serverAbrUrl: string; expiresAt: number; profile: string }
  | { kind: 'unavailable'; reason: string; retryable: boolean };
```

- Ordered profiles with health scores.
- Per-profile timeout ~2.5 s; total budget ~8 s.
- Circuit breaker on repeated 403/429/5xx.
- Single-flight per `videoId`.
- Negative cache.
- Descriptor cache until `expiresAt - 5 min`.

### 10.2 Format policy

1. Progressive audio-only (Opus 251 / AAC 140).
2. Progressive muxed itag 18 only if explicitly allowed by config.
3. SABR only if the gateway supports SABR.
4. Otherwise typed `AUDIO_ONLY_UNAVAILABLE` / `SABR_UNSUPPORTED`.

Default for room deployments: **strict audio-only**. Never send video bytes over room Wi-Fi. If only muxed is available, reject or demux the audio track with a lightweight streaming MP4 demuxer (no transcoding).

### 10.3 Disk-backed write-through cache

Do not use fixed-size in-memory segments.

State per `(videoId, itag)`:

```text
IDLE -> RESOLVING -> OPENING -> STREAMING -> COMPLETE
                                   \-> FAILED
```

- One upstream writer streams sequentially into a disk file.
- Writer updates an atomic `bytesAvailable` watermark and notifies waiters.
- Readers open the file and tail from byte 0 immediately.
- First chunk 256 KiB for TTFB and header/probe requests.
- Subsequent chunks 1–2 MiB, using googlevideo `&range=start-end` semantics when supported.
- Exact `contentLength` is required for `206` correctness.
- `Range: bytes=0-1` must return `206`, `Content-Range: bytes 0-1/<total>`, `Content-Length: 2`.
- Multi-range: serve only the first range, or ignore `Range` and return `200`.
- Descriptor expiry: re-resolve once and continue.
- Disk cache: 256 MB / 512 MB / 1–2 GB by profile; ring/LRU eviction.
- Zero media bytes in the JS heap; rely on OS page cache.
- Handle `ENOSPC` by disabling writes and continuing as a pass-through.

### 10.4 Concurrency

- Global active stream semaphore by profile (e.g., 8/16/32/64).
- Per-IP stream cap (e.g., 3).
- Queue timeout (e.g., 5 s) -> `503` + `Retry-After`.
- Metadata in-flight cap (e.g., 8/16/32).
- Per-IP metadata rate limit.
- Abort upstream on client disconnect.
- Backpressure via `stream.pipeline`; never buffer whole media.

### 10.5 Prefetch

- Next track descriptor + first disk chunk only.
- Disk-backed, not RAM-backed.
- Global disk budget and per-client one-track limit.
- Drop prefetch under load.

---

## 11. Caching

| Data | TTL | Storage |
|---|---|---|
| Home | 2–5 min | metadata LRU, stale-while-revalidate |
| Search | 10 min | metadata LRU |
| Suggest | 24 h | metadata LRU |
| Browse | 15–30 min | metadata LRU |
| Queue root | 5 min | metadata LRU + single-flight |
| Queue continuation | 10 min | metadata LRU + single-flight |
| Lyrics | 7 d | metadata LRU |
| Thumbnails | 7 d | disk + HTTP cache |
| Stream descriptor | until expiry − 5 min | metadata LRU |
| Negative results | 1–5 min | metadata LRU |
| Media bytes | descriptor lifetime | disk only |

Metadata LRU limits: 8 / 16 / 32 / 64 MB by profile. Never store media bytes there.

---

## 12. Security

- Same-origin production: remove CORS headers entirely.
- Optional room PIN:
  - 6-digit PIN printed in the terminal.
  - `POST /api/auth/join` -> `HttpOnly; SameSite=Strict; Path=/api` cookie.
  - Works with `<audio>` and `<img>` without query tokens.
  - `ROOM_PIN` config flag; development remains zero-friction when unset.
- Remove `/r`.
- Thumbnail proxy:
  - allowlist `*.googleusercontent.com`, `*.ggpht.com`, `i.ytimg.com`
  - `maxRedirections: 0`
  - resolve DNS and block loopback/private/link-local CIDRs
  - enforce `Content-Type: image/*`
  - cap response at 2 MB
- Body limit 64 KB JSON; no raw 50 MB parser.
- Request/header/connection timeouts.
- Redact signatures and tokens in logs.
- Bind LAN only when explicitly requested.

---

## 13. Observability

- `GET /api/health` — liveness, version, profile, uptime.
- `GET /api/ready` — data dir writable, disk free, event-loop lag.
- `GET /api/metrics` — Prometheus text or JSON.

Metrics:

- `process_resident_memory_bytes`
- `process_heap_bytes`
- `external_memory_bytes`
- `event_loop_lag_seconds`
- `stream_active`
- `stream_bytes_total`
- `stream_ttfb_seconds`
- `stream_errors_total{reason}`
- `cache_hits_total{kind}`
- `cache_misses_total{kind}`
- `queue_requests_total`
- `queue_upstream_seconds`
- `upstream_requests_total{profile,status}`
- `disk_cache_bytes`
- `disk_free_bytes`

Logs: pino, request ID, route, status, duration, cache hit, profile, bytes, redacted URLs.

---

## 14. Build, packaging, deployment

### 14.1 Server build

- TypeScript source.
- CI/desktop: `npm run build` (`tsc` or esbuild) -> `dist/`.
- Termux/low-end: run prebuilt `dist/`; do not run `tsc` on the phone.
- Node 24 native type stripping or `tsx` is allowed for development only.
- `npm ci --omit=dev` on the target.
- `npm pack` produces a portable tarball.

### 14.2 Static UI build

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
rsync -a --delete composeApp/build/dist/wasmJs/productionExecutable/ server/public/
# precompress both formats
find server/public -type f \( -name '*.js' -o -name '*.wasm' -o -name '*.html' -o -name '*.css' \) \
  -exec gzip -9 -k {} \; \
  -exec brotli -q 11 -k {} \;
```

- `.gz` is mandatory over LAN HTTP; `.br` is a bonus where advertised.
- Hashed assets: `Cache-Control: public, max-age=31536000, immutable`.
- `index.html` / `composeApp.js`: `no-cache` or ETag.
- Note: plain LAN HTTP disables `SharedArrayBuffer`, Service Workers, and `crypto.subtle`. If Compose wasmJs needs threads/COOP+COEP, add a LAN TLS strategy later.

### 14.3 Platform launchers

- `npm start` -> `bin/streamify-server.mjs`.
- `start-termux.sh` -> wrapper for compatibility.
- `start.ps1` -> Windows wrapper.
- systemd unit example for Linux.
- launchd plist example for macOS.
- Windows Task Scheduler / service notes.

### 14.4 Profiles

| Profile | RAM | Heap | Metadata LRU | Disk cache | Max streams |
|---|---:|---:|---:|---:|---:|
| low | 2–3 GB | 192 MB | 8 MB | 256 MB | 8–12 |
| balanced | 4–6 GB | 256–384 MB | 16–32 MB | 512 MB–1 GB | 16–32 |
| high | 8 GB+ | 512–768 MB | 32–64 MB | 1–2 GB | 32–64 |

`STREAMIFY_PROFILE=auto` selects by total RAM, CPU count, and free disk.

---

## 15. Migration phases

### Phase 1 — Contract freeze & P0 triage

- Add `node:test` snapshots for all live `/api/yt/*` routes, including `/api/yt/next`.
- Add range tests: 206, HEAD, `bytes=0-1`, multi-range, 416 correctness.
- Delete `/r`.
- Fix `express.raw` body handling and the SABR leak.
- Fix Host-derived thumbnail URLs; return relative URLs.
- Add a queue test fixture set.

### Phase 2 — Fastify cutover + cross-platform scaffold

- Add `bin/streamify-server.mjs`, profile detection, data-dir resolution, launcher.
- Add `config.ts`, `platform/`, `lib/`.
- Create Fastify app and four vertical slices.
- Port routes one at a time behind the same paths.
- Add TypeBox validation.
- Add `undici` pools, timeouts, retries.
- Add per-IP/global semaphores.
- Add `.gz`/`.br` static build.
- Cut over cleanly; keep the old `index.js` only as a temporary fallback.

### Phase 3 — Disk-backed stream engine

- Implement write-through cache state machine.
- Implement googlevideo chunked fetcher and descriptor expiry re-resolve.
- Implement exact range responses and WebKit compliance.
- Add metrics and health/readiness.
- Enforce strict audio-only policy.

### Phase 4 — Security & cross-platform packaging

- Add optional PIN-to-cookie auth.
- Harden thumbnail proxy (redirects, IP blocklist, content type, size cap).
- Remove CORS in production.
- Add Windows/Linux/macOS launch scripts and service examples.
- Add Termux runbook (wake lock, phantom process killer, Ethernet recommendation).
- Add `npm pack` and cross-platform CI.

### Phase 5 — Load verification

- Synthetic range-client tests at 10 / 30 / 60 concurrent streams.
- Measure RSS, external memory, event-loop lag, disk cache hit rate, upstream 403/429, Wi-Fi airtime.
- Test low/balanced/high profiles.
- Run 60 minutes to catch thermal throttling and lmkd.
- Publish capacity numbers and recommended caps.

---

## 16. Test matrix

| Layer | Tests |
|---|---|
| Unit | mappers, range parser, profile selection, data-dir, queue mapper, cache TTL |
| Integration | Fastify routes with `undici` MockAgent; local upstream fixture; static files; range 206/HEAD/416 |
| Contract | JSON snapshots for home/search/browse/next/lyrics; Safari bytes=0-1 |
| Cross-platform CI | Ubuntu, Windows, macOS; Node 20/22/24 |
| Termux | manual smoke: start, health, home, search, next, stream, disk cache, wake lock |
| Load | 10/30/60 streams; RSS, external memory, event-loop lag, 403/429, disk, Wi-Fi |
| Failure | upstream timeout, 403/429, expired descriptor, ENOSPC, client abort, slow reader |

---

## 17. Acceptance criteria

- `npm ci && npm run build && npm start` works on Linux, Windows, macOS, and Termux.
- No native addons in the dependency tree.
- Compose wasm contract unchanged.
- `/api/yt/next` works for root radio, continuation, and related tracks.
- No media bytes in the JS heap; steady-state RSS within profile target.
- 30 concurrent audio streams on mid Android; 50 on high-end hardware as a tested ceiling.
- No open proxy; optional PIN cookie auth.
- `.gz` and `.br` static assets served correctly over LAN HTTP.
- Build-and-copy script exists for the Compose wasm distribution.
- Cross-platform CI green.

---

## 18. Open decisions

1. Is 50 concurrent devices the hard ceiling, or do we design for more on desktop?
2. Independent playback baseline, or a shared/synced room queue?
3. Strict audio-only, or allow muxed fallback behind a config flag?
4. Is a PIN login screen acceptable in the Compose UI, or should auth remain server-side/optional?
5. Do we ship a Docker image for Linux/NAS, or keep `npm start` only?
6. Do we want a Windows service wrapper, or is Task Scheduler enough?

---

## 19. Immediate next step

This plan lives at `server/ARCHITECTURE_PLAN.md`.

1. Start Phase 1: contract tests, queue tests, `/r` deletion, raw-body fix, thumbnail URL fix.
2. Then scaffold Phase 2 without changing the Compose API.
3. Keep `server/ARCHITECTURE_PLAN.md` updated as the implementation evolves.
