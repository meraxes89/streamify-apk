//! gossip.rs — PlumTree Epidemic Broadcast Trees for the Jam mesh (Phase 2).
//!
//! Push-Lazy-Push multicast (Leitão, Pereira & Rodrigues, SRDS'07), adapted
//! for the Streamify wire format:
//!
//!   Eager push  — every new frame is forwarded immediately along the eager
//!                 tree edges: mutations (JamOpWire), PTP ticks and track
//!                 manifests land with one-hop latency.
//!   Lazy tree   — peers outside the eager tree get compact IHAVE
//!                 announcements (message-id batches) on [`GossipParams::
//!                 lazy_tick`]; if an eager copy was lost to Wi-Fi jitter,
//!                 the missing peer GRAFTs the announcer, which reinstates
//!                 the eager link and re-serves the payload.
//!   Pruning     — receiving an eager frame we already have means the link
//!                 is redundant; once the stream has gone QUIET, a few
//!                 redundant deliveries demote the sender to the lazy set
//!                 (hysteresis default 2). During bursts pruning is
//!                 suppressed: full-mesh eager redundancy is the loss
//!                 shield, and a graft reinstates any link a later burst
//!                 actually needs.
//!
//! PROTOCOL REFINEMENTS over the paper (the lead's brief explicitly grants
//! autonomy to refine):
//!   R1  Sequence-gap healing with a reorder window: `sequence` is a dense
//!       per-sender broadcast counter (wire deviation D3), so a receiver
//!       that holds seq N and N+2 knows N+1 is missing WITHOUT waiting for
//!       an IHAVE. The graft waits out `reorder_window` so in-flight
//!       stragglers quench it — only genuinely lost frames pay the heal.
//!   R2  Graft rotation + bounded retries: each retry targets a different
//!       neighbor (fair round-robin), so a single dead or lossy link cannot
//!       starve a heal. After `graft_max_tries` the id is abandoned
//!       (counted, never re-armed) instead of looping forever.
//!   R3  Graft-serving payload cache: delivered frames keep their raw bytes
//!       in a byte-budgeted FIFO (2 MiB default) so any peer — not just the
//!       original sender — can serve a GRAFT. Track manifests (the largest
//!       gossip frames) stay servable long enough for stragglers.
//!   R4  Origin tail flush: the LAST messages of a burst have no successor
//!       to reveal their gap, and pure-eager links never carry an IHAVE for
//!       them. After `tail_flush_delay` of broadcast silence the origin
//!       re-pushes its last `tail_flush_k` payloads once — a single
//!       one-way trip; holders dedupe, stragglers recover.
//!
//! The engine is a pure state machine: `now` is injected, all effects are
//! returned as [`Action`]s, and the mesh node executes them. That keeps the
//! entire epidemic protocol unit-testable with a synthetic clock and zero
//! sockets (see tests below, plus the 5-node chaos integration test).

use std::collections::{HashMap, HashSet, VecDeque};
use std::time::Duration;

use crate::p2p_mesh::{P2pPacketHeader, MSG_GOSSIP_GRAFT, MSG_GOSSIP_IHAVE, MSG_GOSSIP_PRUNE};
use streamify_core::wire_le::{le_u16, le_u32, le_u64};

/// Identity of one gossip message: (origin sender, broadcast sequence).
/// Dense per origin — that density is what makes gap healing (R1) work.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MsgId {
    pub sender: u64,
    pub seq: u32,
}

/// Effect the mesh node must execute on the engine's behalf.
#[derive(Debug, Clone)]
pub enum Action {
    /// Relay a frame verbatim (same header, same origin, same sequence).
    ForwardRaw { to: u64, bytes: Vec<u8> },
    /// Emit an engine control frame (IHAVE / GRAFT); sequence = 0 (D3).
    Control {
        to: u64,
        msg_type: u8,
        payload: Vec<u8>,
    },
}

/// Result of ingesting one data frame.
#[derive(Debug, Clone, Default)]
pub struct Outcomes {
    /// True when the frame was new and must be delivered to the app layer.
    pub delivered_new: bool,
    pub actions: Vec<Action>,
}

/// Tunable PlumTree parameters (production defaults; tests tighten them).
#[derive(Debug, Clone)]
pub struct GossipParams {
    /// Lazy-set flush cadence: how often IHAVE batches leave the node.
    pub lazy_tick: Duration,
    /// Grace between learning a message is missing and GRAFTing — lets a
    /// racing eager copy win without paying the round trip.
    pub graft_delay: Duration,
    /// Backoff between graft retries for the same message id.
    pub graft_retry: Duration,
    /// Give up healing an id after this many graft attempts.
    pub graft_max_tries: u8,
    /// Message ids per IHAVE frame (12 bytes each + 2 byte header).
    pub ihave_batch: usize,
    /// Delivered-id dedupe capacity (ids; ~1000-op floods fit 8× over).
    pub cache_capacity: usize,
    /// Byte budget for the graft-serving raw-payload cache (R3).
    pub payload_cache_bytes: usize,
    /// Consecutive redundant eager deliveries before pruning a link.
    /// Pruning only fires while the stream is QUIET (see on_data): during
    /// bursts full-mesh eager redundancy is the loss shield.
    pub prune_redundancy_threshold: u32,
    /// Reordering absorption window for sequence-gap grafts (R1): a hole
    /// revealed by an out-of-order successor waits this long before a
    /// graft fires, so in-flight stragglers quench the heal. Set ≈ the
    /// network's worst one-way jitter.
    pub reorder_window: Duration,
    /// Origin tail flush (R4): after this much broadcast silence the
    /// origin re-pushes its last `tail_flush_k` payloads — a single
    /// one-way trip that heals the final messages of a burst (which have
    /// no successor to reveal their gap) without a graft round-trip.
    pub tail_flush_delay: Duration,
    /// How many of the origin's most recent broadcasts each flush round
    /// re-pushes.
    pub tail_flush_k: usize,
    /// Maximum tail-flush rounds per quiet epoch (each loss round covers
    /// another factor of the link loss probability).
    pub tail_flush_rounds: u8,
    /// After the flush rounds are spent, the origin keeps announcing its
    /// recent ids (batched IHAVEs — graft backstop) on every `lazy_tick`
    /// for this long after its last broadcast, then goes fully silent.
    pub tail_announce_window: Duration,
    /// R1b grace: the first frame from an unknown origin may be seq N
    /// either because we joined late OR because the burst head was lost /
    /// reordered. Graft-grace at most this many holes below the first-seen
    /// seq (recent-history catch-up); anything older is the CRDT fold's
    /// job, never gossip's.
    pub snap_grace_holes: usize,
    /// Delay before the R1b grace grafts fire (long — by then, in-flight
    /// stragglers and relay copies have long since landed).
    pub snap_grace_delay: Duration,
    /// Distinct peers a single graft attempt is sent to (parallel heal:
    /// one lost control frame no longer costs a full retry round).
    pub graft_fanout: usize,
    /// Copies of a graft SERVE frame (the heal's payload re-send). Heals
    /// are rare, so duplicating the serve collapses the retry-chain tail
    /// probability quadratically (P(both lost) = p²) at negligible cost.
    pub serve_redundancy: u8,
    // ── Phase 1 scale mode (directive A / gap #11: N = 32 peers) ──────
    /// Peer count beyond which scale mode engages (directive: > 16 peers
    /// triggers the anti-saturation machinery). Below it, behavior is the
    /// proven 5-node profile, byte for byte.
    pub scale_threshold: usize,
    /// Maximum eager links a scale-mode node maintains. A 31-neighbor full
    /// mesh eagerly forwarding every frame is 31×31 ≈ 961 datagrams per op
    /// — a mobile UDP socket chokes long before that. Capping the eager
    /// set at `log2(N)+3`-ish keeps per-op emissions ≈ N × cap while the
    /// lazy IHAVE backstop (batched, 12 bytes/id) plus R1 gap-heal grafts
    /// keep loss resilience at full-mesh levels.
    pub scale_fanout_cap: usize,
    /// Redundant eager deliveries tolerated in scale mode before a link is
    /// demoted to lazy — and UNLIKE small-mesh mode this fires during
    /// bursts too. THE TREE-FORMATION KNOB: at 32 peers the eager overlap
    /// is an 8-way mesh on bootstrap; two consecutive duplicate deliveries
    /// demote the redundant link and the graph collapses to a spanning
    /// tree within the first few frames of any burst. Steady state drops
    /// from ~N×cap to ~N datagrams per op — the socket-saturation curve
    /// the directive demands we avoid — while the lazy IHAVE backstop
    /// (12 bytes per id, ~96 ids per frame) plus R1 gap grafts keep loss
    /// resilience at full-mesh levels. (Quiet-mode small-mesh behavior
    /// is untouched.)
    pub scale_prune_threshold: u32,
    /// Upper bound on graft frames emitted per `on_tick` (anti-storm
    /// pacing): a join-storm catch-up arms hundreds of grafts at once;
    /// releasing them in one tick is exactly the amplification burst the
    /// brief forbids. Due-but-unsent grafts roll to the next tick (5 ms
    /// housekeeping cadence), draining at ≤ `graft_burst_cap` per tick.
    pub graft_burst_cap: usize,
    /// Retry backoff escalation cap: a graft's retry interval grows with
    /// the number of attempts (`graft_retry × (1 + tries/4)`), capped at
    /// this multiplier, so a dead link's heal chain backs off instead of
    /// hammering a saturated socket.
    pub adaptive_backoff_max: u32,
    /// Abandon quarantine window: an id whose heal exhausted its retries
    /// stays quarantined this long before a new IHAVE/tail-announce may
    /// re-arm it. Short enough that a saturated socket's transient losses
    /// heal on the second sweep; long enough that a truly dead id does
    /// not spin the retry loop forever.
    pub abandon_rearm: Duration,
    /// Scale-mode relay-announce fan-out: how many lazy peers each relay
    /// announces an id to (fair rotation over the stream). The origin
    /// still announces its own ids to every lazy peer.
    pub scale_announce_cap: usize,
    /// Adaptive origin fan-out: a node that is actively broadcasting keeps
    /// this many eager links (vs `scale_fanout_cap` for idle relays). The
    /// broadcaster's pushes come from ONE source — bounded — and a wide
    /// first hop is what holds stream latency at 1-2 hops over 10-80 ms
    /// links: a thin origin builds deep random chains whose per-hop delay
    /// dominates the convergence budget at N=32 (measured: 4+ hop tails).
    /// Relays below it provide the second loss-resilient path.
    pub scale_origin_fanout_cap: usize,
    /// Depth of the origin's own-recent id ring — the tail announce and
    /// the quarantine re-arm sweep draw from it. 64 covers the Phase-0
    /// 5-node bursts; 1024 covers 1000-op streams at N=32.
    pub own_recent_depth: usize,
    /// Scale-mode anti-entropy cadence: how often the origin re-runs the
    /// own-recent announcement sweep (watermark reset per round). This is
    /// the retransmission backstop that makes delta-only announces safe.
    pub reconcile_interval: Duration,
    /// Scale-mode delta-only announcements: track the highest sequence
    /// announced per (peer, origin) and never re-announce below it. Dense
    /// per-origin sequences (wire D3) make this safe — a receiver that
    /// missed the only IHAVE still detects the hole via R1 watermark gap
    /// healing the moment any successor arrives, and the R4 tail flush
    /// covers the burst tail. Small-mesh mode keeps the redundant
    /// re-announce (its loss shield) — only scale mode dedupes.
    pub delta_announce: bool,
}

impl Default for GossipParams {
    fn default() -> Self {
        GossipParams {
            lazy_tick: Duration::from_millis(20),
            graft_delay: Duration::from_millis(5),
            graft_retry: Duration::from_millis(40),
            graft_max_tries: 12,
            ihave_batch: 96,
            cache_capacity: 16_384,
            payload_cache_bytes: 2 << 20,
            prune_redundancy_threshold: 2,
            reorder_window: Duration::from_millis(50),
            tail_flush_delay: Duration::from_millis(30),
            tail_flush_k: 16,
            tail_flush_rounds: 4,
            tail_announce_window: Duration::from_secs(2),
            snap_grace_holes: 256,
            snap_grace_delay: Duration::from_millis(300),
            graft_fanout: 2,
            serve_redundancy: 1,
            scale_threshold: 16,
            scale_fanout_cap: 4,
            scale_prune_threshold: 2,
            graft_burst_cap: 32,
            adaptive_backoff_max: 8,
            abandon_rearm: Duration::from_millis(750),
            scale_announce_cap: 4,
            scale_origin_fanout_cap: 16,
            own_recent_depth: 64,
            reconcile_interval: Duration::from_millis(250),
            delta_announce: true,
        }
    }
}

/// Engine telemetry snapshot.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct GossipStats {
    pub broadcasts: u64,
    pub delivered: u64,
    pub dups: u64,
    pub eager_forwards: u64,
    pub lazy_announces: u64,
    pub ihaves_tx: u64,
    pub grafts_tx: u64,
    pub grafts_rx: u64,
    pub graft_heals: u64,
    pub prunes: u64,
    pub abandoned: u64,
    pub cache_evicts: u64,
    pub tail_flushes: u64,
    pub eager_peers: usize,
    pub lazy_peers: usize,
    pub pending_grafts: usize,
    // Phase 1 scale-mode telemetry.
    /// True while the engine runs above `scale_threshold` peers.
    pub scale_mode: bool,
    /// Eager links demoted to lazy by the scale-mode fanout cap.
    pub scale_demotions: u64,
    /// Lazy links promoted back to eager when the adaptive origin cap rose.
    pub scale_promotions: u64,
    /// Announce ids suppressed by the delta-only watermark.
    pub delta_skips: u64,
    /// Grafts held back by the per-tick anti-storm pacing.
    pub grafts_deferred: u64,
    /// Retry delays inflated by the adaptive backoff.
    pub backoff_events: u64,
    /// Ids requested inside batched graft frames (amortization evidence).
    pub graft_ids_tx: u64,
    /// Directional prune REQUESTS sent (receiver asked a source to stop).
    pub prune_requests: u64,
    /// Heal attempts that exhausted their retries (transient — see the
    /// abandon quarantine below).
    pub abandonments: u64,
}

#[derive(Debug, Clone, Copy)]
struct PendingGraft {
    deadline_ns: i64,
    peer: u64,
    tries: u8,
}

/// PlumTree engine — one instance per mesh node.
pub struct GossipEngine {
    params: GossipParams,
    me: u64,
    neighbors: HashSet<u64>,
    eager: HashSet<u64>,
    lazy: HashSet<u64>,
    /// Delivered ids (dedupe + convergence bookkeeping).
    delivered: HashSet<MsgId>,
    order: VecDeque<MsgId>,
    /// ABANDON QUARANTINE (Phase 1): ids whose heal exhausted its retries,
    /// mapped to the abandonment instant. Unlike the Phase-0 forever-set,
    /// quarantine EXPIRES after `abandon_rearm` — under real-world socket
    /// saturation a heal chain can legitimately fail 16 times in a row,
    /// and a permanently-dead id would leave a hole no backstop can ever
    /// fill (the delta-announce watermark already marked it announced).
    /// Re-armable after the window; entries pruned to bound memory.
    abandoned: HashMap<MsgId, i64>,
    /// Raw bytes of recently delivered frames, served on GRAFT (R3).
    payload_cache: HashMap<MsgId, Vec<u8>>,
    payload_order: VecDeque<MsgId>,
    payload_bytes: usize,
    /// Scheduled grafts keyed by the missing message id.
    pending: HashMap<MsgId, PendingGraft>,
    /// Per-lazy-peer announcement outbox, flushed on `lazy_tick`.
    outbox: HashMap<u64, Vec<MsgId>>,
    /// Timebase anchor for the lazy outbox: entries wait at least
    /// `lazy_tick` after the outbox transitions empty→non-empty before an
    /// IHAVE batch leaves the node.
    outbox_since_ns: i64,
    /// Consecutive redundant eager deliveries per peer (prune hysteresis).
    redundancy: HashMap<u64, u32>,
    /// Highest *contiguous* sequence seen per origin (gap detection, R1).
    watermark: HashMap<u64, u32>,
    /// Fair round-robin cursor for graft rotation (R2).
    rotation_cursor: u64,
    /// The origin's own recent broadcast ids, re-pushed by the tail flush.
    own_recent: VecDeque<MsgId>,
    /// Last time WE broadcast (origin-side clock for the tail flush).
    last_broadcast_ns: i64,
    /// Latch: tail flush rounds already fired for the current quiet epoch.
    tail_flush_rounds_fired: u8,
    last_tail_flush_ns: i64,
    last_tail_announce_ns: i64,
    /// Last engine activity (broadcast or delivery) — the quiet gate for
    /// pruning and the tail flush.
    last_activity_ns: i64,
    /// Delta-announce watermark: highest own-origin sequence already
    /// announced to each peer (scale mode only).
    announced: HashMap<u64, u32>,
    stats: GossipStats,
}

impl GossipEngine {
    pub fn new(me: u64, params: GossipParams) -> Self {
        GossipEngine {
            params,
            me,
            neighbors: HashSet::new(),
            eager: HashSet::new(),
            lazy: HashSet::new(),
            delivered: HashSet::new(),
            order: VecDeque::new(),
            abandoned: HashMap::new(),
            payload_cache: HashMap::new(),
            payload_order: VecDeque::new(),
            payload_bytes: 0,
            pending: HashMap::new(),
            outbox: HashMap::new(),
            outbox_since_ns: i64::MIN / 2,
            redundancy: HashMap::new(),
            watermark: HashMap::new(),
            rotation_cursor: 0,
            own_recent: VecDeque::new(),
            last_broadcast_ns: i64::MIN / 2,
            tail_flush_rounds_fired: 0,
            last_tail_flush_ns: i64::MIN / 2,
            last_tail_announce_ns: i64::MIN / 2,
            last_activity_ns: i64::MIN / 2,
            announced: HashMap::new(),
            stats: GossipStats::default(),
        }
    }

    // ── membership ─────────────────────────────────────────────────────

    /// Idempotent. New links start eager (optimistic PlumTree bootstrap):
    /// the flood prunes them down to a tree as redundancy is observed.
    /// In scale mode the eager set is capped immediately (see
    /// [`GossipEngine::enforce_eager_cap`]).
    pub fn neighbor_up(&mut self, peer: u64) {
        self.neighbors.insert(peer);
        if !self.eager.contains(&peer) && !self.lazy.contains(&peer) {
            self.eager.insert(peer);
        }
        self.enforce_eager_cap(false);
    }

    pub fn neighbor_down(&mut self, peer: u64) {
        self.neighbors.remove(&peer);
        self.eager.remove(&peer);
        self.lazy.remove(&peer);
        self.outbox.remove(&peer);
        self.redundancy.remove(&peer);
        self.announced.remove(&peer);
        // Grafts aimed at the departed peer rotate onto another neighbor.
        let ids: Vec<MsgId> = self
            .pending
            .iter()
            .filter(|(_, pg)| pg.peer == peer)
            .map(|(id, _)| *id)
            .collect();
        for id in ids {
            // Rotation target computed before the pending-map borrow.
            let rotated = self.rotate_peer(peer);
            if let Some(pg) = self.pending.get_mut(&id) {
                pg.peer = rotated;
            }
        }
    }

    pub fn knows_peer(&self, peer: u64) -> bool {
        self.neighbors.contains(&peer)
    }

    /// Ops/test hook: demote a peer from the eager tree to the lazy set.
    /// (The flood path does this automatically via prune hysteresis; this
    /// exists so the lazy-announce behavior can be exercised in isolation.)
    pub fn demote_to_lazy(&mut self, peer: u64) {
        self.eager.remove(&peer);
        if self.neighbors.contains(&peer) {
            self.lazy.insert(peer);
        }
    }

    // ── Phase 1 scale mode (directive A / gap #11) ─────────────────────

    /// True while the mesh exceeds `scale_threshold` peers — the
    /// anti-saturation machinery (capped eager fanout, burst pruning,
    /// delta-only announces, graft pacing) is active only then.
    fn is_scaled(&self) -> bool {
        self.neighbors.len() > self.params.scale_threshold
    }

    /// Caps the eager set in scale mode. WHICH links survive is decided by
    /// a decorrelated deterministic ranking — `fnv1a(me ⊕ peer)` — so
    /// different nodes keep different eager neighborhoods even though every
    /// ranking is individually stable (no hash-order nondeterminism, no
    /// adversarial concentration of the tree on the lowest ids).
    ///
    /// Demoted links move to the lazy set: they still receive batched
    /// IHAVEs (12 bytes per id, ~96 ids per frame) and any genuinely lost
    /// frame is healed by R1 gap grafts or R4 tail flushes — PlumTree's
    /// own resilience, now exercised at 32 nodes.
    fn enforce_eager_cap(&mut self, as_origin: bool) {
        if !self.is_scaled() {
            return;
        }
        // ADAPTIVE ORIGIN FANOUT: a node that is actively BROADCASTING keeps
        // a wide eager set (`scale_origin_fanout_cap`) — its pushes come
        // from exactly one source (bounded), and the wide first hop is
        // what holds stream latency at 1-2 hops over 10-80 ms links. Idle
        // relays keep the tight cap (their forwarding multiplies
        // mesh-wide) and provide the second loss-resilient path.
        let cap = if as_origin {
            self.params.scale_origin_fanout_cap
        } else {
            self.params.scale_fanout_cap
        }
        .max(1);
        // Decorrelation: rank depends on BOTH endpoints, so node A's #1
        // pick is node B's #17 — the capped eager graph stays rich.
        let me = self.me;
        let rank = |p: u64| {
            let mut x = [0u8; 16];
            x[..8].copy_from_slice(&me.to_le_bytes());
            x[8..].copy_from_slice(&p.to_le_bytes());
            let mut h = 0x811c_9dc5u32;
            for b in x {
                h ^= b as u32;
                h = h.wrapping_mul(0x0100_0193);
            }
            h
        };
        if self.eager.len() > cap {
            let mut ranked: Vec<u64> = self.eager.iter().copied().collect();
            ranked.sort_by_key(|&p| rank(p));
            for &p in ranked.iter().skip(cap) {
                self.eager.remove(&p);
                self.lazy.insert(p);
                self.stats.scale_demotions += 1;
                self.stats.prunes += 1;
            }
        } else if self.eager.len() < cap {
            // RESTORE: the cap can RISE (idle relay → active origin). Links
            // demoted while idle are promoted back — same decorrelated
            // ranking, so the origin's wide first hop re-forms the moment
            // it starts broadcasting.
            let mut candidates: Vec<u64> = self.lazy.iter().copied().collect();
            candidates.sort_by_key(|&p| rank(p));
            for p in candidates.into_iter().take(cap - self.eager.len()) {
                self.lazy.remove(&p);
                self.eager.insert(p);
                self.stats.scale_promotions += 1;
            }
        }
    }

    /// Delta-announce gate (scale mode): returns true when `id` should be
    /// announced to `peer`, recording the watermark advance when it is.
    fn should_announce(&mut self, peer: u64, id: MsgId) -> bool {
        if !self.is_scaled() || !self.params.delta_announce || id.sender != self.me {
            return true; // small mesh / foreign origin: original behavior
        }
        match self.announced.get(&peer) {
            Some(&mark) if id.seq <= mark => {
                self.stats.delta_skips += 1;
                false
            }
            _ => {
                self.announced.insert(peer, id.seq);
                true
            }
        }
    }

    // ── broadcast / ingest ─────────────────────────────────────────────

    /// Origin-side broadcast: records the frame and fans it out.
    ///
    /// Origin fanout differs from relay fanout in ONE way: EVERY neighbor
    /// (eager and lazy) also gets the id queued for the lazy-cadence IHAVE
    /// batch. The origin is the authoritative holder of its own stream, so
    /// a receiver that lost the eager copy learns what is missing within
    /// one lazy_tick + one-way delay — an announce-based heal path that
    /// does not depend on a successor revealing the gap. `graft_delay`
    /// must cover the straggler window so this never fires spuriously.
    /// Relays announce only to their lazy sets, keeping steady-state
    /// announcement traffic proportional to origins, not peers.
    pub fn broadcast(&mut self, header: &P2pPacketHeader, raw: &[u8], now: i64) -> Vec<Action> {
        let id = MsgId {
            sender: u64::from_le_bytes(header.sender_id),
            seq: header.sequence,
        };
        // The idle→active origin transition widens the eager cap — apply
        // it at the transition point itself (restore promotes the demoted
        // links back, forming the wide first hop for this stream).
        if self.stats.broadcasts == 0 {
            self.enforce_eager_cap(true);
        }
        self.own_recent.push_back(id);
        while self.own_recent.len() > self.params.own_recent_depth.max(1) {
            self.own_recent.pop_front();
        }
        self.last_broadcast_ns = now;
        self.tail_flush_rounds_fired = 0; // new burst → flushes may fire again
        self.record_new(id, raw.to_vec(), now);
        self.stats.broadcasts += 1;
        self.note_sequence(id, None, now);

        let was_empty = self.outbox.is_empty();
        let peers: Vec<u64> = self.neighbors.iter().copied().collect();
        let mut actions = Vec::new();
        for p in peers {
            if p == self.me {
                continue;
            }
            if self.eager.contains(&p) {
                actions.push(Action::ForwardRaw {
                    to: p,
                    bytes: raw.to_vec(),
                });
                self.stats.eager_forwards += 1;
                // Origin-sync: eager peers get the announcement too —
                // EXCEPT in scale mode, where the capped eager overlap
                // plus R1 gap healing already covers them and the
                // delta-only announcement policy takes over.
                if !self.is_scaled() {
                    self.outbox.entry(p).or_default().push(id);
                }
            } else if self.lazy.contains(&p) && self.should_announce(p, id) {
                self.outbox.entry(p).or_default().push(id);
            }
        }
        if was_empty && !self.outbox.is_empty() {
            self.outbox_since_ns = now;
        }
        actions
    }

    /// Relay-side ingest of a gossip-routed data frame.
    pub fn on_data(
        &mut self,
        from: u64,
        header: &P2pPacketHeader,
        raw: &[u8],
        now: i64,
    ) -> Outcomes {
        let id = MsgId {
            sender: u64::from_le_bytes(header.sender_id),
            seq: header.sequence,
        };

        // NOTE: an id in the abandon QUARANTINE is still missing — a late
        // serve arriving for it MUST be delivered (record_new clears the
        // quarantine). Phase-0's `|| abandoned.contains(&id)` here turned
        // the quarantine into a black hole: the heal itself was discarded
        // as a "dup", so a quarantined id could never converge — the
        // Phase-1 permanent-hole bug, root cause of the stalled trials.
        if self.delivered.contains(&id) {
            self.stats.dups += 1;
            let mut actions = Vec::new();
            if self.eager.contains(&from) {
                // Redundancy accounting → DIRECTIONAL PRUNE (classic
                // PlumTree): the RECEIVER of redundant pushes asks the
                // SOURCE to stop pushing — the demotion happens at the
                // sender when it processes the prune frame. Demoting the
                // receiver's own outbound link (the Phase-0 shortcut)
                // never reduced the inbound dup pressure, so the push
                // graph oscillated instead of converging to a tree at
                // N=32 (Phase-1 churn root cause).
                //
                // Quiet gate (small mesh): during an active burst the
                // full-mesh eager redundancy IS the loss shield (a
                // receiver missing a frame on one link still has three
                // more); prunes fire only once the stream has gone quiet.
                // SCALE MODE: prune mid-burst as well — at 32 peers a
                // 4-way capped overlap is already a stronger shield than
                // the 5-node mesh ever had, and the tree must form DURING
                // the flood or the lazy path bottlenecks the stream.
                let (threshold, quiet) = if self.is_scaled() {
                    (self.params.scale_prune_threshold, true)
                } else {
                    let quiet = now.saturating_sub(self.last_activity_ns)
                        >= self.params.lazy_tick.as_nanos() as i64;
                    (self.params.prune_redundancy_threshold, quiet)
                };
                if quiet {
                    let r = self.redundancy.entry(from).or_insert(0);
                    *r += 1;
                    if *r >= threshold.max(1) {
                        self.redundancy.insert(from, 0);
                        self.stats.prune_requests += 1;
                        actions.push(Action::Control {
                            to: from,
                            msg_type: MSG_GOSSIP_PRUNE,
                            payload: Vec::new(),
                        });
                    }
                } else {
                    // Redundancy during a burst is welcome, not punished.
                    self.redundancy.insert(from, 0);
                }
            }
            return Outcomes {
                delivered_new: false,
                actions,
            };
        }

        self.record_new(id, raw.to_vec(), now);
        self.note_sequence(id, Some(from), now);
        let actions = self.fanout(id, raw, Some(from), now);
        Outcomes {
            delivered_new: true,
            actions,
        }
    }

    /// Ingest of a PlumTree control frame (IHAVE / GRAFT).
    pub fn on_control(&mut self, from: u64, msg_type: u8, payload: &[u8], now: i64) -> Vec<Action> {
        match msg_type {
            MSG_GOSSIP_IHAVE => {
                for id in parse_ihave_payload(payload) {
                    // IHAVEs are already lagged by the lazy cadence — a
                    // short graft_delay lets eager stragglers still win.
                    let deadline = now + self.params.graft_delay.as_nanos() as i64;
                    self.schedule_graft(id, from, deadline, now);
                }
                Vec::new()
            }
            MSG_GOSSIP_GRAFT => {
                // The requester grafts us back into its eager tree. Scale
                // mode bounds the reinstated set: graft churn must never
                // grow the eager links past the fanout cap again.
                self.lazy.remove(&from);
                self.eager.insert(from);
                self.enforce_eager_cap(false);
                self.redundancy.insert(from, 0);
                self.stats.grafts_rx += 1;
                let mut actions = Vec::new();
                let ids = parse_graft_payload_multi(payload);
                if !ids.is_empty() {
                    let mut served = 0usize;
                    for id in &ids {
                        if let Some(bytes) = self.payload_cache.get(id) {
                            // Redundant serve: heals are rare, and one lost
                            // serve frame would cost the requester a full
                            // retry round (retry + two one-way trips).
                            for _ in 0..self.params.serve_redundancy.max(1) {
                                actions.push(Action::ForwardRaw {
                                    to: from,
                                    bytes: bytes.clone(),
                                });
                            }
                            served += 1;
                        }
                    }
                    if served > 0 {
                        self.stats.graft_heals += 1;
                    }
                }
                actions
            }
            MSG_GOSSIP_PRUNE => {
                // Directional prune (classic PlumTree): the receiver of
                // redundant pushes asked us to stop. The demotion happens
                // HERE, at the push source — the only place that can
                // actually reduce the receiver's inbound pressure.
                if self.eager.remove(&from) {
                    self.lazy.insert(from);
                    self.stats.prunes += 1;
                }
                self.redundancy.insert(from, 0);
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// Timer tick: flush lazy announcements, fire due grafts.
    pub fn on_tick(&mut self, now: i64) -> Vec<Action> {
        let mut actions = Vec::new();

        // 1. Lazy IHAVE flush — gated on the lazy_tick cadence so a burst
        // of broadcasts does not announce each message individually.
        if !self.outbox.is_empty()
            && now.saturating_sub(self.outbox_since_ns) >= self.params.lazy_tick.as_nanos() as i64
        {
            let outbox = std::mem::take(&mut self.outbox);
            for (peer, ids) in outbox {
                if ids.is_empty() || !self.neighbors.contains(&peer) {
                    continue;
                }
                for chunk in ids.chunks(self.params.ihave_batch.max(1)) {
                    actions.push(Action::Control {
                        to: peer,
                        msg_type: MSG_GOSSIP_IHAVE,
                        payload: build_ihave_payload(chunk),
                    });
                    self.stats.ihaves_tx += 1;
                    self.stats.lazy_announces += chunk.len() as u64;
                }
            }
        }

        // 2. Graft timers — Phase-1 anti-storm machinery, three layers:
        //    BATCHING: due grafts are grouped by target peer and packed
        //    GRAFT_BATCH ids per frame (a catch-up arming 300 ids emits
        //    ~19 frames, not 300 — the frame count is what saturates a
        //    mobile socket, not the byte count).
        //    PACING: at most `graft_burst_cap` graft frames leave the node
        //    per tick; the rest keep their deadline satisfied but roll to
        //    the next tick.
        //    ADAPTIVE BACKOFF: each retry's interval grows with the attempt
        //    count (`retry × (1 + tries/4)`, capped), so a persistently
        //    lossy path backs off instead of hammering.
        let due: Vec<MsgId> = self
            .pending
            .iter()
            .filter(|(_, pg)| pg.deadline_ns <= now)
            .map(|(id, _)| *id)
            .collect();
        // Pass 1: retire exhausted ids into the quarantine, resetting the
        // delta-announce watermark so the tail announce re-advertises them
        // (a quarantined id whose watermark says "announced" would never
        // be requested again — the exact permanent-hole bug Phase 1 fixes).
        let mut any_abandoned = false;
        let mut runnable: Vec<MsgId> = Vec::with_capacity(due.len());
        for id in due {
            let tries = self.pending.get(&id).map(|pg| pg.tries).unwrap_or(0);
            if tries >= self.params.graft_max_tries {
                self.pending.remove(&id);
                self.abandoned.insert(id, now);
                self.stats.abandoned += 1;
                self.stats.abandonments += 1;
                any_abandoned = true;
            } else {
                runnable.push(id);
            }
        }
        if any_abandoned {
            // Watermark reset: the next tail-announce round re-advertises
            // own-recent ids (≤64) to every peer — one bounded re-announce
            // sweep, then delta-only behavior resumes.
            self.announced.clear();
            // Bound the quarantine: expired entries are useless.
            if self.abandoned.len() > 4_096 {
                let rearm_ns = self.params.abandon_rearm.as_nanos() as i64;
                self.abandoned
                    .retain(|_, at| now.saturating_sub(*at) < rearm_ns * 8);
            }
        }
        // Pass 2: group by target peer, emit batched grafts under pacing.
        let burst_cap = self.params.graft_burst_cap.max(1);
        let mut emitted = 0usize;
        let mut i = 0usize;
        while i < runnable.len() {
            if emitted >= burst_cap {
                // Pacing: defer every remaining due graft by one cadence
                // (tries kept — a deferral is not an attempt).
                self.stats.grafts_deferred += (runnable.len() - i) as u64;
                for id in &runnable[i..] {
                    if let Some(pg) = self.pending.get_mut(id) {
                        pg.deadline_ns = now + Duration::from_millis(5).as_nanos() as i64;
                    }
                }
                break;
            }
            // Batch: up to GRAFT_BATCH ids sharing the same primary target.
            let head_peer = self
                .pending
                .get(&runnable[i])
                .map(|pg| pg.peer)
                .unwrap_or(0);
            let mut batch: Vec<MsgId> = Vec::with_capacity(GRAFT_BATCH);
            let mut j = i;
            while j < runnable.len() && batch.len() < GRAFT_BATCH {
                let p = self
                    .pending
                    .get(&runnable[j])
                    .map(|pg| pg.peer)
                    .unwrap_or(0);
                if p == head_peer {
                    batch.push(runnable[j]);
                }
                j += 1;
            }
            if batch.is_empty() {
                i += 1;
                continue;
            }
            // Parallel heal: fan the batch to `graft_fanout` distinct peers
            // so one lost control frame does not cost a retry round.
            let mut targets: Vec<u64> = vec![head_peer];
            for _ in 1..self.params.graft_fanout.max(1) {
                let t = self.rotate_peer(*targets.last().unwrap_or(&head_peer));
                if !targets.contains(&t) {
                    targets.push(t);
                }
            }
            for t in &targets {
                actions.push(Action::Control {
                    to: *t,
                    msg_type: MSG_GOSSIP_GRAFT,
                    payload: build_graft_batch_payload(&batch),
                });
                self.stats.grafts_tx += 1;
                self.stats.graft_ids_tx += batch.len() as u64;
            }
            emitted += targets.len();
            // Rotation target computed before the pending-map borrow.
            let rotated = self.rotate_peer(*targets.last().unwrap_or(&head_peer));
            for id in &batch {
                if let Some(pg) = self.pending.get_mut(id) {
                    let tries = pg.tries;
                    pg.tries = tries + 1;
                    pg.peer = rotated;
                    // Adaptive backoff: escalate the retry interval with
                    // the attempt count (1×, 1.25×, … capped at
                    // `adaptive_backoff_max`×) so dead links drain quietly.
                    let escalation = 1u32
                        + (tries as u32 / 4)
                            .min(self.params.adaptive_backoff_max.saturating_sub(1));
                    if escalation > 1 {
                        self.stats.backoff_events += 1;
                    }
                    let retry_ns = (self.params.graft_retry.as_nanos() as i64)
                        .saturating_mul(escalation as i64);
                    pg.deadline_ns = now + retry_ns;
                }
            }
            i = j.max(i + 1);
        }

        // 3. Origin tail machinery (R4). The final messages of a burst have
        //    no successor to reveal their gap, and pure-eager links never
        //    carry an IHAVE for them. Two backstops, both origin-side and
        //    both bounded:
        //      a) FLUSH — for `tail_flush_rounds` rounds, spaced
        //         `tail_flush_delay`, re-push the last `tail_flush_k`
        //         payloads. One-way trips; holders dedupe, stragglers
        //         recover. Each extra round covers another factor of the
        //         link loss probability. SCALE MODE: flush targets shrink
        //         to the eager set — 31 raw re-pushes × k payloads is the
        //         exact socket-saturation curve the scale cap exists to
        //         prevent, and lazy peers hold the IHAVE/graft backstop.
        //      b) ANNOUNCE — after the rounds are spent, keep batching
        //         own-recent ids into IHAVEs, letting receivers GRAFT for
        //         anything the flush rounds missed.
        //         SMALL MESH: every `lazy_tick` for `tail_announce_window`
        //         (2 s), then full silence — the redundant re-announce IS
        //         the loss shield.
        //         SCALE MODE: a PERIODIC ANTI-ENTROPY RECONCILIATION round
        //         every `reconcile_interval` for as long as the node lives.
        //         Each round RESETS the per-peer announced watermark, so an
        //         IHAVE lost to a kernel-buffer drop is retransmitted on
        //         the next round — the delta-only watermark is safe ONLY
        //         under exactly this re-announcement backstop (a single
        //         lost "only-copy" IHAVE would otherwise leave a mid-stream
        //         hole with no successor to reveal it: the Phase-1
        //         permanent-hole bug). Bounded cost: ≤ own_recent_depth
        //         ids → ≤ 11 IHAVE frames per peer per round.
        let broadcast_quiet = self.last_broadcast_ns > i64::MIN / 4
            && now.saturating_sub(self.last_broadcast_ns)
                >= self.params.tail_flush_delay.as_nanos() as i64;
        if broadcast_quiet && !self.neighbors.is_empty() && !self.own_recent.is_empty() {
            let flush_spacing_ok = now.saturating_sub(self.last_tail_flush_ns)
                >= self.params.tail_flush_delay.as_nanos() as i64;
            if self.tail_flush_rounds_fired < self.params.tail_flush_rounds && flush_spacing_ok {
                self.tail_flush_rounds_fired += 1;
                self.last_tail_flush_ns = now;
                self.stats.tail_flushes += 1;
                let k = self.params.tail_flush_k.max(1);
                let ids: Vec<MsgId> = self.own_recent.iter().rev().take(k).copied().collect();
                // Flush targets: ALL neighbors — the tail ids are exactly
                // the ones with no successor to reveal their gap, so the
                // one-way re-push must reach everyone at ONE hop. Bounded:
                // ≤ k payloads × N peers × tail_flush_rounds, once per
                // quiet epoch (32×31×6 ≈ 6 K datagrams at N=32).
                let peers: Vec<u64> = self.neighbors.iter().copied().collect();
                for id in ids {
                    if let Some(bytes) = self.payload_cache.get(&id) {
                        for &p in &peers {
                            if p != self.me {
                                actions.push(Action::ForwardRaw {
                                    to: p,
                                    bytes: bytes.clone(),
                                });
                            }
                        }
                    }
                }
            } else if self.tail_flush_rounds_fired >= self.params.tail_flush_rounds
                && (self.is_scaled()
                    || now.saturating_sub(self.last_broadcast_ns)
                        < self.params.tail_announce_window.as_nanos() as i64)
                && now.saturating_sub(self.last_tail_announce_ns)
                    >= (if self.is_scaled() {
                        self.params.reconcile_interval
                    } else {
                        self.params.lazy_tick
                    })
                    .as_nanos() as i64
            {
                self.last_tail_announce_ns = now;
                // Reconciliation round boundary: reset the delta watermarks
                // so every own-recent id is re-advertised once this round.
                if self.is_scaled() {
                    self.announced.clear();
                }
                let ids: Vec<MsgId> = self.own_recent.iter().copied().collect();
                let peers: Vec<u64> = self.neighbors.iter().copied().collect();
                for p in peers {
                    if p == self.me {
                        continue;
                    }
                    // Delta-only gate: scale mode skips ids this peer has
                    // already been told about WITHIN this round.
                    let fresh: Vec<MsgId> = if self.is_scaled() && self.params.delta_announce {
                        ids.iter()
                            .copied()
                            .filter(|id| self.should_announce(p, *id))
                            .collect()
                    } else {
                        ids.clone()
                    };
                    for chunk in fresh.chunks(self.params.ihave_batch.max(1)) {
                        if chunk.is_empty() {
                            continue;
                        }
                        actions.push(Action::Control {
                            to: p,
                            msg_type: MSG_GOSSIP_IHAVE,
                            payload: build_ihave_payload(chunk),
                        });
                        self.stats.ihaves_tx += 1;
                    }
                }
            }
        }

        actions
    }

    // ── introspection for tests / telemetry ────────────────────────────

    pub fn is_delivered(&self, id: MsgId) -> bool {
        self.delivered.contains(&id)
    }

    pub fn delivered_count(&self) -> usize {
        self.delivered.len()
    }

    pub fn pending_graft_count(&self) -> usize {
        self.pending.len()
    }

    /// Test/diagnostic probe for one message id.
    pub fn probe(&self, id: MsgId) -> (bool, bool, bool, u32) {
        (
            self.delivered.contains(&id),
            self.pending.contains_key(&id),
            self.abandoned.contains_key(&id),
            self.watermark.get(&id.sender).copied().unwrap_or(0),
        )
    }

    pub fn stats(&self) -> GossipStats {
        let mut s = self.stats;
        s.eager_peers = self.eager.len();
        s.lazy_peers = self.lazy.len();
        s.pending_grafts = self.pending.len();
        s.scale_mode = self.is_scaled();
        s
    }

    // ───────────────────────── internal machinery ──────────────────────

    fn record_new(&mut self, id: MsgId, raw: Vec<u8>, now: i64) {
        self.delivered.insert(id);
        self.order.push_back(id);
        self.last_activity_ns = now;
        // A late copy of a quarantined id is the heal landing — clear the
        // quarantine so future loss of the SAME id can heal again.
        self.abandoned.remove(&id);
        while self.delivered.len() > self.params.cache_capacity {
            match self.order.pop_front() {
                Some(old) => {
                    self.delivered.remove(&old);
                }
                None => break,
            }
        }
        self.pending.remove(&id);
        self.stats.delivered += 1;

        // Graft-serving payload cache (R3), byte-budgeted FIFO.
        if !self.payload_cache.contains_key(&id) {
            self.payload_bytes += raw.len();
            self.payload_cache.insert(id, raw);
            self.payload_order.push_back(id);
            while self.payload_bytes > self.params.payload_cache_bytes
                && self.payload_order.len() > 1
            {
                let old = self.payload_order.pop_front().expect("order non-empty");
                if let Some(b) = self.payload_cache.remove(&old) {
                    self.payload_bytes -= b.len();
                    self.stats.cache_evicts += 1;
                }
            }
        }
    }

    /// Sequence-gap healing (R1): schedule grafts for every hole a newly
    /// delivered (or originated) frame reveals below its sequence number.
    ///
    /// R1b — first-seen grace: the FIRST frame from an unknown origin may
    /// arrive at seq N because we joined the session late (prefix is the
    /// CRDT fold's problem) OR because the burst head was lost / reordered
    /// on every link (prefix is genuinely ours to heal). We cannot tell
    /// the two apart at the engine layer, so we graft-grace the most
    /// recent `snap_grace_holes` holes below the first-seen seq with a
    /// long delay, and ignore anything older.
    fn note_sequence(&mut self, id: MsgId, from: Option<u64>, now: i64) {
        if !self.watermark.contains_key(&id.sender) && id.seq > 1 {
            let grace = self.params.snap_grace_holes as u32;
            let lo = id.seq.saturating_sub(grace).max(1);
            if from.is_some() {
                let deadline = now + self.params.snap_grace_delay.as_nanos() as i64;
                for seq in lo..id.seq {
                    let hole = MsgId {
                        sender: id.sender,
                        seq,
                    };
                    if !self.delivered.contains(&hole) {
                        // Grace grafts also target the origin first.
                        self.schedule_graft(hole, id.sender, deadline, now);
                    }
                }
            }
            self.watermark.insert(id.sender, id.seq - 1);
            return;
        }
        let current = self.watermark.get(&id.sender).copied().unwrap_or(0);
        let mut holes: Vec<u32> = Vec::new();
        if id.seq == current + 1 {
            // Advance the contiguous watermark as far as the cache knows.
            let mut next = id.seq + 1;
            while self.delivered.contains(&MsgId {
                sender: id.sender,
                seq: next,
            }) {
                next += 1;
            }
            self.watermark.insert(id.sender, next - 1);
        } else if id.seq > current + 1 {
            holes = (current + 1..id.seq)
                .filter(|&s| {
                    !self.delivered.contains(&MsgId {
                        sender: id.sender,
                        seq: s,
                    })
                })
                .collect();
            self.watermark.insert(id.sender, id.seq.max(current));
        }
        // id.seq <= watermark: late straggler — nothing to reveal.

        // Grafts are scheduled only for relays (the originator already
        // holds everything it broadcast) — and only after the watermark
        // bookkeeping released its borrows.
        if from.is_some() {
            for seq in holes {
                // Reorder window (R1): in-flight stragglers quench the
                // graft before it fires — only genuinely lost frames pay
                // the round trip. First target is the ORIGIN (it provably
                // holds everything it broadcast); retries rotate.
                let deadline = now + self.params.reorder_window.as_nanos() as i64;
                self.schedule_graft(
                    MsgId {
                        sender: id.sender,
                        seq,
                    },
                    id.sender,
                    deadline,
                    now,
                );
            }
        }
    }

    fn schedule_graft(&mut self, id: MsgId, peer: u64, deadline_ns: i64, now: i64) {
        if self.delivered.contains(&id) || self.pending.contains_key(&id) {
            return;
        }
        // Abandon quarantine: an id that exhausted its retries becomes
        // re-armable after `abandon_rearm` — permanent death would leave
        // an unfillable hole once the delta-announce watermark has marked
        // it announced (Phase 1 hardening).
        if let Some(&at) = self.abandoned.get(&id) {
            if now.saturating_sub(at) < self.params.abandon_rearm.as_nanos() as i64 {
                return;
            }
        }
        self.pending.insert(
            id,
            PendingGraft {
                deadline_ns,
                peer,
                tries: 0,
            },
        );
    }

    fn fanout(&mut self, id: MsgId, raw: &[u8], exclude: Option<u64>, now: i64) -> Vec<Action> {
        let mut actions = Vec::new();
        let was_empty = self.outbox.is_empty();
        let mut outbox_touched = false;
        // SCALE MODE relay-announce bounding: with the eager set capped, a
        // 31-neighbor mesh leaves ~27 lazy peers per relay, and announcing
        // EVERY id to EVERY lazy peer is 32×27 ≈ 860 announce-entries per
        // op — the dominant saturation term at N=32. Each id is instead
        // announced to `scale_announce_cap` lazy peers chosen by fair
        // rotation, so over a stream every lazy peer keeps receiving
        // announcements while per-op announce volume stays bounded. (The
        // ORIGIN still announces its own ids to ALL lazy peers — the
        // origin-sync backstop — and any announced id a peer misses is
        // still healed by R1 watermark gaps once a successor arrives.)
        let lazy_cap = if self.is_scaled() {
            self.params.scale_announce_cap.max(1)
        } else {
            usize::MAX
        };
        let mut lazy_sorted: Vec<u64> = Vec::with_capacity(self.lazy.len());
        let mut lazy_emitted = 0usize;
        for &p in &self.neighbors {
            if Some(p) == exclude || p == self.me {
                continue;
            }
            if self.eager.contains(&p) {
                actions.push(Action::ForwardRaw {
                    to: p,
                    bytes: raw.to_vec(),
                });
                self.stats.eager_forwards += 1;
            } else if self.lazy.contains(&p) {
                lazy_sorted.push(p);
            }
        }
        if !lazy_sorted.is_empty() {
            lazy_sorted.sort_unstable();
            // Fair rotation start — decorrelated per node, stable per call.
            let start = (self.rotation_cursor % lazy_sorted.len() as u64) as usize;
            self.rotation_cursor = self.rotation_cursor.wrapping_add(1);
            for k in 0..lazy_sorted.len() {
                let p = lazy_sorted[(start + k) % lazy_sorted.len()];
                if lazy_emitted >= lazy_cap {
                    self.stats.delta_skips += 1; // announce-space rotation skip
                    continue;
                }
                lazy_emitted += 1;
                if self.should_announce(p, id) {
                    self.outbox.entry(p).or_default().push(id);
                    outbox_touched = true;
                }
            }
        }
        if outbox_touched && was_empty {
            // Fresh outbox epoch → the lazy_tick window starts here.
            self.outbox_since_ns = now;
        }
        actions
    }

    /// Fair graft rotation (R2): cycles deterministically through ALL
    /// neighbors. A plain `find(|p| p != exclude)` over a HashSet picks the
    /// same neighbor every time (hash order is stable per map state), which
    /// can ping-pong grafts between two peers that both lack the message
    /// while the origin — who provably has it — is never asked.
    fn rotate_peer(&mut self, exclude: u64) -> u64 {
        let mut peers: Vec<u64> = self.neighbors.iter().copied().collect();
        if peers.is_empty() {
            return exclude;
        }
        if peers.len() == 1 {
            // Only one candidate left: even if it is the excluded peer it
            // beats silently dropping the heal.
            return peers[0];
        }
        peers.sort_unstable();
        let start = (self.rotation_cursor % peers.len() as u64) as usize;
        self.rotation_cursor = self.rotation_cursor.wrapping_add(1);
        for i in 0..peers.len() {
            let p = peers[(start + i) % peers.len()];
            if p != exclude {
                return p;
            }
        }
        exclude
    }
}

// ────────────────────────────────────────────── IHAVE / GRAFT wire codecs

/// IHAVE payload: `[u16 count][{u64 sender LE, u32 seq LE} × count]`.
pub fn build_ihave_payload(ids: &[MsgId]) -> Vec<u8> {
    let mut p = Vec::with_capacity(2 + ids.len() * 12);
    p.extend_from_slice(&(ids.len().min(u16::MAX as usize) as u16).to_le_bytes());
    for id in ids.iter().take(u16::MAX as usize) {
        p.extend_from_slice(&id.sender.to_le_bytes());
        p.extend_from_slice(&id.seq.to_le_bytes());
    }
    p
}

/// Decodes one IHAVE/GRAFT id record at `base` (12 bytes: u64 sender +
/// u32 seq). `None` on any bounds drift — callers drop the record,
/// never panic (audit #22 item 6 sweep).
fn msg_id_at(payload: &[u8], base: usize) -> Option<MsgId> {
    Some(MsgId {
        sender: le_u64(payload.get(base..base + 8)?)?,
        seq: le_u32(payload.get(base + 8..base + 12)?)?,
    })
}

/// Parses an IHAVE payload; malformed trailing bytes are dropped, never
/// fatal (a half-read announcement still heals most of the tree).
pub fn parse_ihave_payload(payload: &[u8]) -> Vec<MsgId> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let count = le_u16(&payload[0..2]).map(|v| v as usize).unwrap_or(0);
    let entries = (payload.len() - 2) / 12;
    let n = count.min(entries);
    let mut ids = Vec::with_capacity(n);
    for i in 0..n {
        let base = 2 + i * 12;
        ids.extend(msg_id_at(payload, base));
    }
    ids
}

/// GRAFT payload: `[u64 sender LE][u32 seq LE]`.
pub fn build_graft_payload(id: &MsgId) -> Vec<u8> {
    let mut p = Vec::with_capacity(12);
    p.extend_from_slice(&id.sender.to_le_bytes());
    p.extend_from_slice(&id.seq.to_le_bytes());
    p
}

/// Parses a GRAFT payload (strict: exactly 12 bytes).
pub fn parse_graft_payload(payload: &[u8]) -> Option<MsgId> {
    if payload.len() != 12 {
        return None;
    }
    msg_id_at(payload, 0)
}

// ───────────────────────────────────────────── Phase 1 batched grafts
// The classic GRAFT requests ONE id per frame — a 500-id catch-up (join
// storm, kernel-drop burst) costs 500 control frames, and frame COUNT is
// what saturates a mobile UDP socket. The batched form amortizes 16 ids
// into one 194-byte frame while remaining wire-compatible with the
// single-id form (a 12-byte payload is simply a batch of one).

/// Ids packed into one batched GRAFT frame.
pub const GRAFT_BATCH: usize = 16;

/// Batched GRAFT payload: `[u16 count][{u64 sender LE, u32 seq LE} × count]`.
/// The single-id 12-byte form (no count header) is still accepted.
pub fn build_graft_batch_payload(ids: &[MsgId]) -> Vec<u8> {
    let n = ids.len().min(u16::MAX as usize);
    let mut p = Vec::with_capacity(2 + n * 12);
    p.extend_from_slice(&(n as u16).to_le_bytes());
    for id in ids.iter().take(n) {
        p.extend_from_slice(&id.sender.to_le_bytes());
        p.extend_from_slice(&id.seq.to_le_bytes());
    }
    p
}

/// Parses either GRAFT form; strict bounds, never panics.
pub fn parse_graft_payload_multi(payload: &[u8]) -> Vec<MsgId> {
    // Legacy single-id form.
    if payload.len() == 12 {
        return parse_graft_payload(payload).into_iter().collect();
    }
    if payload.len() < 2 {
        return Vec::new();
    }
    let count = match le_u16(&payload[0..2]) {
        Some(c) if c > 0 => c as usize,
        _ => return Vec::new(),
    };
    if payload.len() != 2 + count * 12 {
        return Vec::new(); // malformed: drop the frame entirely
    }
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let base = 2 + i * 12;
        out.extend(msg_id_at(payload, base));
    }
    out
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::p2p_mesh::{encode_packet, SessionId, MAGIC, MSG_CRDT_OP, PROTO_VERSION};

    const A: u64 = 0xAAAA;
    const B: u64 = 0xBBBB;
    const C: u64 = 0xCCCC;
    const D: u64 = 0xDDDD;

    fn tight_params() -> GossipParams {
        GossipParams {
            lazy_tick: Duration::from_millis(10),
            graft_delay: Duration::from_millis(5),
            graft_retry: Duration::from_millis(40),
            graft_max_tries: 3,
            ihave_batch: 8,
            cache_capacity: 512,
            payload_cache_bytes: 64 << 10,
            prune_redundancy_threshold: 2,
            reorder_window: Duration::from_millis(5),
            tail_flush_delay: Duration::from_millis(10),
            tail_flush_k: 4,
            tail_flush_rounds: 4,
            tail_announce_window: Duration::from_millis(50),
            snap_grace_holes: 8,
            snap_grace_delay: Duration::from_millis(10),
            graft_fanout: 1, // exact-action tests pin single-target grafts
            serve_redundancy: 1,
            scale_threshold: 16,
            scale_fanout_cap: 4,
            scale_prune_threshold: 2,
            graft_burst_cap: 32,
            adaptive_backoff_max: 8,
            abandon_rearm: Duration::from_millis(750),
            scale_announce_cap: 4,
            scale_origin_fanout_cap: 16,
            own_recent_depth: 64,
            reconcile_interval: Duration::from_millis(250),
            delta_announce: true,
        }
    }

    fn frame(origin: u64, seq: u32, payload: &[u8]) -> (P2pPacketHeader, Vec<u8>) {
        let mut h = P2pPacketHeader {
            magic: MAGIC,
            version: PROTO_VERSION,
            msg_type: MSG_CRDT_OP,
            sender_id: origin.to_le_bytes(),
            session_id: SessionId::from_session_str("t").0,
            sequence: seq,
            timestamp_mono_ns: 0,
            payload_len: 0,
            checksum_fnv1a: 0,
        };
        let raw = encode_packet(&mut h, payload);
        (h, raw)
    }

    fn engine(me: u64, peers: &[u64]) -> GossipEngine {
        let mut e = GossipEngine::new(me, tight_params());
        for p in peers {
            e.neighbor_up(*p);
        }
        e
    }

    const NS_MS: i64 = 1_000_000;

    #[test]
    fn origin_tail_flush_repushes_last_k_after_quiet() {
        // A broadcasts 5 messages; the flush re-pushes only the last K=4
        // payloads, once, after `tail_flush_delay` of broadcast silence.
        let mut a = engine(A, &[B, C]);
        let mut raws = Vec::new();
        for s in 1..=5u32 {
            let (h, raw) = frame(A, s, b"payload");
            raws.push(raw);
            let _ = a.broadcast(&h, &raws[s as usize - 1], 0);
        }
        // During the burst: no flush (broadcast silence not reached).
        assert!(a.on_tick(5 * NS_MS).is_empty());

        let actions = a.on_tick(11 * NS_MS);
        let pushes: Vec<&Action> = actions
            .iter()
            .filter(|x| matches!(x, Action::ForwardRaw { .. }))
            .collect();
        // K=4 payloads × 2 peers, and seq 1 (beyond the K window) is NOT
        // re-pushed.
        assert_eq!(pushes.len(), 8, "K payloads × all peers");
        for a2 in pushes {
            match a2 {
                Action::ForwardRaw { to, bytes } => {
                    assert!(*to == B || *to == C);
                    assert!(raws.contains(bytes), "payload must come from the cache");
                    assert_ne!(bytes, &raws[0], "seq 1 is outside the flush window");
                }
                _ => unreachable!(),
            }
        }
        assert_eq!(a.stats().tail_flushes, 1);
        // The latch prevents re-flushing the same quiet epoch.
        assert!(a.on_tick(20 * NS_MS).is_empty());
    }

    #[test]
    fn eager_fanout_dedupe_and_quiet_gated_prune() {
        let mut a = engine(A, &[B, C, D]);
        let (h, raw) = frame(A, 1, b"op-1");
        let actions = a.broadcast(&h, &raw, 0);
        // Full-mesh bootstrap: everyone is eager on day one.
        assert_eq!(actions.len(), 3);

        let mut b = engine(B, &[A, C, D]);
        let out = b.on_data(A, &h, &raw, 0);
        assert!(out.delivered_new);
        // B relays to its other neighbors, excluding the source A.
        assert_eq!(out.actions.len(), 2);

        // Redundant copies DURING the burst: redundancy is welcome, the
        // prune is suppressed (quiet gate).
        let out = b.on_data(A, &h, &raw, 5 * NS_MS);
        assert!(!out.delivered_new);
        assert!(out.actions.is_empty());
        let out = b.on_data(A, &h, &raw, 8 * NS_MS);
        assert!(!out.delivered_new);
        assert_eq!(b.stats().prunes, 0, "no pruning while the stream is hot");

        // Once quiet (≥ lazy_tick since the last delivery), the next
        // redundant copies emit DIRECTIONAL prune requests: B asks A (the
        // push source) to stop pushing (hysteresis 2).
        let out = b.on_data(A, &h, &raw, 20 * NS_MS);
        assert!(!out.delivered_new);
        assert!(out.actions.is_empty(), "first dup: counter at 1");
        let out = b.on_data(A, &h, &raw, 21 * NS_MS);
        assert!(!out.delivered_new);
        assert_eq!(out.actions.len(), 1, "second dup: prune request emitted");
        match &out.actions[0] {
            Action::Control { to, msg_type, .. } => {
                assert_eq!(*to, A);
                assert_eq!(*msg_type, MSG_GOSSIP_PRUNE);
            }
            _ => panic!("expected prune control"),
        }
        // B's own eager set is untouched (the demotion happens at A).
        assert_eq!(b.stats().eager_peers, 3);
        assert_eq!(b.stats().prune_requests, 1);

        // A processes the prune: B is demoted in A's push set.
        let _ = a.on_control(B, MSG_GOSSIP_PRUNE, &[], 22 * NS_MS);
        let s = a.stats();
        assert_eq!(s.prunes, 1);
        assert_eq!(s.eager_peers, 2, "B demoted to lazy; C,D remain eager");
    }

    #[test]
    fn ihave_graft_heal_roundtrip() {
        let mut a = engine(A, &[B]);
        let (h, raw) = frame(A, 7, b"manifest");
        let _ = a.broadcast(&h, &raw, 0);

        // B missed the eager copy; A's IHAVE arrives at t=0. Densify the
        // sender's stream below the heal target (seq 1..6) first so the
        // gap detector reveals no spurious pre-history holes (R1b keeps
        // this from mattering in production, but the unit test pins the
        // exact pending set).
        let mut b = engine(B, &[A]);
        for s in 1..7u32 {
            let (hf, rawf) = frame(A, s, b"filler");
            let _ = b.on_data(A, &hf, &rawf, 0);
        }
        let ihave = build_ihave_payload(&[MsgId { sender: A, seq: 7 }]);
        let actions = b.on_control(A, MSG_GOSSIP_IHAVE, &ihave, 0);
        assert!(actions.is_empty(), "graft must wait out graft_delay");
        assert_eq!(b.pending_graft_count(), 1);

        // At t = graft_delay the graft fires.
        let actions = b.on_tick(5 * NS_MS);
        assert_eq!(actions.len(), 1);
        match &actions[0] {
            Action::Control {
                to,
                msg_type,
                payload,
            } => {
                assert_eq!(*to, A);
                assert_eq!(*msg_type, MSG_GOSSIP_GRAFT);
                // Phase 1: grafts are emitted in the batched form
                // (wire-compatible with the single-id 12-byte form).
                assert_eq!(
                    parse_graft_payload_multi(payload),
                    vec![MsgId { sender: A, seq: 7 }]
                );
            }
            _ => panic!("expected graft control"),
        }

        // A serves the payload from its graft cache and re-eagers B.
        let graft = build_graft_payload(&MsgId { sender: A, seq: 7 });
        let actions = a.on_control(B, MSG_GOSSIP_GRAFT, &graft, 5 * NS_MS);
        assert_eq!(actions.len(), 1);
        match &actions[0] {
            Action::ForwardRaw { to, bytes } => {
                assert_eq!(*to, B);
                assert_eq!(bytes, &raw);
            }
            _ => panic!("expected raw re-send"),
        }

        // B ingests the heal: delivered, no more pending grafts.
        let out = b.on_data(A, &h, &raw, 6 * NS_MS);
        assert!(out.delivered_new);
        assert_eq!(b.pending_graft_count(), 0);
        assert_eq!(a.stats().graft_heals, 1);
    }

    #[test]
    fn sequence_gaps_schedule_grafts_immediately() {
        // B receives (A,1) then (A,3) — the hole at 2 must be scheduled
        // WITHOUT any IHAVE (refinement R1).
        let mut b = engine(B, &[A, C]);
        let (h1, raw1) = frame(A, 1, b"op-1");
        let (h3, raw3) = frame(A, 3, b"op-3");
        assert!(b.on_data(A, &h1, &raw1, 0).delivered_new);
        assert!(b.on_data(A, &h3, &raw3, 10 * NS_MS).delivered_new);
        assert_eq!(b.pending_graft_count(), 1);

        let actions = b.on_tick(15 * NS_MS);
        assert!(actions.iter().any(|a| matches!(
            a,
            Action::Control { msg_type: MSG_GOSSIP_GRAFT, payload, .. }
                if parse_graft_payload_multi(payload) == vec![MsgId { sender: A, seq: 2 }]
        )));

        // Delivering the hole clears the pending graft.
        let (h2, raw2) = frame(A, 2, b"op-2");
        assert!(b.on_data(A, &h2, &raw2, 16 * NS_MS).delivered_new);
        assert_eq!(b.pending_graft_count(), 0);
    }

    #[test]
    fn lazy_flush_batches_and_respects_cadence() {
        let mut a = engine(A, &[B, C]);
        a.demote_to_lazy(C); // C pruned to the lazy set
        let (h1, raw1) = frame(A, 1, b"one");
        let (h2, raw2) = frame(A, 2, b"two");
        let _ = a.broadcast(&h1, &raw1, 0);
        let _ = a.broadcast(&h2, &raw2, 1);

        // Before the tick: nothing leaves.
        assert!(a.on_tick(0).is_empty());

        let actions = a.on_tick(10 * NS_MS);
        // Origin-sync announcements: eager B AND demoted C each get one
        // batched IHAVE with both ids.
        let ihaves: Vec<&Action> = actions
            .iter()
            .filter(|a| {
                matches!(
                    a,
                    Action::Control {
                        msg_type: MSG_GOSSIP_IHAVE,
                        ..
                    }
                )
            })
            .collect();
        assert_eq!(ihaves.len(), 2, "origin announces to eager AND lazy peers");
        let mut targets: Vec<u64> = Vec::new();
        for a2 in ihaves {
            match a2 {
                Action::Control { to, payload, .. } => {
                    assert_eq!(parse_ihave_payload(payload).len(), 2);
                    targets.push(*to);
                }
                _ => unreachable!(),
            }
        }
        targets.sort_unstable();
        assert_eq!(targets, vec![B, C]);
        // Second tick: the lazy outbox is drained (no more IHAVEs — the
        // origin tail flush's ForwardRaw pushes are a separate, bounded
        // mechanism with its own test).
        let actions = a.on_tick(20 * NS_MS);
        assert!(!actions.iter().any(|a| matches!(
            a,
            Action::Control {
                msg_type: MSG_GOSSIP_IHAVE,
                ..
            }
        )));
    }

    #[test]
    fn grafts_retry_rotate_and_abandon() {
        let mut b = engine(B, &[A, C]);
        b.schedule_graft(MsgId { sender: A, seq: 9 }, A, 0, 0);
        let retry = 40 * NS_MS;
        let mut t = 5 * NS_MS;

        // First fire targets A.
        let a1 = b.on_tick(t);
        assert_eq!(a1.len(), 1);

        // Rotates to C on the retry (dead-link tolerance, R2).
        t += retry;
        let a2 = b.on_tick(t);
        match &a2[0] {
            Action::Control { to, .. } => assert_eq!(*to, C),
            _ => panic!(),
        }

        // Exhaust max_tries (3) → quarantined, engine goes quiet.
        t += retry;
        let _ = b.on_tick(t);
        t += retry;
        let a4 = b.on_tick(t);
        assert!(a4.is_empty(), "id must be quarantined after max tries");
        assert_eq!(b.stats().abandoned, 1);

        // Quarantine holds: a late IHAVE inside the window cannot re-arm.
        let ihave = build_ihave_payload(&[MsgId { sender: A, seq: 9 }]);
        let _ = b.on_control(A, MSG_GOSSIP_IHAVE, &ihave, t + 1);
        assert_eq!(b.pending_graft_count(), 0);

        // Phase 1 quarantine semantics: after `abandon_rearm` the SAME id
        // becomes healable again — a saturated socket must never turn a
        // transient loss into a permanent hole.
        let rearm = b.params.abandon_rearm.as_nanos() as i64;
        let _ = b.on_control(A, MSG_GOSSIP_IHAVE, &ihave, t + rearm + 1);
        assert_eq!(b.pending_graft_count(), 1, "quarantine expired → re-armed");
    }

    #[test]
    fn neighbor_down_reassigns_pending_grafts() {
        let mut b = engine(B, &[A, C]);
        b.schedule_graft(MsgId { sender: A, seq: 4 }, A, 0, 0);
        b.neighbor_down(A);
        let actions = b.on_tick(5 * NS_MS);
        match &actions[0] {
            Action::Control { to, .. } => assert_eq!(*to, C),
            _ => panic!(),
        }
    }

    #[test]
    fn payload_cache_serves_manifest_sized_frames() {
        let mut a = engine(A, &[B]);
        let big: Vec<u8> = (0..4096u32).map(|i| (i % 256) as u8).collect();
        let (h, raw) = frame(A, 1, &big);
        let _ = a.broadcast(&h, &raw, 0);
        let graft = build_graft_payload(&MsgId { sender: A, seq: 1 });
        let actions = a.on_control(B, MSG_GOSSIP_GRAFT, &graft, 1);
        assert_eq!(actions.len(), 1);
    }

    #[test]
    fn ihave_wire_codec_roundtrip() {
        let ids = vec![
            MsgId {
                sender: 0x0102_0304_0506_0708,
                seq: 42,
            },
            MsgId {
                sender: u64::MAX,
                seq: u32::MAX,
            },
        ];
        let p = build_ihave_payload(&ids);
        assert_eq!(p.len(), 2 + 24);
        assert_eq!(parse_ihave_payload(&p), ids);
        // Truncated / junk payloads degrade to empty, never panic.
        assert!(parse_ihave_payload(&[]).is_empty());
        assert!(parse_ihave_payload(&[0xFF, 0xFF]).is_empty());
        assert!(parse_ihave_payload(&[0x02, 0x00, 0x01]).is_empty());
    }

    #[test]
    fn ihave_graft_parsers_never_panic_on_hostile_bytes() {
        // Audit #22 item 6 regression lock: every truncation and every
        // length-lying header of a valid batch must degrade to dropped
        // records — the recv path must never panic.
        let ids: Vec<MsgId> = (0..5)
            .map(|i| MsgId {
                sender: 0x1000 + i,
                seq: i as u32 * 7,
            })
            .collect();
        let payload = build_graft_batch_payload(&ids);
        for end in 0..=payload.len() {
            let truncated = &payload[..end];
            let _ = parse_graft_payload_multi(truncated);
            let _ = parse_ihave_payload(truncated);
        }
        // Header claims 65535 records the body does not carry.
        let mut lying = payload.clone();
        lying[0] = 0xFF;
        lying[1] = 0xFF;
        assert!(parse_graft_payload_multi(&lying).is_empty());
        // Every single-byte corruption of a valid single-id GRAFT.
        let one = build_graft_payload(&MsgId { sender: 42, seq: 7 });
        for i in 0..one.len() {
            let mut corrupted = one.clone();
            corrupted[i] ^= 0xFF;
            let _ = parse_graft_payload(&corrupted);
        }
        // Degenerate frames.
        assert!(parse_graft_payload_multi(&[]).is_empty());
        assert!(parse_ihave_payload(&[0x01]).is_empty());
        // Sanity: the valid batch still round-trips.
        assert_eq!(parse_graft_payload_multi(&payload), ids);
    }
}
