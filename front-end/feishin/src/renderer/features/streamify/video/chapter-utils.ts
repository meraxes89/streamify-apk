/**
 * features/streamify/video/chapter-utils.ts — the pure chapter math (W2-A).
 *
 * Network-free, dependency-free, and the ONLY place the seconds-space
 * boundary rules live — the Vitest lane (video-chapters.test.ts) locks
 * them: active-chapter selection at boundaries, the last chapter's
 * open-ended end (closed by the element's duration), the scrubber tick
 * positions, and the timecode capture/restore arithmetic the Audio ↔
 * Video toggle rides.
 */

import type { ChaptersPayload } from '/@/renderer/api/streamify/metadata/types';

export type ChapterRow = ChaptersPayload['chapters'][number];

/**
 * The chapter active at `seconds`. Boundary rule: a chapter spans
 * [startSeconds, endSeconds) — the START of chapter N+1 belongs to N+1.
 * The LAST chapter's endSeconds is null on the wire; the caller closes it
 * with the element's duration (`durationSeconds`; null = treat as +∞).
 */
export function activeChapterAt(
    chapters: ChapterRow[],
    seconds: number,
    durationSeconds: null | number = null,
): null | { chapter: ChapterRow; index: number } {
    if (chapters.length === 0 || !Number.isFinite(seconds) || seconds < 0) return null;
    for (let i = chapters.length - 1; i >= 0; i -= 1) {
        const chapter = chapters[i];
        if (seconds >= chapter.startSeconds) {
            // The candidate: is the position still inside it?
            const end =
                chapter.endSeconds !== null
                    ? chapter.endSeconds
                    : (durationSeconds ?? Number.POSITIVE_INFINITY);
            if (seconds < end) return { chapter, index: i };
            return null; // past the last chapter's known end (a gap in the markers)
        }
    }
    return null; // before the first marker (a leading gap)
}

/**
 * The timecode capture for a mode switch: the CURRENT position clamped
 * into [0, duration]. Audio ↔ Video and Video ↔ Audio both land the other
 * element on exactly this second (the timecode-synced contract).
 */
export function captureTimecode(positionSeconds: number, durationSeconds: null | number): number {
    if (!Number.isFinite(positionSeconds) || positionSeconds < 0) return 0;
    if (durationSeconds !== null && Number.isFinite(durationSeconds) && durationSeconds > 0) {
        return Math.min(positionSeconds, Math.max(0, durationSeconds - 0.05));
    }
    return positionSeconds;
}

/** Scrubber tick positions (0..1 fractions) — one per chapter boundary. */
export function chapterTickFractions(chapters: ChapterRow[], durationSeconds: number): number[] {
    if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) return [];
    return chapters
        .filter((chapter) => chapter.startSeconds > 0)
        .map((chapter) => Math.min(1, Math.max(0, chapter.startSeconds / durationSeconds)));
}

/** "1:23:45" / "4:32" — the chapter chip timestamp format. */
export function formatChapterTime(seconds: number): string {
    const clamped = Math.max(0, Math.floor(seconds));
    const h = Math.floor(clamped / 3600);
    const m = Math.floor((clamped % 3600) / 60);
    const s = clamped % 60;
    if (h > 0) {
        return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${m}:${String(s).padStart(2, '0')}`;
}
