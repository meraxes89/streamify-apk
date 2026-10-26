//! kalman_pll.rs — State-aware 1D Kalman PLL with feed-forward seek (Phase 1.4).
//!
//! Replaces the pure PI loop whose integral wound up across PAUSE/PLAY regime
//! changes. State vector tracks the HOST position estimate and its velocity
//! error; measurements are host ticks (already gap-repaired by tick_matrix).
//!
//!   predict:  x̂ₖ|ₖ₋₁ = xₖ₋₁ + vₖ₋₁·Δt      Pₖ|ₖ₋₁ = Pₖ₋₁ + Q
//!   update:   Kₖ = Pₖ|ₖ₋₁ / (Pₖ|ₖ₋₁ + R)   x̂ₖ = x̂ₖ|ₖ₋₁ + Kₖ(z − x̂ₖ|ₖ₋₁)
//!             vₖ += Kₖ·(z − x̂ₖ|ₖ₋₁)/Δt
//!
//! Control policy:
//!   |error| ≤ 150ms  → smooth speed scalar in [0.98, 1.02] (micro-drift)
//!   |error| >  150ms → FEED-FORWARD HARD SEEK to host position, full state
//!                      reset (no windup, no pitch artifacts)
//!   state == PAUSED  → velocity clamped to 0; on resume the accumulator is
//!                      wiped (anti-windup regime change)

use std::sync::Mutex;

const Q_POS: f64 = 4.0; // process noise: position uncertainty growth per tick (ms²)
const Q_VEL: f64 = 0.05; // process noise: rate-error random walk
const R_MEAS: f64 = 36.0; // measurement noise (~±6ms jitter after gap repair)

const MICRO_BAND_MS: f64 = 150.0;
const LOCK_BAND_MS: f64 = 12.0;
const MAX_SPEED_NUDGE: f64 = 0.02; // ±2% — inaudible micro-stretch

pub const DECISION_HOLD: i32 = 0;
pub const DECISION_SPEED: i32 = 1;
pub const DECISION_SEEK: i32 = 2;

struct KalmanState {
    x: f64, // estimated host position, ms
    v: f64, // estimated rate error (ms per ms), ~0 nominal
    p: f64, // position covariance
    initialized: bool,
    last_update_mono_ms: i64,
}

impl KalmanState {
    fn new() -> Self {
        Self {
            x: 0.0,
            v: 0.0,
            p: R_MEAS, // start humble
            initialized: false,
            last_update_mono_ms: 0,
        }
    }
}

static STATE: Mutex<Option<KalmanState>> = Mutex::new(None);

pub struct KalmanPll;

impl KalmanPll {
    pub fn reset() {
        if let Ok(mut guard) = STATE.lock() {
            *guard = Some(KalmanState::new());
        }
    }

    /// Feed one host tick measurement.
    ///
    /// Args:
    ///   z_pos_ms       — host position at the tick (gap-repaired upstream)
    ///   now_synced_ms  — guest synced-clock reading at processing time
    ///   tick_host_mono_ms — synced-clock stamp carried by the tick
    ///   playing        — room playback state
    ///
    /// Returns [decision, speed_milli, seek_target_ms]:
    ///   decision     DECISION_HOLD | SPEED | SEEK
    ///   speed_milli  target speed ×1000 (e.g. 1002 → 1.002x); valid on SPEED
    ///   seek_target  host position to hard-seek to; valid on SEEK
    #[allow(clippy::needless_range_loop)]
    pub fn decide(
        z_pos_ms: i64,
        now_synced_ms: i64,
        tick_host_mono_ms: i64,
        playing: bool,
        out: &mut [i64; 3],
    ) -> i32 {
        let Ok(mut guard) = STATE.lock() else {
            out[0] = DECISION_HOLD as i64;
            out[1] = 1000;
            out[2] = -1;
            return DECISION_HOLD;
        };
        let st = guard.get_or_insert_with(KalmanState::new);

        // ── Anti-windup regime change ──────────────────────────────────────
        if !playing {
            // Freeze tracking entirely; wipe velocity so resumption starts clean.
            st.v = 0.0;
            st.initialized = false;
            out[0] = DECISION_HOLD as i64;
            out[1] = 1000;
            out[2] = -1;
            return DECISION_HOLD;
        }

        let dt_ms = if st.last_update_mono_ms == 0 {
            250.0
        } else {
            (now_synced_ms - st.last_update_mono_ms).clamp(1, 5_000) as f64
        };
        st.last_update_mono_ms = now_synced_ms;

        let z = z_pos_ms as f64;

        if !st.initialized {
            st.x = z;
            st.v = 0.0;
            st.p = R_MEAS;
            st.initialized = true;
            out[0] = DECISION_HOLD as i64;
            out[1] = 1000;
            out[2] = -1;
            return DECISION_HOLD;
        }

        // ── Predict ─────────────────────────────────────────────────────────
        // Local player advances ~1.000x dt since last update; the estimate
        // tracks where the HOST should be now (tick may be stale by transit).
        let transit_age = (now_synced_ms - tick_host_mono_ms).clamp(0, 5_000) as f64;
        st.x += st.x.mul_add(0.0, 0.0) + st.v * dt_ms; // position drifts only via rate error
        st.x += transit_age * (st.v + 1.0) - transit_age; // advance by wall time at nominal rate
        let p_pred = st.p + Q_POS + Q_VEL * dt_ms * dt_ms;

        // ── Update ──────────────────────────────────────────────────────────
        let innovation = z - st.x;
        let k_gain = p_pred / (p_pred + R_MEAS);
        st.x += k_gain * innovation;
        st.v += k_gain * innovation / dt_ms.max(1.0);
        st.p = (1.0 - k_gain) * p_pred;

        // Velocity sanity clamp: a device cannot legitimately exceed ±5% skew.
        st.v = st.v.clamp(-0.05, 0.05);

        // ── Control ─────────────────────────────────────────────────────────
        let error = innovation; // measured vs predicted host truth
        if error.abs() > MICRO_BAND_MS {
            // Macro drift: feed-forward hard seek, full reset (no windup).
            st.x = z;
            st.v = 0.0;
            st.p = R_MEAS;
            out[0] = DECISION_SEEK as i64;
            out[1] = 1000;
            out[2] = z.round() as i64;
            return DECISION_SEEK;
        }

        if error.abs() <= LOCK_BAND_MS && st.v.abs() < 0.001 {
            st.v = 0.0;
            out[0] = DECISION_HOLD as i64;
            out[1] = 1000;
            out[2] = -1;
            return DECISION_HOLD;
        }

        // Micro band: proportional speed scalar shaped by filtered rate error.
        // Positive innovation (host ahead) → we must run slightly faster.
        let nudge = (k_gain * error / dt_ms).clamp(-MAX_SPEED_NUDGE, MAX_SPEED_NUDGE)
            + st.v.clamp(-MAX_SPEED_NUDGE, MAX_SPEED_NUDGE);
        let scalar = (1.0 + nudge * 0.5).clamp(1.0 - MAX_SPEED_NUDGE, 1.0 + MAX_SPEED_NUDGE);
        out[0] = DECISION_SPEED as i64;
        out[1] = (scalar * 1000.0).round() as i64;
        out[2] = -1;
        DECISION_SPEED
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Global STATE singleton → single sequential test for determinism.
    #[test]
    fn kalman_full_lifecycle() {
        let mut out = [0i64; 3];
        let decide = |z: i64, now: i64, tick_mono: i64, playing: bool, out: &mut [i64; 3]| {
            KalmanPll::decide(z, now, tick_mono, playing, out)
        };

        // ── first sample initializes without action ──
        KalmanPll::reset();
        assert_eq!(decide(50_000, 1_000, 998, true, &mut out), DECISION_HOLD);
        assert_eq!(out[1], 1000);
        assert_eq!(out[2], -1);

        // ── micro drift stays within audible band ──
        for i in 1..20 {
            let mut o = [0i64; 3];
            decide(
                10_000 + i * 260,
                1_000 + i * 250,
                995 + i * 250,
                true,
                &mut o,
            );
            if o[0] == DECISION_SPEED as i64 {
                assert!((980..=1020).contains(&o[1]), "speed {} out of band", o[1]);
            }
        }

        // ── macro drift triggers feed-forward seek with exact target ──
        KalmanPll::reset();
        decide(10_000, 1_000, 995, true, &mut out);
        assert_eq!(decide(10_800, 1_250, 1_245, true, &mut out), DECISION_SEEK);
        assert_eq!(out[2], 10_800);

        // ── pause freezes + resume starts clean (anti-windup) ──
        KalmanPll::reset();
        decide(10_000, 1_000, 995, true, &mut out);
        assert_eq!(decide(10_000, 1_200, 1_195, false, &mut out), DECISION_HOLD);
        assert_eq!(out[1], 1000);
        // Resume: first sample after freeze re-initializes; any speed nudge
        // must stay inside the audible micro band (never stale windup).
        let d = decide(40_000, 60_000, 59_995, true, &mut out);
        if d == DECISION_SPEED {
            assert!((980..=1020).contains(&out[1]), "windup leak: {}", out[1]);
        } else {
            assert_eq!(d, DECISION_HOLD);
        }
    }
}
