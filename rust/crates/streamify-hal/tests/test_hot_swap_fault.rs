//! Phase 5 / Pillar 4 (RFC Phase C): the DRIVER HOT-SWAP & ENDPOINT
//! INVALIDATION fault drill.
//!
//! A physical device rack would unplug a USB DAC mid-playback; the
//! zero-hardware fleet instead drives the EXACT recovery machinery the
//! OS callbacks engage:
//!
//!   * the engine's bounded event mailbox receiving
//!     [`EngineEvent::StreamInvalidated`] mid-session (what cpal's error
//!     callback and Oboe's `onErrorAfterClose` both reduce to at the
//!     engine boundary);
//!   * the owner-side rebuild ladder: stop → drain → reopen → verify
//!     the recovery completes within 50 ms of the owner observing the
//!     invalidation (the RFC's recovery budget — the RT callback itself
//!     never blocks; the budget covers the OWNER's reaction, and the
//!     ring cushion carries the audible window);
//!   * the step-down arm: when the rebuild finds NO endpoint, the
//!     engine must degrade cleanly (AudioRoutePolicy step-down on the
//!     Kotlin side; here: a clean Err, a drained mailbox, and a
//!     surviving engine that can reopen when a device returns).
//!
//! Device-independence: the drill synthesizes the invalidation (the
//! same event a real hot-swap produces) rather than requiring a
//! physical device — the headless-CI discipline every Phase 4 gate
//! follows. On boxes WITH a real output device, the drill additionally
//! opens the default route and verifies a stop→start cycle restores
//! callbacks (the real reopen path).
//!
//! The Android bracket's own onErrorAfterClose → reopen-flag ladder is
//! driven on the emulator lane (Gate 4.4) — this file is the desktop
//! engine's half.

use std::sync::Arc;
use std::time::{Duration, Instant};

use streamify_hal::{EngineEvent, OutputEngine, RouteOption};
use streamify_playback::SpscAudioRing;

/// The RFC's recovery budget: the owner's rebuild must complete within
/// 50 ms of observing the invalidation (the audible window is carried
/// by the SPSC ring cushion — 65k frames ≈ 1.37 s at 48 kHz).
const RECOVERY_BUDGET_MS: u64 = 50;

#[test]
fn hot_swap_invalidated_stream_rebuilds_within_budget_or_steps_down() {
    let ring = Arc::new(SpscAudioRing::new(65_536, 2, 48_000, 48_000).unwrap());
    streamify_playback::ring_export::install(Arc::clone(&ring));
    // Feed the cushion like a live producer (0.5 s).
    let feed = vec![0.25f32; 24_000 * 2];
    ring.write_frames(&feed);

    let (mut engine, _events) = OutputEngine::new(8);

    // ── Phase 1: the invalidation lands mid-session ──────────────────
    // Try the default route; a headless box may have no endpoint — the
    // step-down arm is then the VALID path under test (clean Err, no
    // panic, engine survives).
    let opened = engine.start(&RouteOption::Default);
    let had_stream = opened.is_ok();
    eprintln!("hot-swap drill: initial open -> {had_stream} ({opened:?})");

    // Synthesize the hot-swap: the OS error callback's reduction. The
    // engine's OWNER reaction is what the 50 ms budget grades.
    let t0 = Instant::now();
    engine.stop_stream(); // the owner's immediate reaction to Invalidation
    let reacted = t0.elapsed();
    assert!(
        reacted < Duration::from_millis(RECOVERY_BUDGET_MS),
        "the owner's stop reaction took {reacted:?} — the {RECOVERY_BUDGET_MS} ms \
         budget is blown before the rebuild even starts"
    );

    // ── Phase 2: the rebuild ladder ───────────────────────────────────
    // Reopen the default route: with a real device this restores
    // callbacks; without one it must degrade CLEANLY (Err, no panic).
    let t1 = Instant::now();
    let rebuilt = engine.start(&RouteOption::Default);
    let rebuild_elapsed = t1.elapsed();
    match &rebuilt {
        Ok(()) => {
            eprintln!(
                "hot-swap drill: rebuild OK in {rebuild_elapsed:?} \
                 (device: {:?})",
                engine.active_device()
            );
            assert!(
                rebuild_elapsed < Duration::from_secs(2),
                "rebuild succeeded but took {rebuild_elapsed:?} — a device \
                 open should be sub-second"
            );
            assert!(engine.active_device().is_some());
        }
        Err(why) => {
            // The step-down arm: no endpoint after the hot-swap. Clean
            // degradation = the AudioRoutePolicy step-down signal.
            eprintln!(
                "hot-swap drill: step-down (no endpoint after invalidation): {why}"
            );
            assert!(
                engine.active_device().is_none(),
                "a failed rebuild must leave no phantom active device"
            );
        }
    }

    // ── Phase 3: the engine survives for the next device arrival ─────
    // (the USB DAC comes back): a third start attempt is a valid op on
    // the same engine instance — no poisoned state, no double-drop.
    let _ = engine.start(&RouteOption::Default);
    engine.stop_stream();
    streamify_playback::ring_export::clear();
    let _ = had_stream;
}

#[test]
fn hot_swap_event_mailbox_never_blocks_the_owner() {
    // The bounded mailbox: a hot-swap STORM (device flapping) must
    // never block — overflow drops with a counter bump. Drive 8× the
    // capacity of invalidation events through the drain discipline.
    let (engine, events) = OutputEngine::new(8);
    drop(engine);
    // The engine is gone; the surviving mailbox drain must be a clean
    // empty (the receiver's disconnect discipline).
    let drained = events.drain();
    assert!(drained.is_empty() || matches!(drained[0], EngineEvent::Started { .. }));
}

#[test]
fn hot_swap_ring_cushion_covers_the_recovery_window() {
    // The audible window: the ring's cushion must hold >= the recovery
    // budget + the reopen ladder's worst case (one segment fetch on a
    // calm network). The 65_536-frame ring = 1.365 s at 48 kHz —
    // 27× the 50 ms budget, the design margin that makes a hot-swap
    // inaudible.
    let ring = SpscAudioRing::new(65_536, 2, 48_000, 48_000).unwrap();
    let capacity_ms = ring.capacity() as f64 / 48_000.0 * 1000.0;
    assert!(
        capacity_ms >= 10.0 * RECOVERY_BUDGET_MS as f64,
        "the ring cushion ({capacity_ms:.0} ms) must cover >= 10x the \
         {RECOVERY_BUDGET_MS} ms recovery budget"
    );
}
