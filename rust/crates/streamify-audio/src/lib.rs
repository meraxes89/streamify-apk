//! streamify-audio — the hard real-time DSP crate (Discussion #120).
//!
//! RT-SAFETY INVARIANT — the reason this crate exists: the render
//! callback path (WASAPI / PipeWire / AAudio pull callbacks) must never
//! block, never allocate behind a lock, and never touch an async
//! runtime. This crate's dependency list is the compile-time boundary:
//! `streamify-core` (std-only leaf) and `rubato` (pure math) — **no
//! tokio, no rusqlite, no reqwest, no socket2, and none may ever be
//! added**. `scripts/verify_audio_rt_boundary.sh` (CI) fails the build
//! if an async or DB crate appears anywhere under
//! `cargo tree -p streamify-audio`.
//!
//! Contents: the canonical 48 kHz producer-side polyphase sinc SRC
//! (MP-C11), the EBU R128 / ITU-R BS.1770-4 loudness stage (MP-HAL03),
//! the studio EQ / biquad / visualizer DSP, the VirtualHAL adversarial
//! test double (MP-HAL01/02), the desktop HAL mock models (MP-HAL08/09)
//! and the peak-preserving waveform indexer.
//!
//! `cargo test -p streamify-audio` runs the golden-vector DSP suite in
//! isolation — no networking or SQLite in the dependency graph.

pub mod audio_dsp;
pub mod audio_src;
pub mod crossfade;
pub mod desktop_hal_models;
pub mod dsp;
pub mod lufs;
pub mod normalizer;
pub mod seek_guard;
pub mod tick_matrix;
pub mod virtual_hal;
pub mod waveform_indexer;

// Root re-exports (mirror the old monolith root surface for the audio
// domain so downstream paths keep resolving unchanged).
pub use audio_src::{CanonicalSrc, CANONICAL_RATE_HZ};
pub use crossfade::CrossfadeDspEngine;
pub use dsp::{BiquadFilter, FilterType, SpectrumVisualizer, StudioEqualizer};
pub use lufs::{LufsAnalyzer, LufsNormalizer, LufsReport, LUFS_SILENCE};
pub use virtual_hal::{
    AdversarialModes, AdversarialProfile, AudioHalConsumer, DacClockInterpolator, HalPullReport,
    ScenarioReport, VirtualHalCallback, VirtualHalConfig, VirtualHalSink, VirtualHalStats,
    INTERPOLATION_ACCEPTANCE_MS,
};
pub use desktop_hal_models::{
    alsa_presented_frames, pipewire_ticks_to_frames, wasapi_frames_to_render, wasapi_qpc100_to_ns,
    wasapi_scale_device_position, DesktopHalFlavor, DesktopHalModel, DesktopHalModelConfig,
    DesktopHalTelemetry, DESKTOP_CANONICAL_RATE_HZ,
};
