/**
 * streamify-relay — P0 minimal CORS relay + P1 minting surface (W1.0).
 *
 * WHY THIS EXISTS
 *   The Streamify web port (https://meraxes89.github.io/streamify-apk/) runs
 *   the Rust engine compiled to wasm *in the browser*. That engine must reach
 *   YouTube's innertube API and the googlevideo media CDN — both refuse
 *   cross-origin browser requests (no ACAO headers → CORS 403 from the
 *   github.io origin). This Worker is the deliberately DUMB, CORS-open relay:
 *
 *       GET /r?u=<urlencoded-target>  →  fetch the target with the client's
 *                                        headers (minus origin/referer/…), then
 *                                        stream the response back with ACAO:*.
 *
 * P1 / W1.0 — THE MINTING SURFACE (why this relay grew two more duties)
 *   Licensed music (e.g. 4NRXx6U8ABQ) sits behind YouTube's "Sign in to
 *   confirm you're not a bot" wall for datacenter IPs. The unlock is a
 *   PO token minted by REAL browser hardware (BotGuard VM environment
 *   scoring — JSDOM/headless mints are low-trust; measured 2026-11-12).
 *   The mint needs three network legs, and ALL of them go through this
 *   relay so the whole session (challenge, GenerateIT, player call, media
 *   bytes) egresses from ONE consistent IP:
 *
 *     1. GET /bg-session           homepage challenge, extracted server-side
 *     2. GET /r?u=…www.google.com/js/th/…   the BotGuard interpreter script
 *     3. POST /r?u=…jnn-pa.googleapis.com/… Waa/GenerateIT (integrity token)
 *
 *   The jnn-pa WAA request key is ORIGIN-RESTRICTED: a foreign Origin makes
 *   it invalid. Browsers force Origin onto every cross-origin fetch, but the
 *   relay strips it (REQUEST_DROP) — upstream sees an origin-less request,
 *   exactly like the proven Node mint. This is why minting traffic MUST be
 *   same-origin through the relay rather than direct to jnn-pa.
 *
 *   PO token INJECTION into the player request happens CLIENT-SIDE (the web
 *   app builds serviceIntegrityDimensions.poToken + X-Goog-Visitor-Id and
 *   POSTs through /r) — a deliberate choice: it works unchanged against the
 *   already-deployed P0 relay, and keeps this worker dumb.
 *
 *   BINDING RULES (yt-dlp pot/utils.py, verified against the live homepage):
 *     • player request poToken  → mintAsWebsafeString(VIDEO_ID)
 *     • googlevideo &pot=       → VIDEO_ID while the html5_generate_content_
 *       po_token experiment flag is set on the homepage (it IS, 2026-11-12),
 *       else visitorData
 *
 * P3b / BOTGUARD EGRESS — THE 3-TIER PIVOT (2026-11-14):
 *   Tier 1 (zero-token fast path): the resolver's primary rung is now the
 *   VISIONOS client (X-YouTube-Client-Name 101, matrix UA above — verified
 *   live: full-length direct googlevideo URLs + a bonus hlsManifestUrl,
 *   no PO token, no 60-second ANDROID_VR cap). WEB_SAFARI (the WEB client,
 *   X-YouTube-Client-Name 1 + `X-Relay-UA: safari-mac`) is the HLS fallback
 *   rung. ANDROID_VR (28) stays in the matrix for the canary only — it is
 *   burned for full tracks (hard ~60 s byte cap, measured 2026-11-14).
 *
 *   STRICT SESSION ENVELOPE PINNING (the split-brain fix): the browser
 *   mints PO tokens under its REAL client-hint envelope; upstream hops
 *   that disagree with that envelope invalidate the mint (HTTP 403 /
 *   empty webPoSignalOutput). Two passthrough exceptions therefore apply
 *   to the sec-ch-ua* family (dropped for everything else — the P0
 *   landmine fix for APP clients stands):
 *     • jnn-pa.googleapis.com (WAA / GenerateIT) — the architect-pinned
 *       leg: forward the browser's exact User-Agent + Sec-CH-UA-*.
 *     • www.youtube.com/youtubei/* declaring X-YouTube-Client-Name 1
 *       (WEB / WEB_SAFARI) — a real browser's WEB player call CARRIES its
 *       client hints; stripping them is what made the relay's WEB rung
 *       look like a non-browser to the anti-abuse pipeline.
 *   `X-Relay-UA` (named UA override) implies Safari-class UAs, which send
 *   NO client hints — when an override is active the sec-ch-ua* family is
 *   stripped to keep the envelope coherent.
 *
 * PHASE NOTE
 *   No JWT, no rate limiting, no kill switch. Those land in P4 (W4.1):
 *   TODO(P4/W4.1): JWT auth on /r.
 *   TODO(P4/W4.1): per-client rate limiting (per-IP / per-token buckets).
 *   TODO(P4/W4.1): remote kill switch (env-bound flag → 503, still CORS-open).
 *
 * G4.3 — THE EDGE NEGATIVE CACHE (the senior verdict, 2026-11-14): the
 *   phone's live trace (one full play → every rung LOGIN_REQUIRED,
 *   visitor=relay ×6) proved the client-side ladder was an ~18×/track
 *   request AMPLIFIER on this Worker's single shared egress IP. The client
 *   now runs the egress-discipline ladder (≤2 relay rungs, no LR retries —
 *   front-end/web/src/audio/resolve.ts); THIS worker does its half: a
 *   player-POST refusal (playabilityStatus.status === "LOGIN_REQUIRED")
 *   is cached AT THE EDGE (Cache API, per-colo) for 30 min, keyed by
 *   (client, poToken-present, videoId). A retried gated track fails
 *   CLOSED in <1 ms — the shared IP never sees the repeat — and the client
 *   surfaces its official-embed fallback lane instead. Bypass for
 *   diagnostics: request header `X-Relay-NegCache: off`.
 *
 *   Deliberately NEGATIVE-only: positive verdicts carry per-visitor session
 *   state (expiring signed URLs, visitor-scoped fields) — replaying them
 *   across users risks exactly the anomaly signals this whole phase is
 *   cooling down. Streaming is untouched for everything that is not a
 *   player POST (media bytes NEVER buffer).
 *
 * HARD RULES baked in from the W0.1 spike:
 *   • The client's User-Agent is FORWARDED VERBATIM. Workers' default UA gets
 *   404s on some YouTube endpoints while a browser-like UA passes — UA
 *   fidelity is deliberate, not an oversight.
 *   • The upstream STATUS is preserved EXACTLY (206 stays 206, 403 stays 403)
 *   and the body is passed as a STREAM, never buffered. Byte-range audio
 *   from googlevideo through this relay is the project kill criterion.
 *
 * W1-A — THE EDGE METADATA CACHE (/ytm/meta, ytm-meta.ts): the Worker's
 * third duty. All public InnerTube browse/metadata traffic routes through
 * a cached, validated, single-flight endpoint instead of the /r passthrough
 * — the global metadata cache the P1 cloud.ts boundary rule now names
 * (decisions/waves/W1A-edge-kv-metadata-importer.md). The Worker entry
 * signature grows (request, env, ctx): env carries the optional YTM_CACHE
 * KV binding (absent in local dev/test → bypassed cleanly), ctx keeps SWR
 * background revalidations alive past the response.
 *
 * W2-A — THE OPTIONAL APPS SCRIPT SOLVER FORWARDING: when a deployment
 * points STREAMIFY_RELAY_BASE at THIS worker and sets the optional
 * APPS_SCRIPT_SOLVER_URL env var (a script.google.com/macros/s/…/exec
 * deployment), the /r CONTROL-PLANE legs (innertube / WAA / BotGuard
 * script hosts — never googlevideo media bytes, the GSF lane's own rule)
 * forward through the Apps Script envelope so the stream-solve egress
 * rides Google's ASN 15169 instead of the shared Cloudflare edge — the
 * same bet gsfLane.ts makes client-side. /ytm/meta and /bg-session stay
 * worker-local (the KV cache and the homepage challenge cache ARE this
 * worker's duties). A solver-lane failure (network/not-ok envelope)
 * degrades to the direct worker fetch — strictly additive, never worse.
 *
 * Pure Workers runtime — no external dependencies. ES-module default export.
 */

import { ytmMetaRoute, type YtmEnv } from "./ytm-meta";

/** W2-A: the extended env — the Apps Script solver URL is optional and
 * read per-request (wrangler vars change between deployments). */
export interface RelayEnv extends YtmEnv {
  APPS_SCRIPT_SOLVER_URL?: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * Hop-by-hop headers — stripped in BOTH directions (outgoing request headers
 * and returned response headers). Per RFC 7230 §6.1 these describe a single
 * transport connection and must never be forwarded by a proxy.
 */
const HOP_BY_HOP: ReadonlySet<string> = new Set([
  "connection",
  "keep-alive",
  "proxy-authorization",
  "proxy-authenticate",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

/**
 * Request-side-only drops, applied ON TOP of {@link HOP_BY_HOP} when building
 * the outgoing upstream request:
 *   • host / origin / referer — leak the calling site, and upstreams key
 *     behavior off them (YouTube treats wrong-origin requests differently;
 *     jnn-pa's WAA request key is ORIGIN-RESTRICTED — see file header);
 *   • cookie — an open relay must never ride the caller's session;
 *   • cf-* / x-forwarded-* / x-real-ip / true-client-ip — injected by
 *     Cloudflare's edge, they describe THIS worker, not the browser;
 *   • content-length — the runtime recomputes it from the actual (streamed)
 *     body; a stale copy would corrupt Range math;
 *   • x-relay-redirect — a RELAY CONTROL header (opt-in 302 passthrough),
 *     never meaningful upstream.
 * Everything else the client sent is forwarded — notably User-Agent (W0.1
 * finding, see file header), accept*, content-type, range, and the YouTube
 * client headers (x-youtube-client-*, x-goog-api-key, x-goog-visitor-id,
 * authorization).
 */
const REQUEST_DROP: ReadonlySet<string> = new Set([
  "host",
  "origin",
  "referer",
  "cookie",
  "cf-connecting-ip",
  "cf-ipcountry",
  "cf-ray",
  "cf-visitor",
  "x-forwarded-for",
  "x-forwarded-proto",
  "x-real-ip",
  "true-client-ip",
  "content-length",
  "x-relay-redirect",
  // WS-W P0 landmine fix: browsers FORCE Sec-Fetch-* markers onto every
  // fetch (forbidden headers — the wasm engine cannot strip them). YouTube
  // reads them as "this ANDROID app request actually comes from a browser
  // page" and answers the player endpoint with the soft-block "The page
  // needs to be reloaded". The relay is the true client from the upstream's
  // perspective — these headers mis-describe the caller, so they never pass.
  "sec-fetch-dest",
  "sec-fetch-mode",
  "sec-fetch-site",
  "sec-fetch-user",
  "sec-purpose",
  "dnt",
  // X-Relay-UA is a RELAY CONTROL header (P3b: named UA override for the
  // WEB_SAFARI rung), never meaningful upstream.
  "x-relay-ua",
  // G4.3: the negative-cache bypass is a RELAY CONTROL header (canary /
  // diagnostics), never meaningful upstream.
  "x-relay-negcache",
  // Option 3: custom user cookie / session control header
  "x-relay-cookie",
]);

/**
 * The Sec-CH-UA family — SPECIAL-CASED per target (P3b envelope pinning).
 * DROPPED by default (the P0 landmine rule: these markers on APP-client
 * calls earn the "page needs to be reloaded" soft-block), but PASSED
 * THROUGH on the two hops where the browser's real client hints ARE the
 * coherent envelope: the WAA mint legs (jnn-pa) and WEB-declared innertube
 * calls. See the file header's P3b section for the full rationale.
 */
const SEC_CH_UA: ReadonlySet<string> = new Set([
  "sec-ch-ua",
  "sec-ch-ua-arch",
  "sec-ch-ua-bitness",
  "sec-ch-ua-full-version",
  "sec-ch-ua-full-version-list",
  "sec-ch-ua-mobile",
  "sec-ch-ua-model",
  "sec-ch-ua-platform",
  "sec-ch-ua-platform-version",
  "sec-ch-ua-wow64",
]);

/** Methods the relay forwards upstream; anything else on /r → 405. */
const FORWARDED_METHODS: ReadonlySet<string> = new Set(["GET", "HEAD", "POST"]);

/**
 * WS-W P0 landmine fix (W0.4): browsers cannot set the User-Agent header
 * (fetch forbidden-header) — the wasm engine and the canary matrix identify
 * the innertube client via X-YouTube-Client-Name, but the UA rides through
 * as the browser's Chrome UA. YouTube bot-walls APP clients (player
 * endpoint) carrying a browser UA from datacenter IPs
 * ("Sign in to confirm you're not a bot").
 *
 * The relay therefore NORMALIZES the upstream User-Agent to the matrix value
 * for the client the request itself declares. Values sourced from
 * tools/probe-clients.sh (yt-dlp master, 2026-08-24) — same rot-cadence
 * applies: re-verify monthly. Web clients (WEB_REMIX, MWEB, embedded)
 * keep the forwarded browser UA — it is their natural UA.
 *
 * Key = X-YouTube-Client-Name number. Unknown numbers: UA passes through.
 */
const CLIENT_NAME_UA: ReadonlyMap<string, string> = new Map([
  // ANDROID
  ["3", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"],
  // IOS
  [
    "5",
    "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
  ],
  // ANDROID_VR
  [
    "28",
    "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
  ],
  // VISIONOS
  [
    "101",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
  ],
]);

/**
 * Upstream UA for a request: the matrix value when the request declares a
 * known APP client via X-YouTube-Client-Name; the forwarded client UA
 * otherwise (web clients, media bytes, version scrapes).
 */
function upstreamUserAgent(headers: Headers): string | null {
  const clientName = headers.get("x-youtube-client-name");
  if (clientName !== null && CLIENT_NAME_UA.has(clientName)) {
    return CLIENT_NAME_UA.get(clientName) ?? null;
  }
  return null;
}

/**
 * P3b named UA overrides (`X-Relay-UA: <name>`) — the UAs a browser CANNOT
 * set (fetch forbidden header) but the WEB_SAFARI rung needs: Safari-class
 * user agents are what make the WEB client receive HLS manifests. Values
 * mirror the VISIONOS family UA (canary matrix, tools/probe-clients.sh).
 */
const UA_OVERRIDES: ReadonlyMap<string, string> = new Map([
  [
    "safari-mac",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
  ],
  [
    "safari-ios",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 26_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1",
  ],
]);

/**
 * P3b client-hint passthrough rule (the split-brain fix). Returns true when
 * the sec-ch-ua* family must be FORWARDED VERBATIM for this hop:
 *   • jnn-pa.googleapis.com — the WAA mint legs (Create/GenerateIT): the
 *     BotGuard VM telemetry was captured under the browser's real client
 *     hints; contradicting them upstream invalidates the mint.
 *   • www.youtube.com / music.youtube.com /youtubei/* declaring client 1
 *     (WEB / WEB_SAFARI): a real browser's WEB player call carries its
 *     client hints; they belong on the wire.
 * `uaOverrideActive` (an X-Relay-UA override) forces false — Safari sends
 * NO client hints, so forwarding Chrome's would re-split the envelope.
 */
function clientHintsPassThrough(
  target: URL,
  headers: Headers,
  uaOverrideActive: boolean,
): boolean {
  if (uaOverrideActive) return false;
  const host = target.hostname.toLowerCase();
  if (host === "jnn-pa.googleapis.com") return true;
  const isYoutubeWeb =
    (host === "www.youtube.com" || host === "music.youtube.com") &&
    target.pathname.startsWith("/youtubei/");
  if (isYoutubeWeb && headers.get("x-youtube-client-name") === "1") {
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// /bg-session — the homepage challenge cache (W1.0)
// ---------------------------------------------------------------------------

/**
 * Fallback UA for the server-side homepage fetch when the caller sends none.
 * Desktop Chrome — the environment the challenge/session pair is consistent
 * with (the browser client that will consume it sends its own UA, which is
 * preferred and used as the cache key).
 */
const BG_SESSION_FALLBACK_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36";

/** How long a cached (visitorData, ytcfg, ytAtN) triple stays fresh. */
const BG_SESSION_TTL_MS = 60 * 60 * 1000;

/** Modest cap: one entry per distinct client UA (realistically 1–3). */
const BG_SESSION_MAX_ENTRIES = 4;

interface BgSession {
  fetchedAt: number;
  /** ytcfg VISITOR_DATA — the anonymous session id PO tokens bind against. */
  visitorData: string;
  /** Raw ytcfg JSON (strict JSON; the client parses + injects as yt.config_). */
  ytcfg: string;
  /** Raw ytAtN loose-JSON payload (client runs parseLooseJSON on it). */
  ytAtN: string;
}

/**
 * Module-level cache. Workers isolates are per-colo and recycled at will —
 * worst case a cold isolate refetches the homepage once. The (ytcfg, ytAtN)
 * pair must come from the SAME homepage render (the 2026-08 provider patch:
 * plugin-passed challenges lack their page's ytcfg/EVENT_ID and /att/get
 * tokens are rejected) — extraction below grabs both from one fetch.
 */
const bgSessionCache = new Map<string, BgSession>();

/**
 * Extract the minting triple from homepage HTML. Regexes mirror the proven
 * pot-lab recipe (pot-mint2/pot-videobind, provider session_manager 2026-08
 * patch): first ytcfg.set block, first "visitorData" occurrence, the
 * window.ytAtN(...) loose-JSON payload.
 */
function extractBgSession(html: string): BgSession | { error: string } {
  const vdMatch = /"visitorData":"([^"]+)"/.exec(html);
  if (vdMatch === null) return { error: "homepage has no visitorData (consent wall?)" };

  const cfgMatch = /ytcfg\.set\(({.+?})\);/s.exec(html);
  if (cfgMatch === null) return { error: "homepage has no ytcfg.set" };

  const attMatch = /window\.ytAtN\(\s*({[\s\S]*?})\s*\)/.exec(html);
  if (attMatch === null) return { error: "homepage has no ytAtN bgChallenge" };

  return {
    fetchedAt: Date.now(),
    visitorData: vdMatch[1],
    ytcfg: cfgMatch[1],
    ytAtN: attMatch[1],
  };
}

// ---------------------------------------------------------------------------
// /play & /api/search — Direct Serverless Streaming & Search Endpoints
// ---------------------------------------------------------------------------

function extractSapisid(cookie: string): string | null {
  const match = cookie.match(/(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)/);
  if (match) return match[1].trim();
  const trimmed = cookie.trim();
  if (!trimmed.includes("=") && !trimmed.includes(";") && trimmed.length > 15) {
    return trimmed;
  }
  return null;
}

async function computeSapisidHash(sapisid: string, origin: string): Promise<string> {
  const timestamp = Math.floor(Date.now() / 1000);
  const data = new TextEncoder().encode(`${timestamp} ${sapisid} ${origin}`);
  const hashBuffer = await crypto.subtle.digest("SHA-1", data);
  const hashHex = Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `SAPISIDHASH ${timestamp}_${hashHex}`;
}

async function resolveDirectAudioStream(
  videoId: string,
  env: RelayEnv,
  pot?: string | null,
  visitorData?: string | null,
  userCookie?: string | null,
): Promise<{ url: string; mimeType: string; status?: string; tier1Error?: string } | null> {
  let lastTier1Error = "NO_COOKIE";
  // --- Tier 1: Authenticated User Session (Option 3 — bypasses datacenter bot-gates) ---
  if (userCookie && userCookie.trim().length > 0) {
    const sapisid = extractSapisid(userCookie);
    const cleanCookie =
      sapisid && !userCookie.includes("=")
        ? `SAPISID=${sapisid}; __Secure-3PAPISID=${sapisid}`
        : userCookie.trim();

    // 1a: Try IOS with cookie (IOS always gives pre-signed direct audio URLs, no cipher!)
    try {
      const iosHeaders = new Headers({
        "Content-Type": "application/json",
        "User-Agent": "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X;)",
        "Origin": "https://www.youtube.com",
        "X-Origin": "https://www.youtube.com",
        "X-Goog-AuthUser": "0",
        "Cookie": cleanCookie,
      });
      if (sapisid) {
        iosHeaders.set("Authorization", await computeSapisidHash(sapisid, "https://www.youtube.com"));
      }
      const iosRes = await fetch("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", {
        method: "POST",
        headers: iosHeaders,
        body: JSON.stringify({
          videoId,
          context: {
            client: {
              clientName: "IOS",
              clientVersion: "20.11.6",
              hl: "en",
              gl: "US",
            },
          },
        }),
      });
      if (iosRes.ok) {
        const iosData = (await iosRes.json()) as {
          playabilityStatus?: { status?: string; reason?: string };
          streamingData?: {
            formats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
            adaptiveFormats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
          };
        };
        if (iosData.playabilityStatus?.status === "OK") {
          const fmts = [
            ...(iosData.streamingData?.formats || []),
            ...(iosData.streamingData?.adaptiveFormats || []),
          ];
          const audioFmts = fmts.filter((f) => f.mimeType && f.mimeType.startsWith("audio/"));
          const chosen =
            audioFmts.find((f) => f.itag === 140 && f.url) ||
            audioFmts.find((f) => f.url) ||
            fmts.find((f) => f.url);
          if (chosen && chosen.url) {
            let mimeType = "audio/mp4";
            if (chosen.mimeType) mimeType = chosen.mimeType.split(";")[0];
            return { url: chosen.url, mimeType, status: "OK" };
          }
        } else {
          lastTier1Error = `IOS:${iosData.playabilityStatus?.status}:${iosData.playabilityStatus?.reason || ""}`;
        }
      } else {
        lastTier1Error = `IOS_HTTP_${iosRes.status}`;
      }
    } catch (e) {
      lastTier1Error = `IOS_ERR:${e instanceof Error ? e.message : String(e)}`;
    }

    // 1b: Try WEB_REMIX with cookie (only if pre-signed url is available)
    const ytmBody: Record<string, unknown> = {
      videoId,
      playbackContext: {
        contentPlaybackContext: {
          signatureTimestamp: 20768,
        },
      },
      context: {
        client: {
          clientName: "WEB_REMIX",
          clientVersion: "1.20250219.01.00",
          hl: "en",
          gl: "US",
          ...(visitorData ? { visitorData } : {}),
        },
      },
      ...(pot ? { serviceIntegrityDimensions: { poToken: pot } } : {}),
    };

    const ytmHeaders = new Headers({
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
      "Origin": "https://music.youtube.com",
      "Referer": "https://music.youtube.com/",
      "Cookie": cleanCookie,
    });

    if (sapisid) {
      ytmHeaders.set("Authorization", await computeSapisidHash(sapisid, "https://music.youtube.com"));
    }

    try {
      const ytmRes = await fetch("https://music.youtube.com/youtubei/v1/player?prettyPrint=false", {
        method: "POST",
        headers: ytmHeaders,
        body: JSON.stringify(ytmBody),
      });

      if (ytmRes.ok) {
        const ytmData = (await ytmRes.json()) as {
          playabilityStatus?: { status?: string; reason?: string };
          streamingData?: {
            formats?: Array<{ itag?: number; mimeType?: string; url?: string; cipher?: string; signatureCipher?: string }>;
            adaptiveFormats?: Array<{ itag?: number; mimeType?: string; url?: string; cipher?: string; signatureCipher?: string }>;
          };
        };

        if (ytmData.playabilityStatus?.status === "OK") {
          const fmts = [
            ...(ytmData.streamingData?.formats || []),
            ...(ytmData.streamingData?.adaptiveFormats || []),
          ];
          const audioFmts = fmts.filter(
            (f) => (f.mimeType && f.mimeType.startsWith("audio/")) || f.itag === 140,
          );
          const chosen =
            audioFmts.find((f) => f.itag === 140 && f.url) ||
            audioFmts.find((f) => f.url) ||
            audioFmts[0] ||
            fmts[0];

          if (chosen && chosen.url) {
            let mimeType = "audio/mp4";
            if (chosen.mimeType) mimeType = chosen.mimeType.split(";")[0];
            return { url: chosen.url, mimeType, status: "OK" };
          }
        }
      }
    } catch {
      // Continue to unauthenticated rungs
    }
  }

  // --- Tier 2: VISIONOS Pre-Signed Direct Rung (zero cipher, standard & unpartitioned tracks) ---
  const visionosBody = {
    context: {
      client: {
        clientName: "VISIONOS",
        clientVersion: "1.02",
        hl: "en",
        gl: "US",
        deviceMake: "Apple",
        deviceModel: "RealityDevice17,1",
        osName: "visionOS",
        osVersion: "26.5.23O471",
        ...(visitorData ? { visitorData } : {}),
      },
    },
    videoId,
    contentCheckOk: true,
    racyCheckOk: true,
  };

  try {
    const visionRes = await fetch("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&alt=json", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-youtube-client-name": "101",
        "x-youtube-client-version": "1.02",
        "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
      },
      body: JSON.stringify(visionosBody),
    });

    if (visionRes.ok) {
      const visionData = (await visionRes.json()) as {
        playabilityStatus?: { status?: string; reason?: string };
        streamingData?: {
          formats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
          adaptiveFormats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
        };
      };

      if (visionData.playabilityStatus?.status === "OK") {
        const fmts = [
          ...(visionData.streamingData?.formats || []),
          ...(visionData.streamingData?.adaptiveFormats || []),
        ];
        const audioFmts = fmts.filter(
          (f) => (f.mimeType && f.mimeType.startsWith("audio/")) || f.itag === 140,
        );
        const chosen =
          audioFmts.find((f) => f.itag === 140 && f.url) ||
          audioFmts.find((f) => f.url) ||
          fmts.find((f) => f.url);

        if (chosen && chosen.url) {
          let mimeType = "audio/mp4";
          if (chosen.mimeType) mimeType = chosen.mimeType.split(";")[0];
          return { url: chosen.url, mimeType, status: "OK" };
        }
      }
    }
  } catch {
    // Continue to Tier 3
  }

  // --- Tier 3: iOS Client (Apps Script Solver + Direct Worker) ---
  const playerBody: Record<string, unknown> = {
    videoId,
    playbackContext: {
      contentPlaybackContext: {
        signatureTimestamp: 20768,
      },
    },
    context: {
      client: {
        clientName: "iOS",
        clientVersion: "20.11.6",
        deviceModel: "iPhone10,4",
        osName: "iOS",
        osVersion: "16.7.7.20H330",
        platform: "MOBILE",
        hl: "en",
        gl: "US",
      },
    },
  };

  if (visitorData) {
    (playerBody.context as { client: Record<string, unknown> }).client.visitorData = visitorData;
  }
  if (pot) {
    (playerBody as Record<string, unknown>).serviceIntegrityDimensions = {
      poToken: pot,
    };
  }

  const solverUrl = appsScriptSolverUrl(env);
  const targetUrl = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
  const playerHeaders = new Headers({
    "Content-Type": "application/json",
    "User-Agent": "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X;)",
    "X-YouTube-Client-Name": "5",
    "X-YouTube-Client-Version": "20.11.6",
  });
  const playerBodyStr = JSON.stringify(playerBody);

  let playerRes: Response | null = null;
  if (solverUrl !== null) {
    const dummyReq = new Request("https://dummy", { method: "POST", body: playerBodyStr });
    playerRes = await forwardThroughAppsScript(
      solverUrl,
      targetUrl,
      "POST",
      playerHeaders,
      dummyReq.body,
    );
  }

  if (!playerRes || !playerRes.ok) {
    playerRes = await fetch(targetUrl.toString(), {
      method: "POST",
      headers: playerHeaders,
      body: playerBodyStr,
    });
  }

  if (!playerRes.ok) return null;

  const playerData = (await playerRes.json()) as {
    playabilityStatus?: { status?: string; reason?: string };
    streamingData?: {
      formats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
      adaptiveFormats?: Array<{ itag?: number; mimeType?: string; url?: string }>;
    };
  };

  if (playerData.playabilityStatus?.status !== "OK") {
    return { url: "", mimeType: "", status: playerData.playabilityStatus?.status, tier1Error: lastTier1Error };
  }

  const formats = [
    ...(playerData.streamingData?.formats || []),
    ...(playerData.streamingData?.adaptiveFormats || []),
  ];

  const audioFormats = formats.filter(
    (f) => (f.mimeType && f.mimeType.startsWith("audio/")) || f.itag === 140,
  );
  const chosen =
    audioFormats.find((f) => f.itag === 140 && f.url) ||
    audioFormats.find((f) => f.url) ||
    formats.find((f) => f.url);

  if (chosen && chosen.url) {
    let mimeType = "audio/mp4";
    if (chosen.mimeType) mimeType = chosen.mimeType.split(";")[0];
    return { url: chosen.url, mimeType, status: "OK", tier1Error: lastTier1Error };
  }

  return { url: "", mimeType: "", status: playerData?.playabilityStatus?.status || "UNKNOWN", tier1Error: lastTier1Error };
}

async function searchYouTube(q: string): Promise<Array<{ videoId: string; title: string; artist?: string }>> {
  try {
    const searchRes = await fetch("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        context: {
          client: {
            clientName: "WEB",
            clientVersion: "2.20250219.01.00",
            hl: "en",
            gl: "US",
          },
        },
        query: q,
      }),
    });

    if (!searchRes.ok) return [];

    const data = (await searchRes.json()) as {
      contents?: {
        twoColumnSearchResultsRenderer?: {
          primaryContents?: {
            sectionListRenderer?: {
              contents?: Array<{
                itemSectionRenderer?: {
                  contents?: Array<{
                    videoRenderer?: {
                      videoId?: string;
                      title?: { runs?: Array<{ text?: string }> };
                      ownerText?: { runs?: Array<{ text?: string }> };
                    };
                  }>;
                };
              }>;
            };
          };
        };
      };
    };

    const contents =
      data?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents || [];

    const results: Array<{ videoId: string; title: string; artist?: string }> = [];
    for (const c of contents) {
      for (const v of c.itemSectionRenderer?.contents || []) {
        const vr = v.videoRenderer;
        if (vr && vr.videoId) {
          results.push({
            videoId: vr.videoId,
            title: vr.title?.runs?.[0]?.text || "",
            artist: vr.ownerText?.runs?.[0]?.text || "",
          });
        }
      }
    }
    return results;
  } catch {
    return [];
  }
}

async function handlePlay(request: Request, url: URL, env: RelayEnv): Promise<Response> {
  const isDebug = url.searchParams.get("debug") === "1";
  let videoId = url.searchParams.get("videoId");
  const queryParam = url.searchParams.get("q") || url.searchParams.get("query");

  if (!videoId && queryParam) {
    const searchMatches = await searchYouTube(queryParam);
    if (searchMatches.length > 0 && searchMatches[0].videoId) {
      videoId = searchMatches[0].videoId;
    } else {
      return jsonResponse({ error: "no search matches found", query: queryParam }, 404);
    }
  }

  if (!videoId) {
    return jsonResponse({ error: "missing videoId or query parameter ?videoId= or ?q=" }, 400);
  }

  const pot = url.searchParams.get("pot");
  const visitorData = url.searchParams.get("visitorData");
  const userCookie = url.searchParams.get("cookie") || request.headers.get("x-relay-cookie");
  const clientRange = request.headers.get("range");

  // Step 1: Attempt direct resolve
  let resolved = await resolveDirectAudioStream(videoId, env, pot, visitorData, userCookie);

  if (isDebug) {
    return jsonResponse({
      debug: true,
      videoId,
      hasCookie: !!userCookie,
      cookieLength: userCookie?.length ?? 0,
      extractedSapisid: userCookie ? extractSapisid(userCookie) : null,
      resolvedStatus: resolved?.status ?? "NULL",
      tier1Error: resolved?.tier1Error ?? "NONE",
      resolvedHasUrl: !!resolved?.url,
      resolvedUrl: resolved?.url ? resolved.url.substring(0, 80) : null
    }, 200);
  }
  let effectiveVideoId = videoId;
  let isSubstituted = false;

  // Step 2: Fallback to Catalog Substitution if primary resolve fails or is gated
  if (!resolved || !resolved.url) {
    let titleToSearch = url.searchParams.get("title") || "";
    if (!titleToSearch) {
      try {
        const oembedRes = await fetch(
          `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${videoId}&format=json`,
        );
        if (oembedRes.ok) {
          const oembedData = (await oembedRes.json()) as { title?: string };
          if (oembedData.title) titleToSearch = oembedData.title;
        }
      } catch {
        // Fall back to videoId
      }
    }

    const searchQuery = `${titleToSearch || videoId} audio`;
    const candidates = await searchYouTube(searchQuery);

    for (const cand of candidates.slice(0, 3)) {
      if (cand.videoId === videoId) continue;
      const subResolved = await resolveDirectAudioStream(cand.videoId, env, pot, visitorData, userCookie);
      if (subResolved && subResolved.url) {
        resolved = subResolved;
        effectiveVideoId = cand.videoId;
        isSubstituted = true;
        break;
      }
    }
  }

  if (!resolved || !resolved.url) {
    return jsonResponse(
      {
        error: "stream resolve failed",
        videoId,
        status: resolved?.status || "UNKNOWN",
      },
      404,
    );
  }

  // Step 3: Fetch googlevideo media bytes from the SAME Worker IP and pipe back with Range support
  const outgoingHeaders: Record<string, string> = {
    "User-Agent": "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X;)",
  };
  if (clientRange) {
    outgoingHeaders["Range"] = clientRange;
  } else {
    outgoingHeaders["Range"] = "bytes=0-";
  }

  try {
    const mediaRes = await fetch(resolved.url, {
      headers: outgoingHeaders,
    });

    const responseHeaders = new Headers();
    responseHeaders.set("Content-Type", mediaRes.headers.get("content-type") || resolved.mimeType);
    if (mediaRes.headers.has("content-range")) {
      responseHeaders.set("Content-Range", mediaRes.headers.get("content-range")!);
    }
    if (mediaRes.headers.has("content-length")) {
      responseHeaders.set("Content-Length", mediaRes.headers.get("content-length")!);
    }
    responseHeaders.set("Accept-Ranges", "bytes");
    responseHeaders.set("Cache-Control", "public, max-age=3600");
    if (isSubstituted) {
      responseHeaders.set("X-Streamify-Substituted", effectiveVideoId);
    }

    for (const [k, v] of Object.entries(corsHeaders())) {
      responseHeaders.set(k, v);
    }
    responseHeaders.set(
      "Access-Control-Expose-Headers",
      "Content-Length, Content-Range, Accept-Ranges, Content-Type, X-Streamify-Substituted",
    );

    return new Response(mediaRes.body, {
      status: mediaRes.status,
      statusText: mediaRes.statusText,
      headers: responseHeaders,
    });
  } catch (err) {
    return jsonResponse(
      {
        error: "stream fetch failed",
        detail: err instanceof Error ? err.message : String(err),
      },
      502,
    );
  }
}

async function handleSearch(_request: Request, url: URL): Promise<Response> {
  const q = url.searchParams.get("q") || url.searchParams.get("query");
  if (!q) {
    return jsonResponse({ error: "missing search query ?q=" }, 400);
  }

  try {
    const searchRes = await fetch("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        context: {
          client: {
            clientName: "WEB",
            clientVersion: "2.20250219.01.00",
            hl: "en",
            gl: "US",
          },
        },
        query: q,
      }),
    });

    if (!searchRes.ok) {
      return jsonResponse({ error: "upstream search failed", status: searchRes.status }, 502);
    }

    const data = (await searchRes.json()) as {
      contents?: {
        twoColumnSearchResultsRenderer?: {
          primaryContents?: {
            sectionListRenderer?: {
              contents?: Array<{
                itemSectionRenderer?: {
                  contents?: Array<{
                    videoRenderer?: {
                      videoId?: string;
                      title?: { runs?: Array<{ text?: string }> };
                      ownerText?: { runs?: Array<{ text?: string }> };
                      lengthText?: { simpleText?: string };
                      thumbnail?: { thumbnails?: Array<{ url?: string }> };
                    };
                  }>;
                };
              }>;
            };
          };
        };
      };
    };

    const contents =
      data?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents || [];

    const results: Array<{
      videoId: string;
      title: string;
      artist?: string;
      duration?: string;
      thumbnail?: string;
    }> = [];

    for (const c of contents) {
      for (const v of c.itemSectionRenderer?.contents || []) {
        const vr = v.videoRenderer;
        if (vr && vr.videoId) {
          results.push({
            videoId: vr.videoId,
            title: vr.title?.runs?.[0]?.text || "",
            artist: vr.ownerText?.runs?.[0]?.text || "",
            duration: vr.lengthText?.simpleText || "",
            thumbnail: vr.thumbnail?.thumbnails?.slice(-1)[0]?.url || "",
          });
        }
      }
    }

    return jsonResponse({ query: q, count: results.length, results }, 200);
  } catch (err) {
    return jsonResponse(
      { error: "search exception", detail: err instanceof Error ? err.message : String(err) },
      500
    );
  }
}

/**
 * GET /bg-session — the browser mint stack's first leg.
 *
 * Server-side homepage fetch with SOCS=CAI (consent-bypass cookie; the
 * browser cannot set Cookie on fetch — forbidden header), UA forwarded from
 * the caller so the (challenge, visitorData) pair stays coherent with the
 * very browser that will run the VM. Result cached per-UA for an hour.
 * `?fresh=1` bypasses the cache (mint retry path).
 */
async function bgSession(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const forceFresh = url.searchParams.get("fresh") === "1";
  const ua = request.headers.get("user-agent") ?? BG_SESSION_FALLBACK_UA;

  if (!forceFresh) {
    const hit = bgSessionCache.get(ua);
    if (hit !== undefined && Date.now() - hit.fetchedAt < BG_SESSION_TTL_MS) {
      return jsonResponse({ ...hit, cache: "hit" }, 200);
    }
  }

  let html: string;
  try {
    const res = await fetch("https://www.youtube.com/", {
      headers: {
        accept: "text/html",
        "accept-language": "en-US,en;q=0.7",
        "user-agent": ua,
        cookie: "SOCS=CAI",
      },
      redirect: "follow",
    });
    if (!res.ok) {
      return jsonResponse(
        { error: "homepage fetch failed", status: res.status },
        502,
      );
    }
    html = await res.text();
  } catch (err) {
    return jsonResponse(
      {
        error: "homepage fetch failed",
        detail: err instanceof Error ? err.message : String(err),
      },
      502,
    );
  }

  const extracted = extractBgSession(html);
  if ("error" in extracted) {
    return jsonResponse({ error: extracted.error }, 502);
  }

  // Tiny LRU: evict oldest entries beyond the cap.
  if (bgSessionCache.size >= BG_SESSION_MAX_ENTRIES) {
    const oldest = bgSessionCache.keys().next();
    if (oldest.done !== true) bgSessionCache.delete(oldest.value);
  }
  bgSessionCache.set(ua, extracted);

  return jsonResponse(
    {
      ...extracted,
      expiresAt: extracted.fetchedAt + BG_SESSION_TTL_MS,
      cache: "miss",
    },
    200,
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Upstream target allowlist. googlevideo uses per-edge hostnames such as
 * `rr4---sn-x0.googlevideo.com`, so googlevideo is matched by the
 * `.googlevideo.com` SUFFIX; the bare apex is allowed defensively.
 *
 * W1.0 minting additions (path-scoped where possible):
 *   • www.google.com   — ONLY /js/th/* (the BotGuard interpreter "trayride"
 *     scripts the challenge's interpreterUrl points at). Any other google.com
 *     path stays out — this is not a general google proxy.
 *   • jnn-pa.googleapis.com — the Waa/GenerateIT integrity-token endpoint
 *     (attestation for the PO mint). Nothing else lives on that host.
 */
function isAllowedTarget(target: URL): boolean {
  const host = target.hostname.toLowerCase();
  if (
    host === "www.youtube.com" ||
    host === "music.youtube.com" ||
    host === "youtubei.googleapis.com" ||
    host === "googlevideo.com" ||
    host.endsWith(".googlevideo.com")
  ) {
    return true;
  }
  if (host === "www.google.com") {
    return target.pathname.startsWith("/js/th/");
  }
  if (host === "jnn-pa.googleapis.com") {
    return true;
  }
  return false;
}

/**
 * CORS headers attached to EVERY response this Worker produces — success,
 * error, preflight, and upstream passthrough alike. `Access-Control-Expose-
 * Headers` whitelists exactly what browser-side JS (the wasm engine) may
 * read: the Range trio (Content-Range / Accept-Ranges / Content-Length) is
 * REQUIRED for byte-range audio; X-Relay-Location / X-Relay-Status power
 * the hop-surfacing mode (see relay()); X-Relay-Ms is this relay's timing
 * header. Open ACAO:* is a DELIBERATE P0 decision — hardening lands P4 (W4.1).
 */
function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Expose-Headers":
      "Content-Range, Accept-Ranges, Content-Length, Content-Type, X-Relay-Location, X-Relay-Status, X-Relay-Ms",
  };
}

/** JSON response with CORS attached (every non-passthrough reply). */
function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...corsHeaders(),
    },
  });
}

// ---------------------------------------------------------------------------
// G4.3 — the edge negative cache (player POSTs only)
// ---------------------------------------------------------------------------

/**
 * How long a cached LOGIN_REQUIRED refusal stays authoritative (the
 * senior's 15–30 min window — the colo/ASN reputation decay horizon).
 */
const NEG_CACHE_TTL_MS = 30 * 60 * 1000;

/** Only bodies this small are ever buffered for the cache (player JSON is
 * typically ≪ 1 MB; anything bigger passes straight through, uncached). */
const NEG_CACHE_MAX_BYTES = 2 * 1024 * 1024;

/** The innertube hosts whose /player endpoints are cacheable surfaces. */
function isInnertubePlayerPost(target: URL, method: string): boolean {
  const host = target.hostname.toLowerCase();
  const innertube =
    host === "youtubei.googleapis.com" ||
    host === "www.youtube.com" ||
    host === "music.youtube.com";
  return (
    method === "POST" &&
    innertube &&
    /^\/youtubei\/v[0-9]+\/player(\/.*)?$/.test(target.pathname)
  );
}

/**
 * The cache key for one player call: the client identity (body's
 * context.client.clientName, falling back to the X-YouTube-Client-Name
 * header), whether the call carried a PO token, and the videoId. Distinct
 * envelopes never collide (a WEB refusal does not poison the IOS rung).
 */
async function playerNegCacheKey(
  request: Request,
): Promise<string | null> {
  try {
    const body = (await request.clone().json()) as {
      videoId?: string;
      context?: { client?: { clientName?: string } };
      serviceIntegrityDimensions?: { poToken?: string };
    };
    const videoId = body.videoId;
    if (typeof videoId !== "string" || videoId.length === 0) return null;
    const client =
      body.context?.client?.clientName ??
      request.headers.get("x-youtube-client-name") ??
      "unknown";
    const pot = body.serviceIntegrityDimensions?.poToken !== undefined;
    return `g43/player/${String(client).toUpperCase()}/${pot ? "pot" : "bare"}/${videoId}`;
  } catch {
    return null; // not JSON / unreadable — not a cacheable player call
  }
}

/** The synthetic GET request that addresses one cache entry (Cache API
 * only stores GETs; the synthetic origin never leaves the edge). */
function negCacheRequest(key: string): Request {
  return new Request(`https://negcache.streamify.internal/${key}`, {
    method: "GET",
  });
}

/**
 * The edge cache handle — `caches.default` in the Workers runtime. The
 * tsconfig's DOM lib also declares a CacheStorage (without `.default`),
 * so the access goes through a local narrowing cast (runtime-true).
 */
function edgeCache(): Cache {
  return (caches as unknown as { default: Cache }).default;
}

interface NegCacheEnvelope {
  cachedAt: number;
  body: string;
}

/** Look up one cached refusal; null on miss/expiry. */
async function negCacheLookup(key: string): Promise<Response | null> {
  try {
    const hit = await edgeCache().match(negCacheRequest(key));
    if (hit === undefined) return null;
    const envelope = (await hit.json()) as NegCacheEnvelope;
    if (Date.now() - envelope.cachedAt > NEG_CACHE_TTL_MS) {
      void edgeCache().delete(negCacheRequest(key));
      return null;
    }
    return new Response(envelope.body, {
      status: 200,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        ...corsHeaders(),
        "X-Relay-Negcache": "hit",
        "X-Relay-Ms": "0",
      },
    });
  } catch {
    return null; // a broken cache entry must never break the relay
  }
}

/** Store one refusal body (fire-and-forget; failures are non-fatal). */
async function negCacheStore(key: string, body: string): Promise<void> {
  try {
    const envelope: NegCacheEnvelope = { cachedAt: Date.now(), body };
    await edgeCache().put(
      negCacheRequest(key),
      new Response(JSON.stringify(envelope), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          // TTL + slack (the lookup re-validates age explicitly — belt and
          // braces: the Cache API's eviction is not a clock).
          "Cache-Control": `public, max-age=${Math.ceil(NEG_CACHE_TTL_MS / 1000) + 60}`,
        },
      }),
    );
  } catch {
    /* best-effort — the passthrough still answered */
  }
}

/** Parse playabilityStatus.status out of a buffered player JSON body. */
function playabilityStatusOf(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as {
      playabilityStatus?: { status?: string };
    };
    return parsed.playabilityStatus?.status ?? null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// W2-A — the Apps Script solver lane (optional /r forwarding)
// ---------------------------------------------------------------------------

/** The envelope URL rides as ?q= — stay under Apps Script's GET URL limit
 * (the same 7 500-char cap gsfLane.ts carries client-side). */
const SOLVER_ENVELOPE_URL_MAX = 7_500;
/** Control-plane POST bodies are small JSON; anything bigger (a stray
 * media-ish payload) stays on the direct worker fetch. */
const SOLVER_BODY_MAX_BYTES = 256 * 1024;

/**
 * Validate + return the configured Apps Script solver URL, or null. Same
 * shape gsfLane.ts's normalizeGsfUrl accepts: https + script.google.com +
 * /macros/s/ prefix — a malformed/placeholder value reads as unconfigured
 * (the additive rule: a bad knob can never break the direct lane).
 */
export function appsScriptSolverUrl(env: RelayEnv): null | string {
  const raw = env.APPS_SCRIPT_SOLVER_URL;
  if (typeof raw !== "string" || raw === "") return null;
  const trimmed = raw.trim();
  if (trimmed.startsWith("${") && trimmed.endsWith("}")) return null;
  try {
    const url = new URL(trimmed);
    if (url.protocol !== "https:") return null;
    if (url.hostname !== "script.google.com") return null;
    if (!url.pathname.startsWith("/macros/s/")) return null;
    return url.toString();
  } catch {
    return null;
  }
}

/**
 * The solver lane serves CONTROL-PLANE legs only — the innertube hosts, the
 * WAA mint, and the BotGuard interpreter script. googlevideo media bytes
 * never ride it (the GSF lane's own rule: UrlFetchApp quota + latency are
 * for tiny JSON verdicts, not megabyte range fetches; the media CDN is
 * CORS-open and cross-IP by design — the W2.0 measurement).
 */
export function isControlPlaneTarget(target: URL): boolean {
  const host = target.hostname.toLowerCase();
  return (
    host === "www.youtube.com" ||
    host === "music.youtube.com" ||
    host === "youtubei.googleapis.com" ||
    host === "jnn-pa.googleapis.com" ||
    (host === "www.google.com" && target.pathname.startsWith("/js/th/"))
  );
}

/** The Apps Script envelope response (the GSF wire — gsfLane.ts's mirror). */
interface SolverEnvelope {
  ok?: boolean;
  s?: number;
  h?: Record<string, string>;
  b?: string;
  e?: "b64" | "t";
  eip?: string;
  error?: string;
}

/**
 * Forward one /r request through the Apps Script envelope. Returns the
 * unwrapped Response on success, or NULL when the lane failed (network,
 * not-ok envelope, oversized body/URL) — the caller then runs the direct
 * worker fetch (the additive degradation rule).
 */
async function forwardThroughAppsScript(
  solverUrl: string,
  target: URL,
  method: string,
  outgoing: Headers,
  body: ReadableStream<Uint8Array> | null,
): Promise<null | Response> {
  try {
    let bodyText: string | undefined;
    let enc: "b64" | "t" = "t";
    if (body !== null) {
      const bytes = new Uint8Array(await new Response(body).arrayBuffer());
      if (bytes.byteLength > SOLVER_BODY_MAX_BYTES) return null;
      const isText = looksLikeText(outgoing.get("content-type"));
      if (isText) {
        bodyText = new TextDecoder().decode(bytes);
      } else {
        // Binary (protobuf-JSON is text, but the WAA legs can carry real
        // bytes) — base64 envelope, same as the client-side lane.
        let binary = "";
        const CHUNK = 0x8000;
        for (let i = 0; i < bytes.length; i += CHUNK) {
          binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
        }
        bodyText = btoa(binary);
        enc = "b64";
      }
    }
    const headers: Record<string, string> = {};
    outgoing.forEach((value, name) => {
      headers[name] = value;
    });
    const envelope = JSON.stringify({
      u: target.toString(),
      m: method.toLowerCase() === "post" ? "post" : "get",
      h: headers,
      ...(bodyText !== undefined ? { b: bodyText, e: enc } : {}),
    });
    const url = `${solverUrl}${solverUrl.includes("?") ? "&" : "?"}q=${encodeURIComponent(envelope)}`;
    if (url.length > SOLVER_ENVELOPE_URL_MAX) return null;

    const res = await fetch(url, { method: "GET", redirect: "follow" });
    const wire = (await res.json()) as SolverEnvelope;
    if (wire.ok !== true || typeof wire.s !== "number") {
      return null; // the script's own error — lane-level, not a verdict
    }
    const responseHeaders = new Headers();
    for (const [name, value] of Object.entries(wire.h ?? {})) {
      try {
        responseHeaders.set(name, value);
      } catch {
        /* exotic header names are dropped, not fatal */
      }
    }
    const responseBody =
      wire.e === "b64" && typeof wire.b === "string"
        ? base64ToBytes(wire.b)
        : (wire.b ?? "");
    return new Response(
      typeof responseBody === "string" ? responseBody : new Uint8Array(responseBody),
      {
        status: wire.s,
        headers: {
          // The relay's CORS posture — the envelope answer must cross origins
          // exactly like a direct passthrough would.
          ...corsHeaders(),
          ...Object.fromEntries(responseHeaders.entries()),
        },
      },
    );
  } catch {
    return null; // lane down — the direct fetch below answers
  }
}

/** JSON/text-ish content types ride the envelope as text. */
function looksLikeText(contentType: null | string): boolean {
  if (contentType === null) return true;
  const lower = contentType.toLowerCase();
  return (
    lower.includes("json") ||
    lower.includes("text") ||
    lower.includes("x-www-form-urlencoded") ||
    lower === ""
  );
}

function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

// ---------------------------------------------------------------------------
// Worker entry
// ---------------------------------------------------------------------------

export default {
  async fetch(request: Request, env: RelayEnv, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (err) {
      // Belt-and-braces: an unexpected throw must still answer WITH CORS —
      // otherwise a browser-side error path becomes undebuggable.
      return jsonResponse(
        {
          error: "relay internal error",
          detail: err instanceof Error ? err.message : String(err),
        },
        500,
      );
    }
  },
};

/** Route table: OPTIONS anywhere → preflight; /health; /; /bg-session;
 * /ytm/meta (W1-A); /r; else 404. */
async function route(request: Request, env: RelayEnv, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const method = request.method.toUpperCase();
  const path = url.pathname;

  // ---- CORS preflight (ANY path) ---------------------------------------
  // Echo the browser's Access-Control-Request-Headers verbatim (fallback "*"
  // when absent/empty) so custom headers (x-goog-api-key, x-youtube-client-*,
  // range, content-type…) preflight cleanly; 86400s caches it away.
  if (method === "OPTIONS") {
    const requested = request.headers.get("access-control-request-headers");
    return new Response(null, {
      status: 204,
      headers: {
        ...corsHeaders(),
        "Access-Control-Allow-Methods": "GET, POST, HEAD, OPTIONS",
        "Access-Control-Allow-Headers":
          requested !== null && requested.length > 0 ? requested : "*",
        "Access-Control-Max-Age": "86400",
      },
    });
  }

  if (path === "/play") {
    if (method !== "GET" && method !== "HEAD") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return handlePlay(request, url, env);
  }

  if (path === "/api/search" || path === "/search") {
    if (method !== "GET") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return handleSearch(request, url);
  }

  if (path === "/health") {
    if (method !== "GET") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return new Response("ok", {
      status: 200,
      headers: { "Content-Type": "text/plain; charset=utf-8", ...corsHeaders() },
    });
  }

  if (path === "/") {
    if (method !== "GET") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return jsonResponse(
      {
        service: "streamify-relay",
        usage: "/r?u=<urlencoded-target> · /bg-session",
        phase: "G4.3 — egress discipline (the senior verdict): edge negative cache",
        // Capability probe: the web app checks these to know whether the
        // deployed worker has the surface a milestone needs (an older
        // deployment answers this route without the field — capability =
        // absent). The G3b relay is functionally identical to P3b (the
        // youtubei.js stack rides the SAME /r passthrough + envelope
        // pinning + UA matrix); the marker exists for deployment
        // verification.
        capabilities: [
          "bg-session", // W1.0 — homepage challenge cache (dormant in G3b)
          "ua-override", // P3b — X-Relay-UA named overrides (WEB_SAFARI)
          "client-hints-passthrough", // P3b — sec-ch-ua* on WAA + WEB legs
          "g3b-oss-stack", // G3b — YouTube.js + bgutils-js egress surface
          "neg-cache", // G4.3 — edge negative cache (LOGIN_REQUIRED, 30 min)
          "ytm-meta", // W1-A — edge KV metadata cache (/ytm/meta)
          "apps-script-solver", // W2-A — optional /r forwarding via APPS_SCRIPT_SOLVER_URL
        ],
      },
      200,
    );
  }

  if (path === "/bg-session") {
    if (method !== "GET") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return bgSession(request);
  }

  // ---- W1-A: the edge metadata cache ------------------------------------
  if (path === "/ytm/meta") {
    if (method !== "GET") {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return ytmMetaRoute(request, env, ctx);
  }

  if (path === "/r") {
    if (!FORWARDED_METHODS.has(method)) {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return relay(request, url, env);
  }

  return jsonResponse({ error: "not found" }, 404);
}

// ---------------------------------------------------------------------------
// /r — the relay proper
// ---------------------------------------------------------------------------

async function relay(request: Request, url: URL, env: RelayEnv): Promise<Response> {
  const started = Date.now();
  const method = request.method.toUpperCase();

  // ---- Target validation -------------------------------------------------
  const raw = url.searchParams.get("u");
  if (raw === null || raw === "") {
    return jsonResponse({ error: "missing ?u=" }, 400);
  }

  let target: URL;
  try {
    target = new URL(raw);
  } catch {
    return jsonResponse(
      { error: "invalid ?u= — must be an absolute http/https URL" },
      400,
    );
  }
  if (target.protocol !== "http:" && target.protocol !== "https:") {
    return jsonResponse({ error: "only http/https targets are supported" }, 400);
  }

  if (!isAllowedTarget(target)) {
    const host = target.hostname.toLowerCase();
    // TODO(P4/W4.1): log/alert on allowlist violations (abuse signal).
    return jsonResponse({ error: "host not allowed", host }, 403);
  }

  const customCookie = request.headers.get("x-relay-cookie") || url.searchParams.get("cookie");
  const hasCustomCookie = typeof customCookie === "string" && customCookie.trim().length > 0;

  // ---- G4.3: the edge negative cache (player POSTs only) ------------------
  // Lookup BEFORE the upstream fetch: a gated track fails closed in <1 ms
  // and the shared egress never sees the repeat. `X-Relay-NegCache: off`
  // bypasses (canary/diagnostics); the header is dropped upstream.
  // Authenticated user sessions bypass the negative cache.
  const negKey = (!hasCustomCookie && isInnertubePlayerPost(target, method))
    ? await playerNegCacheKey(request)
    : null;
  if (
    negKey !== null &&
    request.headers.get("x-relay-negcache") !== "off"
  ) {
    const hit = await negCacheLookup(negKey);
    if (hit !== null) return hit;
  }

  // ---- Redirect mode (W1.0) ----------------------------------------------
  // Default: follow (the audio path wants bytes, not hops). Opt-in via
  // `X-Relay-Redirect: manual` — googlevideo sometimes answers cross-edge
  // 302s and the chunk fetcher's hop protocol wants to OWN the hop (amortize
  // the edge URL across chunks instead of re-paying the hop every chunk).
  //
  // THE BROWSER TRAP (why hops surface as 200 + X-Relay-Location, NEVER as
  // a literal 3xx): a literal 302 with the raw Location is unusable from a
  // page — redirect:'manual' yields an opaque-redirect (status 0, headers
  // unreadable) and redirect:'follow' auto-follows STRAIGHT to the raw
  // upstream URL, which has no ACAO → CORS death. So in manual mode the
  // relay converts upstream 3xx into a plain 200 whose custom headers
  // (CORS-exposed, unlike Location) carry the hop:
  //
  //     X-Relay-Location: <absolute upstream Location>
  //     X-Relay-Status:  <the true upstream status, diagnostics>
  //
  // The client re-wraps X-Relay-Location through /r itself and the relay
  // re-validates the hop target against the allowlist like any other.
  const manualRedirect = request.headers.get("x-relay-redirect") === "manual";
  const redirect: RequestRedirect = manualRedirect ? "manual" : "follow";

  // ---- Outgoing headers ---------------------------------------------------
  // Copy ALL client headers minus the drop sets (hop-by-hop + request-side
  // drops — see REQUEST_DROP's doc comment). This FORWARDS the client's
  // User-Agent deliberately: W0.1 finding — Workers' default UA gets 404s on
  // some YouTube innertube endpoints while the browser-like UA passes.
  //
  // EXCEPT: requests declaring an APP client via X-YouTube-Client-Name get
  // the matrix UA for that client (see upstreamUserAgent) — the browser
  // itself cannot set app UAs (forbidden header) and YouTube bot-walls the
  // player endpoint for browser-UA'd app clients.
  //
  // P3b: `X-Relay-UA: <name>` (a control header — dropped below) overrides
  // the UA with a named matrix value (WEB_SAFARI needs a Safari-class UA the
  // browser cannot set). The matrix UA (app clients) wins over the named
  // override — an app client's UA identity is its client claim, not a
  // cosmetic choice.
  const clientNameUa = upstreamUserAgent(request.headers);
  const namedUa = request.headers.get("x-relay-ua");
  const namedUaValue =
    namedUa !== null ? UA_OVERRIDES.get(namedUa.toLowerCase()) : undefined;
  const uaOverride = clientNameUa ?? (namedUaValue ?? null);
  // P3b envelope pinning: sec-ch-ua* rides ONLY on the hops where the
  // browser's real client hints belong on the wire (WAA mint legs, WEB
  // innertube) — and never alongside a Safari-class UA override (Safari
  // sends no client hints; forwarding Chrome's would re-split the envelope).
  const hintsThrough = clientHintsPassThrough(target, request.headers, uaOverride !== null);
  const outgoing = new Headers();
  request.headers.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || REQUEST_DROP.has(lower)) return;
    if (!hintsThrough && SEC_CH_UA.has(lower)) return;
    if (uaOverride !== null && lower === "user-agent") return; // set below
    outgoing.set(name, value);
  });
  if (uaOverride !== null) {
    outgoing.set("user-agent", uaOverride);
  }

  // Option 3: Authenticated user session forwarding
  if (hasCustomCookie) {
    const cleanCookie = customCookie.trim();
    outgoing.set("cookie", cleanCookie);
    const host = target.hostname.toLowerCase();
    const origin = host === "music.youtube.com" ? "https://music.youtube.com" : "https://www.youtube.com";
    if (!outgoing.has("origin")) outgoing.set("origin", origin);
    if (!outgoing.has("referer")) outgoing.set("referer", `${origin}/`);
    const sapisid = extractSapisid(cleanCookie);
    if (sapisid && !outgoing.has("authorization")) {
      outgoing.set("authorization", await computeSapisidHash(sapisid, origin));
    }
  }

  // ---- Body -----------------------------------------------------------------
  // POST bodies (innertube JSON payloads, WAA protobuf-JSON) stream through
  // untouched. GET/HEAD must be bodyless — fetch() throws otherwise.
  const body: ReadableStream<Uint8Array> | null =
    method === "POST" ? request.body : null;

  // ---- W2-A: the optional Apps Script solver lane --------------------------
  // When APPS_SCRIPT_SOLVER_URL is configured (a script.google.com/macros/s/
  // …/exec deployment), CONTROL-PLANE legs forward through the Apps Script
  // envelope (Google-ASN egress — the GSF bet, server-side). Media bytes
  // (googlevideo) and manual-redirect hops never ride it: UrlFetchApp
  // follows redirects itself and cannot surface the hop protocol. A lane
  // failure degrades to the direct worker fetch below — never worse.
  const solverUrl = appsScriptSolverUrl(env);
  if (solverUrl !== null && !manualRedirect && isControlPlaneTarget(target)) {
    const forwarded = await forwardThroughAppsScript(
      solverUrl,
      target,
      method,
      outgoing,
      body,
    );
    if (forwarded !== null) {
      return forwarded;
    }
  }

  // ---- Upstream fetch --------------------------------------------------------
  let upstream: Response;
  try {
    upstream = await fetch(target.toString(), {
      method,
      headers: outgoing,
      body,
      redirect,
    });
  } catch (err) {
    return jsonResponse(
      {
        error: "upstream fetch failed",
        detail: err instanceof Error ? err.message : String(err),
      },
      502,
    );
  }

  // ---- Hop surfacing (manual mode) -----------------------------------------
  // Convert upstream 3xx → 200 + X-Relay-Location/X-Relay-Status (see the
  // redirect-mode comment above for why a literal 3xx cannot cross CORS).
  // A 3xx without Location falls through to normal passthrough (a broken
  // upstream should fail loudly, not hang the client's hop loop).
  if (manualRedirect && upstream.status >= 300 && upstream.status <= 399) {
    const location = upstream.headers.get("location");
    if (location !== null) {
      let absolute: string;
      try {
        absolute = new URL(location, target).toString();
      } catch {
        return jsonResponse(
          { error: "upstream redirect has an unparseable Location", location },
          502,
        );
      }
      return new Response(null, {
        status: 200,
        headers: {
          ...corsHeaders(),
          "X-Relay-Location": absolute,
          "X-Relay-Status": String(upstream.status),
          "Cache-Control": "no-store",
          "X-Relay-Ms": String(Date.now() - started),
        },
      });
    }
  }

  // ---- Response passthrough ----------------------------------------------------
  // ALL upstream headers pass except hop-by-hop + content-length (the runtime
  // recomputes length from the streamed body — a stale value would break Range
  // math). Content-Encoding is forwarded AS-IS: the audio path (googlevideo)
  // is identity-encoded, and the JSON path stays internally consistent — see
  // README "Known caveats" before touching this.
  //
  // G4.3 EXCEPTION (player POSTs with a cache key): the body is buffered
  // (small JSON — NEVER media), inspected for the LOGIN_REQUIRED refusal,
  // and cached at the edge when gated. Everything else keeps the strict
  // streaming passthrough below — byte-range audio remains untouched.
  if (negKey !== null && upstream.status === 200) {
    const contentType = upstream.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) {
      const text = await upstream.text(); // buffer (bounded by the check below)
      if (text.length <= NEG_CACHE_MAX_BYTES) {
        const status = playabilityStatusOf(text);
        if (status === "LOGIN_REQUIRED") {
          await negCacheStore(negKey, text);
        }
        const headers = new Headers();
        upstream.headers.forEach((value, name) => {
          const lower = name.toLowerCase();
          if (HOP_BY_HOP.has(lower) || lower === "content-length") return;
          headers.set(name, value);
        });
        for (const [name, value] of Object.entries(corsHeaders())) {
          headers.set(name, value);
        }
        headers.set("X-Relay-Ms", String(Date.now() - started));
        headers.set(
          "X-Relay-Negcache",
          status === "LOGIN_REQUIRED" ? "stored" : "miss",
        );
        return new Response(text, { status: upstream.status, headers });
      }
      // Too large to cache — still JSON, still answer from the buffer.
      const headers = new Headers();
      upstream.headers.forEach((value, name) => {
        const lower = name.toLowerCase();
        if (HOP_BY_HOP.has(lower) || lower === "content-length") return;
        headers.set(name, value);
      });
      for (const [name, value] of Object.entries(corsHeaders())) {
        headers.set(name, value);
      }
      headers.set("X-Relay-Ms", String(Date.now() - started));
      headers.set("X-Relay-Negcache", "too-large");
      return new Response(text, { status: upstream.status, headers });
    }
  }

  // The upstream STATUS is preserved EXACTLY: 206 stays 206, 403 stays 403.
  // That fidelity plus STREAMING the body (never buffering it) is the project
  // kill criterion — do not "fix" 206 → 200 and do not buffer for logging.
  const headers = new Headers();
  upstream.headers.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || lower === "content-length") return;
    headers.set(name, value);
  });
  for (const [name, value] of Object.entries(corsHeaders())) {
    headers.set(name, value);
  }
  headers.set("X-Relay-Ms", String(Date.now() - started));

  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers,
  });
}
