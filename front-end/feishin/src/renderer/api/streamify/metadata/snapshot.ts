/**
 * streamify/metadata/snapshot.ts — the per-call in-memory metadata
 * snapshot (the "Snapshot Upsert" stage of the W1-A pipeline).
 *
 * A module singleton keyed by the SAME cache-key scheme the worker uses
 * (endpoint / id_or_query / gl / hl / continuation) — one key discipline
 * across the seam. Holds:
 *   • the last-known-good payload per key (serves repeat calls with zero
 *     network — source 'snapshot');
 *   • not-found entries for SNAPSHOT_NEGATIVE_TTL_MS (15 min) so repeat
 *     calls in one session never re-hit (the zero-retry-storm guarantee).
 *
 * Deliberately DUMB state (the house store discipline): no reactivity —
 * consumers poll through the provider; tests reset via
 * resetMetadataSnapshotForTests().
 */

import { type MetadataPayload, SNAPSHOT_NEGATIVE_TTL_MS } from './types';

export interface SnapshotEntry {
    atMs: number;
    /** true = a not-found remembrance (payload null). */
    negative: boolean;
    payload: MetadataPayload | null;
}

const entries = new Map<string, SnapshotEntry>();

/** Drop everything (tests only). */
export function resetMetadataSnapshotForTests(): void {
    entries.clear();
}

export function snapshotKey(
    endpoint: string,
    idOrQuery: string,
    gl: string,
    hl: string,
    continuation?: string,
): string {
    const base = `meta/${endpoint}/${gl || 'US'}/${hl || 'en'}/${encodeURIComponent(idOrQuery)}`;
    if (continuation !== undefined && continuation !== '') {
        return `${base}/${continuation.length}~${continuation.slice(0, 12)}`;
    }
    return base;
}

/** Look up a key: a hit only when non-expired (positives never expire —
 * they are last-known-good; negatives expire after 15 min). */
export function snapshotLookup(key: string, now: number = Date.now()): null | SnapshotEntry {
    const entry = entries.get(key);
    if (entry === undefined) return null;
    if (entry.negative && now - entry.atMs > SNAPSHOT_NEGATIVE_TTL_MS) {
        entries.delete(key);
        return null;
    }
    return entry;
}

/** Read-only size (tests + diagnostics). */
export function snapshotSize(): number {
    return entries.size;
}

/** Upsert (insert-or-replace) — the pipeline's final stage. */
export function snapshotUpsert(
    key: string,
    payload: MetadataPayload | null,
    now: number = Date.now(),
): void {
    entries.set(key, { atMs: now, negative: payload === null, payload });
}
