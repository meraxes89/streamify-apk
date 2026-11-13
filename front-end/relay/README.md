# streamify-relay — P0 minimal CORS relay + P1 minting surface (W0.4 / W1.0)

A single-file Cloudflare Worker that acts as a deliberately **dumb, CORS-open relay** so the
Streamify web port (wasm Rust engine in the browser, deployed at
`https://meraxes89.github.io/streamify-apk/`) can reach YouTube's **innertube API** and the
**googlevideo** media CDN — plus, since P1 (W1.0), the **BotGuard/PO-token minting legs**
needed to unlock licensed music behind YouTube's datacenter bot wall.

## Why this exists

The browser-side engine calls `youtubei`/innertube endpoints and pulls audio bytes from
`*.googlevideo.com`. Both upstreams refuse cross-origin browser requests — they send **no
`Access-Control-Allow-Origin` headers**, so every `fetch` from the `github.io` origin dies with a
**CORS 403** before the wasm engine ever sees a byte. The only realistic P0 fix is a server-side
hop that re-issues the request and re-attaches CORS. This Worker is that hop, kept as dumb as
possible on purpose:

```
browser ──GET /r?u=<urlencoded-target>──▶ streamify-relay (Workers)
         ◀── upstream response, streamed, ACAO:* ──┘
```

## P1 / W1.0 — the minting surface

Licensed tracks (e.g. `4NRXx6U8ABQ`) answer the player endpoint with `LOGIN_REQUIRED`
("Sign in to confirm you're not a bot") from datacenter IPs. The unlock is a **PO token
minted by real browser hardware** — the BotGuard VM scores its environment, and
JSDOM/headless-environment mints measure as low-trust (they do NOT lift the wall;
verified 2026-11-12 with provider-recipe Node mints across every binding/client/IP
combination). So the mint runs **natively in the user's browser**, and every network leg
of the mint goes through this relay:

```
browser ──GET /bg-session────────────────────▶ relay ──▶ youtube.com homepage
                (visitorData + ytcfg + ytAtN challenge, server-cached 1 h)
browser ──GET /r?u=…www.google.com/js/th/…───▶ relay ──▶ BotGuard interpreter JS
browser ──POST /r?u=…jnn-pa…/GenerateIT──────▶ relay ──▶ WAA integrity token
browser   (locally: VM → snapshot → WebPoMinter → per-video PO token)
browser ──POST /r?u=…/youtubei/v1/player─────▶ relay ──▶ formats  (poToken injected
                                                           client-side in the body)
```

Why all legs through the relay:

1. **The WAA request key is origin-restricted.** `jnn-pa.googleapis.com` echoes any
   Origin in ACAO, but the bgutils `GenerateIT` request key is only valid for requests
   **without a foreign Origin**. Browsers force `Origin` onto every cross-origin fetch;
   the relay strips it (REQUEST_DROP) — upstream sees an origin-less request, exactly
   like the proven Node mint.
2. **One consistent egress IP.** Challenge, integrity token, player call and media bytes
   all egress from the same Worker — no mint-IP/use-IP mismatch (POTs are IP-bound per
   the bgutil provider's own notes).
3. **No CORS anywhere** — the page talks only same-origin to the relay.

PO-token **injection into the player request is client-side by design**: the web app
builds `serviceIntegrityDimensions.poToken` + `X-Goog-Visitor-Id` itself and POSTs
through `/r` — this works unchanged against the already-deployed P0 relay, and keeps the
worker dumb. Binding rules (yt-dlp `pot/utils.py`, cross-checked against the live
homepage): the **player** poToken is minted with `mintAsWebsafeString(VIDEO_ID)`; the
**googlevideo** `&pot=` param uses VIDEO_ID while the homepage's
`html5_generate_content_po_token` experiment flag is set (it is, 2026-11-12), else
visitorData.

> **Deploy note (resolved 2026-11-13):** the W1.0 code IS the live worker now —
> version `ab781f0f-c9b7-4d2a-a848-ecc9af6631bd` serves `/bg-session` and the
> `www.google.com/js/th/*` + `jnn-pa.googleapis.com` allowlist entries; live
> verification matrix in *Deploy* below. CI does **not** deploy the relay — it is a
> manual step by design (deploy credentials are never in the tree).

## Endpoints

| Endpoint                | Behaviour |
| ----------------------- | --------- |
| `OPTIONS *`             | 204 preflight: `Allow-Methods: GET, POST, HEAD, OPTIONS`, echoes the request's `Access-Control-Request-Headers` (fallback `*`), `Max-Age: 86400`. |
| `GET /health`           | 200 `text/plain` `ok`. |
| `GET /`                 | 200 JSON `{ service, usage, phase }`. |
| `GET /bg-session`       | W1.0: cached homepage challenge — `{ visitorData, ytcfg, ytAtN, fetchedAt, expiresAt, cache }` (see below). `?fresh=1` bypasses the cache. |
| `GET/HEAD/POST /r?u=…`  | The relay (see allowlist below). `X-Relay-Redirect: manual` opts into 302 passthrough. Other methods → 405. |
| anything else           | 404 JSON `{ error: "not found" }`. |

Every response — success, error, or upstream passthrough — carries:

```
Access-Control-Allow-Origin: *
Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length, Content-Type, Location, X-Relay-Ms
```

(`Location` powers the opt-in 302-passthrough mode; see *Redirect mode* below.)

### `GET /bg-session` (W1.0)

The first leg of the browser mint. The worker fetches the YouTube homepage **server-side**
with `SOCS=CAI` (the consent-bypass cookie — browsers cannot set `Cookie` on fetch, it is
a forbidden header) and the **caller's User-Agent** (so the challenge/visitorData pair is
coherent with the very browser that will run the VM), then extracts and returns:

| Field | What it is |
| ----- | ---------- |
| `visitorData` | the anonymous session id PO tokens bind against (first `"visitorData"` in the page) |
| `ytcfg` | the raw `ytcfg.set({...})` JSON — the client injects it as `yt.config_` (BotGuard reads `EVENT_ID`) |
| `ytAtN` | the raw `window.ytAtN({...})` loose-JSON payload — the client runs bgutils `parseLooseJSON` on it to get `R.bgChallenge` |
| `fetchedAt` / `expiresAt` / `cache` | cache bookkeeping (server caches per-UA for 1 h; the (ytcfg, ytAtN) pair must come from one render — the 2026-08 provider patch) |

Why server-side extraction: the homepage is ~1 MB; the extracted triple ~50 KB. And the
SOCS cookie is impossible client-side.

### Redirect mode (W1.0)

`/r` follows upstream redirects by default (the audio path wants bytes, not hops). With
`X-Relay-Redirect: manual` the upstream fetch uses `redirect: "manual"` and any 3xx is
**converted to a plain 200** whose CORS-exposed custom headers carry the hop:

```
HTTP/1.1 200
X-Relay-Location: <absolute upstream Location>
X-Relay-Status:  <true upstream status, e.g. 302>
```

Why not pass the literal 3xx + `Location` through? **A literal redirect can never cross CORS
into a page**: with `redirect:'manual'` the browser yields an opaque-redirect (status 0,
headers unreadable), and with `redirect:'follow'` it hops straight to the raw upstream URL —
googlevideo sends no ACAO, so the fetch dies. Custom headers listed in `Access-Control-
Expose-Headers` are the only hop signal a page can actually read. The chunk fetcher's hop
protocol consumes `X-Relay-Location`, re-wraps it through `/r` (so the allowlist re-validates
every hop), and caches the edge URL to amortize the hop across chunks. The header is
relay-control only: it is in `REQUEST_DROP` and never forwarded upstream.

## Upstream host allowlist

| Host | Match | Why |
| ---- | ----- | --- |
| `www.youtube.com` | exact | innertube web client endpoints |
| `music.youtube.com` | exact | innertube YouTube-Music client |
| `youtubei.googleapis.com` | exact | innertube API host used by some clients |
| `*.googlevideo.com` | suffix | media CDN edge nodes (`rr4---sn-x0.googlevideo.com`, …) |
| `googlevideo.com` | exact (defensive) | bare apex, just in case |
| `www.google.com` | exact host **and** `/js/th/*` path | W1.0: the BotGuard interpreter ("trayride") scripts the challenge's `interpreterUrl` points at — nothing else on google.com is proxied |
| `jnn-pa.googleapis.com` | exact | W1.0: the `Waa/GenerateIT` integrity-token endpoint (PO mint attestation) |

Anything else → 403 JSON `{ error: "host not allowed", host }`. The relay is an open proxy for
exactly these hosts and nothing else.

## 206 / Range — the kill criterion

Byte-range audio streaming **is the project's kill criterion**, so the relay is built around it:

* The client's `Range` header is **forwarded verbatim** (it is not in the drop list).
* The upstream **status is preserved exactly** — `206 Partial Content` stays 206, `403` stays
  403, `304` stays 304. No rewriting, no normalization.
* The upstream body is returned as a **stream** (`new Response(upstream.body, …)`), never
  buffered — audio bytes flow through the worker chunk-by-chunk.
* `Content-Range` / `Accept-Ranges` / `Content-Length` are passed through and explicitly listed in
  `Access-Control-Expose-Headers` so browser JS can read them on a cross-origin response.
* `content-length` is dropped from the copied header set (both directions) because the Workers
  runtime recomputes it from the actual streamed body — a stale copy would corrupt Range math.

### Header policy (request side)

Forwarded to upstream: **every** client header except —

| Dropped | Reason |
| ------- | ------ |
| `host`, `origin`, `referer` | leak / mis-describe the calling site; YouTube keys behavior off them |
| `cookie` | the open relay must never ride the caller's session |
| `cf-connecting-ip`, `cf-ipcountry`, `cf-ray`, `cf-visitor`, `x-forwarded-for`, `x-forwarded-proto`, `x-real-ip`, `true-client-ip` | injected by Cloudflare's edge — they describe the worker, not the browser |
| `sec-fetch-*`, `sec-ch-ua*`, `sec-purpose`, `dnt` | **P0 landmine fix**: browsers FORCE these markers onto every fetch (forbidden headers — the wasm engine cannot strip them). YouTube reads them as "this ANDROID app request actually comes from a browser page" and soft-blocks the player endpoint ("The page needs to be reloaded"). The relay is the true client from the upstream's perspective — these headers mis-describe the caller, so they never pass. |
| `connection`, `keep-alive`, `proxy-authorization`, `proxy-authenticate`, `te`, `trailer`, `transfer-encoding`, `upgrade` | hop-by-hop (RFC 7230 §6.1), never forwarded by a proxy |
| `content-length` | runtime recomputes it from the streamed body |

Explicitly forwarded (worth naming): `user-agent` (see the UA policy below — W0.1 finding: the
Workers default UA gets 404s on some YouTube endpoints while a browser-like UA passes),
`accept`, `accept-language`, `accept-encoding`, `content-type`, `range`,
`x-youtube-client-name`, `x-youtube-client-version`, `x-goog-api-key`,
`x-goog-visitor-id`, `x-user-agent`, `authorization`.

### User-Agent policy (P0 landmine fix)

Browsers **cannot set the `User-Agent` header** (a fetch forbidden-header), so every wasm-engine
request rides the browser's Chrome UA. That is correct for web clients — and YouTube **bot-walls
the player endpoint** for APP clients (`ANDROID`/`IOS`/`ANDROID_VR`/`VISIONOS`) carrying a browser
UA from datacenter IPs ("Sign in to confirm you're not a bot").

The relay therefore **normalizes the upstream UA for the client the request itself declares**:
requests carrying `X-YouTube-Client-Name` ∈ {`3` ANDROID, `5` IOS, `28` ANDROID_VR, `101` VISIONOS}
get the matching matrix UA (`tools/probe-clients.sh` / yt-dlp master values — same rot-cadence,
re-verify monthly). Web clients (`WEB_REMIX`, `MWEB`, embedded) and media-byte fetches keep the
forwarded browser UA — it is their natural UA. This also upgrades the canary matrix: its
browser-side probes now reach YouTube with faithful app UAs (largely mitigating the
"ua=browser (fetch cannot set UA)" deviation the canary documents per row).

POST bodies (innertube JSON payloads) stream through untouched.

### Header policy (response side)

**All** upstream headers pass through except hop-by-hop (same list as above) and
`content-length` (runtime recomputes). `Content-Encoding` is forwarded as-is — see *Known
caveats*. The relay then adds the CORS set plus `X-Relay-Ms` (wall-clock ms spent inside the
worker).

## Local dev

```bash
cd front-end/relay
bun install            # or: npm install
bunx wrangler dev --port 8787     # or: npm run dev — no Cloudflare account needed
```

Point the web app at the local relay — either build-time:

```bash
VITE_RELAY_BASE_URL=http://localhost:8787
```

or at runtime via the URL param the web app honors:

```
https://meraxes89.github.io/streamify-apk/?relay=http://localhost:8787
```

Smoke checks (from another terminal):

```bash
curl -i http://localhost:8787/health
curl -i "http://localhost:8787/"
curl -i "http://localhost:8787/r?u=https%3A%2F%2Fwww.youtube.com%2F"          # 200 (or upstream's status)
curl -i "http://localhost:8787/r?u=https%3A%2F%2Fevil.example.com%2F"          # 403 host not allowed
curl -i "http://localhost:8787/bg-session"                                    # W1.0 challenge triple (JSON)
curl -i "http://localhost:8787/r?u=https%3A%2F%2Fwww.google.com%2Fjs%2Fnot-th%2Fx.js"   # 403 (path-scoped)
curl -i -H "Range: bytes=0-1023" \
  "http://localhost:8787/r?u=https%3A%2F%2Frr4---sn-x0.googlevideo.com%2Fvideoplayback%3F..."   # 206 must survive
```

## Deploy

**LIVE** since 2026-11-12 at <https://streamify-relay.zephyr4289.workers.dev>
(account subdomain `zephyr4289`; first real deploy replaced the account's initial
Hello-World placeholder — wrangler version `ac91b113-9197-40af-853c-1cad5acb5d40`).

Redeployed **2026-11-13** with the W1.0 minting surface — version
`ab781f0f-c9b7-4d2a-a848-ecc9af6631bd`. Live verification (cold probes against
production, not `wrangler dev`): `/` reports the P1 phase banner; `/health` 200;
`/bg-session` returns the full triple (visitorData 452 chars, ytcfg ~762 KB, ytAtN
~42 KB with the `bgChallenge` payload) and answers `cache: hit` on the immediate
second call; `www.google.com/js/th/<probe>` passes the allowlist (Google's own 404
on the fake path — not the relay's `host not allowed`) while
`www.google.com/search` is refused with 403 (the `/js/th/*` path scoping holds);
`jnn-pa.googleapis.com/$rpc/…/GenerateIT` passes; `example.com` refused 403;
`www.youtube.com` passthrough 200 with `ACAO:*`, the full expose list and
`X-Relay-Ms`.

Redeploying:

```bash
export CLOUDFLARE_API_TOKEN=<token>      # Workers Scripts:Edit scope
export CLOUDFLARE_ACCOUNT_ID=<account-id> # not required with a single-account token
cd front-end/relay
npx wrangler deploy                       # or: bun run deploy
```

The token is handed over a secure channel and used once — it never enters the tree
(gitleaks runs on every PR). The full deploy record + the live verification matrix
(player matrix, 206 byte-range, mid-file seek, WEB_REMIX search — all through the
deployed worker) lives in `front-end/decisions/W0.6-live-wiring.md`.

Validation with **no account at all**:

```bash
npx wrangler deploy --dry-run --outdir /tmp/relay-dry-run   # bundles + validates config, exit 0
bunx tsc --noEmit                                           # strict typecheck
```

Runs on the Workers **free plan** — nothing here uses a paid feature (no KV, no Durable
Objects, no Queues, no Cron, no custom domains).

## P4 TODOs (per W4.1 — all deliberately absent in P0)

* **JWT auth** on `/r` — `TODO(P4/W4.1)` in `src/index.ts`.
* **Rate limiting** — per-IP / per-token buckets.
* **Kill switch** — env-bound flag that makes `/r` answer 503 (still CORS-open, so browsers see
  a clean error) without a redeploy.
* Logging/alerting on allowlist violations.

## Security note

`Access-Control-Allow-Origin: *` is a **deliberate P0 decision**: the relay carries no secrets
(`cookie` is stripped, nothing is logged), only public YouTube/innertube/googlevideo traffic
flows through, and it cannot be used as a general-purpose open proxy thanks to the host
allowlist. Real hardening (JWT, rate limits, kill switch) lands in P4 (W4.1) — see the TODOs
above and in `src/index.ts`.

## Known caveats

* **`*.workers.dev` edge protection rejects non-browser User-Agents (2026-11-12 finding).**
  Cloudflare's edge in front of `workers.dev` domains runs browser-integrity checks: a
  request carrying e.g. `Python-urllib/3.12` gets `403 error code: 1010` **before the worker
  code ever runs** (verified: same request with a browser UA → `200 ok`). The relay's real
  clients (browsers on `github.io`) are unaffected. Implication for probing tooling: curl and
  browser-UA'd HTTP clients pass; bare python-urllib / default scripting UAs are banned at the
  edge — set a browser-like `User-Agent` on any health checks or canary tooling that hits the
  deployed URL.
* **`Content-Encoding` passthrough.** Upstream headers are copied verbatim. If the Workers
  runtime ever transparently decompresses a subresponse *without* dropping the
  `Content-Encoding` header, a browser would try to gunzip plain bytes. In practice the audio
  path (googlevideo) is identity-encoded (media bytes are already compressed — no
  `Content-Encoding`), so the kill-criterion path is unaffected; the innertube JSON path stays
  internally consistent. Verified on the first live deploy (2026-11-12, W0.6) before the P4
  hardening.
* **URL normalization.** The target is parsed with `new URL()` for validation and re-serialized
  for the upstream fetch. Well-formed youtubei/googlevideo URLs round-trip unchanged; a
  hand-mangled target could get normalized (this is acceptable for P0 and fails safe with an
  upstream 400/403, not a relay misroute).
