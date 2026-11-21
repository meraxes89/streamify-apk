//! iOS audio-session policy core — Phase 5 / MP-UI02 (host-testable half).
//!
//! The pure event → command mapping behind the iOS audio-session
//! adapter ([`crate::platform::apple::ios_audio_session`] binds it to
//! `native/audio/ios_audio_session.mm` over the frozen C ABI of
//! `native/include/ios_audio_session.h`):
//!
//!   * **interruptions** — `AVAudioSessionInterruptionTypeBegan` → the
//!     engine pauses NOW; `...Ended` → the engine resumes when the OS
//!     advises it (`AVAudioSessionInterruptionOptionShouldResume`,
//!     mirrored as [`SF_IOSAS_OPT_SHOULD_RESUME`]);
//!   * **route changes** — `OldDeviceUnavailable` (unplugged
//!     headphones / disconnected Bluetooth) → the engine pauses
//!     immediately: audio must never blast out of a suddenly-public
//!     speaker (the mission's hard rule);
//!   * **AirPlay teardowns** — clean silence: session-level hygiene
//!     handled by the `.mm` (observers out, deactivate-with-notify),
//!     NO engine command — playback continues on the local route.
//!
//! Everything here is pure Rust with no foreign API touch, compiled on
//! EVERY target and unit-audited on every CI runner (the Phase 4b
//! "host-testable core + thin platform shell" discipline; the mission's
//! "host testable core" mandate). The C ABI codes are mirrored from the
//! header (pinned there with `_Static_assert`s, cross-checked textually
//! by `tests/test_capability_adapters.rs`, and pinned from Rust here).

use crate::contract::MediaCommand;

// ─────────────────────────────────────────────────────────────────────────────
//  The frozen C ABI codes (mirror of native/include/ios_audio_session.h)
// ─────────────────────────────────────────────────────────────────────────────

/// Interruption began (`AVAudioSessionInterruptionTypeBegan`).
pub const SF_IOSAS_EVT_INTERRUPT_BEGAN: u32 = 1;
/// Interruption ended (`AVAudioSessionInterruptionTypeEnded`); the
/// callback's `options` carries [`SF_IOSAS_OPT_SHOULD_RESUME`] when the
/// OS permits resuming.
pub const SF_IOSAS_EVT_INTERRUPT_ENDED: u32 = 2;
/// Route change: the previous output device vanished
/// (`AVAudioSessionRouteChangeReasonOldDeviceUnavailable`).
pub const SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE: u32 = 3;
/// The AirPlay route went away (clean teardown — no engine command).
pub const SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN: u32 = 4;

/// Interruption-end option: the OS advises playback may resume
/// (`AVAudioSessionInterruptionOptionShouldResume`).
pub const SF_IOSAS_OPT_SHOULD_RESUME: u32 = 0x1;

/// Category flag: `.mixWithOthers` (`AVAudioSessionCategoryOptionMixWithOthers`).
pub const SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS: u32 = 0x1;
/// Category flag: `.duckOthers` (`AVAudioSessionCategoryOptionDuckOthers`).
pub const SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS: u32 = 0x2;

/// Success status shared with the C ABI.
pub const SF_IOSAS_OK: u32 = 0;
/// The session bridge is not active (start never called / stopped).
pub const SF_IOSAS_ERR_NOT_ACTIVE: u32 = 1;
/// AVFoundation / AVAudioSession unavailable (non-Apple host, macOS
/// stub lane, or the framework refused).
pub const SF_IOSAS_ERR_NO_AVFOUNDATION: u32 = 2;
/// The session category/options could not be applied.
pub const SF_IOSAS_ERR_CATEGORY: u32 = 3;
/// Bad state (double start without stop, null callback).
pub const SF_IOSAS_ERR_STATE: u32 = 4;

/// The decoded shape of one C-ABI session event.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IosAudioSessionEvent {
    /// An interruption began: the engine must pause immediately.
    InterruptionBegan,
    /// An interruption ended; `should_resume` mirrors the OS's
    /// `AVAudioSessionInterruptionOptionShouldResume` hint.
    InterruptionEnded { should_resume: bool },
    /// The previous audio route device vanished (headphones unplugged,
    /// Bluetooth dropped): the engine must pause immediately.
    RouteOldDeviceUnavailable,
    /// An AirPlay route went away: session hygiene only — the engine
    /// keeps playing on the local route.
    RouteAirPlayTeardown,
}

/// Decode one C-ABI `(event, options)` pair. `None` for unknown codes —
/// forward compatibility: a NEWER `.mm` may emit codes this core does
/// not know yet; those are ignored, never fatal.
pub fn event_from_code(code: u32, options: u32) -> Option<IosAudioSessionEvent> {
    match code {
        SF_IOSAS_EVT_INTERRUPT_BEGAN => Some(IosAudioSessionEvent::InterruptionBegan),
        SF_IOSAS_EVT_INTERRUPT_ENDED => Some(IosAudioSessionEvent::InterruptionEnded {
            should_resume: options & SF_IOSAS_OPT_SHOULD_RESUME != 0,
        }),
        SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE => {
            Some(IosAudioSessionEvent::RouteOldDeviceUnavailable)
        }
        SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN => Some(IosAudioSessionEvent::RouteAirPlayTeardown),
        _ => None,
    }
}

/// Stable event name for logs/telemetry.
pub fn event_name(code: u32) -> Option<&'static str> {
    match code {
        SF_IOSAS_EVT_INTERRUPT_BEGAN => Some("interrupt-began"),
        SF_IOSAS_EVT_INTERRUPT_ENDED => Some("interrupt-ended"),
        SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE => Some("route-old-device-unavailable"),
        SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN => Some("route-airplay-teardown"),
        _ => None,
    }
}

/// The interruption/route policy the engine's debounced dispatcher sees
/// (MP-UI02's behavioral contract, host-testable on every CI lane).
///
/// The machine tracks ONLY the interruption latch — it exists so a
/// resumed interruption round is observable from the outside
/// (telemetry can tell "we paused because the OS interrupted" from
/// "we paused because the route vanished"), not to gate the command
/// stream: every Began unconditionally pauses, an Ended resumes iff
/// the OS permits it, a lost route unconditionally pauses, and
/// unknown events are ignored. Duplicate suppression is the engine's
/// own 50-150 ms debounce job (MP-C03) — the policy never second-
/// guesses it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct IosAudioSessionPolicy {
    /// True between `InterruptionBegan` and `InterruptionEnded`.
    interrupted: bool,
}

impl IosAudioSessionPolicy {
    /// New policy in the not-interrupted state.
    pub fn new() -> Self {
        Self { interrupted: false }
    }

    /// Whether an OS interruption is currently latched.
    pub fn is_interrupted(&self) -> bool {
        self.interrupted
    }

    /// Feed one decoded event; returns the engine command it maps to
    /// (`None` = no command).
    pub fn on_event(&mut self, event: IosAudioSessionEvent) -> Option<MediaCommand> {
        match event {
            IosAudioSessionEvent::InterruptionBegan => {
                self.interrupted = true;
                Some(MediaCommand::Pause)
            }
            IosAudioSessionEvent::InterruptionEnded { should_resume } => {
                self.interrupted = false;
                if should_resume {
                    Some(MediaCommand::Play)
                } else {
                    None
                }
            }
            // The mission's hard rule: audio must never blast out of a
            // suddenly-public speaker. Pause immediately, regardless of
            // the interruption latch (a route loss is not an OS
            // interruption and must not clear or set it).
            IosAudioSessionEvent::RouteOldDeviceUnavailable => Some(MediaCommand::Pause),
            // Clean AirPlay teardown: the .mm already did the session
            // hygiene; playback continues on the local route.
            IosAudioSessionEvent::RouteAirPlayTeardown => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn c_abi_event_codes_are_frozen() {
        // Pinned from BOTH sides: the header _Static_asserts these
        // values and tests/test_capability_adapters.rs cross-checks the
        // header text. A drift in either direction fails a lane.
        assert_eq!(SF_IOSAS_EVT_INTERRUPT_BEGAN, 1);
        assert_eq!(SF_IOSAS_EVT_INTERRUPT_ENDED, 2);
        assert_eq!(SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE, 3);
        assert_eq!(SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN, 4);
        // Status + option codes.
        assert_eq!(SF_IOSAS_OK, 0);
        assert_eq!(SF_IOSAS_ERR_NOT_ACTIVE, 1);
        assert_eq!(SF_IOSAS_ERR_NO_AVFOUNDATION, 2);
        assert_eq!(SF_IOSAS_ERR_CATEGORY, 3);
        assert_eq!(SF_IOSAS_ERR_STATE, 4);
        assert_eq!(SF_IOSAS_OPT_SHOULD_RESUME, 0x1);
        assert_eq!(SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS, 0x1);
        assert_eq!(SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS, 0x2);
    }

    #[test]
    fn event_decoding_is_total_both_ways() {
        // Every frozen code decodes...
        for code in [
            SF_IOSAS_EVT_INTERRUPT_BEGAN,
            SF_IOSAS_EVT_INTERRUPT_ENDED,
            SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE,
            SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN,
        ] {
            assert!(event_from_code(code, 0).is_some());
            assert!(event_name(code).is_some());
        }
        // ...and unknown codes are ignored, never fatal (forward compat).
        assert!(event_from_code(0, 0).is_none());
        assert!(event_from_code(99, 0).is_none());
        assert!(event_name(99).is_none());
        // The resume option rides the interruption-ended options word.
        assert_eq!(
            event_from_code(SF_IOSAS_EVT_INTERRUPT_ENDED, SF_IOSAS_OPT_SHOULD_RESUME),
            Some(IosAudioSessionEvent::InterruptionEnded { should_resume: true })
        );
        assert_eq!(
            event_from_code(SF_IOSAS_EVT_INTERRUPT_ENDED, 0),
            Some(IosAudioSessionEvent::InterruptionEnded { should_resume: false })
        );
        // Unknown option bits do not flip the resume hint.
        assert_eq!(
            event_from_code(SF_IOSAS_EVT_INTERRUPT_ENDED, 0x8000_0000)
                .map(|e| matches!(e, IosAudioSessionEvent::InterruptionEnded { should_resume: false })),
            Some(true)
        );
    }

    #[test]
    fn interruption_roundtrip_pauses_then_resumes_on_os_hint() {
        let mut policy = IosAudioSessionPolicy::new();
        assert!(!policy.is_interrupted());
        // Began: pause NOW.
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::InterruptionBegan),
            Some(MediaCommand::Pause)
        );
        assert!(policy.is_interrupted());
        // Ended WITH the OS resume hint: resume.
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::InterruptionEnded { should_resume: true }),
            Some(MediaCommand::Play)
        );
        assert!(!policy.is_interrupted());
    }

    #[test]
    fn interruption_end_without_resume_hint_stays_paused() {
        let mut policy = IosAudioSessionPolicy::new();
        policy.on_event(IosAudioSessionEvent::InterruptionBegan);
        // Ended WITHOUT the hint (a phone call the user hung up, Siri
        // dismissing, ...): the OS did not authorize an auto-resume —
        // the user decides.
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::InterruptionEnded { should_resume: false }),
            None
        );
        assert!(!policy.is_interrupted());
    }

    #[test]
    fn lost_route_pauses_immediately_without_touching_the_latch() {
        let mut policy = IosAudioSessionPolicy::new();
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::RouteOldDeviceUnavailable),
            Some(MediaCommand::Pause)
        );
        assert!(!policy.is_interrupted());
        // A route loss DURING an interruption is still just a pause; the
        // latch belongs to the OS interruption pair alone.
        policy.on_event(IosAudioSessionEvent::InterruptionBegan);
        assert!(policy.is_interrupted());
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::RouteOldDeviceUnavailable),
            Some(MediaCommand::Pause)
        );
        assert!(policy.is_interrupted());
    }

    #[test]
    fn airplay_teardown_is_clean_silence() {
        let mut policy = IosAudioSessionPolicy::new();
        // Teardown, alone or after/before anything: NO engine command —
        // the .mm handled the session hygiene; playback continues on
        // the local route.
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::RouteAirPlayTeardown),
            None
        );
        policy.on_event(IosAudioSessionEvent::InterruptionBegan);
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::RouteAirPlayTeardown),
            None
        );
        assert!(policy.is_interrupted());
    }

    #[test]
    fn unknown_events_are_ignored() {
        let mut policy = IosAudioSessionPolicy::new();
        // Unknown codes never even decode into the typed event layer
        // (the bridge drops them before the policy sees anything).
        assert!(event_from_code(0, 0).is_none());
        assert!(event_from_code(4242, 0).is_none());
        // The known codes still flow after garbage (no state poisoning).
        assert_eq!(
            policy.on_event(IosAudioSessionEvent::InterruptionBegan),
            Some(MediaCommand::Pause)
        );
    }
}
