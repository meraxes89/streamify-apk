//! streamify-storage — device-local encrypted storage (Discussion #120).
//!
//! The SQLite connection pool with its Tier 0 B1 lane discipline
//! (dedicated read/write lanes, WAL maintenance), the schema migration
//! runner, the typed repository layer and the cache encryption envelope
//! ([`crate::crypto::VaultCryptoEngine`]).
//!
//! Device-local BY DESIGN (resp5 §2.5): per-device keys from the OS
//! keychain; the Jam mesh syncs CRDT operations over the network, never
//! the database — which eliminates the key-sharing problem entirely and
//! is also the correct architecture for a CRDT.

pub mod backup;
pub mod crypto;
pub mod db_pool;
pub mod repository;

// Root re-exports (mirror the old monolith root surface).
pub use backup::{BackupArchiveEngine, BackupRecord};
pub use crypto::VaultCryptoEngine;
