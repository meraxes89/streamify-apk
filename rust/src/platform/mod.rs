//! Platform capability adapters — Phase 5 (MP-CAP01..MP-CAP04).
//!
//! The bridge between the unified Rust/Native audio core and the OS
//! capability surfaces of the three desktop/mobile platforms:
//!
//!   * **MP-CAP01** — Windows System Media Transport Controls (SMTC):
//!     [`windows::smtc`] (pure-Rust WinRT projection, `windows` crate) —
//!     taskbar flyout + hardware media keys.
//!   * **MP-CAP02** — Linux MPRIS: [`linux::mpris`] (pure-Rust D-Bus via
//!     `zbus`, service `org.mpris.MediaPlayer2.streamify`) — GNOME shell
//!     media controls / KDE media player plasmoid / `playerctl`.
//!   * **MP-CAP03** — Apple `MPRemoteCommandCenter` +
//!     `MPNowPlayingInfoCenter`: [`apple::remote_command`] (Rust core +
//!     the `native/os/apple_remote_command.mm` C-ABI bridge, resolved at
//!     runtime like the Phase 4b PipeWire dlopen driver).
//!   * **MP-CAP04** — secure storage providers: [`secure_store`]
//!     (`SecureStoreProvider` trait; Windows DPAPI, FreeDesktop Secret
//!     Service, Apple Keychain, and the UniFFI foreign delegate for
//!     Android `EncryptedSharedPreferences`/KeyStore).
//!
//! # Architecture (the Phase 4b discipline, applied to control surfaces)
//!
//! Every adapter is split the same way the desktop audio HALs were:
//!
//!   * a **host-testable core** — pure Rust, compiled on every target,
//!     exercising command mapping, state projection and rate limiting
//!     ([`media_sync`]) — audited headlessly on every CI runner; and
//!   * a **platform glue shell** — cfg-gated to exactly one OS
//!     (`#[cfg(target_os = ...)]`), holding only the foreign API calls.
//!
//! The shells are further abstracted behind small surface traits
//! ([`windows::SmtcSurface`], [`apple::AppleMediaCenter`]) so the test
//! doubles in [`testing`] substitute the OS surface, not the logic, on
//! non-matching hosts — "mock WinRT/Keychain/D-Bus harnesses for
//! non-privileged CI execution" (Phase 5 verification contract §1).
//!
//! # Command flow (the mission's "MediaCommandSource dispatcher")
//!
//! OS surfaces push hardware/OS media intents into the engine through
//! the [`MediaCommandSink`] trait — implemented by
//! [`crate::contract::StreamifyEngine`] via its debounced
//! `on_media_command` entry point (MP-C03, the 50-150 ms duplicate
//! window). Adapters never mutate engine state directly; they are pure
//! command sources, so the engine keeps its single-writer discipline.
//!
//! # Foreign shell surface (Beta's lane)
//!
//! The UniFFI object `OsMediaIntegration` (declared in
//! `src/streamify.udl`, implemented here) is the one facade the desktop
//! and mobile shells drive: `start(window_handle)` attaches the
//! platform surface (gracefully degrading to a headless no-op when the
//! OS surface is unavailable — services, CI sessions, headless hosts),
//! `update_playback`/`update_metadata` mirror engine state outward
//! (rate-limited per surface), and commands flow back into the engine
//! automatically. The secure-store delegate (`SecureStoreDelegate`
//! callback) is installed through the `set_secure_store_delegate`
//! namespace function.

pub mod adapter;
pub mod media_sync;
pub mod secure_store;
pub mod testing;

#[cfg(target_os = "windows")]
pub mod windows;

#[cfg(target_os = "linux")]
pub mod linux;

#[cfg(any(target_os = "macos", target_os = "ios"))]
pub mod apple;

use crate::contract::{EngineError, MediaCommand, PlaybackState, StreamifyEngine};
use std::sync::Arc;
use std::time::Duration;

/// One-second floor between Apple now-playing pushes (MP-CAP03: the
/// directive's hard 1 Hz ceiling for `MPNowPlayingInfoCenter`).
pub const APPLE_NOW_PLAYING_MIN_INTERVAL: Duration = Duration::from_millis(1000);

/// Coalescing floor between SMTC timeline/property pushes. 200 ms (5 Hz)
/// keeps the Windows flyout smooth while staying far below any COM
/// apartment flooding concern (MP-CAP01: "rate-limited to avoid COM
/// thread flooding").
pub const SMTC_SYNC_MIN_INTERVAL: Duration = Duration::from_millis(200);

/// Coalescing floor between MPRIS `PropertiesChanged` bursts. The
/// FreeDesktop guidance asks players not to spam the session bus; 200 ms
/// matches what well-behaved players (VLC, mpv) use.
pub const MPRIS_SYNC_MIN_INTERVAL: Duration = Duration::from_millis(200);

/// Dispatch contract for OS-sourced media commands (the mission's
/// "MediaCommandSource dispatcher" wiring point).
///
/// An OS adapter maps its platform media-key surface onto
/// [`MediaCommand`] variants and pushes them into a sink. The canonical
/// sink is [`StreamifyEngine`] (its `on_media_command` applies the
/// MP-C03 50-150 ms hardware debounce); tests substitute
/// [`testing::RecordingCommandSink`] to observe the exact command
/// stream — including debounce behavior — without an engine.
pub trait MediaCommandSink: Send + Sync {
    /// Offer one OS-sourced command. Returns `Ok(true)` when applied,
    /// `Ok(false)` when the sink swallowed it (e.g. the engine's
    /// duplicate debounce), `Err` on engine faults.
    fn dispatch_media_command(&self, command: MediaCommand) -> Result<bool, EngineError>;
}

impl MediaCommandSink for StreamifyEngine {
    fn dispatch_media_command(&self, command: MediaCommand) -> Result<bool, EngineError> {
        // The engine's own entry point (contract.rs, MP-C03): applies
        // the mandated 50-150 ms duplicate debounce + panic shield.
        self.on_media_command(command)
    }
}

/// Which OS media surface is currently bound to an
/// [`OsMediaIntegration`] instance.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaSurfaceKind {
    /// Windows SMTC (`ISystemMediaTransportControls`) is attached.
    Smtc,
    /// Linux MPRIS D-Bus service `org.mpris.MediaPlayer2.streamify`.
    Mpris,
    /// Apple `MPRemoteCommandCenter` / `MPNowPlayingInfoCenter` bridge.
    AppleRemoteCommand,
    /// No OS surface: headless/no-session graceful fallback (updates
    /// are no-ops, commands cannot arrive) — never an error state.
    HeadlessFallback,
    /// Not started (or stopped): no surface bound yet.
    Inactive,
}

impl MediaSurfaceKind {
    /// Stable identifier string for the UniFFI surface (shell
    /// diagnostics / telemetry labels).
    pub fn surface_id(&self) -> &'static str {
        match self {
            MediaSurfaceKind::Smtc => "smtc",
            MediaSurfaceKind::Mpris => "mpris",
            MediaSurfaceKind::AppleRemoteCommand => "apple-remote-command",
            MediaSurfaceKind::HeadlessFallback => "headless-fallback",
            MediaSurfaceKind::Inactive => "inactive",
        }
    }
}

/// Unified playback projection pushed outward to OS surfaces.
///
/// Deliberately a flat snapshot (not the full `EngineSnapshot`): OS
/// media surfaces only ever render play state, timeline, volume and
/// now-playing metadata. [`crate::contract::PlaybackState`] maps onto
/// [`media_sync::PlaybackSyncState`] at the boundary.
#[derive(Debug, Clone, PartialEq)]
pub struct OsPlaybackUpdate {
    /// Engine lifecycle state at push time.
    pub state: PlaybackState,
    /// Track position, milliseconds from track start.
    pub position_ms: u64,
    /// Track duration, milliseconds (0 when unknown).
    pub duration_ms: u64,
    /// Playback rate (1.0 = normal; 0.0 while paused).
    pub rate: f32,
    /// Output volume `[0.0, 1.0]` (mirrors the `EngineSnapshot`
    /// volume; NaN fails safe to 0 like every volume entry point).
    pub volume: f32,
}

/// Unified now-playing metadata projection pushed outward.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct OsMetadataUpdate {
    /// Track title.
    pub title: String,
    /// Primary artist line.
    pub artist: String,
    /// Album name, when known.
    pub album: Option<String>,
    /// Track duration, milliseconds (0 when unknown).
    pub duration_ms: u64,
    /// Album-art URL (MPRIS `mpris:artUrl`; SMTC/Apple render it
    /// asynchronously), when known.
    pub art_url: Option<String>,
}

/// The one facade the desktop and mobile shells drive (Beta's lane):
/// binds the platform media surface, mirrors engine state outward
/// (rate-limited per surface) and routes OS transport-control events
/// back into the engine through the debounced command dispatcher.
///
/// Headless-safe by contract (MP-CAP01 "fall back gracefully when
/// running headlessly or under non-interactive sessions"): when the
/// platform surface cannot attach — no window handle on Windows,
/// headless Linux session without a D-Bus address, foreign host —
/// `start` returns `Ok(false)`, `active_surface()` reports
/// `HeadlessFallback`, and every update is a no-op. Nothing in this
/// object panics across the boundary (the UniFFI scaffolding shield
/// marshals residual panics into `EngineError::Internal`, MP-C06).
///
/// The implementation is compiled on EVERY target (including Android,
/// where the master plan assigns media sessions to Beta's Media3 shell)
/// so the UDL surface stays uniform; on a host with no adapter module
/// the constructor still succeeds and `start` degrades to the
/// headless fallback.
pub struct OsMediaIntegration {
    /// The command sink every platform adapter dispatches through.
    /// Dead on hosts with no adapter module (Android: Beta's Media3
    /// shell owns media sessions there) — kept unconditionally so the
    /// constructor contract is uniform across targets.
    #[cfg_attr(
        not(any(
            target_os = "windows",
            target_os = "linux",
            target_os = "macos",
            target_os = "ios"
        )),
        allow(dead_code)
    )]
    engine: Arc<StreamifyEngine>,
    state: std::sync::Mutex<IntegrationState>,
}

/// Interior binding state. One surface at a time per integration
/// object: `start` replaces any previous binding (mirroring the engine's
/// own single-active-surface reality). The adapters are boxed to keep
/// the enum (and the `Mutex<IntegrationState>` it lives in) small —
/// `SurfaceAdapter<S>` carries the full sync core inline.
enum IntegrationState {
    /// No surface bound (before start / after stop).
    Inactive,
    #[cfg(target_os = "windows")]
    Smtc(Box<crate::platform::windows::smtc::SmtcAdapter>),
    #[cfg(target_os = "linux")]
    Mpris(Box<crate::platform::linux::mpris::MprisAdapter>),
    #[cfg(any(target_os = "macos", target_os = "ios"))]
    Apple(Box<crate::platform::apple::remote_command::AppleRemoteCommandAdapter>),
    /// `start` ran in a situation with no OS surface: updates become
    /// no-ops, commands never arrive. NOT an error (graceful headless
    /// degradation, MP-CAP01).
    Headless,
}

impl OsMediaIntegration {
    /// Create an integration bound to one engine handle. The engine is
    /// both the command sink (OS transport controls flow into
    /// `on_media_command`) and the canonical source for playback state
    /// (the shell mirrors `EngineSnapshot` pushes out through
    /// `update_playback`/`update_metadata`).
    pub fn new(engine: Arc<StreamifyEngine>) -> Self {
        Self {
            engine,
            state: std::sync::Mutex::new(IntegrationState::Inactive),
        }
    }

    /// Attach the platform media surface (UDL: `start`).
    ///
    /// * Windows: `window_handle` is the shell's top-level `HWND`
    ///   (`ISystemMediaTransportControlsInterop::GetForWindow` requires
    ///   one). `0` = no window → headless fallback.
    /// * Linux: registers `org.mpris.MediaPlayer2.streamify` on the
    ///   session bus (requires `DBUS_SESSION_BUS_ADDRESS` or
    ///   `$XDG_RUNTIME_DIR/bus`; absent → headless fallback, never a
    ///   crash). `window_handle` is ignored.
    /// * macOS/iOS: subscribes `MPRemoteCommandCenter` through the
    ///   `apple_remote_command.mm` bridge when the host process linked
    ///   it; otherwise headless fallback. `window_handle` is ignored.
    ///
    /// Returns `Ok(true)` when a real OS surface attached, `Ok(false)`
    /// for the headless fallback. Only genuine initialization faults
    /// (a desktop session where the surface attached but half-failed)
    /// return `Err`.
    #[cfg_attr(not(target_os = "windows"), allow(unused_variables))]
    pub fn start(&self, window_handle: u64) -> Result<bool, EngineError> {
        let mut guard = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // Replace any previous binding: dropping the old adapter runs
        // its unbind path (event token release / bus name release /
        // command-center teardown).
        *guard = IntegrationState::Inactive;

        #[cfg(target_os = "windows")]
        {
            let adapter = crate::platform::windows::smtc::attach_to_window(
                self.engine.clone(),
                window_handle,
            );
            if adapter.is_surface_bound() {
                *guard = IntegrationState::Smtc(Box::new(adapter));
                Ok(true)
            } else {
                // No HWND / non-interactive session: SMTC explicitly
                // cannot attach. Degrade to headless, never crash.
                *guard = IntegrationState::Headless;
                Ok(false)
            }
        }

        #[cfg(target_os = "linux")]
        {
            let adapter = crate::platform::linux::mpris::start(self.engine.clone());
            let live = adapter.is_surface_bound();
            // Store the adapter only when it carries a live service; a
            // headless host gets the honest HeadlessFallback marker
            // (an unbound adapter would no-op identically, but the
            // surface id would lie).
            if live {
                *guard = IntegrationState::Mpris(Box::new(adapter));
            } else {
                *guard = IntegrationState::Headless;
            }
            Ok(live)
        }

        #[cfg(any(target_os = "macos", target_os = "ios"))]
        {
            let adapter =
                crate::platform::apple::remote_command::attach_to_media_center(
                    self.engine.clone(),
                );
            if adapter.is_surface_bound() {
                *guard = IntegrationState::Apple(Box::new(adapter));
                Ok(true)
            } else {
                *guard = IntegrationState::Headless;
                Ok(false)
            }
        }

        #[cfg(not(any(
            target_os = "windows",
            target_os = "linux",
            target_os = "macos",
            target_os = "ios"
        )))]
        {
            let _ = window_handle;
            *guard = IntegrationState::Headless;
            Ok(false)
        }
    }

    /// Detach the platform surface (UDL: `stop`). Idempotent: stopping
    /// an inactive/headless integration is a no-op `Ok(())`.
    pub fn stop(&self) -> Result<(), EngineError> {
        let mut guard = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // Drop-in-place of the old binding runs its unbind path.
        *guard = IntegrationState::Inactive;
        Ok(())
    }

    /// Mirror one engine playback state outward (UDL:
    /// `update_playback`). Rate-limited per surface (200 ms SMTC/MPRIS,
    /// 1 Hz Apple). No-op in the headless fallback — and never an
    /// error, because a surface going away mid-session (e.g. the D-Bus
    /// daemon restarting) must not surface as an engine fault: the
    /// adapter logs and swallows.
    pub fn update_playback(
        &self,
        state: PlaybackState,
        position_ms: u64,
        duration_ms: u64,
        rate: f32,
        volume: f32,
    ) -> Result<(), EngineError> {
        let guard = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let update = OsPlaybackUpdate {
            state,
            position_ms,
            duration_ms,
            rate,
            volume,
        };
        match &*guard {
            #[cfg(target_os = "windows")]
            IntegrationState::Smtc(adapter) => adapter.update_playback(&update),
            #[cfg(target_os = "linux")]
            IntegrationState::Mpris(adapter) => adapter.update_playback(&update),
            #[cfg(any(target_os = "macos", target_os = "ios"))]
            IntegrationState::Apple(adapter) => adapter.update_playback(&update),
            // Headless / inactive no-op (the explicit consume keeps
            // unused-variable warnings quiet on adapter-less hosts).
            IntegrationState::Headless | IntegrationState::Inactive => {
                let _ = &update;
                Ok(())
            }
        }
    }

    /// Mirror now-playing metadata outward (UDL: `update_metadata`).
    /// New-track identity changes (title/artist/album/duration) bypass
    /// the timeline rate limit so the OS chrome switches tracks
    /// immediately; art/position refreshes share the surface's cadence.
    pub fn update_metadata(
        &self,
        title: String,
        artist: String,
        album: Option<String>,
        duration_ms: u64,
        art_url: Option<String>,
    ) -> Result<(), EngineError> {
        let guard = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let update = OsMetadataUpdate {
            title,
            artist,
            album,
            duration_ms,
            art_url,
        };
        match &*guard {
            #[cfg(target_os = "windows")]
            IntegrationState::Smtc(adapter) => adapter.update_metadata(update.clone()),
            #[cfg(target_os = "linux")]
            IntegrationState::Mpris(adapter) => adapter.update_metadata(update.clone()),
            #[cfg(any(target_os = "macos", target_os = "ios"))]
            IntegrationState::Apple(adapter) => adapter.update_metadata(update),
            IntegrationState::Headless | IntegrationState::Inactive => {
                let _ = &update;
                Ok(())
            }
        }
    }

    /// Currently bound surface kind (UDL: `active_surface`), as its
    /// stable id string (`"smtc"`, `"mpris"`, `"apple-remote-command"`,
    /// `"headless-fallback"`, `"inactive"`).
    pub fn active_surface(&self) -> String {
        let guard = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let kind = match &*guard {
            #[cfg(target_os = "windows")]
            IntegrationState::Smtc(adapter) => {
                if adapter.is_surface_bound() {
                    MediaSurfaceKind::Smtc
                } else {
                    MediaSurfaceKind::HeadlessFallback
                }
            }
            #[cfg(target_os = "linux")]
            IntegrationState::Mpris(adapter) => {
                if adapter.is_surface_bound() {
                    MediaSurfaceKind::Mpris
                } else {
                    MediaSurfaceKind::HeadlessFallback
                }
            }
            #[cfg(any(target_os = "macos", target_os = "ios"))]
            IntegrationState::Apple(adapter) => {
                if adapter.is_surface_bound() {
                    MediaSurfaceKind::AppleRemoteCommand
                } else {
                    MediaSurfaceKind::HeadlessFallback
                }
            }
            IntegrationState::Headless => MediaSurfaceKind::HeadlessFallback,
            IntegrationState::Inactive => MediaSurfaceKind::Inactive,
        };
        kind.surface_id().to_string()
    }
}

