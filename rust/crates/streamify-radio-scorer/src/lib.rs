//! streamify-radio-scorer — the pure radio/blend scoring leaf (WS-W engine
//! facade breadth, work item 3).
//!
//! `RadioAntiDriftEngine` (the anti-drift candidate filter/ranker: signature
//! dedupe, per-artist caps, junk filtering, BPM/key affinity) and the blend
//! weights — moved verbatim from `streamify-resolver/src/radio_scorer.rs`.
//! The scorer is pure (serde + std); it was trapped behind the resolver's
//! native-only transport closure (reqwest/tokio), which the wasm32 build
//! could never cross. `streamify-resolver` re-exports it at the historical
//! `streamify_resolver::radio_scorer` path; the wasm facade
//! (front-end/crates/streamify-wasm) exposes it to JS.

pub mod radio_scorer;

pub use radio_scorer::{BlendWeights, RadioAntiDriftEngine, ScoredCandidate};
