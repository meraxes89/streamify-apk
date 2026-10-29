//! RustyPipe extraction engine — Discussion #120, Part B.
//!
//! The pure-Rust NewPipe replacement: all YouTube Music extraction in
//! this crate that previously hand-rolled anonymous Innertube calls now
//! rides [`rustypipe`] — a native client with an embedded QuickJS
//! evaluator that deciphers the player's signature and `n`-parameter
//! transforms in-process, and a client-fallback ladder
//! (Desktop -> iOS -> TV, see `player_client_order` in the rustypipe
//! source) that walks SABR-era responses without an Android WebView.
//! The legacy raw-Innertube paths in [`crate::resolver`] remain compiled
//! as the degraded final tier so a rustypipe regression can never take
//! the engine below today's production behavior.
//!
//! Proof-of-Origin (PO) tokens: rustypipe 0.11.4 mints PO tokens through
//! the `rustypipe-botguard` companion-binary protocol (it spawns
//! `rustypipe-botguard --snapshot-file <path> -- <idents>` and parses
//! the `valid_until=` tail; it has no in-process token-injection API —
//! see the `rustypipe-botguard` entry in this crate's Cargo.toml for
//! the full rationale). When a botguard binary is reachable (desktop
//! PATH, an explicit override, or Android's nativeLibraryDir where the
//! shell ships the companion binary under a `lib*.so` name), the engine
//! upgrades to the Desktop client ladder with PO-token-backed,
//! de-throttled CDN URLs. Without one, it degrades gracefully to the
//! PO-free iOS/TV ladder — resolution still works. Configure the binary
//! with (in priority order):
//!
//!   1. [`set_botguard_binary_path`] (FFI/UniFFI setter, called before
//!      the first extraction of the process),
//!   2. the `STREAMIFY_BOTGUARD_BIN` environment variable (desktop
//!      shells), or
//!   3. rustypipe's own default detection (`./rustypipe-botguard`, then
//!      `$PATH`).
//!
//! THREADING CONTRACT (resp5 §2.1): every async surface in this module
//! executes on the engine's single shared multi-threaded tokio runtime
//! ([`crate::resolver::get_runtime`]). The RustyPipe client itself is a
//! `Clone`-cheap handle over one reqwest connection pool — no hidden
//! runtimes, no background executors; the botguard child process is
//! spawned via `tokio::process::Command` on the same runtime. Callers on
//! the synchronous FFI boundary use `get_runtime().block_on(...)`
//! exactly like the existing entry points.
//!
//! CACHING (Part B.4): two layers, both SQLite.
//!
//!   * Long-lived *deciphered player transforms* (rustypipe's deobf
//!     data — the deobfuscation JS extracted from player.js plus the
//!     signature timestamp) live in the client's [`CacheStorage`],
//!     implemented by [`SqliteDeobfCache`] on top of streamify-storage's
//!     pooled lanes. That is the expensive cold-start layer: without
//!     it, every process start would re-download and re-parse
//!     player.js.
//!   * Short-lived *resolved CDN format sets* per video id live in the
//!     resolver cache with a TTL derived from the player response's own
//!     `valid_until` (minus a safety margin, see [`cdn_ttl_seconds`]),
//!     giving sub-10 ms repeat resolves while the URLs are still
//!     provably fresh.

use std::ffi::OsString;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use rustypipe::cache::CacheStorage;
use rustypipe::client::RustyPipe;
use rustypipe::error::{Error as RpError, ExtractionError, UnavailabilityReason};
use rustypipe::model::{AudioFormat, TrackItem, VideoFormat, VideoPlayer};
use time::OffsetDateTime;

use streamify_core::{EngineError, NetworkErrorKind};
use streamify_storage::resolver_cache;

use crate::json::{ParsedCandidate, ResolvedStreamFormat};
use crate::resolver::SearchCandidate;

/// Sub-key under which the rustypipe deobfuscation cache (client
/// versions + deciphered player.js transforms) is persisted through
/// [`streamify_storage::resolver_cache`] meta storage.
pub const DEOBF_CACHE_KEY: &str = "rustypipe_deobf";

/// Safety margin subtracted from the player response's `valid_until`
/// before a resolved format set is cached (seconds). YouTube CDN URLs
/// routinely live ~6 h, but the `n`-parameter deciphering and PO-token
/// lifetime fold into `valid_until`; a fresh URL served at 99 % of its
/// life would 403 on the first seek. 15 min is the margin the rescan
/// cadence can absorb while keeping repeat resolves sub-10 ms.
const CDN_TTL_SAFETY_MARGIN_SEC: i64 = 15 * 60;

/// Floor for the CDN cache TTL (seconds): even a response with a tiny
/// `valid_until` tail is worth caching long enough for one back-to-back
/// repeat resolve (gapless pre-buffer of the next track reads the same
/// set twice within seconds).
const CDN_TTL_FLOOR_SEC: u64 = 60;

/// Ceiling for the CDN cache TTL (seconds): rustypipe reports
/// `expires_in_seconds` in the 21 540 (5 h 59 min) range; the ceiling
/// keeps a malformed future `valid_until` from pinning stale rows for
/// days on a device that never re-resolves.
const CDN_TTL_CEILING_SEC: u64 = 6 * 60 * 60;

/// TTL for cached music-search results (seconds). Search responses do
/// not carry a server-provided expiry; 24 h matches the universal-tracks
/// cache cadence (the video-id binding it feeds is permanent, the search
/// page merely re-ranks).
pub(crate) const SEARCH_CACHE_TTL_SEC: u64 = 24 * 60 * 60;

/// Request deadline carried over from the Tier-0 B1 hang-guard
/// discipline on the shared reqwest client (30 s total budget — kills
/// true hangs, leaves the 1-3 s cold-miss budget untouched).
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

// ---------------------------------------------------------------------------
// Botguard binary resolution (companion-binary protocol)
// ---------------------------------------------------------------------------

static BOTGUARD_BINARY_OVERRIDE: OnceLock<String> = OnceLock::new();

/// Pin the `rustypipe-botguard` companion binary used for PO-token
/// minting.
///
/// Must be called BEFORE the first extraction of the process (the
/// RustyPipe client is built lazily on first use and probes the binary
/// once, at build time — see the module docs). Shells deploy the
/// companion binary at:
///
///   * Android: inside `nativeLibraryDir` under a `lib*.so` name (the
///     one app-local directory the loader marks executable) — the
///     shell passes the absolute path here,
///   * desktop: anywhere on `$PATH`, or an explicit path through this
///     setter / `STREAMIFY_BOTGUARD_BIN`.
///
/// An empty path is ignored. The setting is process-global and
/// idempotent: the first call wins, later calls are ignored (a live
/// RustyPipe client cannot be rebuilt mid-process without orphaning
/// its PO-token snapshot).
pub fn set_botguard_binary_path(path: &str) {
    let clean = path.trim();
    if clean.is_empty() {
        return;
    }
    let _ = BOTGUARD_BINARY_OVERRIDE.set(clean.to_string());
}

/// Effective botguard binary override, if any (explicit setter call or
/// `STREAMIFY_BOTGUARD_BIN`).
fn botguard_binary() -> Option<OsString> {
    if let Some(explicit) = BOTGUARD_BINARY_OVERRIDE.get() {
        return Some(OsString::from(explicit));
    }
    if let Ok(from_env) = std::env::var("STREAMIFY_BOTGUARD_BIN") {
        let clean = from_env.trim();
        if !clean.is_empty() {
            return Some(OsString::from(clean));
        }
    }
    None
}

// ---------------------------------------------------------------------------
// The shared client
// ---------------------------------------------------------------------------

/// The process-wide RustyPipe handle, built once on first use
/// (idempotent). All queries on the shared engine runtime borrow from
/// this singleton; the handle is a cheap clone over one reqwest pool,
/// one deobfuscation cache and (when a botguard binary is configured)
/// one PO-token snapshot file.
static CLIENT: OnceLock<Result<RustyPipe, EngineError>> = OnceLock::new();

/// In-process fallback for the deobfuscation cache for the window before
/// a persistent cache database path is bound (the path binds on the
/// first `resolve_track_cdn` call or an explicit cache-db setter; see
/// `resolver_cache`). After binding, both halves are served from SQLite
/// and this cell goes dormant.
static MEM_DEOBF_CACHE: Mutex<Option<String>> = Mutex::new(None);

/// `rustypipe::cache::CacheStorage` implementation backed by the
/// streamify-storage SQLite pool (Part B.4: "cache deciphered player
/// transformations into streamify-storage").
///
/// RustyPipe hands the whole deobfuscation payload over as one JSON
/// string (client versions + the deciphered player.js functions) — a
/// tiny, rarely-changing blob, so a key-value row is the honest shape.
/// Falls back to the in-process cell while no database path is bound so
/// a same-process rebuild of the client never loses the transforms.
struct SqliteDeobfCache;

impl CacheStorage for SqliteDeobfCache {
    fn write(&self, data: &str) {
        if resolver_cache::cache_db_path().is_some() {
            resolver_cache::put_meta(DEOBF_CACHE_KEY, data);
        } else {
            let mut mem = MEM_DEOBF_CACHE.lock().unwrap_or_else(|p| p.into_inner());
            *mem = Some(data.to_string());
        }
    }

    fn read(&self) -> Option<String> {
        if resolver_cache::cache_db_path().is_some() {
            return resolver_cache::get_meta(DEOBF_CACHE_KEY);
        }
        MEM_DEOBF_CACHE
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone()
    }
}

/// Construct the RustyPipe client with the engine's binding discipline.
fn build_client() -> Result<RustyPipe, EngineError> {
    let mut builder = RustyPipe::builder()
        // Tier-0 B1 hang guards: the same 30 s total-request deadline
        // the shared reqwest client carries.
        .timeout(REQUEST_TIMEOUT)
        // No on-disk report artifacts from a shipped library (the
        // reporter is a diagnostic feature; the shell's obs facade
        // already captures resolver failures through the typed error
        // channel).
        .no_reporter()
        // Deciphered player transforms + client versions ride the
        // pooled SQLite lane (Part B.4).
        .storage(Box::new(SqliteDeobfCache))
        // Session-bound PO tokens are minted once and reused instead of
        // one botguard round-trip per player request (content-bound
        // tokens are not mandatory and stay disabled, matching
        // rustypipe's own cache design).
        .po_token_cache();

    if let Some(bin) = botguard_binary() {
        builder = builder.botguard_bin(bin);
    }

    builder.build().map_err(|e| EngineError::Internal {
        error_message: format!("rustypipe client construction failed: {e}"),
        backtrace: None,
    })
}

/// The process-wide RustyPipe client (lazily built, idempotent).
///
/// Errors are latched: a failed construction (for example a botguard
/// override pointing at a non-executable file) is stored and re-surfaced
/// on every call — silent per-request rebuild storms are exactly the
/// failure mode the singleton exists to prevent.
pub fn client() -> Result<&'static RustyPipe, EngineError> {
    let latched = CLIENT.get_or_init(build_client);
    latched.as_ref().map_err(|e| EngineError::Internal {
        error_message: format!("rustypipe client unavailable: {e}"),
        backtrace: None,
    })
}

// ---------------------------------------------------------------------------
// Player mapping (pure, offline-unit-testable)
// ---------------------------------------------------------------------------

/// Rank one audio container for the engine's playback path.
///
/// m4a (AAC in MP4) ranks first — the demuxers in the playback stack
/// consume it natively and gapless metadata rides the MP4 container;
/// webm (Opus) ranks second (higher fidelity per bit, but a heavier
/// decode path). This implements Discussion #120's "audio-only streams
/// (m4a, webm/opus) are prioritized" mandate.
fn audio_rank(format: &AudioFormat) -> u8 {
    match format {
        AudioFormat::M4a => 0,
        AudioFormat::Webm => 1,
        // `#[non_exhaustive]`: a future container ranks last, behind
        // the two the engine's demuxers natively consume.
        _ => 2,
    }
}

/// Rank one muxed video container (the non-audio-only fallback tail).
fn muxed_rank(format: &VideoFormat) -> u8 {
    match format {
        VideoFormat::Mp4 => 0,
        VideoFormat::Webm => 1,
        VideoFormat::ThreeGp => 2,
        // `#[non_exhaustive]` guard (see audio_rank).
        _ => 3,
    }
}

/// Sustained bitrate for one rustypipe stream row.
///
/// `average_bitrate` is the sustained rate the buffer sizing and the
/// shell's ABR ladder should plan around; the nominal `bitrate` is the
/// peak of the VBR envelope and only used as a fallback for rows where
/// YouTube omits the average.
fn sustained_bitrate(average: u32, peak: u32) -> u32 {
    if average > 0 {
        average
    } else {
        peak
    }
}

/// Per-row duration: the row's own `duration_ms` when the response
/// carries it (the actual stream length, which is what the buffer
/// manager must size against), otherwise the video-level duration.
fn row_duration_sec(duration_ms: Option<u32>, video_duration_sec: u32) -> u32 {
    duration_ms
        .map(|ms| ms / 1000)
        .filter(|sec| *sec > 0)
        .unwrap_or(video_duration_sec)
}

/// Map a rustypipe player response onto the engine's stream-format
/// records.
///
/// Ordering contract (Part B): every AUDIO-ONLY format precedes every
/// muxed format; inside each class, m4a before webm, then bitrate
/// descending. `is_audio_only` is therefore a prefix property — the
/// audio pipeline can take `formats[0]` without a linear scan, and any
/// suffix scan short-circuits at the first muxed row.
pub fn map_player_to_formats(player: &VideoPlayer) -> Vec<ResolvedStreamFormat> {
    let video_duration_sec = player.details.duration;

    let mut audio_only: Vec<(u8, ResolvedStreamFormat)> = player
        .audio_streams
        .iter()
        .map(|s| {
            (
                audio_rank(&s.format),
                ResolvedStreamFormat {
                    url: s.url.clone(),
                    mime_type: s.mime.clone(),
                    bitrate: sustained_bitrate(s.average_bitrate, s.bitrate),
                    duration_sec: row_duration_sec(s.duration_ms, video_duration_sec),
                    is_audio_only: true,
                },
            )
        })
        .collect();

    // Stable sort: rows keep YouTube's own itag order inside equal
    // (rank, bitrate) keys.
    audio_only.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.bitrate.cmp(&a.1.bitrate)));

    let mut muxed: Vec<(u8, ResolvedStreamFormat)> = player
        .video_streams
        .iter()
        .map(|s| {
            (
                muxed_rank(&s.format),
                ResolvedStreamFormat {
                    url: s.url.clone(),
                    mime_type: s.mime.clone(),
                    bitrate: sustained_bitrate(s.average_bitrate, s.bitrate),
                    duration_sec: row_duration_sec(s.duration_ms, video_duration_sec),
                    is_audio_only: false,
                },
            )
        })
        .collect();
    muxed.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.bitrate.cmp(&a.1.bitrate)));

    audio_only
        .into_iter()
        .chain(muxed)
        .map(|(_, format)| format)
        .collect()
}

/// CDN cache TTL for one player response, in whole seconds.
///
/// Derived from the response's own `valid_until` (which already folds
/// in the PO-token lifetime) minus the 15-minute safety margin, clamped
/// to [60 s, 6 h] (see the constants above). The margin is the "safety
/// TTL margin" Discussion #120 mandates: a cached URL must be discarded
/// while it is still provably fresh, never served into its expiry
/// window.
pub fn cdn_ttl_seconds(player: &VideoPlayer, now: OffsetDateTime) -> u64 {
    let margin = time::Duration::seconds(CDN_TTL_SAFETY_MARGIN_SEC);
    let clamped_expiry = player.valid_until.saturating_sub(margin);
    let ttl = (clamped_expiry - now).whole_seconds().max(0) as u64;
    ttl.clamp(CDN_TTL_FLOOR_SEC, CDN_TTL_CEILING_SEC)
}

// ---------------------------------------------------------------------------
// Search mapping (pure, offline-unit-testable)
// ---------------------------------------------------------------------------

/// Join a YTM track's artist list into the single artist string the
/// engine's records carry ("A & B" — the legacy Innertube parser's
/// rendering of multi-artist rows).
fn join_artists(artists: &[rustypipe::model::ArtistId]) -> String {
    let names: Vec<&str> = artists.iter().map(|a| a.name.as_str()).collect();
    names.join(" & ")
}

/// Map rustypipe YTM search results onto the resolver's identity-
/// verification candidates (the fuzzy-match verdict in
/// `execute_resolution` proves same-song identity before any videoId
/// is trusted). Order is YouTube's relevance order, preserved.
pub fn map_tracks_to_search_candidates(items: &[TrackItem]) -> Vec<SearchCandidate> {
    items
        .iter()
        .map(|t| SearchCandidate {
            video_id: t.id.clone(),
            title: t.name.clone(),
            artist: join_artists(&t.artists),
            duration_sec: t.duration.unwrap_or(0),
        })
        .collect()
}

/// Map rustypipe YTM search results onto the engine's full candidate
/// records (id/title/artist/album/duration/artwork/score). Relevance
/// order is preserved and mirrored into a descending `score` so the
/// legacy sorting surfaces keep working unchanged.
pub fn map_tracks_to_parsed_candidates(items: &[TrackItem], limit: usize) -> Vec<ParsedCandidate> {
    items
        .iter()
        .take(limit)
        .enumerate()
        .map(|(idx, t)| ParsedCandidate {
            id: t.id.clone(),
            title: t.name.clone(),
            artist: join_artists(&t.artists),
            album: t
                .album
                .as_ref()
                .map(|a| a.name.clone())
                .unwrap_or_default(),
            duration_sec: t.duration.unwrap_or(0),
            thumbnail_url: t
                .cover
                .first()
                .map(|c| c.url.clone())
                .unwrap_or_default(),
            // Descending relevance mirror: head of the list is the most
            // relevant row; clamped so a long tail never scores 0 (0 is
            // reserved for "score unknown" in the shell's sorting
            // contract). NOTE: computed on i32 — a raw `as u8` cast
            // WRAPS past 255 (299 -> 43) and would invert the tail's
            // order.
            score: (100 - idx as i32).clamp(1, 100) as u8,
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Error mapping (pure, offline-unit-testable)
// ---------------------------------------------------------------------------

/// Translate a rustypipe failure onto the engine's stable error taxonomy
/// (MP-C04).
///
/// Transport-level failures map onto [`EngineError::Network`] with the
/// class that routes the retry policy: HTTP status rows carry the
/// status byte (429/503 add a backoff hint), transport chains split
/// into Timeout/Dns/Tls/Connect. Extraction-level failures are internal
/// to the extraction contract (unavailable content, deobfuscation
/// faults, botguard faults, malformed payloads) and map onto
/// [`EngineError::Internal`] with the diagnostic preserved — they are
/// not network faults and blind retries make them worse, not better.
pub fn map_rp_error(err: RpError) -> EngineError {
    match err {
        RpError::HttpStatus(status, _) => {
            let retry_after = if status == 429 || status == 503 { 5_000 } else { 0 };
            EngineError::Network {
                kind: NetworkErrorKind::Http,
                status,
                retry_after,
            }
        }
        RpError::Http(msg) => EngineError::Network {
            kind: classify_transport(&msg),
            status: 0,
            retry_after: 0,
        },
        RpError::Extraction(extraction) => map_extraction_error(extraction),
        RpError::Auth(_) => EngineError::Network {
            kind: NetworkErrorKind::Http,
            status: 401,
            retry_after: 0,
        },
        RpError::Other(msg) => EngineError::Internal {
            error_message: format!("rustypipe: {msg}"),
            backtrace: None,
        },
    }
}

/// Extraction-level failure translation (see [`map_rp_error`]).
fn map_extraction_error(err: ExtractionError) -> EngineError {
    match err {
        ExtractionError::Unavailable {
            reason: UnavailabilityReason::TryAgain,
            ..
        } => EngineError::Network {
            kind: NetworkErrorKind::Http,
            status: 503,
            retry_after: 5_000,
        },
        ExtractionError::Unavailable { .. } => EngineError::Network {
            kind: NetworkErrorKind::Http,
            status: 404,
            retry_after: 0,
        },
        ExtractionError::NotFound { .. } => EngineError::Network {
            kind: NetworkErrorKind::Http,
            status: 404,
            retry_after: 0,
        },
        ExtractionError::BadRequest(_) | ExtractionError::InvalidData(_)
        | ExtractionError::Deobfuscation(_) | ExtractionError::Botguard(_)
        | ExtractionError::WrongResult(_) | ExtractionError::Redirect(_)
        | ExtractionError::DeserializationWarnings => EngineError::Internal {
            error_message: format!("rustypipe extraction: {err}"),
            backtrace: None,
        },
    }
}

/// Split generic transport failures into the retry-policy class.
///
/// reqwest renders its error chains into the `Http(Cow<'static, str>)`
/// string rustypipe carries; the classes the engine's retry policy
/// actually distinguishes are deadlines ("timed out") and connection
/// establishment, with DNS and TLS named explicitly in the chain text.
fn classify_transport(msg: &str) -> NetworkErrorKind {
    let lower = msg.to_ascii_lowercase();
    if lower.contains("timed out") || lower.contains("timeout") {
        NetworkErrorKind::Timeout
    } else if lower.contains("dns") || lower.contains("error resolving") {
        NetworkErrorKind::Dns
    } else if lower.contains("tls") || lower.contains("certificate") {
        NetworkErrorKind::Tls
    } else {
        NetworkErrorKind::Connect
    }
}

// ---------------------------------------------------------------------------
// Async extraction surface (runs on the engine's shared runtime)
// ---------------------------------------------------------------------------

/// Resolve the playable stream-format set for one video id through the
/// RustyPipe player (Part B.2).
///
/// Executes on the shared engine runtime (block_on at the sync
/// boundary). Audio-only formats are prioritized (see
/// [`map_player_to_formats`]); the result is persisted into the
/// resolver cache with the response's own `valid_until` minus the
/// safety margin (Part B.4). Cache writes are best-effort: a cache
/// failure must never fail an otherwise successful resolve.
pub async fn resolve_stream_formats(
    video_id: &str,
) -> Result<Vec<ResolvedStreamFormat>, EngineError> {
    let rp = client()?;
    let player = rp.query().player(video_id).await.map_err(map_rp_error)?;
    let formats = map_player_to_formats(&player);
    if formats.is_empty() {
        return Err(EngineError::Network {
            kind: NetworkErrorKind::Http,
            status: 404,
            retry_after: 0,
        });
    }
    let ttl = cdn_ttl_seconds(&player, OffsetDateTime::now_utc());
    if let Ok(json) = serde_json::to_string(&formats) {
        resolver_cache::put_stream_formats(video_id, &json, ttl);
    }
    Ok(formats)
}

/// Search YouTube Music tracks (Part B.3) for the identity-verification
/// tier of `execute_resolution`.
///
/// Routes through rustypipe's YTM search endpoints (the mobile search
/// clients, with rustypipe's parsing + deobfuscation). Returns
/// candidates in YouTube's relevance order; truncates to `limit`.
pub async fn music_search_candidates(
    query: &str,
    limit: usize,
) -> Result<Vec<SearchCandidate>, EngineError> {
    let rp = client()?;
    let result = rp
        .query()
        .music_search_tracks(query)
        .await
        .map_err(map_rp_error)?;
    let candidates = map_tracks_to_search_candidates(&result.items.items);
    Ok(candidates.into_iter().take(limit).collect())
}

/// Search YouTube Music tracks and return the full engine candidate
/// records (Part B.3, the `search_tracks` surface).
///
/// Results are cached with the search TTL when a cache database is
/// bound (best-effort, same discipline as the CDN cache).
pub async fn search_tracks(
    query: &str,
    limit: usize,
) -> Result<Vec<ParsedCandidate>, EngineError> {
    let rp = client()?;
    let result = rp
        .query()
        .music_search_tracks(query)
        .await
        .map_err(map_rp_error)?;
    let candidates = map_tracks_to_parsed_candidates(&result.items.items, limit);
    if !candidates.is_empty() {
        if let Ok(json) = serde_json::to_string(&candidates) {
            resolver_cache::put_search_results(query, limit, &json, SEARCH_CACHE_TTL_SEC);
        }
    }
    Ok(candidates)
}


// ---------------------------------------------------------------------------
// Tests (offline: pure mappers, no network)
//
// rustypipe's model structs are `#[non_exhaustive]`, so fixtures are
// built through their serde envelopes (the same wire shape rustypipe's
// own response parser produces) instead of struct expressions.
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use rustypipe::model::{TrackItem, VideoPlayer};

    /// Deserialize a fixture against a non-exhaustive model type,
    /// panicking with the serde detail on a malformed fixture.
    fn fixture<T: serde::de::DeserializeOwned>(json: &str) -> T {
        serde_json::from_str(json).expect("test fixture must deserialize")
    }

    fn audio_stream(url: &str, format: &str, avg: u32, peak: u32) -> String {
        format!(
            r#"{{
                "url": "{url}",
                "itag": 140,
                "bitrate": {peak},
                "average_bitrate": {avg},
                "size": 1,
                "index_range": null,
                "init_range": null,
                "duration_ms": 201000,
                "mime": "audio/mp4; codecs=mp4a.40.2",
                "format": "{format}",
                "codec": "mp4a",
                "channels": 2,
                "loudness_db": null,
                "track": null,
                "drm_track_type": null,
                "drm_systems": []
            }}"#
        )
    }

    fn muxed_stream(url: &str, format: &str, avg: u32) -> String {
        format!(
            r#"{{
                "url": "{url}",
                "itag": 18,
                "bitrate": {avg},
                "average_bitrate": {avg},
                "size": 1,
                "index_range": null,
                "init_range": null,
                "duration_ms": 201000,
                "width": 640,
                "height": 360,
                "fps": 30,
                "quality": "360p",
                "hdr": false,
                "mime": "video/mp4; codecs=avc1.42001E, mp4a.40.2",
                "format": "{format}",
                "codec": "avc1",
                "drm_track_type": null,
                "drm_systems": []
            }}"#
        )
    }

    fn player_fixture(audio: &[String], muxed: &[String], duration_sec: u32, valid_until: &str) -> String {
        format!(
            r#"{{
                "details": {{
                    "id": "dQw4w9WgXcQ",
                    "name": "Never Gonna Give You Up",
                    "description": null,
                    "duration": {duration_sec},
                    "thumbnail": [],
                    "channel_id": "UC38IQsAvIs9jz4YgXl1yOQw",
                    "channel_name": "Rick Astley",
                    "view_count": 1,
                    "keywords": [],
                    "is_live": false,
                    "is_live_content": false
                }},
                "video_streams": [{muxed_list}],
                "video_only_streams": [],
                "audio_streams": [{audio_list}],
                "subtitles": [],
                "expires_in_seconds": 21540,
                "valid_until": "{valid_until}",
                "hls_manifest_url": null,
                "dash_manifest_url": null,
                "preview_frames": [],
                "drm": null,
                "client_type": "desktop",
                "visitor_data": null
            }}"#,
            muxed_list = muxed.join(","),
            audio_list = audio.join(","),
        )
    }

    fn rfc3339_plus_hours(hours: i64) -> String {
        let t = OffsetDateTime::now_utc() + time::Duration::hours(hours);
        // RFC 3339 in UTC (the `time::serde::rfc3339` format
        // VideoPlayer's valid_until declares).
        t.format(&time::format_description::well_known::Rfc3339)
            .expect("RFC3339 formatting is infallible for a valid timestamp")
    }

    #[test]
    fn audio_only_formats_rank_m4a_first_then_bitrate() {
        // Deliberately shuffled input: opus (avg 160) above both m4a rows.
        let audio = [
            audio_stream("opus160", "webm", 160_000, 165_000),
            audio_stream("m4a128", "m4a", 128_000, 131_000),
            audio_stream("m4a256", "m4a", 256_000, 260_000),
        ];
        let json = player_fixture(&audio, &[], 201, &rfc3339_plus_hours(6));
        let player: VideoPlayer = fixture(&json);

        let formats = map_player_to_formats(&player);
        assert_eq!(formats.len(), 3, "no muxed rows: nothing appended");
        assert!(formats.iter().all(|f| f.is_audio_only));
        assert_eq!(formats[0].url, "m4a256");
        assert_eq!(formats[1].url, "m4a128");
        assert_eq!(formats[2].url, "opus160");
    }

    #[test]
    fn muxed_rows_form_the_tail() {
        let audio = [audio_stream("m4a", "m4a", 128_000, 131_000)];
        let muxed = [muxed_stream("muxed", "mp4", 700_000)];
        let json = player_fixture(&audio, &muxed, 201, &rfc3339_plus_hours(6));
        let player: VideoPlayer = fixture(&json);

        let formats = map_player_to_formats(&player);
        assert_eq!(formats.len(), 2);
        assert!(formats[0].is_audio_only);
        assert!(!formats[1].is_audio_only, "muxed row keeps the tail slot");
    }

    #[test]
    fn bitrates_and_duration_map_accurately() {
        // average_bitrate wins; row duration_ms maps over the video-level
        // duration.
        let audio = [audio_stream("m4a", "m4a", 128_000, 131_000)];
        let json = player_fixture(&audio, &[], 201, &rfc3339_plus_hours(6));
        let player: VideoPlayer = fixture(&json);
        let formats = map_player_to_formats(&player);
        assert_eq!(
            formats[0].bitrate, 128_000,
            "average_bitrate (sustained) must win over the peak"
        );
        assert_eq!(
            formats[0].duration_sec, 201,
            "row duration_ms (201000 ms) maps to 201 s, not the video-level fallback"
        );

        // Row without duration_ms falls back to the video-level duration.
        let no_row_duration =
            audio_stream("m4a", "m4a", 128_000, 131_000).replace("\"duration_ms\": 201000", "\"duration_ms\": null");
        let json = player_fixture(&[no_row_duration], &[], 999, &rfc3339_plus_hours(6));
        let parsed: VideoPlayer = fixture(&json);
        let formats = map_player_to_formats(&parsed);
        assert_eq!(formats[0].duration_sec, 999);

        // Zero average falls back to the peak.
        let zero_avg =
            audio_stream("m4a", "m4a", 0, 131_000).replace("\"duration_ms\": 201000", "\"duration_ms\": null");
        let json = player_fixture(&[zero_avg], &[], 100, &rfc3339_plus_hours(6));
        let parsed: VideoPlayer = fixture(&json);
        let formats = map_player_to_formats(&parsed);
        assert_eq!(formats[0].bitrate, 131_000);
    }

    #[test]
    fn cdn_ttl_applies_the_safety_margin() {
        let now = OffsetDateTime::now_utc();

        // Six hours of life cache as six hours minus the 15 min margin.
        let json = player_fixture(&[], &[], 100, &rfc3339_plus_hours(6));
        let parsed: VideoPlayer = fixture(&json);
        assert_eq!(
            cdn_ttl_seconds(&parsed, now),
            6 * 3600 - CDN_TTL_SAFETY_MARGIN_SEC as u64,
        );

        // A response already inside the margin floors at the 60 s TTL.
        let json = player_fixture(&[], &[], 100, &rfc3339_plus_hours(0));
        let parsed: VideoPlayer = fixture(&json);
        assert_eq!(cdn_ttl_seconds(&parsed, now), CDN_TTL_FLOOR_SEC);

        // A pathological far-future valid_until ceilings at 6 h.
        let json = player_fixture(&[], &[], 100, &rfc3339_plus_hours(24 * 30));
        let parsed: VideoPlayer = fixture(&json);
        assert_eq!(cdn_ttl_seconds(&parsed, now), CDN_TTL_CEILING_SEC);
    }

    #[test]
    fn track_items_map_with_joined_artists_and_album() {
        let item_json = r#"{
            "id": "abc123",
            "name": "Track",
            "duration": 180,
            "cover": [
                {"url": "https://example/t.jpg", "width": 544, "height": 544}
            ],
            "artists": [
                {"id": "UC1", "name": "Alpha"},
                {"id": null,  "name": "Beta"}
            ],
            "artist_id": "UC1",
            "album": {"id": "MPRE_x", "name": "Record"},
            "view_count": 100,
            "track_type": "track",
            "track_nr": 1,
            "by_va": false
        }"#;
        let items: Vec<TrackItem> = fixture(&format!("[{item_json}]"));

        let candidates = map_tracks_to_search_candidates(&items);
        assert_eq!(candidates[0].video_id, "abc123");
        assert_eq!(candidates[0].artist, "Alpha & Beta");
        assert_eq!(candidates[0].duration_sec, 180);

        let parsed = map_tracks_to_parsed_candidates(&items, 10);
        assert_eq!(parsed[0].album, "Record");
        assert_eq!(parsed[0].thumbnail_url, "https://example/t.jpg");
        assert_eq!(parsed[0].duration_sec, 180);
        assert_eq!(parsed[0].score, 100);

        // Unknown duration maps to 0 (the legacy sentinel).
        let no_duration = item_json.replace("\"duration\": 180", "\"duration\": null");
        let items: Vec<TrackItem> = fixture(&format!("[{no_duration}]"));
        let parsed = map_tracks_to_parsed_candidates(&items, 10);
        assert_eq!(parsed[0].duration_sec, 0);
    }

    #[test]
    fn search_scores_mirror_relevance_and_limit_truncates() {
        let many: Vec<String> = (0..300)
            .map(|i| {
                format!(
                    r#"{{
                        "id": "v{i}",
                        "name": "T",
                        "duration": 1,
                        "cover": [],
                        "artists": [],
                        "artist_id": null,
                        "album": null,
                        "view_count": null,
                        "track_type": "track",
                        "track_nr": null,
                        "by_va": false
                    }}"#
                )
            })
            .collect();
        let items: Vec<TrackItem> = fixture(&format!("[{}]", many.join(",")));

        let parsed = map_tracks_to_parsed_candidates(&items, 300);
        assert_eq!(parsed.len(), 300);
        assert_eq!(parsed[0].score, 100);
        assert_eq!(parsed[299].score, 1, "long tail clamps at 1, never 0");

        let parsed = map_tracks_to_parsed_candidates(&items, 5);
        assert_eq!(parsed.len(), 5, "limit truncates");
    }

    #[test]
    fn http_status_errors_route_to_network() {
        let err = map_rp_error(RpError::HttpStatus(429, "too many requests".into()));
        match err {
            EngineError::Network {
                kind: NetworkErrorKind::Http,
                status: 429,
                retry_after: 5_000,
            } => {}
            other => panic!("429 must map to Network/Http with backoff hint, got {other:?}"),
        }

        let err = map_rp_error(RpError::HttpStatus(500, "server".into()));
        match err {
            EngineError::Network {
                status: 500,
                retry_after: 0,
                ..
            } => {}
            other => panic!("5xx maps to status-carrying Network, got {other:?}"),
        }

        let err = map_rp_error(RpError::Http("connection timed out".into()));
        assert_eq!(
            err,
            EngineError::Network {
                kind: NetworkErrorKind::Timeout,
                status: 0,
                retry_after: 0,
            }
        );

        let err = map_rp_error(RpError::Extraction(ExtractionError::Deobfuscation(
            "nsig failed".into(),
        )));
        match err {
            EngineError::Internal { error_message, .. } => {
                assert!(error_message.contains("nsig failed"))
            }
            other => panic!("deobfuscation faults are internal, got {other:?}"),
        }
    }
}
