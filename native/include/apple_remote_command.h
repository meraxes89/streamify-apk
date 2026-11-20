#ifndef STREAMIFY_APPLE_REMOTE_COMMAND_H
#define STREAMIFY_APPLE_REMOTE_COMMAND_H
// ============================================================================
//  apple_remote_command.h — Phase 5 / MP-CAP03: the frozen C ABI between
//  the Rust core (rust/src/platform/apple/remote_command.rs) and the
//  Objective-C++ glue (native/os/apple_remote_command.mm) that talks to
//  Apple's MPRemoteCommandCenter + MPNowPlayingInfoCenter.
// ============================================================================
//
//  DESIGN (the Phase 4b zero-link-dep discipline, same as the PipeWire
//  dlopen driver): the Rust side resolves these symbols at RUNTIME via
//  dlsym(RTLD_DEFAULT) —
//
//    * the iOS XCFramework links the .mm object alongside the Rust
//      staticlib, so the final app link resolves everything;
//    * the macOS desktop cdylib carries NO link-time dependency; when
//      the host process linked the CMake
//      `streamify_apple_remote_command` static lib, the symbols are
//      simply present, otherwise the Rust bridge reports "unavailable"
//      and the adapter degrades to the headless no-op (never a crash).
//
//  This header is deliberately PURE C (no Apple frameworks) so every
//  CI lane — Linux included — compiles and audits it
//  (tests/test_capability_abi_parity.cc pins the constants, and the
//  Rust suite cross-checks the same values from the other side).
//
//  CONTRACT NOTES
//  --------------
//    * All functions return SF_APPLE_OK (0) on success; any other
//      value is a fault the Rust bridge maps onto its degradation
//      path.
//    * `sf_apple_now_playing_update` is called at MOST 1 Hz by the
//      Rust side (the Phase 5 mandate against CoreAudio/Springboard
//      IPC stalls) — the .mm adds no throttling of its own.
//    * String pointers may be NULL: NULL means "keep the previously
//      pushed value" (so timeline-only pushes do not blank the lock
//      screen). Negative `rate` / non-positive `duration_sec` also
//      mean "leave the previous value untouched".
//    * The command callback fires on the run loop the subscription
//      was made on (the app main thread); it must not block.

#include <stdint.h>

// Portable compile-time assertion: C11 _Static_assert for the C lanes
// (the .mm, the stub, any C consumer), static_assert for the C++ audit
// lanes (tests/test_capability_abi_parity.cc), and the classic
// negative-array-size trick for anything older.
#if defined(__cplusplus)
#define SF_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#define SF_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#else
#define SF_STATIC_ASSERT(cond, msg) \
    typedef char sf_static_assert_line[(cond) ? 1 : -1]
#endif

#ifdef __cplusplus
extern "C" {
#endif

// ── Command codes (frozen; mirrored by Rust SF_APPLE_CMD_* and pinned
//    from both languages by the test suites) ─────────────────────────────
enum {
    SF_APPLE_CMD_PLAY = 1,     /* -> MediaCommand::Play                    */
    SF_APPLE_CMD_PAUSE = 2,    /* -> MediaCommand::Pause                   */
    SF_APPLE_CMD_TOGGLE = 3,   /* -> MediaCommand::TogglePlayPause         */
    SF_APPLE_CMD_NEXT = 4,     /* -> MediaCommand::Next                    */
    SF_APPLE_CMD_PREVIOUS = 5, /* -> MediaCommand::Previous                */
    SF_APPLE_CMD_SEEK = 6      /* -> MediaCommand::Seek { position_ms }    */
};

// Pin the wire values: a drift in either language fails a compile lane.
SF_STATIC_ASSERT(SF_APPLE_CMD_PLAY == 1, "SF_APPLE_CMD_PLAY is frozen at 1");
SF_STATIC_ASSERT(SF_APPLE_CMD_PAUSE == 2, "SF_APPLE_CMD_PAUSE is frozen at 2");
SF_STATIC_ASSERT(SF_APPLE_CMD_TOGGLE == 3, "SF_APPLE_CMD_TOGGLE is frozen at 3");
SF_STATIC_ASSERT(SF_APPLE_CMD_NEXT == 4, "SF_APPLE_CMD_NEXT is frozen at 4");
SF_STATIC_ASSERT(SF_APPLE_CMD_PREVIOUS == 5, "SF_APPLE_CMD_PREVIOUS is frozen at 5");
SF_STATIC_ASSERT(SF_APPLE_CMD_SEEK == 6, "SF_APPLE_CMD_SEEK is frozen at 6");

/// Success status shared by every entry point.
#define SF_APPLE_OK 0u
/// The bridge is not active (start was never called / already stopped).
#define SF_APPLE_ERR_NOT_ACTIVE 1u
/// The MediaPlayer framework is unavailable in this process/OS.
#define SF_APPLE_ERR_NO_MEDIAPLAYER 2u
/// Subscription of one or more remote commands failed.
#define SF_APPLE_ERR_SUBSCRIBE 3u
/// Bad arguments (e.g. start called twice without stop).
#define SF_APPLE_ERR_STATE 4u

/// Remote-command callback: `command` is one of the `SF_APPLE_CMD_*`
/// codes; `position_ms` carries the scrubber position for
/// `SF_APPLE_CMD_SEEK` (0 for the discrete commands); `user` is the
/// opaque context handed to `sf_apple_remote_command_start`.
typedef void (*sf_apple_command_cb)(uint32_t command, uint64_t position_ms,
                                    void *user);

/// Subscribe the five transport commands + the position-scrubber command
/// on the shared `MPRemoteCommandCenter`. At most ONE subscription is
/// active at a time (a second start without stop fails with
/// SF_APPLE_ERR_STATE).
uint32_t sf_apple_remote_command_start(sf_apple_command_cb cb, void *user);

/// Unsubscribe everything and clear the now-playing info. Idempotent.
uint32_t sf_apple_remote_command_stop(void);

/// Push the now-playing image. `rate < 0`, `duration_sec <= 0` and NULL
/// strings mean "keep the previous value" (see the contract notes).
uint32_t sf_apple_now_playing_update(double position_sec, double duration_sec,
                                     double rate, const char *title,
                                     const char *artist, const char *album,
                                     const char *art_url);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // STREAMIFY_APPLE_REMOTE_COMMAND_H
