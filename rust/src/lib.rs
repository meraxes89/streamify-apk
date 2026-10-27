pub mod airdrop;
pub mod aligner;
// Multiplatform Phase 3 (MP-C11): producer-side polyphase sinc SRC to
// the canonical 48 kHz f32 rate (rubato SincFixedOut).
pub mod audio_dsp;
pub mod audio_src;
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
// Multiplatform Phase 3 (MP-C12/MP-C13): the JNI surface compiles ONLY
// for the Android target — the `jni` crate is target-gated in
// Cargo.toml, and the desktop JVM / Apple lanes reach the core
// exclusively through the UniFFI contract (no second bridge).
#[cfg(target_os = "android")]
pub mod jni_bridge;
pub mod json;
pub mod lyrics;
// Phase 4a (MP-HAL03): EBU R128 / ITU-R BS.1770-4 loudness measurement
// and normalization on the canonical 48 kHz pipeline — the golden-vector
// DSP suite's loudness stage.
pub mod lufs;
// Multiplatform Phase 3 (MP-C15): tracing + metrics façade with
// per-platform (feature-gated) subscribers.
pub mod neuro_queue;
pub mod normalizer;
pub mod obs;
pub mod playlist_parser;
pub mod ptp;
pub mod queue_engine;
// Jam Phase 2 — zero-server P2P mesh fabric (feat/jam-p2p-mesh-transport):
// the JNI surface for it lives in jni_bridge_p2p.rs (Phase 4 trimmed it
// to the six frozen NativeBridge symbols; the deprecated NativeMeshEngine
// / NativeRadioEngine exports went with their long-removed Kotlin classes).
// Multiplatform Phase 3 (MP-C13): Android-only, like jni_bridge above.
pub mod chunk_swarmer;
pub mod gossip;
#[cfg(target_os = "android")]
pub mod jni_bridge_p2p;
pub mod kalman_pll;
pub mod p2p_mesh;
// Phase 3 — P2P chunk swarmer & local LAN sync
// (feat/phase3-rust-chunk-swarmer-sync): byte-range integrity verifier,
// resumable transfer state, two-way library catalog sync. The JNI bridge
// for the removed NativeChunkSyncEngine class was deleted in Phase 4
// (clean parity); the engines below stay with their own tests.
pub mod chunk_verifier;
pub mod local_sync;
// Phase 4 — Streamify Connect gateway & WearOS P2P sync
// (feat/phase4-rust-connect-gateway-wear-sync): device discovery
// registry with presence leases, cross-device session handoff &
// remote intent dispatch, WearOS compact binary sync. The JNI bridge
// for the removed RustConnectGateway class was deleted in Phase 4
// (clean parity); the engines below stay with their own tests.
pub mod connect_gateway;
pub mod device_registry;
pub mod remote_intent_engine;
pub mod wear_sync;
// Phase 5 — kinetic gesture physics & waveform indexer
// (feat/phase5-rust-gesture-physics-waveform): analytical 2nd-order
// spring/fling solver with edge rubberbanding, peak-preserving RMS
// waveform downsampling with a compact binary cache. The JNI bridge
// for the removed RustGesturePhysics / RustWaveformIndexer classes was
// deleted in Phase 4 (clean parity); the engines stay with their tests.
pub mod gesture_physics;
pub mod radio_scorer;
pub mod repository;
pub mod resolver;
pub mod search;
pub mod seek_guard;
pub mod spotify_ingest;
pub mod tagger;
pub mod task_orchestrator;
pub mod tick_matrix;
// Phase 4a (MP-HAL01/MP-HAL02): the VirtualHAL deterministic adversarial
// audio test double — simulated DAC presentation clock (jitter, underrun,
// overrun, rate-change stress) validating the DacPresentationClock
// contract headless on every CI runner. Pure-Rust twin of
// native/include/virtual_hal.h.
pub mod virtual_hal;
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
    contract_version, AudioErrorKind, AudioStreamConfig, BufferStats, CryptoErrorKind,
    DacPresentationClock, EngineError, EngineEventListener, EngineSnapshot, JamSessionHandle,
    JamSyncResult, MediaCommand, NetworkErrorKind, PlaybackState, StorageErrorKind,
    StreamifyEngine, TrackMetadata, CONTRACT_VERSION, DAC_CLOCK_MAX_SAMPLE_RATE,
    DAC_CLOCK_MIN_SAMPLE_RATE, MAX_JAM_DELTA_BYTES, MAX_JAM_SESSION_NAME_BYTES,
    MEDIA_COMMAND_DEBOUNCE_MS,
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
// Multiplatform Phase 3: the canonical 48 kHz producer SRC stage.
pub use audio_src::{CanonicalSrc, CANONICAL_RATE_HZ};
// Phase 4a (MP-HAL03): the BS.1770-4 loudness stage.
pub use lufs::{LufsAnalyzer, LufsNormalizer, LufsReport, LUFS_SILENCE};
// Phase 4a (MP-HAL01/MP-HAL02): the VirtualHAL test double + the
// canonical HAL consumer interface and clock interpolator.
pub use virtual_hal::{
    AdversarialModes, AdversarialProfile, AudioHalConsumer, DacClockInterpolator,
    HalPullReport, ScenarioReport, VirtualHalCallback, VirtualHalConfig, VirtualHalSink,
    VirtualHalStats, INTERPOLATION_ACCEPTANCE_MS,
};
// Multiplatform Phase 3 (MP-C15): observability entry points for the
// client shells (Beta's init wiring).
pub use obs::{init_observability, register_core_metrics};
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
