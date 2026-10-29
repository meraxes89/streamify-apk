use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchCandidate {
    pub id: i32,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: i32,
    pub cover_art_path: String,
    pub filepath: String,
    #[serde(default)]
    pub bpm: f32,
    #[serde(default)]
    pub is_liked: bool,
    #[serde(default)]
    pub score: f32,
}

pub struct FuzzySearchEngine;

impl FuzzySearchEngine {
    /// High-performance normalized Levenshtein similarity with early exit optimizations
    pub fn levenshtein_similarity(s1: &str, s2: &str) -> f32 {
        let s1_clean = s1.trim().to_lowercase();
        let s2_clean = s2.trim().to_lowercase();

        if s1_clean == s2_clean {
            return 1.0;
        }
        if s1_clean.is_empty() || s2_clean.is_empty() {
            return 0.0;
        }

        let len1 = s1_clean.chars().count();
        let len2 = s2_clean.chars().count();
        let max_len = len1.max(len2);

        let v1: Vec<char> = s1_clean.chars().collect();
        let v2: Vec<char> = s2_clean.chars().collect();

        // 2-row rolling buffer (zero heap matrix allocation)
        let mut prev_row: Vec<usize> = (0..=len2).collect();
        let mut curr_row: Vec<usize> = vec![0; len2 + 1];

        // Classic DP recurrence on row indices — iterator form obscures it.
        #[allow(clippy::needless_range_loop)]
        for i in 0..len1 {
            curr_row[0] = i + 1;
            #[allow(clippy::needless_range_loop)]
            for j in 0..len2 {
                let cost = if v1[i] == v2[j] { 0 } else { 1 };
                curr_row[j + 1] = (curr_row[j] + 1)
                    .min(prev_row[j + 1] + 1)
                    .min(prev_row[j] + cost);
            }
            prev_row.copy_from_slice(&curr_row);
        }

        let distance = prev_row[len2] as f32;
        (1.0 - (distance / max_len as f32)).clamp(0.0, 1.0)
    }

    /// Fast Jaro-Winkler string distance metric
    pub fn jaro_winkler_similarity(s1: &str, s2: &str) -> f32 {
        let s1_clean = s1.trim().to_lowercase();
        let s2_clean = s2.trim().to_lowercase();

        if s1_clean == s2_clean {
            return 1.0;
        }
        if s1_clean.is_empty() || s2_clean.is_empty() {
            return 0.0;
        }

        let v1: Vec<char> = s1_clean.chars().collect();
        let v2: Vec<char> = s2_clean.chars().collect();
        let len1 = v1.len();
        let len2 = v2.len();

        let match_window = (len1.max(len2) / 2).saturating_sub(1);
        let mut v1_matches = vec![false; len1];
        let mut v2_matches = vec![false; len2];

        let mut matches = 0usize;
        for i in 0..len1 {
            let start = i.saturating_sub(match_window);
            let end = (i + match_window + 1).min(len2);
            for j in start..end {
                if !v2_matches[j] && v1[i] == v2[j] {
                    v1_matches[i] = true;
                    v2_matches[j] = true;
                    matches += 1;
                    break;
                }
            }
        }

        if matches == 0 {
            return 0.0;
        }

        let mut transpositions = 0usize;
        let mut k = 0usize;
        for i in 0..len1 {
            if v1_matches[i] {
                while !v2_matches[k] {
                    k += 1;
                }
                if v1[i] != v2[k] {
                    transpositions += 1;
                }
                k += 1;
            }
        }

        let m = matches as f32;
        let jaro =
            (m / len1 as f32 + m / len2 as f32 + (m - (transpositions / 2) as f32) / m) / 3.0;

        // Prefix bonus (Winkler modification)
        let mut prefix_len = 0usize;
        for i in 0..len1.min(len2).min(4) {
            if v1[i] == v2[i] {
                prefix_len += 1;
            } else {
                break;
            }
        }

        (jaro + prefix_len as f32 * 0.1 * (1.0 - jaro)).clamp(0.0, 1.0)
    }

    /// Unified harmonic similarity score (Combines substring containment, Jaro-Winkler, Levenshtein)
    pub fn calculate_similarity(query: &str, target: &str) -> f32 {
        let q = query.trim().to_lowercase();
        let t = target.trim().to_lowercase();

        if q.is_empty() || t.is_empty() {
            return 0.0;
        }
        if q == t {
            return 1.0;
        }

        // Substring exact match boost
        if t.contains(&q) {
            let ratio = q.len() as f32 / t.len() as f32;
            return 0.75 + (0.25 * ratio);
        }

        let jw = Self::jaro_winkler_similarity(&q, &t);
        let lev = Self::levenshtein_similarity(&q, &t);

        // Weighted balance
        (jw * 0.65) + (lev * 0.35)
    }

    /// High-throughput candidate re-ranking across title, artist, and album
    pub fn rank_candidates(query: &str, candidates: &mut [SearchCandidate]) {
        let q = query.trim().to_lowercase();
        if q.is_empty() {
            return;
        }

        for c in candidates.iter_mut() {
            let title_score = Self::calculate_similarity(&q, &c.title);
            let artist_score = Self::calculate_similarity(&q, &c.artist);
            let combo_score = Self::calculate_similarity(&q, &format!("{} {}", c.title, c.artist));

            let max_score = title_score.max(artist_score).max(combo_score);
            let liked_bonus = if c.is_liked { 0.08 } else { 0.0 };

            c.score = (max_score + liked_bonus).clamp(0.0, 1.0);
        }

        candidates.sort_by(|a, b| {
            b.score
                .partial_cmp(&a.score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
    }
}
