/**
 * playlist-controller.test.ts - the endpoint round-trip lock (P3A).
 *
 * Full CRUD + track add/remove + replacePlaylist reorder through the real
 * StreamifyController, against the in-memory cloud simulator at the
 * PostgREST boundary (network-free by construction; the realtime lane and
 * the query client are module-mocked, the CRDT/sync engine runs for
 * real). This is the surface feishin's mutation hooks drive.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CloudSimulator } from './cloud-simulator';

import { createCloudSimulator } from './cloud-simulator';

const h = vi.hoisted(() => {
    return {
        cloud: null as CloudSimulator | null,
        snapshot: {
            loadedAt: '2026-11-16T00:00:00.000Z',
            songById: new Map(),
        },
    };
});

vi.mock('../playlist-sync/postgrest', async () => {
    const sim = await import('./cloud-simulator');
    return sim.createPostgrestMock(h);
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
    getStreamifyLibrary: vi.fn(async () => h.snapshot),
    invalidateStreamifyLibraryMemo: vi.fn(),
}));

// The controller's static graph reaches the store barrel + the router +
// the playback engine through streamify-supabase / stream-source; those
// are not under test here, so they are stubbed at the module boundary
// (network-free lane, same discipline as the P2-A suite).
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

import { resetOpMintForTests } from '../jam/jam-op';
import { PlaylistOutbox } from '../playlist-sync/outbox';
import { setPlaylistStorageBackendForTests } from '../playlist-sync/persistence';
import { encodeStreamifyCredential } from '../session';
import { StreamifyController } from '../streamify-controller';

import { setStreamifyPlaylistsState } from '/@/renderer/store/streamify-playlists.store';
import { ServerType } from '/@/shared/types/types';

/**
 * Fire the post-mutation debounce so the scheduled sync pass materializes
 * the local ops into the cloud simulator (the real flow: the 300ms
 * debounce runs, the pass executes, the tables converge).
 */
async function flushSync(): Promise<void> {
    await vi.advanceTimersByTimeAsync(500);
}

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

const api = { server: SERVER, serverId: 'streamify' };

function songRow(
    id: string,
    title: string,
): {
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
} {
    return {
        addedRank: 0,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 200,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: id,
        title,
    };
}

beforeEach(() => {
    vi.useFakeTimers();
    h.cloud = createCloudSimulator(['user-1']);
    h.snapshot = {
        loadedAt: '2026-11-16T00:00:00.000Z',
        songById: new Map(['t1', 't2', 't3', 't4'].map((id) => [id, songRow(id, `Title ${id}`)])),
    };
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

describe('StreamifyController — the P3A playlist surface', () => {
    it('createPlaylist -> getPlaylistList -> getPlaylistDetail round-trip', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { comment: 'Summer mix', name: 'Roadtrip', public: true },
        });
        expect(created?.id).toBeDefined();
        await flushSync();
        const list = await StreamifyController.getPlaylistList({
            apiClientProps: api,
            query: { sortBy: 'name' as never, sortOrder: 'ASC' as never, startIndex: 0 },
        });
        expect(list.totalRecordCount).toBe(1);
        expect(list.items[0]?.name).toBe('Roadtrip');
        expect(list.items[0]?.description).toBe('Summer mix');
        const detail = await StreamifyController.getPlaylistDetail({
            apiClientProps: api,
            query: { id: created!.id },
        });
        expect(detail.id).toBe(created!.id);
        expect(detail.songCount).toBe(0);
        const count = await StreamifyController.getPlaylistListCount({
            apiClientProps: api,
            query: {} as never,
        });
        expect(count).toBe(1);
        // The durable row materialized via the scheduled sync pass.
        expect(h.cloud?.playlists.has(created!.id)).toBe(true);
        expect(h.cloud?.playlists.get(created!.id)?.name).toBe('Roadtrip');
    });

    it('addToPlaylist -> getPlaylistSongList (ordered, playlistItemId set)', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Mix' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1', 't2', 't3'] },
            query: { id },
        });
        await flushSync();
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id },
        });
        expect(songs.items.map((song) => song.id)).toEqual(['t1', 't2', 't3']);
        expect(songs.items[0]?.playlistItemId).toBeDefined();
        // The packed row ids materialized with dense positions.
        expect(h.cloud?.tracks.size).toBe(3);
        const ids = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id },
        });
        expect(ids.items).toEqual(['t1', 't2', 't3']);
    });

    it('removeFromPlaylist: by song id AND by playlistItemId', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Mix' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1', 't2', 't3'] },
            query: { id },
        });
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id },
        });
        const middleItemId = songs.items[1]!.playlistItemId!;
        // Remove by the per-item id (the Navidrome-style lane).
        await StreamifyController.removeFromPlaylist({
            apiClientProps: api,
            query: { id, songId: [middleItemId] },
        });
        let after = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id },
        });
        expect(after.items).toEqual(['t1', 't3']);
        // Remove by the feishin song id (removes every element of it).
        await StreamifyController.removeFromPlaylist({
            apiClientProps: api,
            query: { id, songId: ['t1'] },
        });
        after = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id },
        });
        expect(after.items).toEqual(['t3']);
    });

    it('replacePlaylist reorders + adds + removes in one convergence', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Mix' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1', 't2', 't3'] },
            query: { id },
        });
        // Reverse the order and swap t2 for t4.
        await StreamifyController.replacePlaylist({
            apiClientProps: api,
            body: { songId: ['t3', 't4', 't1'] },
            query: { id },
        });
        await flushSync();
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id },
        });
        expect(songs.items.map((song) => song.id)).toEqual(['t3', 't4', 't1']);
        // setPlaylistSongs (the Navidrome verb) drives the same lane.
        await StreamifyController.setPlaylistSongs({
            apiClientProps: api,
            body: { id, songIds: ['t1', 't3'] },
        });
        await flushSync();
        const after = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id },
        });
        expect(after.items).toEqual(['t1', 't3']);
        // The table reflects the converged order (dense re-grid).
        const rows = Array.from(h.cloud!.tracks.values()).sort((a, b) => a.position - b.position);
        expect(rows.map((row) => row.track_id)).toEqual(['t1', 't3']);
        expect(rows.map((row) => row.position)).toEqual([0, 1]);
    });

    it('movePlaylistItem moves a single element between neighbours', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Mix' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1', 't2', 't3'] },
            query: { id },
        });
        // movePlaylistItem is optional on the controller contract; the
        // streamify implementation provides it (asserted non-null here).
        await StreamifyController.movePlaylistItem!({
            apiClientProps: api,
            query: { endingIndex: 0, playlistId: id, startingIndex: 2, trackId: 't3' },
        });
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id },
        });
        expect(songs.items.map((song) => song.id)).toEqual(['t3', 't1', 't2']);
    });

    it('updatePlaylist writes the metadata LWW register', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Old name' },
        });
        const id = created!.id;
        await StreamifyController.updatePlaylist({
            apiClientProps: api,
            body: { comment: 'New desc', name: 'New name', public: false },
            query: { id },
        });
        await flushSync();
        const detail = await StreamifyController.getPlaylistDetail({
            apiClientProps: api,
            query: { id },
        });
        expect(detail.name).toBe('New name');
        expect(detail.description).toBe('New desc');
        // The durable row carries the register clock in updated_at + the value.
        const row = h.cloud?.playlists.get(id);
        expect(row?.name).toBe('New name');
        expect(row?.is_public).toBe(false);
        expect(row?.updated_at).not.toBe('');
    });

    it('deletePlaylist tombstones: reads throw, the row is gone, and stale adds die', async () => {
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Doomed' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1'] },
            query: { id },
        });
        await flushSync();
        expect(h.cloud?.playlists.has(id)).toBe(true);
        await StreamifyController.deletePlaylist({ apiClientProps: api, query: { id } });
        await flushSync();
        await expect(
            StreamifyController.getPlaylistDetail({ apiClientProps: api, query: { id } }),
        ).rejects.toThrow('Playlist not found');
        // The sync pass re-issued the durable delete.
        expect(h.cloud?.playlists.has(id)).toBe(false);
        expect(h.cloud?.tracks.size).toBe(0);
        const list = await StreamifyController.getPlaylistList({
            apiClientProps: api,
            query: { sortBy: 'name' as never, sortOrder: 'ASC' as never, startIndex: 0 },
        });
        expect(list.totalRecordCount).toBe(0);
    });

    it('offline mutations survive: applied locally, flushed on reconnect', async () => {
        h.cloud!.setOffline(true);
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Offline mix' },
        });
        const id = created!.id;
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1', 't2'] },
            query: { id },
        });
        // The read gate serves the LOCAL replica (first sync failed but the
        // replica has content), so the offline state is fully usable.
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id },
        });
        expect(songs.items.map((song) => song.id)).toEqual(['t1', 't2']);
        expect(h.cloud?.playlists.size).toBe(0); // nothing materialized yet
        // Reconnect: the next read gate runs the pass and flushes (the
        // scheduled retry also fires under advanced timers).
        h.cloud!.setOffline(false);
        await vi.advanceTimersByTimeAsync(2_500);
        const after = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id },
        });
        expect(after.items).toEqual(['t1', 't2']);
        expect(h.cloud?.playlists.size).toBe(1);
        expect(h.cloud?.tracks.size).toBe(2);
    });

    it('legacy v4 rows from the web app ingest deterministically', async () => {
        // A playlist + rows exactly as the shipping web app writes them:
        // uuid_generate_v4() ids and integer positions. The read gate
        // itself performs the first sync (no mutation debounce needed).
        h.cloud!.playlists.set('11111111-2222-4333-8444-555555555555', {
            cover_url: null,
            created_at: '2026-11-01T00:00:00.000Z',
            description: null,
            id: '11111111-2222-4333-8444-555555555555',
            is_public: true,
            name: 'Legacy web playlist',
            updated_at: '2026-11-01T00:00:00.000Z',
            user_id: 'user-1',
        });
        h.cloud!.tracks.set('aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', {
            added_at: '2026-11-01T00:00:01.000Z',
            added_by: 'user-1',
            id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
            playlist_id: '11111111-2222-4333-8444-555555555555',
            position: 1,
            track_id: 't2',
        });
        h.cloud!.tracks.set('ffffffff-1111-4222-8333-444444444444', {
            added_at: '2026-11-01T00:00:02.000Z',
            added_by: 'user-1',
            id: 'ffffffff-1111-4222-8333-444444444444',
            playlist_id: '11111111-2222-4333-8444-555555555555',
            position: 0,
            track_id: 't1',
        });
        const songs = await StreamifyController.getPlaylistSongList({
            apiClientProps: api,
            query: { id: '11111111-2222-4333-8444-555555555555' },
        });
        // Position order (t1 at 0, t2 at 1) - the legacy ordering the
        // web app maintains - reads through the CRDT view.
        expect(songs.items.map((song) => song.id)).toEqual(['t1', 't2']);
        // CRDT mutations compose on top of legacy elements.
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t3'] },
            query: { id: '11111111-2222-4333-8444-555555555555' },
        });
        await flushSync();
        const after = await StreamifyController.getPlaylistSongIds({
            apiClientProps: api,
            query: { id: '11111111-2222-4333-8444-555555555555' },
        });
        expect(after.items).toEqual(['t1', 't2', 't3']);
        expect(h.cloud?.tracks.size).toBe(3);
    });

    it('query invalidation fires for local mutations (cache reactivity)', async () => {
        const { queryClient } = await import('/@/renderer/lib/react-query');
        const created = await StreamifyController.createPlaylist({
            apiClientProps: api,
            body: { name: 'Mix' },
        });
        await StreamifyController.addToPlaylist({
            apiClientProps: api,
            body: { songId: ['t1'] },
            query: { id: created!.id },
        });
        expect(queryClient.invalidateQueries).toHaveBeenCalled();
    });
});
