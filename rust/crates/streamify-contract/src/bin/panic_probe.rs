//! A6 (issue #148): the executable panic-policy probe — the runtime
//! witness for the per-profile panic contract documented in
//! `docs/architecture/engines/13_playback_dataplane.md` §8 and pinned
//! by `rust/Cargo.toml`'s `[profile.release] panic = "abort"`.
//!
//! The probe drives ONE deliberate panic through the same
//! [`ffi_shield`](crate::panic_shield_self_test) path every UniFFI
//! trampoline specifies, and its behavior IS the contract:
//!
//! * **unwind configurations** (dev / test / downstream binaries that
//!   override the strategy): the shield catches the panic, marshals it
//!   into `EngineError::Internal`, the probe prints
//!   `SHIELD_MARSHALED_PANIC` and exits 0 — the panic NEVER crosses
//!   the boundary as an unwind.
//! * **`panic = "abort"` configurations** (shipped release artifacts):
//!   the panic terminates the process at the panic site (SIGABRT on
//!   Unix) — the probe never reaches its exit path, and the fault
//!   surfaces through the platform crash reporter instead.
//!
//! The two legs are split at COMPILE time via `cfg(panic = "...")`,
//! which rustc sets from the actual panic strategy of this build —
//! the same flag the shield itself is compiled under, so the probe
//! cannot drift from the configuration it claims to witness.
//!
//! Verification recipes (both legs):
//!
//! ```text
//! # unwind leg (what CI's test profile executes via
//! # tests/test_panic_policy.rs):
//! cargo test --manifest-path rust/Cargo.toml -p streamify-contract \
//!     --test test_panic_policy
//!
//! # abort leg (release profile — expect SIGABRT / exit code 134):
//! cargo build --manifest-path rust/Cargo.toml --release \
//!     -p streamify-contract --bin panic_probe
//! rust/target/release/panic_probe; echo "exit=$?"
//! # -> the process aborts before printing anything; exit != 0.
//! ```

#[cfg(not(panic = "abort"))]
use streamify_contract::EngineError;

use streamify_contract::panic_shield_self_test;

fn main() {
    let marshaled = panic_shield_self_test();

    #[cfg(panic = "abort")]
    {
        // Unreachable in this configuration BY CONSTRUCTION: the panic
        // inside the shield aborted the process before it returned. If
        // this line ever runs, the abort policy silently regressed —
        // report it loudly (the contract test pins the profile so this
        // cannot ship unnoticed).
        let _ = marshaled;
        eprintln!("panic_probe: UNREACHABLE — abort-compiled probe returned from the shield");
        std::process::exit(3);
    }

    #[cfg(not(panic = "abort"))]
    {
        match marshaled {
            Err(EngineError::Internal { error_message, .. })
                if error_message.contains("panic under panic_shield_self_test") =>
            {
                // The marshaling path held: typed Internal error, panic
                // message + backtrace preserved, no unwind escaped.
                println!("SHIELD_MARSHALED_PANIC");
                std::process::exit(0);
            }
            other => {
                eprintln!("panic_probe: unexpected shield result: {other:?}");
                std::process::exit(2);
            }
        }
    }
}
