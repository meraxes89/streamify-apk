/// <reference types="vite/client" />
/**
 * lib/gsfLane.ts — G5: the GSF (Google-Script Fetch) engine-egress lane.
 *
 * THE LANE (decisions/G5-gsf-unburnable-lane.md): the user deploys the
 * tools/gsf Apps Script once (free, 2 minutes — their own Google account,
 * their own 20k-calls/day quota) and every CONTROL-PLANE call (player /
 * config / search) egresses from GOOGLE'S OWN ASN 15169 through
 * UrlFetchApp — the egress class YouTube's first-party traffic rides —
 * instead of the shared Cloudflare Worker relay (AS13335) that bot-walls
 * for licensed music after 1–2 plays. Media bytes NEVER ride this lane
 * (googlevideo authorizes cross-IP fetches — the W2.0 measurement — so the
 * <audio> element keeps streaming from the user's own IP); only the tiny
 * JSON legs do (~1–3 calls per track, plus best-effort SABR posts).
 *
 * THE WIRE (a GET envelope — deliberately NO custom headers, NO preflight):
 *   GET {execUrl}?q=<urlencoded JSON
 *        {u: url, m: 'get'|'post', h: {headers}, b: body, e: 't'|'b64'}>
 *   → {ok, s: status, h: headers, b: body, e: 't'|'b64', eip}
 * The script serves application/json to ANY origin (deployed "Anyone") —
 * the canonical Apps Script web-app posture, and script.google.com is on
 * youtubei.googleapis.com's measured CORS allowlist, which is exactly why
 * this lane exists at all.
 *
 * DISCIPLINE (the G4.3 rules carry over unchanged):
 *   • A GSF attempt that network-fails (CORS refusal, dead deployment,
 *     private-network block) marks the lane DEAD for the page session
 *     after two consecutive failures — later calls skip it (the
 *     direct-lane negative-cache pattern; a posture cannot heal mid-load).
 *   • An HTTP response from the script is a REAL verdict (upstream status
 *     included) and is returned as-is — verdicts stay egress-attributable,
 *     and LOGIN_REQUIRED is never retried anywhere (resolve.ts enforces).
 *   • The lane is strictly ADDITIVE: unconfigured or dead → every caller
 *     keeps its exact G4.3 behavior (native bridge → direct fetch → relay).
 */

import { GAPI_PLAYER_ENDPOINT } from '../audio/botguard/protocol';

// ---------------------------------------------------------------------------
// Configuration (?gsf= > VITE_GSF_URL > localStorage)
// ---------------------------------------------------------------------------

const GSF_STORAGE_KEY = 'streamify.gsf.url';
/** /exec URLs are long; the envelope rides as ?q= — stay under URL limits. */
const MAX_ENVELOPE_URL_CHARS = 7_500;

let urlCandidate: string | null | undefined;

function normalizeGsfUrl(url: string): string | null {
  const trimmed = url.trim();
  if (trimmed === '') return null;
  try {
    const u = new URL(trimmed);
    if (u.protocol !== 'https:') return null;
    if (u.hostname !== 'script.google.com') return null;
    if (!u.pathname.startsWith('/macros/s/')) return null;
    return u.toString();
  } catch {
    return null;
  }
}

/** The configured script URL, if any (?gsf= > build env > localStorage). */
export function gsfUrl(): string | null {
  if (urlCandidate === undefined) {
    let found: string | null = null;
    try {
      found =
        normalizeGsfUrl(
          new URLSearchParams(window.location.search).get('gsf') ?? '',
        ) ??
        normalizeGsfUrl(
          (import.meta.env.VITE_GSF_URL as string | undefined) ?? '',
        ) ??
        normalizeGsfUrl(localStorage.getItem(GSF_STORAGE_KEY) ?? '');
    } catch {
      found = null; // private mode etc. — the lane stays off
    }
    urlCandidate = found;
  }
  return urlCandidate;
}

/** Persist (or clear, null/empty) the per-device script URL. */
export function setGsfUrl(url: string | null): void {
  const normalized = url === null ? null : normalizeGsfUrl(url);
  try {
    if (normalized === null) localStorage.removeItem(GSF_STORAGE_KEY);
    else localStorage.setItem(GSF_STORAGE_KEY, normalized);
  } catch {
    /* private mode — this page load keeps the lane in memory */
  }
  urlCandidate = normalized;
  gsfHealthPromise = null;
  gsfFailures = 0;
  gsfDead = normalized === null;
}

/** Is the lane configured at all (health not yet considered)? */
export function gsfConfigured(): boolean {
  return gsfUrl() !== null;
}

// ---------------------------------------------------------------------------
// Session health (the direct-lane negative-cache pattern)
// ---------------------------------------------------------------------------

export interface GsfHealth {
  ok: boolean;
  /** The script's UrlFetch egress IP (diagnostics: "which world am I in"). */
  egressIp: string | null;
  error: string | null;
}

let gsfHealthPromise: Promise<GsfHealth> | null = null;
let gsfFailures = 0;
let gsfDead = false;

/** Two consecutive network-layer failures kill the lane for the page session. */
function noteGsfNetworkFailure(reason: string): void {
  gsfFailures += 1;
  if (gsfFailures >= 2) gsfDead = true;
  gsfHealthPromise ??= Promise.resolve({
    ok: false,
    egressIp: null,
    error: reason,
  });
}

/** Dev/diagnostics: is the lane considered alive right now? */
export function gsfAlive(): boolean {
  return gsfConfigured() && !gsfDead;
}

/**
 * The health probe (?ping=1): the script answers with its egress IP.
 * Memoized per page session; `refreshGsfHealth()` re-arms it (the panel's
 * Test button).
 */
export function gsfHealth(): Promise<GsfHealth> {
  if (!gsfConfigured()) {
    return Promise.resolve({
      ok: false,
      egressIp: null,
      error: 'unconfigured',
    });
  }
  if (gsfHealthPromise === null) {
    const base = gsfUrl() as string;
    const url = `${base}${base.includes('?') ? '&' : '?'}ping=1`;
    gsfHealthPromise = (async (): Promise<GsfHealth> => {
      try {
        const res = await fetch(url, {
          method: 'GET',
          credentials: 'omit',
          signal: AbortSignal.timeout(8_000),
        });
        if (!res.ok) {
          return { ok: false, egressIp: null, error: `ping HTTP ${res.status}` };
        }
        const body = (await res.json()) as { ok?: boolean; eip?: string };
        if (body.ok !== true) {
          return {
            ok: false,
            egressIp: null,
            error: 'ping answered not-ok (redeploy the web app?)',
          };
        }
        gsfFailures = 0;
        return { ok: true, egressIp: body.eip ?? null, error: null };
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        return {
          ok: false,
          egressIp: null,
          error: `ping failed (${reason}) — is the deployment "Anyone"?`,
        };
      }
    })();
    gsfHealthPromise.catch(() => undefined); // never unhandled
  }
  return gsfHealthPromise;
}

/** Re-arm the memoized health (the Test button / a settings change). */
export function refreshGsfHealth(): Promise<GsfHealth> {
  gsfHealthPromise = null;
  gsfFailures = 0;
  gsfDead = !gsfConfigured();
  return gsfHealth();
}

// ---------------------------------------------------------------------------
// The fetch
// ---------------------------------------------------------------------------

export interface GsfFetchInit {
  method?: string;
  headers?: Record<string, string>;
  /** Text or binary (Uint8Array/ArrayBuffer → base64 envelope). */
  body?: string | Uint8Array | ArrayBuffer;
  signal?: AbortSignal | null;
}

interface GsfEnvelopeResponse {
  ok?: boolean;
  s?: number;
  h?: Record<string, string>;
  b?: string;
  e?: 't' | 'b64';
  eip?: string;
  error?: string;
}

/** chunk-safe base64 of binary bodies (SABR UMP protobufs). */
function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

/**
 * One allowlisted fetch through the script (Google-ASN egress). Throws at
 * the NETWORK layer on script-unreachable / not-ok envelopes — callers
 * treat that as "lane down" and fall back per their own discipline; an
 * upstream HTTP status is returned as a real Response (verdicts stay
 * attributable).
 */
export async function gsfFetch(
  url: string,
  init: GsfFetchInit = {},
): Promise<Response> {
  if (!gsfAlive()) {
    throw new Error('gsf lane not alive (unconfigured or dead this session)');
  }
  const base = gsfUrl() as string;
  let bodyStr: string | undefined;
  let enc: 't' | 'b64' = 't';
  if (init.body !== undefined) {
    if (typeof init.body === 'string') {
      bodyStr = init.body;
    } else if (init.body instanceof Uint8Array) {
      bodyStr = bytesToBase64(init.body);
      enc = 'b64';
    } else {
      bodyStr = bytesToBase64(new Uint8Array(init.body));
      enc = 'b64';
    }
  }
  const envelope = JSON.stringify({
    u: url,
    m: (init.method ?? 'GET').toLowerCase() === 'post' ? 'post' : 'get',
    h: init.headers ?? {},
    ...(bodyStr !== undefined ? { b: bodyStr, e: enc } : {}),
  });
  const target = `${base}${base.includes('?') ? '&' : '?'}q=${encodeURIComponent(envelope)}`;
  if (target.length > MAX_ENVELOPE_URL_CHARS) {
    throw new Error(
      `gsf envelope too large (${target.length} chars — the relay lane takes this call)`,
    );
  }
  let res: Response;
  try {
    res = await fetch(target, {
      method: 'GET', // the envelope IS the request — no preflight anywhere
      credentials: 'omit',
      ...(init.signal !== undefined && init.signal !== null
        ? { signal: init.signal }
        : {}),
    });
  } catch (err) {
    noteGsfNetworkFailure(err instanceof Error ? err.message : String(err));
    throw err instanceof Error ? err : new Error(String(err));
  }
  let wire: GsfEnvelopeResponse;
  try {
    wire = (await res.json()) as GsfEnvelopeResponse;
  } catch (err) {
    noteGsfNetworkFailure('envelope response is not JSON');
    throw new Error('gsf: envelope response is not JSON');
  }
  if (wire.ok !== true || typeof wire.s !== 'number') {
    // The script's OWN error (allowlist/envelope/quota) — a lane-level
    // failure, not an upstream verdict.
    noteGsfNetworkFailure(wire.error ?? 'script answered not-ok');
    throw new Error(`gsf: ${wire.error ?? 'script answered not-ok'}`);
  }
  gsfFailures = 0; // a real answer — the lane is warm
  const bodyBytes =
    wire.e === 'b64'
      ? base64ToBytes(typeof wire.b === 'string' ? wire.b : '')
      : (wire.b ?? '');
  const headers = new Headers();
  for (const [name, value] of Object.entries(wire.h ?? {})) {
    try {
      headers.set(name, value);
    } catch {
      /* exotic header names are dropped, not fatal */
    }
  }
  return new Response(
    // Byte-copy through the typed-array constructor: TS 5.9's BodyInit
    // requires Uint8Array<ArrayBuffer>; the copy satisfies both it and
    // TS 5.6 (behavior-preserving).
    typeof bodyBytes === 'string' ? bodyBytes : new Uint8Array(bodyBytes),
    { status: wire.s, headers },
  );
}

// ---------------------------------------------------------------------------
// The live posture probe (the Engine Egress panel's Test button)
// ---------------------------------------------------------------------------

export interface GsfProbeResult {
  ping: GsfHealth;
  /** The licensed-track player verdict THROUGH the script (null = not run). */
  licensed: {
    status: string;
    reason: string | null;
    egressIp: string | null;
  } | null;
}

/** A known Content-ID track (the canary's licensed sample). */
export const GSF_PROBE_LICENSED_VIDEO_ID = '4NRXx6U8ABQ';

/**
 * ping + one tokenless IOS player POST for a licensed track, both through
 * the script — measures the whole G5 bet live: script reachable AND the
 * Google-ASN egress serving licensed playability.
 */
export async function gsfProbePosture(): Promise<GsfProbeResult> {
  const ping = await refreshGsfHealth();
  if (!ping.ok) return { ping, licensed: null };
  try {
    const res = await gsfFetch(GAPI_PLAYER_ENDPOINT, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-youtube-client-name': '5',
        'x-youtube-client-version': '21.26.4',
      },
      body: JSON.stringify({
        context: {
          client: {
            clientName: 'IOS',
            clientVersion: '21.26.4',
            hl: 'en',
            gl: 'US',
            userAgent:
              'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)',
            deviceMake: 'Apple',
            deviceModel: 'iPhone16,2',
            osName: 'iPhone',
            osVersion: '18.3.2.22D82',
          },
        },
        videoId: GSF_PROBE_LICENSED_VIDEO_ID,
        contentCheckOk: true,
        racyCheckOk: true,
      }),
    });
    const player = (await res.json()) as {
      playabilityStatus?: { status?: string; reason?: string };
    };
    return {
      ping,
      licensed: {
        status: player.playabilityStatus?.status ?? 'UNKNOWN',
        reason: player.playabilityStatus?.reason ?? null,
        egressIp: ping.egressIp,
      },
    };
  } catch (err) {
    return {
      ping,
      licensed: {
        status: 'LANE_ERROR',
        reason: err instanceof Error ? err.message : String(err),
        egressIp: ping.egressIp,
      },
    };
  }
}
