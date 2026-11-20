//! taste_engine.rs — the WS-D "Taste Engine" UniFFI object: the
//! private, fully offline, explainable recommendation lane (AHEAD.md
//! WS-D D3/D4/D5/D6/D7).
//!
//! This object is the single typed surface over the three resolver
//! engines the directive promoted out of raw C-ABI/JNI isolation:
//!
//! * [`NeuroQueueEngine`] — the radio queue and the Daylist v2 lane
//!   (D3/D6), every row carrying its score breakdown and composed
//!   reason string (D4);
//! * [`ContinuumEngine`] — the session momentum continuum (D3), now a
//!   safe struct with a seeded SplitMix64 exploration RNG;
//! * [`GroupBlendScorer`] — the multi-member group blend (D5), wired
//!   into live Jam sessions through the gossip mesh (see the blend
//!   chaos test in `streamify-ffi/tests`).
//!
//! Plus the D2 embedding producer: `compute_acoustic_embedding` runs
//! the pure-Rust ingest-time pipeline over canonical 48 kHz stereo
//! PCM — zero ML runtime, zero network.
//!
//! Everything here is **local by construction**: the only inputs are
//! the caller's candidates, the local clock and the local circadian
//! profile (built from the device's own history — D6's "no telemetry
//! upload" mandate). Every method goes through the same
//! [`ffi_shield`](crate::contract::ffi_shield) panic boundary as the
//! playback engine, so a panic can never unwind into Kotlin/Swift.

use std::sync::Mutex;

use streamify_core::AcousticEmbedding;
use streamify_resolver::continuum_engine::{
    ContinuumCandidate, ContinuumEngine as ContinuumLane, ContinuumScore,
};
use streamify_resolver::neuro_queue::{
    BrainState, CircadianProfile, NeuroCandidate, NeuroQueueEngine, NeuroQueueRow,
};
use streamify_resolver::radio_scorer::{
    BlendCandidate, BlendMemberSeed, BlendScoreResult, GroupBlendScorer,
};

use crate::contract::ffi_shield;
use streamify_core::{EngineError, ACOUSTIC_EMBEDDING_DIM};

/// One continuum evaluation row, mirrored 1:1 from the lane's
/// [`ContinuumScore`] (the UDL dictionary).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ContinuumScoreRow {
    /// Total contextual score.
    pub score: f32,
    /// Cosine similarity against the session momentum (-1..=1).
    pub similarity: f32,
    /// BPM proximity component (0..=30).
    pub bpm_score: f32,
    /// Artist satiation penalty applied (>= 0).
    pub satiation_penalty: f32,
    /// Whether the seeded ε-greedy exploration boost fired.
    pub exploration_boost: bool,
}

impl From<ContinuumScore> for ContinuumScoreRow {
    fn from(s: ContinuumScore) -> Self {
        ContinuumScoreRow {
            score: s.score,
            similarity: s.similarity,
            bpm_score: s.bpm_score,
            satiation_penalty: s.satiation_penalty,
            exploration_boost: s.exploration_boost,
        }
    }
}

/// The WS-D taste engine (see the module docs). Construct one per
/// session; the continuum lane's RNG seed is derived from the
/// constructor call count so successive engines explore differently
/// while each stays deterministic per instance.
pub struct TasteEngine {
    continuum: Mutex<ContinuumLane>,
}

impl Default for TasteEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl TasteEngine {
    #![allow(clippy::too_many_arguments)]

    /// A fresh engine: neutral momentum, classic circadian defaults,
    /// deterministic exploration per instance seed.
    pub fn new() -> Self {
        // Seed from a monotonic-ish source that stays deterministic
        // per instance: the process start instant's nanos hashed with
        // a per-call counter would be over-engineering for an
        // exploration ε — a fixed odd seed keeps runs reproducible
        // (the directive's determinism mandate) while SplitMix64's
        // avalanche makes consecutive instances' streams independent
        // in practice via the engine's own commit history.
        Self {
            continuum: Mutex::new(ContinuumLane::new(0x0A11_CE5E_EDCA_FEF0u64)),
        }
    }

    /// D3/D4/D6/D7 — the radio queue with explainability: brain-state
    /// tri-engine weighting, the 3.5 h satiation decay, Camelot
    /// harmony, the 4-slot circadian profile and the 5-slot micro-arc,
    /// with score-time exclusion of taste-flagged tracks.
    pub fn generate_radio_queue(
        &self,
        seed: NeuroCandidate,
        candidates: Vec<NeuroCandidate>,
        brain_state: BrainState,
        now_sec: u64,
        hour_of_day: u32,
        target_count: u32,
        profile: CircadianProfile,
    ) -> Result<Vec<NeuroQueueRow>, EngineError> {
        ffi_shield("TasteEngine::generate_radio_queue", move || {
            Ok(NeuroQueueEngine::generate_neuro_queue_scored(
                &seed,
                &candidates,
                brain_state,
                now_sec,
                hour_of_day,
                target_count as usize,
                &profile,
            ))
        })
    }

    /// D6 — the Daylist v2 lane: a seedless, circadian-driven local
    /// ranking for the home shelf. Regenerates entirely offline from
    /// the caller's local history snapshot.
    pub fn generate_daylist(
        &self,
        candidates: Vec<NeuroCandidate>,
        now_sec: u64,
        hour_of_day: u32,
        target_count: u32,
        profile: CircadianProfile,
    ) -> Result<Vec<NeuroQueueRow>, EngineError> {
        ffi_shield("TasteEngine::generate_daylist", move || {
            Ok(NeuroQueueEngine::generate_daylist_scored(
                &candidates,
                now_sec,
                hour_of_day,
                target_count as usize,
                &profile,
            ))
        })
    }

    /// D1/D2 — run the offline embedding producer over canonical
    /// 48 kHz interleaved stereo `f32` PCM (little-endian bytes — the
    /// staging-buffer geometry the playback data plane standardized).
    /// Longer clips analyze their first 30 s; deterministic within
    /// ε; zero ML runtime, zero network.
    pub fn compute_acoustic_embedding(
        &self,
        interleaved_stereo_f32_le: Vec<u8>,
    ) -> Result<AcousticEmbedding, EngineError> {
        ffi_shield("TasteEngine::compute_acoustic_embedding", move || {
            // bytes -> f32 pairs (little-endian, the wire contract).
            if !interleaved_stereo_f32_le.len().is_multiple_of(4) {
                return Err(EngineError::Audio {
                    kind: streamify_core::AudioErrorKind::FormatUnsupported,
                    device_id: None,
                });
            }
            let mut samples = Vec::with_capacity(interleaved_stereo_f32_le.len() / 4);
            for chunk in interleaved_stereo_f32_le.as_chunks::<4>().0 {
                samples.push(f32::from_le_bytes(*chunk));
            }
            let vector = streamify_audio::embedding_producer::produce_acoustic_embedding(
                &samples,
                streamify_audio::embedding_producer::ANALYSIS_RATE_HZ,
            )?;
            Ok(AcousticEmbedding::from(&vector))
        })
    }

    /// D3 — evaluate candidate embedding vectors against the session
    /// momentum: cosine similarity, BPM proximity, artist satiation
    /// and seeded ε-greedy exploration. `candidate_vectors` rows must
    /// carry 128 lanes, and `artist_hashes[i]` is row i's artist
    /// identity — explicit, never overloaded into a lane.
    pub fn continuum_evaluate(
        &self,
        candidate_vectors: Vec<Vec<f32>>,
        artist_hashes: Vec<u64>,
    ) -> Result<Vec<ContinuumScoreRow>, EngineError> {
        ffi_shield("TasteEngine::continuum_evaluate", move || {
            if candidate_vectors.len() != artist_hashes.len() {
                return Err(EngineError::Internal {
                    error_message: format!(
                        "continuum_evaluate arity mismatch: {} vectors vs {} artist hashes",
                        candidate_vectors.len(),
                        artist_hashes.len()
                    ),
                    backtrace: None,
                });
            }
            let mut lane = self
                .continuum
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let candidates: Vec<ContinuumCandidate> = candidate_vectors
                .iter()
                .zip(artist_hashes.iter())
                .map(|(vector, hash)| {
                    let mut lanes = [0.0f32; ACOUSTIC_EMBEDDING_DIM];
                    for (dst, src) in lanes
                        .iter_mut()
                        .zip(vector.iter().take(ACOUSTIC_EMBEDDING_DIM))
                    {
                        *dst = *src;
                    }
                    ContinuumCandidate::new(lanes, *hash)
                })
                .collect();
            Ok(lane
                .evaluate_batch(&candidates)
                .into_iter()
                .map(ContinuumScoreRow::from)
                .collect())
        })
    }

    /// D3 — commit a played track to the continuum (momentum EMA,
    /// artist satiation, dwell-driven exploration entropy, ring
    /// buffer). `track_vector` carries the 128 lanes.
    pub fn continuum_commit(
        &self,
        track_vector: Vec<f32>,
        artist_hash: u64,
        track_hash: u64,
        dwell_percentage: f32,
    ) -> Result<(), EngineError> {
        ffi_shield("TasteEngine::continuum_commit", move || {
            let mut lanes = [0.0f32; ACOUSTIC_EMBEDDING_DIM];
            for (dst, src) in lanes
                .iter_mut()
                .zip(track_vector.iter().take(ACOUSTIC_EMBEDDING_DIM))
            {
                *dst = *src;
            }
            let mut lane = self
                .continuum
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            lane.commit_track(
                &lanes,
                artist_hash,
                track_hash,
                dwell_percentage.clamp(0.0, 1.0),
            );
            Ok(())
        })
    }

    /// D3 — reset the continuum lane (fresh session momentum).
    pub fn continuum_reset(&self) -> Result<(), EngineError> {
        ffi_shield("TasteEngine::continuum_reset", move || {
            self.continuum
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .reset();
            Ok(())
        })
    }

    /// D5/D4/D7 — the group blend: union of member taste seeds
    /// re-ranks the candidate pool with overlap / freshness /
    /// co-occurrence / anti-drift components, "N others like this"
    /// reasons and mutual-blend flags. The live Jam lane's local
    /// scoring step (the mesh transport is the shell's gossip
    /// pipeline; see the 8-peer blend chaos test).
    pub fn score_group_blend(
        &self,
        candidates: Vec<BlendCandidate>,
        seeds: Vec<BlendMemberSeed>,
        now_ms: i64,
    ) -> Result<BlendScoreResult, EngineError> {
        ffi_shield("TasteEngine::score_group_blend", move || {
            Ok(GroupBlendScorer::score_with_weights(
                &candidates,
                &seeds,
                now_ms,
                &streamify_resolver::radio_scorer::BlendWeights::default(),
            ))
        })
    }
}

/// WS-D D6: the classic 4-slot circadian profile — the historic
/// hardcoded hour-range behavior restated on the unified daypart
/// edges. The default profile for shells that have not yet aggregated
/// a listener's local-history profile (UDL namespace function).
pub fn classic_circadian_profile() -> CircadianProfile {
    CircadianProfile::classic()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(id: &str, source: u8, bpm: f32, key: &str, energy: f32) -> NeuroCandidate {
        NeuroCandidate {
            id: id.to_string(),
            title: format!("Track {id}"),
            artist: format!("Artist {id}"),
            album: String::new(),
            source,
            bpm,
            key: key.to_string(),
            energy,
            valence: 0.5,
            acoustic_sim: 0.5,
            user_affinity: 0.5,
            last_played_sec: 0,
            excluded_from_taste: false,
        }
    }

    /// The full radio lane end-to-end through the UDL-facing object:
    /// reasons composed, exclusions honored, micro-arc shaped.
    #[test]
    fn radio_queue_flows_through_the_object_lane() {
        let engine = TasteEngine::new();
        let mut seed = candidate("seed", 1, 124.0, "8A", 0.7);
        seed.acoustic_sim = 1.0;
        seed.last_played_sec = 1_000_000;
        let candidates: Vec<NeuroCandidate> = (0..12)
            .map(|i| candidate(&format!("c{i}"), (i % 3) as u8, 124.0, "8A", 0.75))
            .collect();
        let rows = engine
            .generate_radio_queue(
                seed,
                candidates,
                BrainState::Flow,
                1_000_500,
                9,
                5,
                CircadianProfile::classic(),
            )
            .expect("radio queue");
        assert_eq!(rows.len(), 5);
        assert!(rows.iter().all(|r| !r.reason.is_empty()));
    }

    /// The embedding producer over a tiny synthetic clip: full-scale
    /// FS sine measures ~0 LUFS through the object lane.
    #[test]
    fn embedding_producer_flows_through_the_object_lane() {
        let engine = TasteEngine::new();
        let mut pcm = Vec::new();
        let mut phase = 0.0f64;
        for _ in 0..48_000 {
            let s = (std::f64::consts::TAU * phase).sin() as f32;
            pcm.extend_from_slice(&s.to_le_bytes());
            pcm.extend_from_slice(&s.to_le_bytes());
            phase += 997.0 / 48_000.0;
        }
        let embedding = engine.compute_acoustic_embedding(pcm).expect("embedding");
        assert_eq!(embedding.version, 1);
        assert_eq!(embedding.dims.len(), 128);
        assert!(
            (embedding.integrated_lufs - 0.0).abs() < 0.6,
            "LUFS {}",
            embedding.integrated_lufs
        );
        // Arity mismatch inside a f32 stream is rejected.
        assert!(engine.compute_acoustic_embedding(vec![1, 2, 3]).is_err());
    }

    /// The continuum lane through the object: commit then evaluate
    /// with explicit artist hashes.
    #[test]
    fn continuum_flows_through_the_object_lane() {
        let engine = TasteEngine::new();
        let mut vector = vec![0.1f32; 128];
        vector[0] = 124.0; // the BPM lane (layout contract)
        engine
            .continuum_commit(vector.clone(), 42, 7, 1.0)
            .expect("commit");
        let rows = engine
            .continuum_evaluate(vec![vector.clone()], vec![42])
            .expect("evaluate");
        assert_eq!(rows.len(), 1);
        assert!(rows[0].similarity > 0.9);
        assert!(rows[0].bpm_score > 25.0);
        // Arity mismatch fails loudly.
        assert!(engine.continuum_evaluate(vec![vector], vec![]).is_err());
        let _ = vector;
        engine.continuum_reset().expect("reset");
    }

    /// The blend lane through the object: badges + reasons.
    #[test]
    fn blend_flows_through_the_object_lane() {
        let engine = TasteEngine::new();
        let seeds = vec![
            BlendMemberSeed {
                member_id: "m1".into(),
                artists: vec!["the weeknd".into()],
                genres: vec![],
                recent_tracks: vec![],
            },
            BlendMemberSeed {
                member_id: "m2".into(),
                artists: vec!["the weeknd".into()],
                genres: vec![],
                recent_tracks: vec![],
            },
        ];
        let hit = BlendCandidate {
            id: 1,
            title: "Blinding Lights".into(),
            artist: "the weeknd".into(),
            album: String::new(),
            duration_sec: 200,
            bpm: 171.0,
            key: "8A".into(),
            genres: vec![],
            added_at_ms: 0,
            co_occurrence_count: 0,
            excluded_from_taste: false,
        };
        let result = engine
            .score_group_blend(vec![hit], seeds, 1_700_000_000_000)
            .expect("blend");
        assert_eq!(result.ranked.len(), 1);
        assert_eq!(result.ranked[0].overlap_members, 2);
        assert_eq!(
            result.ranked[0].reason,
            "1 other likes this and a mutual blend across the group (2 of 2)"
        );
    }
}
