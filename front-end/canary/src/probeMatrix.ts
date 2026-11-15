// probeMatrix.ts — the browser-side probe runner.
//
// Matrix phase: a faithful translation of tools/probe-clients.sh — 10 clients × 2
// videos = 20 player POSTs through the relay, 2s spacing between requests (`sleep 2`),
// per-row metrics (playabilityStatus.status, itag-140/251 audio format count, total
// adaptiveFormats, signatureCipher count, playabilityStatus.reason).
//
// R-C phase: translated from tools/probe-search-resolver.sh with the search leg elided
// (the licensed video 4NRXx6U8ABQ is resolved directly): R1 licensed playback, R2
// PO-token (n/a by design), R3 ACAO on the googlevideo CDN, R4 IP-pin tolerance.
// The split-traffic model matters: the player resolve goes through the relay
// (datacenter CF IP) while the media bytes are fetched DIRECT from this browser's
// residential IP — that asymmetry IS the experiment.
//
// FRAMEWORK-FREE: no import.meta.env in this module — relayBase is a parameter.

import { CLIENTS, SIGNATURE_TIMESTAMP, VIDEOS } from './matrix';
import type { ClientSpec } from './matrix';
import { relayFetch } from './relayFetch';
import type { ProbeReport, ProbeRow, RCondition } from './types';

const UA_NOTE = 'ua=browser (fetch cannot set UA)';

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

export interface RunProbeMatrixOptions {
  onProgress?: (done: number, total: number, lastRow: ProbeRow | null) => void;
}

function hasKey(obj: unknown, key: string): boolean {
  return typeof obj === 'object' && obj !== null && Object.prototype.hasOwnProperty.call(obj, key);
}

/**
 * Player payload construction, field-for-field per the jq in tools/probe-clients.sh:
 *
 *   {context: {client: ({clientName, clientVersion, hl:"en", gl:"US"}
 *               + (if $extraCtx then $extraCtx else {} end)
 *               + (if ($extraCtx // {}) | has("userAgent") then {} else {userAgent:$ua} end))},
 *    videoId:$vid, contentCheckOk:true, racyCheckOk:true}
 *   + (if $extraTop then $extraTop else {} end)
 *
 * i.e. extraClientJson merges INTO context.client, userAgent is appended when the
 * merged client object lacks one, and extraTopLevelJson merges at the TOP level.
 */
export function buildPlayerPayload(client: ClientSpec, videoId: string): Record<string, unknown> {
  const mergedClient: Record<string, unknown> = {
    clientName: client.clientName,
    clientVersion: client.clientVersion,
    hl: 'en',
    gl: 'US'
  };
  if (client.extraClientJson) Object.assign(mergedClient, client.extraClientJson);
  if (!hasKey(mergedClient, 'userAgent')) mergedClient.userAgent = client.userAgent;

  const payload: Record<string, unknown> = {
    context: { client: mergedClient },
    videoId,
    contentCheckOk: true,
    racyCheckOk: true
  };
  if (client.extraTopLevelJson) Object.assign(payload, client.extraTopLevelJson);
  return payload;
}

/** One matrix cell: client row × videoId → ProbeRow. Never throws. */
async function probeOne(relayBase: string, client: ClientSpec, videoId: string): Promise<ProbeRow> {
  const endpoint = `https://${client.host}/youtubei/v1/player`;
  const res = await relayFetch(relayBase, endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-YouTube-Client-Name': client.clientNumber,
      'X-YouTube-Client-Version': client.clientVersion
    },
    body: JSON.stringify(buildPlayerPayload(client, videoId))
  });

  const body = (res.json ?? {}) as any;
  const ps = (body?.playabilityStatus ?? {}) as any;
  const adaptive: any[] = Array.isArray(body?.streamingData?.adaptiveFormats)
    ? (body.streamingData.adaptiveFormats as any[])
    : [];

  // jq: .playabilityStatus.status // "NO-RESPONSE"
  const playability = typeof ps?.status === 'string' ? ps.status : 'NO-RESPONSE';
  // jq: ([.streamingData.adaptiveFormats[]? | select((.itag==140) or (.itag==251))] | length)
  const audioFmts = adaptive.filter((f) => f?.itag === 140 || f?.itag === 251).length;
  const fmt251 = adaptive.some((f) => f?.itag === 251);
  // jq: ([.streamingData.adaptiveFormats[]?] | length)
  const totalFmts = adaptive.length;
  // jq: ([.streamingData.adaptiveFormats[]? | has("signatureCipher")] | length)
  const ciphered = adaptive.filter((f) => hasKey(f, 'signatureCipher')).length;
  // jq: .playabilityStatus.reason // "-"
  const reason = (ps?.reason ?? '-') as string;

  return {
    client: client.name,
    video: videoId,
    endpoint,
    status: res.status,
    ok: res.status === 200 && playability === 'OK',
    playability,
    audioFmts,
    fmt251,
    totalFmts,
    ciphered,
    reason,
    latencyMs: res.latencyMs,
    notes: UA_NOTE
  };
}

// ---------------------------------------------------------------------------
// R-C phase (tools/probe-search-resolver.sh, search leg elided)
// ---------------------------------------------------------------------------

const R1_INTERPRET =
  "resolve via relay (datacenter CF IP); media bytes fetched direct from THIS browser's residential IP";
const R1_INTERPRET_WOULD =
  "resolve via relay (datacenter CF IP); media bytes would be fetched direct from THIS browser's residential IP";

interface R1Outcome {
  playerStatus: number;
  playability: string;
  reason: string;
  /** CDN URL extracted from streamingData (audio adaptiveFormats first, formats fallback) */
  cdnUrl: string | null;
  /** HTTP status of the DIRECT CDN fetch, or null when the fetch threw */
  fetchStatus: number | null;
  /** error string when the direct fetch threw (TypeError → CORS) */
  fetchError: string | null;
  byteLength: number;
  failure: 'none' | 'playability' | 'no-url' | 'http' | 'cors' | 'bytes';
  detail: string;
}

async function runR1(relayBase: string): Promise<R1Outcome> {
  const endpoint = 'https://www.youtube.com/youtubei/v1/player';
  // Payload per tools/probe-search-resolver.sh step 2 — ANDROID client, ST 19850.
  const payload = {
    context: {
      client: {
        clientName: 'ANDROID',
        clientVersion: '21.26.364',
        androidSdkVersion: 34,
        hl: 'en',
        gl: 'US'
      }
    },
    videoId: VIDEOS[1], // 4NRXx6U8ABQ — licensed/official content
    contentCheckOk: true,
    racyCheckOk: true,
    playbackContext: {
      contentPlaybackContext: {
        signatureTimestamp: SIGNATURE_TIMESTAMP,
        html5Preference: 'HTML5_PREF_WANTS'
      }
    }
  };

  const res = await relayFetch(relayBase, endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-YouTube-Client-Name': '3',
      'X-YouTube-Client-Version': '21.26.364'
    },
    body: JSON.stringify(payload)
  });

  const body = (res.json ?? {}) as any;
  const ps = (body?.playabilityStatus ?? {}) as any;
  const playability = typeof ps?.status === 'string' ? ps.status : 'NO-RESPONSE';
  const reason = (ps?.reason ?? '-') as string;

  // URL extraction per probe-search-resolver.sh:
  //   ([adaptiveFormats audio/* .url] + [formats .url]) | first
  // — first audio adaptiveFormats url, else first streamingData.formats url.
  const adaptive: any[] = Array.isArray(body?.streamingData?.adaptiveFormats)
    ? (body.streamingData.adaptiveFormats as any[])
    : [];
  const formats: any[] = Array.isArray(body?.streamingData?.formats)
    ? (body.streamingData.formats as any[])
    : [];
  let cdnUrl: string | null = null;
  for (const f of adaptive) {
    if (typeof f?.mimeType === 'string' && f.mimeType.startsWith('audio/') && typeof f?.url === 'string' && f.url) {
      cdnUrl = f.url;
      break;
    }
  }
  if (!cdnUrl) {
    for (const f of formats) {
      if (typeof f?.url === 'string' && f.url) {
        cdnUrl = f.url;
        break;
      }
    }
  }

  const outcome: R1Outcome = {
    playerStatus: res.status,
    playability,
    reason,
    cdnUrl,
    fetchStatus: null,
    fetchError: null,
    byteLength: 0,
    failure: 'none',
    detail: ''
  };

  if (playability !== 'OK') {
    outcome.failure = 'playability';
    outcome.detail = `player playability '${playability}': ${reason} — ${R1_INTERPRET_WOULD}`;
    return outcome;
  }
  if (!cdnUrl) {
    outcome.failure = 'no-url';
    outcome.detail = `no direct stream URL in streamingData (playability OK, formats may require signature decryption) — ${R1_INTERPRET_WOULD}`;
    return outcome;
  }

  // Step 2: fetch the CDN URL DIRECT (NOT via relay) — residential-IP evidence.
  try {
    const r = await fetch(cdnUrl, { headers: { Range: 'bytes=0-1024' }, mode: 'cors' });
    const buf = await r.arrayBuffer();
    outcome.fetchStatus = r.status;
    outcome.byteLength = buf.byteLength;
    if ((r.status === 200 || r.status === 206) && buf.byteLength >= 1) {
      outcome.failure = 'none';
      outcome.detail = `HTTP ${r.status}, ${buf.byteLength} bytes read via Range: bytes=0-1024 — ${R1_INTERPRET}`;
    } else if (r.status === 200 || r.status === 206) {
      outcome.failure = 'bytes';
      outcome.detail = `HTTP ${r.status} but 0 bytes read via Range: bytes=0-1024 — ${R1_INTERPRET}`;
    } else {
      outcome.failure = 'http';
      outcome.detail = `HTTP ${r.status} on direct Range fetch (Forbidden / expired URL?) — ${R1_INTERPRET}`;
    }
  } catch (e) {
    outcome.fetchError = e instanceof Error ? `${e.name}: ${e.message}` : String(e);
    outcome.failure = 'cors';
    outcome.detail = `TypeError: CORS/no ACAO (${outcome.fetchError}) — ${R1_INTERPRET_WOULD}`;
  }
  return outcome;
}

async function runR3(r1: R1Outcome): Promise<RCondition> {
  if (!r1.cdnUrl) {
    return {
      id: 'R3',
      name: 'ACAO on googlevideo CDN URLs',
      result: 'unknown',
      detail: 'R1 produced no CDN URL — cannot test ACAO on googlevideo from this browser'
    };
  }
  try {
    const r = await fetch(r1.cdnUrl, { mode: 'cors', method: 'GET', headers: { Range: 'bytes=0-0' } });
    const acao = r.headers.get('access-control-allow-origin');
    const contentRange = r.headers.get('content-range');
    await r.arrayBuffer(); // drain the 1-byte range body
    return {
      id: 'R3',
      name: 'ACAO on googlevideo CDN URLs',
      result: 'pass',
      detail: `fetch resolved — ACAO: ${
        acao ?? '(none, but fetch resolved)'
      }; content-range: ${contentRange ?? '(not exposed by CORS)'}; HTTP ${r.status}`
    };
  } catch (e) {
    const err = e instanceof Error ? `${e.name}: ${e.message}` : String(e);
    return {
      id: 'R3',
      name: 'ACAO on googlevideo CDN URLs',
      result: 'fail',
      detail: `no Access-Control-Allow-Origin on CDN — cross-origin JS decode (Web Audio) impossible without relay (fetch threw: ${err})`
    };
  }
}

function deriveR4(r1: R1Outcome): RCondition {
  if (r1.failure === 'none') {
    return {
      id: 'R4',
      name: 'IP-pin tolerance',
      result: 'pass',
      detail: `URL resolved via relay IP plays from residential IP (IP-pin tolerated) — HTTP ${r1.fetchStatus} on the direct Range fetch`
    };
  }
  if (r1.failure === 'http' && r1.fetchStatus === 403 && r1.playability === 'OK') {
    return {
      id: 'R4',
      name: 'IP-pin tolerance',
      result: 'fail',
      detail:
        'URL IP-pinned to resolver IP — HTTP 403 on direct fetch while player playability was OK (URL minted via relay IP, requested from residential IP)'
    };
  }
  if (r1.failure === 'cors') {
    return {
      id: 'R4',
      name: 'IP-pin tolerance',
      result: 'unknown',
      detail: 'cannot distinguish (no ACAO) — the direct fetch failed at the CORS layer before any HTTP status was visible'
    };
  }
  if (r1.failure === 'http') {
    return {
      id: 'R4',
      name: 'IP-pin tolerance',
      result: 'unknown',
      detail: `HTTP ${r1.fetchStatus} on direct fetch — not a clean 403 IP-pin signal`
    };
  }
  return {
    id: 'R4',
    name: 'IP-pin tolerance',
    result: 'unknown',
    detail: `no CDN URL to test (R1 failure mode: ${r1.failure}) — cannot test IP-pin tolerance`
  };
}

async function runRConditions(relayBase: string): Promise<RCondition[]> {
  const r1 = await runR1(relayBase);
  const r1Condition: RCondition = {
    id: 'R1',
    name: 'Licensed-content playback from residential IP',
    result: r1.failure === 'none' ? 'pass' : 'fail',
    detail: r1.detail
  };
  const r2: RCondition = {
    id: 'R2',
    name: 'PO-token minting in browser',
    result: 'n/a',
    detail:
      'structurally unavailable on web — rustypipe-botguard requires QuickJS (rquickjs-sys) which does not compile to wasm32-unknown-unknown (W0.1 landmine); PO-free client ladder is the web strategy'
  };
  const r3 = await runR3(r1);
  const r4 = deriveR4(r1);
  return [r1Condition, r2, r3, r4];
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

/**
 * Runs the full canary: 20 matrix player calls (2s spacing) + the 4 R-C conditions.
 * Never aborts on a failed request — failures land in the rows/conditions as
 * status 0 / NO-RESPONSE / unknown.
 */
export async function runProbeMatrix(
  relayBase: string,
  opts: RunProbeMatrixOptions = {}
): Promise<ProbeReport> {
  const startedAt = new Date().toISOString();
  const rows: ProbeRow[] = [];
  const total = CLIENTS.length * VIDEOS.length; // 10 × 2 = 20
  let done = 0;

  // loop order matches the shell: outer videos, inner clients
  for (const videoId of VIDEOS) {
    for (const client of CLIENTS) {
      if (done > 0) await sleep(2000); // `sleep 2` between requests, per probe-clients.sh
      const row = await probeOne(relayBase, client, videoId);
      rows.push(row);
      done += 1;
      opts.onProgress?.(done, total, row);
    }
  }

  const rConditions = await runRConditions(relayBase);

  return {
    rows,
    rConditions,
    startedAt,
    finishedAt: new Date().toISOString(),
    userAgent: navigator.userAgent,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    pageUrl: location.href,
    relayBase
  };
}
