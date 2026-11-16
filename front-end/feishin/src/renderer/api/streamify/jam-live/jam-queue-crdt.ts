/**
 * streamify/jam-live/jam-queue-crdt.ts - the collaborative listening-queue
 * document (P4B: Jam live-queue UI).
 *
 * The fold rules mirror the JAM_ENGINE_SPEC §8 queue merge semantics, in the
 * same register discipline P3A established for playlist documents:
 *
 *   - element identity = the ADD op's op_id (the B2 lineage);
 *   - ordered view sorts live elements by the composite key
 *     (frac, add_op_id) - bit order equals numeric order in this domain;
 *   - REMOVE tombstones are ABSORBING: a late Add replay or a late Reorder
 *     of a tombstoned element is a no-op in any delivery order, and a
 *     remove-before-add delivery mints a tombstone shell;
 *   - REORDER is an order-register LWW keyed by the COUNTER-FREE key
 *     (clock_ms, frac_bits) - deliberately excluding the op counter bits so
 *     the rule computes identically from an op and from a durable row;
 *   - VOTE registers one vote per SENDER (re-voting overwrites), so the
 *     upvote count converges to the set of distinct voters no matter the
 *     delivery order;
 *   - appliedOps makes the fold idempotent: Realtime may redeliver, the
 *     journal never duplicates (UNIQUE(session_code, op_id)).
 *
 * The document is pure data + pure functions - no transport, no store, no
 * React. The session controller owns instances; tests converge two replicas
 * by folding shuffled op streams in both orders.
 */

import { FRAC_FIRST_INDEX, fracCollide } from './jam-live-frac';
import { fracBitsOf, type JamOp, OP_ADD, OP_REMOVE, OP_REORDER, OP_VOTE } from './jam-live-wire';

/** The outcome of folding one op. */
export type JamFoldResult =
    | { applied: false; kind: 'duplicate' | 'rejected' | 'tombstoned' }
    | { applied: true; kind: 'add' | 'remove' | 'reorder' | 'vote' };

/** The collaborative queue document. */
export interface JamQueueDoc {
    /** Op ids already folded (idempotency regardless of delivery). */
    appliedOps: Set<bigint>;
    /** Live + tombstoned elements keyed by add op id. */
    elements: Map<bigint, JamQueueElement>;
    /** Latch: fractional space needs a host compaction pass. */
    needsRebalance: boolean;
}

/** One queue element (a track slot in the room's queue). */
export interface JamQueueElement {
    /** senderNonce hex of the participant who added the track. */
    addedBy: string;
    /** Element identity - the ADD op's op_id (decimal-string in views). */
    addOpId: bigint;
    /** The track's content-hash identity (title/artist/duration bucket). */
    cadId: bigint;
    /** Current order key (fractional index). */
    frac: number;
    /** Counter-free LWW key, part 1: the op-id's clock-ms slice. */
    orderClockMs: bigint;
    /** Counter-free LWW key, part 2: the order key's exact frac bits. */
    orderFracBits: bigint;
    /** The op that last wrote the order register (the Add initially). */
    orderOpId: bigint;
    /** Absorbing tombstone: the REMOVE op id, null while live. */
    removedAtOpId: bigint | null;
    /** senderNonce hex -> the vote op that registered it (one per sender). */
    votes: Map<string, bigint>;
}

export const createJamQueueDoc = (): JamQueueDoc => ({
    appliedOps: new Set<bigint>(),
    elements: new Map<bigint, JamQueueElement>(),
    needsRebalance: false,
});

/** The op-id's clock-ms slice (op_id >>> 16, the counter-free half). */
export function opClockMsOf(opId: bigint): bigint {
    return opId >> 16n;
}

/** Register-key compare: (clockMs, fracBits) lexicographic. */
function orderKeyGreater(
    clockMs: bigint,
    fracBits: bigint,
    currentClockMs: bigint,
    currentFracBits: bigint,
): boolean {
    if (clockMs !== currentClockMs) {
        return clockMs > currentClockMs;
    }
    return fracBits > currentFracBits;
}

/**
 * A live shell for an element whose ADD has not been folded yet (a Vote or
 * Reorder arrived first - at-least-once delivery in any order). Reorders
 * are order-register writes, so their shells seed the register; votes are
 * NOT ordering-authoritative, so their shells seed the minimal key and the
 * late Add always wins it back - that keeps the register convergent in
 * every delivery order.
 */
const mintShell = (op: JamOp, sender: string, seedsRegister: boolean): JamQueueElement => ({
    addedBy: sender,
    addOpId: op.targetAddOpId,
    cadId: op.trackCadId,
    frac: seedsRegister && op.fracIndex > 0 ? op.fracIndex : FRAC_FIRST_INDEX,
    orderClockMs: seedsRegister ? opClockMsOf(op.opId) : 0n,
    orderFracBits: fracBitsOf(seedsRegister && op.fracIndex > 0 ? op.fracIndex : FRAC_FIRST_INDEX),
    orderOpId: op.opId,
    removedAtOpId: null,
    votes: new Map<string, bigint>(),
});

/**
 * Host compaction plan: re-space every live element on the fresh
 * 0.5 + i grid (one Reorder op each). Returned in ORDER, so applying the
 * plan in sequence keeps the register keys strictly increasing.
 */
export interface JamRespaceStep {
    addOpId: bigint;
    newFrac: number;
}

/** Fold one op into the document (any delivery order, at least once). */
export function applyJamQueueOp(doc: JamQueueDoc, op: JamOp): JamFoldResult {
    if (doc.appliedOps.has(op.opId)) {
        return { applied: false, kind: 'duplicate' };
    }
    doc.appliedOps.add(op.opId);
    const sender = bytesToSenderHex(op);

    switch (op.opType) {
        case OP_ADD: {
            const existing = doc.elements.get(op.opId);
            if (existing) {
                if (existing.removedAtOpId !== null) {
                    // remove-before-add: the tombstone absorbs the late Add.
                    return { applied: false, kind: 'tombstoned' };
                }
                // A Vote/Reorder shell exists: fill the identity, merge the
                // order register by the max-key rule (order-independent).
                existing.cadId = op.trackCadId;
                existing.addedBy = sender;
                const clockMs = opClockMsOf(op.opId);
                const fracBits = fracBitsOf(op.fracIndex);
                if (
                    orderKeyGreater(
                        clockMs,
                        fracBits,
                        existing.orderClockMs,
                        existing.orderFracBits,
                    )
                ) {
                    existing.frac = op.fracIndex;
                    existing.orderClockMs = clockMs;
                    existing.orderFracBits = fracBits;
                    existing.orderOpId = op.opId;
                }
                return { applied: true, kind: 'add' };
            }
            doc.elements.set(op.opId, {
                addedBy: sender,
                addOpId: op.opId,
                cadId: op.trackCadId,
                frac: op.fracIndex > 0 ? op.fracIndex : FRAC_FIRST_INDEX,
                orderClockMs: opClockMsOf(op.opId),
                orderFracBits: fracBitsOf(op.fracIndex > 0 ? op.fracIndex : FRAC_FIRST_INDEX),
                orderOpId: op.opId,
                removedAtOpId: null,
                votes: new Map<string, bigint>(),
            });
            detectCollision(doc);
            return { applied: true, kind: 'add' };
        }
        case OP_REMOVE: {
            const target = doc.elements.get(op.targetAddOpId);
            if (!target) {
                // remove-before-add: mint the tombstone shell now.
                doc.elements.set(op.targetAddOpId, {
                    addedBy: sender,
                    addOpId: op.targetAddOpId,
                    cadId: op.trackCadId,
                    frac: FRAC_FIRST_INDEX,
                    orderClockMs: 0n,
                    orderFracBits: fracBitsOf(FRAC_FIRST_INDEX),
                    orderOpId: op.targetAddOpId,
                    removedAtOpId: op.opId,
                    votes: new Map<string, bigint>(),
                });
                return { applied: true, kind: 'remove' };
            }
            if (target.removedAtOpId !== null) {
                // Absorbing: already tombstoned (first remove wins).
                return { applied: false, kind: 'tombstoned' };
            }
            target.removedAtOpId = op.opId;
            return { applied: true, kind: 'remove' };
        }
        case OP_REORDER: {
            let target = doc.elements.get(op.targetAddOpId);
            if (!target) {
                target = mintShell(op, sender, true);
                doc.elements.set(op.targetAddOpId, target);
            }
            if (target.removedAtOpId !== null) {
                return { applied: false, kind: 'tombstoned' };
            }
            const clockMs = opClockMsOf(op.opId);
            const fracBits = fracBitsOf(op.fracIndex);
            if (orderKeyGreater(clockMs, fracBits, target.orderClockMs, target.orderFracBits)) {
                target.frac = op.fracIndex;
                target.orderClockMs = clockMs;
                target.orderFracBits = fracBits;
                target.orderOpId = op.opId;
                detectCollision(doc);
                return { applied: true, kind: 'reorder' };
            }
            return { applied: false, kind: 'duplicate' };
        }
        case OP_VOTE: {
            let target = doc.elements.get(op.targetAddOpId);
            if (!target) {
                target = mintShell(op, sender, false);
                doc.elements.set(op.targetAddOpId, target);
            }
            if (target.removedAtOpId !== null) {
                return { applied: false, kind: 'tombstoned' };
            }
            // Max-by-op-id per sender: a re-vote is a NEWER op (bigger id),
            // so the register converges under any delivery order.
            const existingVote = target.votes.get(sender);
            if (existingVote === undefined || op.opId > existingVote) {
                target.votes.set(sender, op.opId);
            }
            return { applied: true, kind: 'vote' };
        }
        default:
            doc.appliedOps.delete(op.opId);
            return { applied: false, kind: 'rejected' };
    }
}

/** Fold many ops (bootstrap journal + live tail). */
export function applyJamQueueOps(doc: JamQueueDoc, ops: JamOp[]): void {
    for (const op of ops) {
        applyJamQueueOp(doc, op);
    }
}

/**
 * Convergence fingerprint: two replicas that folded the same op SET (in any
 * order, with any duplication) produce identical strings.
 */
export function canonicalJamQueueState(doc: JamQueueDoc): string {
    const parts: string[] = [];
    for (const el of jamQueueOrderedLive(doc)) {
        const voters = [...el.votes.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1));
        parts.push(
            [
                el.addOpId.toString(10),
                el.cadId.toString(10),
                `f:${el.orderFracBits.toString(10)}`,
                `c:${el.orderClockMs.toString(10)}`,
                `v:${voters.map(([sender, op]) => `${sender}:${op.toString(10)}`).join(',')}`,
            ].join('|'),
        );
    }
    for (const el of doc.elements.values()) {
        if (el.removedAtOpId !== null) {
            parts.push(`t:${el.addOpId.toString(10)}:${el.removedAtOpId.toString(10)}`);
        }
    }
    return parts.join(';');
}

/** Live elements ordered by the composite (frac, addOpId) key. */
export function jamQueueOrderedLive(doc: JamQueueDoc): JamQueueElement[] {
    const live = [...doc.elements.values()].filter((el) => el.removedAtOpId === null);
    live.sort((a, b) => (a.frac === b.frac ? num(a.addOpId - b.addOpId) : a.frac - b.frac));
    return live;
}

export function jamQueueRespacePlan(doc: JamQueueDoc): JamRespaceStep[] {
    return jamQueueOrderedLive(doc).map((el, index) => ({
        addOpId: el.addOpId,
        newFrac: FRAC_FIRST_INDEX + index,
    }));
}

/** Upvote count = distinct senders (a re-vote overwrites, never doubles). */
export function jamQueueUpvotes(el: JamQueueElement): number {
    return el.votes.size;
}

function bytesToSenderHex(op: JamOp): string {
    let out = '';
    for (let i = 0; i < op.senderNonce.length; i += 1) {
        out += op.senderNonce[i].toString(16).padStart(2, '0');
    }
    return out;
}

/** Latch the rebalance flag when any two neighbours collide in float space. */
function detectCollision(doc: JamQueueDoc): void {
    const ordered = jamQueueOrderedLive(doc);
    for (let i = 1; i < ordered.length; i += 1) {
        if (ordered[i - 1].frac === ordered[i].frac) {
            // Same frac is legal (tie-broken by addOpId); only a sub-ULP gap
            // between DIFFERENT fracs exhausts the mantissa.
            continue;
        }
        if (fracCollide(ordered[i - 1].frac, ordered[i].frac)) {
            doc.needsRebalance = true;
            return;
        }
    }
}

function num(value: bigint): number {
    return value > 0n ? 1 : value < 0n ? -1 : 0;
}
