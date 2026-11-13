/**
 * lib/ytSession.ts — G3b: the YouTube.js (youtubei.js) session layer.
 *
 * THE OPEN-SOURCE PIVOT (decisions/G3b-open-source-botguard.md): the P3b
 * custom hidden-iframe BotGuard harness and the hand-rolled InnerTube
 * player calls are REPLACED by LuanRT's battle-tested stack —
 * youtubei.js for session/client/stream management, bgutils-js for PO
 * token minting (audio/botguard/potMinter.ts). This module owns the
 * Innertube session: ONE instance, bootstrapped through the relay, whose
 * visitorData binds EVERYTHING (search context, player calls, PO token
 * mints, the &pot= GVS param) into a single coherent session envelope.
 *
 * WHY EVERYTHING GOES THROUGH THE RELAY (the TSG binding rule):
 *   visitor_data + po_token are IP/ASN-bound — the mint leg and the use
 *   leg must egress from the same IP. The relay is that single egress
 *   (challenge → GenerateIT → /player → googlevideo), so the whole
 *   session rides one Cloudflare edge identity. The WAA endpoints are
 *   CORS-open, but a DIRECT browser mint (phone IP) would split the
 *   envelope against the relay-egressed player call — exactly the
 *   P3b split-brain class of failure, at a different seam.
 *
 * THE EVAL SHIM: since v16 youtubei.js ships no JS interpreter; the
 * browser platform's default `eval` is a THROWING STUB. Player.decipher
 * appends a `process(n, sp, s)` tail to the extracted script that
 * returns `{ sig, n }` directly (src/utils/Utils.ts#getNsigProcessorFn),
 * so a plain `new Function(data.output)()` satisfies the contract —
 * the same pattern kira and the bgutils examples use.
 */

import { Innertube, Platform, UniversalCache } from 'youtubei.js/web';
import { relayBaseCandidate } from './engine';
import { relayUrl } from '../audio/botguard/protocol';

// ---------------------------------------------------------------------------
// The eval shim (once, at module load)
// ---------------------------------------------------------------------------

/**
 * youtubei.js's deciphering contract (v16+): `data.output` is the
 * extracted player script with an appended top-level
 * `return process(...)`; the evaluator returns its result (`{ sig?, n? }`).
 * `env` is unused on this path — the appended tail embeds the arguments.
 */
Platform.shim.eval = ((data: { output: string }) =>
  // eslint-disable-next-line no-new-func -- required by the youtubei.js eval contract
  new Function(data.output)()) as unknown as typeof Platform.shim.eval;

// ---------------------------------------------------------------------------
// The relay fetch — every youtubei.js request funnel
// ---------------------------------------------------------------------------

/**
 * Route a fetch through the relay's /r passthrough (kira's proven
 * rewrite pattern, pointed at our Worker):
 *   • the browser cannot set User-Agent (forbidden header) — the relay
 *     normalizes it per X-YouTube-Client-Name (the P3b matrix) and pins
 *     the sec-ch-ua* envelope on the WAA + WEB legs;
 *   • `credentials: 'omit'` — youtubei.js's HTTPClient forces 'include'
 *     in browsers, which the relay's ACAO:* cannot answer; we never want
 *     cookies on this anonymous session anyway;
 *   • Request-carried bodies/methods/headers are preserved (youtubei.js
 *     passes API calls as Request objects with a parallel init).
 */
export async function ytFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const url =
    input instanceof URL
      ? input
      : new URL(typeof input === 'string' ? input : input.url);
  const target = relayUrl(relayBaseCandidate(), url.toString());
  const headers = new Headers(init?.headers);
  if (input instanceof Request) {
    for (const [name, value] of input.headers.entries()) {
      if (!headers.has(name)) headers.set(name, value);
    }
  }
  headers.delete('user-agent'); // forbidden in browsers; the relay owns UA policy
  const request = new Request(
    target,
    input instanceof Request ? (input as RequestInit) : undefined,
  );
  return fetch(request, {
    ...init,
    headers,
    credentials: 'omit',
  });
}

// ---------------------------------------------------------------------------
// The session singleton
// ---------------------------------------------------------------------------

let ytPromise: Promise<Innertube> | null = null;

/**
 * The app-wide Innertube session (memoized; concurrent callers share one
 * bootstrap). Bootstraps through the relay:
 *   • sw.js_data → the REAL visitorData the whole session binds to
 *     (unless the persistent session cache answers first);
 *   • /youtubei/v1/config → cold config;
 *   • iframe_api + /s/player/<id>/player_es6…/base.js → the player for
 *     signatureTimestamp + nsig/sig deciphering (WEB-rung URLs).
 * The UniversalCache(true) persists session + player in IndexedDB, so
 * repeat visits skip the bootstrap legs entirely.
 */
export function getYt(): Promise<Innertube> {
  if (ytPromise === null) {
    ytPromise = Innertube.create({
      fetch: ytFetch,
      cache: new UniversalCache(true),
      // WEB session context: the plain, honest browser identity. The
      // relay pins its UA + client hints on the WEB legs; the APP-family
      // rungs (VISIONOS) get the matrix UA via X-YouTube-Client-Name.
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
