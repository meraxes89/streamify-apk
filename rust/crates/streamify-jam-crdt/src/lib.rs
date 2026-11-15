//! streamify-jam-crdt — the pure Jam CRDT leaf (WS-W engine facade breadth).
//!
//! The jam domain's REPLICATED-STATE engine, split out of `streamify-jam` so
//! it compiles everywhere. The mesh crate's transport surface (tokio net,
//! socket2, rusqlite, ed25519 governance) is native-only, which left the
//! CRDT — the one part all four runtimes share — trapped behind a wall the
//! wasm32 build could never cross (§9.1 says the jam CRDT compiles to wasm
//! "as-is"; the split is what makes that true). The golden rule applies: the
//! engine change lands in `rust/` once and every runtime benefits; the wasm
//! facade (`front-end/crates/streamify-engine-wasm`) only exposes it.
//!
//! Module map (every move is verbatim + documented here):
//! * [`jam_crdt`] — from `streamify-jam/src/jam_crdt.rs`: the Phase-2
//!   democratic CRDT (48-byte ops, folds, vote ledger, promotions). The only
//!   edits: the CAD-ID import now points at this crate's [`cad`] module, and
//!   op-id minting reads [`unix_ms_now`] (SystemTime traps on wasm32).
//! * [`cad`] — from `streamify-storage/src/repository.rs`: the canonical
//!   CAD-ID hasher (FNV-1a over normalized fields + duration bucket).
//! * [`fractional_index`] — NEW: the Kotlin `FractionalIndexEngine` port
//!   (`app/.../jam/FractionalIndexEngine.kt`), previously Kotlin-only.
//! * [`jam_track_codec`] — NEW: the Kotlin `JamTrackCodec` port
//!   (`app/.../jam/JamTrackCodec.kt`), previously Kotlin-only (org.json).
//! * [`state_hash`] — from `streamify-contract/src/contract.rs`: the
//!   canonical BLAKE3 fold hash (the Merkle reconciliation currency).
//!
//! `streamify-jam` re-exports this whole crate's public surface at its own
//! `streamify_jam::jam_crdt` path, so every existing native consumer
//! (contract, ffi/jni, the mesh tests) keeps compiling unchanged.

pub mod cad;
pub mod fractional_index;
pub mod jam_crdt;
pub mod jam_track_codec;
pub mod state_hash;

// Root re-exports (the house root-surface pattern; mirrors streamify-core).
pub use cad::{generate_cad_id_u64, normalize_cad_field};
pub use fractional_index::{
    frac_after, frac_before, frac_between, frac_collide, frac_index_for_insert_at, FracMidpoint,
    FRAC_FIRST_INDEX, FRAC_HEAD,
};
pub use jam_crdt::{
    canonical_cad_id, JamCrdtState, JamOp, OpType, QueueViewEntry, JAM_OP_SIZE, MAX_VOTE_TARGETS,
    VOTE_FLAG_UP,
};
pub use jam_track_codec::{jam_track_from_json, jam_track_to_json, JamTrack, java_string_hash};
pub use state_hash::canonical_jam_state_hash;

// ---------------------------------------------------------------------------
// The house clock
// ---------------------------------------------------------------------------

/// Wall-clock unix milliseconds. Native: `SystemTime`; wasm32: the house
/// wasm clock `js_sys::Date::now()` — `SystemTime::now()` traps on that
/// target (W0.1 landmine #4; the vendored rustypipe uses exactly this
/// lowering). Op-id minting and session timestamps go through here so the
/// whole crate compiles on wasm32-unknown-unknown.
#[cfg(not(target_arch = "wasm32"))]
pub fn unix_ms_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(target_arch = "wasm32")]
pub fn unix_ms_now() -> u64 {
    js_sys::Date::now().max(0.0) as u64
}
