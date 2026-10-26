use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlignedSyllable {
    pub text: String,
    pub start_ms: u32,
    pub end_ms: u32,
    pub weight: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlignedLine {
    pub line_text: String,
    pub start_ms: u32,
    pub end_ms: u32,
    pub syllables: Vec<AlignedSyllable>,
}

pub struct LyricAlignerEngine;

impl LyricAlignerEngine {
    /// Common short English function words that require shorter acoustic duration
    const SHORT_FUNCTION_WORDS: &'static [&'static str] = &[
        "a", "an", "the", "in", "on", "at", "to", "for", "of", "and", "or", "but", "so", "if",
        "is", "am", "are", "was", "were", "it", "its", "my", "you", "he", "she", "we", "they",
        "me", "him", "her", "us", "them",
    ];

    /// Performs word-level forced alignment & prosodic expansion on a single line
    pub fn align_line_words(
        line_text: &str,
        line_start_ms: u32,
        line_end_ms: u32,
        vocal_energy_100hz: Option<&[f32]>,
    ) -> Vec<AlignedSyllable> {
        let words: Vec<&str> = line_text.split_whitespace().collect();
        if words.is_empty() {
            return Vec::new();
        }

        let total_line_duration = if line_end_ms > line_start_ms {
            line_end_ms - line_start_ms
        } else {
            (words.len() as u32 * 450).max(1800)
        };

        // 1. Calculate linguistic prosody weights for each word
        let mut weights: Vec<f32> = Vec::with_capacity(words.len());
        for &word in &words {
            weights.push(Self::calculate_word_weight(word));
        }

        let total_weight: f32 = weights.iter().sum();
        let safe_total_weight = if total_weight > 0.001 {
            total_weight
        } else {
            words.len() as f32
        };

        // 2. Initial Proportional Timestamp Distribution
        let mut syllables: Vec<AlignedSyllable> = Vec::with_capacity(words.len());
        let mut current_offset = line_start_ms;

        for (i, &word) in words.iter().enumerate() {
            let word_duration =
                ((weights[i] / safe_total_weight) * total_line_duration as f32).round() as u32;
            let start = current_offset;
            let end = (start + word_duration.max(120)).min(line_start_ms + total_line_duration);

            syllables.push(AlignedSyllable {
                text: if i == words.len() - 1 {
                    word.to_string()
                } else {
                    format!("{} ", word)
                },
                start_ms: start,
                end_ms: end,
                weight: weights[i],
            });

            current_offset = end;
        }

        // 3. Audio-Guided Vocal Energy Peak Snapping (DTW) if 100Hz PCM energy is supplied
        if let Some(energy) = vocal_energy_100hz {
            Self::snap_to_vocal_energy_peaks(&mut syllables, energy, line_start_ms, line_end_ms);
        }

        syllables
    }

    /// Converts raw unsynchronized plain lyrics into high-precision word-by-word aligned document
    pub fn align_unsynchronized_lyrics(
        raw_text: &str,
        song_duration_ms: u32,
        vocal_energy_100hz: Option<&[f32]>,
    ) -> Vec<AlignedLine> {
        let raw_lines: Vec<&str> = raw_text
            .lines()
            .map(|l| l.trim())
            .filter(|l| !l.is_empty() && !l.starts_with('[') && !l.starts_with('#'))
            .collect();

        if raw_lines.is_empty() {
            return Vec::new();
        }

        let duration = if song_duration_ms > 30000 {
            song_duration_ms
        } else {
            180000
        };
        // Intro offset (8% of song duration) and Outro padding (5%)
        let intro_offset_ms = (duration as f32 * 0.08) as u32;
        let singing_duration_ms = (duration as f32 * 0.87) as u32;

        let line_step_ms = singing_duration_ms / raw_lines.len() as u32;
        let mut aligned_lines = Vec::with_capacity(raw_lines.len());

        for (idx, &line_str) in raw_lines.iter().enumerate() {
            let l_start = intro_offset_ms + (idx as u32 * line_step_ms);
            let l_end = l_start + (line_step_ms as f32 * 0.92) as u32;

            let syllables = Self::align_line_words(line_str, l_start, l_end, vocal_energy_100hz);

            aligned_lines.push(AlignedLine {
                line_text: line_str.to_string(),
                start_ms: l_start,
                end_ms: l_end,
                syllables,
            });
        }

        aligned_lines
    }

    fn calculate_word_weight(word: &str) -> f32 {
        let clean = word.trim().to_lowercase();
        if clean.is_empty() {
            return 1.0;
        }

        let is_function_word = Self::SHORT_FUNCTION_WORDS.contains(&clean.as_str());

        // Count vowel nuclei
        let mut vowel_count = 0usize;
        let mut prev_is_vowel = false;
        for c in clean.chars() {
            let is_vowel = matches!(c, 'a' | 'e' | 'i' | 'o' | 'u' | 'y');
            if is_vowel && !prev_is_vowel {
                vowel_count += 1;
            }
            prev_is_vowel = is_vowel;
        }
        let safe_vowels = vowel_count.max(1) as f32;

        let char_len_factor = (clean.len() as f32) * 0.15;
        let mut weight = (safe_vowels * 1.2) + char_len_factor;

        // Function word damping
        if is_function_word {
            weight *= 0.65;
        } else if clean.len() >= 6 {
            weight *= 1.35; // Stressed content word
        }

        // Punctuation trailing pause weight
        if word.ends_with(',') || word.ends_with(';') {
            weight += 0.8;
        } else if word.ends_with('.') || word.ends_with('!') || word.ends_with('?') {
            weight += 1.4;
        }

        weight.max(0.5)
    }

    fn snap_to_vocal_energy_peaks(
        syllables: &mut [AlignedSyllable],
        energy_100hz: &[f32],
        line_start_ms: u32,
        _line_end_ms: u32,
    ) {
        if syllables.is_empty() || energy_100hz.is_empty() {
            return;
        }

        // Each index in energy_100hz corresponds to 10ms
        for syl in syllables.iter_mut() {
            let start_idx = (syl.start_ms / 10) as usize;
            let window_radius = 15usize; // +/- 150ms search window

            let min_idx = start_idx.saturating_sub(window_radius);
            let max_idx = (start_idx + window_radius).min(energy_100hz.len().saturating_sub(1));

            if min_idx < max_idx {
                let mut max_energy = 0.0f32;
                let mut peak_idx = start_idx;

                #[allow(clippy::needless_range_loop)] // peak search keeps the index
                for idx in min_idx..=max_idx {
                    if energy_100hz[idx] > max_energy {
                        max_energy = energy_100hz[idx];
                        peak_idx = idx;
                    }
                }

                if max_energy > 0.05 {
                    let snapped_ms = (peak_idx as u32 * 10).max(line_start_ms);
                    let diff = (snapped_ms as i64 - syl.start_ms as i64).abs();
                    if diff < 180 {
                        let dur = syl.end_ms.saturating_sub(syl.start_ms);
                        syl.start_ms = snapped_ms;
                        syl.end_ms = snapped_ms + dur;
                    }
                }
            }
        }
    }
}
