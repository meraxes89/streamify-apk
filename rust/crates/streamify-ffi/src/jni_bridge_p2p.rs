//! jni_bridge_p2p.rs — Frozen JNI surface for the P2P mesh (Jam Phase 2).
//!
//! FROZEN CONTRACT (brief §5) — the Kotlin NativeBridge binds against
//! these exact symbols on `com.streamify.app.data.NativeBridge`:
//!
//!   nativeP2pStart(sessionId, deviceId, enableLan, enableWebrtc): Boolean
//!   nativeP2pStop()
//!   nativeP2pBroadcast(msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pSendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pGetConnectedPeerCount(): Int
//!   nativeP2pRegisterIncomingCallback(listener): Boolean   // audit #44 P0-1
//!
//! DEPRECATED SURFACES REMOVED (Phase 4 — UFFI-03/04 + clean parity):
//! the NativeMeshEngine / NativeRadioEngine / swarm-stat exports whose
//! declaring Kotlin classes were removed in earlier migrations (they
//! had zero callers — the Phase-1 grandfathered dangler set). The Jam
//! queue / voting / reconciliation control plane now crosses through
//! the UniFFI StreamifyEngine contract (create_jam_session /
//! apply_jam_delta / get_jam_state_hash); the collaborative-playlist
//! singleton this bridge carried is gone with its callers, while the
//! CRDT library itself stays in `consensus` with its own tests; the
//! mesh's CRDT state machines remain reachable in-process via
//! `p2p_mesh::MeshNode`'s pub surface.
//!
//! HOUSE RULES observed (same discipline as jni_bridge.rs):
//!   • Every entry point is wrapped in `catch_unwind` — a panic must never
//!     unwind across the FFI boundary into ART.
//!   • Default returns on failure: jboolean → JNI_FALSE, jint → 0.
//!   • Lock-poisoned globals are recovered via `into_inner()`, not panics.
//!
//! RUNTIME MODEL: a dedicated 2-worker Tokio runtime is created on Start
//! and dropped on Stop. All frozen entry points are synchronous and
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
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, ReleaseMode};
use jni::sys::{jboolean, jint};
use jni::{JNIEnv, JavaVM};
use tokio::runtime::Runtime;

use streamify_jam::p2p_mesh::{MeshConfig, MeshNode, PeerId, MSG_JAM_APP, RESERVED_CONTROL_TYPES};
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
            let Ok(peer) = env.new_string(peer_hex) else {
                return;
            };
            let Ok(arr) = env.byte_array_from_slice(payload) else {
                return;
            };
            let _ = env.call_method(
                cb.listener.as_obj(),
                "onP2pFrame",
                "(Ljava/lang/String;I[B)V",
                &[
                    (&peer).into(),
                    jint::from(msg_type as i32).into(),
                    (&arr).into(),
                ],
            );
        }
    }));
}

fn deliver_peer_event(cb: &P2pCallback, peer_hex: &str, joined: bool) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut env) = cb.vm.attach_current_thread() {
            let Ok(peer) = env.new_string(peer_hex) else {
                return;
            };
            let _ = env.call_method(
                cb.listener.as_obj(),
                if joined {
                    "onP2pPeerJoined"
                } else {
                    "onP2pPeerLeft"
                },
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
                    if let Some(cb) = snapshot_cb() {
                        deliver_frame(
                            &cb,
                            &pkt.from_peer.to_hex(),
                            pkt.header.msg_type,
                            &pkt.payload,
                        );
                    }
                    // No listener registered: drain quietly. Frames are
                    // still gossip-deduped; dropping them is the same
                    // bounded-discard contract the router applies to a
                    // slow consumer.
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
        with_runtime(spawn_app_bridge);
    }
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
    listener: JObject,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if listener.as_raw().is_null() {
            return JNI_FALSE;
        }
        let vm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(_) => return JNI_FALSE,
        };
        let global = match env.new_global_ref(listener) {
            Ok(g) => g,
            Err(_) => return JNI_FALSE,
        };
        {
            let mut guard = heal(P2P_CB.lock());
            *guard = Some(Arc::new(P2pCallback {
                vm,
                listener: global,
            }));
        }
        // Wire-up point A: live mesh at registration time.
        with_runtime(spawn_app_bridge);
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}
