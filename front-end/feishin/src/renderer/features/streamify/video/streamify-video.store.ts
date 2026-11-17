/**
 * store/streamify-video.store.ts — the W2-A Audio ↔ Official Music Video
 * mode state (features/streamify/video/).
 *
 * Deliberately DUMB state (the house store discipline — see
 * streamify-jam.store.ts): no resolution logic, no side effects. The
 * controller hook (use-video-mode.ts) drives every transition; components
 * read. `resumeAtSec` / `wasPlaying` ride along the mode switch so the
 * overlay can land the <video> element on the exact preserved timecode and
 * play state (the timecode-synced switch contract).
 */

import { create } from 'zustand';

export type StreamifyVideoMode = 'audio' | 'video';
export interface StreamifyVideoState {
    actions: {
        reset: () => void;
        setCounterpart: (counterpartVideoId: null | string) => void;
        setMode: (mode: StreamifyVideoMode) => void;
        setResume: (resumeAtSec: number, wasPlaying: boolean) => void;
        setSourceVideoId: (sourceVideoId: null | string) => void;
        setSurface: (surface: StreamifyVideoSurface) => void;
        setSwitching: (switching: boolean) => void;
        setUnavailable: (unavailable: boolean) => void;
        setVideoUrl: (videoUrl: null | string) => void;
    };
    /** The resolved official-music-video counterpart's videoId (null while unresolved). */
    counterpartVideoId: null | string;
    /** 'audio' (the default — the stock WebPlayer path) | 'video' (the overlay owns playback). */
    mode: StreamifyVideoMode;
    /** The audio position (seconds) + play state captured at the last toggle — the overlay seeks here on load. */
    resumeAtSec: number;
    /** The audio track's videoId the current session resolves against. */
    sourceVideoId: null | string;
    /** 'fullscreen' while the full player is open (the overlay covers the art column) | the small dock. */
    surface: StreamifyVideoSurface;
    /** A toggle is in flight (capturing / resolving / swapping) — the button shows its loading state. */
    switching: boolean;
    /** The counterpart or video stream resolution failed — the toggle disables (audio never interrupts). */
    unavailable: boolean;
    /** The resolved muxed video stream URL (the video ladder's top rung). */
    videoUrl: null | string;
    /** The play state captured at the last toggle. */
    wasPlaying: boolean;
}

export type StreamifyVideoSurface = 'dock' | 'fullscreen';

export const useStreamifyVideoStore = create<StreamifyVideoState>()((set) => ({
    actions: {
        reset: () =>
            set({
                counterpartVideoId: null,
                mode: 'audio',
                resumeAtSec: 0,
                sourceVideoId: null,
                surface: 'dock',
                switching: false,
                unavailable: false,
                videoUrl: null,
                wasPlaying: false,
            }),
        setCounterpart: (counterpartVideoId) => set({ counterpartVideoId }),
        setMode: (mode) => set({ mode }),
        setResume: (resumeAtSec, wasPlaying) => set({ resumeAtSec, wasPlaying }),
        setSourceVideoId: (sourceVideoId) => set({ sourceVideoId }),
        setSurface: (surface) => set({ surface }),
        setSwitching: (switching) => set({ switching }),
        setUnavailable: (unavailable) => set({ unavailable }),
        setVideoUrl: (videoUrl) => set({ videoUrl }),
    },
    counterpartVideoId: null,
    mode: 'audio',
    resumeAtSec: 0,
    sourceVideoId: null,
    surface: 'dock',
    switching: false,
    unavailable: false,
    videoUrl: null,
    wasPlaying: false,
}));

/** Non-reactive reads (the controller drives transitions imperatively). */
export const streamifyVideoStoreBase = useStreamifyVideoStore;
