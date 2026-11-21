//! Sovereign HAL Phase 2 (r13): the Android bracket link directive.
//!
//! The `sovereign_android_loopback` player declares `extern "C"`
//! blocks over the Oboe bracket's exports (`sovereign_oboe_*` in
//! `native/src/oboe_output_bracket.cpp`, compiled into
//! `libstreamify_native_core.so` by the NDK CMake umbrella). Those are
//! LINK-TIME references — dlopen at runtime does not satisfy them.
//!
//! This build script emits the `cargo:rustc-link-lib`/`-search` pair
//! ONLY when `SOVEREIGN_BRACKET_LIB_DIR` is set (the CI legs export it
//! to the CMake output dir; local and desktop builds see nothing and
//! the player compiles to the empty desktop shim as before). Doing it
//! via build.rs instead of command-line flags keeps the wiring immune
//! to wrapper arg parsing — cargo-ndk 4.x ate both the RUSTFLAGS
//! spelling (silently dropped from the link line, r11) and the `--`
//! passthrough (clap "unexpected argument", r12).

fn main() {
    // Re-run if the bracket dir contents change.
    println!("cargo:rerun-if-env-changed=SOVEREIGN_BRACKET_LIB_DIR");
    if let Ok(dir) = std::env::var("SOVEREIGN_BRACKET_LIB_DIR") {
        if !dir.is_empty() {
            println!("cargo:rustc-link-search=native={dir}");
            // -lstreamify_native_core resolves libstreamify_native_core.so
            // (NDK lld); no version suffix is emitted by the CMake target.
            println!("cargo:rustc-link-lib=dylib=streamify_native_core");
            println!(
                "cargo:warning=sovereign-hal: linking the Oboe bracket from {dir} \
                 (libstreamify_native_core.so)"
            );
        }
    }
}
