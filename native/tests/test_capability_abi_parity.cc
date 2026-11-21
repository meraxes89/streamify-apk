// tests/test_capability_abi_parity.cc — Phase 5 / MP-CAP03 + MP-UI02
// ============================================================================
//
//  Host-audit lane for the two frozen capability C ABIs:
//
//    * the Apple remote-command bridge
//      (native/include/apple_remote_command.h), and
//    * the iOS audio-session adapter
//      (native/include/ios_audio_session.h).
//
//  Both headers are deliberately PURE C (no Apple frameworks), so this
//  TU compiles and pins them on EVERY host lane — Linux CI runners
//  included — while the real Objective-C++ glue
//  (native/os/apple_remote_command.mm, native/audio/ios_audio_session.mm)
//  builds only on Apple targets.
//
//  The Rust side (rust/src/platform/apple/remote_command.rs,
//  rust/src/platform/apple/ios_audio_session.rs) declares the SAME codes
//  as SF_APPLE_CMD_* / SF_IOSAS_* constants with its own pinning tests,
//  and rust/tests/test_capability_adapters.rs cross-checks both headers
//  textually — three independent pins, one ABI.
//
//  Suite discipline: self-contained main with pass/fail counters, exit
//  code non-zero on any failure (same shape as the other native
//  suites).

#include "apple_remote_command.h"
#include "ios_audio_session.h"

#include <cstdio>
#include <cstring>

static int g_failures = 0;
static int g_checks = 0;

static void expect(bool condition, const char *what) {
    ++g_checks;
    if (!condition) {
        ++g_failures;
        std::fprintf(stderr, "FAIL: %s\n", what);
    }
}

int main() {
    // The frozen command codes (must equal the Rust SF_APPLE_CMD_* and
    // the values the .mm emits).
    expect(SF_APPLE_CMD_PLAY == 1, "SF_APPLE_CMD_PLAY == 1");
    expect(SF_APPLE_CMD_PAUSE == 2, "SF_APPLE_CMD_PAUSE == 2");
    expect(SF_APPLE_CMD_TOGGLE == 3, "SF_APPLE_CMD_TOGGLE == 3");
    expect(SF_APPLE_CMD_NEXT == 4, "SF_APPLE_CMD_NEXT == 4");
    expect(SF_APPLE_CMD_PREVIOUS == 5, "SF_APPLE_CMD_PREVIOUS == 5");
    expect(SF_APPLE_CMD_SEEK == 6, "SF_APPLE_CMD_SEEK == 6");

    // The status codes.
    expect(SF_APPLE_OK == 0, "SF_APPLE_OK == 0");
    expect(SF_APPLE_ERR_NOT_ACTIVE != 0, "SF_APPLE_ERR_NOT_ACTIVE != 0");
    expect(SF_APPLE_ERR_NO_MEDIAPLAYER != 0, "SF_APPLE_ERR_NO_MEDIAPLAYER != 0");

    // The callback type is a plain C function pointer (the Rust side
    // transmutes exactly this shape — a member-pointer or block type
    // here would be a silent ABI break).
    sf_apple_command_cb cb = nullptr;
    expect(cb == nullptr, "sf_apple_command_cb is a plain function pointer");

    // The entry points link as C symbols on a non-Apple host too (the
    // inert stubs in the .mm provide them there).
    expect(sf_apple_remote_command_stop() == SF_APPLE_OK,
           "stop is callable and idempotent on every host");
    expect(sf_apple_remote_command_start(nullptr, nullptr) ==
               SF_APPLE_ERR_NO_MEDIAPLAYER,
           "start without MediaPlayer reports unavailable (the graceful "
           "fallback)");
    expect(sf_apple_now_playing_update(0.0, 0.0, -1.0, nullptr, nullptr,
                                       nullptr, nullptr) ==
               SF_APPLE_ERR_NO_MEDIAPLAYER,
           "now_playing without MediaPlayer reports unavailable");

    // ── MP-UI02: the frozen iOS audio-session ABI ────────────────────
    // The event codes (must equal the Rust SF_IOSAS_EVT_* and the
    // values the .mm emits).
    expect(SF_IOSAS_EVT_INTERRUPT_BEGAN == 1, "SF_IOSAS_EVT_INTERRUPT_BEGAN == 1");
    expect(SF_IOSAS_EVT_INTERRUPT_ENDED == 2, "SF_IOSAS_EVT_INTERRUPT_ENDED == 2");
    expect(SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE == 3,
           "SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE == 3");
    expect(SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN == 4,
           "SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN == 4");

    // The status + option codes.
    expect(SF_IOSAS_OK == 0, "SF_IOSAS_OK == 0");
    expect(SF_IOSAS_ERR_NOT_ACTIVE != 0, "SF_IOSAS_ERR_NOT_ACTIVE != 0");
    expect(SF_IOSAS_ERR_NO_AVFOUNDATION != 0,
           "SF_IOSAS_ERR_NO_AVFOUNDATION != 0");
    expect(SF_IOSAS_OPT_SHOULD_RESUME == 0x1u,
           "SF_IOSAS_OPT_SHOULD_RESUME == 0x1");
    expect(SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS == 0x1u,
           "SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS == 0x1");
    expect(SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS == 0x2u,
           "SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS == 0x2");

    // The callback type is a plain C function pointer (the Rust side
    // transmutes exactly this shape — a member-pointer or block type
    // here would be a silent ABI break).
    sf_ios_audio_event_cb session_cb = nullptr;
    expect(session_cb == nullptr,
           "sf_ios_audio_event_cb is a plain function pointer");

    // The entry points link as C symbols on a non-iOS host too (the
    // inert stub provides them there).
    expect(sf_ios_audio_session_start(0u, nullptr, nullptr) ==
               SF_IOSAS_ERR_NO_AVFOUNDATION,
           "session start without AVFoundation reports unavailable "
           "(the graceful fallback)");
    expect(sf_ios_audio_session_stop() == SF_IOSAS_OK,
           "session stop is callable and idempotent on every host");

    if (g_failures == 0) {
        std::printf("capability_abi_parity_test_suite: %d checks OK\n", g_checks);
        return 0;
    }
    std::fprintf(stderr, "capability_abi_parity_test_suite: %d/%d FAILED\n",
                 g_failures, g_checks);
    return 1;
}
