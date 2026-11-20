#include "AirDropPhysicsEngine.h"
#include <algorithm>

namespace streamify {

void AirDropPhysicsEngine::stepRK4(AirDropState& state, const TargetDock& target, float dt) {
    if (state.is_docked) {
        if (state.impact_progress < 1.0f) {
            state.impact_progress = std::min(1.0f, state.impact_progress + (dt / 0.220f));
            float t = state.impact_progress;
            float squashY = 1.0f;
            if (t < 0.25f) {
                squashY = 1.0f - (0.15f * (t / 0.25f));
            } else if (t < 0.60f) {
                squashY = 0.85f + (0.22f * ((t - 0.25f) / 0.35f));
            } else {
                squashY = 1.07f - (0.07f * ((t - 0.60f) / 0.40f));
            }
            state.stretch_perp = squashY;
            state.stretch_parallel = 1.0f / std::max(0.01f, squashY);
        }
        return;
    }

    // Force evaluation function for RK4
    auto computeAccel = [&](float px, float py, float vx, float vy) -> std::pair<float, float> {
        float dx = target.x - px;
        float dy = target.y - py;
        float dist = std::sqrt(dx * dx + dy * dy);
        if (dist < 1.0f) return {0.0f, 0.0f};

        // Smooth graceful spring dynamics (k=24.0f, c=9.5f)
        float k = 24.0f;
        float c = 9.5f;

        // When approaching dock before stream is resolved, decelerate smoothly to hover
        if (!state.is_ready_to_dock && dist < 50.0f) {
            k = 6.0f;
            c = 14.0f;
        }

        float fx = k * dx - c * vx;
        float fy = k * dy - c * vy;

        // Orthogonal lift force creating organic aerodynamic parabolic flight arc
        if (target.initial_dist > 1.0f) {
            float liftMag = 180.0f * std::sin(std::clamp(dist / target.initial_dist, 0.0f, 1.0f) * 3.14159265f);
            fx += (-dy / dist) * liftMag;
            fy += ( dx / dist) * liftMag;
        }

        return {fx, fy};
    };

    // RK4 Integration
    float x = state.x;
    float y = state.y;
    float vx = state.vx;
    float vy = state.vy;

    // k1
    auto [ax1, ay1] = computeAccel(x, y, vx, vy);
    float k1_vx = ax1 * dt;
    float k1_vy = ay1 * dt;
    float k1_x  = vx * dt;
    float k1_y  = vy * dt;

    // k2
    auto [ax2, ay2] = computeAccel(x + 0.5f * k1_x, y + 0.5f * k1_y, vx + 0.5f * k1_vx, vy + 0.5f * k1_vy);
    float k2_vx = ax2 * dt;
    float k2_vy = ay2 * dt;
    float k2_x  = (vx + 0.5f * k1_vx) * dt;
    float k2_y  = (vy + 0.5f * k1_vy) * dt;

    // k3
    auto [ax3, ay3] = computeAccel(x + 0.5f * k2_x, y + 0.5f * k2_y, vx + 0.5f * k2_vx, vy + 0.5f * k2_vy);
    float k3_vx = ax3 * dt;
    float k3_vy = ay3 * dt;
    float k3_x  = (vx + 0.5f * k2_vx) * dt;
    float k3_y  = (vy + 0.5f * k2_vy) * dt;

    // k4
    auto [ax4, ay4] = computeAccel(x + k3_x, y + k3_y, vx + k3_vx, vy + k3_vy);
    float k4_vx = ax4 * dt;
    float k4_vy = ay4 * dt;
    float k4_x  = (vx + k3_vx) * dt;
    float k4_y  = (vy + k3_vy) * dt;

    // Advance State
    state.x  += (k1_x + 2.0f * k2_x + 2.0f * k3_x + k4_x) / 6.0f;
    state.y  += (k1_y + 2.0f * k2_y + 2.0f * k3_y + k4_y) / 6.0f;
    state.vx += (k1_vx + 2.0f * k2_vx + 2.0f * k3_vx + k4_vx) / 6.0f;
    state.vy += (k1_vy + 2.0f * k2_vy + 2.0f * k3_vy + k4_vy) / 6.0f;

    // Check dock collision
    float remDx = target.x - state.x;
    float remDy = target.y - state.y;
    float remDist = std::sqrt(remDx * remDx + remDy * remDy);
    if (remDist < 24.0f && state.is_ready_to_dock) {
        state.is_docked = true;
        state.x = target.x;
        state.y = target.y;
        state.vx = 0.0f;
        state.vy = 0.0f;
        state.impact_progress = 0.0f;
        return;
    }

    // Lagrangian Incompressible Fluid Strain Tensor
    float speed = std::sqrt(state.vx * state.vx + state.vy * state.vy);
    state.stretch_parallel = 1.0f + 0.25f * std::tanh(speed / 800.0f);
    state.stretch_perp = 1.0f / state.stretch_parallel; // Volume conservation
    state.rotation_rad = std::atan2(state.vy, state.vx);

    // Dynamic 3D gimbal pitch & roll based on velocity & lateral acceleration
    state.pitch_deg = std::clamp(-state.vy * 0.025f, -12.0f, 12.0f);
    state.roll_deg  = std::clamp( state.vx * 0.025f, -10.0f, 10.0f);
}

} // namespace streamify
