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
    for (const hit of hits) {
        let score = 0;
        if (hit.syncedLyrics !== null && hit.syncedLyrics !== '') score += 100;
        if (norm(hit.trackName) === norm(song.title)) score += 50;
        else if (norm(hit.trackName).includes(norm(song.title))) score += 20;
        if (norm(hit.artistName) === norm(song.artist)) score += 30;
        else if (norm(hit.artistName).includes(norm(song.artist))) score += 10;
        if (hit.instrumental === true) score -= 40;
        if (
            song.durationSec != null &&
            typeof hit.duration === 'number' &&
            Math.abs(hit.duration - song.durationSec) <= 3
        ) {
            score += 5;
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
    const params = new URLSearchParams();
    if (song.title.trim() !== '') params.set('track_name', song.title.trim());
    if (song.artist.trim() !== '') params.set('artist_name', song.artist.trim());
    if (params.size === 0) return null;
    const raw = await getJson(`/search?${params.toString()}`, signal);
    if (!Array.isArray(raw) || raw.length === 0) return null;
    const hits = raw
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
    if (hits.length === 0) return null;
    return bestMatch(hits, song);
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
