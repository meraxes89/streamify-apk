/**
 * outbox-persistence.test.ts - the offline resilience lock (P3A):
 * persistence hydration, pending op-log ordering + dedupe, duplicate op
 * idempotency at the queue level, and the storage-backend injection the
 * network-free test lane needs.
 */

import { beforeEach, describe, expect, it } from 'vitest';

import { jamOpNew, jamOpToBytes, OP_ADD, OP_REMOVE } from '../jam/jam-op';
import { applyPlaylistOp, canonicalDocState, createPlaylistDoc } from '../jam/playlist-crdt';
import { outboxFromJSON, PlaylistOutbox } from '../playlist-sync/outbox';
import {
    clearPlaylistState,
    getPlaylistDeviceId,
    loadPlaylistState,
    playlistStorage,
    type PlaylistStorageBackend,
    savePlaylistState,
    setPlaylistStorageBackendForTests,
} from '../playlist-sync/persistence';

class MemoryBackend implements PlaylistStorageBackend {
    map = new Map<string, string>();
    getItem(key: string): null | string {
        return this.map.get(key) ?? null;
    }
    removeItem(key: string): void {
        this.map.delete(key);
    }
    setItem(key: string, value: string): void {
        this.map.set(key, value);
    }
}

const backend = new MemoryBackend();

beforeEach(() => {
    backend.map.clear();
    setPlaylistStorageBackendForTests(backend);
});

describe('outbox — the pending op-log', () => {
    it('enqueues in mint order, dedupes by op id, and filters per playlist', () => {
        const outbox = new PlaylistOutbox();
        const rowOf = (opId: bigint) => JSON.stringify([opId.toString(10)]);
        void rowOf;
        const mk = (opId: bigint, playlistId: string, opType: number) => ({
            opId: opId.toString(10),
            opType,
            playlistId,
            row: `row-${opId.toString(10)}`,
            trackId: opType === OP_ADD ? 'track-1' : null,
        });
        outbox.enqueue([mk(10n, 'pl-1', OP_ADD), mk(11n, 'pl-1', OP_ADD), mk(12n, 'pl-2', OP_ADD)]);
        expect(outbox.size).toBe(3);
        // Duplicate op id: dropped.
        expect(outbox.enqueue([mk(10n, 'pl-1', OP_ADD)])).toBe(0);
        expect(outbox.size).toBe(3);
        // Per-playlist FIFO by op id.
        const pending1 = outbox.pendingFor('pl-1');
        expect(pending1.map((entry) => entry.opId)).toEqual(['10', '11']);
        // Out-of-order enqueue still flushes in op-id order.
        outbox.enqueue([mk(9n, 'pl-1', OP_ADD)]);
        expect(outbox.pendingFor('pl-1').map((entry) => entry.opId)).toEqual(['9', '10', '11']);
        // Pending Add ids are the sync pass's delivery disambiguator
        // (membership-checked; iteration order is insertion order).
        const pendingAdds = outbox.pendingAddOpIds('pl-1');
        expect(pendingAdds.has('9')).toBe(true);
        expect(pendingAdds.has('10')).toBe(true);
        expect(pendingAdds.has('11')).toBe(true);
        expect(pendingAdds.size).toBe(3);
        expect(outbox.hasPending('pl-2')).toBe(true);
        // clearFor drops exactly that playlist's ops.
        outbox.clearFor('pl-1');
        expect(outbox.size).toBe(1);
        expect(outbox.hasPending('pl-1')).toBe(false);
    });

    it('hydrates from JSON defensively (corrupt rows dropped, never fatal)', () => {
        const good = {
            opId: '42',
            opType: OP_REMOVE,
            playlistId: 'pl-1',
            row: 'row-42',
            trackId: null,
        };
        const hydrated = outboxFromJSON([
            good,
            { opId: 'zzz', opType: 1, playlistId: 'pl-1', row: 'x' }, // bad op id
            { opId: '43', playlistId: 'pl-1', row: 'x' }, // missing opType
            null, // hostile
        ]);
        expect(hydrated.size).toBe(1);
        expect(hydrated.all()[0]?.opId).toBe('42');
        expect(outboxFromJSON('not-an-array').size).toBe(0);
        expect(outboxFromJSON(undefined).size).toBe(0);
    });
});

describe('persistence — CRDT state + outbox survive restarts', () => {
    it('round-trips docs, outbox, and the persistedRemote guard per user', () => {
        const doc = createPlaylistDoc(
            'pl-1',
            { coverUrl: null, description: 'desc', isPublic: false, name: 'Mix' },
            1000n << 16n,
            '2026-11-16T00:00:00.000Z',
        );
        const op = jamOpNew((1001n << 16n) | 5n, new Uint8Array(4), OP_ADD, 0, 7n, 0.5, 0n);
        applyPlaylistOp(doc, { op, trackId: 'track-9' });
        const outbox = new PlaylistOutbox();
        outbox.enqueue([
            {
                opId: op.opId.toString(10),
                opType: OP_ADD,
                playlistId: 'pl-1',
                row: Buffer.from(jamOpToBytes(op)).toString('base64'),
                trackId: 'track-9',
            },
        ]);
        const persistedRemote = new Set(['pl-0']);
        expect(savePlaylistState('user-1', new Map([['pl-1', doc]]), outbox, persistedRemote)).toBe(
            true,
        );

        const hydrated = loadPlaylistState('user-1');
        expect(hydrated.docs.size).toBe(1);
        expect(canonicalDocState(hydrated.docs.get('pl-1')!)).toBe(canonicalDocState(doc));
        expect(hydrated.outbox.size).toBe(1);
        expect(hydrated.outbox.pendingFor('pl-1')[0]?.trackId).toBe('track-9');
        expect(Array.from(hydrated.persistedRemote)).toEqual(['pl-0']);

        // Other users are isolated; clear drops the state.
        expect(loadPlaylistState('user-2').docs.size).toBe(0);
        clearPlaylistState('user-1');
        expect(loadPlaylistState('user-1').docs.size).toBe(0);
        expect(playlistStorage().getItem('streamify.p3a.playlists.user-1')).toBeNull();
    });

    it('never throws on unreadable payloads (fresh state answers)', () => {
        backend.setItem('streamify.p3a.playlists.user-x', '{not json');
        const hydrated = loadPlaylistState('user-x');
        expect(hydrated.docs.size).toBe(0);
        backend.setItem(
            'streamify.p3a.playlists.user-x',
            JSON.stringify({ docs: [], outbox: [], v: 99 }),
        );
        expect(loadPlaylistState('user-x').docs.size).toBe(0); // wrong version
    });

    it('mints a stable device id once', () => {
        const first = getPlaylistDeviceId();
        expect(first.startsWith('dev-')).toBe(true);
        expect(getPlaylistDeviceId()).toBe(first);
        // Persisted across backend resets of the memo only.
        setPlaylistStorageBackendForTests(backend);
        expect(getPlaylistDeviceId()).toBe(first);
    });
});
