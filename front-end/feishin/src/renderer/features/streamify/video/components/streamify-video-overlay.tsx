/**
 * features/streamify/video/components/streamify-video-overlay.tsx — the
 * W2-A video surface: ONE stable <video> element while video mode is live.
 *
 * WHY ONE STABLE ELEMENT: the Audio ↔ Video toggle preserves the exact
 * timecode — remounting the element between the fullscreen surface (the
 * full player open) and the dock (collapsed) would reload the stream and
 * drop the position. The container repositions via CSS; the element (and
 * its WebAudio source node) never remounts.
 *
 * THE WEBAUDIO DISCIPLINE (P4-B's CORS-safe watchdog stays intact):
 *   • crossOrigin='anonymous' — a CORS-unclean video rung fails LOUDLY at
 *     load, never silently mutes through a tainted MediaElementAudioSourceNode
 *     (the same posture the two <audio> elements carry since the fork base).
 *   • ONE createMediaElementSource per element instance (a ref guard — the
 *     InvalidStateError on a second call can never fire).
 *   • The source routes through a dedicated videoGain into the SAME DSP
 *     chain the audio slots use (dsp.preampGain) so the EQ/limiter/visualizer
 *     keep working on video audio; while the EQ watchdog latched a hard
 *     bypass, the gain rewires straight to context.destination (the
 *     attach-source discipline, reactive to the latch).
 *   • Unmount: source + gain disconnect cleanly — switching back to audio
 *     never mutes or throws.
 *
 * THE SCRUBBER: the overlay's own bar — chapter tick marks when chapters
 * exist, click-to-seek, active-chapter highlight; the chapter chips row
 * (fullscreen surface) seeks on click. While video mode is live the
 * overlay is the SOLE timestamp-store writer (the WebPlayer's writers all
 * gate on the paused status the toggle set).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LuX } from 'react-icons/lu';

import { useWebAudio } from '/@/renderer/features/player/hooks/use-webaudio';
import {
    activeChapterAt,
    chapterTickFractions,
    formatChapterTime,
} from '/@/renderer/features/streamify/video/chapter-utils';
import { useStreamifyVideoChapters } from '/@/renderer/features/streamify/video/hooks/use-video-chapters';
import {
    exitVideoModeToAudio,
    registerVideoElement,
} from '/@/renderer/features/streamify/video/hooks/use-video-mode';
import { useStreamifyVideoStore } from '/@/renderer/features/streamify/video/streamify-video.store';
import { usePlayerStoreBase } from '/@/renderer/store';
import { useFullScreenPlayerStore } from '/@/renderer/store/full-screen-player.store';
import { useStreamifyEqStoreBase } from '/@/renderer/store/streamify-eq.store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { Text } from '/@/shared/components/text/text';

export const StreamifyVideoOverlay = () => {
    const { t } = useTranslation();
    const mode = useStreamifyVideoStore((state) => state.mode);
    const videoUrl = useStreamifyVideoStore((state) => state.videoUrl);
    const counterpartVideoId = useStreamifyVideoStore((state) => state.counterpartVideoId);
    const resumeAtSec = useStreamifyVideoStore((state) => state.resumeAtSec);
    const wasPlaying = useStreamifyVideoStore((state) => state.wasPlaying);
    const surface = useStreamifyVideoStore((state) => state.surface);
    const setSurface = useStreamifyVideoStore((state) => state.actions.setSurface);

    const videoRef = useRef<HTMLVideoElement | null>(null);
    const sourceRef = useRef<MediaElementAudioSourceNode | null>(null);
    const gainRef = useRef<GainNode | null>(null);
    const [positionSec, setPositionSec] = useState(resumeAtSec);
    const [durationSec, setDurationSec] = useState(0);

    const { webAudio } = useWebAudio();
    const fullScreenOpen = useFullScreenPlayerStore((state) => state.expanded);
    const eqBypassed = useStreamifyEqStoreBase((state) => state.bypassed);

    const { chapters } = useStreamifyVideoChapters(mode === 'video' ? counterpartVideoId : null);

    // The surface follows the full player: open → the hero surface;
    // collapsed → the dock (the element never remounts — CSS repositions).
    useEffect(() => {
        setSurface(fullScreenOpen ? 'fullscreen' : 'dock');
    }, [fullScreenOpen, setSurface]);

    // The WebAudio attach: one source node per element, rewired when the EQ
    // graph identity or the bypass latch changes, disconnected on unmount.
    useEffect(() => {
        const video = videoRef.current;
        if (mode !== 'video' || video === null) return undefined;
        const context = webAudio?.context ?? null;
        if (context === null) return undefined; // webAudio off — element audio
        if (sourceRef.current === null) {
            try {
                sourceRef.current = context.createMediaElementSource(video);
            } catch {
                // A second createMediaElementSource on this element — the
                // ref guard makes this unreachable; defensive only.
                return undefined;
            }
            gainRef.current = context.createGain();
            sourceRef.current.connect(gainRef.current);
        }
        const gain = gainRef.current;
        if (gain === null) return undefined;
        const wire = () => {
            try {
                gain.disconnect();
            } catch {
                /* already detached */
            }
            if (eqBypassed || !webAudio?.dsp) {
                gain.connect(context.destination);
            } else {
                gain.connect(webAudio.dsp.preampGain);
            }
        };
        wire();
        return () => {
            try {
                gain.disconnect();
            } catch {
                /* already detached */
            }
        };
    }, [mode, webAudio, eqBypassed]);

    // The element registration (the toggle reads the live timecode).
    useEffect(() => {
        registerVideoElement(mode === 'video' ? videoRef.current : null);
        return () => {
            registerVideoElement(null);
        };
    }, [mode, videoUrl]);

    // Land the preserved timecode + play state exactly once per source load.
    const handleLoadedMetadata = useCallback(() => {
        const video = videoRef.current;
        if (video === null) return;
        if (resumeAtSec > 0 && Number.isFinite(video.duration)) {
            video.currentTime = Math.min(resumeAtSec, Math.max(0, video.duration - 0.05));
        }
        setPositionSec(video.currentTime);
        setDurationSec(Number.isFinite(video.duration) ? video.duration : 0);
        if (wasPlaying) {
            void video.play().catch(() => {
                /* autoplay refusal — the user's next click resumes */
            });
        }
    }, [resumeAtSec, wasPlaying]);

    const handleTimeUpdate = useCallback(() => {
        const video = videoRef.current;
        if (video === null) return;
        setPositionSec(video.currentTime);
        // The overlay is the sole timestamp writer while video mode is live
        // (the WebPlayer's writers gate on the paused status).
        useTimestampStoreBase.getState().setTimestamp(video.currentTime);
    }, []);

    const handleDurationChange = useCallback(() => {
        const video = videoRef.current;
        if (video !== null && Number.isFinite(video.duration)) {
            setDurationSec(video.duration);
        }
    }, []);

    const handleEnded = useCallback(() => {
        // The video finished: hand the queue back to the audio path at the
        // natural boundary and advance — the WebPlayer owns transitions.
        const store = useStreamifyVideoStore.getState();
        exitVideoModeToAudio(0, false);
        if (store.wasPlaying) {
            usePlayerStoreBase.getState().mediaAutoNext();
        }
    }, []);

    const handlePlayPause = useCallback(() => {
        const video = videoRef.current;
        if (video === null) return;
        if (video.paused) {
            void video.play().catch(() => undefined);
        } else {
            video.pause();
        }
    }, []);

    const seekTo = useCallback((seconds: number) => {
        const video = videoRef.current;
        if (video === null || !Number.isFinite(video.duration)) return;
        const clamped = Math.min(Math.max(0, seconds), Math.max(0, video.duration - 0.05));
        video.currentTime = clamped;
        setPositionSec(clamped);
        useTimestampStoreBase.getState().setTimestamp(clamped);
    }, []);

    const handleScrubberClick = useCallback(
        (event: React.MouseEvent<HTMLDivElement>) => {
            const video = videoRef.current;
            if (video === null || durationSec <= 0) return;
            const rect = event.currentTarget.getBoundingClientRect();
            const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
            seekTo(fraction * durationSec);
        },
        [durationSec, seekTo],
    );

    if (mode !== 'video' || videoUrl === null) {
        return null;
    }

    const isFullscreen = surface === 'fullscreen';
    const ticks = chapterTickFractions(chapters, durationSec);
    const active = activeChapterAt(chapters, positionSec, durationSec > 0 ? durationSec : null);
    const progressFraction = durationSec > 0 ? Math.min(1, positionSec / durationSec) : 0;

    return (
        <div
            style={{
                alignItems: 'center',
                bottom: isFullscreen ? '120px' : '100px',
                display: 'flex',
                flexDirection: 'column',
                gap: isFullscreen ? '8px' : '4px',
                justifyContent: 'center',
                left: isFullscreen ? 'calc(50% - min(45vw, 45vh))' : undefined,
                padding: isFullscreen ? '0 16px' : '0',
                position: 'fixed',
                right: isFullscreen ? 'calc(50% - min(45vw, 45vh))' : '16px',
                top: isFullscreen ? '48px' : undefined,
                width: isFullscreen ? 'min(90vw, 90vh)' : 'min(280px, 32vw)',
                zIndex: 300,
            }}
        >
            <div
                onClick={handlePlayPause}
                style={{
                    backgroundColor: '#000',
                    borderRadius: isFullscreen ? '8px' : '6px',
                    boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
                    cursor: 'pointer',
                    overflow: 'hidden',
                    position: 'relative',
                    width: '100%',
                }}
            >
                <video
                    crossOrigin="anonymous"
                    key={videoUrl}
                    onDurationChange={handleDurationChange}
                    onEnded={handleEnded}
                    onLoadedMetadata={handleLoadedMetadata}
                    onTimeUpdate={handleTimeUpdate}
                    playsInline
                    ref={videoRef}
                    src={videoUrl}
                    style={{
                        aspectRatio: '16 / 9',
                        display: 'block',
                        width: '100%',
                    }}
                />
                {!isFullscreen ? (
                    <button
                        aria-label={t('streamify.video.switchToAudio')}
                        onClick={(event) => {
                            event.stopPropagation();
                            exitVideoModeToAudio(
                                videoRef.current?.currentTime ?? resumeAtSec,
                                !(videoRef.current?.paused ?? true),
                            );
                        }}
                        style={{
                            alignItems: 'center',
                            background: 'rgba(0,0,0,0.55)',
                            border: 'none',
                            borderRadius: '999px',
                            color: '#fff',
                            cursor: 'pointer',
                            display: 'inline-flex',
                            height: '24px',
                            justifyContent: 'center',
                            position: 'absolute',
                            right: 4,
                            top: 4,
                            width: '24px',
                        }}
                        type="button"
                    >
                        <LuX size={14} />
                    </button>
                ) : null}
            </div>

            {/* The chapter-aware scrubber: ticks + click-to-seek. */}
            <div
                aria-label={t('streamify.video.seekBar')}
                onClick={handleScrubberClick}
                role="slider"
                style={{
                    backgroundColor: 'rgb(var(--theme-colors-primary-400) / 0.25)',
                    borderRadius: '4px',
                    cursor: 'pointer',
                    height: isFullscreen ? '8px' : '6px',
                    position: 'relative',
                    width: '100%',
                }}
                tabIndex={0}
            >
                <div
                    style={{
                        backgroundColor: 'var(--theme-colors-primary)',
                        borderRadius: '4px',
                        height: '100%',
                        width: `${progressFraction * 100}%`,
                    }}
                />
                {ticks.map((fraction) => (
                    <div
                        key={`tick-${fraction}`}
                        style={{
                            backgroundColor: 'rgba(255,255,255,0.75)',
                            height: '100%',
                            left: `${fraction * 100}%`,
                            position: 'absolute',
                            top: 0,
                            width: '2px',
                        }}
                    />
                ))}
            </div>

            {isFullscreen && chapters.length > 0 ? (
                <div
                    style={{
                        display: 'flex',
                        flexDirection: 'row',
                        flexWrap: 'wrap',
                        gap: '6px',
                        justifyContent: 'center',
                        maxHeight: '96px',
                        overflowY: 'auto',
                        width: '100%',
                    }}
                >
                    {chapters.map((chapter, index) => {
                        const isActive = active?.index === index;
                        return (
                            <button
                                key={`${chapter.startSeconds}-${chapter.title}`}
                                onClick={() => seekTo(chapter.startSeconds)}
                                style={{
                                    backgroundColor: isActive
                                        ? 'var(--theme-colors-primary)'
                                        : 'rgb(var(--theme-colors-background) / 0.85)',
                                    border: `1px solid ${
                                        isActive
                                            ? 'var(--theme-colors-primary)'
                                            : 'rgb(var(--theme-colors-text) / 0.2)'
                                    }`,
                                    borderRadius: '999px',
                                    color: isActive ? '#fff' : 'inherit',
                                    cursor: 'pointer',
                                    fontSize: '12px',
                                    padding: '3px 10px',
                                }}
                                type="button"
                            >
                                <Text size="xs" span>
                                    {chapter.title}
                                </Text>
                                <Text size="xs" span style={{ marginLeft: 6, opacity: 0.65 }}>
                                    {formatChapterTime(chapter.startSeconds)}
                                </Text>
                            </button>
                        );
                    })}
                </div>
            ) : null}
        </div>
    );
};
