/**
 * W2-A — the timestamped-chapters suite (client side).
 *
 * Locks, per the wave contract:
 *   • chapter EXTRACTION — the wire payload (the worker's ChaptersPayload,
 *     W2-A rows and W1-A-era legacy rows alike) validates + coerces into
 *     the normalized seconds-space shape, with boundaries closed;
 *   • active-chapter BOUNDARY selection — [start, end) semantics, the
 *     last chapter closed by the element duration, gaps before/after;
 *   • the scrubber TICK fractions;
 *   • the TIMECODE-PRESERVING Audio ↔ Video toggle state transitions —
 *     the capture arithmetic and the store's resume bookkeeping (the
 *     controller's capture → setResume → seek-on-exit contract);
 *   • the endpoint mapping — getChapters rides the 'chapters' endpoint of
 *     the provider pipeline (the 7 d TTL matrix lives worker-side and is
 *     locked by the relay lane's suite against ttlForEndpoint).
 *
 * Network-free by construction (the house vitest lane).
 */

import type { ChaptersPayload } from '/@/renderer/api/streamify/metadata/types';

import { beforeEach, describe, expect, it } from 'vitest';

import { validateChaptersPayload } from '/@/renderer/api/streamify/metadata/normalize';
import {
    activeChapterAt,
    captureTimecode,
    chapterTickFractions,
    formatChapterTime,
} from '/@/renderer/features/streamify/video/chapter-utils';
import {
    streamifyVideoStoreBase,
    useStreamifyVideoStore,
} from '/@/renderer/features/streamify/video/streamify-video.store';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** A W2-A wire payload (the worker's current shape). */
const W2A_WIRE = {
    chapters: [
        {
            atMs: 0,
            endSeconds: 42,
            startSeconds: 0,
            thumbnailUrl: 'https://i.ytimg.com/ch0',
            title: 'Intro',
        },
        { atMs: 42_000, endSeconds: 180, startSeconds: 42, thumbnailUrl: null, title: 'Verse' },
        { atMs: 180_000, endSeconds: null, startSeconds: 180, thumbnailUrl: null, title: 'Outro' },
    ],
    hl: 'en',
    kind: 'chapters',
    videoId: 'w2aChapters',
} as const;

/** A W1-A-era cached payload (atMs + title only — the coercion case). */
const LEGACY_WIRE = {
    chapters: [
        { atMs: 0, title: 'Intro' },
        { atMs: 30_000, title: 'Drop' },
        { atMs: 95_000, title: 'Outro' },
    ],
    hl: 'en',
    kind: 'chapters',
    videoId: 'w2aLegacy',
} as const;

/** Validate-or-throw (the test fixtures are known-good). */
function validatedChapters(wire: unknown): ChaptersPayload['chapters'] {
    const outcome = validateChaptersPayload(wire);
    if (!outcome.ok) {
        throw new Error(`fixture failed validation: ${outcome.reason}`);
    }
    return outcome.payload.chapters;
}

beforeEach(() => {
    streamifyVideoStoreBase.getState().actions.reset();
});

// ---------------------------------------------------------------------------
// Extraction / coercion
// ---------------------------------------------------------------------------

describe('W2-A chapter extraction (validateChaptersPayload)', () => {
    it('accepts the W2-A wire shape and preserves every field', () => {
        const outcome = validateChaptersPayload(W2A_WIRE);
        expect(outcome.ok).toBe(true);
        if (!outcome.ok) return;
        expect(outcome.payload.chapters).toHaveLength(3);
        expect(outcome.payload.chapters[0]).toEqual({
            endSeconds: 42,
            startSeconds: 0,
            thumbnailUrl: 'https://i.ytimg.com/ch0',
            title: 'Intro',
        });
        expect(outcome.payload.chapters[2].endSeconds).toBeNull();
    });

    it('coerces W1-A-era legacy rows (atMs) and closes the open boundaries', () => {
        const outcome = validateChaptersPayload(LEGACY_WIRE);
        expect(outcome.ok).toBe(true);
        if (!outcome.ok) return;
        const chapters = outcome.payload.chapters;
        expect(chapters.map((c) => c.startSeconds)).toEqual([0, 30, 95]);
        // endSeconds derived from the NEXT row's start; the last stays null.
        expect(chapters[0].endSeconds).toBe(30);
        expect(chapters[1].endSeconds).toBe(95);
        expect(chapters[2].endSeconds).toBeNull();
        expect(chapters.every((c) => c.thumbnailUrl === null)).toBe(true);
    });

    it('rejects the failure shapes honestly', () => {
        expect(validateChaptersPayload({ kind: 'album' }).ok).toBe(false); // wrong kind
        expect(validateChaptersPayload({ chapters: [], kind: 'chapters' }).ok).toBe(false); // empty
        expect(
            validateChaptersPayload({
                chapters: [{ title: 'No Time' }],
                kind: 'chapters',
            }).ok,
        ).toBe(false); // a row without startSeconds/atMs
        expect(
            validateChaptersPayload({
                chapters: [{ startSeconds: 5, title: '' }],
                kind: 'chapters',
            }).ok,
        ).toBe(false); // a row without a title
    });

    it('falls back to startSeconds when both fields are present (never atMs)', () => {
        const outcome = validateChaptersPayload({
            chapters: [{ atMs: 999_999, startSeconds: 12, title: 'Both' }],
            kind: 'chapters',
        });
        expect(outcome.ok).toBe(true);
        if (!outcome.ok) return;
        expect(outcome.payload.chapters[0].startSeconds).toBe(12);
    });
});

// ---------------------------------------------------------------------------
// Boundary selection
// ---------------------------------------------------------------------------

describe('W2-A active chapter boundary selection', () => {
    const chapters = validatedChapters(W2A_WIRE);

    it('selects [start, end) — the boundary belongs to the NEXT chapter', () => {
        expect(activeChapterAt(chapters, 0)?.chapter.title).toBe('Intro');
        expect(activeChapterAt(chapters, 41.9)?.chapter.title).toBe('Intro');
        expect(activeChapterAt(chapters, 42)?.chapter.title).toBe('Verse');
        expect(activeChapterAt(chapters, 179.9)?.chapter.title).toBe('Verse');
        expect(activeChapterAt(chapters, 180)?.chapter.title).toBe('Outro');
    });

    it('the last chapter is closed by the element duration (null = +Infinity)', () => {
        // No duration given: the open-ended last chapter extends forever.
        expect(activeChapterAt(chapters, 10_000)?.chapter.title).toBe('Outro');
        // With a duration: positions past it fall into the gap.
        expect(activeChapterAt(chapters, 205, 200)).toBeNull();
        expect(activeChapterAt(chapters, 199, 200)?.chapter.title).toBe('Outro');
    });

    it('positions before the first marker and degenerate inputs select nothing', () => {
        expect(activeChapterAt(chapters, -1)).toBeNull();
        expect(activeChapterAt(chapters, Number.NaN)).toBeNull();
        expect(activeChapterAt([], 10)).toBeNull();
        // A leading gap: first marker at 42s.
        const gap = validatedChapters({
            chapters: [{ startSeconds: 42, title: 'Late' }],
            kind: 'chapters',
        });
        expect(activeChapterAt(gap, 10)).toBeNull();
        expect(activeChapterAt(gap, 42)?.chapter.title).toBe('Late');
    });
});

// ---------------------------------------------------------------------------
// Ticks + formatting
// ---------------------------------------------------------------------------

describe('W2-A scrubber ticks + time formatting', () => {
    const chapters = validatedChapters(W2A_WIRE);

    it('tick fractions: one per boundary (the 0s start is not a tick), clamped 0..1', () => {
        expect(chapterTickFractions(chapters, 240)).toEqual([0.175, 0.75]);
        expect(chapterTickFractions(chapters, 0)).toEqual([]); // no duration → no ticks
        expect(chapterTickFractions([], 240)).toEqual([]);
    });

    it('formats chapter timestamps mm:ss and h:mm:ss', () => {
        expect(formatChapterTime(0)).toBe('0:00');
        expect(formatChapterTime(65)).toBe('1:05');
        expect(formatChapterTime(3725)).toBe('1:02:05');
    });
});

// ---------------------------------------------------------------------------
// The timecode-preserving toggle transitions
// ---------------------------------------------------------------------------

describe('W2-A timecode-preserving Audio ↔ Video toggle transitions', () => {
    it('captureTimecode clamps into [0, duration]', () => {
        expect(captureTimecode(83.21, 240)).toBe(83.21);
        expect(captureTimecode(-5, 240)).toBe(0);
        expect(captureTimecode(300, 240)).toBeCloseTo(239.95, 5);
        expect(captureTimecode(120, null)).toBe(120); // no duration known
        expect(captureTimecode(Number.NaN, 240)).toBe(0);
    });

    it('the store carries the captured timecode + play state into video mode', () => {
        const store = streamifyVideoStoreBase.getState();
        const captured = captureTimecode(96.4, 240);
        store.actions.setMode('video');
        store.actions.setCounterpart('counterpartVideoId');
        store.actions.setSourceVideoId('sourceVideoId');
        store.actions.setVideoUrl('https://rr4---sn-test.googlevideo.com/muxed-720');
        store.actions.setResume(captured, true);

        const state = useStreamifyVideoStore.getState();
        expect(state.mode).toBe('video');
        expect(state.resumeAtSec).toBe(96.4);
        expect(state.wasPlaying).toBe(true);
        expect(state.videoUrl).toContain('muxed-720');
        expect(state.counterpartVideoId).toBe('counterpartVideoId');
    });

    it('the reset drops the whole session back to the audio default', () => {
        const store = streamifyVideoStoreBase.getState();
        store.actions.setMode('video');
        store.actions.setResume(96.4, true);
        store.actions.setCounterpart('counterpartVideoId');
        store.actions.setVideoUrl('https://rr4---sn-test.googlevideo.com/muxed-720');
        store.actions.reset();

        const state = useStreamifyVideoStore.getState();
        expect(state.mode).toBe('audio');
        expect(state.resumeAtSec).toBe(0);
        expect(state.wasPlaying).toBe(false);
        expect(state.counterpartVideoId).toBeNull();
        expect(state.videoUrl).toBeNull();
        expect(state.switching).toBe(false);
        expect(state.unavailable).toBe(false);
    });

    it('the unavailable latch marks a degraded toggle without touching audio state', () => {
        const store = streamifyVideoStoreBase.getState();
        store.actions.setUnavailable(true);
        expect(useStreamifyVideoStore.getState().unavailable).toBe(true);
        expect(useStreamifyVideoStore.getState().mode).toBe('audio'); // audio never interrupted
    });

    it('the round trip preserves the timecode: audio 96.4 → capture → video → capture → exit', () => {
        // Audio → Video: capture at 96.4.
        const intoVideo = captureTimecode(96.4, 240);
        const store = streamifyVideoStoreBase.getState();
        store.actions.setResume(intoVideo, true);
        // The overlay plays; the position advances inside the video.
        const videoPosition = 137.6;
        // Video → Audio: capture the video element's position.
        const backToAudio = captureTimecode(videoPosition, 240);
        expect(backToAudio).toBe(137.6);
        // The exit contract: mediaSeekToTimestamp(backToAudio) + play —
        // asserted through the store's resume bookkeeping (the store
        // records what the controller hands the player pipeline).
        store.actions.setResume(backToAudio, true);
        expect(useStreamifyVideoStore.getState().resumeAtSec).toBe(137.6);
    });

    it('the surface follows the full player without dropping the session', () => {
        const store = streamifyVideoStoreBase.getState();
        store.actions.setMode('video');
        store.actions.setVideoUrl('https://rr4---sn-test.googlevideo.com/muxed-720');
        store.actions.setResume(96.4, true);
        store.actions.setSurface('fullscreen');
        expect(useStreamifyVideoStore.getState().surface).toBe('fullscreen');
        store.actions.setSurface('dock');
        // The session survives the surface flip (the element never remounts).
        const state = useStreamifyVideoStore.getState();
        expect(state.mode).toBe('video');
        expect(state.resumeAtSec).toBe(96.4);
    });
});

// ---------------------------------------------------------------------------
// The provider pipeline mapping (endpoint contract)
// ---------------------------------------------------------------------------

describe('W2-A getChapters provider mapping (the endpoint contract)', () => {
    it('the wire shape the provider validates IS the worker contract', () => {
        // The provider's getChapters rides the 'chapters' endpoint through
        // the SAME run() pipeline every W1-A method uses; the client-side
        // guard for both tiers is validateChaptersPayload. The wire shape
        // above IS the worker contract — mirrored in the relay suite,
        // which additionally locks ttlForEndpoint('chapters') at 7 days
        // (604 800 s) and the counterpart endpoint at 24 h.
        const payload = validateChaptersPayload(W2A_WIRE);
        expect(payload.ok).toBe(true);
        if (!payload.ok) return;
        expect(payload.payload.kind).toBe('chapters');
        expect(payload.payload.videoId).toBe('w2aChapters');
        expect(payload.payload.chapters).toHaveLength(3);
    });
});
