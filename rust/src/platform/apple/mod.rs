//! Apple platform capability adapters (Phase 5).
//!
//! MP-CAP03 lives in [`remote_command`]: the
//! `MPRemoteCommandCenter` + `MPNowPlayingInfoCenter` bridge —
//! Rust core (this module) + the Objective-C++ glue in
//! `native/os/apple_remote_command.mm` behind the C ABI of
//! `native/include/apple_remote_command.h`.
//!
//! The bridge is resolved at RUNTIME (dlsym, `RTLD_DEFAULT`) — the
//! same zero-link-dependency discipline as the Phase 4b PipeWire
//! dlopen driver — so:
//!
//!   * the iOS XCFramework links the `.mm` object alongside the Rust
//!     staticlib (final app link resolves everything), and
//!   * the macOS desktop cdylib never carries an undefined-symbol
//!     dependency on a library the JNA host may not have linked;
//!     when the host process DID link the CMake
//!     `streamify_apple_remote_command` static lib, the symbols are
//!     simply there.
//!
//! Unresolved symbols = headless no-op fallback (never a crash) —
//! which is also what every non-Apple host lane sees.

pub mod remote_command;
