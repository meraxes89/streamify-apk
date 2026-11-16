/**
 * streamify/jam/jam-op.ts - the frozen 48-byte Jam op row, ported 1:1 from
 * `rust/crates/streamify-jam-crdt/src/jam_crdt.rs` (P3A: Jam CRDT playlist
 * backing).
 *
 * FORMAT CONTRACT (byte-identical to the Rust/Android/wasm engines - see
 * fixtures/jam_golden_vectors.json, which pins this image across runtimes):
 *
 *   [0..8)    op_id            u64 LE  (unix_ms << 16 | 16-bit counter)
 *   [8..12)   sender_nonce     [u8;4]  (device identity)
 *   [12]      op_type          u8      (1=Add 2=Remove 3=Reorder 4=Vote)
 *   [13]      policy_flags     u8
 *   [14..16)  pad1             zeros (non-zero rows are rejected)
 *   [16..24)  track_cad_id     u64 LE
 *   [24..32)  frac_index       f64 raw IEEE-754 bits LE
 *   [32..40)  target_add_op_id u64 LE
 *   [40..44)  checksum         u32 LE  (FNV-1a-32 over bytes [0..40))
 *   [44..48)  pad2             zeros (non-zero rows are rejected)
 *
 * THE u64 BRIDGE DISCIPLINE (mirrors front-end/web/src/lib/jamEngine.ts):
 * op ids and cad ids are unix_ms<<16-class values - above 2^53, where JS
 * numbers lose exactness. They are held as bigint here and cross every
 * serialization boundary as decimal STRINGS. The 48-byte row itself crosses
 * as bytes, so precision is exact everywhere it matters.
 */

/** Wire size of one JamOp row (repr(C): 48 bytes, naturally aligned). */
export const JAM_OP_SIZE = 48;

/** op_type values (mirrors Rust OpType). */
export const OP_ADD = 1;
export const OP_REMOVE = 2;
export const OP_REORDER = 3;
export const OP_VOTE = 4;

/** The only op types the playlist domain ever mints. */
export type PlaylistOpType = typeof OP_ADD | typeof OP_REMOVE | typeof OP_REORDER;

const FNV1A_32_OFFSET = 0x811c9dc5;
const FNV1A_32_PRIME = 0x01000193;

/**
 * A decoded op. u64 fields are bigint (the bridge discipline); `fracIndex`
 * is the f64 order key and `fracBits` its exact IEEE-754 bit pattern.
 */
export interface JamOp {
    checksum: number;
    fracIndex: number;
    opId: bigint;
    opType: number;
    policyFlags: number;
    /** 4 device-identity bytes. */
    senderNonce: Uint8Array;
    targetAddOpId: bigint;
    trackCadId: bigint;
}

/** The op's JSON view (u64s as decimal strings - the DTO the engines share). */
export interface JamOpDto {
    cadId: string;
    frac: number;
    opId: string;
    /** 1=Add 2=Remove 3=Reorder 4=Vote. */
    opType: number;
    policyFlags: number;
    /** 8 hex chars. */
    senderNonce: string;
    target: string;
}

/** The op's exact IEEE-754 bit pattern (bigint so it survives JSON). */
export function fracBitsOf(frac: number): bigint {
    const view = new DataView(new ArrayBuffer(8));
    view.setFloat64(0, frac, true);
    return view.getBigUint64(0, true);
}

/** IEEE-754 raw bits (exact integer) -> f64. */
export function fracFromBits(bits: bigint): number {
    const view = new DataView(new ArrayBuffer(8));
    view.setBigUint64(0, bits, true);
    return view.getFloat64(0, true);
}

/** FNV-1a 32 over the packed payload bytes [0..40): every field except the
 * checksum itself and the trailing pad. Pads are included BY DESIGN - they
 * are forced to zero here and by fromBytes, making the span deterministic
 * across senders. */
export function jamOpChecksum(op: Omit<JamOp, 'checksum'>): number {
    const bytes = jamOpToBytes({ ...op, checksum: 0 });
    let hash = FNV1A_32_OFFSET;
    for (let i = 0; i < 40; i += 1) {
        hash ^= bytes[i];
        // wrapping u32 multiply - Math.imul is exact for the low 32 bits.
        hash = Math.imul(hash, FNV1A_32_PRIME) >>> 0;
    }
    return hash >>> 0;
}

/** Inverse of jamOpToBytes. Rejects wrong lengths and non-zero pads. */
export function jamOpFromBytes(bytes: Uint8Array): JamOp | null {
    if (bytes.length !== JAM_OP_SIZE) {
        return null;
    }
    if (
        bytes[14] !== 0 ||
        bytes[15] !== 0 ||
        bytes[44] !== 0 ||
        bytes[45] !== 0 ||
        bytes[46] !== 0 ||
        bytes[47] !== 0
    ) {
        return null;
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return {
        checksum: view.getUint32(40, true),
        fracIndex: fracFromBits(view.getBigUint64(24, true)),
        opId: view.getBigUint64(0, true),
        opType: bytes[12],
        policyFlags: bytes[13],
        senderNonce: bytes.slice(8, 12),
        targetAddOpId: view.getBigUint64(32, true),
        trackCadId: view.getBigUint64(16, true),
    };
}

/** Parse a JSON view back into a sealed op (checksum recomputed). */
export function jamOpFromDto(dto: JamOpDto): JamOp | null {
    const opType = dto.opType;
    if (!Number.isInteger(opType) || opType < OP_ADD || opType > OP_VOTE) {
        return null;
    }
    let opId: bigint;
    let cadId: bigint;
    let target: bigint;
    try {
        opId = BigInt(dto.opId);
        cadId = BigInt(dto.cadId);
        target = BigInt(dto.target);
    } catch {
        return null;
    }
    if (opId < 0n || cadId < 0n || target < 0n) {
        return null;
    }
    const nonce = hexToBytes(dto.senderNonce);
    if (nonce === null) {
        return null;
    }
    const frac = dto.frac;
    if (!Number.isFinite(frac)) {
        return null;
    }
    return jamOpNew(opId, nonce, opType, dto.policyFlags & 0xff, cadId, frac, target);
}

/** True when the sealed checksum matches the payload (the wire gate). */
export function jamOpIsValid(op: JamOp): boolean {
    return (
        jamOpChecksum(op) === op.checksum >>> 0 &&
        Number.isFinite(op.fracIndex) &&
        op.opType >= OP_ADD &&
        op.opType <= OP_VOTE
    );
}

/** Construct an op with zeroed pads and the FNV-1a checksum sealed. */
export function jamOpNew(
    opId: bigint,
    senderNonce: Uint8Array,
    opType: number,
    policyFlags: number,
    trackCadId: bigint,
    fracIndex: number,
    targetAddOpId: bigint,
): JamOp {
    const op: JamOp = {
        checksum: 0,
        fracIndex,
        opId,
        opType,
        policyFlags,
        senderNonce: senderNonce.slice(0, 4),
        targetAddOpId,
        trackCadId,
    };
    op.checksum = jamOpChecksum(op);
    return op;
}

/** Explicit little-endian serialization - no padding ambiguity. */
export function jamOpToBytes(op: JamOp): Uint8Array {
    const bytes = new Uint8Array(JAM_OP_SIZE);
    const view = new DataView(bytes.buffer);
    view.setBigUint64(0, op.opId, true);
    bytes.set(op.senderNonce.slice(0, 4), 8);
    bytes[12] = op.opType;
    bytes[13] = op.policyFlags;
    // [14..16) pad stays zero
    view.setBigUint64(16, op.trackCadId, true);
    view.setBigUint64(24, fracBitsOf(op.fracIndex), true);
    view.setBigUint64(32, op.targetAddOpId, true);
    view.setUint32(40, op.checksum >>> 0, true);
    // [44..48) pad stays zero
    return bytes;
}

/** The JSON view (u64s as decimal strings; sender nonce as 8 hex chars). */
export function jamOpToDto(op: JamOp): JamOpDto {
    return {
        cadId: op.trackCadId.toString(10),
        frac: op.fracIndex,
        opId: op.opId.toString(10),
        opType: op.opType,
        policyFlags: op.policyFlags,
        senderNonce: bytesToHex(op.senderNonce),
        target: op.targetAddOpId.toString(10),
    };
}

// ---------------------------------------------------------------------------
// Op-id mint - the hybrid logical clock (Rust JamOp::generate_op_id)
// ---------------------------------------------------------------------------

let globalOpCounter = 0;
let lastSeenMs = 0n;

export function bytesToHex(bytes: Uint8Array): string {
    let out = '';
    for (let i = 0; i < bytes.length; i += 1) {
        out += bytes[i].toString(16).padStart(2, '0');
    }
    return out;
}

/**
 * Strictly monotonic op-id generation, immune to clock step-back:
 * LAST_SEEN_MS is ratcheted forward, so a regressing wall clock yields
 * prev+1 rather than a smaller id. Same-millisecond calls disambiguate
 * through the 16-bit counter - the exact Rust discipline.
 *
 * The counter can be seeded per device (see seedOpCounter) so two devices
 * minting in the same millisecond do not systematically collide on the
 * low 16 bits.
 */
export function generateOpId(nowMs: bigint = BigInt(Date.now())): bigint {
    let currentMs = nowMs;
    if (lastSeenMs >= currentMs) {
        currentMs = lastSeenMs + 1n;
    }
    lastSeenMs = currentMs;
    const counter = BigInt(globalOpCounter & 0xffff);
    globalOpCounter = (globalOpCounter + 1) & 0xffff;
    return ((currentMs & 0xffffffffffffn) << 16n) | counter;
}

export function hexToBytes(hex: string): null | Uint8Array {
    if (hex.length === 0 || hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) {
        return null;
    }
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i += 1) {
        out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Small byte helpers (shared with the element-id packing)
// ---------------------------------------------------------------------------

/** Test-only: reset the ratchet + counter (deterministic ids per test). */
export function resetOpMintForTests(nowMs = 0n, counter = 0): void {
    lastSeenMs = nowMs;
    globalOpCounter = counter & 0xffff;
}

/** Seed the 16-bit counter from device identity (reduces cross-device
 * same-millisecond collisions to independent random overlap). */
export function seedOpCounter(seed: number): void {
    globalOpCounter = seed & 0xffff;
}
