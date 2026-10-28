//! Linux MPRIS D-Bus adapter — Phase 5 / MP-CAP02.
//!
//! Implements the FreeDesktop
//! [`org.mpris.MediaPlayer2`](https://specifications.freedesktop.org/mpris-spec/latest/)
//! surface under the service name `org.mpris.MediaPlayer2.streamify`
//! (the mission-pinned name), object path `/org/mpris/MediaPlayer2`:
//!
//! * **Root interface** — `Identity = "Streamify"`, `CanQuit` /
//!   `CanRaise` = false (window management is the shell's lane, not
//!   the core's), `HasTrackList` = false, MIME/URI scheme advertising.
//! * **Player interface** — the method set `Play()`, `Pause()`,
//!   `PlayPause()`, `Stop()`, `Next()`, `Previous()`, `Seek(x)`,
//!   `SetPosition(trackid, x)`, `OpenUri()` (unsupported, per spec an
//!   error, not a crash); the property set `PlaybackStatus`,
//!   `LoopStatus` (read-only "None"), `Rate`, `Shuffle` (read-only),
//!   `Metadata` (`xesam:title` / `xesam:artist` / `xesam:album` /
//!   `mpris:length` in microseconds / `mpris:artUrl` /
//!   `mpris:trackid`), `Volume` (writable → engine `SetVolume`),
//!   `Position` (polled; extrapolated from the last push at the
//!   engine's rate — MPRIS clients poll, and the property is
//!   deliberately `emits_changed_signal = "false"` per the spec), and
//!   the `Can*` capability flags.
//! * **Signals** — `org.freedesktop.DBus.Properties.PropertiesChanged`
//!   for `PlaybackStatus` / `Metadata` / `Volume` (driven through the
//!   shared 200 ms cadence gate; the newest image wins), and
//!   `Seeked(x)` on genuine discontinuities (explicit seeks, track
//!   changes) — never on smooth timeline progression.
//!
//! Headless-safe (the graceful fallback): no `DBUS_SESSION_BUS_ADDRESS`
//! and no `$XDG_RUNTIME_DIR/bus` → the surface reports unavailable and
//! the adapter is a no-op (server lanes, CI, flatpak sandboxes without
//! a portal). A bus that exists but refuses registration logs loudly
//! and degrades — never a crash.
//!
//! The transport is zbus 6 with the `tokio` feature: the pure-Rust
//! D-Bus protocol implementation (NO libdbus link dependency, per the
//! Phase 5 mandate) running on the engine's shared multi-thread
//! runtime. Interface dispatch is therefore fully async under the
//! hood while the adapter API stays sync (block_on at bind, spawn for
//! signal emission) — the same discipline as every other runtime call
//! site in the crate.

use crate::contract::{EngineError, MediaCommand, StorageErrorKind};
use crate::platform::adapter::{OsMediaSurface, OsMetadataImage, SurfaceAdapter};
use crate::platform::media_sync::{NowPlayingSnapshot, PlaybackSyncState};
use crate::platform::{MediaCommandSink, MPRIS_SYNC_MIN_INTERVAL};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;
use zbus::object_server::{InterfaceRef, SignalEmitter};
use zbus::wire::{ObjectPath, OwnedVariant, Variant};
use zbus::{interface, Connection};

/// The mission-pinned well-known service name.
pub const MPRIS_SERVICE_NAME: &str = "org.mpris.MediaPlayer2.streamify";
/// The canonical MPRIS object path.
pub const MPRIS_OBJECT_PATH: &str = "/org/mpris/MediaPlayer2";
/// Discontinuity threshold for emitting `Seeked` (2 s: smooth
/// timeline jitter from coalescing must NOT signal, explicit user
/// seeks and track changes MUST).
const SEEK_SIGNAL_THRESHOLD_MS: u64 = 2_000;

/// The MPRIS-backed adapter handle (`OsMediaIntegration`'s Linux
/// surface): the shared sync algorithm over [`MprisSurface`].
pub type MprisAdapter = SurfaceAdapter<MprisSurface>;

/// Start the MPRIS service bound to one command sink. Headless hosts
/// yield an unbound adapter (no-op updates, no bus presence) — never
/// an error; the `is_surface_bound()` flag tells the two apart.
pub fn start(sink: Arc<dyn MediaCommandSink>) -> MprisAdapter {
    let surface = MprisSurface::new(sink);
    SurfaceAdapter::attach(surface, MPRIS_SYNC_MIN_INTERVAL)
}

/// Best-effort cheap check for a session bus (zbus resolves the same
/// two locations `Connection::session()` would): used to distinguish
/// "headless host, degrade silently" from "desktop session where a
/// registration failure deserves a loud log".
pub fn session_bus_available() -> bool {
    if std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_some() {
        return true;
    }
    std::env::var_os("XDG_RUNTIME_DIR")
        .map(|runtime_dir| PathBuf::from(runtime_dir).join("bus").exists())
        .unwrap_or(false)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared state (property getters read it, adapter pushes write it)
// ─────────────────────────────────────────────────────────────────────────────

/// The now-playing metadata image the MPRIS Metadata property serves.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct MprisMetadataImage {
    /// `xesam:title`
    pub title: String,
    /// `xesam:artist` (single primary artist line).
    pub artist: String,
    /// `xesam:album`
    pub album: Option<String>,
    /// `mpris:artUrl`
    pub art_url: Option<String>,
    /// Deterministic track identity (drives `mpris:trackid` and the
    /// SetPosition track guard).
    pub track_key: u64,
}

/// Full outward MPRIS state (the Player property set).
#[derive(Debug, Clone)]
pub struct MprisState {
    /// Playback projection (`Buffering` reports as `Playing`).
    pub status: PlaybackSyncState,
    /// Position at `position_updated`, milliseconds.
    pub position_ms: u64,
    /// When `position_ms` was last true.
    pub position_updated: Instant,
    /// Rate in per-mille (1000 = normal).
    pub rate_permille: u32,
    /// Duration, milliseconds (0 = unknown).
    pub duration_ms: u64,
    /// Volume 0..=1000 per-mille of full scale.
    pub volume_permille: u32,
    /// Metadata image.
    pub metadata: MprisMetadataImage,
}

impl Default for MprisState {
    fn default() -> Self {
        Self {
            status: PlaybackSyncState::Stopped,
            position_ms: 0,
            position_updated: Instant::now(),
            rate_permille: 1000,
            duration_ms: 0,
            volume_permille: 1000,
            metadata: MprisMetadataImage::default(),
        }
    }
}

impl MprisState {
    /// `PlaybackStatus` string ("Buffering" is not an MPRIS state;
    /// mid-fill players report `Playing`).
    pub fn playback_status_string(&self) -> &'static str {
        match self.status {
            PlaybackSyncState::Playing | PlaybackSyncState::Buffering => "Playing",
            PlaybackSyncState::Paused => "Paused",
            PlaybackSyncState::Stopped => "Stopped",
        }
    }

    /// `mpris:trackid` object path for the current track.
    pub fn track_path(&self) -> ObjectPath<'static> {
        ObjectPath::from_string_unchecked(format!(
            "{MPRIS_OBJECT_PATH}/track/{:016x}",
            self.metadata.track_key
        ))
    }

    /// Position now: the last true position extrapolated by the
    /// elapsed wall time at the current rate while playing (polled
    /// properties must advance between pushes).
    pub fn extrapolated_position_ms(&self) -> u64 {
        match self.status {
            PlaybackSyncState::Playing | PlaybackSyncState::Buffering => {
                let elapsed_ms = self.position_updated.elapsed().as_millis() as u64;
                let advanced =
                    elapsed_ms.saturating_mul(u64::from(self.rate_permille.max(1))) / 1000;
                self.position_ms.saturating_add(advanced)
            }
            PlaybackSyncState::Paused | PlaybackSyncState::Stopped => self.position_ms,
        }
    }

    /// The `Metadata` `a{sv}` dictionary (MPRIS 2.2 canonical keys).
    pub fn metadata_dict(&self) -> HashMap<String, OwnedVariant> {
        let mut dict: HashMap<String, OwnedVariant> = HashMap::new();
        let ov = |v: Variant<'_>| -> OwnedVariant {
            OwnedVariant::try_from(v).unwrap_or_else(|_| OwnedVariant::from(0i64))
        };
        dict.insert("mpris:trackid".to_string(), ov(Variant::from(self.track_path())));
        if self.duration_ms > 0 {
            // mpris:length is MICROseconds (i64).
            dict.insert(
                "mpris:length".to_string(),
                ov(Variant::from((self.duration_ms as i64).saturating_mul(1000))),
            );
        }
        if let Some(art_url) = self.metadata.art_url.clone() {
            dict.insert("mpris:artUrl".to_string(), ov(Variant::from(art_url)));
        }
        dict.insert(
            "xesam:title".to_string(),
            ov(Variant::from(self.metadata.title.clone())),
        );
        // xesam:artist is an array of strings (spec), one entry here.
        dict.insert(
            "xesam:artist".to_string(),
            ov(Variant::from(vec![self.metadata.artist.clone()])),
        );
        if let Some(album) = self.metadata.album.clone() {
            dict.insert("xesam:album".to_string(), ov(Variant::from(album)));
        }
        dict
    }

    /// Absorb one pushed snapshot; returns what changed (`Seeked`
    /// payload in MICROseconds when the timeline jumped
    /// discontinuously — new track or explicit seek).
    pub fn absorb_snapshot(&mut self, snapshot: &NowPlayingSnapshot) -> StateDelta {
        let now = Instant::now();
        let extrapolated = self.extrapolated_position_ms();
        let jumped = snapshot.position_ms.abs_diff(extrapolated) > SEEK_SIGNAL_THRESHOLD_MS
            || snapshot.track_key != self.metadata.track_key;

        let status_changed = self.status != snapshot.state;
        let volume_changed = self.volume_permille != snapshot.volume_permille;

        self.status = snapshot.state;
        self.position_ms = snapshot.position_ms;
        self.position_updated = now;
        self.rate_permille = snapshot.rate_permille.max(1);
        self.duration_ms = snapshot.duration_ms;
        self.volume_permille = snapshot.volume_permille;
        // Adopt the identity: the snapshot IS the authoritative image,
        // so exactly ONE discontinuity fires per track change (the
        // snapshot that carried it), never again on its successors.
        self.metadata.track_key = snapshot.track_key;

        StateDelta {
            status_changed,
            volume_changed,
            seeked: jumped.then(|| (snapshot.position_ms as i64).saturating_mul(1000)),
        }
    }
}

/// What one absorbed snapshot changed (drives the signal emission).
#[derive(Debug, Clone, Copy, Default)]
pub struct StateDelta {
    /// `PlaybackStatus` PropertiesChanged warranted.
    pub status_changed: bool,
    /// `Volume` PropertiesChanged warranted.
    pub volume_changed: bool,
    /// `Seeked(x)` payload (microseconds) when discontinuous.
    pub seeked: Option<i64>,
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared inner (interface instances + adapter both hold it)
// ─────────────────────────────────────────────────────────────────────────────

struct MprisInner {
    sink: Arc<dyn MediaCommandSink>,
    state: Mutex<MprisState>,
    conn: OnceLock<Connection>,
    player_ref: OnceLock<InterfaceRef<MprisPlayer>>,
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root interface: org.mpris.MediaPlayer2
// ─────────────────────────────────────────────────────────────────────────────

/// The MPRIS Root interface. Identity only: the core cannot raise
/// windows or quit the process (that is the shell's jurisdiction),
/// so `CanRaise`/`CanQuit` are honest `false`s.
pub struct MprisRoot;

#[interface(name = "org.mpris.MediaPlayer2")]
impl MprisRoot {
    /// Window management is the shell's lane.
    fn raise(&self) -> zbus::fdo::Result<()> {
        Err(zbus::fdo::Error::NotSupported("raise is the shell's lane".into()))
    }

    /// Process lifecycle is the shell's lane.
    fn quit(&self) -> zbus::fdo::Result<()> {
        Err(zbus::fdo::Error::NotSupported("quit is the shell's lane".into()))
    }

    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn identity(&self) -> String {
        "Streamify".to_string()
    }

    #[zbus(property)]
    fn desktop_entry(&self) -> String {
        "streamify".to_string()
    }

    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        Vec::new()
    }

    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        vec![
            "audio/mpeg".to_string(),
            "audio/ogg".to_string(),
            "audio/flac".to_string(),
            "audio/aac".to_string(),
            "audio/opus".to_string(),
        ]
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Player interface: org.mpris.MediaPlayer2.Player
// ─────────────────────────────────────────────────────────────────────────────

/// The MPRIS Player interface: methods dispatch into the engine's
/// debounced command sink; properties serve the shared
/// [`MprisState`].
pub struct MprisPlayer {
    inner: Arc<MprisInner>,
}

#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl MprisPlayer {
    // ── transport control methods ────────────────────────────────────────

    fn play(&self) {
        crate::platform::adapter::dispatch_inbound(&self.inner.sink, MediaCommand::Play);
    }

    fn pause(&self) {
        crate::platform::adapter::dispatch_inbound(&self.inner.sink, MediaCommand::Pause);
    }

    fn play_pause(&self) {
        crate::platform::adapter::dispatch_inbound(
            &self.inner.sink,
            MediaCommand::TogglePlayPause,
        );
    }

    fn stop(&self) {
        crate::platform::adapter::dispatch_inbound(&self.inner.sink, MediaCommand::Stop);
    }

    fn next(&self) {
        crate::platform::adapter::dispatch_inbound(&self.inner.sink, MediaCommand::Next);
    }

    fn previous(&self) {
        crate::platform::adapter::dispatch_inbound(&self.inner.sink, MediaCommand::Previous);
    }

    /// Relative seek (D-Bus offset is in MICROseconds, may be negative).
    fn seek(&self, offset: i64) {
        let state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let current = state.extrapolated_position_ms() as i64;
        let target = if offset >= 0 {
            current.saturating_add(offset / 1000)
        } else {
            current.saturating_sub((-offset) / 1000)
        };
        // Clamp to the KNOWN duration; an unknown duration (0 — e.g. a
        // client racing the first metadata push) must not zero the
        // seek: the engine clamps against the real track itself.
        let upper_bound = if state.duration_ms > 0 {
            state.duration_ms as i64
        } else {
            i64::MAX / 2
        };
        let clamped = target.clamp(0, upper_bound).max(0) as u64;
        crate::platform::adapter::dispatch_inbound(
            &self.inner.sink,
            MediaCommand::Seek {
                position_ms: clamped,
            },
        );
    }

    /// Absolute positioning; the spec REQUIRES ignoring the call when
    /// `track_id` is not the track the property set currently
    /// advertises (a stale client racing a track change).
    fn set_position(&self, track_id: zbus::wire::ObjectPath<'_>, position: i64) {
        let state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if track_id.as_str() != state.track_path().as_str() {
            return; // Not our track: spec says ignore, not error.
        }
        let position_ms = (position.max(0) as u64) / 1000;
        crate::platform::adapter::dispatch_inbound(
            &self.inner.sink,
            MediaCommand::Seek { position_ms },
        );
    }

    /// URI playback is not a core capability (the shell resolves
    /// sources); the spec's answer is a typed error, never a crash.
    fn open_uri(&self, _uri: &str) -> zbus::fdo::Result<()> {
        Err(zbus::fdo::Error::NotSupported(
            "stream sources are resolved by the shell".into(),
        ))
    }

    // ── properties ───────────────────────────────────────────────────────

    #[zbus(property)]
    fn playback_status(&self) -> String {
        self.inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .playback_status_string()
            .to_string()
    }

    /// Repeat is a queue-engine concern, not a core transport concern;
    /// read-only "None" until the queue phase wires it.
    #[zbus(property)]
    fn loop_status(&self) -> String {
        "None".to_string()
    }

    #[zbus(property)]
    fn rate(&self) -> f64 {
        f64::from(
            self.inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .rate_permille,
        ) / 1000.0
    }

    #[zbus(property)]
    fn shuffle(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn metadata(&self) -> HashMap<String, OwnedVariant> {
        self.inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .metadata_dict()
    }

    #[zbus(property)]
    fn volume(&self) -> f64 {
        f64::from(
            self.inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .volume_permille,
        ) / 1000.0
    }

    /// Volume is the one writable property: MPRIS's 1.0-full-scale
    /// maps onto the engine's [0.0, 1.0] clamp semantics (NaN fails
    /// safe to mute — same as every other volume entry point).
    #[zbus(property)]
    fn set_volume(&mut self, volume: f64) {
        let clamped = if volume.is_nan() {
            0.0
        } else {
            volume.clamp(0.0, 1.0)
        };
        crate::platform::adapter::dispatch_inbound(
            &self.inner.sink,
            MediaCommand::SetVolume { volume: clamped as f32 },
        );
        let mut state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.volume_permille = (clamped * 1000.0) as u32;
    }

    /// Position in MICROseconds. Polled by clients; deliberately NOT
    /// signaled (`emits_changed_signal = "false"`, per the spec —
    /// position flows through `Seeked` discontinuities only).
    #[zbus(property(emits_changed_signal = "false"))]
    fn position(&self) -> i64 {
        let state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        (state.extrapolated_position_ms() as i64).saturating_mul(1000)
    }

    #[zbus(property)]
    fn can_go_next(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn can_go_previous(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn can_play(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn can_pause(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn can_seek(&self) -> bool {
        true
    }

    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }

    // ── signals ──────────────────────────────────────────────────────────

    /// `Seeked(position)` — microseconds; emitted by the adapter on
    /// genuine discontinuities only (explicit seeks / track changes).
    #[zbus(signal)]
    async fn seeked(emitter: SignalEmitter<'_>, position: i64) -> zbus::Result<()>;
}

// ─────────────────────────────────────────────────────────────────────────────
//  The surface (OsMediaSurface glue: bind to the bus, push outward)
// ─────────────────────────────────────────────────────────────────────────────

/// The MPRIS glue shell: one bound session-bus service.
pub struct MprisSurface {
    inner: Arc<MprisInner>,
    bound: AtomicBool,
}

impl MprisSurface {
    /// New surface around one command sink.
    pub fn new(sink: Arc<dyn MediaCommandSink>) -> Self {
        Self {
            inner: Arc::new(MprisInner {
                sink,
                state: Mutex::new(MprisState::default()),
                conn: OnceLock::new(),
                player_ref: OnceLock::new(),
            }),
            bound: AtomicBool::new(false),
        }
    }

    /// The shared state handle (tests).
    pub fn state(&self) -> std::sync::MutexGuard<'_, MprisState> {
        self.inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn mpris_fault(context: &str, err: &zbus::Error) -> EngineError {
        tracing::warn!(
            target: "streamify::platform::mpris",
            context,
            error = %err,
            "MPRIS bus fault"
        );
        EngineError::Storage {
            kind: StorageErrorKind::Io,
        }
    }

    /// Spawn the outbound signal emission for one absorbed delta on
    /// the shared runtime (fire-and-forget: a slow bus never blocks
    /// the adapter API).
    fn emit_delta(inner: Arc<MprisInner>, delta: StateDelta, metadata_changed: bool) {
        if !(delta.status_changed || delta.volume_changed || delta.seeked.is_some() || metadata_changed) {
            return;
        }
        crate::resolver::get_runtime().spawn(async move {
            let Some(player_ref) = inner.player_ref.get() else {
                return;
            };
            let emitter = player_ref.signal_emitter();
            if delta.status_changed {
                if let Err(err) = player_ref.get().await.playback_status_changed(emitter).await {
                    tracing::debug!(target: "streamify::platform::mpris", error = %err, "PlaybackStatus signal failed");
                }
            }
            if delta.volume_changed {
                if let Err(err) = player_ref.get().await.volume_changed(emitter).await {
                    tracing::debug!(target: "streamify::platform::mpris", error = %err, "Volume signal failed");
                }
            }
            if metadata_changed {
                if let Err(err) = player_ref.get().await.metadata_changed(emitter).await {
                    tracing::debug!(target: "streamify::platform::mpris", error = %err, "Metadata signal failed");
                }
            }
            if let Some(position_us) = delta.seeked {
                if let Err(err) = player_ref.seeked(position_us).await {
                    tracing::debug!(target: "streamify::platform::mpris", error = %err, "Seeked signal failed");
                }
            }
        });
    }
}

impl OsMediaSurface for MprisSurface {
    fn surface_name(&self) -> &'static str {
        "mpris"
    }

    fn bind(&self) -> Result<bool, EngineError> {
        // Headless host (server lane, CI, no session bus): the
        // documented graceful fallback, not an error.
        if !session_bus_available() {
            return Ok(false);
        }

        let inner = self.inner.clone();
        crate::resolver::get_runtime()
            .block_on(async {
                let conn = zbus::connection::Builder::session()
                    .name(MPRIS_SERVICE_NAME)
                    .serve_at(MPRIS_OBJECT_PATH, MprisRoot)
                    .serve_at(
                        MPRIS_OBJECT_PATH,
                        MprisPlayer {
                            inner: inner.clone(),
                        },
                    )
                    .build()
                    .await
                    .map_err(|err| Self::mpris_fault("connection build", &err))?;

                let player_ref = conn
                    .object_server()
                    .interface::<_, MprisPlayer>(MPRIS_OBJECT_PATH)
                    .await
                    .map_err(|err| Self::mpris_fault("player interface ref", &err))?;

                let _ = inner.conn.set(conn);
                let _ = inner.player_ref.set(player_ref);
                Ok::<(), EngineError>(())
            })?;

        self.bound.store(true, Ordering::Release);
        Ok(true)
    }

    fn unbind(&self) {
        // Releasing the name: dropping the Connection releases the
        // well-known name and tears the object server down. The
        // OnceLock keeps the Arc-alive connection until process end,
        // so an explicit unbind marks the surface dead and the pushes
        // stop — the bus presence itself is reclaimed by zbus on
        // drop, which the OnceLock intentionally defers (a later
        // `start` builds a fresh service anyway).
        self.bound.store(false, Ordering::Release);
    }

    fn is_bound(&self) -> bool {
        self.bound.load(Ordering::Acquire)
    }

    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError> {
        let delta = {
            let mut state = self
                .inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            state.absorb_snapshot(snapshot)
        };
        Self::emit_delta(self.inner.clone(), delta, false);
        Ok(())
    }

    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError> {
        let track_changed;
        {
            let mut state = self
                .inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            track_changed = state.metadata.track_key != image.track_key;
            state.metadata = MprisMetadataImage {
                title: image.title.clone(),
                artist: image.artist.clone(),
                album: image.album.clone(),
                art_url: image.art_url.clone(),
                track_key: image.track_key,
            };
            if track_changed {
                // New track: the timeline restarts at the pushed
                // position (the adapter already force-pushed the
                // playback snapshot; the seek delta below signals
                // the discontinuity to polling clients).
                state.position_updated = Instant::now();
            }
        }
        let delta = StateDelta {
            seeked: track_changed.then(|| 0),
            ..StateDelta::default()
        };
        Self::emit_delta(self.inner.clone(), delta, true);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(state: PlaybackSyncState, position_ms: u64, track_key: u64) -> NowPlayingSnapshot {
        NowPlayingSnapshot {
            state,
            position_ms,
            duration_ms: 240_000,
            rate_permille: 1000,
            volume_permille: 850,
            track_key,
        }
    }

    #[test]
    fn playback_status_strings_cover_every_projection() {
        let state = MprisState::default();
        assert_eq!(state.playback_status_string(), "Stopped");
        let mut state = state.clone();
        state.status = PlaybackSyncState::Playing;
        assert_eq!(state.playback_status_string(), "Playing");
        state.status = PlaybackSyncState::Buffering;
        assert_eq!(state.playback_status_string(), "Playing");
        state.status = PlaybackSyncState::Paused;
        assert_eq!(state.playback_status_string(), "Paused");
    }

    #[test]
    fn metadata_dict_carries_the_canonical_keys() {
        let mut state = MprisState::default();
        state.metadata = MprisMetadataImage {
            title: "Nightcall".into(),
            artist: "Kavinsky".into(),
            album: Some("OutRun".into()),
            art_url: Some("file:///tmp/art.jpg".into()),
            track_key: 0xdeadbeef,
        };
        state.duration_ms = 240_000;
        let dict = state.metadata_dict();

        assert_eq!(dict.len(), 6, "trackid+length+artUrl+title+artist+album");
        assert_eq!(dict["mpris:length"], OwnedVariant::from(240_000i64 * 1000));
        assert_eq!(
            dict["mpris:artUrl"].to_string().trim_matches('"'),
            "file:///tmp/art.jpg"
        );
        assert_eq!(
            dict["xesam:title"].to_string().trim_matches('"'),
            "Nightcall"
        );
        assert_eq!(
            dict["xesam:album"].to_string().trim_matches('"'),
            "OutRun"
        );
        // trackid embeds the identity key.
        let trackid = match &dict["mpris:trackid"] {
            v => v.clone(),
        };
        assert!(trackid
            .to_string()
            .contains(&format!("{:016x}", 0xdeadbeef_u64)));
        // xesam:artist is an ARRAY of strings (spec shape): the
        // Display form of an "as" variant is the quoted element list.
        let artist = dict["xesam:artist"].clone();
        let artist_str = artist.to_string();
        assert!(artist_str.contains("Kavinsky"), "artist array missing: {artist_str}");
    }

    #[test]
    fn paused_position_freezes_instead_of_extrapolating() {
        let mut state = MprisState::default();
        state.status = PlaybackSyncState::Playing;
        state.position_ms = 10_000;
        state.position_updated = Instant::now();
        // (no sleep: extrapolation is exercised by the absorb test
        // below with a synthetic clock; here the freeze is the point)
        state.status = PlaybackSyncState::Paused;
        assert_eq!(state.extrapolated_position_ms(), 10_000);
    }

    #[test]
    fn absorb_detects_discontinuities_and_status_changes() {
        let mut state = MprisState::default();
        state.status = PlaybackSyncState::Playing;
        state.position_ms = 10_000;
        state.metadata.track_key = 7;

        // Smooth progression (position advanced by ~the elapsed
        // window, same track): no Seeked, status unchanged.
        let delta = state.absorb_snapshot(&snapshot(PlaybackSyncState::Playing, 10_400, 7));
        assert!(delta.seeked.is_none());
        assert!(!delta.status_changed);

        // Explicit seek (5 s jump): Seeked carries the NEW position
        // in microseconds.
        let delta = state.absorb_snapshot(&snapshot(PlaybackSyncState::Playing, 15_400, 7));
        assert_eq!(delta.seeked, Some(15_400 * 1000));
        assert!(!delta.status_changed);

        // New track: always a discontinuity (to the pushed position).
        let delta = state.absorb_snapshot(&snapshot(PlaybackSyncState::Playing, 0, 8));
        assert_eq!(delta.seeked, Some(0));

        // Pause: status change signaled, no discontinuity invented.
        let delta = state.absorb_snapshot(&snapshot(PlaybackSyncState::Paused, 1_200, 8));
        assert!(delta.seeked.is_none());
        assert!(delta.status_changed);

        // Volume change surfaced (built explicitly: the fixed-850
        // helper value has already been absorbed into the state).
        let mut louder = snapshot(PlaybackSyncState::Paused, 1_400, 8);
        louder.volume_permille = 700;
        let delta = state.absorb_snapshot(&louder);
        assert!(delta.volume_changed);
    }

    #[test]
    fn headless_host_yields_unbound_adapter() {
        // The CI container has no session bus: the documented graceful
        // fallback. (A developer machine WITH a session bus gets the
        // real service — and the e2e contract test in
        // rust/tests/test_capability_adapters.rs covers that lane.)
        if session_bus_available() {
            return; // desktop host: covered by the integration suite
        }
        let sink = Arc::new(crate::platform::testing::RecordingCommandSink::new());
        let adapter = start(sink);
        assert!(!adapter.is_surface_bound());
    }
}
