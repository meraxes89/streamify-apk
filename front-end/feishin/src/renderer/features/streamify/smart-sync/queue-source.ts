/**
 * features/streamify/smart-sync/queue-source.ts — W2-B: the play queue →
 * Smart Sync lookahead (N+1 / N+2).
 *
 * Reads the player store's queue state and derives the NEXT tracks in the
 * order playback will actually visit them: with track-shuffle active the
 * `shuffled` index sequence drives the order (the store's own mapping
 * helpers), otherwise the default order after `player.index`. Repeat-all
 * wraps the lookahead (the queue genuinely revisits); repeat-one and the
 * queue's end do not. Tracks resolve to DownloadSpec facts (videoId via
 * the ytm: namespace or the snapshot row) — honest nulls skip a slot
 * instead of fabricating a pre-fetch.
 */

import type { DownloadSpec } from '/@/renderer/api/streamify/downloads/download-manager';
import type { QueueSong } from '/@/shared/types/domain-types';

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { videoIdOfSongId } from '/@/renderer/features/streamify/credits/song-video-id';
import { mapShuffledToQueueIndex, usePlayerStoreBase } from '/@/renderer/store/player.store';
import { PlayerRepeat, PlayerShuffle, PlayerStatus } from '/@/shared/types/types';

export interface QueueLookahead {
    /** The videoId actually playing (null = nothing resolvable). */
    currentVideoId: null | string;
    /** The next tracks in play order (bounded by the caller). */
    upcoming: DownloadSpec[];
}

/** The engine's queue reader (injectable in tests). */
export function defaultQueueReader(): QueueLookahead {
    return queueLookahead(2);
}

/** Is the transport playing (the runtime hook's clean-play gate input)? */
export function isTransportPlaying(): boolean {
    return usePlayerStoreBase.getState().player.status === PlayerStatus.PLAYING;
}

/**
 * The queue's next tracks in play order. Pure read of the live player
 * store — injected in tests via `readQueueState`.
 */
export function queueLookahead(count: number): QueueLookahead {
    const state = usePlayerStoreBase.getState();
    const songs = state.queue.songs;
    const defaultOrder = state.queue.default
        .map((uniqueId) => songs[uniqueId])
        .filter((song): song is QueueSong => song !== undefined);

    const current = state.getCurrentSong();
    const currentVideoId = current !== undefined ? (videoIdOfSongId(current.id) ?? null) : null;

    const upcoming: DownloadSpec[] = [];
    const push = (index: number | undefined): boolean => {
        if (index === undefined) return false;
        const song = defaultOrder[index];
        if (song === undefined) return false;
        const spec = specOf(song);
        if (spec === null) return true; // unplayable/unresolvable slot: skip, keep walking
        if (currentVideoId !== null && spec.videoId === currentVideoId) return true;
        upcoming.push(spec);
        return upcoming.length < count;
    };

    if (
        state.player.shuffle === PlayerShuffle.TRACK &&
        state.queue.shuffled.length > 0 &&
        defaultOrder.length > 0
    ) {
        // Shuffle drives playback: walk the shuffled sequence after the
        // current position (repeat-all wraps once; other modes stop at the end).
        const shuffled = state.queue.shuffled;
        const currentQueueIndex = state.player.index;
        const position = shuffled.indexOf(currentQueueIndex);
        const start = position >= 0 ? position + 1 : 0;
        let keepWalking = true;
        for (let offset = start; offset < shuffled.length && keepWalking; offset += 1) {
            keepWalking = push(mapShuffledToQueueIndex(offset, shuffled));
        }
        if (keepWalking && state.player.repeat === PlayerRepeat.ALL && upcoming.length < count) {
            for (let offset = 0; offset < start && keepWalking; offset += 1) {
                keepWalking = push(mapShuffledToQueueIndex(offset, shuffled));
            }
        }
        return { currentVideoId, upcoming };
    }

    // Default order: walk after the current index (repeat-all wraps once).
    const startIndex = state.player.index + 1;
    let keepWalking = true;
    for (let index = startIndex; index < defaultOrder.length && keepWalking; index += 1) {
        keepWalking = push(index);
    }
    if (keepWalking && state.player.repeat === PlayerRepeat.ALL && upcoming.length < count) {
        for (let index = 0; index < startIndex && keepWalking; index += 1) {
            keepWalking = push(index);
        }
    }
    return { currentVideoId, upcoming };
}

function specOf(song: QueueSong): DownloadSpec | null {
    const videoId = videoIdOfSongId(song.id);
    if (videoId === null) return null;
    return {
        album: song.album ?? null,
        artist: song.artistName ?? '',
        durationSec: song.duration > 0 ? song.duration / 1000 : null,
        songId: song.id,
        source: 'smart-sync',
        title: song.name,
        videoId,
    };
}

/** Snapshot access re-exported for tests that build fake libraries. */
export { currentStreamifyLibrary };
