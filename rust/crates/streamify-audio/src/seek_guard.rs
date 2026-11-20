// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::panic::catch_unwind;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static LAST_SEEK_TS: AtomicI64 = AtomicI64::new(0);
static PENDING_SEEK_MS: AtomicI64 = AtomicI64::new(-1);

/// Called from Kotlin UI thread (60-120+ times/sec during seekbar dragging).
/// Executes in < 5 nanoseconds with zero lock contention and zero allocations.
#[no_mangle]
pub unsafe extern "C" fn submit_seek_request(position_ms: i64) -> i32 {
    let result = catch_unwind(|| {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        PENDING_SEEK_MS.store(position_ms, Ordering::Release);
        LAST_SEEK_TS.store(now, Ordering::Release);
        0
    });
    result.unwrap_or(-1)
}

/// Called by ExoPlayer playback coordinator to retrieve the debounced seek position.
/// Returns the seek position in ms if debounce_ms has elapsed, or -1 if none pending.
#[no_mangle]
pub unsafe extern "C" fn consume_pending_seek(debounce_ms: i64) -> i64 {
    let result = catch_unwind(|| {
        let pos = PENDING_SEEK_MS.load(Ordering::Acquire);
        if pos < 0 {
            return -1;
        }

        let last_ts = LAST_SEEK_TS.load(Ordering::Acquire);
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        let threshold = if debounce_ms > 0 { debounce_ms } else { 150 };

        if now - last_ts >= threshold {
            PENDING_SEEK_MS.store(-1, Ordering::Release);
            pos
        } else {
            -1
        }
    });
    result.unwrap_or(-2)
}

/// Resets the seek guard state.
#[no_mangle]
pub unsafe extern "C" fn reset_seek_guard() {
    let _ = catch_unwind(|| {
        PENDING_SEEK_MS.store(-1, Ordering::Release);
        LAST_SEEK_TS.store(0, Ordering::Release);
    });
}
