/**
 * jam-queue-crdt tests (P4B) - the collaborative queue document.
 *
 * The convergence battery: two replicas fold the SAME op set in different
 * orders (and with duplication) and must produce identical canonical
 * state. Plus the register rules: absorbing tombstones, counter-free
 * reorder LWW, per-sender votes, idempotency, and the host respace plan.
 */

import { describe, expect, it } from 'vitest';

import {
    jamOpNew,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    OP_VOTE,
    resetOpMintForTests,
} from '../jam-live/jam-live-wire';
import {
    applyJamQueueOp,
    applyJamQueueOps,
    canonicalJamQueueState,
    createJamQueueDoc,
    type JamQueueDoc,
    jamQueueOrderedLive,
    jamQueueRespacePlan,
    jamQueueUpvotes,
    opClockMsOf,
} from '../jam-live/jam-queue-crdt';

const NONCE_A = new Uint8Array([0xaa, 0x00, 0x00, 0x01]);
const NONCE_B = new Uint8Array([0xbb, 0x00, 0x00, 0x02]);

const addOp = (opId: bigint, cadId: bigint, frac: number, nonce = NONCE_A) =>
    jamOpNew(opId, nonce, OP_ADD, 0, cadId, frac, 0n);
const removeOp = (opId: bigint, target: bigint, nonce = NONCE_A) =>
    jamOpNew(opId, nonce, OP_REMOVE, 0, 0n, 0, target);
const reorderOp = (opId: bigint, target: bigint, frac: number, nonce = NONCE_A) =>
    jamOpNew(opId, nonce, OP_REORDER, 0, 0n, frac, target);
const voteOp = (opId: bigint, target: bigint, nonce = NONCE_A) =>
    jamOpNew(opId, nonce, OP_VOTE, 0, 0n, 0, target);

const foldBothOrders = (ops: ReturnType<typeof addOp>[]) => {
    const forward = createJamQueueDoc();
    applyJamQueueOps(forward, ops);
    const reversed = createJamQueueDoc();
    applyJamQueueOps(reversed, [...ops].reverse());
    const duplicated = createJamQueueDoc();
    applyJamQueueOps(duplicated, [...ops, ...ops]); // at-least-once delivery
    return { duplicated, forward, reversed };
};

describe('jam-queue-crdt: ordering and adds', () => {
    it('orders live elements by (frac, addOpId)', () => {
        resetOpMintForTests(1000n);
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(1n << 16n, 101n, 1.5),
            addOp(2n << 16n, 102n, 0.5),
            addOp(3n << 16n, 103n, 2.5),
            addOp(4n << 16n, 104n, 0.5), // same frac, higher op id -> after
        ]);
        const ordered = jamQueueOrderedLive(doc);
        expect(ordered.map((el) => el.cadId)).toEqual([102n, 104n, 101n, 103n]);
    });

    it('converges concurrent adds in both delivery orders', () => {
        resetOpMintForTests(2000n);
        const ops = [
            addOp(1n << 16n, 201n, 0.5, NONCE_A),
            addOp(2n << 16n, 202n, 1.5, NONCE_B),
            addOp(3n << 16n, 203n, 1.0, NONCE_A),
        ];
        const { duplicated, forward, reversed } = foldBothOrders(ops);
        const state = canonicalJamQueueState(forward);
        expect(canonicalJamQueueState(reversed)).toBe(state);
        expect(canonicalJamQueueState(duplicated)).toBe(state);
        expect(jamQueueOrderedLive(forward).map((el) => el.cadId)).toEqual([201n, 203n, 202n]);
    });
});

describe('jam-queue-crdt: tombstones', () => {
    it('removes are absorbing across delivery orders', () => {
        resetOpMintForTests(3000n);
        const ops = [
            addOp(1n << 16n, 301n, 0.5),
            removeOp(2n << 16n, 1n << 16n),
            reorderOp(3n << 16n, 1n << 16n, 9.5), // late reorder of a tombstone
        ];
        const { forward, reversed } = foldBothOrders(ops);
        expect(jamQueueOrderedLive(forward)).toHaveLength(0);
        expect(canonicalJamQueueState(forward)).toBe(canonicalJamQueueState(reversed));
    });

    it('remove-before-add mints a tombstone shell (no resurrection)', () => {
        resetOpMintForTests(4000n);
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [removeOp(1n << 16n, 5n << 16n), addOp(5n << 16n, 401n, 0.5)]);
        expect(jamQueueOrderedLive(doc)).toHaveLength(0);
        const shell = doc.elements.get(5n << 16n);
        expect(shell?.removedAtOpId).toBe(1n << 16n);
    });

    it('a second remove is a no-op (first wins)', () => {
        resetOpMintForTests(4100n);
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(1n << 16n, 411n, 0.5),
            removeOp(2n << 16n, 1n << 16n),
            removeOp(3n << 16n, 1n << 16n),
        ]);
        expect(doc.elements.get(1n << 16n)?.removedAtOpId).toBe(2n << 16n);
    });
});

describe('jam-queue-crdt: reorder register (LWW by the counter-free key)', () => {
    it('applies the greater (clockMs, fracBits) register write', () => {
        resetOpMintForTests(5000n);
        const target = 1n << 16n;
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [addOp(target, 501n, 0.5)]);
        // Later clock wins regardless of delivery order...
        applyJamQueueOp(doc, reorderOp(10n << 16n, target, 5.0));
        applyJamQueueOp(doc, reorderOp(5n << 16n, target, 2.0)); // older clock
        expect(doc.elements.get(target)?.frac).toBe(5.0);

        // ...and in the other order:
        const doc2 = createJamQueueDoc();
        applyJamQueueOps(doc2, [addOp(target, 501n, 0.5)]);
        applyJamQueueOp(doc2, reorderOp(5n << 16n, target, 2.0));
        applyJamQueueOp(doc2, reorderOp(10n << 16n, target, 5.0));
        expect(doc2.elements.get(target)?.frac).toBe(5.0);
        expect(canonicalJamQueueState(doc)).toBe(canonicalJamQueueState(doc2));
    });

    it('concurrent reorders converge (last-by-register-key wins)', () => {
        resetOpMintForTests(5100n);
        const target = 1n << 16n;
        const ops = [
            addOp(target, 511n, 0.5),
            reorderOp(2n << 16n, target, 3.0, NONCE_A),
            reorderOp(3n << 16n, target, 7.0, NONCE_B),
        ];
        const { forward, reversed } = foldBothOrders(ops);
        expect(canonicalJamQueueState(forward)).toBe(canonicalJamQueueState(reversed));
        expect(forward.elements.get(target)?.frac).toBe(7.0);
    });
});

describe('jam-queue-crdt: votes', () => {
    it('counts one vote per sender (re-vote overwrites, never doubles)', () => {
        resetOpMintForTests(6000n);
        const target = 1n << 16n;
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(target, 601n, 0.5),
            voteOp(2n << 16n, target, NONCE_A),
            voteOp(3n << 16n, target, NONCE_B),
            voteOp(4n << 16n, target, NONCE_A), // A re-votes
        ]);
        expect(jamQueueUpvotes(doc.elements.get(target)!)).toBe(2);

        const reversed = createJamQueueDoc();
        applyJamQueueOps(reversed, [
            voteOp(4n << 16n, target, NONCE_A),
            voteOp(3n << 16n, target, NONCE_B),
            voteOp(2n << 16n, target, NONCE_A),
            addOp(target, 601n, 0.5),
        ]);
        expect(canonicalJamQueueState(doc)).toBe(canonicalJamQueueState(reversed));
    });

    it('votes on tombstoned elements are no-ops', () => {
        resetOpMintForTests(6100n);
        const target = 1n << 16n;
        const doc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(target, 611n, 0.5),
            removeOp(2n << 16n, target),
            voteOp(3n << 16n, target, NONCE_B),
        ]);
        expect(jamQueueUpvotes(doc.elements.get(target)!)).toBe(0);
    });
});

describe('jam-queue-crdt: idempotency and corruption', () => {
    it('never applies the same op id twice', () => {
        resetOpMintForTests(7000n);
        const doc = createJamQueueDoc();
        const op = addOp(1n << 16n, 701n, 0.5);
        expect(applyJamQueueOp(doc, op).applied).toBe(true);
        expect(applyJamQueueOp(doc, op)).toEqual({ applied: false, kind: 'duplicate' });
        expect(jamQueueOrderedLive(doc)).toHaveLength(1);
    });

    it('exposes the clock-ms slice of an op id', () => {
        expect(opClockMsOf((5000n << 16n) | 0x1234n)).toBe(5000n);
    });
});

describe('jam-queue-crdt: host respace plan', () => {
    it('re-spaces live elements on the 0.5 + i grid, in order', () => {
        resetOpMintForTests(8000n);
        const doc: JamQueueDoc = createJamQueueDoc();
        applyJamQueueOps(doc, [
            addOp(1n << 16n, 801n, 3.5),
            addOp(2n << 16n, 802n, 0.25),
            addOp(3n << 16n, 803n, 1.125),
            removeOp(4n << 16n, 2n << 16n),
        ]);
        const plan = jamQueueRespacePlan(doc);
        expect(plan.map((step) => step.addOpId)).toEqual([3n << 16n, 1n << 16n]);
        expect(plan.map((step) => step.newFrac)).toEqual([0.5, 1.5]);
    });
});
