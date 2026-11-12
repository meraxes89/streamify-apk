/**
 * streamify-relay — WS-W P0 minimal CORS relay (Cloudflare Worker).
 *
 * Browsers cannot call youtubei.googleapis.com / *.googlevideo.com directly:
 * CORS returns 403 (the structural reason this relay exists). This worker is
 * the dumb pipe that makes the browser able to be an engine:
 *
 *     browser fetch ──▶ RELAY /r?u=<encoded-target> ──▶ youtube family
 *                        ◀── response streamed back, ACAO: *
 *
 * P0 scope (W0.4) — deliberately minimal. NOT here (P4 / W4.1): JWT
 * minting, per-key rate limits, kill switch, abuse analytics.
 *
 * Contract details the wasm facade + web app depend on:
 *  - Host allowlist: www.youtube.com, music.youtube.com,
 *    youtubei.googleapis.com, *.googlevideo.com (https only).
 *  - Method allowlist: GET / HEAD / POST.
 *  - CORS `*` on EVERY response — 2xx, 4xx, 5xx, preflight, healthz.
 *  - `X-Streamify-UA` request header is swapped into the outbound
 *    User-Agent (browsers forbid setting UA on fetch; the app-client
 *    recipes need app UAs — W0.1: default fetch UA gets 404s, real
 *    client UAs pass).
 *  - Inbound Origin/Referer/Cookie/sec-* are stripped; Origin/Referer are
 *    restored from the target host (engine Tier-5 + probe parity).
 *  - Range headers ride through; Content-Range/Content-Length ride back via
 *    Access-Control-Expose-Headers. 206 partial-content passthrough is the
 *    P0 kill-criterion test.
 */

export interface Env {
  // P0: no bindings. P4 (W4.1) adds the kill switch / rate-limit KV here.
}

const ALLOWED_EXACT = new Set([
  "www.youtube.com",
  "music.youtube.com",
  "youtubei.googleapis.com",
]);
const ALLOWED_SUFFIX = ".googlevideo.com";

// W0.1 spike: browser-like UA passes where default fetch UAs get 404 (iframe
// API). Used only when the inbound request carries no User-Agent at all.
const DEFAULT_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const CORS_BASE: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS",
  "Access-Control-Allow-Headers":
    "Content-Type, Range, X-Streamify-UA, X-YouTube-Client-Name, X-YouTube-Client-Version, X-Goog-Visitor-Id",
  "Access-Control-Expose-Headers":
    "Content-Range, Content-Length, Accept-Ranges, Content-Type",
  "Access-Control-Max-Age": "86400",
  "Timing-Allow-Origin": "*",
};

const PASS_THROUGH_RESPONSE_HEADERS = [
  "content-type",
  "content-length",
  "content-range",
  "accept-ranges",
  "content-disposition",
  "cache-control",
  "date",
  "etag",
  "last-modified",
  "expires",
  "vary",
];

function hostAllowed(hostname: string): boolean {
  return ALLOWED_EXACT.has(hostname) || hostname.endsWith(ALLOWED_SUFFIX);
}

function corsHeaders(upstream?: Headers): Headers {
  const h = new Headers();
  for (const [k, v] of Object.entries(CORS_BASE)) {
    h.set(k, v);
  }
  if (upstream) {
    for (const name of PASS_THROUGH_RESPONSE_HEADERS) {
      const v = upstream.get(name);
      if (v !== null) {
        h.set(name, v);
      }
    }
  }
  return h;
}

function jsonResponse(status: number, body: unknown): Response {
  const h = corsHeaders();
  h.set("Content-Type", "application/json");
  return new Response(JSON.stringify(body), { status, headers: h });
}

const STRIP_INBOUND = new Set([
  "origin",
  "referer",
  "host",
  "cookie",
  "user-agent",
  "x-streamify-ua",
]);

function buildOutboundHeaders(request: Request, targetUrl: URL): Headers {
  const out = new Headers();
  for (const [name, value] of request.headers.entries()) {
    const lower = name.toLowerCase();
    if (STRIP_INBOUND.has(lower)) {
      continue;
    }
    if (lower.startsWith("sec-") || lower.startsWith("sec-ch-")) {
      continue;
    }
    out.set(name, value);
  }

  const swapUa = request.headers.get("X-Streamify-UA");
  if (swapUa) {
    out.set("User-Agent", swapUa);
  } else if (request.headers.get("User-Agent")) {
    out.set("User-Agent", request.headers.get("User-Agent")!);
  } else {
    out.set("User-Agent", DEFAULT_UA);
  }

  // Engine Tier-5 + probe parity: app-client requests carry Origin/Referer
  // matching the target host (resolver.rs sets them explicitly natively;
  // the browser's were stripped above).
  if (
    targetUrl.hostname === "www.youtube.com" ||
    targetUrl.hostname === "music.youtube.com" ||
    targetUrl.hostname === "youtubei.googleapis.com"
  ) {
    out.set("Origin", `https://${targetUrl.hostname}`);
    out.set("Referer", `https://${targetUrl.hostname}/`);
  }

  return out;
}

export default {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (url.pathname === "/healthz") {
      return jsonResponse(200, {
        ok: true,
        service: "streamify-relay",
        phase: "P0",
      });
    }

    if (url.pathname !== "/r") {
      return jsonResponse(404, {
        ok: false,
        error:
          "not found — WS-W P0 relay; use GET/POST /r?u=<encoded-target> (see /healthz)",
      });
    }

    const target = url.searchParams.get("u");
    if (!target) {
      return jsonResponse(400, { ok: false, error: "missing ?u=<encoded target url>" });
    }

    let targetUrl: URL;
    try {
      targetUrl = new URL(target);
    } catch {
      return jsonResponse(400, { ok: false, error: "invalid target url" });
    }

    if (targetUrl.protocol !== "https:" || !hostAllowed(targetUrl.hostname)) {
      return jsonResponse(403, {
        ok: false,
        error: `host not allowed: ${targetUrl.hostname || "(unparseable)"}`,
      });
    }

    if (!["GET", "HEAD", "POST"].includes(request.method)) {
      return jsonResponse(405, {
        ok: false,
        error: `method ${request.method} not allowed`,
      });
    }

    try {
      const upstream = await fetch(targetUrl.toString(), {
        method: request.method,
        headers: buildOutboundHeaders(request, targetUrl),
        body:
          request.method === "GET" || request.method === "HEAD"
            ? undefined
            : request.body,
        redirect: "follow",
      });

      // Stream the body straight through so 206 partial-content responses
      // (byte-range audio) reach the browser intact — the P0 kill criterion.
      return new Response(upstream.body, {
        status: upstream.status,
        headers: corsHeaders(upstream.headers),
      });
    } catch (e) {
      return jsonResponse(502, {
        ok: false,
        error: `upstream fetch failed: ${e instanceof Error ? e.message : String(e)}`,
      });
    }
  },
} satisfies ExportedHandler<Env>;
