//! streamify-wasm — WS-W P0 proof facade (W0.2 / W0.4).
//!
//! Compiles the vendored, wasm-patched rustypipe YouTube Music engine to
//! wasm32-unknown-unknown and exposes a minimal wasm-bindgen surface:
//!
//!   * [`ping`] — the W0.2 alive check (`"pong"`).
//!   * [`set_relay_base_url`] — routes every rustypipe HTTP call through
//!     the streamify-relay Cloudflare Worker (`{relay}/r?u=<encoded>`),
//!     because browsers cannot call youtubei.googleapis.com directly
//!     (CORS 403).
//!   * [`search_music`] — rustypipe `music_search_tracks`, returned as a
//!     JSON STRING. This is the JSON-string bridge: `serde_wasm_bindgen`
//!     maps `serde_json::Value` to JS Maps, and `JSON.stringify(Map)` is
//!     `"{}"` — so we pass a string and parse on the TS side (W0.1
//!     landmine #3).
//!   * [`resolve_stream`] — the best audio format for the 10-second
//!     playback proof (G0.4): m4a first, then webm, bitrate descending —
//!     mirrors the engine's `map_player_to_formats` ordering contract
//!     (rust/crates/streamify-resolver/src/rustypipe_engine.rs).
//!
//! Known web-build deviations (see third_party/rustypipe/PATCHES.md):
//! QuickJS signature deobfuscation and PO-token minting are compiled OUT
//! on wasm — the PO-free client ladder is the web strategy, exactly as the
//! engine already degrades on iOS (rustypipe_engine.rs module docs).
//!
//! W0.1 landmines honored here:
//!   * #2 async extern machinery — all wasm-bindgen async fns ride
//!     wasm-bindgen-futures' promise glue (no `spawn_local` around async
//!     extern "C").
//!   * #4 `SystemTime::now()` traps on wasm32-unknown-unknown — the
//!     vendored crate routes every clock read through
//!     `js_sys::Date::now()`.

use std::sync::OnceLock;

use serde::{Deserialize, Serialize};
use wasm_bindgen::prelude::*;

use rustypipe::client::{ClientType, RustyPipe, RustyPipeBuilder};
use rustypipe::model::{AudioFormat, AudioStream, TrackItem, VideoFormat, VideoPlayer};

/// W0.2 alive check.
#[wasm_bindgen]
pub fn ping() -> String {
    "pong".into()
}

/// Route all rustypipe HTTP traffic through the streamify-relay worker
/// (e.g. `https://streamify-relay.<account>.workers.dev`, or
/// `http://localhost:8787` against a local `wrangler dev`). No-op on
/// non-wasm builds.
#[wasm_bindgen]
pub fn set_relay_base_url(u: &str) {
    #[cfg(target_family = "wasm")]
    rustypipe::client::set_relay_base_url(Some(u));
    #[cfg(not(target_family = "wasm"))]
    let _ = u;
}

// ---------------------------------------------------------------------------
// Client singleton
// ---------------------------------------------------------------------------

/// Web player ladder (WS-W): the wasm build has no botguard, so rustypipe's
/// default order is [Ios, Tv] — from datacenter IPs the Ios client draws the
/// bot-wall first. The Android app client (no obfuscated URLs per
/// rustypipe's own docs) resolves standard content from datacenter IPs
/// (verified in the W0.4 curl matrix); licensed content stays bot-walled
/// everywhere from datacenter — that is the canary's question, not this
/// facade's.
const PLAYER_LADDER: &[ClientType] = &[ClientType::Android, ClientType::Ios, ClientType::Tv];

/// The wasm engine's Clone-cheap handle (the engine holds it in the
/// same kind of process-wide singleton, see rustypipe_engine.rs::client).
/// Built without cache storage — the browser sandbox has no writable cache
/// lane for it in P0.
fn client() -> Result<RustyPipe, String> {
    static CLIENT: OnceLock<Result<RustyPipe, String>> = OnceLock::new();
    CLIENT
        .get_or_init(|| {
            RustyPipeBuilder::new()
                .no_storage()
                .build()
                .map_err(|e| format!("rustypipe init failed: {e}"))
        })
        .clone()
}

// ---------------------------------------------------------------------------
// Track JSON (the JSON-string bridge payload)
// ---------------------------------------------------------------------------

/// Wire shape consumed by web/src/lib/engine.ts (`Track`).
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrackJson {
    video_id: String,
    title: String,
    artist: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    album: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_sec: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    thumbnail_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    track_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    view_count: Option<u64>,
}

/// Mirrors the engine's TrackItem mapping (map_tracks_to_search_candidates):
/// first artist, album name, cover art (highest-res thumb last in the vec).
fn map_track(item: &TrackItem) -> TrackJson {
    TrackJson {
        video_id: item.id.clone(),
        title: item.name.clone(),
        artist: item
            .artists
            .first()
            .map(|a| a.name.clone())
            .unwrap_or_default(),
        album: item.album.as_ref().map(|a| a.name.clone()),
        duration_sec: item.duration,
        thumbnail_url: item.cover.last().map(|t| t.url.clone()),
        track_type: Some(format!("{:?}", item.track_type)),
        view_count: item.view_count,
    }
}

// ---------------------------------------------------------------------------
// Stream resolution (the G0.4 / kill-criterion audio path)
// ---------------------------------------------------------------------------

/// Wire shape consumed by web/src/lib/engine.ts (`StreamInfo`).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StreamInfoJson {
    stream_url: String,
    itag: u32,
    mime_type: String,
    is_audio_only: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_sec: Option<u32>,
}

/// Audio container rank — mirrors the engine's `audio_rank` exactly:
/// m4a (AAC in MP4) first, webm (Opus) second.
fn audio_rank(format: &AudioFormat) -> u8 {
    match format {
        AudioFormat::M4a => 0,
        AudioFormat::Webm => 1,
        // `#[non_exhaustive]`: a future container ranks last.
        _ => 2,
    }
}

/// Muxed container rank — mirrors the engine's `muxed_rank` (the non-
/// audio-only fallback tail; the `<audio>` element plays the audio track of
/// a muxed MP4 just fine).
fn muxed_rank(format: &VideoFormat) -> u8 {
    match format {
        VideoFormat::Mp4 => 0,
        VideoFormat::Webm => 1,
        VideoFormat::ThreeGp => 2,
        _ => 3,
    }
}

/// Best audio stream for the proof — the prefix of the engine's
/// `map_player_to_formats` ordering contract: every audio-only format
/// precedes every muxed format; inside each class container rank, then
/// bitrate descending.
fn best_audio_stream(player: &VideoPlayer) -> Option<&AudioStream> {
    let mut candidates: Vec<&AudioStream> = player
        .audio_streams
        .iter()
        .filter(|s| !s.url.is_empty())
        .collect();
    candidates.sort_by(|a, b| {
        audio_rank(&a.format)
            .cmp(&audio_rank(&b.format))
            .then(b.average_bitrate.max(b.bitrate).cmp(&a.average_bitrate.max(a.bitrate)))
    });
    candidates.into_iter().next()
}

/// Best muxed stream (fallback when no audio-only format carries a direct
/// URL — e.g. the Android client often answers with muxed formats only
/// from datacenter IPs).
fn best_muxed_stream(player: &VideoPlayer) -> Option<&rustypipe::model::VideoStream> {
    let mut candidates: Vec<&rustypipe::model::VideoStream> = player
        .video_streams
        .iter()
        .filter(|s| !s.url.is_empty())
        .collect();
    candidates.sort_by(|a, b| {
        muxed_rank(&a.format)
            .cmp(&muxed_rank(&b.format))
            .then(b.average_bitrate.max(b.bitrate).cmp(&a.average_bitrate.max(a.bitrate)))
    });
    candidates.into_iter().next()
}

// ---------------------------------------------------------------------------
// wasm-bindgen exports
// ---------------------------------------------------------------------------

/// Full error chain (reqwest wasm errors hide the JS exception in their
/// top-level Display — the cause chain carries "TypeError: ..." etc.).
fn fmt_err_chain(e: &dyn std::error::Error) -> String {
    let mut s = e.to_string();
    let mut cur = e.source();
    while let Some(src) = cur {
        s.push_str(" | cause: ");
        s.push_str(&src.to_string());
        cur = src.source();
    }
    s
}

/// W0.4 proof: search YouTube Music through the relay.
///
/// Returns a JSON STRING (the JSON-string bridge — parse on the TS side;
/// see the module docs for why serde values never cross the boundary
/// directly).
#[wasm_bindgen]
pub async fn search_music(query: &str) -> Result<JsValue, JsValue> {
    let rp = match client() {
        Ok(c) => c,
        Err(e) => return Err(JsValue::from_str(&e)),
    };
    match rp.query().music_search_tracks(query).await {
        Ok(result) => {
            let tracks: Vec<TrackJson> = result.items.items.iter().map(map_track).collect();
            let json = serde_json::to_string(&tracks).unwrap_or_else(|_| "[]".to_owned());
            Ok(JsValue::from_str(&json))
        }
        Err(e) => Err(JsValue::from_str(&format!(
            "search failed: {}",
            fmt_err_chain(&e)
        ))),
    }
}

/// G0.4 / kill-criterion path: resolve the best audio stream for a video
/// id. Returns a JSON STRING `{ streamUrl, itag, mimeType, isAudioOnly,
/// durationSec? }`, or the JSON string `"null"` when the player response
/// carries no direct audio URL (bot-walled / login-required / ciphered).
#[wasm_bindgen]
pub async fn resolve_stream(video_id: &str) -> Result<JsValue, JsValue> {
    let rp = match client() {
        Ok(c) => c,
        Err(e) => return Err(JsValue::from_str(&e)),
    };
    match rp
        .query()
        // Web ladder (WS-W): the wasm build has no botguard, so rustypipe's
        // default order is [Ios, Tv] — from datacenter IPs the Ios client
        // draws the bot-wall and Tv often follows. The Android app client
        // (no obfuscated URLs — rustypipe's own docs) resolves standard
        // content from datacenter IPs (verified in the W0.4 curl matrix:
        // Android + the yt-dlp ANDROID_VR/VISIONOS analogues return direct
        // URLs; licensed content stays bot-walled everywhere — that is the
        // canary's question, not this facade's).
        .player_from_clients(video_id, PLAYER_LADDER)
        .await
    {
        Ok(player) => {
            let json = if let Some(s) = best_audio_stream(&player) {
                let info = StreamInfoJson {
                    stream_url: s.url.clone(),
                    itag: s.itag,
                    mime_type: s.mime.clone(),
                    is_audio_only: true,
                    duration_sec: s.duration_ms.map(|ms| ms / 1000),
                };
                serde_json::to_string(&info).unwrap_or_else(|_| "null".to_owned())
            } else if let Some(s) = best_muxed_stream(&player) {
                // Muxed fallback — the <audio> element plays the audio track
                // of a muxed MP4 (G0.4 accepts "audio comes out of
                // speakers"; the engine's own ordering chains muxed after
                // audio-only).
                let info = StreamInfoJson {
                    stream_url: s.url.clone(),
                    itag: s.itag,
                    mime_type: s.mime.clone(),
                    is_audio_only: false,
                    duration_sec: s.duration_ms.map(|ms| ms / 1000),
                };
                serde_json::to_string(&info).unwrap_or_else(|_| "null".to_owned())
            } else {
                "null".to_owned()
            };
            Ok(JsValue::from_str(&json))
        }
        Err(e) => Err(JsValue::from_str(&format!("resolve failed: {}", fmt_err_chain(&e)))),
    }
}

// ---------------------------------------------------------------------------
// W0.2 AC: minimal wasm-bindgen-test — ping() must return "pong".
// Run on CI in headless Chrome (`cargo test --target wasm32-unknown-unknown
// --headless`); never run the engine's 429-test suite here — that stays on
// the frozen rust/ workspace's CI.
// ---------------------------------------------------------------------------
#[cfg(all(target_arch = "wasm32", test))]
mod tests {
    use super::*;
    use wasm_bindgen_test::*;

    #[wasm_bindgen_test]
    fn ping_returns_pong() {
        assert_eq!(ping(), "pong");
    }
}
