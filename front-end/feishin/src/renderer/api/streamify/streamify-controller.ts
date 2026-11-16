import { StreamifyNotImplementedError } from './streamify-error';
import { streamifyRequest } from './streamify-request';
import { getStreamifySupabase, signOutStreamify } from './streamify-supabase';

import { logger } from '/@/renderer/utils/logger';
import { InternalControllerEndpoint } from '/@/shared/types/domain-types';

/** Version reported by getServerInfo; bumped as endpoints become real. */
const STREAMIFY_CONTROLLER_VERSION = '0.1.0';

/**
 * Controller for the Streamify server type.
 *
 * P1: the auth trio is real (`authenticate`, `getUserInfo`, `getServerInfo`),
 * backed by the Supabase session (see streamify-supabase.ts) and the
 * 401-refresh-retry request lane (streamify-request.ts).
 *
 * Stub semantics for everything else (until implemented, one endpoint at a
 * time): Promise-typed endpoints reject asynchronously with a
 * `StreamifyNotImplementedError` (react-query / handler layers surface those
 * as honest error states); the three sync-typed URL builders
 * (`getImageUrl`, `getImageRequest`, `getDownloadUrl`) return empty values so
 * render paths that call them synchronously show the stock placeholders
 * instead of crashing.
 *
 * See PATCHES.md at the fork root for the registration rationale.
 */
export const StreamifyController: InternalControllerEndpoint = {
    addToPlaylist: async () => {
        throw new StreamifyNotImplementedError('addToPlaylist');
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
    createPlaylist: async () => {
        throw new StreamifyNotImplementedError('createPlaylist');
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
    deletePlaylist: async () => {
        throw new StreamifyNotImplementedError('deletePlaylist');
    },
    deletePlaylistImage: async () => {
        throw new StreamifyNotImplementedError('deletePlaylistImage');
    },
    getAlbumArtistDetail: async () => {
        throw new StreamifyNotImplementedError('getAlbumArtistDetail');
    },
    getAlbumArtistInfo: async () => {
        throw new StreamifyNotImplementedError('getAlbumArtistInfo');
    },
    getAlbumArtistList: async () => {
        throw new StreamifyNotImplementedError('getAlbumArtistList');
    },
    getAlbumArtistListCount: async () => {
        throw new StreamifyNotImplementedError('getAlbumArtistListCount');
    },
    getAlbumDetail: async () => {
        throw new StreamifyNotImplementedError('getAlbumDetail');
    },
    getAlbumInfo: async () => {
        throw new StreamifyNotImplementedError('getAlbumInfo');
    },
    getAlbumList: async () => {
        throw new StreamifyNotImplementedError('getAlbumList');
    },
    getAlbumListCount: async () => {
        throw new StreamifyNotImplementedError('getAlbumListCount');
    },
    getAlbumRadio: async () => {
        throw new StreamifyNotImplementedError('getAlbumRadio');
    },
    getArtistList: async () => {
        throw new StreamifyNotImplementedError('getArtistList');
    },
    getArtistListCount: async () => {
        throw new StreamifyNotImplementedError('getArtistListCount');
    },
    getArtistRadio: async () => {
        throw new StreamifyNotImplementedError('getArtistRadio');
    },
    getDownloadUrl: () => {
        // Sync-typed URL builder — empty string until P2 implements downloads.
        return '';
    },
    getFavoriteSongs: async () => {
        throw new StreamifyNotImplementedError('getFavoriteSongs');
    },
    getFolder: async () => {
        throw new StreamifyNotImplementedError('getFolder');
    },
    getGenreList: async () => {
        throw new StreamifyNotImplementedError('getGenreList');
    },
    getImageRequest: () => {
        // Sync-typed URL builder — null renders the stock image placeholder.
        return null;
    },
    getImageUrl: () => {
        // Sync-typed URL builder — null renders the stock image placeholder
        // (a throw here crashes render paths that call it synchronously).
        return null;
    },
    getInternetRadioStations: async () => {
        throw new StreamifyNotImplementedError('getInternetRadioStations');
    },
    getLyrics: async () => {
        throw new StreamifyNotImplementedError('getLyrics');
    },
    getMusicFolderList: async () => {
        throw new StreamifyNotImplementedError('getMusicFolderList');
    },
    getPlaylistDetail: async () => {
        throw new StreamifyNotImplementedError('getPlaylistDetail');
    },
    getPlaylistList: async () => {
        throw new StreamifyNotImplementedError('getPlaylistList');
    },
    getPlaylistListCount: async () => {
        throw new StreamifyNotImplementedError('getPlaylistListCount');
    },
    getPlaylistSongIds: async () => {
        throw new StreamifyNotImplementedError('getPlaylistSongIds');
    },
    getPlaylistSongList: async () => {
        throw new StreamifyNotImplementedError('getPlaylistSongList');
    },
    getPlayQueue: async () => {
        throw new StreamifyNotImplementedError('getPlayQueue');
    },
    getRandomSongList: async () => {
        throw new StreamifyNotImplementedError('getRandomSongList');
    },
    getRoles: async () => {
        throw new StreamifyNotImplementedError('getRoles');
    },
    getScanStatus: async () => {
        throw new StreamifyNotImplementedError('getScanStatus');
    },
    getServerInfo: async (args) => {
        // Real request through the Streamify request lane: GoTrue's
        // /auth/v1/user validates the access token (and exercises the
        // 401 → refresh → retry → clean-sign-out lane) on every boot.
        await streamifyRequest('/auth/v1/user', { method: 'GET' });

        return {
            features: {},
            id: args.apiClientProps.server?.id,
            version: STREAMIFY_CONTROLLER_VERSION,
        };
    },
    getSimilarSongs: async () => {
        throw new StreamifyNotImplementedError('getSimilarSongs');
    },
    getSongDetail: async () => {
        throw new StreamifyNotImplementedError('getSongDetail');
    },
    getSongList: async () => {
        throw new StreamifyNotImplementedError('getSongList');
    },
    getSongListCount: async () => {
        throw new StreamifyNotImplementedError('getSongListCount');
    },
    getStreamUrl: async () => {
        throw new StreamifyNotImplementedError('getStreamUrl');
    },
    getStructuredLyrics: async () => {
        throw new StreamifyNotImplementedError('getStructuredLyrics');
    },
    getTagList: async () => {
        throw new StreamifyNotImplementedError('getTagList');
    },
    getTopSongs: async () => {
        throw new StreamifyNotImplementedError('getTopSongs');
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
    movePlaylistItem: async () => {
        throw new StreamifyNotImplementedError('movePlaylistItem');
    },
    refreshItems: async () => {
        throw new StreamifyNotImplementedError('refreshItems');
    },
    removeFromPlaylist: async () => {
        throw new StreamifyNotImplementedError('removeFromPlaylist');
    },
    replacePlaylist: async () => {
        throw new StreamifyNotImplementedError('replacePlaylist');
    },
    savePlayQueue: async () => {
        throw new StreamifyNotImplementedError('savePlayQueue');
    },
    scrobble: async () => {
        throw new StreamifyNotImplementedError('scrobble');
    },
    search: async () => {
        throw new StreamifyNotImplementedError('search');
    },
    setPlaylistSongs: async () => {
        throw new StreamifyNotImplementedError('setPlaylistSongs');
    },
    setRating: async () => {
        throw new StreamifyNotImplementedError('setRating');
    },
    shareItem: async () => {
        throw new StreamifyNotImplementedError('shareItem');
    },
    startLibraryScan: async () => {
        throw new StreamifyNotImplementedError('startLibraryScan');
    },
    updateInternetRadioStation: async () => {
        throw new StreamifyNotImplementedError('updateInternetRadioStation');
    },
    updatePlaylist: async () => {
        throw new StreamifyNotImplementedError('updatePlaylist');
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
