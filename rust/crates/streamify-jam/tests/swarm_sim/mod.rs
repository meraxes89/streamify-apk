//! swarm_sim — deterministic in-memory swarm chaos harness (Phase 3).
//!
//! A pure-logic message router that wires N [`SwarmManager`]s together
//! with per-frame packet loss, payload corruption injection, link
//! partitions, and random peer drops — no sockets, no wall clock, no
//! flakiness: a seeded `StdRng` makes every chaos run byte-reproducible.
//! The 10-node / 20%-loss suite in `test_local_sync_chaos.rs` and the
//! partition suite in `test_chunk_swarmer.rs` both drive this harness.
//! Different test binaries exercise different harness subsets, so
//! dead-code analysis is per-binary and intentionally silenced.

#![allow(dead_code)]

use std::collections::{HashMap, VecDeque};

use rand::{Rng, SeedableRng};
use streamify_jam::chunk_swarmer::{SwarmAction, SwarmManager, SwarmParams, TrackSwarmStats};
use streamify_jam::p2p_mesh::{
    MSG_CHUNK_DATA, MSG_CHUNK_HAVE, MSG_CHUNK_REQUEST, MSG_TRACK_MANIFEST,
    MSG_TRACK_MANIFEST_REQUEST,
};

/// One simulated device: its swarm engine + its assembled track bytes.
pub struct SimNode {
    pub id: u64,
    pub mgr: SwarmManager,
}

/// Global chaos accounting (asserted by the suites).
#[derive(Debug, Default, Clone)]
pub struct SimStats {
    pub frames_dropped: u64,
    pub frames_corrupted: u64,
    pub chunk_data_frames: u64,
    pub peer_drop_events: u64,
    pub partition_events: u64,
}

pub struct SimMesh {
    pub nodes: Vec<SimNode>,
    rng: rand::rngs::StdRng,
    /// Per-frame drop probability on every link.
    pub loss: f64,
    /// Per-delivered CHUNK_DATA frame corruption probability.
    pub corrupt: f64,
    /// Directed link liveness (from, to) → up.
    links: HashMap<(u64, u64), bool>,
    pub stats: SimStats,
    /// Logical clock (ns); one tick = [`TICK_NS`].
    pub now: i64,
    /// The origin's seed-time fan-out, delivered on the first tick.
    seed_actions: Vec<SwarmAction>,
    queue: VecDeque<(u64, SwarmAction)>,
}

/// One logical tick (1 ms of simulated time).
pub const TICK_NS: i64 = 1_000_000;

impl SimMesh {
    /// `n` nodes (ids 0..n); `origin` seeds `track_id` from `data`.
    #[allow(clippy::too_many_arguments)] // one arg per sim-harness knob
    pub fn new(
        n: usize,
        seed: u64,
        loss: f64,
        corrupt: f64,
        params: SwarmParams,
        track_id: u64,
        data: &[u8],
        origin: usize,
    ) -> Self {
        assert!(origin < n);
        let mut nodes = Vec::with_capacity(n);
        let mut seed_actions = Vec::new();
        for i in 0..n as u64 {
            let mut mgr = SwarmManager::new(i, params.clone());
            if i as usize == origin {
                seed_actions = mgr.seed_track(track_id, data, 0, &[]);
            }
            nodes.push(SimNode { id: i, mgr });
        }
        let mut links = HashMap::new();
        for a in 0..n as u64 {
            for b in 0..n as u64 {
                if a != b {
                    links.insert((a, b), true);
                }
            }
        }
        SimMesh {
            nodes,
            rng: rand::rngs::StdRng::seed_from_u64(seed),
            loss,
            corrupt,
            links,
            stats: SimStats::default(),
            now: 0,
            seed_actions,
            queue: VecDeque::new(),
        }
    }

    /// Delivers the origin's seed-time manifest broadcast + HAVE unicast
    /// (call once before the first `tick`).
    pub fn announce_origin(&mut self, origin: usize) {
        let from = origin as u64;
        for action in self.seed_actions.drain(..) {
            self.queue.push_back((from, action));
        }
        self.drain_queue();
    }

    fn link_up(&self, from: u64, to: u64) -> bool {
        self.links.get(&(from, to)).copied().unwrap_or(false)
    }

    fn alive_peers(&self, of: u64) -> Vec<u64> {
        (0..self.nodes.len() as u64)
            .filter(|&p| p != of && self.link_up(of, p))
            .collect()
    }

    /// Deterministic per-frame loss draw.
    fn frame_survives(&mut self) -> bool {
        self.rng.gen::<f64>() >= self.loss
    }

    /// Drops ALL links of `node` and tells every other engine the peer
    /// is gone (mirrors the mesh's `sweep_expired_peers` path).
    pub fn drop_peer(&mut self, node: usize) {
        let id = node as u64;
        for other in 0..self.nodes.len() as u64 {
            if other != id {
                self.links.insert((id, other), false);
                self.links.insert((other, id), false);
            }
        }
        for n in self.nodes.iter_mut() {
            if n.id != id {
                n.mgr.on_peer_down(id);
            }
        }
        self.stats.peer_drop_events += 1;
    }

    /// Restores ALL links of `node`; every other engine greets it via
    /// `on_peer_up` (fresh HAVE announcements bootstrap the rejoin).
    pub fn restore_peer(&mut self, node: usize) {
        let id = node as u64;
        let peers: Vec<u64> = (0..self.nodes.len() as u64).filter(|&p| p != id).collect();
        for &other in &peers {
            self.links.insert((id, other), true);
            self.links.insert((other, id), true);
        }
        for i in 0..self.nodes.len() {
            if self.nodes[i].id != id {
                let actions = self.nodes[i].mgr.on_peer_up(id, self.now, &peers);
                self.enqueue(self.nodes[i].id, actions);
            }
        }
        self.drain_queue();
    }

    /// Splits the mesh into two halves (cross links down).
    pub fn partition(&mut self, a: &[usize], b: &[usize]) {
        for &x in a {
            for &y in b {
                self.links.insert((x as u64, y as u64), false);
                self.links.insert((y as u64, x as u64), false);
            }
        }
        self.stats.partition_events += 1;
    }

    /// Heals a partition: all links up. Engines recover through their
    /// own timeout-driven re-requests and the HAVE re-announce cadence —
    /// no external nudging, which is exactly what we want to prove.
    pub fn heal_partition(&mut self) {
        let n = self.nodes.len() as u64;
        for a in 0..n {
            for b in 0..n {
                if a != b {
                    self.links.insert((a, b), true);
                }
            }
        }
    }

    fn enqueue(&mut self, from: u64, actions: Vec<SwarmAction>) {
        for a in actions {
            self.queue.push_back((from, a));
        }
    }

    /// Routes one frame from `from` to `to`, applying link state, packet
    /// loss, and (for CHUNK_DATA) corruption injection. Returns the
    /// destination engine's reply actions.
    fn deliver_frame(
        &mut self,
        from: u64,
        to: u64,
        msg_type: u8,
        payload: &[u8],
    ) -> Vec<SwarmAction> {
        if !self.link_up(from, to) {
            self.stats.frames_dropped += 1;
            return Vec::new();
        }
        if !self.frame_survives() {
            self.stats.frames_dropped += 1;
            return Vec::new();
        }
        let mut payload = payload.to_vec();
        if msg_type == MSG_CHUNK_DATA {
            self.stats.chunk_data_frames += 1;
            if self.rng.gen::<f64>() < self.corrupt && payload.len() > 20 {
                // Flip one byte inside the chunk body (past the 16-byte
                // chunk header) — the Blake3 gate must catch downstream.
                let at = 16 + (payload.len() - 17) / 2;
                payload[at] ^= 0xA5;
                self.stats.frames_corrupted += 1;
            }
        }
        let peers = self.alive_peers(to);
        let node = &mut self.nodes[to as usize];
        match msg_type {
            MSG_TRACK_MANIFEST => node.mgr.on_manifest(&payload, from, self.now),
            MSG_TRACK_MANIFEST_REQUEST => node.mgr.on_manifest_request(&payload, from, self.now),
            MSG_CHUNK_HAVE => node.mgr.on_have(&payload, from, self.now),
            MSG_CHUNK_REQUEST => node.mgr.on_chunk_request(&payload, from, self.now),
            MSG_CHUNK_DATA => node.mgr.on_chunk_data(&payload, from, self.now, &peers),
            _ => Vec::new(),
        }
    }

    /// Drains the frame queue to quiescence (deterministic FIFO order).
    fn drain_queue(&mut self) {
        let mut guard = 0usize;
        while let Some((from, action)) = self.queue.pop_front() {
            match action {
                SwarmAction::Unicast {
                    to,
                    msg_type,
                    payload,
                } => {
                    let replies = self.deliver_frame(from, to, msg_type, &payload);
                    for r in replies {
                        self.queue.push_back((to, r));
                    }
                }
                SwarmAction::Broadcast { msg_type, payload } => {
                    for to in 0..self.nodes.len() as u64 {
                        if to != from {
                            let replies = self.deliver_frame(from, to, msg_type, &payload);
                            for r in replies {
                                self.queue.push_back((to, r));
                            }
                        }
                    }
                }
            }
            guard += 1;
            assert!(guard < 200_000, "sim queue did not quiesce (routing loop?)");
        }
    }

    /// Runs ONE tick: every engine housekeeps (timeouts, announces,
    /// pipeline fills), then the frame queue drains to quiescence.
    pub fn tick(&mut self) {
        self.now += TICK_NS;
        for i in 0..self.nodes.len() {
            let me = self.nodes[i].id;
            let peers = self.alive_peers(me);
            let actions = self.nodes[i].mgr.on_tick(self.now, &peers);
            self.enqueue(me, actions);
        }
        self.drain_queue();
    }

    /// True once every node holds a verified, complete copy.
    pub fn all_complete(&self, track_id: u64) -> bool {
        self.nodes.iter().all(|n| {
            n.mgr
                .stats()
                .iter()
                .any(|s| s.track_id == track_id && s.complete)
        })
    }

    /// Every node's per-track stats (same order as node ids).
    pub fn stats_of(&self, track_id: u64) -> Vec<TrackSwarmStats> {
        self.nodes
            .iter()
            .filter_map(|n| n.mgr.stats().into_iter().find(|s| s.track_id == track_id))
            .collect()
    }

    /// Runs up to `max_ticks` ticks; `Some(ticks)` on completion.
    pub fn run_until_complete(&mut self, track_id: u64, max_ticks: usize) -> Option<usize> {
        for t in 1..=max_ticks {
            self.tick();
            if self.all_complete(track_id) {
                return Some(t);
            }
        }
        None
    }

    /// Each node's assembled track (None if not complete).
    pub fn take_all(&mut self, track_id: u64) -> Vec<Option<Vec<u8>>> {
        self.nodes
            .iter_mut()
            .map(|n| n.mgr.take_track(track_id))
            .collect()
    }
}

/// Fast, small-chunk parameter set for the sims.
pub fn sim_params(chunk: usize, pipeline: usize, max_total: usize, window: u32) -> SwarmParams {
    SwarmParams {
        chunk_size: chunk,
        pipeline_per_peer: pipeline,
        request_timeout: std::time::Duration::from_millis(20),
        announce_interval: std::time::Duration::from_millis(5),
        endgame_threshold: 2,
        endgame_dup_peers: 2,
        sequential_window: window,
        max_total_inflight: max_total,
    }
}

/// Deterministic pseudo-audio bytes (formulaic — no RNG dependency).
pub fn pseudo_audio(len: usize) -> Vec<u8> {
    (0..len).map(|i| ((i * 31 + 7) % 251) as u8).collect()
}
