#ifndef STREAMIFY_IOS_AUDIO_SESSION_H
#define STREAMIFY_IOS_AUDIO_SESSION_H
// ============================================================================
//  ios_audio_session.h — Phase 5 / MP-UI02: the frozen C ABI between the
//  Rust core (rust/src/platform/ios_audio_session.rs for the host-testable
//  policy + rust/src/platform/apple/ios_audio_session.rs for the dlsym
//  bridge) and the Objective-C++ glue
//  (native/audio/ios_audio_session.mm) that talks to AVAudioSession on
//  iOS.
// ============================================================================
//
//  DESIGN (the Phase 4b zero-link-dep discipline, same as the Apple
//  remote-command bridge): the Rust side resolves these symbols at
//  RUNTIME via dlsym(RTLD_DEFAULT) —
//
//    * the iOS XCFramework links the .mm object alongside the Rust
//      staticlib, so the final app link resolves everything;
//    * hosts that linked only the inert stub
//      (native/audio/ios_audio_session_stub.c — every non-iOS lane)
//      get SF_IOSAS_ERR_NO_AVFOUNDATION and the adapter degrades to the
//      headless no-op (never a crash).
//
//  This header is deliberately PURE C (no Apple frameworks) so every CI
//  lane — Linux included — compiles and audits it
//  (tests/test_capability_abi_parity.cc pins the constants, and the
//  Rust suite cross-checks the same values from the other side).
//
//  CONTRACT NOTES
//  --------------
//    * `category_options` is a bitwise OR of the
//      SF_IOSAS_CATEGORY_OPT_* flags; the session is configured as
//      AVAudioSessionCategoryPlayback + those options.
//    * Events are delivered on the run loop that registered the
//      notifications (the app main thread); the callback must not
//      block — the Rust side's dispatch is lock-bounded and
//      callback-free by contract.
//    * `options` in the callback carries event-specific flags; today
//      only SF_IOSAS_EVT_INTERRUPT_ENDED sets
//      SF_IOSAS_OPT_SHOULD_RESUME (mirroring
//      AVAudioSessionInterruptionOptionShouldResume).
//    * At most ONE subscription is active at a time (a second start
//      without stop fails with SF_IOSAS_ERR_STATE).

#include <stdint.h>

// Portable compile-time assertion (guarded: apple_remote_command.h may
// already have defined it when both headers share a TU — e.g. the
// parity audit suite).
#if defined(__cplusplus)
#ifndef SF_STATIC_ASSERT
#define SF_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#endif
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#ifndef SF_STATIC_ASSERT
#define SF_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#endif
#else
#ifndef SF_STATIC_ASSERT
#define SF_STATIC_ASSERT(cond, msg) \
    typedef char sf_static_assert_line[(cond) ? 1 : -1]
#endif
#endif

#ifdef __cplusplus
extern "C" {
#endif

// ── Event codes (frozen; mirrored by Rust SF_IOSAS_EVT_* and pinned
//    from both languages by the test suites) ──────────────────────────
enum {
    /// AVAudioSessionInterruptionTypeBegan: playback must pause NOW.
    SF_IOSAS_EVT_INTERRUPT_BEGAN = 1,
    /// AVAudioSessionInterruptionTypeEnded: `options` carries
    /// SF_IOSAS_OPT_SHOULD_RESUME when the OS permits resuming.
    SF_IOSAS_EVT_INTERRUPT_ENDED = 2,
    /// Route change, AVAudioSessionRouteChangeReasonOldDeviceUnavailable
    /// (unplugged headphones / disconnected Bluetooth): pause
    /// immediately — audio must not blast out of a suddenly-public
    /// speaker.
    SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE = 3,
    /// The AirPlay route went away (teardown): session-level hygiene
    /// only — the .mm already deactivated cleanly; the engine is NOT
    /// commanded (playback continues on the local route).
    SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN = 4
};

// Pin the wire values: a drift in either language fails a compile lane.
SF_STATIC_ASSERT(SF_IOSAS_EVT_INTERRUPT_BEGAN == 1,
                 "SF_IOSAS_EVT_INTERRUPT_BEGAN is frozen at 1");
SF_STATIC_ASSERT(SF_IOSAS_EVT_INTERRUPT_ENDED == 2,
                 "SF_IOSAS_EVT_INTERRUPT_ENDED is frozen at 2");
SF_STATIC_ASSERT(SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE == 3,
                 "SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE is frozen at 3");
SF_STATIC_ASSERT(SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN == 4,
                 "SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN is frozen at 4");

/// Interruption-end option flag (callback `options`): the OS advises
/// the app may resume playback (AVAudioSessionInterruptionOptionShouldResume).
#define SF_IOSAS_OPT_SHOULD_RESUME 0x1u

/// Category configuration flags (AVAudioSessionCategoryPlayback + these).
#define SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS 0x1u
#define SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS 0x2u

/// Success status shared by every entry point.
#define SF_IOSAS_OK 0u
/// The session bridge is not active (start never called / stopped).
#define SF_IOSAS_ERR_NOT_ACTIVE 1u
/// AVFoundation / AVAudioSession unavailable (non-Apple host, macOS
/// stub lane, or the framework failed to load).
#define SF_IOSAS_ERR_NO_AVFOUNDATION 2u
/// The session category/options could not be applied.
#define SF_IOSAS_ERR_CATEGORY 3u
/// Bad state (double start without stop, null callback).
#define SF_IOSAS_ERR_STATE 4u

/// Session event callback: `event` is one of the SF_IOSAS_EVT_* codes;
/// `options` carries the event flags (SF_IOSAS_OPT_SHOULD_RESUME);
/// `user` is the opaque context handed to sf_ios_audio_session_start.
typedef void (*sf_ios_audio_event_cb)(uint32_t event, uint32_t options,
                                      void *user);

/// Activate the audio session: configure
/// AVAudioSessionCategoryPlayback with `category_options`, register
/// the interruption + route-change observers and deliver their events
/// to `cb`. At most ONE subscription is active at a time.
uint32_t sf_ios_audio_session_start(uint32_t category_options,
                                    sf_ios_audio_event_cb cb, void *user);

/// Deactivate cleanly: unregister the observers and deactivate the
/// session with AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
/// (the polite teardown — other apps' audio comes back). Idempotent.
uint32_t sf_ios_audio_session_stop(void);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // STREAMIFY_IOS_AUDIO_SESSION_H
