//! streamify-resolver — the stream extraction crate (Discussion #120).
//!
//! YouTube Music extraction through the pure-Rust RustyPipe engine
//! (JavaScript cipher deciphering + botguard Proof-of-Origin tokens — no
//! Android WebView, identical native resolution on every platform), the
//! legacy Innertube parser, fuzzy search, the radio/blend scorers, the
//! neural continuation queue, lyrics compilation and alignment, the
//! lofty tagger, the resumable downloader and Spotify playlist
//! ingestion.
//!
//! THREADING CONTRACT (resp5 §2.1): one multi-threaded tokio runtime
//! owned by the engine, initialized in [`resolver::get_runtime`]'s
//! `OnceLock` — every async surface in the workspace (storage lanes,
//! jam mesh, platform adapters, RustyPipe queries) blocks on or spawns
//! onto THAT runtime; redundant background runtimes are forbidden.

pub mod aligner;
pub mod continuum_engine;
pub mod downloader;
pub mod json;
pub mod lyrics;
pub mod neuro_queue;
pub mod playlist_parser;
pub mod radio_scorer;
pub mod resolver;
pub mod rustypipe_engine;
pub mod search;
pub mod spotify_ingest;
pub mod tagger;

// Root re-exports (mirror the old monolith root surface).
pub use aligner::{AlignedLine, AlignedSyllable, LyricAlignerEngine};
pub use downloader::{DownloadProgress, StreamDownloader};
pub use json::{InnertubeParser, ParsedCandidate, ResolvedStreamFormat};
pub use lyrics::{CompiledLyricEntry, CompiledLyrics, LyricCompiler};
pub use neuro_queue::{BrainState, NeuroCandidate, NeuroQueueEngine};
pub use playlist_parser::{ParsedPlaylistResult, ParsedPlaylistTrack, PlaylistParser};
pub use radio_scorer::{
    BlendCandidate, BlendMemberSeed, BlendScoreResult, BlendScoredCandidate, BlendWeights,
    GroupBlendScorer, RadioAntiDriftEngine, ScoredCandidate,
};
pub use resolver::StreamResolver;
pub use rustypipe_engine::{
    cdn_ttl_seconds, client, map_player_to_formats, map_rp_error, set_botguard_binary_path,
};
pub use search::{FuzzySearchEngine, SearchCandidate};
pub use tagger::AudioMetadataEngine;
