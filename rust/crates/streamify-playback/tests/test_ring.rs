//! The SPSC write-edge discipline suite: the ring's cursor protocol,
//! the cooperative flush handshake under concurrent stress, and the
//! zero-allocation proof for the edge (the RFC §5.5 gate — a
//! counting global allocator wraps every edge call).

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Barrier};
use std::thread;
use std::time::Instant;

use streamify_playback::{AudioSink, RingSink, SINK_STATS_WIRE_MAGIC, SINK_STATS_WIRE_BYTES};

// ─────────────────────────────────────────────────────────────────────────────
// The counting allocator (test-only): proves the edge never allocates.
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

/// Snapshot the allocation counter.
fn allocs() -> u64 {
    ALLOC_COUNT.load(Ordering::Relaxed)
}

// ─────────────────────────────────────────────────────────────────────────────
// Zero allocation on the write edge (RFC §5.5)
// ─────────────────────────────────────────────────────────────────────────────

/// Every edge operation — write, read, flush request/servicing, stats,
/// and the 96-byte wire serialization — must run with ZERO heap
/// allocations. The guard runs single-threaded (no other engine
/// threads exist in this test), so any count delta is attributable to
/// the edge itself.
#[test]
fn write_edge_is_zero_allocation() {
    let (mut sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
    let batch = vec![0.5f32; 512 * 2]; // staging built before measuring

    // Warmup: touch every code path once (first-touch effects, lazy
    // internals) — then measure the steady state.
    for _ in 0..4 {
        sink.write_float(&batch).unwrap();
        let mut dst = vec![0.0f32; 256 * 2];
        ring.read_frames_into(&mut dst);
    }
    let mut dst = vec![0.0f32; 256 * 2];

    let before = allocs();
    for _ in 0..64 {
        sink.write_float(&batch).unwrap();
        ring.read_frames_into(&mut dst);
        let _ = ring.occupancy();
        let _ = ring.estimated_latency_ms();
        let stats = ring.stats();
        let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
        assert!(stats.to_wire(&mut wire));
    }
    let delta = allocs() - before;
    assert_eq!(
        delta, 0,
        "the write edge must be zero-allocation (measured {delta} allocations over 64 cycles)"
    );

    // The flush handshake is allocation-free too. Run it inline: with
    // no consumer on another thread, the request burns its bounded
    // ack deadline (the lazy path) and the next read services it —
    // both halves on THIS thread, no spawn (spawning allocates).
    let before = allocs();
    let ring = sink.ring();
    ring.request_flush();
    let mut dst2 = [0.0f32; 16 * 2];
    let served = ring.read_frames_into(&mut dst2);
    assert_eq!(served, 0, "the flush-servicing read serves silence");
    let delta = allocs() - before;
    assert_eq!(delta, 0, "the flush handshake must be zero-allocation");
}

// ─────────────────────────────────────────────────────────────────────────────
// Concurrent SPSC stress with flush hammering (mirrors the C++ suite)
// ─────────────────────────────────────────────────────────────────────────────

/// The exact production race the generational flush closes (AMP-10 /
/// MP-C10): a control lane hammers `request_flush` while the producer
/// writes and the consumer reads continuously. The occupancy invariant
/// `0 <= head - tail` must hold on every external observation, reads
/// never exceed their request, and every generation gets acked.
#[test]
fn spsc_flush_survives_concurrent_read_write_stress() {
    let (sink, ring) = RingSink::new(4096, 2, 48_000, 48_000).unwrap();
    let sink = Arc::new(std::sync::Mutex::new(sink));
    let violations = Arc::new(AtomicU64::new(0));
    let stop = Arc::new(AtomicBool::new(false));
    let requests_done = Arc::new(AtomicBool::new(false));
    let barrier = Arc::new(Barrier::new(4));

    // Producer: chunked commits of varying sizes.
    {
        let sink = Arc::clone(&sink);
        let stop = Arc::clone(&stop);
        let barrier = Arc::clone(&barrier);
        thread::spawn(move || {
            barrier.wait();
            let mut k: u64 = 0;
            let batch = vec![0.25f32; 128 * 2];
            while !stop.load(Ordering::Relaxed) {
                k = k
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                let frames = 1 + (k % 97) as usize;
                let mut guard = sink.lock().unwrap();
                let _ = guard.write_float(&batch[..frames * 2]);
                if k.is_multiple_of(64) {
                    thread::yield_now();
                }
            }
        });
    }

    // Control lane: flush hammer (bounded).
    {
        let ring = ring.clone();
        let barrier = Arc::clone(&barrier);
        let requests_done = Arc::clone(&requests_done);
        thread::spawn(move || {
            barrier.wait();
            for i in 0..20_000u64 {
                ring.request_flush();
                if i.is_multiple_of(256) {
                    thread::yield_now();
                }
            }
            requests_done.store(true, Ordering::Release);
        });
    }

    // Consumer: audio-callback-shaped reads.
    {
        let ring = ring.clone();
        let violations = Arc::clone(&violations);
        let barrier = Arc::clone(&barrier);
        let requests_done = Arc::clone(&requests_done);
        thread::spawn(move || {
            barrier.wait();
            let mut k: u64 = 0x9E37_79B9_7F4A_7C15;
            let mut dst = vec![0.0f32; 128 * 2];
            let mut i = 0u64;
            while i < 200_000 {
                k = k
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                let want = 1 + (k % 128) as usize;
                let served = ring.read_frames_into(&mut dst[..want * 2]);
                if served > want * 2 {
                    violations.fetch_add(1, Ordering::Relaxed);
                }
                i += 1;
                if i == 200_000 && !requests_done.load(Ordering::Acquire) {
                    i = 0; // keep acking until the control lane drains
                }
            }
        });
    }

    barrier.wait();
    // The control and consumer lanes are bounded; wait for them, then
    // release the producer.
    while !requests_done.load(Ordering::Acquire) {
        thread::sleep(std::time::Duration::from_millis(5));
    }
    // Give the consumer a moment to service the last generation, then
    // check the invariants.
    thread::sleep(std::time::Duration::from_millis(50));
    stop.store(true, Ordering::Relaxed);
    thread::sleep(std::time::Duration::from_millis(50));

    let (requested, acked) = ring.flush_generations();
    assert_eq!(requested, acked, "every flush generation must be acked");
    assert_eq!(violations.load(Ordering::Relaxed), 0);
    assert!(ring.occupancy() <= ring.capacity());
    let stats = ring.stats();
    assert_eq!(stats.layout_magic, SINK_STATS_WIRE_MAGIC);
}

// ─────────────────────────────────────────────────────────────────────────────
// Watermark + stats accounting under producer/consumer pacing
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn watermarks_track_occupancy_extremes() {
    let (mut sink, ring) = RingSink::new(2048, 2, 48_000, 48_000).unwrap();
    // Fill to half, drain fully, fill to the brim.
    sink.write_float(&vec![0.5; 1024 * 2]).unwrap();
    let mut dst = vec![0.0f32; 1024 * 2];
    ring.read_frames_into(&mut dst);
    sink.write_float(&vec![0.5; 2048 * 2]).unwrap();
    let stats = ring.stats();
    assert_eq!(stats.high_watermark_frames, 2048);
    assert_eq!(stats.low_watermark_frames, 0);
    assert_eq!(stats.current_fullness_frames, 2048);
    assert_eq!(stats.frames_written, 1024 + 2048);
    assert_eq!(stats.frames_read, 1024);
    assert_eq!(stats.overrun_events, 0);
    assert_eq!(stats.underrun_events, 0);
    // Estimated latency: 2048 frames at 48 kHz = ~42.67 ms.
    assert!((stats.estimated_latency_ms - 42.666).abs() < 0.1);
    // Sequence is monotonic across reads.
    let s1 = ring.stats().sequence;
    let s2 = ring.stats().sequence;
    assert!(s2 > s1);
}

/// A short consumer read near the drained tail counts exactly one
/// underrun — and the ring keeps serving afterwards.
#[test]
fn consumer_starvation_counts_and_recovers() {
    let (mut sink, ring) = RingSink::new(512, 2, 48_000, 48_000).unwrap();
    sink.write_float(&vec![0.5; 100 * 2]).unwrap();
    let mut dst = vec![0.0f32; 200 * 2];
    assert_eq!(ring.read_frames_into(&mut dst), 200);
    let stats = ring.stats();
    assert_eq!(stats.underrun_events, 1, "the starved tail counts once");
    // The ring refills and a matched-size read serves cleanly.
    sink.write_float(&vec![0.25; 50 * 2]).unwrap();
    let mut small = vec![0.0f32; 50 * 2];
    assert_eq!(ring.read_frames_into(&mut small), 100);
    assert_eq!(ring.stats().underrun_events, 1);
}

/// Rapid back-to-back flush requests coalesce: the consumer acks the
/// newest observed generation in one step (level-triggered protocol).
#[test]
fn flush_requests_coalesce_level_triggered() {
    let (mut sink, ring) = RingSink::new(256, 2, 48_000, 48_000).unwrap();
    sink.write_float(&vec![0.5; 256 * 2]).unwrap();

    // Three requests land before any read services them.
    let ring2 = ring.clone();
    let ring3 = ring.clone();
    let started = Instant::now();
    // No consumer yet: each request burns its ack deadline lazily; the
    // generations stack.
    let requester = thread::spawn(move || ring2.request_flush());
    thread::sleep(std::time::Duration::from_millis(10));
    let requester2 = thread::spawn(move || ring3.request_flush());
    requester.join().unwrap();
    requester2.join().unwrap();
    assert!(
        started.elapsed() >= std::time::Duration::from_millis(100),
        "consumerless requests wait out their deadlines"
    );

    let (requested, _) = ring.flush_generations();
    assert_eq!(requested, 2);

    // One consumer read services BOTH generations at once.
    let mut dst = vec![0.0f32; 16 * 2];
    assert_eq!(ring.read_frames_into(&mut dst), 0);
    assert_eq!(ring.occupancy(), 0);
    let (requested, acked) = ring.flush_generations();
    assert_eq!((requested, acked), (2, 2));
}
