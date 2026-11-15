# G4 — The residential-direct pivot (every egress × every client)

**Date:** 2026-11-14 · **Branch:** `ws-w/g4-residential-direct` · **Supersedes:** G3b-open-source-botguard.md (the ladder's shape; the G3b open-source stack is kept, its egress is replaced)

## The mandate

After the G3b merge, the phone (Brave/Android/5G, the LIVE relay) reported
`no rung resolved` for
[tixIb21MO5U](https://www.youtube.com/watch?v=tixIb21MO5U) — every rung
refusing with **"This video is unavailable"**. The directive: find an
extremely intelligent engineering solution, search how the open-source
ecosystem actually does this, and fix it ruthlessly in the next PR.

## The diagnosis (probe evidence, 2026-11-14)

`tixIb21MO5U` is **BOHEMIA – Ek Tera Pyar** — licensed music. A live
13-client × 2-video matrix through the production relay
(`tools`-style raw player POSTs, 2s spacing) produced:

| Client (www surface, via relay) | tixIb21MO5U (licensed) | 4NRXx6U8ABQ (licensed) | dQw4w9WgXcQ (standard) |
|---|---|---|---|
| VISIONOS / IOS / ANDROID / ANDROID_VR / TV / TV_DOWNGRADED / TV_SAMSUNG / MWEB / WEB / WEB_SAFARI | LOGIN_REQUIRED "Sign in to confirm you're not a bot" | same | — |
| TV_SIMPLY (75) | UNPLAYABLE (bot reason) | UNPLAYABLE | — |
| WEB_MUSIC (67) | UNPLAYABLE "The page needs to be reloaded" | LOGIN_REQUIRED | — |
| WEB_EMBEDDED (56) | ERROR "This video is unavailable" | ERROR | — |
| **VISIONOS (www, via relay)** | — | — | **OK + 27 URL-ful formats + HLS** |

The verdict: the relay's **shared Cloudflare Workers egress is
selectively burned** — licensed music is bot-walled on every client,
standard content still passes. This also explains the phone's exact
symptom: session-carrying player calls (visitorData + PO token) get the
harsher `UNPLAYABLE "This video is unavailable"` refusal rather than the
bare bot message — a rejected/tainted session, not a missing one.

This is the documented ceiling the BgUtils README warns about: *"a PO
Token does not bypass IP-based login restrictions (common on datacenter
IPs)"*. No token minted through that egress can ever unlock licensed
music — the G3b architecture was egress-dead the moment YouTube tightened
the shared-IP wall (the yt-dlp master client comments independently date
the tightening: *"Since 2026.07, HLS formats are only returned with some
logged-in or 'trusted' sessions"* for web_safari).

## The research (how the open-source world actually does it)

1. **yt-dlp master (`_base.py`)** — the authoritative client matrix and
   PO-token policies. Key facts extracted:
   - `ios` GVS policy: `not_required_with_player_token=True` — **a
     videoId-bound PO token on the PLAYER call waives the GVS token
     requirement for the media URLs**; PLAYER token itself only
     `recommended`. This is the licensed-music recipe.
   - `android_vr` is dead (since 2026.08.17 all formats 403 at 1.65.10),
     `web_safari` HLS is dying (2026.07 comment), `tv_simply` requires
     GVS for progressive but not HLS.
2. **LuanRT/kira** ("YouTube client built with Vue, demonstrating SABR
   playback via YouTube.js & googlevideo") — the reference web app:
   - innertube via its own bridge proxy (`ytc-bridge`), **SABR media
     (`initplayback`) DIRECT from the browser** — googlevideo is
     CORS-open;
   - the **googlevideo** package's `SabrStream` / `SabrStreamingAdapter`
     implements the whole Server-ABR protocol (UMP frames, format
     selection, PO-token negotiation, reload directives);
   - the mint: WAA `Create` on **jnn-pa.googleapis.com** (CORS-open)
     with the interpreter INLINE — **no www.youtube.com leg at all**;
   - `pot` minted per-video and attached to the SABR requests.
3. **LuanRT/YouTube.js** — its browser build defaults to
   `youtubei.googleapis.com` (`Constants.URLS.API.PRODUCTION_2`) — the
   one innertube surface Google's API frontend serves to cross-origin
   browsers. HTTPClient otherwise targets `www.youtube.com/youtubei/`
   (PRODUCTION_1) — the host choice is ours to make per request.
4. **BgUtils-js / youtube-trusted-session-generator** — the IP/ASN
   single-envelope rule that now finally has a satisfiable egress: the
   phone itself.
5. **Probe verification of the googleapis surface** (via the live relay):
   - `IOS @ youtubei.googleapis.com`: **playability OK for tixIb21MO5U**
     — the exact video that failed every G3b rung — even from the BURNED
     Cloudflare egress (SABR-only descriptors, no URLs);
   - `VISIONOS @ youtubei.googleapis.com`: dQw4w9WgXcQ **OK with direct
     pre-signed itag 140/251 URLs + HLS + SABR url**;
   - `search` + `config` on the same surface: healthy (search for
     "blinding lights" → the official video first).

## The pivot in one sentence

**The phone's own browser — real hardware, residential IP — becomes the
egress for every IP-sensitive leg, riding exactly the three surface
families YouTube itself serves to cross-origin browsers:
`youtubei.googleapis.com` (innertube API), `jnn-pa.googleapis.com` (WAA
mint), `*.googlevideo.com` (media + SABR).** The relay survives as the
www-content leg (sw.js_data, /s/player base.js, iframe_api) and as the
per-request fallback egress.

## What was built

### `lib/ytSession.ts` — the dual-lane funnel + the API-surface rewrite

- **The load-bearing trick:** youtubei.js builds every API call against
  `www.youtube.com/youtubei/` (PRODUCTION_1). `ytFetch` rewrites
  `/youtubei/*` calls onto `youtubei.googleapis.com` (PRODUCTION_2) —
  moving search, player and config onto the DIRECT lane — while content
  legs (sw.js_data, /s/player, iframe_api) stay on www → relay.
- Direct hosts (`youtubei.googleapis.com`, `jnn-pa`, `googlevideo`) are
  fetched DIRECT; a network-layer failure falls back to the relay for
  that request (degraded egress beats no egress). HTTP verdicts are
  returned as-is — never retried on the other lane, so a playability
  refusal stays attributable to its egress.
- `directPlayerPost` — the resolver's raw player POST helper with the
  same direct-first/relay-fallback semantics and lane reporting.

### `audio/resolve.ts` — the G4 ladder (direct-first, relay-fallback)

```
1. visionos-direct   — VISIONOS @ googleapis, tokenless. Standard
                       content: direct pre-signed itag 140/251 URLs,
                       used AS-IS (P3b-proven behavior, probe-verified
                       on the new surface).
2. ios-direct        — IOS @ googleapis, tokenless. The licensed-tolerant
                       client (OK for tixIb21MO5U even from the burned
                       CF egress). Answers SABR descriptors / HLS.
3. ios-pot-direct    — IOS @ googleapis + videoId-bound player PO token
                       (the yt-dlp not_required_with_player_token
                       waiver). THE licensed unlock.
4. web-pot-direct    — the G3b rung (youtubei.js WEB + po_token +
                       decipher), now direct via the funnel rewrite.
5. visionos-relay    — raw POST to www.youtube.com via the relay (the
                       standard-content lifeline for direct-hostile
                       networks; probe-verified STILL WORKING).
6. web-safari-hls    — the G3b relay rung, vestigial by design (yt-dlp:
                       2026.07 trusted-session restriction).
```

Every rung prefers: progressive URLs → HLS manifest (the remux lanes) →
**the SABR lane**. Honest verdict aggregation with the `*` marker for
direct lanes; the failed-rung ledger and rotation-escalation survive
unchanged.

### `audio/sabr/sabrStreamPlayer.ts` — the SABR playback lane

The progressive-URL sunset is here (IOS-class responses: OK playability,
itags 140/249/250/251 as URL-less SABR descriptors). The new lane pipes
googlevideo's `SabrStream` (audio-only, `EnabledTrackTypes.AUDIO_ONLY`,
AAC-preferred) into a MediaSource SourceBuffer and hands the element an
MSE object URL — playback starts while segments stream in.

**The SABR egress rule (probe-measured):** googlevideo serves ACAO ONLY
to `https://www.youtube.com` — a foreign page's SABR POSTs
(`initplayback`, `application/octet-stream` → preflight) are CORS-dead
direct, on the phone exactly like in the lab (this is why kira routes
googlevideo through its ytc-bridge proxy when not running as an
extension). The SABR requests therefore ride the RELAY (UMP bodies
stream both ways; the relay adds ACAO:*). The IP-pin is tolerated on
SABR POSTs (curl-verified: a URL minted for a Cloudflare egress answered
`200 application/vnd.yt-ump` from a mismatched lab IP).

**Protection-status semantics** (from googlevideo's own handler + kira):
status 1/2 = attestation PENDING — a warning; media keeps flowing while
the attestation settles. Status 3 = attestation REQUIRED — the hard
refusal. The lane fails honestly on 3+ and reload directives → the rung
is ledgered → the ladder escalates.

### `lib/engine.ts` + `lib/searchStore.ts` — search joins the session

- `searchMusic` now uses the YouTube.js session (`yt.search`) — direct
  googleapis via the funnel rewrite, one session envelope for search and
  playback. The Rust engine's `search_music` (relay egress) stays as the
  fallback lane.
- **The stale-results fix** (the "I searched blinding lights but a
  Bohemia track played" anomaly): a request-epoch guard drops any
  superseded response — a slow older query can never land its results
  under a newer query's label.

### What did NOT change

- **The relay is untouched and NOT redeployed** — its allowlist already
  covers `youtubei.googleapis.com`, jnn-pa and googlevideo; the UA matrix
  and envelope pinning keep serving the www rungs.
- The mint (potMinter/potSession) code is unchanged — the dual-lane
  funnel automatically moves its WAA legs onto the direct lane (jnn-pa is
  CORS-open), binding the tokens to the phone's IP, coherent with the
  direct player calls that use them.
- The HLS remux lanes, the element player, the spine, the SW media
  bridge — all unchanged consumers of the resolver's output.

## Verification matrix

| Probe | Result |
|---|---|
| Live relay 13-client matrix (licensed × 2) | LOGIN_REQUIRED across the board — the burned-egress diagnosis, reproduced |
| `IOS @ googleapis` for **tixIb21MO5U** | **OK playability** (the G3b-killing video), SABR descriptors present |
| `VISIONOS @ googleapis` for dQw | OK + direct pre-signed 140/251 URLs + HLS + SABR |
| `search`/`config` @ googleapis | Healthy (19 videoRender results; config 200) |
| `tsc --noEmit` + `vite build` | PASS (this PR) |
| jnn-pa (WAA) CORS | `access-control-allow-origin` reflecting the origin — the direct mint lane is browser-viable |
| googlevideo CORS | **youtube.com-only** (ACAO reflected for https://www.youtube.com, absent for foreign origins) → SABR rides the relay |
| Relay-lane verdict determinism | **Flapping**: 6 identical IOS POSTs → OK/LR/OK/LR/OK/OK — hence the 3-attempt flap-retry policy |
| Local headless (vite dev, live relay) | dQw resolves `visionos-direct` (clen 3,449,447 — the P3b measurement); **tix + 4NRX resolve `ios-direct` as SABR streams** (titles verified); mint kind=live (120/700-char tokens); the SABR session starts, the UMP protocol runs through the relay, protection events fire and the MSE lane is exercised; the flap later went fully hostile (probe load poisoning the shared IP — the diagnosis feeding itself) |
| **The phone (the acceptance bar)** | run `g3b-test.html?relay=https://streamify-relay.zephyr4289.workers.dev` — the G4 matrix resolves all three canonical videos on the DIRECT rungs (the sandbox cannot test them: its IP is blocked at youtubei.googleapis.com) |

## Known limitations / deferred

- **SABR is sequential** (download-to-end, ~3–5 MB per track): playback
  starts immediately, seeks beyond the buffered window wait for the
  download. Fully-seekable ABR is the Shaka-adapter (`googlevideo/
  sabr-streaming-adapter`) future.
- **The www-surface relay rungs remain on the burned egress for licensed
  content** — they exist for standard content and direct-hostile
  networks only.
- **visitorData provenance**: the session's visitorData is born on the
  relay egress (sw.js_data is a www leg). Tokens bind (visitorData →
  mint IP); the mint and use legs share the phone's IP, which is the
  binding that matters. If licensed playback still refuses on the phone
  with a *live* token, the next lever is a direct-born visitorData (the
  `/config` responseContext carries one).
- The `tv_simply` / `web_music` / `tv` clients are mapped and
  probe-documented but not yet ladder rungs — the matrix lives in this
  doc for the next tightening.

---

# G4.1 — the deploy fix + the direct-born visitor (2026-11-14, same PR)

**The phone reported `no rung resolved DOGP2dYUGE0` — every rung
LOGIN_REQUIRED — a full day after the G4 push. The forensic finding: the
G4 code never reached the phone.** `web-deploy.yml`'s branch allowlist
ended at the G3b branch; `ws-w/g4-residential-direct` was never added, so
every G4 push shipped CI-green and deployed NOTHING. The live Pages
bundle was still the G3b merge (d3310e7) — its rung labels
`[VISIONOS]/[WEB+POT]/[WEB_SAFARI]` (no `*` direct markers, which every
G4 verdict carries) are the smoking gun. The phone was re-testing the
burned-egress G3b ladder and correctly reporting its failure.

## What G4.1 changes

1. **The deploy wiring** — `branches: [main, 'ws-w/g4-residential-direct']`
   (historical merged branches pruned). Every push to the PR branch now
   lands on Pages within minutes; the phone finally gets the G4 ladder.
2. **The direct-born visitor (the deferred lever, pulled)** —
   `directBornVisitorData()` in `lib/ytSession.ts`: POST
   `/youtubei/v1/config` on the googleapis surface carrying NO visitor
   id; the `responseContext.visitorData` is a visitor BORN on the calling
   egress. Direct lane first, one relay retry (a relay-born visitor is
   coherent but NOT persisted — the next page load retries for a direct
   birth). Persisted 30 d and seeded into `Innertube.create({ visitor_data })`
   — youtubei.js honors the seed in BOTH its fresh and cached session
   paths (`Session.js #getSessionData:
   visitor_data: options.visitor_data || device_info[13]`). Verified
   live 2026-11-14: the endpoint mints a fresh visitor even from the
   relay's burned Cloudflare egress (~0.4 s, 48-char visitor).
3. **Lane-aware rung policy** — direct rungs: 2 attempts (the OK/LR flap
   was measured on the relay's shared-IP wall; direct verdicts don't
   flap). Relay rungs keep 3. A `*-direct` rung that relay-fallbacked
   labels its verdict `@relay-egress`; the terminal error carries
   `visitor=direct|relay|session`. A trailing network error no longer
   launders a definitive refusal out of the ledger (stickiness).
4. **The G4 direct-lane canary** (`resolver-canary.yml`, nightly +
   dispatchable) — from a clean runner IP: the CORS preflight posture of
   `youtubei.googleapis.com` for foreign origins (the one load-bearing
   unknown of the pivot — the lab IP is hard-blocked at that host, and
   kira's proxy spoofs a youtube.com origin, so no amount of lab probing
   settles it), plus IOS/VISIONOS playability for `dQw4w9WgXcQ` and
   `DOGP2dYUGE0` on the googleapis surface with the per-call ACAO header
   recorded. Non-gating: a posture change is YouTube's environmental
   choice, not registry rot; the ladder degrades per-request onto the
   relay either way — the canary exists to DETECT and to leave evidence.

## The CORS evidence ledger (2026-11-14, live probes)

| Surface | Foreign-origin preflight | Foreign-origin POST | Verdict |
|---|---|---|---|
| `jnn-pa.googleapis.com` WAA (`$rpc/google.internal.waa.v1.Waa/Create`) | **200**, ACAO reflects the origin, allow-methods includes POST, allow-headers covers `content-type,x-goog-api-key,x-user-agent` (the exact bgutils set) | **200**, ACAO reflected, real challenge body | **The direct mint lane is browser-viable — verified.** |
| `youtubei.googleapis.com` `/youtubei/v1/player` | **IP-walled from every datacenter egress tried** (the lab sandbox AND both CI runners get Google's “Sorry… automated queries” 403 HTML — runs 43564752070 / 43566827760). The wall answers BEFORE CORS is considered, so the foreign-origin CORS posture is measurable only from a residential IP. | same (the wall) | **The phone's `g3b-test.html` row 1 is the arbiter.** Either way the ladder survives: a CORS-dead direct lane throws at the network layer → per-request relay fallback → the direct-lane negative cache routes all later attempts straight to the relay. And the relay's Cloudflare egress IS served by this surface — IOS@googleapis answered OK for licensed content from it (the G4 matrix), which is the operative licensed lane. |
| `*.googlevideo.com` SABR POST | youtube.com-only (measured, G4) | — | SABR rides the relay (unchanged). |

## The research addendum (what the ecosystem actually says, 2026-11-14)

- **yt-dlp wiki PO Token Guide**: `ios` requires "GVS or Player" PO
  token; "A PO Token is generated by either BotGuard (Web), DroidGuard
  (Android), iOSGuard (iOS). A PO Token from one platform cannot be used
  on another." — the `ios-pot-direct` rung's BotGuard(web)-minted player
  token is the known-weak rung of the ladder; the tokenless
  `ios-direct` (SABR lane) and `web-pot-direct` (platform-matched WEB +
  BotGuard) rungs carry the licensed load if it refuses.
- **kira** (LuanRT's reference SABR app): ships a proxy that SPOOFS
  `origin: https://www.youtube.com` for every youtube-host request — the
  author of YouTube.js himself does NOT rely on direct cross-origin
  innertube from a foreign page. Our direct rungs may yet prove
  relay-fallback-bound; the honest-lane labels make that visible per
  verdict instead of hiding it.
- **yt-dlp master `_base.py`** (the client matrix the rungs cite):
  `ios` GVS policy `not_required_with_player_token=True`, PLAYER token
  `required=False, recommended=True` — the waiver rung 3 rides.

