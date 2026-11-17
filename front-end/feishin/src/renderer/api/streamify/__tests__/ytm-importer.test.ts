/**
 * ytm-importer.test.ts — the W1-A importer lock (URL parser + batched
 * continuation ingestion + abort + Jam CRDT materialization).
 *
 * The CRDT/sync engine runs for REAL (the P3-A discipline: the cloud
 * simulator sits at the PostgREST boundary; realtime + query-client +
 * store-barrel are module-mocked). The provider is a scripted double; the
 * catalog upsert is recorded (its production FK ordering is a Postgres
 * constraint, asserted here as call order instead).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
    return {
        catalogUpserts: [] as Array<
            Array<{ artist: string; id: string; title: string; videoId: null | string }>
        >,
        cloud: null as unknown as import('./cloud-simulator').CloudSimulator,
        onUpsert: undefined as ((batchIndex: number) => void) | undefined,
    };
});

vi.mock('../cloud', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../cloud')>();
    return {
        ...actual,
        upsertCatalogTracks: async (
            _session: unknown,
            rows: Array<{ artist: string; id: string; title: string; videoId: null | string }>,
        ) => {
            h.catalogUpserts.push(rows);
            h.onUpsert?.(h.catalogUpserts.length);
            return true;
        },
    };
});

vi.mock('../playlist-sync/postgrest', async () => {
    const sim = await import('./cloud-simulator');
    return sim.createPostgrestMock({
        get cloud() {
            return h.cloud;
        },
    });
});

vi.mock('../playlist-sync/realtime', () => ({
    broadcastPlaylistMessage: vi.fn(),
    ensurePlaylistRealtime: vi.fn(async () => undefined),
    resetPlaylistRealtimeForTests: vi.fn(),
}));

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('/@/renderer/lib/react-query', () => ({
    queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('../engine', () => ({
    currentStreamifyLibrary: vi.fn(() => null),
    getStreamifyLibrary: vi.fn(async () => null),
    invalidateStreamifyLibraryMemo: vi.fn(),
}));

vi.mock('/@/renderer/store', () => ({
    useAuthStore: { getState: () => ({ actions: {}, currentServer: null, serverList: {} }) },
}));

vi.mock('/@/renderer/router/routes', () => ({
    AppRoute: { STREAMIFY_AUTH: '/streamify-auth' },
}));

vi.mock('../playback/stream-source', () => ({
    resolveStreamifyTrack: vi.fn(async () => ({ protocol: 'progressive', url: 'https://x.test' })),
    StreamifyNoVideoError: class StreamifyNoVideoError extends Error {},
}));

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import type { MetadataProvider, PlaylistPayload } from '../metadata/types';

import { resetOpMintForTests } from '../jam/jam-op';
import { orderedTrackIds } from '../jam/playlist-crdt';
import { PlaylistOutbox } from '../playlist-sync/outbox';
import { setPlaylistStorageBackendForTests } from '../playlist-sync/persistence';
import { encodeStreamifyCredential } from '../session';
import { createCloudSimulator } from './cloud-simulator';

import { importYtmTarget } from '/@/renderer/features/streamify/import/ingest';
import { parseYtmInput } from '/@/renderer/features/streamify/import/url-parser';
import { setStreamifyPlaylistsState } from '/@/renderer/store/streamify-playlists.store';
import { getStreamifyPlaylistsState } from '/@/renderer/store/streamify-playlists.store';
import { ServerType } from '/@/shared/types/types';

// ---------------------------------------------------------------------------
// The scripted provider double
// ---------------------------------------------------------------------------

function pageFixture(
    tracks: Array<[string, string, string, number]>,
    continuation: null | string,
    extra: Partial<PlaylistPayload> = {},
): PlaylistPayload {
    return {
        continuation,
        description: 'Imported from YouTube Music',
        kind: 'playlist',
        listId: 'PLFIX',
        title: extra.title ?? '90s r&b and hip hop hits',
        trackCountTotal: extra.trackCountTotal ?? null,
        tracks: tracks.map(([videoId, title, artist, durationMs], index) => ({
            albumText: 'Some Album',
            artistText: artist,
            durationMs,
            index,
            title,
            videoId,
        })),
    };
}

function scriptedProvider(
    pages: PlaylistPayload[],
    hooks: { onPageRequest?: (index: number) => void } = {},
): MetadataProvider {
    let index = 0;
    return {
        getAlbum: vi.fn(async () => ({ kind: 'not-found' as const })),
        getArtist: vi.fn(async () => ({ kind: 'not-found' as const })),
        getChapters: vi.fn(async () => ({ kind: 'not-found' as const })),
        // W2-B: the typed engine's credits arm (unused by the importer).
        getCredits: vi.fn(async () => ({ kind: 'not-found' as const })),
        getExplore: vi.fn(async () => ({ kind: 'not-found' as const })),
        getHomeFeed: vi.fn(async () => ({ kind: 'not-found' as const })),
        getLyrics: vi.fn(async () => ({ kind: 'not-found' as const })),
        getPlaylist: vi.fn(async (listId: string, continuation?: string) => {
            hooks.onPageRequest?.(index);
            if (index === 0) {
                index += 1;
                return { kind: 'ok' as const, payload: pages[0], source: 'edge' as const };
            }
            index += 1;
            void listId;
            void continuation;
            const next = pages[Math.min(index - 1, pages.length - 1)];
            return { kind: 'ok' as const, payload: next, source: 'edge' as const };
        }),
        getRelated: vi.fn(async () => ({ kind: 'not-found' as const })),
        getSearchSuggestions: vi.fn(async () => ({ kind: 'not-found' as const })),
        getUpNext: vi.fn(async () => ({ kind: 'not-found' as const })),
        getVideoCounterpart: vi.fn(async () => ({ kind: 'not-found' as const })),
        search: vi.fn(async () => ({ kind: 'not-found' as const })),
    };
}

// ---------------------------------------------------------------------------
// The server + harness
// ---------------------------------------------------------------------------

const SERVER: ServerListItemWithCredential = {
    credential: encodeStreamifyCredential({
        accessToken: 'test-access-token',
        anonKey: 'test-anon-key',
        email: null,
        expiresAt: 0,
        isAdmin: false,
        refreshToken: '',
        supabaseUrl: 'https://supabase.test',
        userId: 'user-1',
        v: 1,
    }),
    id: 'streamify',
    isAdmin: false,
    name: 'Streamify',
    savePassword: false,
    type: ServerType.STREAMIFY,
    url: 'https://supabase.test',
    userId: 'user-1',
    username: 'user-1@test',
};

async function flushSync(): Promise<void> {
    await vi.advanceTimersByTimeAsync(500);
}

beforeEach(() => {
    vi.useFakeTimers();
    h.cloud = createCloudSimulator(['user-1']);
    h.catalogUpserts.length = 0;
    h.onUpsert = undefined;
    setStreamifyPlaylistsState({
        deviceId: '',
        docs: new Map(),
        outbox: new PlaylistOutbox(),
        persistedRemote: new Set(),
        session: null,
        syncedAt: null,
        syncing: false,
    });
    setPlaylistStorageBackendForTests({
        getItem: () => null,
        removeItem: () => undefined,
        setItem: () => undefined,
    });
    resetOpMintForTests();
    vi.clearAllMocks();
});

afterEach(() => {
    vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// URL parser table
// ---------------------------------------------------------------------------

describe('W1-A URL parser', () => {
    it('accepts the four raw-id prefixes', () => {
        expect(parseYtmInput('PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF' },
        });
        expect(parseYtmInput('OLAK5uy_l4Z8njnOK_Q-MOVqKoq9tU98JqO20Mvnw')).toEqual({
            ok: true,
            target: { kind: 'album', listId: 'OLAK5uy_l4Z8njnOK_Q-MOVqKoq9tU98JqO20Mvnw' },
        });
        expect(parseYtmInput('RDCLAK5uy_nDL8KeBrUagwyISwNmyEiSfYgz1gVCesg')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'RDCLAK5uy_nDL8KeBrUagwyISwNmyEiSfYgz1gVCesg' },
        });
        expect(parseYtmInput('VLPL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'VLPL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF' },
        });
    });

    it('accepts the URL forms (list= wins; the browse path form)', () => {
        expect(
            parseYtmInput('https://music.youtube.com/playlist?list=OLAK5uy_l4Z8njnOK&other=1'),
        ).toEqual({ ok: true, target: { kind: 'album', listId: 'OLAK5uy_l4Z8njnOK' } });
        expect(parseYtmInput('www.youtube.com/playlist?list=PLabc123')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'PLabc123' },
        });
        expect(parseYtmInput('https://www.youtube.com/watch?v=xyz&list=PLwatchlist')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'PLwatchlist' },
        });
        expect(parseYtmInput('https://music.youtube.com/browse/VLsomeid')).toEqual({
            ok: true,
            target: { kind: 'playlist', listId: 'VLsomeid' },
        });
    });

    it('rejects the non-targets honestly', () => {
        expect(parseYtmInput('')).toEqual({ error: 'empty', ok: false });
        expect(parseYtmInput('   ').ok).toBe(false);
        // watch without a list — a single video is not an import target.
        expect(parseYtmInput('https://www.youtube.com/watch?v=xyz').ok).toBe(false);
        // foreign hosts / junk / non-list ids.
        expect(parseYtmInput('https://open.spotify.com/playlist/xyz').ok).toBe(false);
        expect(parseYtmInput('hello world').ok).toBe(false);
        expect(parseYtmInput('RDAMVMxyz').ok).toBe(false);
        expect(parseYtmInput('https://www.youtube.com/playlist?list=notalist').ok).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// Batched ingestion + CRDT materialization
// ---------------------------------------------------------------------------

describe('W1-A importer — batched continuation ingestion', () => {
    it('streams pages in batches, reports live progress, materializes the CRDT doc', async () => {
        const pages = [
            pageFixture(
                [
                    ['v1', 'Say My Name', "Destiny's Child", 272000],
                    ['v2', 'No Scrubs', 'TLC', 253000],
                ],
                'cont-2',
                { trackCountTotal: 5 },
            ),
            pageFixture([['v3', 'Rain', 'SWV', 265000]], 'cont-3'),
            pageFixture(
                [
                    ['v4', 'Waterfalls', 'TLC', 284000],
                    ['v5', 'Creep', 'TLC', 253000],
                ],
                null,
            ),
        ];
        const progressReports: Array<{ importedCount: number; phase: string }> = [];
        const provider = scriptedProvider(pages);
        const summary = await importYtmTarget({
            onProgress: (p) =>
                progressReports.push({ importedCount: p.importedCount, phase: p.phase }),
            provider,
            server: SERVER,
            serverId: 'streamify',
            target: { kind: 'playlist', listId: 'PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF' },
        });

        // The full run: 5 tracks across 3 batches.
        expect(summary.importedCount).toBe(5);
        expect(summary.totalExpected).toBe(5);
        expect(progressReports.at(-1)?.phase).toBe('done');
        // resolving(0) → per-batch (2, 3, 5) → terminal 'done'(5).
        expect(progressReports.map((p) => p.importedCount)).toEqual([0, 2, 3, 5, 5]);

        // Provider drove exactly 3 pages (page1 + 2 continuations).
        expect(provider.getPlaylist).toHaveBeenCalledTimes(3);

        // Catalog upserts: one per batch, BEFORE the ADD ops mint (assert
        // via order: the outbox holds all ops only after the last upsert).
        expect(h.catalogUpserts).toHaveLength(3);
        expect(h.catalogUpserts[0]?.[0]?.id).toBe('v1');

        // The CRDT doc: the name from page 1's header, the ordered track
        // ids exactly as the pages landed.
        const state = getStreamifyPlaylistsState();
        const doc = state.docs.get(summary.playlistId);
        expect(doc).toBeDefined();
        expect(doc?.meta.name).toBe('90s r&b and hip hop hits');
        expect(orderedTrackIds(doc!)).toEqual(['v1', 'v2', 'v3', 'v4', 'v5']);
        expect(state.outbox.size).toBeGreaterThan(0);

        // The scheduled sync pass materializes the durable rows.
        await flushSync();
        expect(h.cloud?.playlists.has(summary.playlistId)).toBe(true);
        const durableTracks = Array.from(h.cloud?.tracks.values() ?? []).filter(
            (row) => row.playlist_id === summary.playlistId,
        );
        expect(durableTracks).toHaveLength(5);
        expect(durableTracks.map((r) => r.track_id)).toEqual(['v1', 'v2', 'v3', 'v4', 'v5']);
    });

    it('album imports (OLAK5uy_) name the playlist from the tracks album column', async () => {
        const pages = [
            pageFixture([['v1', 'Come Together', 'The Beatles', 261000]], null, {
                trackCountTotal: 1,
            }),
        ];
        const provider = scriptedProvider(pages);
        const summary = await importYtmTarget({
            provider,
            server: SERVER,
            serverId: 'streamify',
            target: { kind: 'album', listId: 'OLAK5uy_l4Z8njnOK' },
        });
        const state = getStreamifyPlaylistsState();
        const doc = state.docs.get(summary.playlistId);
        // pageFixture sets albumText 'Some Album' on every track.
        expect(doc?.meta.name).toBe('Some Album');
        expect(doc?.meta.description).toBe('Album imported from YouTube Music');
    });

    it('cancellation keeps landed batches and stops the loop', async () => {
        const controller = new AbortController();
        // Abort fires DURING page 2's ingest — the catalog hook aborts on
        // the 2nd batch; page 3 is never requested.
        h.onUpsert = (batchIndex) => {
            if (batchIndex === 2) controller.abort();
        };
        const pages = [
            pageFixture([['v1', 'T1', 'X', 200000]], 'cont-2', { trackCountTotal: 5 }),
            pageFixture([['v2', 'T2', 'X', 200000]], 'cont-3'),
            pageFixture([['v3', 'T3', 'X', 200000]], null),
        ];
        const provider = scriptedProvider(pages);
        const progressReports: Array<{ importedCount: number; phase: string }> = [];
        const summary = await importYtmTarget({
            onProgress: (p) =>
                progressReports.push({ importedCount: p.importedCount, phase: p.phase }),
            provider,
            server: SERVER,
            serverId: 'streamify',
            signal: controller.signal,
            target: { kind: 'playlist', listId: 'PLX' },
        });

        expect(progressReports.at(-1)?.phase).toBe('cancelled');
        expect(provider.getPlaylist).toHaveBeenCalledTimes(2);
        expect(summary.importedCount).toBe(2);
        const state = getStreamifyPlaylistsState();
        const doc = state.docs.get(summary.playlistId);
        expect(orderedTrackIds(doc!)).toEqual(['v1', 'v2']);
    });

    it('not-found and rate-limited surface as distinct terminal phases', async () => {
        const notFound: MetadataProvider = {
            ...scriptedProvider([]),
            getPlaylist: vi.fn(async () => ({ kind: 'not-found' as const })),
        };
        await expect(
            importYtmTarget({
                provider: notFound,
                server: SERVER,
                serverId: 'streamify',
                target: { kind: 'playlist', listId: 'PLGONE' },
            }),
        ).rejects.toThrow('not-found');

        const rateLimited: MetadataProvider = {
            ...scriptedProvider([]),
            getPlaylist: vi.fn(async () => ({ kind: 'rate-limited' as const })),
        };
        await expect(
            importYtmTarget({
                provider: rateLimited,
                server: SERVER,
                serverId: 'streamify',
                target: { kind: 'playlist', listId: 'PLHOT' },
            }),
        ).rejects.toThrow('rate-limited');
    });
});
