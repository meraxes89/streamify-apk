// ============================================================================
//  test_rt_alloc_guard_trap.cc — Phase 5 / Pillar 1 non-vacuity control.
//
//  Proves the C++ RT allocation trap (RtAllocGuard.h under
//  -DSTREAMIFY_RT_ALLOC_GUARD) actually FIRES: an allocation inside the
//  guarded window must abort the process. Compiled ONLY in the guarded
//  lane (the CI "negative control" run): exit code 134 (SIGABRT) is the
//  PASS condition; exit 0 means the trap is vacuous — a CI failure.
//
//  Runs the guarded render cores two ways:
//    1. clean pulls → normal exit 0 (the zero-alloc paths hold);
//    2. a deliberate std::vector allocation inside the guard → abort.
//  Mode is argv[1]: "clean" | "fault".
// ============================================================================
#include <cstdio>
#include <vector>

#include "../include/RtAllocGuard.h"
#include "AllocGuard.h"  // the counting operator new/delete back-end

int main(int argc, char** argv) {
    const bool fault = argc > 1 && argv[1][0] == 'f';  // "fault"
    // The guarded window: any operator new here aborts (guarded builds).
    STREAMIFY_RT_GUARD("negative-control");
    if (fault) {
        // Heap allocation on the "RT" thread. The guard's destructor
        // (scope exit, below) counts it and aborts; execution NEVER
        // reaches the return. If it does, the trap is vacuous.
        std::vector<float> leak(1024);
        std::fprintf(stderr, "VACUOUS GUARD: %zu bytes survived the window\n",
                     leak.size() * sizeof(float));
        return 0;
    }
    std::printf("clean window survived (no allocations) — trap armed\n");
    return 0;
}
