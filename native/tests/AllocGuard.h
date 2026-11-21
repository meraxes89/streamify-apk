#ifndef STREAMIFY_TEST_ALLOC_GUARD_H
#define STREAMIFY_TEST_ALLOC_GUARD_H
// ============================================================================
//  AllocGuard.h — shared zero-allocation audit guard for the native suites
// ============================================================================
//
//  The dsp_test_suite binary links several test TUs; the global
//  operator new/delete replacements live in exactly ONE TU
//  (test_dsp_phase1.cc, which owns them since Phase 1) and route every
//  allocation through the counters below. All suites (phase 1/2/3)
//  include this header to arm the same guard around their hot paths.
//
//  Implementation note (Phase 3): the counters are function-local
//  `static thread_local` references behind inline accessors instead of
//  cross-TU `extern thread_local` variables. GCC 14 -O2 miscompiles the
//  UBSan instrumentation of extern-TLS accesses from OTHER TUs (the
//  address goes through a null check that fires, making every count()
//  read 0 — silently VACUOUS zero-alloc proofs on local g++ builds;
//  clean at -O0/-O1 and on CI clang-17). The inline-guard-function
//  pattern is well-formed on every toolchain: the TLS object is
//  uniquely defined in a COMDAT section, initialized on first use, and
//  needs no TLS-init wrapper.
//
//  Usage:   { streamify_test::AllocGuard g; hotPathCall(...);
//            check(g.count() == 0, "zero allocs"); }
// ============================================================================

namespace streamify_test {

inline int& guardDepthRef() {
    static thread_local int depth = 0;
    return depth;
}

inline unsigned long long& allocCountRef() {
    static thread_local unsigned long long count = 0;
    return count;
}

struct AllocGuard {
    AllocGuard() {
        ++guardDepthRef();
        allocCountRef() = 0;
    }
    ~AllocGuard() { --guardDepthRef(); }
    unsigned long long count() const { return allocCountRef(); }
};

}  // namespace streamify_test

#endif  // STREAMIFY_TEST_ALLOC_GUARD_H
