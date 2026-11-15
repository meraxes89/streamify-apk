/* tslint:disable */
/* eslint-disable */

/**
 * The wasm engine control plane. See the crate docs for the semantic map.
 */
export class WasmEngine {
    free(): void;
    [Symbol.dispose](): void;
    /**
     * Register a snapshot listener; returns its id. Registration bumps the
     * rev and fires the listener immediately with a full snapshot (the UDL
     * "fresh listener gets a full snapshot next pass" guarantee, hoisted to
     * registration time on this single-threaded runtime).
     */
    add_event_listener(cb: Function): number;
    /**
     * Atomically apply one Jam CRDT delta batch. `delta` is a
     * concatenation of 48-byte little-endian wire rows (the frozen
     * `rust_48` image pinned by fixtures/jam_golden_vectors.json — the
     * same rows the mesh gossips as MSG_CRDT_OP payloads). Rows merge in
     * order; a row refused at the boundary (corrupt checksum, non-zero
     * pads, NaN fraction, unknown op type, tombstone-suppressed Add replay)
     * is counted in `opsRejected` and skipped — a partially corrupt batch
     * still delivers its clean rows (the mesh ingress contract).
     */
    apply_jam_delta(delta: Uint8Array | number[]): any;
    clear_queue(): void;
    /**
     * Housekeeping hook for the JS wrapper (no-op here; the UDL has no
     * listener removal and the engine outlives the page — documented).
     */
    close(): void;
    /**
     * Create the engine's active Jam session. Mirrors
     * `StreamifyEngine::create_jam_session` exactly: trim + blank/128-byte
     * cap rejection, the strictly-monotonic id mint, the wall-clock stamp.
     * Returns the handle JSON (u64s as strings) or throws.
     */
    create_jam_session(session_name: string): any;
    /**
     * Enqueue a track row; returns its queue index. Duplicated ids are
     * allowed (the UDL queue holds rows, not a set) up to MAX_QUEUE_ROWS;
     * beyond the cap the row is refused (returns u32::MAX).
     */
    enqueue_track(track_json: string, enqueued_by: string): number;
    /**
     * One-shot cold-start read (the sanctioned one-shot snapshot).
     */
    get_engine_snapshot_json(): string;
    /**
     * Presentation state of the engine's active Jam room (the local
     * replica view — the transport layer fills participants once the
     * channel is live; `isHost` reflects the LOCAL engine, exactly like
     * the contract).
     */
    get_jam_room_state(): any;
    /**
     * Deterministic state hash of the active Jam session (BLAKE3 over the
     * canonical fold). Requires a live session; never mutates state.
     */
    get_jam_state_hash(): any;
    /**
     * One-shot diagnostics read of the lifecycle state name.
     */
    get_playback_state(): string;
    /**
     * Read the whole queue (one consistent snapshot).
     */
    get_queue_json(): string;
    /**
     * One-way `Idle→Buffering`, idempotent (second call: no bump, no event).
     */
    initialize(): void;
    constructor();
    /**
     * The debounced media-command entry (APPLIED=true / DEBOUNCED=false).
     * Duplicate identity within the 100 ms window is swallowed WITHOUT
     * bumping the rev — exactly the UDL contract.
     */
    on_media_command(cmd_json: string): boolean;
    /**
     * `play_track`: the row must be staged AND carry a resolved URL —
     * returns false (FAILED) otherwise, exactly the UDL precondition.
     * On success: cursor selects the row, position target resets, state →
     * Playing, play intent emitted.
     */
    play_track(track_id: string): boolean;
    /**
     * Buffer health ingest (the sinks' periodic telemetry).
     */
    record_buffer_stats(buffered_duration_ms: number, underrun_count: number, fill_ratio: number): void;
    /**
     * DAC clock ingest — rate validated against the 8k–192k envelope
     * (invalid samples are dropped, mirroring the UDL validation).
     */
    record_dac_clock(dac_frame_position: number, sample_rate: number, monotonic_ns: number): void;
    /**
     * Web extension: the sink mirrors transport reality (element
     * waiting/playing/pause). Narrowing only — never Idle, never Error;
     * lifecycle authority stays with the command plane.
     */
    report_sink_state(state: string): void;
    /**
     * Web extension: natural end-of-track. Advances WITHOUT wrap-around;
     * queue exhausted → Idle (pipeline released, queue retained).
     */
    report_track_ended(): void;
    /**
     * Direct volume set (the slider path — bypasses the debounce, exactly
     * like DesktopEnginePort documents for SetVolume).
     */
    set_volume(volume: number): void;
    /**
     * Attach the resolved stream URL to a staged row (the web landing of
     * `resolve_stream_formats`). Returns false when the id is unknown.
     */
    stage_stream_url(track_id: string, url: string): boolean;
    /**
     * Consume + clear the latched play intent (the JS sink's ack).
     */
    take_play_intent_json(): string | undefined;
}

/**
 * `canonical_cad_id(title, artist, duration_sec)` — the canonical track
 * identity for op building, DECIMAL STRING.
 */
export function canonical_cad_id(title: string, artist: string, duration_sec: number): string;

/**
 * `FractionalIndexEngine.after` → `{"value":f64,"needsRebalance":bool}`.
 */
export function frac_after(last?: number | null): string;

/**
 * `FractionalIndexEngine.before`.
 */
export function frac_before(first?: number | null): string;

/**
 * `FractionalIndexEngine.between`.
 */
export function frac_between(prev?: number | null, next?: number | null): string;

/**
 * `FractionalIndexEngine.collide(a, b)`.
 */
export function frac_collide(a: number, b: number): boolean;

/**
 * `FractionalIndexEngine.indexForInsertAt(position, orderedFracs)`.
 */
export function frac_index_for_insert_at(position: number, ordered_fracs: Float64Array | number[]): string;

/**
 * `JamOp.generate_op_id()` — the strictly-monotonic id mint, DECIMAL
 * STRING (u64 > 2^53; the TS bridge parses BigInt).
 */
export function jam_generate_op_id(): string;

/**
 * Unpack one 48-byte wire row into the op JSON (u64s as decimal strings);
 * returns `"null"` for a structurally corrupt row (non-zero pads, wrong
 * length) — a malformed op must never enter the CRDT.
 */
export function jam_op_from_bytes(bytes: Uint8Array | number[]): string;

/**
 * Pack one op JSON into the frozen 48-byte little-endian wire row
 * (`JamOp::new` semantics: pads zeroed, checksum sealed over [0..40)).
 * The op JSON must carry `opId`/`senderNonce`(8 hex)/`opType`(1..=4)/
 * `cadId`/`frac`; `policyFlags`/`target` default to 0. Throws on a
 * malformed row (unknown op type, bad hex, non-numeric u64 string).
 */
export function jam_op_to_bytes(op_json: string): Uint8Array;

/**
 * `jamTrackFromJson(raw)` — decode one queue entry; returns the JSON
 * string `"null"` when the title is missing/blank (a titleless row must
 * never enter the jam queue).
 */
export function jam_track_from_json(raw: string): string;

/**
 * `jamTrackToJson(trackJson)` — normalize-encode one queue entry (blank
 * optionals dropped, addedBy blank dropped). Throws on malformed JSON.
 */
export function jam_track_to_json(track_json: string): string;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_wasmengine_free: (a: number, b: number) => void;
    readonly canonical_cad_id: (a: number, b: number, c: number, d: number, e: number) => [number, number];
    readonly frac_after: (a: number, b: number) => [number, number];
    readonly frac_before: (a: number, b: number) => [number, number];
    readonly frac_between: (a: number, b: number, c: number, d: number) => [number, number];
    readonly frac_collide: (a: number, b: number) => number;
    readonly frac_index_for_insert_at: (a: number, b: number, c: number) => [number, number];
    readonly jam_generate_op_id: () => [number, number];
    readonly jam_op_from_bytes: (a: number, b: number) => [number, number];
    readonly jam_op_to_bytes: (a: number, b: number) => [number, number, number, number];
    readonly jam_track_from_json: (a: number, b: number) => [number, number];
    readonly jam_track_to_json: (a: number, b: number) => [number, number, number, number];
    readonly wasmengine_add_event_listener: (a: number, b: any) => number;
    readonly wasmengine_apply_jam_delta: (a: number, b: number, c: number) => [number, number, number];
    readonly wasmengine_clear_queue: (a: number) => void;
    readonly wasmengine_close: (a: number) => void;
    readonly wasmengine_create_jam_session: (a: number, b: number, c: number) => [number, number, number];
    readonly wasmengine_enqueue_track: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly wasmengine_get_engine_snapshot_json: (a: number) => [number, number];
    readonly wasmengine_get_jam_room_state: (a: number) => [number, number, number];
    readonly wasmengine_get_jam_state_hash: (a: number) => [number, number, number];
    readonly wasmengine_get_playback_state: (a: number) => [number, number];
    readonly wasmengine_get_queue_json: (a: number) => [number, number];
    readonly wasmengine_initialize: (a: number) => void;
    readonly wasmengine_new: () => number;
    readonly wasmengine_on_media_command: (a: number, b: number, c: number) => number;
    readonly wasmengine_play_track: (a: number, b: number, c: number) => number;
    readonly wasmengine_record_buffer_stats: (a: number, b: number, c: number, d: number) => void;
    readonly wasmengine_record_dac_clock: (a: number, b: number, c: number, d: number) => void;
    readonly wasmengine_report_sink_state: (a: number, b: number, c: number) => void;
    readonly wasmengine_report_track_ended: (a: number) => void;
    readonly wasmengine_set_volume: (a: number, b: number) => void;
    readonly wasmengine_stage_stream_url: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly wasmengine_take_play_intent_json: (a: number) => [number, number];
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
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
