// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::ffi::CStr;
use std::panic::catch_unwind;
use std::sync::atomic::{AtomicI32, Ordering};

use crate::db_pool::{self, Lane};

static THERMAL_STATUS: AtomicI32 = AtomicI32::new(0); // 0 = None, 1 = Light, 2 = Moderate, 3 = Severe, 4 = Critical, 5 = Emergency

#[no_mangle]
pub unsafe extern "C" fn update_thermal_status(status: i32) -> i32 {
    let result = catch_unwind(|| {
        THERMAL_STATUS.store(status, Ordering::Relaxed);
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn get_thermal_status() -> i32 {
    THERMAL_STATUS.load(Ordering::Relaxed)
}

#[no_mangle]
pub unsafe extern "C" fn flush_database_wal(db_path_ptr: *const std::os::raw::c_char) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() {
            return -1;
        }
        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        if db_path.is_empty() {
            return -1;
        }

        // Tier 0 B1 (#23): pooled write-lane connection — periodic WAL
        // maintenance no longer pays open + pragmas per flush. TRUNCATE
        // checkpoints succeed because pooled read-lane conns idle in
        // autocommit hold no WAL pin between statements.
        match db_pool::get(db_path, Lane::Write) {
            Ok(pooled) => {
                let conn = pooled.lock().unwrap_or_else(|p| p.into_inner());
                let _ =
                    conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE); PRAGMA shrink_memory;");
                0
            }
            Err(_) => -2,
        }
    });
    result.unwrap_or(-3)
}
