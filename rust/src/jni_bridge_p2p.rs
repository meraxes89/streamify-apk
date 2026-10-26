//! jni_bridge_p2p.rs — Frozen JNI surface for the P2P mesh (Jam Phase 2).
//!
//! FROZEN CONTRACT (brief §5) — Engineer 3's Kotlin Orchestrator binds
//! against these exact five symbols on `com.streamify.app.data.NativeBridge`:
//!
//!   nativeP2pStart(sessionId, deviceId, enableLan, enableWebrtc): Boolean
//!   nativeP2pStop()
//!   nativeP2pBroadcast(msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pSendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pGetConnectedPeerCount(): Int
//!
//! EXTENSIONS (additive, clearly marked, NOT part of the frozen contract —
//! safe to ignore; see PR "interface freeze" table):
//!   nativeP2pSwarmSeedTrack(trackId: Long, data: ByteArray): Boolean
//!   nativeP2pSwarmStats(trackId: Long): String (JSON, never null)
//!
//! ═══════════════════════════════════════════════════════════════════════
//! PHASE 1 FROZEN CONTRACT (feat/phase1-rust-mesh-32peers, directive §5) —
//! Engineer 3's Kotlin Mesh Orchestrator binds against these exact symbols
//! on `com.streamify.app.mesh.NativeMeshEngine`:
//!
//!   setTopology(topology: Int)                                  // 0=Multi,1=Single
//!   startLanBeacon(roomId: String)
//!   stopLanBeacon()
//!   setMemberAcl(peerPubkey: ByteArray, permissions: Int)
//!   kickPeer(peerPubkey: ByteArray, ban: Boolean)
//!
//! PHASE 1 EXTENSIONS (additive, clearly marked, NOT frozen):
//!   nativeMeshSubmitTransportIntent(kind: Int, body: ByteArray): Boolean
//!   nativeMeshPollLanRooms(): String (JSON, never null)
//!   nativeMeshGovernanceStats(): String (JSON, never null)
//!   nativeMeshDeclareHost(): Boolean
//! ═══════════════════════════════════════════════════════════════════════
//! PHASE 2 FROZEN CONTRACT (feat/phase2-rust-crdt-blend-voting,
//! directive §5) — democratic voting + collaborative playlist bindings:
//!
//!   NativeMeshEngine (com.streamify.app.mesh):
//!     castVote(targetOpId: ByteArray, isUpvote: Boolean): Boolean
//!     getTrackVotes(targetOpId: ByteArray): Int
//!     applyPlaylistOp(opBytes: ByteArray): Boolean
//!     exportPlaylistDelta(sinceVectorClock: Long): ByteArray
//!
//!   NativeRadioEngine (com.streamify.app.audio):
//!     scoreGroupBlendCandidates(candidateJson: String,
//!                               memberSeedsJson: String): String
//!
//! PHASE 2 EXTENSIONS (additive, clearly marked, NOT frozen):
//!   nativeMeshQueueAdd(cadId: Long, fracBits: Long): Long
//!   nativeMeshQueueRemove(targetAddOpId: Long): Boolean
//!   nativeMeshQueueViews(): String (JSON, never null)
//!   nativeMeshBroadcastActivity(cadId: Long, artist: String, album: String,
//!                               progressMs: Long, inJam: Boolean,
//!                               paused: Boolean): Boolean
//!   nativeMeshFriendActivity(): String (JSON, never null)
//!   nativeMeshPlaylistSnapshot(): String (JSON, never null)
//!   nativeMeshSetPlaylistRole(authorId: Int, role: Int): Boolean
//! ═══════════════════════════════════════════════════════════════════════
//!
//! HOUSE RULES observed (same discipline as jni_bridge.rs):
//!   • Every entry point is wrapped in `catch_unwind` — a panic must never
//!     unwind across the FFI boundary into ART.
//!   • Default returns on failure: jboolean → JNI_FALSE, jint → 0,
//!     jstring → "{}" (never null: the Kotlin side declares non-null String).
//!   • Lock-poisoned globals are recovered via `into_inner()`, not panics.
//!
//! RUNTIME MODEL: a dedicated 2-worker Tokio runtime is created on Start
//! and dropped on Stop. All five frozen entry points are synchronous and
//! non-blocking — broadcast/send enqueue onto the mesh's outbound stage;
//! peer count reads an atomic snapshot. The mesh binds 0.0.0.0:7777 with
//! SO_REUSEPORT (falling back to an ephemeral port if the fixed one is
//! held by a zombie process).
//!
//! MSG-TYPE GUARD: the JNI surface refuses to inject internal control
//! types (heartbeat, beacon, gossip, chunk frames) — protocol traffic is
//! not spoofable from the app layer. App-visible types are PTP_SYNC
//! (0x01) and CRDT_OP (0x02), plus future app-level extensions ≥ 0x20.

use std::collections::BTreeSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JClass, JString, ReleaseMode};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobject, jstring};
use jni::{JNIEnv, JavaVM};
use tokio::runtime::Runtime;

use crate::consensus::{CollabPlaylistState, PlaylistRole};
use crate::jam_crdt::{JamOp, OpType};
use crate::p2p_mesh::{MeshConfig, MeshNode, PeerId, RESERVED_CONTROL_TYPES, MSG_JAM_APP};

const JNI_TRUE: jboolean = 1;
const JNI_FALSE: jboolean = 0;

struct P2pRuntime {
    rt: Runtime,
    node: Arc<MeshNode>,
}

static P2P: Mutex<Option<P2pRuntime>> = Mutex::new(None);

#[inline]
fn heal<T>(r: Result<T, PoisonError<T>>) -> T {
    r.unwrap_or_else(|e| e.into_inner())
}

fn stop_runtime() {
    let mut guard = heal(P2P.lock());
    if let Some(rt) = guard.take() {
        rt.node.shutdown();
    } // dropping `rt` parks and drops the Tokio runtime
}

fn with_node<T>(f: impl FnOnce(&Arc<MeshNode>) -> T) -> Option<T> {
    let guard = heal(P2P.lock());
    guard.as_ref().map(|r| f(&r.node))
}

/// Runtime + node accessor for the app bridge (audit #44 P0-1): the
/// forwarder tasks must be spawned ON the mesh's own Tokio runtime, so
/// the wire-up points need the handle, not just the node.
fn with_runtime<T>(f: impl FnOnce(&Runtime, &Arc<MeshNode>) -> T) -> Option<T> {
    let guard = heal(P2P.lock());
    guard.as_ref().map(|r| f(&r.rt, &r.node))
}

// ════════════════════════════ audit #44 P0-1: incoming-callback bridge ═══
//
// `nativeP2pRegisterIncomingCallback` was declared in Kotlin with no
// native export — an UnsatisfiedLinkError caught and downgraded to
// `false`, which meant the native mesh NEVER delivered frames into
// `NativeBridge.incomingFrames`: the entire Jam v4 ingress path was
// silent on real devices (JVM tests never noticed; they exercise the
// meshOverride mock). This bridge closes that hole:
//
//   • registration stores a GlobalRef to the Kotlin listener + the
//     JavaVM (same pattern as jni_bridge_chunk_sync's sync callback);
//   • a forwarder task on the mesh runtime subscribes to MSG_JAM_APP
//     and upcalls `onP2pFrame(peerHex, msgType, payload)`;
//   • a peer watcher polls `peers_snapshot()` and upcalls
//     `onP2pPeerJoined`/`onP2pPeerLeft` on set deltas — the signal
//     JamEngine's Merkle reconciliation is documented to key off;
//   • every upcall attaches its thread transiently, is wrapped in
//     catch_unwind, and swallows JVM failures: the callback layer must
//     never take the mesh down (house rule);
//   • tasks select on the node's stop signal, so Stop/restart tears
//     them down with the node they serve.

struct P2pCallback {
    vm: JavaVM,
    listener: GlobalRef,
}

static P2P_CB: Mutex<Option<Arc<P2pCallback>>> = Mutex::new(None);

fn snapshot_cb() -> Option<Arc<P2pCallback>> {
    heal(P2P_CB.lock()).clone()
}

/// Transient per-event thread attach (same contract as the chunk-sync
/// callback): a JVM failure is swallowed, never propagated.
fn deliver_frame(cb: &P2pCallback, peer_hex: &str, msg_type: u8, payload: &[u8]) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut env) = cb.vm.attach_current_thread() {
            let Ok(peer) = env.new_string(peer_hex) else { return };
            let Ok(arr) = env.byte_array_from_slice(payload) else { return };
            let _ = env.call_method(
                cb.listener.as_obj(),
                "onP2pFrame",
                "(Ljava/lang/String;I[B)V",
                &[(&peer).into(), jint::from(msg_type as i32).into(), (&arr).into()],
            );
        }
    }));
}

fn deliver_peer_event(cb: &P2pCallback, peer_hex: &str, joined: bool) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut env) = cb.vm.attach_current_thread() {
            let Ok(peer) = env.new_string(peer_hex) else { return };
            let _ = env.call_method(
                cb.listener.as_obj(),
                if joined { "onP2pPeerJoined" } else { "onP2pPeerLeft" },
                "(Ljava/lang/String;)V",
                &[(&peer).into()],
            );
        }
    }));
}

/// Spawns the forwarder + peer-watcher tasks on the live mesh runtime.
/// Called from both wire-up points (registration with a live node, and
/// mesh start with a pre-registered callback) — idempotent per node
/// lifetime because each spawn selects on that node's stop signal.
fn spawn_app_bridge(rt: &Runtime, node: &Arc<MeshNode>) {
    // Frame forwarder: everything the Kotlin layer multiplexes onto
    // MSG_JAM_APP lands here after gossip dedupe.
    let mut rx = node.subscribe(MSG_JAM_APP, 256);
    let mut stop = node.stop_signal();
    rt.spawn(async move {
        loop {
            tokio::select! {
                _ = stop.changed() => break,
                pkt = rx.recv() => {
                    let Some(pkt) = pkt else { break };
                    match snapshot_cb() {
                        Some(cb) => deliver_frame(
                            &cb,
                            &pkt.from_peer.to_hex(),
                            pkt.header.msg_type,
                            &pkt.payload,
                        ),
                        // No listener registered: drain quietly. Frames are
                        // still gossip-deduped; dropping them is the same
                        // bounded-discard contract the router applies to a
                        // slow consumer.
                        None => {}
                    }
                }
            }
        }
    });

    // Peer lifecycle watcher: 1.5 s poll of the peer table, join/leave
    // emitted on set deltas. The mesh sweeper evicts stale peers on a
    // 5 s cadence, so a leave event lands within ~6.5 s of a peer's
    // death — bounded, and strictly better than the previous signal
    // (nothing at all).
    let node = Arc::clone(node);
    let mut stop = node.stop_signal();
    rt.spawn(async move {
        let mut known: BTreeSet<u64> = BTreeSet::new();
        let mut interval = tokio::time::interval(Duration::from_millis(1500));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = stop.changed() => break,
                _ = interval.tick() => {
                    let Some(cb) = snapshot_cb() else { continue };
                    let now: BTreeSet<u64> =
                        node.peers_snapshot().iter().map(|p| p.id.0).collect();
                    for id in now.difference(&known) {
                        deliver_peer_event(&cb, &PeerId(*id).to_hex(), true);
                    }
                    for id in known.difference(&now) {
                        deliver_peer_event(&cb, &PeerId(*id).to_hex(), false);
                    }
                    known = now;
                }
            }
        }
    });
}

/// Wire-up point B: mesh start with an already-registered callback.
fn wire_app_bridge_if_registered() {
    if snapshot_cb().is_some() {
        with_runtime(|rt, node| spawn_app_bridge(rt, node));
    }
}

/// Phase 2: singleton collaborative playlist replica (the frozen
/// applyPlaylistOp / exportPlaylistDelta surface binds here). The
/// author id is per-device (audit #22 P0-1): bound from the mesh
/// PeerId at start; role grants flow through SetRole ops and the
/// host-side set-role extension.
static PLAYLIST: Mutex<Option<CollabPlaylistState>> = Mutex::new(None);

/// Per-device CRDT author id, folded from the mesh PeerId (Blake3 of
/// the device string). 0 = not yet bound (before the first mesh
/// start); the legacy constant 1 applies only inside that pre-start
/// window and is adopted away at start — see [`bind_playlist_author`].
static PLAYLIST_AUTHOR: AtomicU32 = AtomicU32::new(0);

#[inline]
fn playlist_author() -> u32 {
    match PLAYLIST_AUTHOR.load(Ordering::Relaxed) {
        0 => 1, // pre-start lazy init only; replaced at mesh start
        a => a,
    }
}

/// Binds the per-device author at mesh start (audit #22 P0-1).
///
/// Previously every device minted ops as author 1: two devices hitting
/// the same local counter produced identical `(author<<32)|counter`
/// item ids on different songs — an author-namespace collision the
/// CRDT design assumes impossible. Deriving from `PeerId` (Blake3 of
/// the device string, stable per device) gives distinct devices
/// distinct authors (collision odds ~2^-32 on the 64→32 fold, vs the
/// previous certainty of collision). An already-live singleton
/// (pre-start lazy init, or a restart under a new device id) adopts
/// the new author with its historical item identities untouched —
/// namespaces are disjoint, so convergence and tombstones are
/// unaffected.
fn bind_playlist_author(device: &str) {
    let raw = PeerId::from_device_str(device).0;
    let folded = ((raw >> 32) as u32) ^ (raw as u32);
    let author = if folded == 0 { u32::MAX } else { folded };
    PLAYLIST_AUTHOR.store(author, Ordering::Relaxed);
    let mut guard = heal(PLAYLIST.lock());
    if let Some(pl) = guard.as_mut() {
        pl.adopt_author(author);
    }
}

#[inline]
fn playlist_with<T>(f: impl FnOnce(&mut CollabPlaylistState) -> T) -> Option<T> {
    let mut guard = heal(PLAYLIST.lock());
    let pl = guard.get_or_insert_with(|| CollabPlaylistState::new(playlist_author()));
    Some(f(pl))
}

/// Reads a Java byte array into an owned Vec (NoCopyBack borrow, copy out).
fn read_jbytes(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    let len = match env.get_array_length(arr) {
        Ok(l) if l >= 0 => l as usize,
        _ => return None,
    };
    if len == 0 {
        return Some(Vec::new());
    }
    let elems = match unsafe { env.get_array_elements(arr, ReleaseMode::NoCopyBack) } {
        Ok(e) => e,
        Err(_) => return None,
    };
    let slice = unsafe { std::slice::from_raw_parts(elems.as_ptr() as *const u8, len) };
    Some(slice.to_vec())
}

fn app_msg_type_ok(msg_type: jint) -> bool {
    if !(0..=255).contains(&msg_type) {
        return false;
    }
    !RESERVED_CONTROL_TYPES.contains(&(msg_type as u8))
}

// ═══════════════════════════════════════════════ frozen contract (§5) ═══

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pStart(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    device_id: JString,
    enable_lan: jboolean,
    enable_webrtc: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let session: String = match env.get_string(&session_id) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let device: String = match env.get_string(&device_id) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        if session.trim().is_empty() || device.trim().is_empty() {
            return JNI_FALSE;
        }

        // Restart semantics: a second Start tears the previous mesh down.
        stop_runtime();

        let rt = match tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
        {
            Ok(rt) => rt,
            Err(_) => return JNI_FALSE,
        };

        let mut cfg = MeshConfig::new(&session, &device);
        cfg.enable_lan = enable_lan != 0;
        cfg.enable_webrtc = enable_webrtc != 0;

        let node = match rt.block_on(async { MeshNode::start(cfg) }) {
            Ok(n) => n,
            Err(_) => return JNI_FALSE,
        };

        // Bind the per-device CRDT author BEFORE the node goes live so
        // no playlist op can be minted under the legacy shared author.
        bind_playlist_author(&device);

        *heal(P2P.lock()) = Some(P2pRuntime { rt, node });
        // audit #44 P0-1: a listener registered before Start (or carried
        // over from a previous mesh life) gets its forwarders now.
        wire_app_bridge_if_registered();
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        stop_runtime();
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pBroadcast(
    mut env: JNIEnv,
    _class: JClass,
    msg_type: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if !app_msg_type_ok(msg_type) {
            return JNI_FALSE;
        }
        let bytes = match read_jbytes(&mut env, &payload) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| node.broadcast(msg_type as u8, &bytes)) {
            Some(Ok(_)) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSendToPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
    msg_type: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if !app_msg_type_ok(msg_type) {
            return JNI_FALSE;
        }
        let hex: String = match env.get_string(&peer_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let peer = match PeerId::from_hex(&hex) {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        let bytes = match read_jbytes(&mut env, &payload) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| node.send_to_peer(peer, msg_type as u8, &bytes)) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pGetConnectedPeerCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        with_node(|node| node.peer_count() as jint).unwrap_or(0)
    }))
    .unwrap_or(0)
}

// ── audit #44 P0-1: the missing sixth frozen symbol ──────────────────

/// `nativeP2pRegisterIncomingCallback(listener: P2pIncomingListener):
/// Boolean` — registers the Kotlin-side ingress listener and returns
/// whether the upcall bridge is live.
///
/// Wire-up orders handled:
///   • register AFTER Start (the NativeBridge.p2pStart flow): forwarder
///     tasks spawn immediately onto the live runtime;
///   • register BEFORE Start: the global ref is stored and Start wires
///     the tasks (wire_app_bridge_if_registered).
///
/// Re-registration replaces the previous global ref (the old one is
/// released on drop); Stop does NOT unregister — the ref survives so a
/// same-listener restart needs no re-register, and a stale listener's
/// upcalls fail harmlessly (swallowed) until it is replaced.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pRegisterIncomingCallback(
    env: JNIEnv,
    _class: JClass,
    listener: jobject,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if listener.is_null() {
            return JNI_FALSE;
        }
        let vm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(_) => return JNI_FALSE,
        };
        let global = match env.new_global_ref(unsafe { JClass::from_raw(listener) }) {
            Ok(g) => g,
            Err(_) => return JNI_FALSE,
        };
        {
            let mut guard = heal(P2P_CB.lock());
            *guard = Some(Arc::new(P2pCallback { vm, listener: global }));
        }
        // Wire-up point A: live mesh at registration time.
        with_runtime(|rt, node| spawn_app_bridge(rt, node));
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}

// ════════════════════════════════════════ extensions (NOT frozen §5) ════
// Additive surface for the audio swarmer. Clearly marked so Engineer 3 can
// ignore them without breaking the frozen contract above.

/// Turns this device into the seeder for `trackId` (the "fastest device
/// fetched from the CDN" role): chunks the audio, gossips the Blake3
/// manifest, announces availability to every connected peer.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSwarmSeedTrack(
    mut env: JNIEnv,
    _class: JClass,
    track_id: jlong,
    data: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if track_id < 0 {
            return JNI_FALSE;
        }
        let bytes = match read_jbytes(&mut env, &data) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| {
            node.swarm_seed_track(track_id as u64, &bytes);
            true
        }) {
            Some(true) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// JSON telemetry for one track's swarm (never null; `{}` when unknown).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSwarmStats(
    env: JNIEnv,
    _class: JClass,
    track_id: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let stats: Vec<_> = node
                .swarm_stats()
                .into_iter()
                .filter(|s| s.track_id == track_id as u64)
                .collect();
            if stats.is_empty() {
                "{}".to_string()
            } else {
                serde_json::to_string(&stats[0]).unwrap_or_else(|_| "{}".to_string())
            }
        })
        .unwrap_or_else(|| "{}".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

// ═══════════════════════════════════════════════════════════════════════
// PHASE 1 — FROZEN CONTRACT (directive §5): com.streamify.app.mesh.
// NativeMeshEngine. The five signatures below are byte-exact with the
// directive; all are void-returning (Kotlin `external fun` unit calls).
// ═══════════════════════════════════════════════════════════════════════

/// `setTopology(topology: Int)` — 0 = MultiRender, 1 = SingleRender.
/// Unknown values are ignored (the engine keeps its current topology).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_setTopology(
    _env: JNIEnv,
    _class: JClass,
    topology: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(t) = crate::jam_governor::Topology::from_jint(topology) {
            with_node(|node| {
                node.set_topology(t);
            });
        }
    }));
}

/// `startLanBeacon(roomId: String)` — begins broadcasting the room
/// session descriptor (RoomID | HostPubKey | Epoch | Capacity |
/// MemberCount) in every beacon on the LAN subnet, and declares this
/// device the room host (room-creation semantics, directive B).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_startLanBeacon(
    mut env: JNIEnv,
    _class: JClass,
    room_id: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let room: String = match env.get_string(&room_id) {
            Ok(s) => s.into(),
            Err(_) => return,
        };
        if room.trim().is_empty() {
            return;
        }
        with_node(|node| {
            node.start_lan_beacon(&room);
        });
    }));
}

/// `stopLanBeacon()` — stops advertising the room descriptor (the node
/// remains in the mesh; beacons revert to the legacy 12-byte form).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_stopLanBeacon(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_node(|node| node.stop_lan_beacon());
    }));
}

/// `setMemberAcl(peerPubkey: ByteArray, permissions: Int)` — host-only:
/// binds permission bits (0x01 playback / 0x02 volume / 0x04 co-host) to
/// a member's 32-byte ephemeral pubkey, locally AND via the signed
/// ACL_UPDATE wire frame so every replica enforces it at ingress.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_setMemberAcl(
    mut env: JNIEnv,
    _class: JClass,
    peer_pubkey: JByteArray,
    permissions: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match read_jbytes(&mut env, &peer_pubkey) {
            Some(b) => b,
            None => return,
        };
        let mut pk = [0u8; 32];
        if bytes.len() != 32 {
            return; // pubkey identity is exactly 32 bytes (Ed25519)
        }
        pk.copy_from_slice(&bytes);
        let bits = (permissions as u8) & 0x07; // only the three defined bits
        with_node(|node| node.set_member_acl(pk, bits));
    }));
}

/// `kickPeer(peerPubkey: ByteArray, ban: Boolean)` — host-only: evicts the
/// targeted member via the signed KICK_DIRECTIVE; `ban` blacklists the
/// pubkey for the session duration (re-join attempts are refused at the
/// beacon boundary on every replica).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_kickPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_pubkey: JByteArray,
    ban: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match read_jbytes(&mut env, &peer_pubkey) {
            Some(b) => b,
            None => return,
        };
        let mut pk = [0u8; 32];
        if bytes.len() != 32 {
            return;
        }
        pk.copy_from_slice(&bytes);
        with_node(|node| node.kick_peer(pk, ban != 0));
    }));
}

// ═════════════════════════════ PHASE 1 extensions (NOT frozen §5) ══════
// Additive surface for the mesh orchestrator. Clearly marked so
// Engineer 3 can ignore them without breaking the frozen contract above.

/// Submits a transport control intent (Play/Pause/Seek/Skip/SkipPrev/
/// QueueReorder/Volume — kinds 0..6). The engine signs it with this
/// node's ephemeral key, fences it to the current epoch, and routes it
/// per topology (SingleRender → unicast to host; MultiRender → gossip).
/// `body` is 16 bytes (kind-specific, e.g. position_ms u64 LE).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshSubmitTransportIntent(
    mut env: JNIEnv,
    _class: JClass,
    kind: jint,
    body: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(kind) = crate::jam_governor::IntentKind::from_u8(kind.clamp(0, 255) as u8) else {
            return JNI_FALSE;
        };
        let bytes = match read_jbytes(&mut env, &body) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        let mut b = [0u8; 16];
        if bytes.len() > 16 {
            return JNI_FALSE;
        }
        b[..bytes.len()].copy_from_slice(&bytes);
        match with_node(|node| node.submit_transport_intent(kind, b)) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Polls the LAN room discovery registry (directive B): JSON array of
/// nearby live Jam rooms, newest first, never null (`[]` when empty or
/// the mesh is not running). Field names are the Kotlin contract.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshPollLanRooms(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let rooms: Vec<serde_json::Value> = node
                .lan_rooms()
                .into_iter()
                .map(|r| {
                    serde_json::json!({
                        "roomId": hex::encode(r.room_id),
                        "hostPubkey": hex::encode(r.host_pubkey),
                        "epoch": r.epoch,
                        "capacity": r.capacity,
                        "memberCount": r.member_count,
                        "hostAddr": r.from_addr.to_string(),
                        "port": r.port,
                    })
                })
                .collect();
            serde_json::to_string(&rooms).unwrap_or_else(|_| "[]".to_string())
        })
        .unwrap_or_else(|| "[]".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Governance telemetry JSON (never null): identity, host, epoch,
/// topology, blacklist size, and the wire-boundary rejection counters.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshGovernanceStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let snap = node.governance_snapshot();
            let m = node.stats();
            serde_json::json!({
                "mePubkey": hex::encode(snap.me_pubkey),
                "hostPubkey": hex::encode(snap.host_pubkey),
                "isHost": snap.is_host,
                "epoch": snap.epoch,
                "topology": if snap.topology == crate::jam_governor::Topology::SingleRender { "SingleRender" } else { "MultiRender" },
                "memberCount": snap.member_count,
                "capacity": snap.capacity,
                "blacklistLen": snap.blacklist_len,
                "ptpSuppressed": m.ptp_suppressed,
                "sigRejects": m.gov_sig_rejects,
                "epochRejects": m.gov_epoch_rejects,
                "aclRejects": m.gov_acl_rejects,
                "rateLimited": m.gov_rate_limited,
                "replays": m.gov_replays,
                "blacklistRejects": m.gov_blacklist_rejects,
                "kicksApplied": m.gov_kicks_applied,
                "intentsCommitted": m.gov_intents_committed,
                "intentsForwarded": m.gov_intents_forwarded,
                "elections": m.gov_elections,
            })
            .to_string()
        })
        .unwrap_or_else(|| "{}".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Self-declares this device the room host without starting a LAN beacon
/// (used when the room was created via QR/paste pairing instead of the
/// zero-friction LAN flow).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshDeclareHost(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        match with_node(|node| {
            node.declare_host();
            true
        }) {
            Some(true) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

// ═══════════════════════════════════════════════════════════════════
// PHASE 2 — FROZEN CONTRACT (directive §5):
// com.streamify.app.mesh.NativeMeshEngine, democratic voting +
// collaborative playlist CRDT. Signatures are byte-exact with the
// directive.
// ═══════════════════════════════════════════════════════════════════

/// Reads an 8-byte little-endian u64 op-id argument (the directive's
/// `target_op_id: jbyteArray`). Wrong-length arrays are rejected without
/// panicking.
fn read_jbytes_u64(env: &mut JNIEnv, arr: &JByteArray) -> Option<u64> {
    let bytes = read_jbytes(env, arr)?;
    if bytes.len() != 8 {
        return None;
    }
    Some(u64::from_le_bytes(bytes.try_into().ok()?))
}

/// `castVote(targetOpId: ByteArray, isUpvote: Boolean): Boolean` — signs
/// an epoch-fenced vote with this node's ephemeral governance key, merges
/// it into the local CRDT replica, and gossips it mesh-wide (upvote when
/// true, retraction when false). Returns false when the mesh is down or
/// the target id is malformed.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_castVote(
    mut env: JNIEnv,
    _class: JClass,
    target_op_id: JByteArray,
    is_upvote: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(target) = read_jbytes_u64(&mut env, &target_op_id) else {
            return JNI_FALSE;
        };
        match with_node(|node| node.cast_vote(target, is_upvote != 0)) {
            Some(Ok(_)) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// `getTrackVotes(targetOpId: ByteArray): Int` — net upvote count for one
/// queue element as seen by this replica (retractions subtract; 0 when
/// the mesh is down or the id is malformed).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_getTrackVotes(
    mut env: JNIEnv,
    _class: JClass,
    target_op_id: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(target) = read_jbytes_u64(&mut env, &target_op_id) else {
            return 0;
        };
        with_node(|node| node.vote_count(target) as jint).unwrap_or(0)
    }))
    .unwrap_or(0)
}

/// `applyPlaylistOp(opBytes: ByteArray): Boolean` — parses + merges one
/// collaborative playlist op frame (64-byte LE header + text tail) into
/// the singleton playlist engine. Permission-rejected and corrupt ops
/// return false and never touch the state.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_applyPlaylistOp(
    mut env: JNIEnv,
    _class: JClass,
    op_bytes: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(bytes) = read_jbytes(&mut env, &op_bytes) else {
            return JNI_FALSE;
        };
        playlist_with(|pl| {
            matches!(
                pl.apply_bytes(&bytes),
                crate::consensus::PlaylistApplyResult::Applied
            )
        })
        .unwrap_or(false) as jboolean
    }))
    .unwrap_or(JNI_FALSE)
}

/// `exportPlaylistDelta(sinceVectorClock: Long): ByteArray` — the op-log
/// delta since the caller's Lamport watermark, wire-encoded as a
/// length-prefixed op batch (empty array when nothing is newer). The
/// jlong is the compact form of the caller's vector clock (max applied
/// Lamport timestamp); the exact per-author clock path is the JSON-clock
/// extension below.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_exportPlaylistDelta(
    env: JNIEnv,
    _class: JClass,
    since_vector_clock: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let watermark = if since_vector_clock < 0 {
            0
        } else {
            since_vector_clock as u64
        };
        let delta = playlist_with(|pl| {
            let ops = pl.export_delta_since_watermark(watermark);
            CollabPlaylistState::encode_delta(&ops)
        })
        .unwrap_or_default();
        match env.byte_array_from_slice(&delta) {
            Ok(a) => a.into_raw(),
            // Kotlin contract: never null — degrade to an empty array.
            Err(_) => env
                .byte_array_from_slice(&[])
                .map(|a| a.into_raw())
                .unwrap_or(std::ptr::null_mut()),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

// ═══════════════════════════════════════════════════════════════════
// PHASE 2 FROZEN CONTRACT (directive §5) — com.streamify.app.audio.
// NativeRadioEngine: group taste blend scorer. JSON in, ranked JSON out.
// ═══════════════════════════════════════════════════════════════════

/// `scoreGroupBlendCandidates(candidateJson: String,
/// memberSeedsJson: String): String` — multi-peer candidate ranking over
/// the Phase 2 blend formula (w1·OverlapAffinity + w2·FreshnessDecay +
/// w3·CoOccurrenceRank − w4·AntiDriftPenalty). Never null: malformed
/// input yields a JSON error envelope `{"error":"…"}`.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_audio_NativeRadioEngine_scoreGroupBlendCandidates(
    mut env: JNIEnv,
    _class: JClass,
    candidate_json: JString,
    member_seeds_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let cj: String = match env.get_string(&candidate_json) {
            Ok(s) => s.into(),
            Err(_) => return error_string(&env, "candidateJson unreadable"),
        };
        let sj: String = match env.get_string(&member_seeds_json) {
            Ok(s) => s.into(),
            Err(_) => return error_string(&env, "memberSeedsJson unreadable"),
        };
        let out = match crate::radio_scorer::GroupBlendScorer::score_json(&cj, &sj) {
            Ok(json) => json,
            Err(e) => error_envelope(e),
        };
        match env.new_string(&out) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn error_envelope(msg: String) -> String {
    format!(
        "{{\"error\":{}}}",
        serde_json::to_string(&msg).unwrap_or_else(|_| "\"scoring failed\"".into())
    )
}

fn error_string(env: &JNIEnv, msg: &str) -> jstring {
    let envelope = error_envelope(msg.to_string());
    match env.new_string(&envelope) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ═════════════════════════════════════════════════ PHASE 2 extensions
// (NOT frozen §5) — additive surface for Engineer 3's Kotlin mesh
// orchestrator. Clearly marked so they can be ignored without breaking
// the frozen contract above.

/// Adds a track to the mesh CRDT queue (the local origin path for the
/// democratic queue — mirrored to every replica as a CRDT_OP broadcast).
/// Returns the new element's add op id (the vote target), or -1 on
/// failure. `frac_bits` is the f64 fraction's bit pattern
/// (f64.toRawBits() on the Kotlin side).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshQueueAdd(
    _env: JNIEnv,
    _class: JClass,
    cad_id: jlong,
    frac_bits: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if cad_id <= 0 {
            return -1;
        }
        let frac = f64::from_bits(frac_bits as u64);
        if !frac.is_finite() || frac < 0.0 {
            return -1;
        }
        let nonce = session_nonce();
        let op = JamOp::new(
            JamOp::generate_op_id(),
            nonce,
            OpType::Add,
            0,
            cad_id as u64,
            frac,
            0,
        );
        match with_node(|node| node.submit_queue_op(&op)) {
            Some(Ok(())) => op.op_id as jlong,
            _ => -1,
        }
    }))
    .unwrap_or(-1)
}

/// Removes a queue element by its add op id (tombstones it mesh-wide).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshQueueRemove(
    _env: JNIEnv,
    _class: JClass,
    target_add_op_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if target_add_op_id <= 0 {
            return JNI_FALSE;
        }
        let nonce = session_nonce();
        let op = JamOp::new(
            JamOp::generate_op_id(),
            nonce,
            OpType::Remove,
            0,
            0,
            0.0,
            target_add_op_id as u64,
        );
        match with_node(|node| node.submit_queue_op(&op)) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Device nonce (4 bytes) for locally minted queue ops: a stable
/// per-session derivation from the mesh session id.
fn session_nonce() -> [u8; 4] {
    let mut nonce = [0u8; 4];
    let session = with_node(|n| n.session().0).unwrap_or_default();
    let h = blake3::hash(&session);
    nonce.copy_from_slice(&h.as_bytes()[..4]);
    nonce
}

/// Democratic queue views (never null): proposed rail, committed block
/// (active playback order), and full playback order with vote counts.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshQueueViews(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let proposed: Vec<serde_json::Value> =
                node.crdt_proposed_queue().into_iter().map(view_json).collect();
            let committed: Vec<serde_json::Value> =
                node.crdt_committed_queue().into_iter().map(view_json).collect();
            let playback: Vec<serde_json::Value> =
                node.crdt_playback_order().into_iter().map(view_json).collect();
            let stats = node.stats();
            serde_json::json!({
                "proposed": proposed,
                "committed": committed,
                "playback": playback,
                "votesApplied": stats.votes_applied,
                "voteRejects": stats.vote_rejects,
            })
            .to_string()
        })
        .unwrap_or_else(|| "{}".to_string());
        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn view_json(e: crate::jam_crdt::QueueViewEntry) -> serde_json::Value {
    serde_json::json!({
        "addOpId": format!("{:016x}", e.add_op_id),
        "cadId": e.cad_id as i64,
        "frac": e.frac,
        "votes": e.votes,
    })
}

/// Broadcasts this device's live listening state (directive D) with the
/// engine-side format validation + sender throttle. Returns false when
/// throttled (the UI backs off and retries) or when the mesh is down.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshBroadcastActivity(
    mut env: JNIEnv,
    _class: JClass,
    cad_id: jlong,
    artist: JString,
    album: JString,
    progress_ms: jlong,
    in_jam: jboolean,
    paused: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let artist: String = env.get_string(&artist).map(|s| s.into()).unwrap_or_default();
        let album: String = env.get_string(&album).map(|s| s.into()).unwrap_or_default();
        let room: Option<[u8; 16]> = with_node(|n| n.session().0);
        match with_node(|node| {
            node.broadcast_friend_activity(
                cad_id.max(0) as u64,
                &artist,
                &album,
                progress_ms.max(0) as u64,
                room.as_ref(),
                in_jam != 0,
                paused != 0,
            )
        }) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Friend activity feed (never null): JSON array of live entries,
/// freshest first, TTL-pruned.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshFriendActivity(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let rows: Vec<serde_json::Value> = node
                .friend_activities()
                .into_iter()
                .map(|e| {
                    serde_json::json!({
                        "peerId": format!("{:016x}", e.peer.0),
                        "cadId": e.cad_id as i64,
                        "progressMs": e.progress_ms,
                        "roomId": e.room_id.map(hex::encode),
                        "inJam": e.in_jam,
                        "paused": e.paused,
                        "artist": e.artist,
                        "album": e.album,
                    })
                })
                .collect();
            serde_json::to_string(&rows).unwrap_or_else(|_| "[]".to_string())
        })
        .unwrap_or_else(|| "[]".to_string());
        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Collaborative playlist snapshot (never null): title + live rows in
/// fractional order + the caller's exact vector clock and Lamport
/// watermark (the inputs for exact delta pulls).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshPlaylistSnapshot(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = playlist_with(|pl| {
            let rows: Vec<serde_json::Value> = pl
                .snapshot_items()
                .into_iter()
                .map(|i| {
                    serde_json::json!({
                        "itemId": format!("{:016x}", i.item_id),
                        "cadId": i.cad_id as i64,
                        "frac": i.frac,
                    })
                })
                .collect();
            let clock: Vec<serde_json::Value> = pl
                .clock()
                .iter()
                .map(|(author, seq)| serde_json::json!({"author": author, "seq": seq}))
                .collect();
            serde_json::json!({
                "title": pl.title(),
                "items": rows,
                "vectorClock": clock,
                "lamportWatermark": pl.my_watermark(),
            })
            .to_string()
        })
        .unwrap_or_else(|| "{}".to_string());
        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Host-side role grant for the collaborative playlist roster (local
/// trust mutation; Admin path). Roles: 1=Viewer, 2=Editor, 3=Admin.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshSetPlaylistRole(
    _env: JNIEnv,
    _class: JClass,
    author_id: jint,
    role: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(role) = PlaylistRole::from_u8(role.clamp(0, 255) as u8) else {
            return JNI_FALSE;
        };
        if author_id <= 0 {
            return JNI_FALSE;
        }
        playlist_with(|pl| pl.set_role_local(author_id as u32, role)).is_some() as jboolean
    }))
    .unwrap_or(JNI_FALSE)
}
