/**
 * api/streamify/downloads/format.ts — P4-A: the STREAMIFY_DL_V1 container.
 *
 * The at-rest wire format for one downloaded track (all integers
 * little-endian, mirroring the vault-v2 family in
 * rust/crates/streamify-storage/src/crypto.rs):
 *
 *   ┌──────────────────────┬────────┐
 *   │ "STREAMIFY_DL_V1"    │ 15 B   │ magic
 *   │ version              │ u32    │ = 1
 *   │ chunkSize            │ u32    │ = 65536 (the cipher's bound)
 *   │ totalPlainLen        │ u32    │ plaintext bytes
 *   │ chunkCount           │ u32    │ ceil(total / chunkSize)
 *   ├──────────────────────┼────────┤
 *   │ record × chunkCount  │ …      │ until EOF
 *   └──────────────────────┴────────┘
 *
 *   record := ctLen:u32 (≤ chunkSize + 0; the sealed payload length)
 *             ‖ nonce:24 B
 *             ‖ ct:ctLen B
 *             ‖ tag:16 B
 *
 * ctLen is derivable (chunkSize for every record except the last, where
 * it is totalPlainLen − (n−1)·chunkSize) — storing it anyway is the
 * vault-v2 robustness choice: a per-record length field survives
 * future format additions and lets the size check catch truncation
 * before any authentication attempt.
 */

import { type CipherWasmExports, openChunk, sealChunk } from './cipher';

export const DL_MAGIC = 'STREAMIFY_DL_V1';
export const DL_MAGIC_BYTES = Uint8Array.from(DL_MAGIC, (c) => c.charCodeAt(0));
export const DL_HEADER_SIZE = 15 + 4 * 4; // magic + version/chunkSize/total/chunkCount
export const DL_RECORD_FIXED = 4 + 24 + 16; // ctLen + nonce + tag (ct is variable)

export interface DecodedRecord {
    ct: Uint8Array;
    nonce: Uint8Array;
    tag: Uint8Array;
}

export interface DlHeader {
    chunkCount: number;
    chunkSize: number;
    totalPlainLen: number;
    version: number;
}

export function chunkCountFor(totalPlainLen: number, chunkSize: number): number {
    if (totalPlainLen <= 0) return 0;
    return Math.ceil(totalPlainLen / chunkSize);
}

export function concat(parts: Uint8Array[]): Uint8Array {
    let total = 0;
    for (const p of parts) total += p.length;
    const out = new Uint8Array(total);
    let off = 0;
    for (const p of parts) {
        out.set(p, off);
        off += p.length;
    }
    return out;
}

/** The exact container size for a given header (the truncation check). */
export function containerSize(header: DlHeader): number {
    return recordOffset(header, header.chunkCount);
}

export function decodeHeader(bytes: Uint8Array): DlHeader {
    if (bytes.length < DL_HEADER_SIZE) {
        throw new Error('streamify dl: truncated header');
    }
    for (let i = 0; i < DL_MAGIC_BYTES.length; i += 1) {
        if (bytes[i] !== DL_MAGIC_BYTES[i]) {
            throw new Error('streamify dl: bad magic (not a STREAMIFY_DL_V1 container)');
        }
    }
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const header: DlHeader = {
        chunkCount: dv.getUint32(27, true),
        chunkSize: dv.getUint32(19, true),
        totalPlainLen: dv.getUint32(23, true),
        version: dv.getUint32(15, true),
    };
    if (header.version !== 1) {
        throw new Error(`streamify dl: unsupported version ${header.version}`);
    }
    if (header.chunkSize <= 0 || header.totalPlainLen < 0) {
        throw new Error('streamify dl: nonsensical header fields');
    }
    if (header.chunkCount !== chunkCountFor(header.totalPlainLen, header.chunkSize)) {
        throw new Error('streamify dl: chunkCount disagrees with total/chunkSize');
    }
    return header;
}

/** Read record `index` out of a container (bounds-checked against the header). */
export function decodeRecord(bytes: Uint8Array, header: DlHeader, index: number): DecodedRecord {
    const off = recordOffset(header, index);
    const ctLen = plainLenForChunk(header, index);
    const end = off + DL_RECORD_FIXED + ctLen;
    if (bytes.length < end) {
        throw new Error(`streamify dl: record ${index} truncated (container size check)`);
    }
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const storedLen = dv.getUint32(off, true);
    if (storedLen !== ctLen) {
        throw new Error(`streamify dl: record ${index} length mismatch`);
    }
    const ct = bytes.slice(off + 28, off + 28 + ctLen);
    const nonce = bytes.slice(off + 4, off + 28);
    const tag = bytes.slice(off + 28 + ctLen, off + 28 + ctLen + 16);
    return { ct, nonce, tag };
}

export function encodeHeader(header: DlHeader): Uint8Array {
    const out = new Uint8Array(DL_HEADER_SIZE);
    const dv = new DataView(out.buffer);
    out.set(DL_MAGIC_BYTES, 0);
    dv.setUint32(15, header.version, true);
    dv.setUint32(19, header.chunkSize, true);
    dv.setUint32(23, header.totalPlainLen, true);
    dv.setUint32(27, header.chunkCount, true);
    return out;
}

/** One sealed record, ready to append to a sink. */
export function encodeRecord(ct: Uint8Array, nonce: Uint8Array, tag: Uint8Array): Uint8Array {
    const out = new Uint8Array(DL_RECORD_FIXED + ct.length);
    const dv = new DataView(out.buffer);
    dv.setUint32(0, ct.length, true);
    out.set(nonce, 4);
    out.set(ct, 28);
    out.set(tag, 28 + ct.length);
    return out;
}

/**
 * Open a whole container. Throws StreamifyCipherAuthError from the cipher
 * layer on the first bad chunk (the quarantine signal); verifies the
 * exact container size BEFORE any authentication attempt.
 */
export function openContainer(
    wasm: CipherWasmExports,
    key: Uint8Array,
    bytes: Uint8Array,
): { header: DlHeader; plain: Uint8Array } {
    const header = decodeHeader(bytes);
    const expected = containerSize(header);
    if (bytes.length !== expected) {
        throw new Error(
            `streamify dl: container size ${bytes.length} ≠ expected ${expected} (truncated or grafted)`,
        );
    }
    const parts: Uint8Array[] = [];
    for (let i = 0; i < header.chunkCount; i += 1) {
        const rec = decodeRecord(bytes, header, i);
        parts.push(openChunk(wasm, key, rec.nonce, i, rec.ct, rec.tag));
    }
    return { header, plain: concat(parts) };
}

/** Per-record plaintext length (the last record is the short one). */
export function plainLenForChunk(header: DlHeader, index: number): number {
    const last = header.chunkCount - 1;
    if (index < 0 || index > last) {
        throw new Error(`streamify dl: chunk index ${index} out of range`);
    }
    return index === last ? header.totalPlainLen - last * header.chunkSize : header.chunkSize;
}

/** The byte offset of record `index` within the container. */
export function recordOffset(header: DlHeader, index: number): number {
    let off = DL_HEADER_SIZE;
    for (let i = 0; i < index; i += 1) {
        off += DL_RECORD_FIXED + plainLenForChunk(header, i);
    }
    return off;
}

/**
 * Seal a whole plaintext into a container (the in-memory fallback path
 * when a response carries no Content-Length; also the test path).
 */
export function sealContainer(
    wasm: CipherWasmExports,
    key: Uint8Array,
    plain: Uint8Array,
    mintNonce: () => Uint8Array,
): { bytes: Uint8Array; header: DlHeader } {
    const chunkSize = wasm.dl_chunk_size();
    const header: DlHeader = {
        chunkCount: chunkCountFor(plain.length, chunkSize),
        chunkSize,
        totalPlainLen: plain.length,
        version: 1,
    };
    const parts: Uint8Array[] = [encodeHeader(header)];
    for (let i = 0; i < header.chunkCount; i += 1) {
        const pt = plain.slice(i * chunkSize, (i + 1) * chunkSize);
        const nonce = mintNonce();
        const { ct, tag } = sealChunk(wasm, key, nonce, i, pt);
        parts.push(encodeRecord(ct, nonce, tag));
    }
    const bytes = concat(parts);
    return { bytes, header };
}
