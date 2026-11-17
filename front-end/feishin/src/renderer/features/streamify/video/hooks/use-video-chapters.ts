/**
 * features/streamify/video/hooks/use-video-chapters.ts — the W2-A chapter
 * fetch (getChapters — the 7 d-TTL Edge KV endpoint).
 *
 * One fetch per active media id: the COUNTERPART's videoId while video
 * mode is live (official music videos carry the chapters), the audio
 * track's own videoId otherwise (DJ sets / mixes / long uploads). The
 * provider's snapshot layer dedupes repeat calls; not-found caches 15 min
 * (the honest "this video has no chapters" — the UI simply hides).
 */

import type { ChapterRow } from '/@/renderer/features/streamify/video/chapter-utils';

import { useEffect, useState } from 'react';

import { getChaptersTyped } from '/@/renderer/api/streamify/metadata/provider';

export interface VideoChapters {
    chapters: ChapterRow[];
    /** false while the fetch is in flight (the strip skeleton). */
    loaded: boolean;
}

export function useStreamifyVideoChapters(activeVideoId: null | string): VideoChapters {
    const [state, setState] = useState<VideoChapters>({ chapters: [], loaded: false });

    useEffect(() => {
        let cancelled = false;
        if (activeVideoId === null || activeVideoId === '') {
            setState({ chapters: [], loaded: true });
            return undefined;
        }
        setState({ chapters: [], loaded: false });
        (async () => {
            // The typed seam (the unified provider's accessor — same shape
            // as the importer's getPlaylistTyped).
            const result = await getChaptersTyped(activeVideoId);
            if (cancelled) return;
            if (result.kind === 'ok') {
                setState({ chapters: result.payload.chapters, loaded: true });
            } else {
                // not-found / rate-limited / parse-error: the honest empty
                // form — chapters are an enhancement, never a blocker.
                setState({ chapters: [], loaded: true });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [activeVideoId]);

    return state;
}
