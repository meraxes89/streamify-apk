/**
 * features/streamify/credits/credits-store.ts — W2-B: the Liner Notes
 * drawer state (which song's credits are open).
 *
 * Deliberately dumb state (the streamify-downloads.store pattern): plain
 * zustand create, no actions beyond open/close, no persist — the modal is
 * ephemeral UI; the credits data itself is fetched per videoId by
 * use-song-credits (session-cached inside the hook's module).
 */

import { create } from 'zustand';

export interface CreditsRequest {
    artist: null | string;
    title: string;
    videoId: string;
}

export interface StreamifyCreditsSlice {
    /** The open request (null = closed). */
    request: CreditsRequest | null;
}

interface StreamifyCreditsActions {
    close: () => void;
    open: (request: CreditsRequest) => void;
}

export const useStreamifyCreditsStoreBase = create<
    StreamifyCreditsActions & StreamifyCreditsSlice
>()((set) => ({
    close: () => set({ request: null }),
    open: (request) => set({ request }),
    request: null,
}));

/** Snapshot read (non-React call sites — the context-menu action). */
export function getStreamifyCreditsState(): StreamifyCreditsSlice {
    return useStreamifyCreditsStoreBase.getState();
}

/** Test seam: reset to closed. */
export function resetStreamifyCreditsStoreForTests(): void {
    useStreamifyCreditsStoreBase.setState({ request: null });
}
