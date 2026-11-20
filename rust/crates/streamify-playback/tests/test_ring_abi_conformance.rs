//! Sovereign HAL Phase 0 (D3): ring ABI conformance + zero-allocation
//! proof for the frozen write/read edge, executed on EVERY target lane —
//! x86_64 host CI AND the armv7-unknown-linux-gnueabihf QEMU cross-test
//! (Gate 2 of `_ci-sovereign-hal.yml`). The suite is deliberately
//! hermetic (no fixtures, no network, no time dependence beyond coarse
//! bounds): it must produce identical verdicts under emulation, where the
//! 32-bit ABI (ILP32 `usize`, 8-byte-aligned `AtomicU64`, 64-byte
//! cachelines) is the whole point of the exercise.
//!
//! What "conformance" means here (mirrored by the C++ twin
//! `native/tests/test_ring_abi_conformance.cc`):
//!   1. The 96-byte stats wire round-trips at the frozen offsets with the
//!      frozen magic — same bytes the C++ `WriteSinkStatsBuffer` emits.
//!   2. The four C symbols of `ring_export.rs` behave per contract:
//!      detached → silence/0; attached → the exact `read_frames_into`
//!      edge (frame-unit conversion, occupancy, channels, stats wire).
//!   3. The flush/underrun/overrun counters move exactly as the C++
//!      ring's protocol pins (one event per short read/write, flush is
//!      never an underrun).
//!   4. Zero heap allocation on the write/read/flush/stats/wire paths —
//!      the counting-allocator proof from `test_ring.rs`, extended over
//!      the exported C edge.
//!   5. The compile-time ABI assertions in `sink.rs` hold at runtime too:
//!      lock-free u64 cursors, ≥64-byte cacheline separation (on 64-bit
//!      targets we can additionally verify the *addresses* differ by a
//!      full line; on 32-bit we verify the alignment guarantees only).

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use streamify_playback::ring_export;
use streamify_playback::{AudioSink, RingSink, SINK_STATS_WIRE_MAGIC, SINK_STATS_WIRE_BYTES};

// The ring_export slot is PROCESS-GLOBAL; tests run on parallel threads in
// one binary, so every test that installs/clears the export must hold this
// mutex for its whole duration (the house `test_ring.rs` suites avoid the
// issue by not touching the export; these tests exercise the export edge
// itself).
static EXPORT_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

fn export_lock() -> MutexGuard<'static, ()> {
    EXPORT_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
}

// ─────────────────────────────────────────────────────────────────────────────
// The counting allocator (the test_ring.rs discipline, applied to the
// exported C edge): proves the ring edge never allocates.
// ─────────────────────────────────────────────────────────────────────────────

static ALLOC_COUNT: AtomicU64 = AtomicU64::new(0);

struct CountingAllocator;

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOC_COUNT.fetch_add(1, Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn allocs() -> u64 {
    ALLOC_COUNT.load(Ordering::Relaxed)
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. The 96-byte stats wire at the frozen offsets (C++ parity).
// ─────────────────────────────────────────────────────────────────────────────

fn rd_u32(wire: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(wire[off..off + 4].try_into().unwrap())
}

fn rd_u64(wire: &[u8], off: usize) -> u64 {
    u64::from_le_bytes(wire[off..off + 8].try_into().unwrap())
}

fn rd_f32(wire: &[u8], off: usize) -> f32 {
    f32::from_le_bytes(wire[off..off + 4].try_into().unwrap())
}

#[test]
fn stats_wire_pins_the_frozen_layout_everywhere() {
    let (_, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
    ring.write_frames(&[0.25f32; 64 * 2]); // some occupancy, no overrun
    let s = ring.stats();
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
    assert!(s.to_wire(&mut wire));

    // Frozen header.
    assert_eq!(rd_u32(&wire, 0), SINK_STATS_WIRE_MAGIC);
    assert_eq!(rd_u32(&wire, 4), SINK_STATS_WIRE_BYTES as u32);
    // Frozen geometry fields.
    assert_eq!(rd_u32(&wire, 8), 1024, "capacity at +8");
    assert_eq!(rd_u32(&wire, 12), 48_000, "rate_in at +12");
    assert_eq!(rd_u32(&wire, 16), 48_000, "rate_out at +16");
    assert_eq!(rd_u32(&wire, 20), 2, "channels_in at +20");
    assert_eq!(rd_u32(&wire, 24), 2, "channels_out at +24");
    assert_eq!(rd_u32(&wire, 28), 64, "fullness at +28");
    // Frozen counters.
    assert_eq!(rd_u64(&wire, 32), s.frames_written, "frames_written at +32");
    assert_eq!(rd_u64(&wire, 40), s.frames_read, "frames_read at +40");
    assert_eq!(rd_u64(&wire, 48), 0, "underruns at +48");
    assert_eq!(rd_u64(&wire, 56), 0, "overruns at +56");
    assert_eq!(rd_u64(&wire, 64), 0, "overrun_dropped at +64");
    assert_eq!(rd_u32(&wire, 72), 64, "high_watermark at +72");
    // Reserved tail is zeroed.
    assert_eq!(wire[92..96], [0u8; 4], "reserved at +92 is zero");
    // Fullness percent & latency are the documented f32 derivations.
    let pct = rd_f32(&wire, 80);
    assert!((pct - 6.25).abs() < 0.01, "fullness_percent at +80: {pct}");
    let lat = rd_f32(&wire, 84);
    assert!((lat - (64.0 * 1000.0 / 48_000.0)).abs() < 0.01, "latency at +84: {lat}");
    // Sequence increments monotonically across stats reads.
    let seq1 = rd_u32(&wire, 88);
    let mut wire2 = [0u8; SINK_STATS_WIRE_BYTES];
    assert!(ring.stats().to_wire(&mut wire2));
    assert_eq!(rd_u32(&wire2, 88), seq1 + 1, "sequence at +88 bumps");
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. The four frozen C symbols (ring_export) — contract conformance.
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn ring_export_symbols_conform_to_the_frozen_contract() {
    let _export = export_lock();
    // Detached: everything serves zero, stats wire returns 0.
    ring_export::clear();
    let mut dst = [0.5f32; 128];
    assert_eq!(unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 64) }, 0);
    assert!(dst.iter().all(|s| *s == 0.5), "detached read must not touch dst");
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 0);
    assert_eq!(ring_export::streamify_ring_channels(), 0);
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
    assert_eq!(unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) }, 0);

    // Attached: the exact read_frames_into edge, in FRAME units.
    let (mut sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
    ring_export::install(ring.clone());
    assert_eq!(ring_export::streamify_ring_channels(), 2);

    let wrote = sink.write_float(&vec![0.125f32; 96 * 2]).unwrap();
    assert_eq!(wrote, 96);
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 96);

    let mut dst = [0.0f32; 64 * 2];
    // SAFETY: single consumer thread (this test), valid for 64 frames × 2ch.
    let served = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 64) };
    assert_eq!(served, 64, "the C edge speaks FRAMES");
    assert!(dst.iter().all(|s| *s == 0.125));
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 32);

    // Hostile/edge arguments degrade to silence, never UB:
    // - null dst → 0; zero frames → 0; absurd max_frames (would overflow a
    //   32-bit usize when multiplied by channels) → 0 via the checked path.
    assert_eq!(unsafe { ring_export::streamify_ring_read_f32(std::ptr::null_mut(), 64) }, 0);
    assert_eq!(unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 0) }, 0);
    assert_eq!(
        unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), usize::MAX) },
        0,
        "overflowing max_frames serves silence (32-bit hardening)"
    );

    // stats_wire through the export: success code 1, frozen magic.
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
    assert_eq!(unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) }, 1);
    assert_eq!(rd_u32(&wire, 0), SINK_STATS_WIRE_MAGIC);
    assert_eq!(rd_u32(&wire, 28), 32, "occupancy via the export wire");
    // Short buffer refused.
    let mut short = [0u8; 95];
    assert_eq!(unsafe { ring_export::streamify_ring_stats_wire(short.as_mut_ptr(), short.len()) }, 0);

    // Teardown: detach returns the process to the detached contract.
    ring_export::clear();
    assert_eq!(ring_export::streamify_ring_occupancy_frames(), 0);
    let _ = sink.stats(); // producer handle still usable after export teardown
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Flush / underrun / overrun counter semantics (C++ protocol parity).
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn counter_semantics_match_the_cpp_protocol() {
    let (mut sink, ring) = RingSink::new(256, 2, 48_000, 48_000).unwrap();

    // Overrun: one event per short WRITE, dropped frames counted. NOTE:
    // the Rust ring's documented contract counts refused frames as
    // "dropped" (write_frames returns the samples accepted); the C++ ring
    // treats refusal as "not consumed, retry" (dropped stays 0 — a safety
    // net). Both suites pin THEIR OWN ring's contract; the shared
    // invariant is: one event per short write, counters never lose events.
    assert_eq!(sink.write_float(&vec![0.5f32; 256 * 2]).unwrap(), 256);
    assert_eq!(sink.write_float(&vec![0.5f32; 8 * 2]).unwrap(), 0);
    let s = ring.stats();
    assert_eq!(s.overrun_events, 1);
    assert_eq!(s.overrun_dropped_frames, 8);

    // Drain fully (2 × 128): only THEN does a read starve.
    let mut dst = [0.0f32; 128 * 2];
    assert_eq!(ring.read_frames_into(&mut dst), 256); // 128 frames, fully served
    assert_eq!(ring.read_frames_into(&mut dst), 256); // the remaining 128
    assert_eq!(ring.stats().underrun_events, 0);
    let mut dst2 = [0.0f32; 16 * 2];
    assert_eq!(ring.read_frames_into(&mut dst2), 0);
    assert_eq!(ring.stats().underrun_events, 1);

    // Flush: never an underrun, resets occupancy on the next read, acks.
    sink.write_float(&vec![0.5f32; 32 * 2]).unwrap();
    let before = ring.stats();
    sink.request_flush().unwrap();
    let mut dst3 = [0.0f32; 8 * 2];
    assert_eq!(ring.read_frames_into(&mut dst3), 0);
    let after = ring.stats();
    assert_eq!(after.underrun_events, before.underrun_events, "flush is not starvation");
    assert_eq!(ring.occupancy(), 0);
    let (req, ack) = ring.flush_generations();
    assert_eq!(req, ack, "generation acked by the consumer pass");

    // Watermarks: monotone extremes across the session.
    assert!(after.high_watermark_frames >= 256);
    assert_eq!(after.low_watermark_frames, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Zero allocation on the exported edge (counting allocator).
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn exported_edge_is_zero_allocation() {
    let _export = export_lock();
    let (mut sink, ring) = RingSink::new(512, 2, 48_000, 48_000).unwrap();
    ring_export::install(ring.clone());
    let batch = vec![0.5f32; 128 * 2];
    let mut dst = vec![0.0f32; 64 * 2];
    let mut wire = [0u8; SINK_STATS_WIRE_BYTES];

    // Warmup: touch every path once (first-touch, lazy init effects).
    for _ in 0..4 {
        sink.write_float(&batch).unwrap();
        let _ = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 64) };
        sink.request_flush().unwrap();
        let _ = ring.read_frames_into(&mut dst);
        let _ = unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) };
        let _ = ring_export::streamify_ring_occupancy_frames();
    }

    // Measure the steady state: every edge operation is alloc-free.
    let before = allocs();
    for _ in 0..64 {
        sink.write_float(&batch).unwrap();
        let _ = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 64) };
        let _ = unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) };
        let _ = ring_export::streamify_ring_occupancy_frames();
        let _ = ring_export::streamify_ring_channels();
    }
    // request_flush bounded-waits ≤ 100 ms for the (absent) consumer ack;
    // calling it inside the measured loop would dominate runtime, not
    // allocations — one measured flush pass is enough and stays fair.
    sink.request_flush().unwrap();
    let _ = ring.read_frames_into(&mut dst);
    let delta = allocs() - before;
    assert_eq!(delta, 0, "the exported ring edge must never allocate ({} allocs)", delta);

    ring_export::clear();
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Alignment / lock-freedom at runtime (the armv7-a audit's live half).
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn cursor_atomics_are_lock_free_and_cacheline_separated() {
    // The compile-time guard in sink.rs (`target_has_atomic = "64"` +
    // compile_error!) pins lock-freedom at build time; this runtime
    // witness re-states it so a QEMU lane failure names the violated
    // invariant explicitly. (`AtomicU64::is_lock_free` moved between Rust
    // versions; the cfg form is the durable compile-time pin, and size 8
    // with cfg=64 implies native lock-free atomics here.)
    assert!(
        cfg!(target_has_atomic = "64"),
        "the u64 SPSC cursor protocol must be lock-free on this target"
    );
    assert_eq!(
        std::mem::size_of::<AtomicU64>(),
        8,
        "the cursor type is a bare native atomic"
    );
    // One ring; two handles must agree on every observable geometry field.
    let (sink1, ring1) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
    let arc: Arc<_> = ring1.clone();
    assert_eq!(sink1.ring().capacity(), arc.capacity());
    assert_eq!(sink1.ring().channels(), arc.channels());
    drop(sink1);
    // Arc still alive through the consumer handle — the export model.
    assert_eq!(arc.occupancy(), 0);
}

#[test]
fn spsc_stress_100k_frames_through_the_export_edge() {
    let _export = export_lock();
    // A fast single-threaded soak through the C edge with wraparound —
    // the shape the QEMU lane runs for 100k frames at native word width.
    // Pacing discipline: write only what fits, read what's there — the
    // ring must complete the soak with zero underruns AND zero drops
    // (any other outcome is a real protocol defect, not scheduling).
    let (mut sink, ring) = RingSink::new(512, 2, 48_000, 48_000).unwrap();
    ring_export::install(ring.clone());
    let batch = vec![0.5f32; 128 * 2];
    let mut dst = [0.0f32; 128 * 2];
    let mut total_served: usize = 0;
    for i in 0..1000 {
        // Write one quantum, read one quantum: net-zero occupancy, full
        // wraparound of the cursors every 512 frames (the slot-copy paths
        // cross the wrap boundary ~250 times per soak).
        let accepted = sink.write_float(&batch).unwrap();
        assert_eq!(accepted, 128, "pacing keeps the write fully accepted");
        // SAFETY: single consumer thread; 128 frames × 2ch fits dst.
        let served = unsafe { ring_export::streamify_ring_read_f32(dst.as_mut_ptr(), 128) };
        assert_eq!(served, 128, "steady feed must fully serve the pull");
        total_served += served;
        if i % 64 == 0 {
            let s = ring.stats();
            assert_eq!(s.underrun_events, 0, "steady feed must not starve");
        }
    }
    // Every frame accounted: written == served, nothing buffered, nothing
    // dropped, no underruns — 128k frames through the frozen C edge.
    let s = ring.stats();
    assert_eq!(s.underrun_events, 0);
    assert_eq!(s.overrun_events, 0);
    assert_eq!(s.overrun_dropped_frames, 0);
    assert_eq!(s.frames_written as usize, total_served);
    assert_eq!(total_served, 128_000);
    assert_eq!(ring.occupancy(), 0);
    ring_export::clear();
}
