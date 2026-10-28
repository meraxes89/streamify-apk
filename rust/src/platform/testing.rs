//! Host-lane test doubles — Phase 5 verification contract §1.
//!
//! The "mock WinRT / Keychain / D-Bus harnesses for non-privileged CI
//! execution": every OS surface the adapters talk to has a recording
//! double here, compiled on EVERY target, so the full adapter
//! algorithm (command mapping, cadence, degradation, metadata
//! identity) is audited on plain CI runners — no Windows session, no
//! macOS keychain, no session bus required.
//!
//!   * [`RecordingCommandSink`] — the engine stand-in: records every
//!     dispatched [`crate::contract::MediaCommand`] (and the engine's
//!     debounce verdicts), so tests assert the exact command stream.
//!   * [`MockMediaSurface`] — the OS-chrome stand-in: records every
//!     playback/metadata push the [`crate::platform::adapter::SurfaceAdapter`]
//!     made, with configurable bind behavior (bound / headless /
//!     flaky) to pin the degradation paths.
//!
//! The real platform shells stay cfg-gated to their OS; these doubles
//! are the ones the `rust/tests/test_capability_adapters.rs` suite
//! drives.

use crate::contract::{EngineError, MediaCommand};
use crate::platform::adapter::{OsMediaSurface, OsMetadataImage};
use crate::platform::media_sync::NowPlayingSnapshot;
use crate::platform::MediaCommandSink;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;

// ─────────────────────────────────────────────────────────────────────────────
//  Command sink double
// ─────────────────────────────────────────────────────────────────────────────

/// Recording [`MediaCommandSink`]: every dispatched command lands in
/// `commands()` in arrival order; optional fault injection makes the
/// sink refuse everything (the adapters must log-and-swallow, never
/// propagate into OS callbacks).
#[derive(Debug, Default)]
pub struct RecordingCommandSink {
    commands: Mutex<Vec<MediaCommand>>,
    dispatch_count: AtomicUsize,
    refuse_all: AtomicBool,
}

impl RecordingCommandSink {
    /// Empty recorder.
    pub fn new() -> Self {
        Self::default()
    }

    /// Make every subsequent dispatch return an error (fault
    /// injection for the inbound path's swallow discipline).
    pub fn refuse_dispatches(&self) {
        self.refuse_all.store(true, Ordering::Release);
    }

    /// All commands recorded so far.
    pub fn commands(&self) -> Vec<MediaCommand> {
        self.commands
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// Total dispatch attempts (including refused ones).
    pub fn dispatch_attempts(&self) -> usize {
        self.dispatch_count.load(Ordering::Acquire)
    }

    /// Clear the recording (keeps refusal state).
    pub fn reset(&self) {
        self.commands
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clear();
    }
}

impl MediaCommandSink for RecordingCommandSink {
    fn dispatch_media_command(&self, command: MediaCommand) -> Result<bool, EngineError> {
        self.dispatch_count.fetch_add(1, Ordering::AcqRel);
        if self.refuse_all.load(Ordering::Acquire) {
            return Err(EngineError::Internal {
                message: "injected dispatch fault".to_string(),
                backtrace: None,
            });
        }
        self.commands
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(command);
        Ok(true)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  OS surface double
// ─────────────────────────────────────────────────────────────────────────────

/// The bind behavior a [`MockMediaSurface`] should exhibit.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MockBindBehavior {
    /// Bind succeeds (a healthy OS surface).
    #[default]
    Bound,
    /// The surface reports unavailable (headless host): the graceful
    /// fallback path.
    Unavailable,
    /// The surface reports a genuine bind fault (half-initialized
    /// desktop session): the loud-degradation path.
    Faulty,
}

/// Recording [`OsMediaSurface`]: captures every push for assertions,
/// with configurable [`MockBindBehavior`] and optional push faults
/// (mid-session degradation). Thread-safe by construction — the
/// no-deadlock stress tests hammer it from every thread.
#[derive(Debug, Default)]
pub struct MockMediaSurface {
    behavior: Mutex<MockBindBehavior>,
    playback_pushes: Mutex<Vec<NowPlayingSnapshot>>,
    metadata_pushes: Mutex<Vec<OsMetadataImage>>,
    push_fault: AtomicBool,
}

impl MockMediaSurface {
    /// A surface with the given bind behavior.
    pub fn new(behavior: MockBindBehavior) -> Self {
        Self {
            behavior: Mutex::new(behavior),
            playback_pushes: Mutex::new(Vec::new()),
            metadata_pushes: Mutex::new(Vec::new()),
            push_fault: AtomicBool::new(false),
        }
    }

    /// Switch the bind behavior (lifecycle tests: surface recovering
    /// or dying between attaches).
    pub fn set_behavior(&self, behavior: MockBindBehavior) {
        *self
            .behavior
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = behavior;
    }

    /// Make every subsequent push fail (mid-session degradation).
    pub fn fail_pushes(&self) {
        self.push_fault.store(true, Ordering::Release);
    }

    /// Every playback snapshot pushed so far.
    pub fn playback_pushes(&self) -> Vec<NowPlayingSnapshot> {
        self.playback_pushes
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// Every metadata image pushed so far.
    pub fn metadata_pushes(&self) -> Vec<OsMetadataImage> {
        self.metadata_pushes
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }
}

impl OsMediaSurface for MockMediaSurface {
    fn surface_name(&self) -> &'static str {
        "mock-surface"
    }

    fn bind(&self) -> Result<bool, EngineError> {
        match *self
            .behavior
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
        {
            MockBindBehavior::Bound => Ok(true),
            MockBindBehavior::Unavailable => Ok(false),
            MockBindBehavior::Faulty => Err(EngineError::Storage {
                kind: crate::contract::StorageErrorKind::Io,
            }),
        }
    }

    fn unbind(&self) {
        // Recording-only double: nothing to release.
    }

    fn is_bound(&self) -> bool {
        matches!(
            *self
                .behavior
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
            MockBindBehavior::Bound
        )
    }

    fn push_playback(&self, snapshot: &NowPlayingSnapshot) -> Result<(), EngineError> {
        if self.push_fault.load(Ordering::Acquire) {
            return Err(EngineError::Storage {
                kind: crate::contract::StorageErrorKind::Io,
            });
        }
        self.playback_pushes
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(snapshot.clone());
        Ok(())
    }

    fn push_metadata(&self, image: &OsMetadataImage) -> Result<(), EngineError> {
        if self.push_fault.load(Ordering::Acquire) {
            return Err(EngineError::Storage {
                kind: crate::contract::StorageErrorKind::Io,
            });
        }
        self.metadata_pushes
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(image.clone());
        Ok(())
    }
}

/// Convenience: an adapter over a shared [`MockMediaSurface`] plus the
/// recording handle (the common test shape: the adapter owns its
/// `Arc`, the test keeps a twin for assertions — both see the same
/// recording state behind the interior mutexes).
pub fn mock_adapter(
    behavior: MockBindBehavior,
    min_interval: std::time::Duration,
) -> (
    crate::platform::adapter::SurfaceAdapter<std::sync::Arc<MockMediaSurface>>,
    std::sync::Arc<MockMediaSurface>,
) {
    let surface = std::sync::Arc::new(MockMediaSurface::new(behavior));
    let adapter = crate::platform::adapter::SurfaceAdapter::attach(surface.clone(), min_interval);
    (adapter, surface)
}
