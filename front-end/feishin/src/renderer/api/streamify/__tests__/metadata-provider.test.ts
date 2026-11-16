/**
 * metadata-provider.test.ts — the W1-A seam's network-free lock.
 *
 * Covers the task DoD: "metadata/provider.ts normalization, snapshot
 * upsert, and 404 vs 429 backoff separation". The edge tier is a fake
 * EdgeFetch (counted), the fallback tier is a mocked resolver session
 * (the youtubei.js lane), the diagnostics go to the mocked logger.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('../playback/resolver/lib/ytSession', () => ({
    getYt: vi.fn(),
}));

import type { EdgeFetch } from '../metadata/edge';
import type { LyricsPayload, PlaylistPayload } from '../metadata/types';

import { resetInnertubeFallbackForTests } from '../metadata/innertube-fallback';
import {
    createMetadataProviderForTests,
    resetMetadataProviderForTests,
} from '../metadata/provider';
import { resetMetadataSnapshotForTests, snapshotKey, snapshotLookup } from '../metadata/snapshot';
import { getYt } from '../playback/resolver/lib/ytSession';

import { logger } from '/@/renderer/utils/logger';

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

type Scripted =
    | { body: null; missing: true; status: 200 }
    | { body: null; status: 429 }
    | { body: null; status: 503 }
    | { body: unknown; status: 200 };

function edgeFetchScripted(
    script: Scripted[],
    log: { calls: string[]; urls: string[] },
): EdgeFetch {
    let index = 0;
    return (async (url: string) => {
        log.urls.push(url);
        log.calls.push(`#${index + 1}`);
        const entry = script[Math.min(index, script.length - 1)];
        index += 1;
        if (entry.status === 429) {
            return {
                header: () => '2',
                json: () => Promise.reject(new Error('no body')),
                status: 429,
            };
        }
        if (entry.status === 503) {
            return {
                header: () => null,
                json: () => Promise.reject(new Error('no body')),
                status: 503,
            };
        }
        if ('missing' in entry) {
            return {
                header: () => null,
                json: () => Promise.resolve({ cache: { state: 'fill' }, missing: true }),
                status: 200,
            };
        }
        return {
            header: () => null,
            json: () => Promise.resolve({ cache: { state: 'fill' }, payload: entry.body }),
            status: 200,
        };
    }) as EdgeFetch;
}

const LYRICS_PAYLOAD: LyricsPayload = {
    hl: 'en',
    kind: 'lyrics',
    lines: [
        { atMs: 1000, text: 'first' },
        { atMs: 5000, text: 'second' },
    ],
    source: 'Musixmatch',
    synced: true,
    videoId: 'VID1',
};

const PLAYLIST_PAGE1: PlaylistPayload = {
    continuation: 'cont-2',
    description: 'desc',
    kind: 'playlist',
    listId: 'PL1',
    title: 'Mix Vol. 1',
    trackCountTotal: 3,
    tracks: [
        {
            albumText: 'A',
            artistText: 'X',
            durationMs: 200000,
            index: 0,
            title: 'T1',
            videoId: 'v1',
        },
        {
            albumText: 'A',
            artistText: 'X',
            durationMs: 200000,
            index: 1,
            title: 'T2',
            videoId: 'v2',
        },
    ],
};

/** The mocked getYt resolver session: a youtubei.js-shaped double. */
const FAKE_YT = {
    music: {
        getAlbum: vi.fn(),
        getArtist: vi.fn(),
        getExplore: vi.fn(),
        getHomeFeed: vi.fn(),
        getLyrics: vi.fn(),
        getPlaylist: vi.fn(),
        getRelated: vi.fn(),
        getSearchSuggestions: vi.fn(),
        getUpNext: vi.fn(),
        search: vi.fn(),
    },
};

beforeEach(() => {
    vi.clearAllMocks();
    resetMetadataProviderForTests();
    resetMetadataSnapshotForTests();
    resetInnertubeFallbackForTests();
    vi.mocked(getYt).mockResolvedValue(FAKE_YT as never);
});

afterEach(() => {
    vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// The ok path: edge → snapshot upsert → snapshot hit
// ---------------------------------------------------------------------------

describe('W1-A provider — ok path + snapshot upsert', () => {
    it('serves from the edge, upserts the snapshot, then answers from the snapshot', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: LYRICS_PAYLOAD, status: 200 }], log),
        });
        const first = await provider.getLyrics('VID1', 'en');
        expect(first.kind).toBe('ok');
        if (first.kind !== 'ok') throw new Error('unreachable');
        expect(first.source).toBe('edge');
        expect(first.payload.synced).toBe(true); // synced flag preserved
        expect(first.payload.lines).toHaveLength(2);

        // The snapshot entry exists under the shared key discipline.
        const key = snapshotKey('lyrics', 'VID1', 'US', 'en');
        expect(snapshotLookup(key)?.payload?.kind).toBe('lyrics');

        // Second call: the snapshot tier answers — no further edge traffic.
        const before = log.calls.length;
        const second = await provider.getLyrics('VID1', 'en');
        expect(second.kind).toBe('ok');
        if (second.kind !== 'ok') throw new Error('unreachable');
        expect(second.source).toBe('snapshot');
        expect(log.calls.length).toBe(before);
    });

    it('builds the edge URL from the relay knob with the full param set', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: PLAYLIST_PAGE1, status: 200 }], log),
        });
        const res = await provider.getPlaylist('PL1', 'cont-2');
        expect(res.kind).toBe('ok');
        const url = log.urls[0];
        expect(url).toContain('/ytm/meta?');
        expect(url).toContain('endpoint=playlist');
        expect(url).toContain('id=PL1');
        expect(url).toContain('continuation=cont-2');
        void res;
    });
});

// ---------------------------------------------------------------------------
// 404 vs 429: the failure separation
// ---------------------------------------------------------------------------

describe('W1-A provider — 404 vs 429 separation', () => {
    it('not-found: zero retries, remembered 15 min, no fallback', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: null, missing: true, status: 200 }], log),
        });
        const first = await provider.getLyrics('GONE', 'en');
        expect(first.kind).toBe('not-found');
        // ZERO retry storm: exactly one edge call, zero fallback calls.
        expect(log.calls).toHaveLength(1);
        expect(FAKE_YT.music.getLyrics).not.toHaveBeenCalled();
        // The negative snapshot entry blocks re-hits within the window.
        const second = await provider.getLyrics('GONE', 'en');
        expect(second.kind).toBe('not-found');
        expect(log.calls).toHaveLength(1);
    });

    it('rate-limited: backoff 250→500, then the circuit opens for 60 s', async () => {
        const sleeps: number[] = [];
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        let clock = 1_000;
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: null, status: 429 }], log),
            now: () => clock,
            sleep: (ms) => {
                sleeps.push(ms);
                clock += ms; // the backoff advances the clock too
                return Promise.resolve();
            },
        });
        // The fallback also fails (empty music response) → rate-limited
        // surfaces after the circuit opens.
        FAKE_YT.music.getLyrics.mockResolvedValue(undefined);
        const first = await provider.getLyrics('VID1', 'en');
        expect(first.kind).toBe('rate-limited');
        // Three edge attempts. The doubling ramp runs underneath the
        // server's Retry-After floor (max(250, 2000) = 2000; the ramp
        // never catches up within three attempts — honest).
        expect(log.calls).toHaveLength(3);
        expect(sleeps).toEqual([2000, 2000]);
        // The circuit is open: a further call fails FAST (no edge traffic).
        const before = log.calls.length;
        const second = await provider.getLyrics('OTHER', 'en');
        expect(second.kind).toBe('rate-limited');
        expect(log.calls.length).toBe(before);
        // …and closes after the 60 s cooldown.
        clock += 61 * 1000;
        const third = await provider.getLyrics('THIRD', 'en');
        expect(third.kind).toBe('rate-limited'); // upstream still 429 in the fake
        expect(log.calls.length).toBe(before + 3); // a fresh backoff cycle ran
    });

    it('rate-limited edge but a working fallback still serves the payload', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        let clock = 1_000;
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: null, status: 429 }], log),
            now: () => clock,
            sleep: (ms) => {
                clock += ms;
                return Promise.resolve();
            },
        });
        FAKE_YT.music.getLyrics.mockResolvedValue({
            description: { text: '[00:01.00] alpha\n[00:05.00] beta' },
            footer: { text: 'Source: Musixmatch' },
        });
        const res = await provider.getLyrics('VID1', 'en');
        expect(res.kind).toBe('ok');
        if (res.kind !== 'ok') throw new Error('unreachable');
        expect(res.source).toBe('fallback');
        expect(res.payload.synced).toBe(true);
        expect(res.payload.lines[0]?.atMs).toBe(1000);
    });
});

// ---------------------------------------------------------------------------
// Parse errors + the fallback tier
// ---------------------------------------------------------------------------

describe('W1-A provider — parse errors + the fallback tier', () => {
    it('invalid edge payload → diagnostic logged, clean fallback, ok from fallback', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted(
                // synced as string, lines empty — fails validateLyricsPayload.
                [{ body: { ...LYRICS_PAYLOAD, lines: [], synced: 'yes' }, status: 200 }],
                log,
            ),
        });
        FAKE_YT.music.getLyrics.mockResolvedValue({
            description: { text: 'plain line one\nplain line two' },
            footer: { text: 'Source: Musixmatch' },
        });
        const res = await provider.getLyrics('VID1', 'en');
        expect(res.kind).toBe('ok');
        if (res.kind !== 'ok') throw new Error('unreachable');
        expect(res.source).toBe('fallback');
        expect(res.payload.synced).toBe(false);
        expect(logger.warn).toHaveBeenCalled();
    });

    it('both tiers fail the schema → the typed parse-error result', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted(
                [{ body: { kind: 'playlist', tracks: 'not-an-array' }, status: 200 }],
                log,
            ),
        });
        const res = await provider.getPlaylist('PLX');
        expect(res.kind).toBe('parse-error');
        if (res.kind !== 'parse-error') throw new Error('unreachable');
        expect(res.detail).toContain('playlist');
    });

    it('edge unavailable → the client-side InnerTube lane serves the playlist', async () => {
        const log: { calls: string[]; urls: string[] } = { calls: [], urls: [] };
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeFetchScripted([{ body: null, status: 503 }], log),
        });
        FAKE_YT.music.getPlaylist.mockResolvedValue({
            contents: [
                {
                    album: { name: 'Some Album' },
                    artists: [{ name: 'SWV' }],
                    duration: { seconds: 265, text: '4:25' },
                    id: 'zH74g3l7eH4',
                    title: 'Rain',
                },
            ],
            has_continuation: false,
            header: {
                second_subtitle: { text: '4M views • 147 tracks • 11 hours' },
                title: { text: '90s r&b and hip hop hits' },
            },
        });
        const res = await provider.getPlaylist('PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF');
        expect(res.kind).toBe('ok');
        if (res.kind !== 'ok') throw new Error('unreachable');
        expect(res.source).toBe('fallback');
        expect(res.payload.title).toBe('90s r&b and hip hop hits');
        expect(res.payload.trackCountTotal).toBe(147);
        expect(res.payload.tracks[0]).toMatchObject({
            artistText: 'SWV',
            durationMs: 265000,
            title: 'Rain',
            videoId: 'zH74g3l7eH4',
        });
        expect(res.payload.continuation).toBeNull();
    });
});
