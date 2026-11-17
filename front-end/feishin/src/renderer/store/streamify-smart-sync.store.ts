/**
 * store/streamify-smart-sync.store.ts — W2-B: the Smart Sync UI slice.
 *
 * Deliberately dumb state (the streamify-downloads.store pattern): plain
 * zustand create, no actions, no persist — the engine owns every
 * transition and writes through setStreamifySmartSyncState(); the settings
 * panel and player runtime subscribe with selectors. Durability lives in
 * the OPFS sidecars (source/lastPlayedAt) + the settings blob
 * (smartSyncEnabled), never here.
 */

import { create } from 'zustand';

export interface SmartSyncBreakerState {
    /** Epoch ms — the cooldown's end (0 = closed). */
    openUntilMs: number;
    reason: null | string;
}

export interface SmartSyncUsageState {
    /** Smart-synced entries currently in the store. */
    entries: number;
    /** Sum of smart-sync ciphertextBytes. */
    usedBytes: number;
}

export interface StreamifySmartSyncSlice {
    /** The circuit breaker (a 403/429/bot-gate/solver trip halts pre-fetching). */
    breaker: SmartSyncBreakerState;
    /** The last completed sync pass (epoch ms; null = never this session). */
    lastSyncAtMs: null | number;
    /** The LRU usage snapshot (the settings meter). */
    usage: SmartSyncUsageState;
}

export const EMPTY_SMART_SYNC_SLICE: StreamifySmartSyncSlice = {
    breaker: { openUntilMs: 0, reason: null },
    lastSyncAtMs: null,
    usage: { entries: 0, usedBytes: 0 },
};

export const useStreamifySmartSyncStoreBase = create<StreamifySmartSyncSlice>()(() => ({
    ...EMPTY_SMART_SYNC_SLICE,
}));

/** Snapshot read (the engine + runtime hook). */
export function getStreamifySmartSyncState(): StreamifySmartSyncSlice {
    return useStreamifySmartSyncStoreBase.getState();
}

/** Test seam: reset for a fresh engine run. */
export function resetStreamifySmartSyncStoreForTests(): void {
    useStreamifySmartSyncStoreBase.setState({ ...EMPTY_SMART_SYNC_SLICE });
}

/** Partial write (engine transitions only). */
export function setStreamifySmartSyncState(partial: Partial<StreamifySmartSyncSlice>): void {
    useStreamifySmartSyncStoreBase.setState(partial);
}
