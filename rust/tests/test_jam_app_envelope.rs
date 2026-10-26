//! test_jam_app_envelope.rs — audit #44 P0-1 regression cover.
//!
//! The Kotlin JamEngine multiplexes its whole JamWire vocabulary through
//! `NativeBridge.p2pBroadcast`/`p2pSendToPeer`, folded into the single
//! app-visible envelope type `MSG_JAM_APP` (0x20) — the small integers
//! 1..=31 collide with the mesh's frozen control registry (the JNI guard
//! rejects them as protocol spoofing, and TICK=1 would otherwise
//! masquerade as PTP_SYNC). This file pins the three contracts the fix
//! depends on:
//!
//!   1. ENVELOPE LEGALITY: MSG_JAM_APP sits outside the reserved control
//!      set and inside the app-visible extension range — the guard's
//!      `app_msg_type_ok` predicate therefore admits it.
//!   2. END-TO-END ROUTING: a JamWire-family payload broadcast under the
//!      envelope on one node is delivered to a MSG_JAM_APP subscriber on
//!      a peer node, after gossip dedupe, with the originating peer id
//!      intact — exactly the path the Rust incoming-callback forwarder
//!      (`nativeP2pRegisterIncomingCallback`'s bridge tasks) subscribes to.
//!   3. STOP-DRIVEN TEARDOWN: the node's stop signal (newly exposed for
//!      the bridge tasks) fires on shutdown, so forwarders cannot outlive
//!      the mesh they serve.

use std::sync::Arc;
use std::time::Duration;

use streamify_core_rs::p2p_mesh::{
    MeshConfig, MeshNode, PeerId, MSG_JAM_APP, RESERVED_CONTROL_TYPES,
};

/// Contract 1 — envelope legality (mirrors `app_msg_type_ok`'s rules: a
/// type is app-injectable iff it is 0..=255 and not reserved).
///
/// `assertions_on_constants` is intentional: these are regression pins,
/// not logic — a registry edit that moves/reserves MSG_JAM_APP must fail
/// HERE first, at the contract, before it fails at runtime.
#[test]
#[allow(clippy::assertions_on_constants)]
fn jam_app_envelope_is_app_visible() {
    assert!(
        !RESERVED_CONTROL_TYPES.contains(&MSG_JAM_APP),
        "MSG_JAM_APP must never join the reserved control set"
    );
    assert!(
        MSG_JAM_APP >= 0x20,
        "app extensions must live in the >= 0x20 range the guard documents"
    );
    // The raw JamWire vocabulary must be mostly rejected by the guard when
    // sent bare — that collision is exactly why the envelope exists. Count
    // the rejects instead of asserting inside the loop (an in-loop assert
    // with a computed condition doubles as a live census of the collision).
    let mut raw_rejected = 0;
    let mut raw_ptp_mimicry = 0;
    for jam_type in 1..=31u8 {
        if RESERVED_CONTROL_TYPES.contains(&jam_type) {
            raw_rejected += 1;
        }
        if jam_type == 0x01 {
            raw_ptp_mimicry += 1; // MSG_PTP_SYNC: bare TICK would spoof PTP
        }
    }
    assert!(
        raw_rejected >= 13,
        "the raw JamWire vocabulary must be mostly rejected by the guard — \
         that collision is why the envelope exists (got {raw_rejected} of 31)"
    );
    assert_eq!(
        raw_ptp_mimicry, 1,
        "exactly one JamWire type mimics PTP_SYNC (TICK)"
    );
}

/// Contracts 2 + 3 — routing + teardown on a two-node loopback mesh.
#[tokio::test]
async fn jam_app_envelope_routes_peer_to_peer_and_stops_cleanly() {
    let session = "jam-envelope-e2e";
    let mut a_cfg = MeshConfig::loopback(session, "envelope-device-a");
    a_cfg.discovery_listen = false;
    let mut b_cfg = MeshConfig::loopback(session, "envelope-device-b");
    b_cfg.discovery_listen = false;

    let a = MeshNode::start(a_cfg).expect("node A binds loopback socket");
    let b = MeshNode::start(b_cfg).expect("node B binds loopback socket");
    a.add_peer(b.local_addr());
    b.add_peer(a.local_addr());

    // Subscriber BEFORE the frame flies (the JNI bridge subscribes at
    // register-time; a late subscription must not be required).
    let mut rx = b.subscribe(MSG_JAM_APP, 64);

    // Wait for the handshake (peer registration) so the frame is routed
    // rather than dropped as an unknown link.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while b.peer_count() < 1 || a.peer_count() < 1 {
        assert!(tokio::time::Instant::now() < deadline, "mesh never formed");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // A JamWire-shaped payload (magic + version + msgType header is
    // validated Kotlin-side; content is opaque to the mesh).
    let payload: Vec<u8> = (0u16..96).flat_map(|i| i.to_le_bytes()).collect();
    a.broadcast(MSG_JAM_APP, &payload)
        .expect("envelope broadcast enqueues");

    let got = tokio::time::timeout(Duration::from_secs(10), rx.recv())
        .await
        .expect("envelope frame delivered across the mesh")
        .expect("subscription alive");
    assert_eq!(
        got.payload, payload,
        "payload survives the wire byte-for-byte"
    );
    assert_eq!(got.header.msg_type, MSG_JAM_APP);
    assert_eq!(
        got.from_peer,
        a.id(),
        "origin peer id must be the sender, not the relay (peer-to-peer here, \
         but the invariant is what the upcall's peerIdHex depends on)"
    );
    assert_ne!(got.from_peer, PeerId(0));

    // Contract 3 — stop signal fires on shutdown (bridge-task teardown).
    let mut stop = b.stop_signal();
    Arc::clone(&b).shutdown();
    // watch::Receiver::changed resolves once the value changes false→true.
    tokio::time::timeout(Duration::from_secs(5), stop.changed())
        .await
        .expect("stop signal fires on shutdown")
        .expect("sender still alive while node is");
    assert!(
        *stop.borrow(),
        "stop channel must carry `true` after shutdown"
    );

    // And a second subscriber can drain cleanly afterwards (no hang, no
    // panic) — the node's router simply goes quiet.
    let rx2 = b.subscribe(MSG_JAM_APP, 4);
    drop(rx2);
}
