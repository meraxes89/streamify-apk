/**
 * streamify/playlist-sync/outbox.ts - the pending op-log (P3A).
 *
 * THE OFFLINE CONTRACT: every locally-minted op is enqueued here BEFORE
 * the flush is attempted, and the queue survives restarts (persistence.ts
 * writes it next to the CRDT state). Ops leave the queue only after a
 * sync pass confirmed their playlist's durable state - until then the
 * sync pass re-materializes them from the engine state on every attempt.
 *
 * Duplicate op ids are dropped at enqueue (the mint is per-op, but the
 * queue is the dedupe point for replays and retried enqueues).
 */

export interface OutboxEntry {
    /** Enqueue time (ISO) - telemetry only, never ordering. */
    enqueuedAt: string;
    /** Element target for Remove/Reorder, the mint id for Add. */
    opId: string;
    opType: number;
    /** The playlist the op belongs to. */
    playlistId: string;
    /** The sealed 48-byte row, base64 (the wire form - checksum intact). */
    row: string;
    /** The out-of-band track id (Add ops). */
    trackId: null | string;
}

/** An append-only queue with op-id dedupe. */
export class PlaylistOutbox {
    /** Whether the queue holds anything at all. */
    get size(): number {
        return this.entries.length;
    }

    private readonly entries: OutboxEntry[] = [];

    /** All pending entries (mint order = op_id order per device). */
    all(): OutboxEntry[] {
        return this.entries.slice();
    }

    /** Drop all entries for a playlist (after a confirmed flush, or when
     * the playlist is deleted locally - FK-dead ops must not linger). */
    clearFor(playlistId: string): void {
        for (let i = this.entries.length - 1; i >= 0; i -= 1) {
            if (this.entries[i].playlistId === playlistId) {
                this.entries.splice(i, 1);
            }
        }
    }

    /** Enqueue ops (mint order preserved); duplicates are dropped. */
    enqueue(entries: Array<Omit<OutboxEntry, 'enqueuedAt'>>): number {
        const known = new Set(this.entries.map((entry) => entry.opId));
        let added = 0;
        for (const entry of entries) {
            if (known.has(entry.opId)) {
                continue;
            }
            known.add(entry.opId);
            this.entries.push({ ...entry, enqueuedAt: new Date().toISOString() });
            added += 1;
        }
        return added;
    }

    /** Whether anything is pending for the playlist. */
    hasPending(playlistId: string): boolean {
        return this.entries.some((entry) => entry.playlistId === playlistId);
    }

    /** The set of pending Add op ids for one playlist (the sync pass's
     * "not yet delivered" disambiguator - see sync.ts). */
    pendingAddOpIds(playlistId: string): Set<string> {
        const ids = new Set<string>();
        for (const entry of this.entries) {
            if (entry.playlistId === playlistId && entry.opType === 1) {
                ids.add(entry.opId);
            }
        }
        return ids;
    }

    /** Pending entries for one playlist, op-id ascending (flush order).
     * The op ids are decimal strings above 2^53 - compare numerically. */
    pendingFor(playlistId: string): OutboxEntry[] {
        return this.entries
            .filter((entry) => entry.playlistId === playlistId)
            .sort((a, b) => {
                const ai = BigInt(a.opId);
                const bi = BigInt(b.opId);
                return ai < bi ? -1 : ai > bi ? 1 : 0;
            });
    }

    /** Replace the whole queue (hydration). */
    replace(entries: OutboxEntry[]): void {
        this.entries.length = 0;
        for (const entry of entries) {
            this.entries.push({ ...entry });
        }
    }

    /** JSON-safe view (u64s already decimal strings in the wire form). */
    toJSON(): OutboxEntry[] {
        return this.entries.map((entry) => ({ ...entry }));
    }
}

/** Parse a persisted queue; corrupt rows are dropped, never fatal. */
export function outboxFromJSON(raw: unknown): PlaylistOutbox {
    const outbox = new PlaylistOutbox();
    if (!Array.isArray(raw)) {
        return outbox;
    }
    for (const entry of raw) {
        if (
            typeof entry?.opId === 'string' &&
            /^\d+$/.test(entry.opId) &&
            typeof entry.playlistId === 'string' &&
            typeof entry.row === 'string' &&
            typeof entry.opType === 'number'
        ) {
            outbox.enqueue([
                {
                    opId: entry.opId,
                    opType: entry.opType,
                    playlistId: entry.playlistId,
                    row: entry.row,
                    trackId: typeof entry.trackId === 'string' ? entry.trackId : null,
                },
            ]);
        }
    }
    return outbox;
}
