use hmac::{Hmac, Mac, KeyInit};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone)]
pub struct AcousticProofPayload {
    pub track_id: String,
    pub subband_energies: [f32; 16],
    pub duration_sec: u32,
    pub nonce: u64,
}

#[derive(Debug, Clone)]
pub struct MeshCandidateSubmission {
    pub node_id: String,
    pub lufs: f32,
    pub camelot_key: String,
    pub vector: [f32; 128],
    pub proof_digest: [u8; 32],
}

pub struct ByzantineConsensusEngine;

impl ByzantineConsensusEngine {
    /// Generates HMAC-SHA256 Proof-of-Acoustic-Compute digest
    pub fn generate_proof(payload: &AcousticProofPayload, secret_key: &[u8]) -> [u8; 32] {
        let mut mac =
            HmacSha256::new_from_slice(secret_key).expect("HMAC can take key of any size");

        mac.update(payload.track_id.as_bytes());
        for energy in &payload.subband_energies {
            let quantized = (*energy * 1000.0) as i32;
            mac.update(&quantized.to_le_bytes());
        }
        mac.update(&payload.duration_sec.to_le_bytes());
        mac.update(&payload.nonce.to_le_bytes());

        let result = mac.finalize();
        let mut out = [0u8; 32];
        out.copy_from_slice(&result.into_bytes()[..32]);
        out
    }

    /// Verifies 2-Peer Byzantine Consensus Threshold:
    /// 1. Anti-collusion (distinct node IDs)
    /// 2. |ΔLUFS| <= 0.3
    /// 3. Matching Camelot Key
    /// 4. Cosine Similarity >= 0.94
    pub fn verify_peer_consensus(
        peer1: &MeshCandidateSubmission,
        peer2: &MeshCandidateSubmission,
    ) -> bool {
        // Rule 1: Anti-collusion (different submitting edge nodes)
        if peer1.node_id == peer2.node_id {
            return false;
        }

        // Rule 2: Integrated Loudness Variance Tolerance (|ΔLUFS| <= 0.3)
        if (peer1.lufs - peer2.lufs).abs() > 0.3 {
            return false;
        }

        // Rule 3: Exact Harmonic Key Match
        if peer1.camelot_key != peer2.camelot_key {
            return false;
        }

        // Rule 4: High-Dimensional Acoustic Embedding Similarity (Cosine Sim >= 0.94)
        let sim = compute_cosine_similarity_128(&peer1.vector, &peer2.vector);
        sim >= 0.94
    }
}

pub fn compute_cosine_similarity_128(a: &[f32; 128], b: &[f32; 128]) -> f32 {
    let mut dot = 0.0f32;
    let mut norm_a = 0.0f32;
    let mut norm_b = 0.0f32;

    for i in 0..128 {
        dot += a[i] * b[i];
        norm_a += a[i] * a[i];
        norm_b += b[i] * b[i];
    }

    let denom = (norm_a.sqrt() * norm_b.sqrt()).max(1e-6);
    dot / denom
}

// Backward-compatible ConsensusEngine helper
pub struct ConsensusEngine;

impl ConsensusEngine {
    pub fn generate_proof_of_compute(pcm_slice: &[f32], nonce: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(nonce.as_bytes());

        for sample in pcm_slice {
            hasher.update(sample.to_le_bytes());
        }

        let result = hasher.finalize();
        hex::encode(result)
    }

    pub fn verify_byzantine_consensus(
        lufs_a: f32,
        lufs_b: f32,
        key_a: &str,
        key_b: &str,
        vec_a: &[f32],
        vec_b: &[f32],
    ) -> bool {
        if (lufs_a - lufs_b).abs() > 0.35 {
            return false;
        }

        if !key_a.is_empty() && !key_b.is_empty() && key_a != key_b {
            return false;
        }

        if !vec_a.is_empty() && vec_a.len() == vec_b.len() {
            let sim = Self::cosine_similarity(vec_a, vec_b);
            if sim < 0.94 {
                return false;
            }
        }

        true
    }

    pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
        let mut dot = 0.0f32;
        let mut norm_a = 0.0f32;
        let mut norm_b = 0.0f32;

        for (x, y) in a.iter().zip(b.iter()) {
            dot += x * y;
            norm_a += x * x;
            norm_b += y * y;
        }

        let denom = (norm_a.sqrt() * norm_b.sqrt()).max(1e-9);
        (dot / denom).clamp(-1.0, 1.0)
    }

    pub fn verify_lyric_drift_consensus(drift_a_ms: i32, drift_b_ms: i32) -> bool {
        (drift_a_ms - drift_b_ms).abs() <= 15
    }
}

mod hex {
    pub fn encode(bytes: impl AsRef<[u8]>) -> String {
        bytes
            .as_ref()
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect()
    }
}

// ═══════════════════════════════════════════════════════════════════════
// PHASE 2 (feat/phase2-rust-crdt-blend-voting) — Collaborative Playlist
// CRDT Operational Transform (directive C / BEHIND.md gap #35).
//
// Conflict-free multi-peer real-time playlist editing (Add / Remove /
// Reorder / Rename) over fractional-index LWW-Element-Set registers:
//   • item identity: u64 = (author_id << 32) | local_counter — author-
//     scoped, collision-free, minted by the ADD op;
//   • per-item liveness and fractional position are INDEPENDENT LWW
//     registers keyed by (lamport_ts, author_id) — remove-then-re-add
//     resurrects, concurrent reorders converge, merge order never matters;
//   • playlist title: one LWW register (Rename, Admin-gated);
//   • role-based edit gating: the operation header carries the author's
//     claimed role (Admin=3 / Editor=2 / Viewer=1); every mutation is
//     verified against its kind BEFORE applying (Viewer edits and
//     Editor Renames are rejected). A strict mode additionally cross-
//     checks the claim against the local roster (forgery detection at
//     ingress boundaries; permissive mode keeps pure replica merging
//     byte-convergent);
//   • delta sync: every op carries (author_id, per-author strictly
//     monotonic op_seq) — a vector clock. Peers exchange ONLY the op-log
//     deltas since the last synchronized clock, plus a lamport-watermark
//     fast path that fits the frozen jlong JNI signature.
//
// Wire discipline (house rules): little-endian, strict bounds checking,
// FNV-1a checksum, zero panics on hostile frames.
// ═══════════════════════════════════════════════════════════════════════

use std::collections::HashMap;

/// FNV-1a/32 parameters (matches jam_crdt / p2p_mesh house checksum).
const FNV1A_32_OFFSET: u32 = 0x811c_9dc5;
const FNV1A_32_PRIME: u32 = 0x0100_0193;

/// Fixed wire header of a [`PlaylistOp`]; the Rename/SetRole text tail
/// follows it (≤ [`PLAYLIST_OP_MAX_TEXT_LEN`] bytes).
pub const PLAYLIST_OP_HEADER_LEN: usize = 64;
/// Longest UTF-8 text tail a single op may carry.
pub const PLAYLIST_OP_MAX_TEXT_LEN: usize = 96;
/// Op-log retention (delta export window); beyond it callers fall back to
/// a full snapshot sync.
pub const PLAYLIST_LOG_CAP: usize = 4096;

/// Mutation kinds of the collaborative playlist.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PlaylistOpKind {
    Add = 0,
    Remove = 1,
    Reorder = 2,
    Rename = 3,
    /// Grants/changes a member's role (Admin-only). `item_id` carries the
    /// target author id, `track_cad_id` the new role as u64.
    SetRole = 4,
}

impl PlaylistOpKind {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(PlaylistOpKind::Add),
            1 => Some(PlaylistOpKind::Remove),
            2 => Some(PlaylistOpKind::Reorder),
            3 => Some(PlaylistOpKind::Rename),
            4 => Some(PlaylistOpKind::SetRole),
            _ => None,
        }
    }

    /// Minimum role required to issue this mutation.
    pub fn required_role(self) -> PlaylistRole {
        match self {
            PlaylistOpKind::Add | PlaylistOpKind::Remove | PlaylistOpKind::Reorder => {
                PlaylistRole::Editor
            }
            PlaylistOpKind::Rename | PlaylistOpKind::SetRole => PlaylistRole::Admin,
        }
    }
}

/// Collaborative roles (permission ladder).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u8)]
pub enum PlaylistRole {
    Viewer = 1,
    Editor = 2,
    Admin = 3,
}

impl PlaylistRole {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            1 => Some(PlaylistRole::Viewer),
            2 => Some(PlaylistRole::Editor),
            3 => Some(PlaylistRole::Admin),
            _ => None,
        }
    }
}

/// Why a playlist op was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaylistReject {
    /// Frame too short / too long / bad text length / nonzero reserved.
    Malformed,
    /// Checksum mismatch.
    Corrupt,
    /// Unknown op kind or role byte, non-finite fraction, zero author.
    Invalid,
    /// Claimed role in the header is below the kind's requirement
    /// (e.g. Viewer Add, Editor Rename) — the directive's permission gate.
    PermissionDenied,
    /// Strict mode: header role does not match the local roster entry.
    ForgedRole,
    /// Strict mode: unknown author (roster miss) on an Admin-grade op.
    UnknownAuthor,
}

/// Result of applying one playlist op.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaylistApplyResult {
    /// Merged into the state (idempotent replays included).
    Applied,
    /// Rejected at the boundary — not merged, not logged, not relayed.
    Rejected(PlaylistReject),
}

/// One collaborative playlist operation — 64-byte LE header + text tail:
///
/// ```text
/// [0]      op_kind u8
/// [1]      claimed_role u8
/// [2..6)   author_id u32
/// [6..14)  op_seq u64          per-author strictly monotonic
/// [14..22) lamport_ts u64      LWW timestamp
/// [22..30) item_id u64         Add mints; others reference
/// [30..38) track_cad_id u64    (SetRole: the new role)
/// [38..46) frac_bits u64       (Add / Reorder)
/// [46..48) text_len u16        0..=96
/// [48..52) checksum u32        FNV-1a over [0..48)
/// [52..64) reserved [u8;12]    must be zero
/// [64..64+text_len) UTF-8 text (Rename title)
/// ```
#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistOp {
    pub kind: PlaylistOpKind,
    pub claimed_role: PlaylistRole,
    pub author_id: u32,
    pub op_seq: u64,
    pub lamport: u64,
    pub item_id: u64,
    pub track_cad_id: u64,
    pub frac: f64,
    pub text: String,
    checksum: u32,
}

impl PlaylistOp {
    /// Truncates `text` to at most [`PLAYLIST_OP_MAX_TEXT_LEN`] **bytes**
    /// on a UTF-8 char boundary (audit #22 item 8).
    ///
    /// The old `chars().take(96)` sealed ops with up to 96 *chars* of
    /// multibyte text (96 emoji = 384 bytes) whose `text_len` header
    /// field then exceeded [`PLAYLIST_OP_MAX_TEXT_LEN`] — a self-created
    /// op rejected by its own [`PlaylistOp::from_bytes`]. The wire
    /// contract is BYTES; this makes the constructor agree with it.
    /// Backing off at most 3 bytes (UTF-8 chars are ≤ 4 bytes long)
    /// lands on the boundary; an all-padding edge simply yields a
    /// shorter tail, which the exact-length parser accepts.
    fn truncate_text(text: &str) -> String {
        if text.len() <= PLAYLIST_OP_MAX_TEXT_LEN {
            return text.to_string();
        }
        let mut end = PLAYLIST_OP_MAX_TEXT_LEN;
        while end > 0 && !text.is_char_boundary(end) {
            end -= 1;
        }
        text[..end].to_string()
    }

    /// Seals an op (checksum computed over the exact header span).
    #[allow(clippy::too_many_arguments)] // one arg per op-header field
    pub fn new(
        kind: PlaylistOpKind,
        claimed_role: PlaylistRole,
        author_id: u32,
        op_seq: u64,
        lamport: u64,
        item_id: u64,
        track_cad_id: u64,
        frac: f64,
        text: &str,
    ) -> Self {
        let mut op = PlaylistOp {
            kind,
            claimed_role,
            author_id,
            op_seq,
            lamport,
            item_id,
            track_cad_id,
            frac,
            text: Self::truncate_text(text),
            checksum: 0,
        };
        op.checksum = op.compute_checksum();
        op
    }

    fn compute_checksum(&self) -> u32 {
        let header = self.header_bytes();
        // FNV-1a over [0..48): every header field except the checksum
        // itself (the reserved span [52..64) is zero-checked at parse —
        // the jam_crdt house discipline).
        let mut hash = FNV1A_32_OFFSET;
        for b in header[..48].iter() {
            hash ^= *b as u32;
            hash = hash.wrapping_mul(FNV1A_32_PRIME);
        }
        hash
    }

    fn header_bytes(&self) -> [u8; PLAYLIST_OP_HEADER_LEN] {
        let mut b = [0u8; PLAYLIST_OP_HEADER_LEN];
        b[0] = self.kind as u8;
        b[1] = self.claimed_role as u8;
        b[2..6].copy_from_slice(&self.author_id.to_le_bytes());
        b[6..14].copy_from_slice(&self.op_seq.to_le_bytes());
        b[14..22].copy_from_slice(&self.lamport.to_le_bytes());
        b[22..30].copy_from_slice(&self.item_id.to_le_bytes());
        b[30..38].copy_from_slice(&self.track_cad_id.to_le_bytes());
        b[38..46].copy_from_slice(&self.frac.to_bits().to_le_bytes());
        b[46..48].copy_from_slice(&(self.text.len() as u16).to_le_bytes());
        b[48..52].copy_from_slice(&self.checksum.to_le_bytes());
        // [52..64) reserved zero
        b
    }

    /// Wire form: header + text tail. Little-endian everywhere.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut v = self.header_bytes().to_vec();
        v.extend_from_slice(self.text.as_bytes());
        v
    }

    /// Bounds-checked inverse of [`PlaylistOp::to_bytes`] — malformed or
    /// hostile frames return `None`, never panic.
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < PLAYLIST_OP_HEADER_LEN {
            return None;
        }
        let header: [u8; PLAYLIST_OP_HEADER_LEN] =
            bytes[..PLAYLIST_OP_HEADER_LEN].try_into().ok()?;
        if header[52..64].iter().any(|&x| x != 0) {
            return None; // reserved span must be zero
        }
        let text_len = u16::from_le_bytes([header[46], header[47]]) as usize;
        if text_len > PLAYLIST_OP_MAX_TEXT_LEN {
            return None;
        }
        if bytes.len() != PLAYLIST_OP_HEADER_LEN + text_len {
            return None; // exact-length contract, no trailing junk
        }
        let kind = PlaylistOpKind::from_u8(header[0])?;
        let claimed_role = PlaylistRole::from_u8(header[1])?;
        let author_id = u32::from_le_bytes([header[2], header[3], header[4], header[5]]);
        let op_seq = u64::from_le_bytes(header[6..14].try_into().ok()?);
        let lamport = u64::from_le_bytes(header[14..22].try_into().ok()?);
        let item_id = u64::from_le_bytes(header[22..30].try_into().ok()?);
        let track_cad_id = u64::from_le_bytes(header[30..38].try_into().ok()?);
        let frac = f64::from_bits(u64::from_le_bytes(header[38..46].try_into().ok()?));
        if !frac.is_finite() {
            return None;
        }
        let checksum = u32::from_le_bytes(header[48..52].try_into().ok()?);
        let text = String::from_utf8(bytes[PLAYLIST_OP_HEADER_LEN..].to_vec()).ok()?;

        let mut op = PlaylistOp {
            kind,
            claimed_role,
            author_id,
            op_seq,
            lamport,
            item_id,
            track_cad_id,
            frac,
            text,
            checksum: 0,
        };
        if op.compute_checksum() != checksum {
            return None;
        }
        op.checksum = checksum;
        Some(op)
    }
}

/// Live view of one playlist row (UI binding / snapshot sync).
#[derive(Debug, Clone, PartialEq)]
pub struct PlaylistItemView {
    pub item_id: u64,
    pub cad_id: u64,
    pub frac: f64,
}

/// LWW element register: (value presence, timestamp).
#[derive(Debug, Clone, Copy)]
struct ItemCore {
    cad_id: u64,
    alive: bool,
    /// (lamport, author) of the deciding Add/Remove.
    alive_ts: (u64, u32),
    frac: f64,
    /// (lamport, author) of the deciding Reorder/Add.
    frac_ts: (u64, u32),
}

/// Conflict-free collaborative playlist replica.
pub struct CollabPlaylistState {
    /// This replica's author identity (≥ 1; 0 is invalid).
    author_id: u32,
    /// Local Lamport clock (max observed + 1 when emitting).
    lamport: u64,
    /// Local per-author op sequence (vector clock component).
    local_seq: u64,
    /// Item id minting counter (low 32 bits of item_id).
    item_counter: u32,
    /// LWW-Element-Set payload.
    items: HashMap<u64, ItemCore>,
    /// LWW title register: (title, lamport, author).
    title: (String, u64, u32),
    /// Op log (bounded) — the delta export window.
    log: Vec<PlaylistOp>,
    /// Log membership index — (author, op_seq) keys already journaled
    /// (re-delivered frames never duplicate rows).
    logged: std::collections::HashSet<(u32, u64)>,
    /// Role roster (local trust state; LWW-merged via SetRole ops).
    roster: HashMap<u32, PlaylistRole>,
    /// LWW watermark per roster entry — the (lamport, author) of
    /// the last merged SetRole op or local trust grant (audit #22
    /// item 7: the roster used to merge last-ARRIVAL-wins, which
    /// diverged replicas observing the same op set in different
    /// orders; every other register was already keyed by the
    /// (lamport, author) total order).
    roster_ts: HashMap<u32, (u64, u32)>,
    /// Vector clock: highest op_seq applied per author.
    clock: HashMap<u32, u64>,
    /// Strict mode: cross-check header role claims against `roster`
    /// (ingress boundaries). Permissive (default) keeps pure merges
    /// convergent regardless of local roster drift.
    strict_roster: bool,
    /// Rejection counters (telemetry / tests).
    pub rejects: [u64; 7],
}

impl CollabPlaylistState {
    /// Creates the replica. The creating author is seeded Admin.
    pub fn new(author_id: u32) -> Self {
        assert!(author_id >= 1, "author id 0 is reserved/invalid");
        let mut roster = HashMap::new();
        roster.insert(author_id, PlaylistRole::Admin);
        CollabPlaylistState {
            author_id,
            lamport: 1,
            local_seq: 0,
            item_counter: 0,
            items: HashMap::new(),
            title: ("Collaborative Playlist".to_string(), 0, 0),
            log: Vec::new(),
            logged: std::collections::HashSet::new(),
            roster,
            roster_ts: HashMap::new(),
            clock: HashMap::new(),
            strict_roster: false,
            rejects: [0; 7],
        }
    }

    /// Toggles strict roster enforcement (ingress-boundary mode).
    pub fn set_strict_roster(&mut self, strict: bool) {
        self.strict_roster = strict;
    }

    /// Adopts a per-device author id (audit #22 P0-1 fix).
    ///
    /// The JNI singleton used to mint every op as author 1 on every
    /// device, so two devices' `(author<<32)|counter` item identities
    /// could collide across replicas — voiding the author-scoped
    /// collision-free guarantee this CRDT's design relies on. The bridge
    /// now derives the author from the mesh `PeerId` (Blake3 of the
    /// device string — stable per device) and calls this at mesh start.
    ///
    /// Convergence-safety of the swap: item identity is
    /// `(author, counter)`; old and new namespaces are disjoint, so
    /// historical items/tombstones keep their minted ids and merge
    /// exactly as before. The local `item_counter` and `local_seq`
    /// CONTINUE across the swap (still strictly monotonic — receivers'
    /// per-author delta-sync clocks have never seen the new author, so
    /// every op under it arrives as a fresh, monotonic stream).
    ///
    /// The previous author's locally-held role carries across (normally
    /// the creator's Admin from [`CollabPlaylistState::new`], or the
    /// host-granted role after a delta sync): a bare swap would silently
    /// demote this replica to the unknown-author default (Editor) and
    /// break host-side SetRole mints. An existing roster entry for the
    /// new author (host grant / prior stint under the same stable id)
    /// wins over the carried role.
    pub fn adopt_author(&mut self, new_author: u32) {
        assert!(new_author >= 1, "author id 0 is reserved/invalid");
        if new_author == self.author_id {
            return;
        }
        let carried_role = self.role_of(self.author_id);
        self.roster.entry(new_author).or_insert(carried_role);
        self.author_id = new_author;
    }

    /// Current local role of an author (strict-mode lookups).
    pub fn role_of(&self, author: u32) -> PlaylistRole {
        self.roster
            .get(&author)
            .copied()
            .unwrap_or(PlaylistRole::Editor)
    }

    /// Local (host-side) roster mutation — the trust root besides Admin
    /// SetRole ops. Stamped at the current lamport watermark under the
    /// reserved pseudo-author `u32::MAX`, so concurrent same-lamport
    /// remote ops lose and only strictly-later ops override (audit
    /// #22 item 7 LWW discipline).
    pub fn set_role_local(&mut self, author: u32, role: PlaylistRole) {
        self.roster.insert(author, role);
        self.roster_ts.insert(author, (self.lamport, u32::MAX));
    }

    pub fn title(&self) -> &str {
        &self.title.0
    }

    pub fn lamport_watermark(&self) -> u64 {
        self.lamport
    }

    pub fn clock(&self) -> &HashMap<u32, u64> {
        &self.clock
    }

    /// Live rows in fractional order (snapshot sync / UI binding).
    pub fn snapshot_items(&self) -> Vec<PlaylistItemView> {
        let mut rows: Vec<PlaylistItemView> = self
            .items
            .iter()
            .filter(|(_, it)| it.alive)
            .map(|(id, it)| PlaylistItemView {
                item_id: *id,
                cad_id: it.cad_id,
                frac: it.frac,
            })
            .collect();
        rows.sort_by(|a, b| {
            a.frac
                .partial_cmp(&b.frac)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(a.item_id.cmp(&b.item_id))
        });
        rows
    }

    // ── op construction (origin side) ─────────────────────────────────

    fn next_seq(&mut self) -> u64 {
        self.local_seq += 1;
        self.local_seq
    }

    fn next_lamport(&mut self) -> u64 {
        self.lamport += 1;
        self.lamport
    }

    fn mint_item_id(&mut self) -> u64 {
        self.item_counter = self.item_counter.wrapping_add(1).max(1);
        ((self.author_id as u64) << 32) | (self.item_counter as u64)
    }

    fn my_role(&self) -> PlaylistRole {
        self.role_of(self.author_id)
    }

    /// Builds (and locally applies) an Add op. `None` when the local role
    /// is below Editor.
    pub fn build_add(&mut self, cad_id: u64, frac: f64) -> Option<PlaylistOp> {
        if self.my_role() < PlaylistRole::Editor || !frac.is_finite() {
            return None;
        }
        let item_id = self.mint_item_id();
        let op = PlaylistOp::new(
            PlaylistOpKind::Add,
            self.my_role(),
            self.author_id,
            self.next_seq(),
            self.next_lamport(),
            item_id,
            cad_id,
            frac,
            "",
        );
        self.apply_op(&op);
        Some(op)
    }

    /// Builds (and locally applies) a Remove op.
    pub fn build_remove(&mut self, item_id: u64) -> Option<PlaylistOp> {
        if self.my_role() < PlaylistRole::Editor {
            return None;
        }
        let op = PlaylistOp::new(
            PlaylistOpKind::Remove,
            self.my_role(),
            self.author_id,
            self.next_seq(),
            self.next_lamport(),
            item_id,
            0,
            0.0,
            "",
        );
        self.apply_op(&op);
        Some(op)
    }

    /// Builds (and locally applies) a Reorder op (fractional reposition).
    pub fn build_reorder(&mut self, item_id: u64, frac: f64) -> Option<PlaylistOp> {
        if self.my_role() < PlaylistRole::Editor || !frac.is_finite() {
            return None;
        }
        let op = PlaylistOp::new(
            PlaylistOpKind::Reorder,
            self.my_role(),
            self.author_id,
            self.next_seq(),
            self.next_lamport(),
            item_id,
            0,
            frac,
            "",
        );
        self.apply_op(&op);
        Some(op)
    }

    /// Builds (and locally applies) a Rename op (Admin only).
    pub fn build_rename(&mut self, title: &str) -> Option<PlaylistOp> {
        if self.my_role() < PlaylistRole::Admin {
            return None;
        }
        let op = PlaylistOp::new(
            PlaylistOpKind::Rename,
            self.my_role(),
            self.author_id,
            self.next_seq(),
            self.next_lamport(),
            0,
            0,
            0.0,
            title,
        );
        self.apply_op(&op);
        Some(op)
    }

    /// Builds (and locally applies) a SetRole op (Admin only). The roster
    /// merges LWW so every replica converges on the granted role.
    pub fn build_set_role(&mut self, target_author: u32, role: PlaylistRole) -> Option<PlaylistOp> {
        if self.my_role() < PlaylistRole::Admin {
            return None;
        }
        let op = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            self.my_role(),
            self.author_id,
            self.next_seq(),
            self.next_lamport(),
            target_author as u64,
            role as u64,
            0.0,
            "",
        );
        self.apply_op(&op);
        Some(op)
    }

    // ── merge (replica side) ───────────────────────────────────────────

    /// Merges one op. Convergence contract:
    ///   • the per-author vector clock ADVANCES monotonically but NEVER
    ///     gates the merge — an out-of-order frame with a lower op_seq
    ///     than one already seen is still merged (LWW registers make it
    ///     inert; short-circuiting on the clock would silently drop
    ///     unseen holes and diverge replicas);
    ///   • every register is LWW keyed by the pair (lamport, author) — one
    ///     author never emits two ops with the same lamport, so pair
    ///     equality means a replay and pair order is total: merge results
    ///     are independent of arrival order;
    ///   • exact (lamport, author) ties between an Add and a Remove (only
    ///     possible cross-author) resolve ADD-WINS, matching classic
    ///     LWW-Element-Set semantics.
    /// Permission gate FIRST — rejected ops never touch state, log, or
    /// clock.
    pub fn apply_op(&mut self, op: &PlaylistOp) -> PlaylistApplyResult {
        // 1. Header-vs-kind permission gate (content-only, convergent).
        if op.claimed_role < op.kind.required_role() {
            self.rejects[PlaylistReject::PermissionDenied as usize] += 1;
            return PlaylistApplyResult::Rejected(PlaylistReject::PermissionDenied);
        }

        // 2. Strict roster cross-check (ingress mode).
        if self.strict_roster {
            match self.roster.get(&op.author_id) {
                Some(&role) if role == op.claimed_role => {}
                Some(_) => {
                    self.rejects[PlaylistReject::ForgedRole as usize] += 1;
                    return PlaylistApplyResult::Rejected(PlaylistReject::ForgedRole);
                }
                None => {
                    if op.kind.required_role() == PlaylistRole::Admin {
                        self.rejects[PlaylistReject::UnknownAuthor as usize] += 1;
                        return PlaylistApplyResult::Rejected(PlaylistReject::UnknownAuthor);
                    }
                    // Unknown editor-level authors are trusted permissively;
                    // the boundary layer owns authentication.
                }
            }
        }

        // 3. Clocks advance (never gate the merge — see contract above).
        let e = self.clock.entry(op.author_id).or_insert(0);
        if op.op_seq > *e {
            *e = op.op_seq;
        }
        self.lamport = self.lamport.max(op.lamport);

        // 4. LWW merges per register.
        let ts = (op.lamport, op.author_id);
        match op.kind {
            PlaylistOpKind::Add | PlaylistOpKind::Remove => {
                let alive = op.kind == PlaylistOpKind::Add;
                let entry = self.items.entry(op.item_id).or_insert(ItemCore {
                    cad_id: op.track_cad_id,
                    alive: false,
                    alive_ts: (0, 0),
                    frac: op.frac,
                    frac_ts: (0, 0),
                });
                if ts > entry.alive_ts || (ts == entry.alive_ts && alive) {
                    entry.alive = alive;
                    entry.alive_ts = ts;
                    if alive {
                        entry.cad_id = op.track_cad_id;
                        if op.frac != 0.0 && ts > entry.frac_ts {
                            entry.frac = op.frac;
                            entry.frac_ts = ts;
                        }
                    }
                }
            }
            PlaylistOpKind::Reorder => {
                // A reorder whose Add has not arrived yet (out-of-order
                // delivery) creates a dead entry carrying the fractional
                // position — the same causality-tolerant discipline as the
                // Remove branch. Without this, a reorder arriving before
                // its element's add would be silently LOST and replicas
                // applying the same op set in different orders would
                // diverge on the item's position (found by the randomized
                // split-brain chaos test).
                let entry = self.items.entry(op.item_id).or_insert(ItemCore {
                    cad_id: 0,
                    alive: false,
                    alive_ts: (0, 0),
                    frac: op.frac,
                    frac_ts: (0, 0),
                });
                if ts > entry.frac_ts {
                    entry.frac = op.frac;
                    entry.frac_ts = ts;
                }
            }
            PlaylistOpKind::Rename => {
                if (op.lamport, op.author_id) >= (self.title.1, self.title.2) {
                    self.title = (op.text.clone(), op.lamport, op.author_id);
                }
            }
            PlaylistOpKind::SetRole => {
                if let Some(role) = PlaylistRole::from_u8(op.track_cad_id as u8) {
                    // LWW roster merge keyed by (lamport, author) — the
                    // same total-order register key as every other
                    // register. Ties are same-op replays (idempotent);
                    // arrival order no longer decides (audit #22 item 7).
                    let target = op.item_id as u32;
                    let ts = (op.lamport, op.author_id);
                    if ts >= self.roster_ts.get(&target).copied().unwrap_or((0, 0)) {
                        self.roster.insert(target, role);
                        self.roster_ts.insert(target, ts);
                    }
                }
            }
        }

        // 5. Op-log append (bounded, deduped by (author, op_seq)).
        if self.logged.insert((op.author_id, op.op_seq)) {
            self.log.push(op.clone());
            if self.log.len() > PLAYLIST_LOG_CAP {
                let drop = self.log.len() - PLAYLIST_LOG_CAP;
                self.log.drain(..drop);
            }
        }
        PlaylistApplyResult::Applied
    }

    /// Parses + merges a wire op. The JNI `applyPlaylistOp` surface.
    pub fn apply_bytes(&mut self, bytes: &[u8]) -> PlaylistApplyResult {
        let Some(op) = PlaylistOp::from_bytes(bytes) else {
            self.rejects[PlaylistReject::Corrupt as usize] += 1;
            return PlaylistApplyResult::Rejected(PlaylistReject::Corrupt);
        };
        if op.author_id == 0 {
            self.rejects[PlaylistReject::Invalid as usize] += 1;
            return PlaylistApplyResult::Rejected(PlaylistReject::Invalid);
        }
        self.apply_op(&op)
    }

    // ── delta sync (directive C) ───────────────────────────────────────

    /// EXACT delta: every logged op whose per-author sequence is beyond
    /// the caller's vector clock. Ops are emitted in (lamport, author,
    /// seq) order so ingestion is deterministic.
    pub fn export_delta_since_clock(&self, since: &HashMap<u32, u64>) -> Vec<PlaylistOp> {
        let mut ops: Vec<PlaylistOp> = self
            .log
            .iter()
            .filter(|op| op.op_seq > since.get(&op.author_id).copied().unwrap_or(0))
            .cloned()
            .collect();
        ops.sort_by(|a, b| {
            a.lamport
                .cmp(&b.lamport)
                .then(a.author_id.cmp(&b.author_id))
                .then(a.op_seq.cmp(&b.op_seq))
        });
        ops
    }

    /// Lamport-watermark fast path (the frozen jlong JNI signature): every
    /// logged op with `lamport > watermark`. Over-delivery is harmless
    /// (merges are idempotent); the exact path is
    /// [`Self::export_delta_since_clock`]. Ops that share a lamport with
    /// the watermark are INCLUDED (safe over-inclusion) because a caller
    /// at watermark L may still lack same-L ops from slower authors.
    pub fn export_delta_since_watermark(&self, watermark: u64) -> Vec<PlaylistOp> {
        let mut ops: Vec<PlaylistOp> = self
            .log
            .iter()
            .filter(|op| op.lamport >= watermark)
            .cloned()
            .collect();
        ops.sort_by(|a, b| {
            a.lamport
                .cmp(&b.lamport)
                .then(a.author_id.cmp(&b.author_id))
                .then(a.op_seq.cmp(&b.op_seq))
        });
        ops
    }

    /// Wire form of a delta batch: concatenated op frames, each preceded by
    /// a u16 length prefix (LE) so a stream of frames parses losslessly.
    pub fn encode_delta(ops: &[PlaylistOp]) -> Vec<u8> {
        let mut out = Vec::with_capacity(ops.len() * (PLAYLIST_OP_HEADER_LEN + 8));
        for op in ops {
            let frame = op.to_bytes();
            out.extend_from_slice(&(frame.len() as u16).to_le_bytes());
            out.extend_from_slice(&frame);
        }
        out
    }

    /// Inverse of [`Self::encode_delta`] — bounds-checked, never panics.
    pub fn decode_delta(bytes: &[u8]) -> Option<Vec<PlaylistOp>> {
        let mut ops = Vec::new();
        let mut i = 0usize;
        while i < bytes.len() {
            if i + 2 > bytes.len() {
                return None;
            }
            let len = u16::from_le_bytes([bytes[i], bytes[i + 1]]) as usize;
            i += 2;
            if i + len > bytes.len() {
                return None;
            }
            let op = PlaylistOp::from_bytes(&bytes[i..i + len])?;
            i += len;
            ops.push(op);
        }
        Some(ops)
    }

    /// The caller's own vector clock compacted to the Lamport watermark —
    /// the value the frozen `exportPlaylistDelta(since: jlong)` JNI feeds.
    pub fn my_watermark(&self) -> u64 {
        self.lamport
    }
}

#[cfg(test)]
mod collab_playlist_tests {
    use super::*;

    #[test]
    fn adopt_author_moves_namespace_and_carries_role() {
        // Audit #22 P0-1 regression lock. Device A (legacy author 1) has
        // already minted items when the mesh starts and binds its
        // per-device author 0xA11CE.
        let mut a = CollabPlaylistState::new(1);
        let add_a = a.build_add(111, 0.25).expect("pre-adoption add");
        assert_eq!(add_a.item_id >> 32, 1); // minted in the legacy namespace

        a.adopt_author(0x000A_11CE);
        // Historical item identity is untouched (namespaces disjoint).
        assert_eq!(add_a.item_id >> 32, 1);
        assert_eq!(a.role_of(0x000A_11CE), PlaylistRole::Admin); // carried
                                                                 // New mints land in the adopted namespace — distinct devices can
                                                                 // no longer collide on the same (author, counter) identity. The
                                                                 // per-replica counter CONTINUES across the swap (1 → 2): identity
                                                                 // stays unique because the AUTHOR half changed — the
                                                                 // disjoint-namespace property is what adopt_author relies on,
                                                                 // not a counter reset.
        let add_b = a.build_add(222, 0.5).expect("post-adoption add");
        assert_eq!(add_b.item_id >> 32, 0x000A_11CE);
        assert_eq!(add_b.item_id & 0xFFFF_FFFF, 2);

        // Idempotent re-adoption of the same author is a no-op.
        a.adopt_author(0x000A_11CE);

        // An existing roster entry for the new author (host grant) wins
        // over the carried role: a viewer stays a viewer.
        let mut b = CollabPlaylistState::new(2);
        b.set_role_local(0x0000_BEEF, PlaylistRole::Viewer);
        b.adopt_author(0x0000_BEEF);
        assert_eq!(b.role_of(0x0000_BEEF), PlaylistRole::Viewer);
        assert!(b.build_add(1, 0.5).is_none()); // viewer cannot add
    }

    #[test]
    #[should_panic(expected = "author id 0 is reserved")]
    fn adopt_author_rejects_reserved_zero() {
        let mut c = CollabPlaylistState::new(3);
        c.adopt_author(0);
    }

    #[test]
    fn multibyte_text_truncates_on_char_boundary_and_round_trips() {
        // Audit #22 item 8 regression lock: `chars().take(96)` used to
        // seal a 384-byte op for 96 emoji that its own `from_bytes`
        // rejected (text_len header > PLAYLIST_OP_MAX_TEXT_LEN bytes).
        let emoji = "🎨".repeat(96); // 384 bytes, 96 chars
        let op = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            7,
            5,
            44,
            0,
            0,
            0.5,
            &emoji,
        );
        // Sealed op now honors the byte budget...
        assert!(op.text.len() <= PLAYLIST_OP_MAX_TEXT_LEN);
        // ...landed on a char boundary (whole emoji only, none sliced)...
        assert_eq!(op.text.chars().count(), 24); // 96 bytes / 4B per emoji
        assert!(op.text.chars().all(|c| c == '🎨'));
        // ...and survives its own wire parser — the self-reject repro.
        let back = PlaylistOp::from_bytes(&op.to_bytes()).expect("multibyte round-trip");
        assert_eq!(back, op);

        // Boundary case: 94 ASCII + one 4-byte emoji = 98 bytes — the
        // emoji does not fit, truncation backs off to the 94 ASCII
        // prefix rather than slicing the emoji mid-sequence.
        let mixed = "a".repeat(94) + "🎨";
        let op2 = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            7,
            6,
            45,
            0,
            0,
            0.5,
            &mixed,
        );
        assert_eq!(op2.text, "a".repeat(94));
        assert_eq!(
            PlaylistOp::from_bytes(&op2.to_bytes()).unwrap(),
            op2,
            "backed-off truncation round-trips"
        );

        // Exact fit: 96 bytes of 3-byte CJK (32 chars) passes untouched.
        let cjk = "乐".repeat(32); // 3 bytes each = 96 bytes exactly
        let op3 = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            7,
            7,
            46,
            0,
            0,
            0.5,
            &cjk,
        );
        assert_eq!(op3.text.len(), PLAYLIST_OP_MAX_TEXT_LEN);
        assert_eq!(op3.text.chars().count(), 32);
        assert_eq!(PlaylistOp::from_bytes(&op3.to_bytes()).unwrap(), op3);

        // Pure ASCII unaffected (the historical behavior).
        let ascii = "x".repeat(200);
        let op4 = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            7,
            8,
            47,
            0,
            0,
            0.5,
            &ascii,
        );
        assert_eq!(op4.text.len(), PLAYLIST_OP_MAX_TEXT_LEN);
        assert_eq!(PlaylistOp::from_bytes(&op4.to_bytes()).unwrap(), op4);
    }

    #[test]
    fn playlist_op_wire_round_trip_and_hostile_frames() {
        let op = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            7,
            3,
            42,
            0,
            0,
            0.5,
            "Roadtrip 2026 🚗",
        );
        let bytes = op.to_bytes();
        assert_eq!(
            bytes.len(),
            PLAYLIST_OP_HEADER_LEN + "Roadtrip 2026 🚗".len()
        );
        let back = PlaylistOp::from_bytes(&bytes).expect("round-trip");
        assert_eq!(back, op);

        // Truncation at every boundary is a clean None.
        for cut in 0..bytes.len() {
            assert!(
                PlaylistOp::from_bytes(&bytes[..cut]).is_none(),
                "cut at {cut}"
            );
        }
        // Flipped checksum bit.
        let mut corrupt = bytes.clone();
        corrupt[48] ^= 0x01;
        assert!(PlaylistOp::from_bytes(&corrupt).is_none());
        // Nonzero reserved byte.
        let mut reserved = bytes.clone();
        reserved[60] = 1;
        assert!(PlaylistOp::from_bytes(&reserved).is_none());
        // Trailing junk violates the exact-length contract.
        let mut junk = bytes.clone();
        junk.push(0);
        assert!(PlaylistOp::from_bytes(&junk).is_none());
        // Non-finite fraction.
        let nan_op = PlaylistOp::new(
            PlaylistOpKind::Add,
            PlaylistRole::Editor,
            7,
            4,
            43,
            99,
            1,
            f64::NAN,
            "",
        );
        assert!(PlaylistOp::from_bytes(&nan_op.to_bytes()).is_none());
        // Unknown kind/role bytes.
        let mut bad_kind = op.to_bytes();
        bad_kind[0] = 9;
        for b in bad_kind[48..52].iter_mut() {
            *b = 0;
        }
        let checksum = {
            let mut h = FNV1A_32_OFFSET;
            for b in bad_kind[..48].iter() {
                h ^= *b as u32;
                h = h.wrapping_mul(FNV1A_32_PRIME);
            }
            h
        };
        bad_kind[48..52].copy_from_slice(&checksum.to_le_bytes());
        assert!(PlaylistOp::from_bytes(&bad_kind).is_none());
    }

    #[test]
    fn playlist_lifecycle_and_permission_gates() {
        let mut host = CollabPlaylistState::new(1);
        assert_eq!(host.role_of(1), PlaylistRole::Admin);

        // Admin can do everything.
        let a1 = host.build_add(101, 0.25).expect("admin add");
        let a2 = host.build_add(102, 0.75).expect("admin add");
        let _ = host.build_rename("Weekend Blend").expect("admin rename");
        assert_eq!(host.title(), "Weekend Blend");
        assert_eq!(host.snapshot_items().len(), 2);
        assert_eq!(host.snapshot_items()[0].cad_id, 101);

        // Remove tombstones; a fresh Add (new item id) is independent.
        let _ = host.build_remove(a1.item_id).expect("admin remove");
        assert_eq!(host.snapshot_items().len(), 1);
        let a3 = host.build_add(103, 0.5).expect("re-add");
        assert_ne!(a3.item_id, a1.item_id);
        assert_eq!(host.snapshot_items().len(), 2);

        // Reorder moves the fractional position.
        let _ = host.build_reorder(a2.item_id, 0.1).expect("reorder");
        assert_eq!(host.snapshot_items()[0].cad_id, 102);

        // Viewer-gated ops: forge the header role directly.
        let viewer_add = PlaylistOp::new(
            PlaylistOpKind::Add,
            PlaylistRole::Viewer,
            9,
            1,
            100,
            (9u64 << 32) | 1,
            999,
            0.5,
            "",
        );
        assert_eq!(
            host.apply_op(&viewer_add),
            PlaylistApplyResult::Rejected(PlaylistReject::PermissionDenied)
        );
        // Editor attempting Rename (Admin-grade).
        let editor_rename = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Editor,
            9,
            1,
            101,
            0,
            0,
            0.0,
            "hostile takeover",
        );
        assert_eq!(
            host.apply_op(&editor_rename),
            PlaylistApplyResult::Rejected(PlaylistReject::PermissionDenied)
        );
        assert_eq!(host.title(), "Weekend Blend");
    }

    #[test]
    fn playlist_strict_mode_forgery_rejected() {
        let mut host = CollabPlaylistState::new(1);
        // Grant author 5 Editor via the Admin SetRole op.
        let grant = host.build_set_role(5, PlaylistRole::Editor).expect("grant");
        host.set_strict_roster(true);

        // Matching claim merges fine.
        let ok = PlaylistOp::new(
            PlaylistOpKind::Add,
            PlaylistRole::Editor,
            5,
            1,
            10,
            (5u64 << 32) | 1,
            55,
            0.5,
            "",
        );
        assert_eq!(host.apply_op(&ok), PlaylistApplyResult::Applied);

        // Forged Admin claim from author 5 (roster says Editor).
        let forged = PlaylistOp::new(
            PlaylistOpKind::Rename,
            PlaylistRole::Admin,
            5,
            2,
            11,
            0,
            0,
            0.0,
            "pwned",
        );
        assert_eq!(
            host.apply_op(&forged),
            PlaylistApplyResult::Rejected(PlaylistReject::ForgedRole)
        );

        // Unknown author pushing an Admin-grade op in strict mode.
        let stranger = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            77,
            1,
            12,
            6,
            PlaylistRole::Admin as u64,
            0.0,
            "",
        );
        assert_eq!(
            host.apply_op(&stranger),
            PlaylistApplyResult::Rejected(PlaylistReject::UnknownAuthor)
        );
        assert_eq!(host.title(), "Collaborative Playlist");
        // The grant op itself is in the log for replica roster sync.
        assert!(host
            .log
            .iter()
            .any(|op| op.kind == PlaylistOpKind::SetRole && op.item_id == 5));
        let _ = grant;
    }

    #[test]
    fn playlist_concurrent_edits_converge() {
        // Three authors edit concurrently on separate replicas; each ships
        // its op stream to the others; final states must be identical.
        let mut a = CollabPlaylistState::new(1);
        let mut b = CollabPlaylistState::new(2);
        let mut c = CollabPlaylistState::new(3);

        let a1 = a.build_add(11, 0.5).unwrap();
        let a2 = a.build_add(12, 0.6).unwrap();
        let b1 = b.build_add(21, 0.55).unwrap();
        let c1 = c.build_add(31, 0.65).unwrap();
        // Concurrent remove + reorder racing on the same item.
        let rem = a.build_remove(a1.item_id).unwrap();
        let reo = b.build_reorder(a1.item_id, 0.05).unwrap();
        let rn = a.build_rename("Converged Title").unwrap();

        // Ship everything to everyone in DIFFERENT orders.
        let streams: Vec<Vec<PlaylistOp>> = vec![
            vec![
                a1.clone(),
                a2.clone(),
                b1.clone(),
                c1.clone(),
                rem.clone(),
                reo.clone(),
                rn.clone(),
            ],
            vec![
                rn.clone(),
                c1.clone(),
                reo.clone(),
                rem.clone(),
                b1.clone(),
                a2.clone(),
                a1.clone(),
            ],
            vec![
                b1.clone(),
                reo.clone(),
                a1.clone(),
                c1.clone(),
                a2.clone(),
                rn.clone(),
                rem.clone(),
            ],
        ];
        let mut replicas = [a, b, c];
        for (rep, stream) in replicas.iter_mut().zip(streams.iter()) {
            for op in stream {
                assert_eq!(rep.apply_op(op), PlaylistApplyResult::Applied);
            }
        }
        let snapshots: Vec<_> = replicas.iter().map(|r| r.snapshot_items()).collect();
        assert_eq!(snapshots[0], snapshots[1]);
        assert_eq!(snapshots[1], snapshots[2]);
        assert_eq!(replicas[0].title(), replicas[1].title());
        assert_eq!(replicas[1].title(), replicas[2].title());
        assert_eq!(replicas[1].title(), "Converged Title");

        // The remove (lamport newer than the reorder) wins: a1 gone.
        let ids: Vec<u64> = snapshots[0].iter().map(|i| i.cad_id).collect();
        assert_eq!(ids, vec![21, 12, 31]);

        // Replaying the full streams again changes nothing (idempotence).
        for (rep, stream) in replicas.iter_mut().zip(streams.iter()) {
            for op in stream {
                assert_eq!(rep.apply_op(op), PlaylistApplyResult::Applied);
            }
        }
        assert_eq!(replicas[0].snapshot_items(), snapshots[0]);
    }

    #[test]
    fn playlist_delta_sync_vector_clock() {
        let mut a = CollabPlaylistState::new(1);
        let op1 = a.build_add(1, 0.1).unwrap();
        let op2 = a.build_add(2, 0.2).unwrap();
        let op3 = a.build_rename("Synced").unwrap();

        // Fresh replica B with an empty clock gets the FULL delta.
        let mut b = CollabPlaylistState::new(2);
        let full = a.export_delta_since_clock(&HashMap::new());
        assert_eq!(full.len(), 3);
        for op in &full {
            assert_eq!(b.apply_op(op), PlaylistApplyResult::Applied);
        }
        assert_eq!(b.snapshot_items().len(), 2);
        assert_eq!(b.title(), "Synced");

        // B edits; A pulls only B's ops it has not seen — filtering by A's
        // OWN vector clock excludes the three ops A authored and B echoed
        // back into its log, leaving exactly B's fresh op.
        let b_op = b.build_add(3, 0.3).unwrap();
        let delta_to_a = b.export_delta_since_clock(&a.clock().clone());
        assert_eq!(delta_to_a.len(), 1);
        assert_eq!(delta_to_a[0].op_seq, b_op.op_seq);
        assert_eq!(a.apply_op(&delta_to_a[0]), PlaylistApplyResult::Applied);
        assert_eq!(a.snapshot_items().len(), 3);

        // Incremental: A's own clock now covers its 3 ops → empty delta.
        let a_clock = a.clock().clone();
        assert!(a.export_delta_since_clock(&a_clock).is_empty());

        // Watermark fast path: ops with lamport >= watermark inclusive.
        let w = a.my_watermark();
        let fast = a.export_delta_since_watermark(w);
        assert!(fast.iter().all(|op| op.lamport >= w));
        // A peer at watermark 0 sees everything.
        assert_eq!(a.export_delta_since_watermark(0).len(), 4);

        // Delta codec round-trip.
        let batch = a.export_delta_since_watermark(0);
        let encoded = CollabPlaylistState::encode_delta(&batch);
        let decoded = CollabPlaylistState::decode_delta(&encoded).expect("decode");
        assert_eq!(decoded, batch);
        // Hostile truncation never panics: every cut either fails to decode
        // or decodes to a strict PREFIX of the batch (frame boundaries).
        for cut in 1..encoded.len() {
            match CollabPlaylistState::decode_delta(&encoded[..cut]) {
                None => {}
                Some(prefix) => {
                    assert!(prefix.len() < batch.len());
                    assert_eq!(&prefix[..], &batch[..prefix.len()]);
                }
            }
        }
        let _ = (op1, op2, op3);
    }

    #[test]
    fn playlist_remove_then_readd_resurrects() {
        let mut s = CollabPlaylistState::new(1);
        let add = s.build_add(77, 0.5).unwrap();
        let _ = s.build_remove(add.item_id).unwrap();
        assert_eq!(s.snapshot_items().len(), 0);

        // Same item id re-added with a NEWER lamport resurrects it.
        let resurrect = PlaylistOp::new(
            PlaylistOpKind::Add,
            PlaylistRole::Admin,
            1,
            99,
            10_000,
            add.item_id,
            77,
            0.9,
            "",
        );
        assert_eq!(s.apply_op(&resurrect), PlaylistApplyResult::Applied);
        assert_eq!(s.snapshot_items().len(), 1);
        assert_eq!(s.snapshot_items()[0].frac, 0.9);
    }

    #[test]
    fn set_role_lww_converges_regardless_of_arrival_order() {
        // Audit #22 item 7 regression lock: the roster used to merge
        // last-ARRIVAL-wins; replicas observing the same op set in
        // different orders diverged on the granted role.
        let grant_editor = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            1,
            1,
            10,
            5,
            PlaylistRole::Editor as u64,
            0.0,
            "",
        );
        let grant_viewer = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            1,
            2,
            20,
            5,
            PlaylistRole::Viewer as u64,
            0.0,
            "",
        );
        let late_admin_grant = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            9,
            1,
            25,
            5,
            PlaylistRole::Admin as u64,
            0.0,
            "",
        );

        let mut a = CollabPlaylistState::new(1);
        let mut b = CollabPlaylistState::new(2);
        for op in [&grant_editor, &grant_viewer, &late_admin_grant] {
            a.apply_op(op);
        }
        for op in [&late_admin_grant, &grant_viewer, &grant_editor] {
            b.apply_op(op);
        }
        // Highest (lamport, author) wins on BOTH replicas.
        assert_eq!(a.role_of(5), PlaylistRole::Admin);
        assert_eq!(b.role_of(5), PlaylistRole::Admin);

        // Stale re-delivery never regresses the register.
        a.apply_op(&grant_viewer);
        assert_eq!(a.role_of(5), PlaylistRole::Admin);

        // Same-lamport grants from different authors tie-break by author
        // id — order-independent on both replicas.
        let g7 = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            7,
            1,
            30,
            6,
            PlaylistRole::Viewer as u64,
            0.0,
            "",
        );
        let g8 = PlaylistOp::new(
            PlaylistOpKind::SetRole,
            PlaylistRole::Admin,
            8,
            1,
            30,
            6,
            PlaylistRole::Editor as u64,
            0.0,
            "",
        );
        let mut c = CollabPlaylistState::new(1);
        let mut d = CollabPlaylistState::new(1);
        c.apply_op(&g7);
        c.apply_op(&g8);
        d.apply_op(&g8);
        d.apply_op(&g7);
        assert_eq!(c.role_of(6), PlaylistRole::Editor);
        assert_eq!(d.role_of(6), PlaylistRole::Editor);

        // A local trust grant yields to strictly-later remote grants…
        let mut e = CollabPlaylistState::new(1);
        e.apply_op(&grant_editor);
        e.set_role_local(5, PlaylistRole::Admin);
        e.apply_op(&grant_viewer); // ts (20, 1) > stamp (10, MAX)
        assert_eq!(e.role_of(5), PlaylistRole::Viewer);
        // …and survives stale ones.
        let mut f = CollabPlaylistState::new(1);
        f.apply_op(&grant_viewer);
        f.set_role_local(5, PlaylistRole::Admin); // stamped (20, MAX)
        f.apply_op(&grant_editor); // ts (10, 1) < (20, MAX) — rejected
        assert_eq!(f.role_of(5), PlaylistRole::Admin);
    }
}
