/**
 * streamify/derive.ts - the derivations feishin's "smart" read endpoints
 * need: top songs, similar songs, artist/album radio, random sets.
 *
 * Policy (P1 kickoff): "derive from what jam data actually has (play stats,
 * metadata); where data doesn't exist yet, return sensible fallbacks and
 * note it in decisions/p1/". The play-event lane (user_play_events) is the
 * real signal: play counts per track, per artist. The derivations below
 * use exactly that, and fall back to library order when a user has no
 * play history yet - never to fabricated data.
 */

import { LibrarySnapshot, SongRow } from './library';
import { seededShuffle } from './query';

export interface RandomSongFilter {
    genre?: string;
    limit?: number;
    maxYear?: number;
    minYear?: number;
    played: 'all' | 'never' | 'played';
}

/** Album radio: the album's songs, then the artist's, then most-played fill. */
export function albumRadio(snapshot: LibrarySnapshot, albumId: string, count: number): SongRow[] {
    const album = snapshot.albumById.get(albumId);
    const albumPool = albumSongs(snapshot, albumId);
    const seen = new Set(albumPool.map((row) => row.id));
    let pool = albumPool;
    if (album !== undefined && pool.length < count) {
        const artistPool = artistSongs(snapshot, artistIdForAlbum(snapshot, album)).filter(
            (row) => !seen.has(row.id),
        );
        artistPool.forEach((row) => seen.add(row.id));
        pool = [...pool, ...seededShuffle(artistPool, `${snapshot.sessionKey}:radio:${albumId}`)];
    }
    if (pool.length < count) {
        const fill = snapshot.songs
            .filter((row) => !seen.has(row.id))
            .sort((a, b) => b.playCount - a.playCount || a.title.localeCompare(b.title));
        pool = [...pool, ...fill];
    }
    return pool.slice(0, count);
}

/** Songs of an album (by feishin album id), library order. */
export function albumSongs(snapshot: LibrarySnapshot, albumId: string): SongRow[] {
    const album = snapshot.albumById.get(albumId);
    if (album === undefined) {
        return [];
    }
    return album.songIds
        .map((id) => snapshot.songById.get(id))
        .filter((row): row is SongRow => row !== undefined);
}

/**
 * Artist radio: the artist's songs shuffled, topped up from the user's
 * most-played library when the artist alone cannot fill the count.
 */
export function artistRadio(snapshot: LibrarySnapshot, artistId: string, count: number): SongRow[] {
    const artistPool = seededShuffle(
        artistSongs(snapshot, artistId),
        `${snapshot.sessionKey}:radio:${artistId}`,
    );
    if (artistPool.length >= count) {
        return artistPool.slice(0, count);
    }
    const seen = new Set(artistPool.map((row) => row.id));
    const fill = snapshot.songs
        .filter((row) => !seen.has(row.id))
        .sort((a, b) => b.playCount - a.playCount || a.title.localeCompare(b.title));
    return [...artistPool, ...fill].slice(0, count);
}

/** Songs of an artist (by feishin artist id), library order. */
export function artistSongs(snapshot: LibrarySnapshot, artistId: string): SongRow[] {
    const artist = snapshot.artistById.get(artistId);
    if (artist === undefined) {
        return [];
    }
    return artist.songIds
        .map((id) => snapshot.songById.get(id))
        .filter((row): row is SongRow => row !== undefined);
}

/**
 * Random songs with the query's filters. Year/genre filters have no data
 * to match yet, so they honestly empty the result (documented); the
 * played filter uses the real play stats.
 */
export function randomSongs(snapshot: LibrarySnapshot, filter: RandomSongFilter): SongRow[] {
    // A genre filter has nothing to match yet (no genre data) - the honest
    // result is empty, recorded in decisions/p1/data-mapping.md.
    if (filter.genre !== undefined && filter.genre !== '') {
        return [];
    }
    if (filter.minYear !== undefined || filter.maxYear !== undefined) {
        return [];
    }
    let rows = snapshot.songs;
    if (filter.played === 'played') {
        rows = rows.filter((row) => row.playCount > 0);
    } else if (filter.played === 'never') {
        rows = rows.filter((row) => row.playCount === 0);
    }
    const shuffled = seededShuffle(rows, `${snapshot.sessionKey}:random:${snapshot.songs.length}`);
    return shuffled.slice(0, filter.limit ?? 10);
}

/**
 * Similar songs for a seed track. Derivation (from play stats + metadata):
 * same artist first (play-count ranked, seed excluded), then the rest of
 * the library by play count. No co-play matrix exists yet - recorded in
 * decisions/p1/data-mapping.md as the facade need for a richer mix.
 */
export function similarSongs(snapshot: LibrarySnapshot, songId: string, count: number): SongRow[] {
    const seed = snapshot.songById.get(songId);
    if (seed === undefined) {
        return [];
    }
    const sameArtist = artistSongs(
        snapshot,
        seed.artist.length > 0 ? artistIdForName(snapshot, seed.artist) : '',
    )
        .filter((row) => row.id !== seed.id)
        .sort((a, b) => b.playCount - a.playCount || a.title.localeCompare(b.title));
    const rest = snapshot.songs
        .filter((row) => row.id !== seed.id && row.artist !== seed.artist)
        .sort((a, b) => b.playCount - a.playCount || a.title.localeCompare(b.title));
    return [...sameArtist, ...rest].slice(0, count);
}

/**
 * Top songs for an artist: play-count ranked from the user's own events.
 * Fallback (no events for the artist): the artist's songs in library
 * order - a documented, honest fallback, not invented popularity.
 */
export function topSongs(
    snapshot: LibrarySnapshot,
    artistId: null | string,
    artistName: null | string,
    limit: number,
): SongRow[] {
    let candidates: SongRow[];
    if (artistId !== null) {
        candidates = artistSongs(snapshot, artistId);
    } else if (artistName !== null) {
        const key = artistName.trim().toLowerCase();
        candidates = snapshot.songs.filter((row) => row.artist.trim().toLowerCase() === key);
    } else {
        candidates = [];
    }
    return [...candidates]
        .sort((a, b) => b.playCount - a.playCount || a.title.localeCompare(b.title))
        .slice(0, limit);
}

function artistIdForAlbum(snapshot: LibrarySnapshot, album: { artistName: string }): string {
    return artistIdForName(snapshot, album.artistName);
}

function artistIdForName(snapshot: LibrarySnapshot, artistName: string): string {
    const key = artistName.trim().toLowerCase();
    for (const artist of snapshot.artists) {
        if (artist.key === key) {
            return artist.id;
        }
    }
    return '';
}
