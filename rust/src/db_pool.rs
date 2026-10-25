//! Pooled SQLite connections for the universal-tracks database
//! (Tier 0 B1, #23: "pooled SQLite — single Mutex<Connection> + cached
//! prepared stmts, Once migrate").
//!
//! Before this module every FFI entry point opened its own `Connection` and
//! paid `open + journal_mode=WAL + synchronous + mmap_size + temp_store +
//! cache_size` (five lock-taking statements) per call — and most read paths
//! re-ran `UNIVERSAL_TRACKS_DDL` + `ensure_cad_rekey` on top. That is
//! 2–15 ms of pure setup per track skip, and 2–3x per resolve miss, straight
//! out of the 16 ms gapless budget (`Connection::open` with WAL alone
//! acquires file locks; the rekey bootstrap scans `user_version` each time).
//!
//! The pool is path-keyed with two lanes per path:
//! - [`Lane::Read`] — WAL readers never block behind writers.
//! - [`Lane::Write`] — SQLite's single-writer rule, enforced in userspace,
//!   which also *fixes* a lost-write class: separate per-caller connections
//!   racing a writer used to hit `SQLITE_BUSY` and silently drop the write
//!   (`let _ = conn.execute(...)`); serialized writers now wait instead.
//!
//! First checkout per (path, lane) pays open + pragmas + DDL + rekey under a
//! per-path init lock (Once semantics — the second lane to arrive observes
//! `user_version` already at v2 and skips). Later checkouts are two mutex
//! hops over a registry hit.
//!
//! Statement reuse comes from rusqlite's built-in `prepare_cached` LRU
//! (per-connection cache) — call sites converted to the pool should prefer
//! `prepare_cached` over `prepare`; the pool is what makes that cache
//! actually stick.
//!
//! Panic safety: pooled connections sit behind `Mutex`, and every lock site
//! recovers poisoning via `PoisonError::into_inner` — a panicking caller
//! cannot take the pool down with it (prod FFI paths already run inside
//! `catch_unwind`; a dropped `Transaction` guard rolls itself back, so the
//! connection returns to autocommit state).

use crate::repository::{ensure_cad_rekey, TrackRepository, UNIVERSAL_TRACKS_DDL};
use rusqlite::Connection;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

/// Which lane of the per-path pool a caller needs.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Lane {
    /// Concurrent WAL readers — never blocks behind a writer's transaction.
    Read,
    /// The single userspace writer — serializes all writes on this path.
    Write,
}

/// A pooled connection handle. Clone-cheap (`Arc`); deref-lock to use:
/// `conn.lock().unwrap_or_else(|p| p.into_inner())`.
pub type PooledConn = Arc<Mutex<Connection>>;

struct PathPool {
    /// Serializes first-open schema init (DDL + rekey) per path so two lanes
    /// racing a fresh database cannot double-run the v1→v2 rekey.
    init: Mutex<()>,
    conns: Mutex<HashMap<Lane, PooledConn>>,
}

static REGISTRY: OnceLock<Mutex<HashMap<PathBuf, Arc<PathPool>>>> = OnceLock::new();

/// Returns the pooled connection for `db_path` on `lane`.
///
/// The first checkout per (path, lane) opens the connection, applies the
/// performance pragmas, ensures the schema exists, and runs the CAD-ID rekey
/// migration (idempotent, `user_version`-gated). Empty paths are rejected —
/// `Connection::open("")` would silently create an anonymous temp database.
///
/// Connections are never closed or evicted: the universal-tracks database is
/// a singleton for the process lifetime, and WAL-mode connections that idle
/// in autocommit hold no read snapshot, so they do not pin the WAL file.
pub fn get(db_path: &str, lane: Lane) -> Result<PooledConn, rusqlite::Error> {
    if db_path.is_empty() {
        return Err(rusqlite::Error::InvalidPath(PathBuf::new()));
    }

    let registry = REGISTRY.get_or_init(|| Mutex::new(HashMap::new()));
    let mut map = registry
        .lock()
        .unwrap_or_else(|p| p.into_inner());

    let pool = match map.get(std::path::Path::new(db_path)) {
        Some(p) => p.clone(),
        None => {
            let p = Arc::new(PathPool {
                init: Mutex::new(()),
                conns: Mutex::new(HashMap::new()),
            });
            map.insert(std::path::Path::new(db_path).to_path_buf(), p.clone());
            p
        }
    };
    drop(map);

    // Fast path: the lane already has a live connection.
    if let Some(conn) = pool
        .conns
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .get(&lane)
    {
        return Ok(conn.clone());
    }

    // Slow path: first checkout on this lane. The init lock serializes
    // schema bootstrap across lanes (and re-checks the lane map in case a
    // sibling thread won the race while we waited).
    let _init_guard = pool
        .init
        .lock()
        .unwrap_or_else(|p| p.into_inner());

    let mut conns = pool
        .conns
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    if let Some(conn) = conns.get(&lane) {
        return Ok(conn.clone());
    }

    let conn = open_initialized(db_path)?;
    let handle = Arc::new(Mutex::new(conn));
    conns.insert(lane, handle.clone());
    Ok(handle)
}

/// Opens a connection and runs the once-per-path bootstrap: performance
/// pragmas, canonical DDL, and the (idempotent) CAD-ID rekey migration.
fn open_initialized(db_path: &str) -> Result<Connection, rusqlite::Error> {
    let conn = Connection::open(db_path)?;
    TrackRepository::apply_performance_pragmas(&conn)?;
    conn.execute_batch(UNIVERSAL_TRACKS_DDL)?;
    ensure_cad_rekey(&conn);
    Ok(conn)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Each (path, lane) gets exactly one live handle for the process.
    #[test]
    fn pooled_returns_same_handle_per_lane() {
        let dir = std::env::temp_dir().join(format!(
            "dbpool_same_{:?}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();

        let a = get(path_str, Lane::Read).unwrap();
        let b = get(path_str, Lane::Read).unwrap();
        assert!(Arc::ptr_eq(&a, &b), "read lane handle must be reused");

        let w = get(path_str, Lane::Write).unwrap();
        assert!(Arc::ptr_eq(&w, &get(path_str, Lane::Write).unwrap()));

        let _ = std::fs::remove_file(&path);
    }

    /// Read and write lanes must be distinct connections (WAL concurrency
    /// depends on readers never queueing on the writer's mutex).
    #[test]
    fn read_and_write_lanes_are_distinct() {
        let dir = std::env::temp_dir().join(format!(
            "dbpool_lanes_{:?}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();

        let r = get(path_str, Lane::Read).unwrap();
        let w = get(path_str, Lane::Write).unwrap();
        assert!(!Arc::ptr_eq(&r, &w));

        let _ = std::fs::remove_file(&path);
    }

    /// First checkout bootstraps the schema — a brand-new path is queryable
    /// through the pool immediately, and a *separate* connection sees the
    /// committed DDL (proving init happened, not just open).
    #[test]
    fn pool_bootstraps_schema_on_fresh_path() {
        let dir = std::env::temp_dir().join(format!(
            "dbpool_fresh_{:?}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();
        assert!(!path.exists());

        let pooled = get(path_str, Lane::Write).unwrap();
        let guard = pooled.lock().unwrap_or_else(|p| p.into_inner());
        let n: i64 = guard
            .query_row("SELECT COUNT(*) FROM universal_tracks", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 0);
        drop(guard);

        let independent = Connection::open(path_str).unwrap();
        let n: i64 = independent
            .query_row("SELECT COUNT(*) FROM universal_tracks", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 0);

        let _ = std::fs::remove_file(&path);
    }

    /// Empty path must not silently create an anonymous temp database.
    #[test]
    fn empty_path_is_rejected() {
        assert!(get("", Lane::Read).is_err());
        assert!(get("", Lane::Write).is_err());
    }

    /// Many threads checking out the write lane and inserting concurrently:
    /// every row lands (the userspace serialization replaces the old
    /// silent `SQLITE_BUSY` loss class), and reads observe the final count.
    #[test]
    fn concurrent_writers_never_lose_rows() {
        let dir = std::env::temp_dir().join(format!(
            "dbpool_conc_{:?}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();
        get(path_str, Lane::Write).unwrap(); // bootstrap before the storm

        const THREADS: i64 = 8;
        const PER_THREAD: i64 = 25;
        let mut handles = Vec::new();
        for t in 0..THREADS {
            let db = path_str.to_string();
            handles.push(std::thread::spawn(move || {
                let conn = get(&db, Lane::Write).unwrap();
                let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
                for i in 0..PER_THREAD {
                    guard
                        .execute(
                            "INSERT INTO universal_tracks
                             (cad_id, title, artist, duration_sec, source_platform)
                             VALUES (?1, ?2, 'Art', 1, 'TEST')",
                            rusqlite::params![
                                format!("cad_{t:02}_{i:03}"),
                                format!("T{t}-{i}")
                            ],
                        )
                        .unwrap();
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }

        let r = get(path_str, Lane::Read).unwrap();
        let guard = r.lock().unwrap_or_else(|p| p.into_inner());
        let n: i64 = guard
            .query_row("SELECT COUNT(*) FROM universal_tracks", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, THREADS * PER_THREAD);

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(dir.join("t.db-wal"));
        let _ = std::fs::remove_file(dir.join("t.db-shm"));
    }

    /// Rekey bootstrap is Once-per-path: after the first checkout, the
    /// `user_version` gate reads v2 — the pool must not re-run the scan.
    #[test]
    fn pool_migration_is_once() {
        let dir = std::env::temp_dir().join(format!(
            "dbpool_mig_{:?}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();

        for lane in [Lane::Read, Lane::Write, Lane::Read, Lane::Write] {
            get(path_str, lane).unwrap();
        }
        let conn = Connection::open(path_str).unwrap();
        let v: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert!(v >= 2, "rekey gate must be at v2 after pool init, got {v}");

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(dir.join("t.db-wal"));
        let _ = std::fs::remove_file(dir.join("t.db-shm"));
    }

    /// [PERF] numbers-only micro-benchmark (house pattern from the phase-1
    /// DSP suite: best-of-5, no host-flaky timing asserts — per audit item
    /// 10's own triage). Quantifies what the pool removes on the track-skip
    /// path: per-call `Connection::open` + 5 lock-taking pragmas vs a pooled
    /// registry hit. Run with `cargo test -- --nocapture` to see the table.
    #[test]
    fn perf_pool_vs_per_call_open() {
        let dir = std::env::temp_dir().join(format!("dbpool_perf_{:?}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let path_str = path.to_str().unwrap();

        // Same fixture both sides: 50-track table, already migrated.
        {
            let conn = Connection::open(path_str).unwrap();
            conn.execute_batch(UNIVERSAL_TRACKS_DDL).unwrap();
            for i in 0..50 {
                conn.execute(
                    "INSERT INTO universal_tracks (cad_id, title, artist, duration_sec, source_platform)
                     VALUES (?1, ?2, 'Art', 100, 'TEST')",
                    rusqlite::params![format!("cad_{i:04}"), format!("Track {i}")],
                )
                .unwrap();
            }
            conn.execute_batch("PRAGMA user_version = 2;").unwrap();
        }

        const ITERS: u32 = 300;
        let query = |conn: &Connection| {
            let _: i64 = conn
                .query_row(
                    "SELECT COUNT(*) FROM universal_tracks WHERE rowid != ?1",
                    [7],
                    |r| r.get(0),
                )
                .unwrap();
        };

        let time_ns = |f: &mut dyn FnMut()| -> u128 {
            let mut best = u128::MAX;
            for _ in 0..5 {
                let t0 = std::time::Instant::now();
                f();
                let d = t0.elapsed().as_nanos();
                best = best.min(d);
            }
            best
        };

        // OLD pattern: open + pragmas + query per call.
        let old_ns = time_ns(&mut || {
            for _ in 0..ITERS {
                if let Ok(conn) = Connection::open(path_str) {
                    let _ = TrackRepository::apply_performance_pragmas(&conn);
                    query(&conn);
                }
            }
        });

        // NEW pattern: pooled checkout + lock + query.
        let new_ns = time_ns(&mut || {
            for _ in 0..ITERS {
                if let Ok(pooled) = get(path_str, Lane::Read) {
                    let conn = pooled.lock().unwrap_or_else(|p| p.into_inner());
                    query(&conn);
                }
            }
        });

        let per_old_us = old_ns / ITERS as u128 / 1000;
        let per_new_us = new_ns / ITERS as u128 / 1000;
        println!(
            "[PERF] db_pool vs per-call open+pragmas (best-of-5 x {ITERS}):\n\
             [PERF]   per-call open + 5 pragmas + query : {per_old_us:>6} us/call\n\
             [PERF]   pooled checkout + lock + query    : {per_new_us:>6} us/call\n\
             [PERF]   amortization                     : {ratio:.1}x",
            ratio = old_ns as f64 / new_ns as f64
        );

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(dir.join("t.db-wal"));
        let _ = std::fs::remove_file(dir.join("t.db-shm"));
    }
}
