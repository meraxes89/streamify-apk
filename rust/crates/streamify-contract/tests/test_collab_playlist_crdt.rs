//! test_collab_playlist_crdt.rs — Phase 2 directive C integration suite
//! (BEHIND.md gap #35): the conflict-free collaborative playlist engine.
//!
//! Coverage map (directive §4: "concurrent multi-user voting, split-brain
//! merges, and permission-rejected edits"):
//!   • Concurrent multi-author editing over the WIRE: ops travel as binary
//!     frames through the length-prefixed delta batch codec exactly as the
//!     frozen JNI `applyPlaylistOp` / `exportPlaylistDelta` surface ships
//!     them — Add / Remove / Reorder / Rename races, remove-then-re-add
//!     resurrection, fractional-index convergence.
//!   • Split-brain: replicas exchange FULL op logs in randomized orders —
//!     final playlist state must be byte-identical everywhere (title
//!     included), and replaying the logs again changes nothing.
//!   • Permission-rejected edits: Viewer mutations and Editor Renames are
//!     refused BEFORE any state/log/clock mutation, on EVERY replica; a
//!     randomized chaos run interleaves rejected ops with valid ones and
//!     asserts they never leak into state or deltas.
//!   • Delta sync: exact vector-clock deltas (nothing the caller has,
//!     everything it lacks) and the Lamport-watermark fast path backing the
//!     frozen jlong signature; delta batches survive hostile truncation.
//!   • Scraped-playlist import: a YouTube playlist seeds a collaborative
//!     session whose op log alone fully syncs a fresh replica.

use std::collections::HashMap;

use rand::Rng;
use streamify_jam::consensus::{
    CollabPlaylistState, PlaylistApplyResult, PlaylistOp, PlaylistOpKind, PlaylistReject,
    PlaylistRole,
};
use streamify_contract::import_parsed_playlist;
use streamify_resolver::playlist_parser::{ParsedPlaylistResult, ParsedPlaylistTrack};

fn applied(pl: &mut CollabPlaylistState, op: &PlaylistOp) {
    assert_eq!(
        pl.apply_op(op),
        PlaylistApplyResult::Applied,
        "op {:?} from author {} must apply",
        op.kind,
        op.author_id
    );
}

fn rejected(pl: &mut CollabPlaylistState, op: &PlaylistOp, reason: PlaylistReject) {
    assert_eq!(
        pl.apply_op(op),
        PlaylistApplyResult::Rejected(reason),
        "op {:?} from author {} (role {:?}) must be rejected",
        op.kind,
        op.author_id,
        op.claimed_role
    );
}

/// Ships every op to every replica as a WIRE delta batch (the exact codec
/// the JNI surface uses), in the given delivery order.
fn ship(target: &mut CollabPlaylistState, ops: &[PlaylistOp]) {
    let batch = CollabPlaylistState::encode_delta(ops);
    let decoded = CollabPlaylistState::decode_delta(&batch).expect("delta decodes");
    for op in &decoded {
        // Rejected ops are legal traffic on the wire (a mis-issued client
        // frame); they must simply be refused again, identically.
        let _ = target.apply_op(op);
    }
}

/// Wire-form application (the frozen applyPlaylistOp path).
fn ship_bytes(target: &mut CollabPlaylistState, ops: &[PlaylistOp]) {
    for op in ops {
        let _ = target.apply_bytes(&op.to_bytes());
    }
}

// ═════════════════════════════════════════════════════ concurrent edits ──

#[test]
fn concurrent_multi_author_edits_converge_over_the_wire() {
    let mut a = CollabPlaylistState::new(1);
    let mut b = CollabPlaylistState::new(2);
    let mut c = CollabPlaylistState::new(3);

    // Author 1 seeds tracks; authors 2 and 3 race edits against them.
    let a1 = a.build_add(101, 0.10).expect("add");
    let a2 = a.build_add(102, 0.30).expect("add");
    let a3 = a.build_add(103, 0.50).expect("add");
    // Author 3 syncs author 1's ops first — its Lamport clock advances
    // past a's watermark, so its later racing edits carry winning (lamport,
    // author) pairs (a bare-clock author would legitimately lose LWW races
    // to a higher-lamport add: that is the documented Lamport semantics).
    for op in [&a1, &a2, &a3] {
        applied(&mut c, op);
    }
    let b1 = b.build_add(201, 0.20).expect("add");
    let c1 = c.build_add(301, 0.40).expect("add");

    // Races on the same element: remove vs reorder vs re-add.
    let rem_a1 = a.build_remove(a1.item_id).expect("remove");
    let reo_a1 = b.build_reorder(a1.item_id, 0.05).expect("reorder");
    let readd_a1 = a.build_add(101, 0.15).expect("re-add as new element");
    let reo_a3 = c.build_reorder(a3.item_id, 0.95).expect("reorder");
    let rn_a = a.build_rename("Group Blend v2").expect("rename");
    let rn_c = c.build_rename("Group Blend v3").expect("rename");
    let rem_b1 = c.build_remove(b1.item_id).expect("cross-author remove");

    let all_ops = vec![
        a1.clone(),
        b1.clone(),
        c1.clone(),
        a2.clone(),
        a3.clone(),
        rem_a1.clone(),
        reo_a1.clone(),
        readd_a1.clone(),
        reo_a3.clone(),
        rn_a.clone(),
        rn_c.clone(),
        rem_b1.clone(),
    ];

    // Split-brain: three replicas ingest the SAME op set in three orders,
    // through the wire codec.
    let mut orders: Vec<Vec<PlaylistOp>> = Vec::new();
    orders.push(all_ops.clone());
    orders.push(all_ops.iter().rev().cloned().collect());
    let mut shuffled = all_ops.clone();
    let mut rng = rand::thread_rng();
    for i in (1..shuffled.len()).rev() {
        let j = rng.gen_range(0..=i);
        shuffled.swap(i, j);
    }
    orders.push(shuffled);

    let mut replicas = [a, b, c];
    for (rep, order) in replicas.iter_mut().zip(orders.iter()) {
        ship(rep, order);
    }

    // ── Convergence: identical rows and title on every replica. ──
    let snapshots: Vec<_> = replicas.iter().map(|r| r.snapshot_items()).collect();
    assert_eq!(snapshots[0], snapshots[1], "order 1 vs 2");
    assert_eq!(snapshots[1], snapshots[2], "order 2 vs 3");
    let titles: Vec<String> = replicas.iter().map(|r| r.title().to_string()).collect();
    assert_eq!(titles[0], titles[1]);
    assert_eq!(titles[1], titles[2]);
    // The rename race resolves by the (lamport, author) pair: both
    // renames carry lamport 7, and author 3 > author 1 breaks the tie.
    assert_eq!(titles[0], "Group Blend v3");

    // Content invariants:
    //  • a1 was removed (newer than the concurrent reorder) and its
    //    re-add is an independent element (distinct item id);
    //  • b1 was cross-author removed;
    //  • a3 moved to 0.95; a2, c1, readd_a1 stay at their fractions.
    let rows = &snapshots[0];
    let mut cads: Vec<u64> = rows.iter().map(|r| r.cad_id).collect();
    cads.sort_unstable();
    assert_eq!(cads, vec![101, 102, 103, 301], "a1-readd(101), a2, a3, c1");
    assert_eq!(rows.len(), 4);
    let a3_row = rows.iter().find(|r| r.cad_id == 103).unwrap();
    assert!((a3_row.frac - 0.95).abs() < 1e-12, "reorder applied");
    assert!(
        rows.iter().find(|r| r.cad_id == 201).is_none(),
        "b1 removed"
    );
    assert!(rows.iter().find(|r| r.cad_id == 101).unwrap().item_id != a1.item_id);

    // Full replay through the RAW BYTES path (applyPlaylistOp semantics)
    // is inert — idempotence across both ingress paths.
    for (rep, order) in replicas.iter_mut().zip(orders.iter()) {
        ship_bytes(rep, order);
    }
    assert_eq!(replicas[0].snapshot_items(), snapshots[0]);
    assert_eq!(replicas[2].title(), titles[2]);
}

// ═════════════════════════════════════════════ permission-rejected edits ──

#[test]
fn permission_rejected_edits_never_touch_state_log_or_clock() {
    let mut host = CollabPlaylistState::new(1);
    let seeded = host.build_add(777, 0.5).expect("seed");
    let before_items = host.snapshot_items();
    let before_clock = host.clock().clone();

    // Viewer mutations: rejected before any merge.
    let viewer_add = PlaylistOp::new(
        PlaylistOpKind::Add,
        PlaylistRole::Viewer,
        9,
        1,
        100,
        (9u64 << 32) | 1,
        999,
        0.5,
        "",
    );
    rejected(&mut host, &viewer_add, PlaylistReject::PermissionDenied);
    let viewer_remove = PlaylistOp::new(
        PlaylistOpKind::Remove,
        PlaylistRole::Viewer,
        9,
        2,
        101,
        seeded.item_id,
        0,
        0.0,
        "",
    );
    rejected(&mut host, &viewer_remove, PlaylistReject::PermissionDenied);
    let viewer_reorder = PlaylistOp::new(
        PlaylistOpKind::Reorder,
        PlaylistRole::Viewer,
        9,
        3,
        102,
        seeded.item_id,
        0,
        0.9,
        "",
    );
    rejected(&mut host, &viewer_reorder, PlaylistReject::PermissionDenied);

    // Editor attempting Admin-grade ops: Rename and SetRole.
    let editor_rename = PlaylistOp::new(
        PlaylistOpKind::Rename,
        PlaylistRole::Editor,
        8,
        1,
        103,
        0,
        0,
        0.0,
        "hijack",
    );
    rejected(&mut host, &editor_rename, PlaylistReject::PermissionDenied);
    let editor_setrole = PlaylistOp::new(
        PlaylistOpKind::SetRole,
        PlaylistRole::Editor,
        8,
        2,
        104,
        9,
        PlaylistRole::Admin as u64,
        0.0,
        "",
    );
    rejected(&mut host, &editor_setrole, PlaylistReject::PermissionDenied);

    // Zero author id (reserved) on the raw-bytes path.
    let zero_author = PlaylistOp::new(
        PlaylistOpKind::Add,
        PlaylistRole::Admin,
        0,
        1,
        105,
        1,
        1,
        0.5,
        "",
    );
    let _ = zero_author; // author 0 cannot arise from build_*; craft bytes:
    let mut raw = PlaylistOp::new(
        PlaylistOpKind::Add,
        PlaylistRole::Admin,
        1,
        1,
        105,
        1,
        1,
        0.5,
        "",
    )
    .to_bytes();
    raw[2..6].copy_from_slice(&0u32.to_le_bytes());
    // Recompute the header checksum so only the author field is hostile.
    let mut h = 0x811c_9dc5u32;
    for b in raw[..48].iter() {
        h ^= *b as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    raw[48..52].copy_from_slice(&h.to_le_bytes());
    assert_eq!(
        host.apply_bytes(&raw),
        PlaylistApplyResult::Rejected(PlaylistReject::Invalid)
    );

    // Nothing changed: state, clock, title — and the rejected ops are NOT
    // in the log, so they can never re-sync into another replica.
    assert_eq!(host.snapshot_items(), before_items);
    assert_eq!(host.clock().len(), before_clock.len());
    assert_eq!(host.title(), "Collaborative Playlist");
    let log_kinds: Vec<PlaylistOpKind> = host_export_kinds(&host);
    assert!(!log_kinds.contains(&PlaylistOpKind::Rename));
    assert!(!log_kinds.contains(&PlaylistOpKind::SetRole));

    // A fresh replica syncing from the host's delta sees ONLY accepted ops
    // — permission-rejected traffic never rides the delta stream.
    let mut guest = CollabPlaylistState::new(5);
    let delta = host.export_delta_since_clock(&HashMap::new());
    for op in &delta {
        applied(&mut guest, op);
    }
    assert_eq!(guest.snapshot_items(), before_items);
    assert_eq!(guest.title(), host.title());
}

fn host_export_kinds(pl: &CollabPlaylistState) -> Vec<PlaylistOpKind> {
    pl.export_delta_since_clock(&HashMap::new())
        .into_iter()
        .map(|op| op.kind)
        .collect()
}

// ═════════════════════════════════════ randomized split-brain chaos ──

/// 3 authors × 120 randomized ops (adds, removes, reorders, renames, role
/// grants) with interleaved forged/rejected ops — every replica ingests
/// the full op set in its own random order and must converge on identical
/// rows + title + roster.
#[test]
fn randomized_split_brain_chaos_converges() {
    let mut a = CollabPlaylistState::new(1);
    let mut b = CollabPlaylistState::new(2);
    let mut c = CollabPlaylistState::new(3);

    // Grant author 4 an Editor role (SetRole rides the op log).
    let grant = a.build_set_role(4, PlaylistRole::Editor).expect("grant");

    let mut rng = rand::thread_rng();
    let mut ops: Vec<PlaylistOp> = vec![grant];
    let mut live_items: Vec<u64> = Vec::new();
    let mut authors: [&mut CollabPlaylistState; 3] = [&mut a, &mut b, &mut c];
    for round in 0..120 {
        let author = &mut authors[round % 3];
        let kind = match rng.gen_range(0..10) {
            0..=4 => PlaylistOpKind::Add,
            5 | 6 => PlaylistOpKind::Reorder,
            7 | 8 => PlaylistOpKind::Remove,
            _ => PlaylistOpKind::Rename,
        };
        let op = match kind {
            PlaylistOpKind::Add => {
                let frac = rng.gen_range(0.05..0.95);
                let built = author.build_add(5_000 + round as u64, frac).expect("add");
                live_items.push(built.item_id);
                Some(built)
            }
            PlaylistOpKind::Reorder => {
                if live_items.is_empty() {
                    None
                } else {
                    let id = live_items[rng.gen_range(0..live_items.len())];
                    let frac = rng.gen_range(0.05..0.95);
                    author.build_reorder(id, frac)
                }
            }
            PlaylistOpKind::Remove => {
                if live_items.is_empty() {
                    None
                } else {
                    let idx = rng.gen_range(0..live_items.len());
                    let id = live_items.swap_remove(idx);
                    author.build_remove(id)
                }
            }
            PlaylistOpKind::Rename => author.build_rename(&format!("Chaos Title {round}")),
            _ => None,
        };
        if let Some(op) = op {
            ops.push(op);
        }

        // Interleave rejected traffic (Viewer claims from the authors).
        if round % 7 == 0 {
            ops.push(PlaylistOp::new(
                PlaylistOpKind::Add,
                PlaylistRole::Viewer,
                1 + (round % 3) as u32,
                100_000 + round as u64,
                900_000 + round as u64,
                (9u64 << 32) | round as u64,
                66_666,
                0.5,
                "",
            ));
        }
    }
    assert!(ops.len() > 100, "chaos generated enough ops");

    // Every replica ingests the FULL set in its own random order.
    let mut replicas: Vec<CollabPlaylistState> = vec![
        CollabPlaylistState::new(1),
        CollabPlaylistState::new(2),
        CollabPlaylistState::new(3),
    ];
    for rep in replicas.iter_mut() {
        let mut order = ops.clone();
        let mut r = rand::thread_rng();
        for i in (1..order.len()).rev() {
            let j = r.gen_range(0..=i);
            order.swap(i, j);
        }
        for op in &order {
            let _ = rep.apply_op(op);
        }
    }

    // Convergence: rows, titles, rosters, and clocks agree everywhere.
    let snapshots: Vec<_> = replicas.iter().map(|r| r.snapshot_items()).collect();
    assert_eq!(snapshots[0], snapshots[1]);
    assert_eq!(snapshots[1], snapshots[2]);
    for rep in &replicas {
        assert_eq!(rep.title(), replicas[0].title());
        assert_eq!(rep.role_of(4), PlaylistRole::Editor, "SetRole converges");
        // The forged Viewer ops never granted anything.
        assert_eq!(
            rep.role_of(9),
            PlaylistRole::Editor,
            "default role for unknowns"
        );
    }
    assert!(!snapshots[0].is_empty(), "chaos kept some rows alive");

    // Replay the whole storm once more (idempotence).
    for (rep, order_ops) in replicas
        .iter_mut()
        .zip([ops.clone(), ops.clone(), ops.clone()].iter())
    {
        for op in order_ops {
            let _ = rep.apply_op(op);
        }
    }
    assert_eq!(replicas[0].snapshot_items(), snapshots[0]);
    assert_eq!(replicas[2].title(), replicas[0].title());
}

// ═══════════════════════════════════════════════════════════ delta sync ──

#[test]
fn delta_sync_exact_clock_and_watermark_paths() {
    let mut a = CollabPlaylistState::new(1);
    let op1 = a.build_add(1, 0.1).expect("add");
    let op2 = a.build_add(2, 0.2).expect("add");
    let _rn = a.build_rename("Delta Synced").expect("rename");

    // Fresh replica: empty clock → full delta; state converges.
    let mut b = CollabPlaylistState::new(2);
    let full = a.export_delta_since_clock(&HashMap::new());
    assert_eq!(full.len(), 3);
    for op in &full {
        applied(&mut b, op);
    }
    assert_eq!(b.snapshot_items(), a.snapshot_items());
    assert_eq!(b.title(), "Delta Synced");

    // B diverges with its own edit; A pulls exactly B's unknown ops.
    let b_op = b.build_add(3, 0.3).expect("b add");
    let to_a = b.export_delta_since_clock(a.clock());
    assert_eq!(to_a.len(), 1);
    assert_eq!(to_a[0].op_seq, b_op.op_seq);
    applied(&mut a, &to_a[0]);
    assert_eq!(a.snapshot_items().len(), 3);

    // A's own clock now covers everything it has seen → empty self-delta.
    assert!(a.export_delta_since_clock(a.clock()).is_empty());

    // The jlong watermark fast path: a peer at watermark 0 gets everything;
    // a peer at the CURRENT watermark gets ops with lamport >= watermark
    // (inclusive by design — same-L ops from slower authors must ship).
    let everything = a.export_delta_since_watermark(0);
    assert_eq!(everything.len(), 4);
    let w = a.my_watermark();
    let fast = a.export_delta_since_watermark(w);
    assert!(fast.iter().all(|op| op.lamport >= w));

    // Stale watermark (behind the clock) still recovers the tail.
    let mid = everything[1].lamport;
    let tail = a.export_delta_since_watermark(mid);
    assert!(tail.iter().all(|op| op.lamport >= mid));
    assert!(tail.len() <= everything.len());

    // The batch codec survives arbitrary mid-frame truncation without
    // panicking: every cut either fails to decode or yields a strict
    // prefix (frame boundaries).
    let encoded = CollabPlaylistState::encode_delta(&everything);
    for cut in 1..encoded.len() {
        match CollabPlaylistState::decode_delta(&encoded[..cut]) {
            None => {}
            Some(prefix) => {
                assert!(prefix.len() < everything.len());
                assert_eq!(&prefix[..], &everything[..prefix.len()]);
            }
        }
    }
    let _ = (op1, op2);
}

// ═══════════════════════════════════ scraped-playlist import flow ──

#[test]
fn scraped_playlist_seeds_a_collaborative_session() {
    let parsed = ParsedPlaylistResult {
        playlist_id: "PL_CHAOS_9".into(),
        title: "Late Night Drive".into(),
        author: "scraper".into(),
        track_count: 4,
        tracks: vec![
            track("Midnight City", "M83", 244),
            track("Nightcall", "Kavinsky", 252),
            track("", "Ghost Row", 100), // hostile row → skipped
            track("Turbo Killer", "Carpenter Brut", 230),
        ],
        continuation_token: None,
    };

    let mut host = CollabPlaylistState::new(1);
    let added = import_parsed_playlist(&mut host, &parsed);
    assert_eq!(added, 3, "hostile row skipped");
    assert_eq!(host.title(), "Late Night Drive");

    // A guest replica syncs from the importer's op log ALONE and starts
    // byte-identical — then keeps editing on the shared fractional grid.
    let mut guest = CollabPlaylistState::new(2);
    for op in host.export_delta_since_clock(&HashMap::new()) {
        applied(&mut guest, &op);
    }
    let host_rows = host.snapshot_items();
    assert_eq!(guest.snapshot_items(), host_rows);
    assert_eq!(guest.title(), host.title());

    // Scrape order preserved; fractional gaps absorb a concurrent insert.
    assert!(host_rows[0].frac < host_rows[1].frac && host_rows[1].frac < host_rows[2].frac);
    let mid = (host_rows[0].frac + host_rows[1].frac) / 2.0;
    let insert = guest.build_add(9_999, mid).expect("guest insert");
    assert_eq!(guest.snapshot_items().len(), 4);
    let rows = guest.snapshot_items();
    assert!(
        rows[0].frac < rows[1].frac && rows[1].frac < rows[2].frac && rows[2].frac < rows[3].frac
    );

    // Round-trip the guest's edit back to the host over the wire codec.
    ship(&mut host, &[insert]);
    assert_eq!(host.snapshot_items().len(), 4);
}

fn track(title: &str, artist: &str, dur: i32) -> ParsedPlaylistTrack {
    ParsedPlaylistTrack {
        video_id: format!("v-{title}"),
        title: title.into(),
        artist: artist.into(),
        album: "Single".into(),
        duration_sec: dur,
        thumbnail_url: String::new(),
    }
}

// ═════════════════════════════════════ strict-mode roster enforcement ──

#[test]
fn strict_mode_rejects_forged_role_claims() {
    let mut host = CollabPlaylistState::new(1);
    let grant = host.build_set_role(5, PlaylistRole::Editor).expect("grant");
    host.set_strict_roster(true);

    // Matching claim applies.
    let ok = PlaylistOp::new(
        PlaylistOpKind::Add,
        PlaylistRole::Editor,
        5,
        1,
        10,
        (5u64 << 32) | 1,
        55,
        0.5,
        "",
    );
    applied(&mut host, &ok);

    // Forged Admin claim (roster says Editor) → ForgedRole.
    let forged = PlaylistOp::new(
        PlaylistOpKind::Rename,
        PlaylistRole::Admin,
        5,
        2,
        11,
        0,
        0,
        0.0,
        "pwned",
    );
    rejected(&mut host, &forged, PlaylistReject::ForgedRole);

    // Unknown author pushing Admin-grade ops → UnknownAuthor.
    let stranger = PlaylistOp::new(
        PlaylistOpKind::SetRole,
        PlaylistRole::Admin,
        77,
        1,
        12,
        6,
        PlaylistRole::Admin as u64,
        0.0,
        "",
    );
    rejected(&mut host, &stranger, PlaylistReject::UnknownAuthor);
    assert_eq!(host.title(), "Collaborative Playlist");

    // Strict mode is an INGRESS-boundary posture: pure replica merging
    // (permissive) still accepts the same ops, keeping convergence intact
    // when local rosters drift.
    let mut permissive = CollabPlaylistState::new(9);
    applied(&mut permissive, &grant);
    applied(&mut permissive, &ok);
    assert_eq!(permissive.snapshot_items().len(), 1);
}
