#include <iostream>
#include <vector>
#include <cassert>
#include <cstdio>
#include <iterator>
#include <cmath>
#include "../engine/VectorStore.h"
#include "../engine/AirDropPhysicsEngine.h"

int main() {
    std::cout << "[TEST] Starting SIMD & Physics Test Suite..." << std::endl;

    // 1. Test AirDrop RK4 ODE integration
    streamify::AirDropState state{};
    state.x = 0.0f;
    state.y = 0.0f;
    state.z = 1.0f;
    state.vx = 10.0f;
    state.vy = 5.0f;
    state.vz = 0.0f;
    state.is_docked = false;

    streamify::TargetDock target{100.0f, 100.0f, 141.42f};

    // Step 50 ticks of RK4
    for (int i = 0; i < 50; ++i) {
        streamify::AirDropPhysicsEngine::stepRK4(state, target, 0.016f);
        assert(std::isfinite(state.x));
        assert(std::isfinite(state.y));
        assert(std::isfinite(state.z));
    }
    std::cout << "  - AirDrop RK4 ODE Simulation: PASSED" << std::endl;

    // 2. Test VectorStore. Remove any stale state first: the store appends
    // to the backing file, and identical vectors from a previous run make
    // the top-1 self-match assertion order-dependent (flaky shard).
    std::remove("/tmp/test_vector_store.bin");
    VectorStore& store = VectorStore::getInstance();
    store.init("/tmp/test_vector_store.bin", 64);
    
    std::vector<float> vec1(64, 0.5f);
    std::vector<float> vec2(64, -0.5f);
    int off1 = store.addVector(vec1);
    int off2 = store.addVector(vec2);
    assert(off1 >= 0);
    assert(off2 >= 0);

    auto results = store.searchNearest(vec1, 5);
    assert(!results.empty());
    assert(results[0].vector_offset == off1);
    assert(results[0].similarity > 0.99f); // Self-similarity must be ~1.0
    std::cout << "  - VectorStore 64-D Cosine Search: PASSED" << std::endl;

    std::cout << "[TEST] All SIMD Physics Tests Passed Successfully!" << std::endl;
    return 0;
}
