/**
 * api/streamify/wasm/cipher-loader.ts — P4-A: the typed loader for the
 * vendored cipher wasm.
 *
 * The artifact (streamify_cipher_bg.wasm) is the compact, cipher-only
 * build of front-end/crates/streamify-wasm (feature `cipher`, see
 * crates/streamify-wasm/build-feishin-cipher.sh and artifact.json in this
 * folder). It has EXACTLY ONE import — wasm-bindgen 0.2.130's externref
 * table initializer — which this loader provides by hand, so no wasm-pack
 * glue JS is vendored: only the .wasm bytes plus this typed module.
 *
 * Two entry points:
 *   - loadCipherWasm(): the production path (browser) — fetches the
 *     artifact next to this module (Vite rewrites the URL to a hashed
 *     asset) and instantiates it.
 *   - initCipherFromBytes(bytes): the test/CLI path — instantiate from
 *     bytes the caller already holds (vitest reads the artifact with
 *     node:fs; this keeps the loader free of environment sniffing).
 */

/** The raw wasm export surface (mirror of the Rust ABI in cipher.rs). */
export interface CipherWasmExports {
    readonly __wbindgen_externrefs: WebAssembly.Table;
    dl_chunk_size(): number;
    dl_format_version(): number;
    dl_open_chunk(chunkIndex: number, ctLen: number): number;
    dl_scratch_ptr(): number;
    dl_scratch_size(): number;
    dl_seal_chunk(chunkIndex: number, ptLen: number): number;
    dl_self_test(): number;
    readonly memory: WebAssembly.Memory;
}

/** wasm-bindgen's import module name (0.2.130 default; pinned with the artifact). */
const IMPORT_MODULE = './streamify_wasm_bg.js';

let active: CipherWasmExports | null = null;

/** The already-initialized module, or null when not yet loaded. */
export function currentCipherWasm(): CipherWasmExports | null {
    return active;
}

/**
 * Instantiate the cipher module from bytes. Synchronous (WebAssembly
 * Module/Instance compile of a 35 KB artifact is cheap); throws on
 * validation failure so callers can surface an honest init error.
 */
export function initCipherFromBytes(bytes: ArrayBuffer | Uint8Array): CipherWasmExports {
    if (active !== null) {
        throw new Error('streamify cipher wasm already initialized');
    }
    const buffer = bytes instanceof Uint8Array ? new Uint8Array(bytes) : new Uint8Array(bytes);
    const module = new WebAssembly.Module(buffer);
    // The externref init closure references the instance lazily (called BY
    // the module during __wbindgen_start, well after assignment), so a
    // const binding introduced after this map is safe.
    const lateBound: { instance: null | WebAssembly.Instance } = { instance: null };
    const imports: Record<string, Record<string, () => void>> = {
        [IMPORT_MODULE]: {
            // wasm-bindgen 0.2.130 externref table init (verbatim from the
            // generated glue — grow/seed semantics are part of the pinned
            // artifact contract; see the build script's manifest).
            __wbindgen_init_externref_table: () => {
                const table = (lateBound.instance?.exports as unknown as CipherWasmExports)
                    ?.__wbindgen_externrefs;
                if (table === undefined) return;
                table.set(0, undefined);
                table.set(1024, undefined);
                table.set(1025, null);
                table.set(1026, true);
                table.set(1027, false);
            },
        },
    };
    const mod: WebAssembly.Instance = new WebAssembly.Instance(module, imports);
    lateBound.instance = mod;
    const wasm = mod.exports as unknown as CipherWasmExports;
    const start = (mod.exports as Record<string, () => void>).__wbindgen_start;
    start?.();
    active = wasm;
    return wasm;
}

/** The production loader: fetch + instantiate the vendored artifact. */
export async function loadCipherWasm(): Promise<CipherWasmExports> {
    if (active !== null) return active;
    const url = new URL('./streamify_cipher_bg.wasm', import.meta.url);
    const res = await fetch(url);
    if (!res.ok) {
        throw new Error(`streamify cipher wasm fetch failed: ${res.status} ${res.statusText}`);
    }
    return initCipherFromBytes(await res.arrayBuffer());
}

/** Test seam: drop the singleton so a fresh init can run. */
export function resetCipherWasmForTests(): void {
    active = null;
}
