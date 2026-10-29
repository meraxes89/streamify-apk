// ============================================================================
//  apple_remote_command.mm — Phase 5 / MP-CAP03: the Objective-C++ half of
//  the Apple media-center bridge (native/os/apple_remote_command.mm).
// ============================================================================
//
//  Subscribes the Streamify core to Apple's control surfaces:
//
//    * MPRemoteCommandCenter — play / pause / togglePlayPause / nextTrack /
//      previousTrack / changePlaybackPositionCommand (Control Center, the
//      lock screen, AirPlay routes, headset buttons, CarPlay).
//    * MPNowPlayingInfoCenter — the now-playing image (title / artist /
//      album / elapsed / duration / rate) rendered by the OS chrome.
//
//  Every incoming command maps onto the frozen C ABI of
//  native/include/apple_remote_command.h (`SF_APPLE_CMD_*`) and lands in
//  the Rust trampoline (rust/src/platform/apple/remote_command.rs),
//  which turns it into a MediaCommand and dispatches through the
//  engine's debounced sink. The 1 Hz update ceiling is enforced on the
//  RUST side (the mandate's rate limit); this file applies every update
//  it receives verbatim.
//
//  ARTWORK NOTE: `art_url` is accepted for ABI parity but NOT applied —
//  MPMediaItemPropertyArtwork wants an MPMediaItemArtwork object (an
//  image provider), not a URL, and synchronous URL fetching has no
//  place in this bridge. The Apple shells own artwork rendering.
//
//  COMPILATION (the Phase 4b host-audit discipline): the file compiles
//  in three configurations —
//
//    1. Apple + MediaPlayer: the real bridge (what the XCFramework
//       script and the CMake APPLE lane build);
//    2. Apple without MediaPlayer headers: an inert stub returning
//       SF_APPLE_ERR_NO_MEDIAPLAYER (the Rust side degrades to the
//       headless no-op — graceful, never a crash);
//    3. Non-Apple hosts: the same inert stub, so accidental inclusion
//       in a host lane still compiles (pure C semantics, no Apple
//       types escape this TU).
// ============================================================================

#include "apple_remote_command.h"

#import <Foundation/Foundation.h>

#if defined(__has_include)
#if __has_include(<MediaPlayer/MediaPlayer.h>)
#define SF_HAVE_MEDIAPLAYER 1
#include <MediaPlayer/MediaPlayer.h>
#endif
#endif

#if defined(SF_HAVE_MEDIAPLAYER) && \
    (defined(__IPHONE_OS_VERSION_MIN_REQUIRED) || defined(__MAC_OS_X_VERSION_MIN_REQUIRED) || \
     (defined(__ENVIRONMENT_MAC_OS_X_VERSION_MIN_REQUIRED__) && __ENVIRONMENT_MAC_OS_X_VERSION_MIN_REQUIRED__ >= 101200))
#define SF_APPLE_BRIDGE_ACTIVE 1
#endif

#ifdef SF_APPLE_BRIDGE_ACTIVE

// ── Bridge state ────────────────────────────────────────────────────────────
// One live subscription at a time (start fails with SF_APPLE_ERR_STATE if
// the shell double-starts). The (command, token) pairs are kept so stop()
// can unregister exactly what start() registered — addTargetWithHandler:
// returns an opaque token that removeTarget: consumes. The serial queue
// serializes start/stop/update against the main-queue command callbacks.

static sf_apple_command_cb g_command_cb = NULL;
static void *g_command_user = NULL;
static NSMutableArray<NSArray *> *g_subscriptions = nil;  // @[command, token]
static dispatch_queue_t g_bridge_queue = NULL;

/// Subscribe one command; on success the (command, token) pair is
/// retained for the teardown pass.
static uint32_t sf_subscribe_command(MPRemoteCommand *command,
                                     uint32_t code) {
    if (command == nil) {
        return SF_APPLE_ERR_SUBSCRIBE;
    }
    // The block captures the frozen code + the opaque context; events
    // arrive on the run loop the command center uses (the app main
    // thread). The callback must not block — the Rust side's dispatch
    // is lock-bounded and callback-free by contract.
    void *user = g_command_user;
    id token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(
                                    MPRemoteCommandEvent *event) {
        if (g_command_cb == NULL) {
            return MPRemoteCommandHandlerStatusCommandFailed;
        }
        uint64_t position_ms = 0;
        if (code == SF_APPLE_CMD_SEEK &&
            [event isKindOfClass:[MPChangePlaybackPositionCommandEvent class]]) {
            MPChangePlaybackPositionCommandEvent *seekEvent =
                (MPChangePlaybackPositionCommandEvent *)event;
            position_ms = (uint64_t)llround(seekEvent.positionTime * 1000.0);
        }
        g_command_cb(code, position_ms, user);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    if (token == nil) {
        return SF_APPLE_ERR_SUBSCRIBE;
    }
    [g_subscriptions addObject:@[command, token]];
    return SF_APPLE_OK;
}

uint32_t sf_apple_remote_command_start(sf_apple_command_cb cb, void *user) {
    if (cb == NULL) {
        return SF_APPLE_ERR_STATE;
    }
    __block uint32_t result = SF_APPLE_OK;
    dispatch_sync(g_bridge_queue, ^{
        if (g_command_cb != NULL) {
            result = SF_APPLE_ERR_STATE;  // double start
            return;
        }
        g_command_cb = cb;
        g_command_user = user;
        g_subscriptions = [NSMutableArray array];

        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        // The five transport commands + the position scrubber.
        result = sf_subscribe_command(center.playCommand, SF_APPLE_CMD_PLAY);
        if (result != SF_APPLE_OK) return;
        result = sf_subscribe_command(center.pauseCommand, SF_APPLE_CMD_PAUSE);
        if (result != SF_APPLE_OK) return;
        result = sf_subscribe_command(center.togglePlayPauseCommand,
                                       SF_APPLE_CMD_TOGGLE);
        if (result != SF_APPLE_OK) return;
        result = sf_subscribe_command(center.nextTrackCommand, SF_APPLE_CMD_NEXT);
        if (result != SF_APPLE_OK) return;
        result = sf_subscribe_command(center.previousTrackCommand,
                                       SF_APPLE_CMD_PREVIOUS);
        if (result != SF_APPLE_OK) return;
        result = sf_subscribe_command(center.changePlaybackPositionCommand,
                                       SF_APPLE_CMD_SEEK);
    });
    if (result != SF_APPLE_OK) {
        // Half-subscribed: tear everything down so the next start is
        // clean (the Rust side treats any non-OK as "unavailable").
        sf_apple_remote_command_stop();
    }
    return result;
}

uint32_t sf_apple_remote_command_stop(void) {
    dispatch_sync(g_bridge_queue, ^{
        if (g_subscriptions != nil) {
            for (NSArray *pair in g_subscriptions) {
                MPRemoteCommand *command = pair[0];
                id token = pair[1];
                [command removeTarget:token];
            }
            g_subscriptions = nil;
        }
        g_command_cb = NULL;
        g_command_user = NULL;
        // Clearing the now-playing info releases the lock-screen claim.
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
    });
    return SF_APPLE_OK;
}

/// NSString from a UTF-8 C string; nil for NULL / invalid UTF-8 (the
/// "keep the previous value" contract).
static NSString *sf_optional_string(const char *utf8) {
    if (utf8 == NULL) {
        return nil;
    }
    return [NSString stringWithUTF8String:utf8];
}

uint32_t sf_apple_now_playing_update(double position_sec, double duration_sec,
                                     double rate, const char *title,
                                     const char *artist, const char *album,
                                     const char *art_url) {
    (void)art_url;  // See the ARTWORK NOTE: Apple shells render artwork.
    __block uint32_t result = SF_APPLE_OK;
    dispatch_sync(g_bridge_queue, ^{
        if (g_command_cb == NULL) {
            // Not subscribed: updating the OS chrome without owning the
            // now-playing slot would fight whoever owns it.
            result = SF_APPLE_ERR_NOT_ACTIVE;
            return;
        }
        NSMutableDictionary<NSString *, id> *info =
            [NSMutableDictionary dictionaryWithDictionary:
                [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo];
        // Timeline triple: negative rate / non-positive duration mean
        // "leave the previous value" (the Rust contract).
        if (rate >= 0.0) {
            info[MPNowPlayingInfoPropertyPlaybackRate] = @(rate);
        }
        if (duration_sec > 0.0) {
            info[MPMediaItemPropertyPlaybackDuration] = @(duration_sec);
        }
        if (position_sec >= 0.0) {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position_sec);
        }
        // Metadata: nil keeps the previous entry in the copied dict.
        NSString *title_ns = sf_optional_string(title);
        if (title_ns != nil) {
            info[MPMediaItemPropertyTitle] = title_ns;
        }
        NSString *artist_ns = sf_optional_string(artist);
        if (artist_ns != nil) {
            info[MPMediaItemPropertyArtist] = artist_ns;
        }
        NSString *album_ns = sf_optional_string(album);
        if (album_ns != nil) {
            info[MPMediaItemPropertyAlbumTitle] = album_ns;
        }
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
    });
    return result;
}

// ── Bridge queue bootstrap (once per process) ──────────────────────────────
__attribute__((constructor)) static void sf_apple_bridge_init(void) {
    g_bridge_queue = dispatch_queue_create("com.streamify.core.apple-bridge",
                                           DISPATCH_QUEUE_SERIAL);
}

#else  // !SF_APPLE_BRIDGE_ACTIVE: inert stubs (non-Apple hosts / no MediaPlayer)

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

#endif  // SF_APPLE_BRIDGE_ACTIVE
