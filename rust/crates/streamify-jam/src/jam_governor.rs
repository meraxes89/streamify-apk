//! Streamify Jam Governor v3 — pure election & death-pivot math (P8/P9/P3.3).
//!
//! Units standardized to MILLISECONDS everywhere (matches Phase-2 columns).
//! Epoch authority lives in the SERVER (`jam_takeover` returns the fencing
//! token); this unit deliberately contains no epoch math.
//!
//! Pure functions only — no globals, no FFI state, parallel-safe tests.
//!
//! ═══════════════════════════════════════════════════════════════════════
//! PHASE 1 (feat/phase1-rust-mesh-32peers) — Room governance fabric
//! (BEHIND.md gaps #13, #14, #18), appended below the v3 pure functions:
//!
//!   • `Topology`            — MultiRender (N lockstep players) vs
//!                             SingleRender (host owns the single renderer;
//!                             guests route epoch-fenced signed intents to
//!                             the host and keep a silent PTP presence).
//!   • Permission bits       — ALLOW_PLAYBACK_CONTROL 0x01, ALLOW_VOLUME_
//!                             CONTROL 0x02, IS_COHOST 0x04, carried in
//!                             presence beacons and ACL_UPDATE frames.
//!   • `RoomGovernor`        — pure, socket-free governance state machine:
//!                             member registry keyed by 32-byte Ed25519
//!                             pubkeys, epoch fencing, ACL evaluation,
//!                             anti-replay (monotonic nonces), per-peer
//!                             token-bucket rate limiting (5 intents/sec),
//!                             session blacklist, and host-death failover
//!                             reusing the v3 `elect_successor` above.
//!   • Wire codecs           — TRANSPORT_INTENT / KICK_DIRECTIVE /
//!                             ACL_UPDATE frames: little-endian, strictly
//!                             bounds-checked, Ed25519-signed. Verification
//!                             happens at the WIRE BOUNDARY in p2p_mesh
//!                             before any frame reaches the CRDT queue.
//!
//! Identity model: every node derives an ephemeral Ed25519 keypair; the
//! 32-byte verifying key IS the node's "pubkey" (the same 32 bytes the
//! discovery beacon advertises as the host's ephemeral key). PeerId (the
//! 8-byte wire id) is bound to the pubkey via the beacon registry, so a
//! kicked pubkey cannot evade the blacklist by rotating its device string.
//! ═══════════════════════════════════════════════════════════════════════

use std::collections::{HashMap, HashSet, VecDeque};

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};

use crate::jam_crdt::{JamOp, VOTE_FLAG_UP};
use streamify_core::wire_le::{le_u32, le_u64};

/// Permission bits embedded in peer presence tokens (directive D).
pub const ALLOW_PLAYBACK_CONTROL: u8 = 0x01;
pub const ALLOW_VOLUME_CONTROL: u8 = 0x02;
pub const IS_COHOST: u8 = 0x04;

/// Bits granted to members the host has not explicitly constrained —
/// preserves the pre-governance `ControlPolicy::EVERYONE` semantics.
pub const ACL_DEFAULT_BITS: u8 = ALLOW_PLAYBACK_CONTROL | ALLOW_VOLUME_CONTROL;

/// Maximum accepted guest intents per peer per second (anti queue-spam).
pub const INTENT_RATE_LIMIT_PER_SEC: f64 = 5.0;
/// Token-bucket burst depth for the per-peer intent limiter.
pub const INTENT_RATE_BURST: f64 = 5.0;

/// Phase 2: maximum accepted vote frames per peer per second. Deliberately
/// separate from the intent bucket — votes are lighter-weight CRDT events
/// (idempotent, LWW), so a member up-voting a handful of tracks in a burst
/// must not starve their transport intents.
pub const VOTE_RATE_LIMIT_PER_SEC: f64 = 10.0;
/// Token-bucket burst depth for the per-peer vote limiter.
pub const VOTE_RATE_BURST: f64 = 10.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PivotResult {
    /// Extrapolated position in ms; safe to hard-seek.
    Ok(i64),
    /// Extrapolation reached/passed track end → normal advance instead.
    BeyondEnd,
    /// Track identity differs from the dead host's → full TRACK_CHANGE first.
    Mismatch,
}

pub struct JamGovernor;

impl JamGovernor {
    /// U3: deterministic successor election. Members are UUID strings;
    /// comparison is on LOWERCASE FIXED-WIDTH HEX — byte-order equivalent
    /// across every device, immune to locale collation.
    ///
    /// `host_lease_expired == false` → current host retains authority.
    /// Otherwise the lowest non-host member wins; `None` when the host was
    /// alone (room should end rather than elect a ghost).
    pub fn elect_successor(
        participant_ids: &[String],
        host_id: &str,
        host_lease_expired: bool,
    ) -> Option<String> {
        if !host_lease_expired {
            return Some(host_id.to_lowercase());
        }

        let mut min_id: Option<String> = None;
        for id in participant_ids {
            if id.as_str() == host_id {
                continue; // dead host excluded
            }
            let lower = id.to_lowercase();
            match &min_id {
                Some(cur) if lower >= *cur => {}
                _ => min_id = Some(lower),
            }
        }
        min_id
    }

    /// 3.3 Death Pivot: extrapolate the dead host's last known trajectory to
    /// "now" so guests experience zero discontinuity when authority moves.
    ///
    /// * U5 — `track_matches == false` → [`PivotResult::Mismatch`] (caller
    ///   must run a full TRACK_CHANGE before claiming authority).
    /// * Negative elapsed (clock skew / reordered stamps) → hold at the last
    ///   known position rather than rewinding.
    /// * U6 — result at/after track end → [`PivotResult::BeyondEnd`] (caller
    ///   performs a normal advance; pivot skipped).
    pub fn extrapolate_pivot(
        last_known_pos_ms: i64,
        last_tick_mono_ms: i64,
        current_synced_mono_ms: i64,
        track_duration_ms: i64,
        track_matches: bool,
    ) -> PivotResult {
        if !track_matches {
            return PivotResult::Mismatch;
        }

        let elapsed = current_synced_mono_ms - last_tick_mono_ms;
        if elapsed < 0 {
            return PivotResult::Ok(last_known_pos_ms.max(0));
        }

        let extrapolated = last_known_pos_ms + elapsed;

        if track_duration_ms > 0 && extrapolated >= track_duration_ms {
            PivotResult::BeyondEnd
        } else {
            PivotResult::Ok(extrapolated.max(0))
        }
    }

    /// Advisory check used by guests BEFORE calling the server RPC: am I the
    /// member the hybrid contract would prefer right now?
    /// (Server remains the sole grantor; this only avoids doomed RPC calls.)
    pub fn is_advisory_successor(
        participant_ids: &[String],
        host_id: &str,
        self_id: &str,
        self_recently_seen: bool,
    ) -> bool {
        match Self::elect_successor(participant_ids, host_id, true) {
            None => false,
            Some(successor) => successor == self_id.to_lowercase() && self_recently_seen,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MEMBERS: [&str; 4] = ["b3d1c2", "a1b2c3", "f4e5d6", "c4d5e6"];

    #[test]
    fn election_full_matrix() {
        // Healthy lease → host retains.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "b3d1c2", false),
            Some("b3d1c2".to_string())
        );

        // Expired → lowest non-host wins.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "b3d1c2", true),
            Some("a1b2c3".to_string())
        );

        // Host was already lowest → next lowest inherits.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "a1b2c3", true),
            Some("b3d1c2".to_string())
        );

        // Host was alone → room ends (no ghost authority).
        let solo = vec!["b3d1c2".to_string()];
        assert_eq!(JamGovernor::elect_successor(&solo, "b3d1c2", true), None);

        // Case-insensitivity: mixed-case entries compare byte-order-safe.
        let mixed = vec!["B3D1C2".to_string(), "A1B2C3".to_string()];
        assert_eq!(
            JamGovernor::elect_successor(&mixed, "B3D1C2", true),
            Some("a1b2c3".to_string())
        );
    }

    #[test]
    fn advisory_matches_election() {
        assert!(JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "A1B2C3",
            true
        ));
        assert!(!JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "f4e5d6",
            true
        ));
        // Stale self (not recently seen) never advises a claim.
        assert!(!JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "a1b2c3",
            false
        ));
    }

    #[test]
    fn pivot_full_matrix() {
        let duration = 200_000i64;

        // Perfect forward extrapolation.
        assert_eq!(
            JamGovernor::extrapolate_pivot(10_000, 0, 5_000, duration, true),
            PivotResult::Ok(15_000)
        );

        // Exact boundary → normal advance.
        assert_eq!(
            JamGovernor::extrapolate_pivot(195_000, 0, 5_000, duration, true),
            PivotResult::BeyondEnd
        );

        // Far past end → normal advance.
        assert_eq!(
            JamGovernor::extrapolate_pivot(180_000, 0, 30_000_000, duration, true),
            PivotResult::BeyondEnd
        );

        // Negative skew → hold at last known position.
        assert_eq!(
            JamGovernor::extrapolate_pivot(10_000, 5_000, 4_000, duration, true),
            PivotResult::Ok(10_000)
        );

        // Unknown-duration tracks (0) never report BeyondEnd.
        assert_eq!(
            JamGovernor::extrapolate_pivot(150_000, 0, 160_000, 0, true),
            PivotResult::Ok(310_000)
        );

        // Track mismatch short-circuits everything.
        assert_eq!(
            JamGovernor::extrapolate_pivot(0, 0, 0, duration, false),
            PivotResult::Mismatch
        );
    }

    #[test]
    fn wire_field_reads_degrade_to_malformed_not_panic() {
        // Audit #22 item 6 sweep lock: every truncation of every frame
        // kind (and a full-length zero frame, which exercises the wire
        // field reads themselves) must produce a clean reject — the
        // verify paths must never panic on hostile bytes.
        let mut gov = RoomGovernor::from_seed([7u8; 32]);
        for len in 0..=INTENT_FRAME_LEN {
            let frame = vec![0u8; len];
            let _ = gov.verify_intent(&frame, 0);
        }
        for len in 0..=VOTE_FRAME_LEN {
            let frame = vec![0u8; len];
            let _ = gov.verify_vote(&frame, 0);
        }
        for len in 0..=KICK_FRAME_LEN {
            let frame = vec![0u8; len];
            let _ = gov.verify_kick(&frame);
        }
        for len in 0..=ACL_FRAME_LEN {
            let frame = vec![0u8; len];
            let _ = gov.verify_acl_update(&frame);
        }
        // A full-length zero INTENT is rejected (not a valid kind and/or
        // bad key material) but — critically — does not panic.
        let zero = vec![0u8; INTENT_FRAME_LEN];
        assert!(gov.verify_intent(&zero, 0).is_err());
    }
}

// ═════════════════════════════════════════════════════════════════════
// PHASE 1 — Topology, ACLs, signed intents, kick/block, election failover
// (BEHIND.md gaps #13, #14, #18). Everything below is a pure, socket-free
// state machine: time is injected, effects are returned, and p2p_mesh
// executes them at the wire boundary.
// ═════════════════════════════════════════════════════════════════════

/// Rendering topology of the room (directive C / gap #13).
///
/// * `MultiRender`  — every device phase-locks its own renderer (the
///   pre-existing Jam invariant: N lockstep players).
/// * `SingleRender` — ONE renderer (the host's). Guests suppress local
///   render, route transport controls to the host as atomic epoch-fenced
///   signed intents, and keep only a silent low-cadence PTP presence so a
///   host migration can hard-seek instantly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Topology {
    MultiRender = 0,
    SingleRender = 1,
}

impl Topology {
    /// JNI jint mapping (frozen `NativeMeshEngine_setTopology` contract).
    pub fn from_jint(v: i32) -> Option<Topology> {
        match v {
            0 => Some(Topology::MultiRender),
            1 => Some(Topology::SingleRender),
            _ => None,
        }
    }
}

/// Transport-control kinds carried by intent frames (directive C).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum IntentKind {
    Play = 0,
    Pause = 1,
    Seek = 2,
    SkipNext = 3,
    SkipPrev = 4,
    QueueReorder = 5,
    Volume = 6,
}

impl IntentKind {
    pub fn from_u8(v: u8) -> Option<IntentKind> {
        match v {
            0 => Some(IntentKind::Play),
            1 => Some(IntentKind::Pause),
            2 => Some(IntentKind::Seek),
            3 => Some(IntentKind::SkipNext),
            4 => Some(IntentKind::SkipPrev),
            5 => Some(IntentKind::QueueReorder),
            6 => Some(IntentKind::Volume),
            _ => None,
        }
    }

    /// Which permission bit governs this intent.
    pub fn required_bit(self) -> u8 {
        match self {
            IntentKind::Volume => ALLOW_VOLUME_CONTROL,
            _ => ALLOW_PLAYBACK_CONTROL,
        }
    }
}

/// Kick directive flavors (directive D / gap #18).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum KickKind {
    /// Remove from the room; re-join permitted.
    Kick = 0,
    /// Remove AND blacklist the pubkey for the session duration.
    KickAndBan = 1,
}

// ─────────────────────────────────────────────────── wire frame layouts
// All frames little-endian; every parse is bounds-checked and returns
// `None`/`Err` on truncation — a malformed frame can never panic the
// ingress path.

/// TRANSPORT_INTENT frame (MSG_TRANSPORT_INTENT = 0x0C), 225 bytes:
///
/// ```text
/// [0]      kind u8
/// [1..9)   epoch u64            (fencing — stale epochs rejected)
/// [9..13)  nonce u32            (per-origin strictly monotonic)
/// [13..45) origin_pubkey [u8;32]
/// [45..61) body [u8;16]         (kind-specific: position_ms, volume, …)
/// [61..125) origin_sig [u8;64]  (Ed25519 over [0..61))
/// [125..129) commit_flags u32   (bit0: host-committed)
/// [129..161) host_pubkey [u8;32]
/// [161..225) host_cosig [u8;64] (Ed25519 over [0..129) by the host)
/// ```
pub const INTENT_FRAME_LEN: usize = 225;
pub const INTENT_COMMITTED_FLAG: u32 = 0x0000_0001;

/// KICK_DIRECTIVE frame (MSG_KICK_DIRECTIVE = 0x0D), 141 bytes:
///
/// ```text
/// [0]      kick_kind u8
/// [1..5)   reason_code u32
/// [5..13)  epoch u64
/// [13..45) target_pubkey [u8;32]
/// [45..77) host_pubkey [u8;32]
/// [77..141) host_sig [u8;64]    (Ed25519 over [0..77))
/// ```
pub const KICK_FRAME_LEN: usize = 141;

/// ACL_UPDATE frame (MSG_ACL_UPDATE = 0x0E), 142 bytes:
///
/// ```text
/// [0]      reserved u8
/// [1..5)   acl_version u32
/// [5..13)  epoch u64
/// [13..45) target_pubkey [u8;32]
/// [45]     permission_bits u8   (ALLOW_* bits)
/// [46..78) host_pubkey [u8;32]
/// [78..142) host_sig [u8;64]    (Ed25519 over [0..78))
/// ```
pub const ACL_FRAME_LEN: usize = 142;

/// VOTE frame (MSG_VOTE_OP = 0x10, Phase 2 directive A), 152 bytes:
///
/// ```text
/// [0..48)   JamOp (op_type = 4; policy_flags bit0 = upvote / retraction;
///            target_add_op_id = the voted queue element)
/// [48..80)  voter_pubkey [u8;32]  (Ed25519 verifying key)
/// [80..88)  epoch u64             (room epoch fence)
/// [88..152) voter_sig [u8;64]     (Ed25519 over [0..88))
/// ```
///
/// Verified at the WIRE BOUNDARY (bounds → signature → blacklist → epoch
/// fence → vote rate bucket) before the op is merged into the vote ledger
/// keyed by the 32-byte pubkey — exactly the discipline TRANSPORT_INTENT
/// established in Phase 1. Replay protection comes from the gossip
/// engine's (origin, sequence) dedupe in front of the boundary.
pub const VOTE_FRAME_LEN: usize = 152;

/// Why a governance frame was rejected at the wire boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    /// Slice shorter than the frame kind requires (malformed / hostile).
    Malformed,
    /// Ed25519 signature mismatch — unsigned or forged frame.
    BadSignature,
    /// Frame epoch does not match the room epoch (stale or future).
    EpochFence,
    /// Signer is on the session blacklist.
    Blacklisted,
    /// Signer lacks the permission bit for this intent kind.
    AclDenied,
    /// Per-peer token bucket exhausted (5 intents/sec).
    RateLimited,
    /// Nonce not strictly greater than the last accepted nonce.
    Replayed,
    /// Host-directive signed by a key that is not the room host.
    NotHost,
    /// Unknown intent kind byte.
    UnknownKind,
}

/// Verified (fully authorized) transport intent, ready to be applied.
#[derive(Debug, Clone)]
pub struct VerifiedIntent {
    pub kind: IntentKind,
    pub epoch: u64,
    pub nonce: u32,
    pub origin_pubkey: [u8; 32],
    pub body: [u8; 16],
    /// True when the frame carries a valid host countersignature.
    pub committed: bool,
}

/// Verified host kick directive.
#[derive(Debug, Clone)]
pub struct VerifiedKick {
    pub kick_kind: KickKind,
    pub reason_code: u32,
    pub epoch: u64,
    pub target_pubkey: [u8; 32],
}

/// Verified host ACL update.
#[derive(Debug, Clone)]
pub struct VerifiedAclUpdate {
    pub acl_version: u32,
    pub epoch: u64,
    pub target_pubkey: [u8; 32],
    pub permission_bits: u8,
}

/// Verified democratic vote (Phase 2 directive A): the sealed JamOp plus
/// the voter's Ed25519 pubkey the ledger keys on.
#[derive(Debug, Clone)]
pub struct VerifiedVote {
    pub op: JamOp,
    pub voter_pubkey: [u8; 32],
    pub epoch: u64,
}

/// Host-authority event surfaced to the app layer via the mesh.
#[derive(Debug, Clone)]
pub enum GovernanceEvent {
    /// This node was kicked (optionally banned) by the host.
    Kicked { reason_code: u32, banned: bool },
    /// Host lease expired; a successor was elected and the epoch advanced.
    HostMigrated { new_host: [u8; 32], epoch: u64 },
    /// An ACL update targeting this node changed its permission bits.
    AclChanged { permission_bits: u8 },
    /// A blacklisted pubkey attempted to (re-)join the mesh.
    BlacklistedJoinAttempt { pubkey: [u8; 32] },
}

// ───────────────────────────────────────────────────────── token bucket

/// Classic token bucket, `now` injected (pure / unit-testable).
#[derive(Debug, Clone)]
pub struct TokenBucket {
    tokens: f64,
    last_ns: i64,
    capacity: f64,
    refill_per_sec: f64,
}

impl TokenBucket {
    pub fn new(capacity: f64, refill_per_sec: f64, now_ns: i64) -> Self {
        TokenBucket {
            tokens: capacity,
            last_ns: now_ns,
            capacity,
            refill_per_sec,
        }
    }

    pub fn try_consume(&mut self, now_ns: i64) -> bool {
        let elapsed_s = (now_ns.saturating_sub(self.last_ns)) as f64 / 1e9;
        if elapsed_s > 0.0 {
            self.tokens = (self.tokens + elapsed_s * self.refill_per_sec).min(self.capacity);
            self.last_ns = now_ns;
        }
        if self.tokens >= 1.0 {
            self.tokens -= 1.0;
            true
        } else {
            false
        }
    }
}

// ─────────────────────────────────────────────────────── the governor

/// Point-in-time governance snapshot (telemetry / tests / JNI).
#[derive(Debug, Clone)]
pub struct GovernanceSnapshot {
    pub me_pubkey: [u8; 32],
    pub host_pubkey: [u8; 32],
    pub epoch: u64,
    pub topology: Topology,
    pub member_count: usize,
    pub capacity: u8,
    pub blacklist_len: usize,
    pub is_host: bool,
    /// Highest epoch ever observed in a host claim (anti-downgrade).
    pub max_observed_epoch: u64,
}

/// Pure room-governance state machine. One instance lives inside every
/// [`crate::p2p_mesh::MeshNode`]; all verification methods are called from
/// the datagram ingress path BEFORE any frame is applied or dispatched.
pub struct RoomGovernor {
    signing: SigningKey,
    /// Whether this node started the room (self-declared host authority).
    declared_host: bool,
    /// Whether this node adopted a foreign host claim (joined a governed
    /// room) — guests advertise the room descriptor too, so every beacon
    /// populates the mesh-wide pubkey registry elections depend on.
    room_adopted: bool,
    /// Explicit advertisement suppression (stopLanBeacon).
    beacon_suppressed: bool,
    host_pubkey: [u8; 32],
    epoch: u64,
    /// Highest epoch seen in beacons / directives (monotonic anti-downgrade).
    max_observed_epoch: u64,
    topology: Topology,
    capacity: u8,
    /// Explicit per-member permission bits (absent → [`ACL_DEFAULT_BITS`]).
    acl: HashMap<[u8; 32], u8>,
    /// Session-duration blacklist of pubkeys (kick + ban).
    blacklist: HashSet<[u8; 32]>,
    /// PeerId-level blacklist for peers whose pubkey is unknown (legacy).
    peer_blacklist: HashSet<u64>,
    /// Per-origin intent rate limiter (directive: 5 ops/sec per peer).
    rate: HashMap<[u8; 32], TokenBucket>,
    /// Phase 2: per-origin VOTE rate limiter (10 votes/sec per peer).
    vote_rate: HashMap<[u8; 32], TokenBucket>,
    /// Anti-replay watermark: last accepted nonce per origin pubkey.
    last_nonce: HashMap<[u8; 32], u32>,
    /// Events queued for the app layer (drained by the mesh).
    events: VecDeque<GovernanceEvent>,
    /// Monotonic nonce source for locally emitted intents.
    local_nonce: u32,
    /// ACL table version, bumped on every host update.
    acl_version: u32,
}

impl RoomGovernor {
    /// Deterministic governor for tests: the keypair derives from `seed`.
    pub fn from_seed(seed: [u8; 32]) -> Self {
        let signing = SigningKey::from_bytes(&seed);
        let host = signing.verifying_key().to_bytes();
        RoomGovernor {
            signing,
            declared_host: false,
            room_adopted: false,
            beacon_suppressed: false,
            host_pubkey: host,
            epoch: 1,
            max_observed_epoch: 1,
            topology: Topology::MultiRender,
            capacity: 32,
            acl: HashMap::new(),
            blacklist: HashSet::new(),
            peer_blacklist: HashSet::new(),
            rate: HashMap::new(),
            vote_rate: HashMap::new(),
            last_nonce: HashMap::new(),
            events: VecDeque::new(),
            local_nonce: 0,
            acl_version: 0,
        }
    }

    pub fn pubkey(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    pub fn is_host(&self) -> bool {
        self.host_pubkey == self.pubkey()
    }

    pub fn epoch(&self) -> u64 {
        self.epoch
    }

    pub fn topology(&self) -> Topology {
        self.topology
    }

    pub fn capacity(&self) -> u8 {
        self.capacity
    }

    pub fn set_capacity(&mut self, capacity: u8) {
        self.capacity = capacity;
    }

    pub fn set_topology(&mut self, topology: Topology) {
        self.topology = topology;
    }

    /// Self-declares this node the room host (room-creation semantics:
    /// the device that starts the LAN beacon / room owns authority).
    pub fn declare_host(&mut self) {
        self.host_pubkey = self.pubkey();
        self.declared_host = true;
        self.beacon_suppressed = false;
        self.epoch = self.epoch.max(1);
        self.max_observed_epoch = self.max_observed_epoch.max(self.epoch);
    }

    /// Room advertisement data for beacons: `Some((host_pubkey, epoch))`
    /// when this node participates in a governed room (declared host or
    /// guest that adopted a claim) and advertisement is not suppressed.
    /// GUESTS advertise too — the beacon sender binding is what populates
    /// the pubkey registry, and a converged registry is exactly what makes
    /// host-death elections deterministic on every survivor.
    pub fn advertise_room(&self) -> Option<([u8; 32], u64)> {
        if self.beacon_suppressed || !(self.declared_host || self.room_adopted) {
            return None;
        }
        Some((self.host_pubkey, self.epoch))
    }

    /// Suppresses room advertisement in beacons (stopLanBeacon).
    pub fn set_beacon_suppressed(&mut self, suppressed: bool) {
        self.beacon_suppressed = suppressed;
    }

    /// Beacon/host-claim ingestion: a non-declaring node adopts the highest
    /// observed host claim. Claims are advisory (unsigned beacons); real
    /// authority still requires the host signing key to commit intents or
    /// issue kicks — a forged claim can only misdirect, never authorize.
    pub fn observe_host_claim(&mut self, host_pubkey: [u8; 32], epoch: u64) {
        if self.declared_host {
            return; // our own claim stands until death-election says otherwise
        }
        if epoch >= self.max_observed_epoch {
            self.max_observed_epoch = epoch;
            if epoch >= self.epoch {
                if host_pubkey != self.pubkey() {
                    self.room_adopted = true;
                }
                self.host_pubkey = host_pubkey;
                self.epoch = epoch;
            }
        }
    }

    /// Registers (or refreshes) a member pubkey ↔ peer-id binding.
    pub fn register_member(&mut self, pubkey: [u8; 32], peer_id: u64) {
        // A blacklisted pubkey can never re-enter the registry.
        if self.blacklist.contains(&pubkey) {
            self.events
                .push_back(GovernanceEvent::BlacklistedJoinAttempt { pubkey });
            return;
        }
        let _ = peer_id; // binding table lives in the mesh; kept for API symmetry
    }

    pub fn is_blacklisted_pubkey(&self, pubkey: &[u8; 32]) -> bool {
        self.blacklist.contains(pubkey)
    }

    pub fn is_blacklisted_peer(&self, peer_id: u64) -> bool {
        self.peer_blacklist.contains(&peer_id)
    }

    /// PeerId-level eviction used by kick ingestion so that legacy
    /// (pubkey-less) re-join attempts are shut out as well.
    pub fn blacklist_peer_id(&mut self, peer_id: u64) {
        self.peer_blacklist.insert(peer_id);
    }

    /// Blacklists both the pubkey and (best-effort) the peer id.
    pub fn blacklist(&mut self, pubkey: [u8; 32], peer_id: Option<u64>) {
        self.blacklist.insert(pubkey);
        if let Some(p) = peer_id {
            self.peer_blacklist.insert(p);
        }
        self.acl.remove(&pubkey);
        self.rate.remove(&pubkey);
        self.vote_rate.remove(&pubkey);
    }

    /// Host-side ACL mutation (local; the wire frame is built separately).
    pub fn set_member_acl(&mut self, pubkey: [u8; 32], bits: u8) -> u32 {
        self.acl.insert(pubkey, bits);
        self.acl_version = self.acl_version.wrapping_add(1);
        self.acl_version
    }

    /// Permission bits in force for a member.
    pub fn member_bits(&self, pubkey: &[u8; 32]) -> u8 {
        self.acl.get(pubkey).copied().unwrap_or(ACL_DEFAULT_BITS)
    }

    /// Takes the next strictly-monotonic local nonce.
    fn next_nonce(&mut self) -> u32 {
        self.local_nonce = self.local_nonce.wrapping_add(1).max(1);
        self.local_nonce
    }

    /// Drains queued governance events (mesh delivers them to subscribers).
    pub fn drain_events(&mut self) -> Vec<GovernanceEvent> {
        self.events.drain(..).collect()
    }

    // ── frame construction (origin side) ────────────────────────────────

    /// Builds a signed, epoch-fenced transport intent from this node.
    pub fn build_intent(&mut self, kind: IntentKind, body: [u8; 16]) -> Vec<u8> {
        let nonce = self.next_nonce();
        let mut f = vec![0u8; INTENT_FRAME_LEN];
        f[0] = kind as u8;
        f[1..9].copy_from_slice(&self.epoch.to_le_bytes());
        f[9..13].copy_from_slice(&nonce.to_le_bytes());
        f[13..45].copy_from_slice(&self.pubkey());
        f[45..61].copy_from_slice(&body);
        let sig = self.signing.sign(&f[..61]);
        f[61..125].copy_from_slice(&sig.to_bytes());
        // commit_flags/host_pubkey/host_cosig stay zero: uncommitted intent.
        f
    }

    /// Pure signature check (no state mutation): shared by the full gate
    /// path and the commit path so the nonce/rate gates are consumed
    /// exactly once per frame.
    #[allow(clippy::type_complexity)] // tuple mirrors the intent wire fields
    fn verify_intent_signature(
        &self,
        frame: &[u8],
    ) -> Result<(IntentKind, u64, u32, [u8; 32], [u8; 16]), RejectReason> {
        if frame.len() != INTENT_FRAME_LEN {
            return Err(RejectReason::Malformed);
        }
        let kind = IntentKind::from_u8(frame[0]).ok_or(RejectReason::UnknownKind)?;
        let epoch = le_u64(&frame[1..9]).ok_or(RejectReason::Malformed)?;
        let nonce = le_u32(&frame[9..13]).ok_or(RejectReason::Malformed)?;
        let mut origin = [0u8; 32];
        origin.copy_from_slice(&frame[13..45]);
        let mut body = [0u8; 16];
        body.copy_from_slice(&frame[45..61]);
        let mut sig_bytes = [0u8; 64];
        sig_bytes.copy_from_slice(&frame[61..125]);
        let verifying = VerifyingKey::from_bytes(&origin).map_err(|_| RejectReason::Malformed)?;
        verifying
            .verify(&frame[..61], &Signature::from_bytes(&sig_bytes))
            .map_err(|_| RejectReason::BadSignature)?;
        Ok((kind, epoch, nonce, origin, body))
    }

    /// Host-side: countersigns an already-verified guest intent, producing
    /// the committed frame that safely rides the gossip tree. The caller
    /// (`governance_ingest_intent`) has ALREADY run the full gate path —
    /// re-running it here would consume the anti-replay nonce and the
    /// rate token a second time and reject the very intent being
    /// committed; only the signature itself is re-checked.
    pub fn commit_intent(&mut self, frame: &[u8]) -> Option<Vec<u8>> {
        if frame.len() != INTENT_FRAME_LEN || !self.is_host() {
            return None;
        }
        // The origin signature must itself be valid before we countersign.
        if self.verify_intent_signature(frame).is_err() {
            return None;
        }
        let mut committed = frame.to_vec();
        let flags = u32::from_le_bytes(committed[125..129].try_into().ok()?);
        committed[125..129].copy_from_slice(&(flags | INTENT_COMMITTED_FLAG).to_le_bytes());
        committed[129..161].copy_from_slice(&self.host_pubkey);
        let sig = self.signing.sign(&committed[..129]);
        committed[161..225].copy_from_slice(&sig.to_bytes());
        Some(committed)
    }

    /// Builds the signed host kick directive.
    pub fn build_kick(&self, kind: KickKind, reason_code: u32, target: [u8; 32]) -> Vec<u8> {
        let mut f = vec![0u8; KICK_FRAME_LEN];
        f[0] = kind as u8;
        f[1..5].copy_from_slice(&reason_code.to_le_bytes());
        f[5..13].copy_from_slice(&self.epoch.to_le_bytes());
        f[13..45].copy_from_slice(&target);
        f[45..77].copy_from_slice(&self.host_pubkey);
        let sig = self.signing.sign(&f[..77]);
        f[77..141].copy_from_slice(&sig.to_bytes());
        f
    }

    /// Builds the signed host ACL update.
    pub fn build_acl_update(&self, target: [u8; 32], bits: u8) -> Vec<u8> {
        let mut f = vec![0u8; ACL_FRAME_LEN];
        f[1..5].copy_from_slice(&self.acl_version.wrapping_add(1).to_le_bytes());
        f[5..13].copy_from_slice(&self.epoch.to_le_bytes());
        f[13..45].copy_from_slice(&target);
        f[45] = bits;
        f[46..78].copy_from_slice(&self.host_pubkey);
        let sig = self.signing.sign(&f[..78]);
        f[78..142].copy_from_slice(&sig.to_bytes());
        f
    }

    /// Phase 2 (directive A): builds the signed, epoch-fenced vote frame
    /// for a locally cast vote. `op` must be an already-sealed Vote op
    /// (op_type = 4, direction in policy_flags bit0).
    pub fn build_vote(&mut self, op: &JamOp) -> Vec<u8> {
        let mut f = vec![0u8; VOTE_FRAME_LEN];
        f[..48].copy_from_slice(&op.to_bytes());
        f[48..80].copy_from_slice(&self.pubkey());
        f[80..88].copy_from_slice(&self.epoch.to_le_bytes());
        let sig = self.signing.sign(&f[..88]);
        f[88..152].copy_from_slice(&sig.to_bytes());
        f
    }

    /// Phase 2: local-origin vote admission. The caster consumes the SAME
    /// per-pubkey bucket the receivers will, so a throttled flood never
    /// enters ANY ledger — the caster's own included — and replicas stay
    /// consistent on vote counts.
    pub fn try_local_vote(&mut self, now_ns: i64) -> bool {
        let me = self.pubkey();
        let bucket = self
            .vote_rate
            .entry(me)
            .or_insert_with(|| TokenBucket::new(VOTE_RATE_BURST, VOTE_RATE_LIMIT_PER_SEC, now_ns));
        bucket.try_consume(now_ns)
    }

    /// Phase 2 (directive A): wire-boundary verification of a vote frame.
    /// Gate order mirrors [`Self::verify_intent`]: bounds + sealed op →
    /// Ed25519 signature → blacklist → epoch fence → vote rate bucket.
    pub fn verify_vote(&mut self, frame: &[u8], now_ns: i64) -> Result<VerifiedVote, RejectReason> {
        if frame.len() != VOTE_FRAME_LEN {
            return Err(RejectReason::Malformed);
        }
        let Some(op) = JamOp::from_bytes(&frame[..48]) else {
            return Err(RejectReason::Malformed);
        };
        if op.op_type != 4 {
            return Err(RejectReason::UnknownKind);
        }
        // Bit0 is the direction bit; the rest must stay clean.
        if op.policy_flags & !VOTE_FLAG_UP != 0 {
            return Err(RejectReason::Malformed);
        }
        // Defense in depth: the sealed op's own FNV checksum must also
        // hold (it protects the 48-byte op whenever it travels outside
        // this frame; a mismatch is a malformed frame regardless of the
        // Ed25519 layer that follows).
        if !op.is_valid() {
            return Err(RejectReason::Malformed);
        }
        let mut voter = [0u8; 32];
        voter.copy_from_slice(&frame[48..80]);
        let epoch = le_u64(&frame[80..88]).ok_or(RejectReason::Malformed)?;
        let mut sig_bytes = [0u8; 64];
        sig_bytes.copy_from_slice(&frame[88..152]);

        let verifying = VerifyingKey::from_bytes(&voter).map_err(|_| RejectReason::Malformed)?;
        verifying
            .verify(&frame[..88], &Signature::from_bytes(&sig_bytes))
            .map_err(|_| RejectReason::BadSignature)?;

        if self.blacklist.contains(&voter) {
            return Err(RejectReason::Blacklisted);
        }
        if epoch != self.epoch {
            return Err(RejectReason::EpochFence);
        }
        let bucket = self
            .vote_rate
            .entry(voter)
            .or_insert_with(|| TokenBucket::new(VOTE_RATE_BURST, VOTE_RATE_LIMIT_PER_SEC, now_ns));
        if !bucket.try_consume(now_ns) {
            return Err(RejectReason::RateLimited);
        }

        Ok(VerifiedVote {
            op,
            voter_pubkey: voter,
            epoch,
        })
    }

    // ── frame verification (wire boundary) ─────────────────────────────

    /// Verifies a TRANSPORT_INTENT frame against every governance gate:
    /// bounds → signature → blacklist → epoch fence → replay → rate → ACL.
    pub fn verify_intent(
        &mut self,
        frame: &[u8],
        now_ns: i64,
    ) -> Result<VerifiedIntent, RejectReason> {
        // 1. Origin authenticity: Ed25519 over [0..61) — plus the wire
        //    field parse (shared with the commit path).
        let (kind, epoch, nonce, origin, body) = self.verify_intent_signature(frame)?;

        // 2. Blacklist.
        if self.blacklist.contains(&origin) {
            return Err(RejectReason::Blacklisted);
        }

        // 3. Epoch fence (exact match — stale AND future both rejected).
        if epoch != self.epoch {
            return Err(RejectReason::EpochFence);
        }

        // 4. Anti-replay: strictly monotonic nonce per origin.
        if let Some(&last) = self.last_nonce.get(&origin) {
            if nonce <= last {
                return Err(RejectReason::Replayed);
            }
        }
        self.last_nonce.insert(origin, nonce);

        // 5. Per-peer rate limit (5 intents/sec, burst 5).
        let bucket = self.rate.entry(origin).or_insert_with(|| {
            TokenBucket::new(INTENT_RATE_BURST, INTENT_RATE_LIMIT_PER_SEC, now_ns)
        });
        if !bucket.try_consume(now_ns) {
            return Err(RejectReason::RateLimited);
        }

        // 6. Host countersignature (commit) check.
        let flags = le_u32(&frame[125..129]).ok_or(RejectReason::Malformed)?;
        let mut host_pk = [0u8; 32];
        host_pk.copy_from_slice(&frame[129..161]);
        let mut cosig = [0u8; 64];
        cosig.copy_from_slice(&frame[161..225]);
        let mut committed = false;
        if flags & INTENT_COMMITTED_FLAG != 0 {
            if host_pk != self.host_pubkey {
                return Err(RejectReason::NotHost);
            }
            let host_vk =
                VerifyingKey::from_bytes(&host_pk).map_err(|_| RejectReason::Malformed)?;
            host_vk
                .verify(&frame[..129], &Signature::from_bytes(&cosig))
                .map_err(|_| RejectReason::BadSignature)?;
            committed = true;
        }

        // 7. ACL — hosts and co-hosts pass everything; everyone else needs
        //    the bit matching the intent kind. Only enforced for intents we
        //    will actually apply (uncommitted guest→host traffic is checked
        //    by the host; committed traffic was vetted at commit time).
        if !committed && !self.is_host() {
            // Non-host nodes only apply committed intents in SingleRender;
            // in MultiRender every node applies locally, so enforce here too.
            let bits = self.member_bits(&origin);
            let host_or_cohost =
                self.is_host() && origin == self.host_pubkey || bits & IS_COHOST != 0;
            if !host_or_cohost && bits & kind.required_bit() == 0 {
                return Err(RejectReason::AclDenied);
            }
        } else if committed {
            // Committed frames were ACL-checked by the host before signing;
            // still enforce locally-known restrictions as defense-in-depth.
            let bits = self.member_bits(&origin);
            if bits & IS_COHOST == 0
                && bits & kind.required_bit() == 0
                && origin != self.host_pubkey
            {
                return Err(RejectReason::AclDenied);
            }
        }

        Ok(VerifiedIntent {
            kind,
            epoch,
            nonce,
            origin_pubkey: origin,
            body,
            committed,
        })
    }

    /// Verifies a host-signed KICK_DIRECTIVE.
    pub fn verify_kick(&mut self, frame: &[u8]) -> Result<VerifiedKick, RejectReason> {
        if frame.len() != KICK_FRAME_LEN {
            return Err(RejectReason::Malformed);
        }
        let kick_kind = match frame[0] {
            0 => KickKind::Kick,
            1 => KickKind::KickAndBan,
            _ => return Err(RejectReason::UnknownKind),
        };
        let reason_code = le_u32(&frame[1..5]).ok_or(RejectReason::Malformed)?;
        let epoch = le_u64(&frame[5..13]).ok_or(RejectReason::Malformed)?;
        let mut target = [0u8; 32];
        target.copy_from_slice(&frame[13..45]);
        let mut host_pk = [0u8; 32];
        host_pk.copy_from_slice(&frame[45..77]);
        let mut sig_bytes = [0u8; 64];
        sig_bytes.copy_from_slice(&frame[77..141]);

        if host_pk != self.host_pubkey {
            return Err(RejectReason::NotHost);
        }
        let host_vk = VerifyingKey::from_bytes(&host_pk).map_err(|_| RejectReason::Malformed)?;
        host_vk
            .verify(&frame[..77], &Signature::from_bytes(&sig_bytes))
            .map_err(|_| RejectReason::BadSignature)?;
        if epoch < self.epoch {
            // Stale kick from a pre-failover host: reject so a deposed host
            // cannot evict members after losing authority.
            return Err(RejectReason::EpochFence);
        }
        if epoch > self.max_observed_epoch {
            self.max_observed_epoch = epoch;
        }
        Ok(VerifiedKick {
            kick_kind,
            reason_code,
            epoch,
            target_pubkey: target,
        })
    }

    /// Verifies a host-signed ACL_UPDATE.
    pub fn verify_acl_update(&mut self, frame: &[u8]) -> Result<VerifiedAclUpdate, RejectReason> {
        if frame.len() != ACL_FRAME_LEN {
            return Err(RejectReason::Malformed);
        }
        let acl_version = le_u32(&frame[1..5]).ok_or(RejectReason::Malformed)?;
        let epoch = le_u64(&frame[5..13]).ok_or(RejectReason::Malformed)?;
        let mut target = [0u8; 32];
        target.copy_from_slice(&frame[13..45]);
        let bits = frame[45];
        let mut host_pk = [0u8; 32];
        host_pk.copy_from_slice(&frame[46..78]);
        let mut sig_bytes = [0u8; 64];
        sig_bytes.copy_from_slice(&frame[78..142]);

        if host_pk != self.host_pubkey {
            return Err(RejectReason::NotHost);
        }
        let host_vk = VerifyingKey::from_bytes(&host_pk).map_err(|_| RejectReason::Malformed)?;
        host_vk
            .verify(&frame[..78], &Signature::from_bytes(&sig_bytes))
            .map_err(|_| RejectReason::BadSignature)?;
        if epoch < self.epoch {
            return Err(RejectReason::EpochFence);
        }
        Ok(VerifiedAclUpdate {
            acl_version,
            epoch,
            target_pubkey: target,
            permission_bits: bits,
        })
    }

    /// Applies a verified kick: local blacklist state + self-removal event.
    pub fn apply_kick(&mut self, kick: &VerifiedKick) {
        let banned = kick.kick_kind == KickKind::KickAndBan;
        if kick.target_pubkey == self.pubkey() {
            self.events.push_back(GovernanceEvent::Kicked {
                reason_code: kick.reason_code,
                banned,
            });
        }
        if banned {
            self.blacklist.insert(kick.target_pubkey);
            self.acl.remove(&kick.target_pubkey);
            self.rate.remove(&kick.target_pubkey);
        } else {
            // Plain kick: membership revoked until re-join; no blacklist.
            self.acl.remove(&kick.target_pubkey);
        }
    }

    /// Applies a verified ACL update (monotonic in acl_version).
    pub fn apply_acl_update(&mut self, update: &VerifiedAclUpdate) {
        self.acl
            .insert(update.target_pubkey, update.permission_bits);
        self.acl_version = self.acl_version.max(update.acl_version);
        if update.target_pubkey == self.pubkey() {
            self.events.push_back(GovernanceEvent::AclChanged {
                permission_bits: update.permission_bits,
            });
        }
    }

    /// Host-death failover (gap #11's "leader election failover"): runs the
    /// v3 [`JamGovernor::elect_successor`] over the known member pubkeys
    /// (fixed-width lowercase hex — byte-order safe, exactly the U3 rule).
    ///
    /// Every survivor executes this on its own converged view and reaches
    /// the SAME successor; the epoch advances by exactly one, so intents
    /// fenced to the dead host's epoch die with it.
    pub fn elect_on_host_death(&mut self, member_pubkeys: &[[u8; 32]]) -> Option<[u8; 32]> {
        let me = self.pubkey();
        let host_hex = hex_string(&self.host_pubkey);
        let members: Vec<String> = member_pubkeys
            .iter()
            .map(hex_string)
            .chain(std::iter::once(hex_string(&me)))
            .collect();
        let successor = JamGovernor::elect_successor(&members, &host_hex, true)?;
        let succ_bytes = bytes_from_hex(&successor)?;
        if succ_bytes == me {
            // We won the election: adopt authority and advance the epoch.
            self.host_pubkey = me;
            self.declared_host = true;
        } else {
            self.host_pubkey = succ_bytes;
        }
        self.epoch = self.epoch.saturating_add(1);
        self.max_observed_epoch = self.max_observed_epoch.max(self.epoch);
        self.events.push_back(GovernanceEvent::HostMigrated {
            new_host: succ_bytes,
            epoch: self.epoch,
        });
        Some(succ_bytes)
    }

    pub fn snapshot(&self) -> GovernanceSnapshot {
        GovernanceSnapshot {
            me_pubkey: self.pubkey(),
            host_pubkey: self.host_pubkey,
            epoch: self.epoch,
            topology: self.topology,
            member_count: self.acl.len(),
            capacity: self.capacity,
            blacklist_len: self.blacklist.len(),
            is_host: self.is_host(),
            max_observed_epoch: self.max_observed_epoch,
        }
    }
}

/// Lowercase fixed-width hex (the U3 election ordering domain).
pub fn hex_string(bytes: &[u8; 32]) -> String {
    let mut s = String::with_capacity(64);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

fn bytes_from_hex(s: &str) -> Option<[u8; 32]> {
    let mut out = [0u8; 32];
    if s.len() != 64 {
        return None;
    }
    let bytes = s.as_bytes();
    for i in 0..32 {
        let hi = (bytes[i * 2] as char).to_digit(16)?;
        let lo = (bytes[i * 2 + 1] as char).to_digit(16)?;
        out[i] = ((hi << 4) | lo) as u8;
    }
    Some(out)
}
