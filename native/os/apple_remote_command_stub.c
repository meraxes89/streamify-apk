// apple_remote_command_stub.c — Phase 5 / MP-CAP03 companion stub.
// ============================================================================
//
//  The inert implementation of the frozen C ABI
//  (native/include/apple_remote_command.h) for NON-Apple hosts: pure C,
//  no frameworks, every entry point reporting SF_APPLE_ERR_NO_MEDIAPLAYER.
//
//  Why a separate TU: the real bridge (native/os/apple_remote_command.mm)
//  is Objective-C++ — it needs Foundation/MediaPlayer headers and an
//  ObjC++ compiler, none of which exist on the Linux/Windows host lanes.
//  This stub keeps the C ABI *linkable and testable everywhere*
//  (capability_abi_parity_test_suite links it on every host), while the
//  Apple lanes link the real .mm instead — same symbols, same ABI, one
//  behavioral contract: "no MediaPlayer → unavailable, never a crash".
//
//  The Rust side resolves these symbols at runtime via dlsym; a host
//  that links ONLY this stub (e.g. a Linux desktop cdylib) gets the
//  documented headless no-op fallback from the Apple adapter.

#include "apple_remote_command.h"

uint32_t sf_apple_remote_command_start(sf_apple_command_cb cb, void *user) {
    (void)cb;
    (void)user;
    return SF_APPLE_ERR_NO_MEDIAPLAYER;
}

uint32_t sf_apple_remote_command_stop(void) { return SF_APPLE_OK; }

uint32_t sf_apple_now_playing_update(double position_sec, double duration_sec,
                                     double rate, const char *title,
                                     const char *artist, const char *album,
                                     const char *art_url) {
    (void)position_sec;
    (void)duration_sec;
    (void)rate;
    (void)title;
    (void)artist;
    (void)album;
    (void)art_url;
    return SF_APPLE_ERR_NO_MEDIAPLAYER;
}
