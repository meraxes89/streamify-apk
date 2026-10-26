//! jni_bridge_chunk_sync.rs → jni_bridge_connect.rs — JNI surface for the
//! Phase 4 Connect gateway, remote intent engine & WearOS sync
//! (feat/phase4-rust-connect-gateway-wear-sync, directive §2.4).
//!
//! ENGINEER 3 BINDS AGAINST `com.streamify.core.rust.RustConnectGateway`
//! (all `external fun` declarations; Kotlin owns the class — this crate
//! is strictly `rust/` isolated):
//!
//! FROZEN CONTRACT (directive §2.4 — the six symbols):
//!   nativeInitGateway(deviceId: String, deviceName: String,
//!                     deviceType: Int): Long          // 0 = failure
//!   nativeStartDiscovery(): Boolean
//!   nativeStopDiscovery(): Boolean
//!   nativeTransferPlayback(targetDeviceId: String,
//!                          sessionStateJson: String): Boolean
//!   nativeDispatchIntent(targetDeviceId: String, intentType: Int,
//!                        payload: ByteArray): Boolean
//!   nativeStartWearSync(watchDeviceId: String,
//!                       manifestBytes: ByteArray): Boolean
//!
//!   • deviceType: 0 Phone · 1 Tablet · 2 Speaker · 3 TV · 4 Car ·
//!     5 Watch (unknown codes fail init).
//!   • intentType: 1 Play · 2 Pause · 3 SeekTo(payload=u64 LE ms) ·
//!     4 QueueInsert(payload=cad u64 LE + after u32 LE, u32::MAX=tail) ·
//!     5 QueueRemove(payload=index u32 LE) · 6 QueueReorder(payload=
//!     from u32 LE + to u32 LE) · 7 SetVolume(payload=percent u8).
//!   • sessionStateJson: {"trackId":u64,"positionMs":u64,
//!     "queue":[u64],"queueIndex":u32,"repeat":"off|all|one",
//!     "shuffle":bool}
//!   • manifestBytes: concatenated [cad_id u64 LE][data_len u32 LE]
//!     [data bytes] records — the tracks to cache on the watch.
//!   • Target devices must be present in the discovery registry
//!     (nativeStartDiscovery → adverts ingested → registry) before
//!     transfer/intent/wear dispatch succeeds.
//!
//! EXTENSIONS (additive, clearly marked, NOT part of the frozen
//! contract — the transport plumbing the Kotlin socket/relay loop
//! calls; safe to ignore, see the PR "interface freeze" table):
//!   nativeOnConnectFrame(frame: ByteArray): Boolean    // LAN ingress
//!   nativeOnRelayFrame(frame: ByteArray): Boolean      // cloud-relay ingress
//!   nativeTickConnect(): Boolean          // leases + retransmit + pacer
//!   nativePollConnectFrame(): ByteArray   // ONE outbound frame (empty = none)
//!   nativeDrainConnectEvents(): String    // JSON array, never null
//!   nativeGetDevicesJson(): String        // registry snapshot, never null
//!
//! HOUSE RULES (same discipline as jni_bridge.rs / jni_bridge_p2p.rs /
//! jni_bridge_chunk_sync.rs):
//!   • Every entry point wraps its body in `catch_unwind` — a panic must
//!     never unwind across the FFI boundary into ART.
//!   • Default returns on failure: jboolean → JNI_FALSE, jlong → 0,
//!     jstring → "[]"/"{}" (never null: Kotlin declares non-null
//!     String), jbyteArray → empty array.
//!   • Lock-poisoned globals are recovered via `into_inner()`, not
//!     panics. No Java exceptions are thrown from here; sentinel
//!     returns carry the failure so the Kotlin layer logs-and-degrades.
//!
//! ZERO UI-THREAD BLOCKING: every exported method is a short, lock-scoped
//! state update or a pure computation over caller-supplied buffers — no
//! waits, no sleeps, no lock held across a JNI upcall, no callback
//! threads (events are pull-based via nativeDrainConnectEvents; the
//! Kotlin side owns its own threading). The bridge clock is a monotonic
//! `Instant` since process start (logical ms) — the engines themselves
//! stay pure-logic with injected `now_ms`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, PoisonError};
use std::time::Instant;

use jni::objects::{JByteArray, JClass, JString, ReleaseMode};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;

use crate::connect_gateway::{device_id_from_string, ConnectGateway, GatewayEvent};
use crate::device_registry::{DeviceType, DiscoveryOrigin};
use crate::remote_intent_engine::{
    IntentEvent, PlaybackSnapshot, RemoteIntent, RemoteIntentEngine,
};
use crate::wear_sync::{
    PhoneSyncCoordinator, QueueOp, WearSyncAction, WearSyncConfig, WearSyncEvent, MAX_CACHE_TRACKS,
    MAX_TRACK_SEGMENTS, SEGMENT_SIZE,
};

const JNI_TRUE: jboolean = 1;
const JNI_FALSE: jboolean = 0;

/// Max manifest blob (16 tracks × 4 MiB).
const MAX_MANIFEST_BLOB: usize = MAX_CACHE_TRACKS * MAX_TRACK_SEGMENTS as usize * SEGMENT_SIZE;

#[inline]
fn jbool(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[inline]
fn heal<T>(r: Result<T, PoisonError<T>>) -> T {
    r.unwrap_or_else(|e| e.into_inner())
}

// ─────────────────────────────────────────────── bridge state

/// One outbound frame waiting for the Kotlin transport poll.
struct Outbound {
    frame: Vec<u8>,
}

struct ConnectBridgeState {
    gateway: ConnectGateway,
    intent: RemoteIntentEngine,
    wear: PhoneSyncCoordinator,
    /// Monotonic bridge clock (ms since process start).
    started: Instant,
    /// Last logical now handed to the engines.
    last_now_ms: u64,
    /// Heartbeat/advert cadence bookkeeping.
    last_advert_ms: u64,
    /// Monotonic manifest id for wear pushes.
    next_manifest_id: u8,
    /// Outbound FIFO (gateway adverts + RIM frames + WSX frames).
    outbound: Vec<Outbound>,
}

impl ConnectBridgeState {
    fn now_ms(&self) -> u64 {
        self.started.elapsed().as_millis().min(u64::MAX as u128) as u64
    }

    /// Pumps the engines: drains their actions into the outbound FIFO.
    fn pump_engines(&mut self) {
        for a in self.intent.drain_actions() {
            let crate::remote_intent_engine::IntentAction::Send { frame, .. } = a;
            self.outbound.push(Outbound { frame });
        }
        for WearSyncAction::Send { frame } in self.wear.drain_actions() {
            self.outbound.push(Outbound { frame });
        }
    }

    /// Engine housekeeping + presence beacon cadence.
    fn tick(&mut self) {
        let now = self.now_ms();
        self.gateway.tick(now);
        self.intent.tick(now);
        self.wear.tick(now);
        // Presence advert cadence while discovery is active (5 s).
        if self.gateway.discovery_active() && now >= self.last_advert_ms + 5_000 {
            self.last_advert_ms = now;
            let advert = self.gateway.build_presence_advert();
            self.outbound.push(Outbound { frame: advert });
        }
        self.pump_engines();
        self.last_now_ms = now;
    }
}

static BRIDGE: Mutex<Option<ConnectBridgeState>> = Mutex::new(None);
static INIT_TOKEN: AtomicU64 = AtomicU64::new(0);

// ─────────────────────────────────────────────── small JNI helpers

fn read_jstring(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|v| v.into())
}

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

fn make_byte_array(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn make_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Parses the manifest blob: [cad u64 LE][len u32 LE][data]…
fn parse_manifest_blob(blob: &[u8]) -> Option<Vec<(u64, Vec<u8>)>> {
    if blob.is_empty() || blob.len() > MAX_MANIFEST_BLOB {
        return None;
    }
    let mut tracks = Vec::new();
    let mut off = 0usize;
    while off < blob.len() {
        if off + 12 > blob.len() {
            return None;
        }
        let cad = u64::from_le_bytes(blob[off..off + 8].try_into().ok()?);
        let len = u32::from_le_bytes(blob[off + 8..off + 12].try_into().ok()?) as usize;
        off += 12;
        if off + len > blob.len() || len == 0 {
            return None;
        }
        tracks.push((cad, blob[off..off + len].to_vec()));
        off += len;
    }
    if tracks.len() > MAX_CACHE_TRACKS {
        return None;
    }
    Some(tracks)
}

// ─────────────────────────────────────────────── event → JSON

fn device_event_json(
    kind: &str,
    device_id: u64,
    name: &str,
    dt: DeviceType,
    origin: DiscoveryOrigin,
) -> serde_json::Value {
    serde_json::json!({
        "source": "gateway",
        "type": kind,
        "deviceId": format!("{device_id:016x}"),
        "name": name,
        "deviceType": dt.to_string(),
        "origin": origin.to_string(),
    })
}

fn gateway_event_json(ev: &GatewayEvent) -> serde_json::Value {
    use GatewayEvent as E;
    match ev {
        E::DiscoveryStarted => {
            serde_json::json!({"source": "gateway", "type": "discovery_started"})
        }
        E::DiscoveryStopped => {
            serde_json::json!({"source": "gateway", "type": "discovery_stopped"})
        }
        E::DeviceDiscovered {
            device_id,
            name,
            device_type,
            origin,
        } => device_event_json("device_discovered", *device_id, name, *device_type, *origin),
        E::DeviceUpdated {
            device_id,
            name,
            device_type,
            origin,
        } => device_event_json("device_updated", *device_id, name, *device_type, *origin),
        E::DeviceLost {
            device_id,
            name,
            device_type,
            origin,
        } => device_event_json("device_lost", *device_id, name, *device_type, *origin),
        E::PresenceQuery {
            from_device_id,
            name,
        } => serde_json::json!({
            "source": "gateway",
            "type": "presence_query",
            "deviceId": format!("{from_device_id:016x}"),
            "name": name,
        }),
    }
}

fn intent_event_json(ev: &IntentEvent) -> serde_json::Value {
    use IntentEvent as E;
    match ev {
        E::RoleChanged { from, to } => serde_json::json!({
            "source": "intent", "type": "role_changed",
            "from": from.to_string(), "to": to.to_string(),
        }),
        E::HandoffStarted { target, epoch } => serde_json::json!({
            "source": "intent", "type": "handoff_started",
            "target": format!("{target:016x}"), "epoch": epoch,
        }),
        E::HandoffCompleted { target, epoch } => serde_json::json!({
            "source": "intent", "type": "handoff_completed",
            "target": format!("{target:016x}"), "epoch": epoch,
            // THE app-layer signal: halt the local player NOW.
            "haltLocalPlayback": true,
        }),
        E::HandoffFailed { target, reason } => serde_json::json!({
            "source": "intent", "type": "handoff_failed",
            "target": format!("{target:016x}"), "reason": reason.to_string(),
            "localPlaybackContinues": true,
        }),
        E::HandoffAdopted {
            from_controller,
            snapshot,
            epoch,
        } => serde_json::json!({
            "source": "intent", "type": "handoff_adopted",
            "controller": format!("{from_controller:016x}"),
            "epoch": epoch,
            "sessionState": serde_json::from_str::<serde_json::Value>(&snapshot.to_json())
                .unwrap_or(serde_json::Value::Null),
        }),
        E::HandoffRefused {
            from_controller,
            reason,
        } => serde_json::json!({
            "source": "intent", "type": "handoff_refused",
            "controller": format!("{from_controller:016x}"),
            "reason": reason.to_string(),
        }),
        E::HandoffFinalized { controller } => serde_json::json!({
            "source": "intent", "type": "handoff_finalized",
            "controller": format!("{controller:016x}"),
        }),
        E::RemoteTookControl { device_id, epoch } => serde_json::json!({
            "source": "intent", "type": "remote_took_control",
            "deviceId": format!("{device_id:016x}"), "epoch": epoch,
        }),
        E::IntentApplied { seq_id, intent } => serde_json::json!({
            "source": "intent", "type": "intent_applied",
            "seqId": seq_id, "intent": intent_name(intent),
        }),
        E::IntentRejectedStale { seq_id, controller } => serde_json::json!({
            "source": "intent", "type": "intent_rejected_stale",
            "seqId": seq_id,
            "controllerEpoch": controller.0,
            "controllerId": format!("{:016x}", controller.1),
        }),
        E::IntentAbandoned { seq_id, intent } => serde_json::json!({
            "source": "intent", "type": "intent_abandoned",
            "seqId": seq_id, "intent": intent_name(intent),
        }),
        E::ControllerSilent { controller } => serde_json::json!({
            "source": "intent", "type": "controller_silent",
            "controllerEpoch": controller.0,
            "controllerId": format!("{:016x}", controller.1),
        }),
    }
}

fn intent_name(intent: &RemoteIntent) -> &'static str {
    match intent {
        RemoteIntent::Play => "play",
        RemoteIntent::Pause => "pause",
        RemoteIntent::SeekTo { .. } => "seek_to",
        RemoteIntent::QueueInsert { .. } => "queue_insert",
        RemoteIntent::QueueRemove { .. } => "queue_remove",
        RemoteIntent::QueueReorder { .. } => "queue_reorder",
        RemoteIntent::SetVolume { .. } => "set_volume",
    }
}

fn wear_event_json(ev: &WearSyncEvent) -> serde_json::Value {
    use WearSyncEvent as E;
    match ev {
        E::QueueMirrorUpdated { len } => serde_json::json!({
            "source": "wear", "type": "queue_mirror_updated", "len": len,
        }),
        E::QueueActionReceived { op } => serde_json::json!({
            "source": "wear", "type": "queue_action", "op": queue_op_name(op),
        }),
        E::RatingReceived { rating } => serde_json::json!({
            "source": "wear", "type": "rating",
            "cadId": rating.cad_id, "rating": rating.rating,
            "thumbsUp": rating.thumbs_up,
        }),
        E::JamUpvoteReceived { upvote } => serde_json::json!({
            "source": "wear", "type": "jam_upvote",
            "cadId": upvote.cad_id,
            "voterNonce": upvote.voter_nonce.iter().map(|b| format!("{b:02x}")).collect::<String>(),
            "up": upvote.up,
        }),
        E::ManifestReceived {
            manifest_id,
            tracks,
        } => serde_json::json!({
            "source": "wear", "type": "manifest_received",
            "manifestId": manifest_id, "tracks": tracks.len(),
        }),
        E::CacheSegmentVerified { cad_id, seg_idx } => serde_json::json!({
            "source": "wear", "type": "cache_segment_verified",
            "cadId": cad_id, "segIdx": seg_idx,
        }),
        E::CacheSegmentCorrupt { cad_id, seg_idx } => serde_json::json!({
            "source": "wear", "type": "cache_segment_corrupt",
            "cadId": cad_id, "segIdx": seg_idx,
        }),
        E::CacheTrackCompleted { cad_id } => serde_json::json!({
            "source": "wear", "type": "cache_track_completed", "cadId": cad_id,
        }),
        E::CacheTrackFailed { cad_id } => serde_json::json!({
            "source": "wear", "type": "cache_track_failed", "cadId": cad_id,
        }),
        E::CachePaused { reason } => serde_json::json!({
            "source": "wear", "type": "cache_paused",
            "reason": match reason {
                crate::wear_sync::PauseReason::Battery => "battery",
                crate::wear_sync::PauseReason::User => "user",
            },
        }),
        E::CacheResumed => serde_json::json!({"source": "wear", "type": "cache_resumed"}),
        E::CacheCancelled { manifest_id } => serde_json::json!({
            "source": "wear", "type": "cache_cancelled", "manifestId": manifest_id,
        }),
        E::ControlAbandoned { seq } => serde_json::json!({
            "source": "wear", "type": "control_abandoned", "seq": seq,
        }),
    }
}

fn queue_op_name(op: &QueueOp) -> &'static str {
    match op {
        QueueOp::Add { .. } => "add",
        QueueOp::Remove { .. } => "remove",
        QueueOp::Clear => "clear",
        QueueOp::Move { .. } => "move",
    }
}

// ─────────────────────────────────────────────── FROZEN JNI CONTRACT

/// nativeInitGateway(deviceId, deviceName, deviceType) → token (0=fail).
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeInitGateway(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
    device_name: JString,
    device_type: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(id_str) = read_jstring(&mut env, &device_id) else {
            return 0;
        };
        let name_str =
            read_jstring(&mut env, &device_name).unwrap_or_else(|| "Connect Host".into());
        let Some(dt) = DeviceType::from_u8(device_type.clamp(0, 255) as u8) else {
            return 0;
        };
        let self_id = device_id_from_string(&id_str);
        // Deterministic identity seed from the device id string (house
        // pattern: reproducible keys, no RNG at the FFI boundary).
        let seed: [u8; 32] = {
            let mut h = blake3::Hasher::new();
            h.update(b"streamify-connect-identity");
            h.update(id_str.as_bytes());
            let out = h.finalize();
            let mut s = [0u8; 32];
            s.copy_from_slice(out.as_bytes());
            s
        };
        let mut state = ConnectBridgeState {
            gateway: ConnectGateway::new(
                &id_str,
                &name_str,
                dt,
                crate::device_registry::DeviceCaps::typical(dt),
                crate::device_registry::HEARTBEAT_INTERVAL_MS,
                crate::device_registry::LEASE_TTL_MS,
            ),
            intent: RemoteIntentEngine::new(self_id, seed),
            wear: PhoneSyncCoordinator::new(WearSyncConfig::default()),
            started: Instant::now(),
            last_now_ms: 0,
            last_advert_ms: 0,
            next_manifest_id: 1,
            outbound: Vec::new(),
        };
        // Become the ActiveController: a Connect host drives its session.
        state.intent.start_session();
        state.pump_engines();
        let token = INIT_TOKEN.fetch_add(1, Ordering::AcqRel) + 1;
        *heal(BRIDGE.lock()) = Some(state);
        token as jlong
    }))
    .unwrap_or(0)
}

/// nativeStartDiscovery() → Boolean.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeStartDiscovery(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        let started = state.gateway.start_discovery();
        if started {
            // Query probe + immediate first advert go out first.
            let query = state.gateway.build_presence_query();
            state.outbound.push(Outbound { frame: query });
            let now = state.now_ms();
            state.last_advert_ms = now;
            let advert = state.gateway.build_presence_advert();
            state.outbound.push(Outbound { frame: advert });
        }
        jbool(started)
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativeStopDiscovery() → Boolean.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeStopDiscovery(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        jbool(state.gateway.stop_discovery())
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativeTransferPlayback(targetDeviceId, sessionStateJson) → Boolean.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeTransferPlayback(
    mut env: JNIEnv,
    _class: JClass,
    target_device_id: JString,
    session_state_json: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(target_str) = read_jstring(&mut env, &target_device_id) else {
            return JNI_FALSE;
        };
        let Some(json) = read_jstring(&mut env, &session_state_json) else {
            return JNI_FALSE;
        };
        let Some(snapshot) = PlaybackSnapshot::from_json(&json) else {
            return JNI_FALSE;
        };
        let target = device_id_from_string(&target_str);
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        // Defensive routing gate: the target must be a discovered device.
        if state.gateway.registry().get(target).is_none() {
            return JNI_FALSE;
        }
        let now = state.now_ms();
        let ok = state.intent.begin_handoff(target, &snapshot, now).is_ok();
        state.pump_engines();
        jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativeDispatchIntent(targetDeviceId, intentType, payload) → Boolean.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeDispatchIntent(
    mut env: JNIEnv,
    _class: JClass,
    target_device_id: JString,
    intent_type: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(target_str) = read_jstring(&mut env, &target_device_id) else {
            return JNI_FALSE;
        };
        let Some(bytes) = read_jbytes(&mut env, &payload) else {
            return JNI_FALSE;
        };
        let Some(intent) = RemoteIntent::from_kind(intent_type.clamp(0, 255) as u8, &bytes) else {
            return JNI_FALSE;
        };
        let target = device_id_from_string(&target_str);
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        if state.gateway.registry().get(target).is_none() {
            return JNI_FALSE;
        }
        let now = state.now_ms();
        let ok = state.intent.dispatch_intent(intent, now).is_some();
        state.pump_engines();
        jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativeStartWearSync(watchDeviceId, manifestBytes) → Boolean.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeStartWearSync(
    mut env: JNIEnv,
    _class: JClass,
    watch_device_id: JString,
    manifest_bytes: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(watch_str) = read_jstring(&mut env, &watch_device_id) else {
            return JNI_FALSE;
        };
        let Some(blob) = read_jbytes(&mut env, &manifest_bytes) else {
            return JNI_FALSE;
        };
        let Some(tracks) = parse_manifest_blob(&blob) else {
            return JNI_FALSE;
        };
        let watch_id = device_id_from_string(&watch_str);
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        // The watch must be a discovered Wear target.
        let is_watch = state
            .gateway
            .registry()
            .get(watch_id)
            .is_some_and(|d| d.device_type == DeviceType::Watch);
        if !is_watch {
            return JNI_FALSE;
        }
        let manifest_id = state.next_manifest_id;
        state.next_manifest_id = state.next_manifest_id.wrapping_add(1).max(1);
        let now = state.now_ms();
        let refs: Vec<(u64, &[u8])> = tracks.iter().map(|(c, d)| (*c, d.as_slice())).collect();
        let ok = state.wear.start_cache_push(manifest_id, &refs, now);
        state.pump_engines();
        jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

// ─────────────────────────────────────────────── EXTENSIONS (not frozen)

/// nativeOnConnectFrame(frame) — LAN ingress (SCNX advert / RIM / WSX).
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeOnConnectFrame(
    mut env: JNIEnv,
    _class: JClass,
    frame: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(bytes) = read_jbytes(&mut env, &frame) else {
            return JNI_FALSE;
        };
        jbool(route_ingress(&bytes, DiscoveryOrigin::LanBeacon))
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativeOnRelayFrame(frame) — cloud-relay ingress (same bytes, relay
/// origin for SCNX presence; RIM/WSX are channel-agnostic).
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeOnRelayFrame(
    mut env: JNIEnv,
    _class: JClass,
    frame: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(bytes) = read_jbytes(&mut env, &frame) else {
            return JNI_FALSE;
        };
        jbool(route_ingress(&bytes, DiscoveryOrigin::CloudRelay))
    }))
    .unwrap_or(JNI_FALSE)
}

/// Routes an inbound frame to its engine by wire-family magic:
/// SCNX → gateway presence · RIM → intent engine · WSX → wear sync.
fn route_ingress(bytes: &[u8], origin: DiscoveryOrigin) -> bool {
    if bytes.len() < 4 {
        return false;
    }
    let magic32 = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    let magic16 = u16::from_le_bytes([bytes[0], bytes[1]]);
    let mut guard = heal(BRIDGE.lock());
    let Some(state) = guard.as_mut() else {
        return false;
    };
    let now = state.now_ms();
    if magic32 == crate::connect_gateway::CONNECT_MAGIC {
        let ok = match origin {
            DiscoveryOrigin::LanBeacon => state.gateway.on_lan_beacon(bytes, now),
            DiscoveryOrigin::CloudRelay => state.gateway.on_relay_presence(bytes, now),
        };
        state.pump_engines();
        ok
    } else if magic16 == crate::remote_intent_engine::RIM_MAGIC {
        let ok = state.intent.on_frame(bytes, now);
        state.pump_engines();
        ok
    } else if magic16 == crate::wear_sync::WSX_MAGIC {
        let ok = state.wear.on_frame(bytes, now);
        state.pump_engines();
        ok
    } else {
        false // unknown family — refused at the boundary
    }
}

/// nativeTickConnect() — leases, retransmissions, pacer, beacon cadence.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeTickConnect(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return JNI_FALSE;
        };
        state.tick();
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}

/// nativePollConnectFrame() — ONE outbound frame (empty array = none).
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativePollConnectFrame(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let mut guard = heal(BRIDGE.lock());
        let Some(state) = guard.as_mut() else {
            return make_byte_array(&mut env, &[]);
        };
        let frame = if state.outbound.is_empty() {
            Vec::new()
        } else {
            state.outbound.remove(0).frame
        };
        make_byte_array(&mut env, &frame)
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// nativeDrainConnectEvents() — JSON array of pending engine events.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeDrainConnectEvents(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = {
            let mut guard = heal(BRIDGE.lock());
            match guard.as_mut() {
                Some(state) => {
                    let mut items: Vec<serde_json::Value> = Vec::new();
                    for ev in state.gateway.drain_events() {
                        items.push(gateway_event_json(&ev));
                    }
                    for ev in state.intent.drain_events() {
                        items.push(intent_event_json(&ev));
                    }
                    for ev in state.wear.drain_events() {
                        items.push(wear_event_json(&ev));
                    }
                    serde_json::to_string(&items).unwrap_or_else(|_| "[]".into())
                }
                None => "[]".to_string(),
            }
        };
        make_jstring(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// nativeGetDevicesJson() — registry snapshot (device + capability
/// matrix), sorted by device id.
#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustConnectGateway_nativeGetDevicesJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = {
            let guard = heal(BRIDGE.lock());
            match guard.as_ref() {
                Some(state) => {
                    let devices: Vec<serde_json::Value> = state
                        .gateway
                        .registry()
                        .list()
                        .into_iter()
                        .map(|d| {
                            serde_json::json!({
                                "deviceId": format!("{:016x}", d.device_id),
                                "name": d.name,
                                "deviceType": d.device_type.to_string(),
                                "origin": d.origin.to_string(),
                                "codecs": crate::device_registry::codec_names(d.caps.codecs)
                                    .into_iter().collect::<Vec<_>>(),
                                "volumeSteps": d.caps.volume_steps,
                                "directRender": d.caps.direct_render,
                            })
                        })
                        .collect();
                    serde_json::to_string(&devices).unwrap_or_else(|_| "[]".into())
                }
                None => "[]".to_string(),
            }
        };
        make_jstring(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}
