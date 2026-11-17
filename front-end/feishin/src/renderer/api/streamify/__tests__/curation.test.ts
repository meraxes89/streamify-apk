/**
 * P3-B — home-screen curation & library filter predicates: the sort map
 * that drives Feishin's Home shelves and filter bars (NEWEST →
 * RECENTLY_ADDED, FREQUENT → PLAY_COUNT, RECENT → RECENTLY_PLAYED with
 * nulls last, RANDOM → the seeded shuffle, STARRED → the favorite filter
 * + the FAVORITED polarity fix, genre → honest-empty), plus the playTally
 * hydration onto playCount / lastPlayedAt.
 */
import { describe, expect, it } from 'vitest';

import type { AlbumRow, ArtistRow, LibrarySnapshot, SongRow } from '../library';

import { hydrateSnapshotWithTally } from '../playback/play-tally';
import { filterAlbums, filterArtists, filterSongs, seededShuffle, sortSongs } from '../query';

import { LibraryItem, SongListSort, SortOrder } from '/@/shared/types/domain-types';

// -- fixtures ----------------------------------------------------------------

function albumRow(id: string, overrides: Partial<AlbumRow> = {}): AlbumRow {
    return {
        addedRank: Number.MAX_SAFE_INTEGER,
        artistName: 'Artist',
        coverUrl: null,
        durationMs: 0,
        id,
        key: `key_${id}`,
        lastPlayedAt: null,
        name: `Album ${id}`,
        playCount: 0,
        songIds: [],
        ...overrides,
    };
}

function artistRow(id: string, overrides: Partial<ArtistRow> = {}): ArtistRow {
    return {
        addedRank: Number.MAX_SAFE_INTEGER,
        albumIds: [],
        coverUrl: null,
        durationMs: 0,
        id,
        key: `key_${id}`,
        lastPlayedAt: null,
        name: `Artist ${id}`,
        playCount: 0,
        songIds: [],
        ...overrides,
    };
}

function snapshotOf(songs: SongRow[], extras: Partial<LibrarySnapshot> = {}): LibrarySnapshot {
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
        sessionKey: 'curation-test',
        songById: new Map(songs.map((s) => [s.id, s])),
        songs,
        ...extras,
    };
}

function songRow(id: string, overrides: Partial<SongRow> = {}): SongRow {
    return {
        addedRank: Number.MAX_SAFE_INTEGER,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 200,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: null,
        title: `Song ${id}`,
        ...overrides,
    };
}

// ----------------------------------------------------------------------------

describe('STARRED — the favorite filter and the FAVORITED sort polarity', () => {
    it('filterSongs favorite=true keeps only liked rows (the favorites page filter)', () => {
        const songs = [
            songRow('a', { liked: true }),
            songRow('b', { liked: false }),
            songRow('c', { liked: true }),
        ];
        const kept = filterSongs(songs, { favorite: true }, snapshotOf(songs));
        expect(kept.map((s) => s.id)).toEqual(['a', 'c']);
    });

    it('FAVORITED DESC puts favorites FIRST (the polarity fix)', () => {
        const songs = [
            songRow('a', { liked: false, title: 'A' }),
            songRow('b', { liked: true, title: 'B' }),
            songRow('c', { liked: false, title: 'C' }),
            songRow('d', { liked: true, title: 'D' }),
        ];
        const sorted = sortSongs(songs, SongListSort.FAVORITED, SortOrder.DESC, snapshotOf(songs));
        // Favorites first (liked=true), then unliked — feishin's own
        // shared/api/utils.ts orders userFavorite DESC with the dropdown's
        // default DESC for this sort; the polarity must match.
        expect(sorted.slice(0, 2).every((s) => s.liked)).toBe(true);
        expect(sorted.slice(2).every((s) => !s.liked)).toBe(true);
    });

    it('FAVORITED ASC puts unliked first (SQL boolean parity, direction-flipped)', () => {
        const songs = [
            songRow('a', { liked: true, title: 'A' }),
            songRow('b', { liked: false, title: 'B' }),
        ];
        const sorted = sortSongs(songs, SongListSort.FAVORITED, SortOrder.ASC, snapshotOf(songs));
        expect(sorted.map((s) => s.id)).toEqual(['b', 'a']);
    });

    it('album / artist favorite filters honestly match nothing (no cloud model yet)', () => {
        const albums = [albumRow('alb_1')];
        const artists = [artistRow('art_1')];
        expect(filterAlbums(albums, { favorite: true })).toEqual([]);
        expect(filterArtists(artists, { favorite: true })).toEqual([]);
    });
});

describe('NEWEST — RECENTLY_ADDED rides the like-arrival rank', () => {
    it('sorts most-recently-liked first under DESC', () => {
        const songs = [
            songRow('old', { addedRank: 900 }),
            songRow('newest', { addedRank: 0 }),
            songRow('mid', { addedRank: 400 }),
        ];
        const sorted = sortSongs(
            songs,
            SongListSort.RECENTLY_ADDED,
            SortOrder.DESC,
            snapshotOf(songs),
        );
        expect(sorted.map((s) => s.id)).toEqual(['newest', 'mid', 'old']);
    });
});

describe('FREQUENT — PLAY_COUNT from the playTally-hydrated stats', () => {
    it('sorts by play count descending', () => {
        const songs = [
            songRow('rare', { playCount: 1 }),
            songRow('hit', { playCount: 42 }),
            songRow('never', { playCount: 0 }),
        ];
        const sorted = sortSongs(songs, SongListSort.PLAY_COUNT, SortOrder.DESC, snapshotOf(songs));
        expect(sorted.map((s) => s.id)).toEqual(['hit', 'rare', 'never']);
    });
});

describe('RECENT — RECENTLY_PLAYED with never-played rows ALWAYS last', () => {
    it('DESC: newest play first, nulls after regardless of direction', () => {
        const songs = [
            songRow('never_a', { lastPlayedAt: null }),
            songRow('yesterday', { lastPlayedAt: '2026-01-01T00:00:00Z' }),
            songRow('today', { lastPlayedAt: '2026-06-01T12:00:00Z' }),
            songRow('never_b', { lastPlayedAt: null }),
        ];
        const sorted = sortSongs(
            songs,
            SongListSort.RECENTLY_PLAYED,
            SortOrder.DESC,
            snapshotOf(songs),
        );
        expect(sorted.map((s) => s.id)).toEqual(['today', 'yesterday', 'never_a', 'never_b']);
    });

    it('ASC: oldest play first — and the nulls still trail', () => {
        const songs = [
            songRow('never', { lastPlayedAt: null }),
            songRow('yesterday', { lastPlayedAt: '2026-01-01T00:00:00Z' }),
            songRow('today', { lastPlayedAt: '2026-06-01T12:00:00Z' }),
        ];
        const sorted = sortSongs(
            songs,
            SongListSort.RECENTLY_PLAYED,
            SortOrder.ASC,
            snapshotOf(songs),
        );
        expect(sorted.map((s) => s.id)).toEqual(['yesterday', 'today', 'never']);
    });
});

describe('RANDOM — the seeded shuffle (stable within a snapshot, re-rolls on refresh)', () => {
    it('produces a permutation that is stable for the same seed', () => {
        const songs = Array.from({ length: 24 }, (_, i) => songRow(`s${i}`));
        const a = seededShuffle(songs, 'seed-1');
        const b = seededShuffle(songs, 'seed-1');
        const c = seededShuffle(songs, 'seed-2');

        expect(a.map((s) => s.id)).toEqual(b.map((s) => s.id));
        expect(a.map((s) => s.id)).not.toEqual(c.map((s) => s.id));
        // A permutation: same length, same id set, no losses.
        expect(a).toHaveLength(songs.length);
        expect(new Set(a.map((s) => s.id)).size).toBe(songs.length);
        expect(new Set(a.map((s) => s.id))).toEqual(new Set(songs.map((s) => s.id)));
    });

    it('SongListSort.RANDOM uses the snapshot session key (re-rolls on refresh)', () => {
        const songs = Array.from({ length: 12 }, (_, i) => songRow(`s${i}`));
        const first = sortSongs(
            songs,
            SongListSort.RANDOM,
            SortOrder.ASC,
            snapshotOf(songs, { sessionKey: 'load-1' }),
        );
        const second = sortSongs(
            songs,
            SongListSort.RANDOM,
            SortOrder.ASC,
            snapshotOf(songs, { sessionKey: 'load-2' }),
        );
        expect(first.map((s) => s.id)).not.toEqual(second.map((s) => s.id));
    });
});

describe('genre filters — honest empty (no genre column in the cloud schema yet)', () => {
    it('a genre filter on songs matches nothing, without crashing', () => {
        const songs = [songRow('a'), songRow('b')];
        expect(filterSongs(songs, { genreIds: ['gen_1'] }, snapshotOf(songs))).toEqual([]);
    });

    it('a genre filter on albums matches nothing, without crashing', () => {
        expect(filterAlbums([albumRow('alb_1')], { genreIds: ['gen_1'] })).toEqual([]);
    });

    it('the search term still narrows (title / artist / album haystack)', () => {
        const songs = [
            songRow('a', { artist: 'Queen', title: ' Bohemian Rhapsody ' }),
            songRow('b', { album: 'Queens Greatest', title: 'Some Other Song' }),
            songRow('c', { title: 'Unrelated' }),
        ];
        const hits = filterSongs(songs, { searchTerm: 'queen' }, snapshotOf(songs));
        expect(hits.map((s) => s.id)).toEqual(['a', 'b']);
    });

    it('album/artist id filters resolve through the snapshot indexes', () => {
        const songs = [songRow('a', { artist: 'Alpha' }), songRow('b', { artist: 'Beta' })];
        const artists = [artistRow('art_alpha', { key: 'alpha', name: 'Alpha' })];
        const snap = snapshotOf(songs, {
            artistById: new Map(artists.map((a) => [a.id, a])),
            artists,
        });
        const kept = filterSongs(songs, { artistIds: ['art_alpha'] }, snap);
        expect(kept.map((s) => s.id)).toEqual(['a']);
    });
});

describe('playTally hydration onto playCount / lastPlayedAt', () => {
    function tallyStorageWith(
        topTracks: Record<string, { artist: string; count: number; title: string }>,
    ) {
        let value: null | string = JSON.stringify({
            byHour: new Array<number>(24).fill(0),
            seconds: 1000,
            topTracks,
            tracks: 7,
        });
        return { read: () => value, write: (v: string) => void (value = v) };
    }

    it('adds the local tally counts onto the song rows and recomputes aggregates', () => {
        const songs = [
            songRow('a', { album: 'Debut', artist: 'Alpha', playCount: 2 }),
            songRow('b', { album: 'Debut', artist: 'Alpha', playCount: 0 }),
        ];
        const albums = [albumRow('alb_debut', { key: 'debut__alpha', songIds: ['a', 'b'] })];
        const artists = [artistRow('art_alpha', { key: 'alpha', songIds: ['a', 'b'] })];
        const snap = snapshotOf(songs, {
            albumById: new Map(albums.map((a) => [a.id, a])),
            albums,
            artistById: new Map(artists.map((a) => [a.id, a])),
            artists,
        });

        hydrateSnapshotWithTally(
            snap,
            new Date('2026-06-15T10:00:00Z'),
            tallyStorageWith({ a: { artist: 'Alpha', count: 3, title: 'Song a' } }),
        );

        expect(snap.songById.get('a')!.playCount).toBe(5); // 2 cloud + 3 local
        expect(snap.songById.get('b')!.playCount).toBe(0); // untouched
        expect(snap.songById.get('a')!.lastPlayedAt).toBe('2026-06-15T10:00:00.000Z');
        expect(snap.albums[0]!.playCount).toBe(5); // aggregate re-derived
        expect(snap.artists[0]!.playCount).toBe(5);
    });

    it('a tally with no snapshot-known tracks is a no-op', () => {
        const songs = [songRow('a', { playCount: 2 })];
        const snap = snapshotOf(songs);
        hydrateSnapshotWithTally(
            snap,
            new Date(),
            tallyStorageWith({ ghost: { artist: 'X', count: 9, title: 'Ghost' } }),
        );
        expect(snap.songById.get('a')!.playCount).toBe(2);
    });
});

describe('LibraryItem sanity for the Starred grouping (feishin contract)', () => {
    it('the song-like types used by favorites exist on the enum', () => {
        // Feishin's own favorite mutations group these three types onto the
        // same backend; the controller's isSongLikeType mirrors them.
        expect(LibraryItem.SONG).toBe('song');
        expect(LibraryItem.PLAYLIST_SONG).toBe('playlistSong');
        expect(LibraryItem.QUEUE_SONG).toBe('queueSong');
    });
});
