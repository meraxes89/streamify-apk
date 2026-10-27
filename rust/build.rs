// Phase 2 (UFFI-01): generate UniFFI scaffolding from src/streamify.udl at
// build time. The "builtin-bindgen" feature of uniffi_build embeds the
// bindgen, so this needs no external uniffi-bindgen binary on the PATH
// (CI runners only install the stable toolchain + nextest + sccache).
fn main() {
    uniffi_build::generate_scaffolding("./src/streamify.udl").unwrap();
    normalize_scaffolding_lints();
}

/// uniffi 0.28's template opens the file with two `///` doc-comment lines
/// followed by a blank line ahead of the UDL metadata const. That shape is
/// rejected by clippy 1.99+ under `-D warnings`
/// (`empty_line_after_doc_comments`), and attribute-scoped allowances
/// cannot reach into `include!`d code — so the offending boilerplate is
/// demoted to plain comments right after generation.
///
/// Self-verifying by construction: if a future uniffi version changes the
/// template, this rewrite no-ops and the lint resurfaces in the CI clippy
/// gate instead of failing silently here.
fn normalize_scaffolding_lints() {
    let out_dir = std::env::var("OUT_DIR").expect("cargo must set OUT_DIR");
    let path = std::path::Path::new(&out_dir).join("streamify.uniffi.rs");
    let generated = std::fs::read_to_string(&path).expect("scaffolding must exist post-build");

    let boilerplate = "/// Export info about the UDL while used to create us\n\
                       /// See `uniffi_bindgen::macro_metadata` for how this is used.";
    let demoted = "// Export info about the UDL while used to create us\n\
                   // See `uniffi_bindgen::macro_metadata` for how this is used.";

    if generated.contains(boilerplate) {
        std::fs::write(&path, generated.replace(boilerplate, demoted))
            .expect("scaffolding rewrite must succeed");
    }
}
