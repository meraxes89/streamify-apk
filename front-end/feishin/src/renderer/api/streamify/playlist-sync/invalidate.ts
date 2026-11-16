/**
 * streamify/playlist-sync/invalidate.ts - the TanStack Query invalidation
 * seam for playlist mutations and incoming remote CRDT deltas (P3A).
 *
 * "Ensure local mutations and incoming remote CRDT deltas cleanly
 * invalidate/update Feishin's TanStack Query keys so playlist lists and
 * track tables update live without a manual reload."
 *
 * Every path that changes the replica state - controller mutations, the
 * anti-entropy merge, realtime broadcast receives - funnels through here.
 * The streamify server id is stable ('streamify'); invalidating the
 * [serverId, 'playlists'] prefix covers list/count/detail/songList/
 * songListIds, plus the infinite-loader cache the list screens read.
 */

import { STREAMIFY_SERVER_ID } from '../streamify-server-id';

import { queryClient } from '/@/renderer/lib/react-query';
import { LibraryItem } from '/@/shared/types/domain-types';

/** Invalidate every playlist query + the playlist list infinite-loader. */
export function invalidateStreamifyPlaylistQueries(serverId: string = STREAMIFY_SERVER_ID): void {
    queryClient.invalidateQueries({ queryKey: [serverId, 'playlists'] });
    queryClient.invalidateQueries({
        queryKey: [serverId, 'item-list-infinite-loader', LibraryItem.PLAYLIST],
    });
}
