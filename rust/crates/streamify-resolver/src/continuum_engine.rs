// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::collections::HashMap;
use std::panic::catch_unwind;

#[repr(C)]
pub struct ContinuumState {
    // Kinetic Momentum: rolling average of 128-dim acoustic embedding vectors
    pub momentum_vector: [f32; 128],
    pub current_bpm: f32,
    pub current_camelot: i8,

    // Event-based tracking (Zero wall-clock reliance)
    pub session_artist_counts: HashMap<u64, u8>,
    pub session_track_hashes: [u64; 50],
    pub session_track_idx: usize,

    // Exploration Entropy (0.0 to 1.0)
    pub exploration_entropy: f32,
}

impl Default for ContinuumState {
    fn default() -> Self {
        Self {
            momentum_vector: [0.0; 128],
            current_bpm: 120.0,
            current_camelot: 8,
            session_artist_counts: HashMap::with_capacity(64),
            session_track_hashes: [0; 50],
            session_track_idx: 0,
            exploration_entropy: 0.15,
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn init_continuum_state() -> *mut ContinuumState {
    let state = Box::new(ContinuumState::default());
    Box::into_raw(state)
}

#[no_mangle]
pub unsafe extern "C" fn free_continuum_state(state_ptr: *mut ContinuumState) {
    if !state_ptr.is_null() {
        let _ = Box::from_raw(state_ptr);
    }
}

/// Evaluates a batch of candidate tracks in pure SIMD/vector math, returning contextual scores.
#[no_mangle]
pub unsafe extern "C" fn evaluate_continuum_batch(
    state_ptr: *mut ContinuumState,
    candidates_ptr: *const f32, // Flattened [N * 128] float array
    candidate_count: usize,
    out_scores_ptr: *mut f32, // Output [N] float array
) -> i32 {
    let result = catch_unwind(|| {
        if state_ptr.is_null()
            || candidates_ptr.is_null()
            || out_scores_ptr.is_null()
            || candidate_count == 0
        {
            return -1;
        }

        let state = &mut *state_ptr;
        let candidates = std::slice::from_raw_parts(candidates_ptr, candidate_count * 128);
        let out_scores = std::slice::from_raw_parts_mut(out_scores_ptr, candidate_count);

        for i in 0..candidate_count {
            let candidate_vec = &candidates[i * 128..(i + 1) * 128];

            // 1. Vector Cosine Similarity against Momentum Vector
            let mut dot_product = 0.0f32;
            let mut norm_c = 0.0f32;
            let mut norm_m = 0.0f32;

            #[allow(clippy::needless_range_loop)] // parallel vector lanes
            for j in 0..128 {
                let cv = candidate_vec[j];
                let mv = state.momentum_vector[j];
                dot_product += cv * mv;
                norm_c += cv * cv;
                norm_m += mv * mv;
            }

            let denom = (norm_c.sqrt() * norm_m.sqrt()).max(1e-6);
            let sim = (dot_product / denom).clamp(-1.0, 1.0);

            // 2. BPM Proximity Score
            let cand_bpm = candidate_vec[0].abs().clamp(40.0, 240.0);
            let bpm_diff = (cand_bpm - state.current_bpm).abs();
            let bpm_score = 30.0 * (-((bpm_diff * bpm_diff) / (2.0 * 25.0 * 25.0))).exp();

            // 3. Event-based Artist Satiation Penalty
            let artist_hash = (candidate_vec[1].abs() * 1_000_000.0) as u64;
            let artist_count = *state.session_artist_counts.get(&artist_hash).unwrap_or(&0) as f32;
            let satiation_penalty = (artist_count / 5.0).min(1.0) * 50.0;

            // 4. Base Contextual Score
            let mut score = (sim * 60.0) + bpm_score - satiation_penalty;

            // 5. Exploration Entropy Boost (Multi-Armed Bandit ε-Greedy)
            if state.exploration_entropy > 0.2 {
                // Outlier exploration reward
                let pseudo_rand = (i as f32 * 17.31 + state.session_track_idx as f32 * 7.13)
                    .sin()
                    .abs();
                if pseudo_rand < state.exploration_entropy {
                    score += 25.0;
                }
            }

            out_scores[i] = score;
        }

        0
    });

    result.unwrap_or(-2)
}

/// Commits the played track to the continuum engine, updating momentum and entropy.
#[no_mangle]
pub unsafe extern "C" fn commit_track_to_continuum(
    state_ptr: *mut ContinuumState,
    track_vector_ptr: *const f32,
    artist_hash: u64,
    track_hash: u64,
    dwell_percentage: f32, // 0.0 to 1.0
) -> i32 {
    let result = catch_unwind(|| {
        if state_ptr.is_null() || track_vector_ptr.is_null() {
            return -1;
        }

        let state = &mut *state_ptr;
        let track_vec = std::slice::from_raw_parts(track_vector_ptr, 128);

        // 1. Exponential Moving Average Update of Kinetic Momentum
        // Alpha = 0.3 (30% new track, 70% session history)
        let alpha = 0.3f32;
        let one_minus_alpha = 0.7f32;

        #[allow(clippy::needless_range_loop)] // EMA over parallel lanes
        for j in 0..128 {
            state.momentum_vector[j] =
                (state.momentum_vector[j] * one_minus_alpha) + (track_vec[j] * alpha);
        }

        if track_vec[0] > 0.0 {
            state.current_bpm = state.current_bpm * 0.7 + track_vec[0] * 0.3;
        }

        // 2. Update Event-Based Satiation
        if artist_hash != 0 {
            *state.session_artist_counts.entry(artist_hash).or_insert(0) += 1;
        }

        // Natural session decay of older artist counts
        if state.session_track_idx.is_multiple_of(10) {
            for count in state.session_artist_counts.values_mut() {
                if *count > 0 {
                    *count -= 1;
                }
            }
        }

        // 3. Update Exploration Entropy
        if dwell_percentage < 0.2 {
            // Early skip: user is actively searching, spike entropy
            state.exploration_entropy = (state.exploration_entropy + 0.35).min(1.0);
        } else if dwell_percentage > 0.8 {
            // Full listen: vibe locked in, decay entropy
            state.exploration_entropy = (state.exploration_entropy - 0.1).max(0.05);
        }

        // 4. Ring Buffer Tracking
        if track_hash != 0 {
            state.session_track_hashes[state.session_track_idx] = track_hash;
            state.session_track_idx = (state.session_track_idx + 1) % 50;
        }

        0
    });

    result.unwrap_or(-2)
}
