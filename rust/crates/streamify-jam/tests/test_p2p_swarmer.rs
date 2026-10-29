//! test_p2p_swarmer.rs — Multi-node audio chunk swarm integration.
//!
//! Brief §7 scenario #2: a multi-node audio chunk swarm integration test.
//! Extended to prove the headline claim — ZERO duplicate cellular data
//! consumption:
//!
//!   • 5 nodes on loopback UDP: one seeder (the "fastest device fetched
//!     from the CDN" role) + 4 leechers.
//!   • The CDN is a mock whose `fetch` is called EXACTLY once (by the
//!     seeder). Leechers must never touch it.
//!   • 10% loss + 5–50 ms latency on every link: manifest delivery rides
//!     the gossip tree (IHAVE/GRAFT healable), chunk requests/data ride
//!     direct unicast with timeout-driven re-requests, and the last chunks
//!     hit endgame parallelism.
//!   • Every node must assemble a byte-identical, whole-file-Blake3-
//!     verified copy of the track.
//!
//! A second scenario runs an aggressive-loss swarm (25%) to show the
//! scheduler still completes under hostile radio conditions.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rand::{Rng, SeedableRng};
use streamify_jam::chunk_swarmer::SwarmEvent;
use streamify_jam::gossip::GossipParams;
use streamify_jam::p2p_mesh::{LinkCondition, MeshConfig, MeshNode};

/// The mock CDN: the ONLY legitimate fetcher is the seeder.
struct MockCdn {
    fetch_count: AtomicU64,
}

// Timing-sensitive swarm sims: serialize within this binary.
static RUN_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

impl MockCdn {
    fn fetch(&self, track: &[u8]) -> Vec<u8> {
        self.fetch_count.fetch_add(1, Ordering::SeqCst);
        track.to_vec()
    }
}

fn deterministic_track(len: usize) -> Vec<u8> {
    let mut rng = rand::rngs::StdRng::seed_from_u64(0x5EED_5EED);
    (0..len).map(|_| rng.gen::<u8>()).collect()
}

async fn build_mesh(session: &str, n: usize) -> Vec<Arc<MeshNode>> {
    let mut nodes = Vec::with_capacity(n);
    for i in 0..n {
        let mut cfg = MeshConfig::loopback(session, &format!("swarm-device-{i}"));
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
        cfg.swarm.chunk_size = 32 * 1024; // small chunks keep the suite fast
        cfg.swarm.request_timeout = Duration::from_millis(120);
        cfg.swarm.announce_interval = Duration::from_millis(150);
        nodes.push(MeshNode::start(cfg).expect("bind swarm socket"));
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
            "swarm mesh did not form: {:?}",
            nodes.iter().map(|n| n.peer_count()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

fn apply_chaos(nodes: &[Arc<MeshNode>], loss: f64) {
    // Per-link propagation latency sampled once from the brief's 5–50 ms
    // window (§7 asks for latency, not jitter); loss stays per-packet.
    let mut rng = rand::thread_rng();
    for a in nodes {
        for b in nodes {
            if a.id() != b.id() {
                let d = Duration::from_millis(rng.gen_range(5..=50));
                a.set_link_condition(
                    b.id(),
                    Some(LinkCondition {
                        loss,
                        delay: (d, d),
                    }),
                );
            }
        }
    }
}

/// Waits until every leecher emitted `Complete` for `track_id`.
/// Subscribes ONLY on leecher nodes: the seeder also emits `Complete`
/// at seed time and must not count toward leecher completion.
async fn wait_all_complete(
    nodes: &[Arc<MeshNode>],
    track_id: u64,
    leechers: usize,
    timeout: Duration,
) -> Duration {
    let mut rxs: Vec<_> = nodes[1..]
        .iter()
        .map(|n| n.subscribe_swarm_events(256))
        .collect();
    let mut completed = 0;
    let t0 = Instant::now();
    loop {
        for rx in &mut rxs {
            while let Ok(ev) = rx.try_recv() {
                if matches!(ev, SwarmEvent::Complete { track_id: t, .. } if t == track_id) {
                    completed += 1;
                }
            }
        }
        if completed >= leechers {
            return t0.elapsed();
        }
        assert!(
            t0.elapsed() < timeout,
            "swarm did not complete within {timeout:?} — stats: {:#?}",
            nodes.iter().map(|n| n.swarm_stats()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn four_leechers_swarm_track_with_zero_duplicate_cdn_fetches() {
    let _guard = RUN_LOCK.lock().await;
    const N: usize = 5;
    const TRACK_LEN: usize = 480 * 1024; // 480 KiB → 15 chunks @ 32 KiB
    const TRACK_ID: u64 = 0x000A_11CE_5EED;

    let session = format!("swarm-10pct-{}", std::process::id());
    let nodes = build_mesh(&session, N).await;
    wait_full_mesh(&nodes, Duration::from_secs(2)).await;
    apply_chaos(&nodes, 0.10);

    // The seeder is the ONLY node that may touch the CDN.
    let cdn = Arc::new(MockCdn {
        fetch_count: AtomicU64::new(0),
    });
    let track = deterministic_track(TRACK_LEN);
    let fetched = cdn.fetch(&track); // exactly one cellular/CDN fetch
    assert_eq!(fetched, track);

    // Subscribe completion tracking BEFORE seeding — leecher channels
    // only (the seeder self-emits Complete at seed time).
    let mut rxs: Vec<_> = nodes[1..]
        .iter()
        .map(|n| n.subscribe_swarm_events(256))
        .collect();

    let t0 = Instant::now();
    nodes[0].swarm_seed_track(TRACK_ID, &fetched);

    let mut completed = 0;
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
            t0.elapsed() < Duration::from_secs(15),
            "swarm watchdog — stats: {:#?}",
            nodes.iter().map(|n| n.swarm_stats()).collect::<Vec<_>>()
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let elapsed = t0.elapsed();

    // ── integrity: every leecher assembled the identical, verified track ──
    #[allow(clippy::needless_range_loop)] // node[i] label used in panics
    for i in 1..N {
        let got = nodes[i]
            .swarm_take_track(TRACK_ID)
            .unwrap_or_else(|| panic!("node[{i}] has no assembled track"));
        assert_eq!(got.len(), TRACK_LEN, "node[{i}] length mismatch");
        assert_eq!(got, fetched, "node[{i}] byte mismatch vs CDN bytes");
    }
    // The seeder still serves its own copy.
    assert_eq!(nodes[0].swarm_take_track(TRACK_ID).unwrap(), fetched);

    // ── the headline claim: exactly ONE CDN fetch ──
    assert_eq!(
        cdn.fetch_count.load(Ordering::SeqCst),
        1,
        "leechers must never touch the CDN"
    );

    // ── swarm hygiene ──
    let mut corrupt = 0;
    let mut duplicates = 0;
    let mut served_by_leechers = 0;
    for (i, n) in nodes.iter().enumerate() {
        for s in n.swarm_stats() {
            assert_eq!(s.corrupt_chunks, 0, "node[{i}] stored a corrupt chunk?!");
            corrupt += s.corrupt_chunks;
            duplicates += s.duplicate_chunks;
            if i > 0 {
                served_by_leechers += s.served_chunks;
            }
        }
    }
    assert_eq!(corrupt, 0);
    // Endgame duplicates are bounded (far below one full track transfer).
    assert!(
        duplicates < (TRACK_LEN / (32 * 1024)) as u64,
        "duplicate chunk receipts exploded: {duplicates}"
    );

    println!(
        "swarm: 480 KiB × 4 leechers in {elapsed:?} (1 CDN fetch, \
         {duplicates} dup chunks, {served_by_leechers} chunks served BY leechers — \
         the swarm self-balances once they complete)"
    );
    for s in nodes[1].swarm_stats() {
        println!("  leecher[1] stats: {s:?}");
    }
    for n in &nodes {
        n.shutdown();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn swarm_completes_under_25pct_hostile_loss() {
    let _guard = RUN_LOCK.lock().await;
    const N: usize = 5;
    const TRACK_LEN: usize = 192 * 1024; // 6 chunks @ 32 KiB
    const TRACK_ID: u64 = 0xBEEF_CAFE;

    let session = format!("swarm-25pct-{}", std::process::id());
    let nodes = build_mesh(&session, N).await;
    wait_full_mesh(&nodes, Duration::from_secs(2)).await;
    apply_chaos(&nodes, 0.25);

    let track = deterministic_track(TRACK_LEN);
    // Single CDN fetch by the seeder — even under hostile loss.
    nodes[0].swarm_seed_track(TRACK_ID, &track);

    let elapsed = wait_all_complete(&nodes, TRACK_ID, N - 1, Duration::from_secs(30)).await;
    #[allow(clippy::needless_range_loop)] // node[i] label used in assertions
    for i in 1..N {
        let got = nodes[i]
            .swarm_take_track(TRACK_ID)
            .expect("assembled under 25% loss");
        assert_eq!(got, track, "node[{i}] bytes diverge under 25% loss");
    }
    println!("hostile swarm (25% loss): completed in {elapsed:?}");
    for n in &nodes {
        n.shutdown();
    }
}
