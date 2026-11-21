#pragma once
#include <cmath>
#include <utility>

namespace streamify {

struct AirDropState {
    float x;
    float y;
    float z;
    float vx;
    float vy;
    float vz;
    float stretch_parallel;
    float stretch_perp;
    float rotation_rad;
    float pitch_deg;
    float roll_deg;
    float impact_progress;
    bool is_docked;
    bool is_ready_to_dock;
};

struct TargetDock {
    float x;
    float y;
    float initial_dist;
};

class AirDropPhysicsEngine {
public:
    static void stepRK4(AirDropState& state, const TargetDock& target, float dt);
};

} // namespace streamify
