// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use rusqlite::Connection;
use std::ffi::CStr;
use std::panic::catch_unwind;
use crate::repository::TrackRepository;
use crate::resolver::{get_client, get_runtime};

#[derive(serde::Deserialize)]
struct SpotifyRecentlyPlayed {
    #[serde(default)]
    items: Vec<SpotifyPlayHistoryItem>,
}

#[derive(serde::Deserialize)]
struct SpotifyPlayHistoryItem {
    track: Option<SpotifyDeltaTrack>,
}

#[derive(serde::Deserialize)]
struct SpotifyDeltaTrack {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    duration_ms: u32,
    external_ids: Option<ExternalIds>,
    #[serde(default)]
    artists: Vec<SpotifyArtist>,
    album: Option<SpotifyAlbum>,
}

#[derive(serde::Deserialize)]
struct ExternalIds {
    isrc: Option<String>,
}

#[derive(serde::Deserialize)]
struct SpotifyArtist {
    #[serde(default)]
    name: String,
}

#[derive(serde::Deserialize)]
struct SpotifyAlbum {
    #[serde(default)]
    images: Vec<SpotifyImage>,
}

#[derive(serde::Deserialize)]
struct SpotifyImage {
    #[serde(default)]
    url: String,
}

/// Returns the CAD-ID and Video ID for the next track in the queue.
/// If shuffle is enabled, queries a randomized SQLite index.
#[no_mangle]
pub unsafe extern "C" fn get_next_track(
    db_path_ptr: *const std::os::raw::c_char,
    current_cad_id_ptr: *const std::os::raw::c_char,
    is_shuffle: i32,
    out_cad_buf: *mut u8,
    out_cad_len: usize,
    out_video_buf: *mut u8,
    out_video_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() || current_cad_id_ptr.is_null() || out_cad_buf.is_null() || out_video_buf.is_null() {
            return -1;
        }

        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        let current_cad_id = CStr::from_ptr(current_cad_id_ptr).to_str().unwrap_or("");

        let conn = match Connection::open(db_path) {
            Ok(c) => c,
            Err(_) => return -1,
        };

        let _ = TrackRepository::apply_performance_pragmas(&conn);

        // 1. Find the rowid of the current track
        let current_rowid: i64 = conn.query_row(
            "SELECT rowid FROM universal_tracks WHERE cad_id = ?1",
            [current_cad_id],
            |row| row.get(0),
        ).unwrap_or(0);

        // 2. Get the next track (Linear sequential or Random shuffle)
        let next_rowid: i64 = if is_shuffle == 1 {
            // Tier 0 B1 (#23): ORDER BY RANDOM() materialized + sorted the
            // whole table on every shuffle skip (~80 ms+ @ 20k tracks),
            // blowing the 16 ms gapless budget. COUNT + uniform OFFSET is
            // an O(offset) rowid-index walk with no materialization and the
            // same uniform distribution over the non-current candidates.
            let candidates: i64 = conn.query_row(
                "SELECT COUNT(*) FROM universal_tracks WHERE rowid != ?1",
                [current_rowid],
                |row| row.get(0),
            ).unwrap_or(0);
            if candidates <= 0 {
                current_rowid
            } else {
                use rand::Rng;
                let offset = rand::thread_rng().gen_range(0..candidates);
                conn.query_row(
                    "SELECT rowid FROM universal_tracks WHERE rowid != ?1 LIMIT 1 OFFSET ?2",
                    rusqlite::params![current_rowid, offset],
                    |row| row.get(0),
                ).unwrap_or(current_rowid)
            }
        } else {
            conn.query_row(
                "SELECT rowid FROM universal_tracks WHERE rowid > ?1 ORDER BY rowid ASC LIMIT 1",
                [current_rowid],
                |row| row.get(0),
            ).unwrap_or_else(|_| {
                // Circular loopback to first track if reached the end
                conn.query_row(
                    "SELECT MIN(rowid) FROM universal_tracks",
                    [],
                    |row| row.get(0),
                ).unwrap_or(0)
            })
        };

        // 3. Fetch CAD-ID and Video ID
        let (cad_id, video_id): (String, String) = conn.query_row(
            "SELECT cad_id, COALESCE(ytm_video_id, '') FROM universal_tracks WHERE rowid = ?1",
            [next_rowid],
            |row| Ok((row.get(0)?, row.get(1)?)),
        ).unwrap_or_default();

        let cad_bytes = cad_id.as_bytes();
        let vid_bytes = video_id.as_bytes();

        if cad_bytes.len() > out_cad_len || vid_bytes.len() > out_video_len {
            return -2; // Buffer too small
        }

        std::ptr::copy_nonoverlapping(cad_bytes.as_ptr(), out_cad_buf, cad_bytes.len());
        if !vid_bytes.is_empty() {
            std::ptr::copy_nonoverlapping(vid_bytes.as_ptr(), out_video_buf, vid_bytes.len());
        }

        cad_bytes.len() as i32
    });

    result.unwrap_or(-3)
}

/// Background Delta Sync: Fetches latest tracks added to Spotify and syncs them to SQLite.
#[no_mangle]
pub unsafe extern "C" fn spotify_delta_sync(
    db_path_ptr: *const std::os::raw::c_char,
    access_token_ptr: *const std::os::raw::c_char,
    last_sync_timestamp: i64,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() || access_token_ptr.is_null() {
            return -1;
        }

        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        let token = CStr::from_ptr(access_token_ptr).to_str().unwrap_or("");

        if db_path.is_empty() || token.is_empty() {
            return -1;
        }

        let rt = get_runtime();
        rt.block_on(async {
            let client = get_client();
            let url = if last_sync_timestamp > 0 {
                format!("https://api.spotify.com/v1/me/player/recently-played?limit=20&after={}", last_sync_timestamp)
            } else {
                "https://api.spotify.com/v1/me/player/recently-played?limit=20".to_string()
            };

            let resp = match client.get(&url).bearer_auth(token).send().await {
                Ok(r) => r,
                Err(_) => return 0,
            };

            if !resp.status().is_success() {
                return 0;
            }

            let data: SpotifyRecentlyPlayed = match resp.json().await {
                Ok(d) => d,
                Err(_) => return 0,
            };

            let conn = match Connection::open(db_path) {
                Ok(c) => c,
                Err(_) => return 0,
            };

            let _ = TrackRepository::apply_performance_pragmas(&conn);

            let tx = match conn.unchecked_transaction() {
                Ok(t) => t,
                Err(_) => return 0,
            };

            let mut count = 0;
            for item in data.items {
                if let Some(track) = item.track {
                    if track.name.is_empty() { continue; }
                    let duration_sec = track.duration_ms / 1000;
                    let artwork_url = track.album.as_ref().and_then(|a| a.images.first()).map(|i| i.url.clone());
                    let isrc = track.external_ids.and_then(|e| e.isrc);
                    let artist = track.artists.first().map(|a| a.name.clone()).unwrap_or_default();

                    let cad_id = TrackRepository::generate_cad_id(&track.name, &artist, duration_sec);

                    let res = tx.execute(
                        "INSERT INTO universal_tracks 
                         (cad_id, title, artist, duration_sec, artwork_url, isrc_code, spotify_id, source_platform)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'SPOTIFY')
                         ON CONFLICT(cad_id) DO UPDATE SET 
                            spotify_id = COALESCE(excluded.spotify_id, universal_tracks.spotify_id),
                            isrc_code = COALESCE(excluded.isrc_code, universal_tracks.isrc_code),
                            artwork_url = COALESCE(excluded.artwork_url, universal_tracks.artwork_url)",
                        rusqlite::params![
                            cad_id, track.name, artist, duration_sec,
                            artwork_url, isrc, track.id
                        ]
                    );

                    if res.is_ok() {
                        count += 1;
                    }
                }
            }

            let _ = tx.commit();
            count
        })
    });

    result.unwrap_or(-1)
}

/// Graceful shutdown helper: Checkpoints SQLite WAL and ensures clean state.
#[no_mangle]
pub unsafe extern "C" fn shutdown_engine(db_path_ptr: *const std::os::raw::c_char) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() {
            return 0;
        }
        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        if !db_path.is_empty() {
            if let Ok(conn) = Connection::open(db_path) {
                let _ = conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
            }
        }
        0
    });
    result.unwrap_or(-1)
}

// ─────────────────────────────────────────────────────── tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    /// Creates a temp DB with `n` tracks and returns its path.
    fn temp_db(n: usize) -> String {
        static SEQ: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let id = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let pid = std::process::id();
        let path = std::env::temp_dir().join(format!("qe_shuffle_test_{pid}_{id}.db"));
        let path_str = path.to_str().unwrap().to_string();
        let _ = std::fs::remove_file(&path);
        let conn = Connection::open(&path_str).expect("open temp db");
        conn.execute_batch(crate::repository::UNIVERSAL_TRACKS_DDL)
            .expect("create schema");
        for i in 0..n {
            let cad = format!("cad_{:04}", i);
            let title = format!("Track {i}");
            conn.execute(
                "INSERT INTO universal_tracks (cad_id, title, artist, duration_sec, source_platform)
                 VALUES (?1, ?2, 'Art', 100, 'TEST')",
                rusqlite::params![cad, title],
            )
            .expect("insert");
        }
        path_str
    }

    /// Calls the FFI entry exactly like the Kotlin bridge does.
    fn next_track(db: &str, current: &str, shuffle: bool) -> (i32, String) {
        let db_c = CString::new(db).unwrap();
        let cur_c = CString::new(current).unwrap();
        let mut cad_buf = [0u8; 64];
        let mut vid_buf = [0u8; 64];
        let rc = unsafe {
            get_next_track(
                db_c.as_ptr(),
                cur_c.as_ptr(),
                if shuffle { 1 } else { 0 },
                cad_buf.as_mut_ptr(),
                cad_buf.len(),
                vid_buf.as_mut_ptr(),
                vid_buf.len(),
            )
        };
        let cad = cad_buf[..rc.max(0) as usize]
            .iter()
            .map(|&b| b as char)
            .collect();
        (rc, cad)
    }

    #[test]
    fn shuffle_never_returns_current_and_covers_candidates() {
        // Tier 0 B1 regression lock (#23): the shuffle pick must (a) always
        // avoid the current track, (b) be uniform enough to cover the
        // candidate space, (c) never fall out of the table.
        let db = temp_db(50);
        let (rc, _) = next_track(&db, "cad_0000", false); // sanity: linear works
        assert!(rc > 0);

        let mut distinct = std::collections::HashSet::new();
        for draw in 0..600 {
            let current = format!("cad_{:04}", draw % 50);
            let (rc, cad) = next_track(&db, &current, true);
            assert!(rc > 0, "shuffle return code {rc}");
            assert_ne!(cad, current, "shuffle returned the current track");
            assert!(cad.starts_with("cad_"), "out-of-table pick: {cad:?}");
            distinct.insert(cad);
        }
        // Coupon-collector over 49 candidates in 600 draws: expecting full
        // coverage (~218 draws mean); 30 is a fail-proof floor.
        assert!(distinct.len() >= 30, "shuffle not covering: {}", distinct.len());
        std::fs::remove_file(&db).ok();
    }

    #[test]
    fn shuffle_fallbacks_match_legacy_behavior() {
        // Single-track table: candidates == 0 -> stays on the current track
        // (legacy ORDER BY RANDOM() also returned unwrap_or(current)).
        let db = temp_db(1);
        let (rc, cad) = next_track(&db, "cad_0000", true);
        assert_eq!(cad, "cad_0000");
        assert!(rc > 0);
        std::fs::remove_file(&db).ok();

        // Empty table: no row for rowid 0 -> empty strings, rc == 0
        // (legacy behavior preserved).
        let empty = temp_db(0);
        let (rc, cad) = next_track(&empty, "cad_0000", true);
        assert_eq!(rc, 0);
        assert!(cad.is_empty());
        std::fs::remove_file(&empty).ok();
    }

    #[test]
    fn linear_walks_in_order_and_wraps() {
        let db = temp_db(5);
        let (_, a) = next_track(&db, "cad_0000", false);
        assert_eq!(a, "cad_0001");
        let (_, b) = next_track(&db, "cad_0004", false);
        assert_eq!(b, "cad_0000", "linear wraparound to first track");
        std::fs::remove_file(&db).ok();
    }
}
