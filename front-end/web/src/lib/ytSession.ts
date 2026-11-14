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
 * YouTube serves three surface families to CROSS-ORIGIN browser callers —
 * MEASURED 2026-11-14 (resolver-canary run 43564752070, clean runner IP):
 *
 *   • jnn-pa.googleapis.com — the WAA BotGuard endpoints (Create /
 *     GenerateIT): CORS-OPEN, preflight reflects the foreign origin and
 *     allows the exact bgutils header set. THE mint legs — the PO token
 *     binds to the phone's IP.
 *   • youtubei.googleapis.com — the innertube API host: NO ACAO for
 *     foreign origins (the preflight carries no access-control-allow-
 *     origin; kira's proxy spoofs a youtube.com origin for exactly this
 *     reason). Every direct player/search/config fetch dies at preflight
 *     in a browser → the per-request relay fallback is the operative
 *     lane, and the direct-lane negative cache (below) makes later
 *     attempts skip the doomed preflight entirely.
 *   • *.googlevideo.com — the media CDN (progressive bytes via the
 *     no-cors <audio> element; HLS manifests; SABR initplayback POSTs —
 *     those serve ACAO to youtube.com only and ride the relay).
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
 *
 * G4.1 — THE DIRECT-BORN VISITOR (decisions/G4-residential-direct.md): the
 * session's visitorData used to be born on the RELAY egress (youtubei.js
 * mints it from sw.js_data, a www leg), making every "phone-egress" player
 * call carry a datacenter-born visitor identity — a split-brain envelope
 * the G4 doc flagged as the next lever. The lever, now pulled: POST
 * /youtubei/v1/config on the googleapis surface with NO visitor id; the
 * responseContext.visitorData is a visitor BORN on this egress (the phone's
 * IP on the direct lane). It is persisted (30 d) and fed into
 * Innertube.create({ visitor_data }) — youtubei.js honors the seed in both
 * the fresh and the cached session paths (Session.js #getSessionData:
 * `visitor_data: options.visitor_data || device_info[13]`), so visitor,
 * mint and player calls all share ONE phone-born identity.
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

/**
 * G4.1 — the direct-lane negative cache.
 *
 * CANARY-MEASURED 2026-11-14 (run 43564752070, clean runner IP):
 * youtubei.googleapis.com's CORS preflight carries NO
 * access-control-allow-origin for a foreign origin — a browser page on
 * github.io can NEVER fetch it directly (kira's proxy exists for exactly
 * this reason: it spoofs a youtube.com origin and rewrites ACAO on the
 * way back). Every direct player fetch therefore dies at preflight
 * (~100–300 ms per attempt — six-plus attempts on a first licensed play).
 *
 * The first network-layer failure on a direct host marks it DEAD for this
 * page session: later requests for the same host go straight to the relay
 * (whose egress the googleapis surface still serves — IOS answered OK for
 * licensed content even from the burned Cloudflare IP, the G4 matrix).
 * jnn-pa (the mint) is CORS-OPEN to foreign origins — live-verified — so
 * it never lands here and the PO-token mint stays on the phone's IP.
 *
 * The set is per page load (a posture change or a network change heals on
 * refresh); the nightly resolver-canary watches the posture itself.
 */
const directLaneDeadHosts = new Set<string>();

function markDirectLaneDead(host: string): void {
  if (directLaneDeadHosts.size < 32) directLaneDeadHosts.add(host); // bound paranoia
}

/** Dev/diagnostics: is this host's direct lane still considered alive? */
export function directLaneHealthy(url: URL): boolean {
  return !directLaneDeadHosts.has(url.hostname.toLowerCase());
}

// ---------------------------------------------------------------------------
// G4.1 — the direct-born visitorData
// ---------------------------------------------------------------------------

const VISITOR_KEY = 'streamify.yt.visitor.v2';
/** A visitor identity is long-lived; a direct birth is trusted for 30 d. */
const VISITOR_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/**
 * A relay-born visitor is the NORM, not the exception (canary-measured
 * 2026-11-14: youtubei.googleapis.com serves no ACAO to foreign origins,
 * so the /config birth rides the relay in every browser). It is trusted
 * for 7 d — long enough to keep the visitor (and the POT cache bound to
 * it) stable across app opens, short enough that a healed direct posture
 * upgrades the identity within a week.
 */
const RELAY_VISITOR_TTL_MS = 7 * 24 * 60 * 60 * 1000;

interface StoredVisitor {
  visitorData: string;
  bornAt: number;
  provenance: 'direct' | 'relay';
}

let visitorProvenanceState: 'direct' | 'relay' | 'session' = 'session';

/** Where the session's visitorData was born (UI/diagnostics). */
export function visitorProvenance(): 'direct' | 'relay' | 'session' {
  return visitorProvenanceState;
}

function visitorTtlMs(provenance: StoredVisitor['provenance']): number {
  return provenance === 'direct' ? VISITOR_TTL_MS : RELAY_VISITOR_TTL_MS;
}

function readStoredVisitor(): StoredVisitor | null {
  try {
    const raw = localStorage.getItem(VISITOR_KEY);
    if (raw === null) return null;
    const parsed = JSON.parse(raw) as Partial<StoredVisitor>;
    if (
      typeof parsed.visitorData !== 'string' ||
      parsed.visitorData.length === 0 ||
      typeof parsed.bornAt !== 'number' ||
      (parsed.provenance !== 'direct' && parsed.provenance !== 'relay')
    ) {
      return null;
    }
    const stored = parsed as StoredVisitor;
    if (Date.now() - stored.bornAt > visitorTtlMs(stored.provenance)) {
      return null; // expired — a fresh birth (direct lane first) is due
    }
    return stored;
  } catch {
    return null; // private mode / corrupted — mint a fresh one
  }
}

function storeVisitor(v: StoredVisitor): void {
  try {
    localStorage.setItem(VISITOR_KEY, JSON.stringify(v));
  } catch {
    /* private mode — this page load keeps its visitor in memory */
  }
}

/**
 * Mint (or restore) the DIRECT-BORN visitorData (G4.1).
 *
 * POST /youtubei/v1/config on the googleapis surface carrying NO visitor id
 * (neither header nor context) — the responseContext.visitorData that comes
 * back is a visitor BORN on the calling egress. Lane order: DIRECT first
 * (phone IP), then one relay retry — with the canary-measured posture the
 * relay is the lane that answers, and its birth is persisted too (7 d): a
 * stable relay-born identity beats a fresh visitor every page load (visitor
 * churn would also drop the POT cache on every boot — the binding-drift
 * guard). A direct birth (a healed posture, an extension-context future)
 * persists for 30 d. Live-verified 2026-11-14: the endpoint mints a fresh
 * visitor even from the relay's burned Cloudflare egress.
 */
export async function directBornVisitorData(
  signal?: AbortSignal | null,
): Promise<string | null> {
  const stored = readStoredVisitor();
  if (stored !== null) {
    visitorProvenanceState = stored.provenance;
    return stored.visitorData;
  }
  const endpoint =
    'https://youtubei.googleapis.com/youtubei/v1/config?prettyPrint=false&alt=json';
  const body = JSON.stringify({
    context: {
      client: { clientName: 'WEB', clientVersion: '2.20260708.00.00', hl: 'en', gl: 'US' },
    },
  });
  const lanes: Array<{ url: string; provenance: 'direct' | 'relay' }> = [
    { url: endpoint, provenance: 'direct' },
    { url: relayUrl(relayBaseCandidate(), endpoint), provenance: 'relay' },
  ];
  for (const lane of lanes) {
    try {
      const res = await fetch(lane.url, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
        credentials: 'omit',
        ...(signal !== null && signal !== undefined ? { signal } : {}),
      });
      // An HTTP error is a real verdict for THIS lane (e.g. an IP block) —
      // the other lane gets its chance.
      if (!res.ok) continue;
      const json = (await res.json()) as {
        responseContext?: { visitorData?: string };
      };
      const vd = json.responseContext?.visitorData;
      if (typeof vd === 'string' && vd.length > 0) {
        visitorProvenanceState = lane.provenance;
        storeVisitor({ visitorData: vd, bornAt: Date.now(), provenance: lane.provenance });
        return vd;
      }
    } catch {
      // Network-layer failure (CORS/DNS/offline) — next lane.
    }
  }
  return null; // both lanes dead → the session falls back to its own mint
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

  if (isDirectEgressHost(target) && directLaneHealthy(target)) {
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
      // G4.1: a dead direct lane is remembered for the page session —
      // the CORS posture cannot heal mid-load, so later requests skip
      // the doomed preflight entirely. This request still falls through
      // to the relay: degraded (datacenter) egress beats no egress.
      markDirectLaneDead(target.hostname.toLowerCase());
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
  const target = new URL(endpoint);
  // G4.1: a direct lane that already failed this page session (the
  // canary-measured CORS posture: no ACAO for foreign origins on
  // youtubei.googleapis.com) goes straight to the relay — the doomed
  // preflight round-trip is pure latency on every subsequent attempt.
  if (isDirectEgressHost(target) && directLaneHealthy(target)) {
    try {
      const response = await fetch(endpoint, init);
      lastEgressLane.set(target.hostname.toLowerCase(), 'direct');
      return { response, lane: 'direct' };
    } catch {
      // Direct lane unreachable (CORS preflight refused / network
      // blocked) — remembered for the page session, retried once on the
      // relay: the verdict (if any) belongs to the relay egress.
      markDirectLaneDead(target.hostname.toLowerCase());
    }
  }
  const relayTarget = relayUrl(relayBaseCandidate(), endpoint);
  const response = await fetch(relayTarget, init);
  lastEgressLane.set(target.hostname.toLowerCase(), 'relay');
  return { response, lane: 'relay' };
}

// ---------------------------------------------------------------------------
// The session singleton
// ---------------------------------------------------------------------------

let ytPromise: Promise<Innertube> | null = null;

/**
 * The app-wide Innertube session (memoized; concurrent callers share one
 * bootstrap). Bootstraps through the dual-lane funnel:
 *   • a DIRECT-BORN visitorData (G4.1: minted by /config on the googleapis
 *     surface, persisted 30 d, restored zero-network) seeds the session —
 *     youtubei.js honors the seed in both the fresh and the cached paths,
 *     so visitor + mint + player calls share one phone-born identity;
 *   • /youtubei/v1/config on youtubei.googleapis.com — DIRECT (the phone's
 *     own IP is now the session egress);
 *   • sw.js_data + iframe_api + /s/player/<id>/base.js on www.youtube.com —
 *     relay (content legs; signatureTimestamp + nsig/sig deciphering).
 * The UniversalCache(true) persists session + player in IndexedDB, so
 * repeat visits skip the bootstrap legs entirely.
 */
export function getYt(): Promise<Innertube> {
  if (ytPromise === null) {
    ytPromise = (async () => {
      // G4.1: the direct-born visitor seed — a ~300 ms config POST on the
      // first visit (instant from localStorage afterwards). A null (both
      // lanes dead) falls back to youtubei.js's own sw.js_data mint, the
      // G4 behavior — degraded but alive.
      const visitorData = await directBornVisitorData();
      return await Innertube.create({
        fetch: ytFetch,
        cache: new UniversalCache(true),
        retrieve_player: true,
        enable_safety_mode: false,
        ...(visitorData !== null ? { visitor_data: visitorData } : {}),
      });
    })().catch((err: unknown) => {
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

/**
 * Dev/diagnostics: drop the singleton (the IndexedDB cache stays) and the
 * persisted direct-born visitor (forces a fresh birth on the next getYt).
 */
export function resetYtSession(): void {
  ytPromise = null;
  try {
    localStorage.removeItem(VISITOR_KEY);
  } catch {
    /* best-effort */
  }
}
