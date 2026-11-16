/**
 * playlist-sync-convergence.test.ts - the two-replica anti-entropy lock
 * (P3A).
 *
 * Two independent replicas (isolated store states + device ids) editing
 * against one shared cloud simulator, exactly the offline/concurrent
 * scenarios the task demands:
 *
 *   - concurrent track additions at the same index
 *   - reorder vs. remove (offline, both sides)
 *   - rename vs. rename (metadata LWW)
 *   - delete vs. edit (tombstones absorb stale updates)
 *   - offline op-log flush on reconnect + duplicate-flush idempotency
 *
 * Every scenario syncs BOTH replicas to quiescence and asserts identical
 * canonical state plus the converged durable rows. Clocks are pinned via
 * fake timers so op ids (and thus tie-breaks) are deterministic.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CloudSimulator } from './cloud-simulator';

import { createCloudSimulator } from './cloud-simulator';

const h = vi.hoisted(() => {
    return { cloud: null as CloudSimulator | null };
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

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import { resetOpMintForTests, seedOpCounter } from '../jam/jam-op';
import { canonicalDocState, orderedTrackIds } from '../jam/playlist-crdt';
import { PlaylistOutbox } from '../playlist-sync/outbox';
import { setPlaylistStorageBackendForTests } from '../playlist-sync/persistence';
import {
    addTracksToStreamifyPlaylist,
    createStreamifyPlaylist,
    deleteStreamifyPlaylist,
    ensureStreamifyPlaylistsSession,
    moveStreamifyPlaylistItem,
    removeTracksFromStreamifyPlaylist,
    runPlaylistSyncPassGuarded,
    updateStreamifyPlaylistMeta,
} from '../playlist-sync/sync';
import { encodeStreamifyCredential } from '../session';

import {
    getStreamifyPlaylistsState,
    setStreamifyPlaylistsState,
    type StreamifyPlaylistsSlice,
} from '/@/renderer/store/streamify-playlists.store';
import { ServerType } from '/@/shared/types/types';

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

/** Deterministic wall clock (fake timers): T0 + 10s, advanceable. */
const T0 = Date.parse('2026-11-16T12:00:00.000Z');
let clockMs = T0;
const tick = (ms: number): void => {
    clockMs += ms;
    vi.setSystemTime(clockMs);
};

interface Replica extends StreamifyPlaylistsSlice {
    label: string;
}

function makeReplica(label: string): Replica {
    return {
        deviceId: `device-${label}`,
        docs: new Map(),
        label,
        outbox: new PlaylistOutbox(),
        persistedRemote: new Set<string>(),
        session: null,
        syncedAt: null,
        syncing: false,
    };
}

let replicaA: Replica;
let replicaB: Replica;

/** Drive one replica's mutation (state swapped in and out around it). */
async function asReplica<T>(replica: Replica, action: () => Promise<T>): Promise<T> {
    swapIn(replica);
    try {
        return await action();
    } finally {
        swapOut(replica);
    }
}

/** Bind both replicas to the session once (shared account, two devices:
 * each binds from its own empty state, so each hydrates independently). */
async function bindReplicas(): Promise<void> {
    await asReplica(replicaA, () => ensureStreamifyPlaylistsSession(SERVER).then(() => undefined));
    await asReplica(replicaB, () => ensureStreamifyPlaylistsSession(SERVER).then(() => undefined));
}

function hashOf(input: string): number {
    let hash = 0x811c9dc5;
    for (let i = 0; i < input.length; i += 1) {
        hash ^= input.charCodeAt(i);
        hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash >>> 0;
}

function swapIn(replica: Replica): void {
    seedOpCounter(hashOf(replica.deviceId));
    setStreamifyPlaylistsState({
        deviceId: replica.deviceId,
        docs: replica.docs,
        outbox: replica.outbox,
        persistedRemote: replica.persistedRemote,
        session: replica.session,
        syncedAt: replica.syncedAt,
        syncing: false,
    });
}

function swapOut(replica: Replica): void {
    const state = getStreamifyPlaylistsState();
    replica.deviceId = state.deviceId;
    replica.docs = state.docs;
    replica.outbox = state.outbox;
    replica.persistedRemote = state.persistedRemote;
    replica.session = state.session;
    replica.syncedAt = state.syncedAt;
    replica.syncing = state.syncing;
}

/** Sync one replica once (state swapped around the pass). */
async function sync(replica: Replica): Promise<void> {
    await asReplica(replica, async () => {
        const session = getStreamifyPlaylistsState().session;
        if (session === null) {
            throw new Error(`test bug: replica ${replica.label} has no session`);
        }
        await runPlaylistSyncPassGuarded(session);
    });
}

/** Sync both replicas repeatedly until quiescent (max 3 rounds). */
async function syncToQuiescence(): Promise<void> {
    for (let round = 0; round < 3; round += 1) {
        await sync(replicaA);
        await sync(replicaB);
    }
}

beforeEach(() => {
    vi.useFakeTimers({ now: T0 });
    clockMs = T0;
    h.cloud = createCloudSimulator(['user-1']);
    replicaA = makeReplica('aaa');
    replicaB = makeReplica('bbb');
    setPlaylistStorageBackendForTests({
        getItem: () => null,
        removeItem: () => undefined,
        setItem: () => undefined,
    });
    resetOpMintForTests(BigInt(T0), 0);
    vi.clearAllMocks();
});

afterEach(() => {
    vi.useRealTimers();
});

describe('two-replica convergence through the durable tables', () => {
    it('concurrent additions at the same index converge (no phantom rows)', async () => {
        await bindReplicas();
        // A creates the playlist; both replicas see it empty.
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Roadtrip',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await sync(replicaA);
        await sync(replicaB);
        tick(1_000);
        // A adds offline; B adds offline into the SAME gap (both compute
        // fracAfter(empty) = 0.5).
        await asReplica(replicaA, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t1'], 'streamify'),
        );
        tick(1_000);
        await asReplica(replicaB, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t2'], 'streamify'),
        );
        await syncToQuiescence();

        const docA = replicaA.docs.get(playlistId)!;
        const docB = replicaB.docs.get(playlistId)!;
        expect(canonicalDocState(docA)).toBe(canonicalDocState(docB));
        // Both fracs are 0.5; the composite key tie-break orders by add-op
        // id: A minted at the earlier pinned ms, so t1 precedes t2.
        expect(orderedTrackIds(docA)).toEqual(['t1', 't2']);
        expect(orderedTrackIds(docB)).toEqual(['t1', 't2']);
        // Exactly two durable rows - no duplicates, no lost updates.
        expect(h.cloud!.tracks.size).toBe(2);
        const outboxEmpty = replicaA.outbox.size === 0 && replicaB.outbox.size === 0;
        expect(outboxEmpty).toBe(true);
    });

    it('reorder vs. remove (both offline): removal wins on both', async () => {
        await bindReplicas();
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Mix',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await asReplica(replicaA, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t1', 't2', 't3'], 'streamify'),
        );
        await syncToQuiescence();
        tick(1_000);
        // A moves t3 to the front (offline); B removes t3 (offline).
        await asReplica(replicaA, () =>
            moveStreamifyPlaylistItem(
                SERVER,
                playlistId,
                { endingIndex: 0, startingIndex: 2, trackId: 't3' },
                'streamify',
            ),
        );
        tick(1_000);
        await asReplica(replicaB, () =>
            removeTracksFromStreamifyPlaylist(SERVER, playlistId, ['t3'], 'streamify'),
        );
        await syncToQuiescence();

        const docA = replicaA.docs.get(playlistId)!;
        const docB = replicaB.docs.get(playlistId)!;
        expect(canonicalDocState(docA)).toBe(canonicalDocState(docB));
        expect(orderedTrackIds(docA)).toEqual(['t1', 't2']);
        expect(orderedTrackIds(docB)).toEqual(['t1', 't2']);
        expect(docA.elements.get(findElementByTrack(docA, 't3')!)?.removedAtOpId).not.toBeNull();
        expect(h.cloud!.tracks.size).toBe(2);
        // A's reorder row for t3 (new frac id) was block-deleted by B's
        // remove materialization - the block range covers EVERY frac.
        for (const row of h.cloud!.tracks.values()) {
            expect(row.track_id).not.toBe('t3');
        }
    });

    it('rename vs. rename: the later hybrid clock wins on both', async () => {
        await bindReplicas();
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Original',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await syncToQuiescence();
        tick(1_000);
        // A renames to "Alpha" (earlier clock)...
        await asReplica(replicaA, () =>
            updateStreamifyPlaylistMeta(
                SERVER,
                playlistId,
                { coverUrl: null, description: null, isPublic: true, name: 'Alpha' },
                'streamify',
            ),
        );
        tick(1_000);
        // ...B renames to "Beta" (later clock) offline.
        await asReplica(replicaB, () =>
            updateStreamifyPlaylistMeta(
                SERVER,
                playlistId,
                { coverUrl: null, description: null, isPublic: true, name: 'Beta' },
                'streamify',
            ),
        );
        await syncToQuiescence();

        expect(replicaA.docs.get(playlistId)!.meta.name).toBe('Beta');
        expect(replicaB.docs.get(playlistId)!.meta.name).toBe('Beta');
        expect(h.cloud!.playlists.get(playlistId)?.name).toBe('Beta');
    });

    it('delete vs. edit: the tombstone absorbs stale edits, no resurrection', async () => {
        await bindReplicas();
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Doomed',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await asReplica(replicaA, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t1'], 'streamify'),
        );
        await syncToQuiescence();
        tick(1_000);
        // A deletes (offline); B renames + adds a track (offline).
        await asReplica(replicaA, () => deleteStreamifyPlaylist(SERVER, playlistId, 'streamify'));
        tick(1_000);
        await asReplica(replicaB, async () => {
            await updateStreamifyPlaylistMeta(
                SERVER,
                playlistId,
                { coverUrl: null, description: null, isPublic: true, name: 'Zombie' },
                'streamify',
            );
            await addTracksToStreamifyPlaylist(SERVER, playlistId, ['t2'], 'streamify');
        });
        await syncToQuiescence();

        const docA = replicaA.docs.get(playlistId)!;
        const docB = replicaB.docs.get(playlistId)!;
        expect(docA.deletedAtOpId).not.toBeNull();
        expect(docB.deletedAtOpId).not.toBeNull();
        // The durable row is gone and nothing resurrected it (B's stale
        // PATCH no-ops on the missing row; its FK-dead insert never runs).
        expect(h.cloud!.playlists.has(playlistId)).toBe(false);
        expect(h.cloud!.tracks.size).toBe(0);
    });

    it('offline op-log flush on reconnect + duplicate flush idempotency', async () => {
        await bindReplicas();
        // Offline: create + populate entirely from replica A.
        h.cloud!.setOffline(true);
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Offline mix',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await asReplica(replicaA, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t1', 't2', 't3'], 'streamify'),
        );
        // The ops sit in the pending log; nothing is durable.
        expect(replicaA.outbox.size).toBe(3);
        expect(h.cloud!.playlists.size).toBe(0);
        // A failed pass keeps them pending (offline error propagates).
        await expect(sync(replicaA)).rejects.toThrow('simulated offline');
        expect(replicaA.outbox.size).toBe(3);
        // Reconnect: the pass flushes the whole log.
        h.cloud!.setOffline(false);
        await sync(replicaA);
        expect(replicaA.outbox.size).toBe(0);
        expect(h.cloud!.playlists.size).toBe(1);
        expect(h.cloud!.tracks.size).toBe(3);
        // Repeated syncs are idempotent (no duplicate rows, no changes).
        const before = canonicalDocState(replicaA.docs.get(playlistId)!);
        await sync(replicaA);
        await sync(replicaA);
        expect(h.cloud!.tracks.size).toBe(3);
        expect(canonicalDocState(replicaA.docs.get(playlistId)!)).toBe(before);
        // B comes online later and adopts the full state.
        await sync(replicaB);
        expect(canonicalDocState(replicaB.docs.get(playlistId)!)).toBe(before);
    });

    it('remote removals propagate: a confirmed-then-vanished row tombstones', async () => {
        await bindReplicas();
        const created = await asReplica(replicaA, () =>
            createStreamifyPlaylist(SERVER, {
                description: null,
                isPublic: true,
                name: 'Shared',
                serverId: 'streamify',
            }),
        );
        const playlistId = created.id;
        await asReplica(replicaA, () =>
            addTracksToStreamifyPlaylist(SERVER, playlistId, ['t1', 't2'], 'streamify'),
        );
        await syncToQuiescence();
        // B (through the legacy web lane, i.e. directly on the table)
        // removes t1: the row vanishes without any CRDT op.
        for (const [rowId, row] of h.cloud!.tracks) {
            if (row.track_id === 't1') {
                h.cloud!.tracks.delete(rowId);
            }
        }
        // A's next sync infers the removal from the missing row and
        // tombstones locally.
        await sync(replicaA);
        expect(orderedTrackIds(replicaA.docs.get(playlistId)!)).toEqual(['t2']);
        // B sees the same (its own view already excluded t1's row).
        await sync(replicaB);
        expect(canonicalDocState(replicaB.docs.get(playlistId)!)).toBe(
            canonicalDocState(replicaA.docs.get(playlistId)!),
        );
    });
});

function findElementByTrack(
    doc: { elements: Map<bigint, { trackId: string }> },
    trackId: string,
): bigint | undefined {
    for (const [addOpId, element] of doc.elements) {
        if (element.trackId === trackId) {
            return addOpId;
        }
    }
    return undefined;
}
