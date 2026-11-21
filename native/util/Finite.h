#pragma once
#include <cstdint>
#include <cstring>

// ============================================================================
//  Finite.h — fast-math-safe finite/NaN checks (audit #22 item 2)
// ============================================================================
//
//  PROBLEM: the production library builds with global `-ffast-math`
//  (implies `-ffinite-math-only`), under which the compiler is allowed
//  to assume floating values are never NaN/Inf: `std::isfinite` and
//  `std::isnan` fold to compile-time constants, and IEEE NaN-aware
//  comparison guards (`if (!(x >= lo))`) are formally undefined
//  behavior. The guards the test suite proves (tests recompile these
//  same sources with `-fno-fast-math` at compile AND link, per
//  CMakeLists "LTO leak" note) were NOT the guards that shipped.
//
//  FIX PATTERN (established by PR #32's PaletteIsNan, generalized
//  here): inspect the BIT PATTERN through a memcpy load. Integer
//  domain code is untouchable by floating-point flags — identical
//  semantics under -ffast-math, -ffinite-math-only, -fno-fast-math
//  and sanitizers. Single truth for every build mode.
//
//  Cost: 2-3 ALU ops (load, mask, compare) — no branches, no library
//  calls, safe on the audio thread. Bit-exact identical behavior on
//  every IEEE-754 target the NDK supports (all of them).
// ============================================================================

namespace streamify::util {

/// true when `v` is any NaN (quiet or signaling).
inline bool f32_is_nan(float v) {
    std::uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    // Exponent all-ones AND a nonzero mantissa.
    return (bits & 0x7F80'0000u) == 0x7F80'0000u &&
           (bits & 0x007F'FFFFu) != 0u;
}

/// true when `v` is finite (not NaN and not ±Inf).
inline bool f32_is_finite(float v) {
    std::uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    // Exponent not all-ones covers NaN and ±Inf in one test.
    return (bits & 0x7F80'0000u) != 0x7F80'0000u;
}

/// true when double `v` is finite (not NaN and not ±Inf).
inline bool f64_is_finite(double v) {
    std::uint64_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    return (bits & 0x7FF0'0000'0000'0000ull) != 0x7FF0'0000'0000'0000ull;
}

/// Guard helper: returns `fallback` when `v` is NaN or ±Inf, else `v`.
/// Use at configuration boundaries (setters) instead of
/// `if (!(x >= lo))` patterns, whose NaN behavior fast-math may fold.
inline float f32_finite_or(float v, float fallback) {
    return f32_is_finite(v) ? v : fallback;
}

}  // namespace streamify::util
