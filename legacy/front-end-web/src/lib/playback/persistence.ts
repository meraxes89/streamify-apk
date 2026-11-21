/**
 * lib/playback/persistence.ts — P2-B: play-queue persistence (PURE-ish).
 *
 * The queue survives reload, crash, and "I'll come back tomorrow":
 * track rows (metadata), play order, cursor, repeat/shuffle state, the
 * playhead position — and the in-flight scrobble session, so a restored
 * play continues accumulating toward its threshold instead of
 * re-scrobbling.
 *
 * WHAT IS NEVER PERSISTED: resolved stream URLs. They expire in hours
 * and would be dead on restore; the resume path resolves the current
 * track FRESH through the engine port (getStreamUrl). The TrackRow
 * shape carries only metadata by construction — enforced by the
 * serializer's explicit field list, not by convention.
 *
 * THE STORE, DECIDED BY SIZE (not vibes):
 *
 *   • localStorage — small queues (the default). Synchronous, universal,
 *     and the ~5 MB string quota comfortably holds ~2.4k rows at the
 *     serialized ~300 chars/row. LOCAL_MAX_CHARS (750k ≈ 1.5 MB UTF-16)
 *     leaves multiple-headroom against the quota so other keys never
 *     starve.
 *   • IndexedDB — anything larger. A 5k-track queue (~1.5 MB+) rides the
 *     unbounded object store instead of gambling on the string quota.
 *
 * Single-source invariant: exactly ONE lane holds the state at a time —
 * a write clears the other lane, so a load can never see a stale twin.
 * When IndexedDB is unavailable and the payload overflows localStorage
 * (QuotaExceededError), persistence degrades honestly to "not saved"
 * (the queue lives for the session; playback is never blocked).
 *
 * Corruption policy: any shape drift, any non-permutation order, any
 * out-of-range cursor → the payload is DISCARDED and the app boots with
 * a fresh queue. Never a crash, never a half-restored state.
 */

import type { TrackRow } from '../wasmEngine';
import type { RepeatMode } from './queueMachine';

export const PERSIST_FORMAT_VERSION = 2;

/** localStorage lane ceiling (chars) before routing to IndexedDB. */
export const LOCAL_MAX_CHARS = 750_000;

/** The persisted scrobble session (continuity across restore). */
export interface PersistedScrobbleSession {
  trackId: string;
  /** Epoch ms when the session started. */
  startedAt: number;
  /** Accumulated PLAYED time (pause does not accrue). */
  playedMs: number;
  /** Already fired for this session — must not re-fire on restore. */
  scrobbled: boolean;
}

export interface PersistedQueue {
  v: typeof PERSIST_FORMAT_VERSION;
  savedAt: number;
  rows: Array<{ track: TrackRow; enqueuedBy: string }>;
  playOrder: number[];
  cursor: number | null;
  repeat: RepeatMode;
  shuffle: boolean;
  /** Playhead of the cursor row at save time. */
  positionMs: number;
  /** The in-flight scrobble session, when one exists. */
  session: PersistedScrobbleSession | null;
}

// ---------------------------------------------------------------------------
// Injectable lanes (tests inject fakes; production wires the globals)
// ---------------------------------------------------------------------------

/** The synchronous lane (localStorage in production). */
export interface SyncKV {
  get(): string | null;
  set(value: string): void;
  clear(): void;
}

/** The unbounded lane (IndexedDB in production). */
export interface AsyncKV {
  get(): Promise<string | null>;
  set(value: string): Promise<void>;
  clear(): Promise<void>;
}

const LOCAL_KEY = 'streamify.queue.v2';

export function localStorageLane(): SyncKV {
  return {
    get(): string | null {
      try {
        return localStorage.getItem(LOCAL_KEY);
      } catch {
        return null; // private mode / access denied — degrade to absent
      }
    },
    set(value: string): void {
      localStorage.setItem(LOCAL_KEY, value); // QuotaExceeded propagates to the router
    },
    clear(): void {
      try {
        localStorage.removeItem(LOCAL_KEY);
      } catch {
        /* best-effort */
      }
    },
  };
}

const IDB_NAME = 'streamify-playback';
const IDB_STORE = 'queue';
const IDB_KEY = 'current';

/** The IndexedDB lane (null when the API is absent). */
export function idbLane(): AsyncKV | null {
  if (typeof indexedDB === 'undefined') return null;
  let dbPromise: Promise<IDBDatabase> | null = null;
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
    async get(): Promise<string | null> {
      try {
        const store = await tx('readonly');
        return await new Promise((resolve, reject) => {
          const req = store.get(IDB_KEY);
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
        const req = store.put(value, IDB_KEY);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error ?? new Error('indexedDB put failed'));
      });
    },
    async clear(): Promise<void> {
      try {
        const store = await tx('readwrite');
        await new Promise<void>((resolve, reject) => {
          const req = store.delete(IDB_KEY);
          req.onsuccess = () => resolve();
          req.onerror = () => reject(req.error ?? new Error('indexedDB delete failed'));
        });
      } catch {
        /* best-effort */
      }
    },
  };
}

// ---------------------------------------------------------------------------
// The router
// ---------------------------------------------------------------------------

export interface Persistence {
  save(payload: PersistedQueue): Promise<void>;
  load(): Promise<PersistedQueue | null>;
  clear(): Promise<void>;
}

export function createPersistence(
  lanes: { local?: SyncKV; remote?: AsyncKV | null; localMaxChars?: number } = {},
): Persistence {
  const local: SyncKV = lanes.local ?? localStorageLane();
  const remote: AsyncKV | null = lanes.remote === undefined ? idbLane() : lanes.remote;
  const maxChars = lanes.localMaxChars ?? LOCAL_MAX_CHARS;

  return {
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

    async load(): Promise<PersistedQueue | null> {
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

    async clear(): Promise<void> {
      local.clear();
      await remote?.clear().catch(() => undefined);
    },
  };
}

// ---------------------------------------------------------------------------
// Serialization + validation
// ---------------------------------------------------------------------------

/**
 * Explicit field list — the guarantee that resolved URLs never ride
 * along lives HERE (TrackRow's own fields are metadata-only; if the
 * engine ever grows a URL field on the row, this list still excludes
 * it).
 */
export function serializeQueue(p: PersistedQueue): string {
  return JSON.stringify({
    v: PERSIST_FORMAT_VERSION,
    savedAt: p.savedAt,
    rows: p.rows.map((r) => ({
      enqueuedBy: r.enqueuedBy,
      track: {
        id: r.track.id,
        title: r.track.title,
        artist: r.track.artist,
        album: r.track.album,
        durationMs: r.track.durationMs,
        thumbnailUrl: r.track.thumbnailUrl,
        isExplicit: r.track.isExplicit,
      },
    })),
    playOrder: p.playOrder,
    cursor: p.cursor,
    repeat: p.repeat,
    shuffle: p.shuffle,
    positionMs: p.positionMs,
    session: p.session,
  });
}

function isRepeatMode(v: unknown): v is RepeatMode {
  return v === 'off' || v === 'all' || v === 'one';
}

/** Full-shape validation; null on ANY drift (discard, never crash). */
export function deserializeQueue(raw: string): PersistedQueue | null {
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
  if (!Array.isArray(q.rows) || q.rows.length === 0) return null;
  if (q.rows.length > 10_000) return null; // sanity ceiling
  const rows: PersistedQueue['rows'] = [];
  const seenIds = new Set<string>();
  for (const r of q.rows) {
    if (typeof r !== 'object' || r === null) return null;
    const { track, enqueuedBy } = r as Record<string, unknown>;
    if (typeof track !== 'object' || track === null) return null;
    const t = track as Record<string, unknown>;
    if (typeof t.id !== 'string' || t.id === '') return null;
    if (seenIds.has(t.id)) return null; // ids must stay unique
    seenIds.add(t.id);
    if (typeof t.title !== 'string' || typeof t.artist !== 'string') return null;
    if (typeof t.album !== 'string') return null;
    if (typeof t.durationMs !== 'number' || !Number.isFinite(t.durationMs) || t.durationMs < 0) return null;
    rows.push({
      enqueuedBy: typeof enqueuedBy === 'string' ? enqueuedBy : 'web',
      track: {
        id: t.id,
        title: t.title,
        artist: t.artist,
        album: t.album,
        durationMs: t.durationMs,
        thumbnailUrl: typeof t.thumbnailUrl === 'string' ? t.thumbnailUrl : null,
        isExplicit: t.isExplicit === true,
      },
    });
  }
  if (!Array.isArray(q.playOrder) || q.playOrder.length !== rows.length) return null;
  const seen = new Set<number>();
  for (const i of q.playOrder) {
    if (typeof i !== 'number' || !Number.isInteger(i) || i < 0 || i >= rows.length) return null;
    if (seen.has(i)) return null; // must be a permutation
    seen.add(i);
  }
  if (q.cursor !== null) {
    if (typeof q.cursor !== 'number' || !Number.isInteger(q.cursor) || q.cursor < 0 || q.cursor >= q.playOrder.length)
      return null;
  }
  if (!isRepeatMode(q.repeat)) return null;
  if (typeof q.shuffle !== 'boolean') return null;
  if (typeof q.positionMs !== 'number' || !Number.isFinite(q.positionMs) || q.positionMs < 0) return null;
  let session: PersistedScrobbleSession | null = null;
  if (q.session !== null && q.session !== undefined) {
    if (typeof q.session !== 'object') return null;
    const s = q.session as Record<string, unknown>;
    if (typeof s.trackId !== 'string' || s.trackId === '') return null;
    if (!seenIds.has(s.trackId)) return null; // must reference a row
    if (typeof s.startedAt !== 'number' || !Number.isFinite(s.startedAt)) return null;
    if (typeof s.playedMs !== 'number' || !Number.isFinite(s.playedMs) || s.playedMs < 0) return null;
    if (typeof s.scrobbled !== 'boolean') return null;
    session = {
      trackId: s.trackId,
      startedAt: s.startedAt,
      playedMs: s.playedMs,
      scrobbled: s.scrobbled,
    };
  }
  return {
    v: PERSIST_FORMAT_VERSION,
    savedAt: q.savedAt,
    rows,
    playOrder: q.playOrder as number[],
    cursor: q.cursor as number | null,
    repeat: q.repeat,
    shuffle: q.shuffle,
    positionMs: q.positionMs,
    session,
  };
}
