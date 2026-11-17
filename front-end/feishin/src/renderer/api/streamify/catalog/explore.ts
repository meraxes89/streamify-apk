/**
 * api/streamify/catalog/explore.ts — the W1-B regional wiring:
 * getExplore (mood/genre tiles + regional charts) and getHomeFeed
 * (new-release albums/singles + quick-pick song shelves).
 *
 * Feishin's genre surface is a flat Genre list; the W1-B mapping:
 *   - every Explore mood/genre becomes a Genre tile with a stable
 *     `ytm-mood:`/`ytm-genre:` id (regional via gl, localized via hl —
 *     both the provider's defaults unless the caller passes hints);
 *   - a NEW RELEASES pseudo-tile (`ytm-new-releases`, present only when
 *     the home feed answered) routes album-list filtering to the home
 *     feed's new-release sections;
 *   - a mood tile's SONGS come from the Automix engine seeded by a scoped
 *     search (getUpNext is the contract's radio lane — getPlaylist is not
 *     in the W1-A surface, so mood playlists are not listable; documented
 *     honestly in the ADR).
 */

import type { LibrarySnapshot } from '../library';
import type { MetadataProvider, YtmAlbumRef, YtmSongRef, YtmTaxonomy } from '../metadata/provider';

import { getMetadataProvider } from '../metadata/provider';
import { automixRadio, recentExclusions } from './automix';
import { songBySig, songByVideoId, ytmToSong } from './ytm-mappers';

import { Genre, LibraryItem, ServerType, Song } from '/@/shared/types/domain-types';

export const YTM_MOOD_ID_PREFIX = 'ytm-mood:';
export const YTM_GENRE_ID_PREFIX = 'ytm-genre:';
export const NEW_RELEASES_TILE_ID = 'ytm-new-releases';
export const NEW_RELEASES_TILE_NAME = 'New releases';

/**
 * The genre list: Explore moods + genres (regional charts and mood chips
 * — Workout / Focus / Chill / Commute / Party per region), plus the
 * New Releases pseudo-tile when the home feed answered. Resolves [] on
 * any miss (the pre-W1-A stub included) — the honest empty the genre
 * page already renders.
 */
export async function exploreGenreTiles(
    serverId: string,
    opts: { gl?: string; hl?: string; provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<Genre[]> {
    const provider = opts.provider ?? getMetadataProvider();
    const explore = await provider.getExplore(opts.gl, opts.hl);
    if (explore === null) return [];
    const tiles: Genre[] = [];
    for (const mood of explore.moods) tiles.push(tile(moodTileId(mood), mood.name, serverId));
    for (const genre of explore.genres) tiles.push(tile(genreTileId(genre), genre.name, serverId));
    if (tiles.length === 0) return [];
    // The pseudo-tile rides only when new releases actually exist.
    const releases = await newReleaseAlbums(opts);
    if (releases.length > 0) {
        tiles.push(tile(NEW_RELEASES_TILE_ID, NEW_RELEASES_TILE_NAME, serverId));
    }
    return tiles;
}

export function genreTileId(genre: YtmTaxonomy): string {
    return `${YTM_GENRE_ID_PREFIX}${genre.params}`;
}

/** Does a genre-id list carry any YTM tile id? (the controller branch test) */
export function hasYtmTileIds(genreIds: string[] | undefined): boolean {
    return (
        genreIds !== undefined &&
        genreIds.some(
            (id) =>
                id.startsWith(YTM_MOOD_ID_PREFIX) ||
                id.startsWith(YTM_GENRE_ID_PREFIX) ||
                id === NEW_RELEASES_TILE_ID,
        )
    );
}

/**
 * The home feed's song shelves (quick picks / listen-again style sections
 * with actual track items). Resolves [] on a miss.
 */
export async function homeFeedSongs(
    opts: { gl?: string; hl?: string; provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<{ songs: YtmSongRef[]; title: string }[]> {
    const provider = opts.provider ?? getMetadataProvider();
    const feed = await provider.getHomeFeed(opts.gl, opts.hl);
    if (feed === null) return [];
    return feed.sections
        .filter((section) => (section.songs ?? []).length > 0)
        .map((section) => ({ songs: section.songs ?? [], title: section.title }));
}

/**
 * The mood/genre radio (W1-B): seed the Automix engine with a scoped
 * search for the tile's vibe (getUpNext is the contract's radio lane —
 * mood playlists themselves are not listable), filtered against the
 * recently-played ledger. Seed fallback: the home feed's first song
 * shelf. A total miss resolves [] (the honest empty the genre filter
 * had before W1-B).
 */
export async function moodRadioSongs(
    snapshot: LibrarySnapshot,
    genreIdOrName: string,
    opts: {
        count?: number;
        gl?: string;
        hl?: string;
        provider?: MetadataProvider;
        serverId: string;
        signal?: AbortSignal;
    },
): Promise<Song[]> {
    const provider = opts.provider ?? getMetadataProvider();
    const count = opts.count ?? 20;
    const tiles = await exploreGenreTiles(opts.serverId, {
        gl: opts.gl,
        hl: opts.hl,
        provider,
        signal: opts.signal,
    });
    const moodName = tileNameOf(genreIdOrName, tiles);
    if (moodName === null) return [];

    let seed: undefined | YtmSongRef;
    const scoped = await provider.search(`${moodName} music`, 'songs', undefined, opts.gl, opts.hl);
    seed = scoped?.songs[0];
    if (seed === undefined) {
        const shelves = await homeFeedSongs({
            gl: opts.gl,
            hl: opts.hl,
            provider,
            signal: opts.signal,
        });
        seed = shelves[0]?.songs[0];
    }
    if (seed === undefined || seed.videoId === '') return [];

    const exclusions = recentExclusions(snapshot);
    const refs = await automixRadio(provider, seed.videoId, count, {
        extra: {
            sigs: new Set([
                `${seed.title.trim().toLowerCase()}|${seed.artist.trim().toLowerCase()}`,
                ...exclusions.sigs,
            ]),
            videoIds: new Set([seed.videoId, ...exclusions.videoIds]),
        },
        signal: opts.signal,
    });
    const byVideoId = songByVideoId(snapshot);
    const bySig = songBySig(snapshot);
    return refs.map((ref) => ytmToSong(ref, snapshot, opts.serverId, { bySig, byVideoId }));
}

/** mood params → the stable tile id. */
export function moodTileId(mood: YtmTaxonomy): string {
    return `${YTM_MOOD_ID_PREFIX}${mood.params}`;
}

/**
 * The home feed's new-release albums/singles (gl/hl regional). Resolves
 * [] on a miss.
 */
export async function newReleaseAlbums(
    opts: { gl?: string; hl?: string; provider?: MetadataProvider; signal?: AbortSignal } = {},
): Promise<YtmAlbumRef[]> {
    const provider = opts.provider ?? getMetadataProvider();
    const feed = await provider.getHomeFeed(opts.gl, opts.hl);
    if (feed === null) return [];
    const out: YtmAlbumRef[] = [];
    const seen = new Set<string>();
    for (const section of feed.sections) {
        for (const album of section.albums ?? []) {
            if (album.browseId === '' || seen.has(album.browseId)) continue;
            seen.add(album.browseId);
            out.push(album);
        }
    }
    return out;
}

/** A tile id (or a bare genre name from the random-songs picker) → the tile name. */
export function tileNameOf(idOrName: string, tiles: Genre[]): null | string {
    const direct = tiles.find((tile) => tile.id === idOrName);
    if (direct !== undefined) return direct.name;
    const byName = tiles.find(
        (tile) => tile.name.trim().toLowerCase() === idOrName.trim().toLowerCase(),
    );
    return byName?.name ?? null;
}

function tile(id: string, name: string, serverId: string): Genre {
    return {
        _itemType: LibraryItem.GENRE,
        _serverId: serverId,
        _serverType: ServerType.STREAMIFY,
        // No playlist listing in the W1-A contract: counts stay honestly
        // zero until a mood-playlist lane exists (ADR).
        albumCount: 0,
        id,
        imageId: null,
        imageUrl: null,
        name,
        songCount: 0,
    };
}
