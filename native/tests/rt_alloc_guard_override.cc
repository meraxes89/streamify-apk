// ============================================================================
//  rt_alloc_guard_override.cc — Phase 5 / Pillar 1: the unified counting
//  back-end for BOTH allocation-guard families.
//
//  Exactly ONE definition of the global operator new/delete replacements
//  per binary (the house ODR discipline — alloc_guard_override.cc is the
//  legacy single-family version; this TU supersedes it in binaries that
//  also arm the Phase 5 RT trap):
//    * streamify_test counters (AllocGuard.h) — the Phase 4a
//      zero-allocation AUDITS (count and check at scope end);
//    * streamify_rt counters (RtAllocGuard.h) — the Phase 5 RT trap
//      (counted by this TU; the RtGuard destructor ABORTS on any count).
//
//  Scope limits inherited from the Phase-1 design: hooks operator
//  new/delete only; raw malloc on a hot path is not counted (the audited
//  paths use neither — atomics + memcpy + the frozen ring edge).
// ============================================================================

#include <cstdlib>
#include <new>

#include "../include/RtAllocGuard.h"
#include "AllocGuard.h"

void* operator new(std::size_t sz) {
    if (streamify_rt::guardDepth() > 0) {
        streamify_rt::note_alloc();
    }
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
