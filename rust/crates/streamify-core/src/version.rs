//! MP-C05: the FFI contract version.
//!
//! A client shell compares the loaded library's `contract_version()`
//! value against its compiled-in expectation and refuses to start on
//! mismatch. Bump ONLY on breaking contract changes after the Phase 3
//! freeze: adding a method, a dictionary field (optionality rules
//! apply) or an enum variant to a `[NonExhaustive]` surface is
//! additive; renaming, removing or re-typing any contract item is
//! breaking. The value is pinned by a unit test
//! (`contract_version_is_pinned` — lives in streamify-contract's suite)
//! so an accidental bump fails review.

// ============================================================================
//  MP-C05: contract version
// ============================================================================

/// FFI contract version for fail-fast startup ABI verification (MP-C05).
///
/// A client shell compares the loaded library's `contract_version()` value
/// against its compiled-in expectation and refuses to start on mismatch.
/// Bump ONLY on breaking contract changes after the Phase 3 freeze:
/// adding a method, a dictionary field (optionality rules apply) or an
/// enum variant to a `[NonExhaustive]` surface is additive; renaming,
/// removing or re-typing any contract item is breaking. The value is
/// pinned by a unit test (`contract_version_is_pinned`) so an accidental
/// bump fails review.
pub const CONTRACT_VERSION: u32 = 1;

/// First frozen multiplatform contract generation (see [`CONTRACT_VERSION`]).
pub fn contract_version() -> u32 {
    CONTRACT_VERSION
}
