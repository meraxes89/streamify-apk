/**
 * api/streamify/downloads/cipher.ts — P4-A: the typed surface over the
 * cipher wasm.
 *
 * Thin, synchronous helpers around the module's scratch-region ABI (see
 * crates/streamify-wasm/src/cipher.rs for the layout): the caller owns
 * every buffer, randomness is minted here via the platform CSPRNG
 * (crypto.getRandomValues — available in every browser and in Node ≥ 19,
 * so vitest exercises the same path), and NOTHING is allocated inside
 * wasm. Single-threaded by design: the download manager and the offline
 * serve path are the only callers, and never concurrently.
 */

import {
    type CipherWasmExports,
    currentCipherWasm,
    initCipherFromBytes,
    loadCipherWasm,
} from '../wasm/cipher-loader';

export type { CipherWasmExports } from '../wasm/cipher-loader';

/** The wasm's scratch layout (mirror of cipher.rs — offsets into memory). */
const OFF_KEY = 0;
const OFF_NONCE = 32;
const OFF_PT = 64;
const OFF_CT = 65600;
const OFF_TAG = 131136;

/** Tag mismatch — the entry is corrupted (tamper, truncation, reordering). */
export class StreamifyCipherAuthError extends Error {
    constructor(chunkIndex: number) {
        super(`streamify: chunk-cipher authentication failed at index ${chunkIndex}`);
        this.name = 'StreamifyCipherAuthError';
    }
}

let loading: null | Promise<CipherWasmExports> = null;

/** The container chunk size in bytes (single source of truth: the wasm). */
export function cipherChunkSize(wasm: CipherWasmExports): number {
    return wasm.dl_chunk_size();
}

/** Deterministic health probe (the /debug panel's WASM self-test). */
export function cipherSelfTest(wasm: CipherWasmExports): number {
    return wasm.dl_self_test();
}

/** The production init: load the wasm (browser fetch / already-initialized). */
export async function ensureCipher(): Promise<CipherWasmExports> {
    const current = currentCipherWasm();
    if (current !== null) return current;
    loading ??= loadCipherWasm();
    return loading;
}

/** Test/CLI init from bytes (vitest reads the committed artifact). */
export function initCipherForTests(bytes: ArrayBuffer | Uint8Array): CipherWasmExports {
    return initCipherFromBytes(bytes);
}

/** Mint a fresh 32-byte file key from the platform CSPRNG. */
export function mintKey(): Uint8Array {
    const key = new Uint8Array(32);
    globalThis.crypto.getRandomValues(key);
    return key;
}

/** Mint a fresh 24-byte nonce from the platform CSPRNG. */
export function mintNonce(): Uint8Array {
    const nonce = new Uint8Array(24);
    globalThis.crypto.getRandomValues(nonce);
    return nonce;
}

/**
 * Open one chunk. Throws StreamifyCipherAuthError on tag mismatch — the
 * caller's quarantine signal. On success returns a fresh plaintext copy.
 */
export function openChunk(
    wasm: CipherWasmExports,
    key: Uint8Array,
    nonce: Uint8Array,
    chunkIndex: number,
    ct: Uint8Array,
    tag: Uint8Array,
): Uint8Array {
    const chunk = cipherChunkSize(wasm);
    validateSizes(key, nonce, ct.length, chunk);
    if (tag.length !== 16) {
        throw new Error(`streamify cipher: tag must be 16 bytes (got ${tag.length})`);
    }
    let mem = view(wasm);
    const base = wasm.dl_scratch_ptr();
    mem.set(key, base + OFF_KEY);
    mem.set(nonce, base + OFF_NONCE);
    mem.set(ct, base + OFF_CT);
    mem.set(tag, base + OFF_TAG);
    const code = wasm.dl_open_chunk(chunkIndex, ct.length);
    if (code === 1) {
        throw new StreamifyCipherAuthError(chunkIndex);
    }
    if (code !== 0) {
        throw new Error(`streamify cipher: open rejected (code ${code})`);
    }
    // The open's joined-buffer Vec can grow memory — re-acquire the view.
    mem = view(wasm);
    return mem.slice(base + OFF_PT, base + OFF_PT + ct.length);
}

/**
 * Seal one chunk. Returns fresh ct + tag copies; the plaintext never
 * leaves the caller's hands unsealed on the wire (OPFS only ever sees
 * sealChunk output).
 */
export function sealChunk(
    wasm: CipherWasmExports,
    key: Uint8Array,
    nonce: Uint8Array,
    chunkIndex: number,
    plain: Uint8Array,
): { ct: Uint8Array; tag: Uint8Array } {
    const chunk = cipherChunkSize(wasm);
    validateSizes(key, nonce, plain.length, chunk);
    let mem = view(wasm);
    const base = wasm.dl_scratch_ptr();
    mem.set(key, base + OFF_KEY);
    mem.set(nonce, base + OFF_NONCE);
    mem.set(plain, base + OFF_PT);
    const code = wasm.dl_seal_chunk(chunkIndex, plain.length);
    if (code !== 0) {
        throw new Error(`streamify cipher: seal rejected (code ${code})`);
    }
    // The seal's internal Vec can GROW wasm memory (a 64 KiB heap bump);
    // growth detaches the pre-call view — re-acquire before reading back.
    mem = view(wasm);
    const ct = mem.slice(base + OFF_CT, base + OFF_CT + plain.length);
    const tag = mem.slice(base + OFF_TAG, base + OFF_TAG + 16);
    return { ct, tag };
}

function validateSizes(key: Uint8Array, nonce: Uint8Array, payloadLen: number, chunk: number) {
    if (key.length !== 32) {
        throw new Error(`streamify cipher: key must be 32 bytes (got ${key.length})`);
    }
    if (nonce.length !== 24) {
        throw new Error(`streamify cipher: nonce must be 24 bytes (got ${nonce.length})`);
    }
    if (payloadLen > chunk) {
        throw new Error(
            `streamify cipher: payload ${payloadLen} exceeds the ${chunk}-byte chunk bound`,
        );
    }
}

function view(wasm: CipherWasmExports): Uint8Array {
    return new Uint8Array(wasm.memory.buffer);
}
