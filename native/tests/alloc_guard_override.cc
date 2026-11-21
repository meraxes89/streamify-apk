// ============================================================================
//  alloc_guard_override.cc — Phase 4a audit fix: the shared zero-allocation
//  counting TU for the Phase-4a host suites.
// ============================================================================
//
//  The global operator new/delete replacements route every C++ allocation
//  through the AllocGuard counters (AllocGuard.h). Until this change they
//  existed in exactly ONE TU — test_dsp_phase1.cc — and were therefore
//  linked only into dsp_test_suite. The Phase-4a binaries
//  (virtual_hal_test_suite, coreaudio_render_core_test_suite,
//  test_audio_sink_ringbuffer) armed the same guard WITHOUT the counting
//  back-end, so their "zero allocations on the hot path" assertions were
//  structurally vacuous: count() could only ever read 0.
//
//  This TU gives each of those binaries its own single definition. The
//  semantics are identical to the Phase-1 originals (count when a guard is
//  active, malloc, throw bad_alloc on failure); test_dsp_phase1.cc keeps
//  its own copy so the Phase-1 lane is untouched. Exactly one definition
//  per binary — no ODR hazard across targets.
//
//  Scope note: the guard hooks operator new/delete only. Raw malloc on a
//  hot path is not counted (a Phase-1 design limit); the audited paths use
//  neither — by construction they are atomics + memcpy.

#include <cstdlib>
#include <new>

#include "AllocGuard.h"

void* operator new(std::size_t sz) {
    if (streamify_test::guardDepthRef() > 0) {
        ++streamify_test::allocCountRef();
    }
    void* p = std::malloc(sz ? sz : 1);
    if (p == nullptr) throw std::bad_alloc();
    return p;
}

void* operator new[](std::size_t sz) { return ::operator new(sz); }

void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }
