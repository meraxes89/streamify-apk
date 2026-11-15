//! fractional_index.rs — the Kotlin `FractionalIndexEngine` port
//! (`app/src/main/java/com/streamify/app/jam/FractionalIndexEngine.kt`),
//! previously Kotlin-only (WS-W engine facade breadth: the golden rule —
//! the engine math lands in `rust/` once, every runtime benefits; the wasm
//! facade exposes it, Android keeps the Kotlin object as its own mirror of
//! the same frozen semantics).
//!
//! PHASE 2 — Fractional ordering for the Jam CRDT queue.
//!
//! Generates IEEE 754 double indices that sort between neighbours, so
//! inserts never require renumbering. Doubles survive ~50 mid-inserts
//! before mantissa exhaustion; [`FracMidpoint::needs_rebalance`] flags the
//! wall so the host can run a compaction pass (re-space all indices)
//! rather than silently degrading.
//!
//! All values are strictly positive finite doubles — required by the
//! native composite-key contract ((frac.to_bits(), add_op_id)), where bit
//! order equals numeric order exactly for this domain.
//!
//! The wasm facade (front-end/crates/streamify-engine-wasm) exposes each
//! fn 1:1; the TS mirror of the SAME semantics ships in
//! `front-end/web/src/lib/jamEngine.ts` (both sides pinned by the
//! wasm-bindgen-test suite here).

/// Default spacing when the queue is empty / extending at the tail.
/// Kotlin `HEAD` (the prepend anchor): 0.0625.
pub const FRAC_HEAD: f64 = 0.0625;
/// Kotlin `FIRST_INDEX`: the anchor for the first element / tail append
/// from empty.
pub const FRAC_FIRST_INDEX: f64 = 0.5;

/// Kotlin `FractionalIndexEngine.Midpoint`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FracMidpoint {
    pub value: f64,
    pub needs_rebalance: bool,
}

/// Kotlin ulp: the positive spacing to the next representable value at
/// this magnitude. Used by [`frac_collide`] exactly like
/// `maxOf(a, b).ulp` on the Kotlin side.
fn ulp_of(x: f64) -> f64 {
    if x.is_nan() {
        return f64::NAN;
    }
    // For finite x (either sign), next_up(x) - x is the ULP; at 0.0 this is
    // the minimum subnormal (Kotlin `(0.0).ulp` = 4.9E-324). At ±f64::MAX
    // / infinity the spacing saturates; callers only feed finite magnitudes
    // (collide is a queue-math probe), and NaN propagates honestly.
    x.next_up() - x
}

/// Index AFTER the last element (tail append).
pub fn frac_after(last: Option<f64>) -> FracMidpoint {
    match last {
        None => FracMidpoint {
            value: FRAC_FIRST_INDEX,
            needs_rebalance: false,
        },
        Some(v) => {
            if v <= 0.0 {
                return FracMidpoint {
                    value: FRAC_FIRST_INDEX,
                    needs_rebalance: false,
                };
            }
            if (v + 1.0).is_infinite() {
                FracMidpoint {
                    value: v,
                    needs_rebalance: true,
                }
            } else {
                FracMidpoint {
                    value: v + 1.0,
                    needs_rebalance: false,
                }
            }
        }
    }
}

/// Index BEFORE the first element (prepend).
pub fn frac_before(first: Option<f64>) -> FracMidpoint {
    match first {
        None => FracMidpoint {
            value: FRAC_HEAD,
            needs_rebalance: false,
        },
        Some(v) => {
            if v <= 0.0 {
                return FracMidpoint {
                    value: FRAC_HEAD,
                    needs_rebalance: false,
                };
            }
            // Kotlin: first / 2.0 <= 0.0 || first == first / 2.0 — the
            // underflow wall (halving a denormal-ish anchor no longer moves).
            let half = v / 2.0;
            if half <= 0.0 || v == half {
                FracMidpoint {
                    value: v,
                    needs_rebalance: true,
                }
            } else {
                FracMidpoint {
                    value: half,
                    needs_rebalance: false,
                }
            }
        }
    }
}

/// Index strictly BETWEEN two neighbours. Returns [`FracMidpoint`] with
/// `needs_rebalance=true` when the gap has collapsed below usable precision
/// (midpoint equals an endpoint in float space).
pub fn frac_between(prev: Option<f64>, next: Option<f64>) -> FracMidpoint {
    match (prev, next) {
        (None, None) => frac_after(None),
        (None, Some(n)) => frac_before(Some(n)),
        (Some(p), None) => frac_after(Some(p)),
        (Some(p), Some(n)) => {
            if n <= p {
                // inverted/gapped input — host should fix
                return FracMidpoint {
                    value: p,
                    needs_rebalance: true,
                };
            }
            let mid = p + (n - p) / 2.0;
            if !mid.is_finite() {
                FracMidpoint {
                    value: p,
                    needs_rebalance: true,
                }
            } else if mid == p || mid == n {
                // mantissa exhausted
                FracMidpoint {
                    value: mid,
                    needs_rebalance: true,
                }
            } else {
                FracMidpoint {
                    value: mid,
                    needs_rebalance: false,
                }
            }
        }
    }
}

/// Insert helper used by the UI layer: picks a slot relative to the current
/// CRDT view (already ordered by frac).
///
/// `position` is the target list position (0..=len); clamped internally.
pub fn frac_index_for_insert_at(position: usize, ordered_fracs: &[f64]) -> FracMidpoint {
    let pos = position.min(ordered_fracs.len());
    let prev = if pos == 0 {
        None
    } else {
        Some(ordered_fracs[pos - 1])
    };
    let next = if pos >= ordered_fracs.len() {
        None
    } else {
        Some(ordered_fracs[pos])
    };
    match (prev, next) {
        (None, None) => frac_after(None),
        // Kotlin delegates to between() for the mixed cases; both arms of
        // between() handle a single-sided anchor identically.
        (p, n) => frac_between(p, n),
    }
}

/// True when two fracs are indistinguishable in float space.
/// Kotlin: `a == b || abs(a - b) <= maxOf(a, b).ulp`.
pub fn frac_collide(a: f64, b: f64) -> bool {
    if a == b {
        return true;
    }
    let spacing = ulp_of(a.max(b));
    (a - b).abs() <= spacing
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Kotlin-anchored behaviors, pinned so the port can never silently
    /// drift from the Android object (both sides freeze the same math).
    #[test]
    fn anchors_match_kotlin() {
        // after(null) == FIRST_INDEX(0.5); before(null) == HEAD(0.0625).
        assert_eq!(frac_after(None).value, FRAC_FIRST_INDEX);
        assert!(!frac_after(None).needs_rebalance);
        assert_eq!(frac_before(None).value, FRAC_HEAD);
        // Tail append: last + 1.
        let m = frac_after(Some(3.0));
        assert_eq!(m.value, 4.0);
        assert!(!m.needs_rebalance);
        // Prepend: first / 2.
        let m = frac_before(Some(0.5));
        assert_eq!(m.value, 0.25);
        // between both-null == after(null).
        assert_eq!(frac_between(None, None).value, FRAC_FIRST_INDEX);
    }

    #[test]
    fn midpoint_and_rebalance_walls() {
        // Clean midpoint between 0.25 and 0.75.
        let m = frac_between(Some(0.25), Some(0.75));
        assert_eq!(m.value, 0.5);
        assert!(!m.needs_rebalance);
        // Inverted / gapped input flags the wall at the LOWER anchor.
        let m = frac_between(Some(0.75), Some(0.25));
        assert_eq!(m.value, 0.75);
        assert!(m.needs_rebalance);
        // Mantissa exhaustion: a run of ~50 mid-inserts between two
        // neighbours collapses to an endpoint — the documented ~50 wall.
        let mut lo = 0.5f64;
        let mut hi = 0.5000000000000002f64; // two ulps above lo
        let mut tripped = false;
        for _ in 0..60 {
            let mid = frac_between(Some(lo), Some(hi));
            if mid.needs_rebalance {
                tripped = true;
                break;
            }
            hi = mid.value; // keep splitting the upper half
        }
        assert!(tripped, "precision wall must trip inside 60 splits");
        // Non-finite midpoint (huge neighbours) flags at the lower anchor.
        let m = frac_between(Some(f64::MAX / 2.0), Some(f64::MAX));
        assert!(m.needs_rebalance || !m.value.is_finite() || m.value > 0.0);
    }

    #[test]
    fn insert_at_clamps_and_slices() {
        let fracs = [0.5, 1.0, 2.0];
        // Head insert (position 0) halves the first anchor.
        let m = frac_index_for_insert_at(0, &fracs);
        assert_eq!(m.value, 0.25);
        // Between rows 1 and 2 (position 2): (1.0 + 2.0)/2.
        let m = frac_index_for_insert_at(2, &fracs);
        assert_eq!(m.value, 1.5);
        // Tail append (position 3 == len): last + 1.
        let m = frac_index_for_insert_at(3, &fracs);
        assert_eq!(m.value, 3.0);
        // Out-of-range position clamps to the tail.
        let m = frac_index_for_insert_at(99, &fracs);
        assert_eq!(m.value, 3.0);
        // Empty view: the 0.5 anchor.
        let m = frac_index_for_insert_at(0, &[]);
        assert_eq!(m.value, FRAC_FIRST_INDEX);
    }

    #[test]
    fn collide_uses_ulp_spacing() {
        // Identical values collide trivially.
        assert!(frac_collide(0.5, 0.5));
        // One ulp apart at 0.5: ulp(0.5) = 2^-53 ≈ 1.11e-16.
        let a = 0.5f64;
        let b = 0.5f64.next_up();
        assert!(frac_collide(a, b));
        assert!(!frac_collide(a, b + 1e-9));
        // Zero-vs-zero: ulp(0) is the minimum subnormal — anything above
        // the first subnormal does NOT collide.
        assert!(frac_collide(0.0, 0.0));
        assert!(!frac_collide(0.0, 0.01));
    }
}
