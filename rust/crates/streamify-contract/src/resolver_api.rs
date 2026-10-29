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

use streamify_core::EngineError;
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
