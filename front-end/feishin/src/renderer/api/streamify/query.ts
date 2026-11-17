/**
 * streamify/query.ts - the local query engine: feishin's list contracts
 * (filters, sort enums, startIndex/limit pagination) resolved entirely
 * in-memory over the library snapshot ("jam data is in memory, so resolve
 * locally").
 *
 * Sort coverage policy: every enum member has a comparator. Members whose
 * data does not exist in the cloud library yet (rating, bpm, codec, year,
 * genre, ...) fall back to the entity's natural sort with a comment - a
 * list sorted by a missing field is still a stable, correctly-paginated
 * list, and the fallback is recorded in decisions/p1/data-mapping.md
 * rather than silently swallowed.
 *
 * RANDOM: a seeded shuffle (mulberry32 over the snapshot's session key),
 * so pages are stable within one snapshot load and re-roll on refresh.
 */

import { albumArtistIdOf } from './ids';
import { AlbumRow, ArtistRow, LibrarySnapshot, PlaylistRow, SongRow } from './library';

import {
    AlbumArtistListSort,
    AlbumListSort,
    ArtistListSort,
    PlaylistListSort,
    SongListSort,
    SortOrder,
} from '/@/shared/types/domain-types';

// ---------------------------------------------------------------------------
// Pagination (feishin's BasePaginatedResponse contract)
// ---------------------------------------------------------------------------

export interface AlbumListFilter {
    artistIds?: string[];
    compilation?: boolean;
    favorite?: boolean;
    genreIds?: string[];
    maxYear?: number;
    minYear?: number;
    searchTerm?: string;
}

export interface ArtistListFilter {
    favorite?: boolean;
    searchTerm?: string;
}

// ---------------------------------------------------------------------------
// Seeded random (stable within a snapshot load)
// ---------------------------------------------------------------------------

export interface Paginated<T> {
    items: T[];
    startIndex: number;
    totalRecordCount: number;
}

export interface SearchLimits {
    albumArtistLimit?: number;
    albumArtistStartIndex?: number;
    albumLimit?: number;
    albumStartIndex?: number;
    songLimit?: number;
    songStartIndex?: number;
}

export interface SongListFilter {
    albumArtistIds?: string[];
    albumIds?: string[];
    artistIds?: string[];
    favorite?: boolean;
    genreIds?: string[];
    maxYear?: number;
    minYear?: number;
    searchTerm?: string;
}

// ---------------------------------------------------------------------------
// Comparators
// ---------------------------------------------------------------------------

type Comparator<T> = (a: T, b: T) => number;

export function filterAlbums(
    rows: AlbumRow[],
    filter: AlbumListFilter,
    snapshot?: LibrarySnapshot,
): AlbumRow[] {
    const artistIds = filter.artistIds !== undefined ? new Set(filter.artistIds) : null;
    const term = filter.searchTerm?.trim().toLowerCase() ?? '';
    return rows.filter((row) => {
        if (artistIds !== null && !artistIds.has(artistIdOfAlbum(row))) {
            return false;
        }
        if (filter.genreIds !== undefined && filter.genreIds.length > 0) {
            // YTM tiles (W1-B) are branch-handled by the controller before
            // this filter runs; reaching here with a genre filter stays the
            // honest empty (no genre data on snapshot rows).
            return false;
        }
        if (filter.minYear !== undefined || filter.maxYear !== undefined) {
            return false;
        }
        if (filter.favorite !== undefined) {
            // W1-B: the user_item_favorites ledger backs the filter now.
            return snapshot !== undefined
                ? snapshot.likedAlbumIds.has(row.id) === filter.favorite
                : false;
        }
        if (filter.compilation !== undefined) {
            return false;
        }
        if (term !== '') {
            const haystack = `${row.name ?? ''}\n${row.artistName ?? ''}`.toLowerCase();
            if (!haystack.includes(term)) {
                return false;
            }
        }
        return true;
    });
}

export function filterArtists(
    rows: ArtistRow[],
    filter: ArtistListFilter,
    snapshot?: LibrarySnapshot,
): ArtistRow[] {
    const term = filter.searchTerm?.trim().toLowerCase() ?? '';
    return rows.filter((row) => {
        if (filter.favorite !== undefined) {
            // W1-B: the user_item_favorites ledger backs the filter now.
            return snapshot !== undefined
                ? snapshot.likedArtistIds.has(row.id) === filter.favorite
                : false;
        }
        if (term !== '' && !row.name.toLowerCase().includes(term)) {
            return false;
        }
        return true;
    });
}

export function filterPlaylists(
    rows: PlaylistRow[],
    searchTerm: null | string | undefined,
): PlaylistRow[] {
    const term = searchTerm?.trim().toLowerCase() ?? '';
    if (term === '') {
        return rows;
    }
    return rows.filter((row) => row.name.toLowerCase().includes(term));
}

export function filterSongs(
    rows: SongRow[],
    filter: SongListFilter,
    snapshot: LibrarySnapshot,
): SongRow[] {
    // albumIds arrive as feishin album ids; resolve to keys for matching.
    const albumKeys =
        filter.albumIds !== undefined ? new Set(lookupAlbumKeys(snapshot, filter.albumIds)) : null;
    const artistKeys =
        filter.artistIds !== undefined || filter.albumArtistIds !== undefined
            ? lookupArtistKeys(snapshot, [
                  ...(filter.artistIds ?? []),
                  ...(filter.albumArtistIds ?? []),
              ])
            : null;
    const term = filter.searchTerm?.trim().toLowerCase() ?? '';

    return rows.filter((row) => {
        if (albumKeys !== null && !albumKeys.has(albumKeyOfRow(row))) {
            return false;
        }
        if (artistKeys !== null && !artistKeys.has(artistKeyOfRow(row))) {
            return false;
        }
        if (filter.favorite !== undefined && row.liked !== filter.favorite) {
            return false;
        }
        // No genre data yet: a genre filter honestly matches nothing.
        if (filter.genreIds !== undefined && filter.genreIds.length > 0) {
            return false;
        }
        // No year data yet: a year filter honestly matches nothing.
        if (filter.minYear !== undefined || filter.maxYear !== undefined) {
            return false;
        }
        if (term !== '') {
            const haystack =
                `${row.title ?? ''}\n${row.artist ?? ''}\n${row.album ?? ''}`.toLowerCase();
            if (!haystack.includes(term)) {
                return false;
            }
        }
        return true;
    });
}

export function paginate<T>(rows: T[], startIndex: number, limit?: number): Paginated<T> {
    const start = Math.max(0, startIndex);
    const end = limit === undefined ? undefined : start + Math.max(0, limit);
    const slice = end === undefined ? rows.slice(start) : rows.slice(start, end);
    return {
        items: slice,
        startIndex: start,
        totalRecordCount: rows.length,
    };
}

// Songs ---------------------------------------------------------------------

/** Local search across all three sections (case-insensitive substring). */
export function searchSnapshot(
    snapshot: LibrarySnapshot,
    term: string,
    limits: SearchLimits,
): { albumArtistRows: ArtistRow[]; albumRows: AlbumRow[]; songRows: SongRow[] } {
    const needle = term.trim().toLowerCase();
    const songMatches =
        needle === ''
            ? []
            : snapshot.songs.filter(
                  (row) =>
                      (row.title ?? '').toLowerCase().includes(needle) ||
                      (row.artist ?? '').toLowerCase().includes(needle) ||
                      (row.album ?? '').toLowerCase().includes(needle),
              );
    const albumMatches =
        needle === ''
            ? []
            : snapshot.albums.filter(
                  (row) =>
                      (row.name ?? '').toLowerCase().includes(needle) ||
                      (row.artistName ?? '').toLowerCase().includes(needle),
              );
    const artistMatches =
        needle === ''
            ? []
            : snapshot.artists.filter((row) => (row.name ?? '').toLowerCase().includes(needle));

    return {
        albumArtistRows: sliceSection(
            artistMatches,
            limits.albumArtistStartIndex,
            limits.albumArtistLimit,
        ),
        albumRows: sliceSection(albumMatches, limits.albumStartIndex, limits.albumLimit),
        songRows: sliceSection(songMatches, limits.songStartIndex, limits.songLimit),
    };
}

export function seededShuffle<T>(rows: T[], seedKey: string): T[] {
    const rand = mulberry32(hashString(seedKey));
    const out = [...rows];
    for (let i = out.length - 1; i > 0; i -= 1) {
        const j = Math.floor(rand() * (i + 1));
        [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
}

export function sortAlbums(
    rows: AlbumRow[],
    sortBy: AlbumListSort,
    sortOrder: SortOrder,
    snapshot: LibrarySnapshot,
): AlbumRow[] {
    const list = [...rows];
    let comparator: Comparator<AlbumRow>;
    switch (sortBy) {
        case AlbumListSort.ALBUM_ARTIST:
        case AlbumListSort.ARTIST:
            comparator = (a, b) =>
                a.artistName.localeCompare(b.artistName) || a.name.localeCompare(b.name);
            break;
        case AlbumListSort.DURATION:
            comparator = byNumber((row) => row.durationMs);
            break;
        case AlbumListSort.PLAY_COUNT:
            comparator = byNumber((row) => row.playCount);
            break;
        case AlbumListSort.RANDOM:
            return directionForShuffle(
                seededShuffle(list, `${snapshot.sessionKey}:album`),
                sortOrder,
            );
        case AlbumListSort.RECENTLY_ADDED:
            // Negated for DESC=newest-first parity with the song sort (the
            // P3-B polarity fix — see sortSongs).
            comparator = byNumber((row) => -row.addedRank);
            break;
        case AlbumListSort.RECENTLY_PLAYED:
            return isoSorted(list, (row) => row.lastPlayedAt, sortOrder);
        case AlbumListSort.SONG_COUNT:
            comparator = byNumber((row) => row.songIds.length);
            break;
        // No cloud data yet - natural fallback (decisions/p1).
        case AlbumListSort.COMMUNITY_RATING:
        case AlbumListSort.CRITIC_RATING:
        case AlbumListSort.EXPLICIT_STATUS:
        case AlbumListSort.FAVORITED:
        case AlbumListSort.ID:
        case AlbumListSort.NAME:
        case AlbumListSort.RATING:
        case AlbumListSort.RELEASE_DATE:
        case AlbumListSort.SORT_NAME:
        case AlbumListSort.YEAR:
        default:
            comparator = byString((row) => row.name);
            break;
    }
    list.sort(direction(comparator, sortOrder));
    return list;
}

export function sortArtists(
    rows: ArtistRow[],
    sortBy: AlbumArtistListSort | ArtistListSort,
    sortOrder: SortOrder,
    snapshot: LibrarySnapshot,
): ArtistRow[] {
    const list = [...rows];
    let comparator: Comparator<ArtistRow>;
    switch (sortBy) {
        case AlbumArtistListSort.ALBUM_COUNT:
            comparator = byNumber((row) => row.albumIds.length);
            break;
        case AlbumArtistListSort.DURATION:
            comparator = byNumber((row) => row.durationMs);
            break;
        case AlbumArtistListSort.PLAY_COUNT:
            comparator = byNumber((row) => row.playCount);
            break;
        case AlbumArtistListSort.RANDOM:
            return directionForShuffle(
                seededShuffle(list, `${snapshot.sessionKey}:artist`),
                sortOrder,
            );
        case AlbumArtistListSort.RECENTLY_ADDED:
            // Negated for DESC=newest-first parity with the song sort (the
            // P3-B polarity fix — see sortSongs).
            comparator = byNumber((row) => -row.addedRank);
            break;
        case AlbumArtistListSort.SONG_COUNT:
            comparator = byNumber((row) => row.songIds.length);
            break;
        // No cloud data yet - natural fallback (decisions/p1).
        case AlbumArtistListSort.ALBUM:
        case AlbumArtistListSort.FAVORITED:
        case AlbumArtistListSort.NAME:
        case AlbumArtistListSort.RATING:
        case AlbumArtistListSort.RELEASE_DATE:
        default:
            comparator = byString((row) => row.name);
            break;
    }
    list.sort(direction(comparator, sortOrder));
    return list;
}

export function sortPlaylists(
    rows: PlaylistRow[],
    sortBy: PlaylistListSort,
    sortOrder: SortOrder,
): PlaylistRow[] {
    const list = [...rows];
    let comparator: Comparator<PlaylistRow>;
    switch (sortBy) {
        case PlaylistListSort.SONG_COUNT:
            comparator = byNumber((row) => row.songIds.length);
            break;
        case PlaylistListSort.UPDATED_AT:
            return isoSorted(list, (row) => row.updatedAt, sortOrder);
        // No cloud data yet - natural fallback (decisions/p1).
        case PlaylistListSort.DURATION:
        case PlaylistListSort.NAME:
        case PlaylistListSort.OWNER:
        case PlaylistListSort.PUBLIC:
        default:
            comparator = byString((row) => row.name);
            break;
    }
    list.sort(direction(comparator, sortOrder));
    return list;
}

export function sortSongs(
    rows: SongRow[],
    sortBy: SongListSort,
    sortOrder: SortOrder,
    snapshot: LibrarySnapshot,
): SongRow[] {
    const list = [...rows];
    let comparator: Comparator<SongRow>;
    switch (sortBy) {
        case SongListSort.ALBUM:
            comparator = (a, b) =>
                (a.album ?? '').localeCompare(b.album ?? '') || a.title.localeCompare(b.title);
            break;
        case SongListSort.ALBUM_ARTIST:
            comparator = byString((row) => row.artist);
            break;
        case SongListSort.ARTIST:
            comparator = (a, b) =>
                a.artist.localeCompare(b.artist) || a.title.localeCompare(b.title);
            break;
        case SongListSort.DURATION:
            comparator = byNumber((row) => row.durationSec ?? 0);
            break;
        case SongListSort.FAVORITED:
            // FAVORITED polarity (the P3-B fix): feishin's own list sort
            // (shared/api/utils.ts) orders `userFavorite` with the requested
            // direction and the sort dropdown's default for it is DESC —
            // favorites FIRST. Match that: ASC = unliked first (boolean
            // false-first, SQL parity), DESC (after the direction flip) =
            // liked first. The old comparator had the polarity inverted,
            // sinking favorites under their own default direction.
            comparator = (a, b) =>
                Number(a.liked) - Number(b.liked) || a.title.localeCompare(b.title);
            break;
        case SongListSort.ID:
            comparator = byString((row) => row.id);
            break;
        case SongListSort.PLAY_COUNT:
            comparator = byNumber((row) => row.playCount);
            break;
        case SongListSort.RANDOM:
            return directionForShuffle(
                seededShuffle(list, `${snapshot.sessionKey}:song`),
                sortOrder,
            );
        case SongListSort.RECENTLY_ADDED:
            // NEWEST polarity (the P3-B fix, caught by the curation lock):
            // addedRank counts UP from 0 = most recent (likes arrive
            // most-recent-first), so ascending-on-rank would put the
            // NEWEST first under ASC — the exact inverse of every server's
            // "recently added" semantics. Negate: DESC (the Home shelf's
            // direction, Navidrome parity) = newest first, ASC = oldest
            // first.
            comparator = byNumber((row) => -row.addedRank);
            break;
        case SongListSort.RECENTLY_PLAYED:
            return isoSorted(list, (row) => row.lastPlayedAt, sortOrder);
        // No cloud data for these yet - natural fallback (decisions/p1).
        case SongListSort.BPM:
        case SongListSort.CHANNELS:
        case SongListSort.COMMENT:
        case SongListSort.EXPLICIT_STATUS:
        case SongListSort.GENRE:
        case SongListSort.NAME:
        case SongListSort.RATING:
        case SongListSort.RELEASE_DATE:
        case SongListSort.RELEASE_YEAR:
        case SongListSort.SORT_NAME:
        case SongListSort.YEAR:
        default:
            comparator = byString((row) => row.title);
            break;
    }
    list.sort(direction(comparator, sortOrder));
    return list;
}

function albumKeyOfRow(row: SongRow): string {
    // Match library.ts's grouping without re-importing a private helper.
    const album = (row.album ?? '').trim().toLowerCase();
    const artist = (row.artist ?? '').trim().toLowerCase();
    return `${album === '' ? 'unknown album' : album}__${artist === '' ? 'unknown artist' : artist}`;
}

function artistIdOfAlbum(row: AlbumRow): string {
    // The ids module is the single derivation point for entity ids.
    return albumArtistIdOf(row.artistName);
}

// Albums --------------------------------------------------------------------

function artistKeyOfRow(row: SongRow): string {
    const artist = (row.artist ?? '').trim().toLowerCase();
    return artist === '' ? 'unknown artist' : artist;
}

function byNumber<T>(select: (row: T) => number): Comparator<T> {
    return (a, b) => select(a) - select(b);
}

function byString<T>(select: (row: T) => string): Comparator<T> {
    return (a, b) => select(a).localeCompare(select(b));
}

function direction<T>(comparator: Comparator<T>, sortOrder: SortOrder): Comparator<T> {
    if (sortOrder === SortOrder.DESC) {
        return (a, b) => comparator(b, a);
    }
    return comparator;
}

// Artists -------------------------------------------------------------------

function directionForShuffle<T>(shuffled: T[], sortOrder: SortOrder): T[] {
    // A shuffled list is direction-agnostic; DESC reverses for consistency.
    return sortOrder === SortOrder.DESC ? [...shuffled].reverse() : shuffled;
}

function hashString(s: string): number {
    let h = 2166136261;
    for (let i = 0; i < s.length; i += 1) {
        h ^= s.charCodeAt(i);
        h = Math.imul(h, 16777619);
    }
    return h >>> 0;
}

/**
 * Sort by an ISO timestamp with nulls ALWAYS last, regardless of
 * direction (a never-played row is not "the most recently played").
 */
function isoSorted<T>(rows: T[], select: (row: T) => null | string, sortOrder: SortOrder): T[] {
    const present = rows.filter((row) => {
        const value = select(row);
        return value !== null && value !== '';
    });
    const absent = rows.filter((row) => {
        const value = select(row);
        return value === null || value === '';
    });
    present.sort((a, b) => {
        const delta = Date.parse(select(a) ?? '') - Date.parse(select(b) ?? '');
        return sortOrder === SortOrder.DESC ? -delta : delta;
    });
    return [...present, ...absent];
}

// Playlists -----------------------------------------------------------------

function lookupAlbumKeys(snapshot: LibrarySnapshot, albumIds: string[] | undefined): string[] {
    if (albumIds === undefined) {
        return [];
    }
    return albumIds
        .map((id) => snapshot.albumById.get(id)?.key)
        .filter((key): key is string => key !== undefined);
}

// Search --------------------------------------------------------------------

function lookupArtistKeys(snapshot: LibrarySnapshot, artistIds: string[]): Set<string> {
    const keys = new Set<string>();
    for (const id of artistIds) {
        const artist = snapshot.artistById.get(id);
        if (artist !== undefined) {
            keys.add(artist.key);
        }
    }
    return keys;
}

function mulberry32(seed: number): () => number {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

function sliceSection<T>(
    rows: T[],
    startIndex: number | undefined,
    limit: number | undefined,
): T[] {
    const start = Math.max(0, startIndex ?? 0);
    return limit === undefined ? rows.slice(start) : rows.slice(start, start + Math.max(0, limit));
}
