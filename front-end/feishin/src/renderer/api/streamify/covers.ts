/**
 * streamify/covers.ts - the thumbnail lane.
 *
 * Cover art in the cloud library is a set of external CDN URLs (the
 * cover_url column - YouTube Music thumbnails and friends). The mapping is
 * the URL shape feishin's image components already speak: normalized
 * entities carry `imageUrl` directly (BaseImage short-circuits on it), and
 * the id-based lookups below resolve the same URL with a stable cacheKey
 * so feishin's in-session image cache and the browser's HTTP cache do the
 * byte caching.
 *
 * The OPFS-backed persistent cache the kickoff mentions is NOT wired here:
 * feishin's ImageRequest contract is synchronous (url + cacheKey, fetched
 * by useNativeImage), so an OPFS read cannot slot in without either a
 * service worker (upstream's web.vite.config.ts PWA config - off the
 * pre-approved touch list) or upstream component changes. Filed as a
 * facade gap for A in decisions/p1/facade-gaps.md; the URL lane below is
 * complete and honest on its own.
 */

import { LibrarySnapshot } from './library';

import { LibraryItem } from '/@/shared/types/domain-types';

/** The stable cache key for an image (feishin's session cache unit). */
export function coverCacheKey(itemType: LibraryItem, id: string, size?: number): string {
    return ['streamify', itemType, id, size ?? ''].join(':');
}

/**
 * Cover URL for an entity id, from the loaded snapshot. Null when the
 * snapshot is not loaded yet or the entity has no cover - the UI renders
 * its placeholder (the honest empty state for art).
 */
export function coverUrlFor(
    snapshot: LibrarySnapshot | null | undefined,
    itemType: LibraryItem,
    id: string,
): null | string {
    if (id.startsWith('ytm:')) {
        const videoId = id.slice(4);
        return `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
    }
    if (id && /^[a-zA-Z0-9_-]{11}$/.test(id)) {
        return `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
    }
    if (snapshot === null || snapshot === undefined) {
        return null;
    }
    switch (itemType) {
        case LibraryItem.ALBUM: {
            const album = snapshot.albumById.get(id);
            return album?.coverUrl ?? null;
        }
        case LibraryItem.ALBUM_ARTIST:
        case LibraryItem.ARTIST: {
            const artist = snapshot.artistById.get(id);
            return artist?.coverUrl ?? null;
        }
        case LibraryItem.PLAYLIST: {
            const playlist = snapshot.playlistById.get(id);
            return playlist?.coverUrl ?? null;
        }
        case LibraryItem.SONG: {
            const song = snapshot.songById.get(id);
            return song?.coverUrl ?? null;
        }
        default:
            return null;
    }
}
