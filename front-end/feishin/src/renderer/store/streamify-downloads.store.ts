/**
 * store/streamify-downloads.store.ts — P4-A: the downloads state slice.
 *
 * Deliberately dumb state (the streamify-playlists.store.ts pattern):
 * plain zustand create, no actions, no persist — the download manager
 * owns every transition and writes through setStreamifyDownloadsState();
 * the UI subscribes with selectors. Durability of entries lives in OPFS
 * sidecars (meta.json), not in this store: a remount rehydrates from the
 * sweep, never from a mirrored cache.
 */

import type { DownloadStatus } from '/@/renderer/api/streamify/downloads/meta';

import { create } from 'zustand';

export interface DownloadEntryState {
    album: null | string;
    artist: string;
    chunkCount: number;
    /** Container bytes on disk. */
    ciphertextBytes: number;
    codecs: null | string;
    completedAt: null | string;
    durationSec: null | number;
    integrity: DownloadStatus;
    mimeType: string;
    rung: null | string;
    songId: string;
    title: string;
    totalPlainLen: number;
    videoId: string;
}

export interface DownloadJobState {
    artist: string;
    /** Bytes received from the relay so far (plaintext count). */
    receivedBytes: number;
    songId: string;
    startedAt: number;
    title: string;
    /** Null until the response's Content-Length is known. */
    totalBytes: null | number;
    videoId: string;
}

export interface StreamifyDownloadsSlice {
    /** Terminal-state entries (keyed by videoId): complete or corrupt. */
    entries: Record<string, DownloadEntryState>;
    /** Live jobs (keyed by videoId) — the active queue. */
    jobs: Record<string, DownloadJobState>;
    /** Last error per videoId (sticky until the next attempt). */
    lastError: Record<string, string>;
    /** Best-effort OPFS quota report (the /debug + manager meter). */
    storage: null | { quota: number; usage: number };
    /** The cipher wasm init state (the /debug panel). */
    wasm: WasmHealthState;
}

export interface WasmHealthState {
    error: null | string;
    selfTest: null | number;
    status: 'error' | 'idle' | 'loading' | 'ready';
}

export const useStreamifyDownloadsStoreBase = create<StreamifyDownloadsSlice>()(() => ({
    entries: {},
    jobs: {},
    lastError: {},
    storage: null,
    wasm: { error: null, selfTest: null, status: 'idle' },
}));

/** Snapshot read (the download manager + debug panel entry point). */
export function getStreamifyDownloadsState(): StreamifyDownloadsSlice {
    return useStreamifyDownloadsStoreBase.getState();
}

/** Test seam: reset for a fresh manager run. */
export function resetStreamifyDownloadsStoreForTests(): void {
    useStreamifyDownloadsStoreBase.setState({
        entries: {},
        jobs: {},
        lastError: {},
        storage: null,
        wasm: { error: null, selfTest: null, status: 'idle' },
    });
}

/** Partial write (download-manager transitions only). */
export function setStreamifyDownloadsState(partial: Partial<StreamifyDownloadsSlice>): void {
    useStreamifyDownloadsStoreBase.setState(partial);
}
