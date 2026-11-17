/**
 * features/streamify/credits/use-song-credits.ts — W2-B: the Liner Notes
 * data hook.
 *
 * Fetches structured song credits through the W2-B meta bridge
 * (provider.getCredits — the Edge KV /ytm/meta?endpoint=credits lane,
 * 7-day TTL, W1-A's relay normalizer). Session-cached per videoId: the
 * Edge KV + provider snapshot already cache, this is the UI-side request
 * coalescer so a re-opened drawer never re-flies.
 *
 * HONEST STATES (never fabricated):
 *   - 'loading' — in flight.
 *   - 'empty'  — the provider answered but YTM carries no credits shelf
 *     for this track (the honest empty the task demands), or the Wave 1
 *     provider is not installed yet (pre-rebase / stock deployments).
 *   - 'error'  — the call threw (network) — retryable.
 */

import { useCallback, useEffect, useState } from 'react';

import { type MetaCredits, resolveMetaSource } from '/@/renderer/api/streamify/meta-bridge';

export type SongCreditsState =
    | { credits: MetaCredits; kind: 'loaded' }
    | { kind: 'empty' }
    | { kind: 'error' }
    | { kind: 'loading' };

/** Session cache: videoId → the settled state (errors are NOT cached). */
const sessionCache = new Map<
    string,
    { credits: MetaCredits; kind: 'loaded' } | { kind: 'empty' }
>();

/** Test seam: drop the session cache between cases. */
export function resetSongCreditsCacheForTests(): void {
    sessionCache.clear();
}

export function useSongCredits(videoId: null | string): {
    refetch: () => void;
    state: SongCreditsState;
} {
    const [state, setState] = useState<SongCreditsState>({ kind: 'loading' });
    const [nonce, setNonce] = useState(0);

    useEffect(() => {
        if (videoId === null || videoId === '') {
            setState({ kind: 'empty' });
            return;
        }
        let cancelled = false;
        setState({ kind: 'loading' });
        void fetchSongCredits(videoId).then((next) => {
            if (!cancelled) setState(next);
        });
        return () => {
            cancelled = true;
        };
    }, [nonce, videoId]);

    const refetch = useCallback(() => {
        if (videoId !== null) sessionCache.delete(videoId);
        setNonce((n) => n + 1);
    }, [videoId]);

    return { refetch, state };
}

async function fetchSongCredits(videoId: string, hl?: string): Promise<SongCreditsState> {
    const cached = sessionCache.get(videoId);
    if (cached !== undefined) return cached;
    const source = await resolveMetaSource();
    if (source === null) return { kind: 'empty' };
    try {
        const credits = await source.getCredits(videoId, hl);
        if (credits === null) {
            sessionCache.set(videoId, { kind: 'empty' });
            return { kind: 'empty' };
        }
        sessionCache.set(videoId, { credits, kind: 'loaded' });
        return { credits, kind: 'loaded' };
    } catch {
        return { kind: 'error' };
    }
}
