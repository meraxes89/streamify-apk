/**
 * features/streamify/smart-sync/use-smart-sync.ts — W2-B: the runtime
 * wiring that decides WHEN the engine may pre-fetch.
 *
 * THE CLEAN-PLAY RULE (the task's concurrency discipline): sync() fires
 * only after the active track has been PLAYING continuously for
 * SMART_SYNC_CLEAN_PLAY_MS — the initial buffer is healthy, background
 * pre-fetching never competes with active playback bandwidth. Every song
 * change re-arms the window; pause/stop cancels it. The engine re-checks
 * the transport itself (defense in depth — a late timer can never fire a
 * pre-fetch while paused).
 *
 * PLAY STAMPING: each track that becomes current gets its sidecar
 * lastPlayedAt stamped (the LRU input — "recently played" in the manifest
 * context means "recently the current track"; the playTally ledger stays
 * the authority on COUNTED plays for the Discover Mix, untouched here).
 *
 * The hook is mounted ONCE (StreamifyGlobalRuntime) and self-gates on the
 * server type — stock deployments never start the engine.
 */

import { useEffect, useRef } from 'react';

import { ensureDownloadManager } from '/@/renderer/api/streamify/downloads/instance';
import { videoIdOfSongId } from '/@/renderer/features/streamify/credits/song-video-id';
import {
    defaultQueueReader,
    isTransportPlaying,
} from '/@/renderer/features/streamify/smart-sync/queue-source';
import {
    createSmartSyncEngine,
    SMART_SYNC_CLEAN_PLAY_MS,
    type SmartSyncEngine,
} from '/@/renderer/features/streamify/smart-sync/smart-sync-engine';
import { useCurrentServer } from '/@/renderer/store';
import { usePlayerStoreBase } from '/@/renderer/store/player.store';
import { useStreamifySettingsStoreBase } from '/@/renderer/store/streamify-settings.store';
import { ServerType } from '/@/shared/types/types';

let enginePromise: null | Promise<null | SmartSyncEngine> = null;

/**
 * The production engine, bound over the real download manager (memoized —
 * the runtime hook and the settings panel share ONE engine instance).
 */
export function ensureSmartSyncEngine(): Promise<null | SmartSyncEngine> {
    enginePromise ??= ensureDownloadManager().then((manager) =>
        manager === null
            ? null
            : createSmartSyncEngine({
                  isPlaying: isTransportPlaying,
                  manager,
                  readQueue: defaultQueueReader,
                  settings: productionSettings,
                  storageEstimate: productionStorageEstimate,
              }),
    );
    return enginePromise;
}

/** Test seam: drop the memoized engine (fresh wiring per case). */
export function resetSmartSyncEngineForTests(): void {
    enginePromise = null;
}

export function useSmartSync(): void {
    const server = useCurrentServer();
    const isStreamify = server?.type === ServerType.STREAMIFY;

    const timerRef = useRef<null | ReturnType<typeof setTimeout>>(null);
    const lastSongRef = useRef<null | string>(null);

    useEffect(() => {
        if (!isStreamify) return;

        const engine: Promise<null | SmartSyncEngine> = ensureSmartSyncEngine();

        const clearTimer = () => {
            if (timerRef.current !== null) {
                clearTimeout(timerRef.current);
                timerRef.current = null;
            }
        };

        /** Arm the clean-play window (fires one sync pass if it holds). */
        const scheduleCleanPlay = () => {
            clearTimer();
            if (!isTransportPlaying()) return;
            timerRef.current = setTimeout(() => {
                timerRef.current = null;
                if (!isTransportPlaying()) return; // paused mid-window: no sync
                void engine.then((e) => (e === null ? undefined : e.sync()));
            }, SMART_SYNC_CLEAN_PLAY_MS);
        };

        const unsubscribe = usePlayerStoreBase.subscribe((state, previous) => {
            // Stamp the track that just became current (the LRU input) and
            // re-arm the clean-play window — song changes fire this even
            // while the transport stays PLAYING (gapless transitions).
            const uniqueId = state.getCurrentSong()?._uniqueId ?? null;
            const songChanged = uniqueId !== lastSongRef.current;
            if (songChanged && uniqueId !== null) {
                lastSongRef.current = uniqueId;
                const song = state.getCurrentSong();
                if (song !== undefined) {
                    const videoId = videoIdOfSongId(song.id);
                    if (videoId !== null) {
                        void engine.then((e) => (e === null ? undefined : e.stampPlayed(videoId)));
                    }
                }
            }
            if (state.player.status !== previous.player.status || songChanged) {
                scheduleCleanPlay();
            }
        });

        // Arm for whatever is already playing at mount time.
        lastSongRef.current = usePlayerStoreBase.getState().getCurrentSong()?._uniqueId ?? null;
        scheduleCleanPlay();

        return () => {
            unsubscribe();
            clearTimer();
        };
    }, [isStreamify]);
}

function productionSettings(): { enabled: boolean; saveDataOverride: boolean } {
    const { smartSyncEnabled, smartSyncSaveDataOverride } =
        useStreamifySettingsStoreBase.getState().settings;
    return { enabled: smartSyncEnabled, saveDataOverride: smartSyncSaveDataOverride };
}

function productionStorageEstimate(): null | Promise<null | { quota: number; usage: number }> {
    try {
        return navigator.storage.estimate().then((estimate) => ({
            quota: estimate.quota ?? 0,
            usage: estimate.usage ?? 0,
        }));
    } catch {
        return null;
    }
}
