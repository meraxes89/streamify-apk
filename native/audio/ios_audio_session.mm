// ============================================================================
//  ios_audio_session.mm — Phase 5 / MP-UI02: the Objective-C++ half of
//  the iOS audio-session adapter (native/audio/ios_audio_session.mm).
// ============================================================================
//
//  Configures the streamify engine's AVAudioSession for sovereign
//  playback and feeds the two OS-mandated event families back into the
//  engine's debounced MediaCommand dispatcher:
//
//    * Interruptions (AVAudioSessionInterruptionNotification):
//      Began → the engine pauses NOW; Ended → the engine resumes when
//      the OS advises it (AVAudioSessionInterruptionOptionShouldResume).
//    * Route changes (AVAudioSessionRouteChangeNotification):
//      OldDeviceUnavailable (unplugged headphones / dropped Bluetooth)
//      → the engine pauses immediately — audio must never blast out of
//      a suddenly-public speaker. AirPlay teardowns are session-level
//      hygiene: the observer notices the AirPlay route going away,
//      reports it, and the engine keeps playing on the local route.
//
//  Every event crosses the frozen C ABI of
//  native/include/ios_audio_session.h (`SF_IOSAS_EVT_*`) into the Rust
//  trampoline (rust/src/platform/apple/ios_audio_session.rs), where the
//  host-testable policy (rust/src/platform/ios_audio_session.rs) maps it
//  onto a MediaCommand. The deactivate path always passes
//  AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation — the clean
//  AirPlay/route teardown contract: no zombie session, other apps' audio
//  resumes.
//
//  COMPILATION (the Phase 4b host-audit discipline): the file compiles
//  in three configurations —
//
//    1. iOS + AVFoundation: the real adapter (what the XCFramework
//       script builds for every iOS slice);
//    2. Apple without AVFoundation/AVAudioSession headers (macOS host
//       lanes): an inert stub returning SF_IOSAS_ERR_NO_AVFOUNDATION
//       (the Rust side degrades to the headless no-op — graceful,
//       never a crash);
//    3. Non-Apple hosts: the same inert stub, so accidental inclusion
//       in a host lane still compiles (pure C semantics, no Apple
//       types escape this TU).
// ============================================================================

#include "ios_audio_session.h"

#import <Foundation/Foundation.h>

#if defined(__has_include)
#if __has_include(<AVFoundation/AVFoundation.h>)
#include <TargetConditionals.h>
#define SF_HAVE_AVFOUNDATION 1
#import <AVFoundation/AVFoundation.h>
#endif
#endif

#if defined(SF_HAVE_AVFOUNDATION) && defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE
#define SF_IOS_AUDIO_SESSION_ACTIVE 1
#endif

#ifdef SF_IOS_AUDIO_SESSION_ACTIVE

// ── Adapter state ───────────────────────────────────────────────────────────
// One live subscription at a time (start fails with SF_IOSAS_ERR_STATE
// on a double start). Observers are retained as the opaque ids
// addObserverForName: returns so stop() can remove exactly what
// start() registered. All state is touched only from the serial bridge
// queue (notifications arrive on the main run loop; the block bodies
// hop the event out over the C callback).

static sf_ios_audio_event_cb g_event_cb = NULL;
static void *g_event_user = NULL;
static id g_interruption_observer = nil;
static id g_route_observer = nil;
static dispatch_queue_t g_session_queue = NULL;
static BOOL g_was_airplay_active = NO;

/// Deliver one event over the frozen C ABI (bounds-checked at the
/// boundaries; the callback must not block — the Rust side's dispatch
/// is lock-bounded and callback-free by contract).
static void sf_emit(uint32_t event, uint32_t options) {
    if (g_event_cb != NULL) {
        g_event_cb(event, options, g_event_user);
    }
}

/// Whether the current route contains an AirPlay output (used to detect
/// the AirPlay TEARDOWN transition on route changes).
static BOOL sf_route_has_airplay(AVAudioSession *session) {
    for (AVAudioSessionPortDescription *port in session.currentRoute.outputs) {
        if ([port.portType isEqualToString:AVAudioSessionPortAirPlay]) {
            return YES;
        }
    }
    return NO;
}

static uint32_t sf_ios_audio_session_start_impl(uint32_t category_options,
                                            sf_ios_audio_event_cb cb,
                                            void *user) {
    if (cb == NULL) {
        return SF_IOSAS_ERR_STATE;
    }
    __block uint32_t result = SF_IOSAS_OK;
    dispatch_sync(g_session_queue, ^{
        if (g_event_cb != NULL) {
            result = SF_IOSAS_ERR_STATE;  // double start
            return;
        }

        AVAudioSession *session = [AVAudioSession sharedInstance];
        if (session == nil) {
            result = SF_IOSAS_ERR_NO_AVFOUNDATION;
            return;
        }

        // Category: Playback (the sovereign-audio contract — the app
        // plays music with the OS's full cooperation: background
        // rendering, the lock-screen route picker, hardware volume).
        // Options: the shell's mixWithOthers/duckOthers choice, mapped
        // 1:1 from the frozen ABI flags.
        AVAudioSessionCategoryOptions options = 0;
        if (category_options & SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS) {
            options |= AVAudioSessionCategoryOptionMixWithOthers;
        }
        if (category_options & SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS) {
            options |= AVAudioSessionCategoryOptionDuckOthers;
        }
        NSError *error = nil;
        // The modern category API (mode-aware, iOS 10+): the deprecated
        // two-argument spelling trips the -Werror audit lane.
        if (![session setCategory:AVAudioSessionCategoryPlayback
                             mode:AVAudioSessionModeDefault
                          options:options
                            error:&error]) {
            result = SF_IOSAS_ERR_CATEGORY;
            return;
        }
        // The modern activation spelling (iOS 6+): the selector is
        // setActive:withOptions:error: — 'setActive:options:error:' does
        // not exist on AVAudioSession and never compiled before this
        // TU entered the xcframework lane.
        if (![session setActive:YES withOptions:0 error:&error]) {
            result = SF_IOSAS_ERR_CATEGORY;
            return;
        }

        g_event_cb = cb;
        g_event_user = user;
        g_was_airplay_active = sf_route_has_airplay(session);

        NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
        g_interruption_observer =
            [center addObserverForName:AVAudioSessionInterruptionNotification
                                object:session
                                 queue:nil
                            usingBlock:^(NSNotification *note) {
                NSUInteger type =
                    [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
                if (type == AVAudioSessionInterruptionTypeBegan) {
                    sf_emit(SF_IOSAS_EVT_INTERRUPT_BEGAN, 0);
                } else if (type == AVAudioSessionInterruptionTypeEnded) {
                    uint32_t options = 0;
                    NSUInteger os_options = [note
                        .userInfo[AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
                    if (os_options & AVAudioSessionInterruptionOptionShouldResume) {
                        options |= SF_IOSAS_OPT_SHOULD_RESUME;
                    }
                    sf_emit(SF_IOSAS_EVT_INTERRUPT_ENDED, options);
                }
            }];
        g_route_observer =
            [center addObserverForName:AVAudioSessionRouteChangeNotification
                                object:session
                                 queue:nil
                            usingBlock:^(NSNotification *note) {
                NSUInteger reason = [note
                    .userInfo[AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue];
                // Unplugged headphones / disconnected Bluetooth: the
                // engine pauses immediately (MP-UI02's hard rule).
                if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
                    sf_emit(SF_IOSAS_EVT_ROUTE_OLD_DEVICE_UNAVAILABLE, 0);
                }
                // AirPlay teardown: report the transition so the Rust
                // core can log/telemetry it — the engine keeps playing
                // on the local route (clean teardown, no stuck state).
                BOOL airplay_now = sf_route_has_airplay([AVAudioSession sharedInstance]);
                if (g_was_airplay_active && !airplay_now) {
                    sf_emit(SF_IOSAS_EVT_ROUTE_AIRPLAY_TEARDOWN, 0);
                }
                g_was_airplay_active = airplay_now;
            }];
        if (g_interruption_observer == nil || g_route_observer == nil) {
            // Half-registered: tear everything down so the next start
            // is clean (the Rust side treats any non-OK as
            // "unavailable").
            sf_ios_audio_session_stop();
            result = SF_IOSAS_ERR_CATEGORY;
        }
    });
    return result;
}

// The frozen-C-ABI entry points: thin non-static shims so the symbols
// export for dlsym (a `static` start would hide them — the same
// lesson every bridge lane relearns).
uint32_t sf_ios_audio_session_start(uint32_t category_options,
                                    sf_ios_audio_event_cb cb, void *user) {
    return sf_ios_audio_session_start_impl(category_options, cb, user);
}

uint32_t sf_ios_audio_session_stop(void) {
    dispatch_sync(g_session_queue, ^{
        if (g_interruption_observer != nil) {
            [[NSNotificationCenter defaultCenter] removeObserver:g_interruption_observer];
            g_interruption_observer = nil;
        }
        if (g_route_observer != nil) {
            [[NSNotificationCenter defaultCenter] removeObserver:g_route_observer];
            g_route_observer = nil;
        }
        if (g_event_cb != nil) {
            g_event_cb = NULL;
            g_event_user = NULL;
            AVAudioSession *session = [AVAudioSession sharedInstance];
            NSError *error = nil;
            // The polite teardown: other apps' audio comes back. A
            // failure here is logged by the OS and never fatal — the
            // observers are gone either way.
            [session setActive:NO
                     withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                           error:&error];
        }
    });
    return SF_IOSAS_OK;
}

// ── Bridge queue bootstrap (once per process) ──────────────────────────────
__attribute__((constructor)) static void sf_ios_session_bridge_init(void) {
    g_session_queue = dispatch_queue_create("com.streamify.core.ios-audio-session",
                                            DISPATCH_QUEUE_SERIAL);
}

#else  // !SF_IOS_AUDIO_SESSION_ACTIVE: inert stubs (non-iOS hosts)

uint32_t sf_ios_audio_session_start(uint32_t category_options,
                                    sf_ios_audio_event_cb cb, void *user) {
    (void)category_options;
    (void)cb;
    (void)user;
    return SF_IOSAS_ERR_NO_AVFOUNDATION;
}

uint32_t sf_ios_audio_session_stop(void) { return SF_IOSAS_OK; }

#endif  // SF_IOS_AUDIO_SESSION_ACTIVE
