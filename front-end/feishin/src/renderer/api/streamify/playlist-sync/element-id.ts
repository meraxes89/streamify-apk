/**
 * streamify/playlist-sync/element-id.ts - the CRDT element <-> playlist_tracks
 * row-id mapping (P3A).
 *
 * THE DURABLE MAPPING (zero schema changes - the CRDT state rides the
 * existing tables):
 *
 *   playlist_tracks.id (UUID, 128 bits)
 *     = big-endian add_op_id (64 bits) || big-endian frac_bits (64 bits)
 *
 *   - The FIRST half is the element identity: every row belonging to one
 *     CRDT element lives in the half-open UUID "block"
 *     [pack(addOpId, 0), pack(addOpId + 1, 0)) - which makes remove
 *     materialization a single range DELETE that kills the element at ANY
 *     frac (concurrent reorders included), and makes the packing a
 *     deterministic function of the op (both devices derive the same id,
 *     so INSERTs are idempotent by primary key).
 *   - The SECOND half is the order key: sorting rows by (frac_bits,
 *     add_op_id) reproduces the CRDT's composite ordering rule (jam R1)
 *     from the table alone.
 *   - BIG-ENDIAN hex means Postgres' bytewise uuid comparison order equals
 *     the u64 numeric order, so gte/lt range filters in PostgREST implement
 *     the block bounds exactly.
 *
 * LEGACY ROWS (the shipping web app inserts uuid_generate_v4() rows with
 * integer positions): a v4 UUID always carries version nibble '4' at hex
 * position 12 and variant bits 10xx at position 16. A packed row's
 * position-16 nibble is the top exponent nibble of a strictly-positive
 * finite f64 (0x3 or 0x4 - never 10xx), so the variant nibble alone
 * separates the two families with zero false positives. Legacy rows are
 * ingested as elements derived from (position, added_at) - deterministically
 * on every device.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The half-open block bounds covering EVERY row of one element. */
export function elementBlockRange(addOpId: bigint): { gte: string; lt: string } {
    return {
        gte: packElementUuid(addOpId, 0n),
        lt: packElementUuid(addOpId + 1n, 0n),
    };
}

/**
 * True when a row id was minted by uuid_generate_v4() (the legacy web
 * app's lane). Packed CRDT ids can never trip this: their frac half is a
 * strictly-positive finite f64's bit pattern, whose top nibble (the uuid
 * variant position) is 0x3/0x4, never the 10xx variant bits.
 */
export function isLegacyV4RowId(id: string): boolean {
    if (!UUID_RE.test(id)) {
        return false;
    }
    const hex = id.replace(/-/g, '');
    return hex[12] === '4' && /^[89ab]$/i.test(hex[16]);
}

/** Pack (add_op_id, frac_bits) into the 8-4-4-4-12 UUID string. */
export function packElementUuid(addOpId: bigint, fracBits: bigint): string {
    const a = addOpId.toString(16).padStart(16, '0');
    const b = fracBits.toString(16).padStart(16, '0');
    return `${a.slice(0, 8)}-${a.slice(8, 12)}-${a.slice(12, 16)}-${b.slice(0, 4)}-${b.slice(4)}`;
}

/** Inverse of packElementUuid; null when the id is not a well-formed uuid. */
export function unpackElementUuid(id: string): null | { addOpId: bigint; fracBits: bigint } {
    if (!UUID_RE.test(id)) {
        return null;
    }
    const hex = id.replace(/-/g, '');
    try {
        return {
            addOpId: BigInt(`0x${hex.slice(0, 16)}`),
            fracBits: BigInt(`0x${hex.slice(16)}`),
        };
    } catch {
        return null;
    }
}
