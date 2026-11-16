/**
 * streamify/jam-live/jam-live-wire.ts - the frozen 48-byte Jam op row for
 * the listening-room lane (P4B).
 *
 * POST-#190-REBASE CONSOLIDATION: the codec, the op-id mint, and the byte
 * helpers are a pure re-export of P3A's `streamify/jam/jam-op.ts` (the
 * byte-identical Rust/Android/wasm port - see
 * fixtures/jam_golden_vectors.json, which the listening-room tests pin).
 * The LISTENING-ROOM ADDITIONS live here: the `jam_ops` journal-row
 * normalizer (the shape Supabase Realtime/PostgREST delivers) and the
 * policy-flag constants the room permission layer reads.
 */

import { fracFromBits, type JamOp, jamOpNew, OP_ADD, OP_VOTE } from '../jam/jam-op';

export * from '../jam/jam-op';

/** policy_flags bits the listening room uses. */
export const POLICY_NONE = 0;
/** Minted by the host (permission-bearing ops; see the ADR sync protocol). */
export const POLICY_HOST = 0x01;

/**
 * A `jam_ops` journal row as Supabase delivers it (PostgREST SELECT and
 * Realtime postgres_changes alike). u64 columns arrive as decimal string
 * when PostgREST serializes them, but BIGINT JSON numbers are also accepted
 * defensively - the web transport documents the same caveat.
 */
export interface JamJournalRow {
    frac_bits: number | string;
    op_id: number | string;
    op_type: number;
    policy_flags?: null | number;
    sender_nonce: number | string;
    target_add_op_id?: null | number | string;
    track_cad_id: number | string;
}

/**
 * Normalize a `jam_ops` journal row into a sealed op. The journal is
 * server-validated (types, ranges, membership), but every field is still
 * checked here: Realtime payloads are untrusted input like any other wire
 * data. The checksum is minted locally (the row does not carry one), so a
 * folded op is byte-identical to one the engines would have packed.
 */
export function jamOpFromJournalRow(row: JamJournalRow): JamOp | null {
    if (!Number.isInteger(row.op_type) || row.op_type < OP_ADD || row.op_type > OP_VOTE) {
        return null;
    }
    let opId: bigint;
    let nonce: bigint;
    let cadId: bigint;
    let fracBits: bigint;
    let target: bigint;
    try {
        opId = BigInt(row.op_id);
        nonce = BigInt(row.sender_nonce);
        cadId = BigInt(row.track_cad_id);
        fracBits = BigInt(row.frac_bits);
        target = BigInt(row.target_add_op_id ?? 0);
    } catch {
        return null;
    }
    if (opId < 0n || nonce < 0n || cadId < 0n || fracBits < 0n || target < 0n) {
        return null;
    }
    if (nonce > 0xffffffffn) {
        return null;
    }
    const frac = fracFromBits(fracBits);
    if (!Number.isFinite(frac)) {
        return null;
    }
    const policy = row.policy_flags ?? 0;
    if (!Number.isInteger(policy) || policy < 0 || policy > 0xff) {
        return null;
    }
    const nonceBytes = new Uint8Array(4);
    new DataView(nonceBytes.buffer).setUint32(0, Number(nonce), true);
    return jamOpNew(opId, nonceBytes, row.op_type, policy, cadId, frac, target);
}
