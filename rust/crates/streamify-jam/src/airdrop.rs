use std::f32::consts::PI;

/// Cache-line aligned state buffer for zero-alloc high-performance integration.
#[repr(C, align(64))]
pub struct AirdropState {
    pub pos: [f32; 3],         // x, y, z
    pub vel: [f32; 3],         // vx, vy, vz
    pub stretch_parallel: f32, // aerodynamic stretch along velocity
    pub stretch_perp: f32,     // perpendicular squash
    pub rotation_rad: f32,     // 2D trajectory rotation
    pub pitch_deg: f32,        // 3D pitch
    pub roll_deg: f32,         // 3D roll
    pub impact_progress: f32,  // 0.0 -> 1.0 post-docking
    pub is_docked: bool,
    pub is_ready_to_dock: bool,
    pub shock_radius: f32,
    pub shock_alpha: f32,
    pub particle_count: u32,
    pub particle_px: [f32; 256],
    pub particle_py: [f32; 256],
    pub particle_vx: [f32; 256],
    pub particle_vy: [f32; 256],
    pub particle_life: [f32; 256],
    pub particle_radius: [f32; 256],
}

impl Default for AirdropState {
    fn default() -> Self {
        Self {
            pos: [0.0; 3],
            vel: [0.0; 3],
            stretch_parallel: 1.0,
            stretch_perp: 1.0,
            rotation_rad: 0.0,
            pitch_deg: 0.0,
            roll_deg: 0.0,
            impact_progress: 0.0,
            is_docked: false,
            is_ready_to_dock: false,
            shock_radius: 0.0,
            shock_alpha: 0.0,
            particle_count: 0,
            particle_px: [0.0; 256],
            particle_py: [0.0; 256],
            particle_vx: [0.0; 256],
            particle_vy: [0.0; 256],
            particle_life: [0.0; 256],
            particle_radius: [0.0; 256],
        }
    }
}

pub struct AirdropPhysicsEngine;

impl AirdropPhysicsEngine {
    /// Step simulation with RK4 integration, critically-damped spring tensors, and particle kinematics.
    #[inline(always)]
    pub fn step(
        state: &mut AirdropState,
        target_x: f32,
        target_y: f32,
        initial_dist: f32,
        dt: f32,
        particle_budget: u32,
    ) {
        let safe_dt = dt.clamp(0.001, 0.05);

        if state.is_docked {
            if state.impact_progress < 1.0 {
                state.impact_progress = (state.impact_progress + (safe_dt / 0.220)).min(1.0);
                let t = state.impact_progress;
                let squash_y = if t < 0.25 {
                    1.0 - (0.15 * (t / 0.25))
                } else if t < 0.60 {
                    0.85 + (0.22 * ((t - 0.25) / 0.35))
                } else {
                    1.07 - (0.07 * ((t - 0.60) / 0.40))
                };
                state.stretch_perp = squash_y;
                state.stretch_parallel = 1.0 / squash_y.max(0.01);
            }

            // Step shockwave & particles on impact
            if state.shock_alpha > 0.0 {
                state.shock_radius += 180.0 * safe_dt;
                state.shock_alpha = (state.shock_alpha - 4.5 * safe_dt).max(0.0);
            }

            Self::step_particles(state, safe_dt);
            return;
        }

        // Compute acceleration via critically-damped spring + aerodynamic lift
        let compute_accel = |px: f32, py: f32, pvx: f32, pvy: f32| -> (f32, f32) {
            let dx = target_x - px;
            let dy = target_y - py;
            let dist = (dx * dx + dy * dy).sqrt();
            if dist < 1.0 {
                return (0.0, 0.0);
            }

            let mut k = 24.0;
            let mut c = 9.5;

            if !state.is_ready_to_dock && dist < 50.0 {
                k = 6.0;
                c = 14.0;
            }

            let mut fx = k * dx - c * pvx;
            let mut fy = k * dy - c * pvy;

            if initial_dist > 1.0 {
                let progress = (dist / initial_dist).clamp(0.0, 1.0);
                let lift_mag = 180.0 * (progress * PI).sin();
                fx += (-dy / dist) * lift_mag;
                fy += (dx / dist) * lift_mag;
            }

            (fx, fy)
        };

        // RK4 Sub-stepping
        let x = state.pos[0];
        let y = state.pos[1];
        let vx = state.vel[0];
        let vy = state.vel[1];

        // k1
        let (ax1, ay1) = compute_accel(x, y, vx, vy);
        let k1_vx = ax1 * safe_dt;
        let k1_vy = ay1 * safe_dt;
        let k1_x = vx * safe_dt;
        let k1_y = vy * safe_dt;

        // k2
        let (ax2, ay2) = compute_accel(
            x + 0.5 * k1_x,
            y + 0.5 * k1_y,
            vx + 0.5 * k1_vx,
            vy + 0.5 * k1_vy,
        );
        let k2_vx = ax2 * safe_dt;
        let k2_vy = ay2 * safe_dt;
        let k2_x = (vx + 0.5 * k1_vx) * safe_dt;
        let k2_y = (vy + 0.5 * k1_vy) * safe_dt;

        // k3
        let (ax3, ay3) = compute_accel(
            x + 0.5 * k2_x,
            y + 0.5 * k2_y,
            vx + 0.5 * k2_vx,
            vy + 0.5 * k2_vy,
        );
        let k3_vx = ax3 * safe_dt;
        let k3_vy = ay3 * safe_dt;
        let k3_x = (vx + 0.5 * k2_vx) * safe_dt;
        let k3_y = (vy + 0.5 * k2_vy) * safe_dt;

        // k4
        let (ax4, ay4) = compute_accel(x + k3_x, y + k3_y, vx + k3_vx, vy + k3_vy);
        let k4_vx = ax4 * safe_dt;
        let k4_vy = ay4 * safe_dt;
        let k4_x = (vx + k3_vx) * safe_dt;
        let k4_y = (vy + k3_vy) * safe_dt;

        // Advance Kinematics
        state.pos[0] += (k1_x + 2.0 * k2_x + 2.0 * k3_x + k4_x) / 6.0;
        state.pos[1] += (k1_y + 2.0 * k2_y + 2.0 * k3_y + k4_y) / 6.0;
        state.vel[0] += (k1_vx + 2.0 * k2_vx + 2.0 * k3_vx + k4_vx) / 6.0;
        state.vel[1] += (k1_vy + 2.0 * k2_vy + 2.0 * k3_vy + k4_vy) / 6.0;

        // Docking collision check
        let rem_dx = target_x - state.pos[0];
        let rem_dy = target_y - state.pos[1];
        let rem_dist = (rem_dx * rem_dx + rem_dy * rem_dy).sqrt();

        if rem_dist < 24.0 && state.is_ready_to_dock {
            state.is_docked = true;
            state.impact_progress = 0.0;
            state.pos[0] = target_x;
            state.pos[1] = target_y;
            state.vel[0] = 0.0;
            state.vel[1] = 0.0;
            state.shock_radius = 0.0;
            state.shock_alpha = 1.0;

            // Spawn dynamic particle burst within device tier budget
            Self::spawn_particles(state, target_x, target_y, particle_budget);
            return;
        }

        // Velocity-dependent kinetic deformation & perspective angles
        let speed = (state.vel[0] * state.vel[0] + state.vel[1] * state.vel[1]).sqrt();
        let stretch = 1.0 + 0.25 * (speed / 800.0).tanh();
        state.stretch_parallel = stretch;
        state.stretch_perp = 1.0 / stretch;
        state.rotation_rad = state.vel[1].atan2(state.vel[0]);
        state.pitch_deg = (-state.vel[1] * 0.025).clamp(-12.0, 12.0);
        state.roll_deg = (state.vel[0] * 0.025).clamp(-10.0, 10.0);
    }

    #[inline(always)]
    fn spawn_particles(state: &mut AirdropState, target_x: f32, target_y: f32, budget: u32) {
        let count = budget.clamp(16, 256) as usize;
        state.particle_count = count as u32;

        let mut pseudo_rand = 123456789u32;
        for i in 0..count {
            pseudo_rand = pseudo_rand.wrapping_mul(1664525).wrapping_add(1013904223);
            let r1 = (pseudo_rand & 0xFFFF) as f32 / 65535.0;
            pseudo_rand = pseudo_rand.wrapping_mul(1664525).wrapping_add(1013904223);
            let r2 = (pseudo_rand & 0xFFFF) as f32 / 65535.0;

            let angle = r1 * 2.0 * PI;
            let speed = 120.0 + r2 * 360.0;

            state.particle_px[i] = target_x + (r1 - 0.5) * 40.0;
            state.particle_py[i] = target_y + (r2 - 0.5) * 15.0;
            state.particle_vx[i] = angle.cos() * speed;
            state.particle_vy[i] = angle.sin() * speed * 0.5 - 80.0;
            state.particle_radius[i] = 2.5 + r1 * 4.0;
            state.particle_life[i] = 0.95;
        }
    }

    #[inline(always)]
    fn step_particles(state: &mut AirdropState, dt: f32) {
        let gravity = 320.0;
        let count = (state.particle_count as usize).min(256);

        // Vectorized 4-at-a-time loop (Auto-vectorizes to ARM NEON SIMD)
        let mut i = 0;
        while i + 4 <= count {
            for j in 0..4 {
                let idx = i + j;
                state.particle_px[idx] += state.particle_vx[idx] * dt;
                state.particle_py[idx] += state.particle_vy[idx] * dt + 0.5 * gravity * dt * dt;
                state.particle_vy[idx] += gravity * dt;
                state.particle_life[idx] = (state.particle_life[idx] - dt * 2.8).max(0.0);
            }
            i += 4;
        }

        while i < count {
            state.particle_px[i] += state.particle_vx[i] * dt;
            state.particle_py[i] += state.particle_vy[i] * dt + 0.5 * gravity * dt * dt;
            state.particle_vy[i] += gravity * dt;
            state.particle_life[i] = (state.particle_life[i] - dt * 2.8).max(0.0);
            i += 1;
        }
    }
}
