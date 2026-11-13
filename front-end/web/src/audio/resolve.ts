/**
 * audio/resolve.ts — the stream resolver ladder (P1 + W1.0 POT rung).
 *
 * LADDER RUNGS:
 *
 *   1. ANDROID_VR (clientName 28, v1.65.10) — serves DIRECT googlevideo
 *      URLs without signature work and without visitorData (verified:
 *      P0 canary + this session's demux-verify run + the 43 s G1
 *      playback). PRIMARY for standard tracks; needs no mint.
 *
 *   2. WEB (clientName 1) + PO token — the licensed-music rung (W1.0).
 *      When rung 1 answers LOGIN_REQUIRED (the datacenter bot wall),
 *      the resolver lazily ensures a browser-native BotGuard mint
 *      (potSession) and calls the player endpoint with:
 *        • context.client.visitorData  (the mint's homepage session)
 *        • X-Goog-Visitor-Id           (same value, header form)
 *        • serviceIntegrityDimensions.poToken ← mintAsWebsafeString(VIDEO_ID)
 *          (PLAYER-context binding — yt-dlp pot/utils.py; measured
 *          2026-11-12: visitorData-bound tokens do NOT lift the wall)
 *      WEB-rung media URLs carry &pot=<GVS token> — VIDEO_ID-bound while
 *      the homepage's html5_generate_content_po_token experiment is set
 *      (it is, 2026-11-12), else visitorData-bound.
 *
 *      Measured honestly (pot-lab matrix, 2026-11-12): from DATACENTER
 *      egress (sandbox IP, CF Workers IP) even correctly-bound tokens from
 *      JSDOM-environment mints do not lift the licensed wall — the VM
 *      environment score is the differentiator, which only the REAL
 *      browser hardware of a residential user provides. The rung
 *      therefore auto-mints in-page and reports the wall verbatim when
 *      YouTube still refuses; the dev seed (potSession.seedPotSession)
 *      proves the wiring independently of the environment question.
 *
 * Both rungs go THROUGH THE RELAY (the relay swaps in the matrix UA for
 * X-YouTube-Client-Name 28; browsers cannot set app UAs — forbidden
 * header; the WEB rung keeps the browser's own UA, which is its natural
 * one).
 */

import { webCodecsAvailable } from './webcodecsDecoder';
import { playerPoToken, gvsPoToken } from './botguard/potSession';
import { PLAYER_ENDPOINT, WEB_CLIENT, relayUrl } from './botguard/protocol';
import type { AudioCodec } from './types';

/** ANDROID_VR client spec (mirrors the relay's CLIENT_NAME_UA matrix). */
const ANDROID_VR = {
  clientName: 'ANDROID_VR',
  clientVersion: '1.65.10',
  androidSdkVersion: 32,
  osName: 'Android',
  osVersion: '12L',
  hl: 'en',
};

/** itag → codec routing (audio-only set). */
const ITAG_CODEC: Record<number, AudioCodec> = { 140: 'aac', 251: 'opus' };

export interface ResolvedStream {
  videoId: string;
  itag: number;
  codec: AudioCodec;
  /** Direct googlevideo URL (feed to ChunkFetcher — it wraps the relay). */
  url: string;
  mimeType: string;
  bitrate: number;
  contentLength: number | null;
  durationSec: number | null;
  title: string;
  artist: string;
  /** Which ladder rung produced this stream (UI + diagnosis). */
  rung: 'android-vr' | 'web-pot';
  /** PO-token kind backing the rung ('none' for the VR rung). */
  potKind: 'none' | 'live' | 'seeded';
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
   * W2.0 element-path mode: prefer itag 140 (AAC/MP4) EXCLUSIVELY — the
   * `<audio>` element decodes AAC everywhere (Safari cannot play
   * opus-in-webm via media elements). The WASM-spine preference
   * ([251, 140] with WebCodecs) applies otherwise.
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
  streamingData?: { adaptiveFormats?: PlayerFormat[] };
}

/** Extract the playable audio format by machine capability. */
function pickFormat(player: PlayerResponse, elementCompat = false): PlayerFormat | undefined {
  const formats = player.streamingData?.adaptiveFormats ?? [];
  const pick = (itag: number): PlayerFormat | undefined =>
    formats.find((f) => f.itag === itag && typeof f.url === 'string');
  // Preference by machine capability (not by bitrate): the W1.2 deviation
  // (no symphonia Opus decoder) makes AAC the only WASM rung, and this
  // resolver refuses to hand the spine a codec the machine cannot decode.
  // W2.0: the ELEMENT path (direct egress) is always AAC-first — media
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

/** Map a player response to a ResolvedStream (shared by both rungs). */
function toStream(
  videoId: string,
  player: PlayerResponse,
  format: PlayerFormat,
  rung: ResolvedStream['rung'],
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
  };
}

/**
 * Resolve one video's best playable audio stream. Rung 1 (ANDROID_VR,
 * tokenless); on the bot wall → rung 2 (WEB + browser-minted PO token).
 * The thrown error carries BOTH rungs' verdicts for honest diagnosis.
 */
export async function resolveAudioStream(
  videoId: string,
  ctx: ResolveContext,
): Promise<ResolvedStream> {
  // ---- rung 1: ANDROID_VR (tokenless) -----------------------------------
  let vrVerdict = 'not attempted';
  try {
    const player = await playerCallAndroidVr(videoId, ctx);
    const status = player.playabilityStatus?.status ?? 'UNKNOWN';
    if (status === 'OK') {
      const format = pickFormat(player, ctx.elementCompat === true);
      if (format !== undefined && format.url !== undefined) {
        return toStream(videoId, player, format, 'android-vr', 'none');
      }
      vrVerdict = 'OK but no playable audio itag (251/140)';
    } else {
      vrVerdict = `${status}: ${player.playabilityStatus?.reason ?? 'no reason'}`;
    }
  } catch (err) {
    vrVerdict = err instanceof Error ? err.message : String(err);
  }

  // ---- rung 2: WEB + PO token (the licensed-music path, W1.0) ------------
  let potVerdict = 'not attempted';
  const token = await playerPoToken(videoId, ctx.relayBase, {
    autoMint: ctx.autoMint !== false,
    signal: ctx.signal ?? null,
  });
  if (token === null) {
    potVerdict =
      'no PO token available (mint failed or relay predates W1.0 — see the mint status)';
  } else {
    try {
      const player = await playerCallWebPot(videoId, ctx, token);
      const status = player.playabilityStatus?.status ?? 'UNKNOWN';
      if (status === 'OK') {
        const format = pickFormat(player, ctx.elementCompat === true);
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

  throw new Error(
    `no rung resolved ${videoId} — ` +
      `[ANDROID_VR] ${vrVerdict} · [WEB+POT] ${potVerdict}`,
  );
}

// ---------------------------------------------------------------------------
// Rung 1 — ANDROID_VR player POST through the relay (tokenless)
// ---------------------------------------------------------------------------

async function playerCallAndroidVr(
  videoId: string,
  ctx: ResolveContext,
): Promise<PlayerResponse> {
  // Keyless www.youtube.com innertube — the repo's established pattern
  // (see front-end/canary/src/probeMatrix.ts): the www host accepts
  // innertube POSTs without ?key= (only youtubei.googleapis.com
  // demands one, and embedding that key trips the secret scanner on a
  // value that is public in every youtube.com page anyway).
  const res = await fetch(relayUrl(ctx.relayBase, PLAYER_ENDPOINT), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-youtube-client-name': '28',
      'x-youtube-client-version': ANDROID_VR.clientVersion,
    },
    body: JSON.stringify({
      context: { client: ANDROID_VR },
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
// Rung 2 — WEB + PO token through the relay (W1.0)
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
      // WEB keeps the browser's own UA (its natural one; the relay's UA
      // matrix only swaps APP clients). visitorData rides both the body
      // and the header — the coherent "web session" story.
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
