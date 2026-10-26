//! jni_bridge_chunk_sync.rs — JNI surface for the Phase 3 chunk swarm &
//! LAN sync engines (feat/phase3-rust-chunk-swarmer-sync, directive §2.4).
//!
//! ENGINEER 3 BINDS AGAINST `com.streamify.app.sync.NativeChunkSyncEngine`
//! (all `external fun` declarations; Kotlin side owns the class — this
//! crate is strictly `rust/` isolated):
//!
//!   // ── byte-range integrity & hash-tree verification ──
//!   hashBytes(data: ByteArray): ByteArray                 // 32 B Blake3
//!   buildHashTree(data: ByteArray, chunkSize: Int): ByteArray
//!   verifyChunkAgainstTree(manifest: ByteArray, chunkIndex: Int,
//!                          payload: ByteArray): Boolean
//!   verifyFileAgainstTree(manifest: ByteArray, data: ByteArray): Boolean
//!
//!   // ── two-way LAN sync session lifecycle ──
//!   createSyncSession(localCatalog: ByteArray, remoteCatalog: ByteArray,
//!                     policyJson: String): Long            // 0 = failure
//!   startSyncSession(sessionId: Long): Boolean
//!   pauseSyncSession(sessionId: Long): Boolean
//!   resumeSyncSession(sessionId: Long): Boolean
//!   cancelSyncSession(sessionId: Long): Boolean
//!   destroySyncSession(sessionId: Long): Boolean
//!
//!   // ── transfer tracking ──
//!   getFileSyncProgress(sessionId: Long, path: String): Float   // -1 = n/a
//!   getSyncOverallProgress(sessionId: Long): Float
//!   getSyncJobsJson(sessionId: Long): String        // JSON, never null
//!   getSyncSummaryJson(sessionId: Long): String     // JSON, never null
//!   advanceFileSync(sessionId: Long, path: String, direction: Int,
//!                   bytesDelta: Int, fileDone: Boolean): Boolean
//!
//!   // ── chunk completion callbacks ──
//!   registerSyncCallback(callback: Any): Boolean
//!     // Kotlin: class SyncCallback { fun onSyncEvent(eventJson: String) }
//!
//! POLICY JSON (`policyJson`; empty string = engine defaults):
//!   {"allowPull": true, "allowPush": true,
//!    "conflict": "newer_wins",            // skip | newer_wins | keep_both
//!    "maxPushBytes": null}                // null | u64
//!
//! EVENT JSON (one `onSyncEvent` call per drained event):
//!   {"type":"session_started","peer":..,"filesPlanned":..,"bytesPlanned":..}
//!   {"type":"catalogs_exchanged","localEntries":..,"remoteEntries":..,
//!    "pullCandidates":..,"pushCandidates":..,"diverged":..,"identical":..}
//!   {"type":"file_transfer_started","direction":"pull","path":..,
//!    "bytesTotal":..}
//!   {"type":"file_chunk_completed","direction":"pull","path":..,
//!    "bytesDone":..,"bytesTotal":..}
//!   {"type":"file_completed","direction":"pull","path":..,"bytesTotal":..}
//!   {"type":"file_skipped","path":..,"reason":".."}
//!   {"type":"session_completed","summary":{..SyncSummary..}}
//!   {"type":"session_cancelled","summary":{..SyncSummary..}}
//!
//! HOUSE RULES (same discipline as jni_bridge.rs / jni_bridge_p2p.rs):
//!   • Every entry point wraps its body in `catch_unwind` — a panic must
//!     never unwind across the FFI boundary into ART.
//!   • Default returns on failure: jboolean → JNI_FALSE, jlong → 0,
//!     jfloat → -1.0, jstring → "{}"/"[]" (never null: Kotlin declares
//!     non-null String), jbyteArray → empty array.
//!   • Lock-poisoned globals are recovered via `into_inner()`, not panics.
//!   • No Java exceptions are thrown from here; sentinel returns carry
//!     the failure so the Kotlin layer logs-and-degrades.
//!
//! ZERO UI-THREAD BLOCKING: every exported method is a short, lock-scoped
//! state update or a pure computation over caller-supplied buffers — no
//! waits, no sleeps, no lock held across a JNI upcall. The chunk-hash
//! entry points (hashBytes / buildHashTree / verify*) are explicit
//! background-thread APIs: Engineer 3 calls them from the mesh/network
//! thread, never from the UI thread (documented in the PR table). The
//! callback fan-out runs on a dedicated native drainer thread that
//! attaches to the JVM, drains event queues under a SHORT session-map
//! lock, releases it, and only then upcalls into Kotlin.
//!
//! EVENT-QUEUE MEMORY DISCIPLINE: events accumulate only between JNI
//! calls — every session interaction opportunistically drains pending
//! events (delivered to the registered callback, or discarded when no
//! callback is registered). Register the callback BEFORE `start` to
//! observe the full lifecycle.

use std::collections::BTreeMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock, PoisonError};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JObject, JString, ReleaseMode};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use crate::chunk_verifier::{
    hash_payload, ChunkVerifier, HashTreeManifest, MAX_CHUNK_SIZE, MIN_CHUNK_SIZE,
};
use crate::local_sync::{
    ConflictPolicy, Direction, LibraryCatalog, SyncEvent, SyncPolicy, SyncSession,
};

const JNI_TRUE: jboolean = 1;
const JNI_FALSE: jboolean = 0;

/// bool → jboolean (house sentinel convention).
#[inline]
fn jbool(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Direction discriminator for `advanceFileSync` (matches Kotlin enum).
const DIR_PULL: jint = 0;
const DIR_PUSH: jint = 1;

// ─────────────────────────────────────────────── session registry

static SESSIONS: Mutex<BTreeMap<u64, SyncSession>> = Mutex::new(BTreeMap::new());
static NEXT_SESSION_ID: AtomicU64 = AtomicU64::new(1);

#[inline]
fn heal<T>(r: Result<T, PoisonError<T>>) -> T {
    r.unwrap_or_else(|e| e.into_inner())
}

fn direction_from_jint(d: jint) -> Option<Direction> {
    match d {
        DIR_PULL => Some(Direction::Pull),
        DIR_PUSH => Some(Direction::Push),
        _ => None,
    }
}

fn direction_str(d: Direction) -> &'static str {
    match d {
        Direction::Pull => "pull",
        Direction::Push => "push",
    }
}

/// Short-lock session access; drains pending events AFTER the closure
/// runs (callback fan-out happens outside the map lock).
fn with_session<T>(id: jlong, f: impl FnOnce(&mut SyncSession) -> T) -> Option<T> {
    if id <= 0 {
        return None;
    }
    let mut map = heal(SESSIONS.lock());
    let key = id as u64;
    let out = map.get_mut(&key).map(f);
    let drained = match map.get_mut(&key) {
        Some(s) => s.drain_events(),
        None => Vec::new(),
    };
    drop(map);
    deliver_events(drained);
    out
}

// ─────────────────────────────────────────────── callback fan-out

struct CallbackState {
    vm: JavaVM,
    callback: jni::objects::GlobalRef,
}

static CALLBACK: Mutex<Option<CallbackState>> = Mutex::new(None);
static CALLBACK_THREAD_UP: AtomicBool = AtomicBool::new(false);
static CALLBACK_KICK: OnceLock<Mutex<()>> = OnceLock::new();

/// Serializes one event to the callback JSON shape.
fn event_to_json(ev: &SyncEvent) -> serde_json::Value {
    use serde_json::json;
    match ev {
        SyncEvent::SessionStarted {
            peer,
            files_planned,
            bytes_planned,
        } => json!({
            "type": "session_started",
            "peer": peer,
            "filesPlanned": files_planned,
            "bytesPlanned": bytes_planned,
        }),
        SyncEvent::CatalogsExchanged {
            local_entries,
            remote_entries,
            pull_candidates,
            push_candidates,
            diverged,
            identical,
        } => json!({
            "type": "catalogs_exchanged",
            "localEntries": local_entries,
            "remoteEntries": remote_entries,
            "pullCandidates": pull_candidates,
            "pushCandidates": push_candidates,
            "diverged": diverged,
            "identical": identical,
        }),
        SyncEvent::FileTransferStarted {
            direction,
            path,
            bytes_total,
        } => json!({
            "type": "file_transfer_started",
            "direction": direction_str(*direction),
            "path": path,
            "bytesTotal": bytes_total,
        }),
        SyncEvent::FileChunkCompleted {
            direction,
            path,
            bytes_done,
            bytes_total,
        } => json!({
            "type": "file_chunk_completed",
            "direction": direction_str(*direction),
            "path": path,
            "bytesDone": bytes_done,
            "bytesTotal": bytes_total,
        }),
        SyncEvent::FileCompleted {
            direction,
            path,
            bytes_total,
        } => json!({
            "type": "file_completed",
            "direction": direction_str(*direction),
            "path": path,
            "bytesTotal": bytes_total,
        }),
        SyncEvent::FileSkipped { path, reason } => json!({
            "type": "file_skipped",
            "path": path,
            "reason": reason.to_string(),
        }),
        SyncEvent::SessionCompleted(s) => json!({
            "type": "session_completed",
            "summary": s,
        }),
        SyncEvent::SessionCancelled(s) => json!({
            "type": "session_cancelled",
            "summary": s,
        }),
    }
}

/// Delivers drained events to the registered Kotlin callback (or drops
/// them). JVM upcalls happen with NO session lock held — only the
/// short-lived CALLBACK registration lock is held (registration is rare
/// and never contends with the session map).
fn deliver_events(events: Vec<SyncEvent>) {
    if events.is_empty() {
        return;
    }
    let guard = heal(CALLBACK.lock());
    let Some(cb) = guard.as_ref() else {
        return; // no callback registered — bounded discard
    };
    for ev in &events {
        let payload = event_to_json(ev).to_string();
        // Per-event attach/detach keeps the native thread transient; a
        // JVM failure is swallowed (the callback layer must never take
        // the engine down).
        let _ = catch_unwind(AssertUnwindSafe(|| {
            if let Ok(mut env) = cb.vm.attach_current_thread() {
                if let Ok(jstr) = env.new_string(&payload) {
                    let _ = env.call_method(
                        cb.callback.as_obj(),
                        "onSyncEvent",
                        "(Ljava/lang/String;)V",
                        &[(&jstr).into()],
                    );
                }
            }
        }));
    }
}

/// Spawns the background drainer once (first `registerSyncCallback`).
fn spawn_callback_thread() {
    if CALLBACK_THREAD_UP.load(Ordering::Acquire) {
        return;
    }
    let kick = CALLBACK_KICK.get_or_init(|| Mutex::new(()));
    let _guard = heal(kick.lock()); // serialize double-registration
    if CALLBACK_THREAD_UP.swap(true, Ordering::AcqRel) {
        return;
    }
    let spawned = std::thread::Builder::new()
        .name("streamify-chunk-sync-cb".to_string())
        .spawn(|| {
            loop {
                std::thread::sleep(Duration::from_millis(50));
                // Drain every live session under ONE short lock, then
                // deliver outside it.
                let drained: Vec<SyncEvent> = {
                    let mut map = heal(SESSIONS.lock());
                    let mut all = Vec::new();
                    for s in map.values_mut() {
                        all.extend(s.drain_events());
                    }
                    all
                };
                deliver_events(drained);
            }
        });
    if spawned.is_err() {
        // Thread-spawn failure must not brick registration; polling JNI
        // calls still drain opportunistically.
        CALLBACK_THREAD_UP.store(false, Ordering::Release);
    }
}

// ─────────────────────────────────────────────── small JNI helpers

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

fn read_jstring(env: &mut JNIEnv, s: &JString) -> Option<String> {
    match env.get_string(s) {
        Ok(v) => Some(v.into()),
        Err(_) => None,
    }
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

fn parse_policy_json(json: &str) -> Option<SyncPolicy> {
    if json.trim().is_empty() {
        return Some(SyncPolicy::default());
    }
    let v: serde_json::Value = serde_json::from_str(json).ok()?;
    let mut p = SyncPolicy::default();
    if let Some(b) = v.get("allowPull").and_then(|x| x.as_bool()) {
        p.allow_pull = b;
    }
    if let Some(b) = v.get("allowPush").and_then(|x| x.as_bool()) {
        p.allow_push = b;
    }
    if let Some(s) = v.get("conflict").and_then(|x| x.as_str()) {
        p.conflict = match s {
            "skip" => ConflictPolicy::SkipDiverged,
            "newer_wins" => ConflictPolicy::NewerWins,
            "keep_both" => ConflictPolicy::KeepBoth,
            _ => return None, // unknown policy name → refuse loudly
        };
    }
    if let Some(m) = v.get("maxPushBytes") {
        p.max_push_bytes = match m {
            serde_json::Value::Null => None,
            serde_json::Value::Number(n) => Some(n.as_u64()?),
            _ => return None,
        };
    }
    Some(p)
}

// ════════════════════════════════════════════════ verifier entry points

/// `hashBytes(data: ByteArray): ByteArray` — 32-byte Blake3 digest.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_hashBytes(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(bytes) = read_jbytes(&mut env, &data) else {
            return make_byte_array(&mut env, &[]);
        };
        let digest = hash_payload(&bytes);
        make_byte_array(&mut env, &digest)
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `buildHashTree(data: ByteArray, chunkSize: Int): ByteArray` —
/// `HashTreeManifest` wire blob (empty array on any failure: bad chunk
/// size, OOM). `chunkSize` must be within [65536, 1048576].
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_buildHashTree(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    chunk_size: jint,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(bytes) = read_jbytes(&mut env, &data) else {
            return make_byte_array(&mut env, &[]);
        };
        let cs = chunk_size.max(0) as usize;
        let Some(cs) = normalize_chunk_size(cs) else {
            return make_byte_array(&mut env, &[]);
        };
        match HashTreeManifest::build(&bytes, cs) {
            Ok(m) => make_byte_array(&mut env, &m.to_wire()),
            Err(_) => make_byte_array(&mut env, &[]),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn normalize_chunk_size(cs: usize) -> Option<usize> {
    if cs == 0 {
        // 0 = "engine default" convenience for the Kotlin layer.
        return Some(crate::chunk_verifier::MAX_CHUNK_SIZE);
    }
    if (MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&cs) {
        Some(cs)
    } else {
        None
    }
}

/// `verifyChunkAgainstTree(manifest, chunkIndex, payload): Boolean` —
/// the immediate verify-or-reject gate for one incoming chunk.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_verifyChunkAgainstTree(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    chunk_index: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(m_wire) = read_jbytes(&mut env, &manifest) else {
            return JNI_FALSE;
        };
        let Ok(m) = HashTreeManifest::from_wire(&m_wire) else {
            return JNI_FALSE;
        };
        let Some(p) = read_jbytes(&mut env, &payload) else {
            return JNI_FALSE;
        };
        let mut v = ChunkVerifier::new(m);
        let verdict = v.verify_chunk(chunk_index.max(0) as u32, &p);
        if matches!(
            verdict,
            crate::chunk_verifier::ChunkVerdict::Accepted { .. }
                | crate::chunk_verifier::ChunkVerdict::Duplicate { .. }
        ) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// `verifyFileAgainstTree(manifest, data): Boolean` — end-to-end byte
/// equivalence check at transfer completion.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_verifyFileAgainstTree(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    data: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(m_wire) = read_jbytes(&mut env, &manifest) else {
            return JNI_FALSE;
        };
        let Ok(m) = HashTreeManifest::from_wire(&m_wire) else {
            return JNI_FALSE;
        };
        let Some(bytes) = read_jbytes(&mut env, &data) else {
            return JNI_FALSE;
        };
        let v = ChunkVerifier::new(m);
        if v.verify_file(&bytes) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }))
    .unwrap_or(JNI_FALSE)
}

// ══════════════════════════════════════════════════ session lifecycle

/// `createSyncSession(localCatalog, remoteCatalog, policyJson): Long` —
/// negotiates the plan (delta + policy gating). Returns the session id,
/// or 0 on any failure (malformed catalog, bad policy JSON).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_createSyncSession(
    mut env: JNIEnv,
    _class: JClass,
    local_catalog: JByteArray,
    remote_catalog: JByteArray,
    policy_json: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(l_wire) = read_jbytes(&mut env, &local_catalog) else {
            return 0;
        };
        let Some(r_wire) = read_jbytes(&mut env, &remote_catalog) else {
            return 0;
        };
        let Ok(local) = LibraryCatalog::from_wire(&l_wire) else {
            return 0;
        };
        let Ok(remote) = LibraryCatalog::from_wire(&r_wire) else {
            return 0;
        };
        let Some(policy_str) = read_jstring(&mut env, &policy_json) else {
            return 0;
        };
        let Some(policy) = parse_policy_json(&policy_str) else {
            return 0;
        };
        let session = SyncSession::new("lan-peer", &local, &remote, policy);
        let id = NEXT_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let mut map = heal(SESSIONS.lock());
        map.insert(id, session);
        let drained = map
            .get_mut(&id)
            .map(|s| s.drain_events())
            .unwrap_or_default();
        drop(map);
        deliver_events(drained);
        id as jlong
    }))
    .unwrap_or(0)
}

/// `startSyncSession(sessionId): Boolean`.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_startSyncSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        with_session(session_id, |s| s.start())
            .map(jbool)
            .unwrap_or(JNI_FALSE)
    }))
    .unwrap_or(JNI_FALSE)
}

/// `pauseSyncSession(sessionId): Boolean` — pause intent: further
/// progress pushes are rejected until resume.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_pauseSyncSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        with_session(session_id, |s| s.pause())
            .map(jbool)
            .unwrap_or(JNI_FALSE)
    }))
    .unwrap_or(JNI_FALSE)
}

/// `resumeSyncSession(sessionId): Boolean`.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_resumeSyncSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        with_session(session_id, |s| s.resume())
            .map(jbool)
            .unwrap_or(JNI_FALSE)
    }))
    .unwrap_or(JNI_FALSE)
}

/// `cancelSyncSession(sessionId): Boolean` — terminal intent.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_cancelSyncSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        with_session(session_id, |s| s.cancel())
            .map(jbool)
            .unwrap_or(JNI_FALSE)
    }))
    .unwrap_or(JNI_FALSE)
}

/// `destroySyncSession(sessionId): Boolean` — drops the session.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_destroySyncSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if session_id <= 0 {
            return JNI_FALSE;
        }
        let mut map = heal(SESSIONS.lock());
        jbool(map.remove(&(session_id as u64)).is_some())
    }))
    .unwrap_or(JNI_FALSE)
}

// ═════════════════════════════════════════════════ transfer tracking

/// `getFileSyncProgress(sessionId, path): Float` — per-file fraction in
/// [0, 1], or -1.0 for unknown session/job.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_getFileSyncProgress(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    path: JString,
) -> jfloat {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(path) = read_jstring(&mut env, &path) else {
            return -1.0;
        };
        with_session(session_id, |s| s.file_progress(&path))
            .unwrap_or(None)
            .unwrap_or(-1.0)
    }))
    .unwrap_or(-1.0)
}

/// `getSyncOverallProgress(sessionId): Float` — session-wide fraction.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_getSyncOverallProgress(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jfloat {
    catch_unwind(AssertUnwindSafe(|| {
        with_session(session_id, |s| s.overall_progress()).unwrap_or(-1.0)
    }))
    .unwrap_or(-1.0)
}

/// `getSyncJobsJson(sessionId): String` — the planned job list (transport
/// layer work queue). `"[]"` on failure, never null.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_getSyncJobsJson(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        use serde_json::json;
        let jobs = with_session(session_id, |s| {
            s.jobs()
                .into_iter()
                .map(|f| {
                    json!({
                        "direction": direction_str(f.direction),
                        "path": f.path,
                        "bytesTotal": f.bytes_total,
                        "bytesDone": f.bytes_done,
                        "done": f.done,
                    })
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
        let text = serde_json::to_string(&jobs).unwrap_or_else(|_| "[]".to_string());
        make_jstring(&mut env, &text)
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `getSyncSummaryJson(sessionId): String` — terminal/interim report.
/// `"{}"` on failure, never null.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_getSyncSummaryJson(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let summary = with_session(session_id, |s| s.summary());
        let text = match summary {
            Some(s) => serde_json::to_string(&s).unwrap_or_else(|_| "{}".to_string()),
            None => "{}".to_string(),
        };
        make_jstring(&mut env, &text)
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `advanceFileSync(sessionId, path, direction, bytesDelta, fileDone):
/// Boolean` — the transport layer's progress push: `bytesDelta` chunk
/// bytes just landed; `fileDone` marks the payload complete.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_advanceFileSync(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    path: JString,
    direction: jint,
    bytes_delta: jint,
    file_done: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(path) = read_jstring(&mut env, &path) else {
            return JNI_FALSE;
        };
        let Some(dir) = direction_from_jint(direction) else {
            return JNI_FALSE;
        };
        let delta = bytes_delta.max(0) as u64;
        with_session(session_id, |s| {
            let progressed = s.on_bytes_transferred(dir, &path, delta).is_ok();
            let completed = if file_done == JNI_TRUE {
                s.on_file_completed(dir, &path).is_ok()
            } else {
                true
            };
            jbool(progressed && completed)
        })
        .unwrap_or(JNI_FALSE)
    }))
    .unwrap_or(JNI_FALSE)
}

// ═════════════════════════════════════════════════════ callback hook

/// `registerSyncCallback(callback: Any): Boolean` — stores a global
/// reference and spawns the drainer thread; the callback receives one
/// `onSyncEvent(String)` upcall per event, delivered off the session
/// lock on a dedicated native thread.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_sync_NativeChunkSyncEngine_registerSyncCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let vm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(_) => return JNI_FALSE,
        };
        let global = match env.new_global_ref(callback) {
            Ok(g) => g,
            Err(_) => return JNI_FALSE,
        };
        {
            let mut guard = heal(CALLBACK.lock());
            *guard = Some(CallbackState {
                vm,
                callback: global,
            });
        }
        spawn_callback_thread();
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}
