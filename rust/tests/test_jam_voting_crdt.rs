//! test_jam_voting_crdt.rs — Phase 2 directive A integration suite
//! (BEHIND.md gap #37): the democratic group queue voting CRDT.
//!
//! Coverage map:
//!   • Pure CRDT: concurrent multi-user voting with retractions and
//!     split-brain merges (replicas applying the same op stream in
//!     different orders must reach byte-identical folds); promotion
//!     invariants under the ⌊N/2⌋+1 threshold and the host ratio
//!     override; vote-before-add tolerance.
//!   • Wire boundary (live mesh): signed vote convergence across
//!     replicas; forged / tampered / unsigned votes die at the boundary;
//!     the 10/sec per-peer vote flood limiter; queue ops mirrored into
//!     every mesh CRDT replica.
//!   • 32-peer democratic voting chaos: 32 nodes on loopback UDP under
//!     15% packet loss with 10–80 ms latency jitter, 16 shared tracks,
//!     and a 17-voter promotion run — every replica must converge on the
//!     identical vote ledger AND the identical committed (active
//!     playback) queue.
//!   • Friend activity (directive D / gaps #31, #32): frame codec
//!     hostility, sender throttle, receiver gap guard, TTL registry.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use rand::Rng;
use streamify_core_rs::gossip::GossipParams;
use streamify_core_rs::jam_crdt::{
    FullSnapshot, JamCrdtState, JamOp, OpType, PromotionPolicy, PromotionStatus, VoteOutcome,
    VoterId, VOTE_FLAG_UP,
};
use streamify_core_rs::jam_governor::{RejectReason, RoomGovernor};
use streamify_core_rs::p2p_mesh::{
    build_friend_activity_frame, parse_friend_activity, LinkCondition, MeshConfig, MeshNode,
    MeshError, MSG_CRDT_OP, MSG_VOTE_OP,
};

/// Timing-sensitive mesh sims: serialize within this binary (same
/// discipline as the Phase 1 harnesses).
static RUN_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn seed_for(tag: &str) -> [u8; 32] {
    let h = blake3::hash(tag.as_bytes());
    let mut s = [0u8; 32];
    s.copy_from_slice(h.as_bytes());
    s
}

fn voter_of(seed_byte: u8) -> VoterId {
    let mut pk = [0u8; 32];
    pk[0] = seed_byte;
    pk[31] = 0xC7;
    VoterId::from_pubkey(&pk)
}

fn vote_op(op_id: u64, voter_seed: u8, up: bool, target: u64) -> JamOp {
    JamOp::new(
        op_id,
        [voter_seed; 4],
        OpType::Vote,
        if up { VOTE_FLAG_UP } else { 0 },
        0,
        0.0,
        target,
    )
}

fn add_op(op_id: u64, cad: u64, frac: f64) -> JamOp {
    JamOp::new(op_id, [0xAA; 4], OpType::Add, 0, cad, frac, 0)
}

// ═══════════════════════════════════════════════════ pure CRDT scenario

/// 32 voters, 24 tracks, a randomized vote/retract storm — three replicas
/// apply the SAME op stream in three different orders (forward, reversed,
/// shuffled) and must land on byte-identical full folds. Promotion
/// invariants are asserted against the FINAL merged state only (the
/// committed set is a pure function of merged state by construction).
#[test]
fn concurrent_multi_user_voting_split_brain_convergence() {
    let n_voters = 32u32;
    let threshold_policy = PromotionPolicy {
        member_count: n_voters,
        ratio: None,
        min_votes: 1,
    };

    let mut rng = rand::thread_rng();
    // 24 tracks at stable fractions.
    let adds: Vec<JamOp> = (0..24u64)
        .map(|i| add_op(10_000 + i, 0xF00D_0000 + i, (i as f64 + 1.0) / 25.0))
        .collect();

    // Vote storm: every voter touches several tracks; some retractions.
    let mut ops: Vec<JamOp> = Vec::new();
    let mut op_id = 50_000u64;
    for voter in 1..=n_voters as u8 {
        let touched: Vec<u64> = (0..6).map(|_| rng.gen_range(0..24)).collect();
        for t in touched {
            let up = rng.gen_bool(0.85);
            op_id += 1;
            ops.push(vote_op(op_id, voter, up, adds[t as usize].op_id));
            // ~20% of upvotes get retracted later by the same voter.
            if up && rng.gen_bool(0.2) {
                op_id += 1;
                ops.push(vote_op(op_id, voter, false, adds[t as usize].op_id));
            }
        }
    }

    // Deterministic per-voter final intents (the LWW ground truth).
    let mut final_vote: HashMap<(u8, u64), bool> = HashMap::new();
    let mut final_op: HashMap<(u8, u64), u64> = HashMap::new();
    for op in &ops {
        let k = (op.sender_nonce[0], op.target_add_op_id);
        let prev = final_op.get(&k).copied().unwrap_or(0);
        if op.op_id >= prev {
            final_op.insert(k, op.op_id);
            final_vote.insert(k, op.policy_flags & VOTE_FLAG_UP != 0);
        }
    }

    let mut replicas: Vec<JamCrdtState> = Vec::new();
    for _ in 0..3 {
        let mut st = JamCrdtState::new();
        st.set_promotion_policy(threshold_policy);
        replicas.push(st);
    }

    // Replica 0: adds then votes (forward).
    for op in &adds {
        assert!(replicas[0].apply_op(op));
    }
    for op in &ops {
        replicas[0].apply_op_as(op, &voter_of(op.sender_nonce[0]));
    }
    // Replica 1: votes first (out-of-order, before the adds!), then adds.
    for op in ops.iter().rev() {
        replicas[1].apply_op_as(op, &voter_of(op.sender_nonce[0]));
    }
    for op in adds.iter().rev() {
        assert!(replicas[1].apply_op(op));
    }
    // Replica 2: everything shuffled together.
    let mut all: Vec<&JamOp> = adds.iter().chain(ops.iter()).collect();
    let mut rng2 = rand::thread_rng();
    for i in (1..all.len()).rev() {
        let j = rng2.gen_range(0..=i);
        all.swap(i, j);
    }
    for op in all {
        if op.op_type == 4 {
            replicas[2].apply_op_as(op, &voter_of(op.sender_nonce[0]));
        } else {
            assert!(replicas[2].apply_op(op));
        }
    }

    // ── Split-brain convergence: byte-identical full folds. ──
    let folds: Vec<FullSnapshot> = replicas.iter().map(|r| r.fold_full()).collect();
    assert_eq!(folds[0], folds[1], "forward vs reversed op order");
    assert_eq!(folds[0], folds[2], "forward vs shuffled op order");

    // ── Ground truth check: net counts match the LWW resolution. ──
    let threshold = threshold_policy.effective_threshold();
    for t in &adds {
        let expected = final_vote
            .iter()
            .filter(|((_, target), &up)| *target == t.op_id && up)
            .count() as u32;
        let got = replicas[0].vote_count(t.op_id);
        assert_eq!(got, expected, "net votes for track {}", t.op_id);
        let status = replicas[0].promotion_status(t.op_id);
        match status {
            PromotionStatus::Committed { votes } => {
                assert!(votes >= threshold, "committed implies threshold met");
            }
            PromotionStatus::Pending { votes, threshold: th } => {
                assert_eq!(th, threshold);
                assert!(votes < threshold, "pending implies below threshold");
            }
        }
    }

    // ── Fold → hydrate round-trip is lossless. ──
    let mut hydrated = JamCrdtState::new();
    hydrated.set_promotion_policy(threshold_policy);
    hydrated.load_full(folds[0].clone());
    assert_eq!(hydrated.fold_full(), folds[0]);
    assert_eq!(hydrated.committed_queue(), replicas[0].committed_queue());
    assert_eq!(hydrated.playback_order(), replicas[0].playback_order());
}

/// Idempotent replays are inert: re-applying the entire storm changes
/// nothing (Duplicate / Superseded outcomes dominate).
#[test]
fn vote_replays_are_idempotent() {
    let mut st = JamCrdtState::new();
    st.set_promotion_policy(PromotionPolicy { member_count: 3, ratio: None, min_votes: 1 });
    let t = add_op(77_001, 42, 0.5);
    assert!(st.apply_op(&t));

    let v1 = vote_op(88_001, 1, true, t.op_id);
    let v2 = vote_op(88_002, 2, true, t.op_id);
    assert_eq!(
        st.apply_op_as(&v1, &voter_of(1)),
        Some(VoteOutcome::Recorded)
    );
    assert_eq!(
        st.apply_op_as(&v2, &voter_of(2)),
        Some(VoteOutcome::Recorded)
    );
    let before = st.fold_full();

    // Full replay of both ops.
    assert_eq!(st.apply_op_as(&v1, &voter_of(1)), Some(VoteOutcome::Duplicate));
    assert_eq!(st.apply_op_as(&v2, &voter_of(2)), Some(VoteOutcome::Duplicate));
    // Stale (older op_id after the standing vote).
    let stale = vote_op(88_000, 2, false, t.op_id);
    assert_eq!(
        st.apply_op_as(&stale, &voter_of(2)),
        Some(VoteOutcome::Superseded)
    );
    assert_eq!(st.fold_full(), before, "replays and stale frames are inert");
    assert_eq!(st.vote_count(t.op_id), 2);
}

// ══════════════════════════════════════════════ vote wire-frame boundary

/// The signed vote frame survives round-trips and dies on every hostile
/// mutation at the governor's boundary — same discipline as the Phase 1
/// intent-frame test.
#[test]
fn vote_frame_forgery_matrix() {
    let mut caster = RoomGovernor::from_seed(seed_for("vote-caster"));
    caster.declare_host();
    let op = vote_op(99_001, 1, true, 55_005);
    let frame = caster.build_vote(&op);

    // Valid frame verifies (and reveals the voter pubkey).
    let verified = caster.verify_vote(&frame, 1_000_000).expect("own frame verifies");
    assert_eq!(verified.voter_pubkey, caster.pubkey());
    assert_eq!(verified.op.target_add_op_id, 55_005);
    assert_eq!(verified.op.policy_flags & VOTE_FLAG_UP, VOTE_FLAG_UP);

    // Tampered op bytes INSIDE the JamOp checksum span → the sealed op
    // itself fails to parse (op-level integrity) → Malformed.
    let mut forged = frame.clone();
    forged[40] ^= 0xFF; // JamOp checksum field
    assert_eq!(
        caster.verify_vote(&forged, 2_000_000).err(),
        Some(RejectReason::Malformed)
    );
    // Tampered op payload byte (target id span [32..40)) — the op
    // checksum breaks first, same clean rejection.
    let mut forged2 = frame.clone();
    forged2[32] ^= 0x01;
    assert_eq!(
        caster.verify_vote(&forged2, 2_000_000).err(),
        Some(RejectReason::Malformed)
    );
    // Tampered voter pubkey: a flipped bit either breaks the curve-point
    // encoding (Malformed) or the Ed25519 verify (BadSignature) — both are
    // clean boundary rejections; the frame dies either way.
    let mut forged_pk = frame.clone();
    forged_pk[48] ^= 0x01;
    assert!(matches!(
        caster.verify_vote(&forged_pk, 3_000_000).err(),
        Some(RejectReason::BadSignature) | Some(RejectReason::Malformed)
    ));
    // Tampered SIGNATURE byte: pubkey stays a valid curve point, the
    // Ed25519 verify deterministically fails.
    let mut forged_sig = frame.clone();
    forged_sig[100] ^= 0xFF;
    assert_eq!(
        caster.verify_vote(&forged_sig, 3_500_000).err(),
        Some(RejectReason::BadSignature)
    );
    // Tampered epoch (inside signature span).
    let mut forged_epoch = frame.clone();
    forged_epoch[80] ^= 0x01;
    assert_eq!(
        caster.verify_vote(&forged_epoch, 4_000_000).err(),
        Some(RejectReason::BadSignature)
    );
    // Truncated / extended lengths.
    assert_eq!(
        caster.verify_vote(&frame[..100], 5_000_000).err(),
        Some(RejectReason::Malformed)
    );
    let mut extended = frame.clone();
    extended.push(0);
    assert_eq!(
        caster.verify_vote(&extended, 6_000_000).err(),
        Some(RejectReason::Malformed)
    );

    // Stale epoch: a properly-signed vote from a PRE-migration epoch is
    // rejected by the POST-migration authority (epoch fence).
    let mut old = RoomGovernor::from_seed(seed_for("vote-old"));
    old.declare_host();
    assert_eq!(old.epoch(), 1);
    let stale_frame = old.build_vote(&op);
    // Advance the receiver's epoch past the frame's epoch.
    let mut receiver = RoomGovernor::from_seed(seed_for("vote-recv"));
    receiver.declare_host();
    let members: Vec<[u8; 32]> = vec![receiver.pubkey(), old.pubkey()];
    let _ = receiver.elect_on_host_death(&members);
    assert!(receiver.epoch() > 1, "election advanced the epoch");
    assert_eq!(
        receiver.verify_vote(&stale_frame, 7_000_000).err(),
        Some(RejectReason::EpochFence)
    );

    // Blacklisted voter: a VALID signature from a banned pubkey dies.
    let mut banned = RoomGovernor::from_seed(seed_for("vote-banned"));
    banned.declare_host();
    let mut flamer = RoomGovernor::from_seed(seed_for("vote-flamer"));
    flamer.observe_host_claim(banned.pubkey(), 1);
    let flamer_frame = flamer.build_vote(&vote_op(99_002, 2, true, 55_005));
    banned.blacklist(flamer.pubkey(), None);
    assert_eq!(
        banned.verify_vote(&flamer_frame, 8_000_000).err(),
        Some(RejectReason::Blacklisted)
    );
}

// ════════════════════════════════════════════════ live mesh scenarios ──

fn mesh_cfg(session: &str, i: usize) -> MeshConfig {
    let mut cfg = MeshConfig::loopback(session, &format!("vote-node-{i}"));
    cfg.identity_seed = Some(seed_for(&format!("{session}-{i}")));
    cfg
}

async fn build_mesh(session: &str, n: usize) -> Vec<Arc<MeshNode>> {
    let mut nodes = Vec::with_capacity(n);
    for i in 0..n {
        nodes.push(MeshNode::start(mesh_cfg(session, i)).expect("bind"));
    }
    for a in 0..n {
        for b in 0..n {
            if a != b {
                nodes[a].add_peer(nodes[b].local_addr());
            }
        }
    }
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|nd| nd.peer_count() == n - 1) {
            return nodes;
        }
        assert!(t0.elapsed() < Duration::from_secs(5), "mesh did not form");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

/// Adds + signed votes from multiple nodes converge into every replica's
/// mesh CRDT: identical folds, identical vote counts, and the ⌊N/2⌋+1
/// promotion fires identically everywhere.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn democratic_voting_converges_on_live_mesh() {
    let _guard = RUN_LOCK.lock().await;
    let session = format!("vote-live-{}", std::process::id());
    let nodes = build_mesh(&session, 5).await;

    // Three tracks added from three different nodes (raw CRDT_OP path —
    // the Phase 1 compatible queue stream).
    let tracks: Vec<JamOp> = vec![
        add_op(JamOp::generate_op_id(), 0xCAD_0001, 0.30),
        add_op(JamOp::generate_op_id(), 0xCAD_0002, 0.50),
        add_op(JamOp::generate_op_id(), 0xCAD_0003, 0.70),
    ];
    for (i, op) in tracks.iter().enumerate() {
        // submit_queue_op applies locally AND broadcasts (raw broadcast
        // would leave the originator's own replica without the op).
        nodes[i % nodes.len()].submit_queue_op(op).expect("submit add");
    }

    // Wait for the adds to land everywhere.
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.crdt_proposed_queue().len() == 3) {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(3), "adds did not converge");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // Democratic run: tracks 0 and 1 collect 3 of 5 votes (threshold
    // ⌊5/2⌋+1 = 3 → promote); track 2 stays at 2 votes.
    #[allow(clippy::needless_range_loop)] // voter-index labels in assertions
    for voter_idx in 0..3usize {
        for (t, track) in tracks.iter().enumerate() {
            if t < 2 || voter_idx < 2 {
                nodes[voter_idx]
                    .cast_vote(track.op_id, true)
                    .expect("vote cast");
            }
        }
    }

    // Convergence watchdog: every replica must reach the identical fold.
    let t0 = Instant::now();
    let _reference = loop {
        let folds: Vec<FullSnapshot> = nodes.iter().map(|n| n.crdt_fold()).collect();
        if folds.iter().all(|f| *f == folds[0]) && !folds[0].votes.is_empty() {
            break folds[0].clone();
        }
        assert!(
            t0.elapsed() < Duration::from_secs(5),
            "votes did not converge: folds = {:?}",
            folds.iter().map(|f| f.votes.len()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    };

    // Every replica: identical vote counts + identical promotion state.
    for n in &nodes {
        assert_eq!(n.vote_count(tracks[0].op_id), 3);
        assert_eq!(n.vote_count(tracks[1].op_id), 3);
        assert_eq!(n.vote_count(tracks[2].op_id), 2);
        assert!(matches!(
            n.crdt_promotion_status(tracks[0].op_id),
            PromotionStatus::Committed { votes: 3 }
        ));
    }
    // The committed queue holds exactly the two promoted tracks.
    let committed = nodes[0].crdt_committed_queue();
    assert_eq!(committed.len(), 2);
    let playback = nodes[0].crdt_playback_order();
    assert_eq!(playback.len(), 3, "committed + proposed = full playback");
    // Committed entries sit fractionally ahead of the proposed rail.
    assert!(playback[0].frac < playback[2].frac);

    // Retraction drops track 1 below threshold → demotes everywhere.
    nodes[0].cast_vote(tracks[1].op_id, false).expect("retract");
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| {
            matches!(
                n.crdt_promotion_status(tracks[1].op_id),
                PromotionStatus::Pending { votes: 2, .. }
            )
        }) {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(5), "retraction did not converge");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    for n in &nodes {
        n.shutdown();
    }
}

/// Forged and unsigned votes never reach any ledger: a tampered signed
/// frame dies at every boundary; an unsigned Vote op on the app-visible
/// CRDT channel is delivered to apps but NOT merged into the mesh vote
/// ledger (votes are born signed inside the engine).
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn forged_and_unsigned_votes_die_at_the_boundary() {
    let _guard = RUN_LOCK.lock().await;
    let session = format!("vote-forgery-{}", std::process::id());
    let nodes = build_mesh(&session, 3).await;

    let track = add_op(JamOp::generate_op_id(), 0xBAD_CAD, 0.5);
    nodes[0].submit_queue_op(&track).expect("add");
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.crdt_proposed_queue().len() == 1) {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(3));
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // A valid vote from node 1 counts.
    nodes[1].cast_vote(track.op_id, true).expect("valid vote");
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.vote_count(track.op_id) == 1) {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(3), "valid vote did not converge");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // ── Forged signed frame: node 1 casts a REAL vote, node 0 tampers
    // the wire bytes and re-broadcasts. Every boundary must reject it.
    let real_frame_probe = {
        // Cast once more, capture the genuine frame via subscription,
        // flip a voter-pubkey byte, rebroadcast as forged.
        let mut rx = nodes[2].subscribe(MSG_VOTE_OP, 16);
        nodes[1].cast_vote(track.op_id, true).expect("second vote");
        let mut frame = None;
        let t0 = Instant::now();
        while frame.is_none() {
            if let Ok(pkt) = rx.try_recv() {
                frame = Some(pkt.payload);
            } else {
                assert!(t0.elapsed() < Duration::from_secs(2));
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        }
        frame.unwrap()
    };
    let mut tampered = real_frame_probe;
    tampered[50] ^= 0xFF; // voter pubkey byte → signature breaks
    nodes[0].broadcast(MSG_VOTE_OP, &tampered).expect("rebroadcast forged");

    // ── Unsigned vote op on the app-visible CRDT channel. ──
    let mut rx_app = nodes[2].subscribe(MSG_CRDT_OP, 16);
    let unsigned = vote_op(123_456, 9, true, track.op_id);
    nodes[0]
        .broadcast(MSG_CRDT_OP, &unsigned.to_bytes())
        .expect("unsigned vote broadcast");

    tokio::time::sleep(Duration::from_millis(250)).await;

    // The forged frame bumped no ledger anywhere (the rebroadcaster
    // itself never receives its own frame — receivers must reject it).
    for n in &nodes {
        assert!(n.vote_count(track.op_id) <= 2, "forged vote must not count");
    }
    let total_rejects: u64 = nodes
        .iter()
        .map(|n| n.stats().vote_rejects + n.stats().gov_sig_rejects)
        .sum();
    assert!(
        total_rejects > 0,
        "forgery rejected and counted at the wire boundary"
    );
    // The unsigned vote is visible to apps…
    let mut saw_unsigned = false;
    while let Ok(pkt) = rx_app.try_recv() {
        if pkt.payload.len() == 48 && pkt.payload[12] == 4 {
            saw_unsigned = true;
        }
    }
    assert!(saw_unsigned, "app layer still sees the raw CRDT_OP frame");
    // …but never merged into any mesh vote ledger.
    for n in &nodes {
        assert!(n.vote_count(track.op_id) <= 2, "unsigned vote must not count");
    }

    for n in &nodes {
        n.shutdown();
    }
}

/// The 10/sec per-peer vote flood limiter: a burst beyond the bucket is
/// refused locally (Throttled) and never reaches any ledger — the
/// caster's own replica included, so counts stay consistent.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn vote_flood_is_rate_limited() {
    let _guard = RUN_LOCK.lock().await;
    let session = format!("vote-flood-{}", std::process::id());
    let nodes = build_mesh(&session, 3).await;

    let track = add_op(JamOp::generate_op_id(), 0xF100D, 0.5);
    nodes[0].submit_queue_op(&track).expect("add");
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.crdt_proposed_queue().len() == 1) {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(3));
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // 30 votes in a tight burst: at most the 10-token burst gets through.
    let mut accepted = 0;
    let mut throttled = 0;
    for _ in 0..30 {
        match nodes[1].cast_vote(track.op_id, true) {
            Ok(_) => accepted += 1,
            Err(MeshError::Throttled) => throttled += 1,
            Err(e) => panic!("unexpected error: {e}"),
        }
    }
    // One voter = one standing vote: the ledger holds exactly the
    // caster's LAST accepted upvote.
    assert_eq!(nodes[1].vote_count(track.op_id), 1, "one voter, one standing vote");
    assert!(accepted <= 11, "burst capacity respected (accepted {accepted})");
    assert!(throttled >= 18, "excess votes throttled locally ({throttled})");

    // Only the accepted frames ever hit the wire — every replica that
    // sees them converges to the SAME count (the caster's own ledger is
    // the reference: its local admission consumed the same bucket the
    // receivers consume, so no replica ever diverges).
    let t0 = Instant::now();
    loop {
        let counts: Vec<u32> = nodes.iter().map(|n| n.vote_count(track.op_id)).collect();
        if counts.iter().all(|c| *c == counts[0]) && counts[0] == 1 {
            break;
        }
        assert!(
            t0.elapsed() < Duration::from_secs(5),
            "accepted votes converge; counts = {counts:?} (accepted {accepted})"
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    // Every replica holds exactly the one standing voter.
    assert_eq!(nodes[0].vote_count(track.op_id), 1);
    assert_eq!(nodes[2].vote_count(track.op_id), 1);

    for n in &nodes {
        n.shutdown();
    }
}

// ═════════════════════════════════════ 32-peer democratic voting chaos ──

const CHAOS_N: usize = 32;

fn chaos_params() -> GossipParams {
    // Same scale-mode profile the Phase 1 32-peer harness calibrated.
    GossipParams {
        lazy_tick: Duration::from_millis(10),
        graft_delay: Duration::from_millis(2),
        graft_retry: Duration::from_millis(15),
        graft_max_tries: 6,
        ihave_batch: 96,
        cache_capacity: 24_000,
        payload_cache_bytes: 2 << 20,
        prune_redundancy_threshold: 3,
        reorder_window: Duration::from_millis(50),
        tail_flush_delay: Duration::from_millis(10),
        tail_flush_k: 32,
        tail_flush_rounds: 6,
        tail_announce_window: Duration::from_secs(2),
        snap_grace_holes: 256,
        snap_grace_delay: Duration::from_millis(30),
        graft_fanout: 2,
        serve_redundancy: 2,
        scale_threshold: 16,
        scale_fanout_cap: 3,
        scale_prune_threshold: 4,
        abandon_rearm: Duration::from_millis(150),
        graft_burst_cap: 32,
        adaptive_backoff_max: 8,
        scale_announce_cap: 4,
        scale_origin_fanout_cap: 31,
        own_recent_depth: 1024,
        reconcile_interval: Duration::from_millis(80),
        delta_announce: true,
    }
}

/// The headline Phase 2 chaos scenario: 32 peers, 15% per-packet loss,
/// 10–80 ms per-link latency (±3 ms jitter), 16 shared tracks, one track
/// elevated by 17 distinct voters (⌊32/2⌋+1). EVERY replica must converge
/// on the identical vote ledger and the identical committed queue — the
/// promotion itself is the convergence assertion.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn thirty_two_peer_democratic_voting_chaos() {
    let _guard = RUN_LOCK.lock().await;
    let session = format!("vote-chaos32-{}", std::process::id());

    let mut nodes = Vec::with_capacity(CHAOS_N);
    for i in 0..CHAOS_N {
        let mut cfg = MeshConfig::loopback(&session, &format!("v32-node-{i}"));
        cfg.gossip = chaos_params();
        cfg.identity_seed = Some(seed_for(&format!("{session}-{i}")));
        nodes.push(MeshNode::start(cfg).expect("bind chaos node"));
    }
    for a in 0..CHAOS_N {
        for b in 0..CHAOS_N {
            if a != b {
                nodes[a].add_peer(nodes[b].local_addr());
            }
        }
    }
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.peer_count() == CHAOS_N - 1) {
            break;
        }
        // 30 s (run #363 flake fix): formation = 992 directed handshakes on
        // a 4-worker runtime. On a loaded CI runner (this shard races 8
        // other jobs in the parallel matrix) the 10 s deadline expired
        // while the mesh was still legitimately forming — a load artifact,
        // not a protocol bug. 30 s keeps the guard real (a broken mesh
        // still never forms) while removing the wall-clock sensitivity.
        assert!(t0.elapsed() < Duration::from_secs(30), "chaos mesh did not form");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }

    // 15% per-packet loss + 10–80 ms per-link latency (Phase 1 model).
    let mut rng = rand::thread_rng();
    for a in &nodes {
        for b in &nodes {
            if a.id() != b.id() {
                let base = rng.gen_range(10..=77i64);
                a.set_link_condition(
                    b.id(),
                    Some(LinkCondition {
                        loss: 0.15,
                        delay: (
                            Duration::from_millis(base as u64),
                            Duration::from_millis((base + 3) as u64),
                        ),
                    }),
                );
            }
        }
    }

    // 16 tracks seeded from node 0 (the raw CRDT_OP stream merges into
    // every replica's queue via the Phase 2 mirror path).
    let tracks: Vec<JamOp> = (0..16u64)
        .map(|i| add_op(JamOp::generate_op_id(), 0x7000_0000 + i, (i as f64 + 1.0) / 17.0))
        .collect();
    for op in &tracks {
        nodes[0].submit_queue_op(op).expect("seed track");
    }

    // Wait until every replica holds all 16 tracks.
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.crdt_proposed_queue().len() + n.crdt_committed_queue().len() == 16)
        {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(10), "tracks did not converge");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // Democratic run under chaos: 17 distinct voters (threshold
    // ⌊32/2⌋+1 = 17) up-vote track 0; a few others vote tracks 1..3
    // (staying below threshold); two voters retract their stray votes.
    let hero = tracks[0].op_id;
    let t_start = Instant::now();
    #[allow(clippy::needless_range_loop)] // voter-index labels in assertions
    for voter in 0..17usize {
        nodes[voter].cast_vote(hero, true).expect("hero vote");
    }
    #[allow(clippy::needless_range_loop)] // voter-index labels in assertions
    for voter in 17..21usize {
        nodes[voter].cast_vote(tracks[1].op_id, true).expect("stray vote");
        nodes[voter].cast_vote(tracks[2].op_id, true).expect("stray vote");
    }
    nodes[21].cast_vote(tracks[3].op_id, true).expect("lone vote");
    nodes[21].cast_vote(tracks[3].op_id, false).expect("lone retraction");

    // Convergence watchdog: all 32 replicas reach the identical fold.
    let t0 = Instant::now();
    let reference = loop {
        let folds: Vec<FullSnapshot> = nodes.iter().map(|n| n.crdt_fold()).collect();
        if folds.iter().all(|f| *f == folds[0])
            && folds[0].votes.len() >= 4
            && folds[0]
                .votes
                .iter()
                .any(|(t, voters)| *t == hero && voters.len() == 17)
        {
            break folds[0].clone();
        }
        assert!(
            t0.elapsed() < Duration::from_secs(15),
            "votes did not converge across 32 peers (vote targets seen: {:?})",
            folds.iter().map(|f| f.votes.len()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    };
    let converge_elapsed = t_start.elapsed();

    // Every replica: hero promoted with exactly 17 votes, in the identical
    // derived committed queue; the stray tracks stay proposed.
    for n in &nodes {
        assert_eq!(n.vote_count(hero), 17, "hero net votes on every replica");
        assert_eq!(n.vote_count(tracks[3].op_id), 0, "retraction subtracts");
        let committed = n.crdt_committed_queue();
        assert_eq!(committed.len(), 1, "exactly one auto-promotion");
        assert_eq!(committed[0].add_op_id, hero);
        assert_eq!(committed[0].votes, 17);
        assert_eq!(n.crdt_proposed_queue().len(), 15);
        // The committed block sits fractionally ahead of the whole rail.
        let rail_head = n.crdt_proposed_queue()[0].frac;
        assert!(committed[0].frac < rail_head);
    }

    // Fold → hydrate a 33rd replica (late joiner): identical democracy.
    let mut joiner_state = JamCrdtState::new();
    joiner_state.set_promotion_policy(PromotionPolicy {
        member_count: CHAOS_N as u32,
        ratio: None,
        min_votes: 1,
    });
    joiner_state.load_full(reference.clone());
    assert_eq!(joiner_state.fold_full(), reference);
    assert_eq!(joiner_state.vote_count(hero), 17);

    println!(
        "32-peer voting chaos: convergence in {converge_elapsed:?} under 15% loss + 10-80ms jitter"
    );

    for n in &nodes {
        n.shutdown();
    }
}

// ═════════════════════════════════ friend activity (directive D) codec ──

#[test]
fn friend_activity_frame_codec_round_trip_and_hostility() {
    let room = [7u8; 16];
    let frame = build_friend_activity_frame(
        0xCAFE,
        90_000,
        Some(&room),
        true,
        false,
        "Fleetwood Mac",
        "Rumours",
    )
    .expect("build");
    let (cad, prog, rid, in_jam, paused, artist, album) =
        parse_friend_activity(&frame).expect("parse");
    assert_eq!(cad, 0xCAFE);
    assert_eq!(prog, 90_000);
    assert_eq!(rid, Some(room));
    assert!(in_jam && !paused);
    assert_eq!(artist, "Fleetwood Mac");
    assert_eq!(album, "Rumours");

    // Not in a room: room zeroed, flag clear.
    let idle = build_friend_activity_frame(1, 0, None, false, true, "A", "B").unwrap();
    let (_, _, rid, in_jam, paused, _, _) = parse_friend_activity(&idle).unwrap();
    assert_eq!(rid, None);
    assert!(!in_jam && paused);

    // Over-long text truncates at a UTF-8 char boundary (no panic).
    let long_artist = "Å".repeat(300);
    let long_album = "日本語テキスト".repeat(60);
    let clamped = build_friend_activity_frame(2, 5, None, false, false, &long_artist, &long_album)
        .expect("clamped build");
    assert!(clamped.len() <= 36 + 80);
    let (_, _, _, _, _, a, b) = parse_friend_activity(&clamped).unwrap();
    assert!(a.len() <= 38 && b.len() <= 78);
    assert!(a.chars().next().is_some());

    // Hostile frames: every truncation, junk tail, bad version, bad len.
    for cut in 0..frame.len() {
        assert!(parse_friend_activity(&frame[..cut]).is_none(), "cut {cut}");
    }
    let mut junk = frame.clone();
    junk.push(0);
    assert!(parse_friend_activity(&junk).is_none());
    let mut bad_ver = frame.clone();
    bad_ver[0] = 0x02;
    assert!(parse_friend_activity(&bad_ver).is_none());
    let mut bad_len = frame.clone();
    bad_len[34] = 0xFF;
    bad_len[35] = 0xFF;
    assert!(parse_friend_activity(&bad_len).is_none());
    // Invalid UTF-8 text section.
    let mut bad_utf8 = frame.clone();
    let text_at = 36;
    bad_utf8[text_at] = 0xF0;
    bad_utf8[text_at + 1] = 0x28; // orphan surrogate start
    assert!(parse_friend_activity(&bad_utf8).is_none());
}

/// Live-mesh friend activity: registry fill, sender throttle, receiver
/// gap guard, malformed frame rejection, TTL expiry.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn friend_activity_rate_limit_registry_and_ttl() {
    let _guard = RUN_LOCK.lock().await;
    let session = format!("friend-act-{}", std::process::id());
    let nodes = build_mesh(&session, 3).await;

    // First broadcast lands; an immediate second is sender-throttled.
    nodes[0]
        .broadcast_friend_activity(0x1234, "Daft Punk", "Discovery", 42_000, None, false, false)
        .expect("first activity");
    let throttled = nodes[0].broadcast_friend_activity(
        0x1234, "Daft Punk", "Discovery", 43_000, None, false, false,
    );
    assert!(matches!(throttled, Err(MeshError::Throttled)));

    // Receivers see the entry in their registry (and the app layer can
    // subscribe to the raw frames).
    let mut rx = nodes[1].subscribe(streamify_core_rs::p2p_mesh::MSG_FRIEND_ACTIVITY, 16);
    let t0 = Instant::now();
    loop {
        if nodes[1].friend_activities().len() == 1
            && nodes[2].friend_activities().len() == 1
            && rx.try_recv().is_ok()
        {
            break;
        }
        assert!(t0.elapsed() < Duration::from_secs(3), "activity did not propagate");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let entry = &nodes[1].friend_activities()[0];
    assert_eq!(entry.cad_id, 0x1234);
    assert_eq!(entry.artist, "Daft Punk");
    assert_eq!(entry.progress_ms, 42_000);
    assert!(!entry.in_jam);

    // Malformed activity frame dies at the boundary (no registry damage).
    // Direct unicast bypasses the sender throttle — engine-internal path.
    let before = nodes[1].stats().rx_length_reject;
    nodes[0]
        .send_to_peer(nodes[1].id(), streamify_core_rs::p2p_mesh::MSG_FRIEND_ACTIVITY, &[0u8; 8])
        .expect("malformed unicast");
    tokio::time::sleep(Duration::from_millis(120)).await;
    assert!(nodes[1].stats().rx_length_reject > before);
    assert_eq!(nodes[1].friend_activities().len(), 1, "registry undamaged");

    // Receiver gap guard: two frames closer than the loopback min gap
    // (5 ms) — the second is dropped as chatty.
    let f1 = build_friend_activity_frame(0x7777, 1, None, false, false, "X", "Y").unwrap();
    let f2 = build_friend_activity_frame(0x8888, 2, None, false, false, "X", "Y").unwrap();
    let base_rejects = nodes[1].stats().friend_activity_rate_limited;
    nodes[0]
        .send_to_peer(nodes[1].id(), streamify_core_rs::p2p_mesh::MSG_FRIEND_ACTIVITY, &f1)
        .unwrap();
    nodes[0]
        .send_to_peer(nodes[1].id(), streamify_core_rs::p2p_mesh::MSG_FRIEND_ACTIVITY, &f2)
        .unwrap();
    tokio::time::sleep(Duration::from_millis(150)).await;
    assert!(
        nodes[1].stats().friend_activity_rate_limited > base_rejects,
        "chatty peer burst dropped by the receiver gap guard"
    );
    // Exactly ONE of the burst pair survives the gap guard (out-of-order
    // processing may crown either frame; the guard drops the other).
    let rows = nodes[1].friend_activities();
    let f1_present = rows.iter().any(|e| e.cad_id == 0x7777);
    let f2_present = rows.iter().any(|e| e.cad_id == 0x8888);
    assert!(
        f1_present ^ f2_present,
        "exactly one burst frame survives the gap guard (f1={f1_present}, f2={f2_present})"
    );

    for n in &nodes {
        n.shutdown();
    }
}
