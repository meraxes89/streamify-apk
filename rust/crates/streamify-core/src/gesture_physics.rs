//! gesture_physics.rs — kinetic gesture physics: analytical 2nd-order
//! spring solver and fling trajectory projection with edge-boundary
//! rubberbanding (feat/phase5-rust-gesture-physics-waveform, directive §2.1).
//!
//! # Analytical spring solver
//!
//! Every gesture animation follows the damped harmonic oscillator
//!
//! ```text
//! m·x'' + c·x' + k·x = 0        with  c = 2·ζ·√(k·m)
//! ```
//!
//! measured as the displacement from equilibrium `u = x − x_target`,
//! for which the closed-form solutions hold exactly:
//!
//! * underdamped (ζ < 1):
//!   `u(t) = e^(−ζωₙt)·(u₀·cos(ω_d·t) + ((v₀ + ζωₙu₀)/ω_d)·sin(ω_d·t))`
//!   with damped frequency `ω_d = ωₙ·√(1 − ζ²)`;
//! * critically damped (ζ = 1): `u(t) = (u₀ + (v₀ + ωₙu₀)·t)·e^(−ωₙt)`;
//! * overdamped (ζ > 1): `u(t) = A·e^(r₁t) + B·e^(r₂t)` with
//!   `r₁,₂ = −ωₙ·(ζ ∓ √(ζ²−1))`.
//!
//! [`spring_position_at`] / [`spring_velocity_at`] evaluate the exact
//! solution at any future timestamp in O(1). [`spring_trajectory`]
//! fills a caller-owned sample buffer with one position per animation
//! frame using recurrence stepping — the same analytical solution
//! restated as a rotation + decay step, so there is no step-size error,
//! only f32 rounding (the unit suite asserts recurrence-vs-closed-form
//! agreement to 5e-2 absolute over 240 frames and finiteness + bounded
//! tail over 10⁴ frames). Three transcendentals
//! seed the recurrence instead of three per frame, keeping a full
//! 120-frame trajectory far below the 5 µs budget. The whole hot path
//! is allocation-free: positions are written straight into the
//! caller's buffer.
//!
//! # Fling projection & edge rubberbanding
//!
//! Flings decelerate under exponential friction `v(t) = v₀·e^(−μ·t)`,
//! so the raw landing projection is `x₀ + v₀/μ`. Landings beyond a
//! scroll bound pass through the edge rubberband
//!
//! ```text
//! f(x) = bound·(1 − 1/(d·x/bound + 1))
//! ```
//!
//! with resistance `d` = [`RUBBERBAND_RESISTANCE`]: the projected
//! extent asymptotically approaches at most one viewport past the edge
//! no matter how extreme the fling velocity, and the settle-back to
//! the snapped bound is itself a spring ([`snap_to_bounds`] +
//! [`SpringSpec`]).
//!
//! Inputs are validated at construction ([`SpringSpec::new`],
//! [`fling_landing`]). The pure math routines never panic: NaN inputs
//! propagate to NaN outputs, they never abort.

/// Edge rubberband resistance coefficient `d` in
/// `f(x) = bound·(1 − 1/(d·x/bound + 1))` — the classic 0.55 value.
pub const RUBBERBAND_RESISTANCE: f32 = 0.55;

/// Frame interval of a 120 Hz display — the sampling cadence the JNI
/// trajectory solver (`nativeSolveSpringTrajectory`) assumes.
pub const FRAME_INTERVAL_120HZ: f32 = 1.0 / 120.0;

/// Damping-ratio band around ζ = 1 routed to the critically-damped
/// closed form. The exact solution is analytic in ζ around 1, but the
/// overdamped branch loses ~4 f32 mantissa digits to catastrophic
/// cancellation (`r₁ − r₂ → 0`) as ζ → 1, while the critically-damped
/// form stays well-conditioned — inside this band the routing error is
/// far below perception (sub-milli-pixel at the band edge).
const CRITICAL_EPSILON: f32 = 1.0e-4;

/// Validation failure for physics inputs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhysicsError {
    /// A position / velocity / stiffness / ... input was NaN or ±Inf.
    NonFiniteInput,
    /// Stiffness must be > 0 (k = 0 is a dead spring).
    InvalidStiffness,
    /// Damping ratio must be ≥ 0.
    InvalidDampingRatio,
    /// Mass must be > 0.
    InvalidMass,
    /// Friction coefficient must be > 0.
    InvalidFriction,
    /// Bounds must satisfy min < max.
    InvalidBounds,
    /// Finite params whose derived quantities (ωₙ² = k/m, ζ²) overflow
    /// f32 — the closed forms would degrade to NaN via Inf·0.
    DegenerateParameterRange,
}

impl core::fmt::Display for PhysicsError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        let msg = match self {
            PhysicsError::NonFiniteInput => "non-finite (NaN/Inf) physics input",
            PhysicsError::InvalidStiffness => "stiffness must be > 0",
            PhysicsError::InvalidDampingRatio => "damping ratio must be >= 0",
            PhysicsError::InvalidMass => "mass must be > 0",
            PhysicsError::InvalidFriction => "friction coefficient must be > 0",
            PhysicsError::InvalidBounds => "bounds must satisfy min < max",
            PhysicsError::DegenerateParameterRange => {
                "parameter range too extreme: k/m or ζ² overflows f32"
            }
        };
        f.write_str(msg)
    }
}

impl std::error::Error for PhysicsError {}

/// Validated spring parameters `(k, ζ, m)` for one gesture animation.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SpringSpec {
    /// Stiffness `k > 0` — acceleration per px of displacement.
    pub stiffness: f32,
    /// Damping ratio `ζ ≥ 0`: `< 1` underdamped (bouncy), `= 1`
    /// critically damped (fastest settle without overshoot), `> 1`
    /// overdamped (sluggish).
    pub damping_ratio: f32,
    /// Mass `m > 0` — inertia; heavier means slower for the same k.
    pub mass: f32,
}

impl SpringSpec {
    /// Validate and construct. The JNI layer assumes `m = 1`.
    pub fn new(stiffness: f32, damping_ratio: f32, mass: f32) -> Result<Self, PhysicsError> {
        if !stiffness.is_finite() || !damping_ratio.is_finite() || !mass.is_finite() {
            return Err(PhysicsError::NonFiniteInput);
        }
        if stiffness <= 0.0 {
            return Err(PhysicsError::InvalidStiffness);
        }
        if damping_ratio < 0.0 {
            return Err(PhysicsError::InvalidDampingRatio);
        }
        if mass <= 0.0 {
            return Err(PhysicsError::InvalidMass);
        }
        // Derived quantities must stay finite. Extreme-but-finite params
        // (ζ ≳ 1.84e19 squares to +Inf; k/m > f32::MAX overflows ωₙ²)
        // would degrade every closed form to NaN through Inf·0 terms —
        // violating the "NaN in → NaN out" contract from the STATE side.
        // Params are UI-chosen constants; reject the degenerate range
        // instead of letting Inf leak in through the back door.
        if !(stiffness / mass).is_finite() || !(damping_ratio * damping_ratio).is_finite() {
            return Err(PhysicsError::DegenerateParameterRange);
        }
        Ok(Self {
            stiffness,
            damping_ratio,
            mass,
        })
    }

    /// Undamped natural frequency `ωₙ = √(k/m)` (rad/s).
    #[inline]
    pub fn natural_frequency(&self) -> f32 {
        (self.stiffness / self.mass).sqrt()
    }
}

/// Exact solution of the damped oscillator around equilibrium:
/// returns `(displacement, velocity)` at time `t` given initial
/// displacement `u0` and velocity `v0`. O(1), allocation-free, panic-free
/// (NaN in → NaN out).
#[inline]
pub fn spring_displacement(spec: &SpringSpec, u0: f32, v0: f32, t: f32) -> (f32, f32) {
    let omega_n = spec.natural_frequency();
    let zeta = spec.damping_ratio;
    let decay = (-zeta * omega_n * t).exp();
    if (zeta - 1.0).abs() < CRITICAL_EPSILON {
        // u(t) = (u0 + B·t)·e^(−ωₙ·t),  B = v0 + ωₙ·u0
        let b = v0 + omega_n * u0;
        let poly = u0 + b * t;
        (poly * decay, (b - omega_n * poly) * decay)
    } else if zeta < 1.0 {
        // Underdamped rotation at the damped frequency ω_d.
        let omega_d = omega_n * (1.0 - zeta * zeta).sqrt();
        let a = u0;
        let b = (v0 + zeta * omega_n * u0) / omega_d;
        let (c, s) = ((omega_d * t).cos(), (omega_d * t).sin());
        let pos = a * c + b * s;
        let vel = -zeta * omega_n * pos + omega_d * (b * c - a * s);
        (pos * decay, vel * decay)
    } else {
        // Overdamped: two real poles.
        let root = (zeta * zeta - 1.0).sqrt();
        let r1 = -omega_n * (zeta - root);
        let r2 = -omega_n * (zeta + root);
        let inv = 1.0 / (r1 - r2);
        let a = (v0 - r2 * u0) * inv;
        let b = (r1 * u0 - v0) * inv;
        let (e1, e2) = ((r1 * t).exp(), (r2 * t).exp());
        (a * e1 + b * e2, a * r1 * e1 + b * r2 * e2)
    }
}

/// Exact position at time `t` of a spring pulling `current` toward
/// `target` with initial `velocity`. O(1).
#[inline]
pub fn spring_position_at(
    spec: &SpringSpec,
    current: f32,
    target: f32,
    velocity: f32,
    t: f32,
) -> f32 {
    target + spring_displacement(spec, current - target, velocity, t).0
}

/// Exact velocity at time `t` of the same spring. O(1).
#[inline]
pub fn spring_velocity_at(
    spec: &SpringSpec,
    current: f32,
    target: f32,
    velocity: f32,
    t: f32,
) -> f32 {
    spring_displacement(spec, current - target, velocity, t).1
}

/// Fill `out` with spring positions sampled every `dt` seconds:
/// `out[k]` is the exact position at `t = k·dt` (frame 0 = current
/// position). Allocation-free — one closed-form seeding plus O(1)
/// recurrence arithmetic per frame; see the module docs for why the
/// recurrence carries no step-size error.
pub fn spring_trajectory(
    spec: &SpringSpec,
    current: f32,
    target: f32,
    velocity: f32,
    dt: f32,
    out: &mut [f32],
) {
    spring_trajectory_from(spec, current, target, velocity, dt, 0, out);
}

/// [`spring_trajectory`] continued at an absolute frame offset:
/// `out[i]` is the position at `t = (start_frame + i)·dt`. Exists so a
/// future streaming caller can resume a long trajectory in chunks
/// without re-deriving per-frame state (the current JNI bridge fills
/// whole buffers in one shot from frame 0).
pub fn spring_trajectory_from(
    spec: &SpringSpec,
    current: f32,
    target: f32,
    velocity: f32,
    dt: f32,
    start_frame: usize,
    out: &mut [f32],
) {
    let u0 = current - target;
    let omega_n = spec.natural_frequency();
    let zeta = spec.damping_ratio;
    let t0 = start_frame as f32 * dt;
    if (zeta - 1.0).abs() < CRITICAL_EPSILON {
        // (u0 + B·t)·decay, advanced by dt each frame.
        let b = velocity + omega_n * u0;
        let q = (-omega_n * dt).exp();
        let mut decay = (-omega_n * t0).exp();
        let mut t = t0;
        for slot in out.iter_mut() {
            *slot = target + (u0 + b * t) * decay;
            t += dt;
            decay *= q;
        }
    } else if zeta < 1.0 {
        // Phasor (c, s) rotates by (ω_d·dt) each frame; decay shrinks
        // by the per-frame factor q.
        let omega_d = omega_n * (1.0 - zeta * zeta).sqrt();
        let a = u0;
        let b = (velocity + zeta * omega_n * u0) / omega_d;
        let (cd, sd) = ((omega_d * dt).cos(), (omega_d * dt).sin());
        let q = (-zeta * omega_n * dt).exp();
        let phase = omega_d * t0;
        let mut c = phase.cos();
        let mut s = phase.sin();
        let mut decay = (-zeta * omega_n * t0).exp();
        for slot in out.iter_mut() {
            *slot = target + decay * (a * c + b * s);
            let next_c = c * cd - s * sd;
            let next_s = c * sd + s * cd;
            c = next_c;
            s = next_s;
            decay *= q;
        }
    } else {
        // Two exponentials, each advanced by its per-frame factor.
        let root = (zeta * zeta - 1.0).sqrt();
        let r1 = -omega_n * (zeta - root);
        let r2 = -omega_n * (zeta + root);
        let inv = 1.0 / (r1 - r2);
        let a = (velocity - r2 * u0) * inv;
        let b = (r1 * u0 - velocity) * inv;
        let q1 = (r1 * dt).exp();
        let q2 = (r2 * dt).exp();
        let mut e1 = (r1 * t0).exp();
        let mut e2 = (r2 * t0).exp();
        for slot in out.iter_mut() {
            *slot = target + a * e1 + b * e2;
            e1 *= q1;
            e2 *= q2;
        }
    }
}

/// Position at time `t` of a fling under exponential friction:
/// `x(t) = x₀ + (v₀/μ)·(1 − e^(−μ·t))`.
#[inline]
pub fn fling_position(start: f32, velocity: f32, friction: f32, t: f32) -> f32 {
    start + (velocity / friction) * (1.0 - (-friction * t).exp())
}

/// Velocity at time `t` of a fling under exponential friction:
/// `v(t) = v₀·e^(−μ·t)`.
#[inline]
pub fn fling_velocity(velocity: f32, friction: f32, t: f32) -> f32 {
    velocity * (-friction * t).exp()
}

/// Edge rubberband: the visual offset applied to a raw overscroll of
/// `overscroll` px at an edge of content spanning `viewport` px:
/// `f(x) = viewport·(1 − 1/(d·x/viewport + 1))`.
///
/// Monotonic, `f(0) = 0`, asymptotically bounded by `viewport` — no
/// overscroll, however extreme, renders more than one viewport past
/// the edge. Requires `viewport > 0` (guaranteed by validated callers;
/// degenerate values propagate as Inf/NaN without panicking).
#[inline]
pub fn rubberband_offset(overscroll: f32, viewport: f32, resistance: f32) -> f32 {
    let c = resistance * overscroll / viewport;
    viewport * (1.0 - 1.0 / (c + 1.0))
}

/// Hard clamp into `[min_bound, max_bound]` — the settle target the
/// snap-back spring aims at after an over-scroll. Total function:
/// degenerate bounds collapse to their midpoint instead of panicking
/// (`f32::clamp` would panic on `min > max`).
#[inline]
pub fn snap_to_bounds(position: f32, min_bound: f32, max_bound: f32) -> f32 {
    if min_bound >= max_bound {
        return (min_bound + max_bound) * 0.5;
    }
    position.clamp(min_bound, max_bound)
}

/// Projected fling landing position: the exponential-friction raw
/// projection `x₀ + v₀/μ`, damped through the edge rubberband when it
/// lands beyond `[min_bound, max_bound]`.
///
/// Within bounds the raw projection is returned exactly. Past an edge
/// the rubberbanded extent is returned — asymptotically at most one
/// viewport beyond the bound (see [`rubberband_offset`]). The
/// follow-up settle to the snapped bound is a separate spring
/// ([`snap_to_bounds`] + [`SpringSpec`]).
pub fn fling_landing(
    start: f32,
    velocity: f32,
    min_bound: f32,
    max_bound: f32,
    friction: f32,
) -> Result<f32, PhysicsError> {
    if !start.is_finite()
        || !velocity.is_finite()
        || !min_bound.is_finite()
        || !max_bound.is_finite()
        || !friction.is_finite()
    {
        return Err(PhysicsError::NonFiniteInput);
    }
    if friction <= 0.0 {
        return Err(PhysicsError::InvalidFriction);
    }
    if min_bound >= max_bound {
        return Err(PhysicsError::InvalidBounds);
    }
    let raw = start + velocity / friction;
    if raw >= min_bound && raw <= max_bound {
        return Ok(raw);
    }
    let span = max_bound - min_bound;
    if raw > max_bound {
        Ok(max_bound + rubberband_offset(raw - max_bound, span, RUBBERBAND_RESISTANCE))
    } else {
        Ok(min_bound - rubberband_offset(min_bound - raw, span, RUBBERBAND_RESISTANCE))
    }
}
