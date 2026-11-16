/**
 * streamify/jam/cad.ts - the canonical CAD-ID hasher, ported 1:1 from
 * `rust/crates/streamify-jam-crdt/src/cad.rs` (which moved it verbatim from
 * streamify-storage - the byte-exact mirror of the C++ normalization in
 * nativeGenerateCadId).
 *
 * Identity of a track across runtimes: FNV-1a-64 over the normalized
 * (title, artist) bytes plus a 3-second duration bucket.
 *
 * P3A adds one derivation on the same hasher: the PLAYLIST ELEMENT cad -
 * FNV-1a-64 over the Supabase track id's UTF-8 bytes. Playlist elements
 * reference durable catalog rows (tracks.id), not raw content, so their
 * cad is minted from the catalog identity. Both derivations share the
 * frozen FNV constants; the out-of-band trackId (the JamTrackCodec
 * discipline) carries the value, the cad rides the 48-byte op row.
 */

import { bytesToHex, hexToBytes } from './jam-op';

const FNV1A_64_OFFSET = 0xcbf29ce484222325n; // 14695981039346656037
const FNV1A_64_PRIME = 0x100000001b3n; // 1099511628211
const U64_MASK = 0xffffffffffffffffn;

/** Hex view of a device nonce (for logs / DTOs). */
export function deviceNonceHex(deviceId: string): string {
    return bytesToHex(deviceNonceOf(deviceId));
}

/** The 4-byte device nonce for a device id (hex round-trips via jam-op). */
export function deviceNonceOf(deviceId: string): Uint8Array {
    const hex = (fnv1a32Of(deviceId) >>> 0).toString(16).padStart(8, '0');
    return hexToBytes(hex) ?? new Uint8Array(4);
}

/** FNV-1a-32 over a string's UTF-8 bytes (a stable, dependency-free hash). */
export function fnv1a32Of(input: string): number {
    const bytes = new TextEncoder().encode(input);
    let hash = 0x811c9dc5;
    for (const b of bytes) {
        hash ^= b;
        hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash >>> 0;
}

// ---------------------------------------------------------------------------
// 4-byte device nonce (the sender identity carried by every op)
// ---------------------------------------------------------------------------

/** Raw u64 CAD-ID from (title, artist, duration) - Rust parity. */
export function generateCadIdU64(title: string, artist: string, durationSec: number): bigint {
    let hash = FNV1A_64_OFFSET;
    for (const b of normalizeCadField(title, true)) {
        hash = ((hash ^ BigInt(b)) * FNV1A_64_PRIME) & U64_MASK;
    }
    for (const b of normalizeCadField(artist, false)) {
        hash = ((hash ^ BigInt(b)) * FNV1A_64_PRIME) & U64_MASK;
    }
    const bucket = durationSec > 0 ? Math.floor(durationSec / 3) : 0;
    const bucketU32 = bucket >>> 0;
    for (let i = 0; i < 4; i += 1) {
        const byte = (bucketU32 >>> (i * 8)) & 0xff;
        hash = ((hash ^ BigInt(byte)) * FNV1A_64_PRIME) & U64_MASK;
    }
    return hash;
}

/**
 * Byte-exact mirror of the C++ normalization in nativeGenerateCadId:
 * iterates UTF-8 BYTES, keeps ASCII alphanumerics (plus '(' for titles
 * when allow_paren), lowercases ASCII letters, drops everything else (all
 * non-ASCII).
 */
export function normalizeCadField(input: string, allowParen: boolean): Uint8Array {
    const out: number[] = [];
    for (let i = 0; i < input.length; i += 1) {
        const ch = input.charCodeAt(i);
        if (ch > 0x7f) {
            continue; // non-ASCII drops entirely (per-BYTE semantics)
        }
        const b = ch;
        const isAlnum =
            (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a);
        const keep = isAlnum || (allowParen && b === 0x28);
        if (keep) {
            out.push(b >= 0x41 && b <= 0x5a ? b + 0x20 : b);
        }
    }
    return new Uint8Array(out);
}

/**
 * The playlist-element cad: FNV-1a-64 over the Supabase track id's UTF-8
 * bytes (TextEncoder = UTF-8, exactly what Rust `as_bytes()` sees).
 */
export function playlistTrackCadId(trackId: string): bigint {
    let hash = FNV1A_64_OFFSET;
    const bytes = new TextEncoder().encode(trackId);
    for (const b of bytes) {
        hash = ((hash ^ BigInt(b)) * FNV1A_64_PRIME) & U64_MASK;
    }
    return hash;
}
