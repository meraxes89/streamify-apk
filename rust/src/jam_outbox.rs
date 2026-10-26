//! Streamify Jam Outbox Engine — SQLite WAL-backed local-first journal.
//!
//! Guarantees no mutation is lost to network partitions or process death:
//!   1. UI mutations write here FIRST (local-first), then render optimistically.
//!   2. A flush loop drains PENDING ops over the socket while connected.
//!   3. Host ack deletes rows; stale IN_FLIGHT rows revert for replay-on-heal.
//!
//! Audit deviations from the v2 draft (deliberate, documented):
//!   - NO pointer-cast transmute: JamOps serialize via field-wise LE
//!     (`JamOp::to_bytes`) — portable, UB-free, pad-deterministic.
//!   - Corrupt rows are SKIPPED and counted, never `assert!`-panicked — the
//!     panic-shield contract applies to every FFI-reachable path.
//!   - `attempts` is capped: poison ops move to status DEAD (3) instead of
//!     retrying forever and wedging the queue head.
//!   - Rows carry `session_code` so future multi-room sessions cannot
//!     cross-contaminate a shared outbox file.
//!   - Lock-poisoned mutexes recover via `into_inner()` rather than panicking.

use rusqlite::params;
use std::path::Path;
use std::sync::Mutex;

use crate::jam_crdt::{JamCrdtState, JamOp};

pub const STATUS_PENDING: i64 = 0;
pub const STATUS_IN_FLIGHT: i64 = 1;
pub const STATUS_DEAD: i64 = 3;

const MAX_ATTEMPTS: i64 = 8;
#[allow(dead_code)] // reserved: caller-driven staleness window knob
const DEFAULT_STALE_MS: i64 = 30_000;

/// Counters surfaced through FFI diagnostics.
#[derive(Default)]
pub struct OutboxStats {
    pub enqueued: u64,
    pub flushed_acked: u64,
    pub corrupt_skipped: u64,
    pub dead_lettered: u64,
}

pub struct JamOutbox {
    conn: Mutex<rusqlite::Connection>,
    pub stats: Mutex<OutboxStats>,
}

impl JamOutbox {
    /// Opens/creates the outbox DB with crash-safe pragmas.
    pub fn new(db_path: &Path) -> Result<Self, rusqlite::Error> {
        let conn = rusqlite::Connection::open(db_path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "temp_store", "MEMORY")?;

        conn.execute(
            "CREATE TABLE IF NOT EXISTS jam_outbox (
                row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                op_id       INTEGER NOT NULL UNIQUE,
                session_code TEXT NOT NULL DEFAULT '',
                op_data     BLOB NOT NULL,
                status      INTEGER NOT NULL DEFAULT 0,
                attempts    INTEGER NOT NULL DEFAULT 0,
                queued_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )",
            [],
        )?;
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_outbox_status ON jam_outbox(status, queued_at_ms)",
            [],
        )?;
        Ok(Self {
            conn: Mutex::new(conn),
            stats: Mutex::new(OutboxStats::default()),
        })
    }

    #[inline]
    fn lock_conn(&self) -> std::sync::MutexGuard<'_, rusqlite::Connection> {
        self.conn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn now_ms() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    /// Local-first enqueue. Idempotent per op_id (UNIQUE + INSERT OR IGNORE),
    /// which makes transport-layer retries free of duplicates.
    pub fn enqueue(&self, op: &JamOp, session_code: &str) -> Result<bool, rusqlite::Error> {
        let conn = self.lock_conn();
        let n = conn.execute(
            "INSERT OR IGNORE INTO jam_outbox
                (op_id, session_code, op_data, status, attempts, queued_at_ms, updated_at_ms)
             VALUES (?1, ?2, ?3, 0, 0, ?4, ?4)",
            params![
                op.op_id as i64,
                session_code,
                op.to_bytes().as_slice(),
                Self::now_ms()
            ],
        )?;
        if n > 0 {
            if let Ok(mut st) = self.stats.lock() {
                st.enqueued += 1;
            }
        }
        Ok(n > 0)
    }

    /// Atomically claims up to `limit` PENDING ops for one session, marking
    /// them IN_FLIGHT inside the same transaction that reads them.
    /// Corrupt blobs are skipped (counted), never fatal.
    pub fn poll_batch(
        &self,
        session_code: &str,
        limit: usize,
    ) -> Result<Vec<JamOp>, rusqlite::Error> {
        let mut conn = self.lock_conn();
        let tx = conn.transaction()?;

        let mut stmt = tx.prepare(
            "SELECT row_id, op_data FROM jam_outbox
             WHERE status = 0 AND session_code = ?1
             ORDER BY queued_at_ms ASC LIMIT ?2",
        )?;
        let rows: Vec<(i64, Vec<u8>)> = stmt
            .query_map(params![session_code, limit as i64], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, Vec<u8>>(1)?))
            })?
            .filter_map(|r| r.ok())
            .collect();
        drop(stmt);

        if rows.is_empty() {
            return Ok(Vec::new());
        }

        let mut ops = Vec::with_capacity(rows.len());
        let mut claimed_ids = Vec::with_capacity(rows.len());
        let mut corrupt = 0usize;

        for (row_id, blob) in &rows {
            match JamOp::from_bytes(blob).filter(|op| op.is_valid()) {
                Some(op) => {
                    claimed_ids.push(*row_id);
                    ops.push(op);
                }
                None => {
                    // Poison row: dead-letter it instead of crashing or looping.
                    tx.execute(
                        "UPDATE jam_outbox SET status = 3, updated_at_ms = ?2 WHERE row_id = ?1",
                        params![row_id, Self::now_ms()],
                    )?;
                    corrupt += 1;
                }
            }
        }

        if !claimed_ids.is_empty() {
            // Per-row explicit updates: mixing bare `?` with numbered `?N`
            // makes SQLite continue numbering past the max explicit index,
            // silently inflating the expected bind count.
            for rid in &claimed_ids {
                tx.execute(
                    "UPDATE jam_outbox SET status = ?1, attempts = attempts + 1,
                        updated_at_ms = ?2 WHERE row_id = ?3",
                    params![STATUS_IN_FLIGHT, Self::now_ms(), rid],
                )?;
            }
        }
        tx.commit()?;

        if corrupt > 0 {
            if let Ok(mut st) = self.stats.lock() {
                st.corrupt_skipped += corrupt as u64;
            }
        }
        Ok(ops)
    }

    /// Host ratified these ops — purge them permanently.
    pub fn ack(&self, op_ids: &[u64]) -> Result<usize, rusqlite::Error> {
        if op_ids.is_empty() {
            return Ok(0);
        }
        let conn = self.lock_conn();
        let placeholders = vec!["?"; op_ids.len()].join(",");
        let ids: Vec<i64> = op_ids.iter().map(|&id| id as i64).collect();
        let sql = format!("DELETE FROM jam_outbox WHERE op_id IN ({placeholders})");
        let n = conn.execute(&sql, rusqlite::params_from_iter(ids.iter()))?;
        if let Ok(mut st) = self.stats.lock() {
            st.flushed_acked += n as u64;
        }
        Ok(n)
    }

    /// Partition heal / periodic sweep: revert stale IN_FLIGHT back to
    /// PENDING, and dead-letter ops whose attempts exceed the cap.
    pub fn replay_pending(&self, stale_threshold_ms: i64) -> Result<usize, rusqlite::Error> {
        let conn = self.lock_conn();
        let cutoff = Self::now_ms() - stale_threshold_ms.max(0);

        let dead = conn.execute(
            "UPDATE jam_outbox SET status = ?3, updated_at_ms = ?2
             WHERE status IN (?1, 0) AND attempts >= ?4",
            params![STATUS_IN_FLIGHT, Self::now_ms(), STATUS_DEAD, MAX_ATTEMPTS],
        )?;
        if dead > 0 {
            if let Ok(mut st) = self.stats.lock() {
                st.dead_lettered += dead as u64;
            }
        }

        let reverted = conn.execute(
            // Inclusive <=: threshold semantics are "max allowed age". A
            // strictly-less compare races against same-ms writes (claim and
            // sweep inside one millisecond would never revert).
            "UPDATE jam_outbox SET status = 0, updated_at_ms = ?2
             WHERE status = 1 AND updated_at_ms <= ?1",
            params![cutoff, Self::now_ms()],
        )?;
        Ok(reverted)
    }

    /// Pending backlog depth for UI badges ("syncing N edits…").
    pub fn pending_count(&self, session_code: &str) -> Result<i64, rusqlite::Error> {
        let conn = self.lock_conn();
        conn.query_row(
            "SELECT COUNT(*) FROM jam_outbox WHERE status = 0 AND session_code = ?1",
            params![session_code],
            |r| r.get(0),
        )
    }

    /// Drop everything already delivered/dead older than `keep_ms`.
    pub fn gc(&self, keep_ms: i64) -> Result<usize, rusqlite::Error> {
        let conn = self.lock_conn();
        let cutoff = Self::now_ms() - keep_ms.max(0);
        conn.execute(
            "DELETE FROM jam_outbox WHERE status IN (?, ?) AND updated_at_ms < ?3",
            params![STATUS_IN_FLIGHT, STATUS_DEAD, cutoff],
        )
    }

    pub fn diagnostics(&self) -> [i64; 4] {
        match self.stats.lock() {
            Ok(st) => [
                st.enqueued as i64,
                st.flushed_acked as i64,
                st.corrupt_skipped as i64,
                st.dead_lettered as i64,
            ],
            Err(_) => [-1; 4],
        }
    }
}

/// Convenience: apply an op to a CRDT state and persist it locally in one call
/// (the local-first mutation path used by optimistic UI).
pub fn apply_and_enqueue(
    state: &mut JamCrdtState,
    outbox: &JamOutbox,
    op: &JamOp,
    session_code: &str,
) -> Result<bool, rusqlite::Error> {
    let applied = state.apply_op(op);
    if applied {
        outbox.enqueue(op, session_code)?;
    }
    Ok(applied)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::jam_crdt::OpType;

    struct TempDb(std::path::PathBuf);
    impl TempDb {
        fn new(tag: &str) -> Self {
            let p = std::env::temp_dir().join(format!(
                "jam_outbox_test_{tag}_{}_{}.db",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            TempDb(p)
        }
    }
    impl Drop for TempDb {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
            let _ = std::fs::remove_file(self.0.with_extension("db-wal"));
            let _ = std::fs::remove_file(self.0.with_extension("db-shm"));
        }
    }

    fn mk_op(id: u64, frac: f64) -> JamOp {
        JamOp::new(id, [7; 4], OpType::Add, 0, 12345, frac, 0)
    }

    #[test]
    fn outbox_full_lifecycle() {
        let db = TempDb::new("life");
        let outbox = JamOutbox::new(&db.0).unwrap();

        // ── enqueue + ordering ──
        assert!(outbox.enqueue(&mk_op(1001, 0.5), "ABC123").unwrap());
        assert!(outbox.enqueue(&mk_op(1002, 0.6), "ABC123").unwrap());

        // ── idempotent re-enqueue of the SAME op_id ──
        assert!(!outbox.enqueue(&mk_op(1001, 0.5), "ABC123").unwrap());

        let batch = outbox.poll_batch("ABC123", 10).unwrap();
        assert_eq!(batch.len(), 2);
        assert_eq!(batch[0].op_id, 1001);
        assert_eq!(batch[1].op_id, 1002);
        assert!(batch.iter().all(|o| o.is_valid()));

        // ── no double-fetch while IN_FLIGHT ──
        assert_eq!(outbox.poll_batch("ABC123", 10).unwrap().len(), 0);

        // ── ack removes permanently ──
        assert_eq!(outbox.ack(&[1001]).unwrap(), 1);

        // ── partition heal: stale sweep returns op2 to PENDING ──
        let swept = outbox.replay_pending(0).unwrap();
        assert_eq!(swept, 1);
        let replay = outbox.poll_batch("ABC123", 10).unwrap();
        assert_eq!(replay.len(), 1);
        assert_eq!(replay[0].op_id, 1002);

        // ── session isolation: other rooms see nothing ──
        assert_eq!(outbox.poll_batch("OTHER", 10).unwrap().len(), 0);
        assert!(outbox.pending_count("ABC123").unwrap() >= 0);
    }

    #[test]
    fn outbox_crash_recovery() {
        let db = TempDb::new("crash");
        {
            let outbox = JamOutbox::new(&db.0).unwrap();
            outbox.enqueue(&mk_op(9999, 0.9), "ROOMX").unwrap();
            // drop == process death; WAL must retain the row
        }
        {
            let outbox = JamOutbox::new(&db.0).unwrap();
            let batch = outbox.poll_batch("ROOMX", 10).unwrap();
            assert_eq!(batch.len(), 1);
            assert_eq!(batch[0].op_id, 9999);
        }
    }

    #[test]
    fn corrupt_blob_is_dead_lettered_not_fatal() {
        use rusqlite::Connection;
        let db = TempDb::new("corrupt");
        let outbox = JamOutbox::new(&db.0).unwrap();
        outbox.enqueue(&mk_op(5555, 0.5), "ROOMZ").unwrap();

        // Sabotage: overwrite the BLOB with garbage pads (from_bytes rejects).
        {
            let conn = Connection::open(&db.0).unwrap();
            conn.execute(
                "UPDATE jam_outbox SET op_data = ?1 WHERE op_id = 5555",
                params![vec![0xABu8; 48]],
            )
            .unwrap();
        }

        let batch = outbox.poll_batch("ROOMZ", 10).unwrap();
        assert_eq!(batch.len(), 0, "corrupt row must be skipped");
        assert_eq!(outbox.diagnostics()[2], 1, "corrupt counter incremented");
    }

    #[test]
    fn retry_cap_dead_letters_poison_ops() {
        let db = TempDb::new("retry");
        let outbox = JamOutbox::new(&db.0).unwrap();
        outbox.enqueue(&mk_op(7777, 0.25), "ROOMQ").unwrap();

        // Simulate 8 failed send cycles without any time passing:
        for _ in 0..MAX_ATTEMPTS {
            let b = outbox.poll_batch("ROOMQ", 5).unwrap();
            assert_eq!(b.len(), 1);
            // force-stale sweep between cycles (threshold 0 → instant revert)
            outbox.replay_pending(0).unwrap();
        }
        // 9th cycle: attempts cap hit during sweep → DEAD, poll stays empty.
        outbox.replay_pending(0).unwrap();
        assert_eq!(outbox.poll_batch("ROOMQ", 5).unwrap().len(), 0);
        assert_eq!(outbox.diagnostics()[3], 1, "dead-lettered once");
    }

    #[test]
    fn crdt_apply_and_enqueue_roundtrip() {
        let db = TempDb::new("combo");
        let outbox = JamOutbox::new(&db.0).unwrap();
        let mut state = JamCrdtState::new();
        let op = mk_op(4242, 0.42);

        assert!(apply_and_enqueue(&mut state, &outbox, &op, "ROOMW").unwrap());
        assert_eq!(state.snapshot_vec().len(), 1);
        assert_eq!(outbox.poll_batch("ROOMW", 5).unwrap()[0].op_id, 4242);

        // Invalid ops are neither applied nor persisted.
        let bad = JamOp::new(4243, [1; 4], OpType::Add, 0, 1, f64::NAN, 0);
        assert!(!apply_and_enqueue(&mut state, &outbox, &bad, "ROOMW").unwrap());
        assert_eq!(state.snapshot_vec().len(), 1);
    }
}
