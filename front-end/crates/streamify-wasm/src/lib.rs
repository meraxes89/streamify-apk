//! streamify-wasm — WS-W P0 proof crate: wasm-bindgen facade over the
//! engine's pure innertube extraction core.
//!
//! Architecture (see front-end/decisions/W0.2-spike-rustypipe-wasm32.md):
//! the browser cannot call youtubei/googlevideo directly (CORS is the whole
//! reason the relay exists), so every request rides the Cloudflare relay:
//!
//!     TypeScript ──▶ [this facade] ──web-sys fetch──▶ RELAY /r?u=<target>
//!                                                   ──▶ youtubei / googlevideo
//!                                                   ◀── body streamed back
//!
//! The facade builds the exact client requests the engine's Tier-5
//! production path builds (src/clients.rs), parses responses with the
//! engine's own vendored InnertubeParser (src/innertube.rs), and hands
//! results to TypeScript across a JSON-string bridge.
//!
//! W0.1 landmines baked in (each cost a day when hit blind — keep these):
//!  1. JSON-string bridge: serde_wasm_bindgen serializes serde_json::Value
//!     as JS Maps (`JSON.stringify(Map) === "{}"`); every cross-boundary
//!     payload below is a String containing JSON, parsed on the TS side.
//!  2. All promise machinery uses `JsFuture` — never `spawn_local` around
//!     async extern fns (the #1 "future never resolves" cause on wasm32).
//!  3. No `SystemTime::now()` (traps on wasm32-unknown-unknown); use
//!     `js_sys::Date::now()` if wall-clock is ever needed.

mod clients;
mod innertube;

use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;

use innertube::InnertubeParser;
use serde_json::{json, Value};

// ───────────────────────────── relay wiring ────────────────────────────────

thread_local! {
    /// Relay request prefix, e.g. "http://localhost:8787/r" (local wrangler)
    /// or "https://<worker>.workers.dev/r" (production), or the sandbox
    /// gateway form "/r?XTransformPort=8787". A "?" already present means the
    /// next param appends with "&" instead.
    static RELAY: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

/// Configure the relay prefix. MUST be called before search_music/get_stream.
#[wasm_bindgen]
pub fn set_relay(prefix: &str) {
    RELAY.with(|r| *r.borrow_mut() = Some(prefix.trim_end_matches('/').to_string()));
}

fn relay_url(target: &str) -> Result<String, JsValue> {
    let prefix = RELAY
        .with(|r| r.borrow().clone())
        .ok_or_else(|| JsValue::from_str("relay prefix not set — call set_relay() first"))?;
    let sep = if prefix.contains('?') { '&' } else { '?' };
    Ok(format!("{}{}u={}", prefix, sep, urlencoding::encode(target)))
}

// ─────────────────────────────── handshake ─────────────────────────────────

/// Engine handshake — the "can TypeScript call the engine?" smoke export
/// (W0.2 AC: wasm-pack pkg builds; test asserts "pong" in headless Chrome).
#[wasm_bindgen]
pub fn ping() -> String {
    "pong".to_string()
}

// ─────────────────────────────── transport ──────────────────────────────────

/// POST `target` (an absolute youtubei URL) through the relay with the given
/// JSON body and extra headers. Returns the response body text.
async fn relay_post_json(
    target: &str,
    body: String,
    headers: &[(&str, String)],
) -> Result<String, JsValue> {
    let url = relay_url(target)?;

    let init = web_sys::RequestInit::new();
    init.set_method("POST");
    let h = web_sys::Headers::new().map_err(js_err)?;
    h.set("Content-Type", "application/json").map_err(js_err)?;
    for (k, v) in headers {
        h.set(k, v).map_err(js_err)?;
    }
    init.set_headers(&h);
    init.set_body(&JsValue::from_str(&body));

    let req = web_sys::Request::new_with_str_and_init(&url, &init).map_err(js_err)?;
    let window = web_sys::window().ok_or_else(|| JsValue::from_str("no window object"))?;

    // Landmine 2: JsFuture, never spawn_local.
    let resp_val = wasm_bindgen_futures::JsFuture::from(window.fetch_with_request(&req)).await?;
    let resp: web_sys::Response = resp_val.unchecked_into();

    let status = resp.status();
    let text = wasm_bindgen_futures::JsFuture::from(
        resp.text().map_err(js_err)?,
    )
    .await?
    .as_string()
    .unwrap_or_default();

    if !(200..300).contains(&status) {
        let preview: String = text.chars().take(200).collect();
        return Err(JsValue::from_str(&format!(
            "relay/http {} — {}",
            status, preview
        )));
    }
    Ok(text)
}

fn js_err(e: JsValue) -> JsValue {
    e
}

// ───────────────────────────── search facade ────────────────────────────────

/// Track DTO across the JSON-string bridge.
#[derive(serde::Serialize)]
struct Track {
    video_id: String,
    title: String,
    artist: String,
    album: String,
    duration_sec: u32,
    thumbnail_url: String,
}

/// Search YouTube Music through the relay. Returns a JSON string:
/// `{"ok":true,"tracks":[…]}` or `{"ok":false,"error":"…"}`.
#[wasm_bindgen]
pub async fn search_music(query: String) -> String {
    match search_music_inner(&query).await {
        Ok(tracks) => json!({ "ok": true, "tracks": tracks }).to_string(),
        Err(e) => err_json(&e),
    }
}

async fn search_music_inner(query: &str) -> Result<Vec<Track>, JsValue> {
    let target = format!(
        "{}?key={}&prettyPrint=false",
        clients::SEARCH_URL,
        clients::INNER_TUBE_WEB_CLIENT_KEY
    );
    let headers: Vec<(&str, String)> = vec![
        ("X-YouTube-Client-Name", clients::SEARCH_CLIENT_NUMBER.to_string()),
        ("X-YouTube-Client-Version", clients::SEARCH_CLIENT_VERSION.to_string()),
    ];
    let raw = relay_post_json(&target, clients::search_body(query), &headers).await?;

    // Engine parser first — production shape parity.
    let mut candidates = InnertubeParser::parse_candidates(&raw);

    // Probe-parity enrichment (tools/probe-search-resolver.sh fallbacks):
    // overlay-path videoIds the engine parser doesn't cover, musicCardShelf
    // top results, fixedColumns durations, joined runs for title/artist.
    enrich_from_probe_paths(&raw, &mut candidates);

    let tracks: Vec<Track> = candidates
        .into_iter()
        .filter(|c| is_valid_video_id(&c.id))
        .take(20)
        .map(|c| Track {
            title: c.title,
            artist: strip_artist_noise(&c.artist),
            album: c.album,
            duration_sec: c.duration_sec,
            thumbnail_url: c.thumbnail_url,
            video_id: c.id,
        })
        .collect();

    if tracks.is_empty() {
        return Err(JsValue::from_str(
            "search returned 0 parseable tracks (response shape unrecognized or endpoint blocked)",
        ));
    }
    Ok(tracks)
}

// ─────────────────────────── playback facade ───────────────────────────────

/// Resolve a direct CDN audio stream URL for `video_id` by walking the engine
/// player ladder (ANDROID_VR → ANDROID → IOS) through the relay. Returns a
/// JSON string: `{"ok":true,"client":"…","status":"OK","ciphered":N,
/// "stream":{url,mimeType,bitrate,durationSec,itag,loudnessDb}}`
/// or `{"ok":false,"error":"…","attempts":[…]}`.
#[wasm_bindgen]
pub async fn get_stream(video_id: String) -> String {
    if !is_valid_video_id(&video_id) {
        return err_json_str("invalid video id (expected 11 chars [A-Za-z0-9_-])");
    }

    let mut attempts: Vec<Value> = Vec::new();
    for client in clients::PlayerClient::LADDER {
        let headers: Vec<(&str, String)> = vec![
            ("X-YouTube-Client-Name", client.client_number().to_string()),
            ("X-YouTube-Client-Version", client.client_version().to_string()),
            // Browsers forbid setting User-Agent on fetch; the relay swaps
            // this header into the outbound User-Agent.
            ("X-Streamify-UA", client.user_agent().to_string()),
        ];
        let outcome = match relay_post_json(
            clients::PLAYER_URL,
            client.body(&video_id),
            &headers,
        )
        .await
        {
            Ok(raw) => match InnertubeParser::extract_stream_verdict(&raw) {
                Some(verdict) => {
                    let status = verdict.status.clone();
                    let reason = verdict.reason.clone();
                    let ciphered = verdict.ciphered_count;
                    if status == "OK" && !verdict.direct_formats.is_empty() {
                        let best = &verdict.direct_formats[0]; // parser sorts Opus(251) > AAC(140) > bitrate
                        return json!({
                            "ok": true,
                            "client": client.label(),
                            "status": status,
                            "ciphered": ciphered,
                            "stream": {
                                "url": best.url,
                                "mimeType": best.mime_type,
                                "bitrate": best.bitrate,
                                "durationSec": best.duration_sec,
                                "itag": best.itag,
                                "loudnessDb": best.loudness_db,
                            },
                            "attempts": attempts
                        })
                        .to_string();
                    }
                    json!({
                        "client": client.label(),
                        "status": status,
                        "reason": reason,
                        "ciphered": ciphered,
                        "directAudio": verdict.direct_formats.len()
                    })
                }
                None => json!({
                    "client": client.label(),
                    "status": "UNPARSEABLE",
                    "reason": "player response not recognized"
                }),
            },
            Err(e) => json!({
                "client": client.label(),
                "status": "TRANSPORT_ERROR",
                "reason": e.as_string().unwrap_or_default()
            }),
        };
        attempts.push(outcome);
    }

    err_json_value("all player clients failed — see attempts", &attempts)
}

// ──────────────────────────── parse enrichment ─────────────────────────────

/// resolver.rs `is_valid_video_id` parity.
fn is_valid_video_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

struct Suppl {
    id: String,
    title: String,
    artist: String,
    duration_sec: Option<u32>,
    thumbnail_url: String,
}

/// Walk the response for musicResponsiveListItemRenderer / musicCardShelfRenderer
/// nodes the engine parser's pointer set may miss, and patch in probe-parity
/// fields (overlay videoId path, fixedColumns duration, joined runs).
fn enrich_from_probe_paths(raw: &str, candidates: &mut Vec<innertube::ParsedCandidate>) {
    let root: Value = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return,
    };
    let mut suppl: Vec<Suppl> = Vec::new();
    walk_supp(&root, &mut suppl);

    for s in suppl {
        if !is_valid_video_id(&s.id) {
            continue;
        }
        if let Some(existing) = candidates.iter_mut().find(|c| c.id == s.id) {
            if existing.duration_sec == 210 {
                if let Some(d) = s.duration_sec {
                    existing.duration_sec = d;
                }
            }
            if existing.title == "Unknown Title" && !s.title.is_empty() {
                existing.title = s.title;
            }
            if existing.artist == "Unknown Artist" && !s.artist.is_empty() {
                existing.artist = s.artist;
            }
            if existing.thumbnail_url.is_empty() && !s.thumbnail_url.is_empty() {
                existing.thumbnail_url = s.thumbnail_url;
            }
        } else {
            candidates.push(innertube::ParsedCandidate {
                id: s.id,
                title: s.title,
                artist: s.artist,
                album: "YouTube Music".to_string(),
                duration_sec: s.duration_sec.unwrap_or(210),
                thumbnail_url: s.thumbnail_url,
                score: 80,
            });
        }
    }
}

fn walk_supp(val: &Value, out: &mut Vec<Suppl>) {
    match val {
        Value::Object(map) => {
            if let Some(item) = map.get("musicResponsiveListItemRenderer") {
                if let Some(s) = supp_music_item(item) {
                    out.push(s);
                }
            } else if let Some(card) = map.get("musicCardShelfRenderer") {
                if let Some(s) = supp_card(card) {
                    out.push(s);
                }
            } else {
                for v in map.values() {
                    walk_supp(v, out);
                }
            }
        }
        Value::Array(arr) => {
            for v in arr {
                walk_supp(v, out);
            }
        }
        _ => {}
    }
}

/// Joined runs text (probe jq: `text.runs[*].text` joined) or simpleText.
fn text_at(node: &Value, pointer: &str) -> Option<String> {
    let t = node.pointer(pointer)?;
    if let Some(simple) = t.get("simpleText").and_then(|v| v.as_str()) {
        return Some(simple.to_string());
    }
    t.get("runs")
        .and_then(|r| r.as_array())
        .map(|runs| {
            runs.iter()
                .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
                .collect::<String>()
        })
        .filter(|s| !s.is_empty())
}

fn supp_music_item(item: &Value) -> Option<Suppl> {
    // videoId fallbacks in probe order: playlistItemData → doubleTapCommand →
    // playNavigationEndpoint → overlay play button (probe-search-resolver.sh).
    let id = item
        .pointer("/playlistItemData/videoId")
        .or_else(|| item.pointer("/doubleTapCommand/watchEndpoint/videoId"))
        .or_else(|| {
            item.pointer("/playNavigationEndpoint/watchEndpoint/videoId")
        })
        .or_else(|| {
            item.pointer(
                "/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId",
            )
        })
        .and_then(|v| v.as_str())?
        .to_string();

    let title = text_at(
        item,
        "/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text",
    )
    .unwrap_or_else(|| "Unknown Title".to_string());

    let artist = text_at(
        item,
        "/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text",
    )
    .unwrap_or_else(|| "Unknown Artist".to_string());

    let duration_sec = item
        .pointer(
            "/fixedColumns/0/musicResponsiveListItemFixedColumnRenderer/text",
        )
        .and_then(|t| {
            text_at_value(t)
                .map(|s| InnertubeParser::parse_duration_str(&s))
        });

    let thumbnail_url = item
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.last())
        .and_then(|last| last.get("url"))
        .and_then(|u| u.as_str())
        .unwrap_or("")
        .to_string();

    Some(Suppl {
        id,
        title,
        artist,
        duration_sec,
        thumbnail_url,
    })
}

fn supp_card(card: &Value) -> Option<Suppl> {
    let id = card
        .pointer("/onTap/watchEndpoint/videoId")
        .and_then(|v| v.as_str())?
        .to_string();
    let title = text_at(card, "/title").unwrap_or_else(|| "Unknown Title".to_string());
    let artist = text_at(card, "/subtitle").unwrap_or_else(|| "Unknown Artist".to_string());
    Some(Suppl {
        id,
        title,
        artist,
        duration_sec: None,
        thumbnail_url: String::new(),
    })
}

fn text_at_value(t: &Value) -> Option<String> {
    if let Some(simple) = t.get("simpleText").and_then(|v| v.as_str()) {
        return Some(simple.to_string());
    }
    t.get("runs")
        .and_then(|r| r.as_array())
        .map(|runs| {
            runs.iter()
                .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
                .collect::<String>()
        })
        .filter(|s| !s.is_empty())
}

/// Mirrors resolver.rs `strip_artist_noise` (fuzzy-search-side normalization,
/// vendored here because YTM song rows carry "The Weeknd - Topic" artists).
fn strip_artist_noise(artist: &str) -> String {
    let lower = artist.to_lowercase();
    for suffix in [" - topic", " - official artist channel", " - official"] {
        if let Some(stripped) = lower.strip_suffix(suffix) {
            return artist[..stripped.len()].trim().to_string();
        }
    }
    if lower.ends_with("vevo") {
        let stripped = &artist[..artist.len() - 4];
        return stripped
            .trim_end_matches(['-', '–', ' ', '_'])
            .trim()
            .to_string();
    }
    artist.to_string()
}

// ──────────────────────────── error envelopes ───────────────────────────────

fn err_json(msg: &JsValue) -> String {
    let m = msg.as_string().unwrap_or_else(|| "unknown wasm error".into());
    json!({ "ok": false, "error": m }).to_string()
}

fn err_json_str(msg: &str) -> String {
    json!({ "ok": false, "error": msg }).to_string()
}

fn err_json_value(msg: &str, attempts: &[Value]) -> String {
    json!({ "ok": false, "error": msg, "attempts": attempts }).to_string()
}

// ─────────────────────────────── tests ─────────────────────────────────────

#[cfg(test)]
mod tests {
    use wasm_bindgen_test::*;

    wasm_bindgen_test_configure!(run_in_browser);

    #[wasm_bindgen_test]
    fn ping_returns_pong() {
        assert_eq!(crate::ping(), "pong");
    }

    #[wasm_bindgen_test]
    fn video_id_validation_matches_engine() {
        assert!(crate::is_valid_video_id("dQw4w9WgXcQ"));
        assert!(!crate::is_valid_video_id("short"));
        assert!(!crate::is_valid_video_id("toolongidstring11"));
        assert!(!crate::is_valid_video_id("bad*chars*id"));
    }

    #[wasm_bindgen_test]
    fn artist_noise_is_stripped_like_engine() {
        assert_eq!(crate::strip_artist_noise("The Weeknd - Topic"), "The Weeknd");
        assert_eq!(crate::strip_artist_noise("SOMEBANDVEVO"), "SOMEBAND");
        assert_eq!(crate::strip_artist_noise("Clean Artist"), "Clean Artist");
    }

    #[wasm_bindgen_test]
    fn search_enrichment_finds_overlay_video_id() {
        // Minimal musicResponsiveListItemRenderer whose videoId exists ONLY
        // on the probe overlay path — the engine parser drops it, the
        // enrichment pass must recover it.
        let raw = serde_json::json!({
            "contents": [{
                "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                        { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Blinding Lights" } ] } } },
                        { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "The Weeknd - Topic" } ] } } }
                    ],
                    "fixedColumns": [
                        { "musicResponsiveListItemFixedColumnRenderer": { "text": { "simpleText": "3:20" } } }
                    ],
                    "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": {
                        "playNavigationEndpoint": { "watchEndpoint": { "videoId": "dQw4w9WgXcQ" } }
                    }}}}
                }
            }]
        })
        .to_string();

        let mut candidates = crate::innertube::InnertubeParser::parse_candidates(&raw);
        crate::enrich_from_probe_paths(&raw, &mut candidates);

        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].id, "dQw4w9WgXcQ");
        assert_eq!(candidates[0].title, "Blinding Lights");
        assert_eq!(candidates[0].artist, "The Weeknd - Topic");
        assert_eq!(candidates[0].duration_sec, 200);
    }
}
