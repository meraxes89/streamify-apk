//! Generic OS-surface adapter core — Phase 5 (MP-CAP01..03 shared half).
//!
//! [`SurfaceAdapter`] is the one outbound-sync algorithm every platform
//! adapter shares, generic over an [`OsMediaSurface`] (the thin foreign
//! glue shell):
//!
//! * inbound (OS → engine): each surface implementation translates its
//!   native event (WinRT `ButtonPressed`, an MPRIS method call, the
//!   Apple C-ABI callback) into a [`crate::contract::MediaCommand`]
//!   and hands it to its own [`MediaCommandSink`] clone — surfaces own
//!   the inbound path directly, no adapter round-trip, no lock on the
//!   outbound state (headless-safe: a surface that is not bound simply
//!   produces no events);
//! * outbound (engine → OS): the adapter merges playback updates with
//!   the retained metadata image, runs them through the
//!   [`crate::platform::media_sync::PlaybackSyncCore`] rate limiter
//!   (200 ms SMTC/MPRIS, 1 Hz Apple) and pushes the NEWEST snapshot
//!   when the cadence window opens — timeline jitter coalesces,
//!   track-identity changes and pause/stop clear the OS chrome
//!   immediately.
//!
//! The mock surfaces in [`crate::platform::testing`] implement
//! [`OsMediaSurface`], so this exact algorithm is unit-audited on every
//! CI host (the "mock WinRT / Keychain / D-Bus harnesses" of the
//! Phase 5 verification contract) — only the glue shells differ per
//! target.

use crate::contract::{EngineError, MediaCommand};
use crate::platform::media_sync::{playback_sync_state, NowPlayingSnapshot, PlaybackSyncCore};
use crate::platform::{MediaCommandSink, OsMetadataUpdate, OsPlaybackUpdate};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The retained now-playing metadata image (outbound state).
///
/// `track_key` is the deterministic identity hash of
/// `(title, artist, album, duration_ms)` — see [`track_identity_key`].
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct OsMetadataImage {
    /// Track title.
    pub title: String,
    /// Primary artist line.
    pub artist: String,
    /// Album name, when known.
    pub album: Option<String>,
    /// Album-art URL, when known.
    pub art_url: Option<String>,
    /// Deterministic track identity (0 until the first metadata push).
    pub track_key: u64,
}

/// Deterministic 64-bit track identity: BLAKE3 of the identity tuple,
/// truncated. BLAKE3 (not `DefaultHasher`) because the value MUST be
/// stable across processes and releases — MPRIS `SetPosition` matching
/// compares the track id the surface published last.
pub fn track_identity_key(update: &OsMetadataUpdate) -> u64 {
    let mut hasher = blake3::Hasher::new();
    hasher.update(update.title.as_bytes());
    hasher.update(&[0]);
    hasher.update(update.artist.as_bytes());
    hasher.update(&[0]);
    hasher.update(update.album.as_deref().unwrap_or("").as_bytes());
    hasher.update(&[0]);
    hasher.update(&update.duration_ms.to_le_bytes());
    let hash = hasher.finalize();
    let bytes: [u8; 8] = hash.as_bytes()[..8].try_into().expect("blake3 is 32 bytes");
    u64::from_le_bytes(bytes)
}

/// The outbound half of one OS media surface (the "thin platform glue
/// shell" of the Phase 4b discipline): render the newest now-playing
/// image onto the OS chrome.
///
/// Implementations MUST be internally synchronized (`Send + Sync`):
/// pushes arrive from shell threads, binds/unbinds from lifecycle
/// paths. They must NOT panic — a surface fault is reported as
/// `EngineError::Audio/Storage`-class data, and the adapter degrades
/// to unbound rather than poisoning the process.
pub trait OsMediaSurface: Send + Sync {
    /// Human-readable surface name for diagnostics (`active_surface`
    /// reporting, tracing).
    fn surface_name(&self) -> &'static str;

    /// Bind to the OS surface. Returns `Ok(false)` when the surface is
    /// unavailable in this environment (headless host, no session bus,
    /// bridge not linked) — the graceful-fallback signal, NEVER an
    /// error. `Err` is reserved for "binding half-failed" faults.
    fn bind(&self) -> Result<bool, EngineError>;

    /// Unbind (release the event registration / bus name / command
    /// subscription). Idempotent.
    fn unbind(&self);

    /// Whether the surface is currently bound.
    fn is_bound(&self) -> bool;

    /// Push the newest playback snapshot (state + timeline). Called at
    /// the adapter's cadence, never per-frame.
    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError>;

    /// Push the now-playing metadata image (new track / art refresh).
    /// Called on identity changes (immediately) and art refreshes (at
    /// cadence).
    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError>;
}

/// Shared-surface delegation: an `Arc<S>` is a surface, so hosts that
/// keep their own handle (the mock recording surfaces in
/// [`crate::platform::testing`], a shell-owned surface) hand the
/// adapter the same instance they retain.
impl<S: OsMediaSurface> OsMediaSurface for std::sync::Arc<S> {
    fn surface_name(&self) -> &'static str {
        (**self).surface_name()
    }

    fn bind(&self) -> Result<bool, EngineError> {
        (**self).bind()
    }

    fn unbind(&self) {
        (**self).unbind()
    }

    fn is_bound(&self) -> bool {
        (**self).is_bound()
    }

    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError> {
        (**self).push_playback(snapshot)
    }

    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError> {
        (**self).push_metadata(image)
    }
}

/// The shared outbound-sync adapter over one [`OsMediaSurface`].
///
/// Construct through the platform modules
/// ([`crate::platform::windows::smtc`], [`crate::platform::linux::mpris`],
/// [`crate::platform::apple::remote_command`]) or with a mock surface
/// from [`crate::platform::testing`] — the algorithm below is the same
/// everywhere and audited on every host.
pub struct SurfaceAdapter<S: OsMediaSurface> {
    surface: S,
    sync: PlaybackSyncCore,
    metadata: Mutex<OsMetadataImage>,
    bound: AtomicBool,
}

impl<S: OsMediaSurface> SurfaceAdapter<S> {
    /// Bind `surface` and arm the sync core at `min_interval`.
    /// Returns the adapter regardless of bind outcome — check
    /// [`Self::is_surface_bound`]: an unbound adapter is the headless
    /// no-op (all updates Ok-no-op, no events), per MP-CAP01's
    /// graceful-fallback mandate.
    pub fn attach(surface: S, min_interval: Duration) -> Self {
        let bound = matches!(surface.bind(), Ok(true));
        if !bound {
            tracing::info!(
                target: "streamify::platform",
                surface = surface.surface_name(),
                "OS media surface unavailable — headless no-op fallback active"
            );
        }
        Self {
            surface,
            sync: PlaybackSyncCore::new(min_interval),
            metadata: Mutex::new(OsMetadataImage::default()),
            bound: AtomicBool::new(bound),
        }
    }

    /// Whether the OS surface is live.
    pub fn is_surface_bound(&self) -> bool {
        self.bound.load(Ordering::Acquire) && self.surface.is_bound()
    }

    /// Surface name (diagnostics).
    pub fn surface_name(&self) -> &'static str {
        self.surface.surface_name()
    }

    /// Mirror one playback update outward. No-op when unbound; a
    /// surface push fault mid-session degrades to unbound (logged),
    /// never an engine error — the music must keep playing when the
    /// taskbar flyout hiccups.
    pub fn update_playback(&self, update: &OsPlaybackUpdate) -> Result<(), EngineError> {
        if !self.is_surface_bound() {
            return Ok(());
        }
        let snapshot = self.compose_snapshot(update);
        let push =
            self.sync.offer(snapshot.clone()) == crate::platform::media_sync::SyncDecision::Push;
        if push {
            if let Err(err) = self.surface.push_playback(&snapshot) {
                self.degrade(&err);
            }
        }
        Ok(())
    }

    /// Mirror a metadata update outward. An identity change (new
    /// track) forces both a metadata push AND an immediate playback
    /// push so the OS chrome swaps tracks atomically; art-only or
    /// text-only refreshes ride the cadence.
    pub fn update_metadata(&self, update: OsMetadataUpdate) -> Result<(), EngineError> {
        if !self.is_surface_bound() {
            return Ok(());
        }
        let track_key = track_identity_key(&update);
        let image = OsMetadataImage {
            title: update.title,
            artist: update.artist,
            album: update.album,
            art_url: update.art_url,
            track_key,
        };
        let mut metadata = self
            .metadata
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let identity_changed = metadata.track_key != track_key;
        *metadata = image;

        if identity_changed {
            // New track: swap the metadata immediately and re-push the
            // timeline so the scrubber belongs to the new track.
            if let Err(err) = self.surface.push_metadata(&metadata) {
                self.degrade(&err);
                return Ok(());
            }
            if let Some(snapshot) = self.sync.current() {
                // The stored snapshot still carries the OLD track key;
                // retag it so the identity-change push stays coherent.
                let retagged = NowPlayingSnapshot {
                    track_key,
                    ..snapshot
                };
                self.sync.offer(retagged.clone());
                if let Err(err) = self.surface.push_playback(&retagged) {
                    self.degrade(&err);
                }
            }
        }
        Ok(())
    }

    /// The newest retained outward image (what the next cadence
    /// window will push) — test/telemetry surface.
    pub fn retained_snapshot(&self) -> Option<NowPlayingSnapshot> {
        self.sync.current()
    }

    /// The sink-less teardown: release the OS registration.
    pub fn stop(&self) {
        self.surface.unbind();
        self.bound.store(false, Ordering::Release);
    }

    /// Compose the outward snapshot from one update + retained
    /// metadata identity.
    fn compose_snapshot(&self, update: &OsPlaybackUpdate) -> NowPlayingSnapshot {
        let metadata = self
            .metadata
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // f32 rate → per-mille with NaN/∞ failing safe to paused (0).
        let rate_permille = if update.rate.is_finite() {
            (update.rate.clamp(0.0, 4.0) * 1000.0) as u32
        } else {
            0
        };
        // Volume: same clamp semantics as the engine's set_volume
        // (NaN fails safe to full mute — MP-C03 parity).
        let volume_permille = if update.volume.is_finite() {
            (update.volume.clamp(0.0, 1.0) * 1000.0) as u32
        } else {
            0
        };
        NowPlayingSnapshot {
            state: playback_sync_state(update.state),
            position_ms: update.position_ms,
            duration_ms: update.duration_ms,
            rate_permille,
            volume_permille,
            track_key: metadata.track_key,
        }
    }

    /// A surface fault mid-session: unbind + log. The adapter becomes
    /// the headless no-op; the shell can restart it via
    /// `OsMediaIntegration::start`.
    fn degrade(&self, err: &EngineError) {
        tracing::warn!(
            target: "streamify::platform",
            surface = self.surface.surface_name(),
            error = %err,
            "OS media surface fault — degrading to headless no-op"
        );
        self.surface.unbind();
        self.bound.store(false, Ordering::Release);
    }
}

/// Inbound-command convenience shared by the surface implementations:
/// translate + dispatch through the engine's debounced entry point,
/// swallowing sink faults (a refused command is logged, never
/// propagated into a WinRT callback / D-Bus method / C-ABI callback
/// that cannot carry our error taxonomy).
pub fn dispatch_inbound(sink: &Arc<dyn MediaCommandSink>, command: MediaCommand) {
    match sink.dispatch_media_command(command) {
        Ok(true) => {}
        Ok(false) => {
            tracing::debug!(
                target: "streamify::platform",
                "OS media command swallowed by engine debounce"
            );
        }
        Err(err) => {
            tracing::warn!(
                target: "streamify::platform",
                error = %err,
                "OS media command rejected by engine"
            );
        }
    }
}

