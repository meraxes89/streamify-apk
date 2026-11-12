# front-end/canary — W0.1b residential canary

Browser-side **data-collection arm** of the W0.1b proof phase (WS-W). Runs the PO-free
innertube client ladder from a **visitor's residential IP** and posts the evidence to
Supabase (`public.canary_results`) as input for the W0.5 spend decision.

## Why (vs the datacenter-only W0.1 spike)

The W0.1 spike probed YouTube from CI datacenter IPs — which are bot-walled (see
`fleet-config.json`: *"licensedProbe is informational (CI datacenter IPs are bot-walled
by YouTube)"*). The only trustworthy signal about licensed-content playback is one
collected from a residential IP, so W0.1b makes visitors' browsers the probes, with a
deliberate traffic split:

- **Innertube API calls** (`/youtubei/v1/player`) go **through the relay**
  (`GET {relay}/r?u=<encoded>` carrying the real method/headers/body). YouTube sees the
  relay's datacenter IP; the response reflects what the ladder returns for that client
  context.
- **Media bytes** (googlevideo CDN) are fetched **direct from the visitor's browser** —
  that is the residential-IP evidence (R1/R3/R4).

## What a run does

1. **Matrix phase** — faithful translation of `tools/probe-clients.sh`: the 10-client
   CLIENTS array (verbatim) × 2 videos (`dQw4w9WgXcQ` standard, `4NRXx6U8ABQ` licensed)
   = 20 player POSTs through the relay, 2s spacing, per-row metrics
   (`playabilityStatus.status`, itag-140/251 audio count, total adaptiveFormats,
   signatureCipher count, reason, latency).
2. **R-C phase** — translation of `tools/probe-search-resolver.sh` with the search leg
   elided (the licensed video is resolved directly): R1 licensed playback, R2 PO-token
   (n/a by design), R3 ACAO on the CDN, R4 IP-pin tolerance.

## Wiring into the web app (lead's job)

This package is plain TS with one React component and a **peer dep on React 18** — no
build step of its own; the web app's Vite build transpiles the sources directly. In
`web/canary.html`:

```tsx
import { CanaryConsole } from '../../canary/src/ui/CanaryConsole';
// mount into a #canary-root element, e.g.
// createRoot(document.getElementById('canary-root')).render(<CanaryConsole />);
```

(Adjust the relative path to wherever canary.html lives under `front-end/web`.)

## Env vars (web app build)

| var | effect |
|---|---|
| `VITE_RELAY_BASE_URL` | default relay base for the console |
| `VITE_SUPABASE_URL` | enables “Post results to Supabase” |
| `VITE_SUPABASE_ANON_KEY` | anon key used as `apikey` + `Bearer` for the INSERT |

Resolution order for the relay base: `?relay=<base>` query param (per-tester override) →
`VITE_RELAY_BASE_URL` → `''` in production / `http://localhost:8787` only when
`import.meta.env.DEV`. The input on the page is editable regardless.

## Migration (PO's job)

`migrations/001_canary_results.sql` — **PO reviews + applies**. Grants anon **INSERT
only** on `public.canary_results`; there is deliberately **no anon SELECT** (service
role reads; adding authenticated read is a later PO call). Applying it is a prerequisite
for the “Post results to Supabase” button to succeed.

## Known deviation: User-Agent forwarding (documented, not worked around)

Browsers cannot set the `User-Agent` header via fetch (forbidden header name). Through
the relay YouTube therefore sees the **browser's** UA at the header level, while:

- the client identity travels in `X-YouTube-Client-Name` / `X-YouTube-Client-Version`,
  and
- the matrix UA still travels in the JSON **body** (`context.client.userAgent` — bodies
  are settable).

Every matrix row carries `notes: "ua=browser (fetch cannot set UA)"` so the stored data
is self-documenting. Also note: POSTing `Content-Type: application/json` +
`X-YouTube-Client-*` through the relay triggers CORS preflights (`OPTIONS /r?u=…`) —
the relay must answer them with `ACAO:*` and allow those request headers (the deployed
relay does).

## R-C conditions and how to read them

| id | condition | interpretation |
|---|---|---|
| R1 | Licensed-content playback from residential IP | ANDROID player (signatureTimestamp 19850, `html5Preference: HTML5_PREF_WANTS`) resolves `4NRXx6U8ABQ` **via relay**; the first audio adaptiveFormats URL (fallback: first `formats` URL) is then fetched **direct** with `Range: bytes=0-1024`. Pass = HTTP 200/206 with ≥1 byte read. Detail always states: *resolve via relay (datacenter CF IP); media bytes fetched direct from THIS browser's residential IP*. |
| R2 | PO-token minting in browser | `n/a` — structurally unavailable on web: rustypipe-botguard requires QuickJS (`rquickjs-sys`), which does not compile to `wasm32-unknown-unknown` (W0.1 landmine). The PO-free client ladder is the web strategy. |
| R3 | ACAO on googlevideo CDN URLs | Direct `Range: bytes=0-0` fetch of the R1 URL. Pass = fetch resolves (ACAO readable — Web Audio decode possible without the relay); fail = CORS TypeError → no ACAO on the CDN, cross-origin JS decode impossible without the relay; unknown when R1 produced no URL. |
| R4 | IP-pin tolerance | Derived from R1: the URL was minted for the relay IP but fetched from the residential IP. Pass = pin tolerated; HTTP 403 (with playability OK) = URL IP-pinned to the resolver IP; CORS-failed = cannot distinguish (no ACAO); no URL = unknown. |

## Files

| path | role |
|---|---|
| `package.json` | `@streamify/canary` — plain TS, peer dep react ^18.3.0 |
| `src/matrix.ts` | CLIENTS (verbatim from `tools/probe-clients.sh`), VIDEOS, SEARCH_PARAMS, SIGNATURE_TIMESTAMP |
| `src/relayFetch.ts` | relay funnel — `GET {relay}/r?u=<encoded>` with real method/headers/body; never throws |
| `src/probeMatrix.ts` | `runProbeMatrix()` — matrix phase + R-C phase → `ProbeReport` |
| `src/postResults.ts` | `buildRows()` / `postResults()` → Supabase REST insert (anon INSERT only) |
| `src/types.ts` | `ProbeRow` / `RCondition` / `ProbeReport` contracts |
| `src/ui/CanaryConsole.tsx` + `src/ui/canary.css` | the page (state machine: idle → running → done → posted) |
| `migrations/001_canary_results.sql` | table + anon-INSERT policy (PO applies) |

## Ops notes

- Recruiting **5+ residential testers** is the PO's job; this page is only the instrument.
- A full run is 20 relayed player calls (~40s+ with the 2s spacing) + 2 direct CDN range
  fetches — tell testers to expect about a minute.
- Client versions rot monthly (see the header of `tools/probe-clients.sh`) — re-verify
  against yt-dlp `INNERTUBE_CLIENTS` before trusting long-lived data.
- When Supabase env vars are missing, the post card shows an amber note with the env var
  names and a **copy rows JSON** button (clipboard) so testers can submit manually.
