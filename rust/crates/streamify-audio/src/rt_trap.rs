//! The real-time allocation trap (Sovereign HAL Phase 5 / Pillar 1,
//! RFC Phase B) — the streamify-audio arm.
//!
//! Same contract as `streamify_playback::rt_trap`: CI-only feature
//! `rt-alloc-trap` pulls `assert_no_alloc` (MIT OR Apache-2.0; workspace
//! dep with default features OFF so the crate's `disable_release` default
//! cannot neuter the trap); production builds see a pass-through
//! [`assert_no_alloc`] and NO new dependency (the RT-boundary audit —
//! `scripts/verify_audio_rt_boundary.sh`, `cargo tree -p streamify-audio`
//! — keeps the graph at exactly {streamify-core, rubato}; assert_no_alloc
//! rides only in dev/test with the feature on).
//!
//! The trap fires for ANY allocator call inside the guarded window:
//! `Box::new`, `Vec::push` past capacity, `format!`, `String` growth,
//! `malloc`/`free` through FFI-registered Rust allocators — the test
//! binary aborts via `handle_alloc_error` (debug builds). Lock-freedom is
//! enforced separately (the structural dependency audit); a Mutex does
//! not allocate, so the trap and the audit cover complementary halves.

#![cfg_attr(not(feature = "rt-alloc-trap"), allow(unused))]

#[cfg(feature = "rt-alloc-trap")]
pub use assert_no_alloc::{assert_no_alloc, permit_alloc, AllocDisabler};

#[cfg(not(feature = "rt-alloc-trap"))]
/// Pass-through: the trap is a CI-only feature; production builds compile
/// the wrapped RT bodies with the plain closure call.
pub fn assert_no_alloc<T, F: FnOnce() -> T>(func: F) -> T {
    func()
}

#[cfg(not(feature = "rt-alloc-trap"))]
/// Pass-through permit (no-op without the feature).
pub fn permit_alloc<T, F: FnOnce() -> T>(func: F) -> T {
    func()
}
