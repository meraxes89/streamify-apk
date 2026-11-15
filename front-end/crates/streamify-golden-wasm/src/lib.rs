//! streamify-golden-wasm — W1.7: the frozen golden-vector DSP suite on
//! wasm32 (lib surface).
//!
//! The crate is test-only by design: the parity suite lives in
//! [`suite`] (`#[cfg(test)]`) and runs under `wasm-bindgen-test` in
//! headless Chrome (CI: web-build.yml → wasm-tests job). There is no
//! shipped wasm surface to export — the web app's DSP facade lands with
//! the real engine integration, and when it does, THIS suite is the gate
//! its numbers must already satisfy.
//!
//! See `suite.rs` for the mirrored-suite contract and
//! rust/crates/streamify-audio/tests/golden_audio_vectors.rs for the
//! native original this mirrors.

#[cfg(test)]
mod suite;
