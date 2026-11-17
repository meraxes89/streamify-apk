/**
 * audio/get-stream-url.ts — P2-A: the single playback entry point.
 *
 * Contract (posted): front-end/decisions/p2/P2A-contract-getstream-url.md.
 * One object in, everything the element engine (react-player, feishin's dual
 * slots) needs out:
 *
 *   url            the live rung's URL (ladder[0] for progressive)
 *   expiresAt/     so the consumer re-resolves on expiry instead of dying
 *   resolvedAt     mid-track (wire expiresInSeconds; ~6 h default)
 *   durationMs     drives the seek bar before the player reports its own
 *   ladder         ranked codec ladder — audio-only opus/webm → m4a →
 *                  muxed video itag as the last resort; the engine walks
 *                  DOWN it on decode/buffer error, so a track never fails
 *                  on its first rung
 *   codecs         the top rung's codec descriptor
 *   rangeSupport   probed (1-byte Range request): selects the seek strategy
 *                  — in-place seek on range-capable URLs, reload-from-zero +
 *                  silent fast-forward otherwise (see the contract)
 *
 * CACHE: the last resolution per track is cached; seeks and retries hit the
 * cache, never the pipeline. Only `forceRefresh` (a reported 403/expiry)
 * re-resolves — and the consumer, not this module, decides when (never
 * mid-crossfade, per the contract).
 *
 * INSTRUMENTATION: every resolve logs its protocol/format (progressive/hls/
 * sabr, itag, codec, rangeSupport, expiry). `onLog` receives the record;
 * `recentResolveLog()` exposes the ring; the daily corpus job (CI) is the
 * reporting consumer. SABR resolves are the metric that decides the custom
 * engine checkpoint — react-player cannot parse SABR.
 *
 * ADDITIVE: this module wraps `resolveAudioStream` and never edits it. The
 * digest seam (`ResolveContext.onLadderDigest`) is the only coupling.
 *
 * W2-A — THE OPT-IN VIDEO MODE ({ mode: 'video' }): a strictly additive
 * branch. `mode` defaults to 'audio', and the audio execution path below
 * is byte-identical to the pre-W2-A module (the dispatch happens at the
 * top of getStreamUrl, before a single audio line runs). Video mode runs
 * the SAME resolveAudioStream pipeline (zero rung/timeout/header/order
 * changes — the 6-rung ladder is untouched) and only re-ranks the winning
 * rung's digest into a VIDEO ladder: muxed mp4/webm formats (video+audio
 * codecs in one URL — the element-playable class) ranked by quality. A
 * digest with no muxed format (or an hls/sabr win) throws the typed
 * StreamifyVideoModeMissError — the caller degrades cleanly (the toggle
 * hides; audio keeps playing).
 */
import { gvsPoToken } from './botguard/potSession';
import { ChunkFetcher } from './chunkFetcher';
import {
  resolveAudioStream,
  type LadderDigest,
  type LadderRung,
} from './resolve';

// Re-exported so consumers (the feishin bridge) can type against this module
// alone, without importing the pipeline directly.
export type { LadderDigest, LadderDigestSink, LadderRung, ResolvedStream } from './resolve';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/** One rung of the ranked codec ladder (rank 0 is live). */
export interface StreamUrlRung {
  /** 0 = the live rung; the engine walks up on error. */
  rank: number;
  itag: number;
  url: string;
  mimeType: string;
  /** audio/* formats only (false = the muxed video itag, the last resort). */
  audioOnly: boolean;
  /** 'opus' | 'aac' | 'avc1+aac' | 'video+opus' (descriptor, not a mime). */
  codec: string;
  bitrate: number;
  contentLength: number | null;
  /** W1-B (honest badges): the wire's channel count when the format
   *  carried one (2 = stereo, 6 = 5.1); null = unknown — consumers MUST
   *  treat null as "cannot claim spatial", never guess. */
  audioChannels: number | null;
}

/** Everything the playback engine needs for one track, in one object. */
export interface StreamUrlResolution {
  trackId: string;
  /** The live URL: ladder[0] for progressive, the manifest for hls, the
   *  serverAbrStreamingUrl for sabr (NOT element-consumable — honest state). */
  url: string;
  protocol: 'progressive' | 'hls' | 'sabr';
  /** Ranked ladder (progressive only; empty for hls/sabr — no walk exists). */
  ladder: StreamUrlRung[];
  /** The live rung's codec descriptor. */
  codecs: string;
  /** Epoch ms. Together with expiresAt: re-resolve on expiry, at the next
   *  natural boundary — never mid-track (an open media request outlives it). */
  resolvedAt: number;
  /** Epoch ms. From the wire expiresInSeconds when present (~6 h), else the
   *  documented default. */
  expiresAt: number;
  expirySource: 'wire' | 'default';
  /** Pipeline duration — drives the seek bar before the player reports its
   *  own. null when the wire carried none. */
  durationMs: number | null;
  /** Probed 1-byte Range answer for the live rung's URL. true = in-place
   *  seek (Range request on the same URL); false = reload-from-zero + silent
   *  fast-forward; null = not applicable (hls/sabr) or the probe itself
   *  failed — consumers treat null as the conservative (false) strategy. */
  rangeSupport: boolean | null;
  contentLength: number | null;
  title: string;
  artist: string;
  rung: LadderRung;
  isSabr: boolean;
  /** W1-B (honest badges): the live rung's channel count (null = the
   *  wire did not say — the Spatial badge stays suppressed). */
  audioChannels: number | null;
}

/** The instrumentation record — one per resolve (never per cache hit). */
export interface ResolveLogRecord {
  trackId: string;
  at: string;
  protocol: 'progressive' | 'hls' | 'sabr';
  rung: LadderRung;
  itag: number;
  codec: string;
  audioOnly: boolean;
  ladderDepth: number;
  rangeSupport: boolean | null;
  durationMs: number | null;
  expiresInSeconds: number | null;
  expirySource: 'wire' | 'default';
  sabr: boolean;
  title: string;
}

export interface GetStreamUrlOptions {
  /** Relay base (the www-surface rungs + relay fallbacks) — same knob the
   *  spine passes; required, like ResolveContext.relayBase. */
  relayBase: string;
  signal?: AbortSignal | null;
  autoMint?: boolean;
  /** Bypass the cache and re-resolve (a reported 403/expiry — the consumer
   *  calls this, never mid-crossfade). */
  forceRefresh?: boolean;
  /** Injectable probe fetch (tests / Node adapters). Defaults to global. */
  probeImpl?: typeof fetch;
  /** Telemetry sink — one record per resolve. */
  onLog?: (record: ResolveLogRecord) => void;
  /**
   *  W2-A (additive, default 'audio'): 'video' resolves the official
   *  music-video counterpart's playable stream — muxed mp4/webm
   *  (video+audio in one URL) or an adaptive format carrying both codec
   *  families. The default audio rungs, timeouts, headers, and fallback
   *  orders are 100% unchanged; this flag only re-ranks the SAME winning
   *  rung's formats into a video-first ladder.
   */
  mode?: 'audio' | 'video';
}

/**
 * W2-A: the video mode found nothing element-playable — no muxed
 * (video+audio) format on the winning rung, or the rung won on a protocol
 * the element engine cannot parse for video (hls/sabr). Typed so callers
 * degrade cleanly (hide the toggle, keep audio playing) instead of
 * guessing at error strings.
 */
export class StreamifyVideoModeMissError extends Error {
  constructor(videoId: string, detail: string) {
    super(`streamify: video mode miss for ${videoId} (${detail})`);
    this.name = 'StreamifyVideoModeMissError';
  }
}

/** The outcome of a URL health probe (the walk/stale classifier). */
export type ProbeOutcome =
  | { ok: true; acceptsRanges: boolean; contentLength: number; resolvedUrl: string }
  | { ok: false; status: number | null; error: string };

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/** ~6 h — the measured googlevideo URL lifetime (21540 s on the wire). */
const DEFAULT_TTL_MS = 6 * 60 * 60 * 1000;
const LOG_RING_MAX = 50;

const resolutionCache = new Map<string, StreamUrlResolution>();
const inflight = new Map<string, Promise<StreamUrlResolution>>();
const logRing: ResolveLogRecord[] = [];

/** An unranked candidate straight off the digest. */
interface Candidate {
  tier: 0 | 1 | 2;
  itag: number;
  url: string;
  mimeType: string;
  audioOnly: boolean;
  codec: string;
  bitrate: number;
  contentLength: number | null;
  audioChannels: number | null;
}

/** W1-B: the wire channel count, honestly — anything not a positive
 *  finite number is "unknown" (null), never a guess. Exported for the
 *  network-free badge suites. */
export function parseAudioChannels(raw: unknown): number | null {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) return null;
  return Math.round(raw);
}

/** Mime → element-ladder tier. null = not element-playable. */
function classifyMime(
  mimeType: string,
): { tier: 0 | 1 | 2; codec: string; audioOnly: boolean } | null {
  const m = mimeType.toLowerCase();
  if (m.startsWith('audio/')) {
    if (m.includes('opus')) return { tier: 0, codec: 'opus', audioOnly: true };
    if (m.includes('mp4a') || m.includes('aac')) return { tier: 1, codec: 'aac', audioOnly: true };
    // Unknown audio container: tier 1 by default (element-decodable audio).
    return { tier: 1, codec: m.split(';')[0] || 'audio', audioOnly: true };
  }
  if (m.startsWith('video/')) {
    const hasAudio = m.includes('mp4a') || m.includes('aac') || m.includes('opus');
    if (!hasAudio) return null;
    return {
      tier: 2,
      codec: m.includes('opus') ? 'video+opus' : 'avc1+aac',
      audioOnly: false,
    };
  }
  return null;
}

/** Best-effort `&pot=` pin — the G4.2 rule, applied uniformly. */
async function pinUrl(url: string, videoId: string): Promise<string> {
  if (!url.includes('googlevideo.com/')) return url;
  if (url.includes('pot=')) return url;
  try {
    const gvs = await gvsPoToken(videoId);
    if (gvs === null || gvs.length === 0) return url;
    return `${url}&pot=${encodeURIComponent(gvs)}`;
  } catch {
    return url; // best-effort — the bare URL stays
  }
}

/** Candidates from a raw PlayerResponse (the app/relay rungs — literal URLs). */
function candidatesFromResp(digest: LadderDigest & { kind: 'progressive' }): Candidate[] {
  const resp = digest.resp;
  if (resp === undefined) return [];
  const formats = [
    ...(resp.streamingData?.adaptiveFormats ?? []),
    ...(resp.streamingData?.formats ?? []),
  ];
  const out: Candidate[] = [];
  for (const f of formats) {
    if (typeof f.url !== 'string' || f.url.length === 0) continue;
    if (typeof f.mimeType !== 'string' || f.mimeType.length === 0) continue;
    const cls = classifyMime(f.mimeType);
    if (cls === null) continue;
    out.push({
      tier: cls.tier,
      itag: f.itag ?? 0,
      url: f.url,
      mimeType: f.mimeType,
      audioOnly: cls.audioOnly,
      codec: cls.codec,
      bitrate: typeof f.bitrate === 'number' ? f.bitrate : 0,
      contentLength:
        typeof f.contentLength === 'number'
          ? f.contentLength
          : typeof f.contentLength === 'string' && f.contentLength !== ''
            ? Number(f.contentLength)
            : null,
      // The raw PlayerResponse carries audioChannels on audio formats.
      audioChannels: parseAudioChannels((f as { audioChannels?: unknown }).audioChannels),
    });
  }
  return out;
}

/** Candidates from a youtubei.js basic info (the WEB+POT rung — deciphered). */
async function candidatesFromInfo(
  digest: LadderDigest & { kind: 'progressive' },
): Promise<Candidate[]> {
  const info = digest.info;
  const player = digest.player;
  if (info === undefined) return [];
  const formats = [
    ...(info.streaming_data?.adaptive_formats ?? []),
    ...(info.streaming_data?.formats ?? []),
  ];
  const out: Candidate[] = [];
  for (const f of formats) {
    // Audio-only: has_video !== true. Muxed: has_video && has_audio — the
    // itag-18 class, the element ladder's last resort.
    const audioOnly = f.has_video !== true;
    const muxedWithAudio = f.has_video === true && f.has_audio === true;
    if (!audioOnly && !muxedWithAudio) continue;
    const mime = typeof f.mime_type === 'string' ? f.mime_type : '';
    const cls = classifyMime(mime);
    if (cls === null) continue;
    let url: string;
    if (typeof f.url === 'string' && f.url.length > 0) {
      url = f.url;
    } else if (player !== undefined && typeof f.decipher === 'function') {
      try {
        url = await f.decipher(player);
      } catch {
        continue; // this rung's URL is undecipherable — skip it honestly
      }
    } else {
      continue;
    }
    if (typeof url !== 'string' || url.length === 0) continue;
    out.push({
      tier: cls.tier,
      itag: f.itag ?? 0,
      url,
      mimeType: mime,
      audioOnly: cls.audioOnly,
      codec: cls.codec,
      bitrate: typeof f.bitrate === 'number' ? f.bitrate : 0,
      contentLength: typeof f.content_length === 'number' ? f.content_length : null,
      // youtubei.js maps the payload defensively: read whichever naming
      // the installed build exposes; absent = unknown (null), never a guess.
      audioChannels: parseAudioChannels(
        (f as unknown as Record<string, unknown>).audio_channels ??
          (f as unknown as Record<string, unknown>).audioChannels,
      ),
    });
  }
  return out;
}

/** Rank: tier asc, then bitrate desc; keep the best of each tier (the
 *  W1.2-derived element ladder: opus/webm → m4a → muxed video itag). */
function rankToLadder(candidates: Candidate[]): Candidate[] {
  const sorted = [...candidates].sort((a, b) => a.tier - b.tier || b.bitrate - a.bitrate);
  const seen = new Set<number>();
  const out: Candidate[] = [];
  for (const c of sorted) {
    if (seen.has(c.tier)) continue;
    seen.add(c.tier);
    out.push(c);
  }
  return out;
}

/** The wire expiry in seconds, when the digest carried it. */
function wireExpiresInSeconds(digest: LadderDigest | undefined): number | null {
  if (digest === undefined) return null;
  const raw =
    digest.kind === 'progressive' && digest.resp !== undefined
      ? digest.resp.streamingData?.expiresInSeconds
      : digest.kind === 'progressive' && digest.info !== undefined
        ? digest.info.streaming_data?.expires_in_seconds
        : undefined;
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
}

// ---------------------------------------------------------------------------
// Probe
// ---------------------------------------------------------------------------

/** Probe a stream URL's health + range support (the walk/stale classifier).
 *  One 1-byte Range request, direct (the element fetches direct too — the
 *  googlevideo media CDN is CORS-open by design). */
export async function probeStreamUrl(
  url: string,
  probeImpl?: typeof fetch,
): Promise<ProbeOutcome> {
  try {
    const fetcher = new ChunkFetcher({
      relayBase: null,
      clientName: null,
      fetchImpl: probeImpl ?? fetch,
    });
    const probe = await fetcher.probe(url);
    return {
      ok: true,
      acceptsRanges: probe.acceptsRanges,
      contentLength: probe.contentLength,
      resolvedUrl: probe.resolvedUrl,
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    const statusMatch = /probe failed: (\d+)/.exec(message);
    return {
      ok: false,
      status: statusMatch !== null ? Number(statusMatch[1]) : null,
      error: message,
    };
  }
}

// ---------------------------------------------------------------------------
// The entry point
// ---------------------------------------------------------------------------

export async function getStreamUrl(
  trackId: string,
  opts: GetStreamUrlOptions,
): Promise<StreamUrlResolution> {
  // W2-A: the opt-in video branch — dispatched BEFORE the audio path runs;
  // the audio execution below this guard is byte-identical to pre-W2-A.
  if (opts.mode === 'video') {
    return getVideoStreamUrl(trackId, opts);
  }
  const forceRefresh = opts.forceRefresh === true;
  const now = Date.now();

  // 1. Cache hit (fresh) — seeks and retries land here, never on the pipeline.
  const cached = resolutionCache.get(trackId);
  if (!forceRefresh && cached !== undefined && now < cached.expiresAt) {
    return cached;
  }
  if (cached !== undefined && now >= cached.expiresAt) {
    // Expiry is the one cache-invalidating event (per the contract).
    resolutionCache.delete(trackId);
  }

  // 2. In-flight dedup — concurrent callers share one resolve.
  if (!forceRefresh && inflight.has(trackId)) {
    return inflight.get(trackId) as Promise<StreamUrlResolution>;
  }

  const run = (async (): Promise<StreamUrlResolution> => {
    // 3. One pipeline pass, element posture (W2.0), digest captured.
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream(trackId, {
      relayBase: opts.relayBase,
      signal: opts.signal ?? null,
      autoMint: opts.autoMint,
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });

    const protocol: StreamUrlResolution['protocol'] = stream.isSabr
      ? 'sabr'
      : stream.isHls
        ? 'hls'
        : 'progressive';

    // 4. Build the ranked element ladder from the winning rung's formats.
    let ranked: Candidate[] = [];
    if (protocol === 'progressive' && digest?.kind === 'progressive') {
      const raw =
        digest.resp !== undefined
          ? candidatesFromResp(digest)
          : await candidatesFromInfo(digest);
      ranked = rankToLadder(raw);
      // Best-effort &pot= pin on every rung (G4.2, uniform with the top).
      const pinned = await Promise.all(ranked.map((c) => pinUrl(c.url, trackId)));
      ranked = ranked.map((c, i) => ({ ...c, url: pinned[i] as string }));
    }

    const top = ranked[0];
    const url = top !== undefined ? top.url : stream.url;
    const codecs =
      top !== undefined
        ? top.codec
        : protocol === 'hls'
          ? 'aac'
          : protocol === 'sabr'
            ? 'sabr'
            : (stream.codec as string);

    // 5. Expiry: the wire value when present, the documented default else.
    const expiresInSeconds = wireExpiresInSeconds(digest);
    const resolvedAt = Date.now();
    const ttlMs = expiresInSeconds !== null ? expiresInSeconds * 1000 : DEFAULT_TTL_MS;
    const expiresAt = resolvedAt + ttlMs;
    const expirySource: 'wire' | 'default' = expiresInSeconds !== null ? 'wire' : 'default';

    // 6. Range probe on the live URL (progressive only; hls/sabr: n/a).
    let rangeSupport: boolean | null = null;
    let probeLength: number | null = top?.contentLength ?? stream.contentLength;
    if (protocol === 'progressive') {
      const probe = await probeStreamUrl(url, opts.probeImpl);
      if (probe.ok) {
        rangeSupport = probe.acceptsRanges;
        if (probe.contentLength > 0) probeLength = probe.contentLength;
      } else {
        rangeSupport = null; // probe failed — unknown; conservative consumers read false
      }
    }

    const durationMs =
      stream.durationSec !== null && stream.durationSec !== undefined
        ? Math.round(stream.durationSec * 1000)
        : (stream.sabr?.durationMs ?? null);

    const resolution: StreamUrlResolution = {
      trackId,
      url,
      protocol,
      ladder: ranked.map((c, i) => ({
        rank: i,
        itag: c.itag,
        url: c.url,
        mimeType: c.mimeType,
        audioOnly: c.audioOnly,
        codec: c.codec,
        bitrate: c.bitrate,
        contentLength: c.contentLength,
        audioChannels: c.audioChannels,
      })),
      codecs,
      resolvedAt,
      expiresAt,
      expirySource,
      durationMs,
      rangeSupport,
      contentLength: probeLength,
      title: stream.title,
      artist: stream.artist,
      rung: stream.rung,
      isSabr: stream.isSabr,
      audioChannels: top?.audioChannels ?? null,
    };

    // 7. Instrumentation — one record per resolve, protocol + format.
    const record: ResolveLogRecord = {
      trackId,
      at: new Date().toISOString(),
      protocol,
      rung: stream.rung,
      itag: top?.itag ?? stream.itag,
      codec: codecs,
      audioOnly: top?.audioOnly ?? (protocol === 'progressive'),
      ladderDepth: resolution.ladder.length,
      rangeSupport,
      durationMs,
      expiresInSeconds,
      expirySource,
      sabr: stream.isSabr,
      title: stream.title,
    };
    logRing.push(record);
    if (logRing.length > LOG_RING_MAX) logRing.splice(0, logRing.length - LOG_RING_MAX);
    // eslint-disable-next-line no-console
    console.debug(
      `[getStreamUrl] ${trackId} ${protocol} rung=${stream.rung} itag=${record.itag} ` +
        `codec=${codecs} ladder=${resolution.ladder.length} range=${String(rangeSupport)} ` +
        `expiresIn=${expiresInSeconds !== null ? `${expiresInSeconds}s` : 'default'} ` +
        `ttl=${expirySource}${stream.isSabr ? ' SABR-HIT' : ''}`,
    );
    opts.onLog?.(record);

    resolutionCache.set(trackId, resolution);
    return resolution;
  })();

  if (!forceRefresh) inflight.set(trackId, run);
  try {
    return await run;
  } finally {
    if (!forceRefresh) inflight.delete(trackId);
  }
}

// ---------------------------------------------------------------------------
// W2-A — the opt-in video branch (additive; every line above is untouched)
// ---------------------------------------------------------------------------

/**
 * The video cache namespace: the SAME Map, a distinct key — the audio and
 * the video resolution for one videoId are two different answers and must
 * never alias (a video toggle that served the audio ladder would be a
 * silent wrong-track bug). '\u0000' cannot appear in a videoId.
 */
function videoCacheKey(trackId: string): string {
  return `${trackId}\u0000video`;
}

/**
 * W2-A: resolve the element-playable VIDEO stream for a videoId (the
 * official-music-video counterpart). Runs the SAME resolveAudioStream
 * pipeline — the 6-rung ladder, its timeouts, headers, and fallback orders
 * are exactly the audio path's (the digest seam hands us every format the
 * winning rung saw) — then re-ranks into a video-first ladder: muxed
 * mp4/webm formats (video+audio codecs in ONE URL — the only class a
 * single <video> element plays without MSE), highest quality first.
 *
 * Misses are typed (StreamifyVideoModeMissError): no muxed format, or the
 * rung won on hls/sabr (no element-consumable video URL exists) — the
 * caller degrades cleanly and audio keeps playing.
 */
async function getVideoStreamUrl(
  trackId: string,
  opts: GetStreamUrlOptions,
): Promise<StreamUrlResolution> {
  const key = videoCacheKey(trackId);
  const forceRefresh = opts.forceRefresh === true;
  const now = Date.now();

  const cached = resolutionCache.get(key);
  if (!forceRefresh && cached !== undefined && now < cached.expiresAt) {
    return cached;
  }
  if (cached !== undefined && now >= cached.expiresAt) {
    resolutionCache.delete(key);
  }
  if (!forceRefresh && inflight.has(key)) {
    return inflight.get(key) as Promise<StreamUrlResolution>;
  }

  const run = (async (): Promise<StreamUrlResolution> => {
    // One pipeline pass — identical posture to the audio resolve; the
    // digest captures the winning rung's raw formats.
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream(trackId, {
      relayBase: opts.relayBase,
      signal: opts.signal ?? null,
      autoMint: opts.autoMint,
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });

    const protocol: StreamUrlResolution['protocol'] = stream.isSabr
      ? 'sabr'
      : stream.isHls
        ? 'hls'
        : 'progressive';

    if (protocol !== 'progressive' || digest?.kind !== 'progressive') {
      // hls/sabr carry no element-consumable single video URL — honest miss.
      throw new StreamifyVideoModeMissError(trackId, `${protocol} rung has no element-playable video formats`);
    }

    const raw =
      digest.resp !== undefined
        ? candidatesFromResp(digest)
        : await candidatesFromInfo(digest);
    // The VIDEO ladder: muxed formats only (tier 2 — classifyMime already
    // demanded an audio codec inside the video container), quality first.
    const muxed = raw
      .filter((c) => c.tier === 2 && !c.audioOnly)
      .sort((a, b) => b.bitrate - a.bitrate);
    if (muxed.length === 0) {
      throw new StreamifyVideoModeMissError(
        trackId,
        'no muxed video+audio format on the winning rung',
      );
    }

    // Best-effort &pot= pin on every rung (G4.2, uniform with the audio path).
    const pinned = await Promise.all(muxed.map((c) => pinUrl(c.url, trackId)));
    const ranked = muxed.map((c, i) => ({ ...c, url: pinned[i] as string }));
    const top = ranked[0];
    const url = top.url;

    const expiresInSeconds = wireExpiresInSeconds(digest);
    const resolvedAt = Date.now();
    const ttlMs = expiresInSeconds !== null ? expiresInSeconds * 1000 : DEFAULT_TTL_MS;
    const expiresAt = resolvedAt + ttlMs;
    const expirySource: 'wire' | 'default' = expiresInSeconds !== null ? 'wire' : 'default';

    // Range probe on the live video URL (same classifier as the audio top).
    let rangeSupport: boolean | null = null;
    let probeLength: null | number = top.contentLength;
    const probe = await probeStreamUrl(url, opts.probeImpl);
    if (probe.ok) {
      rangeSupport = probe.acceptsRanges;
      if (probe.contentLength > 0) probeLength = probe.contentLength;
    }

    const durationMs =
      stream.durationSec !== null && stream.durationSec !== undefined
        ? Math.round(stream.durationSec * 1000)
        : (stream.sabr?.durationMs ?? null);

    const resolution: StreamUrlResolution = {
      trackId,
      url,
      protocol,
      ladder: ranked.map((c, i) => ({
        rank: i,
        itag: c.itag,
        url: c.url,
        mimeType: c.mimeType,
        audioOnly: false, // every video rung carries its own audio track
        codec: c.codec,
        bitrate: c.bitrate,
        contentLength: c.contentLength,
        // W1-B's honest channel count rides the video rungs too (a muxed
        // format's audioChannels is the wire's own claim, null = unknown).
        audioChannels: c.audioChannels,
      })),
      codecs: top.codec,
      resolvedAt,
      expiresAt,
      expirySource,
      durationMs,
      rangeSupport,
      contentLength: probeLength,
      title: stream.title,
      artist: stream.artist,
      rung: stream.rung,
      isSabr: false,
      audioChannels: top.audioChannels,
    };

    // Instrumentation — one record per video resolve, video-marked.
    const record: ResolveLogRecord = {
      trackId,
      at: new Date().toISOString(),
      protocol,
      rung: stream.rung,
      itag: top.itag,
      codec: top.codec,
      audioOnly: false,
      ladderDepth: resolution.ladder.length,
      rangeSupport,
      durationMs,
      expiresInSeconds,
      expirySource,
      sabr: false,
      title: stream.title,
    };
    logRing.push(record);
    if (logRing.length > LOG_RING_MAX) logRing.splice(0, logRing.length - LOG_RING_MAX);
    // eslint-disable-next-line no-console
    console.debug(
      `[getStreamUrl] ${trackId} VIDEO ${protocol} rung=${stream.rung} itag=${top.itag} ` +
        `codec=${top.codec} ladder=${resolution.ladder.length} range=${String(rangeSupport)}`,
    );
    opts.onLog?.(record);

    resolutionCache.set(key, resolution);
    return resolution;
  })();

  if (!forceRefresh) inflight.set(key, run);
  try {
    return await run;
  } finally {
    if (!forceRefresh) inflight.delete(key);
  }
}

/**
 * W2-A: the cached VIDEO resolution, if any (the toggle's expiry check —
 * separate from the audio peek by the video namespace key).
 */
export function peekVideoStreamUrl(trackId: string): StreamUrlResolution | null {
  return resolutionCache.get(videoCacheKey(trackId)) ?? null;
}

/** The cached resolution, if any — no resolving (the consumer's expiry check). */
export function peekStreamUrl(trackId: string): StreamUrlResolution | null {
  return resolutionCache.get(trackId) ?? null;
}

/** Drop the cached resolution (a reported 403/stale/expiry). The next
 *  getStreamUrl re-resolves. Never resolves here. */
export function invalidateStreamUrl(trackId: string, reason: string): boolean {
  const had = resolutionCache.delete(trackId);
  // eslint-disable-next-line no-console
  console.debug(`[getStreamUrl] invalidated ${trackId} (${reason})`);
  return had;
}

/** The instrumentation ring (the last LOG_RING_MAX resolve records). */
export function recentResolveLog(): readonly ResolveLogRecord[] {
  return logRing;
}

/** Test/diagnostics hook: drop every cache entry and the log ring. */
export function clearStreamUrlCache(): void {
  resolutionCache.clear();
  inflight.clear();
  logRing.splice(0, logRing.length);
}
