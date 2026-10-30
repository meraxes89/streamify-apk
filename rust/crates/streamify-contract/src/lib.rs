//! streamify-contract — the UniFFI contract crate (Discussion #120).
//!
//! Implements `src/streamify.udl` (the workspace-root UDL shared with
//! the Gradle codegen tasks and the iOS xcframework script): the
//! [`StreamifyEngine`] object, the OS media-integration facade, the
//! secure-store delegation, the observability façade and the playback
//! queue orchestration that composes the storage + resolver domains.
//!
//! The contract's DATA types (EngineError, DacPresentationClock,
//! PlaybackState, ...) are defined in `streamify-core` and re-exported
//! at this crate's root: the uniffi 0.28 `FfiConverter<UT>` tag pattern
//! lets the scaffolding below bind cross-crate types through those
//! re-exports, so the UDL remains the single semantic source of truth.

pub mod contract;
pub mod obs;
pub mod playlist_import;
pub mod platform;
pub mod queue_engine;
pub mod resolver_api;


// ── Root re-exports: the contract surface ──────────────────────────────────
// These mirror the old monolith root exactly — the UniFFI scaffolding
// (include_scaffolding! below) resolves every UDL-declared type by bare
// name through them, and the streamify-ffi umbrella re-exports the
// union so `streamify_core_rs::X` paths keep resolving for consumers.
pub use contract::{
    contract_version, AudioErrorKind, AudioStreamConfig, BufferStats, CryptoErrorKind,
    DacPresentationClock, EngineError, EngineEventListener, EngineSnapshot, EqualizerBand,
    JamRoomState, JamSessionHandle, JamSyncResult, MediaCommand, NetworkErrorKind, PlaybackState,
    PlaylistSummary, QueueItem, SearchResult, StorageErrorKind, StreamifyEngine, Track,
    TrackMetadata, CONTRACT_VERSION, DAC_CLOCK_MAX_SAMPLE_RATE, DAC_CLOCK_MIN_SAMPLE_RATE,
    EQUALIZER_BAND_COUNT, MAX_JAM_DELTA_BYTES, MAX_JAM_SESSION_NAME_BYTES,
    MEDIA_COMMAND_DEBOUNCE_MS,
};
// Phase 5 (MP-CAP01..04): the platform capability surface for the
// desktop/mobile shells — the UniFFI facade, the secure-store delegate
// callback + its UDL types, and the namespace functions the shells
// install/self-test through.
pub use platform::secure_store::{
    secure_store_roundtrip, set_secure_store_delegate, SecureFetchResult, SecureStoreDelegate,
    SecureStoreOpStatus,
};
pub use platform::OsMediaIntegration;
// Multiplatform Phase 3 (MP-C15): observability entry points for the
// client shells (Beta's init wiring).
pub use obs::{init_observability, register_core_metrics};
// Collaborative playlist import glue (spans the jam + resolver domains;
// lives here in the composition layer by design).
pub use playlist_import::import_parsed_playlist;
// Discussion #120, Part C: the modularized StreamResolver surface —
// namespace functions (`resolve_stream_formats`, `search_music_tracks`,
// `set_resolver_cache_db`) plus the record types the UDL declares
// (their canonical home is the owning domain crate; the scaffolding
// binds them through these re-exports exactly like the core types
// above).
pub use resolver_api::{
    resolve_stream_formats, search_music_tracks, search_tracks, self_test_search_result,
    set_resolver_cache_db,
};
pub use streamify_resolver::json::{ParsedCandidate, ResolvedStreamFormat};

// ─── Phase 2 (UFFI-01): UniFFI scaffolding ────────────────────────────────
// Generated at build time from the workspace-root src/streamify.udl by
// this crate's build.rs. The emitted FFI trampolines and FfiConverter
// impls resolve the contract types by bare name, so the re-exports
// above must stay in scope at this include site. Types are defined in
// `contract` / `streamify-core`; the object implementations live there
// as inherent methods.
uniffi::include_scaffolding!("streamify");
