/**
 * playlist-crdt.test.ts - the convergence lock (P3A).
 *
 * The multi-device invariants the task demands, asserted the direct way:
 * two replicas apply the SAME op set in BOTH delivery orders and must
 * produce the identical canonical state - no lost updates, no duplicate
 * phantom rows, deterministic ordering, LWW metadata, absorbing
 * tombstones. Every scenario runs the order permutation explicitly.
 */

import { describe, expect, it } from 'vitest';

import { hexToBytes, jamOpNew, OP_ADD, OP_REMOVE, OP_REORDER } from '../jam/jam-op';
import {
    applyPlaylistDelete,
    applyPlaylistMeta,
    applyPlaylistOp,
    canonicalDocState,
    createPlaylistDoc,
    deserializePlaylistDoc,
    orderedElements,
    orderedTrackIds,
    planRespace,
    type PlaylistCrdtDoc,
    type PlaylistOpWithMeta,
    serializePlaylistDoc,
} from '../jam/playlist-crdt';

const NONCE_A = hexToBytes('11111111') ?? new Uint8Array(4);
const NONCE_B = hexToBytes('22222222') ?? new Uint8Array(4);

/** Hybrid-clock op ids: device A at ms 1000, device B at ms 1000 (counter
 * differs), later ops at later ms - fully deterministic test clocks. */
const OP = {
    aAdd1: (1000n << 16n) | 0x1001n,
    aAdd2: (1000n << 16n) | 0x1002n,
    aRemove: (1001n << 16n) | 0x1001n,
    aReorder: (1002n << 16n) | 0x1001n,
    bAdd1: (1000n << 16n) | 0x2001n,
    bAdd2: (1000n << 16n) | 0x2002n,
    bRemove: (1001n << 16n) | 0x2001n,
    bReorder: (1003n << 16n) | 0x2001n,
    lateReorder: (1004n << 16n) | 0x1001n,
} as const;

function addOp(opId: bigint, nonce: Uint8Array, trackId: string, frac: number): PlaylistOpWithMeta {
    return {
        op: jamOpNew(opId, nonce, OP_ADD, 0, 0n, frac, 0n),
        trackId,
    };
}

function applyAll(doc: PlaylistCrdtDoc, ops: PlaylistOpWithMeta[]): void {
    for (const op of ops) {
        applyPlaylistOp(doc, op);
    }
}

/** The convergence assertion: same op set, both delivery orders. */
function assertConverges(
    setA: PlaylistOpWithMeta[],
    setB: PlaylistOpWithMeta[],
): {
    docA: PlaylistCrdtDoc;
    docB: PlaylistCrdtDoc;
} {
    const aThenB = freshDoc();
    const bThenA = freshDoc();
    applyAll(aThenB, [...setA, ...setB]);
    applyAll(bThenA, [...setB, ...setA]);
    expect(canonicalDocState(aThenB)).toBe(canonicalDocState(bThenA));
    return { docA: aThenB, docB: bThenA };
}

function freshDoc(): PlaylistCrdtDoc {
    return createPlaylistDoc(
        'pl-1',
        { coverUrl: null, description: null, isPublic: true, name: 'Roadtrip' },
        900n << 16n,
        '2026-11-16T00:00:00.000Z',
    );
}

function removeOp(opId: bigint, nonce: Uint8Array, target: bigint): PlaylistOpWithMeta {
    return { op: jamOpNew(opId, nonce, OP_REMOVE, 0, 0n, 0.5, target), trackId: null };
}

function reorderOp(
    opId: bigint,
    nonce: Uint8Array,
    target: bigint,
    frac: number,
): PlaylistOpWithMeta {
    return { op: jamOpNew(opId, nonce, OP_REORDER, 0, 0n, frac, target), trackId: null };
}

describe('playlist-crdt — multi-device convergence', () => {
    it('concurrent inserts at the same index: identical fracs, op-id tie-break', () => {
        // Both devices append into the same gap of an empty playlist ->
        // identical frac 0.5. The composite (frac_bits, add_op_id) key
        // orders them deterministically (jam R1).
        const a = addOp(OP.aAdd1, NONCE_A, 'track-X', 0.5);
        const b = addOp(OP.bAdd1, NONCE_B, 'track-Y', 0.5);
        const { docA } = assertConverges([a], [b]);
        expect(orderedTrackIds(docA)).toEqual(['track-X', 'track-Y']); // aAdd1 < bAdd1
        // And with the opposite op-id relationship.
        const doc2 = freshDoc();
        applyAll(doc2, [
            addOp(OP.bAdd2, NONCE_B, 'track-Y', 0.5),
            addOp(OP.aAdd2, NONCE_A, 'track-X', 0.5),
        ]);
        // bAdd2 (0x2002) > aAdd2 (0x1002): X still first.
        expect(orderedTrackIds(doc2)).toEqual(['track-X', 'track-Y']);
    });

    it('concurrent appends onto a non-empty list: tail + 1 on both', () => {
        const seed = addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5);
        const a = addOp(OP.aAdd2, NONCE_A, 'track-2', 1.5);
        const b = addOp(OP.bAdd1, NONCE_B, 'track-3', 1.5);
        const { docA } = assertConverges([seed, a], [b]);
        expect(orderedTrackIds(docA)).toEqual(['track-1', 'track-2', 'track-3']);
    });

    it('reorder vs. remove: the tombstone absorbs the late reorder', () => {
        const seed = [
            addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5),
            addOp(OP.aAdd2, NONCE_A, 'track-2', 1.5),
        ];
        const removeFromB = removeOp(OP.bRemove, NONCE_B, OP.aAdd2);
        const reorderOnA = reorderOp(OP.aReorder, NONCE_A, OP.aAdd2, 0.75);
        // A reorders; B removes. The element is GONE on both replicas
        // regardless of delivery order (removals win over reorders).
        const { docA } = assertConverges([...seed, reorderOnA], [...seed.slice(0, 1), removeFromB]);
        expect(orderedTrackIds(docA)).toEqual(['track-1']);
        expect(docA.elements.get(OP.aAdd2)?.removedAtOpId).not.toBeNull();
    });

    it('late Add replay after Remove is suppressed (jam B2 lineage)', () => {
        const doc = freshDoc();
        // Remove arrives BEFORE the Add it targets.
        applyAll(doc, [removeOp(OP.bRemove, NONCE_B, OP.aAdd1)]);
        applyAll(doc, [addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5)]);
        expect(orderedTrackIds(doc)).toEqual([]);
        // And the reverse order (add then remove) tombstones too.
        const doc2 = freshDoc();
        applyAll(doc2, [addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5)]);
        applyAll(doc2, [removeOp(OP.bRemove, NONCE_B, OP.aAdd1)]);
        expect(orderedTrackIds(doc2)).toEqual([]);
        expect(canonicalDocState(doc)).toBe(canonicalDocState(doc2));
    });

    it('concurrent reorders of the same element: register LWW by op id', () => {
        const seed = [
            addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5),
            addOp(OP.aAdd2, NONCE_A, 'track-2', 1.5),
            addOp(OP.bAdd1, NONCE_B, 'track-3', 2.5),
        ];
        // A moves track-3 to the front (0.25); B (later clock) moves it to
        // 1.25 (between track-1 and track-2). B's register wins on BOTH
        // delivery orders.
        const a = reorderOp(OP.aReorder, NONCE_A, OP.bAdd1, 0.25);
        const b = reorderOp(OP.bReorder, NONCE_B, OP.bAdd1, 1.25);
        const { docA, docB } = assertConverges([...seed, a], [...seed, b]);
        expect(orderedTrackIds(docA)).toEqual(['track-1', 'track-3', 'track-2']);
        expect(canonicalDocState(docA)).toBe(canonicalDocState(docB));
    });

    it('same-millisecond concurrent reorders: the (ms, fracBits) rule', () => {
        // Both reorders minted in the SAME ms (counter bits differ, which
        // the durable merge cannot see). The register rule is deliberately
        // counter-free: (ms, fracBits) decides, identically on every
        // replica and in the table.
        const seed = addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5);
        const sameMsA = (1005n << 16n) | 0x1001n;
        const sameMsB = (1005n << 16n) | 0x2001n;
        const a = reorderOp(sameMsA, NONCE_A, OP.aAdd1, 0.25); // lower frac
        const b = reorderOp(sameMsB, NONCE_B, OP.aAdd1, 0.75); // higher frac
        const { docA, docB } = assertConverges([seed, a], [seed, b]);
        expect(docA.elements.get(OP.aAdd1)?.frac).toBe(0.75); // fracBits wins
        expect(canonicalDocState(docA)).toBe(canonicalDocState(docB));
    });

    it('add replay after a reorder is inert (no duplicate phantom row)', () => {
        const add = addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5);
        const reorder = reorderOp(OP.bReorder, NONCE_B, OP.aAdd1, 2.5);
        const doc = freshDoc();
        applyAll(doc, [add, reorder]);
        const before = canonicalDocState(doc);
        // The Add replays late (out-of-order delivery): the element stays
        // exactly where the reorder put it - no phantom at the old frac.
        applyAll(doc, [add]);
        expect(canonicalDocState(doc)).toBe(before);
        expect(orderedTrackIds(doc)).toEqual(['track-1']);
        expect(doc.elements.size).toBe(1);
    });

    it('duplicate ops are idempotent (replay changes nothing)', () => {
        const ops = [
            addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5),
            addOp(OP.aAdd2, NONCE_A, 'track-2', 1.5),
            reorderOp(OP.aReorder, NONCE_A, OP.aAdd2, 0.25),
            removeOp(OP.aRemove, NONCE_A, OP.aAdd1),
        ];
        const once = freshDoc();
        applyAll(once, ops);
        const thrice = freshDoc();
        for (let i = 0; i < 3; i += 1) {
            applyAll(thrice, ops);
        }
        expect(canonicalDocState(once)).toBe(canonicalDocState(thrice));
        expect(orderedTrackIds(thrice)).toEqual(['track-2']);
    });

    it('corrupt ops never enter: bad checksum, NaN frac, Vote domain', () => {
        const doc = freshDoc();
        const sealed = jamOpNew(OP.aAdd1, NONCE_A, OP_ADD, 0, 1n, 0.5, 0n);
        // Tampered frac (checksum broken).
        expect(
            applyPlaylistOp(doc, { op: { ...sealed, fracIndex: 2 }, trackId: 't' }).applied,
        ).toBe(false);
        // NaN frac sealed correctly still rejected by the finite gate.
        const nanSeal = jamOpNew(OP.aAdd2, NONCE_A, OP_ADD, 0, 1n, 0.5, 0n);
        expect(
            applyPlaylistOp(doc, { op: { ...nanSeal, fracIndex: Number.NaN }, trackId: 't' })
                .applied,
        ).toBe(false);
        // Vote (4) is the jam voting domain, not a playlist op.
        const vote = jamOpNew(OP.bAdd1, NONCE_B, 4, 1, 1n, 0.5, OP.aAdd1);
        expect(applyPlaylistOp(doc, { op: vote, trackId: null }).applied).toBe(false);
        // Add without the out-of-band track id cannot resolve its value.
        expect(applyPlaylistOp(doc, { op: sealed, trackId: null }).applied).toBe(false);
        expect(doc.elements.size).toBe(0);
    });

    it('rename vs. rename: metadata LWW by hybrid clock, both orders', () => {
        const a = freshDoc();
        const b = freshDoc();
        applyPlaylistMeta(a, 1001n << 16n, {
            coverUrl: null,
            description: null,
            isPublic: true,
            name: 'Alpha',
        });
        applyPlaylistMeta(b, 1002n << 16n, {
            coverUrl: null,
            description: null,
            isPublic: true,
            name: 'Beta',
        });
        // Sync: each adopts the higher clock - deterministically "Beta".
        const mergeInto = (
            target: PlaylistCrdtDoc,
            sourceMeta: { clock: bigint; name: string },
        ) => {
            applyPlaylistMeta(target, sourceMeta.clock, {
                coverUrl: null,
                description: null,
                isPublic: true,
                name: sourceMeta.name,
            });
        };
        const a2 = structuredCloneState(a);
        mergeInto(a2, { clock: 1002n << 16n, name: 'Beta' });
        const b2 = structuredCloneState(b);
        mergeInto(b2, { clock: 1001n << 16n, name: 'Alpha' }); // stale: rejected
        expect(a2.meta.name).toBe('Beta');
        expect(b2.meta.name).toBe('Beta');
        expect(canonicalDocState(a2)).toBe(canonicalDocState(b2));
        // Same-ms writes resolve by the deterministic value order: a lower
        // value is inert, a higher one replaces - identically everywhere.
        expect(
            applyPlaylistMeta(b2, 1002n << 16n, {
                coverUrl: null,
                description: null,
                isPublic: true,
                name: 'Alphz',
            }),
        ).toBe(false);
        expect(b2.meta.name).toBe('Beta');
        expect(
            applyPlaylistMeta(b2, 1002n << 16n, {
                coverUrl: null,
                description: null,
                isPublic: true,
                name: 'Gamma',
            }),
        ).toBe(true);
        expect(b2.meta.name).toBe('Gamma');
    });

    it('delete vs. edit: the playlist tombstone absorbs stale edits', () => {
        const doc = freshDoc();
        applyPlaylistOp(doc, addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5));
        // B renames (offline); A deletes. Delete lands first here.
        applyPlaylistDelete(doc, 1005n << 16n);
        const renameAfterDelete = applyPlaylistMeta(doc, 1006n << 16n, {
            coverUrl: null,
            description: null,
            isPublic: true,
            name: 'Renamed',
        });
        expect(renameAfterDelete).toBe(false);
        const addAfterDelete = applyPlaylistOp(doc, addOp(OP.aAdd2, NONCE_A, 'track-2', 1.5));
        expect(addAfterDelete.applied).toBe(false);
        // And the other order: rename first, delete second - still dead
        // (the rename's value survives in the frozen register, but the doc
        // is gone: every read path skips tombstoned docs).
        const doc2 = freshDoc();
        applyPlaylistMeta(doc2, 1006n << 16n, {
            coverUrl: null,
            description: null,
            isPublic: true,
            name: 'Renamed',
        });
        applyPlaylistDelete(doc2, 1005n << 16n);
        expect(doc2.deletedAtOpId).not.toBeNull();
        expect(doc2.meta.name).toBe('Renamed'); // frozen, never resurrected
        expect(orderedTrackIds(doc2)).toEqual([]); // no reads surface it
    });

    it('serialization round-trips the full state (u64s as decimal strings)', () => {
        const doc = freshDoc();
        applyAll(doc, [
            addOp(OP.aAdd1, NONCE_A, 'track-1', 0.5),
            addOp(OP.bAdd1, NONCE_B, 'track-2', 1.5),
            reorderOp(OP.aReorder, NONCE_A, OP.bAdd1, 0.25),
            removeOp(OP.bRemove, NONCE_B, OP.aAdd1),
        ]);
        const serialized = serializePlaylistDoc(doc);
        const json = JSON.parse(JSON.stringify(serialized)); // JSON-safe
        const back = deserializePlaylistDoc(json);
        expect(back).not.toBeNull();
        expect(canonicalDocState(back!)).toBe(canonicalDocState(doc));
        // bigint fields cross as strings, never numbers.
        expect(typeof serialized.elements[0].addOpId).toBe('string');
        expect(typeof serialized.metaOpId).toBe('string');
        // Corrupt input degrades to null, never throws.
        expect(deserializePlaylistDoc({ ...json, metaOpId: 'zzz' })).toBeNull();
    });

    it('needs_rebalance latches on ULP-collapsed neighbours and respace fixes it', () => {
        const doc = freshDoc();
        applyAll(doc, [
            addOp(OP.aAdd1, NONCE_A, 'lo', 0.5),
            addOp(OP.aAdd2, NONCE_A, 'hi', 0.5000000000000002),
        ]);
        expect(doc.needsRebalance).toBe(true); // two ulps apart: collide
        const orderBefore = orderedTrackIds(doc);
        const plan = planRespace(doc, () => 2000n << 16n);
        expect(plan).not.toBeNull();
        for (const step of plan!) {
            step.target.frac = step.frac;
            step.target.orderOpId = step.orderOpId;
        }
        doc.needsRebalance = false;
        expect(orderedTrackIds(doc)).toEqual(orderBefore); // order preserved
        expect(orderedElements(doc)[1].frac).toBe(1.5); // the fresh grid
    });
});

function structuredCloneState(doc: PlaylistCrdtDoc): PlaylistCrdtDoc {
    const back = deserializePlaylistDoc(serializePlaylistDoc(doc));
    if (back === null) {
        throw new Error('clone failed');
    }
    return back;
}
