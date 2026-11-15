# G5 — The unburnable lane: the GSF egress (Google ASN via Apps Script), and the official-embed retirement

**Date:** 2026-11-14 · **Branch:** `ws-w/g4-residential-direct` (iterating on #167) · **Extends:** G4.3-egress-discipline.md

## The mandate

After G4.3 the phone reported the SAME burn curve in a tighter form: 1–2
licensed tracks fully playing, then every rung `LOGIN_REQUIRED`,
`visitor=relay/app=relay`, `bot-gate=1`, the remaining rungs skipped under
the worker-egress cap. The product verdict that came with it: **no
compromises — any song, unlimited plays, and NO official-embed fallback**
(the IFrame lane played whole videos with ads).

G4.3's discipline was correct but its ceiling is arithmetic: the relay is
ONE shared Cloudflare egress (AS13335). Every licensed play spends trust
from a pool that is pre-heated by every other Worker on the colo — hygiene
changes the slope, never the class. **No amount of request discipline makes
a datacenter IP class unburnable.** The senior's own Q4 principle states
the fix: "true multi-user = each client with its own network interface."
The browser cannot BE that interface (the CORS wall), so the lane must be
an egress the user owns that YouTube's classifiers treat as first-party.

## The probe that changed the architecture (2026-11-14, live)

`youtubei.googleapis.com`'s CORS posture, mapped by exact `Origin` string
(OPTIONS preflight on `/youtubei/v1/player`):

| Origin | Preflight | ACAO |
|---|---|---|
| `https://www.youtube.com` | 200 | reflected (youtube.com) |
| `https://music.youtube.com` | 200 | reflected (music.youtube.com) |
| `https://script.google.com` | **200** | **reflected (script.google.com)** |
| `https://docs.google.com` | 200 | reflected (docs.google.com) |
| `https://sites.google.com` | 200 | reflected (sites.google.com) |
| `https://<anything>.pages.dev` | 403 "Sorry…" | none |
| `null` (sandboxed iframe) | 403 "Sorry…" | none |
| `https://google.com` | 403 | none |
| `*.script.googleusercontent.com` (HtmlService sandbox) | 403 | none |

Two facts fall out:

1. **The allowlist is a fixed list of Google first-party interactive
   surfaces** — and `script.google.com` is ON it. An Apps Script web app
   deployed "Anyone" serves cross-origin JSON to any browser (the
   canonical pattern), and its outbound `UrlFetchApp` calls egress from
   **Google's own ASN 15169** — the egress class YouTube's first-party
   traffic rides. Per-deploying-account quota (20k UrlFetch calls/day,
   free tier ≈ hundreds of tracks/day), not a shared abusive pool.
2. **The HtmlService "first-party iframe" variant is dead**: the sandbox
   executes user code at `*.script.googleusercontent.com`, which the same
   allowlist refuses. The server-side proxy (UrlFetchApp) is the only
   shape that works.

Combined with the W2.0 measurement (googlevideo authorizes cross-IP media
fetches — the `<audio>` element streams from the user's own IP regardless
of who resolved the URL) this yields the architecture:

```
CONTROL PLANE  (player / config / search — tiny JSON)
  browser → script.google.com/macros/s/…/exec  → UrlFetchApp → youtubei
             (CORS-allowlisted surface, GET envelope, zero preflight)
             egress: GOOGLE ASN 15169, the user's own quota

MEDIA PLANE   (progressive googlevideo bytes)
  <audio src=…> direct from the phone — cross-IP authorized (W2.0),
  never touches the script, the relay, or any quota

FALLBACK      the relay keeps exactly its G4.3 discipline (cap 2, no
              retry, edge negative cache) — GSF-off builds are unchanged
```

## Why this is inside the senior's verdicts

- **Not the vetoed fan-out:** multi-Worker fan-out failed because every
  Worker shares AS13335 + one JA3/JA4. The GSF lane is a different egress
  CLASS (Google's own ASN, Google's fetch stack) — diversity where
  diversity is real.
- **Not the vetoed residential pool:** no split-horizon proxying, no
  cost, no third-party ToS. A user's own free Google account relaying
  Google-to-Google API calls.
- **Not BYOC:** zero credentials in the app; the script refuses
  `Authorization`/`Cookie` headers outright; end users need nothing.
- **It IS "each client with its own network interface"**, as close as the
  browser allows: per-user quota on an unburnable egress class.
- The native-shell bridge (`window.streamifyNative`) keeps its place as
  the premium lane (phone IP, no third party); GSF is what the pure web
  gets instead of the retired embed.

## What changed (this iteration)

### 1. `tools/gsf/` — the deployable script (Code.gs + README + canary probe)
A deliberately dumb, allowlisted pipe: `youtubei.googleapis.com`,
`jnn-pa.googleapis.com`, `*.googlevideo.com`, `www.youtube.com/youtubei/*`
only; GET+POST only; credential headers refused; 8 MB response cap;
`?ping=1` health + egress-IP echo; the `?q=` GET envelope
(`{u, m, h, b, e}` — text or base64 bodies, binary-safe for the SABR UMP
protobufs). 2-minute deploy guide in `tools/gsf/README.md`.

### 2. `lib/gsfLane.ts` — the client lane
Config: `?gsf=` → `VITE_GSF_URL` (repository variable `GSF_URL`) →
`localStorage`. Health memoized per page session; **two consecutive
network-layer failures kill the lane for the session** (the direct-lane
negative-cache pattern — a posture cannot heal mid-load). `gsfFetch`
builds the GET envelope (no custom headers → no preflight anywhere),
decodes text/base64 into a real `Response`, and refuses envelopes that
would blow URL limits (the relay takes those). `gsfProbePosture()` =
ping + a tokenless IOS player POST for a licensed track — the whole bet
in one button.

### 3. `lib/ytSession.ts` — the lane order
Every direct-class call now runs **NATIVE bridge → GSF → plain fetch →
relay**: the visitor births (a GSF-born visitor persists 30 d —
direct-class trust), the youtubei.js funnel (`ytFetch`), and the raw
player POSTs (`directPlayerPost`, which now reports `'gsf'`). The relay
is demoted from the operative lane to the last resort.

### 4. `audio/resolve.ts` — GSF-egress rungs are uncapped
`laneUsed === 'gsf'` never consumes the worker-egress budget (it is the
user's own quota, not the shared IP), verdicts annotate `@gsf-egress`,
LOGIN_REQUIRED discipline is unchanged (deterministic verdicts are never
retried on any lane), and the terminal diagnosis carries the fix surface:
`gsf=off — deploy the unburnable lane (tools/gsf/README.md)`.

### 5. `audio/sabr/sabrStreamPlayer.ts` — SABR POSTs ride GSF first
The UMP protobufs travel base64 in the GET envelope; whole responses (no
streaming — the honest cost, noted in the module header). The relay keeps
the lane when GSF is off/dead. SABR's IP-pin tolerance (G4.2's
curl-verified measurement) is what makes a Google-ASN-carried session
legal at all.

### 6. The official-embed lane is RETIRED
`audio/iframeLane.ts` + `components/IframeLanePanel.tsx` deleted; the
bot-gate branch in HomeScreen now surfaces the honest diagnosis and the
GSF fix (`Engine Egress` panel — status, URL setter, live Test). The
product verdict stands: no ads, no whole-video players, no delegates.

### 7. `resolver-canary.yml` — the G5 GSF canary (optional)
When the `GSF_URL` repository variable is set, the nightly canary runs
`tools/gsf/probe-gsf.sh` — ping + licensed verdict through the script —
so the core bet (Google ASN serves licensed playability) is measured
nightly, not discovered through user reports.

## What did NOT change

- The G4.3 discipline in full: no-retry on LOGIN_REQUIRED, the 2-rung
  relay cap, per-family visitors, rotation-once-per-load, the edge
  negative cache (the relay still needs its manual `wrangler deploy`).
- The G4.2 SABR circuit breakers (8 s first-media budget, 6× no-media
  abort).
- The mint (jnn-pa is CORS-open — PO tokens still bind to the phone's IP).
- The native-shell bridge contract (the APK lane when a shell lands).

## The honest unknown

Whether YouTube serves licensed `player` OK from the Apps Script egress
is measurable only against a real deployment (the sandbox cannot deploy;
no datacenter probe substitutes for ASN 15169). The architecture is
deliberately falsifiable-with-grace: if the licensed probe comes back
`LOGIN_REQUIRED` through the script, the Engine Egress panel says so, the
ladder keeps the relay rungs, and the canary records the drift — nothing
regresses. If it comes back OK (the bet: Google's own ASN is the one
class YouTube does not burn for a free API call with a valid envelope),
the user gets exactly what was demanded: **any song, unlimited times, no
ads, no embeds.**
