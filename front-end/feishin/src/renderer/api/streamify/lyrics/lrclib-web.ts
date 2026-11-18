/**
 * api/streamify/lyrics/lrclib-web.ts — the tier-1 LRCLIB lane for the web
 * build (W1-B).
 *
 * WHY THIS EXISTS: upstream's remote-lyrics tier is Electron-only
 * (features/lyrics/api/lyrics-api.ts reads window.api.lyrics, which is
 * null on the web build). Feishin's own tier-1 source is LRCLIB — the
 * same public API the Electron main process calls
 * (src/main/features/core/lyrics/lrclib.ts) — and lrclib.net is CORS-open,
 * so the renderer can call it directly with zero relay involvement. This
 * module mirrors that client call-for-call (getLyricsBySongId included,
 * same naming) so the two-tier pipeline (lyrics-pipeline.ts) keeps
 * Feishin's primary lookup semantics: timestamped LRC from LRCLIB is
 * always the first choice.
 *
 * Honesty rules: a miss resolves null (never fabricated); the fetch
 * implementation is injectable for the network-free vitest suites.
 */

import type { SongRow } from '../library';

const LRCLIB_BASE = 'https://lrclib.net/api';

export type LrclibFetch = (
    input: string,
    init?: { signal?: AbortSignal },
) => Promise<{ json: () => Promise<unknown>; ok: boolean; status: number }>;

/** The search-hit shape lrclib returns (a superset we consume honestly).
 *  Both fetchers normalize the optional wire fields to null, so the
 *  pipeline never sees undefined. */
export interface LrclibSearchResult {
    albumName: null | string;
    artistName: string;
    duration: null | number;
    id: number;
    instrumental: boolean;
    plainLyrics: null | string;
    syncedLyrics: null | string;
    trackName: string;
}

let fetchImpl: LrclibFetch | null = null;

export function cleanTitleForLyrics(raw: string): string {
    return raw
        .replace(/\s*[\(\[](?:official|music|video|audio|lyric(?:s)?|visualizer|hd|4k|mv|remastered|version|edit|extended)[^\)\]]*[\)\]]/gi, '')
        .replace(/\s*-\s*(?:official|music|video|audio|lyric(?:s)?|visualizer).*/gi, '')
        .replace(/\s*(?:ft\.|feat\.|featuring)\s+.*$/gi, '')
        .replace(/\s{2,}/g, ' ')
        .trim();
}

/**
 * Rank search hits: synced beats plain (the two-tier rule), then title
 * fidelity, then artist fidelity, then duration proximity (±3 s window,
 * the same tolerance the Electron client's match threshold implies).
 */
export function bestMatch(
    hits: LrclibSearchResult[],
    song: Pick<SongRow, 'album' | 'artist' | 'durationSec' | 'title'>,
): LrclibSearchResult | null {
    let best: LrclibSearchResult | null = null;
    let bestScore = -Infinity;
    const cleanedTitle = cleanTitleForLyrics(song.title);
    for (const hit of hits) {
        let score = 0;
        if (hit.syncedLyrics !== null && hit.syncedLyrics !== '') score += 100;
        const normHitTrack = norm(hit.trackName);
        const normSongTitle = norm(song.title);
        const normCleaned = norm(cleanedTitle);

        if (normHitTrack === normSongTitle || (normCleaned !== '' && normHitTrack === normCleaned)) score += 50;
        else if (normHitTrack.includes(normSongTitle) || normSongTitle.includes(normHitTrack)) score += 25;
        else if (normCleaned !== '' && (normHitTrack.includes(normCleaned) || normCleaned.includes(normHitTrack))) score += 20;

        const normHitArtist = norm(hit.artistName);
        const normSongArtist = norm(song.artist);
        if (normHitArtist === normSongArtist) score += 30;
        else if (normHitArtist.includes(normSongArtist) || normSongArtist.includes(normHitArtist)) score += 15;

        if (hit.instrumental === true) score -= 40;
        if (
            song.durationSec != null &&
            typeof hit.duration === 'number' &&
            Math.abs(hit.duration - song.durationSec) <= 3
        ) {
            score += 10;
        }
        if (score > bestScore) {
            bestScore = score;
            best = hit;
        }
    }
    return best;
}

/** Fetch lyrics by the lrclib track id (parity with the Electron client). */
export async function getLyricsBySongId(
    songId: number,
    signal?: AbortSignal,
): Promise<LrclibSearchResult | null> {
    const raw = await getJson(`/get/${songId}`, signal);
    if (typeof raw !== 'object' || raw === null) return null;
    const r = raw as Partial<LrclibSearchResult>;
    if (typeof r.id !== 'number' || typeof r.trackName !== 'string') return null;
    return {
        albumName: r.albumName ?? null,
        artistName: r.artistName ?? '',
        duration: typeof r.duration === 'number' ? r.duration : null,
        id: r.id,
        instrumental: r.instrumental === true,
        plainLyrics: r.plainLyrics ?? null,
        syncedLyrics: r.syncedLyrics ?? null,
        trackName: r.trackName,
    };
}

function parseHits(raw: unknown): LrclibSearchResult[] {
    if (!Array.isArray(raw) || raw.length === 0) return [];
    return raw
        .map((row): LrclibSearchResult | null => {
            if (typeof row !== 'object' || row === null) return null;
            const r = row as Partial<LrclibSearchResult>;
            if (typeof r.id !== 'number' || typeof r.trackName !== 'string') return null;
            return {
                albumName: r.albumName ?? null,
                artistName: typeof r.artistName === 'string' ? r.artistName : '',
                duration: typeof r.duration === 'number' ? r.duration : null,
                id: r.id,
                instrumental: r.instrumental === true,
                plainLyrics: typeof r.plainLyrics === 'string' ? r.plainLyrics : null,
                syncedLyrics: typeof r.syncedLyrics === 'string' ? r.syncedLyrics : null,
                trackName: r.trackName,
            };
        })
        .filter((row): row is LrclibSearchResult => row !== null);
}

/**
 * Search LRCLIB for the song's lyrics. The `/api/search` endpoint accepts
 * track/artist/album filters; we query the fuzzy free-text form (the same
 * one the Electron client uses via `?q=`) plus the structured form when we
 * have the facts, taking whichever answers first with a synced hit.
 */
export async function searchLrclib(
    song: Pick<SongRow, 'album' | 'artist' | 'durationSec' | 'title'>,
    signal?: AbortSignal,
): Promise<LrclibSearchResult | null> {
    let cleanedTitle = cleanTitleForLyrics(song.title);
    if (cleanedTitle.includes(' - ')) {
        const parts = cleanedTitle.split(' - ');
        if (parts.length === 2 && norm(parts[0]) === norm(song.artist)) {
            cleanedTitle = parts[1].trim();
        }
    }

    // Tier 1a: Try cleaned structured query if title was stripped of noise
    if (cleanedTitle !== '' && cleanedTitle !== song.title.trim()) {
        const params = new URLSearchParams();
        params.set('track_name', cleanedTitle);
        if (song.artist.trim() !== '') params.set('artist_name', song.artist.trim());
        const raw = await getJson(`/search?${params.toString()}`, signal);
        const hits = parseHits(raw);
        if (hits.length > 0) {
            const match = bestMatch(hits, song);
            if (match) return match;
        }
    }

    // Tier 1b: Try raw structured query
    if (song.title.trim() !== '' || song.artist.trim() !== '') {
        const params = new URLSearchParams();
        if (song.title.trim() !== '') params.set('track_name', song.title.trim());
        if (song.artist.trim() !== '') params.set('artist_name', song.artist.trim());
        const raw = await getJson(`/search?${params.toString()}`, signal);
        const hits = parseHits(raw);
        if (hits.length > 0) {
            const match = bestMatch(hits, song);
            if (match) return match;
        }
    }

    // Tier 1c: Try fuzzy text query with artist + title
    const searchTarget = `${song.artist.trim()} ${cleanedTitle || song.title.trim()}`.trim();
    if (searchTarget !== '') {
        const raw = await getJson(`/search?q=${encodeURIComponent(searchTarget)}`, signal);
        const hits = parseHits(raw);
        if (hits.length > 0) {
            const match = bestMatch(hits, song);
            if (match) return match;
        }
    }

    // Tier 1d: Try fuzzy text query with title only
    const titleOnly = (cleanedTitle || song.title.trim()).trim();
    if (titleOnly !== '' && titleOnly !== searchTarget) {
        const raw = await getJson(`/search?q=${encodeURIComponent(titleOnly)}`, signal);
        const hits = parseHits(raw);
        if (hits.length > 0) {
            return bestMatch(hits, song);
        }
    }

    return null;
}

/** Test seam: inject a fake fetch (network-free vitest lane). */
export function setLrclibFetcherForTests(fetcher: LrclibFetch | null): void {
    fetchImpl = fetcher;
}

async function getJson(path: string, signal?: AbortSignal): Promise<null | unknown> {
    try {
        const res = await theFetcher()(`${LRCLIB_BASE}${path}`, { signal });
        if (!res.ok) return null; // 404 and friends: an honest miss
        return await res.json();
    } catch {
        return null; // offline / aborted — a miss, never a throw
    }
}

function norm(value: null | string | undefined): string {
    return (value ?? '').trim().toLowerCase();
}

function theFetcher(): LrclibFetch {
    return fetchImpl ?? ((input, init) => fetch(input, init));
}
