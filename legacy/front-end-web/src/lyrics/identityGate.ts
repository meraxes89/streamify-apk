/// <reference types="vite/client" />
/**
 * lyrics/identityGate.ts — the same-song identity verification gate
 * (1:1 port of LyricsResolver.kt §2 + FuzzyTitleMatcher's pure-Kotlin
 * fallback evaluator — the Rust fast path is replaced by the identical
 * pure-TypeScript chain, which was the Kotlin fallback anyway).
 *
 * THE CONTRACT: every provider result is verified against the input track
 * metadata BEFORE being accepted, so a different song's lyrics can never
 * win the race. Three independent gates, all must pass:
 *   • title   — exact cleaned equality, else bounded fuzzy similarity ≥ 0.72
 *   • artist  — bidirectional substring tolerance after noise stripping
 *   • duration— ±15 s; unknown durations on EITHER side never reject
 *
 * The similarity evaluator preserves the Kotlin order exactly:
 *   1. exact equality → 1.0
 *   2. direct substring containment → 0.8 + 0.2 × length ratio
 *   3. token Jaccard > 0.5 → the Jaccard score
 *   4. bounded Levenshtein (strings ≤ 30 chars) → 1 − dist/maxLen
 *   5. otherwise → 0.0
 */

import { cleanArtist, cleanTitle } from './sanitize';

/** The fuzzy-title similarity floor from the Kotlin gate (0.72). */
export const TITLE_SIMILARITY_THRESHOLD = 0.72;

/** Token-separator normalizer for set/token comparisons. */
const CLEAN_REGEX = /[^a-z0-9]+/;

// ---------------------------------------------------------------------------
// Levenshtein (O(N) two-row rolling memory — same as the Kotlin fallback)
// ---------------------------------------------------------------------------

export function computeLevenshtein(a: string, b: string): number {
  const lenA = a.length;
  const lenB = b.length;
  let prevRow = new Array<number>(lenB + 1);
  let currRow = new Array<number>(lenB + 1);
  for (let j = 0; j <= lenB; j++) prevRow[j] = j;

  for (let i = 1; i <= lenA; i++) {
    currRow[0] = i;
    for (let j = 1; j <= lenB; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      currRow[j] = Math.min(
        currRow[j - 1] + 1,
        prevRow[j] + 1,
        prevRow[j - 1] + cost,
      );
    }
    const temp = prevRow;
    prevRow = currRow;
    currRow = temp;
  }
  return prevRow[lenB];
}

// ---------------------------------------------------------------------------
// The similarity evaluator (Kotlin calculateSimilarity, pure chain)
// ---------------------------------------------------------------------------

export function calculateSimilarity(s1: string, s2: string): number {
  const str1 = s1.trim().toLowerCase();
  const str2 = s2.trim().toLowerCase();
  if (str1 === str2) return 1.0;
  if (str1 === '' || str2 === '') return 0.0;

  // Direct substring containment — scaled by length ratio.
  if (str2.includes(str1) || str1.includes(str2)) {
    const lenRatio = Math.min(str1.length, str2.length) / Math.max(str1.length, str2.length);
    return 0.8 + 0.2 * lenRatio;
  }

  // Token Jaccard similarity.
  const tokens1 = new Set(str1.split(CLEAN_REGEX).filter((t) => t !== ''));
  const tokens2 = new Set(str2.split(CLEAN_REGEX).filter((t) => t !== ''));
  if (tokens1.size > 0 && tokens2.size > 0) {
    let intersection = 0;
    for (const t of tokens1) if (tokens2.has(t)) intersection += 1;
    const union = tokens1.size + tokens2.size - intersection;
    const jaccard = intersection / union;
    if (jaccard > 0.5) return jaccard;
  }

  // Bounded Levenshtein (identifiers longer than 30 chars are treated as
  // non-matching — the Kotlin bound, cheap and deliberate).
  const maxLen = Math.max(str1.length, str2.length);
  if (maxLen > 30) return 0.0;
  const distance = computeLevenshtein(str1, str2);
  return Math.max(0.0, 1.0 - distance / maxLen);
}

// ---------------------------------------------------------------------------
// The three gates (LyricsResolver.kt §2 semantics)
// ---------------------------------------------------------------------------

/**
 * Verifies a candidate track title actually refers to the requested song:
 * exact cleaned equality first, then bounded fuzzy similarity ≥ 0.72.
 */
export function candidateTitleMatches(
  inputCleanTitle: string,
  rawCandidateTitle: string | null | undefined,
): boolean {
  if (rawCandidateTitle == null || rawCandidateTitle.trim() === '') return false;
  const cand = cleanTitle(rawCandidateTitle).toLowerCase().trim();
  const input = inputCleanTitle.toLowerCase().trim();
  if (cand === '' || input === '') return false;
  if (cand === input) return true;

  return calculateSimilarity(input, cand) >= TITLE_SIMILARITY_THRESHOLD;
}

/**
 * Verifies the candidate artist refers to the requested artist
 * (bidirectional substring tolerant; blank inputs never reject).
 */
export function candidateArtistMatches(
  inputCleanArtist: string,
  rawCandidateArtist: string | null | undefined,
): boolean {
  if (inputCleanArtist.trim() === '') return true;
  if (rawCandidateArtist == null || rawCandidateArtist.trim() === '') return false;
  const cand = cleanArtist(rawCandidateArtist).toLowerCase().trim();
  const input = inputCleanArtist.toLowerCase().trim();
  if (cand === '' || input === '') return true;
  return cand === input || cand.includes(input) || input.includes(cand);
}

/** Duration gate (±15s). Unknown durations on either side never reject. */
export function candidateDurationMatches(
  inputDurationSec: number,
  candidateDurationSec: number,
  toleranceSec: number = 15,
): boolean {
  if (inputDurationSec <= 0 || candidateDurationSec <= 0) return true;
  return Math.abs(candidateDurationSec - inputDurationSec) <= toleranceSec;
}

/** Full same-recording proof — the gate every provider result must pass. */
export function isSameRecording(input: {
  title: string;
  artist: string;
  durationSec: number;
}, candidate: {
  title: string | null | undefined;
  artist: string | null | undefined;
  durationSec: number;
}): boolean {
  return (
    candidateTitleMatches(input.title, candidate.title) &&
    candidateArtistMatches(input.artist, candidate.artist) &&
    candidateDurationMatches(input.durationSec, candidate.durationSec)
  );
}
