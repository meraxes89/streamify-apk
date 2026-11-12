/* tslint:disable */
/* eslint-disable */

/**
 * W0.2 alive check.
 */
export function ping(): string;

/**
 * G0.4 / kill-criterion path: resolve the best audio stream for a video
 * id. Returns a JSON STRING `{ streamUrl, itag, mimeType, isAudioOnly,
 * durationSec? }`, or the JSON string `"null"` when the player response
 * carries no direct audio URL (bot-walled / login-required / ciphered).
 */
export function resolve_stream(video_id: string): Promise<any>;

/**
 * W0.4 proof: search YouTube Music through the relay.
 *
 * Returns a JSON STRING (the JSON-string bridge — parse on the TS side;
 * see the module docs for why serde values never cross the boundary
 * directly).
 */
export function search_music(query: string): Promise<any>;

/**
 * Route all rustypipe HTTP traffic through the streamify-relay worker
 * (e.g. `https://streamify-relay.<account>.workers.dev`, or
 * `http://localhost:8787` against a local `wrangler dev`). No-op on
 * non-wasm builds.
 */
export function set_relay_base_url(u: string): void;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly ping: () => [number, number];
    readonly resolve_stream: (a: number, b: number) => any;
    readonly search_music: (a: number, b: number) => any;
    readonly set_relay_base_url: (a: number, b: number) => void;
    readonly wasm_bindgen_10feaddee92b4bdd___convert__closures_____invoke___js_sys_daca4dbd301495d8___Function_fn_wasm_bindgen_10feaddee92b4bdd___JsValue_____wasm_bindgen_10feaddee92b4bdd___sys__Undefined___js_sys_daca4dbd301495d8___Function_fn_wasm_bindgen_10feaddee92b4bdd___JsValue_____wasm_bindgen_10feaddee92b4bdd___sys__Undefined_______true_: (a: number, b: number, c: any, d: any) => void;
    readonly wasm_bindgen_10feaddee92b4bdd___convert__closures_____invoke___wasm_bindgen_10feaddee92b4bdd___JsValue__core_37ee0ce0c4cd57d5___result__Result_____wasm_bindgen_10feaddee92b4bdd___JsError___true_: (a: number, b: number, c: any) => [number, number];
    readonly wasm_bindgen_10feaddee92b4bdd___convert__closures_____invoke_______true_: (a: number, b: number) => void;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_destroy_closure: (a: number, b: number) => void;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
