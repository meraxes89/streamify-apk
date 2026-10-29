//! wear_sync.rs — WearOS compact P2P binary sync protocol & offline-cache
//! transfer coordinator (Phase 4, directive §2.3, gap #55).
//!
//! MISSION: keep a watch face and its primary phone host in sync over
//! transports that are hostile to chatty protocols — Bluetooth RFCOMM
//! (127 B–1 KiB frames, tens-of-ms latency) and Wi-Fi Direct — using an
//! ultra-compact bit-packed binary wire format, and push offline audio
//! caches to watch storage with bandwidth pacing that respects the
//! watch's battery.
//!
//! ── WIRE FORMAT ("WSX frame family" — Wear Sync eXchange) ─────────────
//!   offset  field         len  notes
//!   0       magic u16      2    0x5753 ('W','S')
//!   2       version u8     1    0x01
//!   3       frame_type u8  1    0x01 QUEUE_DELTA · 0x02 QUEUE_ACTION ·
//!   4                          0x03 TRACK_RATING · 0x04 JAM_UPVOTE ·
//!   5                          0x05 CACHE_MANIFEST · 0x06 CACHE_ACK ·
//!   6                          0x07 CACHE_CHUNK · 0x08 CACHE_CONTROL ·
//!   7                          0x09 SYNC_ACK
//!   4       seq u16        2    per-direction frame counter (echoed by
//!   5                          SYNC_ACK; wraps at 65 535)
//!   6       flags u8       1    bit0 = ack_requested
//!   7       payload_len u16 2
//!   9       payload        n    ≤ 1 KiB
//!   9+n     fnv1a u32      4    over [0..9+n)
//!
//! Total overhead: 13 bytes. Control frames (queue/rating/upvote/ack)
//! land at 14–32 bytes — BLE-adjacent; cache data frames at ≤ 1 KiB+13 —
//! one RFCOMM frame. A fourth frozen family: mesh 0x5354 = gossip,
//! SCNX = Connect presence, RIM = remote intents; WSX = wear sync.
//!
//! ── COMPACTION (gap #55) ───────────────────────────────────────────────
//!   • VarInts (LEB128): cad ids / lengths / positions encode in 1–5 B
//!     instead of fixed 4/8 B (a 3-entry queue delta serializes to ~11 B
//!     payload, ~24 B on the wire).
//!   • Bit-packing: queue ops 2 bits (4 per byte), ratings 4 bits +
//!     thumbs flag 4 bits (one byte), ack bitmaps 8 segments per byte.
//!   • ZERO-ALLOCATION codec: [`WearBuf`] writes into caller-supplied
//!     stack buffers ([`encode_frame_into`], `encode_*` payloads);
//!     decoders borrow the input slice. The coordinators then copy each
//!     encoded frame ONCE into the action queue — allocation happens at
//!     the transport boundary, never inside the codec.
//!
//! ── BIDIRECTIONAL QUEUE & VOTING SYNC (directive §2.3) ─────────────────
//!   phone → watch : QUEUE_DELTA (miniature queue mirror updates — the
//!                   watch face renders the next few tracks)
//!   watch → phone : QUEUE_ACTION (watch-side queue edits), TRACK_RATING
//!                   (1..5 stars + thumbs override), JAM_UPVOTE (Jam
//!                   upvote carrying the watch's 4-byte voter nonce —
//!                   the phone maps it onto the mesh CRDT identity via
//!                   `VoterId::from_nonce`)
//!   Reliable mini-protocol: ack_requested frames are retried on RTO
//!   until a SYNC_ACK echo arrives (bounded attempts, event on abandon)
//!   — the watch's vote must never vanish on a flaky RFCOMM link.
//!
//! ── THROTTLED OFFLINE CACHE SYNC (directive §2.3) ──────────────────────
//!   The phone serializes the manifest (per-track layout + total hash +
//!   per-segment Blake3 hashes — Phase 3 verifier discipline,
//!   miniaturized) into a byte stream, then chunks it across
//!   CACHE_MANIFEST frames carrying (manifest_id, chunk_idx,
//!   chunk_count, data); the watch reassembles BY INDEX — immune to
//!   network reordering — and parses once complete. CACHE_CHUNK frames
//!   carry 1 KiB segments under a TOKEN
//!   BUCKET pacer: refill = rate × elapsed, capped burst, in-flight
//!   window of 16 unacked segments (backpressure). The watch verifies
//!   EVERY segment hash before buffering it and acks in 64-segment
//!   bitmap batches; corrupt segments draw an immediate SEG_BAD control
//!   → priority retransmit. Track completion re-verifies the assembled
//!   file against the manifest total hash (byte-equivalence gate).
//!   Battery-aware pacing (auto-pause + hysteresis + eco rate):
//!     pause  when pct < 20  and not charging
//!     resume when pct ≥ 30  or charging
//!     eco    (rate × 0.5) when pct < 40 and not charging
//!
//! PURE LOGIC: no sockets, no wall clock (`now_ms` injected); hostile-
//! input discipline throughout (bounds checks, checksum, budget caps,
//! counters on every refusal).

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};

use crate::chunk_verifier::hash_payload;

/// WSX magic: 'W','S' (u16 LE = 0x5753).
pub const WSX_MAGIC: u16 = 0x5753;
/// Wear sync wire protocol version.
pub const WSX_VERSION: u8 = 0x01;
/// WSX header length.
pub const WSX_HEADER_LEN: usize = 9;
/// Payload budget (u16 length field; data frames carry 1 KiB segments).
pub const MAX_WSX_PAYLOAD: usize = 1024;
/// Whole-frame budget (header + payload + checksum).
pub const MAX_WSX_FRAME: usize = WSX_HEADER_LEN + MAX_WSX_PAYLOAD + 4;
/// Offline-cache segment size (1000 B data — ≤ 10 B of varint header
/// keeps the whole CACHE_CHUNK payload inside the 1 KiB budget, one
/// RFCOMM-friendly frame).
pub const SEGMENT_SIZE: usize = 1000;
/// Segment-count ceiling per track (4 MiB @ 1 KiB — a full-length Opus
/// track; larger caches sync track-by-track at the app layer).
pub const MAX_TRACK_SEGMENTS: u32 = 4096;
/// Tracks per manifest (a watch holds ~8–16 offline tracks).
pub const MAX_CACHE_TRACKS: usize = 16;
/// Manifest byte-stream budget (bounded accumulation on the watch; one
/// full manifest must frame into ≤ 255 CACHE_MANIFEST frames).
pub const MAX_MANIFEST_BYTES: usize = 255 * (MAX_WSX_PAYLOAD - 8);
/// Queue mirror bound (the watch face shows the next handful).
pub const MAX_QUEUE_MIRROR: usize = 64;
/// Unacked-segment in-flight window (backpressure for the pacer).
pub const CACHE_WINDOW: usize = 16;
/// Retransmit timeout for cache segments + ack_requested control frames.
pub const WEAR_RTO_MS: u64 = 1_500;
/// Max attempts before an ack_requested control frame is abandoned.
pub const MAX_CONTROL_ATTEMPTS: u32 = 8;
/// Watch acks are batched: one bitmap per this many verified segments.
pub const ACK_FLUSH_SEGS: u32 = 16;

// Frame types (frozen).
pub const WSX_QUEUE_DELTA: u8 = 0x01;
pub const WSX_QUEUE_ACTION: u8 = 0x02;
pub const WSX_TRACK_RATING: u8 = 0x03;
pub const WSX_JAM_UPVOTE: u8 = 0x04;
pub const WSX_CACHE_MANIFEST: u8 = 0x05;
pub const WSX_CACHE_ACK: u8 = 0x06;
pub const WSX_CACHE_CHUNK: u8 = 0x07;
pub const WSX_CACHE_CONTROL: u8 = 0x08;
pub const WSX_SYNC_ACK: u8 = 0x09;

// Cache control codes (frozen).
pub const CTRL_BATTERY: u8 = 0x01;
pub const CTRL_USER_PAUSE: u8 = 0x02;
pub const CTRL_USER_RESUME: u8 = 0x03;
pub const CTRL_CANCEL: u8 = 0x04;
pub const CTRL_TRACK_DONE: u8 = 0x05;
pub const CTRL_SEG_BAD: u8 = 0x06;
pub const CTRL_TRACK_BAD: u8 = 0x07;

/// flags bit: sender wants a SYNC_ACK echo.
pub const FLAG_ACK_REQUESTED: u8 = 0x01;

/// FNV-1a/32 (house checksum; local copy, family polynomial).
fn fnv1a32(data: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for &b in data {
        hash ^= b as u32;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Blake3 over a slice (watch-side segment verification).
fn seg_hash(data: &[u8]) -> [u8; 32] {
    hash_payload(data)
}

// ─────────────────────────────────────────────── zero-alloc wire codec

/// Stack-buffer writer — the zero-allocation encode surface.
pub struct WearBuf<'a> {
    buf: &'a mut [u8],
    pos: usize,
}

impl<'a> WearBuf<'a> {
    pub fn new(buf: &'a mut [u8]) -> Self {
        WearBuf { buf, pos: 0 }
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    fn put_u8(&mut self, v: u8) -> Option<()> {
        *self.buf.get_mut(self.pos)? = v;
        self.pos += 1;
        Some(())
    }

    /// LEB128 unsigned varint (1 byte per 7 bits).
    fn put_varint(&mut self, v: u64) -> Option<()> {
        let mut v = v;
        loop {
            let byte = (v & 0x7F) as u8;
            v >>= 7;
            if v == 0 {
                self.put_u8(byte)?;
                return Some(());
            }
            self.put_u8(byte | 0x80)?;
        }
    }

    fn put_bytes(&mut self, data: &[u8]) -> Option<()> {
        for &b in data {
            self.put_u8(b)?;
        }
        Some(())
    }

    /// Packed 2-bit fields (LSB-first, 4 per byte) — queue ops.
    fn put_pack2(&mut self, values: &[u8]) -> Option<()> {
        for chunk in values.chunks(4) {
            let mut byte = 0u8;
            for (i, &v) in chunk.iter().enumerate() {
                byte |= (v & 0x03) << (i * 2);
            }
            self.put_u8(byte)?;
        }
        Some(())
    }
}

/// Decodes a LEB128 varint at `pos`; returns (value, next_pos).
fn varint_at(bytes: &[u8], pos: usize) -> Option<(u64, usize)> {
    let mut value: u64 = 0;
    let mut shift = 0u32;
    let mut p = pos;
    loop {
        let b = *bytes.get(p)?;
        p += 1;
        value |= ((b & 0x7F) as u64) << shift;
        if b & 0x80 == 0 {
            return Some((value, p));
        }
        shift += 7;
        if shift > 63 {
            return None; // hostile: >10-byte varint
        }
    }
}

/// Unpacks `n` 2-bit fields (LSB-first) starting at `pos`.
fn unpack2(bytes: &[u8], pos: usize, n: usize) -> Option<(Vec<u8>, usize)> {
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let byte = *bytes.get(pos + i / 4)?;
        out.push((byte >> ((i % 4) * 2)) & 0x03);
    }
    Some((out, pos + n.div_ceil(4)))
}

/// Encodes one WSX frame into the caller's buffer; returns the length.
pub fn encode_frame_into(
    out: &mut [u8],
    frame_type: u8,
    seq: u16,
    flags: u8,
    payload: &[u8],
) -> Option<usize> {
    if payload.len() > MAX_WSX_PAYLOAD || out.len() < WSX_HEADER_LEN + payload.len() + 4 {
        return None;
    }
    out[0..2].copy_from_slice(&WSX_MAGIC.to_le_bytes());
    out[2] = WSX_VERSION;
    out[3] = frame_type;
    out[4..6].copy_from_slice(&seq.to_le_bytes());
    out[6] = flags;
    out[7..9].copy_from_slice(&(payload.len() as u16).to_le_bytes());
    out[9..9 + payload.len()].copy_from_slice(payload);
    let end = WSX_HEADER_LEN + payload.len();
    let sum = fnv1a32(&out[..end]);
    out[end..end + 4].copy_from_slice(&sum.to_le_bytes());
    Some(end + 4)
}

/// Owned-frame convenience (coordinator boundary: ONE copy).
/// Owned-frame convenience (coordinator boundary: ONE copy — also the
/// test/binding surface for hand-built frames).
pub fn encode_frame(frame_type: u8, seq: u16, flags: u8, payload: &[u8]) -> Vec<u8> {
    let mut buf = vec![0u8; WSX_HEADER_LEN + payload.len() + 4];
    let n =
        encode_frame_into(&mut buf, frame_type, seq, flags, payload).expect("payload pre-budgeted");
    buf.truncate(n);
    buf
}

/// A parsed WSX frame (payload borrowed).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WsxFrame<'a> {
    pub frame_type: u8,
    pub seq: u16,
    pub flags: u8,
    pub payload: &'a [u8],
}

/// Why a frame was refused at the boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WsxReject {
    Truncated,
    BadMagic,
    BadVersion,
    BadType,
    Oversize,
    BadChecksum,
}

impl std::fmt::Display for WsxReject {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            WsxReject::Truncated => "frame truncated below WSX header",
            WsxReject::BadMagic => "magic mismatch (not a WSX frame)",
            WsxReject::BadVersion => "unsupported WSX version",
            WsxReject::BadType => "unknown WSX frame type",
            WsxReject::Oversize => "payload exceeds WSX budget",
            WsxReject::BadChecksum => "FNV-1a checksum mismatch",
        };
        f.write_str(s)
    }
}

pub fn parse_frame(bytes: &[u8]) -> Result<WsxFrame<'_>, WsxReject> {
    if bytes.len() < WSX_HEADER_LEN + 4 {
        return Err(WsxReject::Truncated);
    }
    if u16::from_le_bytes([bytes[0], bytes[1]]) != WSX_MAGIC {
        return Err(WsxReject::BadMagic);
    }
    if bytes[2] != WSX_VERSION {
        return Err(WsxReject::BadVersion);
    }
    let frame_type = bytes[3];
    if !(WSX_QUEUE_DELTA..=WSX_SYNC_ACK).contains(&frame_type) {
        return Err(WsxReject::BadType);
    }
    let plen = u16::from_le_bytes([bytes[7], bytes[8]]) as usize;
    if plen > MAX_WSX_PAYLOAD {
        return Err(WsxReject::Oversize);
    }
    if bytes.len() < WSX_HEADER_LEN + plen + 4 {
        return Err(WsxReject::Truncated);
    }
    let sum_off = bytes.len() - 4;
    let wire_sum = u32::from_le_bytes([
        bytes[sum_off],
        bytes[sum_off + 1],
        bytes[sum_off + 2],
        bytes[sum_off + 3],
    ]);
    if fnv1a32(&bytes[..sum_off]) != wire_sum {
        return Err(WsxReject::BadChecksum);
    }
    Ok(WsxFrame {
        frame_type,
        seq: u16::from_le_bytes([bytes[4], bytes[5]]),
        flags: bytes[6],
        payload: &bytes[WSX_HEADER_LEN..WSX_HEADER_LEN + plen],
    })
}

/// Internal borrowing reader (decoder side).
struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Reader { bytes, pos: 0 }
    }

    fn u8(&mut self) -> Option<u8> {
        let v = *self.bytes.get(self.pos)?;
        self.pos += 1;
        Some(v)
    }

    fn varint(&mut self) -> Option<u64> {
        let (v, next) = varint_at(self.bytes, self.pos)?;
        self.pos = next;
        Some(v)
    }

    fn hash32(&mut self) -> Option<[u8; 32]> {
        let mut h = [0u8; 32];
        for b in h.iter_mut() {
            *b = self.u8()?;
        }
        Some(h)
    }

    fn u16_pair(&mut self) -> Option<u16> {
        let lo = self.u8()?;
        let hi = self.u8()?;
        Some(u16::from_le_bytes([lo, hi]))
    }

    fn rest(&self) -> &'a [u8] {
        self.bytes.get(self.pos..).unwrap_or(&[])
    }
}

// ─────────────────────────────────────────────── semantic payload codecs

/// Queue mutation ops (2-bit wire codes; shared by QUEUE_DELTA batches
/// and single QUEUE_ACTION edits).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueueOp {
    Add { cad_id: u64, after_pos: u32 },
    Remove { index: u32 },
    Clear,
    Move { from: u32, to: u32 },
}

impl QueueOp {
    fn code(&self) -> u8 {
        match self {
            QueueOp::Add { .. } => 0,
            QueueOp::Remove { .. } => 1,
            QueueOp::Clear => 2,
            QueueOp::Move { .. } => 3,
        }
    }

    fn decode(code: u8, r: &mut Reader<'_>) -> Option<QueueOp> {
        match code {
            0 => {
                let cad_id = r.varint()?;
                let after = r.varint()? as u32;
                Some(QueueOp::Add {
                    cad_id,
                    after_pos: after,
                })
            }
            1 => Some(QueueOp::Remove {
                index: r.varint()? as u32,
            }),
            2 => Some(QueueOp::Clear),
            3 => {
                let from = r.varint()? as u32;
                let to = r.varint()? as u32;
                Some(QueueOp::Move { from, to })
            }
            _ => None,
        }
    }
}

/// Bound for a queue delta batch.
pub const MAX_QUEUE_DELTA_ENTRIES: usize = 32;

fn encode_op_body(op: &QueueOp, w: &mut WearBuf<'_>) -> Option<()> {
    match op {
        QueueOp::Add { cad_id, after_pos } => {
            w.put_varint(*cad_id)?;
            w.put_varint(*after_pos as u64)?;
        }
        QueueOp::Remove { index } => w.put_varint(*index as u64)?,
        QueueOp::Clear => {}
        QueueOp::Move { from, to } => {
            w.put_varint(*from as u64)?;
            w.put_varint(*to as u64)?;
        }
    }
    Some(())
}

/// QUEUE_DELTA payload codec (phone → watch mirror updates).
pub fn encode_queue_delta(ops: &[QueueOp], out: &mut [u8]) -> Option<usize> {
    if ops.is_empty() || ops.len() > MAX_QUEUE_DELTA_ENTRIES {
        return None;
    }
    let mut w = WearBuf::new(out);
    w.put_varint(ops.len() as u64)?;
    let codes: Vec<u8> = ops.iter().map(|o| o.code()).collect();
    w.put_pack2(&codes)?;
    for op in ops {
        encode_op_body(op, &mut w)?;
    }
    Some(w.pos())
}

pub fn decode_queue_delta(payload: &[u8]) -> Option<Vec<QueueOp>> {
    let mut r = Reader::new(payload);
    let n = r.varint()? as usize;
    if n == 0 || n > MAX_QUEUE_DELTA_ENTRIES {
        return None;
    }
    let (codes, pos) = unpack2(payload, r.pos, n)?;
    let mut rr = Reader::new(payload);
    rr.pos = pos;
    let mut ops = Vec::with_capacity(n);
    for &c in &codes {
        ops.push(QueueOp::decode(c, &mut rr)?);
    }
    Some(ops)
}

/// QUEUE_ACTION payload codec (watch → phone edit; same op grammar).
pub fn encode_queue_action(op: &QueueOp, out: &mut [u8]) -> Option<usize> {
    let mut w = WearBuf::new(out);
    w.put_u8(op.code())?;
    encode_op_body(op, &mut w)?;
    Some(w.pos())
}

pub fn decode_queue_action(payload: &[u8]) -> Option<QueueOp> {
    let mut r = Reader::new(payload);
    let code = r.u8()?;
    QueueOp::decode(code, &mut r)
}

/// One track rating (1..=5 stars + thumbs-up override), 4+4 bit-packed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrackRating {
    pub cad_id: u64,
    /// 1..=5 (0 is refused at encode/decode).
    pub rating: u8,
    pub thumbs_up: bool,
}

pub fn encode_track_rating(r: &TrackRating, out: &mut [u8]) -> Option<usize> {
    if !(1..=5).contains(&r.rating) {
        return None;
    }
    let mut w = WearBuf::new(out);
    w.put_varint(r.cad_id)?;
    // packed byte: low nibble = rating, high nibble = flags (bit0 = thumbs)
    w.put_u8((r.rating & 0x0F) | (u8::from(r.thumbs_up) << 4))?;
    Some(w.pos())
}

pub fn decode_track_rating(payload: &[u8]) -> Option<TrackRating> {
    let mut r = Reader::new(payload);
    let cad_id = r.varint()?;
    let packed = r.u8()?;
    let rating = packed & 0x0F;
    if !(1..=5).contains(&rating) {
        return None;
    }
    Some(TrackRating {
        cad_id,
        rating,
        thumbs_up: packed & 0x10 != 0,
    })
}

/// A Jam upvote from the watch face (voter nonce maps onto the mesh
/// CRDT identity via `VoterId::from_nonce`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JamUpvoteMsg {
    pub cad_id: u64,
    pub voter_nonce: [u8; 4],
    pub up: bool,
}

pub fn encode_jam_upvote(u: &JamUpvoteMsg, out: &mut [u8]) -> Option<usize> {
    let mut w = WearBuf::new(out);
    w.put_varint(u.cad_id)?;
    w.put_bytes(&u.voter_nonce)?;
    w.put_u8(u8::from(u.up))?;
    Some(w.pos())
}

pub fn decode_jam_upvote(payload: &[u8]) -> Option<JamUpvoteMsg> {
    let mut r = Reader::new(payload);
    let cad_id = r.varint()?;
    let mut nonce = [0u8; 4];
    for b in nonce.iter_mut() {
        *b = r.u8()?;
    }
    let up = r.u8()? != 0;
    Some(JamUpvoteMsg {
        cad_id,
        voter_nonce: nonce,
        up,
    })
}

// ─────────────────────────────────────────────── manifest byte codec

/// One offline-cache track: identity + layout + integrity roots.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheTrackInfo {
    pub cad_id: u64,
    pub total_len: u64,
    /// Segment size in bytes (≤ SEGMENT_SIZE; only the final segment may
    /// be short).
    pub seg_size: u32,
    /// Blake3 over the whole file (final assembly check).
    pub total_hash: [u8; 32],
}

/// One manifest entry (info + per-segment hashes).
#[derive(Debug, Clone)]
pub struct ManifestEntry {
    pub info: CacheTrackInfo,
    /// Blake3 of each segment, in order (len == segment count).
    pub seg_hashes: Vec<[u8; 32]>,
}

/// Segment-count sanity for a track entry.
pub fn segments_for(total_len: u64, seg_size: u32) -> Option<u32> {
    if seg_size == 0 || seg_size as usize > SEGMENT_SIZE || total_len == 0 {
        return None;
    }
    let n = total_len.div_ceil(seg_size as u64);
    if n > MAX_TRACK_SEGMENTS as u64 {
        return None;
    }
    Some(n as u32)
}

/// Serializes the full manifest byte stream (count varint + entries).
/// The phone chunks this across CACHE_MANIFEST frames; the watch
/// accumulates and parses once complete.
pub fn encode_manifest_bytes(entries: &[ManifestEntry]) -> Option<Vec<u8>> {
    if entries.is_empty() || entries.len() > MAX_CACHE_TRACKS {
        return None;
    }
    // Pre-size the buffer from a worst-case estimate, write, truncate.
    let est = 10
        + entries
            .iter()
            .map(|e| 30 + 32 + 32 * e.seg_hashes.len())
            .sum::<usize>();
    if est > MAX_MANIFEST_BYTES {
        return None;
    }
    let mut out = vec![0u8; est];
    let n = {
        let mut w = WearBuf::new(&mut out);
        w.put_varint(entries.len() as u64)?;
        for e in entries {
            if segments_for(e.info.total_len, e.info.seg_size)? as usize != e.seg_hashes.len() {
                return None; // hash count must match the layout exactly
            }
            w.put_varint(e.info.cad_id)?;
            w.put_varint(e.info.total_len)?;
            w.put_varint(e.info.seg_size as u64)?;
            w.put_varint(e.seg_hashes.len() as u64)?;
            w.put_bytes(&e.info.total_hash)?;
            for h in &e.seg_hashes {
                w.put_bytes(h)?;
            }
        }
        w.pos()
    };
    out.truncate(n);
    Some(out)
}

/// Parses a COMPLETE manifest byte stream (watch side).
pub fn decode_manifest_bytes(bytes: &[u8]) -> Option<Vec<ManifestEntry>> {
    let mut r = Reader::new(bytes);
    let count = r.varint()? as usize;
    if count == 0 || count > MAX_CACHE_TRACKS {
        return None;
    }
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        let cad_id = r.varint()?;
        let total_len = r.varint()?;
        let seg_size = r.varint()? as u32;
        let n_hashes = r.varint()?;
        let total_hash = r.hash32()?;
        let expected = segments_for(total_len, seg_size)?;
        if n_hashes != expected as u64 {
            return None; // lying hash count — hard reject
        }
        let mut seg_hashes = Vec::with_capacity(n_hashes as usize);
        for _ in 0..n_hashes {
            seg_hashes.push(r.hash32()?);
        }
        entries.push(ManifestEntry {
            info: CacheTrackInfo {
                cad_id,
                total_len,
                seg_size,
                total_hash,
            },
            seg_hashes,
        });
    }
    Some(entries)
}

// ─────────────────────────────────────────────── events, actions, config

/// App-layer events surfaced by both coordinators.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WearSyncEvent {
    /// (Watch) queue mirror updated; `len` = mirror size after apply.
    QueueMirrorUpdated { len: usize },
    /// (Phone) the watch edited the shared queue.
    QueueActionReceived { op: QueueOp },
    /// (Phone) rating arrived from the watch.
    RatingReceived { rating: TrackRating },
    /// (Phone) Jam upvote arrived (route into the mesh CRDT with
    /// `VoterId::from_nonce`).
    JamUpvoteReceived { upvote: JamUpvoteMsg },
    /// (Watch) full manifest received & validated.
    ManifestReceived {
        manifest_id: u8,
        tracks: Vec<CacheTrackInfo>,
    },
    /// (Watch) segment verified + buffered.
    CacheSegmentVerified { cad_id: u64, seg_idx: u32 },
    /// (Watch) segment failed Blake3 — discarded, retransmit requested.
    CacheSegmentCorrupt { cad_id: u64, seg_idx: u32 },
    /// (Watch) track assembled + total hash verified.
    CacheTrackCompleted { cad_id: u64 },
    /// (Watch) track failed the total hash — dropped, restart needed.
    CacheTrackFailed { cad_id: u64 },
    /// (Phone) pacer paused.
    CachePaused { reason: PauseReason },
    /// (Phone) pacer resumed.
    CacheResumed,
    /// (Both) manifest cancelled.
    CacheCancelled { manifest_id: u8 },
    /// (Phone) an ack_requested control frame was abandoned.
    ControlAbandoned { seq: u16 },
}

/// Why the cache pacer paused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PauseReason {
    Battery,
    User,
}

/// Pacer/config knobs (directive §2.3 defaults).
#[derive(Debug, Clone, Copy)]
pub struct WearSyncConfig {
    /// Pause threshold: pct below this AND not charging → pause.
    pub battery_pause_pct: u8,
    /// Resume threshold (hysteresis): pct at/above this OR charging.
    pub battery_resume_pct: u8,
    /// Eco pacing threshold: below this AND not charging → rate × 0.5.
    pub battery_eco_pct: u8,
    /// Base pacing rate (bytes per second; default 256 KiB).
    pub bytes_per_second: u64,
    /// Token-bucket burst cap (default 16 KiB).
    pub burst_bytes: u64,
    /// Retransmit timeout (ms).
    pub rto_ms: u64,
}

impl Default for WearSyncConfig {
    fn default() -> Self {
        WearSyncConfig {
            battery_pause_pct: 20,
            battery_resume_pct: 30,
            battery_eco_pct: 40,
            bytes_per_second: 256 * 1024,
            burst_bytes: 16 * 1024,
            rto_ms: WEAR_RTO_MS,
        }
    }
}

/// Effects the transport must execute.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WearSyncAction {
    Send { frame: Vec<u8> },
}

/// Boundary/telemetry counters.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct WearSyncStats {
    pub frames_rx: u64,
    pub frames_rejected: u64,
    pub frames_tx: u64,
    pub segs_sent: u64,
    pub segs_retransmitted: u64,
    pub segs_verified: u64,
    pub segs_corrupt: u64,
    pub tracks_completed: u64,
    pub control_retries: u64,
}

/// Builds a CACHE_CHUNK payload (pure — borrow-safe from tick paths).
fn build_chunk_payload(track_info: &CacheTrackInfo, data: &[u8], idx: u32) -> Option<Vec<u8>> {
    let seg = track_info.seg_size as usize;
    let lo = idx as usize * seg;
    let hi = (lo + seg).min(data.len());
    if lo >= data.len() {
        return None;
    }
    let mut payload = vec![0u8; 5 + (hi - lo)];
    let mut w = WearBuf::new(&mut payload);
    w.put_varint(track_info.cad_id)?;
    w.put_varint(idx as u64)?;
    w.put_bytes(&data[lo..hi])?;
    let n = w.pos();
    payload.truncate(n);
    Some(payload)
}

/// Recent-frame seq window for retry dedupe (bounded, wraparound-safe).
const RECENT_SEQ_WINDOW: usize = 256;

fn is_duplicate(recent: &mut VecDeque<u16>, seen: &mut HashSet<u16>, seq: u16) -> bool {
    if seen.contains(&seq) {
        return true;
    }
    if recent.len() == RECENT_SEQ_WINDOW {
        if let Some(old) = recent.pop_front() {
            seen.remove(&old);
        }
    }
    recent.push_back(seq);
    seen.insert(seq);
    false
}

// ─────────────────────────────────────────────── phone-side coordinator

/// One in-push track on the phone side (audio bytes owned here — the app
/// layer loads one manifest's worth at a time; watch caches are
/// track-sized, ≤ 4 MiB each).
struct PushTrack {
    info: CacheTrackInfo,
    seg_hashes: Vec<[u8; 32]>,
    data: Vec<u8>,
    acked: Vec<bool>,
    /// First not-fully-acked index (advance pointer).
    base: u32,
    /// In-flight retransmit state: seg → (deadline_ms, attempts).
    inflight: BTreeMap<u32, (u64, u32)>,
}

impl PushTrack {
    fn num_segs(&self) -> u32 {
        self.acked.len() as u32
    }

    /// In-flight (sent, un-acked) segment count — the backpressure
    /// metric for the window.
    fn inflight_count(&self) -> usize {
        self.inflight.len()
    }

    /// Next segment never dispatched (un-acked AND not in flight).
    fn next_fresh(&self) -> Option<u32> {
        (self.base..self.num_segs())
            .find(|&i| !self.acked[i as usize] && !self.inflight.contains_key(&i))
    }
}

/// Cache push state on the phone.
struct CachePush {
    manifest_id: u8,
    tracks: Vec<PushTrack>,
    active_track: usize,
    paused: Option<PauseReason>,
    done: bool,
}

/// Pending ack_requested control frame (reliable mini-protocol).
struct PendingControl {
    frame: Vec<u8>,
    deadline: u64,
    attempts: u32,
}

/// Phone-side coordinator: pushes queue deltas + offline cache, receives
/// watch edits / ratings / upvotes / acks / battery reports.
pub struct PhoneSyncCoordinator {
    config: WearSyncConfig,
    seq: u16,
    push: Option<CachePush>,
    bucket_tokens: i64,
    last_refill_ms: u64,
    battery: Option<(u8, bool)>,
    user_paused: bool,
    pending_controls: BTreeMap<u16, PendingControl>,
    /// Retry dedupe for watch-originated ack_requested frames.
    recent_seqs: VecDeque<u16>,
    seen_seqs: HashSet<u16>,
    events: VecDeque<WearSyncEvent>,
    actions: Vec<WearSyncAction>,
    stats: WearSyncStats,
}

impl PhoneSyncCoordinator {
    pub fn new(config: WearSyncConfig) -> Self {
        let burst = config.burst_bytes as i64;
        PhoneSyncCoordinator {
            config,
            seq: 0,
            push: None,
            bucket_tokens: burst,
            last_refill_ms: 0,
            battery: None,
            user_paused: false,
            pending_controls: BTreeMap::new(),
            recent_seqs: VecDeque::new(),
            seen_seqs: HashSet::new(),
            events: VecDeque::new(),
            actions: Vec::new(),
            stats: WearSyncStats::default(),
        }
    }

    pub fn stats(&self) -> WearSyncStats {
        self.stats
    }

    /// Token-bucket level (telemetry/tests).
    pub fn bucket_tokens(&self) -> i64 {
        self.bucket_tokens
    }

    /// Cache pacer state (telemetry/tests).
    pub fn paused(&self) -> Option<PauseReason> {
        self.push.as_ref().and_then(|p| p.paused)
    }

    pub fn drain_events(&mut self) -> Vec<WearSyncEvent> {
        self.events.drain(..).collect()
    }

    pub fn drain_actions(&mut self) -> Vec<WearSyncAction> {
        std::mem::take(&mut self.actions)
    }

    fn next_seq(&mut self) -> u16 {
        self.seq = self.seq.wrapping_add(1);
        self.seq
    }

    fn send(&mut self, frame_type: u8, flags: u8, payload: &[u8]) {
        let seq = self.next_seq();
        let frame = encode_frame(frame_type, seq, flags, payload);
        self.stats.frames_tx += 1;
        if flags & FLAG_ACK_REQUESTED != 0 {
            self.pending_controls.insert(
                seq,
                PendingControl {
                    frame: frame.clone(),
                    deadline: 0, // armed on the first tick
                    attempts: 0,
                },
            );
        }
        self.actions.push(WearSyncAction::Send { frame });
    }

    // ── queue mirror push (phone → watch) ────────────────────────────

    /// Push a queue delta batch (the watch mirrors the next few tracks).
    pub fn push_queue_delta(&mut self, ops: &[QueueOp]) -> bool {
        let mut payload = [0u8; 256];
        let Some(n) = encode_queue_delta(ops, &mut payload) else {
            return false;
        };
        self.send(WSX_QUEUE_DELTA, FLAG_ACK_REQUESTED, &payload[..n]);
        true
    }

    // ── offline cache push (directive §2.3) ──────────────────────────

    /// Starts a cache push: hashes every segment (Blake3 — Phase 3
    /// verifier discipline, miniaturized), emits the manifest frames.
    /// Replaces any active push. `(cad_id, bytes)` per track.
    pub fn start_cache_push(
        &mut self,
        manifest_id: u8,
        tracks: &[(u64, &[u8])],
        now_ms: u64,
    ) -> bool {
        if tracks.is_empty() || tracks.len() > MAX_CACHE_TRACKS {
            return false;
        }
        let mut push_tracks = Vec::with_capacity(tracks.len());
        for (cad_id, data) in tracks {
            let Some(num) = segments_for(data.len() as u64, SEGMENT_SIZE as u32) else {
                return false;
            };
            let seg_hashes: Vec<[u8; 32]> = (0..num)
                .map(|i| {
                    let lo = i as usize * SEGMENT_SIZE;
                    let hi = (lo + SEGMENT_SIZE).min(data.len());
                    seg_hash(&data[lo..hi])
                })
                .collect();
            push_tracks.push(PushTrack {
                info: CacheTrackInfo {
                    cad_id: *cad_id,
                    total_len: data.len() as u64,
                    seg_size: SEGMENT_SIZE as u32,
                    total_hash: seg_hash(data),
                },
                seg_hashes,
                data: data.to_vec(),
                acked: vec![false; num as usize],
                base: 0,
                inflight: BTreeMap::new(),
            });
        }
        self.push = Some(CachePush {
            manifest_id,
            tracks: push_tracks,
            active_track: 0,
            paused: None,
            done: false,
        });
        self.user_paused = false;
        self.bucket_tokens = self.config.burst_bytes as i64;
        self.last_refill_ms = now_ms;
        self.emit_manifest_frames();
        true
    }

    /// Manifest frames: the serialized manifest byte stream chunked into
    /// ≤ 1 KiB frames. Each frame carries (manifest_id, chunk_idx,
    /// chunk_count, data) — reassembly is BY INDEX, immune to network
    /// reordering (a late-arriving tail can never poison the stream).
    fn emit_manifest_frames(&mut self) {
        let Some(push) = &self.push else { return };
        let manifest_id = push.manifest_id;
        let entries: Vec<ManifestEntry> = push
            .tracks
            .iter()
            .map(|t| ManifestEntry {
                info: t.info.clone(),
                seg_hashes: t.seg_hashes.clone(),
            })
            .collect();
        let Some(bytes) = encode_manifest_bytes(&entries) else {
            return;
        };
        // Chunk into frame payloads FIRST (owned), then send.
        const CHUNK: usize = MAX_WSX_PAYLOAD - 8; // id + idx/count varints
        let total = bytes.len().div_ceil(CHUNK);
        if total == 0 || total > 255 {
            return; // cannot frame (encode_manifest_bytes bounds this)
        }
        let mut payloads: Vec<Vec<u8>> = Vec::new();
        for (idx, chunk) in bytes.chunks(CHUNK).enumerate() {
            // Varint head pre-sized (WearBuf writes in place).
            let mut head = [0u8; 8];
            let mut w = WearBuf::new(&mut head);
            let _ = w.put_varint(idx as u64);
            let _ = w.put_varint(total as u64);
            let used = w.pos();
            let mut frame_payload = Vec::with_capacity(1 + used + chunk.len());
            frame_payload.push(manifest_id);
            frame_payload.extend_from_slice(&head[..used]);
            frame_payload.extend_from_slice(chunk);
            payloads.push(frame_payload);
        }
        for payload in payloads {
            self.send(WSX_CACHE_MANIFEST, FLAG_ACK_REQUESTED, &payload);
        }
    }

    /// Watch → phone frame ingestion (edits / ratings / upvotes / acks /
    /// battery / controls). Watch-originated ack_requested frames are
    /// deduped by seq: a retry (our ACK was lost) re-ACKs without
    /// re-raising the event — exactly-once semantics for votes.
    pub fn on_frame(&mut self, bytes: &[u8], now_ms: u64) -> bool {
        let frame = match parse_frame(bytes) {
            Ok(f) => f,
            Err(_) => {
                self.stats.frames_rejected += 1;
                return false;
            }
        };
        self.stats.frames_rx += 1;
        let retryable = matches!(
            frame.frame_type,
            WSX_QUEUE_ACTION | WSX_TRACK_RATING | WSX_JAM_UPVOTE
        );
        if retryable && is_duplicate(&mut self.recent_seqs, &mut self.seen_seqs, frame.seq) {
            self.ack(frame.seq); // idempotent re-ACK; no double event
            return true;
        }
        match frame.frame_type {
            WSX_SYNC_ACK => {
                if let Some(echo) = Reader::new(frame.payload).u16_pair() {
                    self.pending_controls.remove(&echo);
                }
                true
            }
            WSX_QUEUE_ACTION => match decode_queue_action(frame.payload) {
                Some(op) => {
                    self.events
                        .push_back(WearSyncEvent::QueueActionReceived { op });
                    self.ack(frame.seq);
                    true
                }
                None => {
                    self.stats.frames_rejected += 1;
                    false
                }
            },
            WSX_TRACK_RATING => match decode_track_rating(frame.payload) {
                Some(rating) => {
                    self.events
                        .push_back(WearSyncEvent::RatingReceived { rating });
                    self.ack(frame.seq);
                    true
                }
                None => {
                    self.stats.frames_rejected += 1;
                    false
                }
            },
            WSX_JAM_UPVOTE => match decode_jam_upvote(frame.payload) {
                Some(upvote) => {
                    self.events
                        .push_back(WearSyncEvent::JamUpvoteReceived { upvote });
                    self.ack(frame.seq);
                    true
                }
                None => {
                    self.stats.frames_rejected += 1;
                    false
                }
            },
            WSX_CACHE_ACK => self.on_cache_ack(frame.payload),
            WSX_CACHE_CONTROL => self.on_cache_control(frame.payload, now_ms),
            _ => {
                // The phone never receives its own push frames.
                self.stats.frames_rejected += 1;
                false
            }
        }
    }

    fn ack(&mut self, echo_seq: u16) {
        let payload = echo_seq.to_le_bytes();
        self.send(WSX_SYNC_ACK, 0, &payload);
    }

    fn on_cache_ack(&mut self, payload: &[u8]) -> bool {
        let mut r = Reader::new(payload);
        let Some(manifest_id) = r.u8() else {
            return false;
        };
        let Some(cad_id) = r.varint() else {
            return false;
        };
        let Some(base) = r.varint() else { return false };
        let bits = r.rest().to_vec();
        let Some(push) = self.push.as_mut() else {
            return true;
        };
        if push.manifest_id != manifest_id || push.done {
            return true; // stale ack — inert
        }
        let Some(track) = push.tracks.iter_mut().find(|t| t.info.cad_id == cad_id) else {
            return true;
        };
        for (i, &byte) in bits.iter().enumerate() {
            for bit in 0..8 {
                if byte & (1 << bit) != 0 {
                    let idx = base as usize + i * 8 + bit;
                    if idx < track.acked.len() {
                        track.acked[idx] = true;
                        track.inflight.remove(&(idx as u32));
                    }
                }
            }
        }
        while track.base < track.acked.len() as u32 && track.acked[track.base as usize] {
            track.base += 1;
        }
        true
    }

    fn on_cache_control(&mut self, payload: &[u8], now_ms: u64) -> bool {
        let mut r = Reader::new(payload);
        let Some(code) = r.u8() else { return false };
        match code {
            CTRL_BATTERY => {
                let Some(pct) = r.u8() else { return false };
                let Some(charging) = r.u8() else { return false };
                self.battery = Some((pct.min(100), charging != 0));
                self.reconcile_pause(now_ms);
                true
            }
            CTRL_USER_PAUSE => {
                self.user_paused = true;
                self.reconcile_pause(now_ms);
                true
            }
            CTRL_USER_RESUME => {
                self.user_paused = false;
                self.reconcile_pause(now_ms);
                true
            }
            CTRL_CANCEL => {
                let Some(id) = r.u8() else { return false };
                if self.push.as_ref().is_some_and(|p| p.manifest_id == id) {
                    self.push = None;
                    self.events
                        .push_back(WearSyncEvent::CacheCancelled { manifest_id: id });
                }
                true
            }
            CTRL_TRACK_DONE => {
                let Some(cad) = r.varint() else { return false };
                self.stats.tracks_completed += 1;
                if let Some(push) = self.push.as_mut() {
                    if let Some(t) = push.tracks.iter_mut().find(|t| t.info.cad_id == cad) {
                        for i in 0..t.acked.len() {
                            t.acked[i] = true;
                        }
                        t.inflight.clear();
                        t.base = t.num_segs();
                    }
                }
                self.advance_track();
                true
            }
            CTRL_SEG_BAD => {
                let Some(cad) = r.varint() else { return false };
                let Some(idx) = r.varint() else { return false };
                self.stats.segs_corrupt += 1;
                // Priority retransmit (build-first: borrow-safe).
                let out = self.push.as_ref().and_then(|push| {
                    push.tracks
                        .iter()
                        .find(|t| t.info.cad_id == cad)
                        .and_then(|t| build_chunk_payload(&t.info, &t.data, idx as u32))
                });
                if let Some(payload) = out {
                    self.send(WSX_CACHE_CHUNK, 0, &payload);
                    self.stats.segs_retransmitted += 1;
                    if let Some(push) = self.push.as_mut() {
                        if let Some(t) = push.tracks.iter_mut().find(|t| t.info.cad_id == cad) {
                            let attempts = t.inflight.get(&(idx as u32)).map_or(1, |(_, a)| *a + 1);
                            t.inflight
                                .insert(idx as u32, (now_ms + self.config.rto_ms, attempts));
                        }
                    }
                }
                true
            }
            CTRL_TRACK_BAD => {
                let Some(_cad) = r.varint() else { return false };
                // Watch requests restart — drop the push; the app layer
                // may start a fresh manifest.
                self.push = None;
                true
            }
            _ => {
                self.stats.frames_rejected += 1;
                false
            }
        }
    }

    /// Battery/user pause reconciliation with hysteresis. Works with no
    /// battery report yet (user pause alone still applies).
    fn reconcile_pause(&mut self, now_ms: u64) {
        let currently_paused = self.push.as_ref().and_then(|p| p.paused);
        let new_pause = if self.user_paused {
            Some(PauseReason::User)
        } else if let Some((pct, charging)) = self.battery {
            let battery_pause = pct < self.config.battery_pause_pct && !charging;
            let resume_ok = pct >= self.config.battery_resume_pct || charging;
            if battery_pause || (currently_paused == Some(PauseReason::Battery) && !resume_ok) {
                Some(PauseReason::Battery)
            } else {
                None
            }
        } else {
            None
        };
        let changed = self.push.as_ref().is_some_and(|p| p.paused != new_pause);
        if changed {
            if let Some(push) = self.push.as_mut() {
                push.paused = new_pause;
            }
            match new_pause {
                Some(reason) => self.events.push_back(WearSyncEvent::CachePaused { reason }),
                None => {
                    self.events.push_back(WearSyncEvent::CacheResumed);
                    // Restart the pacer clock so the burst doesn't
                    // overdraw right after a long pause.
                    self.last_refill_ms = now_ms;
                }
            }
        }
    }

    fn advance_track(&mut self) {
        if let Some(push) = self.push.as_mut() {
            let done_current =
                push.tracks[push.active_track].base >= push.tracks[push.active_track].num_segs();
            if done_current {
                if push.active_track + 1 < push.tracks.len() {
                    push.active_track += 1;
                } else {
                    push.done = true;
                }
            }
        }
    }

    /// Pacer tick: refill the token bucket, dispatch segments within the
    /// window/budget, retransmit RTO-expired segments, retry pending
    /// ack_requested control frames.
    pub fn tick(&mut self, now_ms: u64) {
        self.tick_refill(now_ms);
        self.tick_dispatch(now_ms);
        self.tick_retransmit(now_ms);
        self.tick_control_retries(now_ms);
    }

    fn tick_refill(&mut self, now_ms: u64) {
        let active_unpaused = self
            .push
            .as_ref()
            .is_some_and(|p| p.paused.is_none() && !p.done);
        if active_unpaused {
            let elapsed = now_ms.saturating_sub(self.last_refill_ms);
            self.last_refill_ms = now_ms;
            // Eco pacing: half rate when battery is low but above pause.
            let mut rate = self.config.bytes_per_second;
            if let Some((pct, charging)) = self.battery {
                if pct < self.config.battery_eco_pct && !charging {
                    rate /= 2;
                }
            }
            let refill = (elapsed.saturating_mul(rate)) / 1000;
            self.bucket_tokens =
                (self.bucket_tokens + refill as i64).min(self.config.burst_bytes as i64);
        } else {
            self.last_refill_ms = now_ms;
        }
    }

    fn tick_dispatch(&mut self, now_ms: u64) {
        // Collect the next dispatch candidate (read-only pass).
        let candidate: Option<(usize, u32, usize)> = self.push.as_ref().and_then(|push| {
            if push.paused.is_some() || push.done {
                return None;
            }
            let t = push.active_track;
            let track = push.tracks.get(t)?;
            if track.inflight_count() >= CACHE_WINDOW {
                return None;
            }
            track
                .next_fresh()
                .map(|idx| (t, idx, track.info.seg_size as usize))
        });
        let Some((t, idx, seg_cost)) = candidate else {
            // Nothing dispatchable: maybe the active track drained —
            // advance (awaiting acks otherwise).
            self.advance_track();
            return;
        };
        if self.bucket_tokens < seg_cost as i64 {
            return; // pacer budget exhausted this tick
        }
        // Build the payload (borrow ends here), then send.
        let payload = self.push.as_ref().and_then(|push| {
            push.tracks
                .get(t)
                .and_then(|track| build_chunk_payload(&track.info, &track.data, idx))
        });
        let Some(payload) = payload else { return };
        self.bucket_tokens -= seg_cost as i64;
        self.send(WSX_CACHE_CHUNK, 0, &payload);
        self.stats.segs_sent += 1;
        if let Some(push) = self.push.as_mut() {
            if let Some(track) = push.tracks.get_mut(t) {
                track
                    .inflight
                    .entry(idx)
                    .or_insert((now_ms + self.config.rto_ms, 1));
            }
        }
    }

    fn tick_retransmit(&mut self, now_ms: u64) {
        // Read-only scan for RTO-expired in-flight segments.
        let expired: Vec<(usize, u32)> = self
            .push
            .as_ref()
            .map(|push| {
                push.tracks
                    .get(push.active_track)
                    .map(|track| {
                        track
                            .inflight
                            .iter()
                            .filter(|(_, (deadline, _))| now_ms >= *deadline)
                            .map(|(idx, _)| (push.active_track, *idx))
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default()
            })
            .unwrap_or_default();
        for (t, idx) in expired {
            // Retransmissions ride the pacer too — corrupt-heal priority
            // retransmits (CTRL_SEG_BAD) are the only pacing bypass.
            let payload = self.push.as_ref().and_then(|push| {
                push.tracks
                    .get(t)
                    .and_then(|track| build_chunk_payload(&track.info, &track.data, idx))
            });
            let Some(payload) = payload else { continue };
            self.send(WSX_CACHE_CHUNK, 0, &payload);
            self.stats.segs_retransmitted += 1;
            if let Some(push) = self.push.as_mut() {
                if let Some(track) = push.tracks.get_mut(t) {
                    let attempts = track.inflight.get(&idx).map_or(1, |(_, a)| *a + 1);
                    track
                        .inflight
                        .insert(idx, (now_ms + self.config.rto_ms, attempts));
                }
            }
        }
    }

    fn tick_control_retries(&mut self, now_ms: u64) {
        let mut retry: Vec<u16> = Vec::new();
        for (seq, pc) in self.pending_controls.iter_mut() {
            if pc.attempts == 0 {
                // Armed on first sight of a tick.
                pc.deadline = now_ms + self.config.rto_ms;
                pc.attempts = 1;
            } else if now_ms >= pc.deadline {
                if pc.attempts >= MAX_CONTROL_ATTEMPTS {
                    retry.push(*seq);
                } else {
                    pc.attempts += 1;
                    pc.deadline = now_ms + self.config.rto_ms;
                    let frame = pc.frame.clone();
                    self.stats.control_retries += 1;
                    self.stats.frames_tx += 1;
                    self.actions.push(WearSyncAction::Send { frame });
                }
            }
        }
        for seq in retry {
            self.pending_controls.remove(&seq);
            self.events
                .push_back(WearSyncEvent::ControlAbandoned { seq });
        }
    }

    /// Push complete? (all tracks fully acked / reported done).
    pub fn cache_complete(&self) -> bool {
        self.push.as_ref().is_some_and(|p| p.done)
    }
}

// ─────────────────────────────────────────────── watch-side coordinator

/// Per-track receive state on the watch.
struct RecvTrack {
    info: CacheTrackInfo,
    seg_hashes: Vec<[u8; 32]>,
    /// Verified segment payloads (None until verified).
    segs: Vec<Option<Vec<u8>>>,
    verified: u32,
}

/// Watch-side coordinator: mirrors the queue, sends edits/ratings/
/// upvotes, receives the offline cache, verifies every segment, reports
/// battery.
pub struct WatchSyncCoordinator {
    seq: u16,
    queue_mirror: Vec<u64>,
    manifest_id: Option<u8>,
    /// Chunk-indexed manifest reassembly (reordering-immune).
    manifest_chunks: BTreeMap<u8, Vec<u8>>,
    manifest_chunk_count: Option<u8>,
    tracks: HashMap<u64, RecvTrack>,
    track_order: Vec<u64>,
    /// Pending ack bitmap batches (cad → (base, bits)).
    ack_batch: BTreeMap<u64, (u32, Vec<u8>)>,
    ack_since_flush: u32,
    /// Watch-side reliable mini-protocol: ack_requested frames awaiting
    /// a SYNC_ACK echo (a vote/rating must never vanish on flaky links).
    pending_controls: BTreeMap<u16, WatchPendingControl>,
    /// Retry dedupe for phone-originated ack_requested frames (queue
    /// deltas / manifest chunks).
    recent_seqs: VecDeque<u16>,
    seen_seqs: HashSet<u16>,
    events: VecDeque<WearSyncEvent>,
    actions: Vec<WearSyncAction>,
    stats: WearSyncStats,
}

/// Pending ack_requested control frame on the watch side. The watch
/// coordinator's tick is clock-less — retry pacing is tick-count based
/// (`ticks_per_retry` ≈ WEAR_RTO_MS / tick cadence).
struct WatchPendingControl {
    frame: Vec<u8>,
    /// Ticks since the last (re)send.
    ticks_since_send: u64,
    attempts: u32,
}

impl WatchSyncCoordinator {
    pub fn new() -> Self {
        WatchSyncCoordinator {
            seq: 0,
            queue_mirror: Vec::new(),
            manifest_id: None,
            manifest_chunks: BTreeMap::new(),
            manifest_chunk_count: None,
            tracks: HashMap::new(),
            track_order: Vec::new(),
            ack_batch: BTreeMap::new(),
            ack_since_flush: 0,
            pending_controls: BTreeMap::new(),
            recent_seqs: VecDeque::new(),
            seen_seqs: HashSet::new(),
            events: VecDeque::new(),
            actions: Vec::new(),
            stats: WearSyncStats::default(),
        }
    }

    pub fn stats(&self) -> WearSyncStats {
        self.stats
    }

    pub fn queue_mirror(&self) -> &[u64] {
        &self.queue_mirror
    }

    pub fn drain_events(&mut self) -> Vec<WearSyncEvent> {
        self.events.drain(..).collect()
    }

    pub fn drain_actions(&mut self) -> Vec<WearSyncAction> {
        std::mem::take(&mut self.actions)
    }

    fn next_seq(&mut self) -> u16 {
        self.seq = self.seq.wrapping_add(1);
        self.seq
    }

    fn send(&mut self, frame_type: u8, flags: u8, payload: &[u8]) {
        let seq = self.next_seq();
        let frame = encode_frame(frame_type, seq, flags, payload);
        self.stats.frames_tx += 1;
        if flags & FLAG_ACK_REQUESTED != 0 {
            self.pending_controls.insert(
                seq,
                WatchPendingControl {
                    frame: frame.clone(),
                    ticks_since_send: 0,
                    attempts: 1,
                },
            );
        }
        self.actions.push(WearSyncAction::Send { frame });
    }

    /// Watch → phone queue edit (propagates into the shared queue).
    pub fn send_queue_action(&mut self, op: &QueueOp) -> bool {
        let mut payload = [0u8; 24];
        let Some(n) = encode_queue_action(op, &mut payload) else {
            return false;
        };
        self.send(WSX_QUEUE_ACTION, FLAG_ACK_REQUESTED, &payload[..n]);
        // The mirror reflects the edit immediately (local-first).
        self.apply_op(*op);
        true
    }

    /// Rate the current track (1..=5 + thumbs override).
    pub fn send_rating(&mut self, rating: &TrackRating) -> bool {
        let mut payload = [0u8; 16];
        let Some(n) = encode_track_rating(rating, &mut payload) else {
            return false;
        };
        self.send(WSX_TRACK_RATING, FLAG_ACK_REQUESTED, &payload[..n]);
        true
    }

    /// Upvote from the watch face into the phone's Jam session.
    pub fn send_jam_upvote(&mut self, upvote: &JamUpvoteMsg) -> bool {
        let mut payload = [0u8; 24];
        let Some(n) = encode_jam_upvote(upvote, &mut payload) else {
            return false;
        };
        self.send(WSX_JAM_UPVOTE, FLAG_ACK_REQUESTED, &payload[..n]);
        true
    }

    /// Report battery state (drives the phone's auto-pause + eco pacing).
    pub fn report_battery(&mut self, pct: u8, charging: bool) -> bool {
        let payload = [CTRL_BATTERY, pct.min(100), u8::from(charging)];
        self.send(WSX_CACHE_CONTROL, 0, &payload);
        true
    }

    /// User pause / resume of the cache sync.
    pub fn user_pause(&mut self, pause: bool) -> bool {
        let payload = [if pause {
            CTRL_USER_PAUSE
        } else {
            CTRL_USER_RESUME
        }];
        self.send(WSX_CACHE_CONTROL, 0, &payload);
        true
    }

    /// Cancel the incoming manifest.
    pub fn cancel_manifest(&mut self, manifest_id: u8) -> bool {
        let payload = [CTRL_CANCEL, manifest_id];
        self.send(WSX_CACHE_CONTROL, 0, &payload);
        if self.manifest_id.is_none() || self.manifest_id == Some(manifest_id) {
            self.manifest_id = None;
            self.manifest_chunks.clear();
            self.manifest_chunk_count = None;
            self.tracks.clear();
            self.track_order.clear();
            self.events
                .push_back(WearSyncEvent::CacheCancelled { manifest_id });
        }
        true
    }

    /// Apply a queue op onto the mirror (bounded, panic-free).
    fn apply_op(&mut self, op: QueueOp) {
        match op {
            QueueOp::Add { cad_id, after_pos } => {
                if self.queue_mirror.len() >= MAX_QUEUE_MIRROR {
                    self.queue_mirror.remove(0);
                }
                let pos = (after_pos as usize).min(self.queue_mirror.len());
                self.queue_mirror.insert(pos, cad_id);
            }
            QueueOp::Remove { index } => {
                if (index as usize) < self.queue_mirror.len() {
                    self.queue_mirror.remove(index as usize);
                }
            }
            QueueOp::Clear => self.queue_mirror.clear(),
            QueueOp::Move { from, to } => {
                let (from, to) = (from as usize, to as usize);
                if from < self.queue_mirror.len() && to < self.queue_mirror.len() {
                    let cad = self.queue_mirror.remove(from);
                    self.queue_mirror.insert(to, cad);
                }
            }
        }
    }

    /// Phone → watch frame ingestion. Phone-originated ack_requested
    /// frames (queue deltas, manifest chunks) are deduped by seq: a
    /// retry re-ACKs without re-applying — exactly-once mirror updates.
    pub fn on_frame(&mut self, bytes: &[u8], _now_ms: u64) -> bool {
        let frame = match parse_frame(bytes) {
            Ok(f) => f,
            Err(_) => {
                self.stats.frames_rejected += 1;
                return false;
            }
        };
        self.stats.frames_rx += 1;
        let retryable = matches!(frame.frame_type, WSX_QUEUE_DELTA | WSX_CACHE_MANIFEST);
        if retryable && is_duplicate(&mut self.recent_seqs, &mut self.seen_seqs, frame.seq) {
            self.ack(frame.seq); // idempotent re-ACK; no double apply
            return true;
        }
        match frame.frame_type {
            WSX_QUEUE_DELTA => match decode_queue_delta(frame.payload) {
                Some(ops) => {
                    for op in ops {
                        self.apply_op(op);
                    }
                    self.events.push_back(WearSyncEvent::QueueMirrorUpdated {
                        len: self.queue_mirror.len(),
                    });
                    self.ack(frame.seq);
                    true
                }
                None => {
                    self.stats.frames_rejected += 1;
                    false
                }
            },
            WSX_SYNC_ACK => {
                // Echo for one of OUR ack_requested frames: retire it.
                if let Some(echo) = Reader::new(frame.payload).u16_pair() {
                    self.pending_controls.remove(&echo);
                }
                true
            }
            WSX_CACHE_MANIFEST => self.on_manifest(frame.payload, frame.seq),
            WSX_CACHE_CHUNK => self.on_chunk(frame.payload),
            _ => {
                self.stats.frames_rejected += 1;
                false
            }
        }
    }

    fn ack(&mut self, echo_seq: u16) {
        let payload = echo_seq.to_le_bytes();
        self.send(WSX_SYNC_ACK, 0, &payload);
    }

    fn on_manifest(&mut self, payload: &[u8], seq: u16) -> bool {
        let mut r = Reader::new(payload);
        let Some(manifest_id) = r.u8() else {
            return false;
        };
        let Some(idx) = r.varint() else { return false };
        let Some(count) = r.varint() else {
            return false;
        };
        if idx > 254 || count == 0 || count > 255 || idx >= count {
            self.stats.frames_rejected += 1;
            return false;
        }
        let (idx, count) = (idx as u8, count as u8);
        let chunk = r.rest();
        // Manifest id switch → reset reassembly.
        if self.manifest_id != Some(manifest_id) {
            self.manifest_id = Some(manifest_id);
            self.manifest_chunks.clear();
            self.manifest_chunk_count = None;
        }
        // Budget bound (hostile floods).
        let buffered: usize = self.manifest_chunks.values().map(Vec::len).sum();
        if buffered + chunk.len() > MAX_MANIFEST_BYTES || self.manifest_chunks.len() >= 255 {
            self.stats.frames_rejected += 1;
            self.manifest_chunks.clear();
            self.manifest_chunk_count = None;
            self.manifest_id = None;
            return false;
        }
        self.manifest_chunks.insert(idx, chunk.to_vec());
        self.manifest_chunk_count = Some(count);
        self.ack(seq);
        // Complete when every chunk index has landed (any order).
        if self.manifest_chunks.len() == count as usize {
            let mut bytes = Vec::with_capacity(buffered + chunk.len());
            for part in self.manifest_chunks.values() {
                bytes.extend_from_slice(part);
            }
            self.manifest_chunks.clear();
            self.manifest_chunk_count = None;
            match decode_manifest_bytes(&bytes) {
                Some(entries) => {
                    for e in entries {
                        let cad = e.info.cad_id;
                        if let Some(t) = self.tracks.get_mut(&cad) {
                            t.info = e.info.clone();
                            t.seg_hashes = e.seg_hashes.clone();
                            // Segment count may have grown: resize buffers.
                            t.segs.resize(e.seg_hashes.len(), None);
                            t.verified = t.segs.iter().filter(|s| s.is_some()).count() as u32;
                        } else if self.tracks.len() < MAX_CACHE_TRACKS {
                            let n = e.seg_hashes.len();
                            self.tracks.insert(
                                cad,
                                RecvTrack {
                                    info: e.info.clone(),
                                    seg_hashes: e.seg_hashes.clone(),
                                    segs: vec![None; n],
                                    verified: 0,
                                },
                            );
                            self.track_order.push(cad);
                        }
                    }
                    let infos: Vec<CacheTrackInfo> = self
                        .track_order
                        .iter()
                        .filter_map(|c| self.tracks.get(c).map(|t| t.info.clone()))
                        .collect();
                    if !infos.is_empty() {
                        self.events.push_back(WearSyncEvent::ManifestReceived {
                            manifest_id,
                            tracks: infos,
                        });
                    }
                }
                None => {
                    self.stats.frames_rejected += 1;
                    self.tracks.clear();
                    self.track_order.clear();
                    return false;
                }
            }
        }
        true
    }

    fn on_chunk(&mut self, payload: &[u8]) -> bool {
        let mut r = Reader::new(payload);
        let Some(cad_id) = r.varint() else {
            return false;
        };
        let Some(idx) = r.varint() else { return false };
        let idx_u = idx as usize;
        // Read-only validation pass, then a mutating pass (borrow-safe).
        let validation: Option<(u32, usize)> = self.tracks.get(&cad_id).and_then(|track| {
            if idx >= track.seg_hashes.len() as u64 {
                return None;
            }
            let expected_len = {
                let seg = track.info.seg_size as usize;
                let lo = idx_u * seg;
                let hi = (lo + seg).min(track.info.total_len as usize);
                hi.saturating_sub(lo)
            };
            Some((track.info.seg_size, expected_len))
        });
        let Some((_seg_size, expected_len)) = validation else {
            self.stats.frames_rejected += 1;
            return false;
        };
        let data = r.rest();
        if data.len() != expected_len {
            self.stats.frames_rejected += 1;
            return false;
        }
        if self
            .tracks
            .get(&cad_id)
            .is_some_and(|t| t.segs[idx_u].is_some())
        {
            // Duplicate delivery: re-ack, no double-verify.
            self.record_ack(cad_id, idx as u32);
            return true;
        }
        if seg_hash(data) != self.tracks[&cad_id].seg_hashes[idx_u] {
            // Corrupt segment: discard + priority retransmit request.
            self.stats.segs_corrupt += 1;
            let mut out = [0u8; 24];
            let mut w = WearBuf::new(&mut out);
            let _ = w.put_u8(CTRL_SEG_BAD);
            let _ = w.put_varint(cad_id);
            let _ = w.put_varint(idx);
            let n = w.pos();
            self.events.push_back(WearSyncEvent::CacheSegmentCorrupt {
                cad_id,
                seg_idx: idx as u32,
            });
            self.send(WSX_CACHE_CONTROL, 0, &out[..n]);
            return true;
        }
        // Verified: buffer + ack + completion check.
        let already = self
            .tracks
            .get(&cad_id)
            .map(|t| t.segs[idx_u].is_some())
            .unwrap_or(false);
        let total = self.tracks[&cad_id].seg_hashes.len() as u32;
        let verified_before = self.tracks[&cad_id].verified;
        let info = self.tracks[&cad_id].info.clone();
        let completes = !already && verified_before + 1 == total;
        if let Some(track) = self.tracks.get_mut(&cad_id) {
            if track.segs[idx_u].is_none() {
                track.segs[idx_u] = Some(data.to_vec());
                track.verified += 1;
            }
        }
        self.stats.segs_verified += 1;
        self.events.push_back(WearSyncEvent::CacheSegmentVerified {
            cad_id,
            seg_idx: idx as u32,
        });
        self.record_ack(cad_id, idx as u32);
        if completes {
            let assembled: Vec<u8> = self.tracks[&cad_id]
                .segs
                .iter()
                .flatten()
                .flat_map(|s| s.iter().copied())
                .collect();
            if assembled.len() as u64 == info.total_len && seg_hash(&assembled) == info.total_hash {
                self.stats.tracks_completed += 1;
                self.events
                    .push_back(WearSyncEvent::CacheTrackCompleted { cad_id });
                let mut out = [0u8; 16];
                let mut w = WearBuf::new(&mut out);
                let _ = w.put_u8(CTRL_TRACK_DONE);
                let _ = w.put_varint(cad_id);
                let n = w.pos();
                self.send(WSX_CACHE_CONTROL, 0, &out[..n]);
            } else {
                self.events
                    .push_back(WearSyncEvent::CacheTrackFailed { cad_id });
                let mut out = [0u8; 16];
                let mut w = WearBuf::new(&mut out);
                let _ = w.put_u8(CTRL_TRACK_BAD);
                let _ = w.put_varint(cad_id);
                let n = w.pos();
                self.send(WSX_CACHE_CONTROL, 0, &out[..n]);
            }
        }
        true
    }

    /// Batches ack bits (8 segments per byte); flushed on threshold/tick.
    fn record_ack(&mut self, cad_id: u64, idx: u32) {
        let rolled = match self.ack_batch.get(&cad_id) {
            Some((base, bits)) => idx.saturating_sub(*base) as usize >= bits.len() * 8,
            None => false,
        };
        if rolled {
            self.flush_ack(cad_id);
        }
        let entry = self.ack_batch.entry(cad_id).or_insert_with(|| {
            let mut bits = vec![0u8; 8];
            bits[0] = 1;
            (idx, bits)
        });
        if entry.0 != idx && !rolled {
            let (base, bits) = entry;
            let offset = idx.saturating_sub(*base) as usize;
            if offset < bits.len() * 8 {
                bits[offset / 8] |= 1 << (offset % 8);
            }
        }
        self.ack_since_flush += 1;
        if self.ack_since_flush >= ACK_FLUSH_SEGS {
            self.flush_all_acks();
        }
    }

    fn flush_ack(&mut self, cad_id: u64) {
        if let Some((base, bits)) = self.ack_batch.remove(&cad_id) {
            let mut payload = [0u8; MAX_WSX_PAYLOAD];
            let mut w = WearBuf::new(&mut payload);
            let manifest_id = self.manifest_id.unwrap_or(0);
            let _ = w.put_u8(manifest_id);
            let _ = w.put_varint(cad_id);
            let _ = w.put_varint(base as u64);
            let _ = w.put_bytes(&bits);
            let n = w.pos();
            self.send(WSX_CACHE_ACK, 0, &payload[..n]);
        }
    }

    fn flush_all_acks(&mut self) {
        let ids: Vec<u64> = self.ack_batch.keys().copied().collect();
        for id in ids {
            self.flush_ack(id);
        }
        self.ack_since_flush = 0;
    }

    /// Watch housekeeping: flush pending ack batches + retry un-acked
    /// ack_requested control frames (bounded attempts, event on
    /// abandon — a watch vote must never vanish silently). Retry pace:
    /// every WEAR_RTO_MS-equivalent in ticks (caller cadence ≈ 10 ms →
    /// 150 ticks).
    pub fn tick(&mut self) {
        self.flush_all_acks();
        const RETRY_TICKS: u64 = WEAR_RTO_MS / 10 + 1;
        let mut retry: Vec<u16> = Vec::new();
        for (seq, pc) in self.pending_controls.iter_mut() {
            if pc.ticks_since_send >= RETRY_TICKS {
                if pc.attempts >= MAX_CONTROL_ATTEMPTS {
                    retry.push(*seq);
                } else {
                    pc.attempts += 1;
                    pc.ticks_since_send = 0;
                    let frame = pc.frame.clone();
                    self.stats.control_retries += 1;
                    self.stats.frames_tx += 1;
                    self.actions.push(WearSyncAction::Send { frame });
                }
            } else {
                pc.ticks_since_send += 1;
            }
        }
        for seq in retry {
            self.pending_controls.remove(&seq);
            self.events
                .push_back(WearSyncEvent::ControlAbandoned { seq });
        }
    }

    /// Takes the assembled bytes of a completed track (caller persists).
    pub fn take_track(&mut self, cad_id: u64) -> Option<Vec<u8>> {
        let track = self.tracks.get(&cad_id)?;
        if track.verified != track.seg_hashes.len() as u32 {
            return None;
        }
        let assembled: Vec<u8> = track
            .segs
            .iter()
            .flatten()
            .flat_map(|s| s.iter().copied())
            .collect();
        if assembled.len() as u64 == track.info.total_len
            && seg_hash(&assembled) == track.info.total_hash
        {
            self.tracks.remove(&cad_id);
            self.track_order.retain(|&c| c != cad_id);
            Some(assembled)
        } else {
            None
        }
    }
}

impl Default for WatchSyncCoordinator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn varint_round(v: u64) {
        let mut buf = [0u8; 16];
        let used = {
            let mut w = WearBuf::new(&mut buf);
            w.put_varint(v).expect("encode");
            w.pos()
        };
        let (back, decoded_len) = varint_at(&buf, 0).expect("decode");
        assert_eq!(back, v);
        assert_eq!(decoded_len, used);
    }

    #[test]
    fn varints_roundtrip_across_magnitudes() {
        for v in [
            0u64,
            1,
            127,
            128,
            300,
            0xFFFF,
            0x1_0000,
            u32::MAX as u64,
            u64::MAX,
        ] {
            varint_round(v);
        }
        // Hostile 10× continuation → rejected.
        let hostile = [0x80u8; 10];
        assert_eq!(varint_at(&hostile, 0), None);
        // Truncated mid-varint.
        assert_eq!(varint_at(&[0x80u8], 0), None);
    }

    #[test]
    fn queue_delta_bit_packed_roundtrip_and_budget() {
        let ops = vec![
            QueueOp::Add {
                cad_id: 0xCAFE,
                after_pos: 3,
            },
            QueueOp::Remove { index: 1 },
            QueueOp::Clear,
            QueueOp::Move { from: 0, to: 2 },
        ];
        let mut buf = [0u8; 64];
        let n = encode_queue_delta(&ops, &mut buf).expect("encode");
        assert_eq!(decode_queue_delta(&buf[..n]), Some(ops.clone()));
        // Compactness proof: 4 diverse ops in ≤ 12 bytes payload.
        assert!(n <= 12, "delta payload = {n} bytes");
        // Full 32-entry batch stays in budget.
        let batch: Vec<QueueOp> = (0..MAX_QUEUE_DELTA_ENTRIES as u64)
            .map(|i| QueueOp::Add {
                cad_id: 0x1000 + i,
                after_pos: i as u32,
            })
            .collect();
        let mut buf = [0u8; 256];
        let n = encode_queue_delta(&batch, &mut buf).expect("batch encode");
        assert_eq!(decode_queue_delta(&buf[..n]), Some(batch));
        // Over-budget / empty batches refused.
        let oversized: Vec<QueueOp> = (0..MAX_QUEUE_DELTA_ENTRIES + 1)
            .map(|i| QueueOp::Remove { index: i as u32 })
            .collect();
        assert_eq!(encode_queue_delta(&oversized, &mut buf), None);
        assert_eq!(encode_queue_delta(&[], &mut buf), None);
    }

    #[test]
    fn rating_and_upvote_roundtrip() {
        let r = TrackRating {
            cad_id: 0xBEEF,
            rating: 5,
            thumbs_up: true,
        };
        let mut buf = [0u8; 16];
        let n = encode_track_rating(&r, &mut buf).expect("encode");
        assert_eq!(decode_track_rating(&buf[..n]), Some(r));
        // Rating 0 / 6 refused at both boundaries.
        for bad in [0u8, 6, 255] {
            let bad_rating = TrackRating {
                cad_id: 1,
                rating: bad,
                thumbs_up: false,
            };
            assert_eq!(encode_track_rating(&bad_rating, &mut buf), None);
        }
        let mut payload = vec![0x01, 0x00]; // cad 1, rating 0 → decode refuses
        payload[0] = 1;
        assert_eq!(decode_track_rating(&payload), None);
        let u = JamUpvoteMsg {
            cad_id: 0x1234,
            voter_nonce: [9, 8, 7, 6],
            up: true,
        };
        let mut buf = [0u8; 24];
        let n = encode_jam_upvote(&u, &mut buf).expect("encode");
        assert_eq!(decode_jam_upvote(&buf[..n]), Some(u));
        // Truncated upvote (missing nonce bytes) refused.
        assert_eq!(decode_jam_upvote(&buf[..n - 3]), None);
    }

    #[test]
    fn wsx_frame_codec_hostile_sweep() {
        let payload = [0xABu8; 40];
        let mut frame = vec![0u8; MAX_WSX_FRAME];
        let n = encode_frame_into(
            &mut frame,
            WSX_QUEUE_DELTA,
            0x1234,
            FLAG_ACK_REQUESTED,
            &payload,
        )
        .expect("encode");
        frame.truncate(n);
        let parsed = parse_frame(&frame).expect("parse");
        assert_eq!(parsed.frame_type, WSX_QUEUE_DELTA);
        assert_eq!(parsed.seq, 0x1234);
        assert_eq!(parsed.flags, FLAG_ACK_REQUESTED);
        assert_eq!(parsed.payload, &payload[..]);
        // Truncations.
        for cut in [0usize, 3, 6, 9, n - 1] {
            assert_eq!(parse_frame(&frame[..cut]), Err(WsxReject::Truncated));
        }
        // Magic / version / type / checksum.
        let mut bad = frame.clone();
        bad[0] ^= 0xFF;
        assert_eq!(parse_frame(&bad), Err(WsxReject::BadMagic));
        let mut bad = frame.clone();
        bad[2] = 2;
        assert_eq!(parse_frame(&bad), Err(WsxReject::BadVersion));
        let mut bad = frame.clone();
        bad[3] = 0x55;
        assert_eq!(parse_frame(&bad), Err(WsxReject::BadType));
        let mut bad = frame.clone();
        bad[12] ^= 0x40;
        assert_eq!(parse_frame(&bad), Err(WsxReject::BadChecksum));
    }

    #[test]
    fn manifest_bytes_roundtrip_and_lies_rejected() {
        let data = vec![7u8; 2500]; // 3 segments @ 1 KiB
        let seg_hashes: Vec<[u8; 32]> = (0..3)
            .map(|i| {
                let lo = i * SEGMENT_SIZE;
                let hi = (lo + SEGMENT_SIZE).min(data.len());
                seg_hash(&data[lo..hi])
            })
            .collect();
        let entries = vec![ManifestEntry {
            info: CacheTrackInfo {
                cad_id: 0x77,
                total_len: data.len() as u64,
                seg_size: SEGMENT_SIZE as u32,
                total_hash: seg_hash(&data),
            },
            seg_hashes,
        }];
        let bytes = encode_manifest_bytes(&entries).expect("encode");
        let back = decode_manifest_bytes(&bytes).expect("decode");
        assert_eq!(back.len(), 1);
        assert_eq!(back[0].info, entries[0].info);
        assert_eq!(back[0].seg_hashes, entries[0].seg_hashes);
        // Lying hash count → hard reject (hand-built hostile stream).
        let mut hand = vec![0u8; 128];
        let used = {
            let mut w = WearBuf::new(&mut hand);
            w.put_varint(1).unwrap();
            w.put_varint(0x77).unwrap();
            w.put_varint(data.len() as u64).unwrap();
            w.put_varint(SEGMENT_SIZE as u64).unwrap();
            w.put_varint(9).unwrap(); // lying count
            w.put_bytes(&[0u8; 32]).unwrap();
            w.put_bytes(&[0u8; 32]).unwrap();
            w.pos()
        };
        hand.truncate(used);
        let lying = hand;
        assert!(decode_manifest_bytes(&lying).is_none());
        // Seg size 0 / oversize / zero-length refused.
        assert_eq!(segments_for(0, 1000), None);
        assert_eq!(segments_for(1000, 0), None);
        assert_eq!(segments_for(1000, SEGMENT_SIZE as u32 + 1), None);
        assert_eq!(
            segments_for((MAX_TRACK_SEGMENTS as u64 + 1) * 1000, 1000),
            None
        );
        assert_eq!(segments_for(2500, 1000), Some(3));
        assert_eq!(segments_for(2000, 1000), Some(2));
    }

    fn deliver(phone: &mut PhoneSyncCoordinator, watch: &mut WatchSyncCoordinator) {
        for a in phone.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            watch.on_frame(&frame, 0);
        }
        for a in watch.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            phone.on_frame(&frame, 0);
        }
    }

    fn deliver_watch_to_phone(watch: &mut WatchSyncCoordinator, phone: &mut PhoneSyncCoordinator) {
        for a in watch.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            phone.on_frame(&frame, 0);
        }
    }

    fn pseudo_track(seed: u8, size: usize) -> Vec<u8> {
        (0..size)
            .map(|i| {
                seed.wrapping_add((i % 251) as u8)
                    .wrapping_mul(3)
                    .wrapping_add(7)
            })
            .collect()
    }

    #[test]
    fn phone_to_watch_queue_and_voting_sync_bidirectional() {
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig::default());
        let mut watch = WatchSyncCoordinator::new();

        // phone → watch: queue delta mirror.
        assert!(phone.push_queue_delta(&[
            QueueOp::Add {
                cad_id: 11,
                after_pos: 0
            },
            QueueOp::Add {
                cad_id: 22,
                after_pos: 1
            },
            QueueOp::Add {
                cad_id: 33,
                after_pos: 2
            },
        ]));
        deliver(&mut phone, &mut watch);
        assert_eq!(watch.queue_mirror(), &[11, 22, 33]);
        assert!(watch
            .drain_events()
            .iter()
            .any(|e| matches!(e, WearSyncEvent::QueueMirrorUpdated { len: 3 })));

        // watch → phone: queue action + rating + upvote.
        assert!(watch.send_queue_action(&QueueOp::Add {
            cad_id: 44,
            after_pos: 1
        }));
        assert!(watch.send_rating(&TrackRating {
            cad_id: 22,
            rating: 5,
            thumbs_up: true
        }));
        assert!(watch.send_jam_upvote(&JamUpvoteMsg {
            cad_id: 33,
            voter_nonce: [1, 2, 3, 4],
            up: true
        }));
        deliver_watch_to_phone(&mut watch, &mut phone);
        let events = phone.drain_events();
        assert!(events.iter().any(|e| matches!(
            e,
            WearSyncEvent::QueueActionReceived {
                op: QueueOp::Add {
                    cad_id: 44,
                    after_pos: 1
                }
            }
        )));
        assert!(events.iter().any(|e| matches!(
            e,
            WearSyncEvent::RatingReceived {
                rating: TrackRating {
                    cad_id: 22,
                    rating: 5,
                    thumbs_up: true
                }
            }
        )));
        assert!(events.iter().any(|e| matches!(
            e,
            WearSyncEvent::JamUpvoteReceived {
                upvote: JamUpvoteMsg {
                    cad_id: 33,
                    voter_nonce: [1, 2, 3, 4],
                    up: true
                }
            }
        )));
        // Watch's own edit reflected locally in its mirror.
        assert_eq!(watch.queue_mirror(), &[11, 44, 22, 33]);
    }

    #[test]
    fn offline_cache_transfer_paces_verifies_and_completes() {
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig {
            bytes_per_second: 4 * 1024 * 1024,
            burst_bytes: 64 * 1024,
            ..WearSyncConfig::default()
        });
        let mut watch = WatchSyncCoordinator::new();

        // Two small tracks (5 segments and 3-ish segments).
        let t1 = pseudo_track(1, 5 * SEGMENT_SIZE);
        let t2 = pseudo_track(2, 3 * SEGMENT_SIZE - 17);
        assert!(phone.start_cache_push(7, &[(0xA1, &t1), (0xB2, &t2)], 0));
        assert!(phone.drain_actions().iter().any(
            |a| matches!(a, WearSyncAction::Send { frame } if frame[3] == WSX_CACHE_MANIFEST)
        ));

        let mut now = 0u64;
        let mut rounds = 0usize;
        loop {
            rounds += 1;
            now += 10;
            phone.tick(now);
            for a in phone.drain_actions() {
                let WearSyncAction::Send { frame } = a;
                watch.on_frame(&frame, now);
            }
            watch.tick();
            for a in watch.drain_actions() {
                let WearSyncAction::Send { frame } = a;
                phone.on_frame(&frame, now);
            }
            if phone.cache_complete() {
                break;
            }
            assert!(
                rounds < 2_000,
                "cache push must converge; phone {:?} watch {:?}",
                phone.stats(),
                watch.stats()
            );
        }
        // Watch verified every segment + total hashes.
        assert_eq!(watch.stats().segs_verified, 8);
        assert_eq!(watch.stats().segs_corrupt, 0);
        assert_eq!(watch.stats().tracks_completed, 2);
        assert_eq!(phone.stats().tracks_completed, 2);
        // Byte-equivalence end-to-end.
        assert_eq!(watch.take_track(0xA1), Some(t1.clone()));
        assert_eq!(watch.take_track(0xB2), Some(t2.clone()));
        // Pacing fired: dispatch took multiple rounds (token budget).
        assert!(rounds > 1, "pacer must throttle dispatch into rounds");
    }

    #[test]
    fn window_backpressure_defers_dispatch() {
        // One fresh segment dispatches per tick; with no acks ever
        // arriving, the in-flight window caps the total at CACHE_WINDOW.
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig {
            bytes_per_second: 10 * 1024 * 1024,
            burst_bytes: 2 * SEGMENT_SIZE as u64,
            ..WearSyncConfig::default()
        });
        let track = pseudo_track(3, 32 * SEGMENT_SIZE);
        phone.start_cache_push(5, &[(0x1, &track)], 0);
        let _ = phone.drain_actions(); // manifest frames held back
        let mut sent = 0usize;
        for t in 1..=40 {
            phone.tick(t * 10);
            sent += count_chunks(&mut phone);
        }
        assert_eq!(sent, CACHE_WINDOW, "in-flight window caps dispatch");
    }

    fn count_chunks(phone: &mut PhoneSyncCoordinator) -> usize {
        phone
            .drain_actions()
            .into_iter()
            .filter(|a| matches!(a, WearSyncAction::Send { frame } if frame[3] == WSX_CACHE_CHUNK))
            .count()
    }

    #[test]
    fn corrupt_segment_is_rejected_and_healed() {
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig {
            bytes_per_second: 4 * 1024 * 1024,
            burst_bytes: 64 * 1024,
            ..WearSyncConfig::default()
        });
        let mut watch = WatchSyncCoordinator::new();
        let track = pseudo_track(9, 2 * SEGMENT_SIZE);
        assert!(phone.start_cache_push(3, &[(0xC9, &track)], 0));
        // Deliver the manifest frames to the watch.
        for a in phone.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            watch.on_frame(&frame, 0);
        }
        assert!(watch
            .drain_events()
            .iter()
            .any(|e| matches!(e, WearSyncEvent::ManifestReceived { manifest_id: 3, .. })));

        // Pump chunks, corrupting the FIRST data segment once (payload
        // byte flipped + FNV re-fixed → only Blake3 can catch it).
        let mut now = 0u64;
        let mut corrupted_once = false;
        let mut seg_bad_seen = false;
        for round in 0..500 {
            now += 10;
            phone.tick(now);
            for a in phone.drain_actions() {
                let WearSyncAction::Send { frame } = a;
                {
                    let mut frame = frame;
                    if frame[3] == WSX_CACHE_CHUNK && !corrupted_once {
                        let flip = frame.len() - 6;
                        frame[flip] ^= 0x01;
                        let sum = fnv1a32(&frame[..frame.len() - 4]).to_le_bytes();
                        let e = frame.len() - 4;
                        frame[e..e + 4].copy_from_slice(&sum);
                        corrupted_once = true;
                    }
                    watch.on_frame(&frame, now);
                }
            }
            watch.tick();
            for a in watch.drain_actions() {
                let WearSyncAction::Send { frame } = a;
                if frame[3] == WSX_CACHE_CONTROL && frame[WSX_HEADER_LEN] == CTRL_SEG_BAD {
                    seg_bad_seen = true;
                }
                phone.on_frame(&frame, now);
            }
            if phone.cache_complete() {
                break;
            }
            assert!(
                round < 499,
                "must converge after corruption heal; phone {:?} watch {:?}",
                phone.stats(),
                watch.stats()
            );
        }
        assert!(seg_bad_seen, "watch must have raised SEG_BAD");
        assert!(corrupted_once);
        assert_eq!(watch.stats().segs_corrupt, 1);
        assert_eq!(watch.stats().segs_verified, 2);
        // Byte-equivalence despite the injected corruption.
        assert_eq!(watch.take_track(0xC9), Some(track));
        assert!(phone.stats().segs_retransmitted >= 1);
    }

    #[test]
    fn battery_pause_hysteresis_and_eco_pacing() {
        let config = WearSyncConfig::default();
        let mut phone = PhoneSyncCoordinator::new(config);
        let mut watch = WatchSyncCoordinator::new();
        let track = pseudo_track(5, 4 * SEGMENT_SIZE);
        phone.start_cache_push(1, &[(0xD4, &track)], 0);
        for a in phone.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            watch.on_frame(&frame, 0);
        }
        // 10% battery, not charging → pause fires.
        watch.report_battery(10, false);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), Some(PauseReason::Battery));
        assert_eq!(
            phone
                .drain_events()
                .into_iter()
                .filter(|e| matches!(e, WearSyncEvent::CachePaused { .. }))
                .count(),
            1
        );
        // Ticks dispatch NOTHING while paused.
        for t in 1..=10 {
            phone.tick(t * 100);
            assert!(count_chunks(&mut phone) == 0);
        }
        // 25% (inside the hysteresis band), still not charging → stays paused.
        watch.report_battery(25, false);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), Some(PauseReason::Battery));
        // 31% → resumes.
        watch.report_battery(31, false);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), None);
        assert!(phone
            .drain_events()
            .iter()
            .any(|e| matches!(e, WearSyncEvent::CacheResumed)));
        // Charging at 10% also resumes.
        watch.report_battery(10, true);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), None);
    }

    fn watch_battery_frame(pct: u8, charging: bool) -> Vec<u8> {
        let payload = [CTRL_BATTERY, pct, u8::from(charging)];
        encode_frame(WSX_CACHE_CONTROL, 1, 0, &payload)
    }

    #[test]
    fn eco_pacing_halves_the_effective_rate() {
        // 190 KB/s base: eco (35%, not charging) → 95 KB/s. Each 10 ms
        // tick refills 950 B — short of the 1000 B segment cost, so
        // dispatch only happens every OTHER tick (or from the initial
        // burst); a full-rate refill would dispatch every tick.
        let track = pseudo_track(5, 8 * SEGMENT_SIZE);
        let mut eco = PhoneSyncCoordinator::new(WearSyncConfig {
            bytes_per_second: 190_000,
            burst_bytes: SEGMENT_SIZE as u64,
            ..WearSyncConfig::default()
        });
        eco.start_cache_push(2, &[(0xE5, &track)], 0);
        let _ = eco.drain_actions();
        eco.on_frame(&watch_battery_frame(35, false), 0);
        assert_eq!(eco.paused(), None);
        // tick 10: initial burst (1000) dispatches segment 0.
        eco.tick(10);
        assert_eq!(count_chunks(&mut eco), 1, "initial burst dispatches one");
        // tick 20: eco refill alone = 950 B < 1000 B → deferred.
        eco.tick(20);
        assert_eq!(eco.bucket_tokens(), 950, "eco refill = 95 KB/s × 10 ms");
        assert_eq!(count_chunks(&mut eco), 0, "eco pacing defers the segment");
        // tick 30: 950 + 950 = 1900 → capped to burst 1000 → dispatches.
        eco.tick(30);
        assert!(count_chunks(&mut eco) >= 1, "accumulated tokens dispatch");
    }

    #[test]
    fn user_pause_blocks_and_cancel_clears() {
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig::default());
        let mut watch = WatchSyncCoordinator::new();
        let track = pseudo_track(7, 2 * SEGMENT_SIZE);
        phone.start_cache_push(9, &[(0xF6, &track)], 0);
        for a in phone.drain_actions() {
            let WearSyncAction::Send { frame } = a;
            watch.on_frame(&frame, 0);
        }
        watch.user_pause(true);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), Some(PauseReason::User));
        phone.tick(100);
        assert!(count_chunks(&mut phone) == 0);
        watch.user_pause(false);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert_eq!(phone.paused(), None);
        // Cancel: clears the push.
        watch.cancel_manifest(9);
        deliver_watch_to_phone(&mut watch, &mut phone);
        assert!(phone
            .drain_events()
            .iter()
            .any(|e| matches!(e, WearSyncEvent::CacheCancelled { manifest_id: 9 })));
        phone.tick(200);
        assert!(count_chunks(&mut phone) == 0);
        assert!(!phone.cache_complete());
    }

    #[test]
    fn hostile_manifest_stream_is_rejected_total() {
        let mut watch = WatchSyncCoordinator::new();
        // Lying hash count vs. layout (proper varints, hostile intent).
        let mut chunk = vec![0u8; 256];
        let used = {
            let mut w = WearBuf::new(&mut chunk);
            w.put_varint(1).unwrap(); // count
            w.put_varint(0x11).unwrap(); // cad
            w.put_varint(1024).unwrap(); // total_len → 1024 segments @ 1 B
            w.put_varint(1).unwrap(); // seg_size 1
            w.put_varint(9).unwrap(); // LYING hash count
            w.put_bytes(&[0u8; 32]).unwrap(); // total hash
            w.put_bytes(&[0u8; 32]).unwrap(); // one hash
            w.pos()
        };
        chunk.truncate(used);
        // Grammar: [manifest_id, idx varint, count varint, data].
        let mut payload = vec![1u8, 0, 1]; // manifest 1, chunk 0 of 1
        payload.extend_from_slice(&chunk);
        let frame = encode_frame(WSX_CACHE_MANIFEST, 1, 0, &payload);
        assert!(!watch.on_frame(&frame, 0), "lying manifest must be refused");
        // No track state leaked.
        assert!(watch.drain_events().is_empty());
        // Oversize count / lying indices.
        let mut payload = vec![1u8, 5, 1]; // idx 5 of count 1 — impossible
        payload.extend_from_slice(&[0u8; 16]);
        let frame = encode_frame(WSX_CACHE_MANIFEST, 2, 0, &payload);
        assert!(!watch.on_frame(&frame, 0));
        let payload = vec![1u8, 0, 0]; // count 0 — impossible
        let frame = encode_frame(WSX_CACHE_MANIFEST, 3, 0, &payload);
        assert!(!watch.on_frame(&frame, 0));
        // Hostile manifest flood: endless chunk frames with distinct
        // indices beyond the byte budget.
        let mut watch2 = WatchSyncCoordinator::new();
        for i in 0..(MAX_MANIFEST_BYTES / (MAX_WSX_PAYLOAD - 8) + 8) {
            let mut payload = vec![4u8];
            let idx = (i % 200) as u64;
            let count = 200u64;
            let mut w = WearBuf::new(&mut payload);
            let _ = w.put_varint(idx);
            let _ = w.put_varint(count);
            let used = w.pos();
            payload.truncate(used);
            payload.extend_from_slice(&[0u8; MAX_WSX_PAYLOAD - 8]);
            let frame = encode_frame(WSX_CACHE_MANIFEST, (i % 60000) as u16, 0, &payload);
            watch2.on_frame(&frame, 0);
        }
        assert!(
            watch2.stats().frames_rejected >= 1,
            "flood must trip the byte budget"
        );
        let buffered: usize = watch2.manifest_chunks.values().map(Vec::len).sum();
        assert!(buffered <= MAX_MANIFEST_BYTES);
    }

    #[test]
    fn queue_mirror_is_bounded_and_edits_are_safe() {
        let mut watch = WatchSyncCoordinator::new();
        for i in 0..(MAX_QUEUE_MIRROR + 10) as u64 {
            watch.apply_op(QueueOp::Add {
                cad_id: i,
                after_pos: u32::MAX,
            });
        }
        assert!(watch.queue_mirror().len() <= MAX_QUEUE_MIRROR);
        // Out-of-range edits are no-ops, never panics.
        watch.apply_op(QueueOp::Remove { index: 9999 });
        watch.apply_op(QueueOp::Move {
            from: 9998,
            to: 9999,
        });
        assert!(watch.queue_mirror().len() <= MAX_QUEUE_MIRROR);
        watch.apply_op(QueueOp::Clear);
        assert!(watch.queue_mirror().is_empty());
    }

    #[test]
    fn control_frames_retry_until_acked_then_abandon() {
        let mut phone = PhoneSyncCoordinator::new(WearSyncConfig {
            rto_ms: 100,
            ..WearSyncConfig::default()
        });
        // Queue delta with ack_requested — no receiver, so retries fire.
        assert!(phone.push_queue_delta(&[QueueOp::Clear]));
        let mut t = 0u64;
        let mut retries_seen = false;
        let mut abandoned = false;
        for i in 0..100 {
            t += 50;
            phone.tick(t);
            let stats = phone.stats();
            if stats.control_retries >= 1 {
                retries_seen = true;
            }
            if phone
                .drain_events()
                .iter()
                .any(|e| matches!(e, WearSyncEvent::ControlAbandoned { .. }))
            {
                abandoned = true;
                break;
            }
            let _ = i;
        }
        assert!(retries_seen, "RTO must retransmit the un-acked delta");
        assert!(abandoned, "exhausted retries must raise ControlAbandoned");
        assert!(phone.stats().control_retries >= MAX_CONTROL_ATTEMPTS as u64 - 1);
    }
}
