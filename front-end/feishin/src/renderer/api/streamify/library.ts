/**
 * streamify/library.ts - the in-memory library snapshot every P1 read
 * resolves against (local-first: the controller NEVER hits the network per
 * call - the snapshot loads once per session, then pagination, sorting,
 * filtering and search are pure in-memory operations).
 *
 * Sources (the existing cloud lanes only - no new Supabase queries):
 *   - likes (user_likes joined against tracks, like order = recency)
 *   - playlist membership (playlist_tracks + tracks by id)
 *   - playlists (public.playlists)
 *   - play events (user_play_events - the top/similar/playCount data)
 *
 * Derivations (the "jam document structure" side of the mapping):
 *   - albums: grouped by (album name, album artist)
 *   - artists: grouped by artist string
 *   - genres: the cloud schema carries no genre column today, so the genre
 *     index is honestly EMPTY (documented in decisions/p1/data-mapping.md;
 *     when the data lands, this index fills in with zero screen changes)
 *
 * Recency model: the cloud rows carry no per-track timestamps. "Recently
 * added" = the like/membership arrival order the load lanes preserve
 * (likes arrive most-recent-first). createdAt/updatedAt on entities are
 * the snapshot's load time - the replica's local arrival time, which is
 * exactly what they claim to be.
 */

import {
    CloudPlaylistRow,
    fetchAllPlaylistTracks,
    fetchItemFavorites,
    fetchLikes,
    fetchPlayEvents,
    fetchPlaylists,
    fetchTracksByIds,
    PlayEventRow,
    PlaylistTrackRow,
} from './cloud';
import {
    albumArtistIdOf,
    albumDisplayNameOf,
    albumIdOf,
    albumKeyOf,
    artistDisplayNameOf,
    artistKeyOf,
    UNKNOWN_ALBUM,
    UNKNOWN_ARTIST,
} from './ids';
import { StreamifySession } from './session';

export interface AlbumRow {
    addedRank: number;
    artistName: string;
    coverUrl: null | string;
    durationMs: number;
    id: string;
    key: string;
    lastPlayedAt: null | string;
    name: string;
    playCount: number;
    songIds: string[];
}

export interface ArtistRow {
    addedRank: number;
    albumIds: string[];
    coverUrl: null | string;
    durationMs: number;
    id: string;
    key: string;
    lastPlayedAt: null | string;
    name: string;
    playCount: number;
    songIds: string[];
}

/** The loaded library - everything P1 reads run against this. */
export interface LibrarySnapshot {
    albumById: Map<string, AlbumRow>;
    albums: AlbumRow[];
    artistById: Map<string, ArtistRow>;
    artists: ArtistRow[];
    /** W1-B: favorited album ids (the user_item_favorites ledger). */
    likedAlbumIds: Set<string>;
    /** W1-B: favorited artist ids (the user_item_favorites ledger). */
    likedArtistIds: Set<string>;
    /** Liked song ids, most recent first. */
    likedIds: string[];
    /** ISO timestamp of the load (the replica arrival time). */
    loadedAt: string;
    /** The play events, most recent first (the derivation input). */
    playEvents: PlayEventRow[];
    playlistById: Map<string, PlaylistRow>;
    playlists: PlaylistRow[];
    /** The session key the snapshot was loaded under (memo identity). */
    sessionKey: string;
    songById: Map<string, SongRow>;
    songs: SongRow[];
}

export interface PlaylistRow {
    coverUrl: null | string;
    createdAt: string;
    description: null | string;
    id: string;
    name: string;
    songIds: string[];
    updatedAt: string;
}

/** One library song - the internal row normalize.ts shapes for feishin. */
export interface SongRow {
    /** Arrival rank (0 = most recent); MAX_RANK = unknown. */
    addedRank: number;
    album: null | string;
    artist: string;
    coverUrl: null | string;
    durationSec: null | number;
    id: string;
    lastPlayedAt: null | string;
    liked: boolean;
    playCount: number;
    streamUrl: null | string;
    title: string;
}

const MAX_RANK = Number.MAX_SAFE_INTEGER;

export async function loadLibrarySnapshot(
    session: StreamifySession,
    sessionKey: string,
    signal?: AbortSignal,
): Promise<LibrarySnapshot> {
    // Step 1: the playlist shells (membership needs their ids).
    const playlistRows = await fetchPlaylists(session, signal);

    // Step 2: the three independent lanes in parallel.
    const [likes, membershipRows, playEvents, itemFavorites] = await Promise.all([
        fetchLikes(session, signal),
        fetchAllPlaylistTracks(
            session,
            playlistRows.map((playlist) => playlist.id),
            signal,
        ),
        fetchPlayEvents(session, 1000, signal),
        fetchItemFavorites(session, signal),
    ]);

    // Step 3: resolve membership track rows (tracks by id - the existing lane).
    const membershipTrackIds = Array.from(new Set(membershipRows.map((row) => row.track_id)));
    const membershipTracks =
        membershipTrackIds.length > 0
            ? await fetchTracksByIds(session, membershipTrackIds, signal)
            : [];

    const loadedAt = new Date().toISOString();
    const songById = new Map<string, SongRow>();

    // Likes first (arrival order = recency), then membership (append merge).
    let rank = 0;
    for (const track of likes) {
        songById.set(track.id, toSongRow(track, true, rank));
        rank += 1;
    }
    rank = 0;
    for (const track of membershipTracks) {
        if (!songById.has(track.id)) {
            // Membership rows arrive oldest-playlist-first; rank inverted so
            // later insertions read as more recent than earlier ones.
            songById.set(track.id, toSongRow(track, false, MAX_RANK - rank));
            rank += 1;
        }
    }

    // Play stats: the recorded track_sig IS the track id (see ids.ts).
    const playCountById = new Map<string, number>();
    const lastPlayedById = new Map<string, string>();
    for (const event of playEvents) {
        const id = event.track_sig;
        playCountById.set(id, (playCountById.get(id) ?? 0) + 1);
        // The max created_at is the latest play (ISO strings compare
        // lexically); taking the max keeps the join correct regardless of
        // the row order PostgREST returns.
        const current = lastPlayedById.get(id);
        if (current === undefined || event.created_at > current) {
            lastPlayedById.set(id, event.created_at);
        }
    }
    for (const song of songById.values()) {
        song.playCount = playCountById.get(song.id) ?? 0;
        song.lastPlayedAt = lastPlayedById.get(song.id) ?? null;
    }

    // Album + artist derivations (single pass, insertion-ordered maps).
    const albumBuckets = new Map<string, AlbumRow>();
    const artistBuckets = new Map<string, ArtistRow>();
    const albumIdsByArtistKey = new Map<string, Set<string>>();

    for (const song of songById.values()) {
        const albumKey = albumKeyOf(song.album, song.artist);
        let album = albumBuckets.get(albumKey);
        if (album === undefined) {
            album = {
                addedRank: MAX_RANK,
                artistName: artistDisplayNameOf(song.artist),
                coverUrl: song.coverUrl,
                durationMs: 0,
                id: albumIdOf(song.album, song.artist),
                key: albumKey,
                lastPlayedAt: null,
                name: albumDisplayNameOf(song.album),
                playCount: 0,
                songIds: [],
            };
            albumBuckets.set(albumKey, album);
        }
        album.songIds.push(song.id);
        album.durationMs += Math.round((song.durationSec ?? 0) * 1000);
        album.playCount += song.playCount;
        if (album.coverUrl === null && song.coverUrl !== null) {
            album.coverUrl = song.coverUrl;
        }
        album.addedRank = Math.min(album.addedRank, song.addedRank);
        album.lastPlayedAt = latestIso(album.lastPlayedAt, song.lastPlayedAt);

        const artistKey = artistKeyOf(song.artist);
        let artist = artistBuckets.get(artistKey);
        if (artist === undefined) {
            artist = {
                addedRank: MAX_RANK,
                albumIds: [],
                coverUrl: song.coverUrl,
                durationMs: 0,
                id: albumArtistIdOf(song.artist),
                key: artistKey,
                lastPlayedAt: null,
                name: artistDisplayNameOf(song.artist),
                playCount: 0,
                songIds: [],
            };
            artistBuckets.set(artistKey, artist);
            albumIdsByArtistKey.set(artistKey, new Set());
        }
        artist.songIds.push(song.id);
        artist.durationMs += Math.round((song.durationSec ?? 0) * 1000);
        artist.playCount += song.playCount;
        if (artist.coverUrl === null && song.coverUrl !== null) {
            artist.coverUrl = song.coverUrl;
        }
        artist.addedRank = Math.min(artist.addedRank, song.addedRank);
        artist.lastPlayedAt = latestIso(artist.lastPlayedAt, song.lastPlayedAt);
        albumIdsByArtistKey.get(artistKey)?.add(album.id);
    }
    for (const artist of artistBuckets.values()) {
        artist.albumIds = Array.from(albumIdsByArtistKey.get(artist.key) ?? []);
    }

    // Playlists with resolvable song ids (position order preserved).
    const playlistRowsOut = buildPlaylistRows(playlistRows, membershipRows, songById);

    const songs = Array.from(songById.values());
    const albums = Array.from(albumBuckets.values());
    const artists = Array.from(artistBuckets.values());

    return {
        albumById: new Map(albums.map((album) => [album.id, album])),
        albums,
        artistById: new Map(artists.map((artist) => [artist.id, artist])),
        artists,
        likedAlbumIds: new Set(
            itemFavorites.filter((row) => row.item_type === 'album').map((row) => row.item_id),
        ),
        likedArtistIds: new Set(
            itemFavorites.filter((row) => row.item_type === 'artist').map((row) => row.item_id),
        ),
        likedIds: likes.map((track) => track.id),
        loadedAt,
        playEvents,
        playlistById: new Map(playlistRowsOut.map((playlist) => [playlist.id, playlist])),
        playlists: playlistRowsOut,
        sessionKey,
        songById,
        songs,
    };
}

function buildPlaylistRows(
    playlistRows: CloudPlaylistRow[],
    membershipRows: PlaylistTrackRow[],
    songById: Map<string, SongRow>,
): PlaylistRow[] {
    return playlistRows.map((playlist) => ({
        coverUrl: playlist.cover_url,
        createdAt: playlist.created_at,
        description: playlist.description,
        id: playlist.id,
        name: playlist.name,
        songIds: membershipRows
            .filter((row) => row.playlist_id === playlist.id && songById.has(row.track_id))
            .sort((a, b) => a.position - b.position)
            .map((row) => row.track_id),
        updatedAt: playlist.updated_at,
    }));
}

function latestIso(a: null | string, b: null | string): null | string {
    if (a === null) {
        return b;
    }
    if (b === null) {
        return a;
    }
    return a >= b ? a : b;
}

function toSongRow(
    track: {
        album: null | string;
        artist: string;
        cover_url: null | string;
        duration_sec: null | number;
        id: string;
        stream_url: null | string;
        title: string;
    },
    liked: boolean,
    addedRank: number,
): SongRow {
    return {
        addedRank,
        album: track.album,
        artist: track.artist,
        coverUrl: track.cover_url,
        durationSec: track.duration_sec,
        id: track.id,
        lastPlayedAt: null,
        liked,
        playCount: 0,
        streamUrl: track.stream_url,
        title: track.title,
    };
}

/** The "unknown" display names, re-exported for normalize. */
export { UNKNOWN_ALBUM, UNKNOWN_ARTIST };
