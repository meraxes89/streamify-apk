//! streamify-core — the workspace's leaf crate (Discussion #120).
//!
//! Pure leaf types, the [`DacPresentationClock`] record, the structured
//! [`EngineError`] taxonomy, [`MediaCommand`], atomic primitives and
//! dependency-free leaf compute (analytical gesture physics, shared
//! little-endian wire-field readers). NOTHING else in the workspace sits
//! below this crate, and this crate depends on NOTHING outside `std` —
//! that is the invariant that lets the real-time audio crate share the
//! contract vocabulary without pulling a single external (let alone
//! async) dependency through it.
//!
//! The UDL-declared data types live here and are re-exported by
//! [`streamify_contract`] at its root, where the UniFFI scaffolding
//! binds them through the `FfiConverter<UT>` tag pattern — the same
//! definitions serve the FFI surface and every in-workspace consumer.

pub mod error;
pub mod gesture_physics;
pub mod jam_session;
pub mod media;
pub mod snapshot;
pub mod version;
// Audit #22 item 6: shared little-endian wire-field readers —
// panic-free ingress parsing for every wire codec (jam mesh).
pub mod wire_le;

// Root re-exports: the contract vocabulary (mirrors the old monolith
// root surface for these items so downstream paths keep resolving).
pub use error::{
    AudioErrorKind, CryptoErrorKind, EngineError, NetworkErrorKind, StorageErrorKind,
};
pub use gesture_physics::{
    fling_landing, fling_position, fling_velocity, rubberband_offset, snap_to_bounds,
    spring_displacement, spring_position_at, spring_trajectory, spring_trajectory_from,
    spring_velocity_at, PhysicsError, SpringSpec, FRAME_INTERVAL_120HZ, RUBBERBAND_RESISTANCE,
};
pub use jam_session::{
    JamSessionHandle, JamSyncResult, MAX_JAM_DELTA_BYTES, MAX_JAM_SESSION_NAME_BYTES,
};
pub use media::{media_command_key, MediaCommand, MEDIA_COMMAND_DEBOUNCE_MS};
pub use snapshot::{
    AudioStreamConfig, BufferStats, DacPresentationClock, EngineSnapshot, PlaybackState,
    TrackMetadata, DAC_CLOCK_MAX_SAMPLE_RATE, DAC_CLOCK_MIN_SAMPLE_RATE,
};
pub use version::{contract_version, CONTRACT_VERSION};
pub use wire_le::{fixed, le_i64, le_u16, le_u32, le_u64};
