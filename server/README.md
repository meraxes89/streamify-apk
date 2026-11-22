# server/ — Stream relay and content-addressed web delivery

A Fastify 5 application ("streamify-relay" v3.0.0, Node 20+) that proxies
the YouTube catalog to clients, streams audio with range support, and serves
the Compose-wasmJs web client from a content-addressed, Merkle-verified
bundle. The legacy Express entrypoint (`index.js`, `npm run start:legacy`)
remains available but is not the production path.

## Run

```bash
cd server
npm ci
npm start          # src/boot/streamify.mjs: hardware-tier probe, V8 heap flags, re-exec -> bin/streamify-server.mjs
# listens on 0.0.0.0:${STREAMIFY_PORT:-${PORT:-3000}}
# optional: ROOM_PIN / STREAMIFY_PIN protects /api/* behind POST /api/auth/join
npm test           # unit (node --test) then contract suite
```

## Endpoints

| Route | Behavior |
|---|---|
| GET /api/health | Event-loop lag p50/p90/p99 plus admission/coalescer/cache/tier telemetry |
| GET /api/capabilities | 25 capability strings |
| GET /api/room/list, /api/room/:roomId | Jam room catalog |
| WS /api/room/:roomId/sync | Hand-rolled RFC 6455 room sync (no ws dependency) |
| GET /api/yt/home, /search, /next, /suggest, /browse | InnerTube catalog |
| GET+POST /api/playlist/scrape | External playlist scraper (Spotify/Apple Music) |
| GET /api/yt/lyrics | LRCLIB lyrics |
| GET /api/yt/thumb | Thumbnail proxy (allowlist-guarded) |
| GET/HEAD /api/stream, /api/stream/:videoId | Stream proxy: resolves via InnerTube, enforces the upstream host ends with `.googlevideo.com` (403 otherwise), serves HTTP 206 with Content-Range from a 256 KiB sparse block cache |
| GET /api/stream/:videoId/manifest.mpd | DASH MPD (Media3/Shaka), SABR path with PO-token minting |
| POST /api/stream/prefetch[/cancel], GET .../status | Two-stage prefetch: stage 1 descriptor, stage 2 warms exactly 256 KiB |
| GET / (static) | Serves `public/` (symlink -> `bundles/<sha256>`); `.wasm`/`.js` immutable-cached 1 year |

Admission control and a single-flight coalescer protect the upstream: cache
hits consume zero permits, saturation returns 503 with `Retry-After`,
timeouts 504; a throttle watchdog evicts cached URLs that drop below
~50 kbps.

## Content-addressed bundle delivery (PR #250)

`scripts/update-bundle.mjs` runs 3 s after boot (disable with
`STREAMIFY_DISABLE_AUTO_UPDATE=1`):

1. Downloads the release asset `Streamify-<tag>-web-wasm.zip` from GitHub
   Releases (built by `_ci-wasm.yml` via
   `:composeApp:wasmJsBrowserDistribution` — it is a Kotlin/Compose browser
   bundle, not Rust-to-WASM).
2. Verifies the zip SHA-256 against the release `SHA256SUMS` asset; zip-slip
   and size-guarded extraction; every `.wasm` must carry the `\0asm` magic.
3. Publishes to `bundles/<sha256>/` with a `.bundle.json` per-file SHA-256
   map and Merkle root; `public` flips via an atomic symlink
   (symlink -> tmp -> rename). Keeps the last 3 bundles, never deleting
   current/previous (rollback safety); state in `.state/manifest.json`.
4. `enforceBundleGate()` verifies WASM-magic and Merkle tree, supporting
   `{ allowDegraded: true }` on cold start with an auto-updating placeholder UI,
   local build auto-detection, and immediate background release fetch.

`pubkey.pem` holds a raw 32-byte Ed25519 public key.
`verifyManifestSignature()` (Ed25519 over the manifest) is exported and
covered by unit tests (tampered-payload and corrupted-signature cases); in
the current live path, `runBundleUpdate()` authenticates via SHA256SUMS +
Merkle — wiring the signature check into the live path is follow-up work.

## Measured performance (BENCHMARK_REPORT.md, 2026-11-20)

| Metric | Loopback | Wi-Fi (802.11) |
|---|---|---|
| Peak throughput | 792.8 req/s (c=20); 759.5 req/s at c=100, 0 errors | 80.7 req/s (c=20) -> 15.8 (c=100), 2.1 % drop |
| Latency P50 | 1.22 ms | 26.75 ms |
| Cold upstream resolve | 128 ms floor (was ~650 ms; 50 ms Happy Eyeballs + 2 min DNS cache) | - |
| Warm cache | ~30.7x speedup, sub-21 ms per track | - |
| Event-loop lag under load | P50 10.60 ms, P99 16.07 ms | - |
| Memory (heap / RSS) | 20.4 MB / 90.1 MB after 4,000+ requests | - |

Search: average 1,016 ms per 20-track query (P50 774.8 ms). Prefetch stage 2
warms exactly 262,144 bytes. Burst coalescing: 100 % single-flight (zero
duplicate upstream calls).

---
Verified against commit 7c60ae82. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 7c60ae82b6459f17876bcdb5d0f168e44c816c5b -->
