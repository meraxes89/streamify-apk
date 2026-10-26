// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use rusqlite::{params, Connection};
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::db_pool::{self, Lane};

#[derive(serde::Deserialize)]
pub struct RawSpotifyTrack {
    pub title: String,
    pub artist: String,
    pub duration_sec: u32,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default)]
    pub isrc: Option<String>,
    #[serde(default)]
    pub spotify_id: Option<String>,
}

pub struct TrackRepository {
    /// Pooled write-lane connection (Tier 0 B1, #23): `new()` used to pay
    /// open + pragmas + DDL + rekey on every call; the pool amortizes all of
    /// it to once per path, and serializing writers in userspace removes the
    /// silent `SQLITE_BUSY` write-loss class.
    conn: db_pool::PooledConn,
    /// Retained so read-shaped methods can check out the read lane (WAL
    /// readers never queue behind a writer's transaction).
    db_path: String,
}

/// Canonical schema for universal_tracks, shared by TrackRepository::new and
/// ensure_db_migrated so read-only paths can bootstrap the table safely.
pub const UNIVERSAL_TRACKS_DDL: &str = "CREATE TABLE IF NOT EXISTS universal_tracks (
        cad_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        artist TEXT NOT NULL,
        duration_sec INTEGER NOT NULL,
        artwork_url TEXT,
        isrc_code TEXT,
        spotify_id TEXT,
        ytm_video_id TEXT,
        source_platform TEXT NOT NULL,
        is_liked INTEGER DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_isrc ON universal_tracks(isrc_code);
    CREATE INDEX IF NOT EXISTS idx_spotify ON universal_tracks(spotify_id);
    CREATE INDEX IF NOT EXISTS idx_ytm ON universal_tracks(ytm_video_id);";

/// PRAGMA user_version gate for the CAD-ID re-key migration.
/// v1 = legacy mixed hash schemes (Rust DefaultHasher / C++ FNV divergence).
/// v2 = single canonical FNV-1a scheme shared by every layer.
const REKEY_SCHEMA_VERSION: i32 = 2;

impl TrackRepository {
    pub fn new(path: &str) -> Result<Self, rusqlite::Error> {
        // Pool checkout subsumes the old per-call bootstrap: open, performance
        // pragmas, canonical DDL, and the once-per-file CAD-ID rekey all run
        // at first checkout (see `db_pool`).
        let conn = db_pool::get(path, Lane::Write)?;
        Ok(TrackRepository {
            conn,
            db_path: path.to_string(),
        })
    }

    /// Applies RAM-Native Memory-Mapped I/O, WAL, and 64MB Cache to the SQLite connection
    pub fn apply_performance_pragmas(conn: &Connection) -> Result<(), rusqlite::Error> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        let mmap_bytes = Self::calculate_mmap_size();
        let _ = conn.pragma_update(None, "mmap_size", mmap_bytes);
        let _ = conn.pragma_update(None, "temp_store", "MEMORY");
        let _ = conn.pragma_update(None, "cache_size", -64000); // 64MB Page Cache
        Ok(())
    }

    /// Dynamically scales MMAP size based on device RAM to prevent low-end OOMs
    pub fn calculate_mmap_size() -> i64 {
        let meminfo = std::fs::read_to_string("/proc/meminfo").unwrap_or_default();
        let total_ram_kb = meminfo
            .lines()
            .find(|line| line.starts_with("MemTotal:"))
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(2048 * 1024);

        let total_ram_bytes = total_ram_kb * 1024;
        if total_ram_bytes >= 6_000_000_000 {
            268_435_456 // 256MB for 6GB+ flagships
        } else if total_ram_bytes >= 3_000_000_000 {
            134_217_728 // 128MB for 3-4GB mid-range
        } else {
            67_108_864 // 64MB for low-end
        }
    }

    /// Generates a Duration-Aware Canonical Hash (CAD-ID).
    /// Prevents "Starboy (Kygo Remix)" from colliding with "Starboy (Original)".
    ///
    /// SINGLE SOURCE OF TRUTH for cross-layer identity. This is a bit-exact port of
    /// the C++ implementation (nativeGenerateCadId in jni_bridge.cc): FNV-1a 64-bit
    /// over ASCII-byte-filtered lowercase title ('(' preserved), ASCII-filtered
    /// artist, then the little-endian u32 duration bucket (seconds / 3).
    /// Every layer (Kotlin fallback, Rust ingest, C++ JNI) must agree on this value.
    #[inline(always)]
    pub fn generate_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
        const FNV_OFFSET: u64 = 14695981039346656037;
        const FNV_PRIME: u64 = 1099511628211;

        let mut hash: u64 = FNV_OFFSET;
        for b in normalize_cad_field(title, true) {
            hash ^= b as u64;
            hash = hash.wrapping_mul(FNV_PRIME);
        }
        for b in normalize_cad_field(artist, false) {
            hash ^= b as u64;
            hash = hash.wrapping_mul(FNV_PRIME);
        }
        let bucket: u32 = if duration_sec > 0 {
            duration_sec / 3
        } else {
            0
        };
        for i in 0..4 {
            hash ^= ((bucket >> (i * 8)) & 0xFF) as u64;
            hash = hash.wrapping_mul(FNV_PRIME);
        }
        format!("{:016x}", hash)
    }

    /// Batch upserts tracks in a single transaction.
    /// Accepts a JSON payload from Kotlin (1-time network cost, safe parsing).
    pub fn batch_upsert_spotify_tracks(&self, json_payload: &str) -> i32 {
        let result = catch_unwind(AssertUnwindSafe(|| {
            let tracks: Vec<RawSpotifyTrack> = match serde_json::from_str(json_payload) {
                Ok(t) => t,
                Err(_) => return -1,
            };

            // One write-lane critical section for the whole batch — the
            // pooled statement is prepared once and re-executed per row
            // (the old `tx.execute` re-prepared per row).
            let conn = self.conn.lock().unwrap_or_else(|p| p.into_inner());
            let tx = match conn.unchecked_transaction() {
                Ok(t) => t,
                Err(_) => return -1,
            };

            let mut stmt = match conn.prepare_cached(
                "INSERT INTO universal_tracks 
                 (cad_id, title, artist, duration_sec, artwork_url, isrc_code, spotify_id, source_platform)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'SPOTIFY')
                 ON CONFLICT(cad_id) DO UPDATE SET 
                    spotify_id = COALESCE(excluded.spotify_id, universal_tracks.spotify_id),
                    isrc_code = COALESCE(excluded.isrc_code, universal_tracks.isrc_code),
                    artwork_url = COALESCE(excluded.artwork_url, universal_tracks.artwork_url)",
            ) {
                Ok(s) => s,
                Err(_) => return -1,
            };

            let mut count = 0;
            for track in tracks {
                let cad_id = Self::generate_cad_id(&track.title, &track.artist, track.duration_sec);

                let res = stmt.execute(params![
                    cad_id,
                    track.title,
                    track.artist,
                    track.duration_sec,
                    track.artwork_url,
                    track.isrc,
                    track.spotify_id
                ]);
                if res.is_ok() {
                    count += 1;
                }
            }

            if tx.commit().is_ok() {
                count
            } else {
                -1
            }
        }));

        result.unwrap_or(-1)
    }

    /// Queries the shelf and writes directly to a Kotlin-provided DirectByteBuffer.
    /// ZERO JSON serialization. ZERO Kotlin String allocations in hot path.
    /// FFI-facing: the caller (C/JNI boundary, itself unsafe) owns the
    /// output buffer; length-guarded writes only.
    #[allow(clippy::not_unsafe_ptr_arg_deref)]
    pub fn fetch_virtual_shelf_to_buffer(&self, out_buf: *mut u8, out_buf_len: usize) -> i32 {
        let result = catch_unwind(AssertUnwindSafe(|| {
            if out_buf.is_null() || out_buf_len < 4 {
                return -1;
            }

            // Read lane: the shelf scan must not queue behind an ingest
            // transaction on the write lane (WAL readers proceed).
            let pooled = match db_pool::get(&self.db_path, Lane::Read) {
                Ok(c) => c,
                Err(_) => return -1,
            };
            let conn = pooled.lock().unwrap_or_else(|p| p.into_inner());

            let mut stmt = match conn.prepare_cached(
                "SELECT cad_id, title, artist, COALESCE(artwork_url, '') FROM universal_tracks LIMIT 500",
            ) {
                Ok(s) => s,
                Err(_) => return -1,
            };

            let rows = match stmt.query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            }) {
                Ok(r) => r,
                Err(_) => return -1,
            };

            let mut offset = 4; // Reserve first 4 bytes for total count
            let mut track_count = 0u32;

            for row_res in rows {
                let row = match row_res {
                    Ok(r) => r,
                    Err(_) => continue,
                };
                let cad_id = row.0.as_bytes();
                let title = row.1.as_bytes();
                let artist = row.2.as_bytes();
                let artwork = row.3.as_bytes();

                let needed = 16 + cad_id.len() + title.len() + artist.len() + artwork.len();
                if offset + needed > out_buf_len {
                    break;
                }

                macro_rules! write_field {
                    ($field:expr) => {
                        let len = $field.len() as u32;
                        let len_bytes = len.to_le_bytes();
                        unsafe {
                            std::ptr::copy_nonoverlapping(
                                len_bytes.as_ptr(),
                                out_buf.add(offset),
                                4,
                            );
                            offset += 4;
                            if !($field).is_empty() {
                                std::ptr::copy_nonoverlapping(
                                    ($field).as_ptr(),
                                    out_buf.add(offset),
                                    ($field).len(),
                                );
                                offset += ($field).len();
                            }
                        }
                    };
                }

                write_field!(cad_id);
                write_field!(title);
                write_field!(artist);
                write_field!(artwork);

                track_count += 1;
            }

            let count_bytes = track_count.to_le_bytes();
            unsafe {
                std::ptr::copy_nonoverlapping(count_bytes.as_ptr(), out_buf, 4);
            }

            offset as i32
        }));

        result.unwrap_or(-3)
    }
}

/// Byte-exact mirror of the C++ normalization in nativeGenerateCadId:
/// iterates UTF-8 BYTES, keeps ASCII alphanumerics (plus '(' for titles when
/// allow_paren), lowercases ASCII letters, drops everything else (all non-ASCII).
fn normalize_cad_field(input: &str, allow_paren: bool) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len());
    for &b in input.as_bytes() {
        let keep = b.is_ascii_alphanumeric() || (allow_paren && b == b'(');
        if keep {
            out.push(b.to_ascii_lowercase());
        }
    }
    out
}

/// One-time migration: re-keys universal_tracks rows that were written with the
/// legacy Rust DefaultHasher CAD-ID scheme onto the canonical FNV-1a scheme.
/// Guarded by PRAGMA user_version so it executes at most once per database file.
pub fn ensure_cad_rekey(conn: &Connection) {
    let current_version: i32 = conn
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .unwrap_or(REKEY_SCHEMA_VERSION);
    if current_version >= REKEY_SCHEMA_VERSION {
        return;
    }

    if conn.execute_batch("BEGIN IMMEDIATE;").is_err() {
        return;
    }

    let migrated = (|| -> Result<bool, rusqlite::Error> {
        let mut stmt =
            conn.prepare("SELECT cad_id, title, artist, duration_sec FROM universal_tracks")?;
        let rows: Vec<(String, String, String, i64)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
            .filter_map(|r| r.ok())
            .collect();
        drop(stmt);

        // Compute the canonical id for every row; collapse intra-batch duplicates,
        // keeping the first occurrence of each target key.
        let mut seen_new_ids = std::collections::HashSet::new();
        let mut plan: Vec<(String, String)> = Vec::with_capacity(rows.len());
        for (old_id, title, artist, duration_sec) in &rows {
            let new_id = TrackRepository::generate_cad_id(
                title,
                artist,
                (*duration_sec).clamp(0, u32::MAX as i64) as u32,
            );
            if new_id == *old_id || !seen_new_ids.insert(new_id.clone()) {
                continue;
            }
            plan.push((old_id.clone(), new_id));
        }

        for (old_id, new_id) in plan {
            // Target key already occupied by a different row → merge into it.
            let occupied: bool = conn
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM universal_tracks WHERE cad_id = ?1)",
                    [&new_id],
                    |r| r.get::<_, i64>(0),
                )
                .map(|v| v != 0)
                .unwrap_or(false);

            if occupied {
                let _ = conn.execute(
                    "UPDATE universal_tracks SET
                        ytm_video_id = COALESCE(NULLIF(ytm_video_id, ''), (SELECT ytm_video_id FROM universal_tracks WHERE cad_id = ?2)),
                        isrc_code    = COALESCE(isrc_code,    (SELECT isrc_code    FROM universal_tracks WHERE cad_id = ?2)),
                        spotify_id    = COALESCE(spotify_id,    (SELECT spotify_id    FROM universal_tracks WHERE cad_id = ?2)),
                        artwork_url  = COALESCE(artwork_url,  (SELECT artwork_url  FROM universal_tracks WHERE cad_id = ?2))
                     WHERE cad_id = ?1",
                    params![new_id, old_id],
                );
                let _ = conn.execute("DELETE FROM universal_tracks WHERE cad_id = ?1", [&old_id]);
            } else {
                let _ = conn.execute(
                    "UPDATE universal_tracks SET cad_id = ?1 WHERE cad_id = ?2",
                    params![new_id, old_id],
                );
            }
        }

        Ok(true)
    })();

    match migrated {
        Ok(true) => {
            let _ = conn.execute_batch("COMMIT;");
            let _ = conn.execute_batch(&format!("PRAGMA user_version = {};", REKEY_SCHEMA_VERSION));
        }
        _ => {
            // Roll back and leave user_version untouched so the migration retries
            // on the next open instead of half-committing a corrupt re-key.
            let _ = conn.execute_batch("ROLLBACK;");
        }
    }
}

/// Bootstrap helper for read-only entry points (e.g. the stream resolver):
/// guarantees the schema exists and pending migrations have run.
/// Tier 0 B1 (#23): now a pool warm-up — open + pragmas + DDL + rekey happen
/// once per path at first checkout; repeat calls are a registry hit.
pub fn ensure_db_migrated(db_path: &str) {
    if db_path.is_empty() {
        return;
    }
    let _ = db_pool::get(db_path, Lane::Read);
}

pub fn generate_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
    TrackRepository::generate_cad_id(title, artist, duration_sec)
}

/// Raw u64 CAD-ID — the value BEFORE hex formatting. Single source of truth
/// for native consumers (Jam CRDT) that need the numeric identity without a
/// string round-trip. Bit-identical to `generate_cad_id`'s parsed value.
#[inline(always)]
pub fn generate_cad_id_u64(title: &str, artist: &str, duration_sec: u32) -> u64 {
    const FNV_OFFSET: u64 = 14695981039346656037;
    const FNV_PRIME: u64 = 1099511628211;

    let mut hash: u64 = FNV_OFFSET;
    for b in normalize_cad_field(title, true) {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    for b in normalize_cad_field(artist, false) {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    let bucket: u32 = if duration_sec > 0 {
        duration_sec / 3
    } else {
        0
    };
    for i in 0..4 {
        hash ^= ((bucket >> (i * 8)) & 0xFF) as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

#[no_mangle]
pub unsafe extern "C" fn batch_upsert_spotify_tracks(
    db_path: *const std::os::raw::c_char,
    json_payload: *const std::os::raw::c_char,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if db_path.is_null() || json_payload.is_null() {
            return -1;
        }
        let c_db = std::ffi::CStr::from_ptr(db_path);
        let c_json = std::ffi::CStr::from_ptr(json_payload);
        let db_str = match c_db.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let json_str = match c_json.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let repo = match TrackRepository::new(db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.batch_upsert_spotify_tracks(json_str)
    }));
    result.unwrap_or(-3)
}

#[no_mangle]
pub unsafe extern "C" fn fetch_virtual_shelf(
    db_path: *const std::os::raw::c_char,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if db_path.is_null() || out_buf.is_null() || out_buf_len < 4 {
            return -1;
        }
        let c_db = std::ffi::CStr::from_ptr(db_path);
        let db_str = match c_db.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let repo = match TrackRepository::new(db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.fetch_virtual_shelf_to_buffer(out_buf, out_buf_len)
    }));
    result.unwrap_or(-3)
}
