/// <reference types="vite/client" />
/**
 * lyrics/resolver.ts — the two-tier synced-lyrics fetcher (port of
 * app/…/data/network/LyricsResolver.kt, v1 web shape).
 *
 * THE LANE MAP (agreed for v1 — Musixmatch/NetEase deliberately skipped,
 * they need Cookie/Authority headers the GSF pipe rightly refuses and
 * relay changes that are not worth it yet):
 *
 *   Tier 0 — YTM ATV timed lyrics for the EXACT pinned videoId, via
 *            gsfFetch (Google-ASN egress). Ground truth: 0.00 s drift
 *            against the actual audio stream in use. Identity gate
 *            SKIPPED here — the pinned videoId IS the identity.
 *   Tier 1 — parallel verified race:
 *              1. YTM search sweep (top card + first shelf items, each
 *                 candidate title-verified, /next → /browse extraction)
 *                 — via gsfFetch
 *              2. LRCLIB (open CORS — direct browser fetch, no lane)
 *   Tier 2 — plain text → even-distribution synthesized timeline.
 *
 * Resolution hierarchy on return: YTM timed → LRCLIB synced → synthesized
 * plain. Results are cached in localStorage keyed by sanitized
 * "title|artist" + videoId (cache-aside, provider verdicts never re-fetch).
 *
 * WIRE DETAILS carried over from the Kotlin implementation:
 *   • ANDROID_MUSIC innertube client (clientName 21, v7.21.50, sdk 34) —
 *     the one YTM serves ATV timedLyricsData for.
 *   • /next resolves the MPLYt_* lyric browseId; /browse carries
 *     timedLyricsData[]. The /browse tree is scanned RECURSIVELY for
 *     "timedLyricsData" (YTM's nesting shifts — no rigid path).
 *   • The Songs-ATV search filter params string is passed verbatim.
 *   • The videoId regex fallback when the parsed shelf tree comes back
 *     in an unexpected shape.
 */

import { gsfFetch } from '../lib/gsfLane';
import {
  cleanArtist,
  cleanTitle,
  isValidVideoId,
  sanitizeCacheKeyArtist,
  sanitizeCacheKeyTitle,
} from './sanitize';
import {
  candidateArtistMatches,
  candidateDurationMatches,
  candidateTitleMatches,
} from './identityGate';
import { synthesizeSyncedLyricsFromPlain } from './lrc';

/** youtubei.js's public ANDROID_MUSIC API key (Constants.API_KEY, same as Kotlin's YTM_KEY). */
const YTM_KEY = 'AIzaSyC1xlRQImGslL28Q8HqTqD_o-w-r2Q_Z4';

const YTM_UA =
  'com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14)';

const NEXT_ENDPOINT = `https://youtubei.googleapis.com/youtubei/v1/next?key=${YTM_KEY}&prettyPrint=false`;
const BROWSE_ENDPOINT = `https://youtubei.googleapis.com/youtubei/v1/browse?key=${YTM_KEY}&prettyPrint=false`;
const SEARCH_ENDPOINT = `https://youtubei.googleapis.com/youtubei/v1/search?key=${YTM_KEY}&prettyPrint=false`;

/** Filter: Songs (ATVs) — passed verbatim from the Kotlin resolver. */
const SEARCH_PARAMS_SONGS_ATV = 'EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D';

// Kotlin timeout budget, in milliseconds.
const TIER0_TIMEOUT_MS = 4500;
const YTM_RACE_TIMEOUT_MS = 4000;
const LRCLIB_TIMEOUT_MS = 2500;

const VIDEO_ID_SCAN_REGEX = /"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"/g;

/** The resolver's public input contract. */
export interface LyricsRequest {
  title: string;
  artist: string;
  durationSec?: number;
  videoId?: string;
}

/** Which lane actually produced the accepted lyrics. */
export type LyricsSource = 'ytm-pinned' | 'ytm-search' | 'lrclib' | 'synthesized';

export interface LyricsResult {
  /** Raw LRC text (or synthesized LRC), ready for parseLrc(). */
  lrc: string;
  source: LyricsSource;
}

// ---------------------------------------------------------------------------
// localStorage cache (cache-aside, sanitized title|artist + videoId)
// ---------------------------------------------------------------------------

const CACHE_PREFIX = 'streamify.lyrics.';
const CACHE_MAX_ENTRIES = 200;

function cacheKey(req: LyricsRequest): string {
  const base = `${sanitizeCacheKeyTitle(req.title)}|${sanitizeCacheKeyArtist(req.artist)}`;
  return isValidVideoId(req.videoId) ? `${base}|${req.videoId}` : base;
}

interface CacheEntry {
  lrc: string;
  source: LyricsSource;
  ts: number;
}

function cacheGet(req: LyricsRequest): LyricsResult | null {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + cacheKey(req));
    if (raw == null) return null;
    const entry = JSON.parse(raw) as CacheEntry;
    if (typeof entry?.lrc !== 'string' || entry.lrc === '') return null;
    return { lrc: entry.lrc, source: entry.source };
  } catch {
    return null; // private mode / corrupt entry — cache is best-effort
  }
}

function cachePut(req: LyricsRequest, result: LyricsResult): void {
  try {
    // Bounded: evict oldest when over capacity (lyrics are tiny; 200 is
    // plenty and keeps localStorage polite).
    const keys: { k: string; ts: number }[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k != null && k.startsWith(CACHE_PREFIX)) {
        try {
          const e = JSON.parse(localStorage.getItem(k) ?? '{}') as CacheEntry;
          keys.push({ k, ts: Number(e?.ts) || 0 });
        } catch {
          keys.push({ k, ts: 0 });
        }
      }
    }
    if (keys.length >= CACHE_MAX_ENTRIES) {
      keys.sort((a, b) => a.ts - b.ts);
      for (const { k } of keys.slice(0, keys.length - CACHE_MAX_ENTRIES + 1)) {
        localStorage.removeItem(k);
      }
    }
    const entry: CacheEntry = { ...result, ts: Date.now() };
    localStorage.setItem(CACHE_PREFIX + cacheKey(req), JSON.stringify(entry));
  } catch {
    /* storage full / private mode — resolution still returns */
  }
}

/** Dev/diagnostics: drop the cached lyrics for one track. */
export function clearLyricsCache(req: LyricsRequest): void {
  try {
    localStorage.removeItem(CACHE_PREFIX + cacheKey(req));
  } catch {
    /* ignore */
  }
}

// ---------------------------------------------------------------------------
// YTM innertube plumbing (all through gsfFetch — Google-ASN egress)
// ---------------------------------------------------------------------------

function ytmContext(): Record<string, unknown> {
  return {
    clientName: 'ANDROID_MUSIC',
    clientVersion: '7.21.50',
    androidSdkVersion: 34,
    hl: 'en',
    gl: 'US',
  };
}

async function ytmPost(
  endpoint: string,
  payload: Record<string, unknown>,
  timeoutMs: number,
): Promise<unknown | null> {
  const res = await gsfFetch(endpoint, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'user-agent': YTM_UA,
      'x-youtube-client-name': '21',
      'x-youtube-client-version': '7.21.50',
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) return null;
  return (await res.json()) as unknown;
}

// ---- recursive JSON scanning (the anti-nesting-shift discipline) ----

function findKeyInJson(node: unknown, key: string): unknown | null {
  if (node === null || typeof node !== 'object') return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findKeyInJson(item, key);
      if (hit !== null) return hit;
    }
    return null;
  }
  const obj = node as Record<string, unknown>;
  if (Object.prototype.hasOwnProperty.call(obj, key)) return obj[key];
  for (const k of Object.keys(obj)) {
    const hit = findKeyInJson(obj[k], key);
    if (hit !== null) return hit;
  }
  return null;
}

/** Extracts the on-screen header title from a /next response, if present. */
function headerTitleFromNext(root: unknown): string | null {
  const header = findKeyInJson(root, 'musicResponsiveHeaderRenderer');
  if (header === null || typeof header !== 'object') return null;
  const title = (header as Record<string, unknown>)['title'];
  const runs = title !== null && typeof title === 'object'
    ? (title as Record<string, unknown>)['runs']
    : null;
  if (Array.isArray(runs) && runs.length > 0) {
    const first = runs[0] as Record<string, unknown>;
    const text = first['text'];
    return typeof text === 'string' && text !== '' ? text : null;
  }
  return null;
}

/**
 * Extracts YTM timed lyrics for one video (Kotlin tryExtractYtmTimedLyrics).
 *
 * @param expectedTitle when non-null, the video's on-screen title must
 *        match before lyrics are accepted; null (Tier 0) trusts the pinned
 *        videoId outright.
 */
async function tryExtractYtmTimedLyrics(
  videoId: string,
  expectedTitle: string | null,
): Promise<string | null> {
  try {
    // Step 1: /next → MPLYt_* browseId (+ optional title cross-check).
    const nextRoot = (await ytmPost(
      NEXT_ENDPOINT,
      { context: { client: ytmContext() }, videoId, isAudioOnly: true },
      TIER0_TIMEOUT_MS,
    )) as Record<string, unknown> | null;
    if (nextRoot === null) return null;

    if (expectedTitle !== null) {
      const headerText = headerTitleFromNext(nextRoot);
      if (
        headerText !== null &&
        !candidateTitleMatches(expectedTitle, headerText)
      ) {
        return null;
      }
    }

    let lyricBrowseId: string | null = null;
    const tabs = findKeyInJson(nextRoot, 'tabs');
    if (Array.isArray(tabs)) {
      for (const tabItem of tabs) {
        if (tabItem === null || typeof tabItem !== 'object') continue;
        const tab = (tabItem as Record<string, unknown>)['tabRenderer'];
        if (tab === null || typeof tab !== 'object') continue;
        const tabObj = tab as Record<string, unknown>;
        const endpoint = tabObj['endpoint'] as Record<string, unknown> | undefined;
        const browseId =
          endpoint !== null && typeof endpoint === 'object'
            ? endpoint!['browseId']
            : undefined;
        const title = tabObj['title'];
        if (
          (typeof title === 'string' && title.toUpperCase().includes('LYRIC')) ||
          (typeof browseId === 'string' && browseId.startsWith('MPLYt_'))
        ) {
          lyricBrowseId = typeof browseId === 'string' ? browseId : null;
          break;
        }
      }
    }
    if (lyricBrowseId == null) return null;

    // Step 2: /browse → timedLyricsData (recursive scan — nesting shifts).
    const browseRoot = (await ytmPost(
      BROWSE_ENDPOINT,
      { context: { client: ytmContext() }, browseId: lyricBrowseId },
      TIER0_TIMEOUT_MS,
    )) as Record<string, unknown> | null;
    if (browseRoot === null) return null;

    const timedData = findKeyInJson(browseRoot, 'timedLyricsData');
    if (!Array.isArray(timedData) || timedData.length === 0) return null;

    const sb: string[] = [];
    for (const item of timedData) {
      if (item === null || typeof item !== 'object') continue;
      const cue = (item as Record<string, unknown>)['cueRange'] as
        | Record<string, unknown>
        | undefined;
      const startMsStr = cue !== null && typeof cue === 'object' ? cue!['startTimeMilliseconds'] : undefined;
      const startMs =
        typeof startMsStr === 'string' ? Number(startMsStr) || 0 : 0;
      const text = (item as Record<string, unknown>)['lyricLine'];
      if (typeof text === 'string' && text.trim() !== '') {
        const mm = Math.floor(startMs / 60_000);
        const ss = Math.floor((startMs % 60_000) / 1000);
        const xx = Math.floor((startMs % 1000) / 10);
        sb.push(
          `[${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}.${String(xx).padStart(2, '0')}]${text.trim()}`,
        );
      }
    }
    return sb.length > 0 ? sb.join('\n') : null;
  } catch {
    return null; // single provider error never fails the resolver
  }
}

// ---------------------------------------------------------------------------
// Tier 1a: YTM search sweep (candidates → verified /next→/browse extraction)
// ---------------------------------------------------------------------------

async function resolveTopAtvCandidates(
  title: string,
  artist: string,
): Promise<string[]> {
  const ids: string[] = [];
  try {
    const bodyText = await (async (): Promise<string | null> => {
      const res = await gsfFetch(SEARCH_ENDPOINT, {
        method: 'POST',
          headers: {
            'content-type': 'application/json',
            'user-agent': YTM_UA,
            'x-youtube-client-name': '21',
            'x-youtube-client-version': '7.21.50',
          },
          body: JSON.stringify({
            context: { client: ytmContext() },
            query: `${title} ${artist}`,
            params: SEARCH_PARAMS_SONGS_ATV,
          }),
          signal: AbortSignal.timeout(YTM_RACE_TIMEOUT_MS),
        },
      );
      if (!res.ok) return null;
      return await res.text();
    })();
    if (bodyText == null) return ids;

    let json: unknown;
    try {
      json = JSON.parse(bodyText);
    } catch {
      json = null;
    }

    // 1. musicCardShelfRenderer (Top result card) + 2. musicShelfRenderer items.
    if (json !== null) {
      const cardShelf = findKeyInJson(json, 'musicCardShelfRenderer');
      if (cardShelf !== null && typeof cardShelf === 'object') {
        const onTap = (cardShelf as Record<string, unknown>)['onTap'] as
          | Record<string, unknown>
          | undefined;
        const watch =
          onTap !== null && typeof onTap === 'object'
            ? (onTap as Record<string, unknown>)['watchEndpoint']
            : undefined;
        const topId =
          watch !== null && typeof watch === 'object'
            ? (watch as Record<string, unknown>)['videoId']
            : undefined;
        if (typeof topId === 'string' && topId !== '' && !ids.includes(topId)) {
          ids.push(topId);
        }
      }

      const shelf = findKeyInJson(json, 'musicShelfRenderer');
      if (shelf !== null && typeof shelf === 'object') {
        const items = (shelf as Record<string, unknown>)['contents'];
        if (Array.isArray(items)) {
          for (const item of items.slice(0, 3)) {
            if (item === null || typeof item !== 'object') continue;
            const renderer = (item as Record<string, unknown>)[
              'musicResponsiveListItemRenderer'
            ] as Record<string, unknown> | undefined;
            if (renderer === null || typeof renderer !== 'object') continue;
            const playlistData = renderer!['playlistItemData'] as
              | Record<string, unknown>
              | undefined;
            const vid =
              playlistData !== null && typeof playlistData === 'object'
                ? playlistData!['videoId']
                : undefined;
            if (typeof vid === 'string' && vid !== '' && !ids.includes(vid)) {
              ids.push(vid);
            }
          }
        }
      }
    }

    // Fallback scan if the tree was different (Kotlin's regex sweep, capped at 3).
    if (ids.length === 0) {
      for (const m of bodyText.matchAll(VIDEO_ID_SCAN_REGEX)) {
        const vid = m[1];
        if (!ids.includes(vid)) {
          ids.push(vid);
          if (ids.length >= 3) break;
        }
      }
    }
  } catch {
    /* single provider error — empty candidates */
  }
  return ids.slice(0, 3);
}

async function fetchYouTubeMusicTimedLyrics(
  sanitizedTitle: string,
  sanitizedArtist: string,
): Promise<string | null> {
  try {
    const candidates = await resolveTopAtvCandidates(sanitizedTitle, sanitizedArtist);
    for (const vid of candidates) {
      const timedLrc = await tryExtractYtmTimedLyrics(vid, sanitizedTitle);
      if (timedLrc !== null && timedLrc.trim() !== '') {
        return timedLrc;
      }
    }
  } catch {
    /* ignore single provider error */
  }
  return null;
}

// ---------------------------------------------------------------------------
// Tier 1b: LRCLIB (open CORS — direct browser fetch, no lane)
// ---------------------------------------------------------------------------

interface LrcLibHit {
  synced: string | null;
  plain: string | null;
}

function verifyLrclibEntry(
  entry: Record<string, unknown>,
  title: string,
  artist: string,
  durationSec: number,
): boolean {
  const okTitle = candidateTitleMatches(title, str(entry['trackName']));
  const okArtist = candidateArtistMatches(artist, str(entry['artistName']));
  const okDuration = candidateDurationMatches(
    durationSec,
    num(entry['duration']),
  );
  const instrumental = entry['instrumental'] === true;
  return okTitle && okArtist && okDuration && !instrumental;
}

function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}

function num(v: unknown): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return 0;
}

async function lrclibGet(url: string): Promise<unknown | null> {
  const res = await fetch(url, {
    method: 'GET',
    credentials: 'omit',
    signal: AbortSignal.timeout(LRCLIB_TIMEOUT_MS),
  });
  if (!res.ok) return null;
  return (await res.json()) as unknown;
}

/**
 * LRCLIB resolver with metadata verification on both endpoints:
 * /api/get echoes matched trackName/artistName/duration — mismatching
 * responses are rejected and the verified /api/search pass runs instead.
 */
async function fetchLrclibVerified(
  title: string,
  artist: string,
  durationSec: number,
): Promise<LrcLibHit | null> {
  try {
    // ---- Pass 1: exact endpoint ----
    let getUrl =
      `https://lrclib.net/api/get?track_name=${encodeURIComponent(title)}` +
      `&artist_name=${encodeURIComponent(artist)}`;
    if (durationSec > 0) getUrl += `&duration=${durationSec}`;

    const direct = await lrclibGet(getUrl);
    if (direct !== null && typeof direct === 'object') {
      const entry = direct as Record<string, unknown>;
      if (verifyLrclibEntry(entry, title, artist, durationSec)) {
        const synced = str(entry['syncedLyrics']);
        if (synced !== null && synced !== '' && synced.includes('[')) {
          return { synced, plain: null };
        }
        const plain = str(entry['plainLyrics']);
        if (plain !== null && plain.length > 20) {
          return { synced: null, plain };
        }
      }
    }

    // ---- Pass 2: verified search endpoint ----
    const q = encodeURIComponent(`${title} ${artist}`);
    const res = await fetch(`https://lrclib.net/api/search?q=${q}`, {
      method: 'GET',
      credentials: 'omit',
      signal: AbortSignal.timeout(LRCLIB_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    const array = (await res.json()) as unknown;
    if (!Array.isArray(array)) return null;

    let plainBackup: string | null = null;
    for (const item of array) {
      if (item === null || typeof item !== 'object') continue;
      const entry = item as Record<string, unknown>;
      if (!verifyLrclibEntry(entry, title, artist, durationSec)) continue;

      const synced = str(entry['syncedLyrics']);
      if (synced !== null && synced !== '' && synced.includes('[')) {
        return { synced, plain: null };
      }
      const plain = str(entry['plainLyrics']);
      if ((plainBackup === null || plainBackup === '') && plain !== null && plain.length > 20) {
        plainBackup = plain;
      }
    }
    if (plainBackup !== null) return { synced: null, plain: plainBackup };
  } catch {
    /* single provider error — empty result */
  }
  return null;
}

// ---------------------------------------------------------------------------
// The main entrypoint (Kotlin fetchSyncedLyrics, v1 two-tier shape)
// ---------------------------------------------------------------------------

/**
 * Fetches synced lyrics for a track. Cache-aside: a cached hit returns
 * without touching the network. Never throws — a total miss returns null.
 */
export async function fetchSyncedLyrics(
  req: LyricsRequest,
): Promise<LyricsResult | null> {
  const cached = cacheGet(req);
  if (cached !== null) return cached;

  const sanitizedTitle = cleanTitle(req.title);
  const sanitizedArtist = cleanArtist(req.artist);
  const durationSec = req.durationSec ?? 0;

  if (sanitizedTitle.trim() === '') return null;

  // -------------------------------------------------------------------
  // TIER 0: exact pinned video identity (no verification — the pinned
  // videoId IS the identity; 0.00 s drift against the played audio).
  // -------------------------------------------------------------------
  if (isValidVideoId(req.videoId)) {
    const pinnedVideoId = req.videoId as string;
    const exact = await withTimeout(
      tryExtractYtmTimedLyrics(pinnedVideoId, null),
      TIER0_TIMEOUT_MS,
    );
    if (exact !== null && exact.trim() !== '' && exact.includes('[')) {
      const result: LyricsResult = { lrc: exact, source: 'ytm-pinned' };
      cachePut(req, result);
      return result;
    }
  }

  // -------------------------------------------------------------------
  // TIER 1: parallel verified provider race.
  // -------------------------------------------------------------------
  const [ytmResult, lrcHit] = await Promise.all([
    withTimeout(
      fetchYouTubeMusicTimedLyrics(sanitizedTitle, sanitizedArtist),
      YTM_RACE_TIMEOUT_MS + TIER0_TIMEOUT_MS, // sweep = search + up to 3 extractions
    ),
    withTimeout(
      fetchLrclibVerified(sanitizedTitle, sanitizedArtist, durationSec),
      LRCLIB_TIMEOUT_MS * 2, // both passes
    ),
  ]);

  // Verified hierarchy:
  // 1. YouTube Music TimedLyrics (line-level, 0.00 s video sync)
  if (ytmResult !== null && ytmResult.includes('[')) {
    const result: LyricsResult = { lrc: ytmResult, source: 'ytm-search' };
    cachePut(req, result);
    return result;
  }
  // 2. LRCLIB synced
  const lrcSynced = lrcHit?.synced ?? null;
  if (lrcSynced !== null && lrcSynced.includes('[')) {
    const result: LyricsResult = { lrc: lrcSynced, source: 'lrclib' };
    cachePut(req, result);
    return result;
  }
  // 3. Plain text fallback → synthesize an evenly distributed timeline.
  const lrcPlain = lrcHit?.plain ?? null;
  if (lrcPlain !== null && lrcPlain.length > 20) {
    const result: LyricsResult = {
      lrc: synthesizeSyncedLyricsFromPlain(lrcPlain, durationSec),
      source: 'synthesized',
    };
    cachePut(req, result);
    return result;
  }

  return null;
}

/** withTimeoutOrNull — null on timeout, never rejects. */
async function withTimeout<T>(p: Promise<T>, ms: number): Promise<T | null> {
  try {
    return await Promise.race([
      p,
      new Promise<null>((resolve) => setTimeout(() => resolve(null), ms)),
    ]);
  } catch {
    return null;
  }
}
