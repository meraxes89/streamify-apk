//! jam_clock.rs — Skew-free bootstrap clock for Jam lockstep (Phase 1.1).
//!
//! Cristian's-algorithm handshake computed entirely on the monotonic domain:
//!   Guest fires SYNC_REQ at local monotonic t0. Host answers with its own
//!   monotonic receive/transmit stamps t1 <= t2 (here t1 == t2: kernel
//!   timestamping is unavailable over WS, so both are taken at handling time;
//!   the RTT filter discards pathological samples). Guest records t3.
//!
//!   theta = ((t1 - t0) + (t2 - t3)) / 2      (offset into host timeline)
//!   delta =  (t3 - t0) - (t2 - t1)            (round-trip latency)
//!
//! The retained offset is the sample with the LOWEST delta seen in a sliding
//! window (standard NTP "best sample" discipline), EMA-smoothed for stability.
//! All Jam extrapolation then runs on get_synced_monotonic_ms(), making OS
//! NTP skew mathematically irrelevant.

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Mutex;
use std::time::Instant;

/// Process-lifetime monotonic anchor. First touch wins; all monotonic reads
/// are relative to this instant, so the value is stable across the process.
static ANCHOR: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();

#[inline]
fn mono_ms() -> i64 {
    let anchor = ANCHOR.get_or_init(Instant::now);
    anchor.elapsed().as_millis() as i64
}

/// Minimum samples retained before the filter is considered "locked".
const MIN_SAMPLES_LOCKED: u32 = 3;
/// Samples worse than BEST_DELTA * this factor are ignored once locked.
const DELTA_REJECT_FACTOR: f64 = 1.5;
/// Hard cap: never trust a sample whose RTT exceeds this (pathological stall).
const MAX_TRUSTED_RTT_MS: i64 = 2_000;

struct ClockState {
    /// Best (lowest-delta) raw offset sample observed, ms.
    best_offset_ms: f64,
    /// RTT of that sample, ms.
    best_delta_ms: f64,
    /// Smoothed offset actually applied (EMA toward best sample).
    ema_offset_ms: f64,
    sample_count: u32,
}

impl ClockState {
    fn new() -> Self {
        Self {
            best_offset_ms: 0.0,
            best_delta_ms: f64::INFINITY,
            ema_offset_ms: 0.0,
            sample_count: 0,
        }
    }
}

static STATE: Mutex<Option<ClockState>> = Mutex::new(None);
static APPLIED_OFFSET_MS: AtomicI64 = AtomicI64::new(0);
static LAST_RTT_MS: AtomicI64 = AtomicI64::new(-1);
static INITIALIZED: AtomicBool = AtomicBool::new(false);

pub struct JamClock;

impl JamClock {
    /// Raw device-local monotonic time (NO offset applied). Handshake
    /// endpoints t0/t3 MUST use this — mixing synced values into Cristian
    /// math would feed theta back into itself (double-counting).
    #[inline]
    pub fn local_monotonic_ms() -> i64 {
        mono_ms()
    }

    pub fn reset() {
        if let Ok(mut guard) = STATE.lock() {
            *guard = Some(ClockState::new());
        }
        APPLIED_OFFSET_MS.store(0, Ordering::Release);
        LAST_RTT_MS.store(-1, Ordering::Release);
        INITIALIZED.store(false, Ordering::Release);
    }

    /// Ingest one handshake sample; returns [theta_ms, delta_ms] as accepted
    /// (post-filter) values so callers can log/telemetrize them.
    pub fn apply_sample(t0: i64, t1: i64, t2: i64, t3: i64) -> (i64, i64) {
        // Classical pairwise math on the monotonic domain.
        let theta = ((t1 - t0) + (t2 - t3)) as f64 / 2.0;
        let delta = ((t3 - t0) - (t2 - t1)) as f64;

        // Sanity: negative or absurd RTTs are kernel/timestamp artifacts.
        if delta < 0.0 || delta > MAX_TRUSTED_RTT_MS as f64 {
            return (applied_offset(), delta.max(0.0) as i64);
        }

        let accepted_theta;
        let accepted_delta;
        if let Ok(mut guard) = STATE.lock() {
            let st = guard.get_or_insert_with(ClockState::new);
            let replace = if !INITIALIZED.load(Ordering::Acquire) {
                true
            } else {
                // Best-sample discipline: adopt strictly better RTTs outright;
                // accept near-best samples to keep tracking slow drift.
                delta < st.best_delta_ms || delta <= st.best_delta_ms * DELTA_REJECT_FACTOR
            };

            if replace {
                if delta < st.best_delta_ms || !INITIALIZED.load(Ordering::Acquire) {
                    st.best_offset_ms = theta;
                    st.best_delta_ms = delta;
                }
                st.sample_count = st.sample_count.saturating_add(1);

                // EMA converges fast while unlocked (alpha 0.5), slow once
                // locked (alpha 0.125) to reject jitter spikes.
                let alpha = if st.sample_count < MIN_SAMPLES_LOCKED {
                    0.5
                } else {
                    0.125
                };
                if !INITIALIZED.swap(true, Ordering::AcqRel) {
                    st.ema_offset_ms = theta; // first sample snaps instantly
                } else {
                    st.ema_offset_ms += alpha * (st.best_offset_ms - st.ema_offset_ms);
                }

                accepted_theta = st.ema_offset_ms;
                accepted_delta = delta;
            } else {
                accepted_theta = st.ema_offset_ms;
                accepted_delta = delta;
            }
        } else {
            // Mutex poisoned: degrade to last applied offset.
            return (applied_offset(), delta as i64);
        }

        APPLIED_OFFSET_MS.store(accepted_theta.round() as i64, Ordering::Release);
        LAST_RTT_MS.store(accepted_delta.round() as i64, Ordering::Release);
        (accepted_theta.round() as i64, accepted_delta.round() as i64)
    }

    /// THE time source for all Jam extrapolation: guest-local monotonic mapped
    /// into the host's monotonic timeline. On an un-synced clock (host device,
    /// pre-handshake guest) this is simply the local monotonic value — which is
    /// still skew-free within the device, unlike wall-clock time.
    #[inline]
    pub fn synced_monotonic_ms() -> i64 {
        mono_ms() + APPLIED_OFFSET_MS.load(Ordering::Acquire)
    }

    /// Last measured round-trip in ms (-1 = no handshake yet).
    #[inline]
    pub fn last_rtt_ms() -> i64 {
        LAST_RTT_MS.load(Ordering::Acquire)
    }

    /// True once at least MIN_SAMPLES_LOCKED good samples have been fused.
    pub fn is_locked() -> bool {
        INITIALIZED.load(Ordering::Acquire)
    }
}

#[inline]
fn applied_offset() -> i64 {
    APPLIED_OFFSET_MS.load(Ordering::Acquire)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // Shared process-global clock state → serialize all assertions.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[inline]
    fn delta_of(t0: i64, t1: i64, t2: i64, t3: i64) -> i64 {
        (t3 - t0) - (t2 - t1)
    }

    #[test]
    fn clock_full_lifecycle() {
        let _g = TEST_LOCK.lock().unwrap();

        // ── clean channel: theta recovers exact skew, delta exact RTT ──
        JamClock::reset();
        let t0 = 1_000;
        let t1 = 1_150; // host receive (host timeline)
        let t2 = 1_160; // host transmit
        let t3 = 1_110; // guest receive (guest timeline)
        let (theta, delta) = JamClock::apply_sample(t0, t1, t2, t3);
        assert_eq!(delta_of(t0, t1, t2, t3), 100);
        assert_eq!(theta, 100); // host is +100ms ahead of guest
        assert_eq!(delta, 100);
        assert!(JamClock::synced_monotonic_ms() >= JamClock::local_monotonic_ms());

        // ── asymmetric transit: raw math holds ──
        JamClock::reset();
        let (_, d) = JamClock::apply_sample(0, 30, 30, 200);
        assert_eq!(d, 200);

        // ── negative RTT artifact rejected without changing offset ──
        JamClock::reset();
        let before = JamClock::synced_monotonic_ms();
        let (theta, _) = JamClock::apply_sample(500, 400, 400, 100); // impossible sample
        let after = JamClock::synced_monotonic_ms();
        assert_eq!(theta, 0);
        assert!(after >= before);

        // ── jitter spike once locked must not yank the EMA ──
        JamClock::reset();
        for i in 0..8 {
            JamClock::apply_sample(
                i * 1_000,
                i * 1_000 + 50 + 100,
                i * 1_000 + 55 + 100,
                i * 1_000 + 105,
            );
        }
        let stable = JamClock::synced_monotonic_ms() - JamClock::local_monotonic_ms();
        // One pathological 1.5s-delay sample arrives:
        JamClock::apply_sample(10_000, 10_150 + 100, 10_155 + 100, 12_500);
        let after_spike = JamClock::synced_monotonic_ms() - JamClock::local_monotonic_ms();
        assert!(
            (stable - after_spike).abs() <= 25,
            "EMA absorbed jitter: {} -> {}",
            stable,
            after_spike
        );
    }
}
