//! The segmented-range source abstraction (RFC §5.1 stage 1).
//!
//! The producer never performs I/O itself: it asks a [`RangeFetcher`]
//! for byte windows of the resolved stream URL. The production HTTP
//! lane plugs in from `streamify-contract` (reusing the
//! `streamify-resolver` downloader discipline: shared runtime, 8 s
//! connect / 15 s idle-read hang guards, incremental SHA-256); the
//! hermetic suites drive [`MemoryRangeFetcher`] with scripted loss and
//! jitter. Keeping the trait in this crate keeps the playback crate
//! free of any async/HTTP dependency (the RT boundary).

use std::collections::HashMap;
use std::sync::Mutex;

use streamify_core::{EngineError, NetworkErrorKind};

/// One segmented-range source: pull byte windows of a track's
/// compressed stream. Implementations MUST be `Send + Sync` (the
/// producer thread calls them; nothing on the audio callback ever
/// does).
///
/// Contract:
/// * `fetch_range(url, start, len)` returns UP TO `len` bytes starting
///   at `start`. An EMPTY return means the source is exhausted past
///   `start` (the producer finishes its decode session).
/// * A transient failure returns `EngineError::Network` — the producer
///   applies its bounded retry policy.
/// * Window sizes are chosen by the CALLER (the producer's buffering
///   budget); the implementation may return fewer bytes (a truncated
///   range — network reality), never more, never bytes outside
///   `[start, start + len)`.
pub trait RangeFetcher: Send + Sync {
    fn fetch_range(&self, url: &str, start: u64, len: u64) -> Result<Vec<u8>, EngineError>;
}

/// An in-memory fixture source: whole byte blobs keyed by URL
/// (fixture keys like `fixture://playback_selftest_440hz_48k.wav`).
///
/// Test-grade by design — served ranges truncate deterministically at
/// the blob end; a URL that never existed returns
/// `NetworkErrorKind::Http` with a 404 status so the retry/ladder
/// paths stay exercisable hermetically.
#[derive(Debug, Default)]
pub struct MemoryRangeFetcher {
    blobs: Mutex<HashMap<String, std::sync::Arc<Vec<u8>>>>,
}

impl MemoryRangeFetcher {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register one source blob under its URL key (cheap `Arc` clone).
    pub fn register(&self, url: &str, bytes: std::sync::Arc<Vec<u8>>) {
        self.blobs
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(url.to_string(), bytes);
    }
}

impl RangeFetcher for MemoryRangeFetcher {
    fn fetch_range(&self, url: &str, start: u64, len: u64) -> Result<Vec<u8>, EngineError> {
        let blobs = self
            .blobs
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(blob) = blobs.get(url) else {
            return Err(EngineError::Network {
                kind: NetworkErrorKind::Http,
                status: 404,
                retry_after: 0,
            });
        };
        let start = start.min(blob.len() as u64) as usize;
        let end = (start as u64 + len).min(blob.len() as u64) as usize;
        Ok(blob[start..end].to_vec())
    }
}

/// The test double the A5 harness drives: a memory source with
/// scripted per-call faults — truncated ranges (loss), hard errors
/// (connection resets), and artificial delay (jitter) — all
/// deterministic (a SplitMix64 sequence keyed by the call index), so
/// the self-test suite never flakes.
///
/// This lives in the library (not a test file) because the
/// `streamify-contract` self-test harness drives it through the
/// engine's installed fetcher seam.
pub struct LossyFixtureFetcher {
    inner: MemoryRangeFetcher,
    state: Mutex<LossyState>,
    /// Every `drop_every`-th call returns a range truncated to
    /// `truncate_to` bytes (0 = the full short read).
    pub drop_every: u64,
    pub truncate_to: usize,
    /// Every `fail_every`-th call fails as a timeout.
    pub fail_every: u64,
    /// Artificial per-call delay (jitter model); the producer's park
    /// loop turns this into realistic buffer dips.
    pub delay_ms: u64,
}

#[derive(Debug, Default)]
struct LossyState {
    calls: u64,
    delays_taken: u64,
}

impl LossyFixtureFetcher {
    /// A deterministic loss/jitter profile over the registered blobs.
    pub fn new(drop_every: u64, truncate_to: usize, fail_every: u64, delay_ms: u64) -> Self {
        Self {
            inner: MemoryRangeFetcher::new(),
            state: Mutex::new(LossyState::default()),
            drop_every,
            truncate_to,
            fail_every,
            delay_ms,
        }
    }

    /// Register one source blob (delegates to the memory core).
    pub fn register(&self, url: &str, bytes: std::sync::Arc<Vec<u8>>) {
        self.inner.register(url, bytes);
    }

    /// Total artificial delay taken so far (the harness's budget
    /// accounting — keeps the suite's wall time bounded).
    pub fn delays_taken(&self) -> u64 {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .delays_taken
    }
}

impl RangeFetcher for LossyFixtureFetcher {
    fn fetch_range(&self, url: &str, start: u64, len: u64) -> Result<Vec<u8>, EngineError> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.calls += 1;
        let call = state.calls;
        if self.delay_ms > 0 && call.is_multiple_of(4) {
            // Jitter: every 4th range pays the artificial delay.
            state.delays_taken += 1;
            std::thread::sleep(std::time::Duration::from_millis(self.delay_ms));
        }
        if self.fail_every > 0 && call.is_multiple_of(self.fail_every) {
            return Err(EngineError::Network {
                kind: NetworkErrorKind::Timeout,
                status: 0,
                retry_after: 1,
            });
        }
        let mut got = self.inner.fetch_range(url, start, len)?;
        if self.drop_every > 0 && call.is_multiple_of(self.drop_every) {
            // Loss: the range arrives truncated — the producer continues
            // from the shorter frontier.
            got.truncate(self.truncate_to.min(got.len()));
        }
        Ok(got)
    }
}
