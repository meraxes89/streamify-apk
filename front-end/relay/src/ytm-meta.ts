/**
 * relay/ytm-meta.ts — W1-A: the Edge KV metadata cache route (/ytm/meta).
 *
 * WHY THIS EXISTS (the W1-A architecture —
 * front-end/decisions/waves/W1A-edge-kv-metadata-importer.md):
 *   All *public* InnerTube browse/metadata traffic routes through this
 *   Worker as a global cache, replacing the rejected Supabase `ytm_cache`
 *   idea. ONLY this worker writes the cache, and only after re-fetching
 *   the data from YouTube itself and validating the response server-side —
 *   a browser can at most probe the cache with different keys, never
 *   plant content in it (the client-writable poisoning vector the
 *   discussion rejected, one tier lower).
 *
 * THE PIPELINE (per request):
 *   1. parse + validate params, build the cache key
 *   2. memory layer lookup → (miss) KV layer lookup (`env.YTM_CACHE`,
 *      absent in local dev / CI → bypassed cleanly, never thrown)
 *   3. hit + fresh  → serve (state 'fresh')
 *      hit + stale  → serve NOW (state 'stale') + background revalidation
 *                     via ctx.waitUntil, but ONLY when the endpoint's SWR
 *                     policy says the payload is worth refreshing (the
 *                     lyrics rule: a fully-synced payload is terminal)
 *      miss         → single-flight coalesced upstream fill (state 'fill'):
 *                     identical concurrent requests share ONE fetch
 *
 * THE UPSTREAM (server-side InnerTube, dependency-free):
 *   Raw innertube POSTs against https://www.youtube.com/youtubei/v1/* —
 *   the same wire contract youtubei.js 18.3.0 issues (the version feishin
 *   pins). The extraction walkers below are ported from youtubei.js's own
 *   parsers, and every response shape in this file was verified live
 *   against production innertube on 2026-11-16 (the probe record lives in
 *   the W1-A ADR). A best-effort music.youtube.com homepage session
 *   (visitorData + client version, cached 1h per isolate) mirrors the
 *   /bg-session discipline; failure degrades to the constant client
 *   version — the calls are functional without a visitor id (measured,
 *   same as the probes).
 *
 * THE TTL MATRIX (enforced; ADR §B):
 *   lyrics / credits / chapters ..... 7 days (serve stale; background
 *                                     revalidate ONLY if payload empty or
 *                                     (lyrics) synced === false)
 *   artist / album / counterpart .... 24 h   (SWR; counterpart = the W2-A
 *                                     official-music-video resolution)
 *   explore / home .................. 6 h   (SWR)
 *   up-next / related .............. 12 h   (SWR)
 *   search / search-suggest ....... 15 min  (SWR)
 *   playlist ...................... 12 h    (SWR; the documented choice for
 *                                     the two classes the task matrix left
 *                                     unlisted)
 *
 * STRICT RULES:
 *   • Every cache key encodes (endpoint, id_or_query, gl, hl) — regional
 *     charts and localized titles never bleed across countries/languages.
 *   • Normalized lyrics payloads carry an explicit `synced: boolean` —
 *     timestamped ([mm:ss.xx]) lines set it true, so any later LRCLIB-tier
 *     consumer can rank synced above plain text.
 *   • Upstream JSON > 2 MiB is served but never cached (the negative-cache
 *     'too-large' precedent). Unparseable upstream JSON is a 502, never
 *     cached, never served.
 *   • An empty-normalized result caches as `missing: true` (the honest
 *     not-found form the provider maps to its typed not-found result).
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** The KV binding (absent in local dev / CI — bypassed, never thrown). */
export interface YtmEnv {
  YTM_CACHE?: KVNamespace;
}

export type YtmEndpoint =
  | 'lyrics'
  | 'credits'
  | 'chapters'
  | 'counterpart'
  | 'artist'
  | 'album'
  | 'explore'
  | 'home'
  | 'up-next'
  | 'related'
  | 'search'
  | 'search-suggest'
  | 'playlist';

const ALL_ENDPOINTS: readonly YtmEndpoint[] = [
  'lyrics',
  'credits',
  'chapters',
  'counterpart',
  'artist',
  'album',
  'explore',
  'home',
  'up-next',
  'related',
  'search',
  'search-suggest',
  'playlist',
];

export interface YtmMetaParams {
  continuation?: string;
  filter?: 'albums' | 'artists' | 'playlists' | 'songs' | 'videos';
  /** browseId / videoId / seedVideoId — the id half of the key identity. */
  id?: string;
  /** The query half (search / search-suggest). */
  query?: string;
  gl: string;
  hl: string;
  endpoint: YtmEndpoint;
}

// ---------------------------------------------------------------------------
// Normalized payload schemas (mirrored client-side in feishin's
// metadata/types.ts — the SCHEMA is the shared contract, not the code).
// ---------------------------------------------------------------------------

export interface LyricsPayload {
  hl: string;
  kind: 'lyrics';
  /** atMs null = unsynced line (plain text). */
  lines: Array<{ atMs: null | number; text: string }>;
  source: string;
  synced: boolean;
  videoId: string;
}

export interface CreditsPayload {
  hl: string;
  kind: 'credits';
  roleGroups: Array<{
    credits: Array<{ name: string; subtitle: null | string }>;
    title: string;
  }>;
  videoId: string;
}

export interface ChaptersPayload {
  /** W2-A contract: the normalized chapter row — startSeconds required,
   * endSeconds derived from the next marker (null on the last), thumbnail
   * from the marker's own thumbnail set (null when the wire carried none).
   * atMs is kept (== startSeconds * 1000) so entries cached by a W1-A-era
   * worker stay readable — the v1 envelope predates the richer shape. */
  chapters: Array<{
    atMs: number;
    endSeconds: null | number;
    startSeconds: number;
    thumbnailUrl: null | string;
    title: string;
  }>;
  hl: string;
  kind: 'chapters';
  videoId: string;
}

/**
 * W2-A: the official-music-video counterpart of an audio track.
 * videoId null = no distinct official video found (the toggle hides);
 * confidence 'strong' = normalized title match + artist line + duration
 * proximity all held, 'weak' = title matched on weaker evidence.
 */
export interface CounterpartPayload {
  artistText: null | string;
  confidence: null | 'strong' | 'weak';
  durationMs: null | number;
  kind: 'counterpart';
  /** The matched video's title (null when videoId is null). */
  title: null | string;
  /** The audio track's videoId the resolution ran for. */
  sourceVideoId: string;
  /** The official music video's videoId, or null when none matched. */
  videoId: null | string;
}

export interface ArtistPayload {
  albums: Array<{ browseId: string; subtitle: null | string; title: string }>;
  browseId: string;
  description: null | string;
  gl: string;
  hl: string;
  kind: 'artist';
  listenersText: null | string;
  name: string;
  topSongs: Array<{ artistText: null | string; title: string; videoId: string }>;
}

export interface AlbumPayload {
  artistText: null | string;
  browseId: string;
  gl: string;
  hl: string;
  kind: 'album';
  title: string;
  trackCountTotal: null | number;
  tracks: Array<{ durationMs: null | number; title: string; videoId: null | string }>;
  year: null | string;
}

export interface ShelfItemPayload {
  browseId: null | string;
  pageType: null | string;
  subtitle: null | string;
  thumbnail: null | string;
  title: string;
  videoId: null | string;
}

export type ExploreHomeKind = 'explore' | 'home';

export interface ExploreHomePayload {
  gl: string;
  hl: string;
  kind: ExploreHomeKind;
  shelves: Array<{ items: ShelfItemPayload[]; title: string }>;
}

export interface UpNextPayload {
  kind: 'up-next';
  seedVideoId: string;
  tracks: Array<{
    artistText: null | string;
    durationMs: null | number;
    title: string;
    videoId: string;
  }>;
}

export interface RelatedPayload {
  kind: 'related';
  seedVideoId: string;
  shelves: Array<{ items: ShelfItemPayload[]; title: string }>;
}

export interface SearchPayload {
  continuation: null | string;
  kind: 'search';
  filter: null | string;
  query: string;
  results: Array<{
    durationMs: null | number;
    subtitle: null | string;
    title: string;
    videoId: null | string;
  }>;
}

export interface SearchSuggestPayload {
  input: string;
  kind: 'search-suggest';
  suggestions: string[];
}

export interface PlaylistTrack {
  albumText: null | string;
  artistText: null | string;
  durationMs: null | number;
  index: number;
  title: string;
  videoId: null | string;
}

export interface PlaylistPayload {
  continuation: null | string;
  description: null | string;
  kind: 'playlist';
  listId: string;
  title: null | string;
  trackCountTotal: null | number;
  tracks: PlaylistTrack[];
}

export type YtmPayload =
  | AlbumPayload
  | ArtistPayload
  | ChaptersPayload
  | CounterpartPayload
  | CreditsPayload
  | ExploreHomePayload
  | LyricsPayload
  | PlaylistPayload
  | RelatedPayload
  | SearchPayload
  | SearchSuggestPayload
  | UpNextPayload;

/** The cache envelope (memory + KV store the same shape). */
export interface YtmEnvelope {
  cachedAt: number;
  endpoint: YtmEndpoint;
  /** Envelope key identity (KV round-trips lose nothing). */
  key: string;
  expiresAt: number;
  /** true when the payload normalized to empty — the not-found form. */
  missing: boolean;
  payload: YtmPayload | null;
  v: 1;
}

// ---------------------------------------------------------------------------
// Key + TTL
// ---------------------------------------------------------------------------

/**
 * FNV-1a 64-bit over a string → 16-char lowercase hex. Bounded keys for
 * unbounded continuation tokens (same digest discipline as the CRDT cad
 * hasher); deliberately dependency-free and deterministic.
 */
export function fnv1a64(value: string): string {
  let hash = 0xcbf29ce484222325n;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= BigInt(value.charCodeAt(i));
    hash = (hash * 0x100000001b3n) & 0xffffffffffffffffn;
  }
  return hash.toString(16).padStart(16, '0');
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** The TTL matrix (ms). Exported for the relay test lane. */
export function ttlForEndpoint(endpoint: YtmEndpoint): number {
  switch (endpoint) {
    case 'lyrics':
    case 'credits':
    case 'chapters':
      return 7 * DAY_MS;
    case 'artist':
    case 'album':
    case 'counterpart':
      return DAY_MS;
    case 'explore':
    case 'home':
      return 6 * 60 * 60 * 1000;
    case 'up-next':
    case 'related':
    case 'playlist':
      return 12 * 60 * 60 * 1000;
    case 'search':
    case 'search-suggest':
      return 15 * 60 * 1000;
  }
}

/**
 * The cache key: `ytm/v1/<endpoint>/<gl>/<hl>/<urlencoded id-or-query>`
 * (+ `/<fnv1a64(continuation)>` when paginating). The defaults (US/en) are
 * PART of the key, never implicit — regional charts and localized titles
 * stay isolated per country/language.
 */
export function buildYtmCacheKey(
  endpoint: YtmEndpoint,
  idOrQuery: string,
  gl: string,
  hl: string,
  continuation?: string,
): string {
  const base = `ytm/v1/${endpoint}/${gl || 'US'}/${hl || 'en'}/${encodeURIComponent(idOrQuery)}`;
  if (continuation !== undefined && continuation !== '') {
    return `${base}/${fnv1a64(continuation)}`;
  }
  return base;
}

// ---------------------------------------------------------------------------
// Request params
// ---------------------------------------------------------------------------

const FILTERS = new Set(['songs', 'videos', 'albums', 'artists', 'playlists']);

const ID_ENDPOINTS = new Set<YtmEndpoint>([
  'lyrics',
  'credits',
  'chapters',
  'counterpart',
  'artist',
  'album',
  'up-next',
  'related',
  'playlist',
]);

export type YtmParamResult = { error: string; ok: false } | { ok: true; params: YtmMetaParams };

export function parseYtmMetaParams(url: URL): YtmParamResult {
  const endpoint = url.searchParams.get('endpoint') ?? '';
  if (!ALL_ENDPOINTS.includes(endpoint as YtmEndpoint)) {
    return { error: `unknown endpoint: ${endpoint}`, ok: false };
  }
  const ep = endpoint as YtmEndpoint;
  const gl = (url.searchParams.get('gl') ?? 'US').trim().slice(0, 8) || 'US';
  const hl = (url.searchParams.get('hl') ?? 'en').trim().slice(0, 16) || 'en';
  const id = url.searchParams.get('id') ?? undefined;
  const query = url.searchParams.get('q') ?? undefined;
  const continuationRaw = url.searchParams.get('continuation') ?? undefined;
  const continuation = continuationRaw === '' ? undefined : continuationRaw;
  const filterRaw = url.searchParams.get('filter') ?? undefined;
  const filter =
    filterRaw !== undefined && FILTERS.has(filterRaw)
      ? (filterRaw as YtmMetaParams['filter'])
      : undefined;

  if (ID_ENDPOINTS.has(ep) && (id ?? '').trim() === '') {
    return { error: `endpoint ${ep} requires ?id=`, ok: false };
  }
  if ((ep === 'search' || ep === 'search-suggest') && (query ?? '').trim() === '') {
    return { error: `${ep} requires ?q=`, ok: false };
  }
  if ((ep === 'artist' || ep === 'album') && continuation !== undefined) {
    return { error: `${ep} does not paginate`, ok: false };
  }
  return {
    ok: true,
    params: {
      continuation,
      endpoint: ep,
      filter,
      gl,
      hl,
      id: id === undefined ? undefined : id.trim(),
      query: query === undefined ? undefined : query.trim(),
    },
  };
}

// ---------------------------------------------------------------------------
// The session (best-effort; mirrors /bg-session's discipline)
// ---------------------------------------------------------------------------

interface YtmSession {
  clientVersion: string;
  fetchedAt: number;
  visitorData: null | string;
}

/** youtubei.js 18.3.0's own YTMUSIC constant (the pin feishin carries) —
 * the live music homepage upgrade is a bonus, never a dependency (the
 * calls work without it — measured). */
const FALLBACK_CLIENT_VERSION = '1.20250219.01.00';
const WEB_CLIENT_VERSION = '2.20260227.07.00';
const YTM_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';

let sessionCache: YtmSession | null = null;
const SESSION_TTL_MS = 60 * 60 * 1000;

/**
 * One session per isolate (metadata calls are anonymous). Extracts
 * visitorData + INNERTUBE_CLIENT_VERSION from the music homepage ytcfg —
 * music.youtube.com IS the WEB_REMIX app, so its version is the music
 * client's. Failure degrades to the constants; never fatal.
 */
async function ytmSession(fetchImpl: typeof fetch): Promise<YtmSession> {
  const now = Date.now();
  if (sessionCache !== null && now - sessionCache.fetchedAt < SESSION_TTL_MS) {
    return sessionCache;
  }
  try {
    const res = await fetchImpl('https://music.youtube.com/', {
      headers: {
        accept: 'text/html',
        'accept-language': 'en-US,en;q=0.7',
        'user-agent': YTM_USER_AGENT,
        cookie: 'SOCS=CAI',
      },
      redirect: 'follow',
    });
    if (res.ok) {
      const html = await res.text();
      const visitor = /"visitorData":"([^"]+)"/.exec(html)?.[1] ?? null;
      const version = /"INNERTUBE_CLIENT_VERSION":"([^"]+)"/.exec(html)?.[1];
      if (version !== undefined) {
        sessionCache = { clientVersion: version, fetchedAt: now, visitorData: visitor ?? null };
        return sessionCache;
      }
    }
  } catch {
    /* degrade — never fatal */
  }
  sessionCache = {
    clientVersion: FALLBACK_CLIENT_VERSION,
    fetchedAt: now,
    visitorData: null,
  };
  return sessionCache;
}

// ---------------------------------------------------------------------------
// Upstream legs
// ---------------------------------------------------------------------------

type ClientKind = 'WEB' | 'YTMUSIC';

interface UpstreamLeg {
  body: Record<string, unknown>;
  client: ClientKind;
  path: string;
}

export interface LegResult {
  /** Serialized JSON byte length (the 2 MiB cache gate). */
  byteLength: number;
  json: unknown | null;
  ok: boolean;
  retryAfterMs?: number;
  status: number;
}

export type LegFetcher = (leg: UpstreamLeg) => Promise<LegResult>;

/** Music (WEB_REMIX) context + isAudioOnly — youtubei.js's exact shape. */
function musicContext(gl: string, hl: string): Record<string, unknown> {
  return {
    context: {
      client: {
        clientName: 'WEB_REMIX',
        clientVersion: FALLBACK_CLIENT_VERSION,
        gl,
        hl,
        userAgent: YTM_USER_AGENT,
      },
    },
    isAudioOnly: true,
  };
}

/** Plain WEB context (the structured-description / chapter-bar legs). */
function webContext(gl: string, hl: string): Record<string, unknown> {
  return {
    context: {
      client: {
        clientName: 'WEB',
        clientVersion: WEB_CLIENT_VERSION,
        gl,
        hl,
        userAgent: YTM_USER_AGENT,
      },
    },
  };
}

/**
 * Search filter params — generated from youtubei.js 18.3.0's
 * SearchFilter.encode (musicSearchType.{song|video|album|artist|playlist}),
 * the exact base64 the library itself sends (verified live 2026-11-16).
 */
const SEARCH_FILTER_PARAMS: Readonly<Record<string, string>> = {
  songs: 'EgWKAQIIAQ%3D%3D',
  videos: 'EgWKAQIQAQ%3D%3D',
  albums: 'EgWKAQIYAQ%3D%3D',
  artists: 'EgWKAQIgAQ%3D%3D',
  playlists: 'EgWKAQIoAQ%3D%3D',
};

/** Production leg fetcher — the exact wire contract the probes used. */
function defaultLegFetcher(session: () => Promise<YtmSession>): LegFetcher {
  return async (leg) => {
    const sess = await session();
    const isWeb = leg.client === 'WEB';
    const headers: Record<string, string> = {
      accept: '*/*',
      'accept-language': '*',
      'content-type': 'application/json',
      'user-agent': YTM_USER_AGENT,
      'x-origin': 'https://www.youtube.com',
      'x-youtube-client-name': isWeb ? '1' : '67',
      'x-youtube-client-version': isWeb ? WEB_CLIENT_VERSION : sess.clientVersion,
    };
    if (!isWeb && sess.visitorData !== null) {
      headers['x-goog-visitor-id'] = sess.visitorData;
    }
    try {
      const res = await fetch(
        `https://www.youtube.com/youtubei/v1/${leg.path}?prettyPrint=false&alt=json`,
        { method: 'POST', headers, body: JSON.stringify(leg.body) },
      );
      const retryAfterMs =
        res.headers.get('retry-after') !== null
          ? Number(res.headers.get('retry-after')) * 1000
          : undefined;
      const text = await res.text();
      let json: unknown | null = null;
      try {
        json = JSON.parse(text);
      } catch {
        json = null;
      }
      return { byteLength: text.length, json, ok: res.ok, retryAfterMs, status: res.status };
    } catch {
      return { byteLength: 0, json: null, ok: false, status: 0 };
    }
  };
}

/** The per-endpoint leg plan (multi-leg flows included). */
function planLegs(params: YtmMetaParams): UpstreamLeg[] {
  switch (params.endpoint) {
    case 'lyrics':
    case 'up-next':
    case 'related':
      // Leg 1: /next(videoId) — carries the tabs (lyrics/related browse
      // ids) and the up-next queue panel. Leg 2 is conditional (below).
      return [{ body: { ...musicContext(params.gl, params.hl), videoId: params.id }, client: 'YTMUSIC', path: 'next' }];
    case 'credits':
    case 'chapters':
      // WEB /next — the structured description (credits) and the chapter
      // bar markers live on the watch page's response.
      return [{ body: { ...webContext(params.gl, params.hl), videoId: params.id }, client: 'WEB', path: 'next' }];
    case 'counterpart':
      // W2-A: leg 1 = YTMUSIC /next(videoId) — the queue panel's first
      // matching entry carries the seed track's title/artist/duration. Leg
      // 2 (conditional, below) = the videos-filtered search that matching
      // runs against.
      return [{ body: { ...musicContext(params.gl, params.hl), videoId: params.id }, client: 'YTMUSIC', path: 'next' }];
    case 'artist':
      return [{ body: { ...musicContext(params.gl, params.hl), browseId: params.id }, client: 'YTMUSIC', path: 'browse' }];
    case 'album':
      return [{ body: { ...musicContext(params.gl, params.hl), browseId: params.id }, client: 'YTMUSIC', path: 'browse' }];
    case 'explore':
      return [
        { body: { ...musicContext(params.gl, params.hl), browseId: 'FEmusic_explore' }, client: 'YTMUSIC', path: 'browse' },
      ];
    case 'home':
      return [
        { body: { ...musicContext(params.gl, params.hl), browseId: 'FEmusic_home' }, client: 'YTMUSIC', path: 'browse' },
      ];
    case 'search': {
      const body: Record<string, unknown> = { ...musicContext(params.gl, params.hl) };
      if (params.continuation !== undefined) {
        // Continuation pages carry ONLY the token (the filter is baked in).
        body.continuation = params.continuation;
      } else {
        body.query = params.query;
        if (params.filter !== undefined && SEARCH_FILTER_PARAMS[params.filter] !== undefined) {
          body.params = SEARCH_FILTER_PARAMS[params.filter];
        }
      }
      return [{ body, client: 'YTMUSIC', path: 'search' }];
    }
    case 'search-suggest':
      return [
        {
          body: { ...musicContext(params.gl, params.hl), input: params.query },
          client: 'YTMUSIC',
          path: 'music/get_search_suggestions',
        },
      ];
    case 'playlist': {
      // VL-normalization (youtubei.js's own rule): PL… / RDCLAK5uy_… /
      // OLAK5uy_… / VL… all browse as VL<id> — verified live for every
      // form (the OLAK5uy_ album URL form included: it yields the richest
      // per-track columns of any layout).
      const raw = params.id ?? '';
      const browseId = raw.startsWith('VL') ? raw : `VL${raw}`;
      const body: Record<string, unknown> = { ...musicContext(params.gl, params.hl) };
      if (params.continuation !== undefined) {
        body.continuation = params.continuation;
      } else {
        body.browseId = browseId;
      }
      return [{ body, client: 'YTMUSIC', path: 'browse' }];
    }
  }
}

export interface UpstreamOutcome {
  legResults: LegResult[];
  legs: unknown[];
}

async function performUpstream(
  params: YtmMetaParams,
  fetchLeg: LegFetcher,
): Promise<UpstreamOutcome> {
  const legs = planLegs(params);
  const legResults: LegResult[] = [];
  for (const leg of legs) {
    legResults.push(await fetchLeg(leg));
  }
  // Conditional leg 2: lyrics/related need the tab's browseId from leg 1
  // (the MPLY/MPTR flow — same mechanism as youtubei.js's tab walk).
  if (params.endpoint === 'lyrics' || params.endpoint === 'related') {
    const pageType =
      params.endpoint === 'lyrics'
        ? 'MUSIC_PAGE_TYPE_TRACK_LYRICS'
        : 'MUSIC_PAGE_TYPE_TRACK_RELATED';
    const tab = findTabByPageType(legResults[0]?.json, pageType);
    if (tab !== null) {
      legResults.push(
        await fetchLeg({
          body: { ...musicContext(params.gl, params.hl), browseId: tab },
          client: 'YTMUSIC',
          path: 'browse',
        }),
      );
    }
  }
  // W2-A conditional leg 2: the counterpart search needs the seed track's
  // title/artist off leg 1's queue panel (no title → no search, the
  // normalizer emits the honest videoId:null miss).
  if (params.endpoint === 'counterpart') {
    const seed = counterpartSeedOf(params.id ?? '', legResults[0]?.json);
    if (seed !== null) {
      legResults.push(
        await fetchLeg({
          body: {
            ...musicContext(params.gl, params.hl),
            params: SEARCH_FILTER_PARAMS.videos,
            query: `${seed.title} ${seed.artistText ?? ''}`.trim(),
          },
          client: 'YTMUSIC',
          path: 'search',
        }),
      );
    }
  }
  return { legResults, legs: legResults.map((r) => r.json) };
}

// ---------------------------------------------------------------------------
// Normalizers — every walk below was verified against live innertube
// responses (2026-11-16). On upstream shape drift the normalizer degrades
// to an honest empty payload (missing), never a throw.
// ---------------------------------------------------------------------------

/* eslint-disable @typescript-eslint/no-explicit-any -- innertube JSON is
 * genuinely dynamic; the walkers in this section ARE the auditable shape
 * contract (ported from youtubei.js 18.3.0's parsers, verified live). */
type Json = Record<string, any>;

function runsText(node: unknown): string {
  const holder = node as { runs?: Array<{ text?: unknown }>; simpleText?: unknown } | null;
  if (holder === null || holder === undefined) return '';
  if (typeof holder.simpleText === 'string') return holder.simpleText;
  if (Array.isArray(holder.runs)) {
    return holder.runs.map((r) => (typeof r?.text === 'string' ? r.text : '')).join('');
  }
  return '';
}

/** "4:21" / "1:02:33" → ms. */
export function parseDurationText(text: string): null | number {
  const parts = text.trim().split(':').map((p) => Number(p));
  if (parts.length < 2 || parts.some((p) => !Number.isFinite(p))) return null;
  let seconds = 0;
  for (const p of parts) seconds = seconds * 60 + p;
  if (seconds <= 0) return null;
  return Math.round(seconds * 1000);
}

/** flexColumns[1] runs are [artist, ' • ', album?, ' • ', duration?] —
 * split on the bullet separator and classify the tail (the YTM list-item
 * convention; verified on songs / playlist / up-next / search alike). */
function splitSongColumns(runs: Array<{ text?: unknown }>): {
  album: null | string;
  artist: null | string;
  durationText: null | string;
} {
  const texts = runs.map((r) => (typeof r?.text === 'string' ? r.text : '')).filter((t) => t !== '');
  const joined = texts.join('');
  const segments = joined.split(' • ').map((s) => s.trim()).filter((s) => s !== '');
  const durationText =
    segments.length > 0 && /^\d+(:\d+)+$/.test(segments[segments.length - 1])
      ? (segments.pop() as string)
      : null;
  const artist = segments.shift() ?? null;
  const album = segments.length > 0 ? (segments[0] as string) : null;
  return { album, artist, durationText };
}

function watchVideoIdOfListItem(item: Json): null | string {
  return (
    item?.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
      ?.playNavigationEndpoint?.watchEndpoint?.videoId ?? null
  );
}

/** durationMs from fixedColumns (playlist/album items) — "4:32". */
function fixedColumnDuration(item: Json): null | number {
  const text = runsText(item?.fixedColumns?.[0]?.musicResponsiveListItemFixedColumnRenderer?.text);
  return text === '' ? null : parseDurationText(text);
}

/** find a music watch-next tab by pageType (the lyrics/related flow). */
function findTabByPageType(nextJson: unknown, pageType: string): string | null {
  const tabs = (nextJson as Json | null)?.contents?.singleColumnMusicWatchNextResultsRenderer
    ?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs as Json[] | undefined;
  if (tabs === undefined) return null;
  for (const tab of tabs) {
    const be = tab?.tabRenderer?.endpoint?.browseEndpoint as Json | undefined;
    const cfg = be?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig as
      | Json
      | undefined;
    if (cfg?.pageType === pageType && typeof be?.browseId === 'string') {
      return be.browseId;
    }
  }
  return null;
}

/**
 * The continuation token: the current form is a trailing
 * continuationItemRenderer inside the shelf contents; the older form is
 * shelf.continuations[0].nextContinuationData (both live — read both).
 */
function shelfContinuationOf(shelf: Json | null | undefined): null | string {
  if (shelf === null || shelf === undefined) return null;
  const legacy = shelf?.continuations?.[0]?.nextContinuationData?.continuation;
  if (typeof legacy === 'string' && legacy !== '') return legacy;
  const contents = (shelf?.contents ?? []) as Json[];
  const last = contents[contents.length - 1]?.continuationItemRenderer;
  const token = last?.continuationEndpoint?.continuationCommand?.token;
  return typeof token === 'string' && token !== '' ? token : null;
}

function playlistTrackOf(item: Json, index: number): PlaylistTrack {
  const li = item.musicResponsiveListItemRenderer as Json;
  const flex = (li.flexColumns ?? []) as Json[];
  const title = runsText(flex[0]?.musicResponsiveListItemFlexColumnRenderer?.text) || '';
  // Column 1: the artist on playlist/album layouts; 'artist • album •
  // duration' on search-style shelves (splitSongColumns handles both).
  const info = splitSongColumns(
    (flex[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?? []) as Array<{ text?: unknown }>,
  );
  // Column 2: the ALBUM on playlist layouts ("The Writing's On The Wall")
  // — or the plays count on album/artist layouts ("350M plays", guarded).
  const col2 = runsText(flex[2]?.musicResponsiveListItemFlexColumnRenderer?.text);
  const albumFromCol2 = col2 !== '' && !/plays?$/i.test(col2.trim()) ? col2 : null;
  return {
    albumText: info.album ?? albumFromCol2,
    artistText: info.artist,
    durationMs: fixedColumnDuration(li),
    index,
    title,
    videoId: watchVideoIdOfListItem(li),
  };
}

/** LRC-style timestamps: a line "[00:12.34] text" → 12340 ms. */
const LRC_LINE = /^\s*\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]\s*(.*)$/;

/** Parse a raw lyrics text block into synced/unsynced lines. */
export function parseLyricsText(raw: string): { lines: LyricsPayload['lines']; synced: boolean } {
  const lines: LyricsPayload['lines'] = [];
  let syncedCount = 0;
  for (const rawLine of raw.split('\n')) {
    const m = LRC_LINE.exec(rawLine);
    if (m !== null) {
      const mins = Number(m[1]);
      const secs = Number(m[2]);
      // The fraction is decimal digits of a second: "34" = 0.34 s,
      // "345" = 0.345 s — so divide by 10^digits, never pad.
      const fracRaw = m[3] ?? '0';
      const frac = Number(fracRaw) / 10 ** fracRaw.length;
      const text = (m[4] ?? '').trim();
      if (text === '') continue;
      lines.push({ atMs: Math.round((mins * 60 + secs + frac) * 1000), text });
      syncedCount += 1;
      continue;
    }
    const text = rawLine.trim();
    if (text === '') continue;
    lines.push({ atMs: null, text });
  }
  return { lines, synced: lines.length > 0 && syncedCount === lines.length };
}

// --- per-endpoint normalizers -----------------------------------------------

function normalizeLyrics(params: YtmMetaParams, lyricsJson: unknown | null): LyricsPayload | null {
  if (lyricsJson === null || lyricsJson === undefined) return null;
  const shelf = (lyricsJson as Json)?.contents?.sectionListRenderer?.contents?.find(
    (c: Json) => c.musicDescriptionShelfRenderer,
  )?.musicDescriptionShelfRenderer as Json | null;
  if (shelf === null || shelf === undefined) return null;
  const text = runsText(shelf.description);
  if (text === '') return null;
  const footer = runsText(shelf.footer);
  const source = footer.replace(/^Source:\s*/i, '').trim() || 'YouTube Music';
  const { lines, synced } = parseLyricsText(text);
  if (lines.length === 0) return null;
  return { hl: params.hl, kind: 'lyrics', lines, source, synced, videoId: params.id ?? '' };
}

function normalizeUpNext(params: YtmMetaParams, nextJson: unknown): UpNextPayload | null {
  const panel = (nextJson as Json)?.contents?.singleColumnMusicWatchNextResultsRenderer
    ?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.[0]?.tabRenderer?.content
    ?.musicQueueRenderer?.content?.playlistPanelRenderer as Json | null | undefined;
  if (panel === null || panel === undefined) return null;
  const tracks: UpNextPayload['tracks'] = [];
  for (const entry of (panel.contents ?? []) as Json[]) {
    const v = entry.playlistPanelVideoRenderer as Json | undefined;
    if (v === undefined) continue;
    const info = splitSongColumns((v.longBylineText?.runs ?? []) as Array<{ text?: unknown }>);
    const title = runsText(v.title);
    if (title === '') continue;
    tracks.push({
      artistText: info.artist,
      durationMs: parseDurationText(runsText(v.lengthText)) ?? null,
      title,
      videoId: typeof v.videoId === 'string' ? v.videoId : '',
    });
  }
  if (tracks.length === 0) return null;
  return { kind: 'up-next', seedVideoId: params.id ?? '', tracks };
}

function carouselItemsOf(shelf: Json): ShelfItemPayload[] {
  const items: ShelfItemPayload[] = [];
  for (const entry of (shelf.contents ?? []) as Json[]) {
    const two = entry.musicTwoRowItemRenderer as Json | undefined;
    if (two === undefined) continue;
    const be = two.navigationEndpoint?.browseEndpoint as Json | undefined;
    const watch = two.navigationEndpoint?.watchEndpoint as Json | undefined;
    items.push({
      browseId: typeof be?.browseId === 'string' ? be.browseId : null,
      pageType:
        be?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType ?? null,
      subtitle: runsText(two.subtitle) || null,
      thumbnail: null,
      title: runsText(two.title) || '',
      videoId: typeof watch?.videoId === 'string' ? watch.videoId : null,
    });
  }
  return items;
}

function normalizeShelfPage(params: YtmMetaParams, json: unknown): ExploreHomePayload | null {
  const list = (json as Json)?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer
    ?.content?.sectionListRenderer?.contents as Json[] | null | undefined;
  if (list === null || list === undefined || list.length === 0) return null;
  const shelves: ExploreHomePayload['shelves'] = [];
  for (const entry of list) {
    const carousel = entry.musicCarouselShelfRenderer as Json | undefined;
    if (carousel === undefined) continue;
    const title = runsText(carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title) || '';
    const items = carouselItemsOf(carousel);
    if (items.length > 0) shelves.push({ items, title });
  }
  if (shelves.length === 0) return null;
  const kind: ExploreHomeKind = params.endpoint === 'explore' ? 'explore' : 'home';
  return { gl: params.gl, hl: params.hl, kind, shelves };
}

function normalizeRelated(params: YtmMetaParams, relatedJson: unknown | null): RelatedPayload | null {
  if (relatedJson === null || relatedJson === undefined) return null;
  const list = (relatedJson as Json)?.contents?.sectionListRenderer?.contents as
    | Json[]
    | null
    | undefined;
  if (list === null || list === undefined) return null;
  const shelves: RelatedPayload['shelves'] = [];
  for (const entry of list) {
    const carousel = entry.musicCarouselShelfRenderer as Json | undefined;
    if (carousel !== undefined) {
      const title = runsText(carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title) || '';
      const items = carouselItemsOf(carousel);
      if (items.length > 0) shelves.push({ items, title });
      continue;
    }
    const shelf = entry.musicShelfRenderer as Json | undefined;
    if (shelf !== undefined) {
      const title = runsText(shelf.title) || '';
      const items: ShelfItemPayload[] = [];
      for (const entry2 of (shelf.contents ?? []) as Json[]) {
        const li = entry2.musicResponsiveListItemRenderer as Json | undefined;
        if (li === undefined) continue;
        items.push({
          browseId: null,
          pageType: null,
          subtitle: runsText(li.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text) || null,
          thumbnail: null,
          title: runsText(li.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text) || '',
          videoId: watchVideoIdOfListItem(li),
        });
      }
      const realItems = items.filter((item) => item.title !== '' || item.videoId !== null);
      if (realItems.length > 0) shelves.push({ items: realItems, title });
    }
  }
  if (shelves.length === 0) return null;
  return { kind: 'related', seedVideoId: params.id ?? '', shelves };
}

function normalizeSearch(params: YtmMetaParams, searchJson: unknown): SearchPayload | null {
  const json = searchJson as Json;
  const sections =
    (json?.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer
      ?.contents as Json[] | undefined) ?? [];
  const contShelf = json?.continuationContents?.musicPlaylistShelfContinuation as Json | undefined;
  const continuation = contShelf ? shelfContinuationOf(contShelf) : null;

  const results: SearchPayload['results'] = [];

  const processListItem = (li: Json) => {
    const flex = (li.flexColumns ?? []) as Json[];
    const info = splitSongColumns(
      (flex[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?? []) as Array<{ text?: unknown }>,
    );
    const title = runsText(flex[0]?.musicResponsiveListItemFlexColumnRenderer?.text) || '';
    const subtitle = runsText(flex[1]?.musicResponsiveListItemFlexColumnRenderer?.text) || null;
    const videoId = watchVideoIdOfListItem(li);
    if (title !== '') {
      results.push({
        durationMs: info.durationText !== null ? parseDurationText(info.durationText) : null,
        subtitle,
        title,
        videoId,
      });
    }
  };

  // Check sections (musicShelfRenderer and itemSectionRenderer with musicResponsiveListItemRenderer)
  for (const sec of sections) {
    if (sec.musicShelfRenderer) {
      for (const entry of ((sec.musicShelfRenderer as Json).contents ?? []) as Json[]) {
        if (entry.musicResponsiveListItemRenderer) {
          processListItem(entry.musicResponsiveListItemRenderer as Json);
        }
      }
    }
    if (sec.itemSectionRenderer) {
      for (const entry of ((sec.itemSectionRenderer as Json).contents ?? []) as Json[]) {
        if (entry.musicResponsiveListItemRenderer) {
          processListItem(entry.musicResponsiveListItemRenderer as Json);
        } else if (entry.musicShelfRenderer) {
          for (const item of ((entry.musicShelfRenderer as Json).contents ?? []) as Json[]) {
            if (item.musicResponsiveListItemRenderer) {
              processListItem(item.musicResponsiveListItemRenderer as Json);
            }
          }
        }
      }
    }
  }

  // Check continuation shelf if present
  if (contShelf) {
    for (const entry of ((contShelf.contents ?? []) as Json[])) {
      if (entry.musicResponsiveListItemRenderer) {
        processListItem(entry.musicResponsiveListItemRenderer as Json);
      }
    }
  }

  const realResults = results.filter((r) => r.title !== '');
  if (realResults.length === 0) return null;
  return {
    continuation,
    filter: params.filter ?? null,
    kind: 'search',
    query: params.query ?? '',
    results: realResults,
  };
}

function normalizeSearchSuggest(params: YtmMetaParams, json: unknown): SearchSuggestPayload | null {
  const section = (json as Json)?.contents?.[0]?.searchSuggestionsSectionRenderer as
    | Json
    | null
    | undefined;
  if (section === null || section === undefined) return null;
  const suggestions: string[] = [];
  for (const entry of (section.contents ?? []) as Json[]) {
    const s = entry.searchSuggestionRenderer?.suggestion as Json | undefined;
    const text = s !== undefined ? runsText(s) : '';
    if (text !== '') suggestions.push(text);
  }
  if (suggestions.length === 0) return null;
  return { input: params.query ?? '', kind: 'search-suggest', suggestions };
}

function normalizeArtist(params: YtmMetaParams, json: unknown): ArtistPayload | null {
  const header = (json as Json)?.header?.musicImmersiveHeaderRenderer as Json | null | undefined;
  if (header === null || header === undefined) return null;
  const name = runsText(header.title);
  if (name === '') return null;
  const sections = (json as Json)?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]
    ?.tabRenderer?.content?.sectionListRenderer?.contents as Json[] | undefined;
  const topSongs: ArtistPayload['topSongs'] = [];
  const albums: ArtistPayload['albums'] = [];
  if (sections !== undefined) {
    for (const section of sections) {
      const shelf = section.musicShelfRenderer as Json | undefined;
      if (shelf !== undefined && /top songs/i.test(runsText(shelf.title))) {
        for (const entry of (shelf.contents ?? []) as Json[]) {
          const li = entry.musicResponsiveListItemRenderer as Json | undefined;
          if (li === undefined) continue;
          const title = runsText(li.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text);
          if (title === '') continue;
          topSongs.push({
            artistText: runsText(li.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text) || null,
            title,
            videoId: watchVideoIdOfListItem(li) ?? '',
          });
        }
      }
      const carousel = section.musicCarouselShelfRenderer as Json | undefined;
      if (
        carousel !== undefined &&
        /albums?/i.test(runsText(carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title))
      ) {
        for (const item of carouselItemsOf(carousel)) {
          if (item.browseId !== null && item.browseId.startsWith('MPRE')) {
            albums.push({ browseId: item.browseId, subtitle: item.subtitle, title: item.title });
          }
        }
      }
    }
  }
  return {
    albums,
    browseId: params.id ?? '',
    description: runsText(header.description) || null,
    gl: params.gl,
    hl: params.hl,
    kind: 'artist',
    listenersText: runsText(header.monthlyListenerCount) || null,
    name,
    topSongs,
  };
}

function normalizeAlbum(params: YtmMetaParams, json: unknown): AlbumPayload | null {
  const two = (json as Json)?.contents?.twoColumnBrowseResultsRenderer as Json | undefined;
  let header: Json | null | undefined;
  let shelf: Json | null | undefined;
  if (two !== undefined) {
    header =
      two.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents?.find(
        (c: Json) => c.musicResponsiveHeaderRenderer,
      )?.musicResponsiveHeaderRenderer ?? null;
    shelf =
      two.secondaryContents?.sectionListRenderer?.contents?.find((c: Json) => c.musicShelfRenderer)
        ?.musicShelfRenderer ??
      two.secondaryContents?.sectionListRenderer?.contents?.find(
        (c: Json) => c.musicPlaylistShelfRenderer,
      )?.musicPlaylistShelfRenderer ??
      null;
  } else {
    header = (json as Json)?.header?.musicResponsiveHeaderRenderer ?? null;
    const list = (json as Json)?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]
      ?.tabRenderer?.content?.sectionListRenderer?.contents as Json[] | undefined;
    shelf =
      list?.find((c: Json) => c.musicShelfRenderer)?.musicShelfRenderer ??
      list?.find((c: Json) => c.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer ??
      null;
  }
  if (header === null || header === undefined) return null;
  const title = runsText(header.title);
  if (title === '') return null;
  const secondSubtitle = runsText(header.secondSubtitle);
  const trackCount = /(\d[\d,.]*)\s+songs?/i.exec(secondSubtitle)?.[1]?.replace(/,/g, '');
  const year = /(\d{4})/.exec(runsText(header.subtitle))?.[1] ?? null;
  const tracks: AlbumPayload['tracks'] = [];
  if (shelf !== null && shelf !== undefined) {
    for (const entry of (shelf.contents ?? []) as Json[]) {
      const li = entry.musicResponsiveListItemRenderer as Json | undefined;
      if (li === undefined) continue;
      const itemTitle = runsText(li.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text);
      if (itemTitle === '') continue;
      tracks.push({
        durationMs: fixedColumnDuration(li),
        title: itemTitle,
        videoId: watchVideoIdOfListItem(li),
      });
    }
  }
  return {
    artistText: runsText(header.straplineTextOne) || null,
    browseId: params.id ?? '',
    gl: params.gl,
    hl: params.hl,
    kind: 'album',
    title,
    trackCountTotal: trackCount !== undefined ? Number(trackCount) || null : null,
    tracks,
    year,
  };
}

/** The playlist page walk — handles BOTH layouts (old singleColumn with a
 * top-level header + the current twoColumn/secondaryContents form with the
 * header inside the tabs) plus continuation pages. */
function normalizePlaylist(params: YtmMetaParams, json: unknown): PlaylistPayload | null {
  const raw = json as Json;
  // Continuation pages answer with appendContinuationItemsAction.
  const append = raw?.onResponseReceivedActions?.find((a: Json) => a.appendContinuationItemsAction)
    ?.appendContinuationItemsAction as Json | undefined;
  if (append !== undefined) {
    const items = ((append.continuationItems ?? []) as Json[]).filter(
      (i) => i?.musicResponsiveListItemRenderer !== undefined,
    );
    const tracks = items.map((item, i) => playlistTrackOf(item, i));
    if (tracks.length === 0) return null;
    const nextToken = shelfContinuationOf({ contents: append.continuationItems ?? [] } as Json);
    return {
      continuation: nextToken,
      description: null,
      kind: 'playlist',
      listId: params.id ?? '',
      title: null,
      trackCountTotal: null,
      tracks,
    };
  }
  // twoColumn (current): tracks in secondaryContents, header in tabs.
  const two = raw?.contents?.twoColumnBrowseResultsRenderer as Json | undefined;
  let shelf: Json | null | undefined;
  let header: Json | null | undefined;
  if (two !== undefined) {
    const secList = two.secondaryContents?.sectionListRenderer?.contents as Json[] | undefined;
    shelf =
      secList?.find((c: Json) => c.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer ??
      secList?.find((c: Json) => c.musicShelfRenderer)?.musicShelfRenderer ?? null;
    header =
      two.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents?.find(
        (c: Json) => c.musicResponsiveHeaderRenderer,
      )?.musicResponsiveHeaderRenderer ?? null;
  } else {
    const list = raw?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content
      ?.sectionListRenderer?.contents as Json[] | undefined;
    shelf =
      list?.find((c: Json) => c.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer ??
      list?.find((c: Json) => c.musicShelfRenderer)?.musicShelfRenderer ?? null;
    header = raw?.header?.musicResponsiveHeaderRenderer ?? null;
  }
  const tracks: PlaylistTrack[] = [];
  if (shelf !== null && shelf !== undefined) {
    for (const entry of (shelf.contents ?? []) as Json[]) {
      if (entry?.musicResponsiveListItemRenderer === undefined) continue;
      tracks.push(playlistTrackOf(entry, tracks.length));
    }
  }
  if (tracks.length === 0 && header === null) return null;
  const secondSubtitle = runsText(header?.secondSubtitle);
  const trackCount = /(\d[\d,.]*)\s+tracks?/i.exec(secondSubtitle)?.[1]?.replace(/,/g, '');
  return {
    continuation: shelfContinuationOf(shelf),
    description: runsText(header?.description) || null,
    kind: 'playlist',
    listId: params.id ?? '',
    title: runsText(header?.title) || null,
    trackCountTotal: trackCount !== undefined ? Number(trackCount) || null : null,
    tracks,
  };
}

function normalizeCredits(params: YtmMetaParams, webNextJson: unknown): CreditsPayload | null {
  const panels = (webNextJson as Json)?.engagementPanels as Json[] | undefined;
  const sd = panels?.find(
    (p) => p?.engagementPanelSectionListRenderer?.panelIdentifier === 'engagement-panel-structured-description',
  )?.engagementPanelSectionListRenderer as Json | undefined;
  const items = sd?.content?.structuredDescriptionContentRenderer?.items as
    | Json[]
    | undefined;
  if (items === undefined) return null;
  const roleGroups: CreditsPayload['roleGroups'] = [];
  for (const entry of items) {
    const attrs = entry.videoAttributesSectionViewModel as Json | undefined;
    if (attrs === undefined) continue;
    const title = typeof attrs.headerTitle === 'string' ? attrs.headerTitle : '';
    const credits: CreditsPayload['roleGroups'][number]['credits'] = [];
    for (const vm of (attrs.videoAttributeViewModels ?? []) as Json[]) {
      const v = vm.videoAttributeViewModel as Json | undefined;
      if (v === undefined) continue;
      const name = typeof v.title === 'string' ? v.title : '';
      if (name === '') continue;
      credits.push({ name, subtitle: typeof v.subtitle === 'string' ? v.subtitle : null });
    }
    if (credits.length > 0) roleGroups.push({ credits, title });
  }
  if (roleGroups.length === 0) return null;
  return { hl: params.hl, kind: 'credits', roleGroups, videoId: params.id ?? '' };
}

function normalizeChapters(params: YtmMetaParams, webNextJson: unknown): ChaptersPayload | null {
  const markers = (webNextJson as Json)?.playerOverlays?.playerOverlayRenderer
    ?.decoratedPlayerBarRenderer?.decoratedPlayerBarRenderer?.multiMarkersPlayerBarRenderer
    ?.markersMap as Json[] | undefined;
  const entry = markers?.find((m) => m.key === 'DESCRIPTION_CHAPTERS' || m.key === 'AUTO_CHAPTERS');
  const rows: Array<{ atMs: number; title: string; thumb: null | string }> = [];
  for (const ch of ((entry?.value?.chapters ?? []) as Json[])) {
    const r = ch.chapterRenderer as Json | undefined;
    if (r === undefined) continue;
    const title = runsText(r.title);
    const at = typeof r.timeRangeStartMillis === 'number' ? r.timeRangeStartMillis : null;
    if (title === '' || at === null) continue;
    // The marker's own thumbnail set — the LAST entry is the largest
    // (YouTube thumbnail arrays ascend by size; same convention the
    // importer's cover picker uses).
    const thumbs = (r.thumbnail?.thumbnails ?? []) as Array<{ url?: unknown }>;
    const thumb =
      thumbs.length > 0 && typeof thumbs[thumbs.length - 1]?.url === 'string'
        ? (thumbs[thumbs.length - 1].url as string)
        : null;
    rows.push({ atMs: at, title, thumb });
  }
  if (rows.length === 0) return null;
  // W2-A: derive the seconds-space boundaries (endSeconds = next marker's
  // start; the LAST chapter runs to the video's end — null, the client
  // closes it with the element's duration).
  const chapters: ChaptersPayload['chapters'] = rows.map((row, i) => ({
    atMs: row.atMs,
    endSeconds: i + 1 < rows.length ? Math.round(rows[i + 1].atMs / 1000) : null,
    startSeconds: Math.round(row.atMs / 1000),
    thumbnailUrl: row.thumb,
    title: row.title,
  }));
  return { chapters, hl: params.hl, kind: 'chapters', videoId: params.id ?? '' };
}

// ---------------------------------------------------------------------------
// W2-A — the official-music-video counterpart resolution
// ---------------------------------------------------------------------------

interface CounterpartSeed {
  artistText: null | string;
  durationMs: null | number;
  title: string;
}

/**
 * The seed track's own identity off the /next queue panel: the entry whose
 * videoId matches the seed (the panel lists the current track first, but
 * the position is not contractual — match on videoId, fall back to the
 * first entry, never guess further).
 */
export function counterpartSeedOf(seedVideoId: string, nextJson: unknown): CounterpartSeed | null {
  const panel = (nextJson as Json)?.contents?.singleColumnMusicWatchNextResultsRenderer
    ?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.[0]?.tabRenderer?.content
    ?.musicQueueRenderer?.content?.playlistPanelRenderer as Json | null | undefined;
  if (panel === null || panel === undefined) return null;
  const entries = (panel.contents ?? []) as Json[];
  let chosen: Json | undefined = undefined;
  for (const entry of entries) {
    const v = entry.playlistPanelVideoRenderer as Json | undefined;
    if (v === undefined) continue;
    if (typeof v.videoId === 'string' && v.videoId === seedVideoId) {
      chosen = v;
      break;
    }
    chosen = chosen ?? v;
  }
  if (chosen === undefined) return null;
  const title = runsText(chosen.title);
  if (title === '') return null;
  const info = splitSongColumns((chosen.longBylineText?.runs ?? []) as Array<{ text?: unknown }>);
  return {
    artistText: info.artist,
    durationMs: parseDurationText(runsText(chosen.lengthText)) ?? null,
    title,
  };
}

/** Decorations that carry no identity — stripped before title comparison. */
const TITLE_NOISE =
  /[(\[](?:official\s+)?(?:music\s+)?(?:video|audio|lyric[s]?\s+video|visualizer|hd|4k|hq|remaster(?:ed)?(?:\s+\d{4})?)[^)\]]*[)\]]/gi;

function normalizeTitleForMatch(title: string): string {
  return title
    .replace(TITLE_NOISE, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

/** Duration tolerance: official videos often add a beat of intro/outro —
 * 15 s absolute OR 12% relative, whichever is larger. */
function durationClose(a: null | number, b: null | number): boolean {
  if (a === null || b === null) return false;
  return Math.abs(a - b) <= Math.max(15_000, Math.max(a, b) * 0.12);
}

/** The counterpart matcher — deterministic, server-side, from server-derived
 * data only (the cache key stays (counterpart, id, gl, hl)). */
export function matchCounterpart(
  seed: CounterpartSeed,
  seedVideoId: string,
  searchResults: Array<{ durationMs: null | number; subtitle: null | string; title: string; videoId: null | string }>,
): CounterpartPayload {
  const seedTitle = normalizeTitleForMatch(seed.title);
  const artist = (seed.artistText ?? '').toLowerCase();
  let best: { confidence: 'strong' | 'weak'; result: { durationMs: null | number; subtitle: null | string; title: string; videoId: null | string } } | null =
    null;
  for (const result of searchResults) {
    if (result.videoId === null || result.videoId === '') continue;
    // The counterpart must be DISTINCT from the audio source — a song whose
    // only "video" is itself has no counterpart (hide the toggle).
    if (result.videoId === seedVideoId) continue;
    const resultTitle = normalizeTitleForMatch(result.title);
    if (resultTitle === '' || resultTitle !== seedTitle) continue;
    const subtitle = (result.subtitle ?? '').toLowerCase();
    const subtitleHasArtist = artist !== '' && subtitle.includes(artist);
    const durationOk = durationClose(seed.durationMs, result.durationMs);
    const strong = subtitleHasArtist && (durationOk || seed.durationMs === null || result.durationMs === null);
    if (best === null || (strong && best.confidence !== 'strong')) {
      best = { confidence: strong ? 'strong' : 'weak', result };
    }
    if (strong) break;
  }
  if (best === null) {
    return {
      artistText: seed.artistText,
      confidence: null,
      durationMs: null,
      kind: 'counterpart',
      title: null,
      sourceVideoId: seedVideoId,
      videoId: null,
    };
  }
  return {
    artistText: seed.artistText,
    confidence: best.confidence,
    durationMs: best.result.durationMs,
    kind: 'counterpart',
    title: best.result.title,
    sourceVideoId: seedVideoId,
    videoId: best.result.videoId,
  };
}

function normalizeCounterpart(params: YtmMetaParams, legs: unknown[]): CounterpartPayload | null {
  const seed = counterpartSeedOf(params.id ?? '', legs[0]);
  if (seed === null) return null;
  const search = normalizeSearch({ ...params, filter: 'videos', query: 'counterpart' }, legs[1] ?? null);
  return matchCounterpart(seed, params.id ?? '', search?.results ?? []);
}

/** The endpoint dispatch — legs arrive in the order performUpstream ran. */
export function normalizeYtmResponse(params: YtmMetaParams, legs: unknown[]): YtmPayload | null {
  switch (params.endpoint) {
    case 'lyrics':
      return normalizeLyrics(params, legs[1] ?? null);
    case 'up-next':
      return normalizeUpNext(params, legs[0]);
    case 'related':
      return normalizeRelated(params, legs[1] ?? null);
    case 'artist':
      return normalizeArtist(params, legs[0]);
    case 'album':
      return normalizeAlbum(params, legs[0]);
    case 'explore':
    case 'home':
      return normalizeShelfPage(params, legs[0]);
    case 'search':
      return normalizeSearch(params, legs[0]);
    case 'search-suggest':
      return normalizeSearchSuggest(params, legs[0]);
    case 'playlist':
      return normalizePlaylist(params, legs[0]);
    case 'credits':
      return normalizeCredits(params, legs[0]);
    case 'chapters':
      return normalizeChapters(params, legs[0]);
    case 'counterpart':
      return normalizeCounterpart(params, legs);
  }
}

// ---------------------------------------------------------------------------
// Validation (normalized payload shape — gates CACHING, not serving)
// ---------------------------------------------------------------------------

export type YtmValidation = { ok: false; reason: string } | { ok: true };

export function validateYtmPayload(endpoint: YtmEndpoint, payload: YtmPayload): YtmValidation {
  switch (endpoint) {
    case 'lyrics': {
      const p = payload as LyricsPayload;
      if (p.kind !== 'lyrics') return { ok: false, reason: 'lyrics: wrong kind' };
      if (typeof p.synced !== 'boolean') return { ok: false, reason: 'lyrics: synced must be boolean' };
      if (!Array.isArray(p.lines) || p.lines.length === 0) {
        return { ok: false, reason: 'lyrics: no lines' };
      }
      return { ok: true };
    }
    case 'credits': {
      const p = payload as CreditsPayload;
      if (p.kind !== 'credits' || !Array.isArray(p.roleGroups)) {
        return { ok: false, reason: 'credits: bad shape' };
      }
      return { ok: true };
    }
    case 'chapters': {
      const p = payload as ChaptersPayload;
      if (p.kind !== 'chapters' || !Array.isArray(p.chapters)) {
        return { ok: false, reason: 'chapters: bad shape' };
      }
      for (const ch of p.chapters) {
        if (typeof ch.title !== 'string' || ch.title === '') {
          return { ok: false, reason: 'chapters: a chapter lacks title' };
        }
        // W2-A rows carry startSeconds; W1-A-era cached rows may carry only
        // atMs (both are valid — the client normalizes atMs → startSeconds).
        if (typeof ch.startSeconds !== 'number' && typeof ch.atMs !== 'number') {
          return { ok: false, reason: 'chapters: a chapter lacks startSeconds/atMs' };
        }
      }
      return { ok: true };
    }
    case 'counterpart': {
      const p = payload as CounterpartPayload;
      if (p.kind !== 'counterpart' || typeof p.sourceVideoId !== 'string') {
        return { ok: false, reason: 'counterpart: bad shape' };
      }
      if (p.videoId !== null && typeof p.videoId !== 'string') {
        return { ok: false, reason: 'counterpart: videoId must be string or null' };
      }
      return { ok: true };
    }
    case 'playlist': {
      const p = payload as PlaylistPayload;
      if (p.kind !== 'playlist' || !Array.isArray(p.tracks)) {
        return { ok: false, reason: 'playlist: bad shape' };
      }
      return { ok: true };
    }
    default:
      return { ok: true };
  }
}

// ---------------------------------------------------------------------------
// SWR policy
// ---------------------------------------------------------------------------

/**
 * Should a STALE serve trigger background revalidation?
 *  - the lyrics/credits/chapters class (7 d): only when the payload is
 *    empty/missing or (lyrics) unsynced — a fully-synced payload is
 *    terminal; refreshing it cannot improve it.
 *  - every other class: always (standard SWR).
 */
export function shouldBackgroundRevalidate(endpoint: YtmEndpoint, envelope: YtmEnvelope): boolean {
  switch (endpoint) {
    case 'lyrics':
    case 'credits':
    case 'chapters': {
      if (envelope.missing || envelope.payload === null) return true;
      if (endpoint === 'lyrics') {
        const p = envelope.payload as LyricsPayload;
        if (p.kind === 'lyrics' && p.synced === false) return true;
      }
      return false;
    }
    default:
      return true;
  }
}

// ---------------------------------------------------------------------------
// Cache layers + single-flight
// ---------------------------------------------------------------------------

/** Per-isolate memory layer (bounded, LRU-ish insertion-order eviction). */
const MEMORY_MAX = 512;
const memoryCache = new Map<string, YtmEnvelope>();

function memoryGet(key: string): YtmEnvelope | null {
  const hit = memoryCache.get(key);
  if (hit === undefined) return null;
  memoryCache.delete(key);
  memoryCache.set(key, hit); // refresh recency
  return hit;
}

function memoryPut(key: string, envelope: YtmEnvelope): void {
  if (memoryCache.size >= MEMORY_MAX) {
    const oldest = memoryCache.keys().next();
    if (oldest.done !== true) memoryCache.delete(oldest.value);
  }
  memoryCache.set(key, envelope);
}

/** An entry is *stale-serveable* for 3× its TTL past expiry; KV's
 * expirationTtl is set to ttl + window so the store evicts on the same
 * schedule (bounded residency, no unbounded free-tier growth). */
const STALE_WINDOW_MULTIPLE = 3;

function staleServeable(envelope: YtmEnvelope, ttlMs: number, now: number): boolean {
  return now < envelope.expiresAt + STALE_WINDOW_MULTIPLE * ttlMs;
}

async function kvGet(env: YtmEnv, key: string): Promise<YtmEnvelope | null> {
  if (env.YTM_CACHE === undefined) return null;
  try {
    const raw = await env.YTM_CACHE.get(key, 'json');
    if (raw === null) return null;
    const envelope = raw as YtmEnvelope;
    if (envelope?.v !== 1 || typeof envelope.key !== 'string') return null;
    return envelope;
  } catch {
    return null; // a broken KV read must never break the route
  }
}

async function kvPut(env: YtmEnv, key: string, envelope: YtmEnvelope, ttlMs: number): Promise<void> {
  if (env.YTM_CACHE === undefined) return;
  try {
    const ttlSec = Math.max(60, Math.ceil((ttlMs * (1 + STALE_WINDOW_MULTIPLE)) / 1000));
    await env.YTM_CACHE.put(key, JSON.stringify(envelope), { expirationTtl: ttlSec });
  } catch {
    /* best-effort — the response still answered */
  }
}

/** Reset the module-level caches (the relay test lane calls this between
 * cases; single-flight state must never leak across tests). */
export function resetYtmMetaForTests(): void {
  memoryCache.clear();
  inflight.clear();
  sessionCache = null;
}

// ---------------------------------------------------------------------------
// The fill (upstream + normalize + validate + cache)
// ---------------------------------------------------------------------------

export type UpstreamErrorKind = 'http' | 'network' | 'parse';

export interface UpstreamError {
  detail?: string;
  endpoint: YtmEndpoint;
  retryAfterMs?: number;
  kind: UpstreamErrorKind;
  status: number;
}

export type FillOutcome =
  | { envelope: YtmEnvelope; oversized: boolean; kind: 'envelope' }
  | { upstreamError: UpstreamError; kind: 'error' };

const MAX_CACHED_JSON_BYTES = 2 * 1024 * 1024;

function startFill(
  key: string,
  params: YtmMetaParams,
  fetchLeg: LegFetcher,
  env: YtmEnv,
  ttlMs: number,
  now: () => number,
): Promise<FillOutcome> {
  const promise = (async (): Promise<FillOutcome> => {
    const { legResults, legs } = await performUpstream(params, fetchLeg);
    // Failure separation at the edge:
    //  429 → typed rate-limit passthrough (Retry-After honored);
    //  network / non-2xx → 502 network;
    //  2xx but unparseable → 502 parse.
    for (const result of legResults) {
      if (result.ok && result.status === 429) {
        return {
          kind: 'error',
          upstreamError: {
            detail: 'upstream rate limit',
            endpoint: params.endpoint,
            retryAfterMs: result.retryAfterMs,
            kind: 'http',
            status: 429,
          },
        };
      }
    }
    for (const result of legResults) {
      if (!result.ok && result.status === 429) {
        return {
          kind: 'error',
          upstreamError: {
            detail: 'upstream rate limit',
            endpoint: params.endpoint,
            retryAfterMs: result.retryAfterMs,
            kind: 'http',
            status: 429,
          },
        };
      }
    }
    for (const result of legResults) {
      if (!result.ok) {
        return {
          kind: 'error',
          upstreamError: {
            detail: 'upstream fetch failed',
            endpoint: params.endpoint,
            kind: 'network',
            status: result.status,
          },
        };
      }
    }
    for (const result of legResults) {
      if (result.ok && result.json === null) {
        return {
          kind: 'error',
          upstreamError: {
            detail: 'upstream response is not JSON',
            endpoint: params.endpoint,
            kind: 'parse',
            status: 502,
          },
        };
      }
    }
    const payload = normalizeYtmResponse(params, legs);
    if (payload !== null) {
      const validation = validateYtmPayload(params.endpoint, payload);
      if (!validation.ok) {
        // Valid upstream, failed normalization — 502, never cached.
        return {
          kind: 'error',
          upstreamError: {
            detail: `normalized payload failed validation: ${validation.reason}`,
            endpoint: params.endpoint,
            kind: 'parse',
            status: 502,
          },
        };
      }
    }
    const oversized = legResults.some((r) => r.byteLength > MAX_CACHED_JSON_BYTES);
    const cachedAt = now();
    const envelope: YtmEnvelope = {
      cachedAt,
      endpoint: params.endpoint,
      expiresAt: cachedAt + ttlMs,
      key,
      missing: payload === null,
      payload,
      v: 1,
    };
    if (!oversized) {
      memoryPut(key, envelope);
      void kvPut(env, key, envelope, ttlMs);
    }
    return { envelope, kind: 'envelope', oversized };
  })().finally(() => {
    inflight.delete(key);
  });
  inflight.set(key, promise);
  return promise;
}

/** One in-flight fill per key — identical concurrent requests coalesce. */
const inflight = new Map<string, Promise<FillOutcome>>();

// ---------------------------------------------------------------------------
// The route
// ---------------------------------------------------------------------------

export interface YtmRouteDeps {
  /** Injectable for the relay test lane (single-flight assertions). */
  fetchLeg?: LegFetcher;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

/**
 * GET /ytm/meta?endpoint=…&id=…&q=…&gl=…&hl=…&continuation=…&filter=…
 * 200 → { v: 1, cache: { key, state, cachedAt, ttlMs, expiresAt,
 *        uncached? }, payload?, missing? }
 * 400 → invalid params · 429 → upstream rate limit (Retry-After honored)
 * 502 → upstream network/parse failure.
 */
export async function ytmMetaRoute(
  request: Request,
  env: YtmEnv,
  ctx: { waitUntil(promise: Promise<unknown>): void },
  deps: YtmRouteDeps = {},
): Promise<Response> {
  const url = new URL(request.url);
  const parsed = parseYtmMetaParams(url);
  if (!parsed.ok) {
    return ytmJsonResponse({ error: parsed.error }, 400);
  }
  const params = parsed.params;
  const now = deps.now ?? Date.now;
  const fetchImpl = deps.fetchImpl ?? fetch;
  const session = () => ytmSession(fetchImpl);
  const fetchLeg = deps.fetchLeg ?? defaultLegFetcher(session);

  const idOrQuery = params.query ?? params.id ?? '';
  const key = buildYtmCacheKey(params.endpoint, idOrQuery, params.gl, params.hl, params.continuation);
  const ttlMs = ttlForEndpoint(params.endpoint);

  // ---- Layer 1: memory ----
  let envelope = memoryGet(key);
  // ---- Layer 2: KV (binding absent → bypassed cleanly) ----
  if (envelope === null) {
    envelope = await kvGet(env, key);
    if (envelope !== null) memoryPut(key, envelope);
  }

  if (envelope !== null && envelope.expiresAt > now()) {
    return serveFromCache('fresh', key, envelope, ttlMs);
  }

  const staleHit = envelope !== null && staleServeable(envelope, ttlMs, now());
  const existing = inflight.get(key);
  if (existing !== undefined && staleHit && envelope !== null) {
    // An identical fill is already running; serve stale NOW (SWR) — the
    // fill replaces the entry when it lands.
    return serveFromCache('stale', key, envelope, ttlMs);
  }

  if (staleHit && envelope !== null) {
    // Stale serve + policy-gated background revalidation.
    if (shouldBackgroundRevalidate(params.endpoint, envelope)) {
      const fillPromise =
        inflight.get(key) ??
        startFill(key, params, fetchLeg, env, ttlMs, now);
      ctx.waitUntil(fillPromise.then(() => undefined, () => undefined));
    }
    return serveFromCache('stale', key, envelope, ttlMs);
  }

  // ---- Miss: single-flight fill (an existing promise is awaited). ----
  const fillPromise =
    existing ?? startFill(key, params, fetchLeg, env, ttlMs, now);
  const fill = await fillPromise;
  if (fill.kind === 'error') {
    const status = fill.upstreamError.status === 429 ? 429 : 502;
    const headers: Record<string, string> = {};
    if (fill.upstreamError.retryAfterMs !== undefined) {
      headers['Retry-After'] = String(Math.ceil(fill.upstreamError.retryAfterMs / 1000));
    }
    return ytmJsonResponse(
      {
        detail: fill.upstreamError.detail,
        endpoint: fill.upstreamError.endpoint,
        ...(fill.upstreamError.retryAfterMs !== undefined
          ? { retryAfterMs: fill.upstreamError.retryAfterMs }
          : {}),
      },
      status,
      headers,
    );
  }
  return renderFill(key, fill.envelope, ttlMs, fill.oversized);
}

function serveFromCache(
  state: 'fresh' | 'stale',
  key: string,
  envelope: YtmEnvelope,
  ttlMs: number,
): Response {
  const body: Record<string, unknown> = {
    cache: { cachedAt: envelope.cachedAt, expiresAt: envelope.expiresAt, key, state, ttlMs },
    v: 1,
  };
  if (envelope.missing) body.missing = true;
  if (envelope.payload !== null) body.payload = envelope.payload;
  return ytmJsonResponse(body, 200);
}

function renderFill(key: string, envelope: YtmEnvelope, ttlMs: number, oversized: boolean): Response {
  const body: Record<string, unknown> = {
    cache: {
      cachedAt: envelope.cachedAt,
      expiresAt: envelope.expiresAt,
      key,
      state: 'fill',
      ttlMs,
      ...(oversized ? { uncached: true } : {}),
    },
    v: 1,
  };
  if (envelope.missing) body.missing = true;
  if (envelope.payload !== null) body.payload = envelope.payload;
  return ytmJsonResponse(body, 200);
}

/** JSON response with the relay's CORS posture (ytm-meta answers browsers). */
function ytmJsonResponse(body: unknown, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
      'Content-Type': 'application/json; charset=utf-8',
      ...headers,
    },
  });
}
