// Clippy: the JNI surface is a wall of `unsafe extern "C"` entry points
// that ALL follow the same documented house safety model (catch_unwind
// shields, sentinel returns, no panics across the boundary — see the
// module docs). Per-function `# Safety` boilerplate would add ~60 lines
// of copy-paste; the model is documented once, here and below.
#![allow(clippy::missing_safety_doc)]

use jni::objects::{JByteArray, JClass, JFloatArray, JLongArray, JObject, JString, ReleaseMode};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::auth::generate_sapisid_hash;
use crate::consensus::{ByzantineConsensusEngine, MeshCandidateSubmission};
use crate::ptp::{PtpEngine, PtpPacket};
use crate::repository::generate_cad_id;
use crate::resolver::resolve_track_cdn;

// ═══════════════════════════════════════════════════════════════════
// PANIC SHIELD CONTRACT
//
// Every JNI entry point below wraps its entire body in catch_unwind.
// A panic must NEVER unwind across the `extern "C"` boundary (that
// aborts the host Android process). Instead each function maps a panic
// onto its own error convention:
//   jint  -> negative code (-10 JNI error unless the fn documents else)
//   jlong -> 0 (null opaque handle)
//   jboolean -> 0 (false)
//   jstring  -> "" (never null: Kotlin side is non-null String)
//   ()    -> swallowed
// ═══════════════════════════════════════════════════════════════════

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateSapisidHash(
    mut env: JNIEnv,
    _class: JClass,
    sapisid: JString,
    origin: JString,
    out_buffer: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let sapisid_str: String = match env.get_string(&sapisid) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let origin_str: String = match env.get_string(&origin) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let mut out_elements = match env.get_array_elements(&out_buffer, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        generate_sapisid_hash(
            sapisid_str.as_ptr(),
            sapisid_str.len(),
            origin_str.as_ptr(),
            origin_str.len(),
            out_elements.as_mut_ptr() as *mut u8,
            out_elements.len(),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateCadId(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_sec: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let title_str: String = env.get_string(&title).map(|s| s.into()).unwrap_or_default();
        let artist_str: String = env
            .get_string(&artist)
            .map(|s| s.into())
            .unwrap_or_default();
        let cad_id = generate_cad_id(&title_str, &artist_str, duration_sec.max(0) as u32);
        match env.new_string(cad_id) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    // Never hand null back across a non-null Kotlin String contract: fall
    // back to an empty CAD-ID string, then to null only if even that fails.
    result.unwrap_or_else(|_| {
        catch_unwind(AssertUnwindSafe(|| {
            env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }))
        .unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeIngestSpotifyTracks(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    json_payload: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let json_str: String = match env.get_string(&json_payload) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let repo = match crate::repository::TrackRepository::new(&db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.batch_upsert_spotify_tracks(&json_str)
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFetchVirtualShelf(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    out_buffer: JObject,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let buf_ptr = match env.get_direct_buffer_address((&out_buffer).into()) {
            Ok(ptr) => ptr,
            Err(_) => return -10,
        };
        let capacity = env
            .get_direct_buffer_capacity((&out_buffer).into())
            .unwrap_or(0);

        if buf_ptr.is_null() || capacity < 4 {
            return -10;
        }

        let repo = match crate::repository::TrackRepository::new(&db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.fetch_virtual_shelf_to_buffer(buf_ptr, capacity)
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResolveTrack(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    cad_id: JString,
    isrc: JString,
    title: JString,
    artist: JString,
    auth_header: JString,
    cookies_bytes: JByteArray,
    cookies_len: jint,
    out_buffer: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let cad_str: String = match env.get_string(&cad_id) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let isrc_str: Option<String> = if isrc.is_null() {
            None
        } else {
            env.get_string(&isrc).ok().map(|s| s.into())
        };
        let title_str: String = match env.get_string(&title) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let artist_str: String = match env.get_string(&artist) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let auth_str: String = if auth_header.is_null() {
            "".to_string()
        } else {
            env.get_string(&auth_header)
                .map(|s| s.into())
                .unwrap_or_default()
        };
        let cookies_actual = env.get_array_length(&cookies_bytes).unwrap_or(0).max(0) as usize;
        let cookies_len = (cookies_len.max(0) as usize).min(cookies_actual);
        let cookies_elements = env
            .get_array_elements(&cookies_bytes, ReleaseMode::NoCopyBack)
            .ok();

        let out_actual = env.get_array_length(&out_buffer).unwrap_or(0).max(0);
        let mut out_elements = match env.get_array_elements(&out_buffer, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_cad = match std::ffi::CString::new(cad_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_isrc = isrc_str.and_then(|s| std::ffi::CString::new(s).ok());
        let c_title = match std::ffi::CString::new(title_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_artist = match std::ffi::CString::new(artist_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_auth = match std::ffi::CString::new(auth_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        resolve_track_cdn(
            c_db.as_ptr(),
            c_cad.as_ptr(),
            c_isrc.as_ref().map_or(std::ptr::null(), |c| c.as_ptr()),
            c_title.as_ptr(),
            c_artist.as_ptr(),
            c_auth.as_ptr(),
            cookies_elements
                .as_ref()
                .map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            cookies_len,
            out_elements.as_mut_ptr() as *mut u8,
            out_actual as usize,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyExchangePkce(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
    verifier: JString,
    redirect_uri: JString,
    client_id: JString,
    out_access: JByteArray,
    out_refresh: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let code_str: String = match env.get_string(&code) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let verifier_str: String = match env.get_string(&verifier) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let redirect_str: String = match env.get_string(&redirect_uri) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let client_id_str: String = if client_id.is_null() {
            "37b8d4f407764d8dbda2f94356e792c3".to_string()
        } else {
            env.get_string(&client_id)
                .map(|s| s.into())
                .unwrap_or_else(|_| "37b8d4f407764d8dbda2f94356e792c3".to_string())
        };

        let access_actual = env.get_array_length(&out_access).unwrap_or(0).max(0) as usize;
        let refresh_actual = env.get_array_length(&out_refresh).unwrap_or(0).max(0) as usize;

        let mut access_elements = match env.get_array_elements(&out_access, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let mut refresh_elements = match env.get_array_elements(&out_refresh, ReleaseMode::CopyBack)
        {
            Ok(e) => e,
            Err(_) => return -10,
        };

        let c_code = match std::ffi::CString::new(code_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_verifier = match std::ffi::CString::new(verifier_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_redirect = match std::ffi::CString::new(redirect_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_client_id = match std::ffi::CString::new(client_id_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::spotify_ingest::spotify_exchange_pkce(
            c_code.as_ptr(),
            c_verifier.as_ptr(),
            c_redirect.as_ptr(),
            c_client_id.as_ptr(),
            access_elements.as_mut_ptr() as *mut u8,
            access_actual,
            refresh_elements.as_mut_ptr() as *mut u8,
            refresh_actual,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyIngestLibrary(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let token_str: String = match env.get_string(&access_token) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_token = match std::ffi::CString::new(token_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::spotify_ingest::spotify_ingest_library(c_db.as_ptr(), c_token.as_ptr())
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFindActiveSlyrLine(
    mut env: JNIEnv,
    _class: JClass,
    slyr_buffer: JByteArray,
    slyr_len: jint,
    playhead_ms: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let actual = env.get_array_length(&slyr_buffer).unwrap_or(0).max(0) as usize;
        // The declared length was previously trusted over the real array
        // length -> OOB read. Clamp instead.
        let slen = (slyr_len.max(0) as usize).min(actual);
        if slen == 0 {
            return -1;
        }

        let elements = match env.get_array_elements(&slyr_buffer, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -1,
        };

        let slice = std::slice::from_raw_parts(elements.as_ptr() as *const u8, slen);
        crate::lyrics::SlyrCompiler::find_active_line(slice, playhead_ms.max(0) as u32)
            .map(|idx| idx as jint)
            .unwrap_or(-1)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCalculatePtp(
    env: JNIEnv,
    _class: JClass,
    seq_id: jint,
    t0: jlong,
    t1: jlong,
    t2: jlong,
    t3: jlong,
    out_results: JLongArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let packet = PtpPacket {
            sequence_id: seq_id.max(0) as u32,
            t0_origin_send: t0.max(0) as u64,
            t1_host_receive: t1.max(0) as u64,
            t2_host_transmit: t2.max(0) as u64,
            t3_client_receive: t3.max(0) as u64,
        };

        let (offset_us, delay_us) = PtpEngine::calculate_offset_and_delay(&packet);
        let results = [offset_us as jlong, delay_us as jlong];
        let _ = env.set_long_array_region(&out_results, 0, &results);
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeVerifyPeerConsensus(
    mut env: JNIEnv,
    _class: JClass,
    node1: JString,
    lufs1: jfloat,
    key1: JString,
    vec1: JFloatArray,
    _proof1: JByteArray,
    node2: JString,
    lufs2: jfloat,
    key2: JString,
    vec2: JFloatArray,
    _proof2: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let node1_str: String = match env.get_string(&node1) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let node2_str: String = match env.get_string(&node2) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let key1_str: String = match env.get_string(&key1) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let key2_str: String = match env.get_string(&key2) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };

        let v1_elems = match env.get_array_elements(&vec1, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return 0,
        };
        let v2_elems = match env.get_array_elements(&vec2, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return 0,
        };

        if v1_elems.len() < 128 || v2_elems.len() < 128 {
            return 0;
        }

        let mut vector1 = [0.0f32; 128];
        let mut vector2 = [0.0f32; 128];
        vector1.copy_from_slice(&v1_elems[..128]);
        vector2.copy_from_slice(&v2_elems[..128]);

        let peer1 = MeshCandidateSubmission {
            node_id: node1_str,
            lufs: lufs1,
            camelot_key: key1_str,
            vector: vector1,
            proof_digest: [0u8; 32],
        };

        let peer2 = MeshCandidateSubmission {
            node_id: node2_str,
            lufs: lufs2,
            camelot_key: key2_str,
            vector: vector2,
            proof_digest: [0u8; 32],
        };

        if ByzantineConsensusEngine::verify_peer_consensus(&peer1, &peer2) {
            1
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitDsp(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        crate::audio_dsp::init_audio_dsp()
    }))
    .unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeDsp(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::audio_dsp::free_audio_dsp(state_ptr as *mut crate::audio_dsp::DspState);
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeProcessDsp(
    env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    input_buffer: JObject,
    output_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Validate against the DIRECT BUFFER CAPACITY, not just non-nullness:
        // stereo interleaved, i16 in (2 B/sample), f32 out (4 B/sample).
        let frames = num_frames as usize;
        let need_in = frames * 2 * std::mem::size_of::<i16>();
        let need_out = frames * 2 * std::mem::size_of::<f32>();
        let in_cap = env
            .get_direct_buffer_capacity((&input_buffer).into())
            .unwrap_or(0);
        let out_cap = env
            .get_direct_buffer_capacity((&output_buffer).into())
            .unwrap_or(0);
        if in_cap < need_in || out_cap < need_out {
            return -2;
        }

        let input_ptr = match env.get_direct_buffer_address((&input_buffer).into()) {
            Ok(p) => p as *const i16,
            Err(_) => return -10,
        };
        let output_ptr = match env.get_direct_buffer_address((&output_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if input_ptr.is_null() || output_ptr.is_null() {
            return -10;
        }

        crate::audio_dsp::process_audio_dsp(
            state_ptr as *mut crate::audio_dsp::DspState,
            input_ptr,
            output_ptr,
            frames,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetNextTrack(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    current_cad_id: JString,
    is_shuffle: jboolean,
    out_cad: JByteArray,
    out_video: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let cad_str: String = match env.get_string(&current_cad_id) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let cad_actual = env.get_array_length(&out_cad).unwrap_or(0).max(0) as usize;
        let vid_actual = env.get_array_length(&out_video).unwrap_or(0).max(0) as usize;

        let mut cad_elements = match env.get_array_elements(&out_cad, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let mut vid_elements = match env.get_array_elements(&out_video, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_cad = match std::ffi::CString::new(cad_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::queue_engine::get_next_track(
            c_db.as_ptr(),
            c_cad.as_ptr(),
            if is_shuffle != 0 { 1 } else { 0 },
            cad_elements.as_mut_ptr() as *mut u8,
            cad_actual,
            vid_elements.as_mut_ptr() as *mut u8,
            vid_actual,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyDeltaSync(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
    last_sync_timestamp: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let token_str: String = match env.get_string(&access_token) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_token = match std::ffi::CString::new(token_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::queue_engine::spotify_delta_sync(
            c_db.as_ptr(),
            c_token.as_ptr(),
            last_sync_timestamp,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeShutdown(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !db_path.is_null() {
            if let Ok(db_str) = env.get_string(&db_path) {
                let s: String = db_str.into();
                if let Ok(c_db) = std::ffi::CString::new(s) {
                    crate::queue_engine::shutdown_engine(c_db.as_ptr());
                }
            }
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeParseLrc(
    mut env: JNIEnv,
    _class: JClass,
    lrc_text: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if lrc_text.is_null() {
            return 0;
        }
        let lrc_str: String = match env.get_string(&lrc_text) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let bytes = lrc_str.as_bytes();
        if bytes.is_empty() {
            return 0;
        }
        crate::lyrics::parse_lrc_file(bytes.as_ptr() as *const std::os::raw::c_char, bytes.len())
            as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetLyricIndex(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
    current_time_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if map_ptr == 0 {
            return -1;
        }
        crate::lyrics::get_lyric_index(
            map_ptr as *mut crate::lyrics::LyricMap,
            current_time_ms.max(0) as u64,
        )
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeLyricMap(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if map_ptr != 0 {
            crate::lyrics::free_lyric_map(map_ptr as *mut crate::lyrics::LyricMap);
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCryptCacheChunk(
    mut env: JNIEnv,
    _class: JClass,
    input_buf: JByteArray,
    output_buf: JByteArray,
    len: jint,
    key_buf: JByteArray,
    offset: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if len <= 0 {
            return -1;
        }
        let n = len as usize;
        // Previously `len` was trusted over real array lengths -> OOB read AND
        // write, plus no aliasing/emptiness checks on the key material.
        let in_actual = env.get_array_length(&input_buf).unwrap_or(0).max(0) as usize;
        let out_actual = env.get_array_length(&output_buf).unwrap_or(0).max(0) as usize;
        let key_actual = env.get_array_length(&key_buf).unwrap_or(0).max(0) as usize;
        if in_actual < n || out_actual < n || key_actual == 0 || offset < 0 {
            return -2;
        }

        let input_elements = match env.get_array_elements(&input_buf, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let mut output_elements = match env.get_array_elements(&output_buf, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let key_elements = match env.get_array_elements(&key_buf, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        crate::cache::crypt_audio_chunk(
            input_elements.as_ptr() as *const u8,
            output_elements.as_mut_ptr() as *mut u8,
            n,
            key_elements.as_ptr() as *const u8,
            key_elements.len(),
            offset as u64,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeUpdateThermalStatus(
    _env: JNIEnv,
    _class: JClass,
    status: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        crate::governor::update_thermal_status(status)
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetThermalStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        crate::governor::get_thermal_status()
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFlushDatabaseWal(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if db_path.is_null() {
            return -1;
        }
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -1,
        };
        crate::governor::flush_database_wal(c_db.as_ptr())
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitNormalizer(
    _env: JNIEnv,
    _class: JClass,
    target_rms: jfloat,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::normalizer::init_normalizer(target_rms)
    }))
    .unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeNormalizer(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::normalizer::free_normalizer(
                state_ptr as *mut crate::normalizer::NormalizerState,
            );
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeApplyNormalization(
    env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    pcm_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Stereo interleaved f32 in-place: validate against real capacity.
        let need = num_frames as usize * 2 * std::mem::size_of::<f32>();
        let cap = env
            .get_direct_buffer_capacity((&pcm_buffer).into())
            .unwrap_or(0);
        if cap < need {
            return -2;
        }

        let pcm_ptr = match env.get_direct_buffer_address((&pcm_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if pcm_ptr.is_null() {
            return -10;
        }

        crate::normalizer::apply_normalization(
            state_ptr as *mut crate::normalizer::NormalizerState,
            pcm_ptr,
            num_frames as usize,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeProcessFusedAudio(
    env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    normalizer_ptr: jlong,
    input_buffer: JObject,
    output_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Validate against DIRECT BUFFER CAPACITY, not just non-nullness:
        // stereo interleaved, i16 in (2 B/sample), f32 out (4 B/sample).
        let frames = num_frames as usize;
        let need_in = frames * 2 * std::mem::size_of::<i16>();
        let need_out = frames * 2 * std::mem::size_of::<f32>();
        let in_cap = env
            .get_direct_buffer_capacity((&input_buffer).into())
            .unwrap_or(0);
        let out_cap = env
            .get_direct_buffer_capacity((&output_buffer).into())
            .unwrap_or(0);
        if in_cap < need_in || out_cap < need_out {
            return -2;
        }

        let input_ptr = match env.get_direct_buffer_address((&input_buffer).into()) {
            Ok(p) => p as *const i16,
            Err(_) => return -10,
        };
        let output_ptr = match env.get_direct_buffer_address((&output_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if input_ptr.is_null() || output_ptr.is_null() {
            return -10;
        }

        // ONE crossing: DSP conversion/EQ then RMS normalization operate
        // back-to-back on the same f32 output region — zero intermediate copies.
        let dsp_result = crate::audio_dsp::process_audio_dsp(
            state_ptr as *mut crate::audio_dsp::DspState,
            input_ptr,
            output_ptr,
            frames,
        );
        if dsp_result != 0 {
            return dsp_result;
        }
        if normalizer_ptr != 0 {
            crate::normalizer::apply_normalization(
                normalizer_ptr as *mut crate::normalizer::NormalizerState,
                output_ptr,
                frames,
            );
        }
        0
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeSubmitSeekRequest(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        crate::seek_guard::submit_seek_request(position_ms)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeConsumePendingSeek(
    _env: JNIEnv,
    _class: JClass,
    debounce_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::seek_guard::consume_pending_seek(debounce_ms)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResetSeekGuard(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        crate::seek_guard::reset_seek_guard()
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitContinuumState(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        crate::continuum_engine::init_continuum_state()
    }))
    .unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeContinuumState(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::continuum_engine::free_continuum_state(
                state_ptr as *mut crate::continuum_engine::ContinuumState,
            );
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeEvaluateContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    candidates: JFloatArray,
    out_scores: JFloatArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 {
            return -1;
        }
        let cand_len = match env.get_array_length(&candidates) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };
        let score_len = match env.get_array_length(&out_scores) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };

        let candidate_count = cand_len / 128;
        if candidate_count == 0 || score_len < candidate_count {
            return -2;
        }

        let cand_elements = match env.get_array_elements(&candidates, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let mut score_elements = match env.get_array_elements(&out_scores, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        crate::continuum_engine::evaluate_continuum_batch(
            state_ptr as *mut crate::continuum_engine::ContinuumState,
            cand_elements.as_ptr(),
            candidate_count,
            score_elements.as_mut_ptr(),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCommitTrackToContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    track_vector: JFloatArray,
    artist_hash: jlong,
    track_hash: jlong,
    dwell_percentage: jfloat,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 {
            return -1;
        }
        let vec_len = match env.get_array_length(&track_vector) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };
        if vec_len < 128 {
            return -2;
        }

        let vec_elements = match env.get_array_elements(&track_vector, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        crate::continuum_engine::commit_track_to_continuum(
            state_ptr as *mut crate::continuum_engine::ContinuumState,
            vec_elements.as_ptr(),
            artist_hash.max(0) as u64,
            track_hash.max(0) as u64,
            dwell_percentage.clamp(0.0, 100.0),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_stepAirDropPhysics(
    mut env: JNIEnv,
    _class: JClass,
    in_out_buffer: JFloatArray,
    target_x: jfloat,
    target_y: jfloat,
    initial_dist: jfloat,
    dt: jfloat,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let len = match env.get_array_length(&in_out_buffer) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return,
        };
        if len < 14 {
            return;
        }

        let mut elements = match env.get_array_elements(&in_out_buffer, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return,
        };

        let mut state = crate::airdrop::AirdropState {
            pos: [elements[0], elements[1], elements[2]],
            vel: [elements[3], elements[4], elements[5]],
            stretch_parallel: elements[6],
            stretch_perp: elements[7],
            rotation_rad: elements[8],
            pitch_deg: elements[9],
            roll_deg: elements[10],
            impact_progress: elements[11],
            is_docked: elements[12] > 0.5,
            is_ready_to_dock: elements[13] > 0.5,
            ..Default::default()
        };

        crate::airdrop::AirdropPhysicsEngine::step(
            &mut state,
            target_x,
            target_y,
            initial_dist,
            dt,
            128,
        );

        elements[0] = state.pos[0];
        elements[1] = state.pos[1];
        elements[2] = state.pos[2];
        elements[3] = state.vel[0];
        elements[4] = state.vel[1];
        elements[5] = state.vel[2];
        elements[6] = state.stretch_parallel;
        elements[7] = state.stretch_perp;
        elements[8] = state.rotation_rad;
        elements[9] = state.pitch_deg;
        elements[10] = state.roll_deg;
        elements[11] = state.impact_progress;
        elements[12] = if state.is_docked { 1.0 } else { 0.0 };
        // Writeback was previously missing entirely: Kotlin never saw the
        // updated readiness flag.
        elements[13] = if state.is_ready_to_dock { 1.0 } else { 0.0 };
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativePinThreadToLittleCores(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let res = catch_unwind(AssertUnwindSafe(|| {
        crate::task_orchestrator::pin_current_thread_to_little_cores()
    }));
    match res {
        Ok(true) => 1,
        _ => 0,
    }
}

// ═══════════════════════════════════════════════════════════════════
// JAM PHASE-1 SYNC CORE: jam_clock / tick_matrix / kalman_pll
// ═══════════════════════════════════════════════════════════════════

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamClockReset(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        crate::jam_clock::JamClock::reset();
    }));
}

/// Ingest one Cristian handshake sample; writes [theta_ms, delta_ms] into out.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamClockApplySample(
    env: JNIEnv,
    _class: JClass,
    t0: jlong,
    t1: jlong,
    t2: jlong,
    t3: jlong,
    out_results: JLongArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let (theta, delta) = crate::jam_clock::JamClock::apply_sample(t0, t1, t2, t3);
        let results = [theta as jlong, delta as jlong];
        let _ = env.set_long_array_region(&out_results, 0, &results);
    }));
}

/// THE Jam time source: guest-local monotonic mapped into the host timeline.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetSyncedJamMonotonicMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::jam_clock::JamClock::synced_monotonic_ms() as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamClockRttMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::jam_clock::JamClock::last_rtt_ms() as jlong
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamTickIngest(
    env: JNIEnv,
    _class: JClass,
    seq: jint,
    pos_ms: jlong,
    host_mono_ms: jlong,
    state: jint,
    policy: jint,
    out_packed: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        // Copy-out buffer: JNI primitive arrays cannot be indexed directly.
        let len = env.get_array_length(&out_packed).unwrap_or(0);
        if len == 0 {
            return 0i32;
        }
        let mut scratch = vec![0i64; len as usize];
        let written = crate::tick_matrix::TickMatrix::ingest(
            seq.max(0) as u32,
            pos_ms,
            host_mono_ms,
            state.clamp(0, 2) as u8,
            policy.clamp(0, 1) as u8,
            &mut scratch,
        );
        if written > 0 {
            let _ = env.set_long_array_region(&out_packed, 0, &scratch[..written]);
        }
        written as jint
    }))
    .unwrap_or(0)
}

/// Kalman PLL decision point. Writes [decision, speed_milli, seek_target_ms].
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeKalmanPllDecide(
    env: JNIEnv,
    _class: JClass,
    z_pos_ms: jlong,
    now_synced_ms: jlong,
    tick_host_mono_ms: jlong,
    playing: jboolean,
    out_results: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let mut out = [0i64; 3];
        let decision = crate::kalman_pll::KalmanPll::decide(
            z_pos_ms,
            now_synced_ms,
            tick_host_mono_ms,
            playing != 0,
            &mut out,
        );
        let _ = env.set_long_array_region(&out_results, 0, &out);
        decision as jint
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeKalmanPllReset(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        crate::kalman_pll::KalmanPll::reset();
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeTickMatrixDiagnostics(
    env: JNIEnv,
    _class: JClass,
    out_results: JLongArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let diag = crate::tick_matrix::TickMatrix::diagnostics();
        let _ = env.set_long_array_region(&out_results, 0, &diag);
    }));
}

/// Raw device-local monotonic ms (offset-free) — handshake endpoint stamps.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetLocalMonotonicMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::jam_clock::JamClock::local_monotonic_ms() as jlong
    }))
    .unwrap_or(0)
}

// ═══════════════════════════════════════════════════════════════════
// JAM PHASE-2 STATE CORE: jam_crdt + jam_outbox
// ═══════════════════════════════════════════════════════════════════

use crate::jam_crdt::{JamCrdtState, JamOp};
use crate::jam_outbox::JamOutbox;

static JAM_CRDT: std::sync::Mutex<Option<JamCrdtState>> = std::sync::Mutex::new(None);
static JAM_OUTBOX: std::sync::Mutex<Option<JamOutbox>> = std::sync::Mutex::new(None);

#[inline]
fn crdt_with<T>(f: impl FnOnce(&mut JamCrdtState) -> T) -> Option<T> {
    let mut guard = JAM_CRDT.lock().ok()?;
    let st = guard.get_or_insert_with(JamCrdtState::new);
    Some(f(st))
}

#[inline]
fn outbox_with<T>(f: impl FnOnce(&JamOutbox) -> T) -> Option<T> {
    let guard = JAM_OUTBOX.lock().ok()?;
    let ob = guard.as_ref()?;
    Some(f(ob))
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamCrdtReset(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut g) = JAM_CRDT.lock() {
            *g = Some(JamCrdtState::new());
        }
    }));
}

/// Applies one op. checksum == -1 seals locally (UI-generated); otherwise the
/// wire checksum is honored and validated. Returns 1 applied / 0 rejected.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamCrdtApplyOp(
    _env: JNIEnv,
    _class: JClass,
    op_id: jlong,
    sender_packed: jlong, // 4 device-nonce bytes in low half
    op_type: jint,
    policy_flags: jint,
    cad_id: jlong,
    frac_bits: jlong,
    target_add_op_id: jlong,
    checksum: jlong, // -1 => seal locally
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let mut nonce = [0u8; 4];
        nonce.copy_from_slice(&(sender_packed as u32).to_be_bytes());
        let mut op = JamOp {
            op_id: op_id as u64,
            sender_nonce: nonce,
            op_type: op_type.clamp(0, 255) as u8,
            policy_flags: policy_flags.clamp(0, 255) as u8,
            _pad1: [0; 2],
            track_cad_id: cad_id as u64,
            frac_index: f64::from_bits(frac_bits as u64),
            target_add_op_id: target_add_op_id as u64,
            checksum: if checksum < 0 { 0 } else { checksum as u32 },
            _pad2: 0,
        };
        if checksum < 0 {
            op.checksum = op.compute_checksum();
        }
        crdt_with(|st| st.apply_op(&op) as jint)
    }))
    .unwrap_or(Some(0))
    .unwrap_or(0)
}

/// Fold: fills out_queue with triplets [fracBits, addOpId, cadId] and
/// out_tomb with suppressed ids. Returns queue-entry count.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamCrdtFold(
    env: JNIEnv,
    _class: JClass,
    out_queue: JLongArray,
    out_tomb: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Some((queue, tomb)) = crdt_with(|st| st.fold_to_snapshot()) else {
            return 0;
        };
        let n = ((env.get_array_length(&out_queue).unwrap_or(0) / 3).min(queue.len() as i32))
            .min(2048) as usize;
        let mut flat = Vec::with_capacity(n * 3);
        for (frac, add_id, cad) in queue.iter().take(n) {
            flat.push(*frac as jlong);
            flat.push(*add_id as jlong);
            flat.push(*cad as jlong);
        }
        let _ = env.set_long_array_region(&out_queue, 0, &flat);

        let tn = (env.get_array_length(&out_tomb).unwrap_or(0)).min(tomb.len() as i32) as usize;
        let tflat: Vec<jlong> = tomb.iter().take(tn).map(|&id| id as jlong).collect();
        let _ = env.set_long_array_region(&out_tomb, 0, &tflat);
        n as jint
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamGenerateOpId(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| JamOp::generate_op_id() as jlong)).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamCanonicalCadId(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_sec: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let t: String = env.get_string(&title).map(|s| s.into()).unwrap_or_default();
        let a: String = env
            .get_string(&artist)
            .map(|s| s.into())
            .unwrap_or_default();
        crate::repository::generate_cad_id_u64(&t, &a, duration_sec.max(0) as u32) as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxOpen(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&db_path)
            .map(|s| s.into())
            .unwrap_or_default();
        if path.is_blank_path() {
            return -1;
        }
        match JamOutbox::new(std::path::Path::new(&path)) {
            Ok(ob) => {
                if let Ok(mut g) = JAM_OUTBOX.lock() {
                    *g = Some(ob);
                }
                1
            }
            Err(_) => -2,
        }
    }))
    .unwrap_or(-3)
}

#[allow(clippy::needless_return)]
trait IsBlankPath {
    fn is_blank_path(&self) -> bool;
}
impl IsBlankPath for String {
    fn is_blank_path(&self) -> bool {
        self.trim().is_empty()
    }
}

/// Enqueues one sealed op into the outbox journal. Returns true when written.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxEnqueue(
    mut env: JNIEnv,
    _class: JClass,
    op_id: jlong,
    sender_packed: jlong,
    op_type: jint,
    policy_flags: jint,
    cad_id: jlong,
    frac_bits: jlong,
    target_add_op_id: jlong,
    session_code: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let session: String = env
            .get_string(&session_code)
            .map(|s| s.into())
            .unwrap_or_default();
        let mut nonce = [0u8; 4];
        nonce.copy_from_slice(&(sender_packed as u32).to_be_bytes());
        let mut op = JamOp {
            op_id: op_id as u64,
            sender_nonce: nonce,
            op_type: op_type.clamp(0, 255) as u8,
            policy_flags: policy_flags.clamp(0, 255) as u8,
            _pad1: [0; 2],
            track_cad_id: cad_id as u64,
            frac_index: f64::from_bits(frac_bits as u64),
            target_add_op_id: target_add_op_id as u64,
            checksum: 0,
            _pad2: 0,
        };
        op.checksum = op.compute_checksum();
        outbox_with(|ob| match ob.enqueue(&op, &session) {
            Ok(written) => written as jboolean,
            Err(_) => 0,
        })
    }))
    .unwrap_or(Some(0))
    .unwrap_or(0)
}

/// Polls pending ops for a session. out_ops stride 7:
/// [op_id, type|policy<<8|nonce, cad, fracBits, target, checksum, 0].
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxPoll(
    mut env: JNIEnv,
    _class: JClass,
    session_code: JString,
    limit: jint,
    out_ops: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let session: String = env
            .get_string(&session_code)
            .map(|s| s.into())
            .unwrap_or_default();
        let capacity = (env.get_array_length(&out_ops).unwrap_or(0).max(0) / 7) as usize;
        let Some(ops) = outbox_with(|ob| {
            ob.poll_batch(&session, (limit.max(0) as usize).min(capacity))
                .ok()
        }) else {
            return 0;
        };
        let Some(ops) = ops else { return 0 };
        let n = ops.len().min(capacity.max(1));
        let mut flat = Vec::with_capacity(n * 7);
        for op in ops.iter().take(n) {
            let mut nonce4 = [0u8; 4];
            nonce4.copy_from_slice(&op.sender_nonce);
            flat.push(op.op_id as jlong);
            flat.push(
                ((op.op_type as i64) << 8)
                    | ((op.policy_flags as i64) << 16)
                    | (u32::from_be_bytes(nonce4) as i64 & 0xFFFF),
            );
            flat.push(op.track_cad_id as jlong);
            flat.push(op.frac_index.to_bits() as i64);
            flat.push(op.target_add_op_id as jlong);
            flat.push(op.checksum as jlong);
            flat.push(0);
        }
        let _ = env.set_long_array_region(&out_ops, 0, &flat);
        n as jint
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxAck(
    env: JNIEnv,
    _class: JClass,
    op_ids: JLongArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let len = env.get_array_length(&op_ids).unwrap_or(0);
        if len == 0 {
            return 0;
        }
        let mut buf = vec![0i64; len as usize];
        if env.get_long_array_region(&op_ids, 0, &mut buf).is_err() {
            return 0;
        }
        let ids: Vec<u64> = buf.iter().map(|&v| v as u64).collect();
        outbox_with(|ob| ob.ack(&ids).unwrap_or(0) as jint).unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxReplay(
    _env: JNIEnv,
    _class: JClass,
    stale_threshold_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        outbox_with(|ob| ob.replay_pending(stale_threshold_ms).unwrap_or(0) as jint)
    }))
    .unwrap_or(Some(0))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxPendingCount(
    mut env: JNIEnv,
    _class: JClass,
    session_code: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let session: String = env
            .get_string(&session_code)
            .map(|s| s.into())
            .unwrap_or_default();
        outbox_with(|ob| ob.pending_count(&session).unwrap_or(0) as jint)
    }))
    .unwrap_or(Some(0))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamOutboxGc(
    _env: JNIEnv,
    _class: JClass,
    keep_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        outbox_with(|ob| ob.gc(keep_ms).unwrap_or(0) as jint)
    }))
    .unwrap_or(Some(0))
    .unwrap_or(0)
}

// ═══════════════════════════════════════════════════════════════════
// JAM PHASE-3 GOVERNOR: election + death-pivot math (pure)
// ═══════════════════════════════════════════════════════════════════

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamElectSuccessor(
    mut env: JNIEnv,
    _class: JClass,
    participants_joined: JString, // UUIDs joined by ','
    host_id: JString,
    lease_expired: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let members: String = env
            .get_string(&participants_joined)
            .map(|s| s.into())
            .unwrap_or_default();
        let host: String = env
            .get_string(&host_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let ids: Vec<String> = members
            .split(',')
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect();
        let result =
            crate::jam_governor::JamGovernor::elect_successor(&ids, &host, lease_expired != 0);
        match result {
            Some(id) => env
                .new_string(id)
                .unwrap_or_else(|_| env.new_string("").unwrap())
                .into_raw(),
            None => env
                .new_string("")
                .unwrap_or_else(|_| env.new_string("").unwrap())
                .into_raw(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Pivot decision. out_results = [code(0=OK,1=BEYOND_END,2=MISMATCH), posMs].
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamExtrapolatePivot(
    env: JNIEnv,
    _class: JClass,
    last_pos_ms: jlong,
    last_tick_mono_ms: jlong,
    current_synced_mono_ms: jlong,
    duration_ms: jlong,
    track_matches: jboolean,
    out_results: JLongArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        use crate::jam_governor::{JamGovernor, PivotResult};
        let r = JamGovernor::extrapolate_pivot(
            last_pos_ms,
            last_tick_mono_ms,
            current_synced_mono_ms,
            duration_ms,
            track_matches != 0,
        );
        let (code, pos) = match r {
            PivotResult::Ok(p) => (0i64, p),
            PivotResult::BeyondEnd => (1i64, -1),
            PivotResult::Mismatch => (2i64, -1),
        };
        let _ = env.set_long_array_region(&out_results, 0, &[code as jlong, pos as jlong]);
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamIsAdvisorySuccessor(
    mut env: JNIEnv,
    _class: JClass,
    participants_joined: JString,
    host_id: JString,
    self_id: JString,
    recently_seen: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let members: String = env
            .get_string(&participants_joined)
            .map(|s| s.into())
            .unwrap_or_default();
        let host: String = env
            .get_string(&host_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let self_id: String = env
            .get_string(&self_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let ids: Vec<String> = members
            .split(',')
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect();
        crate::jam_governor::JamGovernor::is_advisory_successor(
            &ids,
            &host,
            &self_id,
            recently_seen != 0,
        ) as jboolean
    }))
    .unwrap_or(0)
}

/// PHASE 4: wipes the tick sequence ring on HOST_TAKEOVER so the new host's
/// seq restart is accepted instead of misread as a 4-billion wrap gap.
#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeJamTickMatrixReset(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        crate::tick_matrix::TickMatrix::reset();
    }));
}
