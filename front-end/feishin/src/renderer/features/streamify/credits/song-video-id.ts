/**
 * features/streamify/credits/song-video-id.ts — W2-B: songId → the stream
 * videoId, for the song the Liner Notes drawer was opened on.
 *
 * Two honest spellings (the W1-B id-namespace contract): `ytm:<videoId>`
 * ids (Automix / scoped-search / discover songs outside the snapshot)
 * decode by prefix; cloud track ids resolve through the live snapshot row
 * (the tracks table's stream_url IS the videoId). Resolves null when
 * neither lane knows a video — the action then stays honest (no credits
 * fetch, no fabricated drawer).
 */

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';

export const YTM_SONG_ID_PREFIX = 'ytm:';

/** songId (cloud or ytm:) → the stream videoId; null when unknown. */
export function videoIdOfSongId(songId: string): null | string {
    const ytm = videoIdOfYtmSongId(songId);
    if (ytm !== null && ytm !== '') return ytm;
    const snapshot = currentStreamifyLibrary();
    const row = snapshot?.songById.get(songId);
    const videoId = row?.streamUrl;
    return typeof videoId === 'string' && videoId !== '' ? videoId : null;
}

/** A ytm:-namespaced song id → its videoId (null for other ids). */
export function videoIdOfYtmSongId(songId: string): null | string {
    return songId.startsWith(YTM_SONG_ID_PREFIX) ? songId.slice(YTM_SONG_ID_PREFIX.length) : null;
}
