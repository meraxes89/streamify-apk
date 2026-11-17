/**
 * P3-B — the favorites (likes) subsystem: live snapshot updates, the
 * offline journal (drain, coalescing, poison drop, flap reset), and the
 * mutateFavorite flow. Network-free (fetch stubbed at the adapter seam;
 * the cloud lanes are exercised through their injected senders).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { LibrarySnapshot, SongRow } from '../library';
import type { StreamifySession } from '../session';

import {
    applyFavoriteToSnapshot,
    FavoriteJournal,
    type FavoriteJournalStore,
    JOURNAL_CAP,
    MAX_JOURNAL_ATTEMPTS,
    mutateFavorite,
} from '../favorites';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

// -- fixtures ----------------------------------------------------------------

function snapshotOf(songs: SongRow[], likedIds: string[] = []): LibrarySnapshot {
    const songById = new Map(songs.map((s) => [s.id, s]));
    return {
        albumById: new Map(),
        albums: [],
        artistById: new Map(),
        artists: [],
        likedAlbumIds: new Set<string>(),
        likedArtistIds: new Set<string>(),
        likedIds: [...likedIds],
        loadedAt: '2026-01-01T00:00:00.000Z',
        playEvents: [],
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'test',
        songById,
        songs,
    };
}

function songRow(id: string, overrides: Partial<SongRow> = {}): SongRow {
    return {
        addedRank: 0,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 200,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: `vid_${id}`,
        title: `Title ${id}`,
        ...overrides,
    };
}

const session = (): StreamifySession => ({
    accessToken: 'token',
    anonKey: 'anon',
    email: 'user@test',
    expiresAt: 0,
    isAdmin: false,
    refreshToken: 'refresh',
    source: 'credential',
    supabaseUrl: 'https://supabase.test',
    userId: 'user_1',
});

function memJournalStore(): FavoriteJournalStore & { dump(): unknown[] } {
    let ops: null | unknown[] = null;
    return {
        dump: () => ops ?? [],
        load: () => (ops === null ? [] : (ops as never[])),
        save: (next) => {
            ops = [...next];
        },
    };
}

// The cloud write lanes, stubbed at the module boundary (the REAL fetch
// lane is call-for-call identical to the web app's — locked there).
vi.mock('../cloud', () => ({
    likeTrack: vi.fn(async () => true),
    restCall: vi.fn(async () => ({ ok: true, status: 200 })),
    unlikeTrack: vi.fn(async () => true),
}));

import { likeTrack, restCall, unlikeTrack } from '../cloud';

const likeMock = vi.mocked(likeTrack);
const unlikeMock = vi.mocked(unlikeTrack);
const restCallMock = vi.mocked(restCall);

// ----------------------------------------------------------------------------

describe('applyFavoriteToSnapshot (the live update)', () => {
    it('flips rows and reorders likedIds most-recent-first', () => {
        const a = songRow('a', { liked: true });
        const b = songRow('b', { liked: true });
        const c = songRow('c');
        const snap = snapshotOf([a, b, c], ['a', 'b']);

        applyFavoriteToSnapshot(snap, ['c'], true);
        expect(snap.songById.get('c')!.liked).toBe(true);
        expect(snap.likedIds).toEqual(['c', 'a', 'b']); // newest first

        applyFavoriteToSnapshot(snap, ['a'], false);
        expect(snap.songById.get('a')!.liked).toBe(false);
        expect(snap.likedIds).toEqual(['c', 'b']);
    });

    it('unknown ids are a safe no-op', () => {
        const snap = snapshotOf([songRow('a')], ['a']);
        applyFavoriteToSnapshot(snap, ['ghost'], true);
        expect(snap.likedIds).toEqual(['a']);
    });
});

describe('FavoriteJournal', () => {
    beforeEach(() => {
        likeMock.mockReset();
        unlikeMock.mockReset();
    });

    it('drains applied ops in order and empties', async () => {
        const journal = new FavoriteJournal(memJournalStore());
        journal.push({
            facts: {
                album: 'Album',
                artist: 'Artist',
                coverUrl: null,
                durationSec: 200,
                title: 'Title a',
                videoId: null,
            },
            op: 'like',
            trackId: 'a',
        });
        journal.push({ op: 'unlike', trackId: 'b' });

        likeMock.mockResolvedValue(true);
        unlikeMock.mockResolvedValue(true);

        const applied = await journal.drain(session());
        expect(applied).toBe(2);
        expect(journal.size).toBe(0);
        expect(likeMock).toHaveBeenCalledWith(
            expect.anything(),
            'a',
            expect.objectContaining({ title: 'Title a' }),
        );
        expect(unlikeMock).toHaveBeenCalledWith(expect.anything(), 'b');
    });

    it('facts-less like ops insert the edge only (no fabricated catalog metadata)', async () => {
        const journal = new FavoriteJournal(memJournalStore());
        journal.push({ op: 'like', trackId: 'ghost' });

        const applied = await journal.drain(session());
        expect(applied).toBe(1);
        expect(likeMock).not.toHaveBeenCalled(); // never the upsert path
        expect(restCallMock).toHaveBeenCalledWith(
            expect.anything(),
            'user_likes',
            expect.objectContaining({ method: 'POST' }),
        );
    });

    it('coalesces: a like and its undo cancel; repeats stay single', () => {
        const journal = new FavoriteJournal(memJournalStore());
        journal.push({ op: 'like', trackId: 'a' });
        journal.push({ op: 'unlike', trackId: 'a' }); // cancels
        expect(journal.size).toBe(0);

        journal.push({ op: 'unlike', trackId: 'b' });
        journal.push({ op: 'unlike', trackId: 'b' }); // idempotent
        expect(journal.size).toBe(1);
    });

    it('failed ops retry with attempts++ and poison-drop past the cap', async () => {
        const journal = new FavoriteJournal(memJournalStore());
        journal.push({ op: 'like', trackId: 'poison' });

        likeMock.mockResolvedValue(false);
        for (let i = 0; i < MAX_JOURNAL_ATTEMPTS; i += 1) {
            await journal.drain(session());
        }
        expect(journal.size).toBe(0); // dropped after 5 attempts
    });

    it('flap reset: a success resets the attempt counters', async () => {
        const journal = new FavoriteJournal(memJournalStore());
        const facts = {
            album: 'Album',
            artist: 'Artist',
            coverUrl: null,
            durationSec: 200,
            title: 'T',
            videoId: null,
        };
        journal.push({ facts, op: 'like', trackId: 'a' });
        journal.push({ facts, op: 'like', trackId: 'b' });

        likeMock.mockResolvedValue(false);
        await journal.drain(session());
        await journal.drain(session());
        expect(journal.pending().every((op) => op.attempts === 2)).toBe(true);

        likeMock.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
        await journal.drain(session());
        const pending = journal.pending();
        expect(pending).toHaveLength(1);
        expect(pending[0]!.attempts).toBe(0);
    });

    it('rethrows an auth rejection mid-drain (the dead-session path)', async () => {
        const journal = new FavoriteJournal(memJournalStore());
        journal.push({
            facts: {
                album: 'Album',
                artist: 'Artist',
                coverUrl: null,
                durationSec: 200,
                title: 'Title a',
                videoId: null,
            },
            op: 'like',
            trackId: 'a',
        });

        likeMock.mockRejectedValue(new Error('Streamify session unauthorized (401)'));
        await expect(journal.drain(session())).rejects.toThrow('unauthorized');
        expect(journal.size).toBe(1); // the op survives for the next session
    });

    it('caps at JOURNAL_CAP — the oldest drop', () => {
        const journal = new FavoriteJournal(memJournalStore());
        for (let i = 0; i < JOURNAL_CAP + 10; i += 1) {
            journal.push({ op: 'like', trackId: `trk_${i}` });
        }
        expect(journal.size).toBe(JOURNAL_CAP);
        expect(journal.pending()[0]!.trackId).toBe('trk_10');
    });
});

describe('mutateFavorite (the controller flow)', () => {
    beforeEach(() => {
        likeMock.mockReset();
        unlikeMock.mockReset();
    });

    it('favorite: live snapshot update + cloud like with catalog upsert facts', async () => {
        const snap = snapshotOf([songRow('a'), songRow('b')]);
        const journal = new FavoriteJournal(memJournalStore());
        likeMock.mockResolvedValue(true);

        const outcome = await mutateFavorite(session(), snap, ['a'], true, journal);

        expect(outcome).toEqual({ journaled: false, synced: true });
        expect(snap.songById.get('a')!.liked).toBe(true);
        expect(snap.likedIds).toEqual(['a']);
        expect(likeMock).toHaveBeenCalledWith(
            expect.anything(),
            'a',
            expect.objectContaining({ title: 'Title a' }),
        );
        expect(journal.size).toBe(0);
    });

    it('unfavorite: live snapshot update + cloud unlike', async () => {
        const snap = snapshotOf([songRow('a', { liked: true })], ['a']);
        const journal = new FavoriteJournal(memJournalStore());
        unlikeMock.mockResolvedValue(true);

        const outcome = await mutateFavorite(session(), snap, ['a'], false, journal);

        expect(outcome).toEqual({ journaled: false, synced: true });
        expect(snap.songById.get('a')!.liked).toBe(false);
        expect(snap.likedIds).toEqual([]);
        expect(unlikeMock).toHaveBeenCalledWith(expect.anything(), 'a');
    });

    it('offline failure journals the op — the local state stands', async () => {
        const snap = snapshotOf([songRow('a')]);
        const journal = new FavoriteJournal(memJournalStore());
        likeMock.mockRejectedValue(new Error('fetch failed'));

        const outcome = await mutateFavorite(session(), snap, ['a'], true, journal);

        expect(outcome).toEqual({ journaled: true, synced: false });
        expect(snap.songById.get('a')!.liked).toBe(true); // local stands
        expect(journal.size).toBe(1);
        expect(journal.pending()[0]).toMatchObject({ op: 'like', trackId: 'a' });

        // Connectivity returns: the drain applies yesterday's opinion.
        likeMock.mockResolvedValue(true);
        await journal.drain(session());
        expect(journal.size).toBe(0);
    });

    it('a dead session rethrows unauthorized (the clean-sign-out path)', async () => {
        const snap = snapshotOf([songRow('a')]);
        const journal = new FavoriteJournal(memJournalStore());
        likeMock.mockRejectedValue(new Error('Streamify session unauthorized (401)'));

        await expect(mutateFavorite(session(), snap, ['a'], true, journal)).rejects.toThrow(
            'unauthorized',
        );
        // The live flip already happened; the journal carries nothing (the
        // auth seam owns recovery, not a blind retry against a dead session).
    });

    it('signed-out mutations journal for the next session', async () => {
        const snap = snapshotOf([songRow('a')]);
        const journal = new FavoriteJournal(memJournalStore());

        const outcome = await mutateFavorite(null, snap, ['a'], true, journal);

        expect(outcome).toEqual({ journaled: true, synced: false });
        expect(journal.size).toBe(1);
    });

    it('unknown rows journal edge-only ops (no fabricated catalog metadata)', async () => {
        const snap = snapshotOf([songRow('a')]);
        const journal = new FavoriteJournal(memJournalStore());

        await mutateFavorite(session(), snap, ['ghost'], true, journal);

        expect(journal.size).toBe(1);
        expect(journal.pending()[0]).toMatchObject({ op: 'like', trackId: 'ghost' });
        expect(journal.pending()[0]!.facts).toBeUndefined();
    });
});
