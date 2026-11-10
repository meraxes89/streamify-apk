//! The frozen C consumer edge for platform HAL brackets (WS-B B4).
//!
//! `StreamifyEngine::playback_ring()` (streamify-contract) hands the
//! attached plane's [`SpscAudioRing`] to RUST callers — the A5 self-test
//! harness pulls from it directly. But the Apple CoreAudio render
//! callback lives in C++ (`native/audio/coreaudio_pull_adapter.cpp`), and
//! the ring is deliberately NOT a UniFFI surface (the UDL cannot express
//! raw pointers, and audio must never cross the UniFFI object machinery).
//! This module is the platform bracket the dataplane RFC §7 always
//! intended: a four-symbol C ABI over the engine's process-current ring.
//!
//! # The frozen surface (v1)
//!
//! ```c
//! size_t   streamify_ring_read_f32(float* dst, size_t max_frames);
//! uint64_t streamify_ring_occupancy_frames(void);
//! uint32_t streamify_ring_channels(void);
//! int32_t  streamify_ring_stats_wire(uint8_t* out, size_t out_len);
//! ```
//!
//! * `read_f32` serves up to `max_frames` INTERLEAVED frames (the ring's
//!   own geometry) — zero-copy, zero-allocation, zero-lock: the exact
//!   `read_frames_into` edge the A5 harness exercises, so the C++ render
//!   callback and the Rust self-test consume the identical code path.
//! * `stats_wire` serializes the same 96-byte little-endian image the JNI
//!   bridge (C1) and the desktop JNA shim (C5) consume — one wire layout
//!   across every platform lane.
//!
//! # Lifetime
//!
//! The engine installs the ring on `attach_sink` and clears it on
//! `detach_sink` (streamify-contract/src/contract.rs). Readers load the
//! pointer acquire-wise; a swap-out releases the previous `Arc`, so an
//! in-flight read can only ever observe a ring the engine kept alive for
//! it. ONE process-current plane: the app runs a single engine, and the
//! engine itself supports exactly one attached plane at a time — the
//! export mirrors that contract. (A future multi-plane contract would
//! carry handles through the UDL; until then this stays the documented
//! single-slot bracket.)
//!
//! # RT contract
//!
//! The read path performs no allocation, takes no locks and makes no
//! syscalls — it IS `SpscAudioRing::read_frames_into`, view-flavored.

use std::sync::atomic::{AtomicPtr, Ordering};
use std::sync::Arc;

use crate::sink::{SpscAudioRing, SINK_STATS_WIRE_BYTES};

/// The process-current consumer edge (null while no plane is attached).
static CURRENT_RING: AtomicPtr<SpscAudioRing> = AtomicPtr::new(std::ptr::null_mut());

/// Install `ring` as the process-current consumer edge (the engine's
/// `attach_sink`). Lock-free for readers; the previous export (if any) is
/// released AFTER the swap.
pub fn install(ring: Arc<SpscAudioRing>) {
    let ptr = Arc::into_raw(ring) as *mut SpscAudioRing;
    let old = CURRENT_RING.swap(ptr, Ordering::AcqRel);
    release(old);
}

/// Clear the export (the engine's `detach_sink`): subsequent reads serve
/// digital silence (zero frames) until the next attach.
pub fn clear() {
    release(CURRENT_RING.swap(std::ptr::null_mut(), Ordering::AcqRel));
}

fn release(ptr: *mut SpscAudioRing) {
    if !ptr.is_null() {
        // SAFETY: the pointer came from Arc::into_raw on the same type and
        // was never aliased as anything else; dropping the Arc releases
        // the engine's export reference (the engine's own Arc in the
        // playback wiring is independent).
        unsafe { drop(Arc::from_raw(ptr)) };
    }
}

/// The currently exported ring, if any (read-side view helper).
fn current() -> Option<&'static SpscAudioRing> {
    let ptr = CURRENT_RING.load(Ordering::Acquire);
    if ptr.is_null() {
        return None;
    }
    // SAFETY: the slot's Arc keeps the ring alive; the pointer is only
    // ever swapped for another valid Arc-derived pointer or null, and the
    // release discipline above guarantees the storage outlives this call.
    Some(unsafe { &*ptr })
}

/// # Safety
///
/// `dst` must be valid for writes of `max_frames × channels` interleaved
/// f32 samples, and must be called from AT MOST ONE consumer thread at a
/// time (the ring is SPSC — the audio render callback is that consumer).
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_read_f32(dst: *mut f32, max_frames: usize) -> usize {
    let Some(ring) = current() else { return 0 };
    if dst.is_null() || max_frames == 0 {
        return 0;
    }
    let channels = ring.channels();
    // SAFETY: caller guarantees the buffer's extent; the slice is a
    // transient view (dropped before return — never escapes).
    let view = unsafe { std::slice::from_raw_parts_mut(dst, max_frames * channels) };
    // read_frames_into returns SAMPLES served; the C ABI speaks FRAMES
    // (the render core's quantum unit).
    ring.read_frames_into(view) / channels.max(1)
}

/// Live occupancy of the exported ring, in frames (0 while detached).
#[no_mangle]
pub extern "C" fn streamify_ring_occupancy_frames() -> u64 {
    current().map(|ring| ring.occupancy() as u64).unwrap_or(0)
}

/// The exported ring's channel count (0 while detached; 1 or 2 attached).
#[no_mangle]
pub extern "C" fn streamify_ring_channels() -> u32 {
    current().map(|ring| ring.channels() as u32).unwrap_or(0)
}

/// Serialize the exported ring's stats into the frozen 96-byte
/// little-endian wire image (magic `0x51AB0001` — the JNI/C1 and JNA/C5
/// layout). Returns 1 on success, 0 when detached / the buffer is too
/// small.
#[no_mangle]
pub extern "C" fn streamify_ring_stats_wire(out: *mut u8, out_len: usize) -> i32 {
    let Some(ring) = current() else { return 0 };
    if out.is_null() || out_len < SINK_STATS_WIRE_BYTES {
        return 0;
    }
    // SAFETY: caller guarantees the buffer's extent for this call.
    let view = unsafe { std::slice::from_raw_parts_mut(out, SINK_STATS_WIRE_BYTES) };
    if ring.stats().to_wire(view) {
        1
    } else {
        0
    }
}
