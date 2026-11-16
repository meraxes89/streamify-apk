import { recordPlayEventStrict } from './cloud';
import { coverCacheKey, coverUrlFor } from './covers';
import { albumRadio, artistRadio, randomSongs, similarSongs, topSongs } from './derive';
import {
    currentStreamifyLibrary,
    getStreamifyLibrary,
    invalidateStreamifyLibraryMemo,
} from './engine';
import { getStreamifyFavoriteJournal, mutateFavorite } from './favorites';
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
import {
    createQueuePersistence,
    idbLane,
    localStorageLane,
    type QueuePersistence,
} from './playback/queue-persistence';
import { getStreamifyScrobbleBridge, type ScrobbleReport } from './playback/scrobble-bridge';
import {
    lastRungForVideo,
    resolveStreamifyTrack,
    StreamifyNoVideoError,
} from './playback/stream-source';
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
import { resolveStreamifySessionAsync } from './session';
import { StreamifyNotImplementedError } from './streamify-error';
import { streamifyRequest } from './streamify-request';
import { getStreamifySupabase, signOutStreamify } from './streamify-supabase';
import { recordCountedPlay } from './wrapped/listening-ledger';

import { useStreamifySettingsStoreBase } from '/@/renderer/store/streamify-settings.store';
import { logger } from '/@/renderer/utils/logger';
import { InternalControllerEndpoint, LibraryItem } from '/@/shared/types/domain-types';

/** Version reported by getServerInfo; bumped as endpoints become real. */
const STREAMIFY_CONTROLLER_VERSION = '0.2.0';

/**
 * The Streamify controller (P1 reads + the P3-B bridge).
 *
 * SPLIT WITH ENGINEER A: this file is shared - A owns the auth seam
 * (authenticate, the server entry, header injection) and all *Playlist*
 * endpoints; the read methods are P1's. The session contract both ends
 * build against lives in ./session.ts (credential payload codec + the
 * settings.js dev fallback).
 *
 * Every read resolves against the in-memory library snapshot loaded by
 * ./engine.ts (the bridge): local-first, no per-call network. P3-B adds
 * the #187 playback spine bridge (scrobble, play-queue persistence) and
 * the favorites (likes) mutations - see decisions/p3/P3B-favorites-curation-bridge.md.
 * Endpoints beyond the implemented surface keep their
 * StreamifyNotImplementedError stubs - the UI's per-server-type gating
 * hides those affordances until their phase lands.
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

// ---------------------------------------------------------------------------
// The P3-B bridge wiring (play-queue persistence + the scrobble bridge)
// ---------------------------------------------------------------------------

/** Per-server queue persistence (one lane pair per feishin server id). */
const queuePersistenceByServer = new Map<string, QueuePersistence>();

/** Online + signed-in gate for the cloud sends (offline waits, never drops). */
function canSendCloud(session: null | { accessToken: string }): boolean {
    if (session === null || session.accessToken === '') return false;
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return false;
    return true;
}

/** Song-like library item types share the likes backend (feishin's own grouping). */
function isSongLikeType(type: LibraryItem): boolean {
    return (
        type === LibraryItem.SONG ||
        type === LibraryItem.PLAYLIST_SONG ||
        type === LibraryItem.QUEUE_SONG
    );
}

function queuePersistenceFor(serverId: string): QueuePersistence {
    let persistence = queuePersistenceByServer.get(serverId);
    if (persistence === undefined) {
        persistence = createQueuePersistence({
            local: localStorageLane(serverId),
            // null in node/tests (the API is absent) — the small lane then
            // carries everything up to its quota, degrading honestly past it.
            remote: idbLane(serverId),
        });
        queuePersistenceByServer.set(serverId, persistence);
    }
    return persistence;
}

/** The scrobble bridge singleton, wired to the live snapshot + rungs. */
function scrobbleBridge() {
    return getStreamifyScrobbleBridge({
        getSnapshot: () => currentStreamifyLibrary(),
        onCountedPlay: (fired) => {
            // P4-A (Wrapped): the time-windowed listening ledger rides the
            // same threshold crossing that bumps the tally (never starts,
            // never skips). Throws are contained inside the bridge.
            recordCountedPlay({
                album: fired.album,
                artist: fired.artist,
                durationMs: fired.durationMs,
                playedMs: fired.playedMs,
                rung: fired.rung,
                title: fired.title,
                trackId: fired.trackId,
            });
        },
        resolveRung: lastRungForVideo,
    });
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
    createFavorite: async (args) => {
        // P3-B: a favorite IS a cloud like (user_likes ⋈ tracks). Song-like
        // types share the backend (feishin's own mutation grouping); album /
        // album-artist / artist favorites have no cloud model — the honest
        // no-op (the lists stay honestly empty per decisions/p1).
        const { apiClientProps, query } = args;
        if (!isSongLikeType(query.type)) {
            logger.debug(
                `Streamify createFavorite: no cloud backing for ${query.type} — honest no-op`,
                { action: 'streamify_favorite_noop', type: query.type },
            );
            return null;
        }
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const session = await resolveStreamifySessionAsync(apiClientProps.server?.credential);
        await mutateFavorite(session, snapshot, query.id, true, getStreamifyFavoriteJournal());
        return null;
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
    deleteFavorite: async (args) => {
        // The unlike: remove the user_likes edge (the shared catalog row
        // stays). Offline failures journal — see favorites.ts.
        const { apiClientProps, query } = args;
        if (!isSongLikeType(query.type)) {
            logger.debug(
                `Streamify deleteFavorite: no cloud backing for ${query.type} — honest no-op`,
                { action: 'streamify_favorite_noop', type: query.type },
            );
            return null;
        }
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const session = await resolveStreamifySessionAsync(apiClientProps.server?.credential);
        await mutateFavorite(session, snapshot, query.id, false, getStreamifyFavoriteJournal());
        return null;
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
        // Opportunistic journal drain (offline likes ride out with the read).
        const session = await resolveStreamifySessionAsync(apiClientProps.server?.credential);
        if (session !== null) {
            try {
                await getStreamifyFavoriteJournal().drain(session);
            } catch {
                /* offline / auth — the journal survives either way */
            }
        }
        if (query.type === 'rating') {
            // No rating data exists: honest empty (decisions/p1).
            return { items: [], startIndex: 0, totalRecordCount: 0 };
        }
        const liked = snapshot.likedIds
            .map((id) => snapshot.songById.get(id))
            .filter((row) => row !== undefined);
        const artistKey =
            query.artistId !== undefined && query.artistId !== ''
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
    getPlayQueue: async (args) => {
        // P3-B: the #187 queue persistence, bridged. Canonical track ids,
        // queue order, cursor, playhead offset — NEVER resolved stream URLs
        // (they expire; restore resolves fresh through getStreamUrl). The
        // in-flight scrobble session rides along so a restored play keeps
        // accumulating instead of re-scrobbling.
        const { apiClientProps } = args;
        const persistence = queuePersistenceFor(apiClientProps.serverId);
        const payload = await persistence.load();

        // Opportunistic outbox drain (yesterday's offline scrobbles ride out
        // with the restore).
        const session = await resolveStreamifySessionAsync(apiClientProps.server?.credential);
        void scrobbleBridge()
            .drain({
                // P4B: the Streamify scrobble tracking toggle (settings panel).
                // Local session bookkeeping keeps running; only the cloud
                // send lane pauses (queued outbox entries drain on re-enable).
                canSend: () =>
                    canSendCloud(session) &&
                    useStreamifySettingsStoreBase.getState().settings.scrobbleEnabled,
                send: (event) =>
                    recordPlayEventStrict(session, {
                        artist: event.artist,
                        completionRatio:
                            event.durationMs > 0
                                ? Math.min(1, event.playedMs / event.durationMs)
                                : 0,
                        durationPlayedSec: event.playedMs / 1000,
                        title: event.title,
                        trackSig: event.trackId,
                    }),
            })
            .catch(() => undefined);

        if (payload === null) {
            // Nothing persisted (fresh install / cleared storage): the
            // honest empty queue — same shape a Navidrome with no saved
            // queue returns.
            return {
                changed: '',
                changedBy: '',
                currentIndex: 0,
                entry: [],
                positionMs: 0,
                username: '',
            };
        }

        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);

        // Resolve the persisted ids against the CURRENT library — tracks
        // that no longer exist drop out (never a crash, never a dead row);
        // the cursor re-derives to the kept row that matches the saved
        // index, so restore lands on the same song when it survived.
        const entry = [] as ReturnType<typeof normalizeSong>[];
        let currentIndex = 0;
        let cursorSet = false;
        for (let i = 0; i < payload.songIds.length; i += 1) {
            const row = snapshot.songById.get(payload.songIds[i] as string);
            if (row === undefined) continue;
            if (!cursorSet && (i === payload.currentIndex || payload.currentIndex === null)) {
                currentIndex = entry.length;
                cursorSet = true;
            }
            entry.push(normalizeSong(row, snapshot, apiClientProps.serverId));
        }
        if (!cursorSet) {
            // The saved cursor's row did not survive: land on the nearest
            // surviving position (clamped), honest degradation.
            currentIndex = Math.min(payload.currentIndex ?? 0, Math.max(entry.length - 1, 0));
        }

        // Continue the scrobble session across the restore (a fired
        // session never re-fires; an in-flight one keeps its accrued time)
        // — only when it belongs to the song the restore lands on.
        const currentSongId = entry[currentIndex]?.id ?? null;
        const sessionToContinue =
            payload.session !== null && payload.session.trackId === currentSongId
                ? payload.session
                : null;
        scrobbleBridge().continueSession(sessionToContinue, payload.positionMs);

        return {
            changed: new Date(payload.savedAt).toISOString(),
            changedBy: 'feishin',
            currentIndex,
            entry,
            positionMs: payload.positionMs,
            username: session?.email ?? session?.userId ?? '',
        };
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
    savePlayQueue: async (args) => {
        // P3-B: persist the canonical track ids, queue order, cursor, and
        // playhead offset (never resolved stream URLs). An empty songs list
        // CLEARS the persisted state (the queue is gone — same semantics as
        // the web spine's persistence.clear on an emptied machine).
        const { apiClientProps, query } = args;
        const persistence = queuePersistenceFor(apiClientProps.serverId);

        if (query.songs.length === 0) {
            await persistence.clear();
            return;
        }

        // Deduplicate while preserving order (the serializer rejects
        // duplicate ids as corrupt on the way back in — normalize here so a
        // queue that legitimately contains the same song twice via different
        // queue items still round-trips).
        const songIds: string[] = [];
        const seen = new Set<string>();
        for (const id of query.songs) {
            if (!seen.has(id)) {
                seen.add(id);
                songIds.push(id);
            }
        }

        // Clamp the cursor into range (feishin sends undefined when the
        // queue is empty-ish; a stale index clamps to the last row).
        const rawIndex =
            query.currentIndex === undefined || query.currentIndex === null
                ? null
                : query.currentIndex;
        const currentIndex =
            rawIndex === null ? null : Math.max(0, Math.min(rawIndex, songIds.length - 1));

        await persistence.save({
            currentIndex,
            positionMs: Math.max(0, query.positionMs ?? 0),
            savedAt: Date.now(),
            // The in-flight scrobble session rides along — included only
            // when it belongs to the song the cursor names current.
            session: scrobbleBridge().sessionForQueue(
                currentIndex !== null ? (songIds[currentIndex] ?? null) : null,
            ),
            songIds,
            v: 3,
        });
    },
    scrobble: async (args) => {
        // P3-B: Feishin's playback reporting → PlaySessionTracker (min(
        // half-duration, 4 min) ACTUAL played time) → ScrobbleOutbox (200
        // cap, 5-attempt poison drop, flap reset) → recordPlayEventStrict
        // (with the resolved stream rung attached). The Jellyfin-gated
        // progress/seek lanes never fire for a Streamify server
        // (REPORT_PLAYBACK is not in our feature set) — what arrives here
        // is the sparse start/stop/pause/unpause/submission stream the
        // bridge reconstructs actual played time from.
        const { apiClientProps, query } = args;
        const snapshot = await getStreamifyLibrary(apiClientProps.server, apiClientProps.signal);
        const row = snapshot.songById.get(query.id);

        const report: ScrobbleReport = {
            event: query.event,
            id: query.id,
            position: query.position,
            submission: query.submission,
        };
        const facts = {
            album: row?.album ?? '',
            artist: row?.artist ?? '',
            durationMs: row?.durationSec != null ? Math.round(row.durationSec * 1000) : 0,
            title: row?.title ?? '',
            videoId: row?.streamUrl ?? null,
        };

        const session = await resolveStreamifySessionAsync(apiClientProps.server?.credential);
        scrobbleBridge().handleReport(report, facts, {
            canSend: () => canSendCloud(session),
            send: (event) =>
                recordPlayEventStrict(session, {
                    artist: event.artist,
                    completionRatio:
                        event.durationMs > 0 ? Math.min(1, event.playedMs / event.durationMs) : 0,
                    durationPlayedSec: event.playedMs / 1000,
                    title: event.title,
                    trackSig: event.trackId,
                }),
        });
        return null;
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
