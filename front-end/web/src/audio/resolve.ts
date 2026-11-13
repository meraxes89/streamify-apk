/**
 * audio/resolve.ts — the stream resolver ladder (P3b: the 3-Tier pivot).
 *
 * TIER 1 — THE ZERO-TOKEN FAST PATH (replaces the burned ANDROID_VR rung):
 *
 *   1. VISIONOS (clientName 101, v1.02, Apple Vision Pro) — verified live
 *      2026-11-14 through the relay: serves DIRECT, pre-signed googlevideo
 *      URLs (itag 140/251, full contentLength, standard Range streaming)
 *      with NO PO token and NO 60-second cap (ANDROID_VR is capped at
 *      ~60 s for full tracks — hard-measured; the rung is gone).
 *      Bonus: the same response carries an hlsManifestUrl — when the
 *      adaptive formats are missing but HLS is present, the rung serves
 *      the manifest to the element path (Safari-class engines play m3u8
 *      natively; others rotate down the ladder).
 *
 *   2. WEB_SAFARI HLS — the WEB client (clientName 1) presented through a
 *      Safari-class UA (the relay's X-Relay-UA override — a forbidden
 *      header no browser can set itself). Safari-flavored WEB calls
 *      receive HLS (m3u8) manifests that require NO GVS PO token. The
 *      visitorData envelope comes from the relay-cached /bg-session.
 *
 * TIER 2 — WEB + PO TOKEN (the licensed-music rung, W1.0 + P3b dual
 * tokens): when Tier 1 answers LOGIN_REQUIRED (licensed tracks from the
 * relay's datacenter egress), the resolver lazily ensures a browser-native
 * BotGuard mint (potSession) and calls the player endpoint with:
 *   • context.client.visitorData  (the mint's homepage session)
 *   • X-Goog-Visitor-Id           (same value, header form)
 *   • serviceIntegrityDimensions.poToken ← mint(videoId) — the PLAYER-bound
 *     token; the SESSION-bound token (visitorData) rides &pot= on the
 *     googlevideo URL per the homepage experiment flag (videoId while the
 *     flag is set — measured 2026-11-12 — else visitorData).
 * The P3b mint degrades honestly (live → websafe-fallback → coldstart);
 * potKind labels which token class actually backs the stream.
 *
 * ALL rungs go THROUGH THE RELAY (the relay swaps in the matrix UA for
 * X-YouTube-Client-Name 101 / the Safari override for the WEB_SAFARI rung;
 * the plain WEB rung keeps the browser's own UA + client hints — its
 * natural envelope, pinned by the P3b relay).
 *
 * ROTATION LOOP GUARD: rungs that returned a DEFINITIVE refusal (status
 * verdict, not a network error) for a video are ledgered and skipped for
 * the rest of the page session — a URL rotation after an element error
 * must ESCALATE the ladder, never re-pick the same dead rung.
 */

import { webCodecsAvailable } from './webcodecsDecoder';
import { playerPoToken, gvsPoToken } from './botguard/potSession';
import {
  PLAYER_ENDPOINT,
  VISIONOS_CLIENT,
  WEB_CLIENT,
  WEB_SAFARI_PROFILE,
  relayUrl,
} from './botguard/protocol';
import { fetchBgSession } from './botguard/mintCore';
import type { AudioCodec } from './types';

/** itag → codec routing (audio-only set). */
const ITAG_CODEC: Record<number, AudioCodec> = { 140: 'aac', 251: 'opus' };

/** The ladder rungs (ledger + diagnostics). */
export type LadderRung = 'visionos' | 'visionos-hls' | 'web-safari-hls' | 'web-pot';

export interface ResolvedStream {
  videoId: string;
  itag: number;
  codec: AudioCodec;
  /** Direct googlevideo URL or the HLS manifest URL (isHls). */
  url: string;
  mimeType: string;
  bitrate: number;
  contentLength: number | null;
  durationSec: number | null;
  title: string;
  artist: string;
  /** Which ladder rung produced this stream (UI + diagnosis). */
  rung: LadderRung;
  /** PO-token class backing the rung ('none' for the tokenless rungs). */
  potKind: 'none' | 'live' | 'seeded' | 'websafe-fallback' | 'coldstart';
  /** True when url is an HLS (m3u8) manifest — element path only. */
  isHls: boolean;
}

export interface ResolveContext {
  /** Relay base URL (REQUIRED in the page — googlevideo is CORS-walled). */
  relayBase: string;
  /** Abort the resolve. */
  signal?: AbortSignal | null;
  /**
   * Allow the WEB+POT rung to lazily MINT a BotGuard session when missing
   * (default true — licensed tracks need it; the mint runs once per ~12 h).
   */
  autoMint?: boolean;
  /**
   * W2.0 element-path mode: prefer itag 140 (AAC/MP4) EXCLUSIVELY and allow
   * the HLS rungs (the `<audio>` element decodes AAC + HLS everywhere it
   * matters; the WASM spine can demux neither HLS nor Safari's stack).
   */
  elementCompat?: boolean;
}

interface PlayerFormat {
  itag: number;
  url?: string;
  mimeType?: string;
  bitrate?: number;
  contentLength?: string;
}

interface PlayerResponse {
  playabilityStatus?: { status?: string; reason?: string };
  videoDetails?: {
    title?: string;
    author?: string;
    lengthSeconds?: string;
  };
  streamingData?: {
    adaptiveFormats?: PlayerFormat[];
    hlsManifestUrl?: string;
  };
}

// ---------------------------------------------------------------------------
// The failed-rung ledger (rotation loop guard)
// ---------------------------------------------------------------------------

const failedRungs = new Map<string, Set<LadderRung>>();

/**
 * Mark a rung as definitively dead for a video (this page session). The
 * resolver skips ledgered rungs — rotations escalate instead of looping.
 * Cleared per page load; a NETWORK error must never be ledgered (only
 * definitive upstream verdicts and consumer-reported rejects).
 */
export function markRungFailed(videoId: string, rung: LadderRung): void {
  let set = failedRungs.get(videoId);
  if (set === undefined) {
    set = new Set();
    failedRungs.set(videoId, set);
  }
  set.add(rung);
}

/** Dev/diagnostics: the ledgered (dead) rungs for a video. */
export function failedRungsFor(videoId: string): LadderRung[] {
  return [...(failedRungs.get(videoId) ?? [])];
}

/** Drop one video's ledger (fresh start — dev surface). */
export function resetVideoLedger(videoId: string): void {
  failedRungs.delete(videoId);
}

function rungAlive(videoId: string, rung: LadderRung): boolean {
  return !(failedRungs.get(videoId)?.has(rung) === true);
}

// ---------------------------------------------------------------------------
// Format picking
// ---------------------------------------------------------------------------

/** Extract the playable audio format by machine capability. */
function pickFormat(player: PlayerResponse, elementCompat = false): PlayerFormat | undefined {
  const formats = player.streamingData?.adaptiveFormats ?? [];
  const pick = (itag: number): PlayerFormat | undefined =>
    formats.find((f) => f.itag === itag && typeof f.url === 'string');
  // Preference by machine capability (not by bitrate): the W1.2 deviation
  // (no symphonia Opus decoder) makes AAC the only WASM rung, and this
  // resolver refuses to hand the spine a codec the machine cannot decode.
  // W2.0/P3b: the ELEMENT path (direct egress) is always AAC-first — media
  // elements decode mp4a.40.2 on every browser; opus-in-webm does not
  // survive Safari's element pipeline.
  const want: number[] = elementCompat
    ? [140]
    : webCodecsAvailable()
      ? [251, 140]
      : [140];
  for (const itag of want) {
    const chosen = pick(itag);
    if (chosen !== undefined) return chosen;
  }
  return undefined;
}

/** Map a player response to a direct-format ResolvedStream (shared rungs). */
function toStream(
  videoId: string,
  player: PlayerResponse,
  format: PlayerFormat,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
): ResolvedStream {
  return {
    videoId,
    itag: format.itag,
    codec: ITAG_CODEC[format.itag] ?? 'aac',
    url: format.url as string,
    mimeType: format.mimeType ?? '',
    bitrate: format.bitrate ?? 0,
    contentLength:
      format.contentLength !== undefined ? Number(format.contentLength) : null,
    durationSec:
      player.videoDetails?.lengthSeconds !== undefined
        ? Number(player.videoDetails.lengthSeconds)
        : null,
    title: player.videoDetails?.title ?? videoId,
    artist: player.videoDetails?.author ?? '',
    rung,
    potKind,
    isHls: false,
  };
}

/** Map a player response to an HLS-manifest ResolvedStream. */
function toHlsStream(
  videoId: string,
  player: PlayerResponse,
  manifestUrl: string,
  rung: LadderRung,
): ResolvedStream {
  return {
    videoId,
    itag: 0, // HLS: itag-less variant playlists
    codec: 'aac', // YouTube HLS audio variants are fMP4/AAC
    url: manifestUrl,
    mimeType: 'application/vnd.apple.mpegurl',
    bitrate: 0,
    contentLength: null,
    durationSec:
      player.videoDetails?.lengthSeconds !== undefined
        ? Number(player.videoDetails.lengthSeconds)
        : null,
    title: player.videoDetails?.title ?? videoId,
    artist: player.videoDetails?.author ?? '',
    rung,
    potKind: 'none',
    isHls: true,
  };
}

// ---------------------------------------------------------------------------
// The ladder
// ---------------------------------------------------------------------------

/**
 * Resolve one video's best playable audio stream.
 *   Tier 1: VISIONOS (tokenless direct URLs / same-call HLS) → WEB_SAFARI
 *   Tier 2: WEB + PO token (dual-token mint)
 * The thrown error carries EVERY rung's verdict for honest diagnosis.
 */
export async function resolveAudioStream(
  videoId: string,
  ctx: ResolveContext,
): Promise<ResolvedStream> {
  const verdicts: string[] = [];
  const elementCompat = ctx.elementCompat === true;

  // ---- Tier 1 rung 1: VISIONOS (tokenless) ------------------------------
  if (rungAlive(videoId, 'visionos') || rungAlive(videoId, 'visionos-hls')) {
    let vrVerdict = 'not attempted';
    try {
      const player = await playerCallVisionos(videoId, ctx);
      const status = player.playabilityStatus?.status ?? 'UNKNOWN';
      if (status === 'OK') {
        const format = pickFormat(player, elementCompat);
        if (format !== undefined && format.url !== undefined) {
          return toStream(videoId, player, format, 'visionos', 'none');
        }
        const hls = player.streamingData?.hlsManifestUrl;
        if (hls !== undefined && hls.length > 0 && elementCompat) {
          // Adaptive missing, HLS present: serve the manifest (Safari-class
          // engines play it; others rotate down — the ledger then escalates).
          return toHlsStream(videoId, player, hls, 'visionos-hls');
        }
        vrVerdict = 'OK but no playable audio itag (140/251) or HLS manifest';
        markRungFailed(videoId, 'visionos');
        markRungFailed(videoId, 'visionos-hls');
      } else {
        vrVerdict = `${status}: ${player.playabilityStatus?.reason ?? 'no reason'}`;
        markRungFailed(videoId, 'visionos');
        markRungFailed(videoId, 'visionos-hls');
      }
    } catch (err) {
      vrVerdict = err instanceof Error ? err.message : String(err); // network-ish: NOT ledgered
    }
    verdicts.push(`[VISIONOS] ${vrVerdict}`);
  }

  // ---- Tier 1 rung 2: WEB_SAFARI HLS ------------------------------------
  if (elementCompat && rungAlive(videoId, 'web-safari-hls')) {
    let safariVerdict = 'not attempted';
    try {
      const player = await playerCallWebSafari(videoId, ctx);
      const status = player.playabilityStatus?.status ?? 'UNKNOWN';
      const hls = player.streamingData?.hlsManifestUrl;
      if (status === 'OK' && hls !== undefined && hls.length > 0) {
        return toHlsStream(videoId, player, hls, 'web-safari-hls');
      }
      safariVerdict =
        status === 'OK' ? 'OK but no hlsManifestUrl' : `${status}: ${player.playabilityStatus?.reason ?? 'no reason'}`;
      markRungFailed(videoId, 'web-safari-hls');
    } catch (err) {
      safariVerdict = err instanceof Error ? err.message : String(err);
    }
    verdicts.push(`[WEB_SAFARI] ${safariVerdict}`);
  }

  // ---- Tier 2: WEB + PO token (the licensed-music path) ------------------
  let potVerdict = 'not attempted';
  const token = await playerPoToken(videoId, ctx.relayBase, {
    autoMint: ctx.autoMint !== false,
    signal: ctx.signal ?? null,
  });
  if (token === null) {
    potVerdict =
      'no PO token available (mint failed — see the mint status; coldstart/websafe fallbacks exhausted)';
  } else {
    try {
      const player = await playerCallWebPot(videoId, ctx, token);
      const status = player.playabilityStatus?.status ?? 'UNKNOWN';
      if (status === 'OK') {
        const format = pickFormat(player, elementCompat);
        if (format !== undefined && format.url !== undefined) {
          const stream = toStream(videoId, player, format, 'web-pot', token.kind);
          // WEB-rung googlevideo URLs: append the GVS token (binding per
          // the homepage experiment flag — see potSession/mintCore).
          const gvs = await gvsPoToken(videoId);
          if (gvs !== null) {
            stream.url = `${stream.url}&pot=${encodeURIComponent(gvs)}`;
          }
          return stream;
        }
        potVerdict = 'OK but no playable audio itag (251/140)';
      } else {
        potVerdict = `${status}: ${player.playabilityStatus?.reason ?? 'no reason'}`;
      }
    } catch (err) {
      potVerdict = err instanceof Error ? err.message : String(err);
    }
  }
  verdicts.push(`[WEB+POT] ${potVerdict}`);

  throw new Error(`no rung resolved ${videoId} — ${verdicts.join(' · ')}`);
}

// ---------------------------------------------------------------------------
// Tier 1 rung 1 — VISIONOS player POST through the relay (tokenless)
// ---------------------------------------------------------------------------

async function playerCallVisionos(
  videoId: string,
  ctx: ResolveContext,
): Promise<PlayerResponse> {
  // Keyless www.youtube.com innertube — the repo's established pattern
  // (see front-end/canary/src/probeMatrix.ts): the www host accepts
  // innertube POSTs without ?key=. The relay swaps the UA to the Vision
  // Pro matrix value via X-YouTube-Client-Name 101 (P3b).
  const res = await fetch(relayUrl(ctx.relayBase, PLAYER_ENDPOINT), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-youtube-client-name': VISIONOS_CLIENT.clientNumber,
      'x-youtube-client-version': VISIONOS_CLIENT.clientVersion,
    },
    body: JSON.stringify({
      context: {
        client: {
          clientName: VISIONOS_CLIENT.clientName,
          clientVersion: VISIONOS_CLIENT.clientVersion,
          deviceMake: VISIONOS_CLIENT.deviceMake,
          deviceModel: VISIONOS_CLIENT.deviceModel,
          osName: VISIONOS_CLIENT.osName,
          osVersion: VISIONOS_CLIENT.osVersion,
          hl: 'en',
          gl: 'US',
        },
      },
      videoId,
      contentCheckOk: true,
      racyCheckOk: true,
    }),
    signal: ctx.signal ?? undefined,
  });
  if (!res.ok) {
    throw new Error(`player relay call ${res.status}`);
  }
  return (await res.json()) as PlayerResponse;
}

// ---------------------------------------------------------------------------
// Tier 1 rung 2 — WEB_SAFARI (WEB client, Safari UA via the relay override)
// ---------------------------------------------------------------------------

async function playerCallWebSafari(
  videoId: string,
  ctx: ResolveContext,
): Promise<PlayerResponse> {
  // The visitorData envelope: the relay-cached /bg-session homepage scrape
  // (no mint needed — this is the tokenless HLS rung). Best-effort: the
  // call proceeds without it when the relay predates /bg-session.
  let visitorData: string | null = null;
  try {
    const bg = await fetchBgSession(ctx.relayBase, { signal: ctx.signal ?? null });
    visitorData = bg.visitorData;
  } catch {
    visitorData = null;
  }
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    'x-youtube-client-name': WEB_SAFARI_PROFILE.clientNumber,
    'x-youtube-client-version': WEB_SAFARI_PROFILE.clientVersion,
    // The P3b relay control header — swaps in the Safari-class UA the
    // browser cannot set (and, per the envelope rule, strips the browser's
    // Chrome client hints: Safari sends none). An OLD relay forwards it
    // harmlessly upstream (an unknown header) and the rung reports its
    // honest verdict from the browser-UA'd call.
    'x-relay-ua': WEB_SAFARI_PROFILE.uaOverride,
  };
  if (visitorData !== null) {
    headers['x-goog-visitor-id'] = visitorData;
  }
  const client: Record<string, string> = {
    clientName: WEB_SAFARI_PROFILE.clientName,
    clientVersion: WEB_SAFARI_PROFILE.clientVersion,
    hl: 'en',
    gl: 'US',
  };
  if (visitorData !== null) {
    client.visitorData = visitorData;
  }
  const res = await fetch(relayUrl(ctx.relayBase, PLAYER_ENDPOINT), {
    method: 'POST',
    headers,
    body: JSON.stringify({
      context: { client },
      videoId,
      contentCheckOk: true,
      racyCheckOk: true,
    }),
    signal: ctx.signal ?? undefined,
  });
  if (!res.ok) {
    throw new Error(`player relay call ${res.status}`);
  }
  return (await res.json()) as PlayerResponse;
}

// ---------------------------------------------------------------------------
// Tier 2 — WEB + PO token through the relay (W1.0 + P3b dual tokens)
// ---------------------------------------------------------------------------

async function playerCallWebPot(
  videoId: string,
  ctx: ResolveContext,
  token: { poToken: string; visitorData: string },
): Promise<PlayerResponse> {
  const res = await fetch(relayUrl(ctx.relayBase, PLAYER_ENDPOINT), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      // WEB keeps the browser's own UA + client hints (its natural
      // envelope — the P3b relay pins them through). visitorData rides
      // both the body and the header — the coherent "web session" story.
      'x-goog-visitor-id': token.visitorData,
      'x-youtube-client-name': WEB_CLIENT.clientNumber,
      'x-youtube-client-version': WEB_CLIENT.clientVersion,
    },
    body: JSON.stringify({
      context: {
        client: {
          clientName: WEB_CLIENT.clientName,
          clientVersion: WEB_CLIENT.clientVersion,
          visitorData: token.visitorData,
          hl: 'en',
          gl: 'US',
        },
      },
      videoId,
      contentCheckOk: true,
      racyCheckOk: true,
      // PLAYER-context PO token — VIDEO_ID bound (yt-dlp binding rule).
      serviceIntegrityDimensions: { poToken: token.poToken },
    }),
    signal: ctx.signal ?? undefined,
  });
  if (!res.ok) {
    throw new Error(`player relay call ${res.status}`);
  }
  return (await res.json()) as PlayerResponse;
}
