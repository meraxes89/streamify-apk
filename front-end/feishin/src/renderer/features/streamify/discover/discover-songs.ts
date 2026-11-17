/**
 * features/streamify/discover/discover-songs.ts — W2-B: DiscoverTrack →
 * the playable feishin Song.
 *
 * ID NAMESPACE (the W1-B contract, mirrored here so the rebase unifies
 * cleanly): a discover track the snapshot does not know maps to id
 * `ytm:<videoId>` — the controller's getStreamUrl decodes the prefix
 * straight to the resolver, so playback never requires snapshot
 * membership. Tracks the snapshot DOES know (by videoId or title+artist
 * signature) keep their cloud track ids — likes and play stats stay
 * joined to the library row.
 */

import type { LibrarySnapshot } from '/@/renderer/api/streamify/library';

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { albumArtistIdOf, albumIdOf } from '/@/renderer/api/streamify/ids';
import { YTM_SONG_ID_PREFIX } from '/@/renderer/features/streamify/credits/song-video-id';
import {
    discoverSig,
    type DiscoverTrack,
} from '/@/renderer/features/streamify/discover/discover-engine';
import { LibraryItem, type RelatedArtist, ServerType, Song } from '/@/shared/types/domain-types';

type ServerId = string;

/**
 * One discover track → its playable Song. Cloud id when the snapshot
 * knows it (stats stay joined), `ytm:<videoId>` when it does not.
 */
export function discoverTrackToSong(
    track: DiscoverTrack,
    serverId: ServerId,
    snapshot: LibrarySnapshot | null,
): Song {
    let songId = `${YTM_SONG_ID_PREFIX}${track.videoId}`;
    if (snapshot !== null) {
        const byVideoId = rowsByVideoId(snapshot);
        const bySig = rowsBySig(snapshot);
        const known =
            byVideoId.get(track.videoId) ??
            bySig.get(discoverSig(track.title, track.artist)) ??
            null;
        if (known !== null) songId = known.id;
    }

    const artist =
        track.artist !== null && track.artist.trim() !== '' ? track.artist : 'Unknown Artist';
    const artists = [relatedArtist(artist)];
    const generatedAt = snapshot?.loadedAt ?? new Date().toISOString();

    return {
        _itemType: LibraryItem.SONG,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        album: null,
        albumArtistName: artist,
        albumArtists: artists,
        albumId: albumIdOf(null, artist),
        artistName: artist,
        artists,
        bitDepth: null,
        bitRate: 0,
        blurHash: null,
        bpm: null,
        channels: null,
        codec: null,
        comment: null,
        compilation: null,
        container: null,
        createdAt: generatedAt,
        date: null,
        discNumber: 0,
        discSubtitle: null,
        duration: track.durationMs ?? 0,
        explicitStatus: null,
        folderId: null,
        gain: null,
        genres: [],
        id: songId,
        imageId: null,
        imageUrl: null,
        lastPlayedAt: null,
        libraryId: null,
        libraryName: null,
        lyrics: null,
        mbzAlbumId: null,
        mbzAlbumType: null,
        mbzRecordingId: null,
        mbzReleaseGroupId: null,
        mbzTrackId: null,
        missing: null,
        name: track.title,
        originalDate: null,
        originalYear: null,
        participants: null,
        path: null,
        peak: null,
        playCount: 0,
        playlistItemId: undefined,
        releaseDate: null,
        releaseYear: null,
        sampleRate: null,
        size: 0,
        sortName: track.title,
        tags: null,
        thumbHash: null,
        trackNumber: 0,
        trackSubtitle: null,
        updatedAt: generatedAt,
        userFavorite: false,
        userRating: null,
        year: null,
    };
}

/** The snapshot lookup the shelf uses (live library or null). */
export function liveSnapshot(): LibrarySnapshot | null {
    return currentStreamifyLibrary();
}

function relatedArtist(name: string): RelatedArtist {
    return {
        id: albumArtistIdOf(name),
        imageId: null,
        imageUrl: null,
        name,
        userFavorite: false,
        userRating: null,
    };
}

/** (title, artist) sig → snapshot row index (the cross-tier dedup). */
function rowsBySig(snapshot: LibrarySnapshot): Map<string, { id: string }> {
    const map = new Map<string, { id: string }>();
    for (const row of snapshot.songs) {
        map.set(discoverSig(row.title, row.artist), { id: row.id });
    }
    return map;
}

/** videoId → snapshot row index (per call; snapshots are small). */
function rowsByVideoId(snapshot: LibrarySnapshot): Map<string, { id: string }> {
    const map = new Map<string, { id: string }>();
    for (const row of snapshot.songs) {
        if (row.streamUrl !== null && row.streamUrl !== '') map.set(row.streamUrl, { id: row.id });
    }
    return map;
}
