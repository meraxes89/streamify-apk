/**
 * audio/resolve.ts — the stream resolver ladder (G4: residential-direct).
 *
 * WHY G4 EXISTS (measured 2026-11-14, decisions/G4-residential-direct.md):
 * the relay's shared Cloudflare egress answers EVERY innertube client with
 * LOGIN_REQUIRED for licensed music ("Sign in to confirm you're not a bot")
 * and the session-carrying variants get the harsher UNPLAYABLE "This video
 * is unavailable" — the exact phone-reported failure. PO tokens minted
 * through that egress cannot help (the BgUtils README's documented ceiling:
 * PO tokens do not bypass IP-based login restrictions on datacenter IPs).
 *
 * THE PIVOT: the phone's own browser is a first-class egress. YouTube
 * serves three surface families to cross-origin browsers by design
 * (youtubei.googleapis.com innertube, jnn-pa WAA, *.googlevideo.com media —
 * the same surfaces the real web player calls cross-origin), so the
 * IP-sensitive legs now go DIRECT from the phone:
 *
 * G4.1 MEASURED REALITY (resolver-canary runs 43564752070/43566827760,
 * 2026-11-14): youtubei.googleapis.com is IP-walled from every datacenter
 * egress we control, and its foreign-origin CORS posture is measurable only
 * from the phone (g3b-test row 1). If the direct lane is dead from the
 * user's network, every direct player fetch throws at the network layer and
 * the relay fallback is the operative lane (the direct-lane negative cache
 * in ytSession makes later attempts skip the doomed preflight). The
 * `*-direct` rungs keep their names because their CLIENT SPECS target the
 * googleapis surface — the one innertube frontend that answers IOS
 * playability OK for licensed content from the relay's egress — and their
 * verdicts carry the honest `@relay-egress` marker when the relay answered.
 * jnn-pa (the mint) IS CORS-open to foreign origins (live-verified) — PO
 * tokens still bind to the phone's IP.
 *
 *   RUNG 1 — VISIONOS-DIRECT (tokenless, youtubei.googleapis.com): probe-
 *     verified on the live relay egress — standard content returns OK with
 *     DIRECT pre-signed itag 140/251 URLs (used AS-IS, no decipher), plus a
 *     bonus hlsManifestUrl and the SABR url. From the phone's residential
 *     IP this is the fast path.
 *
 *   RUNG 2 — IOS-DIRECT (tokenless, youtubei.googleapis.com): the
 *     licensed-tolerant client — probe-verified OK for the exact video that
 *     failed every G3b rung (BOHEMIA - Ek Tera Pyar) even from the BURNED
 *     Cloudflare egress. Answers URL-less SABR descriptors + an HLS
 *     manifest; both are consumed below.
 *
 *   RUNG 3 — IOS + PLAYER PO TOKEN (direct): the licensed unlock. yt-dlp's
 *     IOS GVS policy: `not_required_with_player_token=True` — a videoId-
 *     bound PO token on the PLAYER call waives the GVS token requirement
 *     for the media URLs. The mint (potMinter) now rides jnn-pa DIRECT
 *     (CORS-open), so token, player call and media fetches all egress from
 *     the phone — one coherent envelope.
 *
 *   RUNG 4 — WEB + PO TOKEN (youtubei.js WEB client, direct googleapis via
 *     the dual-lane session fetch): the G3b licensed rung, kept for its
 *     deciphering machinery (signatureCipher URLs) and its SABR→HLS
 *     fallthrough.
 *
 *   RUNG 5 — VISIONOS-RELAY (www.youtube.com through the relay): the
 *     standard-content lifeline for environments where the direct lane is
 *     unreachable (probe-verified STILL WORKING through the live relay for
 *     standard content: OK + URL-ful formats).
 *
 *   RUNG 6 — WEB_SAFARI HLS (www.youtube.com through the relay + the
 *     Safari-class UA override): the G3b elementCompat fallback. NOTE the
 *     yt-dlp master comment (2026.07): web_safari HLS is only returned for
 *     logged-in or "trusted" sessions — this rung is vestigial and kept
 *     last on purpose.
 *
 * ALL rungs prefer, in order: progressive URLs (element: itag 140 AAC;
 * spine: 251/140 by codec capability) → HLS manifest (the remux lanes) →
 * the SABR lane (audio/hls's sibling audio/sabr: googlevideo's SabrStream
 * over the CORS-open media CDN, MSE-fed to the element).
 *
 * The dual-lane fetch (lib/ytSession.ts) makes every youtubei.googleapis.com
 * call DIRECT with per-request relay fallback on network failure — a
 * network-level failure never gets ledgered (only definitive upstream
 * verdicts do), so a flaky direct lane degrades instead of burning rungs.
 */

import { webCodecsAvailable } from './webcodecsDecoder';
import { playerPoToken, gvsPoToken } from './botguard/potSession';
import {
  GAPI_PLAYER_ENDPOINT,
  PLAYER_ENDPOINT,
  SAFARI_UA_OVERRIDE,
  relayUrl,
} from './botguard/protocol';
import { getYt, directPlayerPost, visitorProvenance } from '../lib/ytSession';
import type { AudioCodec } from './types';

/** itag → codec routing (audio-only set). */
const ITAG_CODEC: Record<number, AudioCodec> = { 140: 'aac', 251: 'opus' };

/**
 * The ladder rungs (ledger + diagnostics). Direct rungs egress from the
 * phone (youtubei.googleapis.com / googlevideo); relay rungs egress from
 * the Cloudflare Worker (www.youtube.com surface).
 */
export type LadderRung =
  | 'visionos-direct'
  | 'ios-direct'
  | 'ios-pot-direct'
  | 'web-pot-direct'
  | 'visionos-relay'
  | 'web-safari-hls';

/**
 * The SABR playback payload (the audio/sabr lane's input). Carried on the
 * ResolvedStream when a rung answers SABR-only descriptors; the element
 * path consumes it through googlevideo's SabrStream (MSE audio).
 */
export interface SabrPayload {
  /** The player response's streamingData.serverAbrStreamingUrl. */
  serverAbrStreamingUrl: string;
  /** videoPlaybackUstreamerConfig (base64) — authorizes the stream session. */
  ustreamerConfig: string | null;
  /** Audio-only SABR format descriptors (wire shape, itag/mimeType/…). */
  formats: unknown[];
  /** Numeric innertube client identity for the SABR requests. */
  clientInfo: {
    clientName: number;
    clientVersion: string;
    osName?: string;
    osVersion?: string;
  };
  /** videoId-bound player PO token when the rung carried one. */
  poToken: string | null;
  /** Total duration in ms (format metadata fallback for SabrStream). */
  durationMs: number;
}

export interface ResolvedStream {
  videoId: string;
  itag: number;
  codec: AudioCodec;
  /**
   * Direct googlevideo URL, the HLS manifest URL (isHls), or — for the
   * SABR lane — the serverAbrStreamingUrl (isSabr; the element path swaps
   * it for an MSE object URL before any load).
   */
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
  /** True when the SABR payload below is the playback source. */
  isSabr: boolean;
  /** Present iff isSabr — the audio/sabr lane's input. */
  sabr?: SabrPayload;
}

export interface ResolveContext {
  /** Relay base URL (the www-surface rungs + relay fallbacks). */
  relayBase: string;
  /** Abort the resolve. */
  signal?: AbortSignal | null;
  /**
   * Allow the POT rungs to lazily MINT a BotGuard session when missing
   * (default true — licensed tracks need it; the mint runs once per ~12 h).
   */
  autoMint?: boolean;
  /**
   * W2.0 element-path mode: prefer itag 140 (AAC/MP4) EXCLUSIVELY and allow
   * the HLS/SABR lanes (the `<audio>` element decodes AAC everywhere it
   * matters; the WASM spine can demux neither HLS nor SABR).
   */
  elementCompat?: boolean;
}

// ---------------------------------------------------------------------------
// Raw player-response shapes (the wire JSON — the direct/relay rungs)
// ---------------------------------------------------------------------------

interface WireFormat {
  itag?: number;
  url?: string;
  signatureCipher?: string;
  cipher?: string;
  mimeType?: string;
  bitrate?: number;
  contentLength?: number | string;
  audioQuality?: string;
  lastModified?: number | string;
}

interface PlayerResponse {
  playabilityStatus?: { status?: string; reason?: string };
  videoDetails?: {
    title?: string;
    author?: string;
    lengthSeconds?: string;
    videoId?: string;
  };
  streamingData?: {
    adaptiveFormats?: WireFormat[];
    formats?: WireFormat[];
    hlsManifestUrl?: string;
    serverAbrStreamingUrl?: string;
    expiresInSeconds?: string | number;
  };
  playerConfig?: {
    mediaCommonConfig?: {
      mediaUstreamerRequestConfig?: {
        videoPlaybackUstreamerConfig?: string;
      };
    };
  };
}

/** youtubei.js structural types (the WEB+POT rung). */
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

// ---------------------------------------------------------------------------
// The client matrix (raw rungs) — field-for-field the canary/probe payloads
// that were live-verified 2026-11-14 (tools/probe-clients.sh lineage; the
// versions rot monthly — re-verify alongside the relay's UA matrix).
// ---------------------------------------------------------------------------

interface ClientSpec {
  /** Ladder-rung prefix + display name. */
  rung: LadderRung;
  /** X-YouTube-Client-Name (numeric innertube client id). */
  clientNumber: string;
  clientName: string;
  clientVersion: string;
  /** context.client.userAgent — the BODY carries the client's app UA
   * (browsers cannot set the header; the relay matrix rewrites the header
   * on the www surface, the direct lane rides the honest browser UA). */
  userAgent: string;
  extraClient: Record<string, unknown>;
}

const SAFARI_MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';

const VISIONOS_SPEC: ClientSpec = {
  rung: 'visionos-direct',
  clientNumber: '101',
  clientName: 'VISIONOS',
  clientVersion: '1.02',
  userAgent: SAFARI_MAC_UA,
  extraClient: {
    deviceMake: 'Apple',
    deviceModel: 'RealityDevice17,1',
    osName: 'visionOS',
    osVersion: '26.5.23O471',
  },
};

const IOS_SPEC: ClientSpec = {
  rung: 'ios-direct',
  clientNumber: '5',
  clientName: 'IOS',
  clientVersion: '21.26.4',
  userAgent:
    'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)',
  extraClient: {
    deviceMake: 'Apple',
    deviceModel: 'iPhone16,2',
    osName: 'iPhone',
    osVersion: '18.3.2.22D82',
  },
};

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
// Wire-format picking (raw player responses)
// ---------------------------------------------------------------------------

/**
 * Pick the playable PROGRESSIVE audio format from a raw response. Only
 * formats with a literal `url` qualify (VISIONOS/IOS URLs are pre-signed;
 * deciphering exists only on the WEB rung). elementCompat → itag 140 only
 * (the element's AAC guarantee); spine → 251/140 by codec capability.
 */
function pickWireFormat(
  resp: PlayerResponse,
  elementCompat: boolean,
): WireFormat | undefined {
  const formats = [
    ...(resp.streamingData?.adaptiveFormats ?? []),
    ...(resp.streamingData?.formats ?? []),
  ];
  const playable = (f: WireFormat): boolean =>
    typeof f.url === 'string' &&
    f.url.length > 0 &&
    typeof f.mimeType === 'string' &&
    f.mimeType.startsWith('audio/');
  const pick = (itag: number): WireFormat | undefined =>
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

/**
 * The FULL SABR descriptor set for the SabrStream payload. SabrStream's
 * selection requires a video format to exist even for AUDIO_ONLY playback
 * (selectFormats throws without one — measured); the AUDIO_ONLY track
 * bitfield is what keeps video from being fetched.
 */
function sabrAllFormats(resp: PlayerResponse): unknown[] {
  return [
    ...(resp.streamingData?.adaptiveFormats ?? []),
    ...(resp.streamingData?.formats ?? []),
  ];
}

function durationSecOf(resp: PlayerResponse): number | null {
  const raw = resp.videoDetails?.lengthSeconds;
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function titleOf(resp: PlayerResponse): { title: string; artist: string } {
  return {
    title: resp.videoDetails?.title ?? '',
    artist: resp.videoDetails?.author ?? '',
  };
}

// ---------------------------------------------------------------------------
// Stream builders
// ---------------------------------------------------------------------------

function wireToStream(
  videoId: string,
  resp: PlayerResponse,
  format: WireFormat,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
): ResolvedStream {
  return {
    videoId,
    itag: format.itag ?? 0,
    codec: ITAG_CODEC[format.itag ?? 0] ?? 'aac',
    url: format.url ?? '',
    mimeType: format.mimeType ?? '',
    bitrate: format.bitrate ?? 0,
    contentLength:
      typeof format.contentLength === 'number'
        ? format.contentLength
        : typeof format.contentLength === 'string' && format.contentLength !== ''
          ? Number(format.contentLength)
          : null,
    durationSec: durationSecOf(resp),
    ...titleOf(resp),
    rung,
    potKind,
    isHls: false,
    isSabr: false,
  };
}

function toHlsStream(
  videoId: string,
  meta: { title: string; artist: string; durationSec: number | null },
  manifestUrl: string,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'] = 'none',
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
    potKind,
    isHls: true,
    isSabr: false,
  };
}

function toSabrStream(
  videoId: string,
  resp: PlayerResponse,
  spec: ClientSpec,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
  poToken: string | null,
): ResolvedStream {
  const durationSec = durationSecOf(resp);
  return {
    videoId,
    itag: 0, // SABR: itag-less descriptors (SabrStream selects)
    codec: 'aac', // selection prefers itag 140 (element-compat AAC)
    url: resp.streamingData?.serverAbrStreamingUrl ?? '',
    mimeType: 'application/vnd.yt.sabr',
    bitrate: 0,
    contentLength: null,
    durationSec,
    ...titleOf(resp),
    rung,
    potKind,
    isHls: false,
    isSabr: true,
    sabr: {
      serverAbrStreamingUrl: resp.streamingData?.serverAbrStreamingUrl ?? '',
      ustreamerConfig:
        resp.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig
          ?.videoPlaybackUstreamerConfig ?? null,
      formats: sabrAllFormats(resp),
      clientInfo: {
        clientName: Number(spec.clientNumber),
        clientVersion: spec.clientVersion,
        ...(typeof spec.extraClient.osName === 'string'
          ? { osName: spec.extraClient.osName }
          : {}),
        ...(typeof spec.extraClient.osVersion === 'string'
          ? { osVersion: spec.extraClient.osVersion }
          : {}),
      },
      poToken,
      durationMs: durationSec !== null ? Math.round(durationSec * 1000) : 0,
    },
  };
}

// ---------------------------------------------------------------------------
// The raw player calls
// ---------------------------------------------------------------------------

/**
 * POST a raw player call to the CORS-open googleapis surface, DIRECT from
 * the phone (lib/ytSession.ts#directPlayerPost retries once on the relay
 * when the direct lane is unreachable — the response's lane is reported so
 * the verdict stays egress-attributable).
 */
async function playerCallDirect(
  spec: ClientSpec,
  videoId: string,
  ctx: ResolveContext,
  poToken: string | null,
): Promise<{ resp: PlayerResponse; lane: 'direct' | 'relay' }> {
  const client: Record<string, unknown> = {
    clientName: spec.clientName,
    clientVersion: spec.clientVersion,
    hl: 'en',
    gl: 'US',
    userAgent: spec.userAgent,
    ...spec.extraClient,
  };
  const headers: Record<string, string> = {
    'x-youtube-client-name': spec.clientNumber,
    'x-youtube-client-version': spec.clientVersion,
  };
  if (poToken !== null) {
    // The PO-token'd rungs bind against the YouTube.js session's
    // visitorData — the same root the mint bound to.
    const visitorData = (await getYt()).session.context.client.visitorData;
    if (typeof visitorData === 'string' && visitorData.length > 0) {
      client.visitorData = visitorData;
      headers['x-goog-visitor-id'] = visitorData;
    }
  }
  const payload: Record<string, unknown> = {
    context: { client },
    videoId,
    contentCheckOk: true,
    racyCheckOk: true,
    ...(poToken !== null
      ? { serviceIntegrityDimensions: { poToken } }
      : {}),
  };
  const { response, lane } = await directPlayerPost(
    GAPI_PLAYER_ENDPOINT,
    payload,
    headers,
    ctx.signal,
  );
  if (!response.ok) {
    throw new Error(`player call ${lane} ${response.status}`);
  }
  return { resp: (await response.json()) as PlayerResponse, lane };
}

/**
 * POST a raw player call to the www.youtube.com surface THROUGH THE RELAY
 * (the G3b WEB_SAFARI mechanism, generalized): the relay swaps in the
 * matrix UA for app clients (X-YouTube-Client-Name) and applies the named
 * Safari override when requested. Deliberately NOT ytFetch — the session
 * funnel rewrites www /youtubei/* calls onto the googleapis surface, and
 * these rungs exist precisely as the www-via-relay fallback lane.
 */
async function playerCallRelay(
  spec: { clientNumber: string; clientName: string; clientVersion: string },
  videoId: string,
  ctx: ResolveContext,
  opts: { uaOverride?: string; poToken?: string | null } = {},
): Promise<PlayerResponse> {
  const yt = await getYt();
  const context = yt.session.context;
  const res = await fetch(
    relayUrl(ctx.relayBase, `${PLAYER_ENDPOINT}?prettyPrint=false&alt=json`),
    {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-youtube-client-name': spec.clientNumber,
        'x-youtube-client-version': spec.clientVersion,
        'x-goog-visitor-id': context.client.visitorData ?? '',
        ...(opts.uaOverride !== undefined
          ? { 'x-relay-ua': opts.uaOverride }
          : {}),
      },
      body: JSON.stringify({
        // The relay rungs deliberately declare their OWN client context (the
        // session context is WEB-shaped; the matrix UA must match the body).
        context: {
          client: {
            clientName: spec.clientName,
            clientVersion: spec.clientVersion,
            hl: 'en',
            gl: 'US',
            userAgent:
              spec.clientNumber === '101' ? SAFARI_MAC_UA : undefined,
            ...(spec.clientNumber === '101'
              ? VISIONOS_SPEC.extraClient
              : {}),
          },
        },
        videoId,
        contentCheckOk: true,
        racyCheckOk: true,
        ...(opts.poToken != null
          ? { serviceIntegrityDimensions: { poToken: opts.poToken } }
          : {}),
      }),
      credentials: 'omit',
      signal: ctx.signal ?? undefined,
    },
  );
  if (!res.ok) {
    throw new Error(`player relay call ${res.status}`);
  }
  return (await res.json()) as PlayerResponse;
}

// ---------------------------------------------------------------------------
// Rung evaluation: one raw response → progressive / HLS / SABR / verdict
// ---------------------------------------------------------------------------

interface RungOutcome {
  stream: ResolvedStream | null;
  verdict: string;
  definitive: boolean;
}

function evaluateRawResponse(
  videoId: string,
  resp: PlayerResponse,
  spec: ClientSpec,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
  poToken: string | null,
  elementCompat: boolean,
): RungOutcome {
  const status = resp.playabilityStatus?.status ?? 'UNKNOWN';
  if (status !== 'OK') {
    return {
      stream: null,
      verdict: `${status}: ${resp.playabilityStatus?.reason ?? 'no reason'}`,
      definitive: true,
    };
  }
  // 1. Progressive URLs — the cheapest playback (no lanes, plain src).
  const format = pickWireFormat(resp, elementCompat);
  if (format !== undefined) {
    return { stream: wireToStream(videoId, resp, format, rung, potKind), verdict: 'OK (progressive)', definitive: false };
  }
  // 2. HLS manifest — the remux lanes (elementCompat only).
  const hls = resp.streamingData?.hlsManifestUrl;
  if (hls !== undefined && hls.length > 0 && elementCompat) {
    return {
      stream: toHlsStream(
        videoId,
        { ...titleOf(resp), durationSec: durationSecOf(resp) },
        hls,
        rung,
        potKind,
      ),
      verdict: 'OK (hls manifest)',
      definitive: false,
    };
  }
  // 3. The SABR lane — URL-less descriptors + the serverAbrStreamingUrl
  //    (the modern reality for IOS-class clients; audio/sabr plays it).
  const sabrUrl = resp.streamingData?.serverAbrStreamingUrl;
  const sabrFormats = sabrAllFormats(resp);
  const sabrAudioItags = sabrFormats
    .filter(
      (f) =>
        typeof (f as WireFormat).mimeType === 'string' &&
        (f as WireFormat).mimeType?.startsWith('audio/'),
    )
    .map((f) => (f as WireFormat).itag ?? '?');
  if (
    sabrUrl !== undefined &&
    sabrUrl.length > 0 &&
    sabrAudioItags.length > 0 &&
    sabrFormats.length > 0 &&
    elementCompat
  ) {
    return {
      stream: toSabrStream(videoId, resp, spec, rung, potKind, poToken),
      verdict: `OK (sabr: ${sabrFormats.length} descriptors, audio ${sabrAudioItags.join(',')})`,
      definitive: false,
    };
  }
  return {
    stream: null,
    verdict: `OK but no playable lane (progressive=none, hls=${hls !== undefined ? 'yes' : 'no'}, sabr=${sabrUrl !== undefined ? 'yes' : 'no'}, audio itags: ${sabrAudioItags.length > 0 ? sabrAudioItags.join(',') : 'NONE'})`,
    definitive: true,
  };
}

// ---------------------------------------------------------------------------
// Rung execution with the flap-retry policy
// ---------------------------------------------------------------------------

/**
 * THE FLAP-RETRY POLICY (probe-measured 2026-11-14, G4.1 lane-aware): the
 * RELAY egress's licensed-content verdicts are PROBABILISTIC — six
 * byte-identical IOS player POSTs through the live relay answered OK /
 * LOGIN_REQUIRED / OK / LOGIN_REQUIRED / OK / OK (a soft per-request wall,
 * not a stable block). A single-shot RELAY rung is therefore a coin flip;
 * three attempts push the miss chance to ~1/8 at ~150–300 ms per attempt.
 *
 * DIRECT-lane verdicts do NOT flap (the flap lives in the relay's shared-IP
 * wall, not in YouTube's per-IP scoring) — so G4.1 halves the direct rungs
 * to two attempts: a licensed refusal from the phone's own IP is a stable
 * verdict, and every wasted attempt is latency the user hears as silence
 * before the ladder reaches the rung that can actually unlock the track.
 */
const RUNG_ATTEMPTS = 3;
const DIRECT_RUNG_ATTEMPTS = 2;

/** What one rung attempt's player call reports back. */
interface RungCallResult {
  resp: PlayerResponse;
  /** Which egress actually answered (direct rungs may relay-fallback). */
  lane?: 'direct' | 'relay';
}

/**
 * Run one raw rung with retries. Returns the stream when any attempt
 * yields one; otherwise the final verdict (for the verdicts aggregate +
 * the ledger decision).
 *
 * HONEST LANES (G4.1): when a `*-direct` rung's fetch network-failed on the
 * direct lane and answered via the relay fallback, the verdict is a
 * RELAY-EGRESS verdict — it is labeled `@relay-egress` so a phone-side
 * LOGIN_REQUIRED under a `*` rung is correctly attributed (the direct lane
 * was unreachable, not refused).
 */
async function runRawRung(
  label: string,
  videoId: string,
  spec: ClientSpec,
  rung: LadderRung,
  potKind: ResolvedStream['potKind'],
  poToken: string | null,
  elementCompat: boolean,
  call: () => Promise<RungCallResult>,
  opts: { maxAttempts?: number } = {},
): Promise<RungOutcome> {
  const maxAttempts = opts.maxAttempts ?? RUNG_ATTEMPTS;
  let outcome: RungOutcome = {
    stream: null,
    verdict: 'not attempted',
    definitive: true,
  };
  let networkError: string | null = null;
  /** Any attempt returned a definitive upstream verdict (sticky). */
  let sawDefinitive = false;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const { resp, lane } = await call();
      outcome = evaluateRawResponse(
        videoId,
        resp,
        spec,
        rung,
        potKind,
        poToken,
        elementCompat,
      );
      if (lane === 'relay') {
        outcome.verdict = `${outcome.verdict} @relay-egress`;
      }
      networkError = null;
      if (outcome.definitive) sawDefinitive = true;
      if (outcome.stream !== null) return outcome;
      // A refusal (or an OK without a playable lane): retry — the relay
      // wall flaps per request, and format availability flaps with it.
    } catch (err) {
      networkError = err instanceof Error ? err.message : String(err);
      outcome = {
        stream: null,
        verdict: networkError,
        definitive: false, // network-ish: never ledgered
      };
    }
    if (attempt < maxAttempts) {
      await new Promise((r) => setTimeout(r, 350 * attempt));
    }
  }
  if (sawDefinitive) {
    // At least one attempt got a REAL upstream refusal — the rung is
    // ledgerable even if the last attempt died at the network layer (a
    // trailing network error must not launder a verdict into a loop).
    return { ...outcome, definitive: true };
  }
  if (networkError !== null) {
    // Every attempt died at the network layer — a dead lane, not a
    // verdict. Report honestly; the caller does NOT ledger it.
    return { stream: null, verdict: `${label} ${networkError}`, definitive: false };
  }
  return outcome;
}

// ---------------------------------------------------------------------------
// The ladder
// ---------------------------------------------------------------------------

/**
 * Resolve one video's best playable audio stream. The thrown error carries
 * EVERY rung's verdict for honest diagnosis.
 */
export async function resolveAudioStream(
  videoId: string,
  ctx: ResolveContext,
): Promise<ResolvedStream> {
  const verdicts: string[] = [];
  const elementCompat = ctx.elementCompat === true;
  const yt = await getYt();

  // ---- Rung 1: VISIONOS-DIRECT (tokenless, phone egress) ------------------
  if (rungAlive(videoId, 'visionos-direct')) {
    const outcome = await runRawRung(
      '[VISIONOS*]',
      videoId,
      VISIONOS_SPEC,
      'visionos-direct',
      'none',
      null,
      elementCompat,
      () => playerCallDirect(VISIONOS_SPEC, videoId, ctx, null),
      { maxAttempts: DIRECT_RUNG_ATTEMPTS },
    );
    if (outcome.stream !== null) return outcome.stream;
    if (outcome.definitive) markRungFailed(videoId, 'visionos-direct');
    verdicts.push(`[VISIONOS*] ${outcome.verdict}`);
  }

  // ---- Rung 2: IOS-DIRECT (tokenless, licensed-tolerant) ------------------
  if (rungAlive(videoId, 'ios-direct')) {
    const outcome = await runRawRung(
      '[IOS*]',
      videoId,
      IOS_SPEC,
      'ios-direct',
      'none',
      null,
      elementCompat,
      () => playerCallDirect(IOS_SPEC, videoId, ctx, null),
      { maxAttempts: DIRECT_RUNG_ATTEMPTS },
    );
    if (outcome.stream !== null) return outcome.stream;
    if (outcome.definitive) markRungFailed(videoId, 'ios-direct');
    verdicts.push(`[IOS*] ${outcome.verdict}`);
  }

  // ---- The PO token (the licensed rungs share one mint) --------------------
  const token = await playerPoToken(videoId, ctx.relayBase, {
    autoMint: ctx.autoMint !== false,
    signal: ctx.signal ?? null,
  });

  // ---- Rung 3: IOS + PLAYER PO TOKEN (direct) ------------------------------
  if (token !== null && rungAlive(videoId, 'ios-pot-direct')) {
    const outcome = await runRawRung(
      '[IOS+POT*]',
      videoId,
      IOS_SPEC,
      'ios-pot-direct',
      token.kind,
      token.poToken,
      elementCompat,
      () => playerCallDirect(IOS_SPEC, videoId, ctx, token.poToken),
      { maxAttempts: DIRECT_RUNG_ATTEMPTS },
    );
    if (outcome.stream !== null) return outcome.stream;
    if (outcome.definitive) markRungFailed(videoId, 'ios-pot-direct');
    verdicts.push(`[IOS+POT*] ${outcome.verdict}`);
  }

  // ---- Rung 4: WEB + PO TOKEN (youtubei.js, dual-lane session fetch) ------
  {
    let verdict = 'not attempted';
    if (token === null) {
      verdict =
        'no PO token available (mint failed — coldstart/websafe fallbacks exhausted)';
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
              verdict = 'OK but no deciphering player (retrieve_player failed)';
            } else {
              const gvs = await gvsPoToken(videoId);
              if (gvs !== null) {
                player.po_token = gvs;
              }
              const url = await format.decipher(player);
              return toStream(videoId, info, format, url, 'web-pot-direct', token.kind);
            }
          } else {
            // SABR-only web responses still carry HLS manifests for
            // Safari-class UAs — not here; but the raw WEB shape is not
            // ours on this rung (youtubei.js parsed it). Fall through with
            // an honest inventory.
            const audioItags = (info.streaming_data?.adaptive_formats ?? [])
              .filter((f) => f.has_video !== true)
              .map((f) => f.itag ?? '?')
              .join(',');
            const hls = info.streaming_data?.hls_manifest_url;
            if (hls !== undefined && hls.length > 0 && elementCompat) {
              return toHlsStream(
                videoId,
                {
                  title: info.basic_info?.title ?? videoId,
                  artist: info.basic_info?.author ?? '',
                  durationSec:
                    typeof info.basic_info?.duration === 'number'
                      ? info.basic_info.duration
                      : null,
                },
                hls,
                'web-pot-direct',
                token.kind,
              );
            }
            verdict = `OK but no playable audio itag (want ${elementCompat ? '140' : '251/140'} — audio itags present: ${audioItags.length > 0 ? audioItags : 'NONE (SABR-only?)'})`;
          }
        } else {
          verdict = `${status}: ${info.playability_status?.reason ?? 'no reason'}`;
        }
      } catch (err) {
        verdict = err instanceof Error ? err.message : String(err);
      }
    }
    verdicts.push(`[WEB+POT*] ${verdict}`);
  }

  // ---- Rung 5: VISIONOS-RELAY (www surface through the relay) -------------
  if (rungAlive(videoId, 'visionos-relay')) {
    const outcome = await runRawRung(
      '[VISIONOS]',
      videoId,
      VISIONOS_SPEC,
      'visionos-relay',
      'none',
      null,
      elementCompat,
      async () => ({
        resp: await playerCallRelay(
          {
            clientNumber: VISIONOS_SPEC.clientNumber,
            clientName: VISIONOS_SPEC.clientName,
            clientVersion: VISIONOS_SPEC.clientVersion,
          },
          videoId,
          ctx,
          {},
        ),
      }),
    );
    if (outcome.stream !== null) return outcome.stream;
    if (outcome.definitive) markRungFailed(videoId, 'visionos-relay');
    verdicts.push(`[VISIONOS] ${outcome.verdict}`);
  }

  // ---- Rung 6: WEB_SAFARI HLS (relay + Safari UA, elementCompat only) -----
  // yt-dlp master (2026.07): web_safari HLS is only returned for logged-in
  // or "trusted" sessions — vestigial, deliberately last.
  if (elementCompat && rungAlive(videoId, 'web-safari-hls')) {
    let safariVerdict = 'not attempted';
    try {
      const player = await playerCallRelay(
        { clientNumber: '1', clientName: 'WEB', clientVersion: '2.20260708.00.00' },
        videoId,
        ctx,
        {
          uaOverride: SAFARI_UA_OVERRIDE,
          poToken: token !== null ? token.poToken : null,
        },
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
          token !== null ? token.kind : 'none',
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

  // G4.1: the session's visitor provenance rides on the diagnosis — a
  // relay-born visitor on a phone-egress session is the split-brain
  // signature, and the fix (a hard refresh) is actionable from the string.
  throw new Error(
    `no rung resolved ${videoId} — ${verdicts.join(' · ')} · visitor=${visitorProvenance()}`,
  );
}

// ---------------------------------------------------------------------------
// youtubei.js format picking (the WEB+POT rung)
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
    isSabr: false,
  };
}
