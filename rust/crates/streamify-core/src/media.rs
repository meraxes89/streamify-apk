//! MP-C03: the reverse control plane — hardware / headset media
//! commands pushed INTO the engine.
//!
//! The platform adapter maps its media-key surface (Bluetooth headset
//! toggles, MPRIS / SMTC / RemoteCommandCenter, Auto play/pause) onto
//! these variants. [`media_command_key`] is the debounce identity used
//! by the engine's 50-150 ms duplicate-event window; it is `pub`
//! (instead of crate-private) because the engine object lives in
//! streamify-contract, above this crate.

// ============================================================================
//  MP-C03: reverse control plane (media commands)
// ============================================================================

/// Reverse control plane: hardware / headset media commands pushed INTO
/// the engine (MP-C03 / MP-DEC-07).
///
/// The platform adapter maps its media-key surface (Bluetooth headset
/// toggles, Android Auto, MPRIS, Windows SMTC, Apple RemoteCommandCenter)
/// onto these variants and feeds them through
/// [`StreamifyEngine::on_media_command`], which applies the mandated
/// 50-150 ms hardware debounce against duplicate events.
#[derive(Debug, Clone, PartialEq)]
pub enum MediaCommand {
    /// Start / resume playback.
    Play,
    /// Suspend playback (buffered data retained).
    Pause,
    /// Headset toggle semantics: Playing <-> Paused.
    TogglePlayPause,
    /// Advance to the next queue item.
    Next,
    /// Return to the previous queue item.
    Previous,
    /// Seek to `position_ms` within the current track.
    Seek {
        /// Target position within the track, milliseconds from start.
        position_ms: u64,
    },
    /// Apply `volume` (clamped to `[0.0, 1.0]`; NaN fails safe to mute).
    SetVolume {
        /// Requested output volume scalar.
        volume: f32,
    },
    /// Stop playback and release the pipeline.
    Stop,
}

/// Hardware debounce window for duplicate media commands (MP-C03):
/// an IDENTICAL command (same variant, same payload) arriving within
/// this window is swallowed. 100 ms sits inside the mandated 50-150 ms
/// envelope — wide enough to collapse Bluetooth stack double-delivery
/// (typically < 20 ms apart), narrow enough that a human double-press
/// (>= ~150 ms apart) still registers as two intents.
pub const MEDIA_COMMAND_DEBOUNCE_MS: u64 = 100;

/// Comparable identity of a media command for the debounce: variant
/// index + clamped payload bits. Two commands compare equal iff they
/// are the same variant with the same (post-clamp) payload — exactly
/// the duplicates a misbehaving hardware stack re-delivers.
pub fn media_command_key(command: &MediaCommand) -> (u8, u64) {
    match command {
        MediaCommand::Play => (0, 0),
        MediaCommand::Pause => (1, 0),
        MediaCommand::TogglePlayPause => (2, 0),
        MediaCommand::Next => (3, 0),
        MediaCommand::Previous => (4, 0),
        MediaCommand::Seek { position_ms } => (5, *position_ms),
        MediaCommand::SetVolume { volume } => {
            // Same clamp semantics as set_volume so a debounced payload
            // key always matches the value that would be applied.
            let clamped = if volume.is_nan() {
                0.0
            } else {
                volume.clamp(0.0, 1.0)
            };
            (6, u64::from(clamped.to_bits()))
        }
        MediaCommand::Stop => (7, 0),
    }
}

