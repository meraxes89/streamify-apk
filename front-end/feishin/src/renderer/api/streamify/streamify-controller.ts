import { coverCacheKey, coverUrlFor } from './covers';
import { albumRadio, artistRadio, randomSongs, similarSongs, topSongs } from './derive';
import {
    currentStreamifyLibrary,
    getStreamifyLibrary,
    invalidateStreamifyLibraryMemo,
} from './engine';
import {
    docToPlaylistRow,
    orderedElements,
    type PlaylistCrdtDoc,
    type PlaylistRowView,
} from './jam/playlist-crdt';
import {
    normalizeAlbum,
    normalizeAlbumArtist,
    normalizePlaylist,
    normalizeSong,
} from './normalize';
import { resolveStreamifyTrack, StreamifyNoVideoError } from './playback/stream-source';
import {
    addTracksToStreamifyPlaylist,
    createStreamifyPlaylist,
    deleteStreamifyPlaylist,
    ensurePlaylistsSynced,
    moveStreamifyPlaylistItem,
    removeTracksFromStreamifyPlaylist,
    replaceStreamifyPlaylistSongs,
    updateStreamifyPlaylistMeta,
} from './playlist-sync/sync';
import {
    filterAlbums,
    filterArtists,
    filterPlaylists,
    filterSongs,
    paginate,
    searchSnapshot,
    sortAlbums,
    sortArtists,
    sortPlaylists,
    sortSongs,
} from './query';
import { StreamifyNotImplementedError } from './streamify-error';
import { streamifyRequest } from './streamify-request';
import { getStreamifySupabase, signOutStreamify } from './streamify-supabase';

import { logger } from '/@/renderer/utils/logger';
import { InternalControllerEndpoint } from '/@/shared/types/domain-types';

/** Version reported by getServerInfo; bumped as endpoints become real. */
const STREAMIFY_CONTROLLER_VERSION = '0.1.0';

/**
 * The Streamify controller (P1: library reads).
 *
 * SPLIT WITH ENGINEER A: this file is shared - A owns the auth seam
 * (authenticate, the server entry, header injection); the read methods
 * below are P1's. The session contract both ends build against lives in
 * ./session.ts (credential payload codec + the settings.js dev fallback).
 *
 * Every read resolves against the in-memory library snapshot loaded by
 * ./engine.ts (the bridge): local-first, no per-call network. Endpoints
 * beyond the P1 read surface keep their StreamifyNotImplementedError
 * stubs - the UI's per-server-type gating hides those affordances until
 * their phase lands (P2 playback, P3 playlists/favorites mutations).
 */

/** The default page size when a caller omits the limit (feishin always sends one for lists). */
const DEFAULT_LIMIT = 100;

/** Live (non-deleted) CRDT docs as the P1B query-engine row shape. */
function livePlaylistRows(docs: Map<string, PlaylistCrdtDoc>): PlaylistRowView[] {
    const rows: PlaylistRowView[] = [];
    for (const doc of docs.values()) {
        if (doc.deletedAtOpId !== null) {
            continue;
        }
        rows.push(docToPlaylistRow(doc));
    }
    return rows;
}

function toNumberOr(value: null | number | undefined, fallback: number): number {
    return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export const StreamifyController: InternalControllerEndpoint = {
    addToPlaylist: async (args) => {
        // P3A: the Jam CRDT lane - Add ops at tail fracs, applied locally
        // first, materialized by the scheduled sync pass.
        const { apiClientProps, body, query } = args;
        await addTracksToStreamifyPlaylist(
            apiClientProps.server,
            query.id,
            body.songId,
            apiClientProps.serverId,
        );
        // New tracks must enter the snapshot's song index for the join in
        // getPlaylistSongList; the reload is lazy.
        invalidateStreamifyLibraryMemo();
        return null;
    },
    authenticate: async () => {
        // Streamify sessions come from Supabase Google OAuth on the
        // /streamify-auth page, never from a username/password server form.
        // Point stock add-server attempts at the real entry point instead of
        // pretending to authenticate.
        throw new Error(
            'Streamify sign-in happens on the /streamify-auth page (Google), not through the server form',
        );
    },
    createFavorite: async () => {
        throw new StreamifyNotImplementedError('createFavorite');
    },
    createInternetRadioStation: async () => {
        throw new StreamifyNotImplementedError('createInternetRadioStation');
    },
    createPlaylist: async (args) => {
        // P3A: mint the CRDT doc (metadata LWW register at mint time);
        // ownerId / queryBuilderRules / sync have no cloud-schema meaning
        // (no smart-playlist engine, no sync target) and are ignored -
        // documented in decisions/p3/P3A-jam-playlists.md.
        const { apiClientProps, body } = args;
        return createStreamifyPlaylist(apiClientProps.server, {
            description: body.comment ?? null,
            isPublic: body.public ?? true,
            name: body.name,
            serverId: apiClientProps.serverId,
        });
    },
    deleteArtistImage: async () => {
        throw new StreamifyNotImplementedError('deleteArtistImage');
    },
    deleteFavorite: async () => {
        throw new StreamifyNotImplementedError('deleteFavorite');
    },
    deleteInternetRadioStation: async () => {
        throw new StreamifyNotImplementedError('deleteInternetRadioStation');
    },
    deleteInternetRadioStationImage: async () => {
        throw new StreamifyNotImplementedError('deleteInternetRadioStationImage');
    },
    deletePlaylist: async (args) => {
        // P3A: the absorbing delete tombstone. The durable row delete (and
        // its cascade) lands with the sync pass; every other replica
        // infers the delete from the row's absence.
        const { apiClientProps, query } = args;
        await deleteStreamifyPlaylist(apiClientProps.server, query.id, apiClientProps.serverId);
        return null;
    },
    deletePlaylistImage: async () => {
        // Optional endpoint. No storage bucket exists in the cloud schema
        // (covers are track-derived urls); the honest surface stays a stub.
        throw new StreamifyNotImplementedError('deletePlaylistImage');
    },
    getAlbumArtistDetail: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.artistById.get(query.id);
        if (row === undefined) {
            throw new Error(`Album artist not found: ${query.id}`);
        }
        return normalizeAlbumArtist(row, apiClientProps.serverId);
    },
    getAlbumArtistInfo: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.artistById.get(query.id);
        if (row === undefined) {
            return null;
        }
        // No biography / similar-artist data exists in the cloud library
        // yet: honest empties (recorded in decisions/p1/data-mapping.md).
        return {
            biography: null,
            imageUrl: row.coverUrl,
            similarArtists: [],
        };
    },
    getAlbumArtistList: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const filtered = filterArtists(snapshot.artists, {
            favorite: query.favorite,
            searchTerm: query.searchTerm,
        });
        const sorted = sortArtists(filtered, query.sortBy, query.sortOrder, snapshot);
        const page = paginate(sorted, query.startIndex, query.limit);
        return {
            items: page.items.map((row) => normalizeAlbumArtist(row, apiClientProps.serverId)),
            startIndex: page.startIndex,
            totalRecordCount: page.totalRecordCount,
        };
    },
    getAlbumArtistListCount: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        return filterArtists(snapshot.artists, {
            favorite: query.favorite,
            searchTerm: query.searchTerm,
        }).length;
    },
    getAlbumDetail: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.albumById.get(query.id);
        if (row === undefined) {
            throw new Error(`Album not found: ${query.id}`);
        }
        const songs = row.songIds
            .map((id) => snapshot.songById.get(id))
            .filter((song) => song !== undefined)
            .map((song) => normalizeSong(song, snapshot, apiClientProps.serverId));
        return {
            ...normalizeAlbum(row, snapshot, apiClientProps.serverId),
            songs,
        };
    },
    getAlbumInfo: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.albumById.get(query.id);
        // No album notes exist in the cloud library: honest empties.
        return {
            imageUrl: row?.coverUrl ?? null,
            notes: null,
        };
    },
    getAlbumList: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const filtered = filterAlbums(snapshot.albums, {
            artistIds: query.artistIds,
            compilation: query.compilation,
            favorite: query.favorite,
            genreIds: query.genreIds,
            maxYear: query.maxYear,
            minYear: query.minYear,
            searchTerm: query.searchTerm,
        });
        const sorted = sortAlbums(filtered, query.sortBy, query.sortOrder, snapshot);
        const page = paginate(sorted, query.startIndex, query.limit);
        return {
            items: page.items.map((row) => normalizeAlbum(row, snapshot, apiClientProps.serverId)),
            startIndex: page.startIndex,
            totalRecordCount: page.totalRecordCount,
        };
    },
    getAlbumListCount: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        return filterAlbums(snapshot.albums, {
            artistIds: query.artistIds,
            compilation: query.compilation,
            favorite: query.favorite,
            genreIds: query.genreIds,
            maxYear: query.maxYear,
            minYear: query.minYear,
            searchTerm: query.searchTerm,
        }).length;
    },
    getAlbumRadio: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const count = toNumberOr(query.count, 20);
        return albumRadio(snapshot, query.albumId, count).map((row) =>
            normalizeSong(row, snapshot, apiClientProps.serverId),
        );
    },
    getArtistList: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        // One artist string per track: the artist list IS the album artist
        // derivation (no separate track-artist roles exist in the data).
        const filtered = filterArtists(snapshot.artists, {
            favorite: query.favorite,
            searchTerm: query.searchTerm,
        });
        const sorted = sortArtists(filtered, query.sortBy, query.sortOrder, snapshot);
        const page = paginate(sorted, query.startIndex, query.limit);
        return {
            items: page.items.map((row) => normalizeAlbumArtist(row, apiClientProps.serverId)),
            startIndex: page.startIndex,
            totalRecordCount: page.totalRecordCount,
        };
    },
    getArtistListCount: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        return filterArtists(snapshot.artists, {
            favorite: query.favorite,
            searchTerm: query.searchTerm,
        }).length;
    },
    getArtistRadio: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const count = toNumberOr(query.count, 20);
        return artistRadio(snapshot, query.artistId, count).map((row) =>
            normalizeSong(row, snapshot, apiClientProps.serverId),
        );
    },
    getDownloadUrl: () => {
        // Sync-typed URL builder — empty string until P2 implements downloads.
        return '';
    },
    getFavoriteSongs: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        if (query.type === 'rating') {
            // No rating data exists: honest empty (decisions/p1).
            return { items: [], startIndex: 0, totalRecordCount: 0 };
        }
        const liked = snapshot.likedIds
            .map((id) => snapshot.songById.get(id))
            .filter((row) => row !== undefined);
        const artistKey =
            query.artistId !== undefined
                ? snapshot.artistById.get(query.artistId)?.key.toLowerCase()
                : undefined;
        const filtered =
            artistKey !== undefined
                ? liked.filter((row) => row.artist.trim().toLowerCase() === artistKey)
                : liked;
        const limit = toNumberOr(query.limit, DEFAULT_LIMIT);
        return {
            items: filtered
                .slice(0, limit)
                .map((row) => normalizeSong(row, snapshot, apiClientProps.serverId)),
            startIndex: 0,
            totalRecordCount: filtered.length,
        };
    },
    getFolder: async () => {
        throw new StreamifyNotImplementedError('getFolder');
    },
    getGenreList: async (args) => {
        const { apiClientProps, query } = args;
        await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        // The cloud schema carries no genre column yet: the honest genre
        // list is empty. When genre data lands, this fills in with zero
        // controller changes (normalizeGenre is ready).
        return { items: [], startIndex: query.startIndex, totalRecordCount: 0 };
    },
    getImageRequest: ({ query }) => {
        const url = coverUrlFor(currentStreamifyLibrary(), query.itemType, query.id);
        if (url === null) {
            return null;
        }
        return {
            cacheKey: coverCacheKey(query.itemType, query.id, query.size),
            url,
        };
    },
    getImageUrl: ({ query }) => {
        return coverUrlFor(currentStreamifyLibrary(), query.itemType, query.id);
    },
    getInternetRadioStations: async () => {
        // No internet radio data exists in the cloud library: honest empty.
        return [];
    },
    getLyrics: async () => {
        throw new StreamifyNotImplementedError('getLyrics');
    },
    getMusicFolderList: async () => {
        // The cloud library is a single flat collection: no folders exist.
        // The honest list is empty; folder-scoped affordances stay hidden.
        return { items: [], startIndex: 0, totalRecordCount: 0 };
    },
    getPlaylistDetail: async (args) => {
        const { apiClientProps, query } = args;
        const docs = await ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal);
        const doc = docs.get(query.id);
        if (doc === undefined || doc.deletedAtOpId !== null) {
            throw new Error(`Playlist not found: ${query.id}`);
        }
        return normalizePlaylist(docToPlaylistRow(doc), apiClientProps.serverId);
    },
    getPlaylistList: async (args) => {
        const { apiClientProps, query } = args;
        const docs = await ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal);
        const filtered = filterPlaylists(livePlaylistRows(docs), query.searchTerm);
        const sorted = sortPlaylists(filtered, query.sortBy, query.sortOrder);
        const page = paginate(sorted, query.startIndex, query.limit);
        return {
            items: page.items.map((row) => normalizePlaylist(row, apiClientProps.serverId)),
            startIndex: page.startIndex,
            totalRecordCount: page.totalRecordCount,
        };
    },
    getPlaylistListCount: async (args) => {
        const { apiClientProps, query } = args;
        const docs = await ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal);
        return filterPlaylists(livePlaylistRows(docs), query.searchTerm).length;
    },
    getPlaylistSongIds: async (args) => {
        const { apiClientProps, query } = args;
        const docs = await ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal);
        const doc = docs.get(query.id);
        if (doc === undefined || doc.deletedAtOpId !== null) {
            throw new Error(`Playlist not found: ${query.id}`);
        }
        const songIds = docToPlaylistRow(doc).songIds;
        return { items: songIds, startIndex: 0, totalRecordCount: songIds.length };
    },
    getPlaylistSongList: async (args) => {
        const { apiClientProps, query } = args;
        const [docs, snapshot] = await Promise.all([
            ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal),
            getStreamifyLibrary(apiClientProps.server, apiClientProps.signal),
        ]);
        const doc = docs.get(query.id);
        if (doc === undefined || doc.deletedAtOpId !== null) {
            throw new Error(`Playlist not found: ${query.id}`);
        }
        // The converged CRDT order, joined against the snapshot for track
        // metadata; playlistItemId carries the element's add-op id (the
        // per-item removal lane removeFromPlaylist accepts).
        const songs = orderedElements(doc)
            .map((entry) => {
                const song = snapshot.songById.get(entry.element.trackId);
                if (song === undefined) {
                    return undefined;
                }
                const normalized = normalizeSong(song, snapshot, apiClientProps.serverId);
                normalized.playlistItemId = entry.element.addOpId.toString(10);
                return normalized;
            })
            .filter((song) => song !== undefined);
        return { items: songs, startIndex: 0, totalRecordCount: songs.length };
    },
    getPlayQueue: async () => {
        throw new StreamifyNotImplementedError('getPlayQueue');
    },
    getRandomSongList: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const rows = randomSongs(snapshot, {
            genre: query.genre,
            limit: query.limit,
            maxYear: query.maxYear,
            minYear: query.minYear,
            played: query.played,
        });
        return {
            items: rows.map((row) => normalizeSong(row, snapshot, apiClientProps.serverId)),
            startIndex: 0,
            totalRecordCount: rows.length,
        };
    },
    getRoles: async () => {
        // One artist string per track: no role model exists.
        return [];
    },
    getScanStatus: async (args) => {
        const snapshot = await getStreamifyLibrary(
            args.apiClientProps.server,
            args.apiClientProps.signal,
        );
        // The cloud library syncs via the snapshot load: "scanning" is the
        // load itself, so the honest status is a completed scan of the
        // loaded songs.
        return {
            count: snapshot.songs.length,
            folderCount: 0,
            lastScan: snapshot.loadedAt,
            scanning: false,
        };
    },
    getServerInfo: async (args) => {
        // Real request through the Streamify request lane: GoTrue's
        // /auth/v1/user validates the access token (and exercises the
        // 401 -> refresh -> retry -> clean-sign-out lane) on every boot.
        await streamifyRequest('/auth/v1/user', { method: 'GET' });

        // The honest capability surface for P1: browse-only, no optional
        // server features (the UI gates tag/role/scan affordances on these).
        return {
            features: {},
            id: args.apiClientProps.serverId,
            version: STREAMIFY_CONTROLLER_VERSION,
        };
    },
    getSimilarSongs: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const count = toNumberOr(query.count, 20);
        return similarSongs(snapshot, query.songId, count).map((row) =>
            normalizeSong(row, snapshot, apiClientProps.serverId),
        );
    },
    getSongDetail: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.songById.get(query.id);
        if (row === undefined) {
            throw new Error(`Song not found: ${query.id}`);
        }
        return normalizeSong(row, snapshot, apiClientProps.serverId);
    },
    getSongList: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const filtered = filterSongs(
            snapshot.songs,
            {
                albumArtistIds: query.albumArtistIds,
                albumIds: query.albumIds,
                artistIds: query.artistIds,
                favorite: query.favorite,
                genreIds: query.genreIds,
                maxYear: query.maxYear,
                minYear: query.minYear,
                searchTerm: query.searchTerm,
            },
            snapshot,
        );
        const sorted = sortSongs(filtered, query.sortBy, query.sortOrder, snapshot);
        const page = paginate(sorted, query.startIndex, query.limit);
        return {
            items: page.items.map((row) => normalizeSong(row, snapshot, apiClientProps.serverId)),
            startIndex: page.startIndex,
            totalRecordCount: page.totalRecordCount,
        };
    },
    getSongListCount: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        return filterSongs(
            snapshot.songs,
            {
                albumArtistIds: query.albumArtistIds,
                albumIds: query.albumIds,
                artistIds: query.artistIds,
                favorite: query.favorite,
                genreIds: query.genreIds,
                maxYear: query.maxYear,
                minYear: query.minYear,
                searchTerm: query.searchTerm,
            },
            snapshot,
        ).length;
    },
    getStreamUrl: async (args) => {
        // P2-A: the playback spine. feishin song id → the snapshot row → the
        // stream videoId (the tracks table's stream_url IS the videoId — see
        // the web app's libraryStore mapping) → the resolver's entry point.
        // Transcode settings are Navidrome/Jellyfin semantics — our source
        // is fixed by the codec ladder; they are read but not honored.
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.songById.get(query.id);
        const videoId = row?.streamUrl ?? '';
        if (videoId === '' || videoId === null) {
            // Honest empty: the ingestion has not mapped a stream video for
            // this track. Surfaced as an error state (react-query) — never
            // fake data.
            throw new StreamifyNoVideoError(query.id);
        }
        // Throws StreamifySabrError on SABR-only resolves (react-player
        // cannot parse SABR — the daily corpus instrumentation counts it).
        const resolution = await resolveStreamifyTrack(videoId);
        return resolution.url;
    },
    getStructuredLyrics: async () => {
        throw new StreamifyNotImplementedError('getStructuredLyrics');
    },
    getTagList: async () => {
        throw new StreamifyNotImplementedError('getTagList');
    },
    getTopSongs: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const limit = toNumberOr(query.limit, 50);
        const rows = topSongs(snapshot, query.artistId, query.artist, limit);
        return {
            items: rows.map((row) => normalizeSong(row, snapshot, apiClientProps.serverId)),
            startIndex: 0,
            totalRecordCount: rows.length,
        };
    },
    getUserInfo: async () => {
        const supabase = getStreamifySupabase();

        if (!supabase) {
            throw new Error(
                'Streamify is not configured for this deployment (missing STREAMIFY_SUPABASE_URL / STREAMIFY_SUPABASE_ANON_KEY in settings.js)',
            );
        }

        const { data, error } = await supabase.auth.getUser();

        if (error || !data.user) {
            // A clear auth rejection (revoked/expired session) triggers the
            // clean sign-out bounce; transient network failures are rethrown
            // untouched so the stock auth hook can route them to the
            // no-network page instead of signing the user out.
            const message = error?.message ?? '';
            const status = (error as undefined | { status?: number })?.status;
            const isAuthRejection =
                status === 401 ||
                status === 403 ||
                /invalid.*jwt|jwt.*invalid|session.*(missing|expired)|refresh_token_not_found|bad_jwt/i.test(
                    message,
                );

            if (isAuthRejection) {
                logger.warn('Streamify session rejected by getUserInfo — signing out', {
                    action: 'streamify_getuserinfo_rejected',
                    reason: message,
                });

                await signOutStreamify();
            }

            throw new Error('Your Streamify session is no longer active — please sign in again');
        }

        const name =
            (data.user.user_metadata?.full_name as string | undefined) ||
            data.user.email ||
            data.user.id;

        return {
            id: data.user.id,
            isAdmin: false,
            name,
        };
    },
    getUserList: async () => {
        throw new StreamifyNotImplementedError('getUserList');
    },
    jukeboxControl: async () => {
        throw new StreamifyNotImplementedError('jukeboxControl');
    },
    movePlaylistItem: async (args) => {
        // P3A: one Reorder op at the ending-index midpoint (the jam insert
        // helper); convergence by the order-register LWW.
        const { apiClientProps, query } = args;
        await moveStreamifyPlaylistItem(
            apiClientProps.server,
            query.playlistId,
            {
                endingIndex: query.endingIndex,
                startingIndex: query.startingIndex,
                trackId: query.trackId,
            },
            apiClientProps.serverId,
        );
    },
    refreshItems: async () => {
        // Rows live in the in-memory snapshot; react-query refetches run
        // the list endpoints again. Nothing to refresh remotely.
        return null;
    },
    removeFromPlaylist: async (args) => {
        // P3A: songIds resolve either as feishin song ids (every element
        // holding that track) or as playlistItemIds (the element add-op
        // id - the per-item lane the playlist detail screen feeds).
        const { apiClientProps, query } = args;
        await removeTracksFromStreamifyPlaylist(
            apiClientProps.server,
            query.id,
            query.songId,
            apiClientProps.serverId,
        );
        return null;
    },
    replacePlaylist: async (args) => {
        // P3A: converge the ordered view onto the given song list (the
        // reorder-column persistence path): stable element matching,
        // surplus Removes, missing Adds, one Reorder per moved element.
        const { apiClientProps, body, query } = args;
        await replaceStreamifyPlaylistSongs(
            apiClientProps.server,
            query.id,
            body.songId,
            apiClientProps.serverId,
        );
        invalidateStreamifyLibraryMemo();
        return null;
    },
    savePlayQueue: async () => {
        throw new StreamifyNotImplementedError('savePlayQueue');
    },
    scrobble: async () => {
        throw new StreamifyNotImplementedError('scrobble');
    },
    search: async (args) => {
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const { albumArtistRows, albumRows, songRows } = searchSnapshot(
            snapshot,
            query.query ?? '',
            {
                albumArtistLimit: query.albumArtistLimit,
                albumArtistStartIndex: query.albumArtistStartIndex,
                albumLimit: query.albumLimit,
                albumStartIndex: query.albumStartIndex,
                songLimit: query.songLimit,
                songStartIndex: query.songStartIndex,
            },
        );
        return {
            albumArtists: albumArtistRows.map((row) =>
                normalizeAlbumArtist(row, apiClientProps.serverId),
            ),
            albums: albumRows.map((row) => normalizeAlbum(row, snapshot, apiClientProps.serverId)),
            songs: songRows.map((row) => normalizeSong(row, snapshot, apiClientProps.serverId)),
        };
    },
    setPlaylistSongs: async (args) => {
        // P3A: setPlaylistSongs is replacePlaylist under the Navidrome
        // verb (the reorder-column mutation hook calls this one).
        const { apiClientProps, body } = args;
        await replaceStreamifyPlaylistSongs(
            apiClientProps.server,
            body.id,
            body.songIds,
            apiClientProps.serverId,
        );
        invalidateStreamifyLibraryMemo();
        return null;
    },
    setRating: async () => {
        throw new StreamifyNotImplementedError('setRating');
    },
    shareItem: async () => {
        throw new StreamifyNotImplementedError('shareItem');
    },
    startLibraryScan: async () => {
        // The cloud library has no server-side scan; the snapshot load is
        // the refresh. Honest no-op.
        return null;
    },
    updateInternetRadioStation: async () => {
        throw new StreamifyNotImplementedError('updateInternetRadioStation');
    },
    updatePlaylist: async (args) => {
        // P3A: the metadata LWW register write (full snapshot per write;
        // concurrent renames resolve by hybrid clock on every replica).
        const { apiClientProps, body, query } = args;
        const docs = await ensurePlaylistsSynced(apiClientProps.server, apiClientProps.signal);
        const doc = docs.get(query.id);
        if (doc === undefined || doc.deletedAtOpId !== null) {
            throw new Error(`Playlist not found: ${query.id}`);
        }
        await updateStreamifyPlaylistMeta(
            apiClientProps.server,
            query.id,
            {
                coverUrl: doc.meta.coverUrl,
                description: body.comment ?? doc.meta.description,
                isPublic: body.public ?? doc.meta.isPublic,
                name: body.name,
            },
            apiClientProps.serverId,
        );
        return null;
    },
    uploadArtistImage: async () => {
        throw new StreamifyNotImplementedError('uploadArtistImage');
    },
    uploadInternetRadioStationImage: async () => {
        throw new StreamifyNotImplementedError('uploadInternetRadioStationImage');
    },
    uploadPlaylistImage: async () => {
        throw new StreamifyNotImplementedError('uploadPlaylistImage');
    },
};
