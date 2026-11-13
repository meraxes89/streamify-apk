/* @ts-self-types="./streamify_engine_wasm.d.ts" */

/**
 * The wasm engine control plane. See the crate docs for the semantic map.
 */
export class WasmEngine {
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        WasmEngineFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_wasmengine_free(ptr, 0);
    }
    /**
     * Register a snapshot listener; returns its id. Registration bumps the
     * rev and fires the listener immediately with a full snapshot (the UDL
     * "fresh listener gets a full snapshot next pass" guarantee, hoisted to
     * registration time on this single-threaded runtime).
     * @param {Function} cb
     * @returns {number}
     */
    add_event_listener(cb) {
        const ret = wasm.wasmengine_add_event_listener(this.__wbg_ptr, cb);
        return ret >>> 0;
    }
    clear_queue() {
        wasm.wasmengine_clear_queue(this.__wbg_ptr);
    }
    /**
     * Housekeeping hook for the JS wrapper (no-op here; the UDL has no
     * listener removal and the engine outlives the page — documented).
     */
    close() {
        wasm.wasmengine_close(this.__wbg_ptr);
    }
    /**
     * Enqueue a track row; returns its queue index. Duplicated ids are
     * allowed (the UDL queue holds rows, not a set) up to MAX_QUEUE_ROWS;
     * beyond the cap the row is refused (returns u32::MAX).
     * @param {string} track_json
     * @param {string} enqueued_by
     * @returns {number}
     */
    enqueue_track(track_json, enqueued_by) {
        let ptr0, len0, ptr1, len1;
        try {
            ptr0 = passStringToWasm0(track_json, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
            len0 = WASM_VECTOR_LEN;
            ptr1 = passStringToWasm0(enqueued_by, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
            len1 = WASM_VECTOR_LEN;
        } catch (e) {
            if (ptr1) { wasm.__wbindgen_free(ptr1, len1 * 1, 1); }
            if (ptr0) { wasm.__wbindgen_free(ptr0, len0 * 1, 1); }
            throw e;
        }
        const ret = wasm.wasmengine_enqueue_track(this.__wbg_ptr, ptr0, len0, ptr1, len1);
        return ret >>> 0;
    }
    /**
     * One-shot cold-start read (the sanctioned one-shot snapshot).
     * @returns {string}
     */
    get_engine_snapshot_json() {
        let deferred1_0;
        let deferred1_1;
        try {
            const ret = wasm.wasmengine_get_engine_snapshot_json(this.__wbg_ptr);
            deferred1_0 = ret[0];
            deferred1_1 = ret[1];
            return getStringFromWasm0(ret[0], ret[1]);
        } finally {
            wasm.__wbindgen_free(deferred1_0, deferred1_1, 1);
        }
    }
    /**
     * One-shot diagnostics read of the lifecycle state name.
     * @returns {string}
     */
    get_playback_state() {
        let deferred1_0;
        let deferred1_1;
        try {
            const ret = wasm.wasmengine_get_playback_state(this.__wbg_ptr);
            deferred1_0 = ret[0];
            deferred1_1 = ret[1];
            return getStringFromWasm0(ret[0], ret[1]);
        } finally {
            wasm.__wbindgen_free(deferred1_0, deferred1_1, 1);
        }
    }
    /**
     * Read the whole queue (one consistent snapshot).
     * @returns {string}
     */
    get_queue_json() {
        let deferred1_0;
        let deferred1_1;
        try {
            const ret = wasm.wasmengine_get_queue_json(this.__wbg_ptr);
            deferred1_0 = ret[0];
            deferred1_1 = ret[1];
            return getStringFromWasm0(ret[0], ret[1]);
        } finally {
            wasm.__wbindgen_free(deferred1_0, deferred1_1, 1);
        }
    }
    /**
     * One-way `Idle→Buffering`, idempotent (second call: no bump, no event).
     */
    initialize() {
        wasm.wasmengine_initialize(this.__wbg_ptr);
    }
    constructor() {
        const ret = wasm.wasmengine_new();
        this.__wbg_ptr = ret;
        WasmEngineFinalization.register(this, this.__wbg_ptr, this);
        return this;
    }
    /**
     * The debounced media-command entry (APPLIED=true / DEBOUNCED=false).
     * Duplicate identity within the 100 ms window is swallowed WITHOUT
     * bumping the rev — exactly the UDL contract.
     * @param {string} cmd_json
     * @returns {boolean}
     */
    on_media_command(cmd_json) {
        const ptr0 = passStringToWasm0(cmd_json, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.wasmengine_on_media_command(this.__wbg_ptr, ptr0, len0);
        return ret !== 0;
    }
    /**
     * `play_track`: the row must be staged AND carry a resolved URL —
     * returns false (FAILED) otherwise, exactly the UDL precondition.
     * On success: cursor selects the row, position target resets, state →
     * Playing, play intent emitted.
     * @param {string} track_id
     * @returns {boolean}
     */
    play_track(track_id) {
        const ptr0 = passStringToWasm0(track_id, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.wasmengine_play_track(this.__wbg_ptr, ptr0, len0);
        return ret !== 0;
    }
    /**
     * Buffer health ingest (the sinks' periodic telemetry).
     * @param {number} buffered_duration_ms
     * @param {number} underrun_count
     * @param {number} fill_ratio
     */
    record_buffer_stats(buffered_duration_ms, underrun_count, fill_ratio) {
        wasm.wasmengine_record_buffer_stats(this.__wbg_ptr, buffered_duration_ms, underrun_count, fill_ratio);
    }
    /**
     * DAC clock ingest — rate validated against the 8k–192k envelope
     * (invalid samples are dropped, mirroring the UDL validation).
     * @param {number} dac_frame_position
     * @param {number} sample_rate
     * @param {number} monotonic_ns
     */
    record_dac_clock(dac_frame_position, sample_rate, monotonic_ns) {
        wasm.wasmengine_record_dac_clock(this.__wbg_ptr, dac_frame_position, sample_rate, monotonic_ns);
    }
    /**
     * Web extension: the sink mirrors transport reality (element
     * waiting/playing/pause). Narrowing only — never Idle, never Error;
     * lifecycle authority stays with the command plane.
     * @param {string} state
     */
    report_sink_state(state) {
        const ptr0 = passStringToWasm0(state, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
        const len0 = WASM_VECTOR_LEN;
        wasm.wasmengine_report_sink_state(this.__wbg_ptr, ptr0, len0);
    }
    /**
     * Web extension: natural end-of-track. Advances WITHOUT wrap-around;
     * queue exhausted → Idle (pipeline released, queue retained).
     */
    report_track_ended() {
        wasm.wasmengine_report_track_ended(this.__wbg_ptr);
    }
    /**
     * Direct volume set (the slider path — bypasses the debounce, exactly
     * like DesktopEnginePort documents for SetVolume).
     * @param {number} volume
     */
    set_volume(volume) {
        wasm.wasmengine_set_volume(this.__wbg_ptr, volume);
    }
    /**
     * Attach the resolved stream URL to a staged row (the web landing of
     * `resolve_stream_formats`). Returns false when the id is unknown.
     * @param {string} track_id
     * @param {string} url
     * @returns {boolean}
     */
    stage_stream_url(track_id, url) {
        let ptr0, len0, ptr1, len1;
        try {
            ptr0 = passStringToWasm0(track_id, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
            len0 = WASM_VECTOR_LEN;
            ptr1 = passStringToWasm0(url, wasm.__wbindgen_malloc, wasm.__wbindgen_realloc);
            len1 = WASM_VECTOR_LEN;
        } catch (e) {
            if (ptr1) { wasm.__wbindgen_free(ptr1, len1 * 1, 1); }
            if (ptr0) { wasm.__wbindgen_free(ptr0, len0 * 1, 1); }
            throw e;
        }
        const ret = wasm.wasmengine_stage_stream_url(this.__wbg_ptr, ptr0, len0, ptr1, len1);
        return ret !== 0;
    }
    /**
     * Consume + clear the latched play intent (the JS sink's ack).
     * @returns {string | undefined}
     */
    take_play_intent_json() {
        const ret = wasm.wasmengine_take_play_intent_json(this.__wbg_ptr);
        let v1;
        if (ret[0] !== 0) {
            v1 = getStringFromWasm0(ret[0], ret[1]);
            wasm.__wbindgen_free(ret[0], ret[1] * 1, 1);
        }
        return v1;
    }
}
if (Symbol.dispose) WasmEngine.prototype[Symbol.dispose] = WasmEngine.prototype.free;
function __wbg_get_imports() {
    const import0 = {
        __proto__: null,
        __wbg___wbindgen_throw_4c88d8bf264fd984: function(arg0, arg1) {
            throw new Error(getStringFromWasm0(arg0, arg1));
        },
        __wbg_call_a410eb4b59ed5965: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = arg0.call(arg1, arg2);
            return ret;
        }, arguments); },
        __wbg_now_463ac581d2fc7ea3: function() {
            const ret = Date.now();
            return ret;
        },
        __wbindgen_generic_0000000000000001: function(arg0, arg1) {
            // Cast intrinsic for `Ref(String) -> Externref`.
            const ret = getStringFromWasm0(arg0, arg1);
            return ret;
        },
        __wbindgen_init_externref_table: function() {
            const table = wasm.__wbindgen_externrefs;
            table.set(0, undefined);
            table.set(1024, undefined);
            table.set(1025, null);
            table.set(1026, true);
            table.set(1027, false);
        },
    };
    return {
        __proto__: null,
        "./streamify_engine_wasm_bg.js": import0,
    };
}

const WasmEngineFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_wasmengine_free(ptr, 1));

function addToExternrefTable0(obj) {
    const idx = wasm.__externref_table_alloc();
    wasm.__wbindgen_externrefs.set(idx, obj);
    return idx;
}

function getStringFromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return decodeText(getUint8ArrayMemory0().subarray(ptr, ptr + len));
}

let cachedUint8ArrayMemory0 = null;
function getUint8ArrayMemory0() {
    if (cachedUint8ArrayMemory0 === null || cachedUint8ArrayMemory0.byteLength === 0) {
        cachedUint8ArrayMemory0 = new Uint8Array(wasm.memory.buffer);
    }
    return cachedUint8ArrayMemory0;
}

function handleError(f, args) {
    try {
        return f.apply(this, args);
    } catch (e) {
        const idx = addToExternrefTable0(e);
        wasm.__wbindgen_exn_store(idx);
    }
}

function passStringToWasm0(arg, malloc, realloc) {
    if (realloc === undefined) {
        const buf = cachedTextEncoder.encode(arg);
        const ptr = malloc(buf.length, 1) >>> 0;
        getUint8ArrayMemory0().subarray(ptr, ptr + buf.length).set(buf);
        WASM_VECTOR_LEN = buf.length;
        return ptr;
    }

    let len = arg.length;
    let ptr = malloc(len, 1) >>> 0;

    const mem = getUint8ArrayMemory0();

    let offset = 0;

    for (; offset < len; offset++) {
        const code = arg.charCodeAt(offset);
        if (code > 0x7F) break;
        mem[ptr + offset] = code;
    }
    if (offset !== len) {
        if (offset !== 0) {
            arg = arg.slice(offset);
        }
        ptr = realloc(ptr, len, len = offset + arg.length * 3, 1) >>> 0;
        const view = getUint8ArrayMemory0().subarray(ptr + offset, ptr + len);
        const ret = cachedTextEncoder.encodeInto(arg, view);

        offset += ret.written;
        ptr = realloc(ptr, len, offset, 1) >>> 0;
    }

    WASM_VECTOR_LEN = offset;
    return ptr;
}

let cachedTextDecoder = new TextDecoder('utf-8', { ignoreBOM: true });
cachedTextDecoder.decode();
const MAX_SAFARI_DECODE_BYTES = 2146435072;
let numBytesDecoded = 0;
function decodeText(bytes) {
    numBytesDecoded += bytes.length;
    if (numBytesDecoded >= MAX_SAFARI_DECODE_BYTES) {
        cachedTextDecoder = new TextDecoder('utf-8', { ignoreBOM: true });
        cachedTextDecoder.decode();
        numBytesDecoded = bytes.length;
    }
    return cachedTextDecoder.decode(bytes);
}

const cachedTextEncoder = new TextEncoder();

if (!('encodeInto' in cachedTextEncoder)) {
    cachedTextEncoder.encodeInto = function (arg, view) {
        const buf = cachedTextEncoder.encode(arg);
        view.set(buf);
        return {
            read: arg.length,
            written: buf.length
        };
    };
}

let WASM_VECTOR_LEN = 0;

let wasmModule, wasmInstance, wasm;
function __wbg_finalize_init(instance, module) {
    wasmInstance = instance;
    wasm = instance.exports;
    wasmModule = module;
    cachedUint8ArrayMemory0 = null;
    wasm.__wbindgen_start();
    return wasm;
}

async function __wbg_load(module, imports) {
    if (typeof Response === 'function' && module instanceof Response) {
        if (!module.ok) {
            throw new Error(`failed to fetch Wasm: ${module.status} ${module.statusText} fetching '${module.url}'`);
        }

        if (typeof WebAssembly.instantiateStreaming === 'function') {
            try {
                return await WebAssembly.instantiateStreaming(module, imports);
            } catch (e) {
                const validResponse = expectedResponseType(module.type);

                if (validResponse && module.headers.get('Content-Type') !== 'application/wasm') {
                    console.warn("`WebAssembly.instantiateStreaming` failed because your server does not serve Wasm with `application/wasm` MIME type. Falling back to `WebAssembly.instantiate` which is slower. Original error:\n", e);

                } else { throw e; }
            }
        }

        const bytes = await module.arrayBuffer();
        return await WebAssembly.instantiate(bytes, imports);
    } else {
        const instance = await WebAssembly.instantiate(module, imports);

        if (instance instanceof WebAssembly.Instance) {
            return { instance, module };
        } else {
            return instance;
        }
    }

    function expectedResponseType(type) {
        switch (type) {
            case 'basic': case 'cors': case 'default': return true;
        }
        return false;
    }
}

function initSync(module) {
    if (wasm !== undefined) return wasm;


    if (module !== undefined) {
        if (Object.getPrototypeOf(module) === Object.prototype) {
            ({module} = module)
        } else {
            console.warn('using deprecated parameters for `initSync()`; pass a single object instead')
        }
    }

    const imports = __wbg_get_imports();
    if (!(module instanceof WebAssembly.Module)) {
        module = new WebAssembly.Module(module);
    }
    const instance = new WebAssembly.Instance(module, imports);
    return __wbg_finalize_init(instance, module);
}

let __wbg_init_promise;
async function __wbg_init(module_or_path) {
    if (wasm !== undefined) return wasm;
    if (__wbg_init_promise === undefined) {
        __wbg_init_promise = __wbg_init_once(module_or_path);
        __wbg_init_promise.catch(() => { __wbg_init_promise = undefined; });
    }
    return __wbg_init_promise;
}

async function __wbg_init_once(module_or_path) {

    if (module_or_path !== undefined) {
        if (Object.getPrototypeOf(module_or_path) === Object.prototype) {
            ({module_or_path} = module_or_path)
        } else {
            console.warn('using deprecated parameters for the initialization function; pass a single object instead')
        }
    }

    if (module_or_path === undefined) {
        module_or_path = new URL('streamify_engine_wasm_bg.wasm', import.meta.url);
    }
    const imports = __wbg_get_imports();

    if (typeof module_or_path === 'string' || (typeof Request === 'function' && module_or_path instanceof Request) || (typeof URL === 'function' && module_or_path instanceof URL)) {
        module_or_path = fetch(module_or_path);
    }

    const { instance, module } = await __wbg_load(await module_or_path, imports);

    return __wbg_finalize_init(instance, module);
}

export { initSync, __wbg_init as default };
