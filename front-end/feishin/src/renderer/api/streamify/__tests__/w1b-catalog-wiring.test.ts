/**
 * w1b-catalog-wiring.test.ts — the DTO & pagination contract lock for the
 * W1-B InnerTube catalog wiring, driven through the REAL
 * StreamifyController (the surface feishin's query hooks call):
 *
 *   - getSimilarSongs: Up Next continuation pages, playTally-ledger
 *     exclusion, seed exclusion, snapshot fallback on a provider miss;
 *   - getGenreList / new releases: the Explore tile DTO + home-feed
 *     albums, paginated honestly;
 *   - getTopSongs: the artist page's real top songs merged ahead of the
 *     user's play-ranked rows, snapshot fallback;
 *   - getAlbumArtistDetail / getAlbumList: the ytm-artist: discography
 *     split (albums + singles + live in the grid, appearsOn/related in
 *     the info endpoint);
 *   - search: the scoped merge (snapshot first, provider appended,
 *     deduped by signature);
 *   - getFavoriteAlbums / getFavoriteArtists: the item-favorites ledger
 *     reads, paginated;
 *   - getStreamUrl: the ytm: id namespace decodes straight to the video.
 *
 * Network-free by construction: the engine is module-mocked with a
 * fixture snapshot, the stream-source seam is stubbed, the provider is a
 * fake (same discipline as the P3-A controller suites).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
    return {
        resolveCalls: [] as string[],
        snapshot: null as unknown,
    };
});

vi.mock('../engine', () => ({
    currentStreamifyLibrary: vi.fn(() => h.snapshot),
    getStreamifyLibrary: vi.fn(async () => h.snapshot),
    invalidateStreamifyLibraryMemo: vi.fn(),
}));

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('/@/renderer/lib/react-query', () => ({
    queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('/@/renderer/store', () => ({
    useAuthStore: { getState: () => ({ actions: {}, currentServer: null, serverList: {} }) },
}));

vi.mock('../playback/stream-source', () => ({
    resolveStreamifyTrack: vi.fn(async (videoId: string) => {
        h.resolveCalls.push(videoId);
        return { protocol: 'progressive', url: `https://stream.test/${videoId}` };
    }),
    StreamifyNoVideoError: class StreamifyNoVideoError extends Error {},
}));

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import type { LibrarySnapshot, SongRow } from '../library';
import type {
    MetadataProvider,
    YtmArtistDetail,
    YtmHomeFeed,
    YtmSearchResults,
    YtmSongRef,
} from '../metadata/provider';

import { getFavoriteAlbums, getFavoriteArtists } from '../catalog/favorite-items';
import { setMetadataProviderForTests, stubMetadataProvider } from '../metadata/provider';
import { StreamifyController } from '../streamify-controller';

import {
    AlbumListSort,
    ArtistListSort,
    GenreListSort,
    LibraryItem,
    Played,
    SortOrder,
} from '/@/shared/types/domain-types';
import { ServerType } from '/@/shared/types/types';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const SERVER: ServerListItemWithCredential = {
    credential: '',
    id: 'streamify',
    isAdmin: false,
    name: 'Streamify',
    savePassword: false,
    type: ServerType.STREAMIFY,
    url: 'https://supabase.test',
    userId: 'user-1',
    username: 'user-1@test',
};

const api = { server: SERVER, serverId: 'streamify' };

/** A fake provider with per-test call recording. */
function fakeProvider(impl: Partial<MetadataProvider>): MetadataProvider & {
    upNextCalls: Array<string | undefined>;
} {
    const upNextCalls: Array<string | undefined> = [];
    return {
        ...stubMetadataProvider,
        upNextCalls,
        ...impl,
    } as MetadataProvider & { upNextCalls: Array<string | undefined> };
}

function snapshotOf(
    songs: SongRow[],
    extras: Partial<Pick<LibrarySnapshot, 'likedAlbumIds' | 'likedArtistIds' | 'playEvents'>> = {},
): LibrarySnapshot {
    const songById = new Map(songs.map((row) => [row.id, row]));
    const albums = new Map<string, string[]>();
    const artists = new Map<string, string[]>();
    for (const row of songs) {
        const albumKey = `${row.album}|${row.artist}`;
        albums.set(albumKey, [...(albums.get(albumKey) ?? []), row.id]);
        artists.set(row.artist, [...(artists.get(row.artist) ?? []), row.id]);
    }
    const albumRows = [...albums.entries()].map(([key, ids]) => ({
        addedRank: 0,
        artistName: key.split('|')[1] ?? '',
        coverUrl: null,
        durationMs: ids.length * 200_000,
        id: key,
        key,
        lastPlayedAt: null,
        name: key.split('|')[0] ?? '',
        playCount: 0,
        songIds: ids,
    }));
    const artistRows = [...artists.entries()].map(([name, ids]) => ({
        addedRank: 0,
        albumIds: [],
        coverUrl: null,
        durationMs: ids.length * 200_000,
        id: name,
        key: name.toLowerCase(),
        lastPlayedAt: null,
        name,
        playCount: 0,
        songIds: ids,
    }));
    return {
        albumById: new Map(albumRows.map((row) => [row.id, row])),
        albums: albumRows,
        artistById: new Map(artistRows.map((row) => [row.id, row])),
        artists: artistRows,
        likedAlbumIds: extras.likedAlbumIds ?? new Set<string>(),
        likedArtistIds: extras.likedArtistIds ?? new Set<string>(),
        likedIds: [],
        loadedAt: '2026-12-01T00:00:00.000Z',
        playEvents: extras.playEvents ?? [],
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'test',
        songById,
        songs,
    };
}

function songRef(videoId: string, title: string, artist: string): YtmSongRef {
    return { artist, durationSec: 180, title, videoId };
}

function songRow(id: string, title: string, artist: string, videoId: string): SongRow {
    return {
        addedRank: 0,
        album: `${artist} Album`,
        artist,
        coverUrl: null,
        durationSec: 200,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: videoId,
        title,
    };
}

beforeEach(() => {
    h.resolveCalls.length = 0;
    setMetadataProviderForTests(null);
});

afterEach(() => {
    setMetadataProviderForTests(null);
});

// ---------------------------------------------------------------------------
// getSimilarSongs — the endless Automix radio
// ---------------------------------------------------------------------------

describe('getSimilarSongs (Automix radio)', () => {
    it('pages Up Next continuations, maps to Song DTOs with ytm: ids, and dedupes', async () => {
        h.snapshot = snapshotOf([songRow('trk_a', 'Seed', 'Seed Artist', 'vid_seed')]);
        const provider = fakeProvider({
            getUpNext: vi.fn(async (_seed: string, continuation?: string) => {
                if (continuation === undefined) {
                    return {
                        continuation: 'page2',
                        songs: [songRef('v1', 'One', 'A'), songRef('v1', 'One', 'A')],
                    };
                }
                if (continuation === 'page2') {
                    return { continuation: null, songs: [songRef('v2', 'Two', 'B')] };
                }
                return { songs: [] };
            }),
        });
        setMetadataProviderForTests(provider);

        const out = (await StreamifyController.getSimilarSongs({
            apiClientProps: api,
            query: { count: 10, songId: 'trk_a' },
        })) as Array<{ _serverType: string; id: string; name: string }>;

        expect(out.map((s) => s.id)).toEqual(['ytm:v1', 'ytm:v2']);
        expect(out.map((s) => s.name)).toEqual(['One', 'Two']);
        expect(out[0]?._serverType).toBe('streamify');
    });

    it('filters recently played track IDs from the playTally ledger and the play-event window', async () => {
        h.snapshot = snapshotOf(
            [
                songRow('trk_seed', 'Seed', 'S', 'vid_seed'),
                songRow('trk_recent', 'Recent', 'R', 'vid_recent'),
            ],
            {
                playEvents: [
                    {
                        artist: 'R',
                        created_at: new Date(Date.now() - 60_000).toISOString(),
                        duration_played_sec: 100,
                        hour_of_day: 1,
                        title: 'Recent',
                        track_sig: 'trk_recent',
                    },
                ],
            },
        );
        const provider = fakeProvider({
            getUpNext: vi.fn(async () => ({
                songs: [songRef('vid_recent', 'Recent', 'R'), songRef('v9', 'Fresh', 'F')],
            })),
        });
        setMetadataProviderForTests(provider);

        const out = (await StreamifyController.getSimilarSongs({
            apiClientProps: api,
            query: { count: 10, songId: 'trk_seed' },
        })) as Array<{ id: string }>;

        expect(out.map((s) => s.id)).toEqual(['ytm:v9']);
    });

    it('reuses the snapshot row (cloud track id) when the radio track is already in the library', async () => {
        h.snapshot = snapshotOf([
            songRow('trk_a', 'Seed', 'S', 'vid_seed'),
            songRow('trk_known', 'Known', 'K', 'vid_known'),
        ]);
        setMetadataProviderForTests(
            fakeProvider({
                getUpNext: vi.fn(async () => ({ songs: [songRef('vid_known', 'Known', 'K')] })),
            }),
        );

        const out = (await StreamifyController.getSimilarSongs({
            apiClientProps: api,
            query: { count: 5, songId: 'trk_a' },
        })) as Array<{ id: string }>;

        expect(out.map((s) => s.id)).toEqual(['trk_known']);
    });

    it('keeps the P1 play-stats derivation on a provider miss (the pre-W1-A stub)', async () => {
        h.snapshot = snapshotOf([
            songRow('trk_a', 'Seed', 'S', 'vid_seed'),
            songRow('trk_b', 'Other', 'S', 'vid_b'),
            songRow('trk_c', 'Elsewhere', 'Z', 'vid_c'),
        ]);

        const out = (await StreamifyController.getSimilarSongs({
            apiClientProps: api,
            query: { count: 5, songId: 'trk_a' },
        })) as Array<{ id: string }>;

        expect(out.map((s) => s.id)).toEqual(['trk_b', 'trk_c']);
    });
});

// ---------------------------------------------------------------------------
// getGenreList / new releases — the Explore wiring
// ---------------------------------------------------------------------------

describe('getGenreList (Explore charts & moods)', () => {
    it('maps Explore moods + genres to paginated Genre tiles with stable ytm- ids', async () => {
        setMetadataProviderForTests(
            fakeProvider({
                getExplore: vi.fn(async () => ({
                    genres: [{ name: 'Pop', params: 'pop-param' }],
                    moods: [
                        { name: 'Workout', params: 'w0' },
                        { name: 'Focus', params: 'f0' },
                    ],
                })),
                getHomeFeed: vi.fn(
                    async (): Promise<null | YtmHomeFeed> => ({
                        sections: [
                            {
                                albums: [
                                    {
                                        artist: 'New Artist',
                                        browseId: 'ALB_new',
                                        kind: 'single',
                                        title: 'Fresh Single',
                                    },
                                ],
                                title: 'New releases',
                            },
                        ],
                    }),
                ),
            }),
        );

        const page = (await StreamifyController.getGenreList({
            apiClientProps: api,
            query: { sortBy: GenreListSort.NAME, sortOrder: SortOrder.ASC, startIndex: 0 },
        })) as { items: Array<{ id: string; name: string }>; totalRecordCount: number };

        expect(page.totalRecordCount).toBe(4); // 2 moods + 1 genre + new releases
        expect(page.items.map((t) => t.name)).toEqual(['Workout', 'Focus', 'Pop', 'New releases']);
        expect(page.items[0]?.id).toBe('ytm-mood:w0');
        expect(page.items[2]?.id).toBe('ytm-genre:pop-param');
        expect(page.items[3]?.id).toBe('ytm-new-releases');
    });

    it('paginates the tiles honestly (startIndex/limit slice, full total)', async () => {
        setMetadataProviderForTests(
            fakeProvider({
                getExplore: vi.fn(async () => ({
                    genres: [],
                    moods: [
                        { name: 'A', params: 'a' },
                        { name: 'B', params: 'b' },
                        { name: 'C', params: 'c' },
                    ],
                })),
                getHomeFeed: vi.fn(async (): Promise<null | YtmHomeFeed> => ({ sections: [] })),
            }),
        );

        const page = (await StreamifyController.getGenreList({
            apiClientProps: api,
            query: {
                limit: 2,
                sortBy: GenreListSort.NAME,
                sortOrder: SortOrder.ASC,
                startIndex: 1,
            },
        })) as { items: Array<{ name: string }>; startIndex: number; totalRecordCount: number };

        expect(page.items.map((t) => t.name)).toEqual(['B', 'C']);
        expect(page.startIndex).toBe(1);
        expect(page.totalRecordCount).toBe(3);
    });

    it('keeps the honest empty on a provider miss (pre-W1-A stub)', async () => {
        const page = (await StreamifyController.getGenreList({
            apiClientProps: api,
            query: { sortBy: GenreListSort.NAME, sortOrder: SortOrder.ASC, startIndex: 0 },
        })) as { items: unknown[]; totalRecordCount: number };
        expect(page.items).toEqual([]);
        expect(page.totalRecordCount).toBe(0);
    });

    it('the New Releases tile routes getAlbumList to the home feed (paginated)', async () => {
        h.snapshot = snapshotOf([]);
        setMetadataProviderForTests(
            fakeProvider({
                getExplore: vi.fn(async () => ({ genres: [], moods: [] })),
                getHomeFeed: vi.fn(
                    async (): Promise<null | YtmHomeFeed> => ({
                        sections: [
                            {
                                albums: [
                                    {
                                        artist: 'X',
                                        browseId: 'ALB_1',
                                        kind: 'album',
                                        title: 'Alpha',
                                    },
                                    {
                                        artist: 'Y',
                                        browseId: 'ALB_2',
                                        kind: 'single',
                                        title: 'Beta',
                                    },
                                ],
                                title: 'New music',
                            },
                        ],
                    }),
                ),
            }),
        );

        const page = (await StreamifyController.getAlbumList({
            apiClientProps: api,
            query: {
                genreIds: ['ytm-new-releases'],
                limit: 1,
                sortBy: AlbumListSort.NAME,
                sortOrder: SortOrder.ASC,
                startIndex: 0,
            },
        })) as {
            items: Array<{ id: string; name: string }>;
            startIndex: number;
            totalRecordCount: number;
        };

        expect(page.items).toHaveLength(1);
        expect(page.items[0]?.id).toBe('ytm-album:ALB_1');
        expect(page.totalRecordCount).toBe(2);
    });
});

// ---------------------------------------------------------------------------
// getTopSongs — the artist page merge
// ---------------------------------------------------------------------------

describe('getTopSongs (artist top songs merge)', () => {
    it('merges the YTM artist top songs ahead of the play-ranked snapshot rows, deduped', async () => {
        h.snapshot = snapshotOf([
            songRow('trk_1', 'Local Hit', 'Artist X', 'v_local'),
            songRow('trk_2', 'Only Local', 'Artist X', 'v_local2'),
        ]);
        setMetadataProviderForTests(
            fakeProvider({
                getArtist: vi.fn(
                    async (): Promise<null | YtmArtistDetail> => ({
                        albums: [],
                        appearsOn: [],
                        browseId: 'ART_x',
                        description: null,
                        liveAlbums: [],
                        name: 'Artist X',
                        relatedArtists: [],
                        singles: [],
                        thumbnailUrl: null,
                        topSongs: [
                            songRef('v_top', 'Real Top', 'Artist X'),
                            songRef('v_local', 'Local Hit', 'Artist X'),
                        ],
                    }),
                ),
                search: vi.fn(
                    async (): Promise<null | YtmSearchResults> => ({
                        albums: [],
                        artists: [{ browseId: 'ART_x', name: 'Artist X' }],
                        playlists: [],
                        songs: [],
                    }),
                ),
            }),
        );

        const page = (await StreamifyController.getTopSongs({
            apiClientProps: api,
            query: { artist: 'Artist X', artistId: 'Artist X', limit: 10 },
        })) as { items: Array<{ id: string; name: string }>; totalRecordCount: number };

        expect(page.items.map((s) => s.name)).toEqual(['Real Top', 'Local Hit', 'Only Local']);
        expect(page.items[0]?.id).toBe('ytm:v_top');
        expect(page.items[1]?.id).toBe('trk_1');
        expect(page.items[2]?.id).toBe('trk_2');
    });

    it('keeps the P1 derivation on a provider miss', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'Local Hit', 'Artist X', 'v_local')]);
        const page = (await StreamifyController.getTopSongs({
            apiClientProps: api,
            query: { artist: 'Artist X', artistId: 'Artist X', limit: 10 },
        })) as { items: Array<{ id: string }> };
        expect(page.items.map((s) => s.id)).toEqual(['trk_1']);
    });
});

// ---------------------------------------------------------------------------
// getAlbumArtistDetail / getAlbumList — the discography split
// ---------------------------------------------------------------------------

describe('getAlbumArtistDetail (structured discography)', () => {
    it('resolves ytm-artist: ids through the provider with the split carried honestly', async () => {
        setMetadataProviderForTests(
            fakeProvider({
                getArtist: vi.fn(
                    async (): Promise<null | YtmArtistDetail> => ({
                        albums: [
                            {
                                artist: 'X',
                                browseId: 'ALB_1',
                                kind: 'album',
                                title: 'LP',
                                trackCount: 10,
                            },
                        ],
                        appearsOn: [
                            {
                                artist: 'Host',
                                browseId: 'ALB_feat',
                                kind: 'album',
                                title: 'Feat On',
                            },
                        ],
                        browseId: 'ART_x',
                        description: 'bio',
                        liveAlbums: [
                            { artist: 'X', browseId: 'ALB_live', kind: 'live', title: 'Live' },
                        ],
                        name: 'Artist X',
                        relatedArtists: [{ browseId: 'ART_y', name: 'Artist Y' }],
                        singles: [
                            { artist: 'X', browseId: 'ALB_s', kind: 'single', title: 'Single' },
                        ],
                        thumbnailUrl: null,
                        topSongs: [songRef('v1', 'Hit', 'Artist X')],
                    }),
                ),
            }),
        );

        const artist = (await StreamifyController.getAlbumArtistDetail({
            apiClientProps: api,
            query: { id: 'ytm-artist:ART_x' },
        })) as { albumCount: number; biography: null | string; id: string; songCount: number };

        expect(artist.id).toBe('ytm-artist:ART_x');
        expect(artist.albumCount).toBe(3); // album + single + live
        expect(artist.songCount).toBe(1);
        expect(artist.biography).toBe('bio');
    });

    it('getAlbumList({artistIds: [ytm-artist:]}) pages the discography grid (albums + singles + live)', async () => {
        h.snapshot = snapshotOf([]);
        setMetadataProviderForTests(
            fakeProvider({
                getArtist: vi.fn(
                    async (): Promise<null | YtmArtistDetail> => ({
                        albums: [{ artist: 'X', browseId: 'ALB_1', kind: 'album', title: 'LP' }],
                        appearsOn: [],
                        browseId: 'ART_x',
                        liveAlbums: [
                            { artist: 'X', browseId: 'ALB_live', kind: 'live', title: 'Live' },
                        ],
                        name: 'X',
                        relatedArtists: [],
                        singles: [
                            { artist: 'X', browseId: 'ALB_s', kind: 'single', title: 'Single' },
                        ],
                        topSongs: [],
                    }),
                ),
            }),
        );

        const page = (await StreamifyController.getAlbumList({
            apiClientProps: api,
            query: {
                artistIds: ['ytm-artist:ART_x'],
                limit: 2,
                sortBy: AlbumListSort.NAME,
                sortOrder: SortOrder.ASC,
                startIndex: 0,
            },
        })) as { items: Array<{ id: string; releaseTypes: string[] }>; totalRecordCount: number };

        expect(page.totalRecordCount).toBe(3);
        expect(page.items).toHaveLength(2);
        expect(page.items[0]?.id).toBe('ytm-album:ALB_1');
        expect(page.items.map((i) => i.releaseTypes[0])).toEqual(['album', 'single']);
    });

    it('snapshot ids keep the P1 path (throw on unknown, normalize on hit)', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'T', 'Local Artist', 'v')]);
        const artist = (await StreamifyController.getAlbumArtistDetail({
            apiClientProps: api,
            query: { id: 'Local Artist' },
        })) as { name: string };
        expect(artist.name).toBe('Local Artist');
    });
});

// ---------------------------------------------------------------------------
// search — the scoped merge
// ---------------------------------------------------------------------------

describe('search (scoped merge)', () => {
    it('puts snapshot matches first, appends provider results, dedupes by signature', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'Blue Song', 'Local Band', 'v1')]);
        setMetadataProviderForTests(
            fakeProvider({
                search: vi.fn(
                    async (): Promise<null | YtmSearchResults> => ({
                        albums: [
                            { artist: 'Y', browseId: 'ALB_b', kind: 'album', title: 'Blue Album' },
                        ],
                        artists: [{ browseId: 'ART_b', name: 'Blue Artist' }],
                        playlists: [{ name: 'Blue Mix', playlistId: 'PL_1' }],
                        songs: [
                            songRef('v9', 'Blue Song', 'Local Band'), // dup of snapshot
                            songRef('v8', 'Blue Remote', 'Other'),
                        ],
                    }),
                ),
            }),
        );

        const out = (await StreamifyController.search({
            apiClientProps: api,
            query: { albumArtistLimit: 10, albumLimit: 10, query: 'Blue', songLimit: 10 },
        })) as {
            albumArtists: Array<{ name: string }>;
            albums: Array<{ name: string }>;
            songs: Array<{ id: string; name: string }>;
        };

        expect(out.songs.map((s) => s.id)).toEqual(['trk_1', 'ytm:v8']);
        expect(out.albums.map((a) => a.name)).toEqual(['Blue Album']);
        expect(out.albumArtists.map((a) => a.name)).toEqual(['Blue Artist']);
    });

    it('section pagination slices AFTER the merge', async () => {
        h.snapshot = snapshotOf([]);
        setMetadataProviderForTests(
            fakeProvider({
                search: vi.fn(
                    async (): Promise<null | YtmSearchResults> => ({
                        albums: [],
                        artists: [],
                        playlists: [],
                        songs: [
                            songRef('v1', 'S1', 'A'),
                            songRef('v2', 'S2', 'A'),
                            songRef('v3', 'S3', 'A'),
                        ],
                    }),
                ),
            }),
        );

        const out = (await StreamifyController.search({
            apiClientProps: api,
            query: { query: 'S', songLimit: 2, songStartIndex: 1 },
        })) as { songs: Array<{ name: string }> };

        expect(out.songs.map((s) => s.name)).toEqual(['S2', 'S3']);
    });
});

// ---------------------------------------------------------------------------
// Favorites — the item ledger
// ---------------------------------------------------------------------------

describe('getFavoriteAlbums / getFavoriteArtists (item-favorites ledger)', () => {
    it('returns the favorited rows, paginated with the full total', () => {
        const snapshot = snapshotOf(
            [songRow('trk_1', 'T1', 'A', 'v1'), songRow('trk_2', 'T2', 'B', 'v2')],
            { likedAlbumIds: new Set(['A Album|A']), likedArtistIds: new Set(['B']) },
        );

        const albums = getFavoriteAlbums(snapshot, 'streamify', { limit: 10, startIndex: 0 });
        expect(albums.totalRecordCount).toBe(1);
        expect(albums.items[0]?.name).toBe('A Album');
        expect(albums.items[0]?.userFavorite).toBe(true);

        const artists = getFavoriteArtists(snapshot, 'streamify', { limit: 10, startIndex: 0 });
        expect(artists.totalRecordCount).toBe(1);
        expect(artists.items[0]?.name).toBe('B');
        expect(artists.items[0]?.userFavorite).toBe(true);
    });

    it('the favorite list filter reads the ledger (getArtistList / getAlbumArtistList)', async () => {
        h.snapshot = snapshotOf(
            [songRow('trk_1', 'T1', 'A', 'v1'), songRow('trk_2', 'T2', 'B', 'v2')],
            { likedArtistIds: new Set(['A']) },
        );

        const page = (await StreamifyController.getArtistList({
            apiClientProps: api,
            query: {
                favorite: true,
                sortBy: ArtistListSort.NAME,
                sortOrder: SortOrder.ASC,
                startIndex: 0,
            },
        })) as { items: Array<{ name: string }>; totalRecordCount: number };

        expect(page.totalRecordCount).toBe(1);
        expect(page.items[0]?.name).toBe('A');
    });
});

// ---------------------------------------------------------------------------
// getStreamUrl — the ytm: id namespace
// ---------------------------------------------------------------------------

describe('getStreamUrl (ytm: ids)', () => {
    it('decodes ytm: ids straight to the resolver, bypassing the snapshot', async () => {
        h.snapshot = snapshotOf([]);
        const url = await StreamifyController.getStreamUrl({
            apiClientProps: api,
            query: { id: 'ytm:vid_remote', transcode: false },
        });
        expect(url).toBe('https://stream.test/vid_remote');
        expect(h.resolveCalls).toEqual(['vid_remote']);
    });

    it('snapshot ids keep the P2-A path (row → streamUrl)', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'T', 'A', 'vid_row')]);
        const url = await StreamifyController.getStreamUrl({
            apiClientProps: api,
            query: { id: 'trk_1', transcode: false },
        });
        expect(url).toBe('https://stream.test/vid_row');
    });
});

// ---------------------------------------------------------------------------
// getRandomSongList — the mood radio + automix seed
// ---------------------------------------------------------------------------

describe('getRandomSongList (mood radio & automix)', () => {
    it('a mood tile seeds the scoped-search radio; the playTally ledger filters repeats', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'T', 'A', 'v1')]);
        setMetadataProviderForTests(
            fakeProvider({
                getExplore: vi.fn(async () => ({
                    genres: [],
                    moods: [{ name: 'Workout', params: 'w0' }],
                })),
                getHomeFeed: vi.fn(async (): Promise<null | YtmHomeFeed> => ({ sections: [] })),
                getUpNext: vi.fn(async () => ({
                    songs: [songRef('v_mood', 'Mood Song', 'M'), songRef('seed_v', 'Seed', 'S')],
                })),
                search: vi.fn(
                    async (query: string): Promise<null | YtmSearchResults> => ({
                        albums: [],
                        artists: [],
                        playlists: [],
                        songs: query === 'Workout music' ? [songRef('seed_v', 'Seed', 'S')] : [],
                    }),
                ),
            }),
        );

        const page = (await StreamifyController.getRandomSongList({
            apiClientProps: api,
            query: { genre: 'ytm-mood:w0', limit: 10, played: Played.All },
        })) as { items: Array<{ id: string }>; totalRecordCount: number };

        expect(page.items.map((s) => s.id)).toEqual(['ytm:v_mood']);
        expect(page.totalRecordCount).toBe(1);
    });

    it('year filters stay the honest empty (no year data)', async () => {
        h.snapshot = snapshotOf([songRow('trk_1', 'T', 'A', 'v1')]);
        const page = (await StreamifyController.getRandomSongList({
            apiClientProps: api,
            query: { maxYear: 2020, played: Played.All },
        })) as { items: unknown[]; totalRecordCount: number };
        expect(page.items).toEqual([]);
        expect(page.totalRecordCount).toBe(0);
    });

    it('a provider miss keeps the seeded library shuffle (played filters included)', async () => {
        h.snapshot = snapshotOf([
            songRow('trk_1', 'T', 'A', 'v1'),
            songRow('trk_2', 'U', 'B', 'v2'),
        ]);
        const page = (await StreamifyController.getRandomSongList({
            apiClientProps: api,
            query: { limit: 2, played: Played.Never },
        })) as { items: Array<{ id: string }>; totalRecordCount: number };
        expect(page.totalRecordCount).toBe(2);
        expect(new Set(page.items.map((s) => s.id))).toEqual(new Set(['trk_1', 'trk_2']));
    });
});

// ---------------------------------------------------------------------------
// getStructuredLyrics through the controller (the feature-gated surface)
// ---------------------------------------------------------------------------

describe('getStructuredLyrics (controller surface)', () => {
    it('returns [] for an unknown song (honest miss, no throw)', async () => {
        h.snapshot = snapshotOf([]);
        const out =
            (await StreamifyController.getStructuredLyrics?.({
                apiClientProps: api,
                query: { songId: 'missing' },
            })) ?? [];
        expect(out).toEqual([]);
    });
});

// ---------------------------------------------------------------------------
// LibraryItem plumbing sanity (favorite type routing)
// ---------------------------------------------------------------------------

describe('favorite type routing (album/artist ledger)', () => {
    it('createFavorite for an album journals + flips the live snapshot set (offline session)', async () => {
        const snapshot = snapshotOf([songRow('trk_1', 'T', 'A', 'v1')]);
        h.snapshot = snapshot;

        await StreamifyController.createFavorite({
            apiClientProps: api,
            query: { id: ['A Album|A'], type: LibraryItem.ALBUM },
        });

        expect(snapshot.likedAlbumIds.has('A Album|A')).toBe(true);
    });
});
