/**
 * api/streamify/catalog/favorite-items.ts — the W1-B read side of the
 * album/artist favorites ledger: getFavoriteAlbums / getFavoriteArtists.
 *
 * These are the fork-side favorites endpoints (feishin's stock controller
 * contract surfaces favorites through the list filters — getAlbumList /
 * getArtistList with favorite: true — which the snapshot's
 * likedAlbumIds/likedArtistIds now back; these functions are the same
 * reads as explicit, paginated endpoints for the fork's query lanes and
 * the W1-B contract tests). Order: recency of the favorite itself is not
 * recoverable from a Set, so the honest order is the library's own
 * (arrival rank for albums, name for artists) — documented in the ADR.
 */

import type { Album, AlbumArtist } from '/@/shared/types/domain-types';

import type { LibrarySnapshot } from '../library';

import { normalizeAlbum, normalizeAlbumArtist } from '../normalize';
import { paginate, type Paginated } from '../query';

/** The favorited albums, paginated (library order). */
export function getFavoriteAlbums(
    snapshot: LibrarySnapshot,
    serverId: string,
    query: { limit?: number; startIndex?: number } = {},
): Paginated<Album> {
    const rows = snapshot.albums.filter((row) => snapshot.likedAlbumIds.has(row.id));
    const page = paginate(rows, query.startIndex ?? 0, query.limit);
    return {
        items: page.items.map((row) => normalizeAlbum(row, snapshot, serverId)),
        startIndex: page.startIndex,
        totalRecordCount: page.totalRecordCount,
    };
}

/** The favorited artists, paginated (library order). */
export function getFavoriteArtists(
    snapshot: LibrarySnapshot,
    serverId: string,
    query: { limit?: number; startIndex?: number } = {},
): Paginated<AlbumArtist> {
    const rows = snapshot.artists.filter((row) => snapshot.likedArtistIds.has(row.id));
    const page = paginate(rows, query.startIndex ?? 0, query.limit);
    return {
        items: page.items.map((row) => normalizeAlbumArtist(row, snapshot, serverId)),
        startIndex: page.startIndex,
        totalRecordCount: page.totalRecordCount,
    };
}
