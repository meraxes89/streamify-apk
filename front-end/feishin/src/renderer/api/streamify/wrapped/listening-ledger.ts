/**
 * api/streamify/wrapped/listening-ledger.ts — P4-A: the time-windowed
 * listening ledger behind Wrapped.
 *
 * DATA BACKING (the task's ruling): playTally verified listens — the
 * threshold crossings #191's PlaySessionTracker fires (never starts,
 * never skips) — extended with the timestamps the aggregate-only tally
 * cannot carry. Two sources merge deterministically:
 *
 *   • LOCAL rows: every counted play, appended by the scrobble bridge's
 *     fire path via recordCountedPlay (the onCountedPlay seam, wired in
 *     streamify-controller.ts). Carries the full facts (album, played
 *     time, rung). Survives offline sessions in localStorage.
 *
 *   • CLOUD seed: snapshot.playEvents (user_play_events — the durable
 *     record the scrobble outbox drains into), re-derived on every
 *     snapshot load; NEVER persisted here.
 *
 * MERGE RULE (no double-count): cloud events with created_at strictly
 * older than the oldest local row are prepended; anything newer is
 * dropped from the seed — by then the outbox has drained it, so it is
 * already a local row (the tally overlay's own honesty rule, applied to
 * windows). Edge: an offline local row that drained later than a newer
 * cloud event keeps its local copy; the cloud copy lands beyond the
 * oldest-local boundary and is excluded. Undercounted only in that rare
 * multi-device interleaving — documented in
 * decisions/p4/P4A-downloads-wrapped-debug.md.
 *
 * GENRES: the cloud schema carries no genre column today (verified —
 * see library.ts's honest-empty genre index). The ledger carries the
 * field, the aggregation is live and tested, and the Wrapped UI renders
 * an honest empty state until the schema grows one.
 */

import type { LibrarySnapshot } from '../library';

export interface LedgerEvent {
    album: string;
    artist: string;
    durationMs: number;
    /** Hour provenance for the chronotype histogram. */
    genres: string[];
    /** Actual played seconds at the threshold crossing. */
    playedSec: number;
    /** The serving lane (resolver rung; 'offline' rows carry the download's rung). */
    rung: null | string;
    /** Provenance — local fire vs cloud backfill. */
    source: 'cloud' | 'local';
    title: string;
    /** The cloud track id (= feishin song id = trackSig). */
    trackId: string;
    /** Epoch ms. */
    ts: number;
}

export interface LedgerStorage {
    read(): null | string;
    write(value: string): void;
}

const KEY = 'streamify.feishin.wrapped.v1';
const CAP = 5000; // local row cap (oldest evicted; ~5 years of heavy listening)

function localStorageLedgerStorage(): LedgerStorage {
    // The same window-guard idiom the scrobble outbox uses (queue-persistence).
    if (typeof window === 'undefined' || window.localStorage === undefined) {
        return { read: () => null, write: () => undefined };
    }
    const ls = window.localStorage;
    return {
        read: () => ls.getItem(KEY),
        write: (value) => {
            ls.setItem(KEY, value);
        },
    };
}

// ---------------------------------------------------------------------------
// Module state
// ---------------------------------------------------------------------------

let localRows: LedgerEvent[] | null = null;
let storage: LedgerStorage = localStorageLedgerStorage();
/** Cloud seeds by snapshot identity — a snapshot reload re-derives once. */
let seededKey: null | string = null;
let seededRows: LedgerEvent[] = [];
/** Cached merge, invalidated by any write or reseed. */
let mergedCache: LedgerEvent[] | null = null;

/** The merged, time-sorted view (ascending ts). */
export function getLedger(): LedgerEvent[] {
    if (localRows === null) localRows = parseRows(storage.read());
    if (mergedCache !== null) return mergedCache;
    const merged = [...seededRows, ...localRows].sort((a, b) => a.ts - b.ts);
    mergedCache = merged;
    return merged;
}

/** The scrobble bridge's fire-path seam (streamify-controller wiring). */
export function recordCountedPlay(fired: {
    album: string;
    artist: string;
    durationMs: number;
    playedMs: number;
    rung: null | string;
    title: string;
    trackId: string;
}): void {
    if (localRows === null) localRows = parseRows(storage.read());
    localRows.push({
        album: fired.album,
        artist: fired.artist,
        durationMs: fired.durationMs,
        genres: [], // the cloud schema has no genre column yet (see the header)
        playedSec: fired.playedMs / 1000,
        rung: fired.rung,
        source: 'local',
        title: fired.title,
        trackId: fired.trackId,
        ts: Date.now(),
    });
    if (localRows.length > CAP) localRows = localRows.slice(localRows.length - CAP);
    persist();
}

// ---------------------------------------------------------------------------
// The two ingest paths
// ---------------------------------------------------------------------------

export function resetLedgerForTests(): void {
    localRows = null;
    seededKey = null;
    seededRows = [];
    mergedCache = null;
}

/** Cloud backfill from the live snapshot (idempotent per snapshot). */
export function seedFromSnapshot(snapshot: LibrarySnapshot): void {
    const key = `${snapshot.sessionKey}|${snapshot.loadedAt}`;
    if (seededKey === key) return;
    seededKey = key;
    if (localRows === null) localRows = parseRows(storage.read());
    const oldestLocal = localRows.length > 0 ? localRows[0].ts : Number.POSITIVE_INFINITY;
    const cloud: LedgerEvent[] = [];
    for (const row of snapshot.playEvents) {
        const ts = Date.parse(row.created_at);
        if (!Number.isFinite(ts)) continue;
        if (ts >= oldestLocal) continue; // already a local row (outbox drained it)
        cloud.push({
            album: '', // the wire carries no album (PlayEventRow)
            artist: row.artist,
            durationMs: 0, // the wire carries no duration (honest zero, ADR'd)
            genres: [],
            playedSec: row.duration_played_sec,
            rung: null,
            source: 'cloud',
            title: row.title,
            trackId: row.track_sig,
            ts,
        });
    }
    seededRows = cloud;
    mergedCache = null;
}

export function setLedgerStorageForTests(s: LedgerStorage): void {
    storage = s;
    localRows = null;
    mergedCache = null;
}

// ---------------------------------------------------------------------------
// Test seams
// ---------------------------------------------------------------------------

function parseRows(raw: null | string): LedgerEvent[] {
    if (raw === null) return [];
    try {
        const parsed = JSON.parse(raw) as LedgerEvent[];
        if (!Array.isArray(parsed)) return [];
        return parsed.filter(
            (r) =>
                typeof r?.ts === 'number' &&
                typeof r?.trackId === 'string' &&
                typeof r?.title === 'string',
        );
    } catch {
        return []; // torn write — honest empty, never a crash
    }
}

function persist(): void {
    if (localRows === null) return;
    storage.write(JSON.stringify(localRows));
    mergedCache = null;
}

export const LEDGER_KEY = KEY;
