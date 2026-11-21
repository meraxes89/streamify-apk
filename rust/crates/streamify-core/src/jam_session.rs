//! Jam session handle and CRDT sync outcome records (UFFI-03/04).
//!
//! The handle is data, not behavior: the session object stays inside
//! the engine behind the typed contract, so the Kotlin side can never
//! hold a stale raw pointer into CRDT interior state. `state_hash` in
//! [`JamSyncResult`] is the deterministic BLAKE3 image of the
//! session's canonical CRDT fold — the diff currency for Merkle
//! reconciliation on the Kotlin side.

/// Opaque handle to the engine's active Jam session (UFFI-03/04).
///
/// The handle is data, not behavior: the session (and its CRDT replica)
/// stays inside the engine behind the typed contract, so the foreign
/// side can never hold a stale reference into CRDT interior state. Two
/// handles compare equal only when they describe the same session
/// mint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JamSessionHandle {
    /// Strictly monotonic per-process id (the `jam_crdt` op-id mint).
    pub session_id: u64,
    /// Room display name, trimmed and non-blank.
    pub session_name: String,
    /// Wall-clock creation time, unix milliseconds.
    pub created_at_unix_ms: u64,
}

/// Outcome of atomically applying one Jam CRDT delta batch (UFFI-03/04).
///
/// `ops_applied` counts rows that passed wire validation and merged
/// (idempotent re-merges of already-known ops count as applied — the
/// CRDT absorbs them without state change). `ops_rejected` counts rows
/// refused at the boundary: corrupt checksums, non-zero pads, NaN
/// fractional indices, unknown op types, and Add replays suppressed by
/// a tombstone (the B2 lineage contract — a deliberate no-op, not an
/// error). `state_hash` is the canonical fold hash AFTER the batch;
/// `needs_rebalance` latches when adjacent queue fractions fall inside
/// relative ULP range (the room should schedule a rebalance pass).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JamSyncResult {
    pub ops_applied: u32,
    pub ops_rejected: u32,
    pub state_hash: String,
    pub needs_rebalance: bool,
}

/// Hard bounds on the Jam surface inputs. The name cap keeps a hostile
/// room title from crossing the FFI unbounded; the delta cap bounds the
/// write-lock hold time of one atomic batch (≈ 21 845 ops at 48 bytes
/// each — far above any legitimate Merkle branch exchange).
pub const MAX_JAM_SESSION_NAME_BYTES: usize = 128;
pub const MAX_JAM_DELTA_BYTES: usize = 1 << 20;

