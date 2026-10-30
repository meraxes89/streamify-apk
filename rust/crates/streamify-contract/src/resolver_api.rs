//! Resolver UniFFI surface — Discussion #120, Part C.
//!
//! The modularized [`StreamResolver`] exposed through the typed
//! contract: `resolve_stream_formats` (the rustypipe-backed stream
//! resolution), `search_music_tracks` (the YTM search surface) and
//! `set_resolver_cache_db` (the persistent memo layer's database
//! binding).
//!
//! These are NAMESPACE functions (not `StreamifyEngine` methods):
//! resolution is a process-global capability bound to the engine's one
//! shared tokio runtime, not per-engine-object state — two engine
//! instances in one process (session handoff, instrumentation hosts)
//! share the resolver singleton by design, exactly like the JNI
//! resolver exports they replace.
//!
//! MP-C06: every entry point is wrapped by the crate's `ffi_shield`
//! (panics marshal into `EngineError::Internal` with a backtrace; a
//! panic never unwinds into Kotlin or Swift). Resolution blocks the
//! FFI caller thread on the shared runtime via `block_on` — the same
//! discipline as every other sync resolver entry point (the runtime's
//! worker threads run the network; the caller thread parks).

use streamify_core::{EngineError, SearchResult, Track};
use streamify_resolver::json::{ParsedCandidate, ResolvedStreamFormat};

use crate::contract::ffi_shield;
use streamify_resolver::resolver::StreamResolver;

/// Resolve the playable stream-format set for one YouTube video id.
///
/// Namespace entry for `StreamResolver::resolve_stream` — see the UDL
/// doc for the foreign-side contract. Cache hits answer without
/// network; cold resolves walk the rustypipe tier first, the legacy
/// anonymous Innertube tier last.
pub fn resolve_stream_formats(video_id: String) -> Result<Vec<ResolvedStreamFormat>, EngineError> {
    ffi_shield("resolve_stream_formats", || {
        StreamResolver::resolve_stream(&video_id)
    })
}

/// Search YouTube Music tracks.
///
/// Namespace entry for `StreamResolver::search_tracks` — see the UDL
/// doc for the foreign-side contract. `limit` is clamped to [1, 50].
pub fn search_music_tracks(
    query: String,
    limit: u32,
) -> Result<Vec<ParsedCandidate>, EngineError> {
    ffi_shield("search_music_tracks", || {
        StreamResolver::search_tracks(&query, limit as usize)
    })
}

/// SW-ENG1-03: map one resolver candidate row onto the fully typed
/// [`Track`] record the typed Apple lane consumes.
///
/// Pure and total: empty strings normalize to `None` on the optional
/// fields (the UI never renders a broken artwork URL); the upstream
/// explicit flag is absent from YTM search rows, so `is_explicit`
/// carries the "not flagged" default; `resolved_stream_url` stays
/// `None` (resolution is the explicit cache-backed step — see the UDL
/// note on `Track`).
fn track_from_candidate(candidate: &ParsedCandidate) -> Track {
    Track {
        id: candidate.id.clone(),
        title: candidate.title.clone(),
        artist: candidate.artist.clone(),
        album: candidate.album.clone(),
        duration_ms: candidate.duration_sec as u64 * 1000,
        thumbnail_url: {
            let url = candidate.thumbnail_url.trim();
            if url.is_empty() { None } else { Some(url.to_string()) }
        },
        is_explicit: false,
        resolved_stream_url: None,
    }
}

/// SW-ENG1-03: the typed search lane — the same resolver pipeline as
/// [`search_music_tracks`], mapped onto the fully typed records the
/// SwiftUI surfaces render.
///
/// The playlist rail is empty until the resolver's playlist tier
/// crosses this contract, and the current mapping is single-page, so
/// `continuation_token` is `None`; both are additive future growth on
/// a stable record shape (MP-C05).
pub fn search_tracks(query: String, limit: u32) -> Result<SearchResult, EngineError> {
    ffi_shield("search_tracks", || {
        let candidates = StreamResolver::search_tracks(&query, limit as usize)?;
        Ok(SearchResult {
            tracks: candidates.iter().map(track_from_candidate).collect(),
            playlists: Vec::new(),
            continuation_token: None,
        })
    })
}

/// SW-ENG1-03: deterministic, network-free contract self-test fixture
/// — a fully populated [`SearchResult`] for the Swift UniFFI binding
/// tests (`UniFFIContractTests.swift`), so cross-FFI deserialization of
/// every record field is verifiable without touching the network.
///
/// `track_count` clamps to [0, 64]; the fixture carries one playlist
/// row and a continuation token so every `SearchResult` field crosses
/// the boundary populated.
pub fn self_test_search_result(track_count: u32) -> Result<SearchResult, EngineError> {
    ffi_shield("self_test_search_result", || {
        let count = track_count.min(64) as usize;
        let tracks = (0..count)
            .map(|i| Track {
                id: format!("selftest_{i:02}_abcdefghij"),
                title: format!("Self Test Track {i:02}"),
                artist: format!("Contract Fixture Artist {i % 3}"),
                album: "UniFFI Contract Round-Trip".to_string(),
                duration_ms: 60_000 + (i as u64) * 1_000,
                thumbnail_url: Some(format!("https://i.ytimg.com/vi/selftest_{i:02}/mqdefault.jpg")),
                is_explicit: i % 5 == 0,
                resolved_stream_url: None,
            })
            .collect();
        Ok(SearchResult {
            tracks,
            playlists: vec![streamify_core::PlaylistSummary {
                id: "selftest_playlist_abcdefghij".to_string(),
                title: "UniFFI Contract Fixture Playlist".to_string(),
                thumbnail_url: Some(
                    "https://i.ytimg.com/vi/selftest_playlist/mqdefault.jpg".to_string(),
                ),
                track_count: 12,
            }],
            continuation_token: Some("selftest-continuation-token".to_string()),
        })
    })
}

/// Bind the resolver's persistent memo database (idempotent, first
/// call wins — see the UDL doc and
/// `streamify_storage::resolver_cache::set_cache_db_path`).
///
/// A blank path is accepted as a no-op (the auto-bind from the first
/// db-path-carrying resolve stays in effect); a path the cache layer
/// rejects (empty after trim) surfaces as `EngineError::Internal`.
pub fn set_resolver_cache_db(db_path: String) -> Result<(), EngineError> {
    ffi_shield("set_resolver_cache_db", || {
        streamify_storage::resolver_cache::set_cache_db_path(&db_path);
        Ok(())
    })
}
