/**
 * W2-B — the playTally-seeded Discover Mix, network-free: seed ranking
 * from the #191/#192 ledger, recent-play filtering, videoId + signature
 * deduplication, round-robin interleaving, the bounded result, the
 * cold-start Explore/Home fallback, and the ytm:-namespace song mapping.
 *
 * The playTally counts this lane feeds the engine are the same ones the
 * canonical min(half-duration, 4 min) rule produces (P2B-playback-spine)
 * — the engine only ever READS the ledger, never re-derives play state.
 */

import { describe, expect, it } from 'vitest';

import type { LibrarySnapshot, SongRow } from '../library';
import type { W2bMetaSource } from '../meta-bridge';
import type { PlayTally } from '../playback/play-tally';

import {
    DISCOVER_MAX_SEEDS,
    DISCOVER_TARGET,
    discoverSeeds,
    discoverSig,
    generateDiscoverMix,
    recentPlayedExclusions,
} from '/@/renderer/features/streamify/discover/discover-engine';
import { discoverTrackToSong } from '/@/renderer/features/streamify/discover/discover-songs';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function fakeSource(over: {
    explore?: null | { shelves: Array<{ items: []; title: string }> };
    home?: null | { shelves: Array<{ items: []; title: string }> };
    related?: Record<string, ReturnType<typeof ref>[]>;
    upNext?: Record<string, ReturnType<typeof ref>[]>;
}): W2bMetaSource {
    return {
        getCredits: async () => null,
        getExplore: async () => over.explore ?? null,
        getHomeFeed: async () => over.home ?? null,
        getRelated: async (seed) => over.related?.[seed] ?? null,
        getUpNext: async (seed) => over.upNext?.[seed] ?? null,
    };
}

function ref(
    videoId: string,
    title: string,
    artist: null | string = 'Someone',
): {
    artist: null | string;
    durationMs: null | number;
    title: string;
    videoId: string;
} {
    return { artist, durationMs: null, title, videoId };
}

function snapshot(over: {
    lastPlayedAt?: Record<string, string>;
    playEvents?: Array<{ at: string; trackId: string }>;
    songs: SongRow[];
}): LibrarySnapshot {
    const songById = new Map(over.songs.map((row) => [row.id, row]));
    return {
        albumById: new Map(),
        albums: [],
        artistById: new Map(),
        artists: [],
        likedIds: [],
        loadedAt: '2026-11-17T00:00:00.000Z',
        playEvents: (over.playEvents ?? []).map((e) => ({
            artist: 'Artist',
            created_at: e.at,
            duration_played_sec: 100,
            hour_of_day: 12,
            title: 'T',
            track_sig: e.trackId,
        })),
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'test',
        songById,
        songs: over.songs.map((row) =>
            over.lastPlayedAt?.[row.id] !== undefined
                ? { ...row, lastPlayedAt: over.lastPlayedAt[row.id] }
                : row,
        ),
    };
}

function songRow(over: Partial<SongRow> & { id: string }): SongRow {
    return {
        addedRank: 0,
        album: null,
        artist: 'Artist',
        coverUrl: null,
        durationSec: 200,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: null,
        title: `Song ${over.id}`,
        ...over,
    };
}

function tally(topTracks: Record<string, { count: number }>): PlayTally {
    return {
        byHour: new Array<number>(24).fill(0),
        seconds: 0,
        topTracks: Object.fromEntries(
            Object.entries(topTracks).map(([id, v]) => [
                id,
                { artist: 'Artist', count: v.count, title: `Song ${id}` },
            ]),
        ),
        tracks: Object.values(topTracks).reduce((n, v) => n + v.count, 0),
    };
}

const NOW = Date.parse('2026-11-17T12:00:00.000Z');
const iso = (ms: number) => new Date(ms).toISOString();
const DAY = 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Seed selection
// ---------------------------------------------------------------------------

describe('w2b discover — playTally seed ranking', () => {
    it('ranks by counted plays, desc, and resolves videoIds through the snapshot', () => {
        const snap = snapshot({
            songs: [
                songRow({ id: 'a', streamUrl: 'vid-a' }),
                songRow({ id: 'b', streamUrl: 'vid-b' }),
                songRow({ id: 'c', streamUrl: null }), // no video: not a seed
                songRow({ id: 'd', streamUrl: 'vid-d' }),
            ],
        });
        const seeds = discoverSeeds(
            snap,
            tally({ a: { count: 2 }, b: { count: 9 }, c: { count: 99 }, d: { count: 5 } }),
        );
        expect(seeds.map((s) => s.videoId)).toEqual(['vid-b', 'vid-d', 'vid-a']);
        expect(seeds[0]).toMatchObject({ artist: 'Artist', title: 'Song b' });
    });

    it('bounds the seed set at DISCOVER_MAX_SEEDS', () => {
        const snap = snapshot({
            songs: ['a', 'b', 'c', 'd', 'e'].map((id) => songRow({ id, streamUrl: `v-${id}` })),
        });
        const seeds = discoverSeeds(
            snap,
            tally({
                a: { count: 1 },
                b: { count: 2 },
                c: { count: 3 },
                d: { count: 4 },
                e: { count: 5 },
            }),
        );
        expect(seeds).toHaveLength(DISCOVER_MAX_SEEDS);
    });

    it('an empty ledger or missing snapshot yields no seeds (the cold start)', () => {
        expect(discoverSeeds(null, tally({}))).toEqual([]);
        const snap = snapshot({ songs: [songRow({ id: 'a', streamUrl: 'vid-a' })] });
        expect(discoverSeeds(snap, tally({}))).toEqual([]);
    });
});

// ---------------------------------------------------------------------------
// Recent-play filtering
// ---------------------------------------------------------------------------

describe('w2b discover — the recent-history window', () => {
    it('excludes tracks played inside the window, by videoId and signature', () => {
        const snap = snapshot({
            lastPlayedAt: { a: iso(NOW - DAY) },
            playEvents: [{ at: iso(NOW - 2 * DAY), trackId: 'b' }],
            songs: [
                songRow({ artist: 'A1', id: 'a', streamUrl: 'vid-a', title: 'Alpha' }),
                songRow({ artist: 'B1', id: 'b', streamUrl: 'vid-b', title: 'Beta' }),
                songRow({
                    artist: 'O1',
                    id: 'old',
                    lastPlayedAt: iso(NOW - 30 * DAY),
                    streamUrl: 'vid-old',
                    title: 'Old',
                }),
                songRow({ artist: 'N1', id: 'never', streamUrl: 'vid-never', title: 'Never' }),
            ],
        });
        const { sigs, videoIds } = recentPlayedExclusions(snap, NOW);
        expect(videoIds.has('vid-a')).toBe(true); // lastPlayedAt inside 7 d
        expect(videoIds.has('vid-b')).toBe(true); // playEvent inside 7 d
        expect(videoIds.has('vid-old')).toBe(false); // outside the window
        expect(videoIds.has('vid-never')).toBe(false);
        expect(sigs.has(discoverSig('Alpha', 'A1'))).toBe(true);
        expect(sigs.has(discoverSig('Never', 'N1'))).toBe(false);
    });

    it('ignores unparseable timestamps honestly', () => {
        const snap = snapshot({
            lastPlayedAt: { a: 'not-a-date' },
            songs: [songRow({ id: 'a', lastPlayedAt: 'not-a-date', streamUrl: 'vid-a' })],
        });
        const { videoIds } = recentPlayedExclusions(snap, NOW);
        expect(videoIds.has('vid-a')).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// The generator
// ---------------------------------------------------------------------------

describe('w2b discover — generateDiscoverMix (warm path)', () => {
    const snap = snapshot({
        songs: ['a', 'b', 'c'].map((id) => songRow({ id, streamUrl: `vid-${id}` })),
    });
    const led = tally({ a: { count: 5 }, b: { count: 4 }, c: { count: 3 } });

    it('interleaves candidates round-robin across the seeds', async () => {
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({
                related: {},
                upNext: {
                    'vid-a': [ref('r1', 'A-one'), ref('r2', 'A-two'), ref('r3', 'A-three')],
                    'vid-b': [ref('r4', 'B-one'), ref('r5', 'B-two')],
                    'vid-c': [ref('r6', 'C-one')],
                },
            }),
            tally: led,
        });
        expect(mix).not.toBeNull();
        expect(mix?.source).toBe('tally');
        // Round-robin: the first candidate of each seed before any second.
        expect(mix?.tracks.map((t) => t.videoId)).toEqual(['r1', 'r4', 'r6', 'r2', 'r5', 'r3']);
        // Seed attribution drives "Because you listened to…".
        expect(mix?.seeds.map((s) => s.videoId)).toEqual(['vid-a', 'vid-b', 'vid-c']);
    });

    it('deduplicates by videoId AND by (title, artist) signature', async () => {
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({
                upNext: {
                    'vid-a': [
                        ref('dup', 'Same Song', 'Same Artist'),
                        ref('sig-dup', 'SAME SONG ', 'same artist'),
                    ],
                    'vid-b': [ref('dup', 'Same Song', 'Same Artist'), ref('keep', 'Other Song')],
                    'vid-c': [ref('x', 'X Song')],
                },
            }),
            tally: led,
        });
        const ids = mix?.tracks.map((t) => t.videoId) ?? [];
        expect(ids.filter((v) => v === 'dup')).toHaveLength(1); // videoId dedup
        expect(ids).not.toContain('sig-dup'); // signature dedup (case/space)
        expect(ids).toContain('keep');
        expect(ids).toContain('x');
    });

    it('never suggests the seeds themselves or recently played tracks', async () => {
        const snapWithRecent = snapshot({
            lastPlayedAt: { a: iso(NOW - DAY) },
            songs: ['a', 'b', 'c'].map((id) => songRow({ id, streamUrl: `vid-${id}` })),
        });
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snapWithRecent,
            source: fakeSource({
                upNext: {
                    // The seed echo (same videoId) AND a same-signature
                    // recently-played candidate are both filtered out.
                    'vid-a': [ref('vid-a', 'Echo of seed'), ref('recent-vid', 'Song a', 'Artist')],
                    'vid-b': [ref('ok1', 'Fine One')],
                    'vid-c': [ref('ok2', 'Fine Two')],
                },
            }),
            tally: led,
        });
        const ids = mix?.tracks.map((t) => t.videoId) ?? [];
        expect(ids).not.toContain('vid-a'); // the seed itself, never suggested
        expect(ids).not.toContain('recent-vid'); // same signature as the played row
        expect(ids).toContain('ok1');
        expect(ids).toContain('ok2');
    });

    it('bounds the mix at DISCOVER_TARGET tracks', async () => {
        const many = Array.from({ length: 60 }, (_, i) => ref(`v${i}`, `Track ${i}`));
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({ upNext: { 'vid-a': many, 'vid-b': many, 'vid-c': many } }),
            tally: led,
        });
        expect(mix?.tracks.length).toBeLessThanOrEqual(DISCOVER_TARGET);
    });

    it('resolves null when every seed feed misses (the honest empty)', async () => {
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({}),
            tally: led,
        });
        expect(mix).toBeNull();
    });
});

describe('w2b discover — the cold-start fallback', () => {
    it('fewer than 3 resolvable seeds falls back to getExplore', async () => {
        const snap = snapshot({
            songs: [songRow({ id: 'a', streamUrl: 'vid-a' })], // 1 seed only
        });
        const exploreTracks = [
            {
                browseId: null,
                subtitle: 'E Artist',
                thumbnail: null,
                title: 'Explore One',
                videoId: 'e1',
            },
            {
                browseId: null,
                subtitle: null,
                thumbnail: null,
                title: 'Explore Two',
                videoId: 'e2',
            },
        ];
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({
                explore: { shelves: [{ items: exploreTracks as never, title: 'New releases' }] },
            }),
            tally: tally({ a: { count: 9 } }),
        });
        expect(mix?.source).toBe('explore');
        expect(mix?.seeds).toEqual([]);
        expect(mix?.tracks.map((t) => t.videoId)).toEqual(['e1', 'e2']);
    });

    it('falls back to getHomeFeed when Explore misses', async () => {
        const snap = snapshot({ songs: [] });
        const homeTracks = [
            { browseId: null, subtitle: null, thumbnail: null, title: 'Home One', videoId: 'h1' },
        ];
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snap,
            source: fakeSource({
                home: { shelves: [{ items: homeTracks as never, title: 'Home' }] },
            }),
            tally: tally({}),
        });
        expect(mix?.source).toBe('home');
        expect(mix?.tracks[0]?.videoId).toBe('h1');
    });

    it('resolves null when both cold-start feeds miss', async () => {
        const mix = await generateDiscoverMix({
            now: () => NOW,
            snapshot: snapshot({ songs: [] }),
            source: fakeSource({}),
            tally: tally({}),
        });
        expect(mix).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// The playable song mapping
// ---------------------------------------------------------------------------

describe('w2b discover — the ytm: song mapping', () => {
    it('maps unknown tracks to the ytm:<videoId> namespace', () => {
        const song = discoverTrackToSong(
            {
                artist: 'New Artist',
                durationMs: 210000,
                title: 'New Track',
                videoId: 'abc123XYZ_-',
            },
            'server-1',
            null,
        );
        expect(song.id).toBe('ytm:abc123XYZ_-');
        expect(song._serverType).toBe('streamify');
        expect(song.name).toBe('New Track');
        expect(song.artistName).toBe('New Artist');
        expect(song.duration).toBe(210000);
    });

    it('keeps the cloud track id for tracks the snapshot knows (by videoId)', () => {
        const snap = snapshot({
            songs: [songRow({ artist: 'KA', id: 'trk_9', streamUrl: 'known-vid', title: 'Known' })],
        });
        const song = discoverTrackToSong(
            { artist: 'KA', durationMs: null, title: 'Known', videoId: 'known-vid' },
            'server-1',
            snap,
        );
        expect(song.id).toBe('trk_9');
    });

    it('keeps the cloud track id for signature matches (title + artist)', () => {
        const snap = snapshot({
            songs: [
                songRow({ artist: 'SA', id: 'trk_sig', streamUrl: 'other-vid', title: 'Sig Song' }),
            ],
        });
        const song = discoverTrackToSong(
            { artist: 'SA', durationMs: null, title: 'Sig Song', videoId: 'brand-new-vid' },
            'server-1',
            snap,
        );
        expect(song.id).toBe('trk_sig');
    });

    it('defaults the artist honestly (Unknown Artist), never null', () => {
        const song = discoverTrackToSong(
            { artist: null, durationMs: null, title: 'T', videoId: 'v' },
            's',
            null,
        );
        expect(song.artistName).toBe('Unknown Artist');
        expect(song.artists[0]?.name).toBe('Unknown Artist');
    });
});
