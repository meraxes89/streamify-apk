# P3b — The BotGuard egress pivot: the 3-Tier resilient architecture

**Status: BUILT & BROWSER-VERIFIED (headless local)** — written 2026-11-14, the
session after P3a merged (#164). Implements the principal systems architect's
3-Tier blueprint against the PO-token "split-brain" failure (empty
`webPoSignalOutput` on non-YouTube origins, HTTP 403 downstream, the burned
ANDROID_VR 60-second cap). This record documents the architecture, the live
measurements that shaped it, and the verification matrix.

## The blueprint (architect's diagnosis, condensed)

YouTube's anti-abuse pipeline (BotGuard + WAA + InnerTube + GVS) cross-validates
the JS runtime fingerprint, the HTTP request headers, the token binding context,
and the egress IP ASN across every hop. When any two hops disagree, the token is
either never minted (`webPoSignalOutput` empty — the W1.0 github.io failure) or
silently invalidated downstream (403). The three failure eras, post-mortemed:

| Attempt | Failure mechanism |
| --- | --- |
| rustypipe-botguard (QuickJS/JSDOM) | No real GPU/audio/timing surfaces → WAA scores the telemetry snapshot near zero. |
| bgutils-js in the MAIN window (W1.0) | BotGuard inspects `window.location` / `document.URL` → non-YouTube origin aborts the minter init → `webPoSignalOutput[0]` undefined. |
| Node minting / CF-Worker minting | Datacenter ASN + binding split: modern web playback wants BOTH a videoId-bound player token AND a visitorData-bound session token; minting one without pinning the other (or from AS13335) gets rejected. |

## The 3-Tier resolver ladder (shipped)

```
Tier 1  ── VISIONOS (clientName 101, v1.02, Vision Pro device fields)
             token-exempt DIRECT googlevideo URLs + bonus hlsManifestUrl
             ↳ fallback within the same call: hlsManifestUrl → element path
         ── WEB_SAFARI (WEB client, X-Relay-UA: safari-mac)
             HLS manifests, no GVS PO token needed
Tier 2  ── WEB + PO token (the licensed rung):
             player token  = mint(videoId)      → serviceIntegrityDimensions
             session token = mint(visitorData)  → cached in localStorage ~6 h
             &pot= binding follows the homepage experiment flag (measured
             2026-11-12: videoId while set, else visitorData)
             Brave-class fallback: skipPrivacyBuffer retry → websafeFallbackToken
             → createColdStartToken (local XOR, first ~1–2 MB unlock)
Tier 3  ── (future, optional) Capacitor/TWA + declarativeNetRequest —
             residential /player + googlevideo, relay-free. Out of scope here.
```

### Live measurements that shaped it (2026-11-14, through the deployed relay)

* **VISIONOS is the real fast path**: `dQw4w9WgXcQ` → OK, full-length direct
  URLs (itag 140 clen 3,449,447 = full 213 s; itag 251 clen 3,433,755), AND an
  `hlsManifestUrl` (`manifest.googlevideo.com/api/manifest/hls_variant/…` —
  inside the existing `.googlevideo.com` allowlist suffix). No PO token.
* **Licensed content (4NRXx6U8ABQ) → LOGIN_REQUIRED on VISIONOS from CF egress**
  — the Tier 2 cascade trigger, exactly as designed.
* **Anonymous WEB from datacenter egress is soft-blocked regardless of UA /
  visitorData / client-hint shape** ("The page needs to be reloaded" /
  LOGIN_REQUIRED — bisected across Chrome UA, Safari UA, ±visitorData,
  ±sec-ch-ua). The WEB_SAFARI rung therefore executes and reports honestly;
  it produces streams where anonymous WEB passes (residential extension mode,
  Tier 3) and degrades elsewhere.
* **ANDROID_VR is burned** (hard ~60 s byte cap on token-free streams) — the
  rung is REMOVED from the resolver (the relay's UA matrix keeps client 28 for
  the canary).

## Tier 2 — the fake-YouTube iframe (the split-brain fix)

The mint moved from the main window into a hidden same-origin
`<iframe srcdoc>` on the Main UI Thread, with a **three-layer origin spoof** so
the BotGuard bytecode believes it runs on `https://www.youtube.com` while
canvas/WebGL/AudioContext/performance pass straight through to the user's REAL
(small, mobile, residential) hardware:

1. **Layer 1 — realm-wide prototype patches.** `Document.prototype.URL /
   documentURI / baseURI / referrer / domain` are ordinary configurable getters:
   patched in the harness realm (the mint iframe only — the app's document is a
   different realm), EVERY access path sees YouTube — including the indirect
   `(0,eval)(…)` re-entrancy the live 2026-11-14 trayride interpreter uses
   (statically verified: the interpreter builds code through indirect eval,
   which re-binds `this` to the real global — no proxy can cover that; the
   prototype patch can and does).
2. **Layer 2 — the `this`-binding.** The interpreter's closure library binds
   `G = this || self` and exposes the whole environment graph (`G.document`,
   `G.location`, the `G.trayride = {…}` VM assignment) through that root. The
   harness invokes the shadow-eval'd interpreter with `boot.call(spoofedWindow,
   …)` — the G-path routes through the spoof while the set-trap still lands the
   VM on the REAL window (where `BotGuardClient` looks it up).
3. **Layer 3 — shadow parameters.** `new Function('window','document',
   'location','top','parent','self','globalThis', interpreterJS)` — bare
   identifier reads resolve to the Proxy wrappers (`window.location` itself is
   `[LegacyUnforgeable]`; parameters are the only way unmodified challenge code
   can be made to see a different location).

**Realm correctness** (the quiet killer): `WebPoMinter` does
`result instanceof Uint8Array` / `mintCallback instanceof Function`. Cross-realm
objects fail those checks — so the interpreter eval, `BotGuardClient`, the
snapshot, AND the minter all live in the iframe's realm, in one harness module
(`mintHarness.ts`) loaded as a dedicated rollup entry (`assets/mint-harness.js`,
fixed name; a `?url` import emits raw TypeScript — useless in a browser). The
parent orchestrates every relayed network leg and drives the harness through
direct same-origin function references.

**The snapshot fallback ladder** (Brave's "farbling" and headless-class
environments leave `webPoSignalOutput` empty):

1. snapshot (privacy buffer ON) →
2. retry with `skipPrivacyBuffer: true` (the VM's built-in bypass) →
3. `websafeFallbackToken` (the 4th GenerateIT tuple element — server-blessed,
   exists precisely for degraded environments; also covers an EMPTY
   integrityToken, the WAA's honest low-trust verdict) →
4. `createColdStartToken` (fully local XOR token, sps=2 window, first ~1–2 MB).

Every degradation is LABELED (`potKind: live | seeded | websafe-fallback |
coldstart`) up to the UI, and the Brave Shields advisory surfaces when the
privacy-buffer path fired.

**Dual-token sessions + the localStorage cache**: the session token
(`mint(visitorData)`) is minted eagerly at boot and persisted (~6 h, LRU-capped
per-video player tokens alongside). A restored cache serves `&pot=` and REPLAYS
without any BotGuard run; a NEW video escalates to a full live mint
(`sessionHasMinter` distinguishes "this page load owns a minter" from "restored
tokens only").

## The relay surface (P3b capabilities — backward-compatible, capability-echoed)

* **`X-Relay-UA: safari-mac | safari-ios`** — named UA overrides for the
  WEB_SAFARI rung (browsers cannot set a Safari UA — forbidden header). Control
  header (dropped upstream, like `X-Relay-Redirect`); an APP client's matrix UA
  always wins.
* **Session envelope pinning** — the `sec-ch-ua*` family (still dropped for APP
  clients — the P0 landmine rule stands) now rides VERBATIM on exactly two
  hops: `jnn-pa.googleapis.com` (the WAA mint legs) and `/youtubei/*` calls
  declaring `X-YouTube-Client-Name: 1` (WEB). A UA override strips the family
  (Safari sends none — forwarding Chrome's would re-split the envelope).
* **`GET /` capability echo** — `{ capabilities: [bg-session, ua-override,
  client-hints-passthrough] }`; an old deployment answers without the field,
  which the web app treats as "capability absent" and degrades honestly.

**The deployed worker predates P3b** — it already serves everything the mint
needs (the W1.0 surface; VISIONOS needed only the pre-existing `101` UA matrix
entry, live-verified). The new capabilities activate on the next
`wrangler deploy` (manual by design; credentials never enter the tree).

## The verification matrix (2026-11-14, headless Chromium + local wrangler dev)

| # | Probe | Result |
| --- | --- | --- |
| V1 | VISIONOS player call through the local P3b relay (curl, matrix UA) | **OK** — full-length itags 140/251 + `hlsManifestUrl` |
| V2 | WEB_SAFARI rung through the local relay (`X-Relay-UA`) | Executes; honest `UNPLAYABLE` from this datacenter IP (the documented anonymous-WEB wall) |
| V3 | Mint iframe boot | Harness loads as a module; `__streamifyMintHarness` exposed |
| V4 | **The origin spoof** (`environmentProbe`) | `origin: https://www.youtube.com`, `url: https://www.youtube.com/`, `isTopLevel: true`, **`realDocumentPatched: true`** (the unproxied real document reads YouTube — layer 1 held) |
| V5 | The full mint pipeline | iframe → VM boots under the spoof → snapshot bytes → GenerateIT → **session ready**. Headless scores low-trust (expected): empty integrityToken → honest **websafe-fallback session**; UI advisory + localStorage cache verified (sessionToken 107 B) |
| V6 | Resolver ladder, fresh licensed video | All three rungs execute with honest verdicts (`[VISIONOS] LOGIN_REQUIRED · [WEB_SAFARI] LOGIN_REQUIRED · [WEB+POT] LOGIN_REQUIRED` — the datacenter reality; the phone is the unlock instrument) |
| V7 | Spine path (WASM lane), dQw | VISIONOS rung resolves itag 251 (3.27 MiB, full); demux 799 packets. NOTE: `SinkTransport.open`'s `await ctx.resume()` hangs forever in headless (autoplay policy, no user gesture) — a PRE-EXISTING transport/headless interaction, not a P3b regression; the element lane (V8) is the headless-verifiable one |
| V8 | **Element path (production lane), dQw** | `<audio src=direct-googlevideo>`: **Playing** at 14 s, 202.9 s of 213 s buffered, `rung: visionos/aac`, `potKind: none` — the tokenless fast path streaming full-length audio through the user-IP lane |

## What the phone test (the PO's instrument) should now show

1. Standard tracks: instant VISIONOS resolve, full-length playback, zero relay
   bytes, zero mint.
2. Licensed tracks: Tier 2 auto-mints in the iframe (the spoof makes the VM
   believe it is on youtube.com); the LIVE minter path (`webPoSignalOutput`
   populated on real hardware) mints both tokens; the licensed wall lifts
   (phone-proven envelope in P1 + the split-brain now fixed).
3. Brave: the skipPrivacyBuffer/websafe ladder keeps playback alive (possibly
   with the first ~1–2 MB cold-start window) + the advisory toast suggesting
   Shields be relaxed for the domain.

## Files (the map)

| File | Role |
| --- | --- |
| `front-end/relay/src/index.ts` | The 3 P3b capabilities: UA overrides, envelope pinning, capability echo |
| `front-end/web/src/audio/botguard/mintHarness.ts` | NEW — the in-iframe realm: 3-layer spoof, VM boot, snapshot (+skipPrivacyBuffer), WebPoMinter, dual-token mint |
| `front-end/web/src/audio/botguard/mintIframe.ts` | NEW — the hidden srcdoc frame host (module URL resolution dev/build, singleton lifecycle) |
| `front-end/web/src/audio/botguard/mintCore.ts` | The pipeline rewrite: iframe steps, the snapshot fallback ladder, the GenerateIT verdict ladder, coldstart/websafe sessions |
| `front-end/web/src/audio/botguard/potSession.ts` | Dual-token sessions, the localStorage cache (+restore, +`sessionHasMinter` escalation), reset surface, kinds |
| `front-end/web/src/audio/botguard/protocol.ts` | VISIONOS/WEB_SAFARI client specs, cache + status types, extended kinds/phases |
| `front-end/web/src/audio/resolve.ts` | The 3-Tier ladder + the failed-rung ledger (rotation loop guard) |
| `front-end/web/src/lib/wasmEngine.ts` | HLS rotation ledger hook (element rejects m3u8 → escalate, never loop) |
| `front-end/web/src/audio/spine.ts` | Rung plumbing (client 101 for visionos media fetches) |
| `front-end/web/src/components/SpineConsole.tsx` | Brave advisory, reset-session, cache-phase status |
| `front-end/web/vite.config.ts` | The mint-harness rollup entry (fixed output name) |
| `front-end/relay/README.md` | The P3b surface documentation |
