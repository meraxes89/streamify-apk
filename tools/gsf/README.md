# GSF — the Google-ASN egress (Streamify's unburnable lane)

**What this is:** a ~200-line Google Apps Script web app you deploy once (free,
2 minutes, your own Google account). It becomes Streamify's *control-plane
egress*: every YouTube player/config/search call goes out through
**Google's own network (ASN 15169)** — the same egress class YouTube's
first-party traffic rides — instead of the shared Cloudflare Worker relay
(AS13335), which YouTube bot-walls for licensed music after 1–2 plays.

**Why it works** (probe-measured 2026-11-14, see
`front-end/decisions/G5-gsf-unburnable-lane.md`):

- `youtubei.googleapis.com`'s CORS allowlist includes `script.google.com`
  (alongside youtube.com / music.youtube.com / docs.google.com /
  sites.google.com) — so a browser page MAY talk to Apps Script web apps,
  which serve cross-origin JSON when deployed "Anyone".
- Apps Script's `UrlFetchApp` egresses from Google's own ASN — not a
  datacenter pool YouTube's abuse classifiers burn. Your script gets its own
  quota (20,000 outbound calls/day on a free account ≈ hundreds of tracks),
  not a shared-abuse IP.
- Media bytes never touch the script: googlevideo authorizes cross-IP
  fetches (the W2.0 measurement), so the `<audio>` element keeps streaming
  from YOUR phone's own IP. The script carries only the tiny JSON legs
  (~1–3 calls per track).

**What it is NOT:** not a credential lane (Authorization/Cookie headers are
refused outright), not an open proxy (Google hosts only, GET/POST only,
response caps), not a shared pool (your deployment, your quota).

---

## Deploy (2 minutes)

1. Open **[script.google.com](https://script.google.com)** → **New project**
   (any free Google account).
2. Delete the editor's starter code, paste all of **`Code.gs`** (this folder).
3. **Deploy → New deployment**:
   - Select type (⚙): **Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**  ← required (this is what makes the browser
     able to call it — "Anyone with a Google account" would demand a login)
4. **Deploy** → authorize (Google shows the usual "unverified app" screen →
   Advanced → Go to project — it is your own script, asking for nothing but
   `UrlFetch`).
5. Copy the **Web app URL** (`https://script.google.com/macros/s/…/exec`).
6. Paste it into Streamify → **Engine Egress** panel → **Test**. You should
   see `healthy · egress <ip>` and a licensed-track probe verdict.

Redeploys after editing code: **Deploy → Manage deployments → ✎ (edit) →
Version: New version → Deploy** — the /exec URL stays stable, so the app and
your users keep working.

## Wire it into the app

Three ways (first match wins):

1. **Per-visit:** append `?gsf=https://script.google.com/macros/s/…/exec` to
   the app URL.
2. **Build default:** set the `VITE_GSF_URL` env var (the Pages deploy reads
   the repository variable `GSF_URL`) — every visitor gets the lane with zero
   setup.
3. **Per-device:** the Engine Egress panel persists it in `localStorage`.

## Verify it is live

The **Engine Egress** panel's **Test** button runs two probes and shows both:

- `ping` — the script answers with its egress IP (`healthy · egress <ip>`).
- a licensed-track `player` POST (IOS envelope, tokenless) through the script
  — `playability OK` means the Google-ASN egress is serving licensed content;
  `LOGIN_REQUIRED` means even Google's egress is gating (report it — that
  would falsify G5's core bet and the relay rungs remain the fallback).

The nightly canary (`resolver-canary.yml`) probes the same two legs when the
`GSF_URL` repository variable/secret is set.

## Quotas & limits (free consumer account)

- **20,000 UrlFetch calls/day** (~1–3 per track + SABR posts if that lane
  rides it — hundreds of tracks/day; effectively unlimited for a person).
- Execution timeout 6 min/invocation — irrelevant here (each call is one
  fetch).
- URL length: the GET envelope carries request bodies; the client refuses
  envelopes that would blow URL limits and falls back to the relay.
- No streaming: responses arrive whole — fine for JSON legs; the SABR media
  lane prefers the element/progressive paths and only uses GSF as a
  best-effort fallback.

## Security notes

The script is a dumb, allowlisted pipe: `youtubei.googleapis.com`,
`jnn-pa.googleapis.com`, `*.googlevideo.com`, and `www.youtube.com/youtubei/*`
only; GET/POST only; `Authorization`/`Cookie` headers refused; 8 MB response
cap; the egress-IP echo is the single non-Google outbound call (cached
10 min). Even a leaked /exec URL grants nothing but a Google-to-Google pipe
on your quota.
