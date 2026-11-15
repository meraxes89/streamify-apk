# WS-W Master Plan v3 (FINAL) — The Full Android → Web Port

**Streamify Web: a TypeScript + WASM implementation of the entire Android app, hosted on GitHub Pages, at $0.**

| | |
|---|---|
| Document | WS-W Master Plan, version 3 (final) |
| Scope | Port 100% of the Android implementation to a static web app |
| Host | GitHub Pages (static, `username.github.io/streamify-apk`) |
| Cost ceiling | $0 — Supabase free tier + Cloudflare free tier only |
| Predecessors | v1 (Shivam, VPS-gateway architecture — superseded) · v2 (zero-cost GH Pages rewrite — superseded) |
| Status | **Evidence-backed**: every architecture-defining claim in this document was measured against the live YouTube/Innertube stack from this repo's tooling (Appendix A) |

---

## 0. Mandate and Hard Constraints

**Port the whole Android implementation to the website.** Not a subset, not a lookalike — the same Rust engine, the same C++ DSP math, the same resolver, the same Supabase backend, the same UI, running as a TypeScript + WASM app on GitHub Pages. The APK and the web player must behave identically for the same user on the same account.

Four hard constraints, non-negotiable, set by the product owner:

1. **GitHub Pages only.** No Cloudflare Pages, no Vercel, no custom hosting. Everything ships as static files from `gh-pages`. This one constraint drives half the architecture (no COOP/COEP → no `SharedArrayBuffer`; no server-side anything).
2. **$0 forever.** Free tiers only: Supabase (auth, DB, realtime, edge functions) and Cloudflare (Workers, KV). No paid tier escape hatch. If we exceed a free-tier ceiling, we degrade gracefully or cap beta size — we never pay.
3. **No VPS. No cloud processing.** All playback, DSP, decoding, and library logic executes on the user's device. The only server-side code is a ~150-line dumb relay and existing Supabase primitives.
4. **APK parity.** Same features, same audio behavior (bit-exact DSP chain), same UI structure. Where the web platform makes a feature impossible, it is called out explicitly in §9.4 — never silently dropped.

---

## 1. Lineage: v1 → v2 → v3

| Dimension | v1 (Shivam) | v2 (zero-cost rewrite) | **v3 (this, final)** |
|---|---|---|---|
| Hosting | CF Pages + SAB (cross-origin isolation) | GH Pages, no SAB | **GH Pages, no SAB — confirmed against actual header posture** |
| Resolver | Server-side rustypipe on Hetzner VPS | Client-first decision tree R-C/R-A/R-B | **Same tree, but every branch is now measured (Appendix A): Innertube 403s foreign origins → relay is structurally mandatory; googlevideo serves no ACAO on app-family URLs → media relays too** |
| Gateway | VPS gateway (~$10–20/mo) | ~150-line CF Worker dumb relay | **Same relay + per-song request-budget math against CF free tier** |
| Evidence | Design intent | Inferred from docs | **Measured: probe matrix, CORS header dumps, 206 Range fetches, TTL capture, IP-pin capture** |
| DSP | Web Audio port with SAB ring | MessageChannel + transferables | **Same + frozen-wire facts verified in source (96-byte `SINK_STATS_WIRE_BYTES`, `wire_le` readers, `ring_export` symbols)** |
| Effort | ~41.5 eng-weeks | ~40 eng-weeks | **43.5 eng-weeks** (adds W0.1b residential canary + W0.5 licensed-content PO path) |

What v3 keeps from v2: the entire zero-cost skeleton, ADR set, relay contract, no-SAB audio path, ledger.
What v3 adds: measured evidence replacing inference (§2, Appendix A), the licensed-content honesty clause (§7.4), the per-song free-tier budget (§8.3), the residential canary gate (W0.1b), and the complete Android port map down to screen filenames (§9).

---

## 2. W0.1 Evidence — What Was Actually Measured

All measurements taken from this repository's own tooling (`tools/probe-clients.sh`, `demo_resolve_cdn`, raw curl), from a datacenter-class IP — the **worst-case** IP reputation a resolver can have. Raw data in Appendix A.

| # | Question | Measured answer | Architecture consequence |
|---|---|---|---|
| E1 | Does search resolve end-to-end with the repo's locked stack? | **YES.** `StreamResolver::search_music` (rustypipe 0.11.4) returned 3 candidates, correct videoId (`J7p4bzqLvCw`) | Search tier is proven; no VPS needed for search |
| E2 | Does standard-content playback resolve from a datacenter IP? | **YES, 5 of 10 clients** (ANDROID_VR ×2, ANDROID, IOS, VISIONOS) return `OK` with audio itags 140+251, no `signatureCipher` | PO-free ladder survives the worst IP class for standard content |
| E3 | Does licensed-content playback resolve from a datacenter IP? | **NO — 10 of 10 clients bot-walled** (`LOGIN_REQUIRED: "Sign in to confirm you're not a bot"`). The repo's own `fleet-config.json` documents this: *"CI datacenter IPs are bot-walled by YouTube; survivorship = standard-content resolution"* | Licensed content through any datacenter egress (CF Workers included) is blocked → §7.4 honesty clause + R-C stretch path |
| E4 | Do googlevideo URLs actually deliver bytes? | **YES.** `HTTP 206 Partial Content`, `audio/mp4`, byte-range seeking proven at two offsets (distinct bytes), via one 302 redirect hop | The fetch contract: follow redirects, forward UA, send Range |
| E5 | Can the browser call Innertube directly (no relay)? | **NO.** With `Origin: https://…github.io`, Innertube returns `403` with zero CORS headers | **The relay is structurally mandatory** — this kills any "no-backend" fantasy and confirms R-A as default |
| E6 | Can the browser read googlevideo bytes directly (no relay)? | **NO for app-family URLs.** ANDROID_VR URLs return `Vary: Origin` + `Cross-Origin-Resource-Policy: cross-origin` but **no `Access-Control-Allow-Origin`** | Media bytes must flow through the relay in R-A; R-C (direct browser CDN fetch) requires WEB-family URLs + PO tokens → stretch path |
| E7 | How long do resolved URLs live? | `max-age=21300` / `expiresInSeconds` ≈ **5.9 hours** | KV cache TTL = 5 h with safety margin (v2's 6 h estimate confirmed) |
| E8 | Is the CDN URL IP-pinned? | URL embeds `ip=<resolver-egress>` + `ipbypass=yes` on redirect | Resolution egress and media egress should be the same relay; cross-IP tolerance is a W0.1b measurement item |
| E9 | Does the format metadata include loudness? | **YES** — itag 140 carries `loudnessDb: 0.99`, `trackAbsoluteLoudnessLkfs`, `contentLength`, `indexRange/initRange` | The web DSP gets the same loudness inputs the APK gets — parity for free |
| E10 | Does the repo engine work natively? | Built in 97 s; search OK; player call on licensed content failed (E3 posture). The engine's PO-free ladder (Desktop→iOS→TV) matches the measured matrix: IOS-class = OK for standard content | The repo ladder logic is sound; the demo failure is the licensed/datacenter wall, not an engine bug |

**Verdict on Shivam's question ("does rustypipe solve links and CDN — can we guarantee VPS replacement?"):**

- **Search + standard-content playback + CDN byte delivery: PROVEN end-to-end, natively, from the worst IP class.** No VPS is required for these.
- **Licensed content: blocked from datacenter egress, all clients.** A VPS would not fix this either (it is also a datacenter IP). The only paths are residential-IP browser resolution (R-C stretch) or PO tokens. **A VPS buys nothing here — the VPS is dead on technical merit, not just cost.**

---

## 3. Repo Ground Truth (fresh clone @ `4758ac6`, WS-C #154 merged)

| Asset | Measured size | Web reuse |
|---|---|---|
| Rust engine (`rust/crates/`, 8 crates) | **65,326 LOC** | Core + playback + audio + resolver + storage logic recompiled to wasm32; contract/ffi/jam partially reused (§9.1) |
| C++ DSP (`native/`, 15 headers, all HALs) | **146,509 LOC** | DSP/mix cores compiled via `cc` crate into the wasm module; platform HALs (ALSA/CoreAudio/WASAPI/PipeWire) are **not** ported — Web Audio replaces them (§9.2) |
| Android UI (`app/`, 23 screens + 5 shared components) | **96,155 LOC** Kotlin (app+shared) | 23 screens filename-mirrored to React; shared components ported 1:1 (§9.3) |
| FFI surface | **124 symbols** (≈62 plain-C `extern "C"` + ≈62 JNI bridge fns) | The C surface becomes the wasm-bindgen export list; JNI surface is Android-only (§9.1) |
| Frozen ABI | `SINK_STATS_WIRE_BYTES = 96` (`streamify-playback/src/sink.rs:42`), `wire_le.rs` readers (`le_u16/u32/u64/i64/fixed<N>`) in `streamify-core`, 4 `streamify_ring_*` externs in `ring_export.rs` | Frozen discipline extended to the web chunk header (§10) |
| rustypipe | **pinned 0.11.4**; PO tokens via **companion-binary protocol** (`rustypipe-botguard` spawn + QuickJS evaluator); PO-free ladder = Desktop→iOS→TV; legacy raw-Innertube tier kept as degraded final fallback | 0.11.4's QuickJS (rquickjs-sys 0.9) **has no wasm bindings** → web rides the PO-free ladder (W0.4 gate decides the rest) |
| Caching | Two-layer SQLite: deobf/player.js transforms (cold-start layer) + resolved format sets TTL'd by `valid_until` | Web maps to OPFS/IndexedDB with identical layering (§9.1) |
| CI | `resolver-canary.yml` daily 06:30 UTC: probe matrix → assertion + licensed verdict + **fleet-rotation PR** (data-only `fleet-config.json` changes) | Web deploys ride the same canary (§12) |
| Supabase | `schema.sql` + migrations (stats overhaul, SEC-10 RLS hardening, drift catchup), `jam_rpc.sql`, `jam_lease.sql`, RLS test suite | Reused as-is, zero schema edits except one additive table (§11) |
| Fleet config | `fleet-config.json` v20261025: `signatureTimestamp: 19850`, client ladder ANDROID 21.26.364 first, survivorship = standard content | Baked into the web resolver defaults; same rotation source |

---

## 4. Target Topology — Four Runtimes, One Engine

```
┌────────────────────────────────────────────────────────────────────┐
│                        STREAMIFY ENGINE (Rust)                     │
│  core · playback · audio · resolver · storage · jam · contract     │
│  (65,326 LOC — one codebase, four compilation targets)             │
└──────┬──────────────┬──────────────┬──────────────┬────────────────┘
       │ cdylib       │ cdylib       │ cdylib       │ wasm32-unknown-unknown
       │ + JNI        │ + UniFFI     │ + cdylib     │ + wasm-bindgen/tsify-next
┌──────▼──────┐ ┌─────▼───────┐ ┌────▼────────┐ ┌───▼─────────────────┐
│ Android APK │ │ iOS app     │ │ Desktop CMP │ │ WEB (this plan)     │
│ Kotlin/     │ │ Swift/      │ │ Compose     │ │ TypeScript + WASM   │
│ Compose     │ │ SwiftUI     │ │ Desktop     │ │ React, GH Pages     │
│ (96k LOC)   │ │             │ │             │ │ front-end/          │
└──────┬──────┘ └─────┬───────┘ └────┬────────┘ └───┬─────────────────┘
       │               │              │              │
       └───────────────┴──────┬───────┴──────────────┘
                              ▼
                    Supabase (auth · Postgres+RLS · RPC ·
                    realtime jam · storage)  ← identical for all four
                              ▲
                              │ (web only, additionally)
                    Cloudflare Worker relay (~150 LOC, dumb pipe)
```

The web runtime is a **fourth consumer of the same engine**, exactly like iOS (UniFFI) and desktop (cdylib) already are. It is not a rewrite — it is a new `front-end/` workspace that compiles the same crates with `wasm32-unknown-unknown` and binds them with `wasm-bindgen` + `tsify-next`, mirroring the UniFFI contract surface.

---

## 5. The `front-end/` Monorepo

Lives at the repo root (`front-end/`, this folder). **Zero edits inside `rust/`** — that workspace's build matrix (Android/iOS/desktop lanes, 429 tests) is frozen and CI-protected. The web build is additive:

```
front-end/
├── WS-W-MASTER-PLAN-v3.md          ← this document
├── Cargo.toml                       ← own workspace; path-deps into ../rust/crates/*
├── crates/
│   ├── streamify-wasm/              ← wasm-bindgen facade mirroring the UniFFI contract
│   │   ├── src/lib.rs               ←   (same operations, same semantics, tsify-next types)
│   │   └── src/audio_glue.rs        ←   ring/stats wire → JS chunk protocol (§10)
│   ├── streamify-dsp-cpp/           ← C++ DSP cores via cc crate (§9.2)
│   └── streamify-web-cache/         ← OPFS/IndexedDB cache (deobf layer + format-TTL layer)
├── web/                             ← the TypeScript app (Vite + React)
│   ├── src/engine/                  ← WasmEnginePort (EnginePort parity, §9.3)
│   ├── src/audio/                   ← AudioWorklet sink, chunk fetcher, MediaSource-free pipeline
│   ├── src/state/                   ← Zustand stores (ViewModel parity)
│   ├── src/screens/                 ← 23 screens, filename-mirrored (§9.3)
│   ├── src/components/              ← shared components 1:1 (§9.3)
│   ├── src/data/                    ← Supabase client, repositories
│   └── src/relay/                   ← CF relay client
├── relay/                           ← Cloudflare Worker source (~150 LOC, §8)
├── canary/                          ← W0.1b residential probe page (deployed with the site)
└── .github/  (in repo root workflows) deploy + canary spokes (§12)
```

**Golden rule:** `rust/` is a read-only dependency from `front-end/`'s perspective. Any engine change the web needs is made in `rust/` itself (benefiting all four runtimes), never patched around.

---

## 6. Architecture Decision Records (ADR-001…014)

Each ADR carries a **kill criterion** — the measured condition under which it is abandoned, per the repo's own ADR discipline.

| ADR | Decision | Rationale (v3 evidence) | Kill criterion |
|---|---|---|---|
| 001 | wasm-bindgen + tsify-next facade mirroring the UniFFI contract | Fourth consumer of one engine; UniFFI itself does not target wasm-bindgen | Facade drift breaks >2 contract ops in a release → freeze facade, add contract tests |
| 002 | GH Pages static hosting, hash-free SPA routes | Constraint #1; single `index.html`, assets at `/` | — (mandated) |
| 003 | No SharedArrayBuffer anywhere | gh-pages sends no COOP/COEP headers (verified posture); SAB unavailable | If GitHub ever ships custom headers → revisit, not before |
| 004 | Worker ↔ AudioWorklet over `MessageChannel` + transferable `ArrayBuffer` chunks | Consequence of 003; zero-copy where it matters | Measured underruns >0.1% on median hardware → chunk-size retune, not redesign |
| 005 | Stable Rust toolchain, no nightly/build-std | Single-threaded worklet → no atomics → no `+atomics` target needed | A crate hard-requires nightly → vendor a patched fork behind a feature flag |
| 006 | Audio decode: WebCodecs `AudioDecoder` first, symphonia-wasm fallback | E10/E9: AAC (itag 140) is the primary format; WebCodecs covers AAC/Opus natively | WebCodecs coverage <90% of beta fleet → symphonia becomes default |
| 007 | Opus (itag 251) via symphonia (no Opus in shipped symphonia) — add `symphonia-codec-opus` | itag 251 is the ladder's secondary audio format | Decoder fails golden vectors → drop 251, keep 140 only |
| 008 | Resolver default = **R-A** (rustypipe-logic-in-browser via dumb relay) | E5: Innertube 403s foreign origins → relay mandatory; keeping ladder/deobf logic client-side keeps the relay dumb and CPU off Workers | Relay latency >400 ms p95 for resolution → move ladder server-side (R-B) |
| 009 | Media bytes flow through the relay in R-A | E6: no ACAO on app-family CDN URLs | R-C proven (W0.1b) → flip media to direct fetch |
| 010 | Relay = CF Worker, KV cache, JWT-gated, allowlisted, kill-switched | $0 tier; §8 contract | CF policy change / ceiling breach → same code deploys to Supabase Edge (R-B) |
| 011 | OPFS/IndexedDB for deobf + format caches, SQLite-shaped API | Parity with repo's two-layer SQLite cache semantics | Safari OPFS gaps in beta fleet → IndexedDB-only shim |
| 012 | Clock = `AudioContext.currentTime` + performance.now() PLL, epoch semantics transplanted from MP-C10 generational flush | v2 ADR-012; no changes | Drift >±10 ms over a track → re-anchor per chunk |
| 013 | Resolver decision tree R-A default / R-B fallback / R-C stretch (§7) | E2/E3/E5/E6 measured | per-branch, see §7 |
| 014 | Legal gate: invite-only beta, `MediaProvider` abstraction, kill switch, no shared cookies/OAuth scraping ever | Constraint on the whole project | Any C&D or ToS escalation → kill switch flips relay off (site still renders; playback disabled) |

---

## 7. Resolver Decision Tree (measured)

```
                     user presses play
                           │
                ┌──────────▼──────────┐
                │  R-A  (DEFAULT)     │   rustypipe logic compiled to wasm32,
                │  resolution via     │   runs IN THE BROWSER; its HTTP rides
                │  dumb CF relay      │   the relay (E5: browser→Innertube is 403)
                └──────────┬──────────┘
              standard content?        licensed content?
                 │ (E2: YES)                │ (E3: bot-walled from datacenter egress)
        ┌────────▼────────┐      ┌──────────▼──────────┐
        │ resolve via     │      │ degrade honestly:   │
        │ PO-free ladder  │      │ "Licensed track     │
        │ VR/IOS class    │      │  unavailable in     │
        │ → CDN URL       │      │  web beta" + R-C    │
        │ → media bytes   │      │  upgrade path       │
        │   via relay     │      └──────────┬──────────┘
        │   (E6: no ACAO) │                 │
        └────────┬────────┘      ┌──────────▼──────────┐
                 │               │  R-C  (STRETCH)     │
                 │               │  in-browser WEB-    │
                 │               │  family resolution  │
                 │               │  + BotGuard PO      │
                 │               │  tokens + ACAO +    │
                 │               │  IP-pin tolerance   │
                 │               │  → zero-relay bytes │
                 │               │  (W0.1b gates this) │
                 │               └─────────────────────┘
        ┌────────▼────────┐
        │  R-B  (FALLBACK)│   if CF relay is down / kill-switched /
        │  Supabase Edge  │   ceiling-breached: same contract on
        │  (Deno)         │   Supabase Edge Functions, rustypipe-wasip1
        └─────────────────┘
```

### 7.1 R-A in detail (default)

- `streamify-resolver` + rustypipe's **deobf/signature/ladder logic** compiled into the browser wasm module. PO companion binary is impossible in wasm (rquickjs-sys 0.9 has no wasm bindings — W0.4 verifies at build time) → the PO-free ladder (Desktop→iOS→TV, matching the shipped iOS posture) is the web posture.
- Every rustypipe HTTP fetch is tunneled through the relay (§8). The relay adds **no `Origin`** — measured E5 shows the 403 only triggers on foreign `Origin`; server-side fetches pass.
- The relay egress resolves the URL → the CDN URL is IP-pinned to the relay (E8) → **the relay also streams the media bytes back** (consistent egress, no pin mismatch). Range requests pass through 1:1 (E4: 206 + true seeking proven).
- Resolution results cached in KV keyed `videoId+client`, TTL 5 h (E7) — repeat plays don't re-resolve.

### 7.2 R-B in detail (fallback)

Identical contract, different substrate: Supabase Edge Functions (Deno runtime, 500k invocations/mo free) running rustypipe compiled to `wasm32-wasip1`. Exists for two reasons: relay redundancy (CF outage / ToS policy change) and the kill-switch independence requirement (ADR-014: if CF kills the Worker, Supabase can carry resolution alone until a decision is made).

### 7.3 R-C in detail (stretch — W0.1b gated)

The zero-relay-bytes endgame: browser resolves via WEB-family clients (which historically receive `Access-Control-Allow-Origin` on CDN URLs) using **in-browser BotGuard PO token generation** (the technique youtubei.js-class projects use — the same JS challenge a real browser can run), then fetches googlevideo directly with the user's residential IP. Four independent conditions must hold: WEB-family resolution from residential IP, PO token minting in-browser, ACAO on the returned URLs, IP-pin tolerance for relay-resolved variants. All four are measured by the W0.1b residential canary (§12). **No engineering is spent on R-C before the canary proves all four.**

### 7.4 Licensed-content honesty clause

The Android app resolves licensed music because it runs on a **residential mobile IP** with the botguard companion binary. The web behind a CF relay shares the datacenter posture measured in E3: licensed content returns `LOGIN_REQUIRED` across all 10 probed clients. **A VPS would not change this.** The web beta therefore ships with: standard-content playback at full parity (E2), licensed content explicitly marked "unavailable in web beta" in the UI (never a spinner), and R-C as the only genuine fix (residential IP + PO). This is written into the beta consent screen so no user expectation is violated.

---

## 8. The Cloudflare Relay Contract (~150 LOC)

```
REQUEST   GET /r?u=<urlencoded-target>          (Authorization: Bearer <supabase-JWT>)
          ├─ 1. verify JWT against Supabase JWKS (issuer, aud, exp)
          ├─ 2. check beta_allowlist (KV-cached 5 min) → 403 if absent
          ├─ 3. KILL SWITCH: KV "relay_enabled" == false → 503 (site still serves UI)
          ├─ 4. host allowlist: www.youtube.com, music.youtube.com,
          │      *.googlevideo.com, youtubei.googleapis.com  → else 400
          ├─ 5. strip Origin/Referer; forward method, Range, UA (client-supplied,
          │      from the fleet ladder), Content-Type, body — nothing else
          ├─ 6. rate limit: KV counter per user, 600 resolution + N media
          │      chunk requests / hour (N sized by §8.3 budget)
          └─ 7. fetch(target) → stream response body through (no parsing, no
                 transformation, no logging of URLs or videoIds)
RESPONSE  status + headers (Content-Type, Content-Range, Accept-Ranges) + body
          media may 302-redirect (E4) → relay follows, streams final 206
```

Design invariants: **dumb pipe** (never parses YouTube payloads — the parsing lives in client-side rustypipe-wasm), **JWT-only** (no anonymous use), **allowlist-only** (no open proxy, ever), **kill-switchable in one KV write**, **log-free** (no user ↔ content linkage stored).

### 8.1 What rides the relay

| Traffic | Rides relay? | Why |
|---|---|---|
| Innertube search/player (rustypipe HTTP) | Yes | E5: browser→Innertube = 403 |
| googlevideo media bytes | Yes (R-A) | E6: no ACAO on app-family URLs |
| Supabase (auth, RPC, realtime jam) | **No** | Supabase serves CORS properly; browser talks to it directly |
| Static assets | **No** | gh-pages CDN |

### 8.2 Caching layers

| Layer | Store | TTL | Key |
|---|---|---|---|
| Resolution results | CF KV | 5 h (E7) | `res:<videoId>:<client>` |
| Allowlist membership | CF KV | 5 min | `allow:<userId>` |
| Deobf/player.js transforms | **client** OPFS (ADR-011) | rustypipe-managed | — |
| Format sets (repeat plays) | **client** OPFS | `valid_until` − margin | `fmt:<videoId>` |

### 8.3 Free-tier budget (per-song math, CF Workers = 100k req/day)

A 3.5-minute track at itag 140 (≈130 kbps) ≈ **3.4 MB** (E4 measured `clen=3449447`). With 256 KB chunks: ~14 chunk requests/song + ~2 resolution calls (KV-cached on replay) ≈ **16 requests/song** → **~6,000 songs/day** fleet-wide before hitting 100k. Beta cap of 40 users × 10 songs/day = 400 songs/day = 6,400 requests — **6% of the daily ceiling**. Chunk size is a single constant; if the fleet grows, raise it to 1 MB (→ ~5 requests/song, ~20k songs/day ceiling). KV reads stay ≤ ~2/song ≈ 8% of the 100k read ceiling. **No free-tier ceiling is reachable at beta scale.**

---

## 9. The Complete Android → Web Port Map

### 9.1 Rust crates (8) → wasm compilation units

| Crate | Web disposition |
|---|---|
| `streamify-core` | **Compile to wasm as-is** (queue engine, wire_le, PTP timestamps, fuzzy rank — all pure Rust). The `wire_le` readers become the web chunk-header readers (§10) |
| `streamify-playback` | **Compile to wasm** (ring, sink, stats). The 4 `streamify_ring_*` C symbols (read_f32, occupancy, channels, stats_wire) become wasm exports; the 96-byte stats wire is served to JS verbatim (frozen ABI honored) |
| `streamify-audio` | **Compile to wasm** (DSP orchestration: init/free/process, normalizer, seek guard). Native-only paths (thermal governor) stub to no-ops behind `#[cfg(target_arch = "wasm32")]` |
| `streamify-resolver` | **Compile to wasm** (search, ladder, deobf logic, lyrics/LRC, playlist parser, tagger, radio scorer, continuum, neuro queue). rustypipe HTTP → relay tunnel; SQLite cache → `streamify-web-cache`. Spotify ingest stays (public PKCE API, CORS-clean). **PO companion-binary path is `#[cfg(not(target_arch = "wasm32"))]`** — the PO-free ladder is the web posture (E10) |
| `streamify-storage` | **Thin port**: repository layer re-bound to OPFS/IndexedDB via `streamify-web-cache`; SQL semantics preserved (same upserts, same virtual shelf) |
| `streamify-jam` | **Compile to wasm as-is** (CRDT ops, outbox, tick matrix, Kalman PLL, election) — transport swaps Supabase realtime websocket for the browser's, which is the same protocol |
| `streamify-contract` | **Compile to wasm**; platform-specific trampolines (iOS session, Apple remote) are `#[cfg]`-ed out; `MediaProvider` trait gains the `WebRelayProvider` as its **second implementation** (ADR-014) |
| `streamify-ffi` | **Not compiled for web.** Its 124-symbol surface (JNI + UniFFI trampolines) is Android/iOS/desktop only. The wasm facade (`streamify-wasm`) mirrors the *operations*, not the FFI mechanics |

### 9.2 C++ DSP (146.5k LOC) → wasm

`native/` splits into **portable cores** (`dsp/`, `mix/`, `include/audio_sink_ringbuffer.h`, LUFS, limiter, frame pacer, palette SIMD) and **platform HALs** (ALSA, CoreAudio, WASAPI, PipeWire, iOS session, Apple remote, wear encoder). The web ports only the cores:

- Compiled into `streamify-dsp-cpp` via the `cc` crate (`-O2 -ffp-contract=off`, matching the repo's bit-exactness discipline) → linked into the wasm module → called from `streamify-audio`'s orchestration exactly as on Android.
- HAL layer is **replaced by Web Audio**: `AudioWorkletNode` is the sink, `MessageChannel` is the ring (ADR-004). The worklet is single-threaded → no atomics → stable toolchain (ADR-005).
- Golden vectors from `fixtures/` run in CI headless-Chrome against the wasm build; **bit-exact output vs the native build is the acceptance bar** for every DSP PR.

### 9.3 Kotlin UI (23 screens + 5 shared components) → React

**Filename mirroring** — every screen keeps its name so diffs stay reviewable across runtimes:

| Android (`app/…/screens/`) | Web (`front-end/web/src/screens/`) |
|---|---|
| `HomeScreen.kt`, `UniversalHomeScreen.kt` | `HomeScreen.tsx`, `UniversalHomeScreen.tsx` |
| `SearchScreen.kt` | `SearchScreen.tsx` |
| `PlayerScreen.kt`, `QueueScreen.kt`, `LyricsScreen.kt` | same names |
| `LibraryScreen.kt`, `AlbumScreen.kt`, `ArtistScreen.kt` | same names |
| `EqualizerScreen.kt`, `DownloadScreen.kt`, `StatsWrappedScreen.kt` | same names |
| `JamSessionScreen.kt`, `BlendScreen.kt`, `CommunityHubScreen.kt` | same names |
| `SettingsScreen.kt`, `UserProfileScreen.kt`, `ProfileSelectionScreen.kt` | same names |
| `AdminDashboardScreen.kt`, `AdminTerminalScreen.kt`, `UpdatesHubScreen.kt` | same names |
| `PrismaticSplashScreen.kt`, `YtOnboardingScreen.kt` | same names |

Shared components port 1:1: `MiniPlayerBar`, `PlaybackSeekbar`, `QueueList`, `TrackListItem`, `VolumeSlider` (+ `StreamifyTheme` → CSS custom properties + the same token values).

**State mapping:** Android ViewModels → **Zustand stores** with the same store-per-screen discipline; `PlaybackController` (shared state machine) ports to a Zustand slice that commands `WasmEnginePort` — the exact role `EnginePort` plays on Android. **`WasmEnginePort` implements the same `EnginePort` interface** (`shared/…/engine/EnginePort.kt` semantics: play/pause/seek/queue ops/stats polling), so the ported `PlaybackController` logic is a translation, not a redesign.

### 9.4 Feature-parity buckets (honest scope)

| Bucket | Features | Web disposition |
|---|---|---|
| **A — full parity** | search, playback (standard content), queue, library, albums/artists, lyrics (LRC parse + align), equalizer (DSP cores), loudness normalization (E9 metadata), stats/wrapped, jam sessions (CRDT over Supabase realtime), profile selection, settings, onboarding, splash, admin dashboard | Ported 1:1 |
| **B — web-adapted parity** | downloads → OPFS offline cache (same chunk-seal-crypt pipeline, `rust_seal_cache_chunk`/`rust_crypt_audio_chunk` run in wasm); blend/community hub → same Supabase data, browser push limits apply; thermal governor → no-op; updates hub → app shows site version + APK link | Ported with platform swaps, feature intact |
| **C — degraded, documented** | licensed music (E3, §7.4); background playback (media-session API where supported); Android-specific hardware paths | Explicit UI state, never silent |

---

## 10. Audio Path Without SharedArrayBuffer

The APK's path is `Rust engine → SPSC ring (SpscAudioRing) → Oboe AAUDIO callback`, with the 96-byte stats wire read by the UI at 30 Hz. The web path preserves the semantics, swaps the transport:

```
rustypipe-wasm (resolve via relay)
      │ ArrayBuffer chunk (f32 PCM from WebCodecs/symphonia → engine)
      ▼
streamify-core/playback in wasm  ── runs the same queue engine, DSP cores,
      │                             seek guard, normalizer as the APK
      │ f32 frames + 16-byte header (extending the wire_le discipline:
      │ magic u32 · epoch u32 · seq u64 — panic-free little-endian,
      │ same style as le_u16/u32/u64/i64/fixed<N>)
      ▼  postMessage(transferable ArrayBuffer) over MessageChannel
AudioWorklet ("sink")  ── pulls, applies final fade via AudioParam (clock
      │                  = AudioContext.currentTime, ADR-012; epoch =
      │                  MP-C10 generational flush semantics transplanted:
      │                  stale-epoch chunks are dropped, never partially)
      ▼
AudioDestinationNode → speakers
      ▲
stats: worklet returns the same 96-byte SINK_STATS_WIRE image the C++ HAL
produces (frozen ABI honored verbatim); JS parses it with the same field
offsets → PlayerScreen/EqualizerScreen UIs render identical meters
```

- **Chunking replaces the ring.** The SPSC ring's contract (no torn reads, backpressure, epoch-stable stats) is preserved by: transferable chunks (no copy, no tear), an epoch that invalidates across seeks/reconfigs, and the worklet's own jitter buffer sized in frames (W1 tunes it; underruns are the kill metric in ADR-004).
- **No atomics, no nightly.** Single worklet thread; `Atomics` never appears; the build runs on stable `wasm32-unknown-unknown` (ADR-005).
- **Seek path:** UI → `WasmEnginePort.seek()` → `submit_seek_request`/`consume_pending_seek` (the repo's own seek-guard pattern, wasm-exported) → worklet drops stale-epoch chunks until the new epoch arrives. This is the MP-C10 flush, transplanted.

---

## 11. Supabase — Reuse Everything, Add One Table

- **Auth**: identical (email OTP + GitHub OAuth as the APK ships). PKCE flow in-browser; `@supabase/supabase-js`; JWT is what the relay verifies (§8).
- **Schema**: `schema.sql` + all three 2026 migrations applied as-is. RLS (post-SEC-10 hardening) needs zero web changes — RLS is client-agnostic.
- **Jam**: `jam_rpc.sql` + `jam_lease.sql` + realtime channels reused verbatim; the CRDT/op engines live in wasm (`streamify-jam`), transport is the same websocket protocol.
- **The single additive migration** (`2026_12_web_beta_allowlist.sql`):

```sql
create table if not exists public.beta_allowlist (
  user_id uuid primary key references auth.users(id) on delete cascade,
  invited_by uuid not null references auth.users(id),
  invited_at timestamptz not null default now(),
  revoked_at timestamptz
);
-- RLS: users read own row; admins (existing role) manage all.
-- The relay checks membership (KV-cached 5 min).
```

- **Keepalive vs the 7-day pause** (free tier pauses idle projects): a GH Actions cron (`keepalive.yml`, daily) calls `SELECT 1` plus a realtime ping through an RPC — an existing-pattern 20-line workflow. If Supabase ever changes the pause policy, the cron is deleted, nothing else moves.

---

## 12. CI & Deploy (riding the repo's existing spokes)

| Workflow | Runs | What it does |
|---|---|---|
| `web-build.yml` (new, 6th spoke) | PRs touching `front-end/**` | `cargo test --target wasm32-unknown-unknown -p streamify-wasm` (headless Chrome via wasm-bindgen-test) + Vite build + lint + **DSP golden vectors bit-exact** vs native artifacts |
| `web-deploy.yml` (new) | merge to `main` | Vite build → `gh-pages` branch push (official `actions/deploy-pages`). ~45 s. Static hash-named assets, immutable cache headers by GH |
| `resolver-canary.yml` (existing) | daily 06:30 UTC | Unchanged — its probe matrix (Appendix A is literally this matrix) feeds fleet rotation; the **web resolver consumes the same `fleet-config.json`** |
| `web-canary.yml` (new) | daily after resolver-canary | Plays a fixed standard-content track end-to-end through the deployed site + relay (headless Chrome, real JWT), asserts audible PCM length; pages on failure |
| `keepalive.yml` (new) | daily | §11 pause guard |
| **W0.1b residential canary** (`canary/`, shipped with the site) | on-demand from invited browsers | Runs the probe matrix (E2/E3) **plus** the four R-C conditions (§7.3) from real residential IPs through the relay; results posted to a Supabase table. This is the only way to measure the things a datacenter cannot |

Deploy is a **push to `gh-pages`** — nothing else. Rollback = re-push the previous commit's artifact (kept by Actions).

---

## 13. Legal Gate (hardened, ADR-014)

1. **Invite-only beta.** `beta_allowlist` (§11) gates the relay; the site itself is public but playback requires sign-in + membership. No public launch is planned under this plan.
2. **`MediaProvider` abstraction.** `WebRelayProvider` is the second implementation of the same trait the APK uses. A future licensed/DRM provider can replace it without app changes. Every product surface (search UI, jam, library) is provider-agnostic.
3. **One kill switch.** `relay_enabled = false` in KV → all playback stops in ≤5 min, UI stays up, library/lyrics/jam (Supabase-side) keep working. Documented in the runbook, tested in `web-canary`.
4. **No shared accounts, no cookie scraping, no OAuth against YouTube.** The resolver uses the same anonymous Innertube posture the APK ships. Users' Supabase accounts are for *our* backend only.
5. **Data hygiene.** The relay logs no user↔content linkage (§8). Supabase stores only what the APK already stores. The W0.1b canary stores probe results (client posture, not identity).
6. **Honest consent.** The beta consent screen states: unofficial client, invite-only, standard-content parity, licensed-content limitations (§7.4).

---

## 14. Execution Ledger — 43.5 eng-weeks, gates G0/G1/G2/G4

| Wave | Work | Weeks | Gate |
|---|---|---|---|
| **W0 — Proof (partially done)** | ~~W0.1 datacenter probe~~ **DONE — Appendix A** · W0.1b residential canary (R-C's four conditions + licensed posture from residential IPs) · W0.2 rustypipe 0.11.4→wasm32 build spike (PO-free ladder compiles; rquickjs-sys confirmed absent from tree) · W0.3 Vite+React+gh-pages skeleton deployed · W0.4 wasm facade spike: `search_music` through relay in a browser · W0.5 decide R-C spend from W0.1b data | 3.5 | **G0**: browser resolves + plays 10 s of a standard track end-to-end. Kill: if the relay path cannot stream 206 bytes to wasm in-browser, the plan stops here |
| **W1 — Audio spine** | WebCodecs decode · symphonia fallback (+Opus, ADR-007) · MessageChannel sink + 16-byte header + epoch flush · 96-byte stats wire to UI · seek guard port · golden vectors green | 8 | **G1**: bit-exact DSP on vectors; underruns <0.1% on median hardware |
| **W2 — Engine breadth** | queue engine, library, downloads→OPFS (chunk seal/crypt), lyrics/LRC, tagger, radio scorer, continuum, neuro queue ports · Zustand stores + `WasmEnginePort` | 10 | **G2**: full bucket-A feature pass on desktop Chrome |
| **W3 — Product surface** | 23 screens + 5 components · theme tokens · jam (realtime + CRDT) · stats/wrapped · admin · onboarding/splash · media-session background playback where supported | 12 | internal demo at APK-parity walkthrough |
| **W4 — Harden & ship beta** | relay hardening (§8 full) · kill-switch drill · `web-canary` + deploy spokes · keepalive · consent screen · 40-user allowlist · 2-week soak | 10 | **G4 ship criteria** (§16) |

3 engineers ≈ 18–22 calendar weeks. Waves overlap (W2 starts at G1).

---

## 15. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Licensed content stays walled (E3) | certain short-term | user-visible gap | §7.4 honesty clause; R-C is the only true fix; W0.1b decides spend |
| YouTube rotates clients/STS again | certain over time | resolution breaks | **The repo already operates the fix**: daily canary + fleet-rotation PRs; web consumes the same `fleet-config.json` |
| rustypipe 0.11.4 wasm build hits a native-only dep (beyond rquickjs, e.g. reqwest/tokio surface) | medium | W0.2 slips | spike is W0.2's entire job; `wasm32-wasip1` + R-B is the escape hatch |
| CF policy change on relaying media bytes | low | playback via CF ends | R-B (Supabase Edge, same contract, 500k invocations) + kill switch |
| Free-tier ceiling (§8.3 math wrong) | low | throttle, not outage | chunk-size constant; allowlist cap; per-user rate limits already in relay |
| Safari gaps (WebCodecs/OPFS/worklet) | medium | degraded on iOS-browser | feature-detected fallbacks; symphonia path covers decode; beta measures fleet coverage |
| DSP golden vectors fail bit-exactness in wasm | low | W1 slips | `-ffp-contract=off` already the repo's discipline; if it still drifts, wasm output becomes the new reference with a documented delta |
| gh-pages SPA route limits | none (single-page app) | — | — |

---

## 16. Ship Criteria (G4)

1. Every bucket-A feature passes the same walkthrough script on web and APK, same account, same Supabase data.
2. DSP bit-exact on all golden vectors (CI-enforced).
3. Underruns <0.1% at p50 hardware; <1% at p90.
4. Relay: JWT-verified, allowlist-enforced, kill-switch drilled (documented flip test), zero logged user↔content linkage.
5. Soak: 40 invited users, 2 weeks, ≥95% playback success on standard content, zero free-tier breaches.
6. Rollback demonstrated (re-push artifact).
7. Legal gate items (§13) all green in the ship review.

---

## Appendix A — W0.1 Measured Data (raw)

### A.1 Environment
- Repo: `streamify-apk` @ `4758ac6` (WS-C #154 merged), fresh shallow clone
- rustypipe: **0.11.4** (Cargo.lock), PO companion-binary protocol, PO-free ladder Desktop→iOS→TV
- Egress IP class: **datacenter** (worst case)
- Toolchain: stable `cargo/rustc 1.99.1`; `demo_resolve_cdn` built in 97 s

### A.2 Native engine run (`cargo run --example demo_resolve_cdn`)
```
Search "Blinding Lights The Weeknd" → 3 candidates:
  [1] Blinding Lights | The Weeknd | J7p4bzqLvCw | 202s   ← correct top hit
  [2] Blinding Lights - Slowed | Eiden XII | yjGStdqbZrY | 240s
  [3] Blinding Lights (Metal Version) | Leo | tDSf2zL8Res | 241s
resolve_stream(J7p4bzqLvCw) → network error: Http (status 404)
  → diagnosed via A.3: licensed content, datacenter IP → bot wall (E3)
```

### A.3 Bare-client probe matrix (`tools/probe-clients.sh`, this session)

Standard content `dQw4w9WgXcQ`:

| Client | Status | Audio (itag 140+251) | Formats | Ciphered |
|---|---|---|---|---|
| ANDROID_VR 1.60.19 | **OK** | 2 | 26 | no |
| ANDROID_VR 1.65.10 | **OK** | 2 | 26 | no |
| ANDROID 21.26.364 | **OK** | 2 | 29 | no (URLs withheld — serverAbr only) |
| IOS 21.26.4 | **OK** | 2 | 27 | no |
| VISIONOS 1.02 | **OK** | 2 | 27 | no |
| MWEB / TVHTML5 ×2 / WEB_EMBEDDED / WEB_REMIX | UNPLAYABLE/ERROR | 0 | 0 | — |

Licensed content `4NRXx6U8ABQ` and `J7p4bzqLvCw`: **all 10 clients `LOGIN_REQUIRED` "Sign in to confirm you're not a bot"** — matching `fleet-config.json`: *"CI datacenter IPs are bot-walled by YouTube; survivorship = standard-content resolution; licensedProbe is informational."*

### A.4 CDN byte delivery (ANDROID_VR, itag 140, `dQw4w9WgXcQ`)
```
URL host: rr5---sn-oji3bc-5n.googlevideo.com  (302 → rr2---sn-oji3b5-5n)
Range bytes=0-4095        → HTTP 206, 4096 B, Content-Type: audio/mp4
Range bytes=100000-106553 → HTTP 206, 6554 B   (distinct bytes — true seeking)
Content-Range: bytes 0-1023/3449447  (clen ≈ 3.4 MB)
metadata: loudnessDb 0.99, trackAbsoluteLoudnessLkfs, initRange/indexRange present
TTL: max-age=21300 (≈5.9 h)
```

### A.5 CORS posture (headers dumped, `Origin: https://streamify.github.io`)
```
www.youtube.com/youtubei/v1/player    → HTTP 403, NO CORS headers  (E5)
music.youtube.com/youtubei/v1/search  → HTTP 403, NO CORS headers  (E5)
googlevideo 302 hop                   → Vary: Origin, CORP: cross-origin, NO ACAO
googlevideo 206 hop                   → Vary: Origin, CORP: cross-origin, NO ACAO  (E6)
URL params observed: ip=<resolver-egress>, ipbypass=yes  (E8)
```

### A.6 Consequences (traceability)

| Evidence | Section using it |
|---|---|
| E1 search OK | §2, §7.1 |
| E2 standard OK ×5 clients | §2, §7, §14 G0 |
| E3 licensed walled | §7.4, §13.6, §15 |
| E4 206/seek/redirect/UA | §8 (relay fetch contract), §10 |
| E5 Innertube 403 foreign origin | §6 ADR-008, §7.1, §8.1 |
| E6 no ACAO on CDN | §6 ADR-009, §7.1, §7.3 |
| E7 TTL 5.9 h | §8.2 |
| E8 IP pin | §7.1, §7.3, W0.1b |
| E9 loudness metadata | §9.4 bucket A |
| E10 engine native run | §2 verdict, W0.2 |

---

## Appendix B — Credits & Change Log

- **v1** (Shivam): the original WASM port architecture; eight sound inversions that survive into v3 (engine-not-rewrite, UniFFI-contract facade, frozen ABI, golden vectors, provider abstraction, decision-tree resolver, canary culture, additive Supabase).
- **v2**: zero-cost GH Pages rewrite under the four hard constraints; R-C/R-A/R-B tree; relay contract; no-SAB audio path; ADR-001…014.
- **v3 (this)**: everything in v2, with inference replaced by measurement (Appendix A), the licensed-content honesty clause, the per-song free-tier budget, the residential canary (W0.1b), the complete port map (crates/C++/23 screens/124 symbols), and the final 43.5-week ledger.

*Prepared for review by Shivam & the product owner. Execute W0.1b next.*
