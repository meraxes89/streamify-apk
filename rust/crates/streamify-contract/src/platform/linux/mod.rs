//! Linux platform capability adapters (Phase 5).
//!
//! MP-CAP02 lives in [`mpris`]: the FreeDesktop MPRIS D-Bus adapter —
//! service `org.mpris.MediaPlayer2.streamify`, interfaces
//! `org.mpris.MediaPlayer2` (Root) and `org.mpris.MediaPlayer2.Player`
//! at `/org/mpris/MediaPlayer2`, speaking to GNOME Shell / KDE
//! plasmoids / `playerctl` / lock screens.
//!
//! Pure-Rust end to end: zbus implements the D-Bus wire protocol
//! in-process (no libdbus link dependency — the Phase 5 mandate), and
//! the connection rides the engine's shared tokio runtime.

pub mod mpris;
