//! A6 (issue #148, AHEAD.md WS-A): the panic-policy contract tests.
//!
//! The release profile ships `panic = "abort"` (an approved consensus —
//! MP-C06/MP-C07, master plan invariant 8: a panic in a shipped library
//! must NEVER unwind into the host process, and abort-fail-fast routes
//! the fault through the platform crash reporter). That makes the
//! `catch_unwind` FFI shields release-inert BY DESIGN — so the panic
//! contract is per-profile, and BOTH halves are pinned here:
//!
//!   1. `shield_marshals_panics_under_unwind_configurations` — runs the
//!      executable probe (`src/bin/panic_probe.rs`, the same crate, so
//!      `CARGO_BIN_EXE_panic_probe` resolves) and asserts the unwind
//!      leg: the shield catches the deliberate panic and marshals it
//!      into `EngineError::Internal` (message + backtrace), exit 0,
//!      `SHIELD_MARSHALED_PANIC` on stdout. This is the configuration
//!      every dev/test build (and any downstream binary that overrides
//!      the strategy back to unwind) runs under.
//!   2. `release_profile_pins_panic_abort_for_shipped_artifacts` —
//!      asserts the OTHER leg's precondition: the workspace
//!      `[profile.release]` really carries `panic = "abort"`. The abort
//!      behavior itself is compile-time semantics (`cfg(panic = "abort")`
//!      splits the probe's legs; a panic under abort cannot return), so
//!      pinning the profile pins the behavior: removing the abort
//!      policy — which would silently change how shipped artifacts die
//!      — fails this test until the RFC and AHEAD.md are updated with
//!      the conscious decision.
//!
//! The full per-profile semantics, the decision record and the local
//! verification recipe for the abort leg live in
//! `docs/architecture/engines/13_playback_dataplane.md` §8:
//!
//! ```text
//! cargo build --manifest-path rust/Cargo.toml --release \
//!     -p streamify-contract --bin panic_probe
//! rust/target/release/panic_probe; echo "exit=$?"   # aborts, exit != 0
//! ```

use std::fs;
use std::path::Path;
use std::process::Command;

/// The unwind leg: the probe binary (built in the SAME configuration
/// this test runs under — dev/test, unwinding) must prove the shield
/// marshaled the deliberate panic instead of letting it escape.
#[test]
fn shield_marshals_panics_under_unwind_configurations() {
    let probe = env!("CARGO_BIN_EXE_panic_probe");
    let output = Command::new(probe)
        .output()
        .expect("panic_probe bin must be built alongside this test");
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    assert!(
        output.status.success(),
        "probe must exit 0 when the shield marshals the panic; \
         status={:?} stdout={stdout:?} stderr={stderr:?}",
        output.status.code()
    );
    assert!(
        stdout.contains("SHIELD_MARSHALED_PANIC"),
        "probe must print the marshaling marker; stdout={stdout:?} stderr={stderr:?}"
    );
}

/// The abort leg's precondition: the shipped-artifact profile really
/// compiles with `panic = "abort"`. Combined with the probe's
/// `cfg(panic = "abort")` split (a panic under abort cannot return),
/// pinning the profile pins the shipped behavior.
///
/// Parsing rule: the `panic = "abort"` line must appear INSIDE the
/// `[profile.release]` section (sections start at column 0 with
/// `[profile.` and end at the next column-0 `[`), so a future
/// `[profile.release-mobile]`-style split cannot satisfy this test by
/// accident.
#[test]
fn release_profile_pins_panic_abort_for_shipped_artifacts() {
    // CARGO_MANIFEST_DIR = rust/crates/streamify-contract; the profile
    // under test lives at the workspace root, two levels up.
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../Cargo.toml")
        .canonicalize()
        .expect("workspace Cargo.toml must exist");
    let text = fs::read_to_string(&manifest).expect("workspace Cargo.toml must be readable");

    let mut in_release = false;
    let mut saw_abort = false;
    for line in text.lines() {
        let line = line.trim_end();
        if line.starts_with('[') {
            in_release = line.trim() == "[profile.release]";
            continue;
        }
        if in_release {
            let compact = line.replace(' ', "");
            if compact == r#"panic="abort""# {
                saw_abort = true;
            }
        }
    }
    assert!(
        saw_abort,
        "the release profile must pin panic = \"abort\" for shipped artifacts \
         (MP-C06/MP-C07 consensus). If this policy is being changed, update \
         docs/architecture/engines/13_playback_dataplane.md §8 and AHEAD.md \
         A6 in the same commit — do not let the shipped crash semantics \
         drift silently."
    );
}
