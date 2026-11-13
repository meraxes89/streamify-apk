/**
 * audio/resolve.ts — the stream resolver ladder (G3b: the open-source
 * pivot — YouTube.js + BgUtils).
 *
 * RUNG 1 — VISIONOS (tokenless, YouTube.js client 'VISIONOS' = id 101,
 *   v1.02): verified live in P3b through the relay — DIRECT, pre-signed
 *   googlevideo URLs (itag 140/251, full contentLength, standard Range
 *   streaming) with NO PO token and NO 60-second cap, plus a bonus
 *   hlsManifestUrl in the same response. The relay swaps the matrix UA
 *   via X-YouTube-Client-Name 101. The raw format URLs are used AS-IS
 *   (no decipher — the P3b-proven behavior).
 *
 * RUNG 2 — WEB + PO TOKEN (the licensed-music rung): a bgutils-js mint
 *   (potSession) with the YouTube.js session's visitorData envelope:
 *   • context.client.visitorData + X-Goog-Visitor-Id — the session the
 *     mint bound against (youtubei.js sets both from its session);
 *   • serviceIntegrityDimensions.poToken ← mint(videoId) — the PLAYER-
 *     bound token (per-call po_token option);
 *   • &pot= on the deciphered googlevideo URL ← mint(videoId) while the
 *     homepage experiment flag binds GVS to videoId (GVS_BINDS_TO_VIDEO_ID),
 *     else visitorData — appended by youtubei.js's Player.decipher.
 *   When the response is SABR-only (the modern web-client reality: the
 *   audio itags arrive as URL-less descriptors), the rung hands back the
 *   response's own HLS manifest (elementCompat) — the remux lane makes
 *   it playable on every engine.
 *
 * RUNG 3 — WEB_SAFARI HLS (elementCompat only): a raw player POST (the
 *   WEB client through the relay's Safari-class UA override — youtubei.js
 *   has no web_safari client, and a browser cannot set a Safari UA),
 *   carrying the rung-2 player token when the mint produced one.
 *   Safari-flavored WEB calls receive HLS (m3u8) manifests that require
 *   NO GVS PO token; Chromium-class engines consume them through the
 *   G3b remux path (audio/hls/hlsRemux.ts — fMP4 byte-concat fast path,
 *   ffmpeg.wasm remux for TS segments).
 *
 * ALL rungs go THROUGH THE RELAY (youtubei.js fetch funnel — see
 * lib/ytSession.ts): the relay swaps in the matrix UA for client 101,
 * applies the Safari override on the WEB_SAFARI rung, and pins the
 * browser's own UA + client hints on the WEB legs (their natural
 * envelope). visitor_data + po_token cross the relay UNMODIFIED in the
 * request bodies/headers (the TSG IP/ASN binding rule: one egress for
 * the whole session).
 *
 * ROTATION LOOP GUARD: rungs that returned a DEFINITIVE refusal (status
 * verdict, not a network error) for a video are ledgered and skipped for
 * the rest of the page session — a URL rotation after an element error
 * must ESCALATE the ladder, never re-pick the same dead rung.
 */

import { webCodecsAvailable } from './webcodecsDecoder';
import { playerPoToken, gvsPoToken } from './botguard/potSession';
import { PLAYER_ENDPOINT, SAFARI_UA_OVERRIDE } from './botguard/protocol';
import { getYt, ytFetch } from '../lib/ytSession';
import type { AudioCodec } from './types';
import type { Innertube } from 'youtubei.js/web';

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

// ---------------------------------------------------------------------------
// youtubei.js structural types (Format/VideoInfo are not exported from
// 'youtubei.js/web' — these are the fields the resolver consumes)
// ---------------------------------------------------------------------------

interface YtFormat {
  itag?: number;
  url?: string;
  signature_cipher?: string;
  cipher?: string;
  mime_type?: string;
  bitrate?: number;
  content_length?: number;
  has_audio?: boolean;
  has_video?: boolean;
  decipher(player: unknown): Promise<string>;
}

interface YtBasicInfo {
  playability_status?: { status?: string; reason?: string } | null;
  basic_info?: {
    title?: string;
    author?: string;
    duration?: number;
  };
  streaming_data?: {
    adaptive_formats?: YtFormat[];
    hls_manifest_url?: string;
  } | null;
}

/** Raw /player POST body shape (the WEB_SAFARI rung). */
interface PlayerResponse {
  playabilityStatus?: { status?: string; reason?: string };
  videoDetails?: {
    title?: string;
    author?: string;
    lengthSeconds?: string;
  };
  streamingData?: {
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

/**
 * Extract the playable audio format by machine capability. Preference by
 * machine capability (not by bitrate): the W1.2 deviation (no symphonia
 * Opus decoder) makes AAC the only WASM rung, and this resolver refuses
 * to hand the spine a codec the machine cannot decode. The ELEMENT path
 * (direct egress) is always AAC-first — media elements decode mp4a.40.2
 * on every browser; opus-in-webm does not survive Safari's element
 * pipeline.
 */
function pickYtFormat(
  info: YtBasicInfo,
  elementCompat = false,
): YtFormat | undefined {
  const formats = info.streaming_data?.adaptive_formats ?? [];
  const playable = (f: YtFormat): boolean =>
    (f.has_audio === true || f.has_audio === undefined) &&
    f.has_video !== true &&
    (typeof f.url === 'string' || typeof f.signature_cipher === 'string' || typeof f.cipher === 'string');
  const pick = (itag: number): YtFormat | undefined =>
    formats.find((f) => f.itag === itag && playable(f));
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

/** Map a youtubei.js basic info + format to a ResolvedStream (shared rungs). */
function toStream(
  videoId: string,
  info: YtBasicInfo,
  format: YtFormat,
  url: string,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
): ResolvedStream {
  return {
    videoId,
    itag: format.itag ?? 0,
    codec: ITAG_CODEC[format.itag ?? 0] ?? 'aac',
    url,
    mimeType: format.mime_type ?? '',
    bitrate: format.bitrate ?? 0,
    contentLength:
      typeof format.content_length === 'number' ? format.content_length : null,
    durationSec:
      typeof info.basic_info?.duration === 'number' ? info.basic_info.duration : null,
    title: info.basic_info?.title ?? videoId,
    artist: info.basic_info?.author ?? '',
    rung,
    potKind,
    isHls: false,
  };
}

/** Map a player response to an HLS-manifest ResolvedStream. */
function toHlsStream(
  videoId: string,
  meta: { title: string; artist: string; durationSec: number | null },
  manifestUrl: string,
  rung: LadderRung,
): ResolvedStream {
  return {
    videoId,
    itag: 0, // HLS: itag-less variant playlists
    codec: 'aac', // YouTube HLS audio variants are AAC (fMP4 or TS)
    url: manifestUrl,
    mimeType: 'application/vnd.apple.mpegurl',
    bitrate: 0,
    contentLength: null,
    durationSec: meta.durationSec,
    title: meta.title,
    artist: meta.artist,
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
 *   Rung 1: VISIONOS (tokenless direct URLs / same-call HLS)
 *   Rung 2: WEB + PO token (bgutils-js dual-token mint — the licensed
 *           rung; falls through to its own HLS manifest when the
 *           response is SABR-only, the modern web-client reality)
 *   Rung 3: WEB_SAFARI HLS (elementCompat only — carries the rung-2
 *           player token when the mint produced one; HLS needs no GVS
 *           token, but licensed playability needs the player call's)
 * The thrown error carries EVERY rung's verdict for honest diagnosis.
 */
export async function resolveAudioStream(
  videoId: string,
  ctx: ResolveContext,
): Promise<ResolvedStream> {
  const verdicts: string[] = [];
  const elementCompat = ctx.elementCompat === true;
  const yt = await getYt();

  // ---- Rung 1: VISIONOS (tokenless) --------------------------------------
  if (rungAlive(videoId, 'visionos') || rungAlive(videoId, 'visionos-hls')) {
    let vrVerdict = 'not attempted';
    try {
      const info = await yt.getBasicInfo(videoId, { client: 'VISIONOS' });
      const status = info.playability_status?.status ?? 'UNKNOWN';
      if (status === 'OK') {
        const format = pickYtFormat(info, elementCompat);
        if (format !== undefined && typeof format.url === 'string') {
          // VISIONOS URLs are direct + pre-signed — used AS-IS (the
          // P3b-proven behavior; no decipher on this rung).
          return toStream(videoId, info, format, format.url, 'visionos', 'none');
        }
        const hls = info.streaming_data?.hls_manifest_url;
        if (hls !== undefined && hls.length > 0 && elementCompat) {
          // Adaptive missing, HLS present: serve the manifest (Safari
          // engines play it natively; Chromium-class engines consume it
          // through the G3b remux path — hlsRemux.ts).
          return toHlsStream(videoId, metaOf(info), hls, 'visionos-hls');
        }
        vrVerdict = 'OK but no playable audio itag (140/251) or HLS manifest';
        markRungFailed(videoId, 'visionos');
        markRungFailed(videoId, 'visionos-hls');
      } else {
        vrVerdict = `${status}: ${info.playability_status?.reason ?? 'no reason'}`;
        markRungFailed(videoId, 'visionos');
        markRungFailed(videoId, 'visionos-hls');
      }
    } catch (err) {
      vrVerdict = err instanceof Error ? err.message : String(err); // network-ish: NOT ledgered
    }
    verdicts.push(`[VISIONOS] ${vrVerdict}`);
  }

  // ---- Rung 2: WEB + PO token (the licensed-music path) --------------------
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
      const info = await yt.getBasicInfo(videoId, {
        client: 'WEB',
        // PLAYER-context token (serviceIntegrityDimensions.poToken) —
        // videoId-bound, per-call so it never leaks into other clients.
        po_token: token.poToken,
      });
      const status = info.playability_status?.status ?? 'UNKNOWN';
      if (status === 'OK') {
        const format = pickYtFormat(info, elementCompat);
        if (format !== undefined) {
          // GVS-context &pot= on the deciphered URL: Player.decipher
          // appends player.po_token (after nsig/sig) — set it to the
          // GVS binding (videoId while the homepage flag is set).
          const player = yt.session.player;
          if (player === undefined) {
            potVerdict = 'OK but no deciphering player (retrieve_player failed)';
          } else {
            const gvs = await gvsPoToken(videoId);
            if (gvs !== null) {
              player.po_token = gvs;
            }
            const url = await format.decipher(player);
            return toStream(videoId, info, format, url, 'web-pot', token.kind);
          }
        } else {
          // The modern web-client reality: URL-less SABR descriptors for
          // the audio itags. When the same response carries an HLS
          // manifest (and the element path can consume one), hand the
          // manifest back — the remux lane (hlsRemux.ts) makes it
          // element-playable on every engine.
          const hls = info.streaming_data?.hls_manifest_url;
          if (hls !== undefined && hls.length > 0 && elementCompat) {
            return toHlsStream(videoId, metaOf(info), hls, 'web-pot');
          }
          // Honest inventory in the verdict: which audio itags the response
          // actually carried (SABR-only responses have none with URLs).
          const audioItags = (info.streaming_data?.adaptive_formats ?? [])
            .filter((f) => f.has_video !== true)
            .map((f) => f.itag ?? '?')
            .join(',');
          potVerdict = `OK but no playable audio itag (want ${elementCompat ? '140' : '251/140'} — audio itags present: ${audioItags.length > 0 ? audioItags : 'NONE (SABR-only?)'})`;
        }
      } else {
        potVerdict = `${status}: ${info.playability_status?.reason ?? 'no reason'}`;
      }
    } catch (err) {
      potVerdict = err instanceof Error ? err.message : String(err);
    }
  }
  verdicts.push(`[WEB+POT] ${potVerdict}`);

  // ---- Rung 3: WEB_SAFARI HLS (elementCompat only) ------------------------
  // Carries the rung-2 player token when the mint produced one (licensed
  // playability needs the player call's PO token regardless of UA; the
  // HLS manifests themselves need no GVS token — the yt-dlp web_safari
  // exception, still true at the time of writing).
  if (elementCompat && rungAlive(videoId, 'web-safari-hls')) {
    let safariVerdict = 'not attempted';
    try {
      const player = await playerCallWebSafari(
        videoId,
        yt,
        ctx,
        token !== null ? token.poToken : null,
      );
      const status = player.playabilityStatus?.status ?? 'UNKNOWN';
      const hls = player.streamingData?.hlsManifestUrl;
      if (status === 'OK' && hls !== undefined && hls.length > 0) {
        return toHlsStream(
          videoId,
          {
            title: player.videoDetails?.title ?? videoId,
            artist: player.videoDetails?.author ?? '',
            durationSec:
              player.videoDetails?.lengthSeconds !== undefined
                ? Number(player.videoDetails.lengthSeconds)
                : null,
          },
          hls,
          'web-safari-hls',
        );
      }
      safariVerdict =
        status === 'OK'
          ? 'OK but no hlsManifestUrl'
          : `${status}: ${player.playabilityStatus?.reason ?? 'no reason'}`;
      markRungFailed(videoId, 'web-safari-hls');
    } catch (err) {
      safariVerdict = err instanceof Error ? err.message : String(err);
    }
    verdicts.push(`[WEB_SAFARI] ${safariVerdict}`);
  }

  throw new Error(`no rung resolved ${videoId} — ${verdicts.join(' · ')}`);
}

// ---------------------------------------------------------------------------
// Rung 3 — WEB_SAFARI: raw player POST through the relay (Safari UA)
// ---------------------------------------------------------------------------

/**
 * The WEB client presented through a Safari-class UA (the relay's
 * X-Relay-UA override — a forbidden header no browser can set itself).
 * A RAW POST rather than a youtubei.js client call: youtubei.js has no
 * web_safari client and offers no per-call header injection. The body
 * carries the YouTube.js session's own context (deep-copy semantics
 * match what its HTTPClient would send), so the visitorData envelope
 * stays coherent across all rungs.
 */
async function playerCallWebSafari(
  videoId: string,
  yt: Innertube,
  ctx: ResolveContext,
  poToken: string | null,
): Promise<PlayerResponse> {
  const context = yt.session.context;
  const res = await ytFetch(PLAYER_ENDPOINT, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-youtube-client-name': '1', // WEB
      'x-youtube-client-version': context.client.clientVersion,
      'x-goog-visitor-id': context.client.visitorData ?? '',
      // The relay control header — swaps in the Safari-class UA (and,
      // per the envelope rule, strips the browser's Chrome client
      // hints: Safari sends none).
      'x-relay-ua': SAFARI_UA_OVERRIDE,
    },
    body: JSON.stringify({
      context,
      videoId,
      contentCheckOk: true,
      racyCheckOk: true,
      // PLAYER-context PO token when the rung-2 mint produced one —
      // licensed playability needs it on the player call itself.
      ...(poToken !== null ? { serviceIntegrityDimensions: { poToken } } : {}),
    }),
    signal: ctx.signal ?? undefined,
  });
  if (!res.ok) {
    throw new Error(`player relay call ${res.status}`);
  }
  return (await res.json()) as PlayerResponse;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function metaOf(info: YtBasicInfo): {
  title: string;
  artist: string;
  durationSec: number | null;
} {
  return {
    title: info.basic_info?.title ?? '',
    artist: info.basic_info?.author ?? '',
    durationSec:
      typeof info.basic_info?.duration === 'number' ? info.basic_info.duration : null,
  };
}
