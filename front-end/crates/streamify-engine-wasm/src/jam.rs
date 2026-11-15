//! jam.rs — the Jam session surface on the engine facade (WS-W engine
//! facade breadth, work item 1: the Jam CRDT cluster).
//!
//! # What this mirrors
//!
//! The UniFFI contract's jam lane (ADR-001: wasm-bindgen + tsify facade
//! mirroring the UniFFI contract), all of it now living in the
//! `streamify-jam-crdt` leaf (see that crate for the split rationale):
//!
//! | UDL / Android semantic (`contract.rs`) | Web lowering (this module) |
//! |---|---|
//! | `StreamifyEngine::create_jam_session(name)` | `WasmEngine::create_jam_session` — same trim/blank/128-byte-cap validation, same strictly-monotonic id mint (`JamOp::generate_op_id`), same wall-clock stamp |
//! | `apply_jam_delta(bytes)` → `JamSyncResult` | `WasmEngine::apply_jam_delta(&[u8])` — same framing contract (whole 48-byte rows, ≤1 MiB), same per-row verdict counting (clean rows deliver even when siblings are corrupt), same BLAKE3 fold hash + `needs_rebalance` |
//! | `get_jam_state_hash()` | `WasmEngine::get_jam_state_hash` — the cross-runtime Merkle currency: a web replica and an Android replica that merged the same op set derive the IDENTICAL 64-hex string (the hash code is one impl in the leaf, not a facade rewrite) |
//! | `get_jam_room_state()` | `WasmEngine::get_jam_room_state` — `roomCode`/`isHost`/`syncOffsetMs`/`participants` |
//!
//! Plus the Kotlin jam objects, ported to the leaf and exposed 1:1:
//! `FractionalIndexEngine` (fractional queue ordering — `frac_*`),
//! `JamTrackCodec` (queue-entry JSON — `jam_track_*`), the CAD-ID mint
//! (`canonical_cad_id`), the op-id mint (`jam_generate_op_id`) and the
//! 48-byte op wire pack/unpack (`jam_op_to_bytes` / `jam_op_from_bytes`).
//!
//! # Bridge conventions
//!
//! * JSON STRING payloads (the W0.2 house bridge — see streamify-wasm's
//!   module docs): serde values never cross the boundary directly.
//! * **u64 fields serialize as decimal STRINGS.** Op ids are
//!   `unix_ms << 16` — comfortably above 2^53, where JS numbers lose
//!   exactness; a JSON number would silently corrupt them. The TS bridge
//!   (`lib/jamEngine.ts`) parses these as BigInt and the op wire is
//!   BYTES (`Uint8Array`), so no precision is ever lost where it matters.
//! * f64 (`frac`) crosses as a JSON number — serde_json's ryu
//!   shortest-round-trip encoding is exact for every f64.

use wasm_bindgen::prelude::*;

use streamify_core::jam_session::{MAX_JAM_DELTA_BYTES, MAX_JAM_SESSION_NAME_BYTES};
use streamify_jam_crdt::fractional_index::{
    frac_after as r_frac_after, frac_before as r_frac_before, frac_between as r_frac_between,
    frac_collide as r_frac_collide, frac_index_for_insert_at as r_frac_index_for_insert_at,
    FracMidpoint,
};
use streamify_jam_crdt::jam_crdt::{JamCrdtState, JamOp, JAM_OP_SIZE, OpType};
use streamify_jam_crdt::jam_track_codec::{
    jam_track_from_json as r_track_from_json, jam_track_to_json as r_track_to_json,
};
use streamify_jam_crdt::{
    canonical_jam_state_hash, unix_ms_now,
    cad::generate_cad_id_u64 as r_cad_id,
};

use crate::WasmEngine;

// ---------------------------------------------------------------------------
// The session record (the contract's JamSession, facade-local)
// ---------------------------------------------------------------------------

/// The engine's active Jam session (the contract's private `JamSession`).
pub struct JamSessionState {
    pub session_id: u64,
    pub session_name: String,
    pub created_at_unix_ms: u64,
    /// The room's shared-queue CRDT: fractional-indexed queue with
    /// tombstones plus the democratic vote ledger.
    pub crdt: JamCrdtState,
}

// -- DTOs (serde layer; u64s as decimal strings — see module docs) --------

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct JamHandleDto {
    session_id: String,
    session_name: String,
    created_at_unix_ms: String,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct JamSyncResultDto {
    ops_applied: u32,
    ops_rejected: u32,
    state_hash: String,
    needs_rebalance: bool,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct JamRoomStateDto {
    room_code: String,
    is_host: bool,
    sync_offset_ms: f64,
    participants: Vec<String>,
}

#[derive(serde::Serialize, Clone, Copy)]
#[serde(rename_all = "camelCase")]
struct FracMidpointDto {
    value: f64,
    needs_rebalance: bool,
}

impl From<FracMidpoint> for FracMidpointDto {
    fn from(m: FracMidpoint) -> Self {
        FracMidpointDto {
            value: m.value,
            needs_rebalance: m.needs_rebalance,
        }
    }
}

/// The op JSON accepted/returned by `jam_op_to_bytes` / `jam_op_from_bytes`
/// (camelCase; u64 fields as decimal strings; `senderNonce` as 8 hex chars).
#[derive(serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct JamOpDto {
    op_id: String,
    sender_nonce: String,
    op_type: u8,
    #[serde(default)]
    policy_flags: u8,
    cad_id: String,
    frac: f64,
    #[serde(default)]
    target: String,
}

fn dto_json<T: serde::Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "{}".to_string())
}

// ---------------------------------------------------------------------------
// WasmEngine: the jam session lane (mirrors the UDL methods 1:1)
// ---------------------------------------------------------------------------

#[wasm_bindgen]
impl WasmEngine {
    /// Create the engine's active Jam session. Mirrors
    /// `StreamifyEngine::create_jam_session` exactly: trim + blank/128-byte
    /// cap rejection, the strictly-monotonic id mint, the wall-clock stamp.
    /// Returns the handle JSON (u64s as strings) or throws.
    pub fn create_jam_session(&mut self, session_name: &str) -> Result<JsValue, JsValue> {
        let trimmed = session_name.trim();
        if trimmed.is_empty() || session_name.len() > MAX_JAM_SESSION_NAME_BYTES {
            return Err(JsValue::from_str(
                "invalid state: blank or oversized Jam session name rejected",
            ));
        }
        // The sanctioned strictly-monotonic per-process id mint: unique
        // across every session this wasm module creates, immune to clock
        // step-back (same discipline as CRDT op ids).
        let session_id = JamOp::generate_op_id();
        let session = JamSessionState {
            session_id,
            session_name: trimmed.to_string(),
            created_at_unix_ms: unix_ms_now(),
            crdt: JamCrdtState::new(),
        };
        let dto = JamHandleDto {
            session_id: session.session_id.to_string(),
            session_name: session.session_name.clone(),
            created_at_unix_ms: session.created_at_unix_ms.to_string(),
        };
        self.jam = Some(session);
        Ok(JsValue::from_str(&dto_json(&dto)))
    }

    /// Atomically apply one Jam CRDT delta batch. `delta` is a
    /// concatenation of 48-byte little-endian wire rows (the frozen
    /// `rust_48` image pinned by fixtures/jam_golden_vectors.json — the
    /// same rows the mesh gossips as MSG_CRDT_OP payloads). Rows merge in
    /// order; a row refused at the boundary (corrupt checksum, non-zero
    /// pads, NaN fraction, unknown op type, tombstone-suppressed Add replay)
    /// is counted in `opsRejected` and skipped — a partially corrupt batch
    /// still delivers its clean rows (the mesh ingress contract).
    pub fn apply_jam_delta(&mut self, delta: &[u8]) -> Result<JsValue, JsValue> {
        if delta.is_empty() || !delta.len().is_multiple_of(JAM_OP_SIZE) {
            return Err(JsValue::from_str(&format!(
                "codec framing: jam delta length {} is not a multiple of the {JAM_OP_SIZE}-byte wire row",
                delta.len()
            )));
        }
        if delta.len() > MAX_JAM_DELTA_BYTES {
            return Err(JsValue::from_str(&format!(
                "codec framing: jam delta of {} bytes exceeds the {MAX_JAM_DELTA_BYTES}-byte batch bound",
                delta.len()
            )));
        }
        let Some(session) = self.jam.as_mut() else {
            return Err(JsValue::from_str(
                "invalid state: no live Jam session for delta application",
            ));
        };
        let mut ops_applied = 0u32;
        let mut ops_rejected = 0u32;
        for row in delta.chunks_exact(JAM_OP_SIZE) {
            match JamOp::from_bytes(row) {
                Some(op) => {
                    if session.crdt.apply_op(&op) {
                        ops_applied += 1;
                    } else {
                        ops_rejected += 1;
                    }
                }
                None => ops_rejected += 1, // non-zero pads / structurally corrupt row
            }
        }
        let dto = JamSyncResultDto {
            ops_applied,
            ops_rejected,
            state_hash: canonical_jam_state_hash(&session.crdt),
            needs_rebalance: session.crdt.needs_rebalance,
        };
        Ok(JsValue::from_str(&dto_json(&dto)))
    }

    /// Deterministic state hash of the active Jam session (BLAKE3 over the
    /// canonical fold). Requires a live session; never mutates state.
    pub fn get_jam_state_hash(&mut self) -> Result<JsValue, JsValue> {
        let Some(session) = self.jam.as_ref() else {
            return Err(JsValue::from_str(
                "invalid state: no live Jam session to hash",
            ));
        };
        Ok(JsValue::from_str(&canonical_jam_state_hash(&session.crdt)))
    }

    /// Presentation state of the engine's active Jam room (the local
    /// replica view — the transport layer fills participants once the
    /// channel is live; `isHost` reflects the LOCAL engine, exactly like
    /// the contract).
    pub fn get_jam_room_state(&mut self) -> Result<JsValue, JsValue> {
        let Some(session) = self.jam.as_ref() else {
            return Err(JsValue::from_str(
                "invalid state: no live Jam session for room state",
            ));
        };
        let dto = JamRoomStateDto {
            room_code: session.session_name.clone(),
            is_host: true,
            sync_offset_ms: 0.0,
            participants: vec![format!("local:{}", session.session_id)],
        };
        Ok(JsValue::from_str(&dto_json(&dto)))
    }
}

// ---------------------------------------------------------------------------
// The Kotlin jam objects, exposed 1:1 (free functions)
// ---------------------------------------------------------------------------

/// `JamOp.generate_op_id()` — the strictly-monotonic id mint, DECIMAL
/// STRING (u64 > 2^53; the TS bridge parses BigInt).
#[wasm_bindgen]
pub fn jam_generate_op_id() -> String {
    JamOp::generate_op_id().to_string()
}

/// `canonical_cad_id(title, artist, duration_sec)` — the canonical track
/// identity for op building, DECIMAL STRING.
#[wasm_bindgen]
pub fn canonical_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
    r_cad_id(title, artist, duration_sec).to_string()
}

/// `FractionalIndexEngine.after` → `{"value":f64,"needsRebalance":bool}`.
#[wasm_bindgen]
pub fn frac_after(last: Option<f64>) -> String {
    dto_json(&FracMidpointDto::from(r_frac_after(last)))
}

/// `FractionalIndexEngine.before`.
#[wasm_bindgen]
pub fn frac_before(first: Option<f64>) -> String {
    dto_json(&FracMidpointDto::from(r_frac_before(first)))
}

/// `FractionalIndexEngine.between`.
#[wasm_bindgen]
pub fn frac_between(prev: Option<f64>, next: Option<f64>) -> String {
    dto_json(&FracMidpointDto::from(r_frac_between(prev, next)))
}

/// `FractionalIndexEngine.indexForInsertAt(position, orderedFracs)`.
#[wasm_bindgen]
pub fn frac_index_for_insert_at(position: u32, ordered_fracs: &[f64]) -> String {
    dto_json(&FracMidpointDto::from(r_frac_index_for_insert_at(
        position as usize,
        ordered_fracs,
    )))
}

/// `FractionalIndexEngine.collide(a, b)`.
#[wasm_bindgen]
pub fn frac_collide(a: f64, b: f64) -> bool {
    r_frac_collide(a, b)
}

/// `jamTrackToJson(trackJson)` — normalize-encode one queue entry (blank
/// optionals dropped, addedBy blank dropped). Throws on malformed JSON.
#[wasm_bindgen]
pub fn jam_track_to_json(track_json: &str) -> Result<String, JsValue> {
    let track = r_track_from_json(track_json).ok_or_else(|| {
        JsValue::from_str("codec framing: jam track JSON rejected (blank/missing title)")
    })?;
    Ok(r_track_to_json(&track))
}

/// `jamTrackFromJson(raw)` — decode one queue entry; returns the JSON
/// string `"null"` when the title is missing/blank (a titleless row must
/// never enter the jam queue).
#[wasm_bindgen]
pub fn jam_track_from_json(raw: &str) -> String {
    match r_track_from_json(raw) {
        Some(track) => r_track_to_json(&track),
        None => "null".to_string(),
    }
}

/// Pack one op JSON into the frozen 48-byte little-endian wire row
/// (`JamOp::new` semantics: pads zeroed, checksum sealed over [0..40)).
/// The op JSON must carry `opId`/`senderNonce`(8 hex)/`opType`(1..=4)/
/// `cadId`/`frac`; `policyFlags`/`target` default to 0. Throws on a
/// malformed row (unknown op type, bad hex, non-numeric u64 string).
#[wasm_bindgen]
pub fn jam_op_to_bytes(op_json: &str) -> Result<Vec<u8>, JsValue> {
    let dto: JamOpDto = serde_json::from_str(op_json)
        .map_err(|e| JsValue::from_str(&format!("codec framing: bad op JSON: {e}")))?;
    let op_id: u64 = dto
        .op_id
        .parse()
        .map_err(|_| JsValue::from_str("codec framing: opId must be a decimal u64 string"))?;
    let cad_id: u64 = dto
        .cad_id
        .parse()
        .map_err(|_| JsValue::from_str("codec framing: cadId must be a decimal u64 string"))?;
    let target: u64 = dto
        .target
        .parse()
        .unwrap_or(0);
    let sender_nonce = hex_field(&dto.sender_nonce)
        .map_err(|_| JsValue::from_str("codec framing: senderNonce must be 8 hex chars"))?;
    let op_type = op_type_from_u8(dto.op_type)
        .ok_or_else(|| JsValue::from_str("codec framing: opType must be 1..=4"))?;
    let op = JamOp::new(op_id, sender_nonce, op_type, dto.policy_flags, cad_id, dto.frac, target);
    Ok(op.to_bytes().to_vec())
}

/// Unpack one 48-byte wire row into the op JSON (u64s as decimal strings);
/// returns `"null"` for a structurally corrupt row (non-zero pads, wrong
/// length) — a malformed op must never enter the CRDT.
#[wasm_bindgen]
pub fn jam_op_from_bytes(bytes: &[u8]) -> String {
    match JamOp::from_bytes(bytes) {
        Some(op) => {
            let dto = JamOpDto {
                op_id: op.op_id.to_string(),
                sender_nonce: op
                    .sender_nonce
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect::<String>(),
                op_type: op.op_type,
                policy_flags: op.policy_flags,
                cad_id: op.track_cad_id.to_string(),
                frac: op.frac_index,
                target: op.target_add_op_id.to_string(),
            };
            dto_json(&dto)
        }
        None => "null".to_string(),
    }
}

/// 8-hex-char → [u8; 4] (the sender nonce field).
fn hex_field(s: &str) -> Result<[u8; 4], ()> {
    let s = s.trim();
    if s.len() != 8 {
        return Err(());
    }
    let mut out = [0u8; 4];
    for (i, pair) in s.as_bytes().chunks(2).enumerate() {
        let hi = (pair[0] as char).to_digit(16).ok_or(())? as u8;
        let lo = (pair[1] as char).to_digit(16).ok_or(())? as u8;
        out[i] = (hi << 4) | lo;
    }
    Ok(out)
}

/// 1..=4 → OpType (the frozen wire enum; anything else is refused at the gate).
fn op_type_from_u8(v: u8) -> Option<OpType> {
    match v {
        1 => Some(OpType::Add),
        2 => Some(OpType::Remove),
        3 => Some(OpType::Reorder),
        4 => Some(OpType::Vote),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Tests (wasm-bindgen-test, headless Chrome — the CI lane)
// ---------------------------------------------------------------------------

#[cfg(all(target_arch = "wasm32", test))]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test;

    /// The frozen jam golden fixture — the SAME file the native suite reads
    /// at runtime (fixtures/jam_golden_vectors.json, the file the neutral
    /// generator script owns), embedded at compile time (golden-wasm
    /// precedent). Read-only reference into the repo root by design.
    const GOLDEN: &[u8] = include_bytes!("../../../../fixtures/jam_golden_vectors.json");

    fn load_golden() -> serde_json::Value {
        serde_json::from_slice(GOLDEN)
            .unwrap_or_else(|e| panic!("embedded jam fixture is not valid JSON: {e}"))
    }

    /// The golden wire image stays bit-frozen ON WASM (the native suite's
    /// pin, mirrored): parse → re-serialize reproduces the frozen 48 bytes
    /// exactly; the op self-validates its checksum; the BLAKE3 image of
    /// every golden op matches the native reference (cross-runtime hash
    /// parity — get_jam_state_hash relies on exactly this property).
    #[wasm_bindgen_test]
    fn jam_golden_wire_images_are_bit_frozen_on_wasm() {
        let doc = load_golden();
        let vectors = doc["vectors"].as_array().expect("fixture: vectors");
        assert!(!vectors.is_empty(), "fixture carries no op vectors");
        for v in vectors {
            let name = v["name"].as_str().unwrap_or("<unnamed>");
            let rust_48 = hex_decode(v["rust_48"].as_str().expect("rust_48"));
            assert_eq!(rust_48.len(), 48, "{name}: rust_48 must be 48 bytes");
            let op = JamOp::from_bytes(&rust_48)
                .unwrap_or_else(|| panic!("{name}: golden rust_48 must parse"));
            assert_eq!(
                op.to_bytes().to_vec(),
                rust_48,
                "{name}: Rust wire image drifted from the golden fixture — a FROZEN \
                 format changed (see scripts/generate_jam_golden_vectors.py)"
            );
            assert!(op.is_valid(), "{name}: golden op checksum must validate");
            // BLAKE3 of the golden image vs the native reference hex.
            let h = blake3::hash(&rust_48).to_hex().to_string();
            assert_eq!(
                h,
                v["rust_48_blake3"].as_str().expect("rust_48_blake3"),
                "{name}: BLAKE3 of the wire image drifted (wasm vs native hash parity)"
            );
            // The two frozen formats must never converge: a Kotlin image
            // either fails to parse or round-trips to DIFFERENT bytes.
            let kotlin_48 =
                crate::jam::tests::hex_decode(v["kotlin_48"].as_str().expect("kotlin_48"));
            if let Some(kop) = JamOp::from_bytes(&kotlin_48) {
                assert_ne!(
                    kop.to_bytes().to_vec(),
                    rust_48,
                    "{name}: Kotlin wire image round-trips to the RUST golden bytes — the \
                     two frozen formats have converged"
                );
            }
        }
    }

    fn hex_decode(s: &str) -> Vec<u8> {
        (0..s.len() / 2)
            .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).expect("valid hex pair"))
            .collect()
    }

    /// The session lifecycle + the convergence contract IN-BROWSER: two
    /// engines (two replicas) merge the same op batch in opposite orders
    /// and derive the identical BLAKE3 state hash — the Merkle root
    /// property that makes cross-device reconciliation possible.
    #[wasm_bindgen_test]
    fn jam_session_lifecycle_and_cross_replica_convergence() {
        let mut a = WasmEngine::new();
        let mut b = WasmEngine::new();

        // Blank name rejected with the contract's exact message.
        assert!(a.create_jam_session("   ").is_err());

        // Create on both engines (the web lowering: one wasm module per
        // page — the second engine stands in for a second PEER replica).
        let ha = a.create_jam_session("Room A").expect("create a");
        let handle: serde_json::Value =
            serde_json::from_str(&ha.as_string().expect("handle json")).expect("parse");
        assert_eq!(handle["sessionName"], "Room A");
        // u64s as strings — the precision bridge (op ids are > 2^53).
        assert!(handle["sessionId"].as_str().is_some());
        assert!(handle["createdAtUnixMs"].as_str().is_some());
        let sid: u64 = handle["sessionId"].as_str().unwrap().parse().unwrap();
        assert!(sid > (1u64 << 53), "session ids live above 2^53");

        assert!(b.create_jam_session("Room A").is_ok());

        // Framing errors: not a multiple of 48 / over the 1 MiB bound.
        assert!(a.apply_jam_delta(&[0u8; 47]).is_err());
        assert!(a.apply_jam_delta(&[]).is_err());

        // One clean batch: 3 Adds. Build via the pack lane (the transport's
        // own path) so the pack fn is exercised end-to-end.
        let cad = |t: &str, a: &str, d: u32| r_cad_id(t, a, d);
        let c1 = cad("Track One", "Artist", 200);
        let c2 = cad("Track Two", "Artist", 180);
        let c3 = cad("Track Three", "Artist", 240);
        let mk = |i: u64, c: u64, frac: f64| {
            jam_op_to_bytes(&serde_json::json!({
                "opId": format!("{}", 1_700_000_000_000u64 * 1000 + i),
                "senderNonce": "aabbccdd",
                "opType": 1,
                "policyFlags": 0,
                "cadId": c.to_string(),
                "frac": frac,
                "target": "0",
            })
            .to_string())
            .expect("pack op")
        };
        let batch_a = [mk(1, c1, 0.5), mk(2, c2, 1.5), mk(3, c3, 2.5)].concat();
        let batch_b = [mk(3, c3, 2.5), mk(1, c1, 0.5), mk(2, c2, 1.5)].concat();

        let ra = a.apply_jam_delta(&batch_a).expect("apply a");
        let rb = b.apply_jam_delta(&batch_b).expect("apply b");
        let sa: serde_json::Value =
            serde_json::from_str(&ra.as_string().unwrap()).expect("sync result a");
        let sb: serde_json::Value =
            serde_json::from_str(&rb.as_string().unwrap()).expect("sync result b");
        assert_eq!(sa["opsApplied"], 3);
        assert_eq!(sa["opsRejected"], 0);
        assert_eq!(sb["opsApplied"], 3);

        // THE convergence property: identical state hash across replicas.
        let ha = a.get_jam_state_hash().expect("hash a").as_string().unwrap();
        let hb = b.get_jam_state_hash().expect("hash b").as_string().unwrap();
        assert_eq!(ha, hb, "cross-replica state hash must converge");
        assert_eq!(ha.len(), 64, "BLAKE3 hex length");

        // Room state mirrors the contract's shape.
        let room = a.get_jam_room_state().expect("room").as_string().unwrap();
        let room: serde_json::Value = serde_json::from_str(&room).unwrap();
        assert_eq!(room["roomCode"], "Room A");
        assert_eq!(room["isHost"], true);
        assert_eq!(room["syncOffsetMs"], 0.0);

        // A corrupt row (non-zero pad) is rejected but the batch delivers.
        let evil = {
            let mut e = mk(4, c1, 3.5);
            e[15] = 0x01; // _pad1 must be zero
            e
        };
        let mixed = [evil, mk(5, c2, 4.5)].concat();
        let r = a.apply_jam_delta(&mixed).expect("apply mixed");
        let sr: serde_json::Value = serde_json::from_str(&r.as_string().unwrap()).unwrap();
        assert_eq!(sr["opsApplied"], 1, "clean row delivers");
        assert_eq!(sr["opsRejected"], 1, "corrupt row rejected");
    }

    /// Fractional-index anchors (the Kotlin object's frozen semantics).
    #[wasm_bindgen_test]
    fn fractional_index_anchors_match_kotlin() {
        let parse = |s: String| -> (f64, bool) {
            let v: serde_json::Value = serde_json::from_str(&s).unwrap();
            (v["value"].as_f64().unwrap(), v["needsRebalance"].as_bool().unwrap())
        };
        // after(null) == 0.5; before(null) == 0.0625; tail append = +1.
        assert_eq!(parse(frac_after(None)), (0.5, false));
        assert_eq!(parse(frac_before(None)), (0.0625, false));
        assert_eq!(parse(frac_after(Some(3.0))), (4.0, false));
        // between(0.25, 0.75) == 0.5; inverted input flags the wall.
        assert_eq!(parse(frac_between(Some(0.25), Some(0.75))), (0.5, false));
        assert_eq!(parse(frac_between(Some(0.75), Some(0.25))), (0.75, true));
        // insert-at slices + clamping.
        let fracs = [0.5, 1.0, 2.0];
        assert_eq!(parse(frac_index_for_insert_at(0, &fracs)), (0.25, false));
        assert_eq!(parse(frac_index_for_insert_at(2, &fracs)), (1.5, false));
        assert_eq!(parse(frac_index_for_insert_at(99, &fracs)), (3.0, false));
        // collide uses ulp spacing.
        assert!(frac_collide(0.5, 0.5f64.next_up()));
        assert!(!frac_collide(0.5, 0.5f64 + 1e-9));
    }

    /// The queue-entry codec + the Java-hash parity pins, in-browser.
    #[wasm_bindgen_test]
    fn jam_track_codec_round_trip_and_java_hash() {
        // Pack lane: canonical encode drops blank optionals; decode rejects
        // blank titles and falls back to -(title.hashCode()).
        let packed = jam_track_to_json(
            &serde_json::json!({
                "title": "hello",
                "artist": "a",
                "coverArtPath": "",
                "durationSec": 100,
                "addedBy": "  ",
                "ytmVideoId": "abc",
            })
            .to_string(),
        )
        .expect("pack track");
        let v: serde_json::Value = serde_json::from_str(&packed).unwrap();
        assert_eq!(v["album"], "Streamify Jam"); // blank album default
        assert!(v.get("addedBy").is_none()); // blank addedBy dropped
        assert_eq!(v["ytmVideoId"], "abc");
        assert!(v.get("isrc").is_none());

        assert_eq!(jam_track_from_json("{\"title\":\"  \"}"), "null");
        let decoded = jam_track_from_json(
            &serde_json::json!({"title": "hello", "artist": "a"}).to_string(),
        );
        let d: serde_json::Value = serde_json::from_str(&decoded).unwrap();
        assert_eq!(d["id"], -99162322i64); // -(Java "hello".hashCode())
    }

    /// The op pack/unpack lane keeps u64 precision across the JSON string
    /// bridge (op ids live above 2^53 — the reason the bridge is strings).
    #[wasm_bindgen_test]
    fn jam_op_pack_unpack_preserves_u64_precision() {
        // A realistic mint: unix_ms(1.77e12) << 16 ≈ 1.16e17 — comfortably
        // above 2^53, where JS numbers round.
        let op_id: u64 = 1_770_000_000_000u64 << 16;
        assert!(op_id > (1u64 << 53));
        let cad: u64 = 0xDEAD_BEEF_CAFE_F00d;
        let packed = jam_op_to_bytes(
            &serde_json::json!({
                "opId": op_id.to_string(),
                "senderNonce": "01020304",
                "opType": 1,
                "cadId": cad.to_string(),
                "frac": 0.5625,
            })
            .to_string(),
        )
        .expect("pack");
        assert_eq!(packed.len(), 48);
        // Round-trip through the JSON string bridge: exact u64 recovery.
        let json = jam_op_from_bytes(&packed);
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["opId"].as_str(), Some(op_id.to_string().as_str()));
        assert_eq!(v["cadId"].as_str(), Some(cad.to_string().as_str()));
        assert_eq!(v["senderNonce"], "01020304");
        assert_eq!(v["frac"], 0.5625);
        assert_eq!(v["target"].as_str(), Some("0"));
        // Structurally corrupt rows never enter the CRDT.
        let mut evil = packed.clone();
        evil[15] = 0xFF; // non-zero pad
        assert_eq!(jam_op_from_bytes(&evil), "null");
        // Unknown op type rejected at the pack gate.
        assert!(jam_op_to_bytes(
            &serde_json::json!({
                "opId": "1", "senderNonce": "aabbccdd", "opType": 9,
                "cadId": "5", "frac": 0.5,
            })
            .to_string(),
        )
        .is_err());
    }
}
