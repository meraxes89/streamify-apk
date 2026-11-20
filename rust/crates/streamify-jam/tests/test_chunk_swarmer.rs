//! test_chunk_swarmer.rs — P2P chunk swarmer integration suite
//! (Phase 3, directive §2.5).
//!
//! Drives the upgraded transfer state machine through the deterministic
//! in-memory chaos harness ([`swarm_sim`]) plus hand-crafted wire frames:
//!   • multi-peer completion with the hybrid scheduler + global window
//!     engaged end-to-end (byte equivalence on every node),
//!   • a full network PARTITION mid-transfer that must heal on its own
//!     through timeout-driven re-requests (no external nudging),
//!   • resumable transfer persistence at scale: mid-transfer crash,
//!     disk rehydration, gap-only refetch (network bytes = the gap),
//!   • global-window backpressure holding for an entire run.

mod swarm_sim;

use streamify_jam::chunk_swarmer::{SwarmAction, SwarmManager, SwarmParams, TrackManifest};
use streamify_jam::p2p_mesh::MSG_CHUNK_REQUEST;

use swarm_sim::{pseudo_audio, sim_params, SimMesh, TICK_NS};

const TRACK: u64 = 0x5EED_0A71;
const TRACK_CRASH: u64 = 0x5EED_0C7A;

/// Hand-crafted HAVE frame (wire layout from the module docs):
/// `[track u64][total_hash 32][num u32][bitmap ceil(num/8)]`.
/// Building it OUTSIDE the crate proves the wire format is a real
/// cross-module contract, not an internal convention.
fn have_frame(manifest: &TrackManifest, bits: &[bool]) -> Vec<u8> {
    let n = manifest.num_chunks as usize;
    assert_eq!(bits.len(), n);
    let mut bitmap = vec![0u8; n.div_ceil(8)];
    for (i, &b) in bits.iter().enumerate() {
        if b {
            bitmap[i / 8] |= 1 << (i % 8);
        }
    }
    let mut p = Vec::with_capacity(44 + bitmap.len());
    p.extend_from_slice(&manifest.track_id.to_le_bytes());
    p.extend_from_slice(&manifest.total_hash);
    p.extend_from_slice(&manifest.num_chunks.to_le_bytes());
    p.extend_from_slice(&bitmap);
    p
}

/// Serves one CHUNK_REQUEST frame from the raw track bytes.
fn serve_chunk(track: u64, data: &[u8], chunk_size: usize, payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() != 12 {
        return None;
    }
    let idx = u32::from_le_bytes(payload[8..12].try_into().unwrap());
    let lo = idx as usize * chunk_size;
    let hi = (lo + chunk_size).min(data.len());
    let mut frame = Vec::with_capacity(16 + (hi - lo));
    frame.extend_from_slice(&track.to_le_bytes());
    frame.extend_from_slice(&idx.to_le_bytes());
    frame.extend_from_slice(&((hi - lo) as u32).to_le_bytes());
    frame.extend_from_slice(&data[lo..hi]);
    Some(frame)
}

#[test]
fn eight_peer_swarm_completes_with_hybrid_scheduler_and_backpressure() {
    // 9 nodes: 1 origin + 8 leechers, 15% loss, 1% corruption, 48 KiB
    // track in 1 KiB chunks. The hybrid window (4) and the global
    // in-flight cap (12) are engaged for the entire run.
    let data = pseudo_audio(48 * 1024);
    let mut mesh = SimMesh::new(
        9,
        0x5EED_0001,
        0.15,
        0.01,
        sim_params(1024, 4, 12, 4),
        TRACK,
        &data,
        0,
    );
    mesh.announce_origin(0);
    let ticks = mesh
        .run_until_complete(TRACK, 2_000)
        .expect("swarm must converge under 15% loss + 1% corruption");

    // End-to-end byte equivalence on EVERY node.
    for (i, bytes) in mesh.take_all(TRACK).iter().enumerate() {
        assert_eq!(
            bytes.as_deref(),
            Some(data.as_slice()),
            "node[{i}] assembled bytes diverge"
        );
    }
    // Corruption actually fired AND was healed: the per-index blacklist
    // forbids the corrupting peer from ever serving that chunk again,
    // so every completion past a corrupt_chunks > 0 is itself the proof
    // that the chunk was re-downloaded from an ALTERNATIVE peer.
    assert!(
        mesh.stats.frames_corrupted >= 1,
        "corruption injection must have fired (seeded): {:?}",
        mesh.stats
    );
    let stats = mesh.stats_of(TRACK);
    assert!(
        stats.iter().any(|s| s.corrupt_chunks >= 1),
        "at least one node must have REJECTED a corrupted chunk: {stats:?}"
    );
    for s in &stats {
        assert_eq!(s.have_chunks, s.num_chunks);
        assert!(s.complete);
    }
    println!(
        "8-peer swarm: 48 KiB converged in {ticks} ticks ({} frames dropped, \
         {} corrupted frames → all healed)",
        mesh.stats.frames_dropped, mesh.stats.frames_corrupted
    );
}

#[test]
fn swarm_survives_a_full_network_partition_and_self_heals() {
    // 6 nodes, 96 KiB track. The mesh splits {0,1,2} | {3,4,5} at tick
    // 6 — the right half is starved mid-transfer with only its partial
    // chunks to trade — and heals at tick 110. Recovery must ride the
    // engines' own timeout re-requests and HAVE re-announce cadence:
    // the harness applies NO nudging.
    let data = pseudo_audio(96 * 1024);
    let mut mesh = SimMesh::new(
        6,
        0x5EED_0002,
        0.0, // clean links: the partition is the chaos under test
        0.0,
        sim_params(1024, 2, 6, 4),
        TRACK,
        &data,
        0,
    );
    mesh.announce_origin(0);

    // Warm-up: everyone learns the manifest and starts fetching.
    for _ in 0..6 {
        mesh.tick();
    }
    assert!(!mesh.all_complete(TRACK), "pre-partition: mid-flight");

    // PARTITION: the origin's half keeps the data; the right half is
    // starved at its partial union (the partitioned peers vanish from
    // each side's alive list — exactly how the real mesh's heartbeat
    // expiry removes them — so no cross-side traffic exists at all).
    mesh.partition(&[0, 1, 2], &[3, 4, 5]);
    for _ in 0..104 {
        mesh.tick();
    }
    let stats = mesh.stats_of(TRACK);
    for (i, s) in stats.iter().enumerate() {
        if i < 3 {
            assert!(s.complete, "origin half must complete during the split");
        } else {
            assert!(!s.complete, "node[{i}] cannot complete while partitioned");
            assert!(
                s.have_chunks < s.num_chunks,
                "right half must be starved below completion ({}/{})",
                s.have_chunks,
                s.num_chunks
            );
        }
    }
    let stalled_at = stats.iter().skip(3).map(|s| s.have_chunks).sum::<u32>();
    assert!(
        stalled_at < 3 * 96,
        "right half is stalled at its partial union"
    );

    // HEAL: links back up; engines recover on their own.
    mesh.heal_partition();
    let ticks = mesh
        .run_until_complete(TRACK, 3_000)
        .expect("swarm must self-heal after the partition lifts");
    for (i, bytes) in mesh.take_all(TRACK).iter().enumerate() {
        assert_eq!(
            bytes.as_deref(),
            Some(data.as_slice()),
            "node[{i}] bytes diverge post-heal"
        );
    }
    println!(
        "partition test: right half stalled at {stalled_at}/288 chunks, healed \
         and converged {ticks} ticks after the split"
    );
}

#[test]
fn resumable_state_persists_across_a_full_crash_at_scale() {
    // 40-chunk transfer crashes at ~40%, restarts from the persisted
    // bitmap + re-verified disk chunks, and refetches ONLY the gap.
    let data = pseudo_audio(40 * 1024);
    let params: SwarmParams = sim_params(1024, 4, 16, 4);
    let manifest = TrackManifest::build(TRACK_CRASH, &data, 1024);
    let full = vec![true; 40];
    let peers = [0x11u64];

    // Phase 1: the doomed node fetches ~40% over a clean link, driven
    // purely through the public wire API (manifest + hand-built HAVE).
    let mut doomed = SwarmManager::new(0x99, params.clone());
    let _ = doomed.on_manifest(&manifest.to_wire(), 0x11, 0);
    let _ = doomed.on_have(&have_frame(&manifest, &full), 0x11, 0);

    let mut t = 0i64;
    let mut have_at_crash = 0u32;
    for _ in 0..500 {
        t += TICK_NS;
        for action in doomed.on_tick(t, &peers) {
            if let SwarmAction::Unicast {
                to,
                msg_type,
                payload,
            } = action
            {
                assert_eq!(to, 0x11);
                if msg_type == MSG_CHUNK_REQUEST {
                    let frame = serve_chunk(TRACK_CRASH, &data, 1024, &payload).unwrap();
                    let _ = doomed.on_chunk_data(&frame, 0x11, t, &peers);
                }
            }
        }
        have_at_crash = doomed
            .stats()
            .into_iter()
            .find(|s| s.track_id == TRACK_CRASH)
            .map(|s| s.have_chunks)
            .unwrap_or(0);
        if have_at_crash >= 16 {
            break;
        }
    }
    assert!(
        (12..40).contains(&have_at_crash),
        "crash point must be mid-transfer: {have_at_crash}/40"
    );

    // CRASH: the state blob is all that survives.
    let blob = doomed
        .save_state(TRACK_CRASH)
        .expect("state blob must serialize");

    // Phase 2: a brand-new process restores from the blob.
    let mut resumed = SwarmManager::new(0x99, params);
    let info = resumed.restore_state(&blob, t).expect("restore must parse");
    assert_eq!(info.track_id, TRACK_CRASH);
    assert_eq!(info.num_chunks, 40);
    assert_eq!(info.have_chunks, have_at_crash);

    // Disk rehydration: every persisted chunk re-verifies against the
    // manifest (simulated reads of the on-disk chunk files).
    for i in 0..40u32 {
        let lo = i as usize * 1024;
        let _ = resumed.rehydrate_chunk(TRACK_CRASH, i, &data[lo..lo + 1024], t, &[]);
    }
    assert_eq!(resumed.stats()[0].restored_chunks, have_at_crash as u64);
    assert_eq!(resumed.stats()[0].provisional_chunks, 0);

    // Rejoin: manifest is a no-op (live swarm wins), the origin's HAVE
    // re-arms the scheduler, and ONLY the gap is fetched.
    let _ = resumed.on_manifest(&manifest.to_wire(), 0x11, t);
    let _ = resumed.on_have(&have_frame(&manifest, &full), 0x11, t);

    let mut t2 = t;
    let mut done = false;
    for _ in 0..600 {
        t2 += TICK_NS;
        for action in resumed.on_tick(t2, &peers) {
            if let SwarmAction::Unicast {
                msg_type, payload, ..
            } = action
            {
                if msg_type == MSG_CHUNK_REQUEST {
                    let frame = serve_chunk(TRACK_CRASH, &data, 1024, &payload).unwrap();
                    let _ = resumed.on_chunk_data(&frame, 0x11, t2, &peers);
                }
            }
        }
        if resumed
            .stats()
            .iter()
            .any(|s| s.track_id == TRACK_CRASH && s.complete)
        {
            done = true;
            break;
        }
    }
    assert!(done, "resumed transfer must complete");
    assert_eq!(
        resumed
            .take_track(TRACK_CRASH)
            .expect("resumed track assembles"),
        data
    );
    // THE headline resume property: the network moved ONLY the gap.
    let st = &resumed.stats()[0];
    assert_eq!(
        st.data_rx_bytes,
        (40 - have_at_crash) as u64 * 1024,
        "resumed session must refetch exactly the missing chunks"
    );
    println!(
        "resume: crashed at {have_at_crash}/40 chunks, refetched only {}",
        40 - have_at_crash
    );
}

#[test]
fn global_window_backpressure_holds_for_the_entire_run() {
    // 5 nodes, clean links, aggressive pipelines (4 per peer × 4 peers
    // = 16 potential) vs a global cap of 5: at every tick boundary the
    // swarm must NEVER exceed 5 outstanding requests — and still finish.
    let data = pseudo_audio(24 * 1024);
    let mut mesh = SimMesh::new(
        5,
        0x5EED_0003,
        0.0,
        0.0,
        sim_params(1024, 4, 5, 0), // window 0: pure rarest-first
        TRACK,
        &data,
        0,
    );
    mesh.announce_origin(0);
    for _ in 0..600 {
        mesh.tick();
        // Backpressure invariant at every observation point: every
        // transmitted request is either still outstanding, or it
        // terminated as a stored chunk / duplicate / corruption /
        // timeout. (Late replies after a timeout can double-count a
        // disposition, which only ever drives the bound DOWN.)
        let st = mesh.stats_of(TRACK);
        let requests_tx: u64 = st.iter().map(|s| s.requests_tx).sum();
        let leecher_have: u64 = st
            .iter()
            .filter(|s| !s.seeder)
            .map(|s| s.have_chunks as u64)
            .sum();
        let duplicates: u64 = st.iter().map(|s| s.duplicate_chunks).sum();
        let corrupt: u64 = st.iter().map(|s| s.corrupt_chunks).sum();
        let timeouts: u64 = st.iter().map(|s| s.timeouts).sum();
        let outstanding = requests_tx as i64
            - leecher_have as i64
            - duplicates as i64
            - corrupt as i64
            - timeouts as i64;
        assert!(
            outstanding <= 5,
            "global window violated at tick {}: {} outstanding (tx={}, have={}, dup={}, corrupt={}, to={})",
            mesh.now / TICK_NS,
            outstanding,
            requests_tx,
            leecher_have,
            duplicates,
            corrupt,
            timeouts
        );
    }
    assert!(mesh.all_complete(TRACK), "must complete under backpressure");
    for (i, bytes) in mesh.take_all(TRACK).iter().enumerate() {
        assert_eq!(
            bytes.as_deref(),
            Some(data.as_slice()),
            "node[{i}] diverges"
        );
    }
}
