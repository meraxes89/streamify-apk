/**
 * audio/resolve.ts — the stream resolver ladder (P1 scope).
 *
 * LADDER RUNGS (P1 task book, honest state after the sandbox reset):
 *
 *   1. ANDROID_VR (clientName 28, v1.65.10) — SERVES DIRECT googlevideo
 *      URLs without signature work and without visitorData (verified:
 *      P0 canary + this session's demux-verify run). PRIMARY for
 *      non-licensed tracks from datacenter IPs and the only rung this
 *      iteration wires end-to-end.
 *   2. WEB / WEB_REMIX + PO token (the BotGuard path, W1.0) — REQUIRED
 *      for licensed music (the P0 canary finding: licensed → LOGIN_
 *      REQUIRED bot-wall for every client from datacenter IPs; the
 *      unlock proof needs a residential browser). The minting stack
 *      (browser-native BotGuard, PO token injection in the relay) was
 *      built in a prior session and LOST with the sandbox before it
 *      was pushed; potSession is the re-integration hook — the
 *      resolver consults it before trying WEB-family rungs.
 *
 * The call goes THROUGH THE RELAY (POST is forwarded; the relay swaps
 * in the matrix UA for X-YouTube-Client-Name 28 — browsers cannot set
 * app UAs, forbidden header).
 */

import { webCodecsAvailable } from './webcodecsDecoder';
import type { AudioCodec } from './types';

/** Client specs (mirrors the relay's CLIENT_NAME_UA matrix). */
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
}

export interface ResolveContext {
  /** Relay base URL (REQUIRED in the page — googlevideo is CORS-walled). */
  relayBase: string;
  /** Abort the resolve. */
  signal?: AbortSignal | null;
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

/**
 * Resolve one video's best playable audio stream. Preference order:
 * itag 251 (opus) when WebCodecs is available, else 140 (aac) — the
 * W1.2 deviation (no symphonia Opus decoder) makes AAC the only WASM
 * rung, and this resolver refuses to hand the spine a codec the
 * machine cannot decode.
 */
export async function resolveAudioStream(
  videoId: string,
  ctx: ResolveContext,
): Promise<ResolvedStream> {
  const player = await playerCall(videoId, ctx);
  const status = player.playabilityStatus?.status ?? 'UNKNOWN';
  if (status !== 'OK') {
    throw new Error(
      `playability ${status}: ${player.playabilityStatus?.reason ?? 'no reason'} ` +
        `(licensed track? needs the POT/WEB rung — see resolve.ts header)`,
    );
  }
  const formats = player.streamingData?.adaptiveFormats ?? [];
  const pick = (itag: number): PlayerFormat | undefined =>
    formats.find((f) => f.itag === itag && typeof f.url === 'string');
  // Ladder preference by machine capability (not by bitrate).
  const want: number[] = webCodecsAvailable() ? [251, 140] : [140];
  let chosen: PlayerFormat | undefined;
  for (const itag of want) {
    chosen = pick(itag);
    if (chosen !== undefined) break;
  }
  if (chosen === undefined || chosen.url === undefined) {
    throw new Error('no playable audio itag (251/140) in adaptiveFormats');
  }
  return {
    videoId,
    itag: chosen.itag,
    codec: ITAG_CODEC[chosen.itag] ?? 'aac',
    url: chosen.url,
    mimeType: chosen.mimeType ?? '',
    bitrate: chosen.bitrate ?? 0,
    contentLength:
      chosen.contentLength !== undefined ? Number(chosen.contentLength) : null,
    durationSec:
      player.videoDetails?.lengthSeconds !== undefined
        ? Number(player.videoDetails.lengthSeconds)
        : null,
    title: player.videoDetails?.title ?? videoId,
    artist: player.videoDetails?.author ?? '',
  };
}

/** ANDROID_VR player POST through the relay. */
async function playerCall(
  videoId: string,
  ctx: ResolveContext,
): Promise<PlayerResponse> {
  // Keyless www.youtube.com innertube — the repo's established pattern
  // (see front-end/canary/src/probeMatrix.ts): the www host accepts
  // innertube POSTs without ?key= (only youtubei.googleapis.com
  // demands one, and embedding that key trips the secret scanner on a
  // value that is public in every youtube.com page anyway).
  const target = 'https://www.youtube.com/youtubei/v1/player';
  const wrapped = `${ctx.relayBase.replace(/\/$/, '')}/r?u=${encodeURIComponent(target)}`;
  const res = await fetch(wrapped, {
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
