//! The real-time allocation trap (Sovereign HAL Phase 5 / Pillar 1,
//! RFC Phase B).
//!
//! CI-only feature `rt-alloc-trap` pulls `assert_no_alloc` (MIT OR
//! Apache-2.0, vendored via the workspace — default features OFF so the
//! crate's `disable_release` default cannot neuter the trap) and re-exports
//! the guard surface under ONE stable name so the RT paths can wrap their
//! callback bodies without a hard dependency when the feature is off:
//!
//! ```ignore
//! // engine.rs (the cpal callback body):
//! rt_trap::assert_no_alloc(|| {
//!     let served = unsafe { ring_export::streamify_ring_read_f32(..) };
//!     // ... underrun zero-fill, clock publish ...
//! })
//! ```
//!
//! With the feature OFF (production builds), [`rt_trap::assert_no_alloc`]
//! is a plain pass-through closure call — zero cost, zero new deps, and
//! the RT-boundary audit (`cargo tree`) sees no new crate.
//!
//! With the feature ON (the `rt-alloc-trap` CI lane), the TEST binary
//! registers [`rt_trap::AllocDisabler`] as its `#[global_allocator]`; any
//! `Box::new`, `Vec::push`, `format!`, `malloc`/`free` — any allocator
//! call — inside the guarded window ABORTS the test process immediately
//! (debug builds: `handle_alloc_error`; the abort is the point — an
//! allocation on the audio thread is a hard failure, not a warning).
//!
//! Locks are NOT trapped by the allocator (a `std::sync::Mutex` does not
//! allocate); the lock-freedom half of the RT contract stays pinned by
//! the structural audits (the RT boundary script + code review discipline
//! that keeps tokio/rusqlite out of the graph).

//! With the feature OFF (production builds), this module still exists as
//! a stable name but exposes only a pass-through [`assert_no_alloc`]
//! equivalent — zero cost, zero new deps, and the RT-boundary audit
//! (`cargo tree`) sees no new crate.

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
