/**
 * P4-A — the chunk-cipher contract, exercised against the REAL committed
 * wasm artifact (round-trip, tamper rejection, position binding, the
 * STREAMIFY_DL_V1 container codec).
 */

import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';

import {
    cipherChunkSize,
    type CipherWasmExports,
    initCipherForTests,
    mintKey,
    mintNonce,
    openChunk,
    sealChunk,
    StreamifyCipherAuthError,
} from '../downloads/cipher';
import {
    chunkCountFor,
    containerSize,
    decodeHeader,
    DL_HEADER_SIZE,
    openContainer,
    plainLenForChunk,
    recordOffset,
    sealContainer,
} from '../downloads/format';

const ARTIFACT = readFileSync(new URL('../wasm/streamify_cipher_bg.wasm', import.meta.url));

let wasm: CipherWasmExports;

beforeAll(() => {
    // One init per file (the loader is a singleton); every fixture below
    // shares the module and mints fresh key/nonce material per case.
    wasm = initCipherForTests(ARTIFACT);
});

describe('P4-A chunk cipher (XChaCha20-Poly1305 per-chunk AEAD)', () => {
    it('round-trips a full chunk', () => {
        const key = mintKey();
        const plain = new Uint8Array(65536).map((_, i) => i & 0xff);
        const nonce = mintNonce();
        const { ct, tag } = sealChunk(wasm, key, nonce, 3, plain);
        expect(ct.length).toBe(65536);
        expect(tag.length).toBe(16);
        const opened = openChunk(wasm, key, nonce, 3, ct, tag);
        expect([...opened]).toEqual([...plain]);
    });

    it('fresh nonces: same plaintext seals to different ciphertext', () => {
        const key = mintKey();
        const plain = new TextEncoder().encode('known plaintext');
        const a = sealChunk(wasm, key, mintNonce(), 0, plain);
        const b = sealChunk(wasm, key, mintNonce(), 0, plain);
        expect([...a.ct]).not.toEqual([...b.ct]);
    });

    it('rejects a corrupted ciphertext byte (Poly1305 tamper)', () => {
        const key = mintKey();
        const nonce = mintNonce();
        const plain = new TextEncoder().encode('audio chunk');
        const { ct, tag } = sealChunk(wasm, key, nonce, 5, plain);
        ct[0] ^= 0xff;
        expect(() => openChunk(wasm, key, nonce, 5, ct, tag)).toThrow(StreamifyCipherAuthError);
    });

    it('rejects a corrupted tag byte', () => {
        const key = mintKey();
        const nonce = mintNonce();
        const plain = new TextEncoder().encode('audio chunk');
        const { ct, tag } = sealChunk(wasm, key, nonce, 5, plain);
        tag[15] ^= 0x01;
        expect(() => openChunk(wasm, key, nonce, 5, ct, tag)).toThrow(StreamifyCipherAuthError);
    });

    it('rejects chunk reordering (the AAD position binding)', () => {
        const key = mintKey();
        const plain = new TextEncoder().encode('audio chunk');
        const sealedAt5 = sealChunk(wasm, key, mintNonce(), 5, plain);
        // Present the record under a different index: auth must fail.
        expect(() => openChunk(wasm, key, sealedAt5.ct, 6, sealedAt5.ct, sealedAt5.tag)).toThrow();
        expect(() => openChunk(wasm, key, mintNonce(), 6, sealedAt5.ct, sealedAt5.tag)).toThrow(
            StreamifyCipherAuthError,
        );
    });

    it('validates ABI args honestly', () => {
        const key = mintKey();
        const plain = new Uint8Array(8);
        const tooBig = new Uint8Array(cipherChunkSize(wasm) + 1);
        expect(() => sealChunk(wasm, new Uint8Array(31), mintNonce(), 0, plain)).toThrow(
            /32 bytes/,
        );
        expect(() => sealChunk(wasm, key, new Uint8Array(12), 0, plain)).toThrow(/24 bytes/);
        expect(() => sealChunk(wasm, key, mintNonce(), 0, tooBig)).toThrow(/chunk bound/);
    });
});

describe('P4-A STREAMIFY_DL_V1 container codec', () => {
    const key = () => mintKey();

    it('round-trips a multi-chunk container (2 full + 1 short)', () => {
        const total = 65536 * 2 + 21000;
        const plain = new Uint8Array(total).map((_, i) => (i * 7) & 0xff);
        const k = key();
        const { bytes, header } = sealContainer(wasm, k, plain, mintNonce);
        expect(header.chunkCount).toBe(3);
        expect(header.totalPlainLen).toBe(total);
        expect(bytes.length).toBe(containerSize(header));
        const opened = openContainer(wasm, k, bytes);
        expect([...opened.plain]).toEqual([...plain]);
        expect(opened.header.chunkCount).toBe(3);
    });

    it('computes the layout deterministically', () => {
        const header = { chunkCount: 3, chunkSize: 65536, totalPlainLen: 152072, version: 1 };
        expect(chunkCountFor(152072, 65536)).toBe(3);
        expect(plainLenForChunk(header, 0)).toBe(65536);
        expect(plainLenForChunk(header, 2)).toBe(21000);
        expect(recordOffset(header, 0)).toBe(DL_HEADER_SIZE);
        expect(recordOffset(header, 1)).toBe(DL_HEADER_SIZE + 44 + 65536);
        expect(containerSize(header)).toBe(DL_HEADER_SIZE + 44 * 3 + 152072);
    });

    it('rejects truncation before any authentication attempt', () => {
        const plain = new Uint8Array(1000).fill(9);
        const { bytes, header } = sealContainer(wasm, key(), plain, mintNonce);
        const truncated = bytes.slice(0, bytes.length - 10);
        // Header still decodes; the size check must fire on open.
        expect(decodeHeader(truncated).chunkCount).toBe(header.chunkCount);
        expect(() => openContainer(wasm, key(), truncated)).toThrow(/container size/);
    });

    it('rejects a container with one corrupted record', () => {
        const plain = new Uint8Array(150000).map((_, i) => i & 0x3f);
        const k = key();
        const { bytes, header } = sealContainer(wasm, k, plain, mintNonce);
        // Corrupt chunk 1's ciphertext: record 1 starts after header+record0.
        const at = recordOffset(header, 1) + 44 + 100;
        bytes[at] ^= 0xff;
        expect(() => openContainer(wasm, k, bytes)).toThrow(StreamifyCipherAuthError);
        // Sanity: the UNTOUCHED container round-trips with the same key.
        const pristine = sealContainer(wasm, k, plain, mintNonce);
        expect(() => openContainer(wasm, k, pristine.bytes)).not.toThrow();
    });

    it('rejects a bad magic / bad version', () => {
        const plain = new Uint8Array(64);
        const { bytes } = sealContainer(wasm, key(), plain, mintNonce);
        const badMagic = bytes.slice();
        badMagic[0] ^= 0x01;
        expect(() => openContainer(wasm, key(), badMagic)).toThrow(/bad magic/);
        const badVersion = bytes.slice();
        badVersion[15] = 2;
        expect(() => decodeHeader(badVersion)).toThrow(/unsupported version/);
    });
});
