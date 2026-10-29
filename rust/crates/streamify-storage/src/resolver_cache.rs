//! Resolver memoization layer — Discussion #120, Part B.4.
//!
//! "Offline / Fast LRU Caching": the resolver's repeat-resolve path must
//! answer from device-local SQLite in well under the 10 ms budget — a
//! back-to-back resolve of the same track (gapless pre-buffer, queue
//! refill after a seek) may never pay the network again while its cached
//! answer is still provably fresh.
//!
//! Three tables, all riding the same per-path pooled lanes
//! ([`crate::db_pool`]) as the universal-tracks schema — one database
//! file per device, one WAL, one lane discipline:
//!
//! * `resolver_stream_cache` — resolved CDN format sets per video id.
//!   Rows carry the TTL derived from the player response's own
//!   `valid_until` (the caller computes `valid_until - safety margin`;
//!   see `streamify-resolver::rustypipe_engine::cdn_ttl_seconds`) and
//!   expire strictly BEFORE the URLs do — the "safety TTL margin"
//!   Discussion #120 mandates. LRU-bounded (512 rows) so a
//!   channel-flip binge cannot grow the table unboundedly.
//! * `resolver_search_cache` — YouTube Music search result pages per
//!   (query, limit), with a fixed 24 h TTL (search pages carry no
//!   server-provided expiry; the video-id bindings they feed are
//!   permanent, the page merely re-ranks).
//! * `resolver_meta_cache` — small key/value rows for the resolver's
//!   long-lived metadata, most importantly the rustypipe deobfuscation
//!   payload (client versions + the deciphered player.js transforms)
//!   under the `rustypipe_deobf` key. TTL-less by design: the
//!   deobfuscation payload is versioned content whose freshness the
//!   owner revalidates (rustypipe falls back to a cold fetch when
//!   YouTube rotates player.js).
//!
//! LAYERING: this module is deliberately type-opaque. It stores and
//! returns JSON strings; the resolver crate owns the record types and
//! the serde envelopes. That keeps the storage crate a leaf domain
//! (no dependency upward), matching the Discussion #120 DAG.
//!
//! DATABASE PATH BINDING: the cache needs a database file, but the
//! resolver's modern surfaces (`resolve_stream(video_id)`,
//! `search_tracks(query)`) do not carry one, and the shells own the
//! real path. The binding is therefore established once per process,
//! in priority order:
//!
//!   1. [`set_cache_db_path`] — explicit setter (the UniFFI contract
//!      exposes it as `set_resolver_cache_db`; desktop and Android
//!      shells may call it at init), or
//!   2. `bind_cache_db_path` — the implicit bind: the first
//!      `resolve_track_cdn` call (whose `db_path` the Kotlin shell
//!      has passed since Phase 0) lends its path to the cache.
//!
//! Until a path is bound every accessor degrades to a no-op / `None`
//! (process-lifetime in-memory behavior only, via the resolver's own
//! singletons) — never an error, and never a fallback to an anonymous
//! temp database.
//!
//! THREADING: every operation takes the pool's WRITE lane. Cache reads
//! also bump `last_used` for the LRU order, so there are no pure reads
//! on the hot path; the resolver calls these from its network-bound FFI
//! threads (never the audio callback thread), where lane serialization
//! costs one mutex hop against a resolved in milliseconds.

use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::params;

use crate::db_pool::{self, Lane};

/// Maximum cached CDN format sets (LRU bound). 512 rows is ~4 MB of
/// JSON at the p95 format-set size (measured ~8 KB), about one week of
/// heavy unique-track listening — beyond it, the least recently used
/// rows are the least likely to be re-resolved anyway.
const MAX_STREAM_ROWS: usize = 512;

/// Maximum cached search pages (LRU bound).
const MAX_SEARCH_ROWS: usize = 128;

/// The bound cache database path (process-lifetime singleton).
static CACHE_DB_PATH: OnceLock<String> = OnceLock::new();

/// Paths whose cache DDL has been ensured. The pool boots each fresh
/// database with the universal-tracks DDL under its own per-path init
/// lock; this set extends the same once-per-path discipline to the
/// resolver cache tables without re-opening connections.
static ENSURED_PATHS: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

/// `resolver_stream_cache` + `resolver_search_cache` +
/// `resolver_meta_cache` DDL (idempotent, WAL-safe).
const RESOLVER_CACHE_DDL: &str = "
CREATE TABLE IF NOT EXISTS resolver_stream_cache (
    video_id     TEXT PRIMARY KEY,
    formats_json TEXT NOT NULL,
    resolved_at  INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    last_used    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS resolver_search_cache (
    query        TEXT NOT NULL,
    result_limit INTEGER NOT NULL,
    results_json TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    last_used    INTEGER NOT NULL,
    PRIMARY KEY (query, result_limit)
);
CREATE TABLE IF NOT EXISTS resolver_meta_cache (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);";

/// Wall-clock seconds since the UNIX epoch (cache TTL arithmetic runs
/// at second granularity — YouTube URL lifetimes are quoted in hours).
fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// The bound cache database path, if any.
pub fn cache_db_path() -> Option<&'static str> {
    CACHE_DB_PATH.get().map(|s| s.as_str())
}

/// Explicitly bind the resolver cache database path (UniFFI
/// `set_resolver_cache_db`).
///
/// First call wins: a shell's init-time binding is authoritative and
/// later calls (including accidental double-calls) are ignored — the
/// RustyPipe client built after the first binding reads its
/// deobfuscation cache from this file, and silently moving the
/// database under a live client would fork the cache identity. Empty
/// paths are ignored (an anonymous SQLite database is never created).
pub fn set_cache_db_path(path: &str) {
    let clean = path.trim();
    if clean.is_empty() {
        return;
    }
    let _ = CACHE_DB_PATH.set(clean.to_string());
}

/// Lend a database path to the cache when none is bound yet (the
/// implicit bind — see the module docs). Returns the effective bound
/// path.
pub fn bind_cache_db_path(path: &str) -> Option<&'static str> {
    let clean = path.trim();
    if !clean.is_empty() {
        let _ = CACHE_DB_PATH.set(clean.to_string());
    }
    cache_db_path()
}

/// Run the cache DDL once per bound path (idempotent; guarded by the
/// ENSURED_PATHS set so steady-state cache access pays exactly one
/// registry hit and zero DDL statements).
fn ensure_cache_tables(db_path: &str) -> rusqlite::Result<()> {
    let registry = ENSURED_PATHS.get_or_init(|| Mutex::new(HashSet::new()));
    {
        let guard = registry.lock().unwrap_or_else(|p| p.into_inner());
        if guard.contains(db_path) {
            return Ok(());
        }
    }

    let conn = db_pool::get(db_path, Lane::Write)?;
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
    guard.execute_batch(RESOLVER_CACHE_DDL)?;

    let mut registry_guard = registry.lock().unwrap_or_else(|p| p.into_inner());
    registry_guard.insert(db_path.to_string());
    Ok(())
}

/// Cached resolved CDN format set for one video id, as the caller's
/// JSON envelope (the resolver stores its `Vec<ResolvedStreamFormat>`
/// serialization).
///
/// `None` when no path is bound, the row is missing, or its TTL has
/// elapsed — the caller resolves through the network and [`put`]s the
/// fresh set. A hit bumps `last_used` for the LRU order.
pub fn get_stream_formats(video_id: &str) -> Option<String> {
    let db_path = cache_db_path()?;
    ensure_cache_tables(db_path).ok()?;

    let conn = db_pool::get(db_path, Lane::Write).ok()?;
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    let now = unix_now();
    let hit = guard
        .query_row(
            "SELECT formats_json FROM resolver_stream_cache
             WHERE video_id = ?1 AND expires_at > ?2",
            params![video_id, now],
            |row| row.get::<_, String>(0),
        )
        .ok()?;

    // LRU bump: the row stays hot (the eviction order is recency of
    // use, not recency of resolve).
    let _ = guard.execute(
        "UPDATE resolver_stream_cache SET last_used = ?1 WHERE video_id = ?2",
        params![now, video_id],
    );
    Some(hit)
}

/// Persist a resolved CDN format set for one video id with a TTL in
/// seconds (the caller applies the safety margin before calling —
/// see the module docs). LRU-evicts beyond [`MAX_STREAM_ROWS`] rows
/// and opportunistically prunes expired rows. Best-effort by design:
/// cache faults never fail a resolve.
pub fn put_stream_formats(video_id: &str, formats_json: &str, ttl_seconds: u64) {
    let Some(db_path) = cache_db_path() else {
        return;
    };
    if ensure_cache_tables(db_path).is_err() {
        return;
    }
    let Ok(conn) = db_pool::get(db_path, Lane::Write) else {
        return;
    };
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    let now = unix_now();
    let _ = guard.execute(
        "INSERT INTO resolver_stream_cache (video_id, formats_json, resolved_at, expires_at, last_used)
         VALUES (?1, ?2, ?3, ?3 + ?4, ?3)
         ON CONFLICT(video_id) DO UPDATE SET
            formats_json = excluded.formats_json,
            resolved_at  = excluded.resolved_at,
            expires_at   = excluded.expires_at,
            last_used    = excluded.last_used",
        params![video_id, formats_json, now, ttl_seconds as i64],
    );

    // Expired rows go first; the LRU bound trims what survives.
    let _ = guard.execute(
        "DELETE FROM resolver_stream_cache WHERE expires_at <= ?1",
        params![now],
    );
    let _ = guard.execute(
        "DELETE FROM resolver_stream_cache WHERE video_id NOT IN (
             SELECT video_id FROM resolver_stream_cache
             ORDER BY last_used DESC LIMIT ?1)",
        params![MAX_STREAM_ROWS as i64],
    );
}

/// Cached search page for one (query, limit) as the caller's JSON
/// envelope, when still inside its TTL. A hit bumps `last_used`.
pub fn get_search_results(query: &str, limit: usize) -> Option<String> {
    let db_path = cache_db_path()?;
    ensure_cache_tables(db_path).ok()?;

    let conn = db_pool::get(db_path, Lane::Write).ok()?;
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    let now = unix_now();
    let hit = guard
        .query_row(
            "SELECT results_json FROM resolver_search_cache
             WHERE query = ?1 AND result_limit = ?2 AND expires_at > ?3",
            params![query, limit as i64, now],
            |row| row.get::<_, String>(0),
        )
        .ok()?;

    let _ = guard.execute(
        "UPDATE resolver_search_cache SET last_used = ?1
         WHERE query = ?2 AND result_limit = ?3",
        params![now, query, limit as i64],
    );
    Some(hit)
}

/// Persist a search page with a TTL in seconds; LRU-evicts beyond
/// [`MAX_SEARCH_ROWS`] rows. Best-effort.
pub fn put_search_results(query: &str, limit: usize, results_json: &str, ttl_seconds: u64) {
    let Some(db_path) = cache_db_path() else {
        return;
    };
    if ensure_cache_tables(db_path).is_err() {
        return;
    }
    let Ok(conn) = db_pool::get(db_path, Lane::Write) else {
        return;
    };
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    let now = unix_now();
    let _ = guard.execute(
        "INSERT INTO resolver_search_cache
             (query, result_limit, results_json, created_at, expires_at, last_used)
         VALUES (?1, ?2, ?3, ?4, ?4 + ?5, ?4)
         ON CONFLICT(query, result_limit) DO UPDATE SET
            results_json = excluded.results_json,
            created_at   = excluded.created_at,
            expires_at   = excluded.expires_at,
            last_used    = excluded.last_used",
        params![query, limit as i64, results_json, now, ttl_seconds as i64],
    );

    let _ = guard.execute(
        "DELETE FROM resolver_search_cache WHERE expires_at <= ?1",
        params![now],
    );
    let _ = guard.execute(
        "DELETE FROM resolver_search_cache WHERE (query, result_limit) NOT IN (
             SELECT query, result_limit FROM resolver_search_cache
             ORDER BY last_used DESC LIMIT ?1)",
        params![MAX_SEARCH_ROWS as i64],
    );
}

/// Read one metadata row (e.g. the rustypipe deobfuscation payload
/// under `rustypipe_deobf`). TTL-less; freshness is the owner's
/// contract (see the module docs).
pub fn get_meta(key: &str) -> Option<String> {
    let db_path = cache_db_path()?;
    ensure_cache_tables(db_path).ok()?;

    let conn = db_pool::get(db_path, Lane::Write).ok()?;
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    guard
        .query_row(
            "SELECT value FROM resolver_meta_cache WHERE key = ?1",
            params![key],
            |row| row.get::<_, String>(0),
        )
        .ok()
}

/// Upsert one metadata row. Best-effort.
pub fn put_meta(key: &str, value: &str) {
    let Some(db_path) = cache_db_path() else {
        return;
    };
    if ensure_cache_tables(db_path).is_err() {
        return;
    }
    let Ok(conn) = db_pool::get(db_path, Lane::Write) else {
        return;
    };
    let guard = conn.lock().unwrap_or_else(|p| p.into_inner());

    let _ = guard.execute(
        "INSERT INTO resolver_meta_cache (key, value, updated_at)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(key) DO UPDATE SET
            value      = excluded.value,
            updated_at = excluded.updated_at",
        params![key, value, unix_now()],
    );
}

// ---------------------------------------------------------------------------
// Tests (tempdir-backed; every test binds a unique path — nextest runs
// one process per test, libtest shares one, so the process-global bind
// must never be order-dependent)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// One comprehensive lifecycle test: the cache database path is a
    /// process-global OnceLock (see the module docs), so under libtest's
    /// shared-process runner every #[test] here would race on one
    /// database — parallel puts from a sibling test can evict rows a
    /// test is asserting on. Scenarios therefore run sequentially in a
    /// single test; nextest (the CI runner) isolates processes per test
    /// anyway, and the sequential order also pins the
    /// expiry-before-eviction interaction in one place.
    #[test]
    fn resolver_cache_full_lifecycle() {
        // ── Bind (temp DATABASE FILE, not the temp directory itself —
        // SQLite cannot open a directory as a database). The file path
        // leaks: the bind is a process-lifetime OnceLock and cannot
        // borrow a dropped tempdir. ─────────────────────────────────
        let dir = tempfile::tempdir().expect("temp dir");
        let db_file = dir.into_path().join("resolver_cache_test.db");
        let leaked = db_file.to_string_lossy().into_owned();
        bind_cache_db_path(&leaked);
        let db = cache_db_path().expect("a path must be bound");

        // ── 1. Stream-format rows: roundtrip, TTL expiry, prune. ─────
        assert!(
            get_stream_formats("vid1").is_none(),
            "no row exists before the first put"
        );

        put_stream_formats("vid1", r#"[{"url":"https://cdn/1"}]"#, 3_600);
        let hit = get_stream_formats("vid1").expect("row must hit inside its TTL");
        assert!(hit.contains("https://cdn/1"));

        // Backdate the row past its TTL: the next read must miss (the
        // safety-margin contract — expired rows are never served).
        {
            let conn = db_pool::get(db, Lane::Write).unwrap();
            let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
            let _ = guard.execute(
                "UPDATE resolver_stream_cache SET expires_at = 1 WHERE video_id = 'vid1'",
                [],
            );
        }
        assert!(get_stream_formats("vid1").is_none(), "expired row must miss");

        // The next put prunes the expired row away.
        put_stream_formats("vid2", "[]", 3_600);
        {
            let conn = db_pool::get(db, Lane::Write).unwrap();
            let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
            let rows: i64 = guard
                .query_row(
                    "SELECT COUNT(*) FROM resolver_stream_cache",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(rows, 1, "the expired vid1 row must have been pruned");
        }

        // ── 2. LRU eviction with deterministic recency. Second-granular
        // wall-clock cannot order same-second inserts, so the eldest
        // (vid2) and the newest (lru_hot) rows get EXPLICIT last_used
        // values; only the bound, the eldest eviction and the newest
        // survivor are asserted — the tie-group victims in between are
        // unspecified by contract. ──────────────────────────────────
        put_stream_formats("lru_hot", "[]", 3_600);
        for i in 0..=MAX_STREAM_ROWS {
            put_stream_formats(&format!("lru_{i:04}"), "[]", 3_600);
        }
        {
            let conn = db_pool::get(db, Lane::Write).unwrap();
            let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
            // vid2 is scripted strictly-oldest; lru_hot strictly-newest.
            let _ = guard.execute(
                "UPDATE resolver_stream_cache SET last_used = 1 WHERE video_id = 'vid2'",
                [],
            );
            let _ = guard.execute(
                "UPDATE resolver_stream_cache SET last_used = last_used + 100
                 WHERE video_id = 'lru_hot'",
                [],
            );
        }
        // One more put triggers the expired-prune + LRU trim pass.
        put_stream_formats("lru_trigger", "[]", 3_600);
        {
            let conn = db_pool::get(db, Lane::Write).unwrap();
            let guard = conn.lock().unwrap_or_else(|p| p.into_inner());
            let rows: i64 = guard
                .query_row(
                    "SELECT COUNT(*) FROM resolver_stream_cache",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(rows, MAX_STREAM_ROWS as i64, "the LRU bound holds");
            let stale_vid2: i64 = guard
                .query_row(
                    "SELECT COUNT(*) FROM resolver_stream_cache WHERE video_id = 'vid2'",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(stale_vid2, 0, "the strictly-oldest row was evicted first");
            let fresh_tail: i64 = guard
                .query_row(
                    "SELECT COUNT(*) FROM resolver_stream_cache WHERE video_id = 'lru_hot'",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(fresh_tail, 1, "the strictly-newest row survived");
        }

        // ── 3. Search rows: roundtrip, limit-keyed, LRU bump. ────────
        assert!(get_search_results("never gonna give you up", 10).is_none());
        put_search_results("never gonna give you up", 10, r#"[{"id":"abc"}]"#, 3_600);
        let hit = get_search_results("never gonna give you up", 10)
            .expect("search row must hit inside its TTL");
        assert!(hit.contains("abc"));
        // A different limit is a different cache row.
        assert!(get_search_results("never gonna give you up", 25).is_none());

        // ── 4. Meta rows: roundtrip + upsert. ─────────────────────────
        assert!(get_meta("rustypipe_deobf").is_none());
        put_meta("rustypipe_deobf", r#"{"sts":"20012"}"#);
        let first = get_meta("rustypipe_deobf").expect("meta row must read back");
        assert!(first.contains("20012"));
        put_meta("rustypipe_deobf", r#"{"sts":"20013"}"#);
        let second = get_meta("rustypipe_deobf").expect("upsert must overwrite");
        assert!(second.contains("20013"));
    }

    /// Cold-start degradation: every accessor returns None / no-ops when
    /// no path is bound. Under nextest this runs in a fresh process
    /// (deterministic cold start); under libtest a sibling test may have
    /// bound a path first, in which case the assertions vacuously pass
    /// — the degradation itself is enforced by construction (the
    /// `cache_db_path()?` / `let Some(...) else` early returns).
    #[test]
    fn unbound_path_degrades_to_none_without_error() {
        if cache_db_path().is_none() {
            assert!(get_stream_formats("vid").is_none());
            assert!(get_search_results("q", 5).is_none());
            assert!(get_meta("k").is_none());
            // Puts are silent no-ops.
            put_stream_formats("vid", "[]", 60);
            put_search_results("q", 5, "[]", 60);
            put_meta("k", "v");
        }
    }
}
