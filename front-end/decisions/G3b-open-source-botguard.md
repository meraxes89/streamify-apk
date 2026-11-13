# G3b — The open-source BotGuard pivot (YouTube.js + BgUtils + ffmpeg.wasm)

**Date:** 2026-11-14 · **Branch:** `ws-w/p3b-open-source-botguard` · **Supersedes:** P3b-botguard-egress.md (the custom harness)

## The mandate

P3b's custom 3-Tier implementation still failed on real mobile hardware
(Brave/Android/5G: every rung `LOGIN_REQUIRED` / `UNPLAYABLE`). The
directive: stop maintaining custom reverse-engineered attestation and
InnerTube plumbing — migrate to the maintained open-source stack:

1. **LuanRT/BgUtils** (`bgutils-js`) — client-side PO token minting
2. **LuanRT/YouTube.js** (`youtubei.js`) — InnerTube session/client/stream
3. **LuanRT/kira** — reference patterns (browser wiring of 1+2)
4. **mtripg6666tdr/youtube-trusted-session-generator** — IP/ASN binding rules
5. **ffmpeg.wasm** — in-worker remux for element-hostile segmented formats

## What was removed

| File | Fate |
|---|---|
| `audio/botguard/mintHarness.ts` | **Deleted** — the srcdoc-iframe realm with 3-layer origin spoofing (`Document.prototype` getters, `boot.call(spoofedWindow)`, shadow-param eval) |
| `audio/botguard/mintIframe.ts` | **Deleted** — the iframe host + `waitForHarness` polling |
| `audio/botguard/mintCore.ts` | **Deleted** — the hand-rolled WAA wire (bg-session homepage challenge, interpreter fetch, GenerateIT) |
| vite `mint-harness` rollup input | **Removed** — no harness entry, default hashed filenames restored |
| `/bg-session` client usage | **Dropped** — visitorData now comes from the YouTube.js session (`sw.js_data` via relay); the relay endpoint stays (dormant, harmless) |

Research finding that justified the whole pivot: the official BgUtils
browser example minted from `localhost:5173` unpenalized — **no origin
spoofing is required**. The VM wants a real browser environment; a real
page is one. kira (the author's own reference app) runs BotGuard on the
main thread with zero realm tricks.

## What was built

### `lib/ytSession.ts` — the YouTube.js session layer

ONE `Innertube` instance (`youtubei.js/web` 18.3.0), bootstrapped through
the relay: `sw.js_data` (real visitorData) → `/youtubei/v1/config` →
`iframe_api` + `/s/player/<id>/player_es6…/base.js` (signatureTimestamp +
nsig/sig extraction). Session + player persist in IndexedDB
(`UniversalCache(true)`), so repeat visits skip the bootstrap legs.

- **`ytFetch`** — every youtubei.js request funnels through the relay's
  `/r?u=` passthrough (kira's rewrite pattern). `credentials: 'omit'`
  (youtubei.js forces `include` in browsers; the relay is ACAO:*), the
  browser-forbidden `user-agent` dropped (the relay owns UA policy per
  client), Request-carried bodies preserved.
- **The eval shim** — since v16 youtubei.js ships no JS interpreter and
  the browser platform's `eval` is a throwing stub. `Player.decipher`
  appends a `process(n, sp, s)` tail that returns `{sig, n}` directly, so
  `new Function(data.output)()` satisfies the contract (kira's pattern).
- **Why every leg through the relay** (the TSG lesson): `visitor_data` +
  `po_token` are IP/ASN-bound — the mint leg and the use leg must egress
  from one IP. The relay IS that single egress: challenge → GenerateIT →
  player → googlevideo. A direct browser mint (phone IP) would re-split
  the envelope against the relay-egressed player call — the P3b
  split-brain class of failure at a different seam.

### `audio/botguard/potMinter.ts` — the bgutils-js v4 mint (MAIN REALM)

The kira/official-example flow, no iframe: `getChallenge({fetchFunction:
ytFetch, requestKey})` → WAA **Create** on jnn-pa (inline interpreter —
no asset fetch, no CORS question) → inject as `<script id={interpreterHash}>`
(deduped) → `BotGuardClient.create` (VM on `window[globalName]`) →
`snapshot({webPoSignalOutput})` → WAA **GenerateIT** → `WebPoMinter.create`
→ `mintAsWebsafeString(binding)`.

- **Brave ladder kept:** primary snapshot → `skipPrivacyBuffer: true`
  retry (the measured P3b behavior) → `websafeFallbackToken` (GenerateIT's
  4th tuple element) → `createColdStartToken` (bgutils's local XOR).
- **Dual tokens kept:** player = videoId-bound (serviceIntegrityDimensions),
  session = visitorData-bound (`&pot=` per `GVS_BINDS_TO_VIDEO_ID` — the
  measured homepage experiment flag; re-verify monthly).
- **Token binding root:** the YouTube.js session's visitorData — the SAME
  value every player call carries.

### `audio/botguard/potSession.ts` — the manager (kept, hardened)

The P3b cache/session manager survives with one addition: a
**binding-drift guard** — a restored localStorage cache whose visitorData
no longer matches the live YouTube.js session is DROPPED (tokens minted
for another visitor poison the player call — split-brain prevention).

### `audio/resolve.ts` — the ladder (rewritten on youtubei.js)

```
1. VISIONOS   — yt.getBasicInfo(id, {client: 'VISIONOS'}) (id 101, the
                relay swaps the matrix UA); direct itag 140/251 URLs used
                AS-IS (P3b-proven); same-call hlsManifestUrl bonus.
2. WEB + POT   — yt.getBasicInfo(id, {client: 'WEB', po_token}) (the
                bgutils mint, videoId-bound player token); decipher with
                the GVS token appended (&pot=). SABR-only responses (the
                modern web-client reality: audio itags as URL-less
                descriptors) fall through to the response's own HLS
                manifest when elementCompat.
3. WEB_SAFARI  — raw player POST through the relay's X-Relay-UA
                (safari-mac) override + the rung-2 player token when the
                mint produced one (licensed playability needs the token
                on the player call regardless of UA; HLS manifests need
                no GVS token — the yt-dlp web_safari exception). Chromium
                engines consume the manifest via the remux lane.
```

Rung order changed from P3b (safari-hls now LAST): the licensed path
needs the mint first, and the safari rung reuses rung-2's token. The
failed-rungs ledger, honest verdict aggregation, and
`markRungFailed` rotation-escalation all survive unchanged.

### `audio/hls/hlsRemux.ts` — the segmented-format lanes

Chromium-class engines cannot play m3u8 in `<audio>`; the directive's
ffmpeg.wasm lane, plus a fast path discovered during verification:

- **fmp4-concat (fast path):** YouTube's VISIONOS-flavored HLS audio
  variants are fragmented MP4 — `[init][seg0][seg1]…` byte-concat IS a
  valid progressive fMP4 file (verified: element probe reads the full
  213.0s duration, 0.55 MiB, no remux cost, no 32 MB download).
- **ffmpeg-remux (the directive's lane):** MPEG-TS segments (the
  WEB_SAFARI flavor serves `seg.ts`) remux `-vn -c:a copy` inside the
  FFmpeg class's own dedicated Web Worker.
  - **Worker spawn fix (the 0.12 bundler trap):** the class spawns
    `type:'module'` workers; inside Vite's pre-bundled deps its
    `new URL('./worker.js', import.meta.url)` never resolves (silent
    hang). Fix: vendored the ESM worker trio
    (`ffmpeg-worker/{worker,const,errors}.js`, verbatim 0.12.15) loaded
    via `?worker&url` — Vite bundles the chain in BOTH dev and build.
  - **ESM core (not UMD):** module workers cannot `importScripts`;
    worker.js's fallback `await import(coreURL)` needs the Emscripten
    ES6 module — `@ffmpeg/core@0.12.10/dist/esm` (pinned).
  - **Hang guards:** 60s load budget, 120s exec budget (a stuck worker is
    recycled), tsconfig excludes the vendored dir (its
    `no-default-lib` directive would nuke the DOM lib program-wide).

### `lib/wasmEngine.ts` — the element-path hook

On a play intent for an `isHls` stream on a non-native-HLS engine:
remux → `blob:` URL → element load (bridge lane skipped for blobs —
they never go stale). A remux failure ledgers the rung (rotation
escalates) and parks the track honestly.

### Relay — functionally unchanged, marked

The G3b stack rides the SAME P3b surface (`/r` allowlist covers
`sw.js_data`, `/s/player/*`, `/api/jnn/*`, jnn-pa, googlevideo, manifests;
envelope pinning; UA matrix incl. client 101). The `/` capability echo
gains `g3b-oss-stack` for deployment verification. No functional relay
change was required.

## Verification matrix (headless Chromium, wrangler dev :8787)

| Probe | Result |
|---|---|
| YouTube.js session boot (sw.js_data + player JS via relay) | PASS — WEB 2.20261112.00.00, real visitorData, STS 20767 |
| VISIONOS resolve (element mode) | PASS — itag 140, full 213s (clen 3,449,447, matches P3b recon) |
| VISIONOS resolve (spine mode) | PASS — itag 251 opus |
| **BgUtils mint (main realm)** | **PASS — kind=live**, playerToken 120ch, sessionToken 700ch, 0.2s (localhost relay) |
| Licensed target 4NRXx6U8ABQ | Honest refusal — all rungs LOGIN_REQUIRED from the sandbox's flagged datacenter IP (sw.js_data literally labels it "Robot/Bot or Crawler"); the LIVE relay egress is a different reputation class — the phone is the real test |
| WEB+POT formats | SABR-only from datacenter (itags 140/249/250/251 present, URL-less) — the documented web-client reality; the HLS fallthrough covers it |
| HLS remux (fMP4 lane) | PASS — 38 segs, 0.55 MiB, element probe duration=213.0s |
| HLS remux (TS lane, app-observed) | The app fell into the WEB_SAFARI TS lane naturally (351 seg.ts fetches + in-app mint ran end-to-end) |
| ffmpeg.wasm load + exec | PASS — worker spawn, ESM core load, real ts→m4a remux |
| Production build | PASS — tsc strict + vite build; worker chunk emitted + classWorkerURL wired |
| App UI element path | Search → play exercised the full chain incl. auto-mint from the app (jnn-pa Create + GenerateIT observed in-page) |

## Known limitations / deferred

- **Datacenter egress reality (the bgutil README's warning):** "a PO
  Token does not bypass IP-based login restrictions (common on
  datacenter IPs)". The phone test against the LIVE Cloudflare relay is
  the acceptance bar; if licensed content still LOGIN_REQUIREDs there,
  the documented next lever is Tier 3 (app-shell residential egress).
- **Search stays on the Rust engine** (`search_music` via rustypipe):
  the resolver is fully TS/YouTube.js now (the Rust `resolve_stream`
  facade entry is dormant — nothing in the app calls it), and moving
  search onto the same YouTube.js session is a natural follow-up (one
  session envelope for search + playback).
- **SABR streaming** (kira's `googlevideo`/SabrStream patterns) is
  studied and documented but not adopted — progressive + HLS + remux
  covers the music use case; SABR is the future-proofing path if
  YouTube completes the progressive sunset.
- **The g3b-test.html page** is a dev-only verification harness (not a
  build input; `?relay=` points it at any worker) — kept for the phone
  smoke test.

## The five sources, honored

1. **BgUtils** — replaced the custom harness entirely (main realm, v4 API).
2. **YouTube.js** — owns session, clients (incl. VISIONOS 101), deciphering, PO-token attachment points.
3. **kira** — the fetchFunction relay pattern, main-thread BotGuard, cold-start-first strategy, the eval-shim shape.
4. **youtube-trusted-session-generator** — the IP/ASN single-egress rule that shapes the whole architecture.
5. **ffmpeg.wasm** — the TS remux lane, in its own Web Worker, with the bundler traps documented and fixed.
