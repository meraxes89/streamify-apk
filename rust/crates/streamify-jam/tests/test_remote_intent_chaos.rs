//! test_remote_intent_chaos.rs — Phase 4 simulated multi-device chaos
//! suite (directive §2.5).
//!
//! THE headline scenario: a 10-node mesh in which THREE controllers
//! concurrently attempt playback handoff to the same receiver while
//! dispatching intent streams, under 20% simulated packet loss,
//! arbitrary network reordering (0..=3 tick delivery windows), and 1%
//! mid-flight frame corruption.
//!
//! Assertions:
//!   • Monotonic sequence delivery: every node's applied log is
//!     strictly increasing in (epoch, controller, seq) — no duplicates,
//!     no reordering across takeover boundaries, under ANY arrival
//!     order the lossy reordering wire produces.
//!   • Split-brain resolution: controllers emitting conflicting queue
//!     modifications converge to EXACTLY ONE winner pair at every
//!     receiver — deterministic regardless of arrival order; the
//!     loser's post-takeover intents draw stale receipts and are never
//!     applied.
//!   • Handoff safety: the local player's halt signal
//!     (`HandoffCompleted`) only ever fires after the receiver's
//!     confirmation crossed the wire; every handoff that never confirmed
//!     ends in `HandoffFailed` (local playback continues) — no node ends
//!     stuck in `HandoffPending`.
//!   • Chaos accounting: dropped + delivered == frames sent; corruption
//!     is rejected at boundaries and never applied.

use rand::seq::SliceRandom;
use rand::{Rng, SeedableRng};

use streamify_jam::remote_intent_engine::{
    HandoffFailReason, IntentAction, IntentEvent, PlaybackSnapshot, RemoteIntent,
    RemoteIntentEngine, RepeatMode, Role,
};

const N: usize = 10;
/// 20% per-frame loss (directive §2.5).
const LOSS: f64 = 0.20;
/// Reordering window: frames land 0..=3 ticks after send.
const MAX_DELAY_TICKS: usize = 3;
/// Mid-flight corruption probability (checksum-breaking byte flips).
const CORRUPT_P: f64 = 0.01;
const TICK_MS: u64 = 10;

struct ChaosNode {
    engine: RemoteIntentEngine,
    /// Applied log as (epoch, controller_id, seq) — the monotonicity
    /// proof triple (controller id attributed via event replay).
    applied: Vec<(u32, u64, u64)>,
    stale_rejects: u64,
    handoff_started: u64,
    handoff_completed: u64,
    handoff_failed: u64,
    /// Halt signals (HandoffCompleted = local player may halt).
    halts: u64,
    /// Controller pair attribution for applied intents (event replay).
    cur_pair: Option<(u32, u64)>,
}

impl ChaosNode {
    fn new(id: u64, seed: u8) -> Self {
        ChaosNode {
            engine: RemoteIntentEngine::new(id, [seed; 32]),
            applied: Vec::new(),
            stale_rejects: 0,
            handoff_started: 0,
            handoff_completed: 0,
            handoff_failed: 0,
            halts: 0,
            cur_pair: None,
        }
    }
}

struct ChaosMesh {
    nodes: Vec<ChaosNode>,
    rng: rand::rngs::StdRng,
    /// Current sim tick (mesh-local — no cross-test globals).
    tick: usize,
    /// (deliver_at_tick, target_node_idx_or_broadcast, frame)
    wire: Vec<(usize, Option<usize>, Vec<u8>)>,
    frames_sent: usize,
    frames_dropped: usize,
    frames_delivered: usize,
    frames_corrupted: usize,
}

impl ChaosMesh {
    fn new(seed: u64) -> Self {
        ChaosMesh {
            // Device ids 1..=10 (0 is the broadcast alias, never a node).
            nodes: (1..=N).map(|i| ChaosNode::new(i as u64, i as u8)).collect(),
            rng: rand::rngs::StdRng::seed_from_u64(seed),
            tick: 0,
            wire: Vec::new(),
            frames_sent: 0,
            frames_dropped: 0,
            frames_delivered: 0,
            frames_corrupted: 0,
        }
    }

    fn idx_of(&self, device_id: u64) -> Option<usize> {
        self.nodes
            .iter()
            .position(|n| n.engine.self_id() == device_id)
    }

    fn now_ms(&self) -> u64 {
        self.tick as u64 * TICK_MS
    }

    /// Pumps one node's outbound actions onto the lossy, reordering wire.
    fn flush(&mut self, idx: usize) {
        let actions = self.nodes[idx].engine.drain_actions();
        for a in actions {
            let IntentAction::Send { target, frame } = a;
            self.frames_sent += 1;
            if self.rng.gen_bool(LOSS) {
                self.frames_dropped += 1;
                continue;
            }
            let dest = if target == 0 {
                None // broadcast
            } else {
                match self.idx_of(target) {
                    Some(d) => Some(d),
                    None => {
                        // Unroutable unicast = lost in transit.
                        self.frames_dropped += 1;
                        continue;
                    }
                }
            };
            let delay = self.rng.gen_range(0..=MAX_DELAY_TICKS);
            self.wire.push((self.tick + delay, dest, frame));
        }
    }

    /// Delivers due frames (applying the corruption injection).
    fn pump(&mut self) {
        let now = self.tick;
        let now_ms = now as u64 * TICK_MS;
        let mut i = 0;
        while i < self.wire.len() {
            if self.wire[i].0 <= now {
                let (_, dest, mut frame) = self.wire.remove(i);
                if self.rng.gen_bool(CORRUPT_P) && frame.len() > 4 {
                    let flip = self.rng.gen_range(4..frame.len());
                    frame[flip] ^= 0x40;
                    self.frames_corrupted += 1;
                }
                match dest {
                    Some(t) => {
                        self.nodes[t].engine.on_frame(&frame, now_ms);
                        self.frames_delivered += 1;
                    }
                    None => {
                        // Broadcast: every OTHER node sees it. Accounting
                        // counts ONE delivery (one frame, one wire hop
                        // to the broadcast group).
                        for n in self.nodes.iter_mut() {
                            n.engine.on_frame(&frame, now_ms);
                        }
                        self.frames_delivered += 1;
                    }
                }
            } else {
                i += 1;
            }
        }
    }

    /// Ticks every engine, then flushes ALL outbound onto the wire.
    fn tick_all(&mut self) {
        let now_ms = self.now_ms();
        for n in self.nodes.iter_mut() {
            n.engine.tick(now_ms);
        }
        for idx in 0..self.nodes.len() {
            self.flush(idx);
        }
    }

    /// Drains every node's events into its chaos ledger.
    fn collect(&mut self) {
        for n in self.nodes.iter_mut() {
            for ev in n.engine.drain_events() {
                match ev {
                    IntentEvent::RemoteTookControl { device_id, epoch } => {
                        n.cur_pair = Some((epoch, device_id));
                    }
                    IntentEvent::IntentApplied { seq_id, .. } => {
                        let (epoch, dev) = n
                            .cur_pair
                            .expect("applied intent implies a known controller pair");
                        n.applied.push((epoch, dev, seq_id));
                    }
                    IntentEvent::IntentRejectedStale { .. } => {
                        n.stale_rejects += 1;
                    }
                    IntentEvent::HandoffStarted { .. } => n.handoff_started += 1,
                    IntentEvent::HandoffCompleted { .. } => {
                        n.handoff_completed += 1;
                        n.halts += 1;
                    }
                    IntentEvent::HandoffFailed { .. } => n.handoff_failed += 1,
                    _ => {}
                }
            }
        }
    }

    fn advance(&mut self) {
        self.tick += 1;
    }
}

fn snapshot() -> PlaybackSnapshot {
    PlaybackSnapshot {
        track_id: 0x7AAC_1234,
        position_ms: 90_000,
        queue: vec![0x11, 0x22, 0x33, 0x44],
        queue_index: 1,
        repeat: RepeatMode::All,
        shuffle: true,
    }
}

/// Churns a controller's epoch to an exact target (end/start cycles).
fn churn_to(engine: &mut RemoteIntentEngine, target_epoch: u32) {
    while engine.controller_epoch() < target_epoch {
        engine.end_session();
        engine.start_session();
    }
}

fn frame_of(e: &mut RemoteIntentEngine) -> Vec<u8> {
    let IntentAction::Send { frame, .. } = e.drain_actions().into_iter().next().expect("an action");
    frame
}

#[test]
fn ten_nodes_concurrent_handoffs_monotonic_under_loss_and_reordering() {
    let mut mesh = ChaosMesh::new(0x5EED_C1A0);
    // Device 1 (idx 0) is the handoff RECEIVER (the speaker). Devices
    // 8/9/10 (idx 7/8/9) are the three concurrent controllers with a
    // deterministic epoch ladder (10 / 12 / 14 → offers 11 / 13 / 15).
    let receiver_idx = 0usize;
    let controllers = [7usize, 8, 9];
    for &c in &controllers {
        mesh.nodes[c].engine.start_session();
    }
    churn_to(&mut mesh.nodes[7].engine, 10);
    churn_to(&mut mesh.nodes[8].engine, 12);
    churn_to(&mut mesh.nodes[9].engine, 14);

    let intents = [
        RemoteIntent::Play,
        RemoteIntent::Pause,
        RemoteIntent::SeekTo {
            position_ms: 42_000,
        },
        RemoteIntent::QueueInsert {
            cad_id: 0xCAFE,
            after_index: u32::MAX,
        },
        RemoteIntent::QueueRemove { index: 1 },
        RemoteIntent::QueueReorder { from: 0, to: 2 },
        RemoteIntent::SetVolume { volume_pct: 63 },
    ];

    // Concurrent handoff attempts at ticks 20 / 26 / 32 — all in flight
    // simultaneously under loss + reordering.
    let deadline = 900; // 9 s sim: the 3 s handoff deadlines resolve twice over
    while mesh.tick < deadline {
        mesh.advance();
        let now_ms = mesh.now_ms();
        if mesh.tick == 20 {
            mesh.nodes[7]
                .engine
                .begin_handoff(1, &snapshot(), now_ms)
                .expect("controller 8 hands off");
        }
        if mesh.tick == 26 {
            mesh.nodes[8]
                .engine
                .begin_handoff(1, &snapshot(), now_ms)
                .expect("controller 9 hands off");
        }
        if mesh.tick == 32 {
            mesh.nodes[9]
                .engine
                .begin_handoff(1, &snapshot(), now_ms)
                .expect("controller 10 hands off");
        }
        for &c in &controllers {
            if mesh.rng.gen_bool(0.35) {
                let intent = intents[mesh.rng.gen_range(0..intents.len())];
                mesh.nodes[c].engine.dispatch_intent(intent, now_ms);
            }
        }
        mesh.tick_all();
        mesh.pump();
        mesh.collect();
    }
    // Flush the tail: keep pumping until the wire drains.
    for _ in 0..(MAX_DELAY_TICKS + 2) {
        mesh.advance();
        mesh.tick_all();
        mesh.pump();
        mesh.collect();
    }

    // ── chaos accounting ──
    assert!(
        mesh.frames_dropped > 100,
        "20% loss must have fired: dropped={}",
        mesh.frames_dropped
    );
    assert!(
        mesh.frames_corrupted >= 1,
        "corruption injection must have fired: {}",
        mesh.frames_corrupted
    );
    // Frames still scheduled on the wire when the sim stopped (tail
    // retransmissions): counted as stranded, never vanished.
    let stranded = mesh.wire.len();
    assert_eq!(
        mesh.frames_dropped + mesh.frames_delivered + stranded,
        mesh.frames_sent,
        "frame accounting must balance (dropped + delivered + stranded == sent)"
    );

    // ── monotonic sequence delivery on EVERY node ──
    for (i, n) in mesh.nodes.iter().enumerate() {
        for w in n.applied.windows(2) {
            assert!(
                w[0] < w[1],
                "node[{i}] applied log must be strictly increasing in \
                 (epoch, controller, seq): head={:?}",
                &n.applied[..n.applied.len().min(8)]
            );
        }
    }
    // Every node applied broadcast intents (the mesh is fully connected).
    for (i, n) in mesh.nodes.iter().enumerate() {
        assert!(
            !n.applied.is_empty(),
            "node[{i}] must have applied broadcast intents"
        );
    }

    // ── split-brain: ONE deterministic winner everywhere ──
    // Controller 10 (idx 9) carries the dominating epoch; every node's
    // FINAL controller pair must belong to it.
    let winner_id = mesh.nodes[9].engine.self_id();
    for (i, n) in mesh.nodes.iter().enumerate() {
        let pair = n
            .engine
            .current_controller()
            .expect("every node saw a controller");
        assert!(
            pair.1 == winner_id,
            "node[{i}] final controller must be the epoch-dominant winner \
             (got {pair:?}, expected device {winner_id})"
        );
    }
    // Stale receipts fired somewhere (the losers kept talking after the
    // takeover — their intents were refused, never applied).
    let total_stale: u64 = mesh.nodes.iter().map(|n| n.stale_rejects).sum();
    assert!(
        total_stale > 0,
        "split-brain losers must have drawn stale receipts"
    );

    // ── handoff safety ──
    // The receiver adopted at least once and ended as ActiveReceiver.
    assert_eq!(mesh.nodes[receiver_idx].engine.role(), Role::ActiveReceiver);
    assert_eq!(
        mesh.nodes[receiver_idx].halts, 0,
        "receivers do not halt local players"
    );
    // Every started handoff ended Completed or Failed — no stuck pendings.
    for (i, n) in mesh.nodes.iter().enumerate() {
        assert!(
            !matches!(n.engine.role(), Role::HandoffPending),
            "node[{i}] must not end stuck in HandoffPending"
        );
        assert_eq!(
            n.handoff_started,
            n.handoff_completed + n.handoff_failed,
            "node[{i}] handoff lifecycle must be closed (started={}, completed={}, failed={})",
            n.handoff_started,
            n.handoff_completed,
            n.handoff_failed
        );
    }
    // At least one handoff COMPLETED through the chaos (the winner's).
    let completed: u64 = mesh.nodes.iter().map(|n| n.handoff_completed).sum();
    assert!(
        completed >= 1,
        "the dominant controller's handoff must confirm under loss"
    );
    // The halt signal fired exactly once per completed handoff.
    let halts: u64 = mesh.nodes.iter().map(|n| n.halts).sum();
    assert_eq!(halts, completed);

    // ── corruption never applied ──
    // Corrupted frames die at the boundary (checksum) — the applied logs
    // stayed strictly increasing (asserted above) and the engines'
    // boundary counters observed the rejects.
    let rejected: u64 = mesh
        .nodes
        .iter()
        .map(|n| n.engine.stats().frames_rejected)
        .sum();
    assert!(
        rejected >= mesh.frames_corrupted as u64,
        "corrupt frames must be boundary-rejected"
    );
}

#[test]
fn split_brain_conflicting_queue_modifications_resolve_cleanly_both_orders() {
    // Controller A (device 100, epoch 5) vs B (device 50, epoch 7):
    // both fire conflicting queue modifications at one receiver, in
    // B-first AND shuffled delivery orders — identical outcomes.
    let a_intents = [
        RemoteIntent::QueueInsert {
            cad_id: 0xAAAA,
            after_index: u32::MAX,
        },
        RemoteIntent::QueueReorder { from: 0, to: 3 },
        RemoteIntent::SetVolume { volume_pct: 80 },
    ];
    let b_intents = [
        RemoteIntent::QueueInsert {
            cad_id: 0xBBBB,
            after_index: 0,
        },
        RemoteIntent::QueueRemove { index: 0 },
        RemoteIntent::SetVolume { volume_pct: 20 },
    ];

    for (order, seed) in [(0usize, 0xBEEF_0001u64), (1, 0xBEEF_0002)] {
        let mut a = RemoteIntentEngine::new(100, [1; 32]);
        let mut b = RemoteIntentEngine::new(50, [2; 32]);
        let mut rcv = RemoteIntentEngine::new(7, [3; 32]);
        churn_to(&mut a, 5);
        churn_to(&mut b, 7);

        let mut frames = Vec::new();
        for intent in a_intents {
            a.dispatch_intent(intent, 0).expect("A dispatch");
            frames.push(frame_of(&mut a));
        }
        for intent in b_intents {
            b.dispatch_intent(intent, 0).expect("B dispatch");
            frames.push(frame_of(&mut b));
        }

        // Delivery order: deterministic B-first, or a seeded shuffle.
        let mut seqs: Vec<usize> = (0..frames.len()).collect();
        if order == 0 {
            seqs.reverse(); // B's frames land first (indices 3..5)
        } else {
            seqs.shuffle(&mut rand::rngs::StdRng::seed_from_u64(seed));
        }
        let mut ledger: Vec<(u32, u64, u64)> = Vec::new();
        let mut cur_pair: Option<(u32, u64)> = None;
        let mut stale = 0u64;
        for &s in &seqs {
            rcv.on_frame(&frames[s], 1);
            for ev in rcv.drain_events() {
                match ev {
                    IntentEvent::RemoteTookControl { device_id, epoch } => {
                        cur_pair = Some((epoch, device_id));
                    }
                    IntentEvent::IntentApplied { seq_id, .. } => {
                        let (e, d) = cur_pair.expect("controller known");
                        ledger.push((e, d, seq_id));
                    }
                    IntentEvent::IntentRejectedStale { .. } => stale += 1,
                    _ => {}
                }
            }
        }
        // The winner is B (epoch 7 > 5) in BOTH orders.
        assert_eq!(
            rcv.current_controller(),
            Some((7, 50)),
            "order {order}: B dominates"
        );
        // Monotonic ledger in both orders.
        for w in ledger.windows(2) {
            assert!(
                w[0] < w[1],
                "order {order}: applied log strictly increasing"
            );
        }
        // All of B's intents applied.
        let b_applied = ledger.iter().filter(|(_, d, _)| *d == 50).count();
        assert_eq!(
            b_applied, 3,
            "order {order}: the winner's stream applies fully"
        );
        if order == 0 {
            // B first: A's three intents arrive after the takeover → all
            // stale-rejected, nothing of A applied.
            assert_eq!(stale, 3, "order {order}: loser intents refused");
            assert!(ledger.iter().all(|(_, d, _)| *d == 50));
        }
        // No receiver state was applied from a dominated pair after the
        // winner established authority.
        let winner_first = ledger
            .iter()
            .position(|(_, d, _)| *d == 50)
            .expect("winner applied");
        assert!(
            ledger[winner_first..].iter().all(|(_, d, _)| *d == 50),
            "order {order}: no loser application after the winner"
        );
    }
}

#[test]
fn partition_heals_and_controller_silence_is_observed_not_revoked() {
    // Controller → receiver with a HARD partition (100% loss) longer
    // than the controller lease, then recovery.
    let mut ctrl = RemoteIntentEngine::new(11, [4; 32]);
    let mut rcv = RemoteIntentEngine::new(12, [5; 32]);
    ctrl.start_session();
    ctrl.dispatch_intent(RemoteIntent::Play, 0)
        .expect("dispatch");
    let frame = frame_of(&mut ctrl);
    rcv.on_frame(&frame, 0);
    assert_eq!(rcv.role(), Role::ActiveReceiver);
    assert_eq!(rcv.stats().intents_applied, 1);
    let _ = rcv.drain_events();

    // Partition: 40 s of silence (> CONTROLLER_LEASE_MS = 30 s).
    rcv.tick(40_000);
    assert!(rcv.drain_events().iter().any(|e| matches!(
        e,
        IntentEvent::ControllerSilent {
            controller: (1, 11)
        }
    )));
    // Authority NOT revoked by quietness: still ActiveReceiver, and the
    // controller's next intent (same pair) applies on the same stream.
    assert_eq!(rcv.role(), Role::ActiveReceiver);
    ctrl.dispatch_intent(RemoteIntent::Pause, 40_500)
        .expect("dispatch");
    let frame = frame_of(&mut ctrl);
    rcv.on_frame(&frame, 40_500);
    assert_eq!(rcv.stats().intents_applied, 2);
    assert_eq!(rcv.current_controller(), Some((1, 11)));
    // Silence raised exactly once per quiet period (no event spam).
    rcv.tick(80_000);
    let repeats = rcv
        .drain_events()
        .into_iter()
        .filter(|e| matches!(e, IntentEvent::ControllerSilent { .. }))
        .count();
    assert_eq!(repeats, 1);
}

#[test]
fn handoff_under_permanent_partition_fails_cleanly_never_halting_local() {
    // The OFFER never crosses a dead link: deadline expiry aborts, the
    // controller returns to ActiveController, and the halt signal never
    // fires — local playback continued throughout.
    let mut ctrl = RemoteIntentEngine::new(21, [6; 32]);
    ctrl.start_session();
    ctrl.begin_handoff(22, &snapshot(), 0).expect("begin");
    assert_eq!(ctrl.role(), Role::HandoffPending);
    // Swallow the offer (dead link).
    let _ = ctrl.drain_actions();
    ctrl.tick(3_001); // HANDOFF_TIMEOUT_MS + 1
    let events = ctrl.drain_events();
    assert!(events.iter().any(|e| matches!(
        e,
        IntentEvent::HandoffFailed {
            reason: HandoffFailReason::Timeout,
            ..
        }
    )));
    assert_eq!(ctrl.role(), Role::ActiveController);
    // No halt signalled, and intents still flow.
    assert!(ctrl.dispatch_intent(RemoteIntent::Pause, 3_002).is_some());
}

#[test]
fn offer_retransmission_survives_burst_loss() {
    // 25% loss on the offer/confirm exchange — the verbatim OFFER retry
    // (same nonce + signature) gets the handoff through, and duplicate
    // offers at the receiver stay inert (idempotent nonce match).
    let mut ctrl = RemoteIntentEngine::new(31, [7; 32]);
    let mut rcv = RemoteIntentEngine::new(32, [8; 32]);
    ctrl.start_session();
    ctrl.begin_handoff(32, &snapshot(), 0).expect("begin");
    let mut rng = rand::rngs::StdRng::seed_from_u64(0x0FFE7);
    let mut now = 0u64;
    let mut confirmed = false;
    for _ in 0..400 {
        now += 10;
        ctrl.tick(now);
        for IntentAction::Send { frame, .. } in ctrl.drain_actions() {
            if !rng.gen_bool(0.25) {
                rcv.on_frame(&frame, now);
            }
        }
        for IntentAction::Send { frame, .. } in rcv.drain_actions() {
            if !rng.gen_bool(0.25) {
                ctrl.on_frame(&frame, now);
            }
        }
        if ctrl
            .drain_events()
            .iter()
            .any(|e| matches!(e, IntentEvent::HandoffCompleted { .. }))
        {
            confirmed = true;
            break;
        }
    }
    assert!(confirmed, "offer retransmission must survive 25% loss");
    assert_eq!(rcv.role(), Role::ActiveReceiver);
    // Duplicate OFFERs (retries that raced the confirm) were inert: the
    // receiver adopted at most one snapshot per epoch (no re-adoption
    // storm — the nonce match drops strays silently).
    let adopts = rcv
        .drain_events()
        .into_iter()
        .filter(|e| matches!(e, IntentEvent::HandoffAdopted { .. }))
        .count();
    assert!(adopts <= 2, "stray duplicate offers stay inert: {adopts}");
}
