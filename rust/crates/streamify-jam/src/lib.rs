//! streamify-jam — the serverless P2P mesh crate (Discussion #120).
//!
//! Zero-server LAN discovery and mesh transport, gossip broadcast, CRDT
//! consensus with the collaborative playlist state machines, the chunk
//! swarmer/verifier pair, two-way library catalog sync, the Connect
//! gateway with device presence leases, remote intent dispatch, WearOS
//! compact binary sync, and the clock-discipline pair (Kalman-PLL +
//! PTP filter). Room governance is signed: ephemeral Ed25519 identities
//! for hosts, signed directives and kicks.
//!
//! All async work rides the ENGINE'S shared tokio runtime
//! (streamify-resolver's `get_runtime()`); this crate never spawns a
//! second executor.

pub mod airdrop;
pub mod chunk_swarmer;
pub mod chunk_verifier;
pub mod connect_gateway;
pub mod consensus;
pub mod device_registry;
pub mod gossip;
pub mod jam_clock;
pub mod jam_crdt;
pub mod jam_governor;
pub mod jam_outbox;
pub mod kalman_pll;
pub mod local_sync;
pub mod p2p_mesh;
pub mod ptp;
pub mod remote_intent_engine;
pub mod wear_sync;

// Root re-exports (mirror the old monolith root surface).
pub use airdrop::{AirdropPhysicsEngine, AirdropState};
pub use consensus::{
    CollabPlaylistState, ConsensusEngine, PlaylistApplyResult, PlaylistItemView, PlaylistOp,
    PlaylistOpKind, PlaylistReject, PlaylistRole,
};
pub use ptp::PtpFilter;
