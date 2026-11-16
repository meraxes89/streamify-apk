/**
 * api/streamify/playback/queue-persistence.ts — P3-B: play-queue
 * persistence for the feishin bridge, ported from #187's
 * front-end/web/src/lib/playback/persistence.ts.
 *
 * WHAT SURVIVES RELOAD: the canonical track IDs (feishin song ids = the
 * cloud tracks table ids), the queue order, the cursor, the playhead
 * offset — and the in-flight scrobble session, so a restored play
 * continues accumulating toward its threshold instead of re-scrobbling.
 *
 * WHAT IS NEVER PERSISTED: resolved stream URLs. They expire in hours
 * and would be dead on reload (403 on restore); the resume path resolves
 * the current track FRESH through getStreamUrl. The guarantee lives in
 * the serializer's EXPLICIT FIELD LIST below — the payload carries ids
 * and offsets only, never URLs, by construction and not by convention.
 * (Feishin's player store owns the canonical queue; #187's queueMachine
 * is deliberately NOT ported — see decisions/p3/P3B-favorites-curation-bridge.md.)
 *
 * THE STORE, DECIDED BY SIZE (not vibes) — the #187 ruling, unchanged:
 *
 *   • localStorage — small queues (the default). Synchronous, universal,
 *     and the ~5 MB string quota comfortably holds thousands of ids.
 *     LOCAL_MAX_CHARS (750k ≈ 1.5 MB UTF-16) leaves multiple headroom
 *     against the quota so other keys never starve.
 *   • IndexedDB — anything larger. A giant queue rides the unbounded
 *     object store instead of gambling on the string quota.
 *
 * Single-source invariant: exactly ONE lane holds the state at a time —
 * a write clears the other lane, so a load can never see a stale twin.
 * When IndexedDB is unavailable and the payload overflows localStorage
 * (QuotaExceededError), persistence degrades honestly to "not saved"
 * (the queue lives for the session; playback is never blocked).
 *
 * Corruption policy: any shape drift, any duplicate id, any out-of-range
 * cursor → the payload is DISCARDED. Never a crash, never a
 * half-restored state.
 */

export const PERSIST_FORMAT_VERSION = 3;

/** localStorage lane ceiling (chars) before routing to IndexedDB. */
export const LOCAL_MAX_CHARS = 750_000;

/** Sanity ceiling — a queue longer than this is corrupt, not huge. */
const MAX_QUEUE_ROWS = 10_000;

/** The unbounded lane (IndexedDB in production). */
export interface AsyncKV {
    clear(): Promise<void>;
    get(): Promise<null | string>;
    set(value: string): Promise<void>;
}

/**
 * The persisted queue — the URL-free shape. `songIds` is the queue in
 * playback order (feishin's queue items, reduced to canonical ids);
 * `currentIndex` indexes INTO songIds; `positionMs` is the cursor row's
 * playhead offset at save time.
 */
export interface PersistedPlayQueue {
    currentIndex: null | number;
    positionMs: number;
    savedAt: number;
    /** The in-flight scrobble session, when one exists. */
    session: null | PersistedScrobbleSession;
    songIds: string[];
    v: typeof PERSIST_FORMAT_VERSION;
}

// ---------------------------------------------------------------------------
// Injectable lanes (tests inject fakes; production wires the globals)
// ---------------------------------------------------------------------------

/** The persisted scrobble session (continuity across restore). */
export interface PersistedScrobbleSession {
    /** Accumulated PLAYED time (pause does not accrue). */
    playedMs: number;
    /** Already fired for this session — must not re-fire on restore. */
    scrobbled: boolean;
    /** Epoch ms when the session started. */
    startedAt: number;
    trackId: string;
}

/** The synchronous lane (localStorage in production). */
export interface SyncKV {
    clear(): void;
    get(): null | string;
    set(value: string): void;
}

const localKeyFor = (serverId: string) => `streamify.feishin.queue.v3:${serverId}`;

export function localStorageLane(serverId: string): SyncKV {
    const key = localKeyFor(serverId);
    return {
        clear(): void {
            try {
                localStorage.removeItem(key);
            } catch {
                /* best-effort */
            }
        },
        get(): null | string {
            try {
                return localStorage.getItem(key);
            } catch {
                return null; // private mode / access denied — degrade to absent
            }
        },
        set(value: string): void {
            localStorage.setItem(key, value); // QuotaExceeded propagates to the router
        },
    };
}

const IDB_NAME = 'streamify-feishin-playback';
const IDB_STORE = 'queue';

export interface QueuePersistence {
    clear(): Promise<void>;
    load(): Promise<null | PersistedPlayQueue>;
    save(payload: PersistedPlayQueue): Promise<void>;
}

// ---------------------------------------------------------------------------
// The router
// ---------------------------------------------------------------------------

export function createQueuePersistence(
    lanes: { local?: SyncKV; localMaxChars?: number; remote?: AsyncKV | null } = {},
): QueuePersistence {
    const local: SyncKV = lanes.local ?? localStorageLane('default');
    const remote: AsyncKV | null = lanes.remote === undefined ? null : lanes.remote;
    const maxChars = lanes.localMaxChars ?? LOCAL_MAX_CHARS;

    return {
        async clear(): Promise<void> {
            local.clear();
            await remote?.clear().catch(() => undefined);
        },

        async load(): Promise<null | PersistedPlayQueue> {
            const small = local.get();
            if (small !== null) {
                const parsed = deserializeQueue(small);
                if (parsed !== null) return parsed;
                // Corrupt small-lane payload: clear it, then check the big lane
                // (a pre-routing write may still live there).
                local.clear();
            }
            if (remote === null) return null;
            const big = await remote.get();
            if (big === null) return null;
            const parsed = deserializeQueue(big);
            if (parsed === null) await remote.clear().catch(() => undefined);
            return parsed;
        },

        async save(payload): Promise<void> {
            const raw = serializeQueue(payload);
            if (raw.length <= maxChars) {
                try {
                    local.set(raw);
                    await remote?.clear().catch(() => undefined);
                    return;
                } catch {
                    // Quota exceeded on the small lane → fall through to the
                    // unbounded lane (when it exists).
                }
            }
            if (remote === null) {
                // No fallback available: honest degradation — not saved.
                local.clear();
                return;
            }
            try {
                await remote.set(raw);
                local.clear(); // single-source invariant
            } catch {
                local.clear();
            }
        },
    };
}

/** Full-shape validation; null on ANY drift (discard, never crash). */
export function deserializeQueue(raw: string): null | PersistedPlayQueue {
    let p: unknown;
    try {
        p = JSON.parse(raw);
    } catch {
        return null;
    }
    if (typeof p !== 'object' || p === null) return null;
    const q = p as Record<string, unknown>;
    if (q.v !== PERSIST_FORMAT_VERSION) return null;
    if (typeof q.savedAt !== 'number' || !Number.isFinite(q.savedAt)) return null;
    if (!Array.isArray(q.songIds) || q.songIds.length === 0) return null;
    if (q.songIds.length > MAX_QUEUE_ROWS) return null;
    const songIds: string[] = [];
    const seenIds = new Set<string>();
    for (const id of q.songIds) {
        if (typeof id !== 'string' || id === '') return null;
        if (seenIds.has(id)) return null; // ids must stay unique
        seenIds.add(id);
        songIds.push(id);
    }
    if (q.currentIndex !== null) {
        if (
            typeof q.currentIndex !== 'number' ||
            !Number.isInteger(q.currentIndex) ||
            q.currentIndex < 0 ||
            q.currentIndex >= songIds.length
        ) {
            return null;
        }
    }
    if (typeof q.positionMs !== 'number' || !Number.isFinite(q.positionMs) || q.positionMs < 0) {
        return null;
    }
    let session: null | PersistedScrobbleSession = null;
    if (q.session !== null && q.session !== undefined) {
        if (typeof q.session !== 'object') return null;
        const s = q.session as Record<string, unknown>;
        if (typeof s.trackId !== 'string' || s.trackId === '') return null;
        if (!seenIds.has(s.trackId)) return null; // must reference a row
        if (typeof s.startedAt !== 'number' || !Number.isFinite(s.startedAt)) return null;
        if (typeof s.playedMs !== 'number' || !Number.isFinite(s.playedMs) || s.playedMs < 0) {
            return null;
        }
        if (typeof s.scrobbled !== 'boolean') return null;
        session = {
            playedMs: s.playedMs,
            scrobbled: s.scrobbled,
            startedAt: s.startedAt,
            trackId: s.trackId,
        };
    }
    return {
        currentIndex: q.currentIndex as null | number,
        positionMs: q.positionMs,
        savedAt: q.savedAt,
        session,
        songIds,
        v: PERSIST_FORMAT_VERSION,
    };
}

// ---------------------------------------------------------------------------
// Serialization + validation
// ---------------------------------------------------------------------------

/** The IndexedDB lane (null when the API is absent — node, tests). */
export function idbLane(serverId: string): AsyncKV | null {
    if (typeof indexedDB === 'undefined') return null;
    let dbPromise: null | Promise<IDBDatabase> = null;
    const db = (): Promise<IDBDatabase> => {
        if (dbPromise === null) {
            dbPromise = new Promise((resolve, reject) => {
                const req = indexedDB.open(IDB_NAME, 1);
                req.onupgradeneeded = () => {
                    const db = req.result;
                    if (!db.objectStoreNames.contains(IDB_STORE)) db.createObjectStore(IDB_STORE);
                };
                req.onsuccess = () => resolve(req.result);
                req.onerror = () => reject(req.error ?? new Error('indexedDB open failed'));
            });
            dbPromise.catch(() => {
                dbPromise = null; // retry-able on the next operation
            });
        }
        return dbPromise;
    };
    const tx = async (mode: IDBTransactionMode): Promise<IDBObjectStore> => {
        const d = await db();
        return d.transaction(IDB_STORE, mode).objectStore(IDB_STORE);
    };
    return {
        async clear(): Promise<void> {
            try {
                const store = await tx('readwrite');
                await new Promise<void>((resolve, reject) => {
                    const req = store.delete(serverId);
                    req.onsuccess = () => resolve();
                    req.onerror = () => reject(req.error ?? new Error('indexedDB delete failed'));
                });
            } catch {
                /* best-effort */
            }
        },
        async get(): Promise<null | string> {
            try {
                const store = await tx('readonly');
                return await new Promise((resolve, reject) => {
                    const req = store.get(serverId);
                    req.onsuccess = () => resolve((req.result as string | undefined) ?? null);
                    req.onerror = () => reject(req.error ?? new Error('indexedDB get failed'));
                });
            } catch {
                return null; // corrupted/blocked DB degrades to absent
            }
        },
        async set(value: string): Promise<void> {
            const store = await tx('readwrite');
            await new Promise<void>((resolve, reject) => {
                const req = store.put(value, serverId);
                req.onsuccess = () => resolve();
                req.onerror = () => reject(req.error ?? new Error('indexedDB put failed'));
            });
        },
    };
}

/**
 * The explicit field list — THE guarantee that resolved URLs never ride
 * along lives here. Only ids, order, cursor, offset, and the scrobble
 * session are serialized; anything else a caller passes is dropped.
 */
export function serializeQueue(p: PersistedPlayQueue): string {
    return JSON.stringify({
        currentIndex: p.currentIndex,
        positionMs: p.positionMs,
        savedAt: p.savedAt,
        session: p.session,
        songIds: p.songIds,
        v: PERSIST_FORMAT_VERSION,
    });
}
