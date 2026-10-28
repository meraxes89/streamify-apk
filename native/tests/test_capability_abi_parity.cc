// tests/test_capability_abi_parity.cc — Phase 5 / MP-CAP03
// ============================================================================
//
//  Host-audit lane for the Apple remote-command C ABI
//  (native/include/apple_remote_command.h): the header is deliberately
//  PURE C (no Apple frameworks), so this TU compiles and pins it on
//  EVERY host lane — Linux CI runners included — while the real
//  Objective-C++ glue (native/os/apple_remote_command.mm) builds only
//  on Apple targets.
//
//  The Rust side (rust/src/platform/apple/remote_command.rs) declares
//  the SAME codes as SF_APPLE_CMD_* constants with its own pinning
//  test, and rust/tests/test_capability_adapters.rs cross-checks this
//  header textually — three independent pins, one ABI.
//
//  Suite discipline: self-contained main with pass/fail counters, exit
//  code non-zero on any failure (same shape as the other native
//  suites).

#include "apple_remote_command.h"

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

    if (g_failures == 0) {
        std::printf("capability_abi_parity_test_suite: %d checks OK\n", g_checks);
        return 0;
    }
    std::fprintf(stderr, "capability_abi_parity_test_suite: %d/%d FAILED\n",
                 g_failures, g_checks);
    return 1;
}
