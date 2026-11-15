// relayFetch.ts — the single funnel for innertube API calls through the WS-W relay.
//
// RELAY CONTRACT (front-end/relay, another agent's work):
//   GET {relay}/r?u=<encodeURIComponent(target)> proxies the incoming request's method +
//   headers (minus Origin/Referer, which the browser forces onto every cross-origin
//   fetch) to the target URL and returns the upstream status + body with ACAO:* so the
//   browser can read it. POST bodies pass through. Content-Range/Content-Length are
//   exposed on responses.
//
// Effect: YouTube's innertube API is reached from the RELAY's (datacenter CF) IP while
// this page runs on the visitor's residential IP — the API half of the canary's split
// traffic model. MEDIA bytes (googlevideo) are fetched DIRECT, never through here
// (that residential half lives in probeMatrix.ts R1/R3).
//
// KNOWN DEVIATION (documented, not worked around): browsers cannot set the User-Agent
// header via fetch (forbidden header name). Through the relay, YouTube sees the
// BROWSER's UA at the header level; the matrix UA still travels in the JSON body
// (context.client.userAgent, settable) and the client identity travels in the
// X-YouTube-Client-* headers. Every ProbeRow carries
// notes = 'ua=browser (fetch cannot set UA)'.
//
// NOTE: POSTing Content-Type: application/json + X-YouTube-Client-* through the relay
// triggers CORS preflights (OPTIONS /r?u=...) — the relay must answer them with
// ACAO:* and allow those request headers (the deployed relay does).
//
// This helper NEVER throws: network/CORS/parse failures come back as
// { status: 0, error: '...' } so one bad request never aborts a probe run.

export interface RelayFetchInit {
  method: string;
  headers?: Record<string, string>;
  body?: string;
}

export interface RelayFetchResult {
  /** upstream HTTP status (0 = the fetch itself failed) */
  status: number;
  /** parsed JSON body when parseable, else null */
  json: any;
  /** the raw Response (unconsumed — json() is read from a clone), or null on fetch failure */
  raw: Response | null;
  /** round-trip latency, ms (performance.now delta) */
  latencyMs: number;
  /** set when the fetch itself failed or the relay surfaced an error */
  error?: string;
}

export async function relayFetch(
  relayBase: string,
  targetUrl: string,
  init: RelayFetchInit
): Promise<RelayFetchResult> {
  const base = relayBase.trim().replace(/\/+$/, '');
  const relayUrl = `${base}/r?u=${encodeURIComponent(targetUrl)}`;
  const t0 = performance.now();
  // curl --max-time 20 analog (probe-clients.sh): a hung request must not stall the
  // 20-request matrix run — aborts surface as status 0 + 'TimeoutError: …' through
  // the catch below. Guarded so engines without AbortSignal.timeout degrade to no
  // timeout instead of failing every request.
  const timeoutSignal: AbortSignal | undefined =
    typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(20000) : undefined;
  try {
    const raw = await fetch(relayUrl, {
      method: init.method,
      headers: init.headers,
      body: init.body,
      signal: timeoutSignal
    });
    const latencyMs = Math.round(performance.now() - t0);
    let json: any = null;
    try {
      // read from a clone so `raw` stays unconsumed for callers that need
      // headers/body afterwards (e.g. Content-Range on ranged relay responses)
      json = await raw.clone().json();
    } catch {
      json = null; // non-JSON upstream body (HTML error page, empty body, …)
    }
    return { status: raw.status, json, raw, latencyMs };
  } catch (e) {
    return {
      status: 0,
      json: null,
      raw: null,
      latencyMs: Math.round(performance.now() - t0),
      error: e instanceof Error ? `${e.name}: ${e.message}` : String(e)
    };
  }
}
