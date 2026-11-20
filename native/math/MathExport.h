#ifndef STREAMIFY_MATH_EXPORT_H
#define STREAMIFY_MATH_EXPORT_H
// ============================================================================
//  MathExport.h — C-ABI visibility shim for the native math kernels
// ============================================================================
//
//  The shared library is compiled with -fvisibility=hidden (native/CMakeLists
//  default), so every symbol intended for OUT-OF-MODULE consumers (Kotlin via
//  JNI, Rust via extern "C" FFI in Engineer 2's mesh crates) must explicitly
//  request default visibility. JNI entry points get this for free through
//  JNIEXPORT; the plain-C math exports below need this attribute.
//
//  Frozen C-ABI surface (Phase-2 directive §5):
//      streamify_hash_candidate_id / _ids_bulk      (CandidateHasher)
//      streamify_candidate_intersection / _union    (CandidateHasher)
//      streamify_transition_score / _key_* / _bpm_* (HarmonicTransitionEngine)
//      streamify_circadian_weights                  (CircadianCurves)
// ============================================================================

#if defined(__GNUC__) || defined(__clang__)
#define STREAMIFY_MATH_API __attribute__((visibility("default")))
#else
#define STREAMIFY_MATH_API
#endif

#endif  // STREAMIFY_MATH_EXPORT_H
