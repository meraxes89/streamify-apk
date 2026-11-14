/**
 * lib/ytSession.ts — G4: the YouTube.js (youtubei.js) session layer,
 * DUAL-LANE egress.
 *
 * THE G4 PIVOT (decisions/G4-residential-direct.md): the relay's shared
 * Cloudflare Workers egress was measured (2026-11-14) to be bot-walled for
 * licensed music on EVERY innertube client (LOGIN_REQUIRED / UNPLAYABLE
 * "This video is unavailable"), and PO tokens minted through it are
 * dead-on-arrival for the same reason the BgUtils README documents: "a PO
 * Token does not bypass IP-based login restrictions (common on datacenter
 * IPs)". The phone's own browser — real hardware, residential IP — is the
 * only first-class egress we have.
 *
 * YouTube serves exactly three surface families to CROSS-ORIGIN browsers
 * (the same surfaces the real web player calls cross-origin, so they ship
 * Access-Control-Allow-Origin):
 *
 *   • youtubei.googleapis.com — the innertube API host youtubei.js itself
 *     defaults to (its Constants.API_ORIGIN); player/search/config calls.
 *   • jnn-pa.googleapis.com — the WAA BotGuard endpoints (Create /
 *     GenerateIT), the mint legs.
 *   • *.googlevideo.com — the media CDN (progressive bytes, HLS manifests,
 *     SABR initplayback), CORS-open for MSE-style consumption.
 *
 * ytFetch therefore routes per-host:
 *
 *   DIRECT  youtubei.googleapis.com, jnn-pa.googleapis.com,
 *           googlevideo.com / *.googlevideo.com
 *           → the phone's own IP; the mint and the player calls share ONE
 *             residential egress (the TSG single-envelope rule, finally
 *             honored at the only egress that can satisfy it).
 *   RELAY   www.youtube.com, music.youtube.com (CORS-walled content legs:
 *           sw.js_data, /s/player base.js, iframe_api, the www-surface
 *           fallback rungs) → the Cloudflare Worker, which is fine for
 *           CONTENT (IP-insensitive) and remains the fallback egress for
 *           everything else.
 *
 * RESILIENCE: a DIRECT attempt that throws at the network layer (a network
 * that blocks googleapis, an offline moment, a hardened browser) falls back
 * to the RELAY for that same request — degraded (datacenter egress) but
 * alive. A DIRECT response with an HTTP error status is a real verdict and
 * is returned as-is (never retried on the other lane: playability verdicts
 * must stay attributable to their egress).
 *
 * THE EVAL SHIM: since v16 youtubei.js ships no JS interpreter; the browser
 * platform's default `eval` is a THROWING STUB. Player.decipher appends a
 * `process(n, sp, s)` tail to the extracted script that returns
 * `{ sig, n }` directly (src/utils/Utils.ts#getNsigProcessorFn), so a plain
 * `new Function(data.output)()` satisfies the contract — the same pattern
 * kira and the bgutils examples use.
 */

import { Innertube, Platform, UniversalCache } from 'youtubei.js/web';
import { relayBaseCandidate } from './engine';
import { relayUrl } from '../audio/botguard/protocol';

// ---------------------------------------------------------------------------
// The eval shim (once, at module load)
// ---------------------------------------------------------------------------

Platform.shim.eval = ((data: { output: string }) =>
  // eslint-disable-next-line no-new-func -- required by the youtubei.js eval contract
  new Function(data.output)()) as unknown as typeof Platform.shim.eval;

// ---------------------------------------------------------------------------
// The egress classifier
// ---------------------------------------------------------------------------

/**
 * Hosts a browser page can reach DIRECTLY (upstream ships ACAO — the
 * cross-origin surfaces of the real YouTube web player). Everything else
 * youtube-related needs the relay.
 */
export function isDirectEgressHost(url: URL): boolean {
  const host = url.hostname.toLowerCase();
  return (
    host === 'youtubei.googleapis.com' ||
    host === 'jnn-pa.googleapis.com' ||
    host === 'googlevideo.com' ||
    host.endsWith('.googlevideo.com')
  );
}

/** Dev/diagnostics: was the last ytFetch for this host direct or relayed? */
const lastEgressLane = new Map<string, 'direct' | 'relay'>();
export function egressLaneFor(url: string): 'direct' | 'relay' | 'untried' {
  try {
    return lastEgressLane.get(new URL(url).hostname.toLowerCase()) ?? 'untried';
  } catch {
    return 'untried';
  }
}

// ---------------------------------------------------------------------------
// Header assembly (shared by both lanes)
// ---------------------------------------------------------------------------

/**
 * Merge headers exactly like the G3b funnel did: init headers win, Request
 * headers fill the gaps, the browser-forbidden UA is dropped (the DIRECT
 * lane's real browser UA IS the honest envelope for web clients; app-client
 * rungs carry their UA inside the JSON body, where browsers can set it).
 */
function mergedHeaders(input: RequestInfo | URL, init?: RequestInit): Headers {
  const headers = new Headers(init?.headers);
  if (input instanceof Request) {
    for (const [name, value] of input.headers.entries()) {
      if (!headers.has(name)) headers.set(name, value);
    }
  }
  headers.delete('user-agent'); // forbidden in browsers; each lane owns UA policy
  return headers;
}

// ---------------------------------------------------------------------------
// ytFetch — the dual-lane funnel every youtubei.js request rides
// ---------------------------------------------------------------------------

/**
 * Route one youtubei.js request:
 *   direct host → DIRECT fetch (relay retry on network-layer failure)
 *   otherwise   → relay /r?u= passthrough (the G3b behavior)
 * Request-carried bodies/methods are preserved on both lanes (the direct
 * attempt rides a clone; the original stays intact for the relay retry).
 *
 * THE API-SURFACE REWRITE (the load-bearing G4 trick): youtubei.js builds
 * every innertube API call against www.youtube.com/youtubei/ (its
 * Constants.URLS.API.PRODUCTION_1 — see HTTPClient.fetch). www.youtube.com
 * is CORS-walled, which is exactly what dragged the whole session onto
 * the relay's burned Cloudflare egress. The IDENTICAL API is served on
 * youtubei.googleapis.com (youtubei.js's own PRODUCTION_2 — probe-verified
 * 2026-11-14: player + search + config all answer on that host, CORS-open).
 * Swapping the host moves search, player and config calls onto the phone's
 * DIRECT egress while the content legs (sw.js_data, /s/player base.js,
 * iframe_api — non-/youtubei paths) stay on www → relay.
 */
export async function ytFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const url =
    input instanceof URL
      ? input
      : new URL(typeof input === 'string' ? input : input.url);
  const headers = mergedHeaders(input, init);

  let target = url;
  if (
    url.hostname === 'www.youtube.com' &&
    url.pathname.startsWith('/youtubei/')
  ) {
    target = new URL(url.toString());
    target.hostname = 'youtubei.googleapis.com';
  }

  if (isDirectEgressHost(target)) {
    // The direct attempt rides a clone with the (possibly rewritten) URL;
    // the original stays intact for the relay fallback.
    const directInput =
      input instanceof Request
        ? new Request(target.toString(), input.clone())
        : target;
    try {
      const response = await fetch(directInput, {
        ...init,
        headers,
        credentials: 'omit',
      });
      lastEgressLane.set(target.hostname.toLowerCase(), 'direct');
      return response;
    } catch {
      // Network-layer failure only (fetch throws on DNS/TLS/CORS/refused —
      // NOT on HTTP error statuses, which are returned above as-is).
      // Fall through to the relay: degraded egress beats no egress.
    }
  }

  const relayTarget = relayUrl(relayBaseCandidate(), target.toString());
  const request = new Request(
    relayTarget,
    input instanceof Request ? (input as unknown as RequestInit) : undefined,
  );
  lastEgressLane.set(target.hostname.toLowerCase(), 'relay');
  return fetch(request, {
    ...init,
    headers,
    credentials: 'omit',
  });
}

// ---------------------------------------------------------------------------
// Direct fetch for the resolver's raw player calls (G4 rungs)
// ---------------------------------------------------------------------------

/**
 * A raw JSON POST to a DIRECT-class host (youtubei.googleapis.com player
 * calls). NOT routed through ytFetch: the resolver needs the fetch to go
 * DIRECT FIRST and to distinguish "direct lane unreachable" (network throw
 * → try relay) from a real upstream verdict (any HTTP status → return it).
 * Never retries a returned verdict — egress attribution stays honest.
 */
export async function directPlayerPost(
  endpoint: string,
  payload: unknown,
  headers: Record<string, string>,
  signal?: AbortSignal | null,
): Promise<{ response: Response; lane: 'direct' | 'relay' }> {
  const body = JSON.stringify(payload);
  const init: RequestInit = {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body,
    credentials: 'omit',
    ...(signal !== null && signal !== undefined ? { signal } : {}),
  };
  try {
    const response = await fetch(endpoint, init);
    lastEgressLane.set(new URL(endpoint).hostname.toLowerCase(), 'direct');
    return { response, lane: 'direct' };
  } catch {
    // Direct lane unreachable (CORS preflight refused / network blocked).
    // One relay retry — the verdict (if any) belongs to the relay egress.
    const target = relayUrl(relayBaseCandidate(), endpoint);
    const response = await fetch(target, init);
    lastEgressLane.set(new URL(endpoint).hostname.toLowerCase(), 'relay');
    return { response, lane: 'relay' };
  }
}

// ---------------------------------------------------------------------------
// The session singleton
// ---------------------------------------------------------------------------

let ytPromise: Promise<Innertube> | null = null;

/**
 * The app-wide Innertube session (memoized; concurrent callers share one
 * bootstrap). Bootstraps through the dual-lane funnel:
 *   • /youtubei/v1/config on youtubei.googleapis.com — DIRECT (the phone's
 *     own IP is now the session egress);
 *   • sw.js_data + iframe_api + /s/player/<id>/base.js on www.youtube.com —
 *     relay (content legs; signatureTimestamp + nsig/sig deciphering).
 * The UniversalCache(true) persists session + player in IndexedDB, so
 * repeat visits skip the bootstrap legs entirely.
 */
export function getYt(): Promise<Innertube> {
  if (ytPromise === null) {
    ytPromise = Innertube.create({
      fetch: ytFetch,
      cache: new UniversalCache(true),
      retrieve_player: true,
      enable_safety_mode: false,
    }).catch((err: unknown) => {
      // A failed bootstrap must not poison later retries.
      ytPromise = null;
      throw err;
    });
  }
  return ytPromise;
}

/** The session's visitorData — the PO-token content binding root. */
export async function ytVisitorData(): Promise<string> {
  const yt = await getYt();
  const visitorData = yt.session.context.client.visitorData;
  if (typeof visitorData !== 'string' || visitorData.length === 0) {
    throw new Error('YouTube.js session has no visitorData');
  }
  return visitorData;
}

/** Dev/diagnostics: drop the singleton (the IndexedDB cache stays). */
export function resetYtSession(): void {
  ytPromise = null;
}
