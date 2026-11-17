/**
 * api/streamify/downloads/meta.ts — P4-A: the per-track sidecar record.
 *
 * One JSON file next to each encrypted container in OPFS
 * (streamify-downloads/<videoId>.meta.json), holding everything the
 * offline serve path and the downloads UI need — including the file key.
 *
 * KEY PLACEMENT (documented in decisions/p4/P4A-downloads-wrapped-debug.md):
 * the per-file key lives in this sidecar, next to the ciphertext. That is
 * an INTEGRITY-first design, matching the task's threat model (tamper,
 * truncation, corruption → quarantine): a local attacker with OPFS read
 * access can recover audio, but cannot corrupt an entry undetectably —
 * every Poly1305 tag check still rules. Feishin is a local media app; the
 * downloads are the user's own tracks, cached for offline playback.
 */

export interface DownloadMeta {
    album: null | string;
    artist: string;
    chunkCount: number;
    chunkSize: number;
    /** Exact container bytes (the size check at serve time). */
    ciphertextBytes: number;
    /** The resolver rung's codec descriptor (provenance). */
    codecs: null | string;
    completedAt: null | string;
    createdAt: string;
    durationSec: null | number;
    /** base64 — the per-file XChaCha20-Poly1305 key (see the header note). */
    key: string;
    /** W2-B (Smart Sync): last time this entry's track PLAYED (LRU input).
     * Absent on P4-A sidecars = never observed — the honest null. */
    lastPlayedAt?: null | string;
    mimeType: string;
    /** The resolver rung that served the download (provenance). */
    rung: null | string;
    songId: string;
    /** W2-B (Smart Sync): who owns this entry. Absent on P4-A sidecars =
     * 'manual' (every pre-W2B download was a user action). Smart Sync's
     * LRU evicts ONLY source === 'smart-sync' — manual downloads are
     * never evicted by background pre-fetching. */
    source?: 'manual' | 'smart-sync';
    status: DownloadStatus;
    title: string;
    totalPlainLen: number;
    videoId: string;
}

export type DownloadStatus = 'complete' | 'corrupt' | 'downloading';

import type { DownloadStorage } from './opfs';

export interface MetaCodec {
    read(videoId: string): Promise<DownloadMeta | null>;
    remove(videoId: string): Promise<void>;
    write(videoId: string, meta: DownloadMeta): Promise<void>;
}

/** W2-B: normalize the persisted source field (absent = manual). */
export function sourceOfMeta(meta: DownloadMeta | null): 'manual' | 'smart-sync' {
    return meta?.source === 'smart-sync' ? 'smart-sync' : 'manual';
}

const META_SUFFIX = '.meta.json';

export function dataNameFor(videoId: string): string {
    return `${sanitizeVideoId(videoId)}.sfdl`;
}

export function decodeKey(b64: string): Uint8Array {
    const binary = atob(b64);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
    return out;
}

/** base64 (no wasm needed — the key is opaque bytes to everyone but the cipher). */
export function encodeKey(key: Uint8Array): string {
    let binary = '';
    for (const b of key) binary += String.fromCharCode(b);
    return btoa(binary);
}

/** In-memory metadata store (tests / the storage-agnostic core). */
export function memoryMetaStore(): MetaCodec & { dump(): DownloadMeta[] } {
    const map = new Map<string, DownloadMeta>();
    return {
        dump: () => [...map.values()],
        read: async (id) => map.get(id) ?? null,
        remove: async (id) => {
            map.delete(id);
        },
        write: async (id, meta) => {
            map.set(id, meta);
        },
    };
}

export function metaNameFor(videoId: string): string {
    return `${sanitizeVideoId(videoId)}${META_SUFFIX}`;
}

/** YT video ids are [A-Za-z0-9_-]{11}; be defensive anyway (filename safety). */
export function sanitizeVideoId(videoId: string): string {
    const safe = videoId.replace(/[^A-Za-z0-9_-]/g, '');
    if (safe.length === 0 || safe.length > 64) {
        throw new Error(`streamify dl: refusing unsafe video id ${JSON.stringify(videoId)}`);
    }
    return safe;
}

/**
 * The storage-backed sidecar codec: the *.meta.json files live in the SAME
 * DownloadStorage directory as the containers (one directory, one naming
 * scheme, one sweep — production OPFS and the in-memory test storage both
 * run this exact codec, so listEntries/sweepOrphans see the sidecars).
 */
export function storageMetaCodec(storage: DownloadStorage): MetaCodec {
    return {
        read: async (videoId) => {
            const bytes = await storage.read(metaNameFor(videoId));
            if (bytes === null) return null;
            try {
                return JSON.parse(new TextDecoder().decode(bytes)) as DownloadMeta;
            } catch {
                return null; // a torn sidecar is an orphan the sweep removes
            }
        },
        remove: async (videoId) => {
            await storage.remove(metaNameFor(videoId));
        },
        write: async (videoId, meta) => {
            const text = JSON.stringify(meta);
            const sink = await storage.openSink(metaNameFor(videoId));
            try {
                await sink.write(new TextEncoder().encode(text));
                await sink.close();
            } catch (err) {
                await sink.abort().catch(() => undefined);
                throw err;
            }
        },
    };
}
