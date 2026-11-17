/**
 * api/streamify/catalog/discography.ts — the W1-B structured artist
 * discography & related graph.
 *
 * yt.music.getArtist(browseId) splits the artist page into Albums,
 * Singles & EPs, Live Performances and Featured On (appearsOn), plus
 * top songs, the biography and Fans-also-like (relatedArtists).
 * yt.music.getRelated(seedVideoId) supplies the SONG-level related
 * graph — alternate versions, covers and remixes of a track.
 *
 * Feishin's AlbumArtist DTO carries the artist entity (with
 * similarArtists for the related shelf); the full split is this module's
 * documented contract (the W1-B ADR) — the album grid pages through
 * getAlbumList({artistIds: ['ytm-artist:<browseId>']}) and the song-level
 * alternates ride getRelated. Every miss resolves the honest empty.
 */

import type { MetadataProvider, YtmAlbumRef, YtmArtistDetail } from '../metadata/provider';

import { getMetadataProvider } from '../metadata/provider';
import { browseIdOfYtmArtist } from './ytm-mappers';

export interface ArtistDiscography {
    albums: YtmAlbumRef[];
    /** Featured On — albums the artist appears on but does not own. */
    appearsOn: YtmAlbumRef[];
    detail: YtmArtistDetail;
    /** Live Performances (concert albums). */
    liveAlbums: YtmAlbumRef[];
    /** Fans Also Like. */
    relatedArtists: YtmArtistDetail['relatedArtists'];
    /** Singles & EPs. */
    singles: YtmAlbumRef[];
    topSongs: YtmArtistDetail['topSongs'];
}

/** The album grid rows for a ytm-artist: id — albums, singles AND live. */
export async function artistAlbumGrid(
    artistId: string,
    opts: { gl?: string; hl?: string; provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<YtmAlbumRef[]> {
    const disco = await artistDiscography(artistId, opts);
    if (disco === null) return [];
    return [...disco.albums, ...disco.singles, ...disco.liveAlbums];
}

/**
 * The full discography split for a ytm-artist: id (or a bare browseId).
 * null on a miss (the controller falls back to the snapshot artist).
 */
export async function artistDiscography(
    artistId: string,
    opts: { gl?: string; hl?: string; provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<ArtistDiscography | null> {
    const provider = opts.provider ?? getMetadataProvider();
    const browseId = browseIdOfYtmArtist(artistId) ?? artistId;
    const detail = await provider.getArtist(browseId, opts.gl, opts.hl);
    if (detail === null) return null;
    return {
        albums: detail.albums,
        appearsOn: detail.appearsOn,
        detail,
        liveAlbums: detail.liveAlbums,
        relatedArtists: detail.relatedArtists,
        singles: detail.singles,
        topSongs: detail.topSongs,
    };
}

/**
 * Song-level alternate versions / covers for a seed video
 * (yt.music.getRelated). [] on a miss.
 */
export async function relatedSongVersions(
    seedVideoId: string,
    opts: { provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<YtmArtistDetail['topSongs']> {
    if (seedVideoId === '') return [];
    const provider = opts.provider ?? getMetadataProvider();
    const related = await provider.getRelated(seedVideoId);
    return related?.songs ?? [];
}
