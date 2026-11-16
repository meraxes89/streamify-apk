/**
 * streamify/jam/playlist-crdt.ts - the playlist CRDT document (P3A).
 *
 * THE MERGE MODEL (a faithful adaptation of the Jam queue CRDT -
 * rust/crates/streamify-jam-crdt - to the playlist domain, with the one
 * extension the offline-playlist case demands):
 *
 *   - Element identity is its ADD op's `op_id` (the jam B2 lineage). Every
 *     element carries an ORDER REGISTER: (frac, orderOpId) - the fractional
 *     index plus the op_id of the Add-or-Reorder that set it. The register
 *     resolves LWW by op_id, which is a HYBRID LOGICAL TIMESTAMP
 *     (unix_ms << 16 | counter, strictly monotonic per device), so
 *     concurrent reorders converge regardless of delivery order. (The jam
 *     engine itself assumes causal delivery for reorders - honest for a
 *     live room queue, not for playlists edited offline. The register is
 *     the deterministic replacement.)
 *   - Remove ops tombstone the target element. Tombstones are absorbing:
 *     a late Add replay or a late Reorder of a tombstoned element is a
 *     no-op on every replica (delivery-order independent).
 *   - The ordered view sorts live elements by the composite key
 *     (frac_bits, add_op_id) - the jam R1 rule: concurrent inserts into
 *     the same gap produce identical fracs and the op id breaks the tie
 *     deterministically.
 *   - Playlist metadata (name/description/visibility/cover) is one LWW
 *     register keyed by the same hybrid clock; each meta write carries the
 *     full snapshot (partial-update races resolve to one whole value).
 *   - Playlist deletion is a tombstone keyed by the same clock; dead docs
 *     absorb every later op, so stale edits can never resurrect a deleted
 *     playlist.
 *
 * All of this is a pure function of the applied op SET - which is what the
 * two-replica convergence tests assert (identical state under both op
 * delivery orders).
 */

import { generateCadIdU64, playlistTrackCadId } from './cad';
import { FRAC_FIRST_INDEX, fracCollide } from './fractional-index';
import {
    fracBitsOf,
    type JamOp,
    jamOpIsValid,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    OP_VOTE,
} from './jam-op';

/** What a merge did (for logging + tests). */
export interface ApplyOpOutcome {
    applied: boolean;
    reason:
        | 'add'
        | 'add-duplicate'
        | 'add-suppressed-tombstone'
        | 'rejected'
        | 'remove'
        | 'remove-absent'
        | 'reorder'
        | 'reorder-absent'
        | 'reorder-superseded'
        | 'reorder-tombstoned'
        | 'skipped-deleted-doc';
}

/** One playlist's full CRDT state. */
export interface PlaylistCrdtDoc {
    createdAt: string;
    /** Playlist tombstone clock (null = live). Absorbing once set. */
    deletedAtOpId: bigint | null;
    /** CRDT element set keyed by add_op_id (includes tombstone shells). */
    elements: Map<bigint, PlaylistElement>;
    id: string;
    /** The metadata LWW register. */
    meta: PlaylistMetaState;
    /** The metadata register clock. */
    metaOpId: bigint;
    /** Latched when adjacent fracs fall within relative ULP range. */
    needsRebalance: boolean;
}

/** One playlist element: an Add op's lasting state. */
export interface PlaylistElement {
    /** Element identity - the ADD op's op_id (the jam B2 lineage). */
    addOpId: bigint;
    /** The content-addressed id (FNV-1a-64 of the track id). */
    cadId: bigint;
    /** The fractional order key (strictly positive finite f64). */
    frac: number;
    /** The op_id of the Add-or-Reorder that set `frac` (register clock). */
    orderOpId: bigint;
    /** True once this element has been observed in (or written to) the
     * durable table - after that, a missing row means "removed remotely".
     * Unconfirmed elements missing from the table are (re-)inserted by the
     * sync pass instead of being tombstoned (they may simply not have been
     * flushed by their origin device yet).
     */
    remoteConfirmed: boolean;
    /** Remove tombstone clock (null = live). Absorbing once set. */
    removedAtOpId: bigint | null;
    /** The durable row's id as last known (null = no row yet). Packed
     * elements derive it from (addOpId, fracBits); LEGACY v4 rows keep
     * their minted id, so it is tracked explicitly. */
    rowId: null | string;
    /** The out-of-band value (the JamTrackCodec discipline). */
    trackId: string;
}

/** The playlist metadata LWW register value (a full snapshot per write). */
export interface PlaylistMetaState {
    coverUrl: null | string;
    description: null | string;
    isPublic: boolean;
    name: string;
}

/** A jam op plus its out-of-band track id (Add ops only). */
export interface PlaylistOpWithMeta {
    op: JamOp;
    trackId: null | string;
}

export function createPlaylistDoc(
    id: string,
    meta: PlaylistMetaState,
    metaOpId: bigint,
    createdAt: string,
): PlaylistCrdtDoc {
    return {
        createdAt,
        deletedAtOpId: null,
        elements: new Map(),
        id,
        meta,
        metaOpId,
        needsRebalance: false,
    };
}

const REJECTED: ApplyOpOutcome = { applied: false, reason: 'rejected' };

export interface OrderedElement {
    cadId: bigint;
    element: PlaylistElement;
    frac: number;
    fracBits: bigint;
}

/** The PlaylistRow shape P1B's query engine already filters/sorts. */
export interface PlaylistRowView {
    coverUrl: null | string;
    createdAt: string;
    description: null | string;
    id: string;
    name: string;
    songIds: string[];
    updatedAt: string;
}

export interface SerializedPlaylistDoc {
    createdAt: string;
    deletedAtOpId: null | string;
    elements: SerializedPlaylistElement[];
    id: string;
    meta: PlaylistMetaState;
    metaOpId: string;
    needsRebalance: boolean;
}

export interface SerializedPlaylistElement {
    addOpId: string;
    cadId: string;
    frac: number;
    orderOpId: string;
    remoteConfirmed: boolean;
    removedAtOpId: null | string;
    rowId: null | string;
    trackId: string;
}

/** Apply a playlist delete (absorbing tombstone). */
export function applyPlaylistDelete(doc: PlaylistCrdtDoc, deleteOpId: bigint): boolean {
    if (doc.deletedAtOpId !== null && doc.deletedAtOpId >= deleteOpId) {
        return false;
    }
    doc.deletedAtOpId = deleteOpId;
    return true;
}

/** Apply a metadata LWW write (full-snapshot register). The comparison
 * key is (hybrid-clock ms, deterministic value order) - counter-free and
 * value-tie-broken, the exact rule the durable merge applies to the
 * playlists row, so the live lane and the sync lane agree by construction. */
export function applyPlaylistMeta(
    doc: PlaylistCrdtDoc,
    metaOpId: bigint,
    meta: PlaylistMetaState,
): boolean {
    if (doc.deletedAtOpId !== null) {
        return false;
    }
    const incomingMs = Number(metaOpId >> 16n);
    const standingMs = Number(doc.metaOpId >> 16n);
    if (
        incomingMs < standingMs ||
        (incomingMs === standingMs && metaKeyOf(meta) <= metaKeyOf(doc.meta))
    ) {
        return false; // superseded or duplicate
    }
    doc.meta = { ...meta };
    doc.metaOpId = metaOpId;
    return true;
}

/**
 * Deterministically merge one operation. Idempotent per op; the outcome is
 * a pure function of (doc, op) so any delivery order converges.
 */
export function applyPlaylistOp(
    doc: PlaylistCrdtDoc,
    opWithMeta: PlaylistOpWithMeta,
): ApplyOpOutcome {
    const op = opWithMeta.op;
    // The jam boundary gates: sealed checksum, finite frac, known op type.
    // Vote ops (4) are the jam voting domain - not a playlist op.
    if (!jamOpIsValid(op) || op.opType === OP_VOTE) {
        return REJECTED;
    }
    if (doc.deletedAtOpId !== null) {
        // Dead docs absorb everything - stale edits never resurrect.
        return { applied: false, reason: 'skipped-deleted-doc' };
    }

    switch (op.opType) {
        case OP_ADD: {
            return applyAdd(doc, op, opWithMeta.trackId);
        }
        case OP_REMOVE: {
            return applyRemove(doc, op);
        }
        case OP_REORDER: {
            return applyReorder(doc, op);
        }
        default: {
            return REJECTED;
        }
    }
}

/** Apply a respace plan (the register side of the compaction). */
export function applyRespacePlan(
    doc: PlaylistCrdtDoc,
    plan: Array<{ frac: number; orderOpId: bigint; target: PlaylistElement }>,
): void {
    for (const step of plan) {
        step.target.frac = step.frac;
        step.target.orderOpId = step.orderOpId;
    }
    doc.needsRebalance = false;
}

/** The playlist-element cad for a track id (see cad.ts). */
export function cadOfTrack(trackId: string): bigint {
    return playlistTrackCadId(trackId);
}

/**
 * A deterministic, human-readable digest of the converged state: the
 * ordered (trackId, fracBits, addOpId) triples, the metadata register
 * (clock MS + full value - the counter bits are internal bookkeeping the
 * comparison rule ignores, so adopted registers with counter 0 fingerprint
 * identically to their origin), the delete liveness, and the tombstoned
 * element set. Two replicas holding the same op set produce the same
 * string regardless of delivery order - the property the convergence
 * suite pins.
 */
export function canonicalDocState(doc: PlaylistCrdtDoc): string {
    const elements = orderedElements(doc)
        .map(
            (entry) =>
                `${entry.element.trackId}@${entry.fracBits.toString(16)}#${entry.element.addOpId.toString(10)}`,
        )
        .join(',');
    const tombstones = Array.from(doc.elements.values())
        .filter((element) => element.removedAtOpId !== null)
        .map((element) => element.addOpId.toString(10))
        .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
        .join(',');
    const meta = `${doc.meta.name}|${doc.meta.description ?? ''}|${doc.meta.isPublic}|${doc.meta.coverUrl ?? ''}`;
    const metaClockMs = Number(doc.metaOpId >> 16n);
    const deleted = doc.deletedAtOpId === null ? 'no' : 'yes';
    return `v1{deleted:${deleted};meta:${metaClockMs}:${meta};live:[${elements}];dead:[${tombstones}]}`;
}

/** The content cad (title/artist/duration) - parity with the jam engines. */
export function contentCadOfTrack(title: string, artist: string, durationSec: number): bigint {
    return generateCadIdU64(title, artist, durationSec);
}

// ---------------------------------------------------------------------------
// Rebalance (the relative-ULP latch, ported from check_rebalance_at)
// ---------------------------------------------------------------------------

export function deserializePlaylistDoc(raw: SerializedPlaylistDoc): null | PlaylistCrdtDoc {
    try {
        const elements = new Map<bigint, PlaylistElement>();
        for (const element of raw.elements ?? []) {
            const addOpId = BigInt(element.addOpId);
            elements.set(addOpId, {
                addOpId,
                cadId: BigInt(element.cadId),
                frac: element.frac,
                orderOpId: BigInt(element.orderOpId),
                remoteConfirmed: element.remoteConfirmed === true,
                removedAtOpId:
                    element.removedAtOpId === null || element.removedAtOpId === undefined
                        ? null
                        : BigInt(element.removedAtOpId),
                rowId: typeof element.rowId === 'string' ? element.rowId : null,
                trackId: element.trackId,
            });
        }
        if (typeof raw.meta?.name !== 'string') {
            return null;
        }
        return {
            createdAt: raw.createdAt,
            deletedAtOpId:
                raw.deletedAtOpId === null || raw.deletedAtOpId === undefined
                    ? null
                    : BigInt(raw.deletedAtOpId),
            elements,
            id: raw.id,
            meta: {
                coverUrl: raw.meta.coverUrl ?? null,
                description: raw.meta.description ?? null,
                isPublic: raw.meta.isPublic !== false,
                name: raw.meta.name,
            },
            metaOpId: BigInt(raw.metaOpId),
            needsRebalance: raw.needsRebalance === true,
        };
    } catch {
        return null;
    }
}

/** The doc as the controller's read paths see it. */
export function docToPlaylistRow(doc: PlaylistCrdtDoc): PlaylistRowView {
    const updatedAtMs = Number(doc.metaOpId >> 16n);
    const updatedAt = doc.metaOpId === 0n ? doc.createdAt : new Date(updatedAtMs).toISOString();
    return {
        coverUrl: doc.meta.coverUrl,
        createdAt: doc.createdAt,
        description: doc.meta.description,
        id: doc.id,
        name: doc.meta.name,
        songIds: orderedTrackIds(doc),
        updatedAt,
    };
}

/** Live elements in converged order. Tombstone shells are excluded. */
export function orderedElements(doc: PlaylistCrdtDoc): OrderedElement[] {
    const out: OrderedElement[] = [];
    for (const element of doc.elements.values()) {
        if (element.removedAtOpId !== null || element.trackId === '') {
            continue;
        }
        if (!Number.isFinite(element.frac) || element.frac <= 0) {
            continue;
        }
        out.push({
            cadId: element.cadId,
            element,
            frac: element.frac,
            fracBits: fracBitsOf(element.frac),
        });
    }
    out.sort((a, b) =>
        a.fracBits < b.fracBits
            ? -1
            : a.fracBits > b.fracBits
              ? 1
              : a.element.addOpId < b.element.addOpId
                ? -1
                : 1,
    );
    return out;
}

// ---------------------------------------------------------------------------
// Serialization (u64s as decimal strings - the bridge discipline)
// ---------------------------------------------------------------------------

/** The ordered track ids (the playlist's converged song list). */
export function orderedTrackIds(doc: PlaylistCrdtDoc): string[] {
    return orderedElements(doc).map((entry) => entry.element.trackId);
}

/**
 * The compaction pass (host-run, per the jam discipline): re-space every
 * live element on a fresh dense grid. Returns the new order register
 * assignments to mint as Reorder ops, or null when no rebalance is needed.
 * Order is preserved exactly - only the spacing changes.
 */
export function planRespace(
    doc: PlaylistCrdtDoc,
    mintOpId: () => bigint,
): Array<{ frac: number; orderOpId: bigint; target: PlaylistElement }> | null {
    if (!doc.needsRebalance) {
        return null;
    }
    const view = orderedElements(doc);
    const plan: Array<{ frac: number; orderOpId: bigint; target: PlaylistElement }> = [];
    view.forEach((entry, index) => {
        const fresh = FRAC_FIRST_INDEX + index; // the replacePlaylist grid
        if (entry.frac !== fresh) {
            plan.push({
                frac: fresh,
                orderOpId: mintOpId(),
                target: entry.element,
            });
        }
    });
    return plan;
}

export function serializePlaylistDoc(doc: PlaylistCrdtDoc): SerializedPlaylistDoc {
    return {
        createdAt: doc.createdAt,
        deletedAtOpId: doc.deletedAtOpId === null ? null : doc.deletedAtOpId.toString(10),
        elements: Array.from(doc.elements.values()).map((element) => ({
            addOpId: element.addOpId.toString(10),
            cadId: element.cadId.toString(10),
            frac: element.frac,
            orderOpId: element.orderOpId.toString(10),
            remoteConfirmed: element.remoteConfirmed,
            removedAtOpId:
                element.removedAtOpId === null ? null : element.removedAtOpId.toString(10),
            rowId: element.rowId,
            trackId: element.trackId,
        })),
        id: doc.id,
        meta: { ...doc.meta },
        metaOpId: doc.metaOpId.toString(10),
        needsRebalance: doc.needsRebalance,
    };
}

function applyAdd(doc: PlaylistCrdtDoc, op: JamOp, trackId: null | string): ApplyOpOutcome {
    const existing = doc.elements.get(op.opId);
    if (existing !== undefined) {
        if (existing.removedAtOpId !== null) {
            // B2: late replay after Remove - suppressed regardless of order.
            return { applied: false, reason: 'add-suppressed-tombstone' };
        }
        // The element exists: its order register is owned by the Add (this
        // op) or a later Reorder - a replay is always inert. (The jam
        // engine re-inserts the old BTreeMap key here, which duplicates an
        // entry a reorder already moved; the register model closes that.)
        return { applied: false, reason: 'add-duplicate' };
    }
    if (trackId === null || trackId === '') {
        // An Add with no resolvable value cannot enter (the out-of-band
        // mapping is mandatory - a bare cad is not invertible).
        return REJECTED;
    }
    const element: PlaylistElement = {
        addOpId: op.opId,
        cadId: op.trackCadId,
        frac: op.fracIndex,
        orderOpId: op.opId,
        remoteConfirmed: false,
        removedAtOpId: null,
        rowId: null,
        trackId,
    };
    doc.elements.set(op.opId, element);
    checkRebalanceAfterInsert(doc, element);
    return { applied: true, reason: 'add' };
}

// ---------------------------------------------------------------------------
// The canonical state fingerprint (convergence assertions + sync diffs)
// ---------------------------------------------------------------------------

function applyRemove(doc: PlaylistCrdtDoc, op: JamOp): ApplyOpOutcome {
    if (op.targetAddOpId === 0n) {
        return REJECTED; // Add ops mint op_ids >= 1; 0 is hostile
    }
    const existing = doc.elements.get(op.targetAddOpId);
    if (existing === undefined) {
        // Remove-before-Add: record a tombstone shell so the late Add is
        // suppressed when it arrives (the jam tombstones-map semantics).
        doc.elements.set(op.targetAddOpId, {
            addOpId: op.targetAddOpId,
            cadId: op.trackCadId,
            frac: 0,
            orderOpId: 0n,
            remoteConfirmed: false,
            removedAtOpId: op.opId,
            rowId: null,
            trackId: '',
        });
        return { applied: true, reason: 'remove-absent' };
    }
    if (existing.removedAtOpId !== null && existing.removedAtOpId >= op.opId) {
        return { applied: false, reason: 'remove-absent' };
    }
    existing.removedAtOpId = op.opId;
    return { applied: true, reason: 'remove' };
}

// ---------------------------------------------------------------------------
// Row-facing helpers (the sync + controller seam)
// ---------------------------------------------------------------------------

function applyReorder(doc: PlaylistCrdtDoc, op: JamOp): ApplyOpOutcome {
    const existing = doc.elements.get(op.targetAddOpId);
    if (existing === undefined) {
        return { applied: false, reason: 'reorder-absent' };
    }
    if (existing.removedAtOpId !== null) {
        // Tombstones are absorbing - a late reorder never resurrects.
        return { applied: false, reason: 'reorder-tombstoned' };
    }
    // Register LWW by (hybrid-clock ms, frac bits) - the counter-free key
    // that the durable merge computes identically (see registerKey).
    const [incomingMs, incomingBits] = registerKey(op.opId, op.fracIndex);
    const [standingMs, standingBits] = registerKey(existing.orderOpId, existing.frac);
    if (incomingMs < standingMs || (incomingMs === standingMs && incomingBits <= standingBits)) {
        return { applied: false, reason: 'reorder-superseded' };
    }
    existing.frac = op.fracIndex;
    existing.orderOpId = op.opId;
    checkRebalanceAfterInsert(doc, existing);
    return { applied: true, reason: 'reorder' };
}

function checkRebalanceAfterInsert(doc: PlaylistCrdtDoc, element: PlaylistElement): void {
    if (doc.elements.size < 2) {
        return;
    }
    const view = orderedElements(doc);
    const index = view.findIndex((entry) => entry.element.addOpId === element.addOpId);
    if (index < 0) {
        return;
    }
    const me = view[index].frac;
    const prev = index > 0 ? view[index - 1].frac : null;
    const next = index < view.length - 1 ? view[index + 1].frac : null;
    // Removals only widen gaps; a violation can only form at an insertion.
    if (prev !== null) {
        const ulp = Math.max(Math.abs(me), Math.abs(prev)) * Number.EPSILON;
        if (me - prev <= ulp * 2 || fracCollide(prev, me)) {
            doc.needsRebalance = true;
        }
    }
    if (next !== null) {
        const ulp = Math.max(Math.abs(me), Math.abs(next)) * Number.EPSILON;
        if (next - me <= ulp * 2 || fracCollide(me, next)) {
            doc.needsRebalance = true;
        }
    }
}

// ---------------------------------------------------------------------------
// The ordered view (the composite (frac_bits, add_op_id) key - jam R1)
// ---------------------------------------------------------------------------
/** Deterministic full-value comparison key (the same-ms tie-break,
 * identical to the sync pass's row rule). */
function metaKeyOf(meta: PlaylistMetaState): string {
    return `${meta.name}\u0000${meta.description ?? ''}\u0000${meta.isPublic ? '1' : '0'}\u0000${meta.coverUrl ?? ''}`;
}

/** The register comparison key: (hybrid-clock ms, frac bits). The counter
 * bits of the op id are DELIBERATELY excluded - the durable table carries
 * only the ms half (added_at), so any rule that depended on the counter
 * would diverge between the origin device and an adopter. (ms, fracBits)
 * is computable identically from an op and from a row, which makes the
 * live lane and the anti-entropy lane agree BY CONSTRUCTION. */
function registerKey(opId: bigint, frac: number): [number, bigint] {
    return [Number(opId >> 16n), fracBitsOf(frac)];
}
