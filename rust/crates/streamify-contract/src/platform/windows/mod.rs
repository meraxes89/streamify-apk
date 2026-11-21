//! Windows platform capability adapters (Phase 5).
//!
//! MP-CAP01 lives in [`smtc`]: the System Media Transport Controls
//! adapter — pure-Rust WinRT projection (the mission's "or pure Rust
//! equivalent under `rust/src/platform/windows/smtc.rs` via the
//! `windows` crate" option). One bridge, one language: the desktop
//! shells drive it through the same UniFFI facade as everything else
//! (no second JNI/JNA edge into a C++ SMTC shim, per the crate's
//! no-second-bridge invariant), and the whole implementation is
//! compile-audited on every host lane via
//! `cargo check --target x86_64-pc-windows-gnu`.

pub mod smtc;
