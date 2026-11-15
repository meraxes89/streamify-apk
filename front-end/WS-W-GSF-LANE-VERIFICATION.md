# WS-W GSF Lane Verification — Real-Browser Matrix (Item 3)

**Track:** WS-W (front-end) · **Date:** 2026-11-15 · **Branch:** `ws-w/home-teardown` (#173)
**Scope:** §16 de-risk — bucket-A playback walkthrough parity, checked early on real browsers
against the real relay, instead of discovering breakage at soak time.

---

## 1. Environment

| Leg | Target | Relay | Bundle |
|---|---|---|---|
| Deployed | `https://meraxes89.github.io/streamify-apk/` | baked `VITE_RELAY_BASE_URL` (production CF Worker) | production build (branch deploy of #173) |
| Local dev | `http://127.0.0.1:5173/streamify-apk/?relay=…` | same production relay, via `?relay=` | vite dev (`import.meta.env.DEV`, /debug live) |

Browsers actually driven (Playwright-class automation, real clicks/typing, no DOM mocks):

| Engine | Version | Result |
|---|---|---|
| Chromium (headless) | 157 / headless-shell 159 | **verified** (2 independent sessions) |
| Firefox (headless) | 157 | **verified (element lane)**; spine stall → issue #174 |
| WebKit / Safari | — | **not runnable in sandbox** (missing system libs, no root) → issue #175 |

## 2. What was exercised

### 2.1 Production UX lane (deployed site, guest)

Onboarding gate → *Continue without an account* → clean home renders → `/search` →
query `never gonna give you up` → results render (Play/Queue rows) → **Play** →
row flips to Pause + MiniPlayerBar shows the track (engine snapshot drives both).

- **Chromium: PASS** · **Firefox: PASS**
- Home after the teardown (#173) verified on the deployed bundle: feed + honest
  empty state + resolve-error lane; `/debug` correctly absent (redirects home);
  footer shows only the canary link.
- Engine state (observed via /debug on the dev bundle): status `playing`,
  buffer 143.7 s @ 94 % fill, 1 start-up underrun, queue 1 row.

### 2.2 WASM spine lane (SABR class — resolve → ranged fetch → demux → WebCodecs → AudioWorklet)

G1 canonical standard-content target `dQw4w9WgXcQ`, egress `spine`, 256 KiB chunks:

| Metric | Chromium | Firefox |
|---|---|---|
| Resolve (relay rung) | OK — itag 251 · opus · 3.27 MiB | OK — same |
| Chunks fetched | **15 / 0 retries** | **2 / 0**, then stall (#174) |
| Bytes | 3.27 MiB | 512 KiB |
| Phase | **Playing** | `Reading header…` forever |
| Position | real-time advancing (28 s+) | — |
| Frames read | 1.35 M | — |
| Underruns | **0** | — |
| **G1 gate** | **PASS (<0.1 %)** | insufficient |

### 2.3 GSF (Google-Script Fetch) lane — resolver override

- **Unconfigured posture (the default for every fresh visitor):** honest —
  `not configured — every control-plane call rides the shared relay (burns after
  1–2 licensed plays)`; the Test button stays disabled until a valid
  `script.google.com/macros/s/…/exec` URL is set. URL normalization rejects
  non-HTTPS / wrong-host inputs.
- **Configured lane (real Apps Script):** **NOT exercised** — requires the
  owner's deployed `tools/gsf` script (repository variable `GSF_URL` / `?gsf=`).
  This is an ops dependency, not a code gap; the lane machinery (envelope,
  probe, negative-cache, egress attribution) is unit-covered and the panel is
  now reachable at `/debug` in dev builds / `VITE_DEBUG_ROUTE=1` branch deploys.
- **Product note for W4:** the GSF *panel* moved off the shipped home with the
  teardown; end users can still arm the lane in production via `?gsf=<exec-url>`
  (persisted to localStorage by `lib/gsfLane.ts`). If beta users need an
  in-product config surface, that is a Settings entry decision for the consent
  screen wave — deliberately not smuggled back onto the home.

### 2.4 Shared-relay throttle observation (datacenter egress)

Repeated automated testing from this sandbox's **datacenter IP** produced
intermittent resolve degradation after a handful of back-to-back plays (ladder
verdicts degrade; spine runs flipped between G1 PASS and stall-at-resolve across
consecutive identical runs; the element lane kept working). This matches the
master plan's Appendix A measurement and is the exact wall the GSF lane exists
to sidestep — recorded here as confirmation, not as a new defect.

## 3. Dev-workflow footgun (documented, dev-only)

A **full page load** of a hash route drops `?relay=` (e.g. bookmarking/opening
`…/#/debug` directly) → the dev build falls back to `http://localhost:8787`
and every relay call fails with `Failed to fetch`. Production is unaffected
(`VITE_RELAY_BASE_URL` is baked at build). Dev workaround: keep the `?relay=`
param in the URL when hard-navigating.

## 4. Breakage filed

| Issue | Finding | Owner path |
|---|---|---|
| #174 | Firefox spine stall after 2 relay chunks (fetch/demux pull-loop halts, no error) | web `src/audio/` (chunkFetcher/demux) |
| #175 | WebKit/Safari matrix cell uncovered in sandbox; needs W4 soak on real devices | ops/W4 |

## 5. Verdict for the §16 ship gate

- Bucket-A **production lane** (search → resolve → play, element egress) is
  **green on Chromium + Firefox against the deployed production bundle**.
- The **spine/SABR quality lane** is **green on Chromium** (G1 PASS, 0 underruns)
  and **red on Firefox** (#174) — not user-facing today (default egress is
  `element`), must be fixed or formally degraded before bucket-A parity on
  Firefox is claimed.
- **WebKit/Safari:** unverified here (#175) — the soak's residential cohort
  must cover it; Safari-specific fallbacks (§15) remain speculative until then.
- **GSF-hosted licensed stream e2e:** pending the ops deployment of `tools/gsf`
  — tracked as the W4 convergence prerequisite it already was.
