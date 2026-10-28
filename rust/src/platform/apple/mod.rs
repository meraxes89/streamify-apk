//! Apple platform capability adapters (Phase 5).
//!
//! MP-CAP03 lives in [`remote_command`]: the
//! `MPRemoteCommandCenter` + `MPNowPlayingInfoCenter` bridge —
//! Rust core (this module) + the Objective-C++ glue in
//! `native/os/apple_remote_command.mm` behind the C ABI of
//! `native/include/apple_remote_command.h`.
//!
//! MP-UI02 lives in [`ios_audio_session`] (iOS only): the
//! `AVAudioSession` adapter — Rust policy core
//! (`crate::platform::ios_audio_session`, host-testable on every lane)
//! + the Objective-C++ glue in `native/audio/ios_audio_session.mm`
//! behind the C ABI of `native/include/ios_audio_session.h`. On iOS the
//! remote-command surface binds it alongside its own subscription
//! (see `remote_command::AppleBridgeSurface::bind`), so one
//! `OsMediaIntegration::start` wires the whole Apple media stack.
//!
//! The bridges are resolved at RUNTIME (dlsym, `RTLD_DEFAULT`) — the
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

#[cfg(target_os = "ios")]
pub mod ios_audio_session;
