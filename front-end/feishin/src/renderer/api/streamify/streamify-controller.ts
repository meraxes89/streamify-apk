import { StreamifyNotImplementedError } from './streamify-error';

import { InternalControllerEndpoint } from '/@/shared/types/domain-types';

/**
 * Stub controller for the Streamify server type.
 *
 * Every endpoint required by `InternalControllerEndpoint` (plus every optional
 * one) throws a `StreamifyNotImplementedError` carrying the endpoint name.
 * There is intentionally no logic here: the UI already gates its affordances
 * per server type, so a registered-but-stubbed controller hides Streamify-only
 * surfaces until the endpoints are implemented for real.
 *
 * See PATCHES.md at the fork root for the registration rationale.
 */
export const StreamifyController: InternalControllerEndpoint = {
    addToPlaylist: () => {
        throw new StreamifyNotImplementedError('addToPlaylist');
    },
    authenticate: () => {
        throw new StreamifyNotImplementedError('authenticate');
    },
    createFavorite: () => {
        throw new StreamifyNotImplementedError('createFavorite');
    },
    createInternetRadioStation: () => {
        throw new StreamifyNotImplementedError('createInternetRadioStation');
    },
    createPlaylist: () => {
        throw new StreamifyNotImplementedError('createPlaylist');
    },
    deleteArtistImage: () => {
        throw new StreamifyNotImplementedError('deleteArtistImage');
    },
    deleteFavorite: () => {
        throw new StreamifyNotImplementedError('deleteFavorite');
    },
    deleteInternetRadioStation: () => {
        throw new StreamifyNotImplementedError('deleteInternetRadioStation');
    },
    deleteInternetRadioStationImage: () => {
        throw new StreamifyNotImplementedError('deleteInternetRadioStationImage');
    },
    deletePlaylist: () => {
        throw new StreamifyNotImplementedError('deletePlaylist');
    },
    deletePlaylistImage: () => {
        throw new StreamifyNotImplementedError('deletePlaylistImage');
    },
    getAlbumArtistDetail: () => {
        throw new StreamifyNotImplementedError('getAlbumArtistDetail');
    },
    getAlbumArtistInfo: () => {
        throw new StreamifyNotImplementedError('getAlbumArtistInfo');
    },
    getAlbumArtistList: () => {
        throw new StreamifyNotImplementedError('getAlbumArtistList');
    },
    getAlbumArtistListCount: () => {
        throw new StreamifyNotImplementedError('getAlbumArtistListCount');
    },
    getAlbumDetail: () => {
        throw new StreamifyNotImplementedError('getAlbumDetail');
    },
    getAlbumInfo: () => {
        throw new StreamifyNotImplementedError('getAlbumInfo');
    },
    getAlbumList: () => {
        throw new StreamifyNotImplementedError('getAlbumList');
    },
    getAlbumListCount: () => {
        throw new StreamifyNotImplementedError('getAlbumListCount');
    },
    getAlbumRadio: () => {
        throw new StreamifyNotImplementedError('getAlbumRadio');
    },
    getArtistList: () => {
        throw new StreamifyNotImplementedError('getArtistList');
    },
    getArtistListCount: () => {
        throw new StreamifyNotImplementedError('getArtistListCount');
    },
    getArtistRadio: () => {
        throw new StreamifyNotImplementedError('getArtistRadio');
    },
    getDownloadUrl: () => {
        throw new StreamifyNotImplementedError('getDownloadUrl');
    },
    getFavoriteSongs: () => {
        throw new StreamifyNotImplementedError('getFavoriteSongs');
    },
    getFolder: () => {
        throw new StreamifyNotImplementedError('getFolder');
    },
    getGenreList: () => {
        throw new StreamifyNotImplementedError('getGenreList');
    },
    getImageRequest: () => {
        throw new StreamifyNotImplementedError('getImageRequest');
    },
    getImageUrl: () => {
        throw new StreamifyNotImplementedError('getImageUrl');
    },
    getInternetRadioStations: () => {
        throw new StreamifyNotImplementedError('getInternetRadioStations');
    },
    getLyrics: () => {
        throw new StreamifyNotImplementedError('getLyrics');
    },
    getMusicFolderList: () => {
        throw new StreamifyNotImplementedError('getMusicFolderList');
    },
    getPlaylistDetail: () => {
        throw new StreamifyNotImplementedError('getPlaylistDetail');
    },
    getPlaylistList: () => {
        throw new StreamifyNotImplementedError('getPlaylistList');
    },
    getPlaylistListCount: () => {
        throw new StreamifyNotImplementedError('getPlaylistListCount');
    },
    getPlaylistSongIds: () => {
        throw new StreamifyNotImplementedError('getPlaylistSongIds');
    },
    getPlaylistSongList: () => {
        throw new StreamifyNotImplementedError('getPlaylistSongList');
    },
    getPlayQueue: () => {
        throw new StreamifyNotImplementedError('getPlayQueue');
    },
    getRandomSongList: () => {
        throw new StreamifyNotImplementedError('getRandomSongList');
    },
    getRoles: () => {
        throw new StreamifyNotImplementedError('getRoles');
    },
    getScanStatus: () => {
        throw new StreamifyNotImplementedError('getScanStatus');
    },
    getServerInfo: () => {
        throw new StreamifyNotImplementedError('getServerInfo');
    },
    getSimilarSongs: () => {
        throw new StreamifyNotImplementedError('getSimilarSongs');
    },
    getSongDetail: () => {
        throw new StreamifyNotImplementedError('getSongDetail');
    },
    getSongList: () => {
        throw new StreamifyNotImplementedError('getSongList');
    },
    getSongListCount: () => {
        throw new StreamifyNotImplementedError('getSongListCount');
    },
    getStreamUrl: () => {
        throw new StreamifyNotImplementedError('getStreamUrl');
    },
    getStructuredLyrics: () => {
        throw new StreamifyNotImplementedError('getStructuredLyrics');
    },
    getTagList: () => {
        throw new StreamifyNotImplementedError('getTagList');
    },
    getTopSongs: () => {
        throw new StreamifyNotImplementedError('getTopSongs');
    },
    getUserInfo: () => {
        throw new StreamifyNotImplementedError('getUserInfo');
    },
    getUserList: () => {
        throw new StreamifyNotImplementedError('getUserList');
    },
    jukeboxControl: () => {
        throw new StreamifyNotImplementedError('jukeboxControl');
    },
    movePlaylistItem: () => {
        throw new StreamifyNotImplementedError('movePlaylistItem');
    },
    refreshItems: () => {
        throw new StreamifyNotImplementedError('refreshItems');
    },
    removeFromPlaylist: () => {
        throw new StreamifyNotImplementedError('removeFromPlaylist');
    },
    replacePlaylist: () => {
        throw new StreamifyNotImplementedError('replacePlaylist');
    },
    savePlayQueue: () => {
        throw new StreamifyNotImplementedError('savePlayQueue');
    },
    scrobble: () => {
        throw new StreamifyNotImplementedError('scrobble');
    },
    search: () => {
        throw new StreamifyNotImplementedError('search');
    },
    setPlaylistSongs: () => {
        throw new StreamifyNotImplementedError('setPlaylistSongs');
    },
    setRating: () => {
        throw new StreamifyNotImplementedError('setRating');
    },
    shareItem: () => {
        throw new StreamifyNotImplementedError('shareItem');
    },
    startLibraryScan: () => {
        throw new StreamifyNotImplementedError('startLibraryScan');
    },
    updateInternetRadioStation: () => {
        throw new StreamifyNotImplementedError('updateInternetRadioStation');
    },
    updatePlaylist: () => {
        throw new StreamifyNotImplementedError('updatePlaylist');
    },
    uploadArtistImage: () => {
        throw new StreamifyNotImplementedError('uploadArtistImage');
    },
    uploadInternetRadioStationImage: () => {
        throw new StreamifyNotImplementedError('uploadInternetRadioStationImage');
    },
    uploadPlaylistImage: () => {
        throw new StreamifyNotImplementedError('uploadPlaylistImage');
    },
};
