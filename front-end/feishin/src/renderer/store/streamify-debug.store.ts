/**
 * store/streamify-debug.store.ts — P4-A: the /debug gate state.
 *
 * One boolean: the persisted developer-mode grant. Written by the /debug
 * page's "remember" switch; read by the route gate. Plain zustand (the
 * streamify-playlists.store.ts pattern) with a localStorage lane — tiny,
 * no actions: the debug route owns the transition.
 */

import { create } from 'zustand';

const KEY = 'streamify.feishin.debug.v1';

export interface StreamifyDebugSlice {
    /** Persisted developer-mode grant (explicit opt-in from a gated session). */
    developerMode: boolean;
}

function readPersisted(): boolean {
    if (typeof window === 'undefined' || window.localStorage === undefined) return false;
    return window.localStorage.getItem(KEY) === '1';
}

export const useStreamifyDebugStoreBase = create<StreamifyDebugSlice>()(() => ({
    developerMode: readPersisted(),
}));

/** Snapshot read (the gate + route). */
export function getStreamifyDebugState(): StreamifyDebugSlice {
    return useStreamifyDebugStoreBase.getState();
}

/** Test seam. */
export function resetStreamifyDebugStoreForTests(): void {
    useStreamifyDebugStoreBase.setState({ developerMode: false });
    if (typeof window !== 'undefined' && window.localStorage !== undefined) {
        window.localStorage.removeItem(KEY);
    }
}

/** Partial write (the route's toggle). */
export function setStreamifyDebugState(partial: Partial<StreamifyDebugSlice>): void {
    useStreamifyDebugStoreBase.setState(partial);
    if (partial.developerMode !== undefined) {
        if (typeof window !== 'undefined' && window.localStorage !== undefined) {
            window.localStorage.setItem(KEY, partial.developerMode ? '1' : '0');
        }
    }
}
