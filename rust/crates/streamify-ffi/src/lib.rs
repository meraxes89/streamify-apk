//! streamify-ffi — the umbrella artifact crate (Discussion #120).
//!
//! Compiles the FINAL unified binaries every shell loads —
//! `libstreamify_core_rs.so` (Android/Linux cdylib),
//! `streamify_core_rs.dll` (Windows) and the `StreamifyCore.xcframework`
//! staticlib slices (iOS/macOS) — and hosts the raw C-ABI surface
//! ([`ffi`]) plus the Android-only JNI bridges
//! ([`jni_bridge`] / [`jni_bridge_p2p`], MP-C12/MP-C13: the `jni`
//! crate is target-gated and the modules carry matching cfg gates, so
//! desktop and Apple builds never compile them).
//!
//! ABI COMPATIBILITY CONTRACT: the `[lib]` name is pinned to
//! `streamify_core_rs` and this crate's root re-exports reconstruct the
//! old monolith's full public surface (module paths AND root items), so
//! every consumer in shared/, app/, desktop/ and iosApp/ — the uniffi_*
//! trampolines compiled into streamify-contract, the Java_* JNI symbols,
//! and the extern "C" exports — links against the workspace build
//! unchanged.

pub mod auth;
pub mod cache;
pub mod ffi;
pub mod governor;
// Multiplatform Phase 3 (MP-C12/MP-C13): the JNI surface compiles ONLY
// for the Android target — the `jni` crate is target-gated in
// Cargo.toml, and the desktop JVM / Apple lanes reach the core
// exclusively through the UniFFI contract (no second bridge).
#[cfg(target_os = "android")]
pub mod jni_bridge;
#[cfg(target_os = "android")]
pub mod jni_bridge_p2p;
pub mod task_orchestrator;

// ── The union surface (old monolith root, reconstructed) ───────────────────
// Every domain crate re-exports its modules and root items; these globs
// rebuild the exact `streamify_core_rs::X` paths the integration tests,
// examples and foreign shells already consume. Module names are globally
// unique across the workspace (they were unique in the monolith), so
// the globs compose without collisions.
pub use streamify_contract::*;
pub use streamify_core::*;
pub use streamify_audio::*;
pub use streamify_jam::*;
pub use streamify_resolver::*;
pub use streamify_storage::*;
