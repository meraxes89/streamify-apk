/**
 * streamify/playlist-sync/persistence.ts - local persistence for the CRDT
 * state + the pending op-log (P3A).
 *
 * THE OFFLINE RESILIENCE REQUIREMENT: "Persist CRDT state + pending op-log
 * locally (IndexedDB/localStorage) and flush/merge automatically on
 * reconnect, sign-in, and incoming Supabase realtime events."
 *
 * Storage: localStorage (feishin is an Electron renderer with synchronous,
 * reliable localStorage; the playlist state is a few hundred bytes per
 * playlist). The backend is injectable so the network-free test lane can
 * run in node (vitest 'node' environment has no localStorage).
 *
 * Keys are per-user: switching accounts hydrates the other user's replica
 * state without cross-contamination.
 */

import {
    deserializePlaylistDoc,
    type PlaylistCrdtDoc,
    serializePlaylistDoc,
} from '../jam/playlist-crdt';
import { type OutboxEntry, outboxFromJSON, PlaylistOutbox } from './outbox';

const STATE_KEY_PREFIX = 'streamify.p3a.playlists.';
const DEVICE_KEY = 'streamify.p3a.device';

/** The minimal storage surface both localStorage and tests implement. */
export interface PlaylistStorageBackend {
    getItem(key: string): null | string;
    removeItem(key: string): void;
    setItem(key: string, value: string): void;
}

class MemoryStorageBackend implements PlaylistStorageBackend {
    private readonly map = new Map<string, string>();

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

let backend: null | PlaylistStorageBackend = null;

export interface HydratedPlaylistState {
    docs: Map<string, PlaylistCrdtDoc>;
    outbox: PlaylistOutbox;
    persistedRemote: Set<string>;
    userId: string;
}

/** The persisted shape (u64s are decimal strings inside the serialized doc). */
export interface PersistedPlaylistState {
    docs: Array<ReturnType<typeof serializePlaylistDoc>>;
    outbox: OutboxEntry[];
    /** Playlist ids confirmed durable at least once - the delete-inference
     * guard must survive restarts or a rebooted replica resurrects remotely
     * deleted playlists on its first sync. */
    persistedRemote: string[];
    savedAt: string;
    userId: string;
    v: 1;
}

/** Drop the persisted state for a user (sign-out hygiene). */
export function clearPlaylistState(userId: string): void {
    try {
        playlistStorage().removeItem(STATE_KEY_PREFIX + userId);
    } catch {
        // removal is best-effort
    }
}

/** Hydrate the persisted replica state for a user (empty when absent). */
export function loadPlaylistState(userId: string): HydratedPlaylistState {
    const docs = new Map<string, PlaylistCrdtDoc>();
    const outbox = new PlaylistOutbox();
    const persistedRemote = new Set<string>();
    let raw: null | string = null;
    try {
        raw = playlistStorage().getItem(STATE_KEY_PREFIX + userId);
    } catch {
        raw = null;
    }
    if (raw === null) {
        return { docs, outbox, persistedRemote, userId };
    }
    try {
        const parsed = JSON.parse(raw) as Partial<PersistedPlaylistState>;
        if (parsed.v !== 1 || parsed.userId !== userId || !Array.isArray(parsed.docs)) {
            return { docs, outbox, persistedRemote, userId };
        }
        for (const serialized of parsed.docs) {
            const doc = deserializePlaylistDoc(serialized);
            if (doc !== null) {
                docs.set(doc.id, doc);
            }
        }
        outbox.replace(outboxFromJSON(parsed.outbox).all());
        if (Array.isArray(parsed.persistedRemote)) {
            for (const id of parsed.persistedRemote) {
                if (typeof id === 'string') {
                    persistedRemote.add(id);
                }
            }
        }
        return { docs, outbox, persistedRemote, userId };
    } catch {
        return { docs, outbox, persistedRemote, userId };
    }
}

/** P4-A (/debug): the savedAt of the persisted state for a user — the
 * playlist lane's last-flush-to-local timestamp. null when never saved.
 * Read-only introspection; the write path stays untouched. */
export function playlistPersistTimestamp(userId: string): null | string {
    try {
        const raw = playlistStorage().getItem(STATE_KEY_PREFIX + userId);
        if (raw === null) return null;
        const parsed = JSON.parse(raw) as { savedAt?: unknown };
        return typeof parsed.savedAt === 'string' ? parsed.savedAt : null;
    } catch {
        return null;
    }
}

/** The storage backend: the global localStorage when present, else memory. */
export function playlistStorage(): PlaylistStorageBackend {
    if (backend !== null) {
        return backend;
    }
    const candidate = (globalThis as { localStorage?: PlaylistStorageBackend }).localStorage;
    if (
        typeof candidate?.getItem === 'function' &&
        typeof candidate?.setItem === 'function' &&
        typeof candidate?.removeItem === 'function'
    ) {
        backend = candidate;
    } else {
        backend = new MemoryStorageBackend();
    }
    return backend;
}

/** Persist the replica state for a user (never throws - a failed save must
 * not take the mutation path down; the next save retries). */
export function savePlaylistState(
    userId: string,
    docs: Map<string, PlaylistCrdtDoc>,
    outbox: PlaylistOutbox,
    persistedRemote: Set<string>,
): boolean {
    try {
        const payload: PersistedPlaylistState = {
            docs: Array.from(docs.values()).map((doc) => serializePlaylistDoc(doc)),
            outbox: outbox.toJSON(),
            persistedRemote: Array.from(persistedRemote),
            savedAt: new Date().toISOString(),
            userId,
            v: 1,
        };
        playlistStorage().setItem(STATE_KEY_PREFIX + userId, JSON.stringify(payload));
        return true;
    } catch {
        return false;
    }
}

/** Test-only: inject a backend (and reset the device-id memo). */
export function setPlaylistStorageBackendForTests(
    replacement: null | PlaylistStorageBackend,
): void {
    backend = replacement;
    deviceIdMemo = null;
}

// ---------------------------------------------------------------------------
// Device identity
// ---------------------------------------------------------------------------

let deviceIdMemo: null | string = null;

/** A stable per-install device id (minted once, persisted forever). */
export function getPlaylistDeviceId(): string {
    if (deviceIdMemo !== null) {
        return deviceIdMemo;
    }
    let id = '';
    try {
        id = playlistStorage().getItem(DEVICE_KEY) ?? '';
    } catch {
        id = '';
    }
    if (id === '') {
        id = `dev-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
        try {
            playlistStorage().setItem(DEVICE_KEY, id);
        } catch {
            // persistence failure just means a fresh id next boot
        }
    }
    deviceIdMemo = id;
    return id;
}
