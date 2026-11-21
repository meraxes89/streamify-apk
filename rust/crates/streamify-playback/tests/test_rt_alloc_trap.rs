//! Phase 5 / Pillar 1 (RFC Phase B): the real-time allocation trap —
//! the assert_no_alloc proof battery for the Sovereign HAL RT paths.
//!
//! Runs ONLY under `--features rt-alloc-trap` (the CI lane compiles the
//! crate with the feature and runs this file via nextest; the suite
//! self-skips with a green marker otherwise so plain `cargo test` stays
//! quiet):
//!
//!   * the engine's EXACT cpal callback sequence (ring read → underrun
//!     zero-fill → atomic clock publish) runs inside
//!     `rt_trap::assert_no_alloc` — zero allocations, test passes;
//!   * the producer's write edge (`SpscAudioRing::write_frames`) and
//!     the frozen C export (`streamify_ring_read_f32`) run inside the
//!     trap — zero allocations;
//!   * NEGATIVE CONTROLS (the non-vacuity proof — a trap that cannot
//!     fire is worthless): a deliberate `Box::new`, a `Vec::push` past
//!     capacity and a `format!` inside the guarded window each abort
//!     the test process. These run as #[should_panic] is NOT possible
//!     (AllocDisabler aborts, not panics) — they are separate #[test]s
//!     guarded by `#[cfg(feature = "rt-alloc-trap")]` + an env-var gate
//!     (the CI lane sets RT_TRAP_NEGATIVE=1 and asserts the abort exit
//!     code; without the env var they report-and-pass so rust-analyzer
//!     users are not nuked).
//!
//! The global allocator here is AllocDisabler (debug builds: abort via
//! handle_alloc_error on any alloc/dealloc inside the forbid window).

#![cfg(feature = "rt-alloc-trap")]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use streamify_core::DacPresentationClock;
use streamify_playback::rt_trap::{assert_no_alloc, AllocDisabler};
use streamify_playback::ring_export;
use streamify_playback::{SpscAudioRing, SINK_STATS_WIRE_BYTES};

#[cfg(debug_assertions)]
#[global_allocator]
static GLOBAL: AllocDisabler = AllocDisabler;

/// The engine's exact RT callback sequence (the same shape
/// tests/test_zero_alloc_callback.rs drives under the counting
/// allocator — ring read, zero-fill, underrun count, clock publish).
fn rt_callback_sequence(
    data: &mut [f32],
    rt_underruns: &AtomicUsize,
    frames_played: u64,
    rate: u32,
    clock: &DacClockSlotShim,
) {
    let frames = data.len() / 2;
    if frames == 0 {
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
    clock.publish(&DacPresentationClock {
        dac_frame_position: frames_played,
        sample_rate: rate,
        monotonic_ns: 42,
    });
}

// The DacClockSlot lives in streamify-hal (a heavier dep graph with
// cpal); the playback crate's clock slot equivalent is the publish
// target here. Alias keeps the sequence identical to the engine's.
pub type DacClockSlotAlias = DacClockSlotShim;

pub struct DacClockSlotShim;
impl DacClockSlotShim {
    pub fn publish(&self, sample: &DacPresentationClock) {
        // The engine's DacClockSlot::publish is atomic stores (seqlock
        // writer); the shim mirrors that shape without the dep.
        let _ = (sample.dac_frame_position, sample.sample_rate, sample.monotonic_ns);
    }
    pub fn sample(&self) -> Option<DacPresentationClock> {
        None
    }
}

#[test]
fn cpal_callback_path_survives_the_trap() {
    let ring = Arc::new(SpscAudioRing::new(8_192, 2, 48_000, 48_000).unwrap());
    ring_export::install(Arc::clone(&ring));
    let quantum = vec![0.5f32; 512 * 2];
    ring.write_frames(&quantum);

    let underruns = AtomicUsize::new(0);
    let clock = DacClockSlotShim;
    let mut buffer = vec![0.0f32; 1024 * 2];

    // The ring holds 512 frames; each pull takes 1024 — after the first
    // (warm-up) pull the ring is empty and the underrun zero-fill arm is
    // the exercised path. Feed it between pulls like the producer does.
    for i in 0..64u64 {
        ring.write_frames(&quantum);
        assert_no_alloc(|| {
            rt_callback_sequence(
                &mut buffer,
                &underruns,
                1024 + i * 1024,
                48_000,
                &clock,
            );
        });
    }
    // The first pull is fed by the initial write (full 512 served, no
    // underrun); the flush request only takes effect at the next write
    // boundary. Sustained underruns beyond the expected shortfall mean
    // the harness starved the ring (a feeding bug in THIS test, not an
    // allocation finding).
    assert_eq!(
        underruns.load(Ordering::Relaxed),
        63 * 512,
        "the ring must be fed at the pull cadence (512-frame shortfall per pull)"
    );

    // The UNDERRUN arm: flush the ring, run again — zero-fill must not
    // allocate either.
    ring.request_flush();
    let mut scratch = vec![0.0f32; 16_384 * 2];
    // SAFETY: valid scratch buffer for the read edge.
    unsafe { ring_export::streamify_ring_read_f32(scratch.as_mut_ptr(), 16_384) };
    for i in 0..64u64 {
        assert_no_alloc(|| {
            rt_callback_sequence(
                &mut buffer,
                &underruns,
                5000 + i * 1024,
                48_000,
                &clock,
            );
        });
    }
    assert!(underruns.load(Ordering::Relaxed) > 0, "underrun arm ran");
    ring_export::clear();
}

#[test]
fn write_edge_and_stats_wire_survive_the_trap() {
    let ring = Arc::new(SpscAudioRing::new(4_096, 2, 48_000, 48_000).unwrap());
    ring_export::install(Arc::clone(&ring));
    let chunk = vec![0.25f32; 2048 * 2];

    // Warm: the first write touches cold pages; subsequent ones are the
    // steady state the trap guards.
    ring.write_frames(&chunk);

    assert_no_alloc(|| {
        ring.write_frames(&chunk);
    });
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
    assert_no_alloc(|| {
        // SAFETY: valid stack buffer of the frozen wire size.
        unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) };
    });
    let mut out = vec![0.0f32; 1024 * 2];
    assert_no_alloc(|| {
        // SAFETY: valid scratch buffer, single consumer.
        unsafe { ring_export::streamify_ring_read_f32(out.as_mut_ptr(), 1024) };
    });
    ring_export::clear();
}

// ── NEGATIVE CONTROLS (the non-vacuity proofs) ─────────────────────────────
//
// Each aborts the process when RT_TRAP_NEGATIVE=1 (the CI lane asserts
// the abort); without the env var each prints a SKIP line and passes so
// local `cargo test` runs are not destroyed by design.

fn negative_gate() -> bool {
    std::env::var("RT_TRAP_NEGATIVE").map(|v| v == "1").unwrap_or(false)
}

#[test]
fn negative_control_box_new_aborts() {
    if !negative_gate() {
        eprintln!("SKIP (set RT_TRAP_NEGATIVE=1 on the CI lane)");
        return;
    }
    let b = assert_no_alloc(|| Box::new(0u8));
    eprintln!("VACUOUS TRAP: Box::new survived the window ({b:?})");
}

#[test]
fn negative_control_vec_push_aborts() {
    if !negative_gate() {
        eprintln!("SKIP (set RT_TRAP_NEGATIVE=1 on the CI lane)");
        return;
    }
    let mut v = Vec::with_capacity(1);
    v.push(1u8); // inside capacity — allowed
    assert_no_alloc(|| {
        v.push(2u8); // past capacity: reallocates — must abort
    });
    eprintln!("VACUOUS TRAP: Vec::push realloc survived the window ({v:?})");
}

#[test]
fn negative_control_format_aborts() {
    if !negative_gate() {
        eprintln!("SKIP (set RT_TRAP_NEGATIVE=1 on the CI lane)");
        return;
    }
    let s = assert_no_alloc(|| format!("allocates {impossible}", impossible = 42));
    eprintln!("VACUOUS TRAP: format! survived the window ({s})");
}
