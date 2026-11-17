/**
 * features/streamify/media-session/use-streamify-media-session.ts — the
 * W3-A mount hook: the OS lock-screen bridge + the deep-link entry.
 *
 * MOUNTED FROM the player bar (the StreamifyMediaSessionBridge component
 * renders inside right-controls.tsx — the same A14 seam as every
 * Streamify player-bar integration). The player bar appears exactly when
 * the app shell is up with a resolved server — which IS "after
 * SERVER_LOCK / auth resolution", the task's boot timing.
 *
 * WHAT RUNS:
 *   1. The position-state + chapter-aware-metadata tick: subscribes the
 *      timestamp/status stores (the same stable Zustand subscriptions the
 *      stock media-session hook uses), throttled to whole-second changes,
 *      and applies the bridge tick — positionState every tick, metadata
 *      only when the track or the ACTIVE CHAPTER changes (W2-A's
 *      chapter-utils boundary rule).
 *   2. The deep-link consumption: parse window.location.search once on
 *      mount; ?play= resolves through the library snapshot (same account
 *      catalog; an unknown id is an honest toast, never a fabricated
 *      song) and starts playback; ?import= opens the W1-A importer modal
 *      pre-populated; the consumed keys are scrubbed with
 *      history.replaceState.
 *
 * A session that is not Streamify: the bridge stays dormant (the stock
 * media-session hook keeps serving it untouched) and deep links are
 * ignored.
 */

import { t } from 'i18next';
import { useEffect, useRef } from 'react';

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { getChaptersTyped } from '/@/renderer/api/streamify/metadata/provider';
import { normalizeSong } from '/@/renderer/api/streamify/normalize';
import { STREAMIFY_SERVER_ID } from '/@/renderer/api/streamify/streamify-server-id';
import { openYtmImportModal } from '/@/renderer/features/streamify/import/components/ytm-import-modal';
import {
    cleanDeepLinkUrl,
    parseDeepLink,
} from '/@/renderer/features/streamify/media-session/deep-link';
import {
    applyBridgeTick,
    type BridgeTrackMeta,
    buildMediaMetadataInit,
} from '/@/renderer/features/streamify/media-session/media-session-bridge';
import {
    activeChapterAt,
    type ChapterRow,
} from '/@/renderer/features/streamify/video/chapter-utils';
import { useStreamifyVideoStore } from '/@/renderer/features/streamify/video/streamify-video.store';
import { useAuthStore } from '/@/renderer/store';
import { subscribePlayerStatus, usePlayerStoreBase } from '/@/renderer/store';
import { subscribePlayerProgress, useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { toast } from '/@/shared/components/toast/toast';
import { PlayerStatus, ServerType } from '/@/shared/types/types';

export function useStreamifyMediaSession(): void {
    const isStreamify = useAuthStore((state) => state.currentServer?.type) === ServerType.STREAMIFY;

    // ------------------------------------------------------------------
    // 1. The position-state + chapter-aware metadata tick
    // ------------------------------------------------------------------
    const chaptersRef = useRef<ChapterRow[]>([]);
    const lastChapterIndexRef = useRef(-1);
    const lastTrackIdRef = useRef<null | string>(null);

    useEffect(() => {
        if (!isStreamify) return undefined;

        const readTrackMeta = (): BridgeTrackMeta => {
            const song = usePlayerStoreBase.getState().getCurrentSong();
            if (song === null || song === undefined) {
                return { album: null, artist: null, title: null, videoId: null };
            }
            return {
                album: song.album ?? null,
                artist: song.artistName ?? null,
                title: song.name ?? null,
                videoId: song.id ?? null,
            };
        };

        const tick = (force = false) => {
            const store = usePlayerStoreBase.getState();
            const song = store.getCurrentSong();
            const status = store.player.status;
            const position = useTimestampStoreBase.getState().timestamp;
            const durationSec = song && song.duration > 0 ? song.duration / 1000 : null;
            const trackId = song?.id ?? null;

            // Chapter re-evaluation (W2-A's boundary rule — the metadata
            // rewrites only at track or chapter boundaries, not per-tick).
            const active = activeChapterAt(chaptersRef.current, position, durationSec);
            const chapterIndex = active?.index ?? -1;
            const trackChanged = trackId !== lastTrackIdRef.current;
            const chapterChanged = chapterIndex !== lastChapterIndexRef.current;

            applyBridgeTick(window.navigator, {
                metadataInit:
                    force || trackChanged || chapterChanged
                        ? buildMediaMetadataInit(readTrackMeta(), active?.chapter.title ?? null)
                        : null,
                playbackState: status === PlayerStatus.PLAYING ? 'playing' : 'paused',
                positionState:
                    durationSec !== null
                        ? { durationSec, playbackRate: 1, positionSec: position }
                        : null,
            });

            lastTrackIdRef.current = trackId;
            lastChapterIndexRef.current = chapterIndex;
        };

        // Chapters follow the active audio track (the overlay owns its own
        // fetch while video mode is live). One fetch per track change.
        const unsubscribeSong = usePlayerStoreBase.subscribe((state) => {
            const song = state.getCurrentSong();
            const trackId = song?.id ?? null;
            if (trackId === lastTrackIdRef.current) return;
            lastTrackIdRef.current = trackId;
            lastChapterIndexRef.current = -1;
            chaptersRef.current = [];
            if (trackId === null) return;
            if (useStreamifyVideoStore.getState().mode === 'video') return;
            void (async () => {
                const result = await getChaptersTyped(trackId);
                if (result.kind === 'ok') {
                    chaptersRef.current = result.payload.chapters;
                    tick(true);
                }
            })();
        });

        const unsubscribeStatus = subscribePlayerStatus(() => {
            tick(true);
        });

        // The position tick — throttled to whole seconds (the OS lock
        // screen does not need sub-second scrub state).
        let lastWholeSecond = -1;
        const unsubscribeProgress = subscribePlayerProgress(({ timestamp }) => {
            const whole = Math.floor(timestamp);
            if (whole === lastWholeSecond) return;
            lastWholeSecond = whole;
            tick();
        });

        tick(true);
        return () => {
            unsubscribeSong();
            unsubscribeStatus();
            unsubscribeProgress();
        };
    }, [isStreamify]);

    // ------------------------------------------------------------------
    // 2. The deep-link consumption (once per mount — the boot entry)
    // ------------------------------------------------------------------
    useEffect(() => {
        if (!isStreamify) return;
        try {
            const url = new URL(window.location.href);
            const parsed = parseDeepLink(url);
            if (parsed === null) return;

            // Scrub FIRST (a slow resolve must not leave the param behind
            // for a refresh to re-trigger).
            window.history.replaceState(
                window.history.state,
                '',
                cleanDeepLinkUrl(url, parsed.consumedKeys),
            );

            if (parsed.target.kind === 'play') {
                const snapshot = currentStreamifyLibrary();
                const row =
                    snapshot !== null ? snapshot.songById.get(parsed.target.videoId) : undefined;
                if (row === undefined || snapshot === null) {
                    toast.info({ message: t('streamify.mediaSession.playNotFound') });
                    return;
                }
                const song = normalizeSong(row, snapshot, STREAMIFY_SERVER_ID);
                usePlayerStoreBase.getState().setQueue([song], 0, 0);
                usePlayerStoreBase.getState().mediaPlay();
            } else {
                // The importer modal reads its prefill from this session key
                // on open (the W1-A modal seeds its input from it).
                try {
                    window.sessionStorage.setItem('streamify.import.prefill', parsed.target.value);
                } catch {
                    // private mode — the modal opens unpopulated
                }
                openYtmImportModal();
            }
        } catch {
            // A malformed URL state must never break boot
        }
    }, [isStreamify]);
}
