/**
 * features/streamify/discover/use-discover-mix.ts — W2-B: the Discover
 * Mix shelf's data hook.
 *
 * One mix per session (regenerated on demand — "Refresh Mix"); reads the
 * playTally ledger + the live snapshot, crawls the W1 provider seam, and
 * exposes the playable Song[] (ytm:-namespaced for tracks outside the
 * library). Errors resolve the honest error state — the shelf never
 * fabricates tracks. Playing is the SHELF's job (the player context lives
 * in its React tree).
 */

import type { Song } from '/@/shared/types/domain-types';

import { useCallback, useEffect, useState } from 'react';

import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { resolveMetaSource } from '/@/renderer/api/streamify/meta-bridge';
import { readTally } from '/@/renderer/api/streamify/playback/play-tally';
import {
    type DiscoverMix,
    generateDiscoverMix,
} from '/@/renderer/features/streamify/discover/discover-engine';
import { discoverTrackToSong } from '/@/renderer/features/streamify/discover/discover-songs';
import { useCurrentServerId } from '/@/renderer/store';

export type DiscoverMixStatus =
    | { kind: 'empty' }
    | { kind: 'error' }
    | { kind: 'loading' }
    | { kind: 'ready'; mix: DiscoverMix; songs: Song[] };

export function useDiscoverMix(): {
    refresh: () => void;
    status: DiscoverMixStatus;
} {
    const [status, setStatus] = useState<DiscoverMixStatus>({ kind: 'loading' });
    const [nonce, setNonce] = useState(0);
    const serverId = useCurrentServerId();

    useEffect(() => {
        let cancelled = false;
        setStatus({ kind: 'loading' });
        void (async () => {
            const source = await resolveMetaSource();
            if (source === null) {
                if (!cancelled) setStatus({ kind: 'empty' });
                return;
            }
            try {
                const mix = await generateDiscoverMix({
                    snapshot: currentStreamifyLibrary(),
                    source,
                    tally: readTally(),
                });
                if (cancelled) return;
                if (mix === null) {
                    setStatus({ kind: 'empty' });
                    return;
                }
                const snapshot = currentStreamifyLibrary();
                setStatus({
                    kind: 'ready',
                    mix,
                    songs: mix.tracks.map((track) =>
                        discoverTrackToSong(track, serverId ?? '', snapshot),
                    ),
                });
            } catch {
                if (!cancelled) setStatus({ kind: 'error' });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [nonce, serverId]);

    const refresh = useCallback(() => {
        setNonce((n) => n + 1);
    }, []);

    return { refresh, status };
}
