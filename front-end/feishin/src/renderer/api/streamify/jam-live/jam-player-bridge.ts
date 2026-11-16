/**
 * streamify/jam-live/jam-player-bridge.ts - the default JamPlayerBridge
 * binding feishin's player store (P4B).
 *
 * The bridge is the ONLY place the Jam layer touches playback: the host
 * subscribes to player events and broadcasts deltas; the follower drives
 * the same store (setQueue / play / pause / seek). Remote-control features
 * (features/remote/) established this store-driven pattern; the bridge is
 * its Jam-shaped distillation, kept injectable so controller tests run
 * network-free against a fake.
 */

import type { QueueSong } from '/@/shared/types/domain-types';

import type { JamTrackPayload } from './jam-live-protocol';
import type { JamPlayerBridge, JamPlayerEvent } from './jam-session-controller';

import {
    subscribeCurrentTrack,
    subscribePlayerSeekToTimestamp,
    subscribePlayerStatus,
    usePlayerStoreBase,
} from '/@/renderer/store/player.store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { PlayerStatus } from '/@/shared/types/types';

const toPayload = (song: null | QueueSong | undefined): JamTrackPayload | null => {
    if (!song) return null;
    const { _contextPlaylistId, _uniqueId, ...payload } = song;
    void _contextPlaylistId;
    void _uniqueId;
    return payload as JamTrackPayload;
};

export const createJamPlayerBridge = (): JamPlayerBridge => {
    let lastReportedTrack: JamTrackPayload | null = null;

    return {
        applyTrack(track, positionMs) {
            const uniqueId = `${track.id}-jam-${Date.now().toString(36)}-${Math.random()
                .toString(36)
                .slice(2, 8)}`;
            const song: QueueSong = {
                ...(track as QueueSong),
                _uniqueId: uniqueId,
            };
            usePlayerStoreBase.getState().setQueue([song], 0);
            if (positionMs > 1_000) {
                // The engine consumes the pending seek once the new source is
                // ready; if the seek races the load, the next host tick's
                // drift corrector re-lands it.
                usePlayerStoreBase.getState().mediaSeekToTimestamp(positionMs / 1000);
            }
        },
        getCurrentTrack: () => lastReportedTrack,
        getPositionMs: () => Math.max(0, useTimestampStoreBase.getState().timestamp * 1000),
        isPlaying: () => usePlayerStoreBase.getState().player.status === PlayerStatus.PLAYING,
        next: () => usePlayerStoreBase.getState().mediaNext(false),
        pause: () => usePlayerStoreBase.getState().mediaPause(),
        play: () => {
            const store = usePlayerStoreBase.getState();
            if (store.player.status !== PlayerStatus.PLAYING) {
                store.mediaPlay();
            }
        },
        seekTo(positionMs) {
            usePlayerStoreBase.getState().mediaSeekToTimestamp(Math.max(0, positionMs) / 1000);
        },
        subscribe(handler: (event: JamPlayerEvent) => void): () => void {
            const stopTrack = subscribeCurrentTrack(({ song }) => {
                const payload = toPayload(song);
                lastReportedTrack = payload;
                handler({ track: payload, type: 'track' });
            });
            const stopStatus = subscribePlayerStatus(({ status }) => {
                handler({ isPlaying: status === PlayerStatus.PLAYING, type: 'status' });
            });
            const stopSeek = subscribePlayerSeekToTimestamp(({ timestamp }) => {
                if (timestamp > 0) {
                    handler({ positionMs: timestamp * 1000, type: 'seek' });
                }
            });
            return () => {
                stopTrack();
                stopStatus();
                stopSeek();
            };
        },
    };
};
