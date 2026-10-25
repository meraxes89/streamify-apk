pub mod airdrop;
pub mod aligner;
pub mod audio_dsp;
pub mod auth;
pub mod backup;
pub mod cache;
pub mod consensus;
pub mod continuum_engine;
pub mod crossfade;
pub mod crypto;

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
pub mod markov;
pub mod neuro_queue;
pub mod normalizer;
pub mod playlist_parser;
pub mod ptp;
pub mod queue_engine;
// Jam Phase 2 — zero-server P2P mesh fabric (feat/jam-p2p-mesh-transport):
pub mod chunk_swarmer;
pub mod dht_signaling;
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
pub mod waveform_indexer;
pub mod queue_optimizer;
pub mod radio_scorer;
pub mod repository;
pub mod resolver;
pub mod search;
pub mod seek_guard;
pub mod spotify_ingest;
pub mod tagger;
pub mod task_orchestrator;
pub mod tick_matrix;

pub use airdrop::{AirdropPhysicsEngine, AirdropState};
pub use aligner::{AlignedLine, AlignedSyllable, LyricAlignerEngine};

pub use backup::{BackupArchiveEngine, BackupRecord};
pub use consensus::{
    CollabPlaylistState, PlaylistApplyResult, PlaylistItemView, PlaylistOp, PlaylistOpKind,
    PlaylistReject, PlaylistRole, ConsensusEngine,
};
pub use crossfade::CrossfadeDspEngine;
pub use crypto::VaultCryptoEngine;
pub use downloader::{DownloadProgress, StreamDownloader};
pub use dsp::{BiquadFilter, FilterType, SpectrumVisualizer, StudioEqualizer};
pub use json::{InnertubeParser, ParsedCandidate, ResolvedStreamFormat};
pub use lyrics::{CompiledLyricEntry, CompiledLyrics, LyricCompiler};
pub use markov::MarkovEngine;
pub use neuro_queue::{BrainState, NeuroCandidate, NeuroQueueEngine};
pub use playlist_parser::{
    import_parsed_playlist, ParsedPlaylistResult, ParsedPlaylistTrack, PlaylistParser,
};
pub use ptp::PtpFilter;
pub use queue_optimizer::{CandidateTrack, QueueOptimizer};
pub use radio_scorer::{
    BlendCandidate, BlendMemberSeed, BlendScoreResult, BlendScoredCandidate, BlendWeights,
    GroupBlendScorer, RadioAntiDriftEngine, ScoredCandidate,
};
pub use resolver::StreamResolver;
pub use search::{FuzzySearchEngine, SearchCandidate};
pub use tagger::{AudioMetadataEngine, TrackMetadata};
