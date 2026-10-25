//! jni_bridge_physics_waveform.rs — JNI surface for the Phase 5
//! kinetic gesture physics solver & waveform indexer
//! (feat/phase5-rust-gesture-physics-waveform, directive §2.3).
//!
//! ENGINEER 3 BINDS AGAINST two new Kotlin-owned classes
//! (`com.streamify.core.rust.RustGesturePhysics` /
//! `com.streamify.core.rust.RustWaveformIndexer`; Kotlin declares the
//! `external fun`s — this crate stays strictly `rust/` isolated).
//!
//! FROZEN CONTRACT (directive §2.3 — the four symbols):
//!   RustGesturePhysics.nativeSolveSpringTrajectory(
//!       currentPos: Float, targetPos: Float, velocity: Float,
//!       stiffness: Float, dampingRatio: Float,
//!       outSamples: FloatArray): Int
//!   RustGesturePhysics.nativeCalculateFlingLanding(
//!       startPos: Float, velocity: Float, minBound: Float,
//!       maxBound: Float, friction: Float): Float
//!   RustWaveformIndexer.nativeGenerateWaveform(
//!       audioBytes: ByteArray, bucketCount: Int,
//!       outBuckets: ByteArray): Int
//!   RustWaveformIndexer.nativeLoadWaveformCache(
//!       cachePath: String, outBuckets: ByteArray): Int
//!
//! CONVENTIONS:
//!   • Trajectory sampling: mass = 1 (the JNI contract fixes m), frame
//!     interval 1/120 s (120 Hz display); outSamples[k] = position at
//!     t = k/120 s — frame 0 IS the current position, so a UI can
//!     start drawing from index 1.
//!   • `Int` returns are element counts on success (`> 0`: frames /
//!     buckets / bytes written) or a negative
//!     [`JniStatus`] code on failure; `nativeCalculateFlingLanding`
//!     returns `Float.NaN` on invalid input (a float return cannot
//!     carry the enum — Kotlin checks `isNaN`).
//!   • `nativeGenerateWaveform` requires `outBuckets.size ==
//!     bucketCount`; `nativeLoadWaveformCache` copies
//!     `min(cacheCount, outBuckets.size)` bytes and returns the count
//!     so the caller can trim its view.
//!   • audioBytes: raw decoded PCM, 16-bit signed little-endian.
//!
//! HOUSE RULES (same discipline as jni_bridge.rs / jni_bridge_p2p.rs /
//! jni_bridge_chunk_sync.rs / jni_bridge_connect.rs):
//!   • Every entry point wraps its body in `catch_unwind` — a panic
//!     must never unwind across the FFI boundary into ART.
//!   • Guards reject zero-length arrays, NaN/Inf floats and invalid
//!     buffer pointers with clean enum codes; no Java exceptions are
//!     thrown, the Kotlin layer logs-and-degrades.
//!   • Array element access goes through `get_array_elements`
//!     (NoCopyBack for read-only payloads, CopyBack for outputs) —
//!     no intermediate Rust-side copies of the PCM payload, no heap
//!     allocation on the trajectory hot path.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JClass, JFloatArray, JString, ReleaseMode};
use jni::sys::{jfloat, jint};
use jni::JNIEnv;

use crate::gesture_physics::{
    fling_landing, spring_trajectory_from, SpringSpec, FRAME_INTERVAL_120HZ,
};
use crate::waveform_indexer::{self, WaveformError, MAX_BUCKETS, MIN_BUCKETS};

/// Clean enum return codes for the bridge entry points. Success
/// returns a positive element count instead (see module docs).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum JniStatus {
    /// Non-finite (NaN/Inf) float argument.
    NonFiniteInput = -1,
    /// Invalid parameter: stiffness <= 0, damping ratio < 0,
    /// friction <= 0, inverted bounds, bucket count outside
    /// `[MIN_BUCKETS, MAX_BUCKETS]`, or params so extreme that
    /// derived quantities (k/m, ζ²) overflow f32.
    InvalidParameter = -2,
    /// Invalid output buffer: null array, zero length, or capacity
    /// mismatch against the requested bucket count.
    InvalidBuffer = -3,
    /// Invalid audio payload: empty or odd-length byte stream.
    InvalidAudio = -4,
    /// Malformed cache blob: magic / header / length / count mismatch.
    InvalidCache = -5,
    /// Cache file I/O failure.
    IoFailure = -6,
    /// A Rust panic was caught by the `catch_unwind` shield — never
    /// crosses into ART.
    InternalPanic = -7,
}

impl JniStatus {
    #[inline]
    fn code(self) -> jint {
        self as jint
    }
}

impl From<waveform_indexer::WaveformError> for JniStatus {
    fn from(e: WaveformError) -> Self {
        match e {
            WaveformError::EmptyAudio | WaveformError::OddByteLength => JniStatus::InvalidAudio,
            WaveformError::InvalidBucketCount(_) => JniStatus::InvalidParameter,
            WaveformError::InvalidCacheMagic
            | WaveformError::TruncatedCache
            | WaveformError::CacheLengthMismatch { .. } => JniStatus::InvalidCache,
            WaveformError::Io(_) => JniStatus::IoFailure,
        }
    }
}

// ───────────────────── RustGesturePhysics ─────────────────────

/// Fills `out_samples` with the spring trajectory (see module docs).
fn solve_spring_inner(
    env: &mut JNIEnv,
    out_samples: &JFloatArray,
    current_pos: jfloat,
    target_pos: jfloat,
    velocity: jfloat,
    stiffness: jfloat,
    damping_ratio: jfloat,
) -> jint {
    // NaN/Inf guard first — directive §2.3 memory & error safety.
    for v in [current_pos, target_pos, velocity, stiffness, damping_ratio] {
        if !v.is_finite() {
            return JniStatus::NonFiniteInput.code();
        }
    }
    let spec = match SpringSpec::new(stiffness, damping_ratio, 1.0) {
        Ok(s) => s,
        Err(_) => return JniStatus::InvalidParameter.code(),
    };
    let len = match env.get_array_length(out_samples) {
        Ok(l) => l,
        Err(_) => return JniStatus::InvalidBuffer.code(),
    };
    if len <= 0 {
        // Zero-length output array — nothing to animate.
        return JniStatus::InvalidBuffer.code();
    }
    // Borrow the Java float array; CopyBack publishes our writes.
    // SAFETY: the elements are released by AutoElements' Drop, and the
    // slice lives only for the duration of this call (no JNI upcalls
    // in between).
    let mut elements = match unsafe { env.get_array_elements(out_samples, ReleaseMode::CopyBack) } {
        Ok(e) => e,
        Err(_) => return JniStatus::InvalidBuffer.code(),
    };
    // jfloat is a type alias for f32 — the slice IS the sample buffer.
    let out: &mut [f32] = &mut elements;
    spring_trajectory_from(
        &spec,
        current_pos,
        target_pos,
        velocity,
        FRAME_INTERVAL_120HZ,
        0,
        out,
    );
    len
}

/// Projected fling landing; NaN on invalid input (documented sentinel).
fn calculate_fling_landing_inner(
    start_pos: jfloat,
    velocity: jfloat,
    min_bound: jfloat,
    max_bound: jfloat,
    friction: jfloat,
) -> jfloat {
    match fling_landing(start_pos, velocity, min_bound, max_bound, friction) {
        Ok(landing) => landing,
        Err(_) => jfloat::NAN,
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)] // the frozen JNI signature is the contract
pub extern "system" fn Java_com_streamify_core_rust_RustGesturePhysics_nativeSolveSpringTrajectory(
    mut env: JNIEnv,
    _class: JClass,
    current_pos: jfloat,
    target_pos: jfloat,
    velocity: jfloat,
    stiffness: jfloat,
    damping_ratio: jfloat,
    out_samples: JFloatArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        solve_spring_inner(
            &mut env,
            &out_samples,
            current_pos,
            target_pos,
            velocity,
            stiffness,
            damping_ratio,
        )
    }))
    .unwrap_or_else(|_| JniStatus::InternalPanic.code())
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustGesturePhysics_nativeCalculateFlingLanding(
    _env: JNIEnv,
    _class: JClass,
    start_pos: jfloat,
    velocity: jfloat,
    min_bound: jfloat,
    max_bound: jfloat,
    friction: jfloat,
) -> jfloat {
    catch_unwind(AssertUnwindSafe(|| {
        calculate_fling_landing_inner(start_pos, velocity, min_bound, max_bound, friction)
    }))
    .unwrap_or(jfloat::NAN)
}

// ───────────────────── RustWaveformIndexer ─────────────────────

/// Generates the amplitude bars into `out_buckets` (see module docs).
fn generate_waveform_inner(
    env: &mut JNIEnv,
    audio_bytes: &JByteArray,
    bucket_count: jint,
    out_buckets: &JByteArray,
) -> jint {
    if !(MIN_BUCKETS..=MAX_BUCKETS).contains(&(bucket_count as usize)) {
        return JniStatus::InvalidParameter.code();
    }
    let audio_len = match env.get_array_length(audio_bytes) {
        Ok(l) => l,
        Err(_) => return JniStatus::InvalidAudio.code(),
    };
    // Zero-length arrays and partial sample frames are rejected — the
    // PCM payload must be 16-bit LE frames.
    if audio_len <= 0 || audio_len % 2 != 0 {
        return JniStatus::InvalidAudio.code();
    }
    let out_len = match env.get_array_length(out_buckets) {
        Ok(l) => l,
        Err(_) => return JniStatus::InvalidBuffer.code(),
    };
    if out_len != bucket_count {
        return JniStatus::InvalidBuffer.code();
    }
    // Read-only borrow of the PCM payload (NoCopyBack: our writes, if
    // any, are discarded — we never write through it).
    // SAFETY: AutoElements releases the elements on Drop; we only
    // reinterpreted jbyte (i8) as u8, an identical-layout cast.
    let audio_elements =
        match unsafe { env.get_array_elements(audio_bytes, ReleaseMode::NoCopyBack) } {
            Ok(e) => e,
            Err(_) => return JniStatus::InvalidAudio.code(),
        };
    let pcm: &[u8] = unsafe {
        std::slice::from_raw_parts(audio_elements.as_ptr() as *const u8, audio_len as usize)
    };
    let amplitudes = match waveform_indexer::generate_waveform(pcm, bucket_count as usize) {
        Ok(a) => a,
        Err(e) => return JniStatus::from(e).code(),
    };
    // Write the bars into the caller's output array.
    let out_elements =
        match unsafe { env.get_array_elements(out_buckets, ReleaseMode::CopyBack) } {
            Ok(e) => e,
            Err(_) => return JniStatus::InvalidBuffer.code(),
        };
    let out: &mut [u8] = unsafe {
        std::slice::from_raw_parts_mut(out_elements.as_ptr() as *mut u8, out_len as usize)
    };
    out.copy_from_slice(&amplitudes);
    bucket_count
}

/// Loads amplitude bars from the cache file into `out_buckets` (see
/// module docs). Returns the number of bytes copied.
fn load_waveform_cache_inner(
    env: &mut JNIEnv,
    cache_path: &JString,
    out_buckets: &JByteArray,
) -> jint {
    let out_len = match env.get_array_length(out_buckets) {
        Ok(l) => l,
        Err(_) => return JniStatus::InvalidBuffer.code(),
    };
    if out_len <= 0 {
        return JniStatus::InvalidBuffer.code();
    }
    let path: String = match env.get_string(cache_path) {
        Ok(s) => s.into(),
        Err(_) => return JniStatus::InvalidParameter.code(),
    };
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => return JniStatus::IoFailure.code(),
    };
    let view = match waveform_indexer::decode_cache_view(&bytes) {
        Ok(v) => v,
        Err(e) => return JniStatus::from(e).code(),
    };
    let copied = (view.amplitudes.len() as jint).min(out_len) as usize;
    // SAFETY: CopyBack borrow for the caller's output array; the
    // written prefix is published when AutoElements drops.
    let out_elements =
        match unsafe { env.get_array_elements(out_buckets, ReleaseMode::CopyBack) } {
            Ok(e) => e,
            Err(_) => return JniStatus::InvalidBuffer.code(),
        };
    let out: &mut [u8] = unsafe {
        std::slice::from_raw_parts_mut(out_elements.as_ptr() as *mut u8, out_len as usize)
    };
    out[..copied].copy_from_slice(&view.amplitudes[..copied]);
    copied as jint
}

#[no_mangle]
#[allow(clippy::too_many_arguments)] // the frozen JNI signature is the contract
pub extern "system" fn Java_com_streamify_core_rust_RustWaveformIndexer_nativeGenerateWaveform(
    mut env: JNIEnv,
    _class: JClass,
    audio_bytes: JByteArray,
    bucket_count: jint,
    out_buckets: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        generate_waveform_inner(&mut env, &audio_bytes, bucket_count, &out_buckets)
    }))
    .unwrap_or_else(|_| JniStatus::InternalPanic.code())
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_core_rust_RustWaveformIndexer_nativeLoadWaveformCache(
    mut env: JNIEnv,
    _class: JClass,
    cache_path: JString,
    out_buckets: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        load_waveform_cache_inner(&mut env, &cache_path, &out_buckets)
    }))
    .unwrap_or_else(|_| JniStatus::InternalPanic.code())
}
