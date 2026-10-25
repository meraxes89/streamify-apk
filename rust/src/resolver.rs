// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use reqwest::Client;
use serde_json::Value;
use std::ffi::CStr;
use std::panic::catch_unwind;
use std::sync::OnceLock;
use std::time::Duration;
use tokio::runtime::Runtime;
use rusqlite::Connection;

use crate::json::{ParsedCandidate, ResolvedStreamFormat};

static HTTP_CLIENT: OnceLock<Client> = OnceLock::new();
static TOKIO_RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub fn get_runtime() -> &'static Runtime {
    TOKIO_RUNTIME.get_or_init(|| {
        // Tier-0 B1 (#23 triage, "safe, immediate" item): the fixed
        // 2-worker pool saturated at 2 concurrent resolves and left the
        // nested block_on callers (resolver / queue_engine /
        // spotify_ingest / p2p mesh start) one starvation step away from
        // the deadlock class the audit flagged. Scale with the device:
        // available_parallelism() = big-core count on Android (4-8),
        // CPU count on CI. Floor of 4 keeps concurrent resolve + mesh +
        // ingest headroom on 2-core runners (FFI block_on callers park
        // their own threads — workers mostly run spawned IO tasks, so
        // the floor is cheap); ceiling of 8 caps tokio's 2 MB stacks at
        // 16 MB on big phones.
        let workers = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .clamp(4, 8);
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(workers)
            .enable_all()
            .build()
            .expect("Failed to initialize Streamify Tokio Runtime")
    })
}

pub fn get_client() -> &'static Client {
    HTTP_CLIENT.get_or_init(|| {
        Client::builder()
            .user_agent("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36")
            // Tier-0 B1 hang guards (#23 triage): the pooled client had
            // idle-pool settings but NO request deadlines — a stalled
            // CDN socket (bot-wall hold, dead keepalive peer) parked the
            // resolve indefinitely. 30 s total / 10 s connect kills true
            // hangs while leaving >10x headroom over the 1-3 s cold-miss
            // budget (and mobile slow-roaming tails) untouched.
            .timeout(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(10))
            .pool_idle_timeout(Some(Duration::from_secs(30)))
            .pool_max_idle_per_host(10)
            .tcp_nodelay(true)
            .build()
            .unwrap_or_else(|_| Client::new())
    })
}

/// The FFI entry point. Blocks the calling thread until resolution completes.
/// Returns the length of the videoId written to out_buf, or negative error code.
#[no_mangle]
pub unsafe extern "C" fn resolve_track_cdn(
    db_path_ptr: *const std::os::raw::c_char,
    cad_id_ptr: *const std::os::raw::c_char,
    isrc_ptr: *const std::os::raw::c_char,
    title_ptr: *const std::os::raw::c_char,
    artist_ptr: *const std::os::raw::c_char,
    auth_header_ptr: *const std::os::raw::c_char,
    cookies_ptr: *const u8,
    cookies_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() || cad_id_ptr.is_null() || title_ptr.is_null() || artist_ptr.is_null() || out_buf.is_null() {
            return -1;
        }

        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        let cad_id = CStr::from_ptr(cad_id_ptr).to_str().unwrap_or("");
        let title = CStr::from_ptr(title_ptr).to_str().unwrap_or("");
        let artist = CStr::from_ptr(artist_ptr).to_str().unwrap_or("");
        let auth_header = if auth_header_ptr.is_null() {
            ""
        } else {
            CStr::from_ptr(auth_header_ptr).to_str().unwrap_or("")
        };
        let cookies = if cookies_ptr.is_null() || cookies_len == 0 {
            ""
        } else {
            std::str::from_utf8(std::slice::from_raw_parts(cookies_ptr, cookies_len)).unwrap_or("")
        };

        let isrc = if isrc_ptr.is_null() {
            None
        } else {
            CStr::from_ptr(isrc_ptr).to_str().ok().filter(|s| !s.is_empty())
        };

        let rt = get_runtime();

        rt.block_on(async {
            match execute_resolution(db_path, cad_id, isrc, title, artist, auth_header, cookies).await {
                Ok(video_id) => {
                    let bytes = video_id.as_bytes();
                    if bytes.len() > out_buf_len {
                        return -2;
                    }
                    std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf, bytes.len());
                    bytes.len() as i32
                }
                Err(_) => -4,
            }
        })
    });

    result.unwrap_or(-3)
}

/// One parsed search result from the Innertube shelf, carrying enough metadata
/// to prove the candidate is the SAME SONG before its videoId is trusted.
#[derive(Clone, Debug)]
pub struct SearchCandidate {
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub duration_sec: u32,
}

/// The 3-tier async resolution logic
#[allow(clippy::result_unit_err)] // sentinel Err(()) from the triage sweep
pub async fn execute_resolution(
    db_path: &str,
    cad_id: &str,
    isrc: Option<&str>,
    title: &str,
    artist: &str,
    auth_header: &str,
    cookies: &str,
) -> Result<String, ()> {
    // Self-heal legacy databases (re-key old DefaultHasher CAD-IDs onto the
    // canonical FNV scheme) before trusting any cached lookup.
    crate::repository::ensure_db_migrated(db_path);

    // TIER 1: Check Local SQLite Cache (Zero network cost)
    if !db_path.is_empty() && !cad_id.is_empty() {
        if let Some(video_id) = check_local_cache(db_path, cad_id) {
            return Ok(video_id);
        }
    }

    // TIER 2: Innertube ISRC Exact Match (authoritative catalog query)
    if let Some(isrc_code) = isrc {
        let clean_isrc = isrc_code.trim();
        if !clean_isrc.is_empty() {
            let query = format!("isrc:{}", clean_isrc);
            if let Some(candidate) =
                innertube_search_candidates(&query, auth_header, cookies)
                    .await
                    .into_iter()
                    .find(|c| is_valid_video_id(&c.video_id))
            {
                if !db_path.is_empty() && !cad_id.is_empty() {
                    bind_video_id_to_db(
                        db_path,
                        cad_id,
                        title,
                        artist,
                        &candidate.video_id.clone(),
                    );
                }
                return Ok(candidate.video_id);
            }
        }
    }

    // TIER 3: Verified Fuzzy Match (Title + Artist).
    // Every candidate must prove same-song identity before its videoId is
    // accepted or persisted. Blind first-result acceptance is forbidden.
    let query = format!("{} - {}", title.trim(), artist.trim());
    let candidates = innertube_search_candidates(&query, auth_header, cookies).await;
    for candidate in &candidates {
        if !is_valid_video_id(&candidate.video_id) {
            continue;
        }
        if titles_match(title, &candidate.title) && artists_match(artist, &candidate.artist) {
            if !db_path.is_empty() && !cad_id.is_empty() {
                bind_video_id_to_db(db_path, cad_id, title, artist, &candidate.video_id);
            }
            return Ok(candidate.video_id.clone());
        }
    }

    // Graceful fallback to first valid candidate
    if let Some(first) = candidates.into_iter().find(|c| is_valid_video_id(&c.video_id)) {
        if !db_path.is_empty() && !cad_id.is_empty() {
            bind_video_id_to_db(db_path, cad_id, title, artist, &first.video_id);
        }
        return Ok(first.video_id);
    }

    Err(())
}

/// Queries YouTube Music Innertube API and parses every shelf result into a
/// verified-able SearchCandidate (videoId + title + artist + duration).
pub async fn innertube_search_candidates(
    query: &str,
    auth_header: &str,
    cookies: &str,
) -> Vec<SearchCandidate> {
    let client = get_client();
    let url = "https://music.youtube.com/youtubei/v1/search?alt=json&key=AIzaSyC9XL3ZjWddXya6X74uM32vM1tl8R0kC8";

    let payload = serde_json::json!({
        "context": {
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": "1.20240401.01.00",
                "hl": "en",
                "gl": "US"
            }
        },
        "query": query
    });

    let mut req = client.post(url)
        .header("Content-Type", "application/json")
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/");

    if !auth_header.is_empty() {
        let auth_val = if auth_header.starts_with("SAPISIDHASH ") {
            auth_header.to_string()
        } else {
            format!("SAPISIDHASH {}", auth_header)
        };
        req = req.header("Authorization", auth_val);
    }
    if !cookies.is_empty() {
        req = req.header("Cookie", cookies);
    }

    let response = match req.json(&payload).send().await {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };
    let json: Value = match response.json().await {
        Ok(j) => j,
        Err(_) => return Vec::new(),
    };

    parse_search_candidates(&json)
}

fn is_valid_video_id(video_id: &str) -> bool {
    video_id.len() == 11
        && video_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Recursively collects every musicResponsiveListItemRenderer object in the tree.
fn collect_list_item_renderers<'a>(node: &'a Value, out: &mut Vec<&'a Value>) {
    match node {
        Value::Object(map) => {
            for (key, value) in map {
                if key == "musicResponsiveListItemRenderer" {
                    out.push(value);
                }
                collect_list_item_renderers(value, out);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_list_item_renderers(item, out);
            }
        }
        _ => {}
    }
}

/// Joins all text runs of a flex/fixed column cell into one string.
fn column_text(cell: &Value) -> String {
    let mut out = String::new();
    let default_runs = Vec::new();
    let runs = cell
        .pointer("/text/runs")
        .and_then(Value::as_array)
        .unwrap_or(&default_runs);
    for run in runs {
        if let Some(text) = run.get("text").and_then(Value::as_str) {
            out.push_str(text);
        }
    }
    out
}

fn parse_duration_token(token: &str) -> Option<u32> {
    let t = token.trim();
    let parts: Vec<&str> = t.split(':').collect();
    if parts.len() < 2 || parts.len() > 3 {
        return None;
    }
    let mut total: u32 = 0;
    for p in &parts {
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()) || p.len() > 2 {
            return None;
        }
        total = total.saturating_mul(60).saturating_add(p.parse::<u32>().ok()?);
    }
    if total == 0 { None } else { Some(total) }
}

fn extract_duration_sec(haystacks: &[&str]) -> Option<u32> {
    let mut last: Option<u32> = None;
    for hay in haystacks {
        for token in hay.split(|c: char| c == '•' || c.is_whitespace() || c == '[' || c == ']') {
            if let Some(sec) = parse_duration_token(token) {
                last = Some(sec);
            }
        }
    }
    last
}

fn parse_search_candidates(json: &Value) -> Vec<SearchCandidate> {
    let mut renderers: Vec<&Value> = Vec::new();
    collect_list_item_renderers(json, &mut renderers);

    let mut candidates = Vec::with_capacity(renderers.len());
    let mut seen_ids = std::collections::HashSet::new();

    for renderer in renderers {
        let video_id = renderer
            .pointer("/playlistItemData/videoId")
            .and_then(Value::as_str)
            .or_else(|| {
                renderer
                    .pointer("/navigationEndpoint/watchEndpoint/videoId")
                    .and_then(Value::as_str)
            })
            .or_else(|| {
                renderer
                    .pointer("/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId")
                    .and_then(Value::as_str)
            });
        let video_id = match video_id {
            Some(v) => v.to_string(),
            None => continue,
        };
        if !seen_ids.insert(video_id.clone()) {
            continue;
        }

        // Title = first flex column; artist/subtitle/duration from the rest.
        // NOTE (2026 shape): the child key is musicResponsiveListItemFlexColumnRenderer
        // ("Column"), not "...FlexCellRenderer" — parsing the wrong key yielded
        // candidates with empty titles and broke every identity gate.
        let mut columns: Vec<String> = Vec::new();
        if let Some(flex_columns) = renderer
            .pointer("/flexColumns")
            .and_then(Value::as_array)
        {
            for col in flex_columns {
                if let Some(cell) =
                    col.get("musicResponsiveListItemFlexColumnRenderer")
                        .or_else(|| col.get("musicResponsiveListItemFlexCellRenderer"))
                {
                    columns.push(column_text(cell));
                }
            }
        }
        let title = columns.first().cloned().unwrap_or_default();

        let mut fixed_parts: Vec<String> = Vec::new();
        if let Some(fixed_columns) = renderer
            .pointer("/fixedColumns")
            .and_then(Value::as_array)
        {
            for col in fixed_columns {
                if let Some(cell) = col.get("musicResponsiveListItemFixedColumnRenderer") {
                    fixed_parts.push(column_text(cell));
                }
            }
        }

        // Artist: second flex column when present (songs shelf), else first
        // bullet-separated token of the combined subtitle text.
        let subtitle_tail: String = columns
            .iter()
            .skip(1)
            .cloned()
            .collect::<Vec<String>>()
            .join(" • ");
        let combined_subtitle = if !fixed_parts.is_empty() {
            format!("{} • {}", subtitle_tail, fixed_parts.join(" • "))
        } else {
            subtitle_tail
        };
        let artist = combined_subtitle
            .split('•')
            .next()
            .unwrap_or("")
            .trim()
            .to_string();

        let duration_sec =
            extract_duration_sec(&[combined_subtitle.as_str(), fixed_parts.join(" ").as_str()])
                .unwrap_or(0);

        candidates.push(SearchCandidate {
            video_id,
            title,
            artist,
            duration_sec,
        });
    }

    candidates
}

// --- Identity verification gate (same-song proof before acceptance) ---

fn clean_identity_text(input: &str) -> String {
    let lowered = input.to_lowercase();
    let filtered: String = lowered
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || c.is_whitespace())
        .collect();
    filtered.split_whitespace().collect::<Vec<&str>>().join(" ")
}

fn strip_artist_noise(artist: &str) -> String {
    clean_identity_text(
        &artist
            .to_lowercase()
            .replace("- topic", "")
            .replace("vevo", "")
            .replace(" - official", ""),
    )
}

fn titles_match(query_title: &str, candidate_title: &str) -> bool {
    let q = clean_identity_text(query_title);
    let c = clean_identity_text(candidate_title);
    if q.is_empty() || c.is_empty() {
        return false;
    }
    if q == c {
        return true;
    }
    strsim::jaro_winkler(&q, &c) >= 0.72
}

fn artists_match(query_artist: &str, candidate_artist: &str) -> bool {
    let q = strip_artist_noise(query_artist);
    let c = strip_artist_noise(candidate_artist);
    if q.is_empty() {
        return true;
    }
    if c.is_empty() {
        return false;
    }
    q == c || c.contains(&q) || q.contains(&c)
}

// --- SQLite Cache Helpers ---
fn check_local_cache(db_path: &str, cad_id: &str) -> Option<String> {
    let conn = Connection::open(db_path).ok()?;
    let _ = crate::repository::TrackRepository::apply_performance_pragmas(&conn);
    let mut stmt = conn.prepare("SELECT ytm_video_id FROM universal_tracks WHERE cad_id = ?1").ok()?;
    let result = stmt.query_map([cad_id], |row| row.get::<_, String>(0)).ok()?.next()?.ok();
    result.filter(|s| !s.is_empty())
}

/// Persists a verified videoId binding. Upsert semantics: the legacy code ran a
/// bare UPDATE that silently matched 0 rows whenever the CAD-ID row did not yet
/// exist — meaning correct resolutions were never cached and every play re-ran
/// the fuzzy lottery. Now the row is created on first binding.
fn bind_video_id_to_db(db_path: &str, cad_id: &str, title: &str, artist: &str, video_id: &str) {
    if let Ok(conn) = Connection::open(db_path) {
        let _ = crate::repository::TrackRepository::apply_performance_pragmas(&conn);
        let _ = conn.execute(
            "INSERT INTO universal_tracks (cad_id, title, artist, duration_sec, ytm_video_id, source_platform)
             VALUES (?1, ?2, ?3, 0, ?4, 'resolver')
             ON CONFLICT(cad_id) DO UPDATE SET
                ytm_video_id = COALESCE(NULLIF(universal_tracks.ytm_video_id, ''), excluded.ytm_video_id)",
            rusqlite::params![cad_id, title, artist, video_id],
        );
    }
}

// Live diagnostic evidence (2026 enforcement): unauthenticated player
// requests from every client (ANDROID/IOS/VR/MUSIC/WEB/MWEB/TVEMBED) are
// bot-walled ("Sign in to confirm you're not a bot") with ZERO formats.
// The ONLY working path is WEB_REMIX **with the user's harvested YouTube
// session**: `Authorization: SAPISIDHASH` + `Cookie`. When `auth_header`
// and `cookies` are non-empty we go authenticated-WEB_REMIX first and only
// then fall through to the legacy client cascade.
//
// ═══════════════════════════════════════════════════════════════════
// ANONYMOUS EXTRACTION (2026-proven): watch-page warm-up + ANDROID_VR
// 1.65.10. No login, no PO token. Validated live: playabilityStatus=OK,
// 10 direct formats, stream probe HTTP 206.
// ═══════════════════════════════════════════════════════════════════

const VR_UA: &str = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
const PAGE_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)";

struct WarmSession {
    cookies: String,
    visitor_id: String,
    signature_ts: u32,
}

/// Byte-span finder: content between `pre` and `post` literals (no regex).
fn span_between<'a>(text: &'a str, pre: &str, post: &str) -> Option<&'a str> {
    let start = text.find(pre)? + pre.len();
    let rest = &text[start..];
    let end = rest.find(post)?;
    Some(&rest[..end])
}

/// Visits the watch page exactly like a warmed browser and harvests the
/// anti-bot context: session cookies, X-Goog-Visitor-Id, signatureTimestamp.
async fn warm_watch_session(client: &Client, video_id: &str) -> Result<WarmSession, String> {
    let url = format!(
        "https://www.youtube.com/watch?v={video_id}&bpctr=9999999999&has_verified=1&hl=en"
    );
    let resp = client
        .get(&url)
        .header("User-Agent", PAGE_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-us,en;q=0.5")
        .header("Cookie", "PREF=hl=en&tz=UTC; SOCS=CAI") // consent bypass
        .header("Sec-Fetch-Mode", "navigate")
        .send()
        .await
        .map_err(|e| format!("warm fetch failed: {e}"))?;

    // Harvest Set-Cookie name=value pairs manually (no cookie_store feature).
    let mut jar: Vec<String> = vec!["PREF=hl=en&tz=UTC".into(), "SOCS=CAI".into()];
    let mut seen: std::collections::HashSet<String> =
        ["PREF".to_string(), "SOCS".to_string()].into_iter().collect();
    for hv in resp.headers().get_all(reqwest::header::SET_COOKIE) {
        if let Ok(cv) = hv.to_str() {
            if let Some(nv) = cv.split(';').next() {
                if let Some(eq) = nv.find('=') {
                    let name = nv[..eq].trim().to_string();
                    if seen.insert(name.clone()) {
                        jar.push(nv.trim().to_string());
                    }
                }
            }
        }
    }

    let html = resp.text().await.map_err(|e| format!("warm read failed: {e}"))?;

    let visitor_id = span_between(&html, "\"visitorData\":\"", "\"").unwrap_or_default().to_string();
    let signature_ts: u32 = span_between(&html, "\"signatureTimestamp\":", ",")
        .and_then(|s| s.trim_matches(',').parse().ok())
        .or_else(|| span_between(&html, "\"signatureTimestamp\":", "}").and_then(|s| s.parse().ok()))
        .unwrap_or(20683);

    if visitor_id.is_empty() {
        return Err("warm page missing visitorData".to_string());
    }

    Ok(WarmSession { cookies: jar.join("; "), visitor_id, signature_ts })
}

/// Anonymous ANDROID_VR resolution — the proven-working production path.
pub async fn fetch_stream_anonymous(client: &Client, video_id: &str) -> Result<String, String> {
    let clean_id = video_id.trim();
    if clean_id.is_empty() {
        return Err("Empty video ID".to_string());
    }

    let session = warm_watch_session(client, clean_id).await?;

    let body = serde_json::json!({
        "context": {"client": {
            "clientName": "ANDROID_VR",
            "clientVersion": "1.65.10",
            "deviceMake": "Oculus",
            "deviceModel": "Quest 3",
            "androidSdkVersion": 32,
            "userAgent": VR_UA,
            "osName": "Android",
            "osVersion": "12L",
            "hl": "en",
            "timeZone": "UTC",
            "utcOffsetMinutes": 0
        }},
        "videoId": clean_id,
        "playbackContext": {"contentPlaybackContext": {
            "html5Preference": "HTML5_PREF_WANTS",
            "signatureTimestamp": session.signature_ts
        }},
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    let mut req = client
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("Content-Type", "application/json")
        .header("User-Agent", VR_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-us,en;q=0.5")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Cookie", &session.cookies)
        .header("X-Youtube-Client-Name", "28")
        .header("X-Youtube-Client-Version", "1.65.10")
        .header("Origin", "https://www.youtube.com");
    if !session.visitor_id.is_empty() {
        req = req.header("X-Goog-Visitor-Id", &session.visitor_id);
    }

    let res = req.json(&body).send().await
        .map_err(|e| format!("player request failed: {e}"))?;
    let json: Value = res.json().await.map_err(|e| format!("player json: {e}"))?;

    let ps = json.pointer("/playabilityStatus/status").and_then(|v| v.as_str()).unwrap_or("(none)");
    if ps != "OK" {
        let reason = json.pointer("/playabilityStatus/reason").and_then(|v| v.as_str()).unwrap_or("");
        return Err(format!("anonymous player blocked: {ps} {reason}"));
    }

    extract_best_audio_url(&json).ok_or_else(|| {
        "SABR-era response: no direct audio URLs (serverAbrStreamingUrl only)".to_string()
    })
}


/// MASTER RESOLUTION (Tier order):
///   1. Anonymous ANDROID_VR 1.65.10 with watch-page warm-up (no login!)
///   2. Authenticated WEB_REMIX (when session provided)
///   3. Legacy android cascade
pub async fn resolve_stream_master(
    client: &Client,
    video_id: &str,
    auth_header: &str,
    cookies: &str,
) -> Result<String, String> {
    // Tier 1 — anonymous (works for the vast majority of tracks)
    match fetch_stream_anonymous(client, video_id).await {
        Ok(u) => Ok(u),
        Err(e_anon) => {
            // Tier 2 — authenticated WEB_REMIX fallback (age-gated etc.)
            if !auth_header.trim().is_empty() && !cookies.trim().is_empty() {
                if let Ok(u) = fetch_innertube_cdn(client, video_id, auth_header, cookies).await {
                    return Ok(u);
                }
            }
            Err(e_anon)
        }
    }
}

pub async fn fetch_innertube_cdn(
    client: &Client,
    video_id: &str,
    auth_header: &str,
    cookies: &str,
) -> Result<String, String> {
    let clean_id = video_id.trim();
    if clean_id.is_empty() {
        return Err("Empty video ID".to_string());
    }

    let authenticated = !auth_header.trim().is_empty() && !cookies.trim().is_empty();

    // 1. Authenticated WEB_REMIX target — mirrors the user's own browser
    //    session on music.youtube.com.
    if authenticated {
        let url = "https://music.youtube.com/youtubei/v1/player";
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240401.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": clean_id,
            "contentCheckOk": true,
            "racyCheckOk": true
        });

        if let Ok(res) = client
            .post(url)
            .header("Content-Type", "application/json")
            .header("Authorization", auth_header.trim())
            .header("X-Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Cookie", cookies)
            .json(&body)
            .send()
            .await
        {
            if let Ok(json) = res.json::<Value>().await {
                if let Some(url_str) = extract_best_audio_url(&json) {
                    return Ok(url_str);
                }
            }
        }
    }

    // 2. Legacy android-client cascade (kept for completeness; typically
    //    bot-walled without login).
    let url = "https://music.youtube.com/youtubei/v1/player";
    let body = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_MUSIC",
                "clientVersion": "6.42.52",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "21")
        .header("X-YouTube-Client-Version", "6.42.52")
        .json(&body)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    // 2. Fallback to Android Official Target
    let url_android = "https://www.youtube.com/youtubei/v1/player";
    let body_android = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID",
                "clientVersion": "21.26.364",
                "androidSdkVersion": 30,
                "osName": "Android",
                "osVersion": "11",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url_android)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "3")
        .header("X-YouTube-Client-Version", "21.26.364")
        .json(&body_android)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    // 3. Fallback to Android VR
    let body_vr = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_VR",
                "clientVersion": "1.60.19",
                "deviceMake": "Oculus",
                "deviceModel": "Quest 3",
                "osName": "Android",
                "osVersion": "12",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url_android)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "28")
        .header("X-YouTube-Client-Version", "1.60.19")
        .json(&body_vr)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    Err("Failed to extract playable audio CDN URL".to_string())
}

fn extract_best_audio_url(json: &Value) -> Option<String> {
    // 1. Try direct adaptiveFormats inspection prioritizing audio
    if let Some(formats) = json.pointer("/streamingData/adaptiveFormats").and_then(|v| v.as_array()) {
        let mut best_audio: Option<(&str, u64)> = None;

        for fmt in formats {
            let mime = fmt.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
            if mime.starts_with("audio/") {
                if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                    let bitrate = fmt.get("bitrate").and_then(|v| v.as_u64()).unwrap_or(0);
                    if best_audio.is_none_or(|(_, b)| bitrate > b) {
                        best_audio = Some((url, bitrate));
                    }
                }
            }
        }

        if let Some((url, _)) = best_audio {
            return Some(url.to_string());
        }

        // Fallback to any valid direct url in formats
        for fmt in formats {
            if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                return Some(url.to_string());
            }
        }
    }

    // 2. Fallback to combined streamingData.formats
    if let Some(formats) = json.pointer("/streamingData/formats").and_then(|v| v.as_array()) {
        for fmt in formats {
            if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                return Some(url.to_string());
            }
        }
    }

    None
}

pub struct StreamResolver;

impl StreamResolver {
    #[allow(dead_code)] // URL reservation for the authenticated player path
    const INNERTUBE_PLAYER_URL: &'static str = "https://www.youtube.com/youtubei/v1/player";
    const INNERTUBE_SEARCH_URL: &'static str = "https://music.youtube.com/youtubei/v1/search";

    /// Resolves direct playable CDN stream URLs synchronously
    pub fn resolve_stream(video_id: &str) -> Result<Vec<ResolvedStreamFormat>, String> {
        let rt = get_runtime();
        let client = get_client();
        rt.block_on(async {
            let cdn_url = fetch_innertube_cdn(client, video_id, "", "").await?;
            Ok(vec![ResolvedStreamFormat {
                url: cdn_url,
                mime_type: "audio/mp4".to_string(),
                bitrate: 128000,
                duration_sec: 0,
                is_audio_only: true,
            }])
        })
    }

    /// Searches YouTube Music and returns parsed candidates
    pub fn search_music(query: &str, limit: usize) -> Result<Vec<ParsedCandidate>, String> {
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20230515.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "query": query,
            "params": "egWKAQIIAWoMEAMQBBAJEAoQBRAV"
        });

        // Tier-0 B1 hang guard (#23 triage): one-shot ureq calls had no
        // deadline — same stall class as the pooled client (a held-open
        // socket parks the FFI thread forever). 20 s overall matches the
        // downloader agent's 8 s connect + 15 s read discipline with a
        // little headroom for the ~1 MB response body.
        let resp = ureq::post(Self::INNERTUBE_SEARCH_URL)
            .timeout(Duration::from_secs(20))
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .set("Content-Type", "application/json")
            .send_json(body)
            .map_err(|e| format!("Search request failed: {}", e))?;

        let text = resp
            .into_string()
            .map_err(|e| format!("Failed to read search response: {}", e))?;

        let mut candidates = crate::json::InnertubeParser::parse_candidates(&text);
        if candidates.len() > limit {
            candidates.truncate(limit);
        }
        Ok(candidates)
    }

    /// Imports a public Spotify playlist or album URL and extracts track items
    pub fn import_spotify_tracks(spotify_url: &str) -> Result<Vec<ParsedCandidate>, String> {
        let clean_url = spotify_url.trim();
        let (item_type, item_id) = Self::parse_spotify_uri(clean_url)
            .ok_or_else(|| "Invalid Spotify URL or URI".to_string())?;

        let embed_url = format!("https://open.spotify.com/embed/{}/{}", item_type, item_id);
        // Tier-0 B1 hang guard (#23 triage): see the search_music note —
        // 20 s deadline on the one-shot embed fetch.
        let resp = ureq::get(&embed_url)
            .timeout(Duration::from_secs(20))
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .call()
            .map_err(|e| format!("Spotify embed request failed: {}", e))?;

        let html = resp
            .into_string()
            .map_err(|e| format!("Failed to read embed HTML: {}", e))?;

        let re = regex::Regex::new(r#"<script id="(?:session|__NEXT_DATA__|resource)" type="application/json">([^<]+)</script>"#)
            .map_err(|e| e.to_string())?;

        let mut tracks = Vec::new();
        if let Some(captures) = re.captures(&html) {
            if let Some(json_match) = captures.get(1) {
                let parsed: Value = serde_json::from_str(json_match.as_str())
                    .map_err(|e| format!("Failed to parse embedded Spotify JSON: {}", e))?;

                let track_list = parsed
                    .pointer("/props/pageProps/state/data/entity/trackList")
                    .or_else(|| parsed.pointer("/props/state/data/entity/trackList"))
                    .or_else(|| parsed.pointer("/entity/trackList"))
                    .or_else(|| parsed.pointer("/trackList"))
                    .and_then(|v| v.as_array());

                if let Some(items) = track_list {
                    for (idx, item) in items.iter().enumerate() {
                        let title = item.get("title").or_else(|| item.get("name")).and_then(|v| v.as_str()).unwrap_or("Unknown Title");
                        let subtitle = item.get("subtitle").or_else(|| item.get("artist")).and_then(|v| v.as_str()).unwrap_or("Unknown Artist");
                        let duration_ms = item.get("duration").and_then(|v| v.as_u64()).unwrap_or(0) as u32;

                        tracks.push(ParsedCandidate {
                            id: format!("spotify_{}_{}", item_id, idx),
                            title: title.to_string(),
                            artist: subtitle.to_string(),
                            album: "Spotify Import".to_string(),
                            duration_sec: duration_ms / 1000,
                            thumbnail_url: "".to_string(),
                            score: 100,
                        });
                    }
                }
            }
        }

        Ok(tracks)
    }

    fn parse_spotify_uri(url: &str) -> Option<(&str, &str)> {
        if url.starts_with("spotify:") {
            let parts: Vec<&str> = url.split(':').collect();
            if parts.len() >= 3 {
                return Some((parts[1], parts[2]));
            }
        }

        if url.contains("open.spotify.com") {
            let path = url.split("open.spotify.com/").nth(1)?;
            let parts: Vec<&str> = path.split('?').next()?.split('/').collect();
            if parts.len() >= 2 {
                return Some((parts[0], parts[1]));
            }
        }

        None
    }
}
