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
 * HARD RULES baked in from the W0.1 spike:
 *   • The client's User-Agent is FORWARDED VERBATIM. Workers' default UA gets
 *   404s on some YouTube endpoints while a browser-like UA passes — UA
 *   fidelity is deliberate, not an oversight.
 *   • The upstream STATUS is preserved EXACTLY (206 stays 206, 403 stays 403)
 *   and the body is passed as a STREAM, never buffered. Byte-range audio
 *   from googlevideo through this relay is the project kill criterion.
 *
 * Pure Workers runtime — no external dependencies. ES-module default export.
 */

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
// Worker entry
// ---------------------------------------------------------------------------

export default {
  async fetch(request: Request): Promise<Response> {
    try {
      return await route(request);
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

/** Route table: OPTIONS anywhere → preflight; /health; /; /bg-session; /r; else 404. */
async function route(request: Request): Promise<Response> {
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
        phase: "P3b — BotGuard egress pivot (VisionOS fast path, envelope pinning)",
        // P3b capability probe: the web app checks these to know whether the
        // deployed worker has the 3-Tier surface (an old P0/W1.0 deployment
        // answers this route without the field — capability = absent).
        capabilities: [
          "bg-session", // W1.0 — minting surface
          "ua-override", // P3b — X-Relay-UA named overrides (WEB_SAFARI)
          "client-hints-passthrough", // P3b — sec-ch-ua* on WAA + WEB legs
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

  if (path === "/r") {
    if (!FORWARDED_METHODS.has(method)) {
      return jsonResponse({ error: "method not allowed", method }, 405);
    }
    return relay(request, url);
  }

  return jsonResponse({ error: "not found" }, 404);
}

// ---------------------------------------------------------------------------
// /r — the relay proper
// ---------------------------------------------------------------------------

async function relay(request: Request, url: URL): Promise<Response> {
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

  // ---- Body -----------------------------------------------------------------
  // POST bodies (innertube JSON payloads, WAA protobuf-JSON) stream through
  // untouched. GET/HEAD must be bodyless — fetch() throws otherwise.
  const body: ReadableStream<Uint8Array> | null =
    method === "POST" ? request.body : null;

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
