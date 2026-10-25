//! test_gesture_physics.rs — Phase 5 kinetic gesture physics suite
//! (directive §2.4).
//!
//! Mathematical verification of the analytical 2nd-order spring solver
//! across all damping ratios (closed form vs an independent RK4
//! integration of m·x'' + c·x' + k·x = 0), trajectory recurrence
//! equivalence and long-horizon stability, extreme fling velocities
//! (> 15,000 px/s) with edge-boundary rubberbanding, over-scroll
//! snap-back, and input validation / NaN-propagation safety.

use streamify_core_rs::gesture_physics::{
    fling_landing, fling_position, fling_velocity, rubberband_offset, snap_to_bounds,
    spring_displacement, spring_position_at, spring_trajectory, spring_trajectory_from,
    spring_velocity_at, PhysicsError, SpringSpec, FRAME_INTERVAL_120HZ, RUBBERBAND_RESISTANCE,
};

// ─────────────────────── convergence across damping ratios

#[test]
fn spring_converges_to_equilibrium_all_damping_ratios() {
    // From rest, 100 px away, every regime settles on the target:
    // slowest is heavy overdamping (pole ≈ −ωₙ/(2ζ)).
    for &zeta in &[
        0.2f32, 0.55, 0.8, 0.95, 0.999, 1.0, 1.001, 1.05, 1.5, 2.5, 5.0,
    ] {
        let spec = SpringSpec::new(200.0, zeta, 1.0).expect("valid spec");
        let x = spring_position_at(&spec, 0.0, 100.0, 0.0, 8.0);
        assert!(
            (x - 100.0).abs() < 0.05,
            "zeta={zeta}: position {x} did not converge to 100"
        );
        let v = spring_velocity_at(&spec, 0.0, 100.0, 0.0, 8.0);
        assert!(v.abs() < 0.05, "zeta={zeta}: velocity {v} did not settle");
    }
}

#[test]
fn spring_closed_form_matches_rk4_integration() {
    // Independent numerical check: RK4 on (u, v) with the physical
    // damping coefficient c = 2ζ√(km), step 1 ms, compared against the
    // closed form at t = 0.5 s and t = 1.0 s.
    fn rk4(spec: &SpringSpec, u0: f32, v0: f32, t_end: f32) -> (f32, f32) {
        let h = 1.0e-3;
        let c = 2.0 * spec.damping_ratio * (spec.stiffness * spec.mass).sqrt();
        let (mut u, mut v) = (u0, v0);
        let steps = (t_end / h).round() as usize;
        for _ in 0..steps {
            let k1u = v;
            let k1v = -(c * v + spec.stiffness * u) / spec.mass;
            let k2u = v + 0.5 * h * k1v;
            let k2v = -(c * (v + 0.5 * h * k1v) + spec.stiffness * (u + 0.5 * h * k1u)) / spec.mass;
            let k3u = v + 0.5 * h * k2v;
            let k3v = -(c * (v + 0.5 * h * k2v) + spec.stiffness * (u + 0.5 * h * k2u)) / spec.mass;
            let k4u = v + h * k3v;
            let k4v = -(c * (v + h * k3v) + spec.stiffness * (u + h * k3u)) / spec.mass;
            u += h / 6.0 * (k1u + 2.0 * k2u + 2.0 * k3u + k4u);
            v += h / 6.0 * (k1v + 2.0 * k2v + 2.0 * k3v + k4v);
        }
        (u, v)
    }

    for &zeta in &[0.5f32, 1.0, 1.5, 2.5] {
        let spec = SpringSpec::new(170.0, zeta, 1.0).expect("valid spec");
        for &t in &[0.5f32, 1.0] {
            let (u, v) = spring_displacement(&spec, -512.0, 850.0, t);
            let (ru, rv) = rk4(&spec, -512.0, 850.0, t);
            // Tolerance dominated by f32 rounding of both methods.
            assert!(
                (u - ru).abs() < 0.05,
                "zeta={zeta} t={t}: closed-form u={u} vs RK4 {ru}"
            );
            assert!(
                (v - rv).abs() < 1.0,
                "zeta={zeta} t={t}: closed-form v={v} vs RK4 {rv}"
            );
        }
    }
}

#[test]
fn spring_initial_conditions_hold_exactly() {
    for &zeta in &[0.3f32, 0.999, 1.0, 1.001, 2.5] {
        let spec = SpringSpec::new(400.0, zeta, 1.0).expect("valid spec");
        let (u, v) = spring_displacement(&spec, -77.5, 1234.5, 0.0);
        assert!((u + 77.5).abs() < 1e-3, "zeta={zeta}: u(0)={u}");
        assert!((v - 1234.5).abs() < 1e-2, "zeta={zeta}: v(0)={v}");
    }
}

#[test]
fn underdamped_overshoots_but_settles() {
    let spec = SpringSpec::new(170.0, 0.3, 1.0).expect("valid spec");
    let mut max_pos = f32::MIN;
    for k in 0..600 {
        let x = spring_position_at(&spec, 0.0, 100.0, 0.0, k as f32 / 120.0);
        max_pos = max_pos.max(x);
    }
    assert!(
        max_pos > 115.0,
        "underdamped spring must overshoot target, max={max_pos}"
    );
    let settled = spring_position_at(&spec, 0.0, 100.0, 0.0, 5.0);
    assert!((settled - 100.0).abs() < 0.01);
}

#[test]
fn critical_and_overdamped_never_overshoot_from_rest() {
    for &zeta in &[1.0f32, 1.001, 1.05, 2.0] {
        let spec = SpringSpec::new(170.0, zeta, 1.0).expect("valid spec");
        for k in 0..1200 {
            let x = spring_position_at(&spec, 0.0, 100.0, 0.0, k as f32 / 120.0);
            assert!(
                x <= 100.0 + 1e-2,
                "zeta={zeta} overshot to {x} at frame {k}"
            );
        }
    }
}

// ─────────────────────── trajectory fills

#[test]
fn trajectory_frame_zero_is_current_position() {
    let spec = SpringSpec::new(300.0, 0.8, 1.0).expect("valid spec");
    let mut out = [0f32; 120];
    spring_trajectory(&spec, 41.5, 512.0, -320.0, FRAME_INTERVAL_120HZ, &mut out);
    assert!((out[0] - 41.5).abs() < 1e-4);
    assert_eq!(out.len(), 120);
}

#[test]
fn trajectory_recurrence_matches_closed_form_all_regimes() {
    // The rotation+decay recurrence is the analytical solution restated:
    // every frame agrees with the direct O(1) evaluation to f32 rounding.
    for &zeta in &[0.35f32, 0.95, 1.0, 1.001, 1.35] {
        let spec = SpringSpec::new(170.0, zeta, 1.0).expect("valid spec");
        let mut out = [0f32; 240];
        spring_trajectory(&spec, 0.0, 512.0, 850.0, FRAME_INTERVAL_120HZ, &mut out);
        for (k, &rec) in out.iter().enumerate() {
            let direct = spring_position_at(&spec, 0.0, 512.0, 850.0, k as f32 / 120.0);
            assert!(
                (rec - direct).abs() < 0.05,
                "zeta={zeta} frame {k}: recurrence {rec} vs closed form {direct}"
            );
        }
    }
}

#[test]
fn trajectory_chunked_continuation_equals_whole_run() {
    // The JNI layer streams long trajectories in chunks; a chunk seam
    // must be invisible.
    let spec = SpringSpec::new(220.0, 0.65, 1.0).expect("valid spec");
    let mut whole = [0f32; 977]; // odd length on purpose
    spring_trajectory(
        &spec,
        -80.0,
        320.0,
        1400.0,
        FRAME_INTERVAL_120HZ,
        &mut whole,
    );
    let mut chunked = [0f32; 977];
    let seams = [0usize, 1, 120, 500, 976];
    for (i, &seam) in seams.iter().enumerate() {
        let next = seams.get(i + 1).copied().unwrap_or(977);
        spring_trajectory_from(
            &spec,
            -80.0,
            320.0,
            1400.0,
            FRAME_INTERVAL_120HZ,
            seam,
            &mut chunked[seam..next],
        );
    }
    for (k, (&w, &c)) in whole.iter().zip(chunked.iter()).enumerate() {
        assert!((w - c).abs() < 0.05, "frame {k}: whole={w} chunked={c}");
    }
}

#[test]
fn trajectory_long_horizon_stays_stable() {
    // 10,000 frames ≈ 83 s at 120 Hz: recurrence drift must stay
    // bounded and the spring must sit on the target.
    let spec = SpringSpec::new(170.0, 0.8, 1.0).expect("valid spec");
    let mut out = vec![0f32; 10_000];
    spring_trajectory(&spec, 0.0, 4096.0, 0.0, FRAME_INTERVAL_120HZ, &mut out);
    for (k, &x) in out.iter().enumerate() {
        assert!(x.is_finite(), "frame {k} went non-finite");
    }
    let tail: f32 = out[9_000..].iter().sum::<f32>() / 1_000.0;
    assert!((tail - 4096.0).abs() < 1.0, "tail mean {tail}");
}

// ─────────────────────── fling projection & rubberbanding

#[test]
fn fling_landing_exact_within_bounds() {
    // Raw projection x0 + v0/μ, returned verbatim.
    let l = fling_landing(0.0, 1000.0, -10_000.0, 10_000.0, 2.0).expect("valid");
    assert!((l - 500.0).abs() < 1e-3);
    let l = fling_landing(3_000.0, -1_200.0, -10_000.0, 10_000.0, 4.0).expect("valid");
    assert!((l - 2_700.0).abs() < 1e-3);
    // The friction curve actually converges to the landing.
    let far = fling_position(0.0, 1000.0, 2.0, 10.0);
    assert!((far - 500.0).abs() < 1e-3);
    assert!(fling_velocity(1000.0, 2.0, 10.0).abs() < 1e-3);
}

#[test]
fn fling_extreme_velocity_rubberbanded_and_bounded() {
    // > 15,000 px/s flings land past the bound, damped by the
    // rubberband, and never render more than one viewport (10,000 px)
    // beyond it — the f(x) = bound·(1 − 1/(d·x/bound + 1)) asymptote.
    const MIN: f32 = 0.0;
    const MAX: f32 = 10_000.0;
    let extreme = fling_landing(0.0, 15_000.0, MIN, MAX, 0.5).expect("valid");
    assert!(extreme > MAX, "extreme fling must overshoot, got {extreme}");
    assert!(
        extreme <= MAX + (MAX - MIN),
        "overshoot exceeds one viewport: {extreme}"
    );
    // Monotonically more velocity → farther landing (rubberband is
    // monotone in the raw overshoot).
    let mut prev = fling_landing(0.0, 10_000.0, MIN, MAX, 0.5).expect("valid");
    for &v in &[12_000f32, 15_000.0, 20_000.0, 50_000.0, 200_000.0] {
        let l = fling_landing(0.0, v, MIN, MAX, 0.5).expect("valid");
        assert!(l > prev, "landing not monotone at v={v}: {l} <= {prev}");
        prev = l;
    }
    // Asymptote: as velocity → ∞ the landing approaches bound+span but
    // never reaches it.
    let huge = fling_landing(0.0, 1.0e9, MIN, MAX, 0.5).expect("valid");
    assert!(huge < MAX + (MAX - MIN));
    // Lower-bound fling mirrors the upper-bound behavior.
    let low = fling_landing(9_000.0, -30_000.0, MIN, MAX, 0.5).expect("valid");
    assert!(low < MIN && low > MIN - (MAX - MIN));
}

#[test]
fn boundary_overscroll_snaps_back_via_spring() {
    // The over-scroll sequence: rubberbanded extent → snapped target →
    // spring settle. The snap is the clamp; the settle is a spring
    // from the extent onto the bound.
    const MIN: f32 = 0.0;
    const MAX: f32 = 10_000.0;
    let extent = fling_landing(9_000.0, 15_000.0, MIN, MAX, 2.0).expect("valid");
    assert!(extent > MAX, "fling must overshoot, got {extent}");
    let target = snap_to_bounds(extent, MIN, MAX);
    assert_eq!(target, MAX);
    let spec = SpringSpec::new(340.0, 1.0, 1.0).expect("valid");
    let settled = spring_position_at(&spec, extent, target, 0.0, 1.0);
    assert!(
        (settled - MAX).abs() < 0.5,
        "snap-back spring did not settle: {settled}"
    );
    // Degenerate bounds must not panic (total clamp).
    assert!(snap_to_bounds(5.0, 10.0, 0.0).is_finite());
}

#[test]
fn rubberband_offset_properties() {
    assert!(rubberband_offset(0.0, 1000.0, RUBBERBAND_RESISTANCE).abs() < 1e-6);
    // Monotone, sub-linear, asymptotically bounded by the viewport.
    let mut prev = 0.0f32;
    for k in 1..=20 {
        let x = k as f32 * 100.0;
        let f = rubberband_offset(x, 1000.0, RUBBERBAND_RESISTANCE);
        assert!(f > prev, "not monotone at x={x}");
        assert!(f < 1000.0);
        prev = f;
    }
    // Directive formula spot-check: f(x) = b·(1 − 1/(d·x/b + 1)).
    let x = 500.0;
    let expected = 1000.0 * (1.0 - 1.0 / (RUBBERBAND_RESISTANCE * x / 1000.0 + 1.0));
    let got = rubberband_offset(x, 1000.0, RUBBERBAND_RESISTANCE);
    assert!((got - expected).abs() < 1e-4);
}

// ─────────────────────── validation & safety

#[test]
fn physics_input_validation_rejects_degenerate_values() {
    assert_eq!(
        SpringSpec::new(0.0, 0.8, 1.0),
        Err(PhysicsError::InvalidStiffness)
    );
    assert_eq!(
        SpringSpec::new(-100.0, 0.8, 1.0),
        Err(PhysicsError::InvalidStiffness)
    );
    assert_eq!(
        SpringSpec::new(100.0, -0.1, 1.0),
        Err(PhysicsError::InvalidDampingRatio)
    );
    assert_eq!(
        SpringSpec::new(100.0, 0.8, 0.0),
        Err(PhysicsError::InvalidMass)
    );
    assert_eq!(
        SpringSpec::new(f32::NAN, 0.8, 1.0),
        Err(PhysicsError::NonFiniteInput)
    );
    assert_eq!(
        SpringSpec::new(100.0, f32::INFINITY, 1.0),
        Err(PhysicsError::NonFiniteInput)
    );
    assert!(SpringSpec::new(100.0, 0.8, 1.0).is_ok());

    // Extreme-but-FINITE params whose derived quantities overflow f32
    // (ζ² = +Inf, ωₙ² = k/m = +Inf) must be rejected at the door —
    // the closed forms would silently degrade to NaN via Inf·0 terms.
    // ζ = 1.84e19: ζ² ≈ 3.4e38 overflows f32::MAX ≈ 3.4e38 boundary.
    assert_eq!(
        SpringSpec::new(100.0, 1.9e19, 1.0),
        Err(PhysicsError::DegenerateParameterRange)
    );
    // k/m overflows: stiffness = f32::MAX, mass = 1e-30.
    assert_eq!(
        SpringSpec::new(f32::MAX, 0.8, 1e-30),
        Err(PhysicsError::DegenerateParameterRange)
    );
    // Boundary sanity: values just inside the finite-derived range pass.
    assert!(SpringSpec::new(100.0, 1.0e19, 1.0).is_ok());

    assert_eq!(
        fling_landing(0.0, 100.0, 0.0, 1.0, 0.0).unwrap_err(),
        PhysicsError::InvalidFriction
    );
    assert_eq!(
        fling_landing(0.0, 100.0, 5.0, 5.0, 1.0).unwrap_err(),
        PhysicsError::InvalidBounds
    );
    assert_eq!(
        fling_landing(0.0, 100.0, 5.0, 1.0, 1.0).unwrap_err(),
        PhysicsError::InvalidBounds
    );
    assert_eq!(
        fling_landing(f32::NAN, 100.0, 0.0, 1.0, 1.0).unwrap_err(),
        PhysicsError::NonFiniteInput
    );
    assert_eq!(
        fling_landing(0.0, f32::INFINITY, 0.0, 1.0, 1.0).unwrap_err(),
        PhysicsError::NonFiniteInput
    );
}

#[test]
fn nan_inputs_propagate_without_panicking() {
    // Pure math must never panic — NaN in, NaN out.
    let spec = SpringSpec::new(170.0, 0.8, 1.0).expect("valid");
    let (u, v) = spring_displacement(&spec, f32::NAN, 0.0, 1.0);
    assert!(u.is_nan() && v.is_nan());
    let x = spring_position_at(&spec, f32::NAN, 100.0, 0.0, 1.0);
    assert!(x.is_nan());
    let mut out = [0f32; 8];
    spring_trajectory(&spec, f32::NAN, 100.0, 0.0, 0.1, &mut out);
    assert!(out.iter().all(|s| s.is_nan()));
}
