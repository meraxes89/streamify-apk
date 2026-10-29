//! Windows System Media Transport Controls adapter — Phase 5 / MP-CAP01.
//!
//! Wraps `Windows.Media.SystemMediaTransportControls` (SMTC) — the
//! Windows 10+ taskbar/lock-screen/flyout media chrome and the hardware
//! media-key surface:
//!
//! * **binding** — `ISystemMediaTransportControlsInterop::GetForWindow`
//!   on the shell's top-level HWND (the desktop shell passes it through
//!   `OsMediaIntegration::start(window_handle)`; the activation factory
//!   is fetched with `windows::core::factory`). No HWND (0), a service
//!   session, or a headless host → the adapter stays unbound: every
//!   update is a no-op `Ok(())`, nothing crashes (MP-CAP01's graceful
//!   fallback mandate). `CoIncrementMTAUsage` arms a process MTA
//!   apartment once so the WinRT event delegate has a home thread
//!   pool even before any COM init on the calling thread.
//! * **inbound** — `ButtonPressed` (Play / Pause / Stop / Next /
//!   Previous; the TV/trick-play buttons are ignored by the pure
//!   mapping table) maps onto [`MediaCommand`] and dispatches through
//!   the engine's debounced `on_media_command` (the
//!   [`crate::platform::MediaCommandSink`] contract). The WinRT
//!   handler closure runs on an MTA thread; the engine's entry point
//!   is lock-bounded and callback-free, so the dispatch cannot
//!   deadlock the COM apartment.
//! * **outbound** — `PlaybackStatus` + `UpdateTimelineProperties` +
//!   `DisplayUpdater.MusicProperties`, driven through the shared
//!   [`SurfaceAdapter`] cadence gate (200 ms, latest-wins coalescing —
//!   no COM apartment flooding from per-frame property pushes). Rate
//!   NaN/∞ fails safe to paused.
//!
//! Everything cross-platform (the mapping table, the cadence, the
//! headless degradation) lives in
//! [`crate::platform::adapter`]/[`crate::platform::media_sync`] and is
//! audited on every CI host; this module is the WinRT glue only.

use crate::contract::{EngineError, StorageErrorKind};
use crate::platform::adapter::{OsMediaSurface, OsMetadataImage, SurfaceAdapter};
use crate::platform::media_sync::{
    smtc_button_to_command, NowPlayingSnapshot, PlaybackSyncState, SmtcButton,
};
use crate::platform::{MediaCommandSink, SMTC_SYNC_MIN_INTERVAL};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Once};
use windows::core::HSTRING;
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, SystemMediaTransportControls,
    SystemMediaTransportControlsButton, SystemMediaTransportControlsButtonPressedEventArgs,
    SystemMediaTransportControlsTimelineProperties,
};
use windows::Win32::Foundation::HWND;
use windows::Win32::System::Com::CoIncrementMTAUsage;
use windows::Win32::System::WinRT::ISystemMediaTransportControlsInterop;

/// The SMTC-backed adapter handle (`OsMediaIntegration`'s Windows
/// surface): the shared sync algorithm over [`WinRtSmtcSurface`].
pub type SmtcAdapter = SurfaceAdapter<WinRtSmtcSurface>;

/// Attach the SMTC surface for `window_handle` (the shell's top-level
/// HWND; 0 = headless → unbound adapter, never an error) with the
/// engine command sink wired into `ButtonPressed`.
pub fn attach_to_window(sink: Arc<dyn MediaCommandSink>, window_handle: u64) -> SmtcAdapter {
    let surface = WinRtSmtcSurface::new(sink, window_handle);
    SurfaceAdapter::attach(surface, SMTC_SYNC_MIN_INTERVAL)
}

/// The WinRT glue shell: one bound `ISystemMediaTransportControls`
/// instance plus its `ButtonPressed` registration token.
pub struct WinRtSmtcSurface {
    sink: Arc<dyn MediaCommandSink>,
    window_handle: u64,
    controls: Mutex<Option<SystemMediaTransportControls>>,
    button_token: Mutex<Option<i64>>,
    bound: AtomicBool,
}

impl WinRtSmtcSurface {
    /// Build the surface around one window handle.
    pub fn new(sink: Arc<dyn MediaCommandSink>, window_handle: u64) -> Self {
        Self {
            sink,
            window_handle,
            controls: Mutex::new(None),
            button_token: Mutex::new(None),
            bound: AtomicBool::new(false),
        }
    }
}

/// One-time process MTA apartment arming: WinRT event delegates need
/// an apartment to be dispatched on; `CoIncrementMTAUsage` is the
/// documented no-message-pump way to guarantee one exists for the
/// lifetime of the process (idempotent, no thread affinity).
static MTA_ARMED: Once = Once::new();

fn arm_mta() {
    MTA_ARMED.call_once(|| {
        // A failure here is non-fatal: the shell's own COM init (any
        // Windows UI framework initializes COM) usually covers us; if
        // not, ButtonPressed stays silent but nothing crashes.
        if let Err(err) = unsafe { CoIncrementMTAUsage() } {
            tracing::warn!(
                target: "streamify::platform::smtc",
                error = %err,
                "CoIncrementMTAUsage failed — SMTC events depend on the host's COM init"
            );
        }
    });
}

/// Map the WinRT button enum onto the pure mapping table's input.
fn winrt_button_to_pure(button: SystemMediaTransportControlsButton) -> SmtcButton {
    // The integer discriminants are pinned by the WinRT contract
    // (Play=0, Pause=1, Stop=2, ..., Next=6, Previous=7); mapping
    // through the constants keeps this honest against any future
    // projection reshuffle.
    if button == SystemMediaTransportControlsButton::Play {
        SmtcButton::Play
    } else if button == SystemMediaTransportControlsButton::Pause {
        SmtcButton::Pause
    } else if button == SystemMediaTransportControlsButton::Stop {
        SmtcButton::Stop
    } else if button == SystemMediaTransportControlsButton::Next {
        SmtcButton::Next
    } else if button == SystemMediaTransportControlsButton::Previous {
        SmtcButton::Previous
    } else if button == SystemMediaTransportControlsButton::FastForward {
        SmtcButton::FastForward
    } else if button == SystemMediaTransportControlsButton::Rewind {
        SmtcButton::Rewind
    } else {
        SmtcButton::Other
    }
}

/// Map the playback sync state onto the WinRT `MediaPlaybackStatus`
/// (Buffering → `Changing`, the closest SMTC concept).
fn sync_state_to_winrt(state: PlaybackSyncState) -> MediaPlaybackStatus {
    match state {
        PlaybackSyncState::Playing => MediaPlaybackStatus::Playing,
        PlaybackSyncState::Paused => MediaPlaybackStatus::Paused,
        PlaybackSyncState::Stopped => MediaPlaybackStatus::Stopped,
        PlaybackSyncState::Buffering => MediaPlaybackStatus::Changing,
    }
}

/// Milliseconds → WinRT `TimeSpan` (100 ns units).
fn ms_to_time_span(ms: u64) -> windows::Foundation::TimeSpan {
    windows::Foundation::TimeSpan {
        Duration: (ms as i64).saturating_mul(10_000),
    }
}

fn smtc_fault(context: &str) -> EngineError {
    tracing::warn!(
        target: "streamify::platform::smtc",
        context,
        "SMTC call failed"
    );
    // SMTC faults are control-plane chrome failures, not audio
    // pipeline faults; the Storage category is the closest typed
    // "system service unavailable" bucket without inventing a new
    // category (the taxonomy is frozen — MP-C04).
    EngineError::Storage {
        kind: StorageErrorKind::Io,
    }
}

impl OsMediaSurface for WinRtSmtcSurface {
    fn surface_name(&self) -> &'static str {
        "smtc"
    }

    fn bind(&self) -> Result<bool, EngineError> {
        // Headless / service session / shell not ready: GetForWindow
        // REQUIRES a real window; 0 means "no window" and attaching
        // would be a hard error on a nonexistent HWND. Graceful: the
        // unbound adapter is the documented no-op fallback.
        if self.window_handle == 0 {
            return Ok(false);
        }

        arm_mta();

        let interop: ISystemMediaTransportControlsInterop =
            windows::core::factory::<SystemMediaTransportControls, _>()
                .map_err(|err| {
                    tracing::warn!(
                        target: "streamify::platform::smtc",
                        error = %err,
                        "SMTC interop activation factory unavailable (non-interactive session?)"
                    );
                    EngineError::Storage {
                        kind: StorageErrorKind::Io,
                    }
                })?;

        let controls: SystemMediaTransportControls = unsafe {
            interop
                .GetForWindow(HWND(self.window_handle as *mut core::ffi::c_void))
                .map_err(|err| {
                    tracing::warn!(
                        target: "streamify::platform::smtc",
                        error = %err,
                        "GetForWindow failed — staying headless"
                    );
                    EngineError::Storage {
                        kind: StorageErrorKind::Io,
                    }
                })?
        };

        // The five transport buttons we own; channel/record stay dark.
        // (Typed as plain fn pointers: each method is a distinct fn
        // item type, which would not unify in the array literal.)
        let button_setters: [fn(
            &SystemMediaTransportControls,
            bool,
        ) -> windows::core::Result<()>; 5] = [
            SystemMediaTransportControls::SetIsPlayEnabled,
            SystemMediaTransportControls::SetIsPauseEnabled,
            SystemMediaTransportControls::SetIsStopEnabled,
            SystemMediaTransportControls::SetIsNextEnabled,
            SystemMediaTransportControls::SetIsPreviousEnabled,
        ];
        for (setter, value) in button_setters.map(|setter| (setter, true)) {
            if let Err(err) = setter(&controls, value) {
                tracing::warn!(
                    target: "streamify::platform::smtc",
                    error = %err,
                    "SMTC button enable failed"
                );
            }
        }

        // ButtonPressed → MediaCommand dispatch on the MTA thread.
        // (Handler contract: Fn(Ref<_>, Ref<_>) -> Result<()>; the args
        // Ref is null-checked before use — WinRT may deliver a null
        // payload on teardown races.)
        let sink = self.sink.clone();
        let handler = windows::Foundation::TypedEventHandler::<
            SystemMediaTransportControls,
            SystemMediaTransportControlsButtonPressedEventArgs,
        >::new(move |_sender, args| {
            if let Some(args) = args.as_ref() {
                if let Ok(button) = args.Button() {
                    if let Some(command) = smtc_button_to_command(winrt_button_to_pure(button)) {
                        crate::platform::adapter::dispatch_inbound(&sink, command);
                    }
                }
            }
            Ok(())
        });
        // windows 0.62's Param contract: event registration takes the
        // handler by reference and clones it into the COM event table.
        let token = controls
            .ButtonPressed(&handler)
            .map_err(|err| {
                tracing::warn!(
                    target: "streamify::platform::smtc",
                    error = %err,
                    "SMTC ButtonPressed subscription failed"
                );
                smtc_fault("subscribe")
            })?;

        *self
            .button_token
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(token);
        *self
            .controls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(controls);
        self.bound.store(true, Ordering::Release);
        Ok(true)
    }

    fn unbind(&self) {
        if let Some(token) = self
            .button_token
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
        {
            if let Some(controls) = self
                .controls
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .as_ref()
            {
                let _ = controls.RemoveButtonPressed(token);
            }
        }
        *self
            .controls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
        self.bound.store(false, Ordering::Release);
    }

    fn is_bound(&self) -> bool {
        self.bound.load(Ordering::Acquire)
    }

    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError> {
        let guard = self
            .controls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(controls) = guard.as_ref() else {
            return Ok(());
        };

        controls
            .SetPlaybackStatus(sync_state_to_winrt(snapshot.state))
            .map_err(|_| smtc_fault("SetPlaybackStatus"))?;

        // Timeline: [0, duration] window with the live position. The
        // cadence gate upstream keeps this at ≤5 Hz — one COM property
        // call per window, never per frame.
        if snapshot.duration_ms > 0 {
            let timeline = SystemMediaTransportControlsTimelineProperties::new()
                .map_err(|_| smtc_fault("TimelineProperties::new"))?;
            timeline
                .SetStartTime(ms_to_time_span(0))
                .map_err(|_| smtc_fault("SetStartTime"))?;
            timeline
                .SetEndTime(ms_to_time_span(snapshot.duration_ms))
                .map_err(|_| smtc_fault("SetEndTime"))?;
            timeline
                .SetPosition(ms_to_time_span(snapshot.position_ms.min(snapshot.duration_ms)))
                .map_err(|_| smtc_fault("SetPosition"))?;
            controls
                .UpdateTimelineProperties(&timeline)
                .map_err(|_| smtc_fault("UpdateTimelineProperties"))?;
        }
        Ok(())
    }

    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError> {
        let guard = self
            .controls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(controls) = guard.as_ref() else {
            return Ok(());
        };

        let updater = controls
            .DisplayUpdater()
            .map_err(|_| smtc_fault("DisplayUpdater"))?;
        updater
            .SetType(MediaPlaybackType::Music)
            .map_err(|_| smtc_fault("SetType"))?;
        let music = updater
            .MusicProperties()
            .map_err(|_| smtc_fault("MusicProperties"))?;
        music
            .SetTitle(&HSTRING::from(&image.title))
            .map_err(|_| smtc_fault("SetTitle"))?;
        music
            .SetArtist(&HSTRING::from(&image.artist))
            .map_err(|_| smtc_fault("SetArtist"))?;
        if let Some(album) = image.album.as_deref() {
            music
                .SetAlbumTitle(&HSTRING::from(album))
                .map_err(|_| smtc_fault("SetAlbumTitle"))?;
        }
        updater.Update().map_err(|_| smtc_fault("Update"))?;
        Ok(())
    }
}

// The attach entry point used by OsMediaIntegration (mod.rs) is
// `attach_to_window` above — the cfg(windows) surface keeps its
// construction logic in this module, one place.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn winrt_button_constants_are_the_pinned_discriminants() {
        // Pin the WinRT enum values the mapping depends on (the pure
        // table maps OUR enum; this guards the WinRT→pure edge).
        assert_eq!(SystemMediaTransportControlsButton::Play.0, 0);
        assert_eq!(SystemMediaTransportControlsButton::Pause.0, 1);
        assert_eq!(SystemMediaTransportControlsButton::Stop.0, 2);
        assert_eq!(SystemMediaTransportControlsButton::Next.0, 6);
        assert_eq!(SystemMediaTransportControlsButton::Previous.0, 7);
    }

    use crate::platform::{OsMetadataUpdate, OsPlaybackUpdate};

    #[test]
    fn headless_attach_is_a_no_op_not_an_error() {
        // window_handle 0 = headless: the surface must report
        // unbindable (false), and every adapter update stays a no-op
        // Ok — MP-CAP01's graceful fallback, pinned on real Windows.
        let sink = Arc::new(crate::platform::testing::RecordingCommandSink::new());
        let adapter = attach_to_window(sink, 0);
        assert!(!adapter.is_surface_bound());
        adapter
            .update_playback(&OsPlaybackUpdate {
                state: crate::contract::PlaybackState::Playing,
                position_ms: 1000,
                duration_ms: 100_000,
                rate: 1.0,
            })
            .unwrap();
        adapter
            .update_metadata(OsMetadataUpdate {
                title: "t".into(),
                artist: "a".into(),
                album: None,
                duration_ms: 1,
                art_url: None,
            })
            .unwrap();
    }
}
