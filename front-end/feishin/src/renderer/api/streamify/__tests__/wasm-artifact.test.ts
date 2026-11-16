/**
 * P4-A — the cipher wasm artifact gate.
 *
 * Verifies the COMMITTED bytes: sha256 against artifact.json (hand-edits
 * or stale manifests fail here), the wasm-bindgen pin, and — through the
 * real loader — the deterministic self-test. This is the deterministic-
 * staging contract from decisions/p4/P4A-downloads-wrapped-debug.md.
 */

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { initCipherForTests } from '../downloads/cipher';
import { resetCipherWasmForTests } from '../wasm/cipher-loader';

const ARTIFACT = readFileSync(new URL('../wasm/streamify_cipher_bg.wasm', import.meta.url));
const MANIFEST = JSON.parse(
    readFileSync(new URL('../wasm/artifact.json', import.meta.url), 'utf8'),
) as {
    bytes: number;
    features: string;
    rustc: string;
    sha256: string;
    'wasm-bindgen': string;
};

describe('P4-A cipher wasm artifact (deterministic staging)', () => {
    it('committed bytes match the manifest sha256', () => {
        const actual = createHash('sha256').update(ARTIFACT).digest('hex');
        expect(actual).toBe(MANIFEST.sha256);
    });

    it('records the build recipe honestly', () => {
        expect(MANIFEST.features).toBe('--no-default-features --features cipher');
        expect(MANIFEST['wasm-bindgen']).toBe('0.2.130');
        expect(MANIFEST.rustc).toMatch(/^\d+\.\d+\.\d+/);
        expect(MANIFEST.bytes).toBe(ARTIFACT.byteLength);
    });

    it('self-test passes through the real loader (0 = OK)', () => {
        const wasm = initCipherForTests(ARTIFACT);
        expect(wasm.dl_self_test()).toBe(0);
        expect(wasm.dl_format_version()).toBe(1);
        expect(wasm.dl_chunk_size()).toBe(65536);
        expect(wasm.dl_scratch_size()).toBe(131152);
        resetCipherWasmForTests();
    });
});
