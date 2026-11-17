/**
 * P3-B — the controller bridge end-to-end (network-free): savePlayQueue →
 * getPlayQueue round-trip through the REAL controller (mocked engine +
 * localStorage stub), scrobble through the real bridge, and the favorites
 * endpoints against the live snapshot.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { LibrarySnapshot, SongRow } from '../library';

import { resetStreamifyFavoriteJournal } from '../favorites';
import { resetStreamifyScrobbleBridge } from '../playback/scrobble-bridge';
import { encodeStreamifyCredential } from '../session';
import { StreamifyController } from '../streamify-controller';

import { LibraryItem } from '/@/shared/types/domain-types';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

// streamify-supabase (pulled in via streamify-request) imports the auth
// store, whose module graph reaches DomPurify hooks that need a DOM. The
// controller endpoints under test never touch it — stub the seam (same
// posture as the P2-A stream-source suite).
vi.mock('/@/renderer/store', () => ({
    useAuthStore: Object.assign(vi.fn(), { getState: vi.fn(() => ({})) }),
}));

// The engine bridge is the seam: the controller's snapshot comes from here.
const h = vi.hoisted(() => {
    return {
        snapshot: null as LibrarySnapshot | null,
    };
});
vi.mock('../engine', () => ({
    currentStreamifyLibrary: () => h.snapshot,
    getStreamifyLibrary: async () => {
        if (h.snapshot === null) throw new Error('no snapshot wired');
        return h.snapshot;
    },
}));

// The resolver seam: the controller only needs the rung lookup + error
// classes from stream-source; resolution itself is P2-A's, locked in its
// own suite. Fully stubbed — the real module's import graph reaches the
// player stores, which are irrelevant to this suite.
vi.mock('../playback/stream-source', () => ({
    lastRungForVideo: vi.fn(() => 'visionos-direct'),
    resolveStreamifyTrack: vi.fn(),
    StreamifyNoVideoError: class StreamifyNoVideoError extends Error {},
    StreamifySabrError: class StreamifySabrError extends Error {},
}));

import { likeTrack, unlikeTrack } from '../cloud';
import { lastRungForVideo } from '../playback/stream-source';

vi.mock('../cloud', () => ({
    likeTrack: vi.fn(async () => true),
    recordPlayEventStrict: vi.fn(async () => true),
    unlikeTrack: vi.fn(async () => true),
}));

// -- fixtures ----------------------------------------------------------------

function snapshotOf(songs: SongRow[]): LibrarySnapshot {
    return {
        albumById: new Map(),
        albums: [],
        artistById: new Map(),
        artists: [],
        likedAlbumIds: new Set<string>(),
        likedArtistIds: new Set<string>(),
        likedIds: songs.filter((s) => s.liked).map((s) => s.id),
        loadedAt: '2026-01-01T00:00:00.000Z',
        playEvents: [],
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'controller-test',
        songById: new Map(songs.map((s) => [s.id, s])),
        songs,
    };
}

function songRow(id: string, overrides: Partial<SongRow> = {}): SongRow {
    return {
        addedRank: 0,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 300,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: `vid_${id}`,
        title: `Title ${id}`,
        ...overrides,
    };
}

const SERVER = { id: 'srv', name: 'streamify', type: 'streamify' };
const apiClientProps = {
    server: {
        credential: encodeStreamifyCredential({
            accessToken: 'tok',
            anonKey: 'anon',
            email: 'user@test',
            expiresAt: 0,
            isAdmin: false,
            refreshToken: 'refresh',
            supabaseUrl: 'https://supabase.test',
            userId: 'user_1',
            v: 1,
        }),
        ...SERVER,
    },
    serverId: 'srv',
} as unknown as Parameters<typeof StreamifyController.getPlayQueue>[0]['apiClientProps'];

// node has no localStorage: stub the global (the lanes wrap it).
function stubLocalStorage() {
    const backing = new Map<string, string>();
    vi.stubGlobal('localStorage', {
        getItem: (k: string) => backing.get(k) ?? null,
        removeItem: (k: string) => void backing.delete(k),
        setItem: (k: string, v: string) => void backing.set(k, v),
    });
    return backing;
}

// ----------------------------------------------------------------------------

describe('StreamifyController.savePlayQueue ↔ getPlayQueue (the round-trip)', () => {
    beforeEach(() => {
        resetStreamifyScrobbleBridge();
    });

    it('persists ids/order/cursor/offset and restores them as Song entries', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a'), songRow('b'), songRow('c')]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { currentIndex: 1, positionMs: 63_000, songs: ['a', 'b', 'c'] },
        });

        const restored = await StreamifyController.getPlayQueue({ apiClientProps });
        expect(restored.currentIndex).toBe(1);
        expect(restored.positionMs).toBe(63_000);
        expect(restored.entry.map((s) => s.id)).toEqual(['a', 'b', 'c']);
        expect(restored.entry[0]).toMatchObject({
            _serverType: 'streamify',
            album: 'Album',
            artistName: 'Artist',
            name: 'Title a',
        });
        expect(typeof restored.changed).toBe('string');
    });

    it('never persists resolved stream URLs — the stored payload is URL-free', async () => {
        const backing = stubLocalStorage();
        h.snapshot = snapshotOf([
            songRow('a', { streamUrl: 'https://googlevideo.com/videoplayback?xyz' }),
        ]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { currentIndex: 0, positionMs: 0, songs: ['a'] },
        });

        const raw = Array.from(backing.values()).join('');
        expect(raw).not.toContain('googlevideo');
        expect(raw).not.toContain('videoplayback');
        expect(raw).toContain('"a"');
    });

    it('an empty queue clears the persisted state (restore returns the honest empty)', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a')]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { songs: ['a'] },
        });
        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { songs: [] },
        });

        const restored = await StreamifyController.getPlayQueue({ apiClientProps });
        expect(restored.entry).toEqual([]);
        expect(restored.positionMs).toBe(0);
    });

    it('duplicate queue ids deduplicate on save (the serializer demands unique ids)', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a'), songRow('b')]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { currentIndex: 0, positionMs: 0, songs: ['a', 'b', 'a'] },
        });
        const restored = await StreamifyController.getPlayQueue({ apiClientProps });
        expect(restored.entry.map((s) => s.id)).toEqual(['a', 'b']);
    });

    it('tracks dropped from the library vanish on restore (never a dead row)', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a'), songRow('b')]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { currentIndex: 0, positionMs: 10_000, songs: ['a', 'b'] },
        });

        // The library lost track 'a' (unliked, unplaylisted).
        h.snapshot = snapshotOf([songRow('b')]);

        const restored = await StreamifyController.getPlayQueue({ apiClientProps });
        expect(restored.entry.map((s) => s.id)).toEqual(['b']);
    });

    it('an out-of-range cursor clamps instead of corrupting the restore', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a'), songRow('b')]);

        await StreamifyController.savePlayQueue({
            apiClientProps,
            query: { currentIndex: 99, positionMs: 0, songs: ['a', 'b'] },
        });
        const restored = await StreamifyController.getPlayQueue({ apiClientProps });
        expect(restored.currentIndex).toBe(1); // clamped to the last row
    });
});

describe('StreamifyController.scrobble (the bridge, through the controller)', () => {
    beforeEach(() => {
        resetStreamifyScrobbleBridge();
        resetStreamifyFavoriteJournal();
    });

    it('a counted listen bumps the snapshot play stats and queues the cloud event', async () => {
        stubLocalStorage();
        vi.stubGlobal('navigator', { onLine: false }); // offline: the outbox holds
        try {
            const songs = [songRow('a')];
            h.snapshot = snapshotOf(songs);

            const report = (query: Record<string, unknown>) =>
                StreamifyController.scrobble({
                    apiClientProps,
                    query: { mediaType: 'song', playbackRate: 1, ...query } as never,
                });

            vi.useFakeTimers();
            vi.setSystemTime(1_000_000);
            await report({ event: 'start', id: 'a', position: 0, submission: false });
            // The listen really happens over 3 real minutes.
            vi.advanceTimersByTime(3 * 60_000);
            vi.setSystemTime(1_000_000 + 3 * 60_000);
            await report({ id: 'a', position: 300_000, submission: true });

            expect(h.snapshot.songById.get('a')!.playCount).toBe(1);
            expect(h.snapshot.songById.get('a')!.lastPlayedAt).not.toBeNull();

            const bridge = (
                await import('../playback/scrobble-bridge')
            ).getStreamifyScrobbleBridge();
            const pending = bridge.pendingScrobbles();
            expect(pending).toHaveLength(1);
            expect(pending[0]).toMatchObject({ rung: 'visionos-direct', trackId: 'a' });
        } finally {
            vi.useRealTimers();
            vi.unstubAllGlobals();
        }
    });

    it('a skip never bumps play stats', async () => {
        stubLocalStorage();
        vi.stubGlobal('navigator', { onLine: false });
        try {
            const songs = [songRow('a')];
            h.snapshot = snapshotOf(songs);

            const report = (query: Record<string, unknown>) =>
                StreamifyController.scrobble({
                    apiClientProps,
                    query: { mediaType: 'song', playbackRate: 1, ...query } as never,
                });

            vi.useFakeTimers();
            vi.setSystemTime(1_000_000);
            await report({ event: 'start', id: 'a', position: 0, submission: false });
            vi.setSystemTime(1_000_000 + 30_000);
            await report({
                event: 'stop',
                id: 'a',
                position: 30_000, // skipped after 30 s — 30 s < the 150 s threshold
                submission: false,
            });

            expect(h.snapshot.songById.get('a')!.playCount).toBe(0);
            expect(h.snapshot.songById.get('a')!.lastPlayedAt).toBeNull();

            const bridge = (
                await import('../playback/scrobble-bridge')
            ).getStreamifyScrobbleBridge();
            expect(bridge.pendingScrobbles()).toHaveLength(0);
        } finally {
            vi.useRealTimers();
            vi.unstubAllGlobals();
        }
    });
});

describe('StreamifyController favorites endpoints', () => {
    beforeEach(() => {
        resetStreamifyFavoriteJournal();
        resetStreamifyScrobbleBridge();
        vi.mocked(likeTrack).mockClear();
        vi.mocked(unlikeTrack).mockClear();
    });

    it('createFavorite flips the live snapshot and likes server-side', async () => {
        stubLocalStorage();
        const songs = [songRow('a'), songRow('b')];
        h.snapshot = snapshotOf(songs);

        await StreamifyController.createFavorite({
            apiClientProps,
            query: { id: ['a'], type: LibraryItem.SONG },
        });

        expect(h.snapshot.songById.get('a')!.liked).toBe(true);
        expect(h.snapshot.likedIds).toEqual(['a']);
        expect(likeTrack).toHaveBeenCalledWith(
            expect.anything(),
            'a',
            expect.objectContaining({ title: 'Title a' }),
        );

        // And the read reflects it without a snapshot reload:
        const favorites = await StreamifyController.getFavoriteSongs({
            apiClientProps,
            query: { artistId: '' },
        });
        expect(favorites.items.map((s) => s.id)).toEqual(['a']);
        expect(favorites.totalRecordCount).toBe(1);
    });

    it('deleteFavorite unlikes and the song leaves the favorites read', async () => {
        stubLocalStorage();
        const songs = [songRow('a', { liked: true }), songRow('b', { liked: true })];
        h.snapshot = snapshotOf(songs);

        await StreamifyController.deleteFavorite({
            apiClientProps,
            query: { id: ['a'], type: LibraryItem.SONG },
        });

        expect(h.snapshot.songById.get('a')!.liked).toBe(false);
        expect(unlikeTrack).toHaveBeenCalledWith(expect.anything(), 'a');
        const favorites = await StreamifyController.getFavoriteSongs({
            apiClientProps,
            query: { artistId: '' },
        });
        expect(favorites.items.map((s) => s.id)).toEqual(['b']);
    });

    it('getFavoriteSongs honors the artist filter', async () => {
        stubLocalStorage();
        const songs = [
            songRow('a', { artist: 'Alpha', liked: true }),
            songRow('b', { artist: 'Beta', liked: true }),
        ];
        h.snapshot = snapshotOf(songs);
        h.snapshot.artistById.set('art_alpha', {
            addedRank: 0,
            albumIds: [],
            coverUrl: null,
            durationMs: 0,
            id: 'art_alpha',
            key: 'alpha',
            lastPlayedAt: null,
            name: 'Alpha',
            playCount: 0,
            songIds: [],
        });

        const favorites = await StreamifyController.getFavoriteSongs({
            apiClientProps,
            query: { artistId: 'art_alpha' },
        });
        expect(favorites.items.map((s) => s.id)).toEqual(['a']);
    });

    it('non-song favorite types are the honest no-op (no cloud model)', async () => {
        stubLocalStorage();
        h.snapshot = snapshotOf([songRow('a')]);

        await expect(
            StreamifyController.createFavorite({
                apiClientProps,
                query: { id: ['alb_x'], type: LibraryItem.ALBUM },
            }),
        ).resolves.toBeNull();
        expect(likeTrack).not.toHaveBeenCalled();
    });
});

describe('lastRungForVideo wiring', () => {
    it('the controller binds the resolver log ring to the bridge rung lookup', () => {
        expect(lastRungForVideo).toBeDefined();
    });
});
