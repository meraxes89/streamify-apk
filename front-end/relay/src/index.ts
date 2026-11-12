/**
 * streamify-relay — P0 minimal CORS relay (W0.4 / Task 3-b).
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
 * PHASE NOTE (P0 — W0.4)
 *   No JWT, no rate limiting, no kill switch. Those land in P4 (W4.1):
 *   TODO(P4/W4.1): JWT auth on /r.
 *   TODO(P4/W4.1): per-client rate limiting (per-IP / per-token buckets).
 *   TODO(P4/W4.1): remote kill switch (env-bound flag → 503, still CORS-open).
 *
 * HARD RULES baked in from the W0.1 spike:
 *   • The client's User-Agent is FORWARDED VERBATIM. Workers' default UA gets
 *     404s on some YouTube endpoints while a browser-like UA passes — UA
 *     fidelity is deliberate, not an oversight.
 *   • The upstream STATUS is preserved EXACTLY (206 stays 206, 403 stays 403)
 *     and the body is passed as a STREAM, never buffered. Byte-range audio
 *     from googlevideo through this relay is the project kill criterion.
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
 *     behavior off them (YouTube treats wrong-origin requests differently);
 *   • cookie — an open relay must never ride the caller's session;
 *   • cf-* / x-forwarded-* / x-real-ip / true-client-ip — injected by
 *     Cloudflare's edge, they describe THIS worker, not the browser;
 *   • content-length — the runtime recomputes it from the actual (streamed)
 *     body; a stale copy would corrupt Range math.
 * Everything else the client sent is forwarded — notably User-Agent (W0.1
 * finding, see file header), accept*, content-type, range, and the YouTube
 * client headers (x-youtube-client-*, x-goog-api-key, authorization).
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
  // WS-W P0 landmine fix: browsers FORCE Sec-Fetch-*/sec-ch-ua* markers onto
  // every fetch (forbidden headers — the wasm engine cannot strip them).
  // YouTube reads them as "this ANDROID app request actually comes from a
  // browser page" and answers the player endpoint with the soft-block
  // "The page needs to be reloaded". The relay is the true client from the
  // upstream's perspective — these headers mis-describe the caller, so they
  // never pass.
  "sec-fetch-dest",
  "sec-fetch-mode",
  "sec-fetch-site",
  "sec-fetch-user",
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
  "sec-purpose",
  "dnt",
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Upstream host allowlist. googlevideo uses per-edge hostnames such as
 * `rr4---sn-x0.googlevideo.com`, so googlevideo is matched by the
 * `.googlevideo.com` SUFFIX; the bare apex is allowed defensively.
 */
function isAllowedHost(host: string): boolean {
  return (
    host === "www.youtube.com" ||
    host === "music.youtube.com" ||
    host === "youtubei.googleapis.com" ||
    host === "googlevideo.com" ||
    host.endsWith(".googlevideo.com")
  );
}

/**
 * CORS headers attached to EVERY response this Worker produces — success,
 * error, preflight, and upstream passthrough alike. `Access-Control-Expose-
 * Headers` whitelists exactly what browser-side JS (the wasm engine) may
 * read: the Range trio (Content-Range / Accept-Ranges / Content-Length) is
 * REQUIRED for byte-range audio; X-Relay-Ms is this relay's timing header.
 * Open ACAO:* is a DELIBERATE P0 decision — hardening lands P4 (W4.1).
 */
function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Expose-Headers":
      "Content-Range, Accept-Ranges, Content-Length, Content-Type, X-Relay-Ms",
  };
}

/** JSON response with CORS attached (every non-passthrough reply). */
function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
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

/** Route table: OPTIONS anywhere → preflight; /health; /; /r; else 404. */
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
        usage: "/r?u=<urlencoded-target>",
        phase: "P0 minimal — hardening lands P4 (W4.1)",
      },
      200,
    );
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

  const host = target.hostname.toLowerCase();
  if (!isAllowedHost(host)) {
    // TODO(P4/W4.1): log/alert on allowlist violations (abuse signal).
    return jsonResponse({ error: "host not allowed", host }, 403);
  }

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
  const uaOverride = upstreamUserAgent(request.headers);
  const outgoing = new Headers();
  request.headers.forEach((value, name) => {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || REQUEST_DROP.has(lower)) return;
    if (uaOverride !== null && lower === "user-agent") return; // set below
    outgoing.set(name, value);
  });
  if (uaOverride !== null) {
    outgoing.set("user-agent", uaOverride);
  }

  // ---- Body -----------------------------------------------------------------
  // POST bodies (innertube JSON payloads) stream through untouched.
  // GET/HEAD must be bodyless — fetch() throws otherwise.
  const body: ReadableStream<Uint8Array> | null =
    method === "POST" ? request.body : null;

  // ---- Upstream fetch --------------------------------------------------------
  let upstream: Response;
  try {
    upstream = await fetch(target.toString(), {
      method,
      headers: outgoing,
      body,
      redirect: "follow",
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
