//! iOS `AVAudioSession` adapter bridge — Phase 5 / MP-UI02 (the
//! platform glue shell half).
//!
//! The dlsym-backed link between the host-testable policy
//! ([`crate::platform::ios_audio_session`]) and the Objective-C++ glue
//! in `native/audio/ios_audio_session.mm`:
//!
//! * **inbound** — the `.mm` subscribes
//!   `AVAudioSessionInterruptionNotification` (began/ended, with the OS
//!   `AVAudioSessionInterruptionOptionShouldResume` hint) and
//!   `AVAudioSessionRouteChangeNotification` (old-device-unavailable,
//!   AirPlay teardown) and calls back over the frozen C ABI
//!   (`sf_ios_audio_session_start`); the trampoline decodes the event,
//!   runs the [`IosAudioSessionPolicy`] and dispatches the mapped
//!   command through the engine's debounced sink;
//! * **outbound** — the session itself is configured by the `.mm` at
//!   `start` time: `AVAudioSessionCategoryPlayback` plus the caller's
//!   mix/duck options ([`DEFAULT_CATEGORY_OPTIONS`]);
//! * **linking** — dlsym (`RTLD_DEFAULT`) resolution at bind time, the
//!   same zero-link-dep discipline as the Apple remote-command bridge
//!   and the Phase 4b PipeWire driver; unresolved (or the inert stub's
//!   `SF_IOSAS_ERR_NO_AVFOUNDATION`) → headless no-op, never a crash,
//!   never a link-time dependency.
//!
//! The C ABI codes below are the single source of truth shared with
//! `native/include/ios_audio_session.h` (which pins them with
//! `_Static_assert`s); the mapping test at the bottom of the policy
//! core pins the Rust side — both lanes compile on every CI host, so
//! the contract cannot drift between languages.
//!
//! Compiled ONLY on iOS ([`crate::platform::apple`]'s gate is macOS +
//! iOS; AVAudioSession does not exist on macOS, where the stub lane
//! would refuse anyway).

use crate::platform::adapter::dispatch_inbound;
use crate::platform::ios_audio_session::{
    event_from_code, IosAudioSessionPolicy, SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS, SF_IOSAS_OK,
};
use crate::platform::MediaCommandSink;
use std::ffi::CStr;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::{Arc, Mutex};

/// The category the integration binds by default:
/// `AVAudioSessionCategoryPlayback` + `.duckOthers` — the sovereign-
/// music posture (background rendering, lock-screen route picker,
/// hardware volume; ambient audio politely ducks while we play).
/// Shells that prefer mixing pass
/// [`SF_IOSAS_CATEGORY_OPT_MIX_WITH_OTHERS`] through the C ABI when
/// they drive the session manually.
pub const DEFAULT_CATEGORY_OPTIONS: u32 = SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS;

// ─────────────────────────────────────────────────────────────────────────────
//  The frozen C ABI (mirrors native/include/ios_audio_session.h)
// ─────────────────────────────────────────────────────────────────────────────

/// Event callback: `(event, options, user)`.
type SfEventCallback = unsafe extern "C" fn(event: u32, options: u32, user: *mut c_void);
type SfStartFn =
    unsafe extern "C" fn(category_options: u32, cb: SfEventCallback, user: *mut c_void) -> u32;
type SfStopFn = unsafe extern "C" fn() -> u32;

/// The `.mm` symbol names (frozen: the header declares them, the
/// XCFramework/CMake lanes link them, this module dlsym's them).
const SYMBOL_START: &[u8] = b"sf_ios_audio_session_start\0";
const SYMBOL_STOP: &[u8] = b"sf_ios_audio_session_stop\0";

/// The callback context handed to the C side as `user` (a leaked Box
/// reclaimed on unbind — the native side treats it as opaque).
struct SessionContext {
    sink: Arc<dyn MediaCommandSink>,
    policy: Mutex<IosAudioSessionPolicy>,
}

/// C ABI trampoline: `.mm` event → policy → MediaCommand → engine sink.
///
/// Runs on the run loop the notifications were registered on (the app
/// main thread); the engine entry point is lock-bounded and callback-
/// free, so this cannot deadlock the run loop.
extern "C" fn session_trampoline(event: u32, options: u32, user: *mut c_void) {
    if user.is_null() {
        return;
    }
    let context = unsafe { &*(user as *const SessionContext) };
    let Some(decoded) = event_from_code(event, options) else {
        // Unknown codes (forward compat) are dropped here, never fatal.
        return;
    };
    let command = {
        let mut policy = context
            .policy
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        policy.on_event(decoded)
    };
    if let Some(cmd) = command {
        dispatch_inbound(&context.sink, cmd);
    }
}

/// The dlsym-backed iOS audio-session glue shell.
pub struct IosAudioSessionBridge {
    start_fn: AtomicPtr<c_void>,
    stop_fn: AtomicPtr<c_void>,
    context: AtomicPtr<c_void>,
    bound: AtomicBool,
}

// SAFETY: the raw pointers are function pointers into already-loaded
// images and the context pointer is exclusively managed by bind/unbind;
// all access is behind atomics with the bound flag as the release/
// acquire hinge.
unsafe impl Send for IosAudioSessionBridge {}
unsafe impl Sync for IosAudioSessionBridge {}

impl IosAudioSessionBridge {
    /// New bridge in the unbound state.
    pub fn new() -> Self {
        Self {
            start_fn: AtomicPtr::new(std::ptr::null_mut()),
            stop_fn: AtomicPtr::new(std::ptr::null_mut()),
            context: AtomicPtr::new(std::ptr::null_mut()),
            bound: AtomicBool::new(false),
        }
    }

    /// Resolve one symbol from the process image (`RTLD_DEFAULT`).
    fn resolve_symbol(name: &[u8]) -> *mut c_void {
        unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr() as *const std::os::raw::c_char) }
    }

    /// Bind to the session adapter: configure the category and register
    /// the interruption/route observers. Returns `true` when the real
    /// OS session attached; `false` is the documented headless fallback
    /// (bridge not linked, or the `.mm` refused) — never an error, per
    /// the graceful-degradation mandate.
    pub fn bind(&self, sink: Arc<dyn MediaCommandSink>, category_options: u32) -> bool {
        // Resolve BOTH symbols first: a half-linked bridge must not
        // half-bind.
        let start = Self::resolve_symbol(SYMBOL_START);
        let stop = Self::resolve_symbol(SYMBOL_STOP);
        if start.is_null() || stop.is_null() {
            // Headless / bridge not linked: the documented no-op
            // fallback (the exact state every non-iOS CI host and a
            // stub-linked macOS build exercises).
            tracing::info!(
                target: "streamify::platform::apple::ios_audio_session",
                "AVAudioSession bridge not linked — interruption/route handling degraded (headless no-op)"
            );
            return false;
        }

        // The callback context: opaque to the C side, ours to reclaim
        // on unbind. Leaked deliberately — the native subscription
        // holds the pointer until sf_ios_audio_session_stop.
        let context = Box::into_raw(Box::new(SessionContext {
            sink,
            policy: Mutex::new(IosAudioSessionPolicy::new()),
        }));
        self.context.store(context, Ordering::Release);

        let start_fn: SfStartFn = unsafe { std::mem::transmute(start) };
        let status = unsafe { start_fn(category_options, session_trampoline, context as *mut c_void) };
        if status != SF_IOSAS_OK {
            // Start refused (no AVFoundation / category refused — the
            // stub lane reports SF_IOSAS_ERR_NO_AVFOUNDATION): reclaim
            // the context and stay headless.
            tracing::info!(
                target: "streamify::platform::apple::ios_audio_session",
                status,
                "sf_ios_audio_session_start refused — headless fallback"
            );
            unsafe { drop(Box::from_raw(context)) };
            self.context.store(std::ptr::null_mut(), Ordering::Release);
            return false;
        }

        self.start_fn.store(start, Ordering::Release);
        self.stop_fn.store(stop, Ordering::Release);
        self.bound.store(true, Ordering::Release);
        tracing::info!(
            target: "streamify::platform::apple::ios_audio_session",
            category_options,
            "AVAudioSession adapter bound (AVAudioSessionCategoryPlayback + options)"
        );
        true
    }

    /// Unbind (unregister the observers, deactivate the session with
    /// the notify-others option). Idempotent.
    pub fn unbind(&self) {
        if !self.bound.swap(false, Ordering::AcqRel) {
            return;
        }
        let stop = self.stop_fn.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !stop.is_null() {
            let stop_fn: SfStopFn = unsafe { std::mem::transmute(stop) };
            let _ = unsafe { stop_fn() };
        }
        // The native side no longer holds the callback: safe to
        // reclaim the context.
        let context = self.context.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !context.is_null() {
            unsafe { drop(Box::from_raw(context)) };
        }
        self.start_fn.store(std::ptr::null_mut(), Ordering::Release);
    }

    /// Whether the session adapter is currently bound.
    pub fn is_bound(&self) -> bool {
        self.bound.load(Ordering::Acquire)
    }
}

impl Default for IosAudioSessionBridge {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper for tests + the header cross-check: the symbol names this
/// module resolves (so a test can assert them against the C header's
/// declarations textually, pinning the ABI from both sides).
pub fn bridge_symbol_names() -> [&'static str; 2] {
    // The CStr conversions are infallible (the constants carry their
    // own NUL); to_str is infallible for ASCII.
    [
        CStr::from_bytes_with_nul(SYMBOL_START).unwrap().to_str().unwrap(),
        CStr::from_bytes_with_nul(SYMBOL_STOP).unwrap().to_str().unwrap(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bridge_symbol_names_are_the_frozen_abis() {
        assert_eq!(
            bridge_symbol_names(),
            ["sf_ios_audio_session_start", "sf_ios_audio_session_stop"]
        );
    }

    #[test]
    fn default_category_is_playback_duck() {
        // The sovereign-music posture: mixWithOthers is NOT set (we
        // take the route), duckOthers IS (ambient audio politely
        // ducks while we play).
        assert_eq!(DEFAULT_CATEGORY_OPTIONS, SF_IOSAS_CATEGORY_OPT_DUCK_OTHERS);
    }

    #[test]
    fn unlinked_host_degrades_to_headless() {
        // On a host that never linked the .mm object (a plain `cargo
        // test` build links no Apple glue), bind must report unavailable
        // and the bridge must stay the documented no-op — never a panic,
        // never an error.
        let sink = Arc::new(crate::platform::testing::RecordingCommandSink::new());
        let bridge = IosAudioSessionBridge::new();
        assert!(!bridge.bind(sink, DEFAULT_CATEGORY_OPTIONS));
        assert!(!bridge.is_bound());
        bridge.unbind();
        assert!(!bridge.is_bound());
    }
}
