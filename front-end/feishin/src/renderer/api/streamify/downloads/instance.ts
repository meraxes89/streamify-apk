/**
 * api/streamify/downloads/instance.ts — P4-A: the production binding.
 *
 * Lazily builds ONE DownloadManager over the real dependencies (OPFS,
 * the vendored cipher wasm, the resolver mirror) and owns its lifecycle
 * surface:
 *   - tryServeOfflineTrack: the zero-egress playback bypass (called by
 *     stream-source's resolveStreamifyTrack BEFORE the resolver — a hit
 *     never touches getStreamUrl or the relay);
 *   - bootDownloads(): init-time wiring (sweep orphans, refresh the
 *     storage estimate, run the wasm self-test into the store for /debug);
 *   - queueSongDownload: the context-menu action's entry point.
 *
 * The resolver import goes STRAIGHT to the vendored mirror (the same
 * module stream-source re-exports) — no import cycle: stream-source
 * owns engine wiring, the mirror owns resolution, and downloads consume
 * the mirror like any other client.
 *
 * Tests replace the instance wholesale via
 * setStreamifyDownloadManagerForTests() — nothing module-private is
 * reachable from outside, so the offline-bypass tests drive the REAL
 * stream-source code against a fake manager.
 */

import { currentStreamifyLibrary } from '../engine';
import { getStreamUrl, type StreamUrlResolution } from '../playback/resolver/audio/get-stream-url';
import { relayBaseCandidate } from '../playback/resolver/lib/relayBase';
import { ensureCipher } from './cipher';
import {
    createDownloadManager,
    type DownloadManager,
    type DownloadSpec,
    type OfflineServeResult,
} from './download-manager';
import { storageMetaCodec } from './meta';
import { type DownloadStorage, opfsStorage } from './opfs';

import { setStreamifyDownloadsState } from '/@/renderer/store/streamify-downloads.store';
import { logger } from '/@/renderer/utils/logger';

let instance: DownloadManager | null = null;
let instanceStorage: DownloadStorage | null = null;
let instancePromise: null | Promise<DownloadManager | null> = null;
let instanceFailed = false;

/** Boot wiring (the downloads route and app init call this). */
export async function bootDownloads(): Promise<void> {
    const manager = await ensureManager();
    if (manager === null) {
        setStreamifyDownloadsState({
            wasm: { error: 'cipher wasm or OPFS unavailable', selfTest: null, status: 'error' },
        });
        return;
    }
    setStreamifyDownloadsState({
        wasm: { error: null, selfTest: manager.wasmSelfTest(), status: 'ready' },
    });
    await manager.sweepOrphans();
    if (instanceStorage !== null) {
        const est = await instanceStorage.estimate().catch(() => null);
        if (est !== null) setStreamifyDownloadsState({ storage: est });
    }
}

/** Async access (creates on first use) — the manager view's boot path. */
export function ensureDownloadManager(): Promise<DownloadManager | null> {
    return ensureManager();
}

// ---------------------------------------------------------------------------
// The public surface
// ---------------------------------------------------------------------------

/** Direct access for the downloads manager view (null before first boot). */
export function getDownloadManager(): DownloadManager | null {
    return instance;
}

/** Quarantine an entry from the stream-source error path (corruption the
 * tag check could not see — e.g. a codec the element cannot decode). */
export async function quarantineOfflineTrack(videoId: string, reason: string): Promise<void> {
    const manager = await ensureManager();
    if (manager === null) return;
    await manager.quarantine(videoId, reason);
}

/** The context-menu / download-action entry point (song-row → spec).
 * W2-B: `source` tags the download's owner — 'manual' (the default, the
 * only pre-W2B spelling) or 'smart-sync' (the background pre-fetcher;
 * those entries are LRU-evictable under the 500 MB cap). */
export async function queueSongDownload(
    songId: string,
    opts: { source?: 'manual' | 'smart-sync' } = {},
): Promise<void> {
    const manager = await ensureManager();
    if (manager === null) return;
    const snapshot = currentStreamifyLibrary();
    const row = snapshot?.songById.get(songId);
    const videoId = row?.streamUrl;
    if (typeof videoId !== 'string' || videoId === '') return;
    const spec: DownloadSpec = {
        album: row?.album ?? null,
        artist: row?.artist ?? '',
        durationSec: row?.durationSec ?? null,
        songId,
        source: opts.source ?? 'manual',
        title: row?.title ?? songId,
        videoId,
    };
    await manager.startDownload(spec);
}

/** Test seam: swap the instance (or clear it) — the stream-source bypass
 * and the manager view then run against the fake. */
export function setStreamifyDownloadManagerForTests(m: DownloadManager | null): void {
    instance = m;
    instanceFailed = m === null;
}

/**
 * The zero-egress bypass. Returns a seekable Blob URL + the entry's
 * facts when a valid, verified offline entry exists; null (quarantine
 * handled inside) when the track must resolve online. Never throws.
 */
export async function tryServeOfflineTrack(videoId: string): Promise<null | OfflineServeResult> {
    const manager = await ensureManager();
    if (manager === null) return null;
    try {
        return await manager.serveOffline(videoId);
    } catch (err) {
        logger.warn('streamify downloads: offline serve failed — online fallback', {
            err,
            videoId,
        });
        return null;
    }
}

async function buildInstance(): Promise<DownloadManager> {
    const storage = opfsStorage(); // throws when OPFS is unavailable
    const wasm = await ensureCipher(); // throws when the artifact fails to load
    instanceStorage = storage;
    return createDownloadManager({
        fetchImpl: (url, init) => fetch(url, init),
        meta: storageMetaCodec(storage),
        resolveOnline: async (videoId) => {
            const r: StreamUrlResolution = await getStreamUrl(videoId, {
                relayBase: relayBaseCandidate(),
            });
            return {
                codecs: r.codecs,
                durationMs: r.durationMs,
                protocol: r.protocol,
                rung: r.rung,
                url: r.url,
            };
        },
        storage,
        wasm,
    });
}

/** The manager, creating it on first use (never throws — null = offline-unavailable). */
function ensureManager(): Promise<DownloadManager | null> {
    if (instanceFailed) return Promise.resolve(null);
    if (instance !== null) return Promise.resolve(instance);
    instancePromise ??= (async () => {
        try {
            instance = await buildInstance();
            return instance;
        } catch (err) {
            instanceFailed = true;
            logger.warn('streamify downloads: manager unavailable (OPFS/wasm) — online only', {
                err,
            });
            return null;
        } finally {
            instancePromise = null;
        }
    })();
    return instancePromise;
}
