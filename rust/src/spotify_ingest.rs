// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use crate::db_pool::{self, Lane};
use crate::repository::TrackRepository;
use crate::resolver::{get_client, get_runtime};
use reqwest::Client;
use std::ffi::CStr;
use std::panic::catch_unwind;

#[derive(serde::Deserialize)]
struct SpotifyPagingResponse<T> {
    items: Vec<T>,
    next: Option<String>,
    /// Kept for wire-schema fidelity (paging contract).
    #[serde(default)]
    #[allow(dead_code)]
    total: u32,
}

#[derive(serde::Deserialize)]
struct SpotifyTrackItem {
    track: Option<SpotifyTrack>,
}

#[derive(serde::Deserialize)]
struct SpotifyTrack {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    duration_ms: u32,
    external_ids: Option<ExternalIds>,
    #[serde(default)]
    artists: Vec<SpotifyArtist>,
    #[serde(default)]
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

#[derive(serde::Deserialize)]
struct TokenResponse {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
}

#[derive(serde::Deserialize)]
struct SpotifyPlaylistsResponse {
    #[serde(default)]
    items: Vec<SpotifyPlaylistItem>,
    next: Option<String>,
}

#[derive(serde::Deserialize)]
struct SpotifyPlaylistItem {
    #[serde(default)]
    #[allow(dead_code)] // wire-schema fidelity
    id: String,
    #[serde(default)]
    #[allow(dead_code)] // wire-schema fidelity
    name: String,
    tracks: Option<SpotifyPlaylistTracksRef>,
}

#[derive(serde::Deserialize)]
struct SpotifyPlaylistTracksRef {
    #[serde(default)]
    href: String,
    /// Kept for wire-schema fidelity (paging contract).
    #[serde(default)]
    #[allow(dead_code)]
    total: u32,
}

/// FFI: Exchanges PKCE Auth Code for Access & Refresh Tokens.
/// Writes tokens directly to provided Kotlin buffers to prevent heap allocations.
#[no_mangle]
pub unsafe extern "C" fn spotify_exchange_pkce(
    code_ptr: *const std::os::raw::c_char,
    verifier_ptr: *const std::os::raw::c_char,
    redirect_uri_ptr: *const std::os::raw::c_char,
    client_id_ptr: *const std::os::raw::c_char,
    out_access_buf: *mut u8,
    out_access_len: usize,
    out_refresh_buf: *mut u8,
    out_refresh_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if code_ptr.is_null()
            || verifier_ptr.is_null()
            || redirect_uri_ptr.is_null()
            || out_access_buf.is_null()
            || out_refresh_buf.is_null()
        {
            return -1;
        }

        let code = CStr::from_ptr(code_ptr).to_str().unwrap_or("");
        let verifier = CStr::from_ptr(verifier_ptr).to_str().unwrap_or("");
        let redirect_uri = CStr::from_ptr(redirect_uri_ptr).to_str().unwrap_or("");
        let client_id = if client_id_ptr.is_null() {
            "37b8d4f407764d8dbda2f94356e792c3"
        } else {
            CStr::from_ptr(client_id_ptr)
                .to_str()
                .unwrap_or("37b8d4f407764d8dbda2f94356e792c3")
        };

        let rt = get_runtime();
        rt.block_on(async {
            let client = get_client();
            let url = "https://accounts.spotify.com/api/token";

            let params = [
                ("grant_type", "authorization_code"),
                ("code", code),
                ("redirect_uri", redirect_uri),
                ("client_id", client_id),
                ("code_verifier", verifier),
            ];

            let resp = match client.post(url).form(&params).send().await {
                Ok(r) => r,
                Err(_) => return -4,
            };

            if !resp.status().is_success() {
                return -4;
            }

            let tokens: TokenResponse = match resp.json().await {
                Ok(t) => t,
                Err(_) => return -4,
            };

            let access_bytes = tokens.access_token.as_bytes();
            let refresh_bytes = tokens.refresh_token.as_deref().unwrap_or("").as_bytes();

            if access_bytes.len() > out_access_len || refresh_bytes.len() > out_refresh_len {
                return -2;
            }

            std::ptr::copy_nonoverlapping(
                access_bytes.as_ptr(),
                out_access_buf,
                access_bytes.len(),
            );
            if !refresh_bytes.is_empty() {
                std::ptr::copy_nonoverlapping(
                    refresh_bytes.as_ptr(),
                    out_refresh_buf,
                    refresh_bytes.len(),
                );
            }

            0 // Success
        })
    });

    result.unwrap_or(-3)
}

/// FFI: Ingests all Liked Songs & Playlists concurrently, writing directly to SQLite.
/// Returns the total number of tracks ingested.
#[no_mangle]
pub unsafe extern "C" fn spotify_ingest_library(
    db_path_ptr: *const std::os::raw::c_char,
    access_token_ptr: *const std::os::raw::c_char,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() || access_token_ptr.is_null() {
            return -1;
        }

        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        let access_token = CStr::from_ptr(access_token_ptr).to_str().unwrap_or("");

        if db_path.is_empty() || access_token.is_empty() {
            return -1;
        }

        let rt = get_runtime();
        rt.block_on(async {
            let client = get_client();
            let mut total_ingested = 0u32;

            // 1. Fetch Liked Songs (/v1/me/tracks)
            let liked_url = "https://api.spotify.com/v1/me/tracks?limit=50";
            total_ingested +=
                fetch_and_commit_spotify_pages(client, access_token, liked_url, db_path).await;

            // 2. Fetch Top Tracks (/v1/me/top/tracks)
            let top_url = "https://api.spotify.com/v1/me/top/tracks?limit=50&time_range=short_term";
            total_ingested +=
                fetch_and_commit_spotify_top_tracks(client, access_token, top_url, db_path).await;

            // 3. Fetch User Playlists (/v1/me/playlists)
            let playlists_url = "https://api.spotify.com/v1/me/playlists?limit=20";
            if let Ok(playlists) = fetch_user_playlists(client, access_token, playlists_url).await {
                for playlist in playlists {
                    if !playlist.tracks_url.is_empty() {
                        total_ingested += fetch_and_commit_spotify_pages(
                            client,
                            access_token,
                            &playlist.tracks_url,
                            db_path,
                        )
                        .await;
                    }
                }
            }

            total_ingested as i32
        })
    });

    result.unwrap_or(-3)
}

struct SimplePlaylist {
    tracks_url: String,
}

async fn fetch_user_playlists(
    client: &Client,
    token: &str,
    start_url: &str,
) -> Result<Vec<SimplePlaylist>, ()> {
    let mut playlists = Vec::new();
    let mut current_url = Some(start_url.to_string());
    let mut pages_fetched = 0;

    while let Some(url) = current_url {
        if pages_fetched >= 3 {
            break;
        } // limit to top 60 playlists for rapid sync
        pages_fetched += 1;

        let resp = client.get(&url).bearer_auth(token).send().await.ok();

        if let Some(resp) = resp {
            if !resp.status().is_success() {
                break;
            }
            let data: SpotifyPlaylistsResponse = match resp.json().await {
                Ok(d) => d,
                Err(_) => break,
            };

            for item in data.items {
                if let Some(tracks_ref) = item.tracks {
                    if !tracks_ref.href.is_empty() {
                        playlists.push(SimplePlaylist {
                            tracks_url: tracks_ref.href,
                        });
                    }
                }
            }
            current_url = data.next;
        } else {
            break;
        }
    }

    Ok(playlists)
}

/// Commits fetched Spotify tracks in one short pooled-write-lane transaction.
///
/// Tier 0 B1 (#23): shared by both ingest fetchers. The old flow opened a
/// connection per call and held an open write transaction across every
/// paged `.await` — seconds of pinned write lock for one commit's worth of
/// work. Now the fetchers drain pages first (network phase), and this helper
/// runs the whole batch under the pooled write lane with a single cached
/// prepared statement re-executed per row.
fn commit_spotify_tracks(db_path: &str, tracks: &[SpotifyTrack]) -> u32 {
    if tracks.is_empty() {
        return 0;
    }
    let pooled = match db_pool::get(db_path, Lane::Write) {
        Ok(c) => c,
        Err(_) => return 0,
    };
    let conn = pooled.lock().unwrap_or_else(|p| p.into_inner());

    let tx = match conn.unchecked_transaction() {
        Ok(t) => t,
        Err(_) => return 0,
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
        Err(_) => return 0,
    };

    let mut count = 0u32;
    for track in tracks {
        let duration_sec = track.duration_ms / 1000;
        let artwork_url = track
            .album
            .as_ref()
            .and_then(|a| a.images.first())
            .map(|i| i.url.clone());
        let isrc = track.external_ids.as_ref().and_then(|e| e.isrc.clone());
        let artist = track
            .artists
            .first()
            .map(|a| a.name.clone())
            .unwrap_or_default();
        let cad_id = TrackRepository::generate_cad_id(&track.name, &artist, duration_sec);

        let res = stmt.execute(rusqlite::params![
            cad_id,
            track.name,
            artist,
            duration_sec,
            artwork_url,
            isrc,
            track.id
        ]);
        if res.is_ok() {
            count += 1;
        }
    }

    let _ = tx.commit();
    count
}

async fn fetch_and_commit_spotify_pages(
    client: &Client,
    token: &str,
    start_url: &str,
    db_path: &str,
) -> u32 {
    let mut current_url = Some(start_url.to_string());
    let mut tracks: Vec<SpotifyTrack> = Vec::new();

    // Phase 1 — network only: drain every page before touching the database
    // so no lock is held across an `.await` (see `commit_spotify_tracks`).
    while let Some(url) = current_url {
        let resp = client.get(&url).bearer_auth(token).send().await.ok();
        let Some(resp) = resp else { break };
        if !resp.status().is_success() {
            break;
        }

        let data: SpotifyPagingResponse<SpotifyTrackItem> = match resp.json().await {
            Ok(d) => d,
            Err(_) => break,
        };

        for item in data.items {
            if let Some(track) = item.track {
                if !track.name.is_empty() {
                    tracks.push(track);
                }
            }
        }
        current_url = data.next;
    }

    // Phase 2 — database only: one short pooled-write-lane transaction.
    commit_spotify_tracks(db_path, &tracks)
}

/// Recursively fetches Spotify direct tracks (e.g., top tracks endpoint where items are tracks directly).
async fn fetch_and_commit_spotify_top_tracks(
    client: &Client,
    token: &str,
    start_url: &str,
    db_path: &str,
) -> u32 {
    let mut current_url = Some(start_url.to_string());
    let mut tracks: Vec<SpotifyTrack> = Vec::new();

    // Phase 1 — network only (top-tracks endpoints paginate too).
    while let Some(url) = current_url {
        let resp = client.get(&url).bearer_auth(token).send().await.ok();
        let Some(resp) = resp else { break };
        if !resp.status().is_success() {
            break;
        }

        let data: SpotifyPagingResponse<SpotifyTrack> = match resp.json().await {
            Ok(d) => d,
            Err(_) => break,
        };

        for track in data.items {
            if !track.name.is_empty() {
                tracks.push(track);
            }
        }
        current_url = data.next;
    }

    // Phase 2 — database only.
    commit_spotify_tracks(db_path, &tracks)
}
