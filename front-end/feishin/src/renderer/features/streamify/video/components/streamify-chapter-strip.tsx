/**
 * features/streamify/video/components/streamify-chapter-strip.tsx — the
 * W2-A chapter chips row for the Full Player (audio mode: the track's own
 * chapters — DJ sets / mixes / long uploads; video mode: the overlay's
 * fullscreen surface renders its own).
 *
 * One upstream hook point (full-screen-player.tsx renders
 * `<StreamifyChapterStrip />`, one line, logged in PATCHES.md):
 *   - clickable chapter chips with the timestamp badge,
 *   - the ACTIVE chapter highlighted live as currentTime advances (the
 *     subscribePlayerProgress discipline — state only flips at chapter
 *     boundaries, so the strip never re-renders per frame),
 *   - click seeks immediately (mediaSeekToTimestamp — the store pipeline).
 *
 * Renders null when the active media has no chapters (the honest form —
 * never a blocker, never an empty-state stub).
 */

import type { ChapterRow } from '/@/renderer/features/streamify/video/chapter-utils';

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { getChaptersTyped } from '/@/renderer/api/streamify/metadata/provider';
import {
    activeChapterAt,
    formatChapterTime,
} from '/@/renderer/features/streamify/video/chapter-utils';
import { useStreamifyVideoStore } from '/@/renderer/features/streamify/video/streamify-video.store';
import { usePlayerSong } from '/@/renderer/store';
import { usePlayerStoreBase } from '/@/renderer/store';
import { subscribePlayerProgress } from '/@/renderer/store/timestamp.store';
import { Text } from '/@/shared/components/text/text';

export const StreamifyChapterStrip = () => {
    const { t } = useTranslation();
    const currentSong = usePlayerSong();
    const mode = useStreamifyVideoStore((state) => state.mode);
    const [chapters, setChapters] = useState<ChapterRow[]>([]);
    const [activeIndex, setActiveIndex] = useState(-1);

    // In video mode the overlay owns the surface (its own chips render
    // there); the strip serves the AUDIO path's chapters.
    const activeVideoId = mode === 'audio' ? (currentSong?.id ?? null) : null;

    useEffect(() => {
        let cancelled = false;
        setActiveIndex(-1);
        if (activeVideoId === null || activeVideoId === '') {
            setChapters([]);
            return undefined;
        }
        (async () => {
            const result = await getChaptersTyped(activeVideoId);
            if (cancelled) return;
            setChapters(result.kind === 'ok' ? result.payload.chapters : []);
        })();
        return () => {
            cancelled = true;
        };
    }, [activeVideoId]);

    // Live active-chapter highlight — flips only at chapter boundaries.
    useEffect(() => {
        if (chapters.length === 0) return undefined;
        const unsubscribe = subscribePlayerProgress(({ timestamp }) => {
            const active = activeChapterAt(chapters, timestamp, null);
            const next = active?.index ?? -1;
            setActiveIndex((prev) => (prev === next ? prev : next));
        });
        return unsubscribe;
    }, [chapters]);

    if (chapters.length === 0) {
        return null;
    }

    const seekTo = (seconds: number) => {
        usePlayerStoreBase.getState().mediaSeekToTimestamp(seconds);
    };

    return (
        <div
            aria-label={t('streamify.video.chapters')}
            style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: '6px',
                justifyContent: 'center',
                maxHeight: '84px',
                overflowY: 'auto',
                padding: '0 1rem',
                width: '100%',
            }}
        >
            {chapters.map((chapter, index) => {
                const isActive = activeIndex === index;
                return (
                    <button
                        key={`${chapter.startSeconds}-${chapter.title}`}
                        onClick={() => seekTo(chapter.startSeconds)}
                        style={{
                            backgroundColor: isActive
                                ? 'var(--theme-colors-primary)'
                                : 'rgb(var(--theme-colors-background) / 0.7)',
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
    );
};
