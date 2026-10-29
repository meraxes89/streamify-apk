//! Host-testable sync core shared by every OS media surface — Phase 5
//! (MP-CAP01..MP-CAP03).
//!
//! Everything in this module is pure Rust with no foreign API touch,
//! compiled on every target and unit-audited on every CI runner (the
//! Phase 4b "host-testable core + thin platform shell" discipline):
//!
//!   * [`PlaybackSyncState`] — the OS-facing projection of the engine's
//!     `PlaybackState` (the OS surfaces distinguish Changing/Buffering
//!     differently than the engine does),
//!   * [`NowPlayingSnapshot`] — the full outward image (timeline +
//!     metadata identity) a surface renders,
//!   * [`RateLimitedTimeline`] — the monotone-interval gate that keeps
//!     OS property pushes inside their mandated cadence (SMTC 200 ms to
//!     avoid COM apartment flooding, Apple 1 Hz hard ceiling, MPRIS
//!     200 ms PropertiesChanged coalescing),
//!   * [`PlaybackSyncCore`] — latest-wins coalescing state: pushes the
//!     NEWEST snapshot once the interval elapses, forces a push on
//!     track-identity changes, and never blocks a command from flowing
//!     the other way,
//!   * per-surface button/method mappings — the pure tables turning
//!     SMTC button codes / MPRIS method names / Apple command kinds
//!     into [`crate::contract::MediaCommand`] variants (or `None` for
//!     "not ours to handle").
//!
//! All of it is deterministic and wall-clock-safe in tests: the rate
//! limiter takes its interval as data, so tests run with tiny intervals
//! instead of sleeping through production cadences.

use crate::contract::{MediaCommand, PlaybackState};
use std::time::{Duration, Instant};

/// OS-facing playback projection. The engine's `Buffering` state has no
/// universal OS equivalent, so each adapter projects it explicitly
/// (SMTC: `Changing`; MPRIS: `Playing`; Apple: paused-rate) instead of
/// this enum guessing on anyone's behalf.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlaybackSyncState {
    /// Actively rendering.
    Playing,
    /// Suspended, buffered data retained.
    Paused,
    /// Stopped / idle: no track loaded on the OS surface.
    Stopped,
    /// Engine-side transition (buffer fill, seek in flight).
    #[default]
    Buffering,
}

/// Map the engine lifecycle state onto the OS projection.
pub fn playback_sync_state(state: PlaybackState) -> PlaybackSyncState {
    match state {
        PlaybackState::Playing => PlaybackSyncState::Playing,
        PlaybackState::Paused => PlaybackSyncState::Paused,
        PlaybackState::Buffering => PlaybackSyncState::Buffering,
        PlaybackState::Idle | PlaybackState::Error => PlaybackSyncState::Stopped,
    }
}

/// The full outward now-playing image one OS surface renders.
///
/// `track_key` is the deterministic track identity (derived from the
/// metadata tuple by the adapter, not passed by the shell): it is what
/// makes rate-limit coalescing safe — a CHANGE of identity must always
/// reach the surface immediately, while pure timeline jitter is what
/// gets coalesced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NowPlayingSnapshot {
    /// Playback projection.
    pub state: PlaybackSyncState,
    /// Position within the track, milliseconds.
    pub position_ms: u64,
    /// Track duration, milliseconds (0 = unknown).
    pub duration_ms: u64,
    /// Playback rate in per-mille (1000 = normal, 0 = paused).
    ///
    /// `f32` in this struct would break the `Eq` contract of the
    /// coalescing comparison (NaN never compares equal), so the
    /// snapshot carries a total-order-friendly integer; the Apple
    /// bridge converts to `f64` at the surface boundary.
    pub rate_permille: u32,
    /// Output volume in per-mille of full scale (1000 = full). Serves
    /// the MPRIS `Volume` property; SMTC and Apple surfaces ignore
    /// it (no OS volume surface on their side).
    pub volume_permille: u32,
    /// Deterministic track identity key (see struct docs).
    pub track_key: u64,
}

/// Result of feeding one update through the sync core.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SyncDecision {
    /// Push this snapshot to the OS surface now.
    Push,
    /// Coalesce: the interval has not elapsed AND the track identity
    /// did not change. The newest snapshot is retained; the next
    /// allowed window pushes it.
    Coalesced,
}

/// Monotone interval gate for OS property pushes.
///
/// The FIRST call always passes (cold-start hydration must reach the
/// surface immediately); subsequent calls pass only after `min_interval`
/// elapsed since the last PASS. Wall-clock via [`Instant`] — monotone,
/// sleep-proof by construction.
#[derive(Debug)]
pub struct RateLimitedTimeline {
    min_interval: Duration,
    last_pass: Option<Instant>,
}

impl RateLimitedTimeline {
    /// New gate with the given minimum push interval.
    pub fn new(min_interval: Duration) -> Self {
        Self {
            min_interval,
            last_pass: None,
        }
    }

    /// The configured minimum interval (data for tests / telemetry).
    pub fn min_interval(&self) -> Duration {
        self.min_interval
    }

    /// Offer a push. Returns `true` (and records the pass) when the
    /// push may proceed, `false` when it must be coalesced away.
    pub fn allow(&mut self) -> bool {
        match self.last_pass {
            None => {
                self.last_pass = Some(Instant::now());
                true
            }
            Some(last) => {
                if last.elapsed() >= self.min_interval {
                    self.last_pass = Some(Instant::now());
                    true
                } else {
                    false
                }
            }
        }
    }

    /// Force the next `allow()` to pass (used on hard state changes
    /// where the surface MUST hear from us, e.g. Stop clearing the OS
    /// chrome). Does not reset the pass clock by itself.
    pub fn force_next(&mut self) {
        // Rewinding the last-pass to `min_interval` ago means the very
        // next allow() both passes and re-arms the clock honestly.
        self.last_pass = Some(Instant::now() - self.min_interval);
    }
}

/// Latest-wins coalescing outward state for one OS surface.
///
/// Thread-safe by interior `Mutex` (control-plane cadence; never held
/// across a foreign call). The OS shells' update storms (a UI shell
/// pushing on every frame, an MPRIS client polling Position) collapse
/// into one surface push per interval carrying the newest image.
#[derive(Debug)]
pub struct PlaybackSyncCore {
    /// The gate behind a mutex: [`Self::offer`] takes `&self` (the
    /// adapter API is shared-immutable), so the pass clock is
    /// interior-mutable. Control-plane cadence; never contended from
    /// the audio path.
    gate: std::sync::Mutex<RateLimitedTimeline>,
    current: std::sync::Mutex<Option<NowPlayingSnapshot>>,
}

impl PlaybackSyncCore {
    /// New core gating pushes at `min_interval`.
    pub fn new(min_interval: Duration) -> Self {
        Self {
            gate: std::sync::Mutex::new(RateLimitedTimeline::new(min_interval)),
            current: std::sync::Mutex::new(None),
        }
    }

    /// Offer the newest outward image. Returns [`SyncDecision::Push`]
    /// when the surface must receive `snapshot` NOW:
    ///
    ///   * first update after attach (cold start), or
    ///   * the interval elapsed since the last push, or
    ///   * the track identity changed (new track: never delayed), or
    ///   * the state left the Playing family (Paused/Stopped must
    ///     clear the OS chrome promptly).
    ///
    /// Otherwise [`SyncDecision::Coalesced`]: the snapshot is retained
    /// as the newest image (the next Push window carries it).
    pub fn offer(&self, snapshot: NowPlayingSnapshot) -> SyncDecision {
        let mut gate = self
            .gate
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut current = self
            .current
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());

        let identity_changed = match &*current {
            Some(prev) => prev.track_key != snapshot.track_key,
            None => true, // cold start
        };
        let left_playing = matches!(
            current.as_ref().map(|s| s.state),
            None | Some(PlaybackSyncState::Playing)
        ) && !matches!(snapshot.state, PlaybackSyncState::Playing);

        if identity_changed || left_playing {
            gate.force_next();
        }

        let decision = if gate.allow() {
            SyncDecision::Push
        } else {
            SyncDecision::Coalesced
        };
        // Latest-wins: even a coalesced offer replaces the stored image.
        *current = Some(snapshot);
        decision
    }

    /// The newest retained image (what the next Push window carries).
    pub fn current(&self) -> Option<NowPlayingSnapshot> {
        self.current
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// The configured push interval (data for tests).
    pub fn min_interval(&self) -> Duration {
        self.gate
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .min_interval()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SMTC button mapping (MP-CAP01) — pure table, shared by the WinRT shell
//  and the host-testable mock surface.
// ─────────────────────────────────────────────────────────────────────────────

/// SMTC button codes, mirroring
/// `Windows::Media::SystemMediaTransportControlsButton` (the integer
/// discriminants are pinned by the WinRT contract and asserted by the
/// Windows-side shell at attach time — the pure mapping below never
/// depends on them being right, it takes the enum).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SmtcButton {
    Play,
    Pause,
    Stop,
    Next,
    Previous,
    /// FastForward — not a streamify surface capability; ignored.
    FastForward,
    /// Rewind — not a streamify surface capability; ignored.
    Rewind,
    /// Record / ChannelUp / ChannelDown — TV concepts, ignored.
    Other,
}

/// Map an SMTC button press onto the reverse-plane command. `None` =
/// the button is not one of ours (disabled / TV-family) and is ignored
/// without touching the engine.
pub fn smtc_button_to_command(button: SmtcButton) -> Option<MediaCommand> {
    match button {
        SmtcButton::Play => Some(MediaCommand::Play),
        SmtcButton::Pause => Some(MediaCommand::Pause),
        SmtcButton::Stop => Some(MediaCommand::Stop),
        SmtcButton::Next => Some(MediaCommand::Next),
        SmtcButton::Previous => Some(MediaCommand::Previous),
        SmtcButton::FastForward | SmtcButton::Rewind | SmtcButton::Other => None,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Apple remote command mapping (MP-CAP03) — pure table, shared by the
//  .mm bridge callback path and the mock media center.
// ─────────────────────────────────────────────────────────────────────────────

// The frozen C-ABI command codes of
// native/include/apple_remote_command.h (`SF_APPLE_CMD_*`). They live
// HERE — cross-platform — so every CI lane can pin the Rust side
// against the header textually (tests/test_capability_adapters.rs),
// while the Apple-only bridge (platform/apple/remote_command.rs) maps
// them onto [`AppleCommandKind`].
pub const SF_APPLE_CMD_PLAY: u32 = 1;
pub const SF_APPLE_CMD_PAUSE: u32 = 2;
pub const SF_APPLE_CMD_TOGGLE: u32 = 3;
pub const SF_APPLE_CMD_NEXT: u32 = 4;
pub const SF_APPLE_CMD_PREVIOUS: u32 = 5;
pub const SF_APPLE_CMD_SEEK: u32 = 6;

/// Apple remote command kinds, mirroring the C ABI codes documented in
/// `native/include/apple_remote_command.h` (`SF_APPLE_CMD_*`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppleCommandKind {
    /// `play` command.
    Play,
    /// `pause` command.
    Pause,
    /// `togglePlayPause` command (headset / Control Center).
    TogglePlayPause,
    /// `nextTrack` command.
    NextTrack,
    /// `previousTrack` command.
    PreviousTrack,
    /// `changePlaybackPositionCommand` (scrubber) — carries a position.
    ChangePlaybackPosition,
}

/// Map an Apple remote command (with its position payload when the kind
/// is `ChangePlaybackPosition`) onto the reverse-plane command.
pub fn apple_command_to_command(
    kind: AppleCommandKind,
    position_ms: u64,
) -> Option<MediaCommand> {
    match kind {
        AppleCommandKind::Play => Some(MediaCommand::Play),
        AppleCommandKind::Pause => Some(MediaCommand::Pause),
        AppleCommandKind::TogglePlayPause => Some(MediaCommand::TogglePlayPause),
        AppleCommandKind::NextTrack => Some(MediaCommand::Next),
        AppleCommandKind::PreviousTrack => Some(MediaCommand::Previous),
        AppleCommandKind::ChangePlaybackPosition => {
            Some(MediaCommand::Seek { position_ms })
        }
    }
}

/// Build the now-playing dictionary `MPNowPlayingInfoCenter` consumes
/// (elapsed/duration/rate triple). Pure conversion — the 1 Hz cadence
/// lives in the adapter's [`PlaybackSyncCore`], NOT here.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct AppleNowPlayingInfo {
    /// Elapsed playback position, seconds.
    pub elapsed_sec: f64,
    /// Track duration, seconds (0 = unknown).
    pub duration_sec: f64,
    /// Playback rate (0.0 = paused; `MPNowPlayingInfoCenter` renders
    /// paused players from this field alone).
    pub rate: f64,
}

/// Derive the Apple now-playing triple from a snapshot. Paused and
/// Stopped surfaces report rate 0 regardless of the engine's last
/// non-zero rate, or Control Center would keep advancing the scrubber
/// of a paused track.
pub fn apple_now_playing_info(snapshot: &NowPlayingSnapshot) -> AppleNowPlayingInfo {
    let rate = match snapshot.state {
        PlaybackSyncState::Playing => f64::from(snapshot.rate_permille) / 1000.0,
        PlaybackSyncState::Buffering
        | PlaybackSyncState::Paused
        | PlaybackSyncState::Stopped => 0.0,
    };
    AppleNowPlayingInfo {
        elapsed_sec: snapshot.position_ms as f64 / 1000.0,
        duration_sec: snapshot.duration_ms as f64 / 1000.0,
        rate,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap(state: PlaybackSyncState, position_ms: u64, track_key: u64) -> NowPlayingSnapshot {
        NowPlayingSnapshot {
            state,
            position_ms,
            duration_ms: 200_000,
            rate_permille: 1000,
            volume_permille: 1000,
            track_key,
        }
    }

    #[test]
    fn playback_state_projection_covers_every_variant() {
        assert_eq!(
            playback_sync_state(PlaybackState::Playing),
            PlaybackSyncState::Playing
        );
        assert_eq!(
            playback_sync_state(PlaybackState::Paused),
            PlaybackSyncState::Paused
        );
        assert_eq!(
            playback_sync_state(PlaybackState::Buffering),
            PlaybackSyncState::Buffering
        );
        assert_eq!(
            playback_sync_state(PlaybackState::Idle),
            PlaybackSyncState::Stopped
        );
        assert_eq!(
            playback_sync_state(PlaybackState::Error),
            PlaybackSyncState::Stopped
        );
    }

    #[test]
    fn rate_limiter_first_pass_always_allowed() {
        let mut gate = RateLimitedTimeline::new(Duration::from_secs(3600));
        assert!(gate.allow());
        // The huge interval means nothing else passes for an hour —
        // no sleep needed for a deterministic assertion.
        assert!(!gate.allow());
        assert!(!gate.allow());
    }

    #[test]
    fn rate_limiter_force_next_rearms_honestly() {
        let mut gate = RateLimitedTimeline::new(Duration::from_secs(3600));
        assert!(gate.allow());
        assert!(!gate.allow());
        gate.force_next();
        assert!(gate.allow());
        assert!(!gate.allow());
    }

    #[test]
    fn sync_core_cold_start_pushes() {
        let core = PlaybackSyncCore::new(Duration::from_secs(3600));
        assert_eq!(core.offer(snap(PlaybackSyncState::Playing, 0, 1)), SyncDecision::Push);
        assert_eq!(core.current(), Some(snap(PlaybackSyncState::Playing, 0, 1)));
    }

    #[test]
    fn sync_core_coalesces_timeline_jitter_latest_wins() {
        let core = PlaybackSyncCore::new(Duration::from_secs(3600));
        core.offer(snap(PlaybackSyncState::Playing, 0, 7));
        // Timeline-only churn inside the window: all coalesced...
        for pos in 1..50u64 {
            assert_eq!(
                core.offer(snap(PlaybackSyncState::Playing, pos * 40, 7)),
                SyncDecision::Coalesced
            );
        }
        // ...and the NEWEST image is the one retained.
        assert_eq!(
            core.current(),
            Some(snap(PlaybackSyncState::Playing, 49 * 40, 7))
        );
    }

    #[test]
    fn sync_core_track_change_forces_push() {
        let core = PlaybackSyncCore::new(Duration::from_secs(3600));
        core.offer(snap(PlaybackSyncState::Playing, 5000, 1));
        // Same window, but a NEW track: must reach the surface now.
        assert_eq!(
            core.offer(snap(PlaybackSyncState::Playing, 0, 2)),
            SyncDecision::Push
        );
    }

    #[test]
    fn sync_core_pause_forces_push() {
        let core = PlaybackSyncCore::new(Duration::from_secs(3600));
        core.offer(snap(PlaybackSyncState::Playing, 5000, 1));
        // Pause must clear the OS chrome immediately, not at the next
        // cadence window.
        assert_eq!(
            core.offer(snap(PlaybackSyncState::Paused, 5000, 1)),
            SyncDecision::Push
        );
    }

    #[test]
    fn smtc_button_mapping_is_total() {
        use MediaCommand as M;
        assert_eq!(smtc_button_to_command(SmtcButton::Play), Some(M::Play));
        assert_eq!(smtc_button_to_command(SmtcButton::Pause), Some(M::Pause));
        assert_eq!(smtc_button_to_command(SmtcButton::Stop), Some(M::Stop));
        assert_eq!(smtc_button_to_command(SmtcButton::Next), Some(M::Next));
        assert_eq!(smtc_button_to_command(SmtcButton::Previous), Some(M::Previous));
        // TV / trick-play buttons are not ours.
        assert_eq!(smtc_button_to_command(SmtcButton::FastForward), None);
        assert_eq!(smtc_button_to_command(SmtcButton::Rewind), None);
        assert_eq!(smtc_button_to_command(SmtcButton::Other), None);
    }

    #[test]
    fn apple_command_mapping_covers_the_five_plus_seek() {
        use MediaCommand as M;
        assert_eq!(
            apple_command_to_command(AppleCommandKind::Play, 0),
            Some(M::Play)
        );
        assert_eq!(
            apple_command_to_command(AppleCommandKind::Pause, 0),
            Some(M::Pause)
        );
        assert_eq!(
            apple_command_to_command(AppleCommandKind::TogglePlayPause, 0),
            Some(M::TogglePlayPause)
        );
        assert_eq!(
            apple_command_to_command(AppleCommandKind::NextTrack, 0),
            Some(M::Next)
        );
        assert_eq!(
            apple_command_to_command(AppleCommandKind::PreviousTrack, 0),
            Some(M::Previous)
        );
        assert_eq!(
            apple_command_to_command(AppleCommandKind::ChangePlaybackPosition, 42_500),
            Some(M::Seek { position_ms: 42_500 })
        );
    }

    #[test]
    fn apple_now_playing_rate_follows_state_not_engine_rate() {
        let mut snapshot = snap(PlaybackSyncState::Playing, 30_000, 1);
        assert_eq!(apple_now_playing_info(&snapshot).rate, 1.0);
        snapshot.state = PlaybackSyncState::Paused;
        // Paused: rate MUST read 0 even though the engine's last rate
        // is still 1.0, or iOS Control Center keeps scrubbing.
        assert_eq!(apple_now_playing_info(&snapshot).rate, 0.0);
        assert_eq!(apple_now_playing_info(&snapshot).elapsed_sec, 30.0);
    }
}
