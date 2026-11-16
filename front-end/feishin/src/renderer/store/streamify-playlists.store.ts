/**
 * store/streamify-playlists.store.ts - the Jam CRDT playlist replica state
 * (P3A).
 *
 * This store is the single in-memory holder of the streamify playlist CRDT
 * replica: the per-playlist docs, the pending op-log (outbox), the session
 * binding, and the remote-confirmation bookkeeping the anti-entropy pass
 * (api/streamify/playlist-sync/sync.ts) maintains. It is deliberately DUMB
 * state - every transition lives in the sync module so the convergence
 * logic stays in one auditable place. No UI component subscribes to it:
 * reactivity flows through TanStack Query invalidation (the controller
 * reads + the sync pass + the realtime lane all invalidate the playlist
 * query keys), which is why the store does not need actions of its own.
 *
 * Persistence is handled by playlist-sync/persistence.ts (localStorage,
 * per-user keys, bigint-safe serialization) - NOT zustand persist, because
 * the outbox is a class with op-id dedupe and the docs are Map<bigint, _>.
 */

import type { PlaylistCrdtDoc } from '/@/renderer/api/streamify/jam/playlist-crdt';
import type { StreamifySession } from '/@/renderer/api/streamify/session';

import { create } from 'zustand';

import { PlaylistOutbox } from '/@/renderer/api/streamify/playlist-sync/outbox';

export interface StreamifyPlaylistsSlice {
    /** Stable per-install device id (the op sender identity). */
    deviceId: string;
    /** The CRDT docs keyed by playlist id (live + tombstoned). */
    docs: Map<string, PlaylistCrdtDoc>;
    /** The pending op-log (offline resilience). */
    outbox: PlaylistOutbox;
    /** Playlist ids confirmed present in the durable table at least once -
     * the guard that separates "not yet flushed" from "deleted remotely". */
    persistedRemote: Set<string>;
    /** The session the replica is bound to (null before the first sign-in). */
    session: null | StreamifySession;
    /** Epoch ms of the last completed sync pass (null = never synced). */
    syncedAt: null | number;
    /** True while a sync pass is in flight. */
    syncing: boolean;
}

export const useStreamifyPlaylistsStoreBase = create<StreamifyPlaylistsSlice>(() => ({
    deviceId: '',
    docs: new Map(),
    outbox: new PlaylistOutbox(),
    persistedRemote: new Set(),
    session: null,
    syncedAt: null,
    syncing: false,
}));

/** Snapshot read (the controller + sync module entry point). */
export function getStreamifyPlaylistsState(): StreamifyPlaylistsSlice {
    return useStreamifyPlaylistsStoreBase.getState();
}

/** Partial write (sync-module transitions only). */
export function setStreamifyPlaylistsState(partial: Partial<StreamifyPlaylistsSlice>): void {
    useStreamifyPlaylistsStoreBase.setState(partial);
}
