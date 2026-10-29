//! test_wear_sync.rs — Phase 4 WearOS sync integration suite
//! (directive §2.5).
//!
//! Bit-level serialization round-trips for the watch queue and voting
//! payloads (the WSX wire family), compactness budgets, and a full
//! duplex phone↔watch session over a LOSSY (20%) link: queue mirror
//! convergence, watch-originated votes/ratings surviving packet loss
//! via the reliable mini-protocol, and the offline cache completing
//! with end-to-end byte equivalence.

use rand::{Rng, SeedableRng};

use streamify_jam::wear_sync::{
    decode_jam_upvote, decode_queue_action, decode_queue_delta, decode_track_rating, encode_frame,
    encode_frame_into, encode_jam_upvote, encode_queue_action, encode_queue_delta,
    encode_track_rating, parse_frame, JamUpvoteMsg, PhoneSyncCoordinator, QueueOp, TrackRating,
    WatchSyncCoordinator, WearSyncAction, WearSyncConfig, CTRL_SEG_BAD, FLAG_ACK_REQUESTED,
    MAX_QUEUE_DELTA_ENTRIES, SEGMENT_SIZE, WSX_CACHE_CHUNK, WSX_CACHE_CONTROL, WSX_JAM_UPVOTE,
    WSX_QUEUE_ACTION, WSX_QUEUE_DELTA, WSX_TRACK_RATING,
};

// ─────────────────────────────────────────────── bit-level round-trips

#[test]
fn queue_payloads_roundtrip_bit_level() {
    // Exhaustive op grammar across magnitudes.
    let ops = vec![
        QueueOp::Add {
            cad_id: 0,
            after_pos: 0,
        },
        QueueOp::Add {
            cad_id: u64::MAX,
            after_pos: u32::MAX,
        },
        QueueOp::Add {
            cad_id: 300,
            after_pos: 7,
        },
        QueueOp::Remove { index: 0 },
        QueueOp::Remove { index: u32::MAX },
        QueueOp::Clear,
        QueueOp::Move { from: 0, to: 1 },
        QueueOp::Move {
            from: u32::MAX,
            to: u32::MAX,
        },
    ];
    let mut buf = [0u8; 256];
    let n = encode_queue_delta(&ops, &mut buf).expect("encode");
    assert_eq!(decode_queue_delta(&buf[..n]), Some(ops.clone()));
    for op in &ops {
        let m = encode_queue_action(op, &mut buf).expect("encode");
        assert_eq!(decode_queue_action(&buf[..m]), Some(*op));
    }
    // Compaction budget: the exhaustive grammar (u64::MAX cad → 10-B
    // varint, u32::MAX positions → 5-B varints) lands ≤ 55 B where
    // fixed-width would need 8×(8+4)=96 B.
    assert!(n <= 55, "8-entry worst-case delta payload = {n} B");

    // A typical batch: 8 small adds (cads ~260, positions < 8) in ≤ 30 B
    // (fixed-width ≈ 96 B — varints + 2-bit op packing shrink ~3×).
    let typical: Vec<QueueOp> = (0..8u64)
        .map(|i| QueueOp::Add {
            cad_id: 0x100 + i,
            after_pos: i as u32,
        })
        .collect();
    let n = encode_queue_delta(&typical, &mut buf).expect("encode");
    assert!(n <= 30, "typical 8-add delta payload = {n} B");
    assert_eq!(decode_queue_delta(&buf[..n]), Some(typical));

    // Max batch round-trips.
    let max_batch: Vec<QueueOp> = (0..MAX_QUEUE_DELTA_ENTRIES as u32)
        .map(|i| QueueOp::Remove { index: i })
        .collect();
    let n = encode_queue_delta(&max_batch, &mut buf).expect("encode");
    assert_eq!(decode_queue_delta(&buf[..n]), Some(max_batch));
}

#[test]
fn rating_payload_roundtrip_bit_level() {
    for rating in 1u8..=5 {
        for thumbs in [false, true] {
            for cad in [0u64, 1, 0xDEAD_BEEF, u64::MAX] {
                let r = TrackRating {
                    cad_id: cad,
                    rating,
                    thumbs_up: thumbs,
                };
                let mut buf = [0u8; 16];
                let n = encode_track_rating(&r, &mut buf).expect("encode");
                assert_eq!(decode_track_rating(&buf[..n]), Some(r));
                // cad varint (≤ 10 worst case) + ONE packed byte.
                assert!(n <= 11, "rating payload = {n} B");
            }
        }
    }
    // Typical small cad: the whole rating rides in 3 B.
    let mut buf = [0u8; 16];
    let n = encode_track_rating(
        &TrackRating {
            cad_id: 42,
            rating: 5,
            thumbs_up: true,
        },
        &mut buf,
    )
    .unwrap();
    assert!(n <= 3, "typical rating payload = {n} B");
    // Rating 0 refused at both boundaries.
    let mut buf = [0u8; 16];
    assert_eq!(
        encode_track_rating(
            &TrackRating {
                cad_id: 1,
                rating: 0,
                thumbs_up: false
            },
            &mut buf
        ),
        None
    );
    assert_eq!(decode_track_rating(&[0x01, 0x00]), None);
}

#[test]
fn jam_upvote_payload_roundtrip_bit_level() {
    for nonce in [[0u8; 4], [1, 2, 3, 4], [0xFF; 4]] {
        for up in [false, true] {
            let u = JamUpvoteMsg {
                cad_id: 0x1234_5678,
                voter_nonce: nonce,
                up,
            };
            let mut buf = [0u8; 24];
            let n = encode_jam_upvote(&u, &mut buf).expect("encode");
            assert_eq!(decode_jam_upvote(&buf[..n]), Some(u));
            // cad varint (≤5) + nonce (4) + flag (1) ≤ 10 B.
            assert!(n <= 10, "upvote payload = {n} B");
        }
    }
    // Truncated at every cut point → refused.
    let mut buf = [0u8; 24];
    let n = encode_jam_upvote(
        &JamUpvoteMsg {
            cad_id: 9,
            voter_nonce: [7; 4],
            up: true,
        },
        &mut buf,
    )
    .unwrap();
    for cut in 0..n {
        assert_eq!(decode_jam_upvote(&buf[..cut]), None, "cut at {cut}");
    }
}

#[test]
fn frame_header_carries_flags_and_seq() {
    let mut buf = [0u8; 128];
    let n = encode_frame_into(
        &mut buf,
        WSX_QUEUE_DELTA,
        0xBEEF,
        FLAG_ACK_REQUESTED,
        &[1, 2, 3],
    )
    .expect("encode");
    let f = parse_frame(&buf[..n]).expect("parse");
    assert_eq!(f.seq, 0xBEEF);
    assert_eq!(f.flags, FLAG_ACK_REQUESTED);
    assert_eq!(f.frame_type, WSX_QUEUE_DELTA);
    assert_eq!(f.payload, &[1, 2, 3]);
}

// ─────────────────────────────────────────────── lossy duplex session

/// Lossy, reordering duplex link between phone and watch (seeded —
/// byte-reproducible chaos).
struct LossyLink {
    loss: f64,
    rng: rand::rngs::StdRng,
    /// (deliver_at_step, to_phone, frame)
    wire: Vec<(usize, bool, Vec<u8>)>,
    dropped: usize,
    delivered: usize,
    corrupted: usize,
}

impl LossyLink {
    fn new(seed: u64, loss: f64) -> Self {
        LossyLink {
            loss,
            rng: rand::rngs::StdRng::seed_from_u64(seed),
            wire: Vec::new(),
            dropped: 0,
            delivered: 0,
            corrupted: 0,
        }
    }

    fn send(&mut self, to_phone: bool, frame: Vec<u8>, step: usize) {
        if self.rng.gen_bool(self.loss) {
            self.dropped += 1;
            return;
        }
        // Reordering window: 0..=3 steps of delay.
        let delay = self.rng.gen_range(0..=3usize);
        self.wire.push((step + delay, to_phone, frame));
    }

    /// Delivers due frames (with a 1% mid-flight corruption).
    fn pump(
        &mut self,
        phone: &mut PhoneSyncCoordinator,
        watch: &mut WatchSyncCoordinator,
        step: usize,
    ) {
        let mut i = 0;
        while i < self.wire.len() {
            let due = self.wire[i].0 <= step;
            if due {
                let (_, to_phone, mut frame) = self.wire.remove(i);
                if self.rng.gen_bool(0.01) {
                    // Flip a payload byte WITHOUT fixing the checksum —
                    // the boundary must reject it outright.
                    let flip = 9 + (self.rng.gen_range(0..frame.len().saturating_sub(13)));
                    if flip < frame.len() {
                        frame[flip] ^= 0x80;
                    }
                    self.corrupted += 1;
                }
                if to_phone {
                    phone.on_frame(&frame, step as u64 * 10);
                } else {
                    watch.on_frame(&frame, step as u64 * 10);
                }
                self.delivered += 1;
            } else {
                i += 1;
            }
        }
    }
}

fn fast_config() -> WearSyncConfig {
    WearSyncConfig {
        bytes_per_second: 8 * 1024 * 1024,
        burst_bytes: 64 * 1024,
        rto_ms: 150, // chaos-cadence RTO (15 sim steps)
        ..WearSyncConfig::default()
    }
}

fn pseudo_track(seed: u8, size: usize) -> Vec<u8> {
    (0..size)
        .map(|i| {
            seed.wrapping_add((i % 241) as u8)
                .wrapping_mul(5)
                .wrapping_add(11)
        })
        .collect()
}

#[test]
fn full_duplex_sync_survives_20pct_loss_and_reordering() {
    let mut phone = PhoneSyncCoordinator::new(fast_config());
    let mut watch = WatchSyncCoordinator::new();
    let mut link = LossyLink::new(0x5EED_0A7C, 0.20);

    // ── phone → watch: queue delta mirror ──
    assert!(phone.push_queue_delta(&[
        QueueOp::Add {
            cad_id: 101,
            after_pos: 0
        },
        QueueOp::Add {
            cad_id: 202,
            after_pos: 1
        },
        QueueOp::Add {
            cad_id: 303,
            after_pos: 2
        },
        QueueOp::Add {
            cad_id: 404,
            after_pos: 3
        },
    ]));

    // ── watch → phone: edit + rating + upvote (before the cache starts,
    //    so the lossy mini-protocol alone carries them) ──
    assert!(watch.send_queue_action(&QueueOp::Add {
        cad_id: 505,
        after_pos: 1
    }));
    assert!(watch.send_rating(&TrackRating {
        cad_id: 202,
        rating: 5,
        thumbs_up: true
    }));
    assert!(watch.send_jam_upvote(&JamUpvoteMsg {
        cad_id: 303,
        voter_nonce: [9, 9, 1, 7],
        up: true
    }));

    // ── offline cache: two sizable tracks (50 + 30 segments → enough
    //    wire traffic for the 20% loss to bite hard) ──
    let t1 = pseudo_track(3, 50 * SEGMENT_SIZE);
    let t2 = pseudo_track(8, 30 * SEGMENT_SIZE - 13);
    assert!(phone.start_cache_push(7, &[(0xA11CE, &t1), (0xB00B, &t2)], 0));

    // Pump until the phone reports the push complete.
    let mut step = 0usize;
    loop {
        step += 1;
        let now = step as u64 * 10;
        phone.tick(now);
        for WearSyncAction::Send { frame } in phone.drain_actions() {
            link.send(false, frame, step);
        }
        watch.tick();
        for WearSyncAction::Send { frame } in watch.drain_actions() {
            link.send(true, frame, step);
        }
        link.pump(&mut phone, &mut watch, step);
        if phone.cache_complete() && step > 40 {
            break;
        }
        assert!(
            step < 8_000,
            "must converge under 20% loss; phone {:?} watch {:?} link {:?}",
            phone.stats(),
            watch.stats(),
            (link.dropped, link.delivered)
        );
    }
    // Grace pump: control-frame retries (a lost vote/rating/edit rides
    // the reliable mini-protocol) get their RTO window after completion.
    for _ in 0..2_000 {
        step += 1;
        let now = step as u64 * 10;
        phone.tick(now);
        for WearSyncAction::Send { frame } in phone.drain_actions() {
            link.send(false, frame, step);
        }
        watch.tick();
        for WearSyncAction::Send { frame } in watch.drain_actions() {
            link.send(true, frame, step);
        }
        link.pump(&mut phone, &mut watch, step);
    }

    // Chaos actually happened.
    assert!(
        link.dropped > 15,
        "packet loss must have fired: {}",
        link.dropped
    );
    assert!(
        link.corrupted >= 1,
        "corruption injection must have fired: {}",
        link.corrupted
    );

    // Queue mirror converged. The watch's own edit (insert AT index 1)
    // landed while the mirror was still empty, then the phone's delta
    // inserted around it — final order per the insert-at-index grammar:
    assert_eq!(watch.queue_mirror(), &[101, 202, 303, 404, 505]);

    // Phone received the watch's edit + rating + upvote (the reliable
    // mini-protocol redelivered whatever 20% of the frames swallowed).
    let events = phone.drain_events();
    assert!(events.iter().any(|e| matches!(
        e,
        streamify_jam::wear_sync::WearSyncEvent::QueueActionReceived {
            op: QueueOp::Add {
                cad_id: 505,
                after_pos: 1
            }
        }
    )));
    assert!(events.iter().any(|e| matches!(
        e,
        streamify_jam::wear_sync::WearSyncEvent::RatingReceived {
            rating: TrackRating {
                cad_id: 202,
                rating: 5,
                thumbs_up: true
            }
        }
    )));
    assert!(events.iter().any(|e| matches!(
        e,
        streamify_jam::wear_sync::WearSyncEvent::JamUpvoteReceived {
            upvote: JamUpvoteMsg {
                cad_id: 303,
                voter_nonce: [9, 9, 1, 7],
                up: true
            }
        }
    )));

    // Byte-equivalence end-to-end on BOTH tracks.
    assert_eq!(watch.take_track(0xA11CE), Some(t1));
    assert_eq!(watch.take_track(0xB00B), Some(t2));
    assert_eq!(watch.stats().tracks_completed, 2);

    // Every corrupted frame was rejected at the boundary (checksum) and
    // never double-counted as a verified segment.
    assert!(phone.stats().frames_rejected + watch.stats().frames_rejected >= link.corrupted as u64);
}

#[test]
fn vote_never_vanishes_under_sustained_loss() {
    // 30% loss, watch-originated ack_requested frames only — every
    // rating/upvote must reach the phone via bounded retries.
    let mut phone = PhoneSyncCoordinator::new(WearSyncConfig::default());
    let mut watch = WatchSyncCoordinator::new();
    let mut link = LossyLink::new(0xFACE_B00C, 0.30);

    for i in 0..10u64 {
        assert!(watch.send_rating(&TrackRating {
            cad_id: 0x900 + i,
            rating: 3,
            thumbs_up: true
        }));
        assert!(watch.send_jam_upvote(&JamUpvoteMsg {
            cad_id: 0xA00 + i,
            voter_nonce: [i as u8; 4],
            up: true
        }));
    }
    let mut got_ratings = 0usize;
    let mut got_upvotes = 0usize;
    let mut step = 0usize;
    loop {
        step += 1;
        watch.tick();
        for WearSyncAction::Send { frame } in watch.drain_actions() {
            link.send(true, frame, step);
        }
        link.pump(&mut phone, &mut watch, step);
        // CUMULATIVE event counting (drain_events is per-iteration).
        for ev in phone.drain_events() {
            match ev {
                streamify_jam::wear_sync::WearSyncEvent::RatingReceived { .. } => {
                    got_ratings += 1
                }
                streamify_jam::wear_sync::WearSyncEvent::JamUpvoteReceived { .. } => {
                    got_upvotes += 1
                }
                _ => {}
            }
        }
        // Phone ACKs flow back so the watch retires its pendings.
        for WearSyncAction::Send { frame } in phone.drain_actions() {
            link.send(false, frame, step);
        }
        if got_ratings == 10 && got_upvotes == 10 {
            break;
        }
        assert!(
            step < 4_000,
            "votes must survive 30% loss; ratings={got_ratings} upvotes={got_upvotes} watch {:?}",
            watch.stats()
        );
    }
    assert!(link.dropped > 10, "loss fired: {}", link.dropped);
    assert!(
        watch.stats().control_retries >= 1,
        "retries must have fired"
    );
}

#[test]
fn corrupt_cache_segment_heals_via_priority_retransmit_under_loss() {
    // 20% loss + a deterministic corruption of the FIRST chunk frame.
    let mut phone = PhoneSyncCoordinator::new(fast_config());
    let mut watch = WatchSyncCoordinator::new();
    let mut link = LossyLink::new(0xC0FF_EE01, 0.20);
    let track = pseudo_track(6, 4 * SEGMENT_SIZE);
    assert!(phone.start_cache_push(3, &[(0xFEED, &track)], 0));

    let mut corrupted_once = false;
    let mut seg_bad_seen = false;
    let mut step = 0usize;
    loop {
        step += 1;
        let now = step as u64 * 10;
        phone.tick(now);
        for WearSyncAction::Send { frame } in phone.drain_actions() {
            let mut frame = frame;
            if !corrupted_once && frame.len() > 3 && frame[3] == WSX_CACHE_CHUNK {
                // Corrupt one DATA byte and RE-FIX the FNV checksum —
                // only the per-segment Blake3 can catch this.
                let flip = frame.len() - 6;
                frame[flip] ^= 0x01;
                let sum = {
                    let mut h: u32 = 0x811c_9dc5;
                    for &b in &frame[..frame.len() - 4] {
                        h ^= b as u32;
                        h = h.wrapping_mul(0x0100_0193);
                    }
                    h
                };
                let e = frame.len() - 4;
                frame[e..e + 4].copy_from_slice(&sum.to_le_bytes());
                corrupted_once = true;
            }
            link.send(false, frame, step);
        }
        watch.tick();
        for WearSyncAction::Send { frame } in watch.drain_actions() {
            if frame.len() > 9 && frame[3] == WSX_CACHE_CONTROL && frame[9] == CTRL_SEG_BAD {
                seg_bad_seen = true; // CTRL_SEG_BAD observed on the wire
            }
            link.send(true, frame, step);
        }
        link.pump(&mut phone, &mut watch, step);
        if phone.cache_complete() && step > 40 {
            break;
        }
        assert!(
            step < 8_000,
            "must heal corruption under loss; phone {:?} watch {:?}",
            phone.stats(),
            watch.stats()
        );
    }
    assert!(corrupted_once);
    assert!(seg_bad_seen, "watch must have raised SEG_BAD");
    assert_eq!(watch.stats().segs_corrupt, 1);
    // Byte-equivalence despite loss + injected corruption.
    assert_eq!(watch.take_track(0xFEED), Some(track));
    assert!(phone.stats().segs_retransmitted >= 1);
}

// Wire-type discriminants referenced by number above are cross-checked
// against the frozen registry here, so a silent renumber fails loudly.
#[test]
fn wire_type_registry_is_frozen() {
    assert_eq!(WSX_QUEUE_DELTA, 0x01);
    assert_eq!(WSX_QUEUE_ACTION, 0x02);
    assert_eq!(WSX_TRACK_RATING, 0x03);
    assert_eq!(WSX_JAM_UPVOTE, 0x04);
    // 0x05 CACHE_MANIFEST · 0x06 CACHE_ACK · 0x07 CACHE_CHUNK ·
    // 0x08 CACHE_CONTROL · 0x09 SYNC_ACK
    let probe = encode_frame(WSX_TRACK_RATING, 1, 0, &[0x01, 0x05]);
    assert_eq!(parse_frame(&probe).unwrap().frame_type, WSX_TRACK_RATING);
}
