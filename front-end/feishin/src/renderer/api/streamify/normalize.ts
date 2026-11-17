/**
 * streamify/normalize.ts - the normalization module (P1 kickoff: "build the
 * mapping as a standalone module: feishin's expected shapes <- jam/library
 * document structure").
 *
 * Every feishin domain type is satisfied field-for-field; fields with no
 * cloud data carry their honest empty value (null / 0 / false) rather than
 * a guess. The full per-field ledger - including which values are derived
 * vs absent - lives in decisions/p1/data-mapping.md.
 */

import { albumArtistIdOf, albumIdOf, genreIdOf } from './ids';
import { AlbumRow, ArtistRow, LibrarySnapshot, PlaylistRow, SongRow } from './library';

import {
    Album,
    AlbumArtist,
    Genre,
    LibraryItem,
    Playlist,
    RelatedArtist,
    ServerType,
    Song,
} from '/@/shared/types/domain-types';

type ServerId = string;

export function normalizeAlbum(
    row: AlbumRow,
    snapshot: LibrarySnapshot,
    serverId: ServerId,
): Album {
    return {
        _itemType: LibraryItem.ALBUM,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumArtistName: row.artistName,
        albumArtists: [relatedArtist(row.artistName)],
        artists: [relatedArtist(row.artistName)],
        blurHash: null,
        comment: null,
        createdAt: snapshot.loadedAt,
        discs: null,
        dominantColor: null,
        duration: row.durationMs,
        explicitStatus: null,
        gain: null,
        genres: [],
        id: row.id,
        imageId: row.id,
        imageUrl: row.coverUrl,
        isCompilation: null,
        lastPlayedAt: row.lastPlayedAt,
        mbzId: null,
        mbzReleaseGroupId: null,
        missing: null,
        name: row.name,
        originalDate: null,
        originalYear: 0,
        participants: null,
        peak: null,
        playCount: row.playCount,
        ratedAt: null,
        recordLabels: [],
        releaseDate: null,
        releaseType: null,
        releaseTypes: [],
        releaseYear: null,
        size: null,
        songCount: row.songIds.length,
        songs: undefined,
        sortName: row.name,
        starredAt: null,
        tags: null,
        thumbHash: null,
        trackYearRange: null,
        updatedAt: snapshot.loadedAt,
        // W1-B: the item-favorites ledger (user_item_favorites).
        userFavorite: snapshot.likedAlbumIds.has(row.id),
        userRating: null,
        version: null,
    };
}

export function normalizeAlbumArtist(
    row: ArtistRow,
    snapshot: LibrarySnapshot,
    serverId: ServerId,
): AlbumArtist {
    return {
        _itemType: LibraryItem.ALBUM_ARTIST,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumCount: row.albumIds.length,
        biography: null,
        blurHash: null,
        dominantColor: null,
        duration: row.durationMs,
        genres: [],
        id: row.id,
        imageId: row.id,
        imageUrl: row.coverUrl,
        lastPlayedAt: row.lastPlayedAt,
        mbz: null,
        missing: null,
        name: row.name,
        playCount: row.playCount,
        ratedAt: null,
        similarArtists: null,
        songCount: row.songIds.length,
        starredAt: null,
        thumbHash: null,
        // W1-B: the item-favorites ledger (user_item_favorites).
        userFavorite: snapshot.likedArtistIds.has(row.id),
        userRating: null,
    };
}

/**
 * Genre entities for names the library actually carries. The cloud schema
 * has no genre column today, so this maps over an empty set in P1 - it
 * exists so the day genre data lands, the list endpoint fills in with zero
 * controller changes.
 */
export function normalizeGenre(
    name: string,
    albumCount: number,
    songCount: number,
    serverId: ServerId,
): Genre {
    return {
        _itemType: LibraryItem.GENRE,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        albumCount,
        id: genreIdOf(name),
        imageId: null,
        imageUrl: null,
        name,
        songCount,
    };
}

export function normalizePlaylist(row: PlaylistRow, serverId: ServerId): Playlist {
    return {
        _itemType: LibraryItem.PLAYLIST,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        blurHash: null,
        description: row.description,
        dominantColor: null,
        duration: null,
        evaluatedAt: null,
        genres: [],
        id: row.id,
        imageId: row.id,
        imageUrl: row.coverUrl,
        name: row.name,
        owner: null,
        ownerId: null,
        public: null,
        rules: null,
        size: row.songIds.length,
        songCount: row.songIds.length,
        sync: null,
        thumbHash: null,
    };
}

export function normalizeSong(row: SongRow, snapshot: LibrarySnapshot, serverId: ServerId): Song {
    return {
        _itemType: LibraryItem.SONG,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        album: row.album,
        albumArtistName: row.artist,
        albumArtists: [relatedArtist(row.artist)],
        albumId: albumIdOf(row.album, row.artist),
        artistName: row.artist,
        artists: [relatedArtist(row.artist)],
        bitDepth: null,
        bitRate: 0,
        blurHash: null,
        bpm: null,
        channels: null,
        codec: null,
        comment: null,
        compilation: null,
        container: null,
        // The replica arrival time (the cloud rows carry no per-track
        // timestamps) - see decisions/p1/data-mapping.md.
        createdAt: snapshot.loadedAt,
        date: null,
        discNumber: 0,
        discSubtitle: null,
        duration: Math.round((row.durationSec ?? 0) * 1000),
        explicitStatus: null,
        folderId: null,
        gain: null,
        genres: [],
        id: row.id,
        imageId: row.id,
        imageUrl: row.coverUrl,
        lastPlayedAt: row.lastPlayedAt,
        libraryId: null,
        libraryName: null,
        lyrics: null,
        mbzAlbumId: null,
        mbzAlbumType: null,
        mbzRecordingId: null,
        mbzReleaseGroupId: null,
        mbzTrackId: null,
        missing: null,
        name: row.title,
        originalDate: null,
        originalYear: null,
        participants: null,
        path: null,
        peak: null,
        playCount: row.playCount,
        playlistItemId: undefined,
        releaseDate: null,
        releaseYear: null,
        sampleRate: null,
        size: 0,
        sortName: row.title,
        tags: null,
        thumbHash: null,
        trackNumber: 0,
        trackSubtitle: null,
        updatedAt: snapshot.loadedAt,
        userFavorite: row.liked,
        userRating: null,
        year: null,
    };
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
