//! Apple `MPRemoteCommandCenter` / `MPNowPlayingInfoCenter` adapter —
//! Phase 5 / MP-CAP03 (+ the MP-UI02 iOS audio-session activation).
//!
//! The Rust half of the Apple media-key + Control Center bridge:
//!
//! * **inbound** — the `.mm` glue subscribes the five
//!   `MPRemoteCommandCenter` commands (play, pause, togglePlayPause,
//!   nextTrack, previousTrack) plus
//!   `changePlaybackPositionCommand` (scrubber) and calls back over
//!   the C ABI (`sf_apple_remote_command_start`); the trampoline maps
//!   the command code onto [`crate::contract::MediaCommand`] and
//!   dispatches through the engine's debounced sink.
//! * **outbound** — `sf_apple_now_playing_update` drives
//!   `MPNowPlayingInfoCenter` with the elapsed / duration / rate
//!   triple; the shared [`SurfaceAdapter`] gate holds this at the
//!   mandated **1 Hz ceiling** (the master plan's explicit rate limit
//!   to prevent CoreAudio/Springboard IPC stalls — timeline jitter
//!   coalesces, track changes pass immediately, paused tracks report
//!   rate 0 so Control Center stops scrubbing).
//! * **linking** — dlsym (`RTLD_DEFAULT`) resolution at bind time
//!   (see the module docs for why); unresolved → headless no-op,
//!   never a crash, never a link-time dependency.
//!
//! The C ABI codes below are the single source of truth shared with
//! `native/include/apple_remote_command.h` (which pins them with
//! `_Static_assert`s); the mapping test at the bottom of this file
//! pins the Rust side — both lanes compile on every CI host, so the
//! contract cannot drift between languages.

use crate::contract::{EngineError, StorageErrorKind};
use crate::platform::adapter::{
    dispatch_inbound, OsMediaSurface, OsMetadataImage, SurfaceAdapter,
};
use crate::platform::media_sync::{
    apple_command_to_command, apple_now_playing_info, AppleCommandKind, NowPlayingSnapshot,
    SF_APPLE_CMD_NEXT, SF_APPLE_CMD_PAUSE, SF_APPLE_CMD_PLAY, SF_APPLE_CMD_PREVIOUS,
    SF_APPLE_CMD_SEEK, SF_APPLE_CMD_TOGGLE,
};
use crate::platform::{APPLE_NOW_PLAYING_MIN_INTERVAL, MediaCommandSink};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_void};
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::Arc;

/// The Apple-bridge-backed adapter handle (`OsMediaIntegration`'s
/// Apple surface): the shared 1 Hz sync algorithm over
/// [`AppleBridgeSurface`].
pub type AppleRemoteCommandAdapter = SurfaceAdapter<AppleBridgeSurface>;

/// Attach the Apple media-center bridge (headless no-op when the host
/// process did not link the `.mm` object).
pub fn attach_to_media_center(sink: Arc<dyn MediaCommandSink>) -> AppleRemoteCommandAdapter {
    let surface = AppleBridgeSurface::new(sink);
    SurfaceAdapter::attach(surface, APPLE_NOW_PLAYING_MIN_INTERVAL)
}

// ─────────────────────────────────────────────────────────────────────────────
//  The frozen C ABI (mirrors native/include/apple_remote_command.h)
// ─────────────────────────────────────────────────────────────────────────────

// The command-code constants themselves live in
// crate::platform::media_sync (cross-platform, so every CI lane can
// pin them against the C header) and are re-exported here for the
// bridge-side docs.
pub use crate::platform::media_sync::{
    SF_APPLE_CMD_NEXT, SF_APPLE_CMD_PAUSE, SF_APPLE_CMD_PLAY, SF_APPLE_CMD_PREVIOUS,
    SF_APPLE_CMD_SEEK, SF_APPLE_CMD_TOGGLE,
};

/// Status codes shared with the C ABI: 0 = success, anything else =
/// the operation failed and the surface degrades (headless).
pub const SF_APPLE_OK: u32 = 0;

/// Map a C ABI command code onto the pure command kind (`None` for
/// unknown codes — forward compatibility: a NEWER .mm may emit codes
/// this core does not know yet; those are ignored, never fatal).
pub fn command_code_to_kind(code: u32) -> Option<AppleCommandKind> {
    match code {
        SF_APPLE_CMD_PLAY => Some(AppleCommandKind::Play),
        SF_APPLE_CMD_PAUSE => Some(AppleCommandKind::Pause),
        SF_APPLE_CMD_TOGGLE => Some(AppleCommandKind::TogglePlayPause),
        SF_APPLE_CMD_NEXT => Some(AppleCommandKind::NextTrack),
        SF_APPLE_CMD_PREVIOUS => Some(AppleCommandKind::PreviousTrack),
        SF_APPLE_CMD_SEEK => Some(AppleCommandKind::ChangePlaybackPosition),
        _ => None,
    }
}

/// C ABI shapes (see the header for the canonical documentation).
type SfCommandCallback =
    unsafe extern "C" fn(command: u32, position_ms: u64, user: *mut c_void);
type SfStartFn = unsafe extern "C" fn(cb: SfCommandCallback, user: *mut c_void) -> u32;
type SfStopFn = unsafe extern "C" fn() -> u32;
type SfNowPlayingFn = unsafe extern "C" fn(
    position_sec: c_double,
    duration_sec: c_double,
    rate: c_double,
    title: *const c_char,
    artist: *const c_char,
    album: *const c_char,
    art_url: *const c_char,
) -> u32;

/// The `.mm` symbol names (frozen: the header declares them, the
/// XCFramework/CMake lanes link them, this module dlsym's them).
const SYMBOL_START: &[u8] = b"sf_apple_remote_command_start\0";
const SYMBOL_STOP: &[u8] = b"sf_apple_remote_command_stop\0";
const SYMBOL_NOW_PLAYING: &[u8] = b"sf_apple_now_playing_update\0";

/// The callback context handed to the C side as `user` (a leaked Box
/// reclaimed on unbind — the native side treats it as opaque).
struct CommandContext {
    sink: Arc<dyn MediaCommandSink>,
}

/// The C ABI trampoline: `.mm` command → MediaCommand → engine sink.
/// Runs on the main thread (MPRemoteCommandCenter dispatches on the
/// run loop the subscription was made on); the engine entry point is
/// lock-bounded and callback-free, so this cannot deadlock the run
/// loop.
extern "C" fn command_trampoline(command: u32, position_ms: u64, user: *mut c_void) {
    if user.is_null() {
        return;
    }
    let context = unsafe { &*(user as *const CommandContext) };
    if let Some(kind) = command_code_to_kind(command) {
        if let Some(cmd) = apple_command_to_command(kind, position_ms) {
            dispatch_inbound(&context.sink, cmd);
        }
    }
}

/// The dlsym-backed Apple glue shell.
pub struct AppleBridgeSurface {
    sink: Arc<dyn MediaCommandSink>,
    start_fn: AtomicPtr<c_void>,
    stop_fn: AtomicPtr<c_void>,
    now_playing_fn: AtomicPtr<c_void>,
    context: AtomicPtr<CommandContext>,
    bound: AtomicBool,
    /// The MP-UI02 audio-session bridge, activated alongside the media
    /// center on iOS (absent on macOS, where no `AVAudioSession`
    /// exists — the stub lane would refuse anyway).
    #[cfg(target_os = "ios")]
    ios_session: crate::platform::apple::ios_audio_session::IosAudioSessionBridge,
}

// SAFETY: the raw pointers are function pointers into already-loaded
// images and the context pointer is exclusively managed by
// bind/unbind; all access is behind atomics with the bound flag as
// the release/acquire hinge.
unsafe impl Send for AppleBridgeSurface {}
unsafe impl Sync for AppleBridgeSurface {}

impl AppleBridgeSurface {
    /// New surface around one sink.
    pub fn new(sink: Arc<dyn MediaCommandSink>) -> Self {
        Self {
            sink,
            start_fn: AtomicPtr::new(std::ptr::null_mut()),
            stop_fn: AtomicPtr::new(std::ptr::null_mut()),
            now_playing_fn: AtomicPtr::new(std::ptr::null_mut()),
            context: AtomicPtr::new(std::ptr::null_mut()),
            bound: AtomicBool::new(false),
            #[cfg(target_os = "ios")]
            ios_session: crate::platform::apple::ios_audio_session::IosAudioSessionBridge::new(),
        }
    }

    /// Resolve one symbol from the process image (`RTLD_DEFAULT`:
    /// wherever the host linked the `.mm` — the app binary on iOS, the
    /// CMake static lib's host on macOS).
    fn resolve_symbol(name: &[u8]) -> *mut c_void {
        unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr() as *const c_char) }
    }
}

impl OsMediaSurface for AppleBridgeSurface {
    fn surface_name(&self) -> &'static str {
        "apple-remote-command"
    }

    fn bind(&self) -> Result<bool, EngineError> {
        // Resolve ALL three symbols first: a half-linked bridge (some
        // symbols present, some not) must not half-bind.
        let start = Self::resolve_symbol(SYMBOL_START);
        let stop = Self::resolve_symbol(SYMBOL_STOP);
        let now_playing = Self::resolve_symbol(SYMBOL_NOW_PLAYING);
        if start.is_null() || stop.is_null() || now_playing.is_null() {
            // Headless / bridge not linked: the documented no-op
            // fallback, not an error (MP-CAP03 test-double contract —
            // the exact state every non-Apple CI host exercises).
            return Ok(false);
        }

        // The callback context: opaque to the C side, ours to reclaim
        // on unbind. Leaked deliberately — the native subscription
        // holds the pointer until sf_apple_remote_command_stop.
        let context = Box::into_raw(Box::new(CommandContext {
            sink: self.sink.clone(),
        }));
        self.context.store(context, Ordering::Release);

        let start_fn: SfStartFn = unsafe { std::mem::transmute(start) };
        let status = unsafe { start_fn(command_trampoline, context as *mut c_void) };
        if status != SF_APPLE_OK {
            // Start refused (no now-playing app context, etc.):
            // reclaim the context and stay headless.
            unsafe { drop(Box::from_raw(context)) };
            self.context.store(std::ptr::null_mut(), Ordering::Release);
            tracing::warn!(
                target: "streamify::platform::apple",
                status,
                "sf_apple_remote_command_start refused — headless fallback"
            );
            return Ok(false);
        }

        self.start_fn.store(start, Ordering::Release);
        self.stop_fn.store(stop, Ordering::Release);
        self.now_playing_fn.store(now_playing, Ordering::Release);
        self.bound.store(true, Ordering::Release);

        // MP-UI02: with the media surface live, also activate the iOS
        // audio session (AVAudioSessionCategoryPlayback + duckOthers,
        // interruption pause/resume, pause-on-route-loss). Best-effort
        // by design: a refused session degrades interruption handling,
        // never the remote-command surface itself.
        #[cfg(target_os = "ios")]
        {
            let category = crate::platform::apple::ios_audio_session::DEFAULT_CATEGORY_OPTIONS;
            if !self.ios_session.bind(self.sink.clone(), category) {
                tracing::info!(
                    target: "streamify::platform::apple",
                    "iOS audio session unavailable — interruption/route handling degraded"
                );
            }
        }
        Ok(true)
    }

    fn unbind(&self) {
        if !self.bound.swap(false, Ordering::AcqRel) {
            return;
        }
        let stop = self.stop_fn.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !stop.is_null() {
            let stop_fn: SfStopFn = unsafe { std::mem::transmute(stop) };
            let _ = unsafe { stop_fn() };
        }
        // MP-UI02: deactivate the audio session AFTER the remote-command
        // center released the now-playing claim — the polite teardown
        // (observers out, AVAudioSession setActive:NO + notify-others).
        #[cfg(target_os = "ios")]
        self.ios_session.unbind();
        // The native side no longer holds the callback: safe to
        // reclaim the context.
        let context = self.context.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !context.is_null() {
            unsafe { drop(Box::from_raw(context)) };
        }
        self.now_playing_fn.store(std::ptr::null_mut(), Ordering::Release);
        self.start_fn.store(std::ptr::null_mut(), Ordering::Release);
    }

    fn is_bound(&self) -> bool {
        self.bound.load(Ordering::Acquire)
    }

    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError> {
        let now_playing = self.now_playing_fn.load(Ordering::Acquire);
        if now_playing.is_null() {
            return Ok(());
        }
        // The pure projection: paused/stopped → rate 0 (Control Center
        // must stop scrubbing), buffering → rate 0.
        let info = apple_now_playing_info(snapshot);
        let fn_ptr: SfNowPlayingFn = unsafe { std::mem::transmute(now_playing) };
        // Positional state without metadata: reuse the LAST pushed
        // strings (the .mm keeps the previous MPNowPlayingInfo dict —
        // pushing NULLs would blank the lock-screen text on every
        // timeline tick).
        let status = unsafe { fn_ptr(info.elapsed_sec, info.duration_sec, info.rate, std::ptr::null(), std::ptr::null(), std::ptr::null(), std::ptr::null()) };
        if status != SF_APPLE_OK {
            return Err(EngineError::Storage {
                kind: StorageErrorKind::Io,
            });
        }
        Ok(())
    }

    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError> {
        let now_playing = self.now_playing_fn.load(Ordering::Acquire);
        if now_playing.is_null() {
            return Ok(());
        }
        let fn_ptr: SfNowPlayingFn = unsafe { std::mem::transmute(now_playing) };
        let title = c_string_or_empty(&image.title);
        let artist = c_string_or_empty(&image.artist);
        let album = image
            .album
            .as_deref()
            .map(c_string_or_empty)
            .unwrap_or_default();
        let art = image
            .art_url
            .as_deref()
            .map(c_string_or_empty)
            .unwrap_or_default();
        // A metadata push also refreshes the timeline triple (the
        // current snapshot, without inventing a position: 0-length
        // duration keeps the .mm's previous one when unknown).
        let status = unsafe {
            fn_ptr(
                0.0,
                0.0,
                -1.0, // rate < 0 = "leave the previous rate untouched"
                title.as_ptr(),
                artist.as_ptr(),
                if image.album.is_some() {
                    album.as_ptr()
                } else {
                    std::ptr::null()
                },
                if image.art_url.is_some() {
                    art.as_ptr()
                } else {
                    std::ptr::null()
                },
            )
        };
        if status != SF_APPLE_OK {
            return Err(EngineError::Storage {
                kind: StorageErrorKind::Io,
            });
        }
        Ok(())
    }
}

/// Interior-NUL-safe C string (media tags with NULs are corrupt data,
/// not a crash: the empty string keeps the ABI call well-formed).
fn c_string_or_empty(s: &str) -> CString {
    CString::new(s).unwrap_or_default()
}

/// Helper for tests + the header cross-check: the symbol names this
/// module resolves (so a test can assert them against the C header's
/// declarations textually, pinning the ABI from both sides).
pub fn bridge_symbol_names() -> [&'static str; 3] {
    // The CStr conversions are infallible (the constants carry their
    // own NUL); to_str is infallible for ASCII.
    [
        CStr::from_bytes_with_nul(SYMBOL_START)
            .unwrap()
            .to_str()
            .unwrap(),
        CStr::from_bytes_with_nul(SYMBOL_STOP)
            .unwrap()
            .to_str()
            .unwrap(),
        CStr::from_bytes_with_nul(SYMBOL_NOW_PLAYING)
            .unwrap()
            .to_str()
            .unwrap(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_codes_match_the_frozen_c_abi() {
        // Pinned from BOTH sides: these values are _Static_assert'ed
        // in native/include/apple_remote_command.h and generated by
        // the enum in native/os/apple_remote_command.mm. A drift in
        // either direction fails a compile lane on some host.
        assert_eq!(SF_APPLE_CMD_PLAY, 1);
        assert_eq!(SF_APPLE_CMD_PAUSE, 2);
        assert_eq!(SF_APPLE_CMD_TOGGLE, 3);
        assert_eq!(SF_APPLE_CMD_NEXT, 4);
        assert_eq!(SF_APPLE_CMD_PREVIOUS, 5);
        assert_eq!(SF_APPLE_CMD_SEEK, 6);
        // Total mapping, both ways.
        for code in [
            SF_APPLE_CMD_PLAY,
            SF_APPLE_CMD_PAUSE,
            SF_APPLE_CMD_TOGGLE,
            SF_APPLE_CMD_NEXT,
            SF_APPLE_CMD_PREVIOUS,
            SF_APPLE_CMD_SEEK,
        ] {
            assert!(command_code_to_kind(code).is_some());
        }
        // Unknown codes (forward compat) are ignored, never fatal.
        assert!(command_code_to_kind(0).is_none());
        assert!(command_code_to_kind(99).is_none());
    }

    #[test]
    fn seek_codes_carry_the_position_payload() {
        assert_eq!(
            command_code_to_kind(SF_APPLE_CMD_SEEK)
                .and_then(|k| apple_command_to_command(k, 61_234)),
            Some(crate::contract::MediaCommand::Seek { position_ms: 61_234 })
        );
    }

    #[test]
    fn bridge_symbol_names_are_the_frozen_abis() {
        assert_eq!(
            bridge_symbol_names(),
            [
                "sf_apple_remote_command_start",
                "sf_apple_remote_command_stop",
                "sf_apple_now_playing_update"
            ]
        );
    }

    #[test]
    fn unlinked_host_degrades_to_headless() {
        // On a host that never linked the .mm object (every non-Apple
        // CI lane, and headless macOS), bind must report unavailable
        // and the adapter must be a no-op — never an error.
        let sink = Arc::new(crate::platform::testing::RecordingCommandSink::new());
        let adapter = attach_to_media_center(sink);
        assert!(!adapter.is_surface_bound());
    }
}
