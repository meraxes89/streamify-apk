// ios_audio_session_stub.c — Phase 5 / MP-UI02 companion stub.
// ============================================================================
//
//  The inert implementation of the frozen C ABI
//  (native/include/ios_audio_session.h) for NON-iOS hosts: pure C, no
//  frameworks, start reporting SF_IOSAS_ERR_NO_AVFOUNDATION.
//
//  Why a separate TU: the real adapter (native/audio/ios_audio_session.mm)
//  is Objective-C++ — it needs AVFoundation/AVAudioSession headers and an
//  ObjC++ compiler, none of which exist on the Linux/Windows host lanes
//  (and AVAudioSession itself does not exist on macOS). This stub keeps
//  the C ABI *linkable and testable everywhere*
//  (capability_abi_parity_test_suite links it on every host), while the
//  XCFramework script compiles the real .mm for the iOS slices instead —
//  same symbols, same ABI, one behavioral contract: "no AVFoundation →
//  unavailable, never a crash".
//
//  The Rust side resolves these symbols at runtime via dlsym; a host
//  that links ONLY this stub (e.g. the macOS desktop cdylib) gets the
//  documented headless no-op fallback from the iOS-session adapter.

#include "ios_audio_session.h"

uint32_t sf_ios_audio_session_start(uint32_t category_options,
                                    sf_ios_audio_event_cb cb, void *user) {
    (void)category_options;
    (void)cb;
    (void)user;
    return SF_IOSAS_ERR_NO_AVFOUNDATION;
}

uint32_t sf_ios_audio_session_stop(void) { return SF_IOSAS_OK; }
