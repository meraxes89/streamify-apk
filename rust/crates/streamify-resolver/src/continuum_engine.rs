//! continuum_engine.rs — the session "kinetic momentum" continuum
//! (WS-D D3: promoted from raw C-ABI/JNI isolation to a first-class,
//! UniFFI-surfaced, unit-tested engine).
//!
//! History: this engine previously lived behind four `#[no_mangle]`
//! `extern "C"` functions consumed by a hand-rolled JNI bridge, with
//! zero test coverage, and — the D1 problem statement — lazily
//! overloaded raw vector positions (`vec[0]` = BPM, `vec[1]` = artist
//! hash) for its feature reads. WS-D fixes all three:
//!
//! * **Safe struct API** — [`ContinuumEngine`] owns its state; the
//!   `#[no_mangle]` surface is gone (the UDL `TasteEngine` lane is the
//!   only consumer now);
//! * **Explicit identity** — candidate artist hashes travel as
//!   [`ContinuumCandidate::artist_hash`], never overloaded into a
//!   vector lane; the BPM read goes through the versioned
//!   [`streamify_core::acoustic_embedding::layout`] contract;
//! * **A properly seeded RNG** — the old sine-based pseudo-random
//!   exploration is replaced by a seeded SplitMix64 generator (the
//!   same family the Java/Go standard libraries use for splittable
//!   randomness): deterministic per seed, statistically sound, and
//!   unit-testable.
//!
//! The scoring model is unchanged in spirit: cosine similarity against
//! the session's momentum vector (an EMA of played embeddings), BPM
//! proximity, event-based artist satiation, and an ε-greedy
//! exploration boost driven by dwell behavior.

use std::collections::HashMap;

use streamify_core::acoustic_embedding::{layout, ACOUSTIC_EMBEDDING_DIM};

/// One candidate row for the continuum lane: the 128-dim acoustic
/// embedding (the versioned [`streamify_core::AcousticEmbeddingVector`]
/// contract) plus the artist's identity hash — explicit, never
/// overloaded into a vector lane (the D1 fix).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ContinuumCandidate {
    /// The 128-dim embedding lanes.
    pub vector: [f32; ACOUSTIC_EMBEDDING_DIM],
    /// Stable hash of the candidate's artist (0 = unknown).
    pub artist_hash: u64,
}

impl ContinuumCandidate {
    /// Wrap a raw embedding with its artist identity.
    pub fn new(vector: [f32; ACOUSTIC_EMBEDDING_DIM], artist_hash: u64) -> Self {
        ContinuumCandidate {
            vector,
            artist_hash,
        }
    }

    /// The candidate's tempo (BPM; 0 = unknown) — read through the
    /// versioned layout contract.
    pub fn bpm(&self) -> f32 {
        self.vector[layout::BPM]
    }
}

/// One scored continuum row.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ContinuumScore {
    /// Total contextual score.
    pub score: f32,
    /// Cosine similarity against the momentum vector (-1..=1).
    pub similarity: f32,
    /// BPM proximity component (0..=30).
    pub bpm_score: f32,
    /// Artist satiation penalty applied (>= 0).
    pub satiation_penalty: f32,
    /// Whether the exploration ε-greedy boost fired.
    pub exploration_boost: bool,
}

/// SplitMix64 — a properly seeded, statistically sound PRNG (the
/// splittable-random family from Steele et al., as used by the Java
/// and Go standard libraries). Deterministic per seed, one `u64` of
/// state, ~2^64 period: everything the old sine-based pseudo-random
/// was not.
#[derive(Debug, Clone)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// A generator seeded from one `u64` (0 re-seeds to the splitmix
    /// constant so the zero seed is not degenerate).
    pub fn new(seed: u64) -> Self {
        SplitMix64 {
            state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
        }
    }

    /// Next raw 64-bit draw.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Next uniform `f32` in `[0, 1)` (24 bits of entropy — f32's
    /// mantissa width).
    pub fn next_f32(&mut self) -> f32 {
        (self.next_u64() >> 40) as f32 / (1u64 << 24) as f32
    }
}

/// The continuum engine's live state (interior; read through
/// accessors).
#[derive(Debug, Clone)]
pub struct ContinuumState {
    /// Kinetic momentum: rolling EMA of 128-dim acoustic embeddings.
    pub momentum_vector: [f32; ACOUSTIC_EMBEDDING_DIM],
    /// EMA of the BPM lane (read via the layout contract).
    current_bpm: f32,

    // Event-based tracking (zero wall-clock reliance).
    session_artist_counts: HashMap<u64, u8>,
    session_track_hashes: [u64; 50],
    session_track_idx: usize,

    /// Exploration entropy (0.0 to 1.0).
    exploration_entropy: f32,
}

impl Default for ContinuumState {
    fn default() -> Self {
        Self {
            momentum_vector: [0.0; ACOUSTIC_EMBEDDING_DIM],
            current_bpm: 120.0,
            session_artist_counts: HashMap::with_capacity(64),
            session_track_hashes: [0; 50],
            session_track_idx: 0,
            exploration_entropy: 0.15,
        }
    }
}

/// The session continuum engine — safe, stateful, deterministic per
/// seed.
#[derive(Debug, Clone)]
pub struct ContinuumEngine {
    state: ContinuumState,
    rng: SplitMix64,
}

impl ContinuumEngine {
    /// Build the engine with an explicit RNG seed (the D3 AC: the
    /// sine-RNG is replaced by a properly seeded generator; identical
    /// seeds produce identical exploration patterns — unit-tested).
    pub fn new(seed: u64) -> Self {
        ContinuumEngine {
            state: ContinuumState::default(),
            rng: SplitMix64::new(seed),
        }
    }

    /// Current exploration entropy (0..=1).
    pub fn exploration_entropy(&self) -> f32 {
        self.state.exploration_entropy
    }

    /// The session momentum vector (telemetry / tests).
    pub fn momentum_vector(&self) -> &[f32; ACOUSTIC_EMBEDDING_DIM] {
        &self.state.momentum_vector
    }

    /// The EMA tempo the engine is tracking (BPM lane via the layout
    /// contract — no raw overloads).
    pub fn current_bpm(&self) -> f32 {
        self.state.current_bpm
    }

    /// Session play count for one artist (event-based satiation state).
    pub fn session_artist_count(&self, artist_hash: u64) -> u8 {
        self.state
            .session_artist_counts
            .get(&artist_hash)
            .copied()
            .unwrap_or(0)
    }

    /// Reset to a fresh session (keeps the RNG seed's stream position —
    /// call [`Self::new`] for a full reset).
    pub fn reset(&mut self) {
        self.state = ContinuumState::default();
    }

    /// Evaluates a batch of candidates in pure vector math, returning
    /// contextual scores with their component decomposition.
    pub fn evaluate_batch(&mut self, candidates: &[ContinuumCandidate]) -> Vec<ContinuumScore> {
        let mut out = Vec::with_capacity(candidates.len());
        for c in candidates {
            // 1. Vector cosine similarity against the momentum vector.
            let mut dot_product = 0.0f32;
            let mut norm_c = 0.0f32;
            let mut norm_m = 0.0f32;
            #[allow(clippy::needless_range_loop)] // parallel vector lanes
            for j in 0..ACOUSTIC_EMBEDDING_DIM {
                let cv = c.vector[j];
                let mv = self.state.momentum_vector[j];
                dot_product += cv * mv;
                norm_c += cv * cv;
                norm_m += mv * mv;
            }
            let denom = (norm_c.sqrt() * norm_m.sqrt()).max(1e-6);
            let sim = (dot_product / denom).clamp(-1.0, 1.0);

            // 2. BPM proximity score (the layout contract's BPM lane).
            let cand_bpm = c.bpm().abs().clamp(40.0, 240.0);
            let bpm_diff = (cand_bpm - self.state.current_bpm).abs();
            let bpm_score = 30.0 * (-((bpm_diff * bpm_diff) / (2.0 * 25.0 * 25.0))).exp();

            // 3. Event-based artist satiation penalty.
            let artist_count = f32::from(self.session_artist_count(c.artist_hash));
            let satiation_penalty = (artist_count / 5.0).min(1.0) * 50.0;

            // 4. Base contextual score.
            let mut score = (sim * 60.0) + bpm_score - satiation_penalty;

            // 5. Exploration entropy boost (ε-greedy over the SEEDED
            //    SplitMix64 — replacing the sine pseudo-random).
            let mut exploration_boost = false;
            if self.state.exploration_entropy > 0.2
                && self.rng.next_f32() < self.state.exploration_entropy
            {
                score += 25.0;
                exploration_boost = true;
            }

            out.push(ContinuumScore {
                score,
                similarity: sim,
                bpm_score,
                satiation_penalty,
                exploration_boost,
            });
        }
        out
    }

    /// Commits a played track: updates momentum (α = 0.3 EMA), event
    /// satiation, exploration entropy and the ring buffer.
    pub fn commit_track(
        &mut self,
        track_vector: &[f32; ACOUSTIC_EMBEDDING_DIM],
        artist_hash: u64,
        track_hash: u64,
        dwell_percentage: f32,
    ) {
        // 1. Exponential moving average of kinetic momentum
        //    (α = 0.3: 30% new track, 70% session history).
        let alpha = 0.3f32;
        let one_minus_alpha = 0.7f32;
        #[allow(clippy::needless_range_loop)] // EMA over parallel lanes
        for j in 0..ACOUSTIC_EMBEDDING_DIM {
            self.state.momentum_vector[j] =
                (self.state.momentum_vector[j] * one_minus_alpha) + (track_vector[j] * alpha);
        }

        let played_bpm = track_vector[layout::BPM];
        if played_bpm > 0.0 {
            self.state.current_bpm = self.state.current_bpm * 0.7 + played_bpm * 0.3;
        }

        // 2. Event-based satiation.
        if artist_hash != 0 {
            *self
                .state
                .session_artist_counts
                .entry(artist_hash)
                .or_insert(0) += 1;
        }

        // Natural session decay of older artist counts: every 10th
        // commit cools every artist's counter by one. The ring index
        // advances FIRST so the very first commit never decays itself
        // (the historical C-ABI order evaluated the check on the
        // pre-increment zero index and ate the first play).
        self.state.session_track_idx = (self.state.session_track_idx + 1) % 50;
        if self.state.session_track_idx.is_multiple_of(10) {
            for count in self.state.session_artist_counts.values_mut() {
                if *count > 0 {
                    *count -= 1;
                }
            }
        }

        // 3. Exploration entropy from dwell behavior.
        if dwell_percentage < 0.2 {
            // Early skip: the user is actively searching — spike entropy.
            self.state.exploration_entropy = (self.state.exploration_entropy + 0.35).min(1.0);
        } else if dwell_percentage > 0.8 {
            // Full listen: vibe locked in — decay entropy.
            self.state.exploration_entropy = (self.state.exploration_entropy - 0.1).max(0.05);
        }

        // 4. Ring-buffer tracking (the index advanced in step 2).
        if track_hash != 0 {
            self.state.session_track_hashes[self.state.session_track_idx] = track_hash;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A deterministic embedding with a known BPM lane.
    fn embedding(bpm: f32, fill: f32) -> [f32; ACOUSTIC_EMBEDDING_DIM] {
        let mut v = [fill; ACOUSTIC_EMBEDDING_DIM];
        v[layout::BPM] = bpm;
        v[layout::CAMELOT_NUMBER] = 8.0;
        v[layout::LAYOUT_VERSION] = 1.0;
        v
    }

    #[test]
    fn splitmix64_is_deterministic_per_seed_and_seedable() {
        let mut a = SplitMix64::new(42);
        let mut b = SplitMix64::new(42);
        for _ in 0..1000 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        let mut c = SplitMix64::new(43);
        assert_ne!(a.next_u64(), c.next_u64(), "different seeds diverge");

        // Uniformity sanity: 100k draws in [0,1) must spread across
        // the range (a sine-based pseudo-random fails this shape).
        let mut rng = SplitMix64::new(7);
        let mut buckets = [0u32; 10];
        for _ in 0..100_000 {
            let f = rng.next_f32();
            buckets[(f * 10.0) as usize] += 1;
        }
        for (i, b) in buckets.iter().enumerate() {
            assert!(
                (8_000..=12_000).contains(b),
                "bucket {i} count {b} — the stream is not uniform"
            );
        }
    }

    #[test]
    fn same_seed_same_exploration_pattern() {
        let candidates: Vec<ContinuumCandidate> = (0..50)
            .map(|i| ContinuumCandidate::new(embedding(120.0, 0.1), 100 + i))
            .collect();
        let mut a = ContinuumEngine::new(1234);
        let mut b = ContinuumEngine::new(1234);
        a.commit_track(&embedding(124.0, 0.5), 1, 1, 0.1); // spike entropy
        b.commit_track(&embedding(124.0, 0.5), 1, 1, 0.1);
        let sa = a.evaluate_batch(&candidates);
        let sb = b.evaluate_batch(&candidates);
        assert_eq!(sa, sb, "identical seeds must produce identical patterns");
    }

    #[test]
    fn momentum_ema_converges_toward_played_embeddings() {
        let mut engine = ContinuumEngine::new(1);
        for i in 0..40 {
            engine.commit_track(&embedding(124.0, 0.5), 1, i, 1.0);
        }
        // After 40 commits at α = 0.3, the momentum BPM lane is within
        // a hair of 124.
        assert!((engine.current_bpm() - 124.0).abs() < 0.5);
        let momentum = engine.momentum_vector();
        assert!((momentum[layout::BPM] - 124.0).abs() < 0.5);
        // And the fill lane converges to 0.5.
        assert!((momentum[3] - 0.5).abs() < 0.01);
    }

    #[test]
    fn artist_satiation_penalizes_and_decays() {
        let mut engine = ContinuumEngine::new(2);
        let candidate = ContinuumCandidate::new(embedding(120.0, 0.1), 77);
        // Before any play: no penalty.
        let s0 = engine.evaluate_batch(&[candidate])[0];
        assert_eq!(s0.satiation_penalty, 0.0);
        // Five plays of artist 77 → the full 50-point penalty (and the
        // first commit no longer self-decays — the historical C-ABI
        // order ate it).
        for i in 0..5 {
            engine.commit_track(&embedding(124.0, 0.5), 77, 100 + i, 1.0);
        }
        assert_eq!(engine.session_artist_count(77), 5);
        let saturated = engine.evaluate_batch(&[candidate])[0];
        assert_eq!(saturated.satiation_penalty, 50.0);
        // Same vector, UNSATURATED artist: identical similarity and
        // BPM terms, so the saturated row must rank strictly below.
        let fresh_artist = ContinuumCandidate::new(embedding(120.0, 0.1), 88);
        let fresh = engine.evaluate_batch(&[fresh_artist])[0];
        assert_eq!(fresh.satiation_penalty, 0.0);
        assert!(
            saturated.score < fresh.score,
            "saturated {} vs fresh {}",
            saturated.score,
            fresh.score
        );
        // …and the pre-session baseline read is preserved for history.
        assert!(s0.similarity == 0.0 || s0.similarity.abs() < 1e-6);
        // Every 10th commit cools all counters by one: 5 more plays of
        // other artists tick the ring index to 10 → artist 77 cools.
        for i in 0..5 {
            engine.commit_track(&embedding(124.0, 0.5), 99, 200 + i, 1.0);
        }
        assert_eq!(engine.session_artist_count(77), 4, "decayed by one");
    }

    #[test]
    fn dwell_behavior_drives_exploration_entropy() {
        let mut engine = ContinuumEngine::new(3);
        let base = engine.exploration_entropy();
        // Early skips spike entropy toward 1.0.
        for i in 0..3 {
            engine.commit_track(&embedding(124.0, 0.5), 5, 200 + i, 0.1);
        }
        assert!(engine.exploration_entropy() > base);
        assert!(engine.exploration_entropy() <= 1.0);
        // Full listens decay it toward the 0.05 floor.
        for i in 0..12 {
            engine.commit_track(&embedding(124.0, 0.5), 5, 300 + i, 0.95);
        }
        assert!(engine.exploration_entropy() <= 0.2);
    }

    #[test]
    fn bpm_proximity_component_peaks_at_the_session_tempo() {
        let mut engine = ContinuumEngine::new(4);
        engine.commit_track(&embedding(124.0, 0.5), 1, 1, 1.0);
        let at = engine.evaluate_batch(&[ContinuumCandidate::new(embedding(124.0, 0.2), 9)])[0];
        let far = engine.evaluate_batch(&[ContinuumCandidate::new(embedding(180.0, 0.2), 9)])[0];
        assert!(at.bpm_score > far.bpm_score);
        assert!(at.bpm_score > 25.0, "on-tempo bpm_score {}", at.bpm_score);
        assert!(far.bpm_score < 5.0, "off-tempo bpm_score {}", far.bpm_score);
    }

    #[test]
    fn similarity_component_tracks_momentum_alignment() {
        let mut engine = ContinuumEngine::new(5);
        engine.commit_track(&embedding(124.0, 0.5), 1, 1, 1.0);
        let aligned = ContinuumCandidate::new(embedding(124.0, 0.5), 9);
        let opposed = {
            let mut v = embedding(124.0, -0.5);
            v[layout::BPM] = 124.0;
            ContinuumCandidate::new(v, 9)
        };
        let sa = engine.evaluate_batch(&[aligned, opposed]);
        assert!(sa[0].similarity > sa[1].similarity);
        assert!(
            sa[0].similarity > 0.9,
            "aligned similarity {}",
            sa[0].similarity
        );
    }

    #[test]
    fn zero_artist_hash_never_saties() {
        let mut engine = ContinuumEngine::new(6);
        for i in 0..10 {
            engine.commit_track(&embedding(124.0, 0.5), 0, 400 + i, 1.0);
        }
        assert!(engine.session_artist_count(0) == 0);
        let c = ContinuumCandidate::new(embedding(124.0, 0.1), 0);
        assert_eq!(engine.evaluate_batch(&[c])[0].satiation_penalty, 0.0);
    }
}
