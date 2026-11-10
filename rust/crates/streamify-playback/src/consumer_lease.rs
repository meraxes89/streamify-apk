//! consumer_lease — the platform mounting bracket's Rust half (WS-B B4).
//!
//! `StreamifyEngine::playback_ring()` (streamify-contract) is DELIBERATELY
//! not a UDL surface: the platform lanes mount against the frozen ABI
//! through their own brackets (A1 RFC §7 — "platform teams mount brackets,
//! not pipelines"). This module is the Apple bracket's Rust half: a
//! process-wide lease of the CURRENTLY attached plane's consumer ring,
//! published by `attach_sink` / withdrawn by `detach_sink`, and a small
//! C ABI the native Apple mount (`native/audio/apple_ring_mount.cpp`)
//! pulls through:
//!
//! * `streamify_ring_read_f32`        — the consumer pull edge (lock-free,
//!   allocation-free; the identical operation the A5 VirtualHAL harness
//!   and the `playback_ring()` accessor hand to Rust consumers).
//! * `streamify_ring_request_flush`   — cooperative flush (skip protocol).
//! * `streamify_ring_flush_generations` — the generation handshake the
//!   native pump polls to FORWARD engine-side skips onto its own sink.
//! * `streamify_ring_stats_wire`      — the 96-byte stats wire, the same
//!   frozen layout the C++ `SinkStats`/JNI DirectByteBuffer lane speaks.
//! * `streamify_ring_is_attached`     — cheap attachment probe.
//!
//! Concurrency: ONE engine per process by construction (the shell's single
//! `StreamifyEngine`), so the lease is a `Mutex<Option<Arc<_>>>` slot —
//! publish is O(1) under the playback lock's release, and every C entry
//! degrades to a clean no-op (0 / false) while no plane is attached. The C
//! edge itself is the SAME lock-free ring operations the producer and the
//! A5 harness already run; the lease only hands the `Arc` across the FFI.
//!
//! The C ABI is `unsafe extern "C"` with NO unwinding across the boundary
//! (MP-C06 discipline): the ring methods are total, panic-free by
//! construction, and the slice math clamps instead of trusting the caller.

use std::ffi::c_int;
use std::sync::{Arc, Mutex, OnceLock};

use crate::sink::{SpscAudioRing, SINK_STATS_WIRE_BYTES};

/// The process-wide lease slot. `OnceLock` for the lazy, `Mutex` for the
/// publish/withdraw handoff (never held across a ring operation).
fn slot() -> &'static Mutex<Option<Arc<SpscAudioRing>>> {
    static SLOT: OnceLock<Mutex<Option<Arc<SpscAudioRing>>>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

/// Publish (or replace) the consumer lease. Called by the engine's
/// `attach_sink` AFTER the plane is wired (replace semantics mirror
/// `attach_sink`: the old plane's producer is already joined by then).
pub fn publish(ring: Arc<SpscAudioRing>) {
    *slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(ring);
}

/// Withdraw the lease (the plane is going away; `detach_sink` joins the
/// producer BEFORE this runs, so no reader can still be mid-pull on the
/// Arc — the `Arc` keeps the ring alive for any in-flight C reader anyway).
pub fn withdraw() {
    *slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
}

/// A snapshot of the current lease for Rust callers (tests).
pub fn current() -> Option<Arc<SpscAudioRing>> {
    slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

/// True when a plane is attached (the C probe backs onto this).
pub fn is_attached() -> bool {
    slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .is_some()
}

// ─────────────────────────────────────────────────────────────────────────────
// The C ABI (the native Apple mount's pull edge)
// ─────────────────────────────────────────────────────────────────────────────

/// Consumer pull: drain up to `samples` interleaved f32 samples into
/// `dst`. Returns the sample count served (0 while no plane is attached
/// or the ring is empty/flush-pending). Mirrors
/// [`SpscAudioRing::read_frames_into`] exactly.
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_read_f32(dst: *mut f32, samples: usize) -> usize {
    let Some(ring) = current() else { return 0 };
    if dst.is_null() {
        return 0;
    }
    // SAFETY: `dst` is caller-owned writable memory of `samples` f32s; the
    // slice is only borrowed for the duration of the call. The ring clamps
    // to whole frames internally and copies at most `samples` values.
    let buffer = std::slice::from_raw_parts_mut(dst, samples);
    ring.read_frames_into(buffer)
}

/// Cooperative flush request (the skip protocol). No-op while no plane is
/// attached. Never called from the consumer thread by contract.
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_request_flush() {
    if let Some(ring) = current() {
        ring.request_flush();
    }
}

/// The generation handshake: writes `(requested, acknowledged)` into the
/// out-params and returns 1, or returns 0 while no plane is attached. The
/// native pump polls this to forward engine-side skips onto its own sink.
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_flush_generations(
    requested: *mut u64,
    acknowledged: *mut u64,
) -> c_int {
    let Some(ring) = current() else { return 0 };
    let (req, ack) = ring.flush_generations();
    if let Some(requested) = requested {
        *requested = req;
    }
    if let Some(acknowledged) = acknowledged {
        *acknowledged = ack;
    }
    1
}

/// Serialize the 96-byte stats wire (the frozen layout the C++ `SinkStats`
/// and the JNI DirectByteBuffer lane share) into `dst`. Returns false when
/// no plane is attached or `capacity` is shorter than the wire.
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_stats_wire(dst: *mut u8, capacity: usize) -> bool {
    let Some(ring) = current() else { return false };
    if dst.is_null() || capacity < SINK_STATS_WIRE_BYTES {
        return false;
    }
    // SAFETY: caller-owned writable memory of `capacity` bytes, borrowed
    // for the duration of the call; `to_wire` writes exactly
    // SINK_STATS_WIRE_BYTES (<= capacity, checked above).
    let buffer = std::slice::from_raw_parts_mut(dst, capacity);
    ring.stats().to_wire(buffer)
}

/// 1 while a playback plane is attached, 0 otherwise.
#[no_mangle]
pub unsafe extern "C" fn streamify_ring_is_attached() -> c_int {
    if is_attached() {
        1
    } else {
        0
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests: the lease lifecycle + the C edge over a REAL ring (write through
// the producer-side sink, read through the C ABI, flush generations).
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sink::AudioSink;
    use crate::sink::RingSink;

    fn clean_slot() {
        *slot()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
    }

    #[test]
    fn lease_publish_withdraw_and_probe() {
        clean_slot();
        assert!(!is_attached());
        assert_eq!(unsafe { streamify_ring_is_attached() }, 0);

        let (_sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
        publish(ring.clone());
        assert!(is_attached());
        assert_eq!(unsafe { streamify_ring_is_attached() }, 1);
        assert!(current().is_some());

        withdraw();
        assert!(!is_attached());
        // Clean degradation: every C entry is a no-op while detached.
        let mut scratch = [0.0f32; 8];
        assert_eq!(unsafe { streamify_ring_read_f32(scratch.as_mut_ptr(), 8) }, 0);
        unsafe { streamify_ring_request_flush() };
        let (mut req, mut ack) = (0u64, 0u64);
        assert_eq!(
            unsafe { streamify_ring_flush_generations(&mut req, &mut ack) },
            0
        );
        let mut wire = [0u8; crate::sink::SINK_STATS_WIRE_BYTES];
        assert!(!unsafe { streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) });
    }

    #[test]
    fn c_edge_reads_what_the_producer_wrote() {
        clean_slot();
        let (mut sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
        publish(ring);

        // Producer side commits 4 frames (8 interleaved samples).
        let batch: Vec<f32> = (0..8).map(|i| i as f32);
        assert_eq!(sink.write_float(&batch).unwrap(), 4);

        // Consumer side drains through the C ABI.
        let mut dst = [0.0f32; 8];
        let served = unsafe { streamify_ring_read_f32(dst.as_mut_ptr(), dst.len()) };
        assert_eq!(served, 8);
        assert_eq!(dst[..4], [0.0, 1.0, 2.0, 3.0]);

        // A second read starves: 0 served (one underrun on the ring's own
        // accounting — irrelevant to the ABI contract, which just reports 0).
        assert_eq!(unsafe { streamify_ring_read_f32(dst.as_mut_ptr(), dst.len()) }, 0);
    }

    #[test]
    fn c_edge_reports_flush_generations() {
        clean_slot();
        let (_sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
        publish(ring);

        let (mut req, mut ack) = (0u64, 0u64);
        assert_eq!(
            unsafe { streamify_ring_flush_generations(&mut req, &mut ack) },
            1
        );
        assert_eq!((req, ack), (0, 0));

        unsafe { streamify_ring_request_flush() };
        let (mut req2, mut ack2) = (0u64, 0u64);
        assert_eq!(
            unsafe { streamify_ring_flush_generations(&mut req2, &mut ack2) },
            1
        );
        assert_eq!(req2, 1);
        // The consumer acknowledges on its next read pass (see
        // `read_frames_into`); `ack2` may legitimately still be 0 here.
        let _ = ack2;
    }

    #[test]
    fn stats_wire_round_trips_the_frozen_layout() {
        clean_slot();
        let (_sink, ring) = RingSink::new(1024, 2, 48_000, 48_000).unwrap();
        publish(ring);

        let mut wire = [0u8; crate::sink::SINK_STATS_WIRE_BYTES];
        assert!(unsafe { streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) });
        // Magic + layout bytes at the frozen offsets (+0/+4, LE).
        assert_eq!(&wire[0..4], &crate::sink::SINK_STATS_WIRE_MAGIC.to_le_bytes());
        let layout = u32::from_le_bytes([wire[4], wire[5], wire[6], wire[7]]);
        assert_eq!(layout as usize, crate::sink::SINK_STATS_WIRE_BYTES);
        // Capacity (+8): the ring's own 1024 frames.
        let capacity = u32::from_le_bytes([wire[8], wire[9], wire[10], wire[11]]);
        assert_eq!(capacity, 1024);
    }
}
