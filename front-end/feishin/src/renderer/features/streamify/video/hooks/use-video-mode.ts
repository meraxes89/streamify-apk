/**
 * features/streamify/video/hooks/use-video-mode.ts — the W2-A toggle
 * controller: the timecode-synced Audio ↔ Official Music Video switch.
 *
 * THE CONTRACT (front-end/decisions/waves/W2A-video-chapters-meta-isolation.md):
 *   Audio → Video:
 *     1. resolve the official music-video counterpart (provider.getVideoCounterpart
 *        — Edge KV, 24 h TTL; videoId null / not-found ⇒ hide + disable, audio
 *        never interrupts),
 *     2. resolve the muxed video stream (getStreamUrl { mode: 'video' } — the
 *        additive resolver branch; StreamifyVideoModeMissError ⇒ same clean
 *        degrade),
 *     3. capture currentTime + isPlaying, pause the audio element through the
 *        player store's OWN status pipeline (mediaPause — the WebPlayer reacts,
 *        scrobble pauses honestly), then hand playback to the overlay, which
 *        seeks to the preserved timecode and resumes when wasPlaying.
 *   Video → Audio:
 *     1. capture the <video> element's currentTime (the overlay registers its
 *        element in this module's singleton slot),
 *     2. mediaSeekToTimestamp(preserved) + mediaPlay() when the video was
 *        playing — the audio element (still loaded, merely paused) resumes at
 *        the exact second.
 *   Track change while in video mode ⇒ exit to audio first (the WebPlayer
 *   owns cross-track transitions; the overlay never races the queue).
 */

import { useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import { getVideoCounterpartTyped } from '/@/renderer/api/streamify/metadata/provider';
import {
    getStreamUrl,
    StreamifyVideoModeMissError,
} from '/@/renderer/api/streamify/playback/resolver/audio/get-stream-url';
import { relayBaseCandidate } from '/@/renderer/api/streamify/playback/resolver/lib/relayBase';
import { captureTimecode } from '/@/renderer/features/streamify/video/chapter-utils';
import {
    streamifyVideoStoreBase,
    useStreamifyVideoStore,
} from '/@/renderer/features/streamify/video/streamify-video.store';
import { useAuthStore } from '/@/renderer/store';
import { usePlayerStoreBase } from '/@/renderer/store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { logger } from '/@/renderer/utils/logger';
import { toast } from '/@/shared/components/toast/toast';
import { PlayerStatus, ServerType } from '/@/shared/types/types';

/**
 * The live <video> element slot — the overlay registers its element on
 * mount and unregisters on unmount, so the controller can read the exact
 * current timecode without dragging a ref through the React tree.
 */
const videoElementSlot: { current: HTMLVideoElement | null } = { current: null };

/**
 * Hand playback back to the audio path at the preserved timecode: seek the
 * (still-loaded, merely paused) audio element, resume through the store's
 * own pipeline when the video was playing, then drop the video session.
 * Shared by the toggle (Video → Audio) and the overlay's ended handler.
 */
export function exitVideoModeToAudio(resumeAtSec: number, wasPlaying: boolean): void {
    const store = streamifyVideoStoreBase.getState();
    store.actions.setSwitching(true);
    usePlayerStoreBase.getState().mediaSeekToTimestamp(resumeAtSec);
    if (wasPlaying) {
        usePlayerStoreBase.getState().mediaPlay();
    }
    store.actions.reset();
}

export function registerVideoElement(element: HTMLVideoElement | null): void {
    videoElementSlot.current = element;
}

export function useStreamifyVideoMode(): { toggle: () => void } {
    const { t } = useTranslation();
    const mode = useStreamifyVideoStore((state) => state.mode);
    const switching = useStreamifyVideoStore((state) => state.switching);
    const modeRef = useRef(mode);
    modeRef.current = mode;
    const switchingRef = useRef(switching);
    switchingRef.current = switching;

    const toggleToAudio = useCallback(() => {
        const video = videoElementSlot.current;
        const wasPlaying =
            video !== null && !video.paused && !video.ended
                ? true
                : streamifyVideoStoreBase.getState().wasPlaying;
        const resumeAtSec = captureTimecode(
            video?.currentTime ?? streamifyVideoStoreBase.getState().resumeAtSec,
            video !== null && Number.isFinite(video.duration) ? video.duration : null,
        );
        exitVideoModeToAudio(resumeAtSec, wasPlaying);
    }, []);

    const toggleToVideo = useCallback(async () => {
        const store = streamifyVideoStoreBase.getState();
        const sourceVideoId = currentSongVideoId();
        if (sourceVideoId === null) return;

        store.actions.setSwitching(true);
        try {
            // 1. The counterpart (Edge KV, 24 h TTL) — videoId null and the
            //    failure results alike are the honest "no official video"
            //    form: disable the toggle, keep audio playing. The typed
            //    seam: the post-#200 unified provider keeps the W1-B DTO
            //    contract chapters/counterpart-free — the typed accessors
            //    are the video feature's surface (getPlaylistTyped's shape).
            const counterpart = await getVideoCounterpartTyped(sourceVideoId);
            if (counterpart.kind !== 'ok' || counterpart.payload.videoId === null) {
                store.actions.setSwitching(false);
                store.actions.setUnavailable(true);
                toast.info({ message: t('streamify.video.noVideoAvailable') });
                return;
            }
            const counterpartVideoId = counterpart.payload.videoId;

            // 2. The muxed video stream (the additive resolver branch — the
            //    audio rungs are untouched; a miss degrades cleanly).
            let videoUrl: string;
            try {
                const resolution = await getStreamUrl(counterpartVideoId, {
                    mode: 'video',
                    relayBase: relayBaseCandidate(),
                });
                videoUrl = resolution.url;
            } catch (err) {
                if (err instanceof StreamifyVideoModeMissError) {
                    store.actions.setSwitching(false);
                    store.actions.setUnavailable(true);
                    toast.info({ message: t('streamify.video.noVideoAvailable') });
                    return;
                }
                throw err;
            }

            // 3. Capture the timecode + play state, then pause the audio
            //    element through the store's own status pipeline.
            const player = usePlayerStoreBase.getState().player;
            const wasPlaying = player.status === PlayerStatus.PLAYING;
            const resumeAtSec = captureTimecode(useTimestampStoreBase.getState().timestamp, null);
            store.actions.setCounterpart(counterpartVideoId);
            store.actions.setSourceVideoId(sourceVideoId);
            store.actions.setVideoUrl(videoUrl);
            store.actions.setResume(resumeAtSec, wasPlaying);
            store.actions.setUnavailable(false);
            if (wasPlaying) {
                usePlayerStoreBase.getState().mediaPause();
            }
            store.actions.setMode('video');
        } catch (err) {
            logger.warn('streamify: video toggle failed', {
                error: err instanceof Error ? err.message : String(err),
            });
            streamifyVideoStoreBase.getState().actions.setSwitching(false);
            toast.error({ message: t('streamify.video.toggleFailed') });
        } finally {
            streamifyVideoStoreBase.getState().actions.setSwitching(false);
        }
    }, [t]);

    const toggle = useCallback(() => {
        if (switchingRef.current) return;
        if (modeRef.current === 'video') {
            toggleToAudio();
        } else {
            void toggleToVideo();
        }
    }, [toggleToAudio, toggleToVideo]);

    // Track changes while in video mode: exit to audio FIRST (the WebPlayer
    // owns cross-track transitions — the overlay must never race the queue).
    useEffect(() => {
        if (mode !== 'video') return undefined;
        const sourceAtOpen = streamifyVideoStoreBase.getState().sourceVideoId;
        return usePlayerStoreBase.subscribe((state) => {
            const current = state.getCurrentSong();
            if (current === undefined || current === null) return;
            if (current.id !== sourceAtOpen) {
                // The queue moved on — hand playback back to the audio path
                // at the track's own start (the queue advanced, not a seek).
                const store = streamifyVideoStoreBase.getState();
                store.actions.reset();
            }
        });
    }, [mode]);

    // The source videoId bookkeeping (the store carries it for the chapters
    // hook + the track-change guard above).
    useEffect(() => {
        const store = streamifyVideoStoreBase.getState();
        const sourceVideoId = currentSongVideoId();
        if (
            sourceVideoId !== null &&
            sourceVideoId !== store.sourceVideoId &&
            store.mode === 'audio'
        ) {
            // A new audio track: clear the previous counterpart/session state.
            store.actions.reset();
        }
    }, [mode]);

    return { toggle };
}

/** The active media's videoId — the audio track's id (the streamify song id). */
function currentSongVideoId(): null | string {
    const song = usePlayerStoreBase.getState().getCurrentSong();
    if (!song) return null;
    const serverType = useAuthStore.getState().currentServer?.type;
    if (serverType !== ServerType.STREAMIFY) return null;
    return song.id;
}
