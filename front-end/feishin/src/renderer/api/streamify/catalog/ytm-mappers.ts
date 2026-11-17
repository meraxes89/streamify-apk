/**
 * api/streamify/catalog/ytm-mappers.ts — the YTM DTO → feishin domain DTO
 * mappers (W1-B). Pure, total, honest: fields the YTM DTO does not carry
 * resolve to their honest empties, exactly like normalize.ts does for
 * snapshot rows (the per-field ledger lives in the W1-B ADR).
 *
 * ID NAMESPACES (the deep-wiring contract):
 *   - a YTM song outside the snapshot maps to id `ytm:<videoId>` — the
 *     controller's getStreamUrl decodes that prefix straight to the
 *     resolver (playback never requires snapshot membership);
 *   - a YTM album maps to id `ytm-album:<browseId>` (getAlbumDetail
 *     resolves it through the provider);
 *   - a YTM artist maps to id `ytm-artist:<browseId>` (getAlbumArtistDetail
 *     resolves it through the provider);
 *   - songs that ARE in the snapshot keep their cloud track ids — the
 *     mapper only creates ytm: ids for videoIds the snapshot doesn't know.
 */

import type { LibrarySnapshot, SongRow } from '../library';
import type {
    YtmAlbumDetail,
    YtmAlbumRef,
    YtmArtistDetail,
    YtmArtistRef,
    YtmSongRef,
} from '../metadata/provider';

import { albumArtistIdOf, albumIdOf, cloudTrackIdOf } from '../ids';
import { normalizeSong } from '../normalize';

import {
    Album,
    AlbumArtist,
    LibraryItem,
    RelatedArtist,
    ServerType,
    Song,
} from '/@/shared/types/domain-types';

type ServerId = string;

/** The ytm: song-id prefix (getStreamUrl decodes it to the videoId). */
export const YTM_SONG_ID_PREFIX = 'ytm:';
export const YTM_ALBUM_ID_PREFIX = 'ytm-album:';
export const YTM_ARTIST_ID_PREFIX = 'ytm-artist:';

export function browseIdOfYtmAlbum(albumId: string): null | string {
    return albumId.startsWith(YTM_ALBUM_ID_PREFIX)
        ? albumId.slice(YTM_ALBUM_ID_PREFIX.length)
        : null;
}

export function browseIdOfYtmArtist(artistId: string): null | string {
    return artistId.startsWith(YTM_ARTIST_ID_PREFIX)
        ? artistId.slice(YTM_ARTIST_ID_PREFIX.length)
        : null;
}

/**
 * The cloud track id a like would need for a YTM song (the trk_ derivation
 * over title_artist — the same id the Android app upserts). Used when a
 * user likes a radio/search song that is not in the snapshot yet: the
 * favorites lane upserts the catalog row from the Song DTO facts.
 */
export function cloudTrackIdOfSong(song: Song): string {
    return cloudTrackIdOf(song.name, song.artistName);
}

/** Extract a YouTube video ID from a videoId, watch URL, or youtu.be link. */
export function extractVideoId(raw: null | string | undefined): null | string {
    if (!raw) return null;
    const trimmed = raw.trim();
    if (!trimmed) return null;

    const match = trimmed.match(/(?:v=|youtu\.be\/|embed\/|\/v\/|\/vi\/)([A-Za-z0-9_-]{11})/);
    if (match) return match[1] ?? null;

    // If it's a URL (e.g. expired googlevideo link), or contains path separators / query params, it's not a video ID:
    if (/^https?:\/\//i.test(trimmed) || trimmed.includes('/') || trimmed.includes('?') || trimmed.length > 32) {
        return null;
    }

    return trimmed;
}

/** The snapshot's (sig → row) index for cross-tier dedup. */
export function songBySig(snapshot: LibrarySnapshot): Map<string, SongRow> {
    const map = new Map<string, SongRow>();
    for (const row of snapshot.songs) map.set(songSig(row.title, row.artist), row);
    return map;
}

/** videoId → snapshot row index (built per call; snapshots are small). */
export function songByVideoId(snapshot: LibrarySnapshot): Map<string, SongRow> {
    const map = new Map<string, SongRow>();
    for (const row of snapshot.songs) {
        const vid = extractVideoId(row.streamUrl);
        if (vid !== null) map.set(vid, row);
    }
    return map;
}

/**
 * The (title, artist) signature — the cross-tier dedup key: a YTM result
 * matching a snapshot row's title+artist is the same track, so the mapper
 * reuses the snapshot's cloud track id (keeps likes/play stats joined).
 */
export function songSig(title: null | string | undefined, artist: null | string | undefined) {
    return `${(title ?? '').trim().toLowerCase()}|${(artist ?? '').trim().toLowerCase()}`;
}

/** A ytm: song id → its videoId (null for non-ytm ids). */
export function videoIdOfYtmSong(songId: string): null | string {
    return songId.startsWith(YTM_SONG_ID_PREFIX) ? songId.slice(YTM_SONG_ID_PREFIX.length) : null;
}

/** The album detail (with tracks) for a ytm-album: id. */
export function ytmAlbumDetailToAlbum(
    detail: YtmAlbumDetail,
    snapshot: LibrarySnapshot,
    serverId: ServerId,
): Album & { songs: Song[] } {
    const base = ytmToAlbum(
        {
            artist: detail.artist,
            browseId: detail.browseId,
            kind: 'album',
            thumbnailUrl: detail.thumbnailUrl ?? null,
            title: detail.title,
            trackCount: detail.tracks.length,
            year: detail.year ?? null,
        },
        serverId,
    );
    const byVideoId = songByVideoId(snapshot);
    const bySig = songBySig(snapshot);
    return {
        ...base,
        releaseYear: detail.year ?? null,
        songs: detail.tracks.map((t) => ytmToSong(t, snapshot, serverId, { bySig, byVideoId })),
    };
}

/** browseId → the ytm-album:/ytm-artist: id (and back). */
export function ytmAlbumIdOf(browseId: string): string {
    return `${YTM_ALBUM_ID_PREFIX}${browseId}`;
}

// ---------------------------------------------------------------------------
// Song
// ---------------------------------------------------------------------------

/** The full artist page DTO (discography split rides the W1-B ADR). */
export function ytmArtistDetailToAlbumArtist(
    detail: YtmArtistDetail,
    serverId: ServerId,
): AlbumArtist {
    const releaseCount = detail.albums.length + detail.singles.length + detail.liveAlbums.length;
    return {
        _itemType: LibraryItem.ALBUM_ARTIST,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumCount: releaseCount,
        biography: detail.description ?? null,
        blurHash: null,
        dominantColor: null,
        duration: 0,
        genres: [],
        id: ytmArtistIdOf(detail.browseId),
        imageId: null,
        imageUrl: detail.thumbnailUrl ?? null,
        lastPlayedAt: null,
        mbz: null,
        missing: null,
        name: detail.name,
        playCount: 0,
        ratedAt: null,
        similarArtists: detail.relatedArtists.map((a) => ytmRelatedArtistRef(a)),
        songCount: detail.topSongs.length,
        starredAt: null,
        thumbHash: null,
        userFavorite: false,
        userRating: null,
    };
}

// ---------------------------------------------------------------------------
// Album / artist
// ---------------------------------------------------------------------------

export function ytmArtistIdOf(browseId: string): string {
    return `${YTM_ARTIST_ID_PREFIX}${browseId}`;
}

export function ytmArtistToAlbumArtist(ref: YtmArtistRef, serverId: ServerId): AlbumArtist {
    return {
        _itemType: LibraryItem.ALBUM_ARTIST,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumCount: 0,
        biography: null,
        blurHash: null,
        dominantColor: null,
        duration: 0,
        genres: [],
        id: ytmArtistIdOf(ref.browseId),
        imageId: null,
        imageUrl: ref.thumbnailUrl ?? null,
        lastPlayedAt: null,
        mbz: null,
        missing: null,
        name: ref.name,
        playCount: 0,
        ratedAt: null,
        similarArtists: null,
        songCount: 0,
        starredAt: null,
        thumbHash: null,
        userFavorite: false,
        userRating: null,
    };
}

/** videoId → the ytm: song id. */
export function ytmSongIdOf(videoId: string): string {
    return `${YTM_SONG_ID_PREFIX}${videoId}`;
}

export function ytmToAlbum(ref: YtmAlbumRef, serverId: ServerId): Album {
    return {
        _itemType: LibraryItem.ALBUM,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumArtistName: ref.artist,
        albumArtists: [ytmRelatedArtist(ref.artist)],
        artists: [ytmRelatedArtist(ref.artist)],
        blurHash: null,
        comment: null,
        // Remote-only releases carry no replica arrival timestamp — the
        // honest empty string (createdAt/updatedAt are non-nullable).
        createdAt: '',
        discs: null,
        dominantColor: null,
        duration: null,
        explicitStatus: null,
        gain: null,
        genres: [],
        id: ytmAlbumIdOf(ref.browseId),
        imageId: null,
        imageUrl: ref.thumbnailUrl ?? null,
        isCompilation: null,
        lastPlayedAt: null,
        mbzId: null,
        mbzReleaseGroupId: null,
        missing: null,
        name: ref.title,
        originalDate: null,
        originalYear: 0,
        participants: null,
        peak: null,
        playCount: 0,
        ratedAt: null,
        recordLabels: [],
        releaseDate: null,
        releaseType: null,
        releaseTypes: [ref.kind],
        releaseYear: ref.year ?? null,
        size: null,
        songCount: ref.trackCount ?? 0,
        songs: undefined,
        sortName: ref.title,
        starredAt: null,
        tags: null,
        thumbHash: null,
        trackYearRange: null,
        updatedAt: '',
        userFavorite: false,
        userRating: null,
        version: null,
    };
}

/**
 * Map one YTM song to feishin's Song DTO. When the snapshot knows the
 * track (by videoId, else by title+artist sig) the SNAPSHOT row wins —
 * its id keeps the likes/play-stats joins — otherwise the ytm: id form.
 */
export function ytmToSong(
    ref: YtmSongRef,
    snapshot: LibrarySnapshot,
    serverId: ServerId,
    indexes?: { bySig?: Map<string, SongRow>; byVideoId?: Map<string, SongRow> },
): Song {
    const byVideoId = indexes?.byVideoId ?? songByVideoId(snapshot);
    const bySig = indexes?.bySig ?? songBySig(snapshot);
    const known = byVideoId.get(ref.videoId) ?? bySig.get(songSig(ref.title, ref.artist));
    if (known !== undefined) {
        if (!extractVideoId(known.streamUrl) && extractVideoId(ref.videoId)) {
            known.streamUrl = ref.videoId;
        }
        return normalizeSong(known, snapshot, serverId);
    }
    const artist = ref.artist.trim() === '' ? 'Unknown Artist' : ref.artist;
    return {
        _itemType: LibraryItem.SONG,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        album: ref.album ?? null,
        albumArtistName: artist,
        albumArtists: [ytmRelatedArtist(artist)],
        albumId: albumIdOf(ref.album ?? null, artist),
        artistName: artist,
        artists: [ytmRelatedArtist(artist)],
        bitDepth: null,
        bitRate: 0,
        blurHash: null,
        bpm: null,
        channels: null,
        codec: null,
        comment: null,
        compilation: null,
        container: null,
        createdAt: snapshot.loadedAt,
        date: null,
        discNumber: 0,
        discSubtitle: null,
        duration:
            ref.durationSec != null && ref.durationSec > 0 ? Math.round(ref.durationSec * 1000) : 0,
        explicitStatus: null,
        folderId: null,
        gain: null,
        genres: [],
        id: ytmSongIdOf(ref.videoId),
        imageId: null,
        imageUrl: ref.thumbnailUrl ?? null,
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
        name: ref.title,
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
        sortName: ref.title,
        tags: null,
        thumbHash: null,
        trackNumber: 0,
        trackSubtitle: null,
        updatedAt: snapshot.loadedAt,
        userFavorite: false,
        userRating: null,
        year: null,
    };
}

function ytmRelatedArtist(name: string): RelatedArtist {
    return {
        id: albumArtistIdOf(name),
        imageId: null,
        imageUrl: null,
        name,
        userFavorite: false,
        userRating: null,
    };
}

function ytmRelatedArtistRef(a: YtmArtistRef): RelatedArtist {
    return {
        id: ytmArtistIdOf(a.browseId),
        imageId: null,
        imageUrl: a.thumbnailUrl ?? null,
        name: a.name,
        userFavorite: false,
        userRating: null,
    };
}
