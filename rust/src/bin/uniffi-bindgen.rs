//! Phase 3 (UFFI-03): standalone UniFFI bindgen CLI entry point.
//!
//! The crate has carried the uniffi dependency with the `cli` feature since
//! Phase 2 (UFFI-01), but the scaffolding pipeline only became autonomous
//! once Gradle could invoke the bindgen without a separately installed
//! `uniffi-bindgen` binary on the PATH: `app/build.gradle.kts` runs
//! `cargo run --bin uniffi-bindgen -- generate rust/src/streamify.udl
//! --language kotlin --out-dir <generated>` (task
//! `generateUniffiKotlinBindings`), which resolves to this target.
//!
//! Why a crate-local binary instead of `cargo install uniffi-bindgen`:
//!   * version lockstep is structural, not procedural — the bindgen that
//!     formats the foreign bindings is guaranteed to match the scaffolding
//!     macros compiled into the cdylib (same `uniffi = "0.28"` entry in
//!     Cargo.toml, no drift between a developer machine and a CI runner);
//!   * zero extra setup on runners: the Rust lanes already install the
//!     stable toolchain, so the codegen needs no additional step;
//!   * the UDL file (not the built `.so`) is the only semantic input, so
//!     generation runs ahead of the Android cross-compilation edges.
//!
//! Host-only by design: this target is never packaged into the APK — the
//! Gradle task builds it implicitly via `cargo run` on the host, and the
//! Android ABI shards package only the cdylib artifacts.

fn main() {
    uniffi::uniffi_bindgen_main();
}
