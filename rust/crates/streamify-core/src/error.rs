//! MP-C04: the structured, nested engine failure taxonomy crossing the
//! FFI boundary.
//!
//! Non-exhaustive BY DESIGN: the Rust-side enum carries
//! `#[non_exhaustive]` so the core can grow categories without breaking
//! in-crate consumers, and the stable numeric codes are the
//! foreign-side forward-compat currency. The `Internal` variant is also
//! the panic channel: every UniFFI entrypoint is wrapped in
//! `catch_unwind` and converts a Rust panic into `Internal {
//! error_message, backtrace }` (MP-C06) — a panic never unwinds into
//! Kotlin or Swift.
//!
//! Home: streamify-core (Discussion #120) so the audio, storage, jam
//! and resolver crates can surface failures through the shared
//! taxonomy without depending on the contract crate above them.

use std::fmt;

// ============================================================================
//  MP-C04: structured error taxonomy
// ============================================================================

/// Network failure taxonomy for [`EngineError::Network`].
///
/// Discriminants are the stable-code kind bytes (see
/// [`EngineError::stable_code`]) and MUST NOT be renumbered.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkErrorKind {
    /// Deadline elapsed before a response (retry is safe).
    Timeout = 1,
    /// Name resolution failed.
    Dns = 2,
    /// TLS handshake / certificate failure.
    Tls = 3,
    /// Transport connection could not be established.
    Connect = 4,
    /// Protocol-level failure with an HTTP status (see `status`).
    Http = 5,
    /// Request cancelled (lifecycle teardown, flush, seek).
    Cancelled = 6,
}

/// Audio-subsystem failure taxonomy for [`EngineError::Audio`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioErrorKind {
    /// No usable output device / device gone.
    DeviceUnavailable = 1,
    /// Pipeline geometry or codec the engine cannot express.
    FormatUnsupported = 2,
    /// Malformed DAC presentation clock sample (MP-C01 validation).
    InvalidClock = 3,
    /// Playback starved waiting for buffered media.
    Underrun = 4,
    /// Audio subsystem failed to initialize.
    Init = 5,
}

/// Storage-subsystem failure taxonomy for [`EngineError::Storage`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StorageErrorKind {
    /// Generic I/O fault.
    Io = 1,
    /// Target does not exist.
    NotFound = 2,
    /// Integrity failure (checksum, header, migration).
    Corrupt = 3,
    /// Access denied by the OS or keyring.
    Permission = 4,
    /// No space left on device.
    Full = 5,
}

/// Cryptography failure taxonomy for [`EngineError::Crypto`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CryptoErrorKind {
    /// Key material unavailable (keyring locked, key evicted).
    KeyUnavailable = 1,
    /// Decryption failed (AEAD tag, header, ciphertext).
    DecryptFailed = 2,
    /// Encryption failed.
    EncryptFailed = 3,
    /// Key material malformed or wrong length.
    InvalidKeyMaterial = 4,
}

/// Typed failure taxonomy crossing the FFI boundary (MP-C04).
///
/// Structured and nested: each category carries its own payload so
/// foreign error handlers can route (retry network, re-init audio,
/// re-key crypto, diagnose internal) without string matching. The enum
/// is `#[non_exhaustive]` in BOTH the UDL (`[NonExhaustive]`) and here:
/// the Rust core may grow categories/variants, and foreign consumers
/// must compile an unknown-variant fallback (the generated Kotlin/Swift
/// bindings do).
///
/// `Internal` is also the panic channel (MP-C06): the FFI shields
/// convert any Rust panic into
/// `Internal { error_message, backtrace }`.
///
/// Stable numeric codes: [`EngineError::stable_code`] maps every error
/// to a `u32` of the form `(category << 24) | (kind << 16) | status`,
/// pinned by unit tests — clients may persist/log the codes across
/// releases, so they MUST NOT be renumbered.
#[derive(Debug, Clone, PartialEq, Eq)]
#[non_exhaustive]
pub enum EngineError {
    /// Stream acquisition / transport failure.
    Network {
        /// Failure class (routes the retry policy).
        kind: NetworkErrorKind,
        /// HTTP status when a response arrived, 0 otherwise.
        status: u16,
        /// Server-advised or engine-estimated retry hint, milliseconds.
        retry_after: u32,
    },
    /// Audio subsystem failure (device, format, clock, underrun, init).
    Audio {
        /// Failure class.
        kind: AudioErrorKind,
        /// HAL device identifier, when known.
        device_id: Option<String>,
    },
    /// Device-local encrypted storage failure.
    Storage {
        /// Failure class.
        kind: StorageErrorKind,
    },
    /// Key material / cipher operation failure.
    Crypto {
        /// Failure class.
        kind: CryptoErrorKind,
    },
    /// Unexpected internal fault, including marshaled Rust panics
    /// (MP-C06). `error_message` carries the panic text or diagnostic;
    /// `backtrace` the captured stack when available. The field name is
    /// pinned to `error_message` (not `message`) by the UniFFI contract —
    /// see the UDL's NAMING NOTE for the Kotlin codegen collision.
    Internal {
        /// Human-readable diagnostic (panic text for shielded panics).
        error_message: String,
        /// Captured backtrace, when available.
        backtrace: Option<String>,
    },
}

impl EngineError {
    /// Stable numeric error code (MP-C04): `(category << 24) | (kind << 16)
    /// | status`. Category and kind bytes are pinned by the
    /// `stable_error_codes_are_pinned` test; the low half-word carries the
    /// HTTP status for `Network` and is zero elsewhere. Codes are part of
    /// the wire contract once persisted by clients — renumbering is a
    /// breaking change.
    pub fn stable_code(&self) -> u32 {
        match self {
            EngineError::Network { kind, status, .. } => {
                (0x01 << 24) | ((*kind as u32) << 16) | u32::from(*status)
            }
            EngineError::Audio { kind, .. } => (0x02 << 24) | ((*kind as u32) << 16),
            EngineError::Storage { kind } => (0x03 << 24) | ((*kind as u32) << 16),
            EngineError::Crypto { kind } => (0x04 << 24) | ((*kind as u32) << 16),
            EngineError::Internal { .. } => 0x05 << 24,
        }
    }
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::Network {
                kind,
                status,
                retry_after,
            } => write!(
                f,
                "network error: {kind:?} (status {status}, retry-after {retry_after} ms)"
            ),
            EngineError::Audio { kind, device_id } => match device_id {
                Some(id) => write!(f, "audio error: {kind:?} (device {id})"),
                None => write!(f, "audio error: {kind:?}"),
            },
            EngineError::Storage { kind } => write!(f, "storage error: {kind:?}"),
            EngineError::Crypto { kind } => write!(f, "crypto error: {kind:?}"),
            EngineError::Internal { error_message, .. } => {
                write!(f, "internal error: {error_message}")
            }
        }
    }
}

impl std::error::Error for EngineError {}

