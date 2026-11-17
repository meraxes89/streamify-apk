/**
 * w1b-lyrics-fallback.test.ts — the two-tier lyrics lock (W1-B):
 *
 *   - LRCLIB hit wins (tier 1 preserved — timestamped LRC first choice);
 *   - a LRCLIB plain-only hit does NOT shadow a YTM synced result
 *     (synced: true beats plain text ACROSS tiers);
 *   - a LRCLIB miss falls back to the YTM tier (plain when that is all
 *     there is);
 *   - a total miss resolves the honest [] (the pre-W1-A stub included);
 *   - LRC parsing is exact; a lying synced flag degrades to plain.
 *
 * Network-free by construction: the LRCLIB fetcher is injected, the
 * provider is a fake.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The unified provider (#200) carries W1-A's engine, whose module scope
// touches the logger; the node lane has no localStorage — mock it like
// metadata-provider.test.ts does.
vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import type { SongRow } from '../library';

import {
    bestMatch,
    getLyricsBySongId,
    type LrclibSearchResult,
    searchLrclib,
    setLrclibFetcherForTests,
} from '../lyrics/lrclib-web';
import {
    fetchLyricsText,
    fetchStructuredLyrics,
    orderCandidates,
    parseLrc,
    stripLrcTimestamps,
    toStructuredLyric,
} from '../lyrics/lyrics-pipeline';
import {
    type MetadataProvider,
    setMetadataProviderForTests,
    stubMetadataProvider,
} from '../metadata/provider';

function songRow(overrides: Partial<SongRow> = {}): SongRow {
    return {
        addedRank: 0,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 213,
        id: 'trk_1',
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: 'vid_1',
        title: 'Title',
        ...overrides,
    };
}

const SYNCED_LRC = '[00:01.500] first line\n[00:04.250] second line\n[01:02.000] third line';
const PLAIN = 'first line\nsecond line\nthird line';

function fakeProvider(lyrics: null | { lyrics: string; synced: boolean }): MetadataProvider {
    return {
        ...stubMetadataProvider,
        getLyrics: vi.fn(async () => lyrics),
    };
}

/** Route the injected fetcher: /search and /get/{id}. */
function installLrclib(
    search: LrclibSearchResult[] | null,
    byId?: LrclibSearchResult | null,
): void {
    setLrclibFetcherForTests(async (input: string) => {
        if (input.includes('/search')) {
            return { json: async () => search ?? [], ok: true, status: 200 };
        }
        if (input.includes('/get/')) {
            return { json: async () => byId ?? null, ok: byId !== null, status: byId ? 200 : 404 };
        }
        return { json: async () => null, ok: false, status: 404 };
    });
}

function lrclibHit(overrides: Partial<LrclibSearchResult> = {}): LrclibSearchResult {
    return {
        albumName: 'Album',
        artistName: 'Artist',
        duration: 213,
        id: 42,
        instrumental: false,
        plainLyrics: PLAIN,
        syncedLyrics: SYNCED_LRC,
        trackName: 'Title',
        ...overrides,
    };
}

beforeEach(() => {
    installLrclib(null);
    setMetadataProviderForTests(null);
});

afterEach(() => {
    setLrclibFetcherForTests(null);
    setMetadataProviderForTests(null);
});

describe('parseLrc (the LRC grammar)', () => {
    it('parses [mm:ss.SSS] timestamps into sorted SynchronizedLyrics', () => {
        const lines = parseLrc(SYNCED_LRC);
        expect(lines).toEqual([
            { startMs: 1500, text: 'first line' },
            { startMs: 4250, text: 'second line' },
            { startMs: 62000, text: 'third line' },
        ]);
    });

    it('parses two-digit fractions as centiseconds and skips empty text', () => {
        const lines = parseLrc('[00:10.50] has text\n[00:20.00]');
        expect(lines).toEqual([{ startMs: 10500, text: 'has text' }]);
    });

    it('stripLrcTimestamps degrades a synced payload to plain text', () => {
        expect(stripLrcTimestamps(SYNCED_LRC)).toBe(PLAIN);
    });
});

describe('the two-tier precedence (orderCandidates)', () => {
    it('LRCLIB synced > YTM synced > LRCLIB plain > YTM plain', () => {
        const ordered = orderCandidates(lrclibHit(), [
            { lang: 'en', lyrics: 'ytm plain', source: 'YouTube Music', synced: false },
            { lang: 'en', lyrics: 'ytm synced', source: 'YouTube Music', synced: true },
        ]);
        expect(ordered.map((c) => c.lyrics)).toEqual([
            SYNCED_LRC,
            'ytm synced',
            PLAIN,
            'ytm plain',
        ]);
    });

    it('a LRCLIB plain-only hit loses to a YTM synced result', () => {
        const ordered = orderCandidates(lrclibHit({ syncedLyrics: null }), [
            { lang: 'en', lyrics: 'ytm synced', source: 'YouTube Music', synced: true },
        ]);
        expect(ordered[0]?.synced).toBe(true);
        expect(ordered[0]?.source).toBe('YouTube Music');
    });
});

describe('fetchStructuredLyrics (the pipeline)', () => {
    it('an LRCLIB synced hit wins even when the YTM tier also has lyrics', async () => {
        installLrclib([lrclibHit()]);
        setMetadataProviderForTests(fakeProvider({ lyrics: '[00:00.000] ytm line', synced: true }));
        const out = await fetchStructuredLyrics(songRow());
        expect(out).toHaveLength(1);
        expect(out[0]?.synced).toBe(true);
        expect(out[0]?.source).toBe('lrclib.net');
        expect(out[0]?.lyrics).toEqual([
            { startMs: 1500, text: 'first line' },
            { startMs: 4250, text: 'second line' },
            { startMs: 62000, text: 'third line' },
        ]);
    });

    it('an LRCLIB miss falls back to YTM synced over plain text', async () => {
        installLrclib([]);
        setMetadataProviderForTests(
            fakeProvider({ lyrics: '[00:02.000] ytm synced line', synced: true }),
        );
        const out = await fetchStructuredLyrics(songRow());
        expect(out).toHaveLength(1);
        expect(out[0]?.synced).toBe(true);
        expect(out[0]?.source).toBe('YouTube Music');
        expect(out[0]?.lyrics).toEqual([{ startMs: 2000, text: 'ytm synced line' }]);
    });

    it('an LRCLIB plain-only hit plus a YTM plain result resolves LRCLIB plain (tier order within plain)', async () => {
        installLrclib([lrclibHit({ syncedLyrics: null })]);
        setMetadataProviderForTests(fakeProvider({ lyrics: 'ytm plain', synced: false }));
        const out = await fetchStructuredLyrics(songRow());
        expect(out[0]?.synced).toBe(false);
        expect(out[0]?.source).toBe('lrclib.net');
        expect(out[0]?.lyrics).toBe(PLAIN);
    });

    it('a total miss resolves the honest []', async () => {
        installLrclib(null);
        setMetadataProviderForTests(fakeProvider(null));
        expect(await fetchStructuredLyrics(songRow())).toEqual([]);
    });

    it('the pre-W1-A stub provider keeps the honest [] on an LRCLIB miss', async () => {
        installLrclib([]);
        setMetadataProviderForTests(null); // stub
        expect(await fetchStructuredLyrics(songRow())).toEqual([]);
    });

    it('a lying synced flag (no parseable timestamps) degrades to an honest plain DTO', async () => {
        installLrclib([]);
        setMetadataProviderForTests(fakeProvider({ lyrics: 'no timestamps here', synced: true }));
        const out = await fetchStructuredLyrics(songRow());
        expect(out[0]?.synced).toBe(false);
        expect(out[0]?.lyrics).toBe('no timestamps here');
    });

    it('fetchLyricsText returns the parsed lines for a synced result, the string otherwise', async () => {
        installLrclib([lrclibHit()]);
        const syncedText = await fetchLyricsText(songRow());
        expect(Array.isArray(syncedText)).toBe(true);

        installLrclib([lrclibHit({ syncedLyrics: null })]);
        const plainText = await fetchLyricsText(songRow());
        expect(plainText).toBe(PLAIN);
    });

    it('toStructuredLyric carries the song facts onto the DTO', () => {
        const lyric = toStructuredLyric(songRow(), {
            lang: 'en',
            lyrics: SYNCED_LRC,
            source: 'lrclib.net',
            synced: true,
        });
        expect(lyric.name).toBe('Title');
        expect(lyric.artist).toBe('Artist');
        expect(lyric.remote).toBe(false);
    });
});

describe('the LRCLIB web client', () => {
    it('getLyricsBySongId fetches /api/get/{id} (Electron naming parity)', async () => {
        installLrclib(null, lrclibHit({ id: 7 }));
        const hit = await getLyricsBySongId(7);
        expect(hit?.id).toBe(7);
        expect(hit?.syncedLyrics).toBe(SYNCED_LRC);
    });

    it('searchLrclib resolves null on a 404 / empty result (never a throw)', async () => {
        installLrclib(null);
        expect(await searchLrclib(songRow())).toBeNull();
        installLrclib([]);
        expect(await searchLrclib(songRow())).toBeNull();
    });

    it('bestMatch ranks synced over plain, then title/artist fidelity', () => {
        const plainExact = lrclibHit({ id: 1, syncedLyrics: null });
        const syncedFuzzy = lrclibHit({ id: 2, trackName: 'Title (remaster)' });
        expect(bestMatch([plainExact, syncedFuzzy], songRow())?.id).toBe(2);
    });

    it('bestMatch prefers the ±3s duration match', () => {
        const off = lrclibHit({ duration: 300, id: 1 });
        const near = lrclibHit({ duration: 214, id: 2 });
        expect(bestMatch([off, near], songRow())?.id).toBe(2);
    });
});
