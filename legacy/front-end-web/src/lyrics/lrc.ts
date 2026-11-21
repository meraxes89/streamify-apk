/// <reference types="vite/client" />
/**
 * lyrics/lrc.ts — the LRC / syllable parser, duration calculus, even-
 * distribution synthesizer and formatter (1:1 port of
 * app/…/data/models/LyricsData.kt parseLrc()/shiftTimestamps()/
 * formatLrc(), plus the ~30-line plain-text synthesizer that stood in for
 * the Rust rustAlignAndCompileLyrics alignment on Android).
 *
 * ANDROID vs WEB (the ONE deliberate divergence, agreed for v1): the Rust
 * SLYR binary crate is skipped — it exists to dodge JNI/GC costs on
 * Android. On web the parsed model is a plain sorted
 * { timeMs, text, syllables[] } array; a binary search over it is
 * sub-microsecond in JS, which is the entire value SLYR provided.
 * The PURE-KOTLIN parser path (LyricsData.kt Tier 2) is therefore the
 * ONLY path here, ported exactly:
 *   • [offset:] global header offset applied to every timestamp
 *   • line timestamps [mm:ss.xx] / [mm:ss.xxx] (2- and 3-digit ms)
 *   • inline word/syllable tags <mm:ss.xx>Word (2- or 3-digit ms),
 *     each syllable ending where the next begins (last: +3500 ms)
 *   • inter-line duration calculus — next-line start minus own start,
 *     floored at 1200 ms; the final line gets 4000 ms
 *   • lineless syllable tracks get one whole-line pseudo-syllable
 *   • unsynced plain lines are NEVER discarded (isSynced=false list)
 */

/** One karaoke word/syllable inside a line. */
export interface Syllable {
  readonly text: string;
  readonly startMs: number;
  readonly endMs: number;
}

/** One synced (or unsynced, timeMs = 0) lyric line. */
export interface LyricsLine {
  readonly timeMs: number;
  readonly text: string;
  readonly syllables: readonly Syllable[];
  readonly durationMs: number;
}

/** The parsed lyric document. */
export interface LyricsData {
  readonly lines: readonly LyricsLine[];
  readonly isSynced: boolean;
  readonly globalOffsetMs: number;
}

export const EMPTY_LYRICS: LyricsData = {
  lines: [],
  isSynced: false,
  globalOffsetMs: 0,
};

/** Minimum inter-line duration (Kotlin coerceAtLeast(1200L)). */
export const MIN_LINE_DURATION_MS = 1200;
/** The final line's duration when no successor exists. */
export const FINAL_LINE_DURATION_MS = 4000;
/** The synthetic last-syllable tail when no next syllable exists. */
export const LAST_SYLLABLE_TAIL_MS = 3500;
/** Synthesizer default when the track duration is unknown (Kotlin: 180 s). */
export const SYNTH_DEFAULT_DURATION_MS = 180_000;

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

const OFFSET_REGEX = /\[offset:\s*(-?\d+)\]/i;
const LINE_REGEX = /\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)/;
const WORD_REGEX = /<(\d{2}):(\d{2})\.(\d{2,3})>([^<[]*)/g;

/** 2-digit ms means centiseconds (×10); 3-digit is milliseconds as-is. */
function msField(msStr: string): number {
  return msStr.length === 2 ? Number(msStr) * 10 : Number(msStr);
}

function stampMs(min: string, sec: string, msStr: string, offsetMs: number): number {
  return Number(min) * 60_000 + Number(sec) * 1000 + msField(msStr) + offsetMs;
}

/**
 * Parses LRC text (line-level, syllable-tagged, or plain) into LyricsData.
 * Unknown/garbage input returns an unsynced EMPTY result, never throws.
 */
export function parseLrc(lrcContent: string): LyricsData {
  if (lrcContent.trim() === '') return EMPTY_LYRICS;

  // 1. Extract the global header offset.
  let offsetMs = 0;
  const offsetMatch = OFFSET_REGEX.exec(lrcContent);
  if (offsetMatch) {
    const parsed = Number(offsetMatch[1]);
    if (Number.isFinite(parsed)) offsetMs = parsed;
  }

  const syncedLines: LyricsLine[] = [];
  const unsyncedLines: LyricsLine[] = [];

  for (const raw of lrcContent.split('\n')) {
    const trimmed = raw.trim();
    if (trimmed === '') continue;

    const lineMatch = LINE_REGEX.exec(trimmed);
    if (lineMatch) {
      const lineStartMs = stampMs(lineMatch[1], lineMatch[2], lineMatch[3], offsetMs);
      const content = lineMatch[4].trim();
      if (content === '') continue;

      // Inline syllable tags: <mm:ss.xx>Word …
      const wordMatches = [...content.matchAll(WORD_REGEX)];
      if (wordMatches.length > 0) {
        let plainText = '';
        const syllables: Syllable[] = [];
        for (let i = 0; i < wordMatches.length; i++) {
          const wm = wordMatches[i];
          const wStartMs = stampMs(wm[1], wm[2], wm[3], offsetMs);
          const wordText = wm[4];
          plainText += wordText;

          const nextStartMs =
            i < wordMatches.length - 1
              ? stampMs(
                  wordMatches[i + 1][1],
                  wordMatches[i + 1][2],
                  wordMatches[i + 1][3],
                  offsetMs,
                )
              : wStartMs + LAST_SYLLABLE_TAIL_MS;

          syllables.push({ text: wordText, startMs: wStartMs, endMs: nextStartMs });
        }
        syncedLines.push({
          timeMs: lineStartMs,
          text: plainText.trim(),
          syllables,
          durationMs: 0,
        });
      } else {
        // Standard line-level LRC without embedded syllable timestamps.
        syncedLines.push({
          timeMs: lineStartMs,
          text: content,
          syllables: [],
          durationMs: 0,
        });
      }
    } else if (!trimmed.startsWith('[')) {
      // Plain unsynced text line (never discarded).
      unsyncedLines.push({ timeMs: 0, text: trimmed, syllables: [], durationMs: 0 });
    }
  }

  if (syncedLines.length > 0) {
    // Sort by start time, then compute the inter-line duration calculus.
    const sorted = syncedLines
      .slice()
      .sort((a, b) => a.timeMs - b.timeMs)
      .map((line) => ({ ...line, syllables: [...line.syllables] }));

    for (let i = 0; i < sorted.length; i++) {
      const line = sorted[i];
      const duration =
        i < sorted.length - 1
          ? Math.max(sorted[i + 1].timeMs - line.timeMs, MIN_LINE_DURATION_MS)
          : FINAL_LINE_DURATION_MS;
      const nextLineTimeMs = line.timeMs + duration;
      const finalSyllables =
        line.syllables.length === 0
          ? [{ text: line.text, startMs: line.timeMs, endMs: nextLineTimeMs }]
          : line.syllables;
      sorted[i] = { ...line, durationMs: duration, syllables: finalSyllables };
    }
    return { lines: sorted, isSynced: true, globalOffsetMs: offsetMs };
  }
  if (unsyncedLines.length > 0) {
    return { lines: unsyncedLines, isSynced: false, globalOffsetMs: 0 };
  }
  return EMPTY_LYRICS;
}

// ---------------------------------------------------------------------------
// Active-line lookup (binary search — the SLYR find_active_line analog)
// ---------------------------------------------------------------------------

/**
 * The index of the line active at playheadMs (the last line whose start is
 * ≤ playhead), or -1 before the first line. Binary search: O(log n).
 */
export function findActiveLineIndex(
  lines: readonly LyricsLine[],
  playheadMs: number,
): number {
  if (lines.length === 0) return -1;
  if (playheadMs < lines[0].timeMs) return -1;
  let lo = 0;
  let hi = lines.length - 1;
  let ans = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (lines[mid].timeMs <= playheadMs) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/** The active syllable index within a line at playheadMs, or -1. */
export function findActiveSyllableIndex(
  line: LyricsLine,
  playheadMs: number,
): number {
  if (line.syllables.length === 0) return -1;
  if (playheadMs < line.syllables[0].startMs) return -1;
  let lo = 0;
  let hi = line.syllables.length - 1;
  let ans = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (line.syllables[mid].startMs <= playheadMs) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

// ---------------------------------------------------------------------------
// Transformations
// ---------------------------------------------------------------------------

/** Shifts every timestamp by offsetMs (user nudges; clamped at 0). */
export function shiftTimestamps(
  lines: readonly LyricsLine[],
  offsetMs: number,
): LyricsLine[] {
  if (offsetMs === 0) return [...lines];
  return lines.map((line) => ({
    timeMs: Math.max(0, line.timeMs + offsetMs),
    text: line.text,
    durationMs: line.durationMs,
    syllables: line.syllables.map((syl) => ({
      text: syl.text,
      startMs: Math.max(0, syl.startMs + offsetMs),
      endMs: Math.max(0, syl.endMs + offsetMs),
    })),
  }));
}

/** Formats lines back to LRC text (syllable tags when present). */
export function formatLrc(
  lines: readonly LyricsLine[],
  offsetMs: number = 0,
): string {
  const fmt = (ms: number): string => {
    const clamped = Math.max(0, ms + offsetMs);
    const min = Math.floor(clamped / 60_000);
    const sec = Math.floor((clamped % 60_000) / 1000);
    const cs = Math.floor((clamped % 1000) / 10);
    return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}.${String(cs).padStart(2, '0')}`;
  };

  const sb: string[] = [];
  for (const line of lines) {
    if (line.syllables.length > 0) {
      sb.push(`[${fmt(line.timeMs)}]${line.syllables.map((syl) => `<${fmt(syl.startMs)}>${syl.text}`).join('')}`);
    } else {
      sb.push(`[${fmt(line.timeMs)}]${line.text}`);
    }
  }
  return sb.join('\n').trim();
}

// ---------------------------------------------------------------------------
// The plain-text → even-distribution synthesizer
// ---------------------------------------------------------------------------

/**
 * Synthesizes a synced timeline from plain lyrics: each non-blank line gets
 * an even slice of the track duration (180 s default when unknown). This
 * replaces Android's Rust rustAlignAndCompileLyrics fallback with the same
 * observable output contract: an LRC string, or the plain text back if
 * nothing could be synthesized.
 */
export function synthesizeSyncedLyricsFromPlain(
  plainText: string,
  durationSec: number,
): string {
  try {
    const durationMs =
      durationSec > 0 ? durationSec * 1000 : SYNTH_DEFAULT_DURATION_MS;
    const lines = plainText
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l !== '');
    if (lines.length === 0) return plainText;

    const stepMs = Math.floor(durationMs / lines.length);
    const sb: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const startMs = i * stepMs;
      const mm = Math.floor(startMs / 60_000);
      const ss = Math.floor((startMs % 60_000) / 1000);
      const xx = Math.floor((startMs % 1000) / 10);
      const stamp = `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}.${String(xx).padStart(2, '0')}`;
      sb.push(`[${stamp}]${lines[i]}`);
    }
    return sb.join('\n').trim();
  } catch {
    return plainText;
  }
}
