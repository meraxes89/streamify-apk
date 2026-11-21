//! neuro_queue.rs — the psychological neuro-acoustic queue engine
//! (WS-D D3/D4/D6/D7: first-class taste-engine scoring with explainable
//! reason strings, unified circadian dayparting and score-time taste
//! exclusions).
//!
//! The queue pipeline (unchanged since its introduction, now unit-
//! tested and surfaced through the UniFFI `TasteEngine` lane):
//!
//! 1. **Tri-engine source weighting** per [`BrainState`] (Spotify /
//!    YouTube / Liked blend);
//! 2. **Satiation decay** — a 3.5 h exponential (τ = 12 600 s) penalty
//!    on recently played tracks;
//! 3. **Camelot harmonic adjacency** on the Open Key wheel;
//! 4. **Circadian dayparting** — a 4-slot [`CircadianProfile`]
//!    (Morning / Afternoon / Evening / Late Night, the same bucket
//!    edges as the Android `DaylistTimeBuckets`) replacing the old
//!    hardcoded hour ranges (D6);
//! 5. **Anti-jarring BPM envelope** (±8 % drift gate);
//! 6. **The 5-slot cinematic micro-arc allocator** (Anchor → Bridge →
//!    Novelty Peak → Stabilizer → Dopamine Shot).
//!
//! Every scored row now carries a [`NeuroScoreBreakdown`] (the feature
//! attributions) and a human-readable [`NeuroQueueRow::reason`]
//! composed from the top attributions (D4) — deterministic string
//! composition, snapshot-tested, zero network.

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

use streamify_core::acoustic_embedding::layout;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum BrainState {
    Flow = 0,       // Normal playback (>80% listened): 45% Spotify : 40% YouTube : 15% Liked
    Distress = 1,   // Fast skip (<10s): 10% Spotify : 0% YouTube : 90% Liked (Emergency Reset)
    Hypnosis = 2,   // Passive dwell (3+ consecutive songs): 35% Spotify : 55% YouTube : 10% Liked
    Impatience = 3, // Scrubbing / Fast-Forward: 50% Spotify : 40% YouTube : 10% Liked (Energy >= 0.75)
    Obsession = 4, // Loop / Repeat track: 70% Spotify : 20% YouTube : 10% Liked (Cosine sim >= 0.90)
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
    /// D7: tracks the user flagged "Exclude from taste profile" are
    /// filtered at SCORE time — they never pollute radio, daylist or
    /// blend outputs, and their play events never feed the profile.
    #[serde(default)]
    pub excluded_from_taste: bool,
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

/// One of the four circadian daypart slots — the SAME bucket edges as
/// the Android `DaylistTimeBuckets` (D6's "unify" contract: one
/// dayparting vocabulary across the engine lane and the shelf UI).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DaypartSlot {
    /// 06:00–10:59 — ascending tempo, bright profiles.
    Morning,
    /// 11:00–16:59 — workout energy, high tempo.
    Afternoon,
    /// 17:00–21:59 — chill unwind, sunset moods.
    Evening,
    /// 22:00–05:59 — ambient drift, low energy.
    LateNight,
}

impl DaypartSlot {
    /// The slot containing `hour` (0..=23); Late Night wraps midnight.
    pub fn from_hour(hour: u32) -> Self {
        match hour {
            6..=10 => DaypartSlot::Morning,
            11..=16 => DaypartSlot::Afternoon,
            17..=21 => DaypartSlot::Evening,
            _ => DaypartSlot::LateNight,
        }
    }

    /// Human label used inside reason strings.
    pub fn label(self) -> &'static str {
        match self {
            DaypartSlot::Morning => "Morning",
            DaypartSlot::Afternoon => "Afternoon",
            DaypartSlot::Evening => "Evening",
            DaypartSlot::LateNight => "Late Night",
        }
    }
}

/// The per-slot taste vector a circadian profile carries: what the
/// listener historically gravitates toward in this daypart. Built from
/// LOCAL history (D6: no telemetry upload — the shell aggregates its
/// own play log and hands the profile over the UDL).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct DaypartVector {
    /// Center tempo this slot gravitates to (BPM).
    pub bpm_target: f32,
    /// Center energy this slot gravitates to (0..=1).
    pub energy_target: f32,
    /// Center brightness / valence this slot gravitates to (0..=1).
    pub brightness_target: f32,
    /// How strongly the slot rewards never-recently-played tracks
    /// (0..=1; 0 = satiation blind, 1 = freshness-forward).
    pub freshness_bias: f32,
}

impl Default for DaypartVector {
    fn default() -> Self {
        DaypartVector {
            bpm_target: 120.0,
            energy_target: 0.5,
            brightness_target: 0.5,
            freshness_bias: 0.5,
        }
    }
}

/// The 4-slot circadian profile driving daypart scoring (D6).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct CircadianProfile {
    pub morning: DaypartVector,
    pub afternoon: DaypartVector,
    pub evening: DaypartVector,
    pub late_night: DaypartVector,
}

impl CircadianProfile {
    /// The classic profile: the historic hardcoded hour-range behavior
    /// restated on the unified 4-slot edges (morning ascending tempo,
    /// afternoon focus, evening unwind, late-night low energy).
    pub fn classic() -> Self {
        CircadianProfile {
            morning: DaypartVector {
                bpm_target: 126.0,
                energy_target: 0.62,
                brightness_target: 0.6,
                freshness_bias: 0.45,
            },
            afternoon: DaypartVector {
                bpm_target: 124.0,
                energy_target: 0.75,
                brightness_target: 0.62,
                freshness_bias: 0.5,
            },
            evening: DaypartVector {
                bpm_target: 116.0,
                energy_target: 0.48,
                brightness_target: 0.5,
                freshness_bias: 0.5,
            },
            late_night: DaypartVector {
                bpm_target: 100.0,
                energy_target: 0.35,
                brightness_target: 0.38,
                freshness_bias: 0.55,
            },
        }
    }

    /// The slot's taste vector for `hour`.
    pub fn active_vector(&self, hour: u32) -> &DaypartVector {
        match DaypartSlot::from_hour(hour) {
            DaypartSlot::Morning => &self.morning,
            DaypartSlot::Afternoon => &self.afternoon,
            DaypartSlot::Evening => &self.evening,
            DaypartSlot::LateNight => &self.late_night,
        }
    }

    /// The active slot for `hour`.
    pub fn active_slot(&self, hour: u32) -> DaypartSlot {
        DaypartSlot::from_hour(hour)
    }
}

impl Default for CircadianProfile {
    fn default() -> Self {
        Self::classic()
    }
}

/// The feature attributions behind one scored row — the explainability
/// currency (D4): reason strings are composed FROM these, and the UI
/// badge lane can render them directly.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct NeuroScoreBreakdown {
    /// Tri-engine source blend (Spotify/YouTube/Liked weighted by
    /// brain state).
    pub base: f32,
    /// 3.5 h exponential satiation penalty (≥ 0).
    pub satiation_penalty: f32,
    /// Camelot harmonic adjacency bonus (≥ 0).
    pub harmonic_bonus: f32,
    /// Circadian daypart fit bonus (≥ 0).
    pub circadian_bonus: f32,
    /// Anti-jarring tempo drift penalty (≥ 0).
    pub tempo_drift_penalty: f32,
    /// |candidate BPM − reference BPM| (seed in radio mode, the slot's
    /// target in daylist mode).
    pub bpm_distance: f32,
    /// Camelot wheel steps between the keys (0 = same, 1 = adjacent,
    /// 7 = opposite; negative = parse failure fallback).
    pub camelot_distance: i32,
    /// Freshness ∈ [0, 1]: 1 = never played, decaying with the 3.5 h
    /// τ over recent plays.
    pub freshness: f32,
}

impl Default for NeuroScoreBreakdown {
    fn default() -> Self {
        NeuroScoreBreakdown {
            base: 0.0,
            satiation_penalty: 0.0,
            harmonic_bonus: 0.0,
            circadian_bonus: 0.0,
            tempo_drift_penalty: 0.0,
            bpm_distance: 0.0,
            camelot_distance: 0,
            freshness: 1.0,
        }
    }
}

/// One recommended row: the candidate, its score, the attributions and
/// the human-readable reason (D4) — the UDL `TasteQueueRow` currency.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NeuroQueueRow {
    pub candidate: NeuroCandidate,
    pub score: f32,
    pub breakdown: NeuroScoreBreakdown,
    pub reason: String,
}

pub struct NeuroQueueEngine;

impl NeuroQueueEngine {
    const TAU_SATIATION_SEC: f64 = 12600.0; // 3.5 Hours exponential decay

    /// The classic entry point (preserved for the JNI-era contract
    /// tests): delegates to [`Self::generate_neuro_queue_scored`] with
    /// the classic circadian profile and strips the explainability
    /// envelope.
    pub fn generate_neuro_queue(
        seed: &NeuroCandidate,
        candidates: &[NeuroCandidate],
        brain_state: BrainState,
        now_sec: u64,
        hour_of_day: u32,
        target_count: usize,
    ) -> Vec<NeuroCandidate> {
        Self::generate_neuro_queue_scored(
            seed,
            candidates,
            brain_state,
            now_sec,
            hour_of_day,
            target_count,
            &CircadianProfile::classic(),
        )
        .into_iter()
        .map(|row| row.candidate)
        .collect()
    }

    /// The radio queue pipeline with full explainability (D3/D4/D6/D7):
    /// every row carries its score breakdown and a composed reason.
    ///
    /// D7: candidates flagged `excluded_from_taste` are dropped at
    /// score time — before any component is computed — so an excluded
    /// track can never surface in radio output nor influence the arc.
    pub fn generate_neuro_queue_scored(
        seed: &NeuroCandidate,
        candidates: &[NeuroCandidate],
        brain_state: BrainState,
        now_sec: u64,
        hour_of_day: u32,
        target_count: usize,
        profile: &CircadianProfile,
    ) -> Vec<NeuroQueueRow> {
        let (w_sp, w_yt, w_lk) = match brain_state {
            BrainState::Flow => (0.45, 0.40, 0.15),
            BrainState::Distress => (0.10, 0.00, 0.90),
            BrainState::Hypnosis => (0.35, 0.55, 0.10),
            BrainState::Impatience => (0.50, 0.40, 0.10),
            BrainState::Obsession => (0.70, 0.20, 0.10),
        };

        let seed_bpm = if seed.bpm > 0.0 { seed.bpm } else { 120.0 };
        let slot = profile.active_slot(hour_of_day);
        let daypart = profile.active_vector(hour_of_day);

        // 1. Score and filter candidates
        let mut scored_list: Vec<(f32, NeuroQueueRow)> = Vec::with_capacity(candidates.len());
        for c in candidates {
            if c.id == seed.id || c.title.trim().is_empty() || c.artist.trim().is_empty() {
                continue;
            }

            // D7: taste-excluded tracks never reach the scorer.
            if c.excluded_from_taste {
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
            let freshness = (1.0 - satiation_penalty / 0.35).clamp(0.0, 1.0);

            // C. Camelot Harmonic Key Adjacency Bonus
            let camelot_distance = Self::camelot_distance(&seed.key, &c.key);
            let harmonic_bonus = Self::camelot_harmonic_bonus(&seed.key, &c.key);

            // D. Circadian Dayparting Resonance (D6: profile-driven,
            // the unified 4-slot vocabulary)
            let circadian_bonus = Self::daypart_fit_bonus(c, seed_bpm, daypart);

            // E. Anti-Jarring BPM Envelope
            let candidate_bpm = if c.bpm > 0.0 { c.bpm } else { 120.0 };
            let bpm_distance = (candidate_bpm - seed_bpm).abs();
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

            let breakdown = NeuroScoreBreakdown {
                base: base_score,
                satiation_penalty,
                harmonic_bonus,
                circadian_bonus,
                tempo_drift_penalty,
                bpm_distance,
                camelot_distance,
                freshness,
            };
            let reason = compose_radio_reason(c, seed, &breakdown, slot, daypart);

            scored_list.push((
                final_score,
                NeuroQueueRow {
                    candidate: c.clone(),
                    score: final_score,
                    breakdown,
                    reason,
                },
            ));
        }

        // Sort descending by score
        scored_list.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

        // 2. The 5-Track Cinematic Micro-Arc Allocator
        let mut queue: Vec<NeuroQueueRow> = Vec::with_capacity(target_count);
        let mut used_ids: HashSet<String> = HashSet::with_capacity(target_count + 8);
        used_ids.insert(seed.id.clone());

        let mut liked_pool: Vec<NeuroQueueRow> = scored_list
            .iter()
            .filter(|(_, r)| r.candidate.source == 0)
            .map(|(_, r)| r.clone())
            .collect();
        let mut spotify_pool: Vec<NeuroQueueRow> = scored_list
            .iter()
            .filter(|(_, r)| r.candidate.source == 1)
            .map(|(_, r)| r.clone())
            .collect();
        let mut yt_pool: Vec<NeuroQueueRow> = scored_list
            .iter()
            .filter(|(_, r)| r.candidate.source == 2)
            .map(|(_, r)| r.clone())
            .collect();
        let mut general_pool: Vec<NeuroQueueRow> =
            scored_list.into_iter().map(|(_, r)| r).collect();

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

    /// The Daylist v2 lane (D6): a seedless, circadian-driven local
    /// ranking for the home shelf. Scoring fits each candidate to the
    /// ACTIVE daypart vector (tempo / energy / brightness targets +
    /// freshness bias), the micro-arc allocator shapes the output, and
    /// every row carries its reason. Entirely local: `now_sec` and the
    /// profile come from the shell's own clock and history.
    pub fn generate_daylist_scored(
        candidates: &[NeuroCandidate],
        now_sec: u64,
        hour_of_day: u32,
        target_count: usize,
        profile: &CircadianProfile,
    ) -> Vec<NeuroQueueRow> {
        let slot = profile.active_slot(hour_of_day);
        let daypart = profile.active_vector(hour_of_day);

        let mut scored_list: Vec<(f32, NeuroQueueRow)> = Vec::with_capacity(candidates.len());
        for c in candidates {
            if c.title.trim().is_empty() || c.artist.trim().is_empty() {
                continue;
            }
            // D7: exclusions bind the daylist lane identically.
            if c.excluded_from_taste {
                continue;
            }

            // Affinity-forward base (no seed acoustic similarity).
            let base = match c.source {
                0 => 0.35 + 0.4 * c.user_affinity.clamp(0.0, 1.0),
                1 => 0.30 + 0.3 * c.user_affinity.clamp(0.0, 1.0),
                _ => 0.25 + 0.2 * c.user_affinity.clamp(0.0, 1.0),
            };

            // Daypart fit: Gaussian-ish falloff from the slot's targets.
            let candidate_bpm = if c.bpm > 0.0 { c.bpm } else { 120.0 };
            let bpm_distance = (candidate_bpm - daypart.bpm_target).abs();
            let bpm_fit = (-((bpm_distance / 25.0) * (bpm_distance / 25.0))).exp();
            let energy_fit = 1.0 - (c.energy - daypart.energy_target).abs().clamp(0.0, 1.0);
            let brightness_fit = 1.0
                - (c.valence - daypart.brightness_target)
                    .abs()
                    .clamp(0.0, 1.0);
            let circadian_bonus = 0.18 * bpm_fit + 0.10 * energy_fit + 0.06 * brightness_fit;

            // Freshness with the slot's bias.
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
            let freshness = (1.0 - satiation_penalty / 0.35).clamp(0.0, 1.0);
            let freshness_term = daypart.freshness_bias * freshness * 0.15;

            let mut final_score = base + circadian_bonus + freshness_term - satiation_penalty;
            if final_score < 0.0 {
                final_score = 0.01;
            }

            let breakdown = NeuroScoreBreakdown {
                base,
                satiation_penalty,
                harmonic_bonus: 0.0,
                circadian_bonus,
                tempo_drift_penalty: 0.0,
                bpm_distance,
                camelot_distance: -1, // no seed key in daylist mode
                freshness,
            };
            let reason = compose_daylist_reason(c, &breakdown, slot, daypart);

            scored_list.push((
                final_score,
                NeuroQueueRow {
                    candidate: c.clone(),
                    score: final_score,
                    breakdown,
                    reason,
                },
            ));
        }

        scored_list.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        let mut queue: Vec<NeuroQueueRow> = Vec::with_capacity(target_count);
        let mut used_ids: HashSet<String> = HashSet::with_capacity(target_count + 8);
        let mut general_pool: Vec<NeuroQueueRow> =
            scored_list.into_iter().map(|(_, r)| r).collect();
        let count = target_count.max(1);
        for _ in 0..count {
            if let Some(row) = Self::pop_first_available(&mut general_pool, &mut used_ids) {
                queue.push(row);
            }
        }
        queue
    }

    fn pop_first_available(
        pool: &mut Vec<NeuroQueueRow>,
        used: &mut HashSet<String>,
    ) -> Option<NeuroQueueRow> {
        let pos = pool.iter().position(|r| !used.contains(&r.candidate.id))?;
        let item = pool.remove(pos);
        used.insert(item.candidate.id.clone());
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

    /// Camelot wheel distance in steps (0 = same key, 1 = adjacent,
    /// …, 6 = opposite side entry). The reason composer's harmonic
    /// vocabulary.
    pub fn camelot_distance(key1: &str, key2: &str) -> i32 {
        let (n1, l1) = Self::parse_camelot(key1);
        let (n2, l2) = Self::parse_camelot(key2);
        let ring = (n1 as i32 - n2 as i32)
            .abs()
            .min(12 - (n1 as i32 - n2 as i32).abs());
        let letter_flip = if l1 == l2 { 0 } else { 1 };
        ring + letter_flip
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

    /// D6: profile-driven daypart fit. The morning slot rewards
    /// at-or-above-target tempo and energy (ascending wake-up), the
    /// afternoon rewards tight tempo focus (workout flow), the evening
    /// and late-night slots reward at-or-below-target energy (unwind
    /// / drift) — all against the profile's target vectors instead of
    /// the old hardcoded hour ranges.
    fn daypart_fit_bonus(c: &NeuroCandidate, seed_bpm: f32, daypart: &DaypartVector) -> f32 {
        let bpm = if c.bpm > 0.0 { c.bpm } else { seed_bpm };
        let bpm_distance = (bpm - daypart.bpm_target).abs();
        let bpm_fit = (-((bpm_distance / 25.0) * (bpm_distance / 25.0))).exp();
        let energy_fit = 1.0 - (c.energy - daypart.energy_target).abs().clamp(0.0, 1.0);
        let brightness_fit = 1.0
            - (c.valence - daypart.brightness_target)
                .abs()
                .clamp(0.0, 1.0);
        0.10 * bpm_fit + 0.07 * energy_fit + 0.04 * brightness_fit
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// D4: the reason composers — deterministic, network-free, snapshot-tested.
// ─────────────────────────────────────────────────────────────────────────────

/// Round-half-up to an integer for stable display formatting (Rust's
/// float Display rounding is not a pinned contract; this is).
fn round0(x: f32) -> i32 {
    if x.is_finite() {
        (x + 0.5).floor() as i32
    } else {
        0
    }
}

/// Compose the radio reason: the top attributions of the row, ordered
/// by contribution weight, joined into one sentence.
pub fn compose_radio_reason(
    candidate: &NeuroCandidate,
    seed: &NeuroCandidate,
    breakdown: &NeuroScoreBreakdown,
    slot: DaypartSlot,
    daypart: &DaypartVector,
) -> String {
    let mut parts: Vec<String> = Vec::with_capacity(4);

    // Tempo proximity (absolute).
    if breakdown.bpm_distance <= 5.0 {
        parts.push(format!("locked to your {} BPM flow", round0(seed.bpm)));
    } else if breakdown.bpm_distance <= 15.0 {
        parts.push(format!("close to your {} BPM tempo", round0(seed.bpm)));
    }

    // Harmonic proximity on the Camelot wheel.
    match breakdown.camelot_distance {
        0 => parts.push(format!("same key as the seed ({})", seed.key)),
        1 => parts.push(format!("one Camelot step from {}", seed.key)),
        2 => parts.push(format!("harmonically near {}", seed.key)),
        _ => {}
    }

    // Track freshness.
    if breakdown.freshness >= 0.99 {
        parts.push("fresh for you".to_string());
    } else if breakdown.freshness < 0.35 {
        parts.push("recently played".to_string());
    }

    // Circadian daypart fit.
    if breakdown.circadian_bonus >= 0.15 {
        parts.push(format!("fits your {} slot", slot.label()));
    } else if breakdown.circadian_bonus >= 0.10 {
        parts.push(format!("a good {} match", slot.label()));
    }

    // Affinity.
    if candidate.user_affinity >= 0.8 && candidate.source == 0 {
        parts.push("one of your likes".to_string());
    }

    if parts.is_empty() {
        let bpm = if candidate.bpm > 0.0 {
            round0(candidate.bpm)
        } else {
            round0(daypart.bpm_target)
        };
        return format!("Matches your current vibe around {} BPM", bpm);
    }
    join_sentence(&parts)
}

/// Compose the daylist reason: daypart-target fit vocabulary.
pub fn compose_daylist_reason(
    _candidate: &NeuroCandidate,
    breakdown: &NeuroScoreBreakdown,
    slot: DaypartSlot,
    daypart: &DaypartVector,
) -> String {
    let mut parts: Vec<String> = Vec::with_capacity(3);
    if breakdown.bpm_distance <= 8.0 {
        parts.push(format!(
            "right on your {} tempo ({} BPM)",
            slot.label(),
            round0(daypart.bpm_target)
        ));
    } else if breakdown.bpm_distance <= 20.0 {
        parts.push(format!(
            "near your {} tempo ({} BPM)",
            slot.label(),
            round0(daypart.bpm_target)
        ));
    }
    if breakdown.freshness >= 0.99 {
        parts.push("fresh for you".to_string());
    }
    if breakdown.circadian_bonus >= 0.2 {
        parts.push(format!("strong {} energy match", slot.label()));
    }
    if parts.is_empty() {
        return format!("Rounds out your {} shelf", slot.label());
    }
    join_sentence(&parts)
}

/// Deterministic sentence join: "a, b, and c".
pub(crate) fn join_sentence(parts: &[String]) -> String {
    match parts.len() {
        0 => String::new(),
        1 => parts[0].clone(),
        2 => format!("{} and {}", parts[0], parts[1]),
        _ => {
            let head = parts[..parts.len() - 1].join(", ");
            format!("{} and {}", head, parts[parts.len() - 1])
        }
    }
}

// The D1 lane contract: the engine scores 128-dim embeddings whose BPM
// lane is `layout::BPM` — asserted at compile time so a future layout
// renumber cannot silently corrupt the continuum's tempo tracking.
const _: () = assert!(layout::BPM == 0);

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

    fn seed() -> NeuroCandidate {
        let mut s = candidate("seed", 1, 124.0, "8A", 0.7);
        s.acoustic_sim = 1.0;
        s.user_affinity = 0.9;
        s.last_played_sec = 1_000_000;
        s
    }

    /// D3: every brain state produces a queue and pins its tri-engine
    /// blend ordering.
    #[test]
    fn all_five_brain_states_generate_queues() {
        let states = [
            BrainState::Flow,
            BrainState::Distress,
            BrainState::Hypnosis,
            BrainState::Impatience,
            BrainState::Obsession,
        ];
        for state in states {
            let candidates: Vec<NeuroCandidate> = (0..30)
                .map(|i| {
                    let mut c = candidate(&format!("c{i}"), (i % 3) as u8, 120.0, "8A", 0.8);
                    c.acoustic_sim = 0.9;
                    c
                })
                .collect();
            let queue = NeuroQueueEngine::generate_neuro_queue_scored(
                &seed(),
                &candidates,
                state,
                1_000_500,
                9,
                5,
                &CircadianProfile::classic(),
            );
            assert!(!queue.is_empty(), "{state:?} produced an empty queue");
            assert!(queue.len() <= 5);
            for row in &queue {
                assert!(!row.reason.is_empty(), "{state:?} row lacks a reason");
                assert!(row.score > 0.0);
            }
        }
    }

    /// D3: Distress is the Emergency Reset — the queue must be Liked-
    /// heavy (source 0 first).
    #[test]
    fn distress_state_anchors_on_liked_tracks() {
        let mut candidates: Vec<NeuroCandidate> = (0..12)
            .map(|i| {
                let mut c = candidate(&format!("lk{i}"), 0, 124.0, "8A", 0.7);
                c.user_affinity = 0.9;
                c.acoustic_sim = 0.9;
                c
            })
            .collect();
        candidates.extend((0..12).map(|i| candidate(&format!("yt{i}"), 2, 124.0, "9A", 0.8)));
        let queue = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &candidates,
            BrainState::Distress,
            1_000_500,
            9,
            6,
            &CircadianProfile::classic(),
        );
        assert!(
            queue.iter().take(3).all(|r| r.candidate.source == 0),
            "Distress must lead with liked anchors"
        );
    }

    /// D3: the 5-slot micro-arc's slot 3 is the Novelty Peak (YouTube)
    /// whenever the pool has a viable YouTube candidate.
    #[test]
    fn micro_arc_slot_three_pulls_the_youtube_novelty_peak() {
        let mut candidates: Vec<NeuroCandidate> = (0..10)
            .map(|i| {
                let mut c = candidate(&format!("sp{i}"), 1, 124.0, "8A", 0.7);
                c.acoustic_sim = 0.9;
                c
            })
            .collect();
        candidates.extend((0..10).map(|i| candidate(&format!("yt{i}"), 2, 124.0, "9A", 0.8)));
        let queue = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &candidates,
            BrainState::Flow,
            1_000_500,
            9,
            5,
            &CircadianProfile::classic(),
        );
        assert_eq!(queue.len(), 5, "the full 5-slot arc");
        assert_eq!(
            queue[2].candidate.source, 2,
            "slot 3 = the Novelty Peak (YouTube)"
        );
        // Slots 2 and 4 are the Bridge / Stabilizer (Spotify lane).
        assert_eq!(queue[1].candidate.source, 1);
        assert_eq!(queue[3].candidate.source, 1);
    }

    /// D3: the 3.5 h satiation decay curve — the penalty halves toward
    /// zero along the documented τ, and a track played one full day
    /// ago is fully rested.
    #[test]
    fn satiation_decay_follows_the_three_and_a_half_hour_tau() {
        let now = 2_000_000u64;
        let tau = 12_600.0f64;
        // The fresh reference is IDENTICAL to the played fixture
        // except `last_played_sec` — apples to apples.
        let mut fresh = candidate("fresh", 1, 124.0, "8A", 0.7);
        fresh.acoustic_sim = 0.9;
        let reference = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[fresh],
            BrainState::Flow,
            now,
            9,
            1,
            &CircadianProfile::classic(),
        )[0]
        .clone();

        for (label, dt, expect_decayed) in [
            ("t=0", 0.0, true),
            ("half-tau", tau * 0.5, true),
            ("tau", tau, true),
            ("2tau", tau * 2.0, true),
            ("24h", 86_400.0, false),
        ] {
            let mut c = candidate("played", 1, 124.0, "8A", 0.7);
            c.acoustic_sim = 0.9;
            c.last_played_sec = now - dt as u64;
            let row = &NeuroQueueEngine::generate_neuro_queue_scored(
                &seed(),
                &[c],
                BrainState::Flow,
                now,
                9,
                1,
                &CircadianProfile::classic(),
            )[0];
            if expect_decayed {
                assert!(
                    row.breakdown.satiation_penalty > 0.0,
                    "{label}: penalty must be positive"
                );
                assert!(
                    row.score < reference.score,
                    "{label}: decayed {} must score below fresh {}",
                    row.score,
                    reference.score
                );
            } else {
                assert_eq!(
                    row.breakdown.satiation_penalty, 0.0,
                    "{label}: fully rested past 24 h"
                );
            }
        }

        // The exact curve: penalty(dt) = 0.35·exp(-dt/τ).
        let mut c = candidate("played", 1, 124.0, "8A", 0.7);
        c.acoustic_sim = 0.9;
        c.last_played_sec = now - 3600;
        let row = &NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[c],
            BrainState::Flow,
            now,
            9,
            1,
            &CircadianProfile::classic(),
        )[0];
        let expected = 0.35 * (-(3600.0 / tau)).exp() as f32;
        assert!(
            (row.breakdown.satiation_penalty - expected).abs() < 1e-4,
            "penalty {} vs exact {}",
            row.breakdown.satiation_penalty,
            expected
        );
    }

    /// D6: the unified 4-slot dayparting — the SAME bucket edges as
    /// the Android DaylistTimeBuckets.
    #[test]
    fn daypart_slots_match_the_daylist_bucket_edges() {
        let cases = [
            (5, DaypartSlot::LateNight),
            (6, DaypartSlot::Morning),
            (10, DaypartSlot::Morning),
            (11, DaypartSlot::Afternoon),
            (16, DaypartSlot::Afternoon),
            (17, DaypartSlot::Evening),
            (21, DaypartSlot::Evening),
            (22, DaypartSlot::LateNight),
            (23, DaypartSlot::LateNight),
            (0, DaypartSlot::LateNight),
        ];
        for (hour, expect) in cases {
            assert_eq!(DaypartSlot::from_hour(hour), expect, "hour {hour}");
        }
    }

    /// D6 (golden): the daylist lane pins morning-vs-night rankings
    /// for a fixture profile — the AC's exact golden test.
    #[test]
    fn daylist_pins_morning_versus_night_rankings() {
        // Fixture candidates: a bright 128 BPM energizer and a mellow
        // 96 BPM wind-down, both otherwise equal.
        let energizer = candidate("energizer", 1, 128.0, "11B", 0.8);
        let mut mellow = candidate("mellow", 1, 96.0, "1A", 0.3);
        mellow.valence = 0.3;

        let profile = CircadianProfile::classic();
        let morning = NeuroQueueEngine::generate_daylist_scored(
            &[energizer.clone(), mellow.clone()],
            1_000_000,
            8,
            2,
            &profile,
        );
        let night = NeuroQueueEngine::generate_daylist_scored(
            &[energizer.clone(), mellow.clone()],
            1_000_000,
            23,
            2,
            &profile,
        );

        // Morning: the energizer leads. Night: the mellow track leads.
        assert_eq!(
            morning[0].candidate.id,
            "energizer",
            "morning ranking: {:?}",
            morning.iter().map(|r| &r.candidate.id).collect::<Vec<_>>()
        );
        assert_eq!(
            night[0].candidate.id,
            "mellow",
            "night ranking: {:?}",
            night.iter().map(|r| &r.candidate.id).collect::<Vec<_>>()
        );
        // And the reasons name the slot.
        assert!(
            morning[0].reason.contains("Morning"),
            "{}",
            morning[0].reason
        );
        assert!(
            night[0].reason.contains("Late Night") || night[0].reason.contains("tempo"),
            "{}",
            night[0].reason
        );
    }

    /// D6: the daylist regenerates entirely offline — pure function of
    /// (candidates, clock, profile), no network, no IO.
    #[test]
    fn daylist_is_deterministic_and_offline() {
        let candidates: Vec<NeuroCandidate> = (0..40)
            .map(|i| candidate(&format!("d{i}"), (i % 3) as u8, 90.0 + i as f32, "8A", 0.4))
            .collect();
        let a = NeuroQueueEngine::generate_daylist_scored(
            &candidates,
            5_000_000,
            14,
            10,
            &CircadianProfile::classic(),
        );
        let b = NeuroQueueEngine::generate_daylist_scored(
            &candidates,
            5_000_000,
            14,
            10,
            &CircadianProfile::classic(),
        );
        assert_eq!(a.len(), b.len());
        for (ra, rb) in a.iter().zip(b.iter()) {
            assert_eq!(ra.candidate.id, rb.candidate.id);
            assert_eq!(ra.reason, rb.reason);
            assert!((ra.score - rb.score).abs() < f32::EPSILON);
        }
    }

    /// D7: taste-excluded tracks never appear in radio or daylist
    /// output — the regression test.
    #[test]
    fn excluded_from_taste_tracks_never_surface() {
        let mut excluded_liked = candidate("excluded", 0, 124.0, "8A", 0.7);
        excluded_liked.excluded_from_taste = true;
        excluded_liked.user_affinity = 1.0; // would otherwise dominate
        let normal: Vec<NeuroCandidate> = (0..6)
            .map(|i| candidate(&format!("n{i}"), (i % 3) as u8, 124.0, "8A", 0.7))
            .collect();

        let radio = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[excluded_liked.clone()],
            BrainState::Flow,
            1_000_500,
            9,
            5,
            &CircadianProfile::classic(),
        );
        assert!(
            radio.is_empty(),
            "the excluded track must not surface in radio output"
        );

        let mut candidates = normal.clone();
        candidates.push(excluded_liked.clone());
        let daylist = NeuroQueueEngine::generate_daylist_scored(
            &candidates,
            1_000_000,
            9,
            10,
            &CircadianProfile::classic(),
        );
        assert!(
            daylist.iter().all(|r| r.candidate.id != "excluded"),
            "the excluded track must not surface in daylist output"
        );

        // Even under Distress (the liked-anchor lane) the exclusion
        // holds.
        let distress = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[excluded_liked],
            BrainState::Distress,
            1_000_500,
            9,
            5,
            &CircadianProfile::classic(),
        );
        assert!(distress.is_empty());
    }

    /// D4 (snapshot): reason strings are pinned composition-for-
    /// composition.
    #[test]
    fn reason_strings_are_pinned() {
        let profile = CircadianProfile::classic();

        // Tight tempo + exact key + fresh + morning fit.
        let mut tight = candidate("tight", 1, 124.0, "8A", 0.75);
        tight.acoustic_sim = 0.95;
        tight.valence = 0.6;
        let rows = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[tight],
            BrainState::Flow,
            1_000_500,
            8, // morning
            1,
            &profile,
        );
        assert_eq!(
            rows[0].reason,
            "locked to your 124 BPM flow, same key as the seed (8A), fresh for you and fits your Morning slot",
            "snapshot pin"
        );

        // Same key + fresh, no daypart fit (afternoon hour, far targets).
        let mut mellow = candidate("mellow", 1, 96.0, "8A", 0.3);
        mellow.valence = 0.3;
        let rows = NeuroQueueEngine::generate_daylist_scored(
            &[mellow],
            1_000_000,
            14, // afternoon, workout energy — the mellow track misses
            1,
            &profile,
        );
        assert_eq!(rows[0].reason, "fresh for you", "daylist snapshot pin");

        // Recently played + far tempo + distant key: the fallback shape.
        let mut stale = candidate("stale", 2, 160.0, "4B", 0.4);
        stale.last_played_sec = 1_000_400; // ~17 min ago → freshness < 0.35
        let rows = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[stale],
            BrainState::Flow,
            1_000_500,
            9,
            1,
            &profile,
        );
        assert_eq!(
            rows[0].reason, "recently played and a good Morning match",
            "staleness vocabulary"
        );

        // The empty-attribution fallback names the vibe tempo: no
        // tempo/harmonic/freshness/daypart/affinity part fires (partly
        // rested at ~1.6 tau, far tempo, distant key, weak daypart fit).
        let mut blank = candidate("blank", 2, 160.0, "4B", 0.4);
        blank.last_played_sec = 1_000_500 - 20_000;
        let rows = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[blank],
            BrainState::Flow,
            1_000_500,
            12, // midday hour with weak daypart fit
            1,
            &profile,
        );
        assert_eq!(
            rows[0].reason, "Matches your current vibe around 160 BPM",
            "fallback vocabulary"
        );
    }

    /// D4: attributions are exposed per row for the UI badge lane.
    #[test]
    fn breakdown_attributions_are_coherent() {
        let mut near = candidate("near", 1, 124.0, "8A", 0.7);
        near.acoustic_sim = 0.9;
        let rows = NeuroQueueEngine::generate_neuro_queue_scored(
            &seed(),
            &[near],
            BrainState::Flow,
            1_000_500,
            9,
            1,
            &CircadianProfile::classic(),
        );
        let b = &rows[0].breakdown;
        assert_eq!(b.camelot_distance, 0);
        assert!(b.harmonic_bonus > 0.0);
        assert!(b.bpm_distance < 1.0);
        assert!(b.freshness > 0.99);
        assert!(
            (b.base + b.harmonic_bonus + b.circadian_bonus
                - b.satiation_penalty
                - b.tempo_drift_penalty
                - rows[0].score)
                .abs()
                < 1e-4
        );
    }

    /// The Camelot distance vocabulary for the reason composer.
    #[test]
    fn camelot_distance_matches_the_wheel() {
        assert_eq!(NeuroQueueEngine::camelot_distance("8A", "8A"), 0);
        assert_eq!(NeuroQueueEngine::camelot_distance("8A", "8B"), 1); // relative flip
        assert_eq!(NeuroQueueEngine::camelot_distance("8A", "9A"), 1); // adjacent
        assert_eq!(NeuroQueueEngine::camelot_distance("8A", "9B"), 2);
        assert_eq!(NeuroQueueEngine::camelot_distance("8A", "2A"), 6); // opposite
        assert_eq!(NeuroQueueEngine::camelot_distance("12A", "1A"), 1); // ring wrap
    }
}
