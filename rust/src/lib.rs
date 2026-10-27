pub mod airdrop;
pub mod aligner;
pub mod audio_dsp;
pub mod auth;
pub mod backup;
pub mod cache;
pub mod consensus;
pub mod continuum_engine;
// Phase 2 (UFFI-01): UniFFI typed contract — implementation of the
// types and interface declared in src/streamify.udl.
pub mod contract;
pub mod crossfade;
pub mod crypto;
pub mod db_pool;

pub mod downloader;
pub mod dsp;
pub mod ffi;
pub mod governor;
pub mod jam_clock;
pub mod jam_crdt;
pub mod jam_governor;
pub mod jam_outbox;
pub mod jni_bridge;
pub mod json;
pub mod lyrics;
pub mod neuro_queue;
pub mod normalizer;
pub mod playlist_parser;
pub mod ptp;
pub mod queue_engine;
// Jam Phase 2 — zero-server P2P mesh fabric (feat/jam-p2p-mesh-transport):
pub mod chunk_swarmer;
pub mod gossip;
pub mod jni_bridge_p2p;
pub mod kalman_pll;
pub mod p2p_mesh;
// Phase 3 — P2P chunk swarmer & local LAN sync
// (feat/phase3-rust-chunk-swarmer-sync): byte-range integrity verifier,
// resumable transfer state, two-way library catalog sync.
pub mod chunk_verifier;
pub mod jni_bridge_chunk_sync;
pub mod local_sync;
// Phase 4 — Streamify Connect gateway & WearOS P2P sync
// (feat/phase4-rust-connect-gateway-wear-sync): device discovery
// registry with presence leases, cross-device session handoff &
// remote intent dispatch, WearOS compact binary sync.
pub mod connect_gateway;
pub mod device_registry;
pub mod jni_bridge_connect;
pub mod remote_intent_engine;
pub mod wear_sync;
// Phase 5 — kinetic gesture physics & waveform indexer
// (feat/phase5-rust-gesture-physics-waveform): analytical 2nd-order
// spring/fling solver with edge rubberbanding, peak-preserving RMS
// waveform downsampling with a compact binary cache.
pub mod gesture_physics;
pub mod jni_bridge_physics_waveform;
pub mod radio_scorer;
pub mod repository;
pub mod resolver;
pub mod search;
pub mod seek_guard;
pub mod spotify_ingest;
pub mod tagger;
pub mod task_orchestrator;
pub mod tick_matrix;
pub mod waveform_indexer;
// Audit #22 item 6: shared little-endian wire-field readers —
// panic-free ingress parsing for every wire codec.
pub mod wire_le;

pub use airdrop::{AirdropPhysicsEngine, AirdropState};
pub use aligner::{AlignedLine, AlignedSyllable, LyricAlignerEngine};

pub use backup::{BackupArchiveEngine, BackupRecord};
pub use consensus::{
    CollabPlaylistState, ConsensusEngine, PlaylistApplyResult, PlaylistItemView, PlaylistOp,
    PlaylistOpKind, PlaylistReject, PlaylistRole,
};
pub use contract::{
    AudioStreamConfig, BufferStats, EngineError, JamSessionHandle, JamSyncResult, PlaybackState,
    StreamifyEngine, TrackMetadata,
};
pub use crossfade::CrossfadeDspEngine;
pub use crypto::VaultCryptoEngine;
pub use downloader::{DownloadProgress, StreamDownloader};
pub use dsp::{BiquadFilter, FilterType, SpectrumVisualizer, StudioEqualizer};
pub use json::{InnertubeParser, ParsedCandidate, ResolvedStreamFormat};
pub use lyrics::{CompiledLyricEntry, CompiledLyrics, LyricCompiler};
pub use neuro_queue::{BrainState, NeuroCandidate, NeuroQueueEngine};
pub use playlist_parser::{
    import_parsed_playlist, ParsedPlaylistResult, ParsedPlaylistTrack, PlaylistParser,
};
pub use ptp::PtpFilter;
pub use radio_scorer::{
    BlendCandidate, BlendMemberSeed, BlendScoreResult, BlendScoredCandidate, BlendWeights,
    GroupBlendScorer, RadioAntiDriftEngine, ScoredCandidate,
};
pub use resolver::StreamResolver;
pub use search::{FuzzySearchEngine, SearchCandidate};
// Phase 2 (UFFI-01): the crate-root `TrackMetadata` is now the UniFFI
// contract record (id / duration_ms / stream_url? / album_art_url?). The
// lofty-backed file-metadata struct remains available at
// `crate::tagger::TrackMetadata`.
pub use tagger::AudioMetadataEngine;

// ─── Phase 2 (UFFI-01): UniFFI scaffolding ────────────────────────────────
// Generated at build time from src/streamify.udl by build.rs. The emitted
// FFI trampolines and FfiConverter impls resolve the contract types by
// bare name, so the re-exports above must stay in scope at this include
// site. Types are defined in `contract`; the object implementation lives
// there as inherent methods (`new` + the five UDL-declared methods).
uniffi::include_scaffolding!("streamify");
