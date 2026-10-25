//! Streamify Jam Operation-Based CRDT (CmRDT) Engine — v3
//!
//! Audit fixes carried from the v2 review:
//!   R3  Identity parity: CAD-IDs are minted ONLY through
//!       `repository::generate_cad_id_u64` (canonical normalization +
//!       duration bucketing). This module never forks the hasher.
//!   R1  Commutativity under equal fractions: concurrent inserts into the
//!       same gap produce identical `frac_index` values. Ordering key is the
//!       composite `(frac_bits, add_op_id)` so merge results are independent
//!       of arrival order (op_id breaks ties deterministically).
//!   R2  Reorder captures the entry BEFORE removal (v2 read a deleted slot
//!       and silently depended on sender echo for cad identity).
//!
//! Additional hardening:
//!   - `JamOp::new()` is the only sanctioned constructor: pads are forced to
//!     zero and the checksum computed over the exact wire span [0..40).
//!   - Explicit little-endian field serialization (`to_bytes` / `from_bytes`)
//!     replaces pointer-cast transmutation — portable across languages and
//!     free of unaligned-read UB. Layout mirrors the repr(C) declaration.
//!   - `needs_rebalance` uses relative ULP distance; shared-fraction entries
//!     (gap 0) trip it immediately.
//!
//! Tombstone contract (B2 lineage):
//!   Every queue element's identity is its ADD op's `op_id`. Remove ops carry
//!   that id in `target_add_op_id`; tombstoning suppresses late replays of the
//!   Add regardless of delivery order. Folds ship tombstones alongside the
//!   queue so fresh replicas cannot resurrect removed elements.
//!
//! ═══════════════════════════════════════════════════════════════════════
//! PHASE 2 (feat/phase2-rust-crdt-blend-voting) — Democratic group queue
//! voting CRDT (directive A / BEHIND.md gap #37):
//!
//!   • `OpType::Vote` (=4) merge logic in `apply_op`: positive upvotes AND
//!     vote retractions tracked per `target_add_op_id`, mapped by voter
//!     identity — one vote per peer per track, idempotent. The vote state
//!     per (target, voter) is an LWW register keyed by the strictly
//!     monotonic `op_id`, so out-of-order delivery and replayed frames are
//!     immune by construction (a stale op can never clobber a newer one).
//!   • `VoterId` — 32-byte voter identity. Strong path: the Ed25519
//!     governance pubkey verified at the mesh wire boundary
//!     (`apply_op_as`). Legacy path: the 4-byte device-nonce namespace
//!     (`apply_op`, e.g. the solo-queue JNI surface).
//!   • Threshold auto-promotion (see `reconcile_promotions`): a track whose
//!     net upvotes reach `PromotionPolicy::effective_threshold`
//!     (⌊N/2⌋+1 majority or host-configured ratio) bubbles up into the
//!     active playback queue via deterministic fractional-index
//!     repositioning that never disturbs un-voted items.
//! ═══════════════════════════════════════════════════════════════════════

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};

use crate::repository::generate_cad_id_u64;

const FNV1A_32_OFFSET: u32 = 0x811c_9dc5;
const FNV1A_32_PRIME: u32 = 0x0100_0193;

/// Wire size of [`JamOp`] (repr(C): 48 bytes, naturally aligned).
pub const JAM_OP_SIZE: usize = 48;

/// `policy_flags` bit carried by Vote ops: set = positive upvote,
/// clear = vote retraction (Phase 2 directive A).
pub const VOTE_FLAG_UP: u8 = 0x01;

/// Hard cap on distinct vote targets held in the ledger. Beyond the cap the
/// oldest target (smallest latest op-id, tie by target id) is evicted — a
/// pure function of ledger content, so eviction is replica-convergent.
pub const MAX_VOTE_TARGETS: usize = 4096;

// ═════════════════════════════════════════════════════════════════
// PHASE 2 — threshold auto-promotion policy (directive A / gap #37)
// ═════════════════════════════════════════════════════════════════

/// When a track's net upvotes cross the effective threshold it is
/// auto-promoted into the active playback queue. Two knobs compose:
///   • default quorum: ⌊N/2⌋ + 1 (N = `member_count`) — a strict majority;
///   • host-configured `ratio`, clamped to (0, 1] at read time —
///     `ceil(ratio · N)`, e.g. 0.75 with N=8 → 6 votes; values above
///     1.0 clamp to 1.0 so the threshold is always achievable.
/// `min_votes` floors the result (solo rooms stay usable), and the final
/// value is clamped to at least 1.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PromotionPolicy {
    /// Room size N used by the quorum/ratio math (Spotify Jam parity: 32).
    pub member_count: u32,
    /// Host override ratio; `None` → plain ⌊N/2⌋+1 majority.
    pub ratio: Option<f32>,
    /// Absolute floor on the effective threshold.
    pub min_votes: u32,
}

impl Default for PromotionPolicy {
    fn default() -> Self {
        PromotionPolicy {
            member_count: 2,
            ratio: None,
            min_votes: 1,
        }
    }
}

impl PromotionPolicy {
    /// Effective promotion threshold in net upvotes.
    pub fn effective_threshold(&self) -> u32 {
        let quorum = (self.member_count / 2) + 1;
        // Audit #22 item 7: clamp the host ratio into (0, 1] — a
        // hostile or buggy ratio > 1.0 used to mint ceil(r · N) > N,
        // an unachievable threshold that deadlocked promotion. NaN
        // and non-positive ratios keep the majority fallback.
        let ratio_based = self
            .ratio
            .filter(|r| *r > 0.0)
            .map(|r| (r.clamp(0.0, 1.0) as f64 * self.member_count as f64).ceil() as u32)
            .unwrap_or(quorum);
        ratio_based.max(self.min_votes).max(1)
    }
}

/// Promotion state of one queue element (query result).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PromotionStatus {
    /// Net votes below threshold.
    Pending { votes: u32, threshold: u32 },
    /// Threshold met — sitting in the active playback queue.
    Committed { votes: u32 },
}

/// One row of the proposed / committed queue views (UI binding).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct QueueViewEntry {
    /// Effective playback position (fractional index).
    pub frac: f64,
    /// Element identity — its ADD op's id (also the vote target).
    pub add_op_id: u64,
    /// Canonical track id.
    pub cad_id: u64,
    /// Net upvotes currently standing.
    pub votes: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum OpType {
    Add = 1,
    Remove = 2,
    Reorder = 3,
    Vote = 4, // Phase 2 directive A — merged by apply_op since feat/phase2-rust-crdt-blend-voting
}

// Global op-id generator state (per process).
static GLOBAL_OP_COUNTER: AtomicU16 = AtomicU16::new(0);
static LAST_SEEN_MS: AtomicU64 = AtomicU64::new(0);

/// The canonical Jam mutation record — 48 bytes on the wire.
#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
pub struct JamOp {
    /// 48-bit unix_ms << 16 | 16-bit per-process counter. Strictly monotonic
    /// per device even under NTP step-back; doubles as element identity.
    pub op_id: u64,
    /// 4-byte device identity (device-nonce prefix from JamEngine).
    pub sender_nonce: [u8; 4],
    pub op_type: u8,
    pub policy_flags: u8,
    pub _pad1: [u8; 2],
    pub track_cad_id: u64,
    pub frac_index: f64,
    pub target_add_op_id: u64,
    pub checksum: u32,
    pub _pad2: u32,
}

impl Default for JamOp {
    fn default() -> Self {
        JamOp {
            op_id: 0,
            sender_nonce: [0; 4],
            op_type: 0,
            policy_flags: 0,
            _pad1: [0; 2],
            track_cad_id: 0,
            frac_index: 0.0,
            target_add_op_id: 0,
            checksum: 0,
            _pad2: 0,
        }
    }
}

impl JamOp {
    /// Sanctioned constructor: zeroed pads, checksum sealed.
    pub fn new(
        op_id: u64,
        sender_nonce: [u8; 4],
        op_type: OpType,
        policy_flags: u8,
        track_cad_id: u64,
        frac_index: f64,
        target_add_op_id: u64,
    ) -> Self {
        let mut op = JamOp {
            op_id,
            sender_nonce,
            op_type: op_type as u8,
            policy_flags,
            _pad1: [0; 2],
            track_cad_id,
            frac_index,
            target_add_op_id,
            checksum: 0,
            _pad2: 0,
        };
        op.checksum = op.compute_checksum();
        op
    }

    /// Strictly monotonic op-id generation, immune to clock step-back:
    /// LAST_SEEN_MS is ratcheted forward, so a regressing wall clock yields
    /// prev+1 rather than a smaller id. Same-millisecond calls disambiguate
    /// through the 16-bit counter.
    pub fn generate_op_id() -> u64 {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let mut current_ms = now_ms;
        let prev = LAST_SEEN_MS.fetch_max(current_ms, Ordering::AcqRel);
        if prev >= current_ms {
            current_ms = prev.saturating_add(1);
            LAST_SEEN_MS.store(current_ms, Ordering::Release);
        }

        ((current_ms & 0xFFFF_FFFF_FFFF) << 16) | GLOBAL_OP_COUNTER.fetch_add(1, Ordering::AcqRel) as u64
    }

    /// FNV-1a 32 over payload bytes [0..40): every field except the checksum
    /// itself and trailing pad. Pads are included BY DESIGN — they are forced
    /// to zero by [`JamOp::new`] and by `from_bytes`, making the span
    /// deterministic across senders.
    pub fn compute_checksum(&self) -> u32 {
        let mut hash = FNV1A_32_OFFSET;
        for b in self.to_bytes()[..40].iter() {
            hash ^= *b as u32;
            hash = hash.wrapping_mul(FNV1A_32_PRIME);
        }
        hash
    }

    #[inline]
    pub fn is_valid(&self) -> bool {
        self.compute_checksum() == self.checksum
    }

    /// Explicit little-endian serialization — no unsafe, no padding ambiguity.
    pub fn to_bytes(&self) -> [u8; JAM_OP_SIZE] {
        let mut b = [0u8; JAM_OP_SIZE];
        b[0..8].copy_from_slice(&self.op_id.to_le_bytes());
        b[8..12].copy_from_slice(&self.sender_nonce);
        b[12] = self.op_type;
        b[13] = self.policy_flags;
        // _pad1 stays zero (b initialized to 0)
        b[16..24].copy_from_slice(&self.track_cad_id.to_le_bytes());
        b[24..32].copy_from_slice(&self.frac_index.to_bits().to_le_bytes());
        b[32..40].copy_from_slice(&self.target_add_op_id.to_le_bytes());
        b[40..44].copy_from_slice(&self.checksum.to_le_bytes());
        // _pad2 stays zero
        b
    }

    /// Inverse of [`to_bytes`]. Rejects non-zero pads (corrupt/hostile rows)
    /// by returning None instead of panicking.
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != JAM_OP_SIZE {
            return None;
        }
        if bytes[14] != 0 || bytes[15] != 0 || bytes[44..48].iter().any(|&x| x != 0) {
            return None;
        }
        let mut op = JamOp::default();
        let mut arr8 = [0u8; 8];
        arr8.copy_from_slice(&bytes[0..8]);
        op.op_id = u64::from_le_bytes(arr8);
        op.sender_nonce.copy_from_slice(&bytes[8..12]);
        op.op_type = bytes[12];
        op.policy_flags = bytes[13];
        arr8.copy_from_slice(&bytes[16..24]);
        op.track_cad_id = u64::from_le_bytes(arr8);
        arr8.copy_from_slice(&bytes[24..32]);
        op.frac_index = f64::from_bits(u64::from_le_bytes(arr8));
        arr8.copy_from_slice(&bytes[32..40]);
        op.target_add_op_id = u64::from_le_bytes(arr8);
        let mut arr4 = [0u8; 4];
        arr4.copy_from_slice(&bytes[40..44]);
        op.checksum = u32::from_le_bytes(arr4);
        Some(op)
    }
}

/// Canonical CAD-ID (u64) delegated to the repository hasher — V1 parity by
/// construction. Kept here as a thin alias so call sites read cleanly.
#[inline]
pub fn canonical_cad_id(title: &str, artist: &str, duration_sec: u32) -> u64 {
    generate_cad_id_u64(title, artist, duration_sec)
}

/// Total-order composite key: primary by fraction bit-pattern (monotonic for
/// finite non-negative f64), secondary by add-op id. Guarantees that two
/// replicas applying the same op set in different orders converge (R1).
fn frac_key(frac_index: f64, add_op_id: u64) -> (u64, u64) {
    (frac_index.to_bits(), add_op_id)
}

// ═══════════════════════════════════════════════════════════════════════
// PHASE 2 — democratic voting ledger (directive A / gap #37)
// ═══════════════════════════════════════════════════════════════════════

/// 32-byte voter identity. Two namespaces, byte-disjoint:
///   • `from_pubkey` — the Ed25519 governance pubkey verified at the mesh
///     wire boundary (the "peer_pubkey" the directive maps votes by);
///   • `from_nonce` — the legacy 4-byte device-nonce domain
///     (`[0x01, 0×27, nonce]`). A real Ed25519 verifying key colliding with
///     that fixed prefix is cryptographically impossible.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct VoterId(pub [u8; 32]);

impl VoterId {
    /// Strong identity: a verified 32-byte Ed25519 governance pubkey.
    pub fn from_pubkey(pk: &[u8; 32]) -> Self {
        VoterId(*pk)
    }

    /// Legacy identity: the 4-byte device nonce carried by `JamOp` itself.
    pub fn from_nonce(nonce: &[u8; 4]) -> Self {
        let mut k = [0u8; 32];
        k[0] = 0x01;
        k[28..32].copy_from_slice(nonce);
        VoterId(k)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

/// One voter's standing vote for one track: LWW register — the strictly
/// monotonic `op_id` (48-bit unix_ms << 16 | counter) is the timestamp, so
/// merge order never matters and replays are inert.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct VoteState {
    up: bool,
    op_id: u64,
}

/// What a Vote op did to the ledger.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VoteOutcome {
    /// Ledger state changed (new vote, retraction, or direction flip).
    Recorded,
    /// Exact replay of an already-known op (same op_id) — idempotent no-op.
    Duplicate,
    /// Stale frame: older than the voter's standing vote — dropped.
    Superseded,
    /// Structurally invalid (zero target id).
    Rejected,
}

/// Per-target vote table: `target_add_op_id → (voter → standing vote)`.
#[derive(Debug, Default)]
struct VoteLedger {
    per_target: HashMap<u64, HashMap<VoterId, VoteState>>,
}

impl VoteLedger {
    /// Net upvote count for one target (retracted voters excluded).
    fn count(&self, target: u64) -> u32 {
        self.per_target
            .get(&target)
            .map(|m| m.values().filter(|v| v.up).count() as u32)
            .unwrap_or(0)
    }

    /// Voter roster for one target, byte-order sorted (deterministic folds).
    fn roster(&self, target: u64) -> Vec<VoterId> {
        let mut ids: Vec<VoterId> = self
            .per_target
            .get(&target)
            .map(|m| m.keys().copied().collect())
            .unwrap_or_default();
        ids.sort();
        ids
    }

    /// Standing vote of one voter on one target (`Some(true)` = upvote).
    fn voted_by(&self, target: u64, voter: &VoterId) -> Option<bool> {
        self.per_target.get(&target)?.get(voter).map(|v| v.up)
    }

    /// Merges one vote event (LWW by `op_id`).
    fn apply(&mut self, target: u64, voter: VoterId, up: bool, op_id: u64) -> VoteOutcome {
        if target == 0 {
            return VoteOutcome::Rejected; // Add ops mint op_ids >= 1; 0 is hostile
        }
        let per = self.per_target.entry(target).or_default();
        if let Some(st) = per.get(&voter) {
            if st.op_id == op_id {
                return VoteOutcome::Duplicate;
            }
            if st.op_id > op_id {
                return VoteOutcome::Superseded;
            }
        }
        per.insert(voter, VoteState { up, op_id });
        // Deterministic capacity eviction: the oldest target (smallest
        // latest op-id, tie by target id) leaves first — content-addressed,
        // so every replica holding the same ledger evicts identically.
        while self.per_target.len() > MAX_VOTE_TARGETS {
            let mut victim: Option<(u64, u64)> = None; // (latest op_id, target)
            for (t, voters) in &self.per_target {
                let latest = voters.values().map(|v| v.op_id).max().unwrap_or(0);
                match victim {
                    Some((lo, lt)) if (latest, *t) >= (lo, lt) => {}
                    _ => victim = Some((latest, *t)),
                }
            }
            match victim {
                Some((_, t)) => {
                    self.per_target.remove(&t);
                }
                None => break,
            }
        }
        VoteOutcome::Recorded
    }

    /// Sorted target list (deterministic folds / queries).
    fn targets(&self) -> Vec<u64> {
        let mut ts: Vec<u64> = self.per_target.keys().copied().collect();
        ts.sort_unstable();
        ts
    }

    /// Total tracked (target, voter) pairs — telemetry / tests.
    fn total_events(&self) -> usize {
        self.per_target.values().map(|m| m.len()).sum()
    }
}

#[derive(Debug, Clone)]
pub struct QueueEntry {
    cad_id: u64,
}

/// CRDT state machine for the shared Jam queue.
#[derive(Debug, Default)]
pub struct JamCrdtState {
    /// Composite-ordered queue: (frac_bits, add_op_id) -> entry.
    pub queue: BTreeMap<(u64, u64), QueueEntry>,
    /// Element tombstones: suppressed add_op_ids (survive folds).
    pub tombstones: HashMap<u64, ()>,
    /// Latched when adjacent fractions fall within relative ULP range.
    /// Reset externally after a successful rebalance pass.
    pub needs_rebalance: bool,
    /// Phase 2: democratic vote ledger (directive A).
    votes: VoteLedger,
    /// Phase 2: elements whose net upvotes meet the promotion threshold —
    /// they sit in the active playback queue. Recomputed from merged state
    /// on every pass: a history-dependent "promote-and-ratchet" rule would
    /// let two replicas that merged the same op set in different orders
    /// disagree on whether a threshold was ever crossed (the exact
    /// split-brain bug this engine exists to prevent), so promotion is a
    /// pure function of the final ledger — retractions that pull a track
    /// back below the threshold demote it to the proposed rail, keeping the
    /// active queue an honest live picture of the room's democratic will.
    committed: HashSet<u64>,
    /// Phase 2: promotion threshold configuration (host-configurable).
    promotion: PromotionPolicy,
}

impl JamCrdtState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Deterministically merges one operation. Idempotent per (op_id, key).
    pub fn apply_op(&mut self, op: &JamOp) -> bool {
        if !op.is_valid() || !op.frac_index.is_finite() {
            return false; // corrupt wire payload or NaN poisoning attempt
        }

        let mut touched: Option<(u64, u64)> = None;
        match op.op_type {
            1 => {
                // Add (element identity == op_id)
                if self.tombstones.contains_key(&op.op_id) {
                    return false; // B2: late replay after Remove — suppressed
                }
                let key = frac_key(op.frac_index, op.op_id);
                self.queue
                    .insert(key, QueueEntry { cad_id: op.track_cad_id });
                touched = Some(key);
            }
            2 => {
                // Remove: tombstone the ELEMENT, then purge any live entry.
                self.tombstones.insert(op.target_add_op_id, ());
                let doomed: Vec<(u64, u64)> = self
                    .queue
                    .keys()
                    .filter(|k| k.1 == op.target_add_op_id)
                    .copied()
                    .collect();
                for k in doomed {
                    self.queue.remove(&k);
                }
                // Removals only WIDEN neighbour gaps — no new density
                // violation can form, so no rebalance check is needed.
            }
            3 => {
                // Reorder: capture before removal (R2), preserve identity.
                let found = self
                    .queue
                    .iter()
                    .find(|(k, _)| k.1 == op.target_add_op_id)
                    .map(|(k, v)| (*k, v.cad_id));
                if let Some((old_key, cad_id)) = found {
                    self.queue.remove(&old_key);
                    let key = frac_key(op.frac_index, op.target_add_op_id);
                    self.queue.insert(key, QueueEntry { cad_id });
                    touched = Some(key);
                }
                // Reordering an absent/tombstoned element is a no-op.
            }
            4 => {
                // Phase 2 (directive A): democratic vote merge. `policy_flags`
                // bit0 carries the direction (upvote / retraction) and
                // `target_add_op_id` the voted element. Voter identity on
                // this legacy path derives from the device nonce; the strong
                // pubkey-verified path is [`JamCrdtState::apply_op_as`].
                // The queue is untouched — no rebalance anchor.
                let voter = VoterId::from_nonce(&op.sender_nonce);
                self.votes.apply(
                    op.target_add_op_id,
                    voter,
                    op.policy_flags & VOTE_FLAG_UP != 0,
                    op.op_id,
                );
            }
            _ => return false, // unknown op types stay rejected at the boundary
        }

        self.reconcile_promotions();
        if let Some(key) = touched {
            self.check_rebalance_at(&key);
        }
        true
    }

    /// M2: RELATIVE ULP check — scale-aware density detection. Shared-fraction
    /// neighbours (composite-key ties) have gap 0 and trip instantly.
    ///
    /// INCREMENTAL: the mutated key is checked against its immediate
    /// BTreeMap neighbours only. A density violation can only FORM at an
    /// insertion site (removals widen gaps), so neighbour checks at every
    /// mutation are equivalent to a full sweep — at O(log n) instead of
    /// the O(n) per-op cost the mesh ingress path cannot afford (measured:
    /// the full sweep is what pushed the Phase 1 32-peer 1000-op chaos
    /// trial over its median budget once the CRDT mirror landed).
    /// Bulk hydration keeps the full sweep via [`Self::check_rebalance_full`].
    fn check_rebalance_at(&mut self, key: &(u64, u64)) {
        use std::ops::Bound;
        if self.queue.len() < 2 {
            return;
        }
        let me = f64::from_bits(key.0);
        // Predecessor gap.
        if let Some((pb, _)) = self.queue.range(..key).next_back() {
            let p = f64::from_bits(pb.0);
            let ulp = me.abs().max(p.abs()) * f64::EPSILON;
            if (me - p) <= ulp * 2.0 {
                self.needs_rebalance = true;
                return;
            }
        }
        // Successor gap.
        if let Some((nb, _)) = self
            .queue
            .range((Bound::Excluded(*key), Bound::Unbounded))
            .next()
        {
            let n = f64::from_bits(nb.0);
            let ulp = n.abs().max(me.abs()) * f64::EPSILON;
            if (n - me) <= ulp * 2.0 {
                self.needs_rebalance = true;
            }
        }
    }

    /// Full-sweep variant for bulk hydration (load_snapshot / load_full):
    /// latches on ANY adjacent pair inside relative ULP range.
    fn check_rebalance_full(&mut self) {
        if self.queue.len() < 2 {
            return;
        }
        let mut prev_bits: Option<u64> = None;
        for k in self.queue.keys() {
            if let Some(pb) = prev_bits {
                let a = f64::from_bits(pb);
                let b = f64::from_bits(k.0);
                let gap = b - a;
                let ulp = b.abs().max(a.abs()) * f64::EPSILON;
                if gap <= ulp * 2.0 {
                    self.needs_rebalance = true;
                    return;
                }
            }
            prev_bits = Some(k.0);
        }
    }

    /// Canonical fold for joins/compaction: ordered queue + tombstone set.
    pub fn fold_to_snapshot(&self) -> (Vec<(f64, u64, u64)>, Vec<u64>) {
        let queue_snap = self
            .queue
            .iter()
            .map(|((bits, add_id), e)| (f64::from_bits(*bits), *add_id, e.cad_id))
            .collect();
        let tomb_snap = self.tombstones.keys().copied().collect();
        (queue_snap, tomb_snap)
    }

    /// Restore from a fold (join hydration / compaction adoption).
    pub fn load_snapshot(&mut self, queue: Vec<(f64, u64, u64)>, tombstones: Vec<u64>) {
        self.queue.clear();
        for (frac, add_id, cad) in queue {
            if frac.is_finite() {
                self.queue.insert(frac_key(frac, add_id), QueueEntry { cad_id: cad });
            }
        }
        self.tombstones.clear();
        for t in tombstones {
            self.tombstones.insert(t, ());
        }
        self.needs_rebalance = false;
        self.check_rebalance_full();
    }

    /// Live view for UI binding: [(frac, add_op_id, cad_id)] in play order.
    pub fn snapshot_vec(&self) -> Vec<(f64, u64, u64)> {
        self.fold_to_snapshot().0
    }

    // ── Phase 2: democratic voting surface (directive A / gap #37) ─────

    /// Strong-path op merge: the caller supplies the voter's VERIFIED 32-byte
    /// governance pubkey (mesh wire-boundary path). Vote ops are recorded
    /// against that pubkey; every other op type ignores `voter` and follows
    /// the standard merge. Returns `Some(VoteOutcome)` for Vote ops and
    /// `None` for every other op type (the boolean `apply_op` remains the
    /// compatibility contract).
    pub fn apply_op_as(&mut self, op: &JamOp, voter: &VoterId) -> Option<VoteOutcome> {
        if !op.is_valid() || !op.frac_index.is_finite() {
            return None; // corrupt wire payload or NaN poisoning attempt
        }
        if op.op_type == 4 {
            let outcome = self.votes.apply(
                op.target_add_op_id,
                *voter,
                op.policy_flags & VOTE_FLAG_UP != 0,
                op.op_id,
            );
            if outcome == VoteOutcome::Rejected {
                return Some(VoteOutcome::Rejected);
            }
            self.reconcile_promotions();
            Some(outcome)
        } else {
            let _ = self.apply_op(op);
            None
        }
    }

    /// Net upvote count for one queue element (retractions subtract).
    pub fn vote_count(&self, target_add_op_id: u64) -> u32 {
        self.votes.count(target_add_op_id)
    }

    /// Track (CAD id) of one live queue element, if present — vote-frame
    /// cad echo and UI lookups.
    pub fn cad_of(&self, target_add_op_id: u64) -> Option<u64> {
        self.queue
            .iter()
            .find(|(k, _)| k.1 == target_add_op_id)
            .map(|(_, e)| e.cad_id)
    }

    /// Voter roster for one queue element, byte-order sorted — the exact
    /// voter set behind a track's vote count (UI "who voted" sheet, tests).
    pub fn voter_roster(&self, target_add_op_id: u64) -> Vec<VoterId> {
        self.votes.roster(target_add_op_id)
    }

    /// One voter's standing vote on one element (`Some(true)` = upvote,
    /// `Some(false)` = retracted-but-recorded, `None` = never voted).
    pub fn voted_by(&self, target_add_op_id: u64, voter: &VoterId) -> Option<bool> {
        self.votes.voted_by(target_add_op_id, voter)
    }

    /// All targets that carry at least one recorded vote event, sorted.
    pub fn voted_targets(&self) -> Vec<u64> {
        self.votes.targets()
    }

    /// Total (target, voter) pairs tracked — ledger size telemetry.
    pub fn vote_event_count(&self) -> usize {
        self.votes.total_events()
    }

    // ── Phase 2: threshold auto-promotion engine (directive A) ────────

    /// Installs a promotion policy (member count / host ratio) and
    /// immediately reconciles — a smaller threshold can promote tracks
    /// that already have enough standing votes.
    pub fn set_promotion_policy(&mut self, policy: PromotionPolicy) {
        self.promotion = policy;
        self.reconcile_promotions();
    }

    /// Current promotion policy (threshold math is caller-visible).
    pub fn promotion_policy(&self) -> &PromotionPolicy {
        &self.promotion
    }

    /// Promotion state of one element: pending (votes, threshold) or
    /// committed (already bubbled into the active playback queue).
    pub fn promotion_status(&self, target_add_op_id: u64) -> PromotionStatus {
        let votes = self.votes.count(target_add_op_id);
        if self.committed.contains(&target_add_op_id) {
            PromotionStatus::Committed { votes }
        } else {
            PromotionStatus::Pending {
                votes,
                threshold: self.promotion.effective_threshold(),
            }
        }
    }

    /// PROPOSED queue view (directive A): live elements that have NOT been
    /// promoted, in manual fractional order — the "candidate" rail the
    /// optimistic UI renders with vote badges.
    pub fn proposed_queue(&self) -> Vec<QueueViewEntry> {
        self.queue
            .iter()
            .filter(|(k, _)| !self.committed.contains(&k.1))
            .map(|(k, e)| QueueViewEntry {
                frac: f64::from_bits(k.0),
                add_op_id: k.1,
                cad_id: e.cad_id,
                votes: self.votes.count(k.1),
            })
            .collect()
    }

    /// COMMITTED queue view (directive A): auto-promoted elements in
    /// democratic playback order — most net upvotes first, ties broken by
    /// the lower add op id (earlier proposal wins). This is the active
    /// playback queue.
    ///
    /// The order is DERIVED, never written back into the queue: each
    /// committed element's effective fractional index is computed from
    /// live merged state (rank inside the committed block → position in
    /// the open interval (0, hi), `hi` = smallest manual fraction still
    /// held by an un-promoted element, 1.0 when none). Two replicas that
    /// merged the same op set in different orders therefore derive the
    /// byte-identical placement — there is no history-dependent physical
    /// repositioning to diverge — while un-promoted elements keep their
    /// manual fractions EXACTLY (the directive's "without disturbing
    /// un-voted items" invariant).
    pub fn committed_queue(&self) -> Vec<QueueViewEntry> {
        let committed: Vec<(u64, u64)> = self
            .queue
            .iter()
            .filter(|(k, _)| self.committed.contains(&k.1))
            .map(|(k, e)| (k.1, e.cad_id))
            .collect();
        if committed.is_empty() {
            return Vec::new();
        }
        // Democratic order: (-net votes, add op id).
        let mut order: Vec<(u64, u64, u32)> = committed
            .into_iter()
            .map(|(id, cad)| (id, cad, self.votes.count(id)))
            .collect();
        order.sort_by(|a, b| b.2.cmp(&a.2).then(a.0.cmp(&b.0)));

        // hi: the manual frontier of the un-promoted rail.
        let hi = self
            .queue
            .keys()
            .filter(|k| !self.committed.contains(&k.1))
            .map(|k| f64::from_bits(k.0))
            .filter(|f| f.is_finite() && *f > 0.0)
            .fold(f64::INFINITY, f64::min)
            .min(1.0);
        let hi = if hi.is_finite() && hi > 0.0 { hi } else { 1.0 };

        let n = order.len() as f64;
        order
            .into_iter()
            .enumerate()
            .map(|(rank, (id, cad, votes))| QueueViewEntry {
                frac: hi * ((rank + 1) as f64) / (n + 1.0),
                add_op_id: id,
                cad_id: cad,
                votes,
            })
            .collect()
    }

    /// Full playback order the player consumes: the derived committed
    /// block (democratic order, fractionally ahead of everything else)
    /// followed by the proposed rail in manual fractional order.
    pub fn playback_order(&self) -> Vec<QueueViewEntry> {
        let mut v = self.committed_queue();
        v.extend(self.proposed_queue());
        v
    }

    /// Threshold auto-promotion pass: rebuilds the committed set from live
    /// merged state. A PURE FUNCTION OF MERGED STATE — every replica
    /// running it over the same queue + ledger derives the same committed
    /// set, regardless of the order votes and adds arrived in:
    ///
    ///   • a live element whose net upvotes meet
    ///     `promotion.effective_threshold()` is promoted (its derived
    ///     playback position comes from [`Self::committed_queue`]);
    ///   • retractions that pull an element back below the threshold
    ///     demote it to the proposed rail — convergence REQUIRES promotion
    ///     to be a function of merged state, not of the merge order in
    ///     which a threshold happened to be crossed;
    ///   • votes for elements that have not been added yet (out-of-order
    ///     delivery) are tolerated and promote the moment the Add lands;
    ///   • the physical queue's manual fractions are NEVER rewritten, so
    ///     there is no history-dependent repositioning state to diverge.
    fn reconcile_promotions(&mut self) {
        if self.votes.per_target.is_empty() && self.committed.is_empty() {
            return; // fast path: no voting activity yet (pure Add/Remove rooms)
        }
        let threshold = self.promotion.effective_threshold();
        let mut next: HashSet<u64> = HashSet::new();
        for target in self.votes.targets() {
            // Vote-before-Add tolerance: only live elements promote.
            if self.votes.count(target) >= threshold && self.queue.keys().any(|k| k.1 == target) {
                next.insert(target);
            }
        }
        self.committed = next;
    }

    /// FULL state fold (Phase 2): queue + tombstones + vote ledger, all in
    /// deterministic order — the join-hydration and split-brain-merge
    /// artifact that carries the complete democratic state (the committed
    /// set is intentionally NOT serialized: it is a pure function of the
    /// queue + ledger + local promotion policy, rebuilt on load). The
    /// legacy `fold_to_snapshot` keeps its frozen 2-tuple shape.
    pub fn fold_full(&self) -> FullSnapshot {
        let (queue, tombstones) = self.fold_to_snapshot();
        FullSnapshot {
            queue,
            tombstones,
            votes: self
                .votes
                .targets()
                .into_iter()
                .map(|t| {
                    let mut voters: Vec<(VoterId, bool, u64)> = self
                        .votes
                        .per_target
                        .get(&t)
                        .map(|m| {
                            m.iter()
                                .map(|(v, s)| (*v, s.up, s.op_id))
                                .collect()
                        })
                        .unwrap_or_default();
                    voters.sort_by_key(|a| a.0);
                    (t, voters)
                })
                .collect(),
        }
    }

    /// Restores a full fold (join hydration / compaction adoption) and
    /// reconciles promotions against the restored ledger.
    pub fn load_full(&mut self, snap: FullSnapshot) {
        self.load_snapshot(snap.queue, snap.tombstones);
        self.votes.per_target.clear();
        for (target, voters) in snap.votes {
            let per = self.votes.per_target.entry(target).or_default();
            for (voter, up, op_id) in voters {
                per.insert(voter, VoteState { up, op_id });
            }
        }
        self.committed.clear();
        self.reconcile_promotions();
        self.check_rebalance_full();
    }
}

/// Deterministic full-state fold artifact (Phase 2 join hydration).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FullSnapshot {
    /// Queue triples (frac, add_op_id, cad_id) in manual play order.
    pub queue: Vec<(f64, u64, u64)>,
    /// Suppressed add op ids.
    pub tombstones: Vec<u64>,
    /// Vote ledger: (target, [(voter, upvote?, op_id)] sorted by voter).
    /// Flat tuple by design: it mirrors the FFI snapshot export verbatim.
    #[allow(clippy::type_complexity)]
    pub votes: Vec<(u64, Vec<(VoterId, bool, u64)>)>,
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Single sequential lifecycle: all assertions share the global op-id
    //    generator, so cargo's parallel runner must never split them. ──

    #[test]
    fn crdt_full_lifecycle() {
        // ── R3: identity parity with the canonical pipeline ──
        for (t, a, d) in [
            ("Starboy", "The Weeknd", 230u32),
            ("Blinding Lights!", "the weeknd", 200),
            ("Señorita (Remix)", "Shawn Mendes", 191),
        ] {
            let hex = crate::repository::generate_cad_id(t, a, d);
            expect_eq_u64(canonical_cad_id(t, a, d), &hex);
        }

        // ── M1: monotonic ids under clock step-back ──
        LAST_SEEN_MS.store(5_000_000_000_000, Ordering::SeqCst);
        let id_hi1 = JamOp::generate_op_id();
        let id_hi2 = JamOp::generate_op_id();
        assert!(id_hi2 > id_hi1);

        // ── B1: tampered frac rejected ──
        let mut tampered = JamOp::new(77_001, [9; 4], OpType::Add, 0, 42, 0.5, 0);
        tampered.frac_index = 0.75; // flip AFTER sealing
        assert!(!tampered.is_valid());

        // ── B2: out-of-order Add after Remove suppressed ──
        let mut st = JamCrdtState::new();
        let cad_a = canonical_cad_id("Track A", "Artist", 100);
        let add_a = JamOp::new(JamOp::generate_op_id(), [1; 4], OpType::Add, 0, cad_a, 0.5, 0);
        let rem_a = JamOp::new(JamOp::generate_op_id(), [1; 4], OpType::Remove, 0, cad_a, 0.0, add_a.op_id);
        assert!(st.apply_op(&rem_a));
        assert!(st.tombstones.contains_key(&add_a.op_id));
        assert!(!st.apply_op(&add_a), "late Add must be tombstoned");
        assert!(st.queue.is_empty());

        // ── R1 litmus: same-fraction concurrent adds CONVERGE ──
        let cad_x = canonical_cad_id("Track X", "Artist", 90);
        let cad_y = canonical_cad_id("Track Y", "Artist", 95);
        let add_x = JamOp::new(88_001, [2; 4], OpType::Add, 0, cad_x, 0.55, 0);
        let add_y = JamOp::new(88_002, [3; 4], OpType::Add, 0, cad_y, 0.55, 0);

        let mut rep1 = JamCrdtState::new();
        let mut rep2 = JamCrdtState::new();
        rep1.apply_op(&add_x);
        rep1.apply_op(&add_y);
        rep2.apply_op(&add_y);
        rep2.apply_op(&add_x);
        assert_eq!(
            rep1.fold_to_snapshot(),
            rep2.fold_to_snapshot(),
            "equal-fraction races must converge deterministically"
        );

        // Full commutativity shuffle including a remove.
        let mut s1 = JamCrdtState::new();
        let mut s2 = JamCrdtState::new();
        let add_b = JamOp::new(89_010, [4; 4], OpType::Add, 0, cad_b(), 0.6, 0);
        let rem_x = JamOp::new(89_011, [4; 4], OpType::Remove, 0, 0, 0.0, add_x.op_id);
        for op in [&add_x, &add_b, &rem_x] {
            s1.apply_op(op);
        }
        for op in [&add_b, &rem_x, &add_x] {
            s2.apply_op(op);
        }
        assert_eq!(s1.fold_to_snapshot(), s2.fold_to_snapshot());

        // ── R2: reorder preserves cad identity captured pre-removal ──
        st.load_snapshot(vec![], vec![]);
        st.apply_op(&add_a).then_some(());
        assert_eq!(st.queue.len(), 1);
        let reorder = JamOp::new(
            JamOp::generate_op_id(),
            [1; 4],
            OpType::Reorder,
            0,
            999_999, // WRONG cad echo — must be ignored thanks to pre-capture
            0.8,
            add_a.op_id,
        );
        assert!(st.apply_op(&reorder));
        let snap = st.snapshot_vec();
        assert_eq!(snap.len(), 1);
        assert_eq!(snap[0].2, cad_a, "reorder must carry original cad, not sender echo");
        assert!((snap[0].0 - 0.8).abs() < f64::EPSILON);

        // ── M2: relative-ULP density latch ──
        let mut dense = JamCrdtState::new();
        dense.apply_op(&JamOp::new(91_001, [5; 4], OpType::Add, 0, 1, 1.0e300, 0));
        dense.apply_op(&JamOp::new(91_002, [5; 4], OpType::Add, 0, 2, 1.0e300 + 1.0e284, 0));
        assert!(dense.needs_rebalance, "ULP-scale gap must latch rebalance");

        // ── M3: NaN poisoning rejected ──
        let nan_op = JamOp::new(92_001, [6; 4], OpType::Add, 0, 7, f64::NAN, 0);
        assert!(!nan_op.frac_index.is_finite());

        // ── byte serde round-trip + pad rejection ──
        let op = add_b;
        let bytes = op.to_bytes();
        assert_eq!(bytes.len(), 48);
        let back = JamOp::from_bytes(&bytes).expect("round-trip");
        assert_eq!(back, op);
        let mut corrupt = bytes;
        corrupt[15] = 0xFF; // pad violation
        assert!(JamOp::from_bytes(&corrupt).is_none());
    }

    fn cad_b() -> u64 {
        canonical_cad_id("Track B", "Artist", 110)
    }

    fn expect_eq_u64(v: u64, hex: &str) {
        let parsed = u64::from_str_radix(hex, 16).expect("hex cad");
        assert_eq!(v, parsed, "u64 variant must match formatted pipeline");
    }

    // ═════════════════════════════════════════════════════════════════
    // PHASE 2 — democratic voting ledger (directive A / gap #37)
    // ═════════════════════════════════════════════════════════════════

    fn vote_op(op_id: u64, nonce: [u8; 4], up: bool, target: u64) -> JamOp {
        JamOp::new(op_id, nonce, OpType::Vote, if up { VOTE_FLAG_UP } else { 0 }, 0, 0.0, target)
    }

    fn voter_pk(seed: u8) -> VoterId {
        let mut pk = [0u8; 32];
        pk[0] = seed;
        pk[31] = 0xAA;
        VoterId::from_pubkey(&pk)
    }

    #[test]
    fn vote_ledger_idempotent_one_vote_per_peer() {
        let mut st = JamCrdtState::new();
        let t = 5001u64;

        // Three distinct voters (strong pubkey path), one duplicate replay.
        let v1 = vote_op(2001, [1; 4], true, t);
        let v2 = vote_op(2002, [2; 4], true, t);
        let v3 = vote_op(2003, [3; 4], true, t);
        assert_eq!(
            st.apply_op_as(&v1, &voter_pk(1)),
            Some(VoteOutcome::Recorded)
        );
        assert_eq!(
            st.apply_op_as(&v2, &voter_pk(2)),
            Some(VoteOutcome::Recorded)
        );
        assert_eq!(
            st.apply_op_as(&v3, &voter_pk(3)),
            Some(VoteOutcome::Recorded)
        );
        // Replay of the exact same op: idempotent, count unchanged.
        assert_eq!(
            st.apply_op_as(&v1, &voter_pk(1)),
            Some(VoteOutcome::Duplicate)
        );
        assert_eq!(st.vote_count(t), 3, "one vote per peer per track");
        assert_eq!(st.voter_roster(t).len(), 3);
        assert_eq!(st.vote_event_count(), 3);
        assert_eq!(st.voted_targets(), vec![t]);

        // A second vote op from the SAME voter with a NEWER op_id does not
        // double-count — it replaces the standing vote (still one entry).
        let v1_again = vote_op(2004, [1; 4], true, t);
        assert_eq!(
            st.apply_op_as(&v1_again, &voter_pk(1)),
            Some(VoteOutcome::Recorded)
        );
        assert_eq!(st.vote_count(t), 3, "re-vote replaces, never stacks");
    }

    #[test]
    fn vote_retraction_and_out_of_order_lww() {
        let mut st = JamCrdtState::new();
        let t = 5002u64;

        let up = vote_op(3001, [7; 4], true, t);
        let retract = vote_op(3002, [7; 4], false, t);
        assert_eq!(st.apply_op_as(&up, &voter_pk(7)), Some(VoteOutcome::Recorded));
        assert_eq!(st.vote_count(t), 1);

        // Retraction (newer op_id) → net count drops to 0, roster keeps history.
        assert_eq!(
            st.apply_op_as(&retract, &voter_pk(7)),
            Some(VoteOutcome::Recorded)
        );
        assert_eq!(st.vote_count(t), 0, "retraction subtracts");
        assert_eq!(st.voter_roster(t).len(), 1, "roster retains retracted voter");
        assert_eq!(st.voted_by(t, &voter_pk(7)), Some(false));

        // Stale out-of-order delivery (older op_id after the retraction).
        let stale = vote_op(3000, [7; 4], true, t);
        assert_eq!(
            st.apply_op_as(&stale, &voter_pk(7)),
            Some(VoteOutcome::Superseded)
        );
        assert_eq!(st.vote_count(t), 0, "stale vote cannot resurrect");

        // Re-upvote with a newer op_id wins again.
        let reup = vote_op(3003, [7; 4], true, t);
        assert_eq!(st.apply_op_as(&reup, &voter_pk(7)), Some(VoteOutcome::Recorded));
        assert_eq!(st.vote_count(t), 1);
    }

    #[test]
    fn vote_merge_order_independence_and_legacy_path() {
        let t1 = 6001u64;
        let t2 = 6002u64;
        let ops = [
            vote_op(4001, [1; 4], true, t1),
            vote_op(4002, [2; 4], true, t1),
            vote_op(4003, [1; 4], false, t2),
            vote_op(4004, [3; 4], true, t2),
            vote_op(4005, [2; 4], false, t1), // voter 2 retracts t1 (newer)
        ];

        let mut a = JamCrdtState::new();
        let mut b = JamCrdtState::new();
        for op in &ops {
            // Strong path keyed by distinct pubkeys.
            let _ = a.apply_op_as(op, &voter_pk(op.sender_nonce[0]));
        }
        for op in ops.iter().rev() {
            let _ = b.apply_op_as(op, &voter_pk(op.sender_nonce[0]));
        }
        assert_eq!(a.vote_count(t1), b.vote_count(t1));
        assert_eq!(a.vote_count(t1), 1, "voter2 retracted, voter1 stands");
        assert_eq!(a.vote_count(t2), b.vote_count(t2));
        assert_eq!(a.vote_count(t2), 1);

        // Legacy nonce path via apply_op: distinct nonces → distinct voters.
        let mut legacy = JamCrdtState::new();
        assert!(legacy.apply_op(&ops[0]));
        assert!(legacy.apply_op(&ops[1]));
        assert!(legacy.apply_op(&ops[4]));
        assert_eq!(legacy.vote_count(t1), 1);

        // Hostile zero-target vote rejected on both paths.
        let hostile = vote_op(4999, [9; 4], true, 0);
        assert_eq!(
            legacy.apply_op_as(&hostile, &voter_pk(9)),
            Some(VoteOutcome::Rejected)
        );
    }

    // ── threshold auto-promotion engine (directive A) ─────────────────

    fn seeded_room(member_count: u32) -> JamCrdtState {
        let mut st = JamCrdtState::new();
        st.set_promotion_policy(PromotionPolicy {
            member_count,
            ratio: None,
            min_votes: 1,
        });
        st
    }

    fn add_op_for(op_id: u64, cad: u64, frac: f64) -> JamOp {
        JamOp::new(op_id, [1; 4], OpType::Add, 0, cad, frac, 0)
    }

    #[test]
    fn promotion_threshold_math() {
        // ⌊N/2⌋+1 majority ladder.
        let p = |n: u32| PromotionPolicy { member_count: n, ratio: None, min_votes: 1 };
        assert_eq!(p(1).effective_threshold(), 1);
        assert_eq!(p(2).effective_threshold(), 2);
        assert_eq!(p(7).effective_threshold(), 4);
        assert_eq!(p(8).effective_threshold(), 5);
        assert_eq!(p(32).effective_threshold(), 17);

        // Host-configured ratio: ceil(ratio·N).
        let r = PromotionPolicy { member_count: 8, ratio: Some(0.75), min_votes: 1 };
        assert_eq!(r.effective_threshold(), 6);
        // Ratio floors below the majority quorum are still allowed.
        let r2 = PromotionPolicy { member_count: 8, ratio: Some(0.25), min_votes: 1 };
        assert_eq!(r2.effective_threshold(), 2);
        // min_votes floor beats everything.
        let m = PromotionPolicy { member_count: 4, ratio: None, min_votes: 9 };
        assert_eq!(m.effective_threshold(), 9);
    }

    #[test]
    fn promotion_bubbles_up_without_disturbing_unvoted() {
        // Room of 5 → threshold 3. Four candidates at stable fracs.
        let mut st = seeded_room(5);
        let a = add_op_for(10_001, 101, 0.40);
        let b = add_op_for(10_002, 102, 0.50);
        let c = add_op_for(10_003, 103, 0.60);
        let d = add_op_for(10_004, 104, 0.70);
        for op in [&a, &b, &c, &d] {
            assert!(st.apply_op(op));
        }
        let before = st.proposed_queue();
        assert_eq!(before.len(), 4);

        // Three voters upvote B → threshold met → B bubbles up.
        for voter in 1..=3 {
            let v = vote_op(11_000 + voter as u64, [voter; 4], true, b.op_id);
            let _ = st.apply_op_as(&v, &voter_pk(voter));
        }
        assert_eq!(
            st.promotion_status(b.op_id),
            PromotionStatus::Committed { votes: 3 }
        );

        // The committed view is exactly [B], and B now sits fractionally
        // AHEAD of every un-voted item…
        let committed = st.committed_queue();
        assert_eq!(committed.len(), 1);
        assert_eq!(committed[0].add_op_id, b.op_id);
        let head_unvoted = st.proposed_queue()[0].frac;
        assert!(committed[0].frac < head_unvoted);

        // …while every un-promoted element keeps its EXACT manual fraction
        // (the derived committed block never rewrites the physical queue).
        let after = st.proposed_queue();
        assert_eq!(after.len(), 3);
        for (prev, cur) in before.iter().filter(|e| e.add_op_id != b.op_id).zip(after.iter()) {
            assert_eq!(prev.add_op_id, cur.add_op_id);
        }
        assert_eq!(after[0].add_op_id, a.op_id);
        assert!((after[0].frac - 0.40).abs() < f64::EPSILON, "un-voted fractions are sacred");
        assert!((after[2].frac - 0.70).abs() < f64::EPSILON);

        // A and C each collect 3 votes too — democratic ordering emerges:
        // all three committed, ranked by (-votes, add_op_id) → B(10_002),
        // A(10_001), C(10_003) at equal votes… B/A/C order by add id when
        // votes tie; give C 4 votes to make it the head.
        for voter in 1..=3 {
            let va = vote_op(12_000 + voter as u64, [voter; 4], true, a.op_id);
            let vc = vote_op(13_000 + voter as u64, [voter; 4], true, c.op_id);
            let _ = st.apply_op_as(&va, &voter_pk(voter));
            let _ = st.apply_op_as(&vc, &voter_pk(voter));
        }
        let vc4 = vote_op(13_004, [4; 4], true, c.op_id);
        let _ = st.apply_op_as(&vc4, &voter_pk(4));

        let committed = st.committed_queue();
        assert_eq!(committed.len(), 3);
        assert_eq!(committed[0].add_op_id, c.op_id, "4 votes head the queue");
        assert_eq!(committed[1].add_op_id, a.op_id, "tie 3-3 → lower add id first");
        assert_eq!(committed[2].add_op_id, b.op_id);
        // Strictly ascending fractional order inside the committed block,
        // and the whole block sits fractionally ahead of the un-voted rail.
        assert!(committed[0].frac < committed[1].frac && committed[1].frac < committed[2].frac);
        let proposed = st.proposed_queue();
        assert_eq!(proposed.len(), 1);
        assert_eq!(proposed[0].add_op_id, d.op_id);
        assert!(committed[2].frac < proposed[0].frac);
        assert!((proposed[0].frac - 0.70).abs() < f64::EPSILON, "D untouched at its manual frac");

        // The full playback order = committed block, then proposed rail.
        let playback = st.playback_order();
        assert_eq!(
            playback.iter().map(|e| e.add_op_id).collect::<Vec<_>>(),
            vec![c.op_id, a.op_id, b.op_id, d.op_id]
        );
        // Manual queue order (legacy view) is completely untouched.
        assert_eq!(
            st.snapshot_vec().iter().map(|t| t.1).collect::<Vec<_>>(),
            vec![a.op_id, b.op_id, c.op_id, d.op_id]
        );
    }

    #[test]
    fn promotion_split_brain_convergence() {
        // Two replicas see the SAME op stream in opposite orders (classic
        // split-brain heal). Final full state — including committed fracs —
        // must be byte-identical.
        let ops: Vec<JamOp> = vec![
            add_op_for(20_001, 201, 0.5),
            add_op_for(20_002, 202, 0.7),
            add_op_for(20_003, 203, 0.9),
            vote_op(21_001, [1; 4], true, 20_001),
            vote_op(21_002, [2; 4], true, 20_001),
            vote_op(21_003, [3; 4], true, 20_002),
            vote_op(21_004, [4; 4], true, 20_002),
            vote_op(21_005, [5; 4], true, 20_002),
            vote_op(21_006, [1; 4], false, 20_001), // voter 1 retracts
            vote_op(21_007, [3; 4], true, 20_003),
        ];

        let mut r1 = seeded_room(5); // threshold 3
        let mut r2 = seeded_room(5);
        for op in &ops {
            let voter = voter_pk(op.sender_nonce[0]);
            let _ = r1.apply_op_as(op, &voter);
        }
        for op in ops.iter().rev() {
            let voter = voter_pk(op.sender_nonce[0]);
            let _ = r2.apply_op_as(op, &voter);
        }
        assert_eq!(r1.fold_full(), r2.fold_full(), "split-brain merges must converge");
        assert_eq!(r1.snapshot_vec(), r2.snapshot_vec());

        // Post-merge invariants: 20_002 kept 3 votes (promoted),
        // 20_001 dropped to 1 (never promoted), 20_003 has 1.
        assert_eq!(r1.vote_count(20_002), 3);
        assert_eq!(r1.vote_count(20_001), 1);
        assert!(matches!(r1.promotion_status(20_002), PromotionStatus::Committed { .. }));
        assert!(matches!(
            r1.promotion_status(20_001),
            PromotionStatus::Pending { votes: 1, threshold: 3 }
        ));

        // Fold → hydrate a third replica from either side: identical state.
        let mut r3 = seeded_room(5);
        r3.load_full(r1.fold_full());
        assert_eq!(r3.fold_full(), r1.fold_full());
        assert_eq!(r3.committed_queue(), r1.committed_queue());
    }

    #[test]
    fn promotion_retraction_demotes_and_removal_purges() {
        let mut st = seeded_room(3); // threshold 2
        let t = add_op_for(30_001, 301, 0.5);
        assert!(st.apply_op(&t));
        for voter in 1..=2 {
            let v = vote_op(31_000 + voter as u64, [voter; 4], true, t.op_id);
            let _ = st.apply_op_as(&v, &voter_pk(voter));
        }
        assert!(matches!(st.promotion_status(t.op_id), PromotionStatus::Committed { votes: 2 }));

        // Both voters retract — the merged count falls below threshold and
        // the element demotes back to the proposed rail. Convergence demands
        // promotion be a pure function of merged state (see
        // reconcile_promotions): a history-dependent ratchet would let two
        // replicas disagree on whether the threshold was ever crossed.
        for voter in 1..=2 {
            let v = vote_op(32_000 + voter as u64, [voter; 4], false, t.op_id);
            let _ = st.apply_op_as(&v, &voter_pk(voter));
        }
        assert!(matches!(
            st.promotion_status(t.op_id),
            PromotionStatus::Pending { votes: 0, threshold: 2 }
        ));
        assert!(st.committed_queue().is_empty());
        assert_eq!(st.proposed_queue().len(), 1);
        // The demoted element returns to its ORIGINAL manual fraction.
        assert!((st.proposed_queue()[0].frac - 0.5).abs() < f64::EPSILON);

        // Remove purges the element entirely.
        let rem = JamOp::new(33_001, [9; 4], OpType::Remove, 0, 301, 0.0, t.op_id);
        assert!(st.apply_op(&rem));
        assert!(st.committed_queue().is_empty());
        assert!(st.proposed_queue().is_empty());

        // Vote-before-Add tolerance: votes for an element that has not
        // arrived yet are recorded but do not promote; the moment the Add
        // lands, the reconciliation promotes it in the same pass.
        let future = add_op_for(34_001, 401, 0.5);
        for voter in 1..=2 {
            let v = vote_op(35_000 + voter as u64, [voter; 4], true, future.op_id);
            let _ = st.apply_op_as(&v, &voter_pk(voter));
        }
        assert!(matches!(
            st.promotion_status(future.op_id),
            PromotionStatus::Pending { votes: 2, threshold: 2 }
        ));
        assert!(st.apply_op(&future), "late Add after its votes");
        assert!(matches!(st.promotion_status(future.op_id), PromotionStatus::Committed { votes: 2 }));
    }

    #[test]
    fn promotion_ratio_clamped_to_achievable_range() {
        // Audit #22 item 7 regression lock: ratio > 1.0 used to mint
        // ceil(r · N) > N — unachievable, promotion deadlocked. Now it
        // clamps to 1.0 (everyone-must-vote); NaN / zero / negative
        // keep the majority fallback.
        let hostile = PromotionPolicy { member_count: 8, ratio: Some(5.0), min_votes: 1 };
        assert_eq!(hostile.effective_threshold(), 8);
        let just_above = PromotionPolicy { member_count: 8, ratio: Some(1.5), min_votes: 1 };
        assert_eq!(just_above.effective_threshold(), 8);
        let upper_edge = PromotionPolicy { member_count: 8, ratio: Some(1.0), min_votes: 1 };
        assert_eq!(upper_edge.effective_threshold(), 8);
        let nan = PromotionPolicy { member_count: 8, ratio: Some(f32::NAN), min_votes: 1 };
        assert_eq!(nan.effective_threshold(), 5);
        let zero = PromotionPolicy { member_count: 8, ratio: Some(0.0), min_votes: 1 };
        assert_eq!(zero.effective_threshold(), 5);
        let neg = PromotionPolicy { member_count: 8, ratio: Some(-0.75), min_votes: 1 };
        assert_eq!(neg.effective_threshold(), 5);
    }
}
