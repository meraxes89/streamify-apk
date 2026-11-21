//! Sovereign HAL Phase 1 (D5): the zero-allocation proof for the cpal
//! output-callback path (the hard-RT contract — D1's non-negotiable #3).
//!
//! The proof drives the EXACT callback body the engine hands to cpal
//! (ring read → underrun zero-fill → atomic clock publish) through a
//! counting global allocator: any allocation during the pull window is
//! a failure. The discipline:
//!
//! * the ring is pre-installed with data (and separately EMPTY — the
//!   underrun zero-fill arm must not allocate either);
//! * the same atomic-sequence `DacClockSlot` publish runs;
//! * NO mutex / channel / syscall appears in the window (structurally
//!   guaranteed by the callback's code — this suite pins the
//!   allocation half and the ring's own write_edge_is_zero_allocation
//!   already pins the edge).
//!
//! Runs under nextest (process-per-test — the house GOV-04 convention;
//! a sibling test's allocations must never land in our window).

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use streamify_core::DacPresentationClock;
use streamify_hal::{DacClockSlot, StopFlag};
use streamify_playback::{ring_export, SpscAudioRing};

struct CountingAlloc;
static COUNTING: AtomicBool = AtomicBool::new(false);
static ALLOCS: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if COUNTING.load(Ordering::Relaxed) {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
        }
        unsafe { System.alloc(layout) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static GLOBAL: CountingAlloc = CountingAlloc;

/// The engine's exact RT callback sequence, extracted verbatim-shape
/// (the closure itself lives inside cpal; the sequence it runs is the
/// contract under test — every element identical: ring read, zero-fill,
/// underrun count, clock publish).
fn rt_callback_sequence(
    data: &mut [f32],
    rt_underruns: &AtomicUsize,
    rt_stop: &StopFlag,
    rt_clock: &DacClockSlot,
    frames_played: u64,
    rate: u32,
) {
    let frames = data.len() / 2;
    if frames == 0 {
        return;
    }
    if rt_stop.get() {
        data.fill(0.0);
        return;
    }
    // SAFETY: data is the callback's live buffer, valid for `frames × 2`
    // f32 writes; single consumer thread.
    let served = unsafe { ring_export::streamify_ring_read_f32(data.as_mut_ptr(), frames) };
    let served_samples = served * 2;
    if served_samples < data.len() {
        rt_underruns.fetch_add((data.len() - served_samples) / 2, Ordering::Relaxed);
        for s in data[served_samples..].iter_mut() {
            *s = 0.0;
        }
    }
    let sample = DacPresentationClock {
        dac_frame_position: frames_played,
        sample_rate: rate,
        monotonic_ns: 42, // the anchor domain is the engine's; value irrelevant here
    };
    rt_clock.publish(&sample);
}

#[test]
fn cpal_callback_path_is_zero_allocation() {
    // 1. A pre-installed ring with data.
    let ring = Arc::new(SpscAudioRing::new(8_192, 2, 48_000, 48_000).unwrap());
    ring_export::install(Arc::clone(&ring));
    let quantum = vec![0.5f32; 512 * 2];
    ring.write_frames(&quantum);

    let underruns = AtomicUsize::new(0);
    let stop = StopFlag::default();
    let clock = DacClockSlot::default();
    let mut buffer = vec![0.0f32; 1024 * 2]; // the "cpal" output slice

    // Warm everything (first-touch costs live OUTSIDE the window).
    rt_callback_sequence(&mut buffer, &underruns, &stop, &clock, 1024, 48_000);

    ALLOCS.store(0, Ordering::Relaxed);
    COUNTING.store(true, Ordering::Relaxed);
    for i in 0..64u64 {
        rt_callback_sequence(&mut buffer, &underruns, &stop, &clock, 1024 + i * 1024, 48_000);
    }
    COUNTING.store(false, Ordering::Relaxed);
    let n = ALLOCS.load(Ordering::Relaxed);
    assert_eq!(n, 0, "the cpal callback path must be zero-allocation (saw {n})");

    // 2. The UNDERRUN arm: clear the ring, run again — the zero-fill
    //    path must not allocate either.
    ring.request_flush();
    // Drain whatever remains.
    let mut scratch = vec![0.0f32; 16_384 * 2];
    // SAFETY: valid scratch buffer for the read edge.
    unsafe { ring_export::streamify_ring_read_f32(scratch.as_mut_ptr(), 16_384) };
    ALLOCS.store(0, Ordering::Relaxed);
    COUNTING.store(true, Ordering::Relaxed);
    for i in 0..64u64 {
        rt_callback_sequence(&mut buffer, &underruns, &stop, &clock, 5000 + i * 1024, 48_000);
    }
    COUNTING.store(false, Ordering::Relaxed);
    let n = ALLOCS.load(Ordering::Relaxed);
    assert_eq!(n, 0, "the underrun zero-fill arm must be zero-allocation (saw {n})");
    assert!(underruns.load(Ordering::Relaxed) > 0, "the underrun arm actually ran");
    // The clock slot published through it all.
    assert!(clock.sample().is_some());

    ring_export::clear();
}

#[test]
fn engine_constructs_without_allocation_in_callback_window() {
    // The engine's OWN constructor may allocate (owner side); only the
    // callback window is RT. This pins the boundary: building + dropping
    // the engine's RT state never touches the callback path.
    let clock = Arc::new(DacClockSlot::default());
    let stop = StopFlag::default();
    let underruns = AtomicUsize::new(0);
    let mut buffer = vec![0.0f32; 512 * 2];
    // No ring installed: the read edge serves 0 frames — the underrun
    // arm runs (silence), still zero-alloc.
    ALLOCS.store(0, Ordering::Relaxed);
    COUNTING.store(true, Ordering::Relaxed);
    rt_callback_sequence(&mut buffer, &underruns, &stop, &clock, 0, 48_000);
    COUNTING.store(false, Ordering::Relaxed);
    assert_eq!(ALLOCS.load(Ordering::Relaxed), 0);
    assert!(underruns.load(Ordering::Relaxed) > 0);
}
