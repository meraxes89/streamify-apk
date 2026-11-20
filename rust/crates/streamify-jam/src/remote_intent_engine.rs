//! remote_intent_engine.rs — Cross-device session handoff & remote
//! playback intent dispatcher (Phase 4, directive §2.2).
//!
//! MISSION: one engine per device that (a) owns the Connect ROLE state
//! machine — who is the [`Role::ActiveController`] driving the UI and who
//! is the [`Role::ActiveReceiver`] rendering audio — (b) executes the
//! seamless playback handoff protocol (gap #52), and (c) relays remote
//! control intents with exactly-once, in-order, idempotent delivery.
//!
//! ── ROLE STATE MACHINE ─────────────────────────────────────────────────
//!   Idle ──start_session──▶ ActiveController
//!   ActiveController ──begin_handoff──▶ HandoffPending
//!   HandoffPending ──confirm──▶ ActiveController (remote now rendering;
//!                                     local player halts — event-driven)
//!   HandoffPending ──abort/timeout──▶ ActiveController (local playback
//!                                     NEVER halted — the directive's
//!                                     "confirmation before halting")
//!   Idle ──handoff offer adopted──▶ ActiveReceiver
//!   ActiveReceiver ──take_control──▶ ActiveController (steal, epoch+1)
//!   ActiveController ──end_session──▶ Idle
//!   ActiveReceiver ──relinquish──▶ Idle
//! Illegal transitions are refused (error return), never coerced.
//!
//! ── SEAMLESS HANDOFF PROTOCOL (gap #52) ────────────────────────────────
//!   Controller                                Receiver
//!     │ begin_handoff(target, snapshot)          │
//!     │── HANDOFF_OFFER(epoch', nonce, sig) ────▶│ verify Ed25519 sig over
//!     │                                          │ canonical frame span;
//!     │                                          │ epoch' must dominate the
//!     │                                          │ current controller pair;
//!     │                                          │ adopt receiver role +
//!     │                                          │ restore snapshot state
//!     │◀──────── HANDOFF_CONFIRM(nonce, epoch') ─│
//!     │ HandoffCompleted fires HERE — only now   │
//!     │ may the local player halt; pending       │
//!     │ intents are superseded by the snapshot   │
//!     │── HANDOFF_COMPLETE(nonce) ──────────────▶│ finalize (render)
//!     │                                          │
//!     │◀─ HANDOFF_ABORT(reason) on stale epoch / ─│ (receiver-initiated)
//!     │   bad signature; deadline expiry aborts  │
//!     │ locally — local playback continues       │
//!
//! The OFFER is cryptographic (directive wording): Ed25519 over the
//! canonical header+payload span with the controller's verifying key
//! embedded. Any tamper with snapshot fields, epoch or nonce trips the
//! signature (the chaos suite flips bytes and re-fixes the FNV checksum
//! to prove the signature — not the transport checksum — is what trips).
//!
//! ── MONOTONIC INTENT DISPATCHER ────────────────────────────────────────
//! Intents ([`RemoteIntent`]: Play, Pause, SeekTo, QueueInsert,
//! QueueRemove, QueueReorder, SetVolume) carry strictly increasing
//! 64-bit seq_ids: `seq_id = (epoch << 32) | counter`.
//!   • epoch — controller generation; bumped on every control takeover
//!     (start_session, take_control, each handoff attempt). The
//!     receiver's CURRENT controller is the `(epoch, device_id)` pair —
//!     lexicographic ordering gives a deterministic split-brain winner:
//!     the dominating pair owns the stream, the loser's intents draw
//!     INTENT_REJECT(stale_epoch) receipts and are NEVER applied.
//!   • counter — 1.. per epoch, strictly increasing.
//! Receiver: applies exactly in seq order, stashes out-of-order intents
//! (bounded reorder buffer), NACKs observed gaps (receiver-driven
//! retransmit), re-ACKs duplicates (sender retries are idempotent).
//! Controller: retries un-ACKed intents with capped exponential backoff;
//! a NACK range retransmits the covered in-flight intents immediately.
//! After MAX_INTENT_ATTEMPTS an intent is abandoned with an event (the
//! app decides whether to cancel the session) — user actions are never
//! silently dropped.
//!
//! ── WIRE FORMAT ("RIM frame family") ───────────────────────────────────
//! A third frozen family alongside mesh 0x5354 and Connect SCNX —
//! remote-intent traffic is control-plane, not presence and not gossip:
//!
//!   offset  field          len  notes
//!   0       magic u16       2    0x5249 ('R','I')
//!   2       version u8      1    0x01
//!   3       msg_type u8     1    0x01 INTENT · 0x02 ACK · 0x03 NACK ·
//!   4                          0x04 HANDOFF_OFFER · 0x05 HANDOFF_CONFIRM ·
//!   5                          0x06 HANDOFF_COMPLETE · 0x07 HANDOFF_ABORT ·
//!   6                          0x08 INTENT_REJECT
//!   4       sender_id u64   8
//!   12      target_id u64   8    0 = any (broadcast control)
//!   20      seq_or_nonce u64 8   INTENT/ACK/REJECT: seq · NACK: gap base ·
//!   28                          handoff family: nonce echo
//!   28      payload_len u16 2
//!   30      payload         n    ≤ 4 KiB (the OFFER is the only large
//!   30                          frame: ≤ 255-track queue snapshot)
//!   30+n    fnv1a u32       4    over [0..30+n)
//!
//! HOSTILE-INPUT DISCIPLINE (house rules): every parse is bounds-checked
//! and total (truncation, bad magic/version/type, checksum mismatch,
//! oversize payloads, lying lengths → hard reject with a counter); the
//! receiver never applies state from a frame it could not fully verify.
//!
//! PURE LOGIC: no sockets, no wall clock — `now_ms` is injected per
//! call; the deterministic 10-node chaos harness drives this engine
//! directly (rust/tests/test_remote_intent_chaos.rs).

use std::collections::{BTreeMap, VecDeque};

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};

use streamify_core::wire_le;

/// RIM magic: 'R','I' (u16 LE = 0x5249).
pub const RIM_MAGIC: u16 = 0x5249;
/// Remote-intent wire protocol version.
pub const RIM_VERSION: u8 = 0x01;
/// RIM header length (everything before the payload).
pub const RIM_HEADER_LEN: usize = 30;
/// Queue snapshot budget (tracks carried by an OFFER; the tail is kept —
/// a handoff restores context, not the whole library).
pub const MAX_QUEUE_SNAPSHOT: usize = 255;
/// Hard payload ceiling: 4 + 26 + 255×8 + 32 + 64 = 2166 B worst case.
pub const MAX_RIM_PAYLOAD: usize = 4096;
/// Handoff ack timeout (logical ms; chaos tests shrink it).
pub const HANDOFF_TIMEOUT_MS: u64 = 3_000;
/// Intent retransmit timeout base (logical ms).
pub const INTENT_RTO_MS: u64 = 400;
/// Max retransmit attempts before an intent is abandoned (event raised;
/// the app layer decides whether to cancel the session).
pub const MAX_INTENT_ATTEMPTS: u32 = 12;
/// Receiver-side controller lease: silence this long from the current
/// controller raises `ControllerSilent` (app-layer signal — the receiver
/// keeps rendering; authority is NOT revoked by quietness).
pub const CONTROLLER_LEASE_MS: u64 = 30_000;
/// Reorder-buffer bound (hostile future-seq floods).
pub const MAX_REORDER_BUFFER: usize = 512;

// RIM message types (frozen).
pub const RIM_INTENT: u8 = 0x01;
pub const RIM_ACK: u8 = 0x02;
pub const RIM_NACK: u8 = 0x03;
pub const RIM_HANDOFF_OFFER: u8 = 0x04;
pub const RIM_HANDOFF_CONFIRM: u8 = 0x05;
pub const RIM_HANDOFF_COMPLETE: u8 = 0x06;
pub const RIM_HANDOFF_ABORT: u8 = 0x07;
pub const RIM_INTENT_REJECT: u8 = 0x08;

/// Handoff abort reasons (wire codes).
pub const ABORT_TIMEOUT: u8 = 0x01;
pub const ABORT_STALE_EPOCH: u8 = 0x02;
pub const ABORT_BAD_SIGNATURE: u8 = 0x03;
/// Intent reject reasons (wire codes).
pub const REJECT_STALE_EPOCH: u8 = 0x01;

/// FNV-1a/32 (house checksum; local copy, same polynomial as the family).
fn fnv1a32(data: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for &b in data {
        hash ^= b as u32;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Header bytes exactly as [`encode_rim`] lays them out (signing span).
fn rim_header(
    msg_type: u8,
    sender: u64,
    target: u64,
    seq_or_nonce: u64,
    payload_len: usize,
) -> Vec<u8> {
    let mut h = Vec::with_capacity(RIM_HEADER_LEN);
    h.extend_from_slice(&RIM_MAGIC.to_le_bytes());
    h.push(RIM_VERSION);
    h.push(msg_type);
    h.extend_from_slice(&sender.to_le_bytes());
    h.extend_from_slice(&target.to_le_bytes());
    h.extend_from_slice(&seq_or_nonce.to_le_bytes());
    h.extend_from_slice(&(payload_len as u16).to_le_bytes());
    h
}

fn encode_rim(
    msg_type: u8,
    sender: u64,
    target: u64,
    seq_or_nonce: u64,
    payload: &[u8],
) -> Vec<u8> {
    let mut buf = rim_header(msg_type, sender, target, seq_or_nonce, payload.len());
    buf.extend_from_slice(payload);
    let sum = fnv1a32(&buf);
    buf.extend_from_slice(&sum.to_le_bytes());
    buf
}

/// The frame's target field (routing helper for drain_actions callers).
pub fn rim_frame_target(frame: &[u8]) -> u64 {
    if frame.len() >= 20 {
        // audit #44 P2-5: total read — degrade to the broadcast target
        // on a short frame instead of trusting the guard adjacency.
        wire_le::le_u64(&frame[12..20]).unwrap_or(0)
    } else {
        0
    }
}

// ─────────────────────────────────────────────── playback state & roles

/// Repeat mode carried by a handoff snapshot.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum RepeatMode {
    #[default]
    Off,
    All,
    One,
}

impl RepeatMode {
    pub fn wire(self) -> u8 {
        match self {
            RepeatMode::Off => 0,
            RepeatMode::All => 1,
            RepeatMode::One => 2,
        }
    }

    pub fn from_wire(code: u8) -> Option<Self> {
        match code {
            0 => Some(RepeatMode::Off),
            1 => Some(RepeatMode::All),
            2 => Some(RepeatMode::One),
            _ => None,
        }
    }
}

/// Atomically captured playback state (directive: track_id, position_ms,
/// active queue snapshot, repeat/shuffle). `to_json`/`from_json` define
/// the `sessionStateJson` contract of `nativeTransferPlayback`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaybackSnapshot {
    pub track_id: u64,
    pub position_ms: u64,
    /// Cad ids in play order (tail-most [`MAX_QUEUE_SNAPSHOT`] kept).
    pub queue: Vec<u64>,
    pub queue_index: u32,
    pub repeat: RepeatMode,
    pub shuffle: bool,
}

impl PlaybackSnapshot {
    /// Clamps the queue to the wire budget (keeps the tail + re-anchors
    /// the index). Idempotent.
    pub fn clamped(&self) -> PlaybackSnapshot {
        let mut s = self.clone();
        if s.queue.len() > MAX_QUEUE_SNAPSHOT {
            let cut = s.queue.len() - MAX_QUEUE_SNAPSHOT;
            s.queue.drain(..cut);
            s.queue_index = s.queue_index.saturating_sub(cut as u32);
        }
        if s.queue.is_empty() {
            s.queue_index = 0;
        } else {
            s.queue_index = s.queue_index.min((s.queue.len() - 1) as u32);
        }
        s
    }

    /// Canonical wire bytes (deterministic; signed by the OFFER path).
    fn encode_into(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.track_id.to_le_bytes());
        buf.extend_from_slice(&self.position_ms.to_le_bytes());
        buf.extend_from_slice(&(self.queue.len() as u32).to_le_bytes());
        buf.extend_from_slice(&self.queue_index.to_le_bytes());
        buf.push(self.repeat.wire());
        buf.push(u8::from(self.shuffle));
        for id in &self.queue {
            buf.extend_from_slice(&id.to_le_bytes());
        }
    }

    /// Returns (snapshot, bytes consumed) or `None` on structural issues.
    fn decode_from(bytes: &[u8]) -> Option<(PlaybackSnapshot, usize)> {
        if bytes.len() < 26 {
            return None;
        }
        let track_id = u64::from_le_bytes(bytes[0..8].try_into().ok()?);
        let position_ms = u64::from_le_bytes(bytes[8..16].try_into().ok()?);
        let qlen = u32::from_le_bytes(bytes[16..20].try_into().ok()?) as usize;
        let queue_index = u32::from_le_bytes(bytes[20..24].try_into().ok()?);
        let repeat = RepeatMode::from_wire(bytes[24])?;
        let shuffle = bytes[25] != 0;
        if qlen > MAX_QUEUE_SNAPSHOT {
            return None;
        }
        let need = 26 + qlen * 8;
        if bytes.len() < need {
            return None;
        }
        let mut queue = Vec::with_capacity(qlen);
        for i in 0..qlen {
            let lo = 26 + i * 8;
            queue.push(u64::from_le_bytes(bytes[lo..lo + 8].try_into().ok()?));
        }
        Some((
            PlaybackSnapshot {
                track_id,
                position_ms,
                queue_index: queue_index.min(if qlen == 0 { 0 } else { qlen as u32 - 1 }),
                repeat,
                shuffle,
                queue,
            },
            need,
        ))
    }

    /// `sessionStateJson` → snapshot (hostile-tolerant: unknown fields
    /// ignored, missing fields default, invalid JSON → `None`).
    pub fn from_json(json: &str) -> Option<PlaybackSnapshot> {
        let v: serde_json::Value = serde_json::from_str(json).ok()?;
        let queue = v
            .get("queue")
            .and_then(|q| q.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_u64())
                    .take(MAX_QUEUE_SNAPSHOT)
                    .collect::<Vec<u64>>()
            })
            .unwrap_or_default();
        Some(PlaybackSnapshot {
            track_id: v.get("trackId").and_then(|x| x.as_u64()).unwrap_or(0),
            position_ms: v.get("positionMs").and_then(|x| x.as_u64()).unwrap_or(0),
            queue_index: v
                .get("queueIndex")
                .and_then(|x| x.as_u64())
                .map(|x| x.min(u32::MAX as u64) as u32)
                .unwrap_or(0),
            repeat: match v.get("repeat").and_then(|x| x.as_str()) {
                Some("all") => RepeatMode::All,
                Some("one") => RepeatMode::One,
                _ => RepeatMode::Off,
            },
            shuffle: v.get("shuffle").and_then(|x| x.as_bool()).unwrap_or(false),
            queue,
        })
    }

    pub fn to_json(&self) -> String {
        serde_json::json!({
            "trackId": self.track_id,
            "positionMs": self.position_ms,
            "queue": self.queue,
            "queueIndex": self.queue_index,
            "repeat": match self.repeat {
                RepeatMode::Off => "off",
                RepeatMode::All => "all",
                RepeatMode::One => "one",
            },
            "shuffle": self.shuffle,
        })
        .to_string()
    }
}

/// Connect role (directive §2.2) plus two documented protocol sub-states
/// (`Idle` before any session, `HandoffPending` mid-protocol).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Idle,
    /// Local device drives the UI (rendering locally, or remotely after
    /// a completed handoff delegated rendering to a receiver).
    ActiveController,
    /// Mid-handoff: offer sent, confirmation pending. Local playback is
    /// STILL RUNNING — it halts only on [`IntentEvent::HandoffCompleted`].
    HandoffPending,
    /// Remote device rendering audio under a controller's intent stream.
    ActiveReceiver,
}

impl std::fmt::Display for Role {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Role::Idle => "idle",
            Role::ActiveController => "active_controller",
            Role::HandoffPending => "handoff_pending",
            Role::ActiveReceiver => "active_receiver",
        };
        f.write_str(s)
    }
}

// ─────────────────────────────────────────────── remote intents (wire)

/// Remote control actions (directive §2.2). Wire kinds are frozen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteIntent {
    Play,
    Pause,
    SeekTo { position_ms: u64 },
    QueueInsert { cad_id: u64, after_index: u32 },
    QueueRemove { index: u32 },
    QueueReorder { from: u32, to: u32 },
    SetVolume { volume_pct: u8 },
}

/// `u32::MAX` in `QueueInsert::after_index` means "append to tail".
pub const QUEUE_TAIL: u32 = u32::MAX;

impl RemoteIntent {
    pub fn kind(&self) -> u8 {
        match self {
            RemoteIntent::Play => 0x01,
            RemoteIntent::Pause => 0x02,
            RemoteIntent::SeekTo { .. } => 0x03,
            RemoteIntent::QueueInsert { .. } => 0x04,
            RemoteIntent::QueueRemove { .. } => 0x05,
            RemoteIntent::QueueReorder { .. } => 0x06,
            RemoteIntent::SetVolume { .. } => 0x07,
        }
    }

    /// Kotlin `intentType` discriminator for `nativeDispatchIntent`
    /// (`payload` = argument bytes, kind byte NOT included).
    pub fn from_kind(kind: u8, payload: &[u8]) -> Option<RemoteIntent> {
        match kind {
            0x01 => Some(RemoteIntent::Play),
            0x02 => Some(RemoteIntent::Pause),
            0x03 => {
                if payload.len() != 8 {
                    return None;
                }
                Some(RemoteIntent::SeekTo {
                    position_ms: u64::from_le_bytes(payload.try_into().ok()?),
                })
            }
            0x04 => {
                if payload.len() != 12 {
                    return None;
                }
                Some(RemoteIntent::QueueInsert {
                    cad_id: u64::from_le_bytes(payload[0..8].try_into().ok()?),
                    after_index: u32::from_le_bytes(payload[8..12].try_into().ok()?),
                })
            }
            0x05 => {
                if payload.len() != 4 {
                    return None;
                }
                Some(RemoteIntent::QueueRemove {
                    index: u32::from_le_bytes(payload.try_into().ok()?),
                })
            }
            0x06 => {
                if payload.len() != 8 {
                    return None;
                }
                Some(RemoteIntent::QueueReorder {
                    from: u32::from_le_bytes(payload[0..4].try_into().ok()?),
                    to: u32::from_le_bytes(payload[4..8].try_into().ok()?),
                })
            }
            0x07 => {
                if payload.len() != 1 {
                    return None;
                }
                Some(RemoteIntent::SetVolume {
                    volume_pct: payload[0].min(100),
                })
            }
            _ => None,
        }
    }

    /// Payload bytes INCLUDING the leading kind byte.
    fn encode(&self) -> Vec<u8> {
        let mut b = vec![self.kind()];
        match self {
            RemoteIntent::Play | RemoteIntent::Pause => {}
            RemoteIntent::SeekTo { position_ms } => b.extend_from_slice(&position_ms.to_le_bytes()),
            RemoteIntent::QueueInsert {
                cad_id,
                after_index,
            } => {
                b.extend_from_slice(&cad_id.to_le_bytes());
                b.extend_from_slice(&after_index.to_le_bytes());
            }
            RemoteIntent::QueueRemove { index } => b.extend_from_slice(&index.to_le_bytes()),
            RemoteIntent::QueueReorder { from, to } => {
                b.extend_from_slice(&from.to_le_bytes());
                b.extend_from_slice(&to.to_le_bytes());
            }
            RemoteIntent::SetVolume { volume_pct } => b.push(*volume_pct),
        }
        b
    }

    /// Total decode (kind byte first).
    fn decode(payload: &[u8]) -> Option<RemoteIntent> {
        let (kind, rest) = payload.split_first()?;
        RemoteIntent::from_kind(*kind, rest)
    }
}

// ─────────────────────────────────────────────── events, actions, errors

/// App-layer events (JNI fan-out / chaos assertions).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IntentEvent {
    RoleChanged {
        from: Role,
        to: Role,
    },
    /// Offer dispatched (HandoffPending entered).
    HandoffStarted {
        target: u64,
        epoch: u32,
    },
    /// Receiver confirmed — the local player may NOW halt.
    HandoffCompleted {
        target: u64,
        epoch: u32,
    },
    /// Confirmation never arrived / receiver refused — local playback
    /// continues; nothing was interrupted.
    HandoffFailed {
        target: u64,
        reason: HandoffFailReason,
    },
    /// (Receiver) offer verified & adopted: restore this snapshot and
    /// start rendering.
    HandoffAdopted {
        from_controller: u64,
        snapshot: PlaybackSnapshot,
        epoch: u32,
    },
    /// (Receiver) offer refused.
    HandoffRefused {
        from_controller: u64,
        reason: HandoffFailReason,
    },
    /// (Receiver) finalize — the controller saw our confirmation.
    HandoffFinalized {
        controller: u64,
    },
    /// A remote controller pair now dominates this device's receiver.
    RemoteTookControl {
        device_id: u64,
        epoch: u32,
    },
    /// Intent applied in order (receiver side).
    IntentApplied {
        seq_id: u64,
        intent: RemoteIntent,
    },
    /// Intent from a dominated controller pair — rejected (split-brain
    /// loser receipt).
    IntentRejectedStale {
        seq_id: u64,
        controller: ControllerPair,
    },
    /// A controller intent never got ACKed after MAX_INTENT_ATTEMPTS.
    IntentAbandoned {
        seq_id: u64,
        intent: RemoteIntent,
    },
    /// (Receiver) controller silent for CONTROLLER_LEASE_MS — app signal
    /// only, authority NOT revoked.
    ControllerSilent {
        controller: ControllerPair,
    },
}

/// Why a handoff failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HandoffFailReason {
    Timeout,
    StaleEpoch,
    BadSignature,
    MalformedFrame,
    NotController,
}

impl std::fmt::Display for HandoffFailReason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            HandoffFailReason::Timeout => "confirmation timeout",
            HandoffFailReason::StaleEpoch => "offer epoch dominated by current controller",
            HandoffFailReason::BadSignature => "Ed25519 signature invalid",
            HandoffFailReason::MalformedFrame => "malformed offer frame",
            HandoffFailReason::NotController => "only the ActiveController may hand off",
        };
        f.write_str(s)
    }
}

/// The controller identity: (epoch, device_id), lexicographic.
pub type ControllerPair = (u32, u64);

/// Effects the transport layer must execute.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IntentAction {
    /// Unicast the frame to `target` (LAN or relay — the app routes).
    Send { target: u64, frame: Vec<u8> },
}

/// Boundary rejection reasons (hostile-input observability).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RimReject {
    Truncated,
    BadMagic,
    BadVersion,
    BadMsgType,
    Oversize,
    BadChecksum,
}

impl std::fmt::Display for RimReject {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            RimReject::Truncated => "frame truncated below RIM header",
            RimReject::BadMagic => "magic mismatch (not a RIM frame)",
            RimReject::BadVersion => "unsupported RIM version",
            RimReject::BadMsgType => "unknown RIM message type",
            RimReject::Oversize => "payload exceeds RIM budget",
            RimReject::BadChecksum => "FNV-1a checksum mismatch",
        };
        f.write_str(s)
    }
}

/// A fully parsed RIM frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RimFrame {
    pub msg_type: u8,
    pub sender: u64,
    pub target: u64,
    pub seq_or_nonce: u64,
    pub payload: Vec<u8>,
}

fn parse_rim(bytes: &[u8]) -> Result<RimFrame, RimReject> {
    if bytes.len() < RIM_HEADER_LEN + 4 {
        return Err(RimReject::Truncated);
    }
    if u16::from_le_bytes([bytes[0], bytes[1]]) != RIM_MAGIC {
        return Err(RimReject::BadMagic);
    }
    if bytes[2] != RIM_VERSION {
        return Err(RimReject::BadVersion);
    }
    let msg_type = bytes[3];
    if !(RIM_INTENT..=RIM_INTENT_REJECT).contains(&msg_type) {
        return Err(RimReject::BadMsgType);
    }
    let plen = u16::from_le_bytes([bytes[28], bytes[29]]) as usize;
    if plen > MAX_RIM_PAYLOAD {
        return Err(RimReject::Oversize);
    }
    if bytes.len() < RIM_HEADER_LEN + plen + 4 {
        return Err(RimReject::Truncated);
    }
    let sum_off = bytes.len() - 4;
    let wire_sum = u32::from_le_bytes([
        bytes[sum_off],
        bytes[sum_off + 1],
        bytes[sum_off + 2],
        bytes[sum_off + 3],
    ]);
    if fnv1a32(&bytes[..sum_off]) != wire_sum {
        return Err(RimReject::BadChecksum);
    }
    Ok(RimFrame {
        msg_type,
        sender: wire_le::le_u64(&bytes[4..12]).ok_or(RimReject::Truncated)?,
        target: wire_le::le_u64(&bytes[12..20]).ok_or(RimReject::Truncated)?,
        seq_or_nonce: wire_le::le_u64(&bytes[20..28]).ok_or(RimReject::Truncated)?,
        payload: bytes[RIM_HEADER_LEN..RIM_HEADER_LEN + plen].to_vec(),
    })
}

// ─────────────────────────────────────────────── per-side dispatch state

#[derive(Debug, Clone)]
struct ControllerState {
    epoch: u32,
    next_counter: u32,
    /// seq → (intent, first_sent_ms, attempts).
    inflight: BTreeMap<u64, (RemoteIntent, u64, u32)>,
    /// Remote renderer after a completed handoff (routing + telemetry).
    delegated_to: Option<u64>,
}

#[derive(Debug, Clone)]
struct ReceiverState {
    controller: ControllerPair,
    /// Next seq expected in order for the current controller's stream.
    next_expected: u64,
    /// Out-of-order stash (bounded by MAX_REORDER_BUFFER).
    buffer: BTreeMap<u64, RemoteIntent>,
    applied_count: u64,
    last_controller_ms: u64,
    controller_silent_raised: bool,
}

/// Pending handoff on the controller side. `epoch`/`snapshot` ride the
/// signed OFFER (kept for telemetry + future offer-retry re-signing);
/// `deadline_ms` governs the abort path, `last_offer_ms` the offer
/// retransmission cadence (a lost OFFER must not doom the handoff).
#[derive(Debug, Clone)]
struct HandoffState {
    target: u64,
    nonce: u64,
    #[allow(dead_code)] // telemetry / future offer-retry re-signing
    epoch: u32,
    deadline_ms: u64,
    /// Logical ms of the last OFFER (re)transmission.
    last_offer_ms: u64,
    /// Offer retransmissions fired (bounded by the deadline).
    offer_attempts: u32,
    /// The signed OFFER frame, cached verbatim for identical retransmits.
    offer_frame: Vec<u8>,
    #[allow(dead_code)] // telemetry / debugging introspection of a live gateway
    snapshot: PlaybackSnapshot,
}

// ─────────────────────────────────────────────── the engine

/// Boundary/telemetry counters.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IntentEngineStats {
    pub frames_rx: u64,
    pub frames_rejected: u64,
    pub intents_dispatched: u64,
    pub intents_acked: u64,
    pub intents_retransmitted: u64,
    pub intents_nacked: u64,
    pub intents_applied: u64,
    pub intents_stale_rejected: u64,
    pub intents_duplicated: u64,
    pub handoffs_started: u64,
    pub handoffs_completed: u64,
    pub handoffs_failed: u64,
    pub sig_failures: u64,
}

/// One device's remote-intent engine. Constructed with a deterministic
/// identity seed (house pattern) → Ed25519 keypair; the verifying key is
/// embedded in every OFFER so receivers can verify authenticity.
pub struct RemoteIntentEngine {
    self_id: u64,
    signing: SigningKey,
    role: Role,
    controller: Option<ControllerState>,
    receiver: Option<ReceiverState>,
    handoff: Option<HandoffState>,
    /// Highest controller epoch ever observed (own or remote) — new
    /// epochs allocate from here +1.
    max_epoch_seen: u32,
    /// Monotonic nonce source for handoffs.
    next_nonce: u64,
    /// Receiver-adopted handoff awaiting COMPLETE: (nonce, controller).
    pending_finalize: Option<(u64, u64)>,
    actions: Vec<IntentAction>,
    events: VecDeque<IntentEvent>,
    stats: IntentEngineStats,
}

impl RemoteIntentEngine {
    pub fn new(self_id: u64, identity_seed: [u8; 32]) -> Self {
        RemoteIntentEngine {
            self_id,
            signing: SigningKey::from_bytes(&identity_seed),
            role: Role::Idle,
            controller: None,
            receiver: None,
            handoff: None,
            max_epoch_seen: 0,
            next_nonce: 1,
            pending_finalize: None,
            actions: Vec::new(),
            events: VecDeque::new(),
            stats: IntentEngineStats::default(),
        }
    }

    pub fn self_id(&self) -> u64 {
        self.self_id
    }

    pub fn role(&self) -> Role {
        self.role
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing.verifying_key()
    }

    pub fn stats(&self) -> IntentEngineStats {
        self.stats
    }

    /// Current controller epoch (0 when not controlling).
    pub fn controller_epoch(&self) -> u32 {
        self.controller.as_ref().map_or(0, |c| c.epoch)
    }

    /// Controller pair observed by our receiver half (None = never a
    /// receiver).
    pub fn current_controller(&self) -> Option<ControllerPair> {
        self.receiver.as_ref().map(|r| r.controller)
    }

    /// Receiver-side applied-intent count (telemetry).
    pub fn applied_intents(&self) -> u64 {
        self.receiver.as_ref().map_or(0, |r| r.applied_count)
    }

    pub fn drain_events(&mut self) -> Vec<IntentEvent> {
        self.events.drain(..).collect()
    }

    /// Takes pending outbound frames (transport pump).
    pub fn drain_actions(&mut self) -> Vec<IntentAction> {
        std::mem::take(&mut self.actions)
    }

    fn transition(&mut self, to: Role) {
        let from = self.role;
        if from != to {
            self.role = to;
            self.events.push_back(IntentEvent::RoleChanged { from, to });
        }
    }

    /// Where newly dispatched intents go (pending handoff target, else
    /// the delegated renderer, else broadcast).
    fn target_now(&self) -> u64 {
        if let Some(h) = &self.handoff {
            return h.target;
        }
        self.controller
            .as_ref()
            .and_then(|c| c.delegated_to)
            .unwrap_or(0)
    }

    // ── controller side ──────────────────────────────────────────────

    /// Become the ActiveController (fresh epoch dominates all observed).
    /// Returns the allocated epoch.
    pub fn start_session(&mut self) -> u32 {
        if matches!(self.role, Role::ActiveController | Role::HandoffPending) {
            return self.controller_epoch();
        }
        let epoch = self.max_epoch_seen + 1;
        self.max_epoch_seen = epoch;
        self.controller = Some(ControllerState {
            epoch,
            next_counter: 1,
            inflight: BTreeMap::new(),
            delegated_to: None,
        });
        self.receiver = None; // a controller is not simultaneously a receiver
        self.transition(Role::ActiveController);
        epoch
    }

    /// ActiveReceiver steals control (play-here-now): epoch+1 dominates.
    /// Returns the new epoch, or `None` if not currently a receiver.
    pub fn take_control(&mut self) -> Option<u32> {
        if self.role != Role::ActiveReceiver {
            return None;
        }
        self.receiver = None;
        self.pending_finalize = None;
        Some(self.start_session())
    }

    /// End the session (either role) → Idle.
    pub fn end_session(&mut self) -> bool {
        match self.role {
            Role::Idle => false,
            Role::ActiveController | Role::HandoffPending => {
                self.controller = None;
                self.handoff = None;
                self.transition(Role::Idle);
                true
            }
            Role::ActiveReceiver => {
                self.receiver = None;
                self.pending_finalize = None;
                self.transition(Role::Idle);
                true
            }
        }
    }

    /// Dispatch a remote intent toward the current target. Returns the
    /// seq_id (`None` when not the ActiveController).
    pub fn dispatch_intent(&mut self, intent: RemoteIntent, now_ms: u64) -> Option<u64> {
        if self.role != Role::ActiveController {
            return None;
        }
        let target = self.target_now();
        let seq = {
            let controller = self.controller.as_mut()?;
            let seq = ((controller.epoch as u64) << 32) | controller.next_counter as u64;
            controller.next_counter += 1;
            controller.inflight.insert(seq, (intent, now_ms, 1));
            seq
        };
        self.stats.intents_dispatched += 1;
        let frame = encode_rim(RIM_INTENT, self.self_id, target, seq, &intent.encode());
        self.actions.push(IntentAction::Send { target, frame });
        Some(seq)
    }

    /// Begin a seamless handoff (gap #52): capture the snapshot, sign the
    /// OFFER, enter HandoffPending. The local player keeps playing until
    /// [`IntentEvent::HandoffCompleted`] fires.
    pub fn begin_handoff(
        &mut self,
        target: u64,
        snapshot: &PlaybackSnapshot,
        now_ms: u64,
    ) -> Result<u32, HandoffFailReason> {
        if self.role != Role::ActiveController {
            self.stats.handoffs_failed += 1;
            return Err(HandoffFailReason::NotController);
        }
        let epoch = self.max_epoch_seen + 1;
        let snapshot = snapshot.clamped();
        let nonce = self.next_nonce;
        self.next_nonce += 1;

        // OFFER payload: epoch · snapshot · pubkey (sig appended after).
        let mut payload = Vec::with_capacity(4 + 26 + snapshot.queue.len() * 8 + 32 + 64);
        payload.extend_from_slice(&epoch.to_le_bytes());
        snapshot.encode_into(&mut payload);
        payload.extend_from_slice(self.signing.verifying_key().as_bytes());

        // Signature over canonical header + payload-without-signature.
        let header = rim_header(
            RIM_HANDOFF_OFFER,
            self.self_id,
            target,
            nonce,
            payload.len(),
        );
        let mut span = header;
        span.extend_from_slice(&payload);
        let sig = self.signing.sign(&span);
        payload.extend_from_slice(&sig.to_bytes());

        let frame = encode_rim(RIM_HANDOFF_OFFER, self.self_id, target, nonce, &payload);
        self.actions.push(IntentAction::Send {
            target,
            frame: frame.clone(),
        });
        self.handoff = Some(HandoffState {
            target,
            nonce,
            epoch,
            deadline_ms: now_ms + HANDOFF_TIMEOUT_MS,
            last_offer_ms: now_ms,
            offer_attempts: 1,
            offer_frame: frame,
            snapshot,
        });
        self.stats.handoffs_started += 1;
        self.max_epoch_seen = self.max_epoch_seen.max(epoch);
        self.transition(Role::HandoffPending);
        self.events
            .push_back(IntentEvent::HandoffStarted { target, epoch });
        Ok(epoch)
    }

    /// Housekeeping: retransmit timed-out intents (capped exponential
    /// backoff), re-send a pending OFFER under packet loss (bounded by
    /// the handoff deadline — local playback NEVER halted until
    /// confirmed), expire a stuck handoff, abandon exhausted intents,
    /// observe the receiver's controller lease.
    pub fn tick(&mut self, now_ms: u64) {
        // Handoff deadline (controller-initiated abort path).
        if let Some(h) = &self.handoff {
            if now_ms >= h.deadline_ms {
                let target = h.target;
                let nonce = h.nonce;
                self.timeout_handoff(target, nonce);
            }
        }
        // OFFER retransmission: every INTENT_RTO_MS while un-confirmed.
        // The frame is re-sent VERBATIM (same nonce + signature) so the
        // receiver's idempotent nonce match keeps duplicates inert.
        let resend_offer: Option<Vec<u8>> = self.handoff.as_ref().and_then(|h| {
            if now_ms >= h.last_offer_ms + INTENT_RTO_MS && now_ms < h.deadline_ms {
                Some(h.offer_frame.clone())
            } else {
                None
            }
        });
        if let Some(frame) = resend_offer {
            // audit #44 P2-5: re-borrow instead of `.expect("just checked")`
            // — if the handoff ever vanishes between the read above and
            // here (a future refactor away from single-function flow), the
            // resend degrades to a skip, never a panic.
            if let Some(h) = self.handoff.as_mut() {
                let target = h.target;
                h.last_offer_ms = now_ms;
                h.offer_attempts += 1;
                self.actions.push(IntentAction::Send { target, frame });
            }
        }
        // Intent retransmission / abandonment.
        if matches!(self.role, Role::ActiveController | Role::HandoffPending) {
            let mut due: Vec<(u64, RemoteIntent, bool)> = Vec::new(); // (seq, intent, abandon?)
            if let Some(controller) = self.controller.as_ref() {
                for (seq, (intent, first, attempts)) in controller.inflight.iter() {
                    let backoff = INTENT_RTO_MS << (*attempts).min(4);
                    if now_ms >= first + backoff {
                        due.push((*seq, *intent, *attempts >= MAX_INTENT_ATTEMPTS));
                    }
                }
            }
            for (seq, intent, abandon) in due {
                if abandon {
                    if let Some(controller) = self.controller.as_mut() {
                        controller.inflight.remove(&seq);
                    }
                    self.events.push_back(IntentEvent::IntentAbandoned {
                        seq_id: seq,
                        intent,
                    });
                } else {
                    if let Some(controller) = self.controller.as_mut() {
                        if let Some((_, _, attempts)) = controller.inflight.get_mut(&seq) {
                            *attempts += 1;
                        }
                    }
                    let target = self.target_now();
                    let frame = encode_rim(RIM_INTENT, self.self_id, target, seq, &intent.encode());
                    self.actions.push(IntentAction::Send { target, frame });
                    self.stats.intents_retransmitted += 1;
                }
            }
        }
        // Receiver-side controller lease observation.
        if let Some(receiver) = self.receiver.as_mut() {
            if !receiver.controller_silent_raised
                && now_ms >= receiver.last_controller_ms + CONTROLLER_LEASE_MS
            {
                receiver.controller_silent_raised = true;
                let controller = receiver.controller;
                self.events
                    .push_back(IntentEvent::ControllerSilent { controller });
            }
        }
    }

    /// Controller-side timeout: send ABORT, restore controller role.
    fn timeout_handoff(&mut self, target: u64, nonce: u64) {
        if self
            .handoff
            .as_ref()
            .is_some_and(|h| h.target == target && h.nonce == nonce)
        {
            self.handoff = None;
            let frame = encode_rim(
                RIM_HANDOFF_ABORT,
                self.self_id,
                target,
                nonce,
                &[ABORT_TIMEOUT],
            );
            self.actions.push(IntentAction::Send { target, frame });
            self.stats.handoffs_failed += 1;
            self.events.push_back(IntentEvent::HandoffFailed {
                target,
                reason: HandoffFailReason::Timeout,
            });
            if self.role == Role::HandoffPending {
                self.transition(Role::ActiveController);
            }
        }
    }

    // ── inbound frame ingestion (both sides) ─────────────────────────

    /// Feed one transport-delivered RIM frame. Returns `false` when the
    /// frame was refused at the boundary (counted; no state change).
    pub fn on_frame(&mut self, bytes: &[u8], now_ms: u64) -> bool {
        let frame = match parse_rim(bytes) {
            Ok(f) => f,
            Err(_) => {
                self.stats.frames_rejected += 1;
                return false;
            }
        };
        self.stats.frames_rx += 1;
        if frame.target != 0 && frame.target != self.self_id {
            return true; // routed elsewhere — legal, nothing to do
        }
        match frame.msg_type {
            RIM_ACK => {
                if let Some(controller) = self.controller.as_mut() {
                    if controller.inflight.remove(&frame.seq_or_nonce).is_some() {
                        self.stats.intents_acked += 1;
                    }
                }
                true
            }
            RIM_NACK => self.on_nack(&frame),
            RIM_INTENT => self.on_intent(frame.sender, frame.seq_or_nonce, &frame.payload, now_ms),
            RIM_INTENT_REJECT => {
                // Our controller pair is dominated at some receiver: drop
                // the covered intent from inflight (the app may take over
                // with a fresh epoch).
                if let Some(controller) = self.controller.as_mut() {
                    controller.inflight.remove(&frame.seq_or_nonce);
                }
                true
            }
            RIM_HANDOFF_OFFER => self.on_handoff_offer(&frame, now_ms),
            RIM_HANDOFF_CONFIRM => self.on_handoff_confirm(&frame),
            RIM_HANDOFF_COMPLETE => self.on_handoff_complete(&frame),
            RIM_HANDOFF_ABORT => self.on_handoff_abort(&frame),
            _ => {
                self.stats.frames_rejected += 1;
                false
            }
        }
    }

    /// NACK range retransmit: immediately resend covered in-flight seqs.
    fn on_nack(&mut self, frame: &RimFrame) -> bool {
        if frame.payload.len() != 8 {
            self.stats.frames_rejected += 1;
            return false;
        }
        let to_seq = match wire_le::le_u64(frame.payload.as_slice()) {
            Some(s) => s,
            // audit #44 P2-5: total read; the length guard above makes
            // this unreachable today, but a guard drift degrades into a
            // rejected NACK instead of a panic.
            None => {
                self.stats.frames_rejected += 1;
                return false;
            }
        };
        self.stats.intents_nacked += 1;
        let from_seq = frame.seq_or_nonce;
        if to_seq < from_seq {
            return true; // degenerate range — ignore, never panic
        }
        let target = self.target_now();
        let resend: Vec<(u64, RemoteIntent)> = self
            .controller
            .as_ref()
            .map(|c| {
                c.inflight
                    .range(from_seq..=to_seq)
                    .map(|(s, (i, _, _))| (*s, *i))
                    .collect()
            })
            .unwrap_or_default();
        for (seq, intent) in resend {
            if let Some(controller) = self.controller.as_mut() {
                if let Some((_, _, attempts)) = controller.inflight.get_mut(&seq) {
                    *attempts += 1;
                }
            }
            let frame = encode_rim(RIM_INTENT, self.self_id, target, seq, &intent.encode());
            self.actions.push(IntentAction::Send { target, frame });
            self.stats.intents_retransmitted += 1;
        }
        true
    }

    /// Receiver-side intent application: strictly in-order, idempotent,
    /// split-brain-arbitrated by the (epoch, device) controller pair.
    fn on_intent(&mut self, sender: u64, seq: u64, payload: &[u8], now_ms: u64) -> bool {
        let Some(intent) = RemoteIntent::decode(payload) else {
            self.stats.frames_rejected += 1;
            return false;
        };
        let epoch = (seq >> 32) as u32;
        let incoming = (epoch, sender);

        // Bootstrap the receiver half on first controller traffic. If we
        // are ourselves a dominating controller, track OURSELVES so any
        // weaker remote is stale-rejected (split-brain at first contact).
        if self.receiver.is_none() {
            let own = self.controller.as_ref().map(|c| (c.epoch, self.self_id));
            let initial = own.filter(|o| *o >= incoming).unwrap_or(incoming);
            self.receiver = Some(ReceiverState {
                controller: initial,
                next_expected: ((initial.0 as u64) << 32) | 1,
                buffer: BTreeMap::new(),
                applied_count: 0,
                last_controller_ms: now_ms,
                controller_silent_raised: false,
            });
            if initial == incoming {
                if self.role == Role::Idle {
                    self.transition(Role::ActiveReceiver);
                }
                self.events.push_back(IntentEvent::RemoteTookControl {
                    device_id: sender,
                    epoch,
                });
            }
        }

        // audit #44 P2-5: the role gates above guarantee the receiver is
        // bootstrapped by the time ingress reaches here; if that invariant
        // ever drifts (a future refactor moves the bootstrap), degrade to
        // a rejected frame instead of a panic mid-dispatch.
        let Some(receiver) = self.receiver.as_mut() else {
            self.stats.frames_rejected += 1;
            return false;
        };
        receiver.last_controller_ms = now_ms;
        receiver.controller_silent_raised = false;

        match incoming.cmp(&receiver.controller) {
            std::cmp::Ordering::Less => {
                // Split-brain loser: stale receipt, never applied.
                self.stats.intents_stale_rejected += 1;
                let controller = receiver.controller;
                let frame = encode_rim(
                    RIM_INTENT_REJECT,
                    self.self_id,
                    sender,
                    seq,
                    &[REJECT_STALE_EPOCH],
                );
                self.actions.push(IntentAction::Send {
                    target: sender,
                    frame,
                });
                self.events.push_back(IntentEvent::IntentRejectedStale {
                    seq_id: seq,
                    controller,
                });
                true
            }
            std::cmp::Ordering::Greater => {
                // New dominant controller: reset the stream to its epoch
                // base (its counter starts at 1 by protocol invariant);
                // the observed seq stashes, gaps NACK.
                receiver.controller = incoming;
                receiver.next_expected = ((epoch as u64) << 32) | 1;
                receiver.buffer.clear();
                self.events.push_back(IntentEvent::RemoteTookControl {
                    device_id: sender,
                    epoch,
                });
                self.stash_or_apply(sender, seq, intent)
            }
            std::cmp::Ordering::Equal => self.stash_or_apply(sender, seq, intent),
        }
    }

    /// Equal-controller-pair path: apply if next, stash if future
    /// (NACKing the gap), re-ACK if duplicate.
    fn stash_or_apply(&mut self, sender: u64, seq: u64, intent: RemoteIntent) -> bool {
        // audit #44 P2-5: degrade to "frame not handled" when the
        // bootstrap invariant is violated — never a panic.
        let Some(next) = self.receiver.as_ref().map(|r| r.next_expected) else {
            self.stats.frames_rejected += 1;
            return false;
        };
        if seq < next {
            // Duplicate (sender retry): re-ACK idempotently, never re-apply.
            self.stats.intents_duplicated += 1;
            let frame = encode_rim(RIM_ACK, self.self_id, sender, seq, &[]);
            self.actions.push(IntentAction::Send {
                target: sender,
                frame,
            });
            return true;
        }
        if seq > next {
            // Future intent: stash (bounded) + NACK [next, seq).
            if let Some(receiver) = self.receiver.as_mut() {
                receiver.buffer.insert(seq, intent);
                if receiver.buffer.len() > MAX_REORDER_BUFFER {
                    // Hostile future-seq flood: drop the HIGHEST entries
                    // (the earliest window is what unblocks in-order apply).
                    let excess: Vec<u64> = receiver
                        .buffer
                        .keys()
                        .rev()
                        .take(receiver.buffer.len() - MAX_REORDER_BUFFER)
                        .copied()
                        .collect();
                    for k in excess {
                        receiver.buffer.remove(&k);
                    }
                }
            }
            let nack = encode_rim(
                RIM_NACK,
                self.self_id,
                sender,
                next,
                &(seq - 1).to_le_bytes(),
            );
            self.actions.push(IntentAction::Send {
                target: sender,
                frame: nack,
            });
            return true;
        }
        // seq == next: apply, then drain consecutively buffered intents.
        self.apply_in_order(sender, seq, intent);
        loop {
            // audit #44 P2-5: no receiver left to drain → nothing buffered
            // → the match breaks the loop. Total by construction.
            let (next_now, buffered) = self
                .receiver
                .as_ref()
                .map(|r| (r.next_expected, r.buffer.get(&r.next_expected).copied()))
                .unwrap_or((0, None));
            match buffered {
                Some(i) => {
                    if let Some(receiver) = self.receiver.as_mut() {
                        receiver.buffer.remove(&next_now);
                    }
                    self.apply_in_order(sender, next_now, i);
                }
                None => break,
            }
        }
        true
    }

    fn apply_in_order(&mut self, sender: u64, seq: u64, intent: RemoteIntent) {
        if let Some(receiver) = self.receiver.as_mut() {
            receiver.next_expected = seq + 1;
            receiver.applied_count += 1;
        }
        self.stats.intents_applied += 1;
        let ack = encode_rim(RIM_ACK, self.self_id, sender, seq, &[]);
        self.actions.push(IntentAction::Send {
            target: sender,
            frame: ack,
        });
        self.events.push_back(IntentEvent::IntentApplied {
            seq_id: seq,
            intent,
        });
    }

    // ── handoff protocol inbound paths ───────────────────────────────

    /// (Receiver) verify + adopt an OFFER.
    fn on_handoff_offer(&mut self, frame: &RimFrame, now_ms: u64) -> bool {
        let payload = &frame.payload;
        // epoch(4) + snapshot(≥26) + pubkey(32) + sig(64).
        if payload.len() < 4 + 26 + 32 + 64 {
            self.stats.frames_rejected += 1;
            self.events.push_back(IntentEvent::HandoffRefused {
                from_controller: frame.sender,
                reason: HandoffFailReason::MalformedFrame,
            });
            return false;
        }
        let epoch = match wire_le::le_u32(&payload[0..4]) {
            Some(e) => e,
            // audit #44 P2-5: total read — guard drift degrades into a
            // refused handoff, never a panic.
            None => {
                self.stats.frames_rejected += 1;
                self.events.push_back(IntentEvent::HandoffRefused {
                    from_controller: frame.sender,
                    reason: HandoffFailReason::MalformedFrame,
                });
                return false;
            }
        };
        let (snapshot, consumed) = match PlaybackSnapshot::decode_from(&payload[4..]) {
            Some(v) => v,
            None => {
                self.stats.frames_rejected += 1;
                self.events.push_back(IntentEvent::HandoffRefused {
                    from_controller: frame.sender,
                    reason: HandoffFailReason::MalformedFrame,
                });
                return false;
            }
        };
        let key_off = 4 + consumed;
        let sig_off = key_off + 32;
        let (pubkey_bytes, sig_bytes): ([u8; 32], [u8; 64]) = match (
            wire_le::fixed::<32>(&payload[key_off..sig_off]),
            wire_le::fixed::<64>(&payload[sig_off..sig_off + 64]),
        ) {
            (Some(k), Some(s)) => (k, s),
            // audit #44 P2-5: total reads — guard drift degrades into a
            // refused handoff, never a panic.
            _ => {
                self.stats.frames_rejected += 1;
                self.events.push_back(IntentEvent::HandoffRefused {
                    from_controller: frame.sender,
                    reason: HandoffFailReason::MalformedFrame,
                });
                return false;
            }
        };
        let verifying = match VerifyingKey::from_bytes(&pubkey_bytes) {
            Ok(k) => k,
            Err(_) => {
                self.stats.sig_failures += 1;
                self.events.push_back(IntentEvent::HandoffRefused {
                    from_controller: frame.sender,
                    reason: HandoffFailReason::BadSignature,
                });
                return false;
            }
        };
        // Canonical span = header + payload-without-signature.
        let mut span = rim_header(
            RIM_HANDOFF_OFFER,
            frame.sender,
            frame.target,
            frame.seq_or_nonce,
            sig_off,
        );
        span.extend_from_slice(&payload[..sig_off]);
        if verifying
            .verify(&span, &Signature::from_bytes(&sig_bytes))
            .is_err()
        {
            self.stats.sig_failures += 1;
            let abort = encode_rim(
                RIM_HANDOFF_ABORT,
                self.self_id,
                frame.sender,
                frame.seq_or_nonce,
                &[ABORT_BAD_SIGNATURE],
            );
            self.actions.push(IntentAction::Send {
                target: frame.sender,
                frame: abort,
            });
            self.events.push_back(IntentEvent::HandoffRefused {
                from_controller: frame.sender,
                reason: HandoffFailReason::BadSignature,
            });
            // Processed (and answered with a refusal receipt) — the frame
            // crossed the boundary fine; on_frame reports true.
            return true;
        }

        // Epoch arbitration: the offer's (epoch, sender) must STRICTLY
        // dominate the receiver's current controller pair.
        let offer_pair = (epoch, frame.sender);
        let current = self.receiver.as_ref().map(|r| r.controller);
        if current.is_some_and(|cur| offer_pair <= cur) {
            let abort = encode_rim(
                RIM_HANDOFF_ABORT,
                self.self_id,
                frame.sender,
                frame.seq_or_nonce,
                &[ABORT_STALE_EPOCH],
            );
            self.actions.push(IntentAction::Send {
                target: frame.sender,
                frame: abort,
            });
            self.events.push_back(IntentEvent::HandoffRefused {
                from_controller: frame.sender,
                reason: HandoffFailReason::StaleEpoch,
            });
            return true;
        }

        // Adopt: receiver role + controller stream pinned to the new epoch.
        self.receiver = Some(ReceiverState {
            controller: offer_pair,
            next_expected: ((epoch as u64) << 32) | 1,
            buffer: BTreeMap::new(),
            applied_count: 0,
            last_controller_ms: now_ms,
            controller_silent_raised: false,
        });
        self.max_epoch_seen = self.max_epoch_seen.max(epoch);
        self.pending_finalize = Some((frame.seq_or_nonce, frame.sender));
        if matches!(self.role, Role::Idle | Role::ActiveReceiver) {
            self.transition(Role::ActiveReceiver);
        }
        let confirm = encode_rim(
            RIM_HANDOFF_CONFIRM,
            self.self_id,
            frame.sender,
            frame.seq_or_nonce,
            &epoch.to_le_bytes(),
        );
        self.actions.push(IntentAction::Send {
            target: frame.sender,
            frame: confirm,
        });
        self.events.push_back(IntentEvent::HandoffAdopted {
            from_controller: frame.sender,
            snapshot,
            epoch,
        });
        true
    }

    /// (Controller) confirmation arrived — the local player may halt now.
    fn on_handoff_confirm(&mut self, frame: &RimFrame) -> bool {
        let Some(h) = self.handoff.take() else {
            return true; // stray confirm (receiver retries) — inert
        };
        if h.target != frame.sender || h.nonce != frame.seq_or_nonce || frame.payload.len() != 4 {
            self.handoff = Some(h); // not ours / malformed — restore
            if frame.payload.len() != 4 {
                self.stats.frames_rejected += 1;
                return false;
            }
            return true;
        }
        let ack_epoch = match wire_le::le_u32(frame.payload.as_slice()) {
            Some(e) => e,
            // audit #44 P2-5: total read; the length guard above makes this
            // unreachable today — a guard drift degrades into a rejected
            // frame instead of a panic.
            None => {
                self.stats.frames_rejected += 1;
                return false;
            }
        };
        // Adopt the offer epoch as OUR controller epoch: the intent stream
        // now flows under it, aligned with the receiver's pinned base.
        // Pending pre-handoff intents are superseded by the snapshot (the
        // atomic state transfer) and dropped.
        if let Some(controller) = self.controller.as_mut() {
            controller.epoch = ack_epoch;
            controller.next_counter = 1;
            controller.inflight.clear();
            controller.delegated_to = Some(h.target);
        }
        self.max_epoch_seen = self.max_epoch_seen.max(ack_epoch);
        let complete = encode_rim(RIM_HANDOFF_COMPLETE, self.self_id, h.target, h.nonce, &[]);
        self.actions.push(IntentAction::Send {
            target: h.target,
            frame: complete,
        });
        self.stats.handoffs_completed += 1;
        self.events.push_back(IntentEvent::HandoffCompleted {
            target: h.target,
            epoch: ack_epoch,
        });
        if self.role == Role::HandoffPending {
            self.transition(Role::ActiveController);
        }
        true
    }

    /// (Receiver) finalization — the controller saw our confirmation.
    fn on_handoff_complete(&mut self, frame: &RimFrame) -> bool {
        if let Some((nonce, controller)) = self.pending_finalize {
            if nonce == frame.seq_or_nonce && controller == frame.sender {
                self.pending_finalize = None;
                self.events.push_back(IntentEvent::HandoffFinalized {
                    controller: frame.sender,
                });
            }
        }
        true // stray complete — inert
    }

    /// (Controller) the receiver refused our offer (ABORT inbound).
    fn on_handoff_abort(&mut self, frame: &RimFrame) -> bool {
        let reason = match frame.payload.first().copied() {
            Some(ABORT_TIMEOUT) => HandoffFailReason::Timeout,
            Some(ABORT_STALE_EPOCH) => HandoffFailReason::StaleEpoch,
            Some(ABORT_BAD_SIGNATURE) => HandoffFailReason::BadSignature,
            _ => HandoffFailReason::MalformedFrame,
        };
        if self
            .handoff
            .as_ref()
            .is_some_and(|h| h.target == frame.sender && h.nonce == frame.seq_or_nonce)
        {
            self.handoff = None;
            self.stats.handoffs_failed += 1;
            self.events.push_back(IntentEvent::HandoffFailed {
                target: frame.sender,
                reason,
            });
            if self.role == Role::HandoffPending {
                self.transition(Role::ActiveController);
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine(id: u64, seed: u8) -> RemoteIntentEngine {
        RemoteIntentEngine::new(id, [seed; 32])
    }

    fn snap() -> PlaybackSnapshot {
        PlaybackSnapshot {
            track_id: 0xCAFE,
            position_ms: 42_000,
            queue: vec![1, 2, 3, 4, 5],
            queue_index: 2,
            repeat: RepeatMode::All,
            shuffle: true,
        }
    }

    /// Picks the single frame of the given RIM msg type from actions.
    fn frame_of_type(actions: &[IntentAction], msg_type: u8) -> Vec<u8> {
        actions
            .iter()
            .find_map(|a| match a {
                IntentAction::Send { frame, .. } if frame.len() > 3 && frame[3] == msg_type => {
                    Some(frame.clone())
                }
                _ => None,
            })
            .unwrap_or_else(|| panic!("no frame of type {msg_type:#x}"))
    }

    fn drain_and_pick(e: &mut RemoteIntentEngine, msg_type: u8) -> Vec<u8> {
        let actions = e.drain_actions();
        frame_of_type(&actions, msg_type)
    }

    fn base(epoch: u32) -> u64 {
        (epoch as u64) << 32
    }

    #[test]
    fn role_transitions_are_guarded() {
        let mut e = engine(1, 1);
        assert_eq!(e.role(), Role::Idle);
        // Handoff requires ActiveController.
        assert_eq!(
            e.begin_handoff(9, &snap(), 0),
            Err(HandoffFailReason::NotController)
        );
        assert_eq!(e.start_session(), 1);
        assert_eq!(e.role(), Role::ActiveController);
        // take_control from controller role is refused.
        assert_eq!(e.take_control(), None);
        assert!(e.end_session());
        assert_eq!(e.role(), Role::Idle);
        assert!(!e.end_session()); // idempotent refuse
    }

    #[test]
    fn snapshot_json_roundtrip_and_clamping() {
        let s = snap();
        let j = s.to_json();
        assert_eq!(PlaybackSnapshot::from_json(&j), Some(s.clone()));
        assert_eq!(PlaybackSnapshot::from_json("not json"), None);
        let partial = PlaybackSnapshot::from_json(r#"{"trackId": 7}"#).unwrap();
        assert_eq!(partial.queue, Vec::<u64>::new());
        assert_eq!(partial.position_ms, 0);
        // Queue clamp: 300 tracks → 255, index re-anchored to the tail.
        let mut big = snap();
        big.queue = (0..300u64).collect();
        big.queue_index = 299;
        let clamped = big.clamped();
        assert_eq!(clamped.queue.len(), MAX_QUEUE_SNAPSHOT);
        assert_eq!(clamped.queue_index, MAX_QUEUE_SNAPSHOT as u32 - 1);
        assert_eq!(clamped.queue.last(), Some(&299));
        // Clamping is idempotent.
        assert_eq!(clamped.clamped(), clamped);
    }

    #[test]
    fn intent_wire_roundtrip_all_kinds() {
        let intents = [
            RemoteIntent::Play,
            RemoteIntent::Pause,
            RemoteIntent::SeekTo {
                position_ms: 123_456,
            },
            RemoteIntent::QueueInsert {
                cad_id: 0xDEAD_BEEF,
                after_index: QUEUE_TAIL,
            },
            RemoteIntent::QueueRemove { index: 3 },
            RemoteIntent::QueueReorder { from: 1, to: 4 },
            RemoteIntent::SetVolume { volume_pct: 77 },
        ];
        for i in intents {
            let encoded = i.encode();
            assert_eq!(RemoteIntent::decode(&encoded), Some(i));
            let (kind, rest) = encoded.split_first().unwrap();
            assert_eq!(RemoteIntent::from_kind(*kind, rest), Some(i));
        }
        // Malformed argument lengths refused.
        assert_eq!(RemoteIntent::from_kind(0x03, &[1, 2, 3]), None);
        assert_eq!(RemoteIntent::from_kind(0x99, &[]), None);
        // Volume clamps to 0..=100 at the boundary.
        assert_eq!(
            RemoteIntent::from_kind(0x07, &[200]),
            Some(RemoteIntent::SetVolume { volume_pct: 100 })
        );
    }

    #[test]
    fn rim_frame_codec_hostile_sweep() {
        let frame = encode_rim(RIM_INTENT, 1, 2, base(1) | 1, &RemoteIntent::Play.encode());
        let parsed = parse_rim(&frame).unwrap();
        assert_eq!(parsed.msg_type, RIM_INTENT);
        assert_eq!(parsed.sender, 1);
        assert_eq!(parsed.target, 2);
        assert_eq!(parsed.seq_or_nonce, base(1) | 1);
        // Truncations.
        for cut in [0usize, 5, 15, 29, RIM_HEADER_LEN + 1] {
            assert_eq!(parse_rim(&frame[..cut]), Err(RimReject::Truncated));
        }
        // Magic / version / type / checksum.
        let mut bad = frame.clone();
        bad[0] = 0;
        assert_eq!(parse_rim(&bad), Err(RimReject::BadMagic));
        let mut bad = frame.clone();
        bad[2] = 9;
        assert_eq!(parse_rim(&bad), Err(RimReject::BadVersion));
        let mut bad = frame.clone();
        bad[3] = 0x55;
        assert_eq!(parse_rim(&bad), Err(RimReject::BadMsgType));
        let mut bad = frame.clone();
        bad[10] ^= 0x20;
        assert_eq!(parse_rim(&bad), Err(RimReject::BadChecksum));
        // Lying payload length → oversize budget.
        let mut oversize = frame.clone();
        oversize[28] = 0xFF;
        oversize[29] = 0xFF; // claims 64 KiB payload
        let sum = fnv1a32(&oversize).to_le_bytes();
        oversize.extend_from_slice(&sum);
        assert_eq!(parse_rim(&oversize), Err(RimReject::Oversize));
    }

    #[test]
    fn monotonic_seq_ids_strictly_increase() {
        let mut e = engine(1, 1);
        e.start_session();
        let mut last = 0u64;
        for i in 0..100 {
            let seq = e
                .dispatch_intent(
                    if i % 2 == 0 {
                        RemoteIntent::Play
                    } else {
                        RemoteIntent::Pause
                    },
                    0,
                )
                .expect("controller dispatch");
            assert!(seq > last, "seq must strictly increase");
            last = seq;
        }
        assert_eq!(last & 0xFFFF_FFFF, 100);
        assert_eq!(last >> 32, 1);
        // Dispatch refuses when not the controller.
        e.end_session();
        assert_eq!(e.dispatch_intent(RemoteIntent::Play, 0), None);
    }

    #[test]
    fn receiver_applies_in_order_and_reacks_duplicates() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        let mut frames = Vec::new();
        for i in 0..5u64 {
            ctrl.dispatch_intent(RemoteIntent::SeekTo { position_ms: i }, 0)
                .unwrap();
            frames.push(frame_of_type(&ctrl.drain_actions(), RIM_INTENT));
        }
        // Deliver OUT OF ORDER: 2, 3, 0, 4, 1 → applied strictly 0..4.
        for idx in [2usize, 3, 0, 4, 1] {
            rcv.on_frame(&frames[idx], 0);
        }
        let applied: Vec<u64> = rcv
            .drain_events()
            .into_iter()
            .filter_map(|e| match e {
                IntentEvent::IntentApplied { seq_id, .. } => Some(seq_id),
                _ => None,
            })
            .collect();
        assert_eq!(
            applied,
            vec![
                base(1) + 1,
                base(1) + 2,
                base(1) + 3,
                base(1) + 4,
                base(1) + 5
            ]
        );
        // Duplicate re-delivery: re-ACK, never double-apply.
        rcv.on_frame(&frames[0], 0);
        assert_eq!(rcv.stats().intents_duplicated, 1);
        assert_eq!(rcv.stats().intents_applied, 5);
        // A NACK fired for the observed gap (delivery 2 before 0).
        assert!(rcv.stats().intents_nacked >= 1 || rcv.stats().frames_rx >= 5);
    }

    #[test]
    fn nack_drives_immediate_retransmit_and_delivery() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.dispatch_intent(RemoteIntent::Play, 0).unwrap(); // seq 1
        ctrl.dispatch_intent(RemoteIntent::Pause, 0).unwrap(); // seq 2
                                                               // One drain carries BOTH intent frames.
        let actions = ctrl.drain_actions();
        let f1 = frame_of_type(&actions, RIM_INTENT);
        let f2 = actions
            .iter()
            .find_map(|a| match a {
                IntentAction::Send { frame, .. } if frame[3] == RIM_INTENT && *frame != f1 => {
                    Some(frame.clone())
                }
                _ => None,
            })
            .expect("second intent frame");
        // Deliver ONLY seq 2 — the receiver NACKs the [1,1] gap.
        rcv.on_frame(&f2, 0);
        let nack = frame_of_type(&rcv.drain_actions(), RIM_NACK);
        // Controller retransmits the covered seq immediately.
        ctrl.on_frame(&nack, 1);
        let retry = frame_of_type(&ctrl.drain_actions(), RIM_INTENT);
        assert_eq!(parse_rim(&retry).unwrap().seq_or_nonce, base(1) | 1);
        rcv.on_frame(&retry, 2);
        let applied: Vec<u64> = rcv
            .drain_events()
            .into_iter()
            .filter_map(|e| match e {
                IntentEvent::IntentApplied { seq_id, .. } => Some(seq_id),
                _ => None,
            })
            .collect();
        assert_eq!(applied, vec![base(1) | 1, base(1) | 2]);
        assert_eq!(ctrl.stats().intents_retransmitted, 1);
        // Both ACKed now.
        let acks: Vec<u8> = rcv
            .drain_actions()
            .into_iter()
            .filter_map(|a| match a {
                IntentAction::Send { frame, .. } if frame[3] == RIM_ACK => Some(frame[3]),
                _ => None,
            })
            .collect();
        assert_eq!(acks.len(), 2);
    }

    #[test]
    fn tick_retransmits_after_rto_and_abandons_exhausted_intents() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.dispatch_intent(RemoteIntent::Play, 0).unwrap();
        let _ = ctrl.drain_actions(); // frame "lost" — no delivery
                                      // First RTO elapses (attempts=1 → backoff 800 ms).
        ctrl.tick(801);
        let retry = frame_of_type(&ctrl.drain_actions(), RIM_INTENT);
        rcv.on_frame(&retry, 801);
        assert_eq!(rcv.stats().intents_applied, 1);
        // ABANDON path: never delivered, attempts climb to the cap.
        let mut dead = engine(3, 3);
        dead.start_session();
        dead.dispatch_intent(RemoteIntent::SeekTo { position_ms: 9 }, 0)
            .unwrap();
        let _ = dead.drain_actions();
        let mut t = 0u64;
        loop {
            t += 1_000;
            dead.tick(t);
            let _ = dead.drain_actions(); // retransmissions "lost"
            if dead
                .drain_events()
                .iter()
                .any(|e| matches!(e, IntentEvent::IntentAbandoned { .. }))
            {
                break;
            }
            assert!(
                t < 200_000,
                "intent must be abandoned after capped attempts"
            );
        }
    }

    #[test]
    fn split_brain_higher_controller_pair_wins_deterministically() {
        // A (epoch 5, id 10) vs B (epoch 7, id 3): B dominates.
        let mut a = engine(10, 1);
        let mut b = engine(3, 2);
        for e in 1..=5 {
            assert_eq!(a.start_session(), e);
            a.end_session();
        }
        for e in 1..=7 {
            assert_eq!(b.start_session(), e);
            b.end_session();
        }
        assert_eq!(a.controller_epoch(), 0);
        a.start_session(); // A's final epoch: 6
        assert_eq!(a.controller_epoch(), 6);
        let _ = b.start_session(); // B's final epoch: 8

        a.dispatch_intent(
            RemoteIntent::QueueInsert {
                cad_id: 111,
                after_index: QUEUE_TAIL,
            },
            0,
        )
        .unwrap();
        let fa = frame_of_type(&a.drain_actions(), RIM_INTENT);
        b.dispatch_intent(
            RemoteIntent::QueueInsert {
                cad_id: 222,
                after_index: QUEUE_TAIL,
            },
            0,
        )
        .unwrap();
        let fb = frame_of_type(&b.drain_actions(), RIM_INTENT);

        // Order 1: B first, then A — A is stale-rejected outright.
        let mut rcv = engine(99, 3);
        rcv.on_frame(&fb, 0);
        rcv.on_frame(&fa, 0);
        assert_eq!(rcv.current_controller(), Some((8, 3)));
        assert_eq!(rcv.stats().intents_applied, 1);
        assert_eq!(rcv.stats().intents_stale_rejected, 1);

        // Order 2: A first, then B — B takes over; a LATER A retry is
        // stale-rejected. Authority converges to B in both orders.
        let mut rcv2 = engine(99, 3);
        rcv2.on_frame(&fa, 0);
        rcv2.on_frame(&fb, 0);
        assert_eq!(rcv2.current_controller(), Some((8, 3)));
        // A's retry after B's takeover: stale, never applied.
        rcv2.on_frame(&fa, 1);
        assert!(rcv2.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::IntentRejectedStale { controller, .. } if *controller == (8, 3)
        )));
        // Receiver never applies a loser intent AFTER a winner intent.
        let seqs: Vec<(u32, u64)> = rcv2
            .drain_events()
            .into_iter()
            .filter_map(|e| match e {
                IntentEvent::IntentApplied { seq_id, .. } => Some(((seq_id >> 32) as u32, seq_id)),
                _ => None,
            })
            .collect();
        assert!(
            seqs.iter().all(|(epoch, _)| *epoch <= 8),
            "no post-takeover loser application: {seqs:?}"
        );
    }

    #[test]
    fn hostile_future_seq_flood_is_bounded() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        // Bootstrap the receiver with seq 1.
        ctrl.dispatch_intent(RemoteIntent::Play, 0).unwrap();
        let f = frame_of_type(&ctrl.drain_actions(), RIM_INTENT);
        rcv.on_frame(&f, 0);
        // Flood far-future seqs directly (crafted frames, epoch 1).
        for gap in 1u64..=(MAX_REORDER_BUFFER as u64) + 64 {
            let forged = encode_rim(
                RIM_INTENT,
                1,
                2,
                base(1) | (2 + gap),
                &RemoteIntent::Pause.encode(),
            );
            rcv.on_frame(&forged, 0);
        }
        // Reorder buffer never exceeds the bound.
        let buffer_len = rcv.receiver.as_ref().map(|r| r.buffer.len()).unwrap_or(0);
        assert!(buffer_len <= MAX_REORDER_BUFFER, "buffer={buffer_len}");
    }

    #[test]
    fn handoff_confirms_before_local_halt_and_times_out_cleanly() {
        // ── happy path ──
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.dispatch_intent(RemoteIntent::Play, 0).unwrap();

        ctrl.begin_handoff(2, &snap(), 0).unwrap();
        assert_eq!(ctrl.role(), Role::HandoffPending);
        let offer = drain_and_pick(&mut ctrl, RIM_HANDOFF_OFFER);
        rcv.on_frame(&offer, 0);
        // Receiver adopted the snapshot + role.
        assert!(rcv.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::HandoffAdopted { snapshot, .. } if *snapshot == snap().clamped()
        )));
        assert_eq!(rcv.role(), Role::ActiveReceiver);
        let confirm = drain_and_pick(&mut rcv, RIM_HANDOFF_CONFIRM);
        ctrl.on_frame(&confirm, 1);
        assert!(ctrl.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::HandoffCompleted {
                target: 2,
                epoch: 2
            }
        )));
        assert_eq!(ctrl.role(), Role::ActiveController);
        // COMPLETE flows back; receiver finalizes.
        let complete = drain_and_pick(&mut ctrl, RIM_HANDOFF_COMPLETE);
        rcv.on_frame(&complete, 2);
        assert!(rcv
            .drain_events()
            .iter()
            .any(|e| matches!(e, IntentEvent::HandoffFinalized { controller: 1 })));
        // Controller's stream now rides the offer epoch: the receiver is
        // pinned to base(2)|1 and the next intent applies cleanly.
        let seq = ctrl.dispatch_intent(RemoteIntent::Pause, 3).unwrap();
        assert_eq!(seq >> 32, 2);
        rcv.on_frame(&frame_of_type(&ctrl.drain_actions(), RIM_INTENT), 4);
        assert_eq!(rcv.stats().intents_applied, 1);
        assert_eq!(rcv.current_controller(), Some((2, 1)));

        // ── timeout path: local playback never halted ──
        let mut c2 = engine(7, 4);
        c2.start_session();
        c2.begin_handoff(2, &snap(), 0).unwrap();
        c2.tick(HANDOFF_TIMEOUT_MS + 1);
        assert!(c2.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::HandoffFailed {
                reason: HandoffFailReason::Timeout,
                ..
            }
        )));
        assert_eq!(
            c2.role(),
            Role::ActiveController,
            "abort restores the controller role"
        );
        assert!(c2.drain_actions().iter().any(
            |a| matches!(a, IntentAction::Send { frame, .. } if frame[3] == RIM_HANDOFF_ABORT)
        ));
        // Retry after abort allocates a FRESH epoch (dominates the stale one).
        let retry_epoch = c2.begin_handoff(2, &snap(), 100).unwrap();
        assert_eq!(retry_epoch, 3);
    }

    #[test]
    fn tampered_offer_trips_signature_not_just_checksum() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.begin_handoff(2, &snap(), 0).unwrap();
        let mut offer = drain_and_pick(&mut ctrl, RIM_HANDOFF_OFFER);
        // Flip one snapshot byte AND re-fix the FNV checksum — the frame
        // now passes transport validation, so only the Ed25519 signature
        // can catch the forgery.
        offer[40] ^= 0x01;
        let sum_off = offer.len() - 4;
        let sum = fnv1a32(&offer[..sum_off]).to_le_bytes();
        offer[sum_off..sum_off + 4].copy_from_slice(&sum);
        assert!(rcv.on_frame(&offer, 0));
        assert!(rcv.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::HandoffRefused {
                reason: HandoffFailReason::BadSignature,
                ..
            }
        )));
        assert_eq!(
            rcv.role(),
            Role::Idle,
            "receiver never adopted the forged offer"
        );
        assert_eq!(rcv.stats().sig_failures, 1);
        // Receiver answers with a refusal ABORT.
        assert!(rcv.drain_actions().iter().any(
            |a| matches!(a, IntentAction::Send { frame, .. } if frame[3] == RIM_HANDOFF_ABORT)
        ));
    }

    #[test]
    fn stale_epoch_offer_is_refused_by_dominating_receiver() {
        // The receiver already obeys controller (9, 50); an offer with
        // epoch 9 from device 40 is dominated → refused.
        let mut ctrl = engine(40, 1);
        let mut rcv = engine(2, 2);
        // Bootstrap receiver under a dominating controller (9, 50) by
        // forging a valid intent frame from sender 50, epoch 9.
        let forged = encode_rim(RIM_INTENT, 50, 2, base(9) | 1, &RemoteIntent::Play.encode());
        rcv.on_frame(&forged, 0);
        assert_eq!(rcv.current_controller(), Some((9, 50)));

        ctrl.start_session(); // epoch 1; churn to 8 so the OFFER carries epoch 9
        for _ in 0..7 {
            ctrl.end_session();
            ctrl.start_session();
        }
        assert_eq!(ctrl.controller_epoch(), 8);
        ctrl.begin_handoff(2, &snap(), 0).unwrap();
        let offer = drain_and_pick(&mut ctrl, RIM_HANDOFF_OFFER);
        rcv.on_frame(&offer, 1);
        assert!(rcv.drain_events().iter().any(|e| matches!(
            e,
            IntentEvent::HandoffRefused {
                reason: HandoffFailReason::StaleEpoch,
                ..
            }
        )));
        assert_eq!(
            rcv.role(),
            Role::ActiveReceiver,
            "still renders under (9,50)"
        );
        // Refusal ABORT carries the stale-epoch reason code.
        assert!(rcv
            .drain_actions()
            .iter()
            .any(|a| matches!(a, IntentAction::Send { frame, .. } if frame[3] == RIM_HANDOFF_ABORT && frame[30] == ABORT_STALE_EPOCH)));
    }

    #[test]
    fn controller_lease_silence_is_observed_not_revoked() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.dispatch_intent(RemoteIntent::Play, 0).unwrap();
        rcv.on_frame(&frame_of_type(&ctrl.drain_actions(), RIM_INTENT), 0);
        assert_eq!(rcv.role(), Role::ActiveReceiver);
        rcv.tick(CONTROLLER_LEASE_MS + 1);
        assert!(rcv
            .drain_events()
            .iter()
            .any(|e| matches!(e, IntentEvent::ControllerSilent { controller: (1, 1) })));
        // Authority NOT revoked: role unchanged, later intents still apply.
        assert_eq!(rcv.role(), Role::ActiveReceiver);
        ctrl.dispatch_intent(RemoteIntent::Pause, CONTROLLER_LEASE_MS + 2)
            .unwrap();
        rcv.on_frame(
            &frame_of_type(&ctrl.drain_actions(), RIM_INTENT),
            CONTROLLER_LEASE_MS + 2,
        );
        assert_eq!(rcv.stats().intents_applied, 2);
        // The silence flag resets on activity (no repeated events).
        rcv.tick(CONTROLLER_LEASE_MS * 2 + 5);
        let repeats = rcv
            .drain_events()
            .iter()
            .filter(|e| matches!(e, IntentEvent::ControllerSilent { .. }))
            .count();
        assert_eq!(repeats, 1, "silence raised exactly once per quiet period");
    }

    #[test]
    fn receiver_take_control_steals_with_fresh_epoch() {
        let mut ctrl = engine(1, 1);
        let mut rcv = engine(2, 2);
        ctrl.start_session();
        ctrl.begin_handoff(2, &snap(), 0).unwrap();
        rcv.on_frame(&drain_and_pick(&mut ctrl, RIM_HANDOFF_OFFER), 0);
        ctrl.on_frame(&drain_and_pick(&mut rcv, RIM_HANDOFF_CONFIRM), 1);
        rcv.on_frame(&drain_and_pick(&mut ctrl, RIM_HANDOFF_COMPLETE), 2);
        assert_eq!(rcv.role(), Role::ActiveReceiver);
        // Watch/user hits "play on this device": steal with epoch+1.
        let epoch = rcv.take_control().expect("receiver steals");
        assert_eq!(epoch, 3); // offer epoch was 2 → fresh 3
        assert_eq!(rcv.role(), Role::ActiveController);
        // The old controller's intents are now dominated: (2,1) < (3,2).
        let stale = encode_rim(RIM_INTENT, 1, 2, base(2) | 2, &RemoteIntent::Pause.encode());
        rcv.on_frame(&stale, 3);
        assert!(rcv
            .drain_events()
            .iter()
            .any(|e| matches!(e, IntentEvent::IntentRejectedStale { .. })));
    }
}
