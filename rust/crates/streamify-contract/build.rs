// Phase 2 (UFFI-01): generate UniFFI scaffolding from this crate's
// src/streamify.udl at build time (the UDL lives with streamify-contract
// per Discussion #120; the Gradle codegen tasks and
// scripts/build_ios_xcframework.sh pin its new path). The
// "builtin-bindgen" feature of uniffi_build embeds the bindgen, so this
// needs no external uniffi-bindgen binary on the PATH (CI runners only
// install the stable toolchain + nextest + sccache).
fn main() {
    uniffi_build::generate_scaffolding("./src/streamify.udl").unwrap();
    normalize_scaffolding_lints();
    mark_error_enum_non_exhaustive();
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

/// uniffi 0.28's UDL grammar cannot express non-exhaustiveness on the
/// `[Error]` interface syntax used for fielded variants (its attribute
/// validator rejects the combination — verified against uniffi_udl
/// 0.28.3). The real `EngineError` (defined in streamify-core since the
/// Discussion #120 split, and `#[non_exhaustive]` per the resp5 §2.4
/// mandate) is FOREIGN to this scaffolding crate, so the exhaustive
/// lowering match the `udl_derive(Error)` macro emits is illegal
/// (E0004) unless the derive knows the enum is non-exhaustive — which
/// it reads off the LOCAL enum definition the UDL template emits.
/// Injecting `#[non_exhaustive]` onto that local definition makes the
/// derive emit the mandated wildcard lowering arm
/// (`_ => panic!("Unexpected variant in non-exhaustive enum")`),
/// matching the semantics of the pre-split same-crate build exactly.
///
/// Self-verifying by construction: if a future uniffi version changes
/// the template, this rewrite no-ops and the E0004 resurfaces in the
/// CI gate instead of failing silently here.
fn mark_error_enum_non_exhaustive() {
    let out_dir = std::env::var("OUT_DIR").expect("cargo must set OUT_DIR");
    let path = std::path::Path::new(&out_dir).join("streamify.uniffi.rs");
    let generated = std::fs::read_to_string(&path).expect("scaffolding must exist post-build");

    let needle = "enum r#EngineError {";
    let marked = "#[non_exhaustive]\nenum r#EngineError {";
    if generated.contains(needle) && !generated.contains(marked) {
        std::fs::write(&path, generated.replace(needle, marked))
            .expect("scaffolding rewrite must succeed");
    }
}
