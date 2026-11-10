//! The sink write edge — the Rust-side mirror of the frozen C++ ABI
//! (`native/include/streamify_audio_sinks.h`, RFC §6).
//!
//! Three pieces:
//!
//! * [`AudioSink`] — the trait the producer writes into and the A5
//!   VirtualHAL double impersonates (RFC §6.2). Field-for-field and
//!   semantics-for-semantics aligned with the frozen 7-symbol JNI
//!   surface; it adds NO wire surface of its own.
//! * [`SpscAudioRing`] — the cacheline-aligned single-producer /
//!   single-consumer ring with the cooperative `RequestFlush`
//!   generation handshake (AMP-10 / MP-C10): release-store request,
//!   consumer acquire-exchange, tail-forward reset on the consumer's
//!   next read. The exact protocol `test_uniffi_contract.rs`
//!   `spsc_cooperative_flush_survives_concurrent_read_stress` pins and
//!   `native/tests/test_audio_sink_ringbuffer.cpp` proves on the C++
//!   side.
//! * [`SinkStats`] — the Rust image of the 96-byte DirectByteBuffer
//!   stats wire (magic `0x51AB0001`), serializable little-endian at the
//!   documented offsets so the JNI bridge (C1) and the desktop JNA shim
//!   (C5) can consume the identical layout.
//!
//! # RT contract
//!
//! [`SpscAudioRing::write_frames`], [`SpscAudioRing::read_frames_into`],
//! the flush handshake and [`SpscAudioRing::stats`] never allocate and
//! never lock: the edge is a memcpy-shaped commit under the SPSC cursor
//! protocol (proved by the counting-allocator gate in
//! `tests/test_ring.rs`). The ring buffer itself is allocated once at
//! construction.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use streamify_core::{AudioErrorKind, EngineError};

/// The stats wire magic, frozen by the C++ ABI (`kSinkStatsWireMagic`).
pub const SINK_STATS_WIRE_MAGIC: u32 = 0x51AB_0001;
/// The stats wire size, frozen by the C++ ABI (`kSinkStatsWireBytes`).
pub const SINK_STATS_WIRE_BYTES: usize = 96;
/// Bounded wait for the consumer to acknowledge a flush request
/// (mirrors `kFlushAckTimeoutMs` on the C++ side; a consumerless
/// requester gives up and the flush applies lazily).
const FLUSH_ACK_TIMEOUT_MS: u64 = 100;
/// Ring capacity bounds (mirror the native `AudioSink` geometry and the
/// VirtualHAL double's own validation).
const MIN_CAPACITY_FRAMES: usize = 64;
const MAX_CAPACITY_FRAMES: usize = 1 << 22;

/// Cacheline alignment for the ring's hot cursors (the C++ ring's
/// `alignas(64)` discipline: the producer and consumer cursors never
/// share a line, so the SPSC protocol cannot false-share).
#[repr(align(64))]
struct CacheLine<T>(T);

/// The Rust-side mirror of the frozen sink contract (RFC §6.2).
///
/// The producer owns exactly one implementing instance (single writer,
/// SPSC by construction); `write_float` returns the FRAMES accepted —
/// a short write means the ring is at its high watermark and the
/// producer parks, never blocks the audio callback. `request_flush` is
/// the cooperative AMP-10 protocol: safe to call while the consumer is
/// live.
pub trait AudioSink: Send {
    /// Write interleaved canonical-geometry `f32` frames. Returns the
    /// number of frames accepted.
    fn write_float(&mut self, frames: &[f32]) -> Result<usize, EngineError>;
    /// Cooperative flush (AMP-10): the consumer performs the cursor
    /// reset on its next read.
    fn request_flush(&mut self) -> Result<(), EngineError>;
    /// Estimated output latency [ms] from the sink's own accounting.
    fn estimated_latency_ms(&self) -> f32;
    /// Telemetry snapshot (the 96-byte wire, one struct).
    fn stats(&self) -> SinkStats;
}

/// The Rust image of the 96-byte stats DirectByteBuffer wire (little-
/// endian, layout v1 — field-for-field the C++ `SinkStats`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SinkStats {
    /// Wire layout magic (always [`SINK_STATS_WIRE_MAGIC`]).
    pub layout_magic: u32,
    /// Wire layout size in bytes (always [`SINK_STATS_WIRE_BYTES`]).
    pub layout_bytes: u32,
    /// Ring capacity in frames (power of two).
    pub capacity_frames: u32,
    /// Input (producer) rate in Hz.
    pub sample_rate_in: u32,
    /// Output (consumer) rate in Hz.
    pub sample_rate_out: u32,
    /// Input channel count.
    pub channels_in: u32,
    /// Output channel count.
    pub channels_out: u32,
    /// Live ring occupancy in frames.
    pub current_fullness_frames: u32,
    /// Frames the producer has committed, cumulative.
    pub frames_written: u64,
    /// Frames the consumer has drained, cumulative.
    pub frames_read: u64,
    /// Consumer pulls the ring could not fully serve.
    pub underrun_events: u64,
    /// Producer writes the ring could not fully accept.
    pub overrun_events: u64,
    /// Frames dropped by those overruns.
    pub overrun_dropped_frames: u64,
    /// Maximum occupancy observed after a write.
    pub high_watermark_frames: u32,
    /// Minimum occupancy observed after a read.
    pub low_watermark_frames: u32,
    /// Occupancy / capacity in percent.
    pub fullness_percent: f32,
    /// Occupancy-derived output latency estimate [ms].
    pub estimated_latency_ms: f32,
    /// Monotonic stats sequence (increments on every read; the change
    /// detector foreign pollers use).
    pub sequence: u32,
}

impl SinkStats {
    /// Serialize the little-endian 96-byte wire image at the frozen
    /// offsets (`+92` reserved, written as 0). Returns `false` when
    /// `dst` is shorter than the wire.
    pub fn to_wire(&self, dst: &mut [u8]) -> bool {
        if dst.len() < SINK_STATS_WIRE_BYTES {
            return false;
        }
        let put_u32 = |dst: &mut [u8], off: usize, v: u32| {
            dst[off..off + 4].copy_from_slice(&v.to_le_bytes());
        };
        let put_u64 = |dst: &mut [u8], off: usize, v: u64| {
            dst[off..off + 8].copy_from_slice(&v.to_le_bytes());
        };
        let put_f32 = |dst: &mut [u8], off: usize, v: f32| {
            dst[off..off + 4].copy_from_slice(&v.to_le_bytes());
        };
        put_u32(dst, 0, self.layout_magic);
        put_u32(dst, 4, self.layout_bytes);
        put_u32(dst, 8, self.capacity_frames);
        put_u32(dst, 12, self.sample_rate_in);
        put_u32(dst, 16, self.sample_rate_out);
        put_u32(dst, 20, self.channels_in);
        put_u32(dst, 24, self.channels_out);
        put_u32(dst, 28, self.current_fullness_frames);
        put_u64(dst, 32, self.frames_written);
        put_u64(dst, 40, self.frames_read);
        put_u64(dst, 48, self.underrun_events);
        put_u64(dst, 56, self.overrun_events);
        put_u64(dst, 64, self.overrun_dropped_frames);
        put_u32(dst, 72, self.high_watermark_frames);
        put_u32(dst, 76, self.low_watermark_frames);
        put_f32(dst, 80, self.fullness_percent);
        put_f32(dst, 84, self.estimated_latency_ms);
        put_u32(dst, 88, self.sequence);
        put_u32(dst, 92, 0);
        true
    }
}

/// The SPSC ring backing the write edge.
///
/// One producer thread (via [`RingSink`]) and one consumer thread (the
/// HAL puller, via an `Arc` clone) share free-running `u64` frame
/// cursors over a power-of-two slot array. Slot access is unsynchronized
/// BY DESIGN and safe under the cursor protocol (see the safety note on
/// [`Sync`]); every cursor publication is a Release store paired with an
/// Acquire load on the observing side, so the memcpy'd frames are
/// visible before the cursor that publishes them.
///
/// The cooperative flush protocol (AMP-10 / MP-C10): a requester bumps
/// `flush_generation`; the CONSUMER, on its next read, observes the
/// change, snaps its tail to the committed head, publishes the ack and
/// serves one silence quantum. The requester bounded-waits for the ack
/// ([`FLUSH_ACK_TIMEOUT_MS`]) — never from the consumer thread itself.
/// Level-triggered and coalescing: N bumps before the next read collapse
/// into one pass.
pub struct SpscAudioRing {
    // ── producer-owned line ───────────────────────────────────────────
    /// Frames committed by the producer (virtual, free-running).
    head: CacheLine<AtomicU64>,
    // ── consumer-owned line ───────────────────────────────────────────
    /// Frames drained by the consumer (virtual, free-running).
    tail: CacheLine<AtomicU64>,
    // ── flush handshake line ─────────────────────────────────────────
    /// Requester-owned: bumped by `request_flush`, never reset.
    flush_generation: CacheLine<AtomicU64>,
    /// Consumer-owned: newest generation the consumer has serviced.
    flush_ack_generation: CacheLine<AtomicU64>,
    // ── telemetry counters (Relaxed; monotonically growing) ──────────
    frames_written: AtomicU64,
    frames_read: AtomicU64,
    underrun_events: AtomicU64,
    overrun_events: AtomicU64,
    overrun_dropped_frames: AtomicU64,
    high_watermark_frames: AtomicU32,
    low_watermark_frames: AtomicU32,
    stats_sequence: AtomicU32,
    // ── immutable configuration + the slot array ──────────────────────
    slots: UnsafeCell<Box<[f32]>>,
    capacity: usize,
    mask: usize,
    channels: usize,
    sample_rate_in: u32,
    sample_rate_out: u32,
}

// SAFETY: `SpscAudioRing` is the classic Lamport SPSC ring. The slot
// array behind `UnsafeCell` is partitioned by the cursor protocol: a
// slot at virtual frame `v` is written ONLY by the single producer and
// only while `v >= head` (i.e. not yet committed) — which implies
// `v >= tail` (the consumer only reads committed frames), and read ONLY
// by the single consumer while `tail <= v < head`. The producer is the
// unique `RingSink` owner (the engine mints exactly one); the consumer
// is the unique HAL puller driving `read_frames_into`. Data writes
// precede `head`'s Release store and follow `tail`'s Release store, so
// the Acquire loads on the observing side establish happens-before for
// every slot transfer. Two concurrent producers or two concurrent
// consumers would violate the protocol — the same construction contract
// the C++ ring documents (`SPSC by construction`).
unsafe impl Sync for SpscAudioRing {}

impl std::fmt::Debug for SpscAudioRing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SpscAudioRing")
            .field("capacity_frames", &self.capacity)
            .field("channels", &self.channels)
            .field("sample_rate_in", &self.sample_rate_in)
            .field("sample_rate_out", &self.sample_rate_out)
            .field("occupancy_frames", &self.occupancy())
            .finish_non_exhaustive()
    }
}

impl SpscAudioRing {
    /// Build the ring for one geometry. `capacity_frames` is
    /// power-of-two-rounded within the native bounds; `channels` is 1
    /// or 2; the in/out rates must be sane hardware rates. The slot
    /// array is allocated HERE, once — nothing on the edge allocates
    /// afterwards.
    pub fn new(
        capacity_frames: usize,
        channels: usize,
        sample_rate_in: u32,
        sample_rate_out: u32,
    ) -> Result<Self, EngineError> {
        let bad = || EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: None,
        };
        if !(MIN_CAPACITY_FRAMES..=MAX_CAPACITY_FRAMES).contains(&capacity_frames) {
            return Err(bad());
        }
        if !matches!(channels, 1 | 2) {
            return Err(bad());
        }
        if !(8_000..=192_000).contains(&sample_rate_in)
            || !(8_000..=192_000).contains(&sample_rate_out)
        {
            return Err(bad());
        }
        let capacity = capacity_frames.max(MIN_CAPACITY_FRAMES).next_power_of_two();
        Ok(Self {
            head: CacheLine(AtomicU64::new(0)),
            tail: CacheLine(AtomicU64::new(0)),
            flush_generation: CacheLine(AtomicU64::new(0)),
            flush_ack_generation: CacheLine(AtomicU64::new(0)),
            frames_written: AtomicU64::new(0),
            frames_read: AtomicU64::new(0),
            underrun_events: AtomicU64::new(0),
            overrun_events: AtomicU64::new(0),
            overrun_dropped_frames: AtomicU64::new(0),
            high_watermark_frames: AtomicU32::new(0),
            low_watermark_frames: AtomicU32::new(capacity as u32),
            stats_sequence: AtomicU32::new(0),
            slots: UnsafeCell::new(vec![0.0f32; capacity * channels].into_boxed_slice()),
            capacity,
            mask: capacity - 1,
            channels,
            sample_rate_in,
            sample_rate_out,
        })
    }

    /// Capacity in frames (power of two).
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// Channel count.
    pub fn channels(&self) -> usize {
        self.channels
    }

    /// Producer-side input rate (Hz).
    pub fn sample_rate_in(&self) -> u32 {
        self.sample_rate_in
    }

    /// Consumer-side output rate (Hz).
    pub fn sample_rate_out(&self) -> u32 {
        self.sample_rate_out
    }

    /// Live occupancy in frames. Must read `tail` BEFORE `head` (the
    /// documented GetStats ordering: guarantees `t <= head(T_tail) <=
    /// head(T_head)`, so the difference never underflows).
    pub fn occupancy(&self) -> usize {
        let t = self.tail.0.load(Ordering::Acquire);
        let h = self.head.0.load(Ordering::Acquire);
        (h - t) as usize
    }

    /// Producer edge: write interleaved `f32` samples (whole frames
    /// only). Returns the SAMPLE count consumed; a short return means
    /// the ring is at capacity (one overrun event is counted per short
    /// write). Zero-allocation, zero-lock.
    pub fn write_frames(&self, samples: &[f32]) -> usize {
        let ch = self.channels;
        let offered = samples.len() - samples.len() % ch;
        if offered == 0 {
            return 0;
        }
        let t = self.tail.0.load(Ordering::Acquire);
        let h = self.head.0.load(Ordering::Relaxed);
        let free = self.capacity - (h - t) as usize;
        let take_frames = (offered / ch).min(free);
        let take = take_frames * ch;
        if take > 0 {
            // SAFETY: producer-exclusive region — every slot in
            // [head, head+take_frames) is beyond the consumer's tail by
            // the free-space computation above, and this thread is the
            // single producer.
            unsafe { self.copy_in(h, &samples[..take]) };
            self.head.0.store(h + take_frames as u64, Ordering::Release);
            let used = (h - t) as usize + take_frames;
            self.high_watermark_frames
                .fetch_max(used as u32, Ordering::Relaxed);
        }
        self.frames_written
            .fetch_add(take_frames as u64, Ordering::Relaxed);
        if take < offered {
            self.overrun_events.fetch_add(1, Ordering::Relaxed);
            self.overrun_dropped_frames
                .fetch_add(((offered - take) / ch) as u64, Ordering::Relaxed);
        }
        take
    }

    /// Consumer edge: drain up to `dst.len()` interleaved samples (the
    /// frame count IS `dst.len() / channels`). Returns the SAMPLE count
    /// served; a FLUSH PENDING pass serves 0 (one silence quantum —
    /// deliberately NOT counted as an underrun: it is the skip
    /// protocol, not starvation). A genuine short read (ring emptier
    /// than the request) counts one underrun event. Zero-allocation,
    /// zero-lock.
    pub fn read_frames_into(&self, dst: &mut [f32]) -> usize {
        let ch = self.channels;
        let want = dst.len() - dst.len() % ch;
        if want == 0 {
            return 0;
        }
        // Flush servicing first: level-triggered, coalescing, and
        // NEVER an underrun — the reset IS the requested behavior.
        let gen = self.flush_generation.0.load(Ordering::Acquire);
        if gen != self.flush_ack_generation.0.load(Ordering::Relaxed) {
            let h = self.head.0.load(Ordering::Acquire);
            self.tail.0.store(h, Ordering::Release);
            self.flush_ack_generation.0.store(gen, Ordering::Release);
            return 0;
        }
        let t = self.tail.0.load(Ordering::Relaxed);
        let h = self.head.0.load(Ordering::Acquire);
        let have = (h - t) as usize;
        let take_frames = (want / ch).min(have);
        let take = take_frames * ch;
        if take > 0 {
            // SAFETY: consumer-visible region — every slot in
            // [tail, tail+take_frames) is below the acquired `head`, so
            // the producer's Release publication ordered those writes
            // before this read; this thread is the single consumer.
            unsafe { self.copy_out(t, &mut dst[..take]) };
            self.tail.0.store(t + take_frames as u64, Ordering::Release);
            let used = have - take_frames;
            self.low_watermark_frames
                .fetch_min(used as u32, Ordering::Relaxed);
        }
        self.frames_read
            .fetch_add(take_frames as u64, Ordering::Relaxed);
        if take < want {
            self.underrun_events.fetch_add(1, Ordering::Relaxed);
        }
        take
    }

    /// Cooperative flush request (any thread except the consumer
    /// itself): bump the generation, then bounded-wait for the
    /// consumer's ack. A consumerless or stalled consumer lets the
    /// deadline pass and the flush applies lazily on the next read.
    pub fn request_flush(&self) {
        let gen = self.flush_generation.0.fetch_add(1, Ordering::Relaxed) + 1;
        let deadline = Instant::now() + Duration::from_millis(FLUSH_ACK_TIMEOUT_MS);
        while self.flush_ack_generation.0.load(Ordering::Acquire) < gen {
            if Instant::now() >= deadline {
                break;
            }
            thread::yield_now();
        }
    }

    /// The generation handshake state, for diagnostics: (requested,
    /// acknowledged).
    pub fn flush_generations(&self) -> (u64, u64) {
        (
            self.flush_generation.0.load(Ordering::Acquire),
            self.flush_ack_generation.0.load(Ordering::Acquire),
        )
    }

    /// Occupancy-derived output latency estimate [ms].
    pub fn estimated_latency_ms(&self) -> f32 {
        self.occupancy() as f32 * 1000.0 / self.sample_rate_out as f32
    }

    /// Telemetry snapshot (one sequence bump per call — the change
    /// detector foreign pollers use).
    pub fn stats(&self) -> SinkStats {
        let fullness = self.occupancy();
        let sequence = self.stats_sequence.fetch_add(1, Ordering::Relaxed) + 1;
        SinkStats {
            layout_magic: SINK_STATS_WIRE_MAGIC,
            layout_bytes: SINK_STATS_WIRE_BYTES as u32,
            capacity_frames: self.capacity as u32,
            sample_rate_in: self.sample_rate_in,
            sample_rate_out: self.sample_rate_out,
            channels_in: self.channels as u32,
            channels_out: self.channels as u32,
            current_fullness_frames: fullness as u32,
            frames_written: self.frames_written.load(Ordering::Relaxed),
            frames_read: self.frames_read.load(Ordering::Relaxed),
            underrun_events: self.underrun_events.load(Ordering::Relaxed),
            overrun_events: self.overrun_events.load(Ordering::Relaxed),
            overrun_dropped_frames: self.overrun_dropped_frames.load(Ordering::Relaxed),
            high_watermark_frames: self.high_watermark_frames.load(Ordering::Relaxed),
            low_watermark_frames: self.low_watermark_frames.load(Ordering::Relaxed),
            fullness_percent: fullness as f32 * 100.0 / self.capacity as f32,
            estimated_latency_ms: self.estimated_latency_ms(),
            sequence,
        }
    }

    /// Copy `samples` (whole frames, `take` samples long) into the
    /// slots starting at virtual frame `start_frame`, wrapping once.
    ///
    /// # Safety
    ///
    /// Caller (the single producer) must guarantee every destination
    /// slot in `[start_frame, start_frame + take/channels)` is
    /// producer-exclusive: not yet committed (`>= head`) and at or
    /// beyond the consumer's acquired tail.
    unsafe fn copy_in(&self, start_frame: u64, samples: &[f32]) {
        let ch = self.channels;
        let slots = unsafe { &mut *self.slots.get() };
        let start = (start_frame as usize & self.mask) * ch;
        let take = samples.len();
        let first = take.min(slots.len() - start);
        slots[start..start + first].copy_from_slice(&samples[..first]);
        if take > first {
            slots[..take - first].copy_from_slice(&samples[first..]);
        }
    }

    /// Copy `take` interleaved samples out of the slots starting at
    /// virtual frame `start_frame`, wrapping once.
    ///
    /// # Safety
    ///
    /// Caller (the single consumer) must guarantee every source slot in
    /// `[start_frame, start_frame + take/frames)` is committed: below
    /// the acquired `head` and not yet drained (`>= tail`).
    unsafe fn copy_out(&self, start_frame: u64, dst: &mut [f32]) {
        let ch = self.channels;
        let slots = unsafe { &*self.slots.get() };
        let start = (start_frame as usize & self.mask) * ch;
        let take = dst.len();
        let first = take.min(slots.len() - start);
        dst[..first].copy_from_slice(&slots[start..start + first]);
        if take > first {
            dst[first..].copy_from_slice(&slots[..take - first]);
        }
    }
}

/// The [`AudioSink`] implementation over an [`SpscAudioRing`] — the
/// producer's handle. The consumer side reads through its own `Arc`
/// clone of the ring (the engine hands it out via `playback_ring()`).
#[derive(Debug)]
pub struct RingSink {
    ring: std::sync::Arc<SpscAudioRing>,
}

impl RingSink {
    /// Build the ring and return the producer sink plus the consumer's
    /// shared handle.
    pub fn new(
        capacity_frames: usize,
        channels: usize,
        sample_rate_in: u32,
        sample_rate_out: u32,
    ) -> Result<(Self, std::sync::Arc<SpscAudioRing>), EngineError> {
        let ring = std::sync::Arc::new(SpscAudioRing::new(
            capacity_frames,
            channels,
            sample_rate_in,
            sample_rate_out,
        )?);
        Ok((Self { ring: ring.clone() }, ring))
    }

    /// The consumer-side handle.
    pub fn ring(&self) -> &std::sync::Arc<SpscAudioRing> {
        &self.ring
    }
}

impl AudioSink for RingSink {
    fn write_float(&mut self, frames: &[f32]) -> Result<usize, EngineError> {
        if !frames.len().is_multiple_of(self.ring.channels) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        Ok(self.ring.write_frames(frames) / self.ring.channels)
    }

    fn request_flush(&mut self) -> Result<(), EngineError> {
        self.ring.request_flush();
        Ok(())
    }

    fn estimated_latency_ms(&self) -> f32 {
        self.ring.estimated_latency_ms()
    }

    fn stats(&self) -> SinkStats {
        self.ring.stats()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constructor_validates_geometry() {
        assert!(SpscAudioRing::new(64, 2, 48_000, 48_000).is_ok());
        assert!(SpscAudioRing::new(8192, 1, 44_100, 48_000).is_ok());
        for bad_cap in [0usize, 16, (1 << 22) + 1] {
            assert!(SpscAudioRing::new(bad_cap, 2, 48_000, 48_000).is_err());
        }
        for bad_ch in [0usize, 3, 9] {
            assert!(SpscAudioRing::new(1024, bad_ch, 48_000, 48_000).is_err());
        }
        for bad_rate in [0u32, 7_999, 192_001] {
            assert!(SpscAudioRing::new(1024, 2, bad_rate, 48_000).is_err());
            assert!(SpscAudioRing::new(1024, 2, 48_000, bad_rate).is_err());
        }
    }

    #[test]
    fn single_thread_write_read_wrap_and_stats() {
        let (mut sink, ring) = RingSink::new(128, 2, 48_000, 48_000).unwrap();
        assert_eq!(ring.capacity(), 128);

        // Fill exactly to capacity.
        let batch: Vec<f32> = (0..128 * 2).map(|i| i as f32).collect();
        assert_eq!(sink.write_float(&batch).unwrap(), 128);
        let s = ring.stats();
        assert_eq!(s.current_fullness_frames, 128);
        assert_eq!(s.high_watermark_frames, 128);
        assert_eq!(s.fullness_percent, 100.0);
        assert_eq!(s.underrun_events, 0);
        assert_eq!(s.overrun_events, 0);

        // Overrun: one more frame is refused (counted, not dropped
        // silently — the producer will retry).
        assert_eq!(sink.write_float(&[9.0, 9.0]).unwrap(), 0);
        let s = ring.stats();
        assert_eq!(s.overrun_events, 1);
        assert_eq!(s.overrun_dropped_frames, 1);

        // Drain half — wrap the producer around on the next fill.
        let mut dst = vec![0.0f32; 64 * 2];
        assert_eq!(ring.read_frames_into(&mut dst), 128); // 64 frames
        assert_eq!(dst[..5], [0.0f32, 1.0, 2.0, 3.0, 4.0]);
        let s = ring.stats();
        assert_eq!(s.current_fullness_frames, 64);
        assert_eq!(s.low_watermark_frames, 64);
        assert_eq!(s.underrun_events, 0);

        // Refill across the wrap boundary: only 64 frames are free, so
        // the second fill accepts HALF its offer (a second overrun
        // event) and lands in slots [0..64).
        assert_eq!(sink.write_float(&batch).unwrap(), 64);
        let mut s = ring.stats();
        assert_eq!(s.overrun_events, 2);
        assert_eq!(s.overrun_dropped_frames, 65);
        assert_eq!(s.current_fullness_frames, 128);

        // Reading resumes at the drained cursor: the first batch's
        // unwrapped tail, then the second batch's wrapped head.
        let mut tail = vec![0.0f32; 128 * 2];
        assert_eq!(ring.read_frames_into(&mut tail), 256);
        assert_eq!(tail[..4], [128.0f32, 129.0, 130.0, 131.0]);
        assert_eq!(tail[252..256], [124.0f32, 125.0, 126.0, 127.0]);
        s = ring.stats();
        assert_eq!(s.underrun_events, 0, "every read was fully served");
        assert_eq!(s.current_fullness_frames, 0);

        // Underrun: a read against an empty ring counts exactly one
        // event and serves nothing (the HAL zero-fills its own
        // quantum — the C++ contract).
        assert_eq!(ring.read_frames_into(&mut dst), 0);
        assert_eq!(ring.stats().underrun_events, 1);
    }

    #[test]
    fn cooperative_flush_resets_on_next_read_without_underrun() {
        let (mut sink, ring) = RingSink::new(256, 2, 48_000, 48_000).unwrap();
        sink.write_float(&vec![0.5; 512]).unwrap();
        assert_eq!(ring.occupancy(), 256);

        // No consumer running: the bounded wait burns its deadline and
        // the flush applies lazily.
        let started = Instant::now();
        sink.request_flush().unwrap();
        assert!(
            started.elapsed() >= Duration::from_millis(FLUSH_ACK_TIMEOUT_MS),
            "a consumerless flush must wait out the ack deadline"
        );
        let (req, ack) = ring.flush_generations();
        assert_eq!(req, 1);
        assert_eq!(ack, 0, "no consumer has serviced the generation yet");

        // The consumer's next read performs the reset and serves
        // silence — NOT an underrun.
        let mut dst = vec![0.0f32; 32 * 2];
        assert_eq!(ring.read_frames_into(&mut dst), 0);
        assert!(dst.iter().all(|s| *s == 0.0));
        assert_eq!(ring.occupancy(), 0);
        let (req, ack) = ring.flush_generations();
        assert_eq!((req, ack), (1, 1));
        let s = ring.stats();
        assert_eq!(s.underrun_events, 0, "flush servicing is not starvation");

        // Normal reads resume afterwards.
        sink.write_float(&vec![0.25; 64 * 2]).unwrap();
        let mut dst2 = vec![0.0f32; 16 * 2];
        assert_eq!(ring.read_frames_into(&mut dst2), 32);
        assert!(dst2.iter().all(|s| *s == 0.25));
    }

    #[test]
    fn stats_wire_layout_matches_the_frozen_offsets() {
        let (_, ring) = RingSink::new(512, 2, 48_000, 48_000).unwrap();
        let s = ring.stats();
        let mut wire = [0u8; SINK_STATS_WIRE_BYTES];
        assert!(s.to_wire(&mut wire));
        let rdu32 = |o: usize| u32::from_le_bytes(wire[o..o + 4].try_into().unwrap());
        assert_eq!(rdu32(0), SINK_STATS_WIRE_MAGIC);
        assert_eq!(rdu32(4), SINK_STATS_WIRE_BYTES as u32);
        assert_eq!(rdu32(8), 512);
        assert_eq!(rdu32(12), 48_000);
        assert_eq!(rdu32(16), 48_000);
        assert_eq!(rdu32(20), 2);
        assert_eq!(rdu32(24), 2);
        // Short buffers are refused.
        assert!(!s.to_wire(&mut [0u8; 95]));
    }
}
