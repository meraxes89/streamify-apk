use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum BrainState {
    Flow = 0,       // Normal playback (>80% listened): 45% Spotify : 40% YouTube : 15% Liked
    Distress = 1,   // Fast skip (<10s): 10% Spotify : 0% YouTube : 90% Liked (Emergency Reset)
    Hypnosis = 2,   // Passive dwell (3+ consecutive songs): 35% Spotify : 55% YouTube : 10% Liked
    Impatience = 3, // Scrubbing / Fast-Forward: 50% Spotify : 40% YouTube : 10% Liked (Energy >= 0.75)
    Obsession = 4, // Loop / Repeat track: 70% Spotify : 20% YouTube : 10% Liked (Cosine Sim >= 0.90)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NeuroCandidate {
    pub id: String,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub source: u8, // 0 = Liked, 1 = Spotify, 2 = YouTube
    #[serde(default = "default_bpm")]
    pub bpm: f32,
    #[serde(default = "default_key")]
    pub key: String,
    #[serde(default = "default_metric")]
    pub energy: f32,
    #[serde(default = "default_metric")]
    pub valence: f32,
    #[serde(default = "default_metric")]
    pub acoustic_sim: f32,
    #[serde(default)]
    pub user_affinity: f32,
    #[serde(default)]
    pub last_played_sec: u64,
    #[serde(default)]
    pub score: f32,
}

fn default_bpm() -> f32 {
    120.0
}
fn default_key() -> String {
    "8A".to_string()
}
fn default_metric() -> f32 {
    0.5
}

pub struct NeuroQueueEngine;

impl NeuroQueueEngine {
    const TAU_SATIATION_SEC: f64 = 12600.0; // 3.5 Hours exponential decay

    /// Generates a psychological, neuro-acoustically optimized queue based on active brain state
    pub fn generate_neuro_queue(
        seed: &NeuroCandidate,
        candidates: &[NeuroCandidate],
        brain_state: BrainState,
        now_sec: u64,
        hour_of_day: u32,
        target_count: usize,
    ) -> Vec<NeuroCandidate> {
        let (w_sp, w_yt, w_lk) = match brain_state {
            BrainState::Flow => (0.45, 0.40, 0.15),
            BrainState::Distress => (0.10, 0.00, 0.90),
            BrainState::Hypnosis => (0.35, 0.55, 0.10),
            BrainState::Impatience => (0.50, 0.40, 0.10),
            BrainState::Obsession => (0.70, 0.20, 0.10),
        };

        let seed_bpm = if seed.bpm > 0.0 { seed.bpm } else { 120.0 };

        // 1. Score and filter candidates
        let mut scored_list: Vec<(f32, NeuroCandidate)> = Vec::with_capacity(candidates.len());
        for c in candidates {
            if c.id == seed.id || c.title.trim().is_empty() || c.artist.trim().is_empty() {
                continue;
            }

            // Brain state hard constraints
            if brain_state == BrainState::Impatience && c.energy < 0.65 {
                continue;
            }
            if brain_state == BrainState::Obsession && c.acoustic_sim < 0.80 {
                continue;
            }

            // A. Base Tri-Engine Weights
            let v_sp = if c.source == 1 {
                c.acoustic_sim
            } else {
                c.acoustic_sim * 0.8
            };
            let n_yt = if c.source == 2 { 0.90 } else { 0.50 };
            let a_lk = if c.source == 0 {
                c.user_affinity.max(0.7)
            } else {
                c.user_affinity * 0.4
            };

            let base_score = (w_sp * v_sp) + (w_yt * n_yt) + (w_lk * a_lk);

            // B. Exponential Satiation Decay Penalty (tau = 3.5 hours)
            let satiation_penalty = if c.last_played_sec > 0 && now_sec >= c.last_played_sec {
                let dt = (now_sec - c.last_played_sec) as f64;
                if dt < 86400.0 {
                    ((-dt / Self::TAU_SATIATION_SEC).exp() * 0.35) as f32
                } else {
                    0.0
                }
            } else {
                0.0
            };

            // C. Camelot Harmonic Key Adjacency Bonus
            let harmonic_bonus = Self::camelot_harmonic_bonus(&seed.key, &c.key);

            // D. Circadian Dayparting Resonance
            let circadian_bonus = Self::compute_circadian_bonus(c, seed_bpm, hour_of_day);

            // E. Anti-Jarring BPM Envelope
            let candidate_bpm = if c.bpm > 0.0 { c.bpm } else { 120.0 };
            let bpm_ratio = candidate_bpm / seed_bpm;
            let tempo_drift_penalty = if bpm_ratio < 0.92 || bpm_ratio > 1.08 {
                0.15 // Penalize >8% sudden tempo jumps without stepped intermediate
            } else {
                0.0
            };

            let mut final_score = base_score - satiation_penalty + harmonic_bonus + circadian_bonus
                - tempo_drift_penalty;
            if final_score < 0.0 {
                final_score = 0.01;
            }

            let mut out_candidate = c.clone();
            out_candidate.score = final_score;
            scored_list.push((final_score, out_candidate));
        }

        // Sort descending by score
        scored_list.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

        // 2. The 5-Track Cinematic Micro-Arc Allocator
        let mut queue: Vec<NeuroCandidate> = Vec::with_capacity(target_count);
        let mut used_ids: HashSet<String> = HashSet::with_capacity(target_count + 8);
        used_ids.insert(seed.id.clone());

        let mut liked_pool: Vec<NeuroCandidate> = scored_list
            .iter()
            .filter(|(_, c)| c.source == 0)
            .map(|(_, c)| c.clone())
            .collect();
        let mut spotify_pool: Vec<NeuroCandidate> = scored_list
            .iter()
            .filter(|(_, c)| c.source == 1)
            .map(|(_, c)| c.clone())
            .collect();
        let mut yt_pool: Vec<NeuroCandidate> = scored_list
            .iter()
            .filter(|(_, c)| c.source == 2)
            .map(|(_, c)| c.clone())
            .collect();
        let mut general_pool: Vec<NeuroCandidate> =
            scored_list.into_iter().map(|(_, c)| c).collect();

        let count = target_count.max(1);
        for i in 0..count {
            let slot = (i % 5) + 1;
            let picked = if brain_state == BrainState::Distress {
                // Emergency Reset: Deploy 90% Liked Anchors
                Self::pop_first_available(&mut liked_pool, &mut used_ids)
                    .or_else(|| Self::pop_first_available(&mut spotify_pool, &mut used_ids))
                    .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
            } else {
                match slot {
                    1 => {
                        // Slot 1: The Anchor (Neurochemical grounding)
                        Self::pop_first_available(&mut liked_pool, &mut used_ids)
                            .or_else(|| Self::pop_first_available(&mut spotify_pool, &mut used_ids))
                            .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
                    }
                    2 => {
                        // Slot 2: The Bridge (Spotify Vibe match)
                        Self::pop_first_available(&mut spotify_pool, &mut used_ids)
                            .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
                    }
                    3 => {
                        // Slot 3: The Novelty Peak (YouTube discovery / deep gem)
                        Self::pop_first_available(&mut yt_pool, &mut used_ids)
                            .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
                    }
                    4 => {
                        // Slot 4: The Stabilizer (Cohesive Spotify Vibe)
                        Self::pop_first_available(&mut spotify_pool, &mut used_ids)
                            .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
                    }
                    5 => {
                        // Slot 5: The Dopamine Shot (Nostalgia / Liked Anchor)
                        Self::pop_first_available(&mut liked_pool, &mut used_ids)
                            .or_else(|| Self::pop_first_available(&mut spotify_pool, &mut used_ids))
                            .or_else(|| Self::pop_first_available(&mut general_pool, &mut used_ids))
                    }
                    _ => Self::pop_first_available(&mut general_pool, &mut used_ids),
                }
            };

            if let Some(track) = picked {
                queue.push(track);
            }
        }

        queue
    }

    fn pop_first_available(
        pool: &mut Vec<NeuroCandidate>,
        used: &mut HashSet<String>,
    ) -> Option<NeuroCandidate> {
        let pos = pool.iter().position(|c| !used.contains(&c.id))?;
        let item = pool.remove(pos);
        used.insert(item.id.clone());
        Some(item)
    }

    fn parse_camelot(key_str: &str) -> (u8, char) {
        let clean = key_str.trim().to_uppercase();
        let num: u8 = clean
            .chars()
            .filter(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse()
            .unwrap_or(8);
        let letter: char = clean
            .chars()
            .find(|c| c.is_ascii_alphabetic())
            .unwrap_or('A');
        (num.clamp(1, 12), letter)
    }

    fn camelot_harmonic_bonus(key1: &str, key2: &str) -> f32 {
        let (n1, l1) = Self::parse_camelot(key1);
        let (n2, l2) = Self::parse_camelot(key2);

        if n1 == n2 && l1 == l2 {
            0.10 // Exact harmonic match
        } else if n1 == n2 && l1 != l2 {
            0.08 // Relative Major / Minor flip
        } else {
            let diff = (n1 as i16 - n2 as i16).abs();
            if diff == 1 || diff == 11 {
                if l1 == l2 {
                    0.06 // Adjacent key on the Camelot wheel (e.g. 8A -> 7A or 9A)
                } else {
                    0.03
                }
            } else {
                0.0
            }
        }
    }

    fn compute_circadian_bonus(c: &NeuroCandidate, seed_bpm: f32, hour: u32) -> f32 {
        if (6..=10).contains(&hour) {
            // Morning Boost: Ascending tempo (+3% BPM bias) & bright acoustic profiles
            if c.energy >= 0.60 && c.bpm >= seed_bpm {
                0.06
            } else {
                0.0
            }
        } else if (14..=18).contains(&hour) {
            // Afternoon Focus: Low variance (<=4% BPM drift)
            let diff = (c.bpm - seed_bpm).abs();
            if diff <= 5.0 {
                0.05
            } else {
                0.0
            }
        } else if hour >= 22 || hour <= 4 {
            // Late Night Dwell: Suppress bright highs, prioritize warmer low-end textures
            if c.energy <= 0.50 {
                0.07
            } else {
                0.0
            }
        } else {
            0.0
        }
    }
}
