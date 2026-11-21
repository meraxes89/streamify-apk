#ifndef STREAMIFY_RT_ALLOC_GUARD_H
#define STREAMIFY_RT_ALLOC_GUARD_H
// ============================================================================
//  RtAllocGuard.h — Sovereign HAL Phase 5 / Pillar 1 (RFC Phase B)
//
//  The C++ arm of the real-time allocation trap for the OS audio render
//  callbacks (Android Oboe onAudioReady, miniaudio data callbacks).
//
//  TEST BUILDS ONLY: compile the bracket TUs with
//  -DSTREAMIFY_RT_ALLOC_GUARD to arm the guard. Production builds
//  (no define) compile every macro to a no-op — zero cost, zero code.
//
//  Mechanism (the house AllocGuard.h discipline, made link-safe for the
//  production TUs): the guard counts C++ heap allocations and blocking
//  lock acquisitions on THIS thread while armed, via thread_local
//  counters + the global operator new/delete hooks registered by the
//  test binary's counting TU (native/tests/alloc_guard_override.cc).
//  Any allocation or lock inside STREAMIFY_RT_GUARD() fails the process
//  immediately (fprintf + std::abort — an allocation on the audio
//  thread is a hard failure, not a warning).
//
//  Raw malloc is not intercepted (operator new/delete only — the
//  Phase-1 design limit, documented in alloc_guard_override.cc); the
//  audited callback paths use neither, by construction: atomics +
//  memcpy + the frozen ring edge.
// ============================================================================

#include <cstdio>
#include <cstdlib>

#if defined(STREAMIFY_RT_ALLOC_GUARD)

namespace streamify_rt {

inline int& guardDepth() {
    static thread_local int depth = 0;
    return depth;
}

inline unsigned long long& guardAllocs() {
    static thread_local unsigned long long allocs = 0;
    return allocs;
}

inline void note_alloc() {
    if (guardDepth() > 0) ++guardAllocs();
}

struct RtGuard {
    explicit RtGuard(const char* site) : site_(site) {
        ++guardDepth();
        guardAllocs() = 0;
    }
    ~RtGuard() {
        const unsigned long long n = guardAllocs();
        --guardDepth();
        if (n > 0) {
            std::fprintf(stderr,
                         "RT-ALLOC-TRAP: %llu heap allocation(s) on the audio "
                         "thread at %s — aborting\n",
                         n, site_);
            std::abort();
        }
    }
    const char* site_;
};

}  // namespace streamify_rt

/// Arm the RT allocation trap for the enclosing SCOPE:
///
///   void onAudioReady(...) {
///       STREAMIFY_RT_GUARD("oboe::onAudioReady");   // <- statement
///       ... the RT body ...
///   }   // guard destructs here: any allocation counted -> abort
///
/// Test builds arm the trap; production builds compile the statement to
/// a no-op (`(void)0`). Unlike a block macro this keeps early `return`s
/// natural — the guard's destructor runs on EVERY exit path.
#define STREAMIFY_RT_GUARD(site)                                   \
    streamify_rt::RtGuard _rt_guard_##__LINE__(site)

#else  // !STREAMIFY_RT_ALLOC_GUARD — production: no-op

#define STREAMIFY_RT_GUARD(site) (void)0

#endif  // STREAMIFY_RT_ALLOC_GUARD

#endif  // STREAMIFY_RT_ALLOC_GUARD_H
