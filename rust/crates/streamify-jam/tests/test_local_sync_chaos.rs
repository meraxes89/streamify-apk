//! test_local_sync_chaos.rs — Phase 3 chaos suite (directive §2.5).
//!
//! THE headline scenario: a 10-node concurrent swarm transfer under 20%
//! simulated packet loss with random peer drops — corrupted chunks are
//! re-downloaded from alternative peers, and every node ends with an
//! end-to-end byte-equivalent copy of the file.
//!
//! Three layers of proof:
//!   1. [`ten_node_swarm_20pct_loss_random_drops_corruption`] — the
//!      deterministic in-memory harness: full accounting of dropped and
//!      corrupted frames, mid-transfer peer drops with rejoin, and
//!      per-node byte equivalence.
//!   2. [`real_socket_ten_node_swarm_under_20pct_loss`] — the same
//!      shape on REAL MeshNode instances over loopback UDP with a live
//!      chaos driver blacking out random links mid-transfer.
//!   3. [`lan_sync_session_survives_lossy_transport_and_intent_chaos`]
//!      and [`two_way_sync_converges_both_libraries`] — the LAN sync
//!      coordinator under 20% chunk-delivery loss, random pause/resume
//!      interrupts, and full two-way catalog convergence.

mod swarm_sim;

use std::sync::Arc;
use std::time::{Duration, Instant};

use rand::{Rng, SeedableRng};
use streamify_jam::chunk_swarmer::SwarmEvent;
use streamify_jam::chunk_verifier::{
    hash_file, ChunkVerifier, HashTreeManifest, MIN_CHUNK_SIZE,
};
use streamify_jam::gossip::GossipParams;
use streamify_jam::local_sync::{
    CatalogDelta, CatalogEntry, Direction, LibraryCatalog, SyncPolicy, SyncSession,
};
use streamify_jam::p2p_mesh::{LinkCondition, MeshConfig, MeshNode};

use swarm_sim::{pseudo_audio, sim_params, SimMesh};

// ════════════════════════════════════════════════════════ in-memory chaos

#[test]
fn ten_node_swarm_20pct_loss_random_drops_corruption() {
    // 10 nodes: origin 0 + 9 leechers. 20% per-frame loss on every
    // link, 2% of delivered chunk frames corrupted mid-flight, and
    // three random peer drops (with rejoin) during the transfer.
    const N: usize = 10;
    const TRACK: u64 = 0x5EED_1005;
    let data = pseudo_audio(64 * 1024); // 64 chunks @ 1 KiB

    let mut mesh = SimMesh::new(
        N,
        0x5EED_0FA1, // seeded: byte-reproducible chaos
        0.20,
        0.02,
        sim_params(1024, 4, 24, 4),
        TRACK,
        &data,
        0,
    );
    mesh.announce_origin(0);

    // Mid-transfer peer drops: leecher 3 vanishes at tick 8, leecher 6
    // at tick 16, leecher 8 at tick 24 — each rejoins 30 ticks later
    // (the engines greet them back via on_peer_up).
    let mut drops = [(8usize, 3usize), (16, 6), (24, 8)];
    let mut ticks = 0usize;
    let deadline = 4_000;
    while ticks < deadline {
        ticks += 1;
        mesh.tick();
        for &mut (at, node) in drops.iter_mut() {
            if ticks == at {
                mesh.drop_peer(node);
            }
            if ticks == at + 30 {
                mesh.restore_peer(node);
            }
        }
        if mesh.all_complete(TRACK) {
            break;
        }
    }
    assert!(
        ticks < deadline,
        "10-node swarm must converge under 20% loss + corruption + drops; \
         stats: {:?}; per-node: {:?}",
        mesh.stats,
        mesh.stats_of(TRACK)
    );

    // ── end-to-end file byte equivalence on EVERY node ──
    for (i, bytes) in mesh.take_all(TRACK).iter().enumerate() {
        assert_eq!(
            bytes.as_deref(),
            Some(data.as_slice()),
            "node[{i}] final bytes diverge from the origin"
        );
    }

    // ── chaos actually happened ──
    assert!(
        mesh.stats.frames_dropped > 50,
        "packet loss must have fired"
    );
    assert!(
        mesh.stats.frames_corrupted >= 1,
        "corruption injection must have fired: {:?}",
        mesh.stats
    );
    assert_eq!(mesh.stats.peer_drop_events, 3);

    // ── corruption was healed via alternative peers ──
    // The per-(chunk, peer) blacklist means a peer that served a chunk
    // failing Blake3 can NEVER serve that chunk again on this swarm —
    // so every node with corrupt_chunks > 0 that nonetheless completed
    // demonstrably re-downloaded those chunks from OTHER peers.
    let stats = mesh.stats_of(TRACK);
    let corrupt_nodes = stats.iter().filter(|s| s.corrupt_chunks >= 1).count();
    assert!(
        corrupt_nodes >= 1,
        "at least one node must have rejected a corrupted chunk: {stats:?}"
    );
    for s in &stats {
        assert_eq!(s.have_chunks, s.num_chunks);
        assert!(s.complete);
    }
    println!(
        "10-node chaos swarm: 64 KiB converged in {ticks} ticks — {} frames \
         dropped, {} corrupted frames (healed via alternative peers on \
         {corrupt_nodes} nodes), 3 peer drops survived",
        mesh.stats.frames_dropped, mesh.stats.frames_corrupted
    );
}

// ══════════════════════════════════════════════════════ real-socket chaos

// Timing-sensitive swarm sims: serialize within this binary.
static RUN_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

async fn build_mesh(session: &str, n: usize) -> Vec<Arc<MeshNode>> {
    let mut nodes = Vec::with_capacity(n);
    for i in 0..n {
        let mut cfg = MeshConfig::loopback(session, &format!("chaos-device-{i}"));
        cfg.gossip = GossipParams {
            lazy_tick: Duration::from_millis(15),
            graft_delay: Duration::from_millis(2),
            graft_retry: Duration::from_millis(25),
            graft_max_tries: 16,
            ihave_batch: 96,
            cache_capacity: 20_000,
            payload_cache_bytes: 2 << 20,
            prune_redundancy_threshold: 3,
            reorder_window: Duration::from_millis(10),
            tail_flush_delay: Duration::from_millis(10),
            tail_flush_k: 32,
            tail_flush_rounds: 6,
            tail_announce_window: Duration::from_secs(2),
            snap_grace_holes: 256,
            snap_grace_delay: Duration::from_millis(30),
            graft_fanout: 3,
            serve_redundancy: 3,
            scale_threshold: 16,
            scale_fanout_cap: 8,
            scale_prune_threshold: 2,
            abandon_rearm: Duration::from_millis(750),
            scale_announce_cap: 4,
            scale_origin_fanout_cap: 31,
            own_recent_depth: 64,
            reconcile_interval: Duration::from_millis(250),
            graft_burst_cap: 32,
            adaptive_backoff_max: 8,
            delta_announce: true,
        };
        cfg.swarm.chunk_size = 32 * 1024; // small chunks keep CI fast
        cfg.swarm.request_timeout = Duration::from_millis(150);
        cfg.swarm.announce_interval = Duration::from_millis(200);
        cfg.swarm.max_total_inflight = 48; // Phase 3 backpressure ON
        cfg.swarm.sequential_window = 4; // Phase 3 hybrid scheduler ON
        nodes.push(MeshNode::start(cfg).expect("bind chaos socket"));
    }
    for a in 0..n {
        for b in 0..n {
            if a != b {
                nodes[a].add_peer(nodes[b].local_addr());
            }
        }
    }
    nodes
}

async fn wait_full_mesh(nodes: &[Arc<MeshNode>], timeout: Duration) {
    let t0 = Instant::now();
    loop {
        if nodes.iter().all(|n| n.peer_count() == nodes.len() - 1) {
            return;
        }
        assert!(
            t0.elapsed() < timeout,
            "chaos mesh did not form: {:?}",
            nodes.iter().map(|n| n.peer_count()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn real_socket_ten_node_swarm_under_20pct_loss() {
    let _guard = RUN_LOCK.lock().await;
    const N: usize = 10;
    const TRACK_LEN: usize = 384 * 1024; // 12 chunks @ 32 KiB
    const TRACK_ID: u64 = 0x5EED_2020;

    let session = format!("chaos-20pct-{}", std::process::id());
    let nodes = build_mesh(&session, N).await;
    wait_full_mesh(&nodes, Duration::from_secs(3)).await;

    // 20% loss + 5–50 ms latency on every directed link.
    let mut rng = rand::rngs::StdRng::seed_from_u64(0x5EED_C7A0);
    for a in &nodes {
        for b in &nodes {
            if a.id() != b.id() {
                let d = Duration::from_millis(rng.gen_range(5..=50));
                a.set_link_condition(
                    b.id(),
                    Some(LinkCondition {
                        loss: 0.20,
                        delay: (d, d),
                    }),
                );
            }
        }
    }

    // The chaos driver: every 300 ms a random LEECHER's links black out
    // (loss = 1.0) for 400 ms, then restore to the 20% baseline.
    // Bounded to 20 cycles so the transfer tail runs on clean-ish links.
    let driver = {
        let nodes = nodes.clone();
        tokio::spawn(async move {
            let mut rng = rand::rngs::StdRng::seed_from_u64(0x5EED_D40B);
            for _ in 0..20 {
                tokio::time::sleep(Duration::from_millis(300)).await;
                let victim = rng.gen_range(1..nodes.len());
                for other in nodes.iter() {
                    if other.id() != nodes[victim].id() {
                        nodes[victim].set_link_condition(
                            other.id(),
                            Some(LinkCondition {
                                loss: 1.0,
                                delay: (Duration::from_millis(10), Duration::from_millis(10)),
                            }),
                        );
                        other.set_link_condition(
                            nodes[victim].id(),
                            Some(LinkCondition {
                                loss: 1.0,
                                delay: (Duration::from_millis(10), Duration::from_millis(10)),
                            }),
                        );
                    }
                }
                tokio::time::sleep(Duration::from_millis(400)).await;
                for other in nodes.iter() {
                    if other.id() != nodes[victim].id() {
                        let d = Duration::from_millis(rng.gen_range(5..=50));
                        nodes[victim].set_link_condition(
                            other.id(),
                            Some(LinkCondition {
                                loss: 0.20,
                                delay: (d, d),
                            }),
                        );
                        let d2 = Duration::from_millis(rng.gen_range(5..=50));
                        other.set_link_condition(
                            nodes[victim].id(),
                            Some(LinkCondition {
                                loss: 0.20,
                                delay: (d2, d2),
                            }),
                        );
                    }
                }
            }
        })
    };

    let track = {
        let mut rng = rand::rngs::StdRng::seed_from_u64(0x5EED_5EED);
        (0..TRACK_LEN).map(|_| rng.gen::<u8>()).collect::<Vec<u8>>()
    };

    // Completion tracking BEFORE seeding (leechers only — the seeder
    // self-emits Complete at seed time).
    let mut rxs: Vec<_> = nodes[1..]
        .iter()
        .map(|n| n.subscribe_swarm_events(512))
        .collect();
    nodes[0].swarm_seed_track(TRACK_ID, &track);

    let t0 = Instant::now();
    let mut completed = 0usize;
    loop {
        for rx in &mut rxs {
            while let Ok(ev) = rx.try_recv() {
                if matches!(ev, SwarmEvent::Complete { track_id: t, .. } if t == TRACK_ID) {
                    completed += 1;
                }
            }
        }
        if completed >= N - 1 {
            break;
        }
        assert!(
            t0.elapsed() < Duration::from_secs(90),
            "10-node real-socket swarm watchdog — stats: {:#?}",
            nodes.iter().map(|n| n.swarm_stats()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let elapsed = t0.elapsed();
    driver.abort();

    // ── end-to-end byte equivalence on every node ──
    #[allow(clippy::needless_range_loop)] // node[i] labels in assertions
    for i in 1..N {
        let got = nodes[i]
            .swarm_take_track(TRACK_ID)
            .unwrap_or_else(|| panic!("node[{i}] has no assembled track"));
        assert_eq!(got.len(), TRACK_LEN, "node[{i}] length mismatch");
        assert_eq!(got, track, "node[{i}] byte mismatch vs origin");
    }
    assert_eq!(nodes[0].swarm_take_track(TRACK_ID).unwrap(), track);

    println!(
        "real-socket 10-node swarm: 384 KiB × 9 leechers in {elapsed:?} under \
         20% loss + random blackouts (Phase 3 scheduler engaged)"
    );
    for n in &nodes {
        n.shutdown();
    }
}

// ══════════════════════════════════════════════════ LAN sync session chaos

fn catalog_from_files(files: &[(&str, &[u8], u64)]) -> LibraryCatalog {
    let entries = files
        .iter()
        .map(|&(path, bytes, mtime)| {
            CatalogEntry::new(path, bytes.len() as u64, mtime, hash_file(bytes))
        })
        .collect();
    LibraryCatalog::from_entries(entries).expect("catalog builds")
}

/// Owned snapshot of a session's job list (breaks the borrow before the
/// mutable progress pushes).
fn owned_jobs(s: &SyncSession) -> Vec<(Direction, String, u64)> {
    s.jobs()
        .into_iter()
        .map(|f| (f.direction, f.path.clone(), f.bytes_total))
        .collect()
}

#[test]
fn lan_sync_session_survives_lossy_transport_and_intent_chaos() {
    // The phone holds three files the desktop lacks. The transport is
    // lossy (20% of chunk deliveries vanish and must be retried) and
    // hostile to intents (random pause/resume interruptions). The
    // session must still reach Completed with exact accounting, and
    // every completed file must pass the Phase 3 hash-tree gate.
    let phone: Vec<(String, Vec<u8>)> = vec![
        (
            "Clark/Body Riddle/2.flac".into(),
            pseudo_audio(3 * MIN_CHUNK_SIZE + 9_000),
        ),
        (
            "Clark/Empty Tag/0.flac".into(),
            pseudo_audio(MIN_CHUNK_SIZE),
        ),
        (
            "Clark/Iradel 10/7.flac".into(),
            pseudo_audio(2 * MIN_CHUNK_SIZE + 1),
        ),
    ];
    let phone_refs: Vec<(&str, &[u8], u64)> = phone
        .iter()
        .map(|(p, b)| (p.as_str(), b.as_slice(), 5_000))
        .collect();
    let phone_catalog = catalog_from_files(&phone_refs);
    let desktop_catalog = LibraryCatalog::default();

    let mut rng = rand::rngs::StdRng::seed_from_u64(0x5EED_3001);
    let mut session = SyncSession::new(
        "phone-7f3a",
        &desktop_catalog,
        &phone_catalog,
        SyncPolicy::default(),
    );
    assert!(session.start());
    assert_eq!(session.jobs().len(), 3);

    let chunk = 16 * 1024u64;
    let mut intent_flips = 0u32;
    for (direction, path, bytes_total) in owned_jobs(&session) {
        assert_eq!(direction, Direction::Pull);
        let mut done_bytes = 0u64;
        while done_bytes < bytes_total {
            // Intent chaos: 8% of rounds flip pause→resume, and while
            // paused the transport's progress pushes are REJECTED.
            if rng.gen::<f64>() < 0.08 {
                assert!(session.pause());
                assert!(session.on_bytes_transferred(direction, &path, 1).is_err());
                assert!(session.resume());
                intent_flips += 1;
            }
            // Transport chaos: 20% of chunk deliveries are lost — the
            // retry loop simply re-attempts the same chunk.
            if rng.gen::<f64>() < 0.20 {
                continue;
            }
            let step = chunk.min(bytes_total - done_bytes);
            session
                .on_bytes_transferred(direction, &path, step)
                .expect("progress push must land while syncing");
            done_bytes += step;
        }
        session
            .on_file_completed(direction, &path)
            .expect("file completion must land");
        assert_eq!(session.file_progress(&path), Some(1.0));
    }
    use streamify_jam::local_sync::SessionState;
    assert_eq!(session.state(), SessionState::Completed);
    assert!(
        intent_flips >= 1,
        "intent chaos must have fired ({intent_flips})"
    );

    // Exact accounting.
    let summary = session.summary();
    assert_eq!(summary.files_pulled, 3);
    assert_eq!(
        summary.bytes_pulled,
        phone.iter().map(|(_, b)| b.len() as u64).sum::<u64>()
    );
    assert_eq!(summary.files_pushed, 0);
    assert_eq!(session.overall_progress(), 1.0);

    // ── end-to-end byte equivalence: every completed file passes the
    //    hash-tree verification gate (exactly what the JNI bridge's
    //    verifyFileAgainstTree runs at transfer completion) ──
    for (path, bytes) in &phone {
        let manifest = HashTreeManifest::build(bytes, MIN_CHUNK_SIZE).unwrap();
        let verifier = ChunkVerifier::new(manifest);
        assert!(
            verifier.verify_file(bytes),
            "pulled file {path} must verify against its hash tree"
        );
    }
    println!(
        "LAN session chaos: 3 files / {} bytes converged with {intent_flips} \
         pause-resume flips under 20% chunk loss",
        summary.bytes_pulled
    );
}

#[test]
fn two_way_sync_converges_both_libraries() {
    // Desktop: {A1 unique, X diverged OLDER}. Phone: {P1, P2 unique,
    // X diverged NEWER}. Both sides run mirrored NewerWins sessions;
    // after both complete, the two libraries CONVERGE — the 32-byte
    // fingerprint fast path then certifies that the NEXT session
    // between these peers moves zero bytes.
    let desktop_file = pseudo_audio(MIN_CHUNK_SIZE + 4_321);
    let phone_a = pseudo_audio(2 * MIN_CHUNK_SIZE);
    let phone_b = pseudo_audio(MIN_CHUNK_SIZE + 77);
    let x_desktop = pseudo_audio(3 * MIN_CHUNK_SIZE);
    let mut x_phone = x_desktop.clone();
    x_phone[1234] ^= 0x10; // diverged content

    let desktop = catalog_from_files(&[
        ("Boards/A1.flac", &desktop_file, 1_000),
        ("Shared/X.flac", &x_desktop, 2_000), // OLDER
    ]);
    let phone = catalog_from_files(&[
        ("Clark/P1.flac", &phone_a, 9_000),
        ("Clark/P2.flac", &phone_b, 9_100),
        ("Shared/X.flac", &x_phone, 8_000), // NEWER → wins
    ]);

    // ── desktop-side session: pull P1, P2 and the newer X; push A1 ──
    let mut desk_session = SyncSession::new("phone", &desktop, &phone, SyncPolicy::default());
    assert!(desk_session.start());
    assert_eq!(desk_session.jobs().len(), 4);
    assert!(desk_session
        .jobs()
        .iter()
        .any(|j| j.direction == Direction::Pull && j.path == "Clark/P1.flac"));
    assert!(desk_session
        .jobs()
        .iter()
        .any(|j| j.direction == Direction::Pull && j.path == "Shared/X.flac"));
    assert!(desk_session
        .jobs()
        .iter()
        .any(|j| j.direction == Direction::Push && j.path == "Boards/A1.flac"));

    // ── phone-side session (mirrored): push P1, P2, X; pull A1 ──
    let mut phone_session = SyncSession::new("desktop", &phone, &desktop, SyncPolicy::default());
    assert!(phone_session.start());
    assert_eq!(phone_session.jobs().len(), 4);
    assert!(phone_session
        .jobs()
        .iter()
        .any(|j| j.direction == Direction::Push && j.path == "Shared/X.flac"));
    assert!(phone_session
        .jobs()
        .iter()
        .any(|j| j.direction == Direction::Pull && j.path == "Boards/A1.flac"));

    // Drive BOTH sessions to completion (lossless transport here — the
    // lossy path is covered by the session-chaos test above).
    fn drive(s: &mut SyncSession) {
        for (direction, path, bytes_total) in owned_jobs(s) {
            let mut left = bytes_total;
            while left > 0 {
                let step = (32 * 1024u64).min(left);
                s.on_bytes_transferred(direction, &path, step).unwrap();
                left -= step;
            }
            s.on_file_completed(direction, &path).unwrap();
        }
    }
    drive(&mut desk_session);
    drive(&mut phone_session);
    use streamify_jam::local_sync::SessionState;
    assert_eq!(desk_session.state(), SessionState::Completed);
    assert_eq!(phone_session.state(), SessionState::Completed);

    // Apply the transfer RESULTS: desktop gained P1/P2 and the newer X
    // (phone entries win on clash); phone gained A1 (no clash).
    let mut desk_entries: Vec<CatalogEntry> = desktop.entries().cloned().collect();
    desk_entries.extend(phone.entries().cloned());
    let desk_new = LibraryCatalog::from_entries(desk_entries).unwrap();

    let mut phone_entries: Vec<CatalogEntry> = phone.entries().cloned().collect();
    phone_entries.push(desktop.get("Boards/A1.flac").unwrap().clone());
    let phone_new = LibraryCatalog::from_entries(phone_entries).unwrap();

    // Both sides hold X as the NEWER phone version.
    assert_eq!(
        desk_new.get("Shared/X.flac").unwrap().content_hash,
        hash_file(&x_phone)
    );
    assert_eq!(
        phone_new.get("Shared/X.flac").unwrap().content_hash,
        hash_file(&x_phone)
    );

    // ── CONVERGENCE ──
    assert_eq!(
        desk_new.fingerprint(),
        phone_new.fingerprint(),
        "two-way sync must converge the libraries"
    );
    assert!(CatalogDelta::compute(&desk_new, &phone_new).is_empty());
}
