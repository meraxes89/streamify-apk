/**
 * api/streamify/wrapped/origin-hints.ts — W3-B: the discovery-origin hint
 * registry behind the Wrapped "Top Discovery" stat.
 *
 * THE RULE (the W3-B ADR): a track's discovery origin is claimed by the
 * recommendation surface that FIRST queues it — Automix radio (W1-B's
 * getUpNext continuations) and the Discover Mix shelf (W2-B). The claim
 * rides a tiny bounded registry (id → origin, first-writer-wins), and the
 * scrobble fire path resolves it at the exact moment the listening ledger
 * row is written — so Wrapped never invents a parallel play counter, it
 * only annotates the threshold crossings #191's tracker already fires.
 *
 * KEYING: hints are keyed by videoId (what the catalog surfaces hold) AND
 * optionally by library track id (song id / trackSig — what the ledger row
 * carries). The fire path resolves trackId → videoId through the live
 * snapshot when the direct trackId hint misses.
 *
 * PERSISTENCE: best-effort localStorage blob ('streamify.wrapped.origins.v1',
 * { entries: [id, origin][], v: 1 }), capped at 512 entries with FIFO
 * eviction — a hint only needs to survive until its track crosses the play
 * threshold, so a bounded window is honest and cheap.
 */

import type { LibrarySnapshot } from '../library';

/** The Automix radio lane (W1-B — getUpNext continuations). */
export const ORIGIN_AUTOMIX = 'automix';
/** The Discover Mix shelf (W2-B — playTally-seeded recommendations). */
export const ORIGIN_DISCOVER_MIX = 'discover-mix';

export const ORIGIN_HINTS_KEY = 'streamify.wrapped.origins.v1';
const CAP = 512;
const MAX_ORIGIN_LEN = 32;

export interface OriginHintsStorage {
    read(): null | string;
    write(value: string): void;
}

function localStorageOriginStorage(): OriginHintsStorage {
    if (typeof window === 'undefined' || window.localStorage === undefined) {
        return { read: () => null, write: () => undefined };
    }
    const ls = window.localStorage;
    return {
        read: () => ls.getItem(ORIGIN_HINTS_KEY),
        write: (value) => {
            ls.setItem(ORIGIN_HINTS_KEY, value);
        },
    };
}

// ---------------------------------------------------------------------------
// Module state (the P4-A ledger idiom: lazy rows + injectable storage)
// ---------------------------------------------------------------------------

let entries: Array<[string, string]> | null = null;
let storage: OriginHintsStorage = localStorageOriginStorage();

/**
 * The fire-path resolver: a counted play's library track id resolves to its
 * discovery origin — directly when the hint was keyed by track id, else via
 * the snapshot's song row (streamUrl carries the videoId lane). Pure with
 * respect to the snapshot: null snapshot / unknown row → honest null.
 */
export function discoveryOriginOf(
    trackId: string,
    snapshot: LibrarySnapshot | null,
): null | string {
    const direct = originOf(trackId);
    if (direct !== null) return direct;
    if (snapshot === null) return null;
    const row = snapshot.songById.get(trackId);
    const videoId = row?.streamUrl;
    if (typeof videoId !== 'string' || videoId === '') return null;
    return originOf(videoId);
}

/** The hint for one id (null when unclaimed — organic / search / library). */
export function originOf(id: string): null | string {
    if (typeof id !== 'string' || id === '') return null;
    const rows = ensureLoaded();
    for (let i = rows.length - 1; i >= 0; i -= 1) {
        if (rows[i][0] === id) return rows[i][1];
    }
    return null;
}

/**
 * Claim the discovery origin for a set of ids. First-writer-wins: a track
 * that already has a hint (or an existing ledger row — the caller's
 * ordering concern) keeps its original origin, because discovery is the
 * FIRST introduction, not the latest queue entry.
 */
export function rememberOrigins(ids: Array<string>, origin: string): void {
    if (typeof origin !== 'string' || origin === '' || origin.length > MAX_ORIGIN_LEN) return;
    const rows = ensureLoaded();
    const known = new Set(rows.map(([id]) => id));
    let changed = false;
    for (const id of ids) {
        if (typeof id !== 'string' || id === '' || known.has(id)) continue;
        known.add(id);
        rows.push([id, origin]);
        changed = true;
    }
    if (!changed) return;
    if (rows.length > CAP) entries = rows.slice(rows.length - CAP);
    persist();
}

export function resetOriginHintsForTests(): void {
    entries = null;
}

export function setOriginHintsStorageForTests(s: OriginHintsStorage): void {
    storage = s;
    entries = null;
}

function ensureLoaded(): Array<[string, string]> {
    if (entries === null) entries = parse(storage.read());
    return entries;
}

// ---------------------------------------------------------------------------
// Test seams
// ---------------------------------------------------------------------------

function parse(raw: null | string): Array<[string, string]> {
    if (raw === null) return [];
    try {
        const parsed = JSON.parse(raw) as { entries?: unknown; v?: unknown };
        if (parsed?.v !== 1 || !Array.isArray(parsed.entries)) return [];
        const out: Array<[string, string]> = [];
        for (const row of parsed.entries) {
            if (
                Array.isArray(row) &&
                typeof row[0] === 'string' &&
                row[0] !== '' &&
                typeof row[1] === 'string' &&
                row[1] !== ''
            ) {
                out.push([row[0], row[1]]);
            }
        }
        return out;
    } catch {
        return []; // torn write — honest empty, never a crash
    }
}

function persist(): void {
    if (entries === null) return;
    storage.write(JSON.stringify({ entries, v: 1 }));
}
