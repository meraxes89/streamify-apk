/**
 * P4-A — the OPFS download lifecycle, driven against the REAL cipher wasm
 * over the in-memory storage contract (progress tracking, cancel cleanup,
 * quota failure, crash orphans, quarantine-on-tamper, offline serve), and
 * the stream-source zero-egress bypass (a downloaded track resolves to a
 * local blob URL WITHOUT the resolver ever being called).
 */

import { readFileSync } from 'node:fs';
import { afterEach, beforeAll, describe, expect, it, beforeAll as setup, vi } from 'vitest';

// The real logger reads localStorage at construction (node has none) —
// the stream-source.test.ts mock idiom, verbatim.
vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: () => undefined, error: () => undefined, warn: () => undefined },
}));

import { type CipherWasmExports, initCipherForTests } from '../downloads/cipher';
import {
    createDownloadManager,
    type DownloadManager,
    type OnlineResolution,
} from '../downloads/download-manager';
import { dataNameFor, storageMetaCodec } from '../downloads/meta';
import { type DownloadStorage, memoryStorage } from '../downloads/opfs';

import {
    getStreamifyDownloadsState,
    resetStreamifyDownloadsStoreForTests,
} from '/@/renderer/store/streamify-downloads.store';

const ARTIFACT = readFileSync(new URL('../wasm/streamify_cipher_bg.wasm', import.meta.url));

// ---------------------------------------------------------------------------
// Node lacks URL.createObjectURL (blob: URLs are a browser concept). The
// serve path hands out blob URLs; the tests polyfill the two calls.
// ---------------------------------------------------------------------------
setup(() => {
    const anyUrl = URL as unknown as {
        createObjectURL?: (b: Blob) => string;
        revokeObjectURL?: (u: string) => void;
    };
    anyUrl.createObjectURL = (b) => {
        void b;
        return `blob:nymock/${++blobSeq}`;
    };
    anyUrl.revokeObjectURL = () => undefined;
});
let blobSeq = 0;

/** A body stream the test paces by hand (progress observations mid-flight).
 * Honors the manager's abort signal like a real fetch would. */
function pacedBody(
    chunks: Uint8Array[],
    onEachPull?: () => void,
    signal?: AbortSignal,
): ReadableStream<Uint8Array> {
    let i = 0;
    return new ReadableStream<Uint8Array>({
        pull(controller) {
            onEachPull?.();
            if (signal?.aborted === true) {
                controller.error(new DOMException('The operation was aborted.', 'AbortError'));
                return;
            }
            if (i < chunks.length) {
                controller.enqueue(chunks[i]);
                i += 1;
            } else {
                controller.close();
            }
        },
    });
}

const DEFAULT_SEGMENTS = [70_000, 4096, 90_000, 61_000, 20_480]; // deliberately NOT chunk-sized

interface Harness {
    declareBody: (total: number, segments?: number[]) => void;
    fetchLog: string[];
    manager: DownloadManager;
    resolutions: Map<string, OnlineResolution>;
    resolverLog: string[];
    storage: DownloadStorage;
    writeData: (videoId: string, bytes: Uint8Array) => Promise<void>;
}

function makeHarness(opts: { onPull?: () => void; quotaBudget?: number } = {}): Harness {
    const storage = memoryStorage({ quotaBudget: opts.quotaBudget });
    // The PRODUCTION sidecar codec: sidecars are files in the same storage
    // directory, so sweep/list treat them exactly like real OPFS runs.
    const meta = storageMetaCodec(storage);
    const resolutions = new Map<string, OnlineResolution>();
    const resolverLog: string[] = [];
    const fetchLog: string[] = [];
    let bodyTotal = -1;
    let bodySegments = DEFAULT_SEGMENTS;

    const fetchImpl = vi.fn(async (url: string, init?: { signal?: AbortSignal }) => {
        fetchLog.push(url);
        const chunks: Uint8Array[] = [];
        let remaining = bodyTotal;
        for (const seg of bodySegments) {
            const take = Math.min(seg, remaining);
            if (take <= 0) break;
            chunks.push(new Uint8Array(take).map((_, i) => (i * 13) & 0xff));
            remaining -= take;
        }
        if (remaining > 0) chunks.push(new Uint8Array(remaining).fill(1));
        const headers = new Headers({
            'content-length': String(bodyTotal),
            'content-type': 'audio/mp4',
        });
        const body = pacedBody(chunks, opts.onPull, init?.signal);
        return { body, headers, ok: true } as unknown as Response;
    });

    const manager = createDownloadManager({
        fetchImpl: fetchImpl as never,
        meta,
        resolveOnline: async (videoId) => {
            resolverLog.push(videoId);
            const r = resolutions.get(videoId);
            if (r === undefined) throw new Error(`no resolution fixture for ${videoId}`);
            return r;
        },
        storage,
        wasm: wasmRef!,
    });

    return {
        declareBody: (total, segments) => {
            bodyTotal = total;
            if (segments !== undefined) bodySegments = segments;
        },
        fetchLog,
        manager,
        resolutions,
        resolverLog,
        storage,
        writeData: async (videoId, bytes) => {
            const sink = await storage.openSink(dataNameFor(videoId));
            await sink.write(bytes);
            await sink.close();
        },
    };
}

let wasmRef: CipherWasmExports | null;

beforeAll(() => {
    wasmRef = initCipherForTests(ARTIFACT);
});

afterEach(() => {
    resetStreamifyDownloadsStoreForTests();
});

const specOf = (videoId: string) => ({
    album: 'Album',
    artist: 'Artist',
    durationSec: 213,
    songId: `song-${videoId}`,
    title: `Title ${videoId}`,
    videoId,
});

const fixture = (videoId: string): OnlineResolution => ({
    codecs: 'mp4',
    durationMs: 213000,
    protocol: 'progressive',
    rung: 'visionos-relay',
    url: `https://media.test/${videoId}.m4a`,
});

describe('P4-A download lifecycle (OPFS in-memory contract)', () => {
    it('streams to a verified container with live progress', async () => {
        const total = 65536 * 3 + 20480; // 3 full chunks + short tail
        const progressAt: number[] = [];
        const h = makeHarness({
            onPull: () => {
                const job = getStreamifyDownloadsState().jobs.v1;
                if (job !== undefined) progressAt.push(job.receivedBytes);
            },
        });
        h.declareBody(total);
        h.resolutions.set('v1', fixture('v1'));

        await h.manager.startDownload(specOf('v1'));

        const entry = getStreamifyDownloadsState().entries.v1;
        expect(entry).toBeDefined();
        expect(entry?.integrity).toBe('complete');
        expect(entry?.totalPlainLen).toBe(total);
        expect(entry?.rung).toBe('visionos-relay');
        expect(entry?.mimeType).toBe('audio/mp4');
        // Progress was observed mid-flight (the store updated as bytes landed).
        expect(progressAt.length).toBeGreaterThan(0);
        expect(progressAt.every((n) => n <= total)).toBe(true);
        expect(getStreamifyDownloadsState().jobs.v1).toBeUndefined();
        // The container on "disk" is exactly the recorded size.
        const data = await h.storage.read(dataNameFor('v1'));
        expect(data?.length).toBe(entry?.ciphertextBytes);
        // 31 header + 4 records (44 fixed each) + 3×65536 + 20480 plaintext:
        expect(entry?.ciphertextBytes).toBe(31 + 44 * 4 + total);
        expect(entry?.chunkCount).toBe(4);
    });

    it('falls back to in-memory sealing when Content-Length is absent', async () => {
        const h = makeHarness();
        // Redefine fetch without content-length via a dedicated manager.
        const storage = memoryStorage();
        const meta = storageMetaCodec(storage);
        const body = pacedBody([new Uint8Array(70_000).fill(3), new Uint8Array(5000).fill(4)]);
        const manager = createDownloadManager({
            fetchImpl: (async () => {
                const headers = new Headers({ 'content-type': 'audio/webm' }); // no length
                return { body, headers, ok: true } as unknown as Response;
            }) as never,
            meta,
            resolveOnline: async () => fixture('nolen'),
            storage,
            wasm: wasmRef!,
        });
        await manager.startDownload(specOf('nolen'));
        const entry = getStreamifyDownloadsState().entries.nolen;
        expect(entry?.integrity).toBe('complete');
        expect(entry?.totalPlainLen).toBe(75_000);
        expect(entry?.mimeType).toBe('audio/webm');
        void h;
    });

    it('cleans up on user cancel (quiet, no error surface)', async () => {
        const h = makeHarness({
            onPull: () => {
                h.manager.cancel('vc'); // cancel once the stream produced something
            },
        });
        h.declareBody(65536 * 4);
        h.resolutions.set('vc', fixture('vc'));
        await h.manager.startDownload(specOf('vc'));
        expect(getStreamifyDownloadsState().entries.vc).toBeUndefined();
        expect(getStreamifyDownloadsState().lastError.vc).toBeUndefined();
        expect(await h.storage.read(dataNameFor('vc'))).toBeNull();
        expect(await h.storage.names()).not.toContain(dataNameFor('vc'));
    });

    it('handles QuotaExceededError with honest cleanup + sticky error', async () => {
        // Header + first record ≈ 65.7 KiB fits; the second record blows it.
        const h = makeHarness({ quotaBudget: 110_000 });
        h.declareBody(65536 * 4);
        h.resolutions.set('q1', fixture('q1'));
        await h.manager.startDownload(specOf('q1'));
        expect(getStreamifyDownloadsState().entries.q1).toBeUndefined();
        expect(getStreamifyDownloadsState().lastError.q1).toMatch(/quota exceeded/);
        expect(await h.storage.read(dataNameFor('q1'))).toBeNull();
    });

    it('rejects size mismatch (declared ≠ received) with cleanup', async () => {
        const storage = memoryStorage();
        const meta = storageMetaCodec(storage);
        const manager = createDownloadManager({
            fetchImpl: (async () => {
                // Declare 1000, deliver 500.
                const headers = new Headers({
                    'content-length': '1000',
                    'content-type': 'audio/mp4',
                });
                const body = pacedBody([new Uint8Array(500).fill(7)]);
                return { body, headers, ok: true } as unknown as Response;
            }) as never,
            meta,
            resolveOnline: async () => fixture('mm'),
            storage,
            wasm: wasmRef!,
        });
        await manager.startDownload(specOf('mm'));
        expect(getStreamifyDownloadsState().entries.mm).toBeUndefined();
        expect(getStreamifyDownloadsState().lastError.mm).toMatch(/size mismatch/);
        expect(await storage.read(dataNameFor('mm'))).toBeNull();
    });

    it('sweeps crash orphans (sidecar stuck at downloading)', async () => {
        const h = makeHarness();
        h.declareBody(1000);
        // A crash orphan: sidecar says 'downloading', data half-written.
        await h.storage.openSink(dataNameFor('orph')).then(async (s) => {
            await s.write(new Uint8Array(1000));
            await s.close();
        });
        const codec = storageMetaCodec(h.storage);
        await codec.write('orph', {
            album: null,
            artist: '',
            chunkCount: 0,
            chunkSize: 65536,
            ciphertextBytes: 0,
            codecs: null,
            completedAt: null,
            createdAt: new Date().toISOString(),
            durationSec: null,
            key: '',
            mimeType: 'audio/mp4',
            rung: null,
            songId: 'song-orph',
            status: 'downloading',
            title: 'Orphan',
            totalPlainLen: 0,
            videoId: 'orph',
        });
        await h.manager.sweepOrphans();
        expect(await codec.read('orph')).toBeNull();
        expect(await h.storage.read(dataNameFor('orph'))).toBeNull();
        expect(await h.storage.names()).not.toContain(dataNameFor('orph'));
    });

    it('quarantines a tampered entry at serve time and falls back to null', async () => {
        const h = makeHarness();
        h.declareBody(65536 + 1000);
        h.resolutions.set('tam', fixture('tam'));
        await h.manager.startDownload(specOf('tam'));
        expect(getStreamifyDownloadsState().entries.tam?.integrity).toBe('complete');

        // Corrupt one ciphertext byte in the stored container.
        const data = await h.storage.read(dataNameFor('tam'));
        data!.set([0xff], data!.length - 100);
        await h.writeData('tam', data!);

        expect(await h.manager.serveOffline('tam')).toBeNull();
        const codec = storageMetaCodec(h.storage);
        const meta = await codec.read('tam');
        expect(meta?.status).toBe('corrupt');
        expect(await h.storage.read(dataNameFor('tam'))).toBeNull();
        expect(getStreamifyDownloadsState().entries.tam?.integrity).toBe('corrupt');
    });

    it('serves offline: local blob URL, verified plaintext, no resolver hit', async () => {
        const h = makeHarness();
        h.declareBody(65536 * 2 + 777);
        h.resolutions.set('off', fixture('off'));
        await h.manager.startDownload(specOf('off'));
        const result = await h.manager.serveOffline('off');
        expect(result).not.toBeNull();
        expect(result?.url).toMatch(/^blob:nymock\/\d+$/);
        expect(result?.mimeType).toBe('audio/mp4');
        expect(result?.durationMs).toBe(213000);
        expect(result?.rung).toBe('visionos-relay');
        // The resolver served the DOWNLOAD only — never the offline serve.
        expect(h.resolverLog).toEqual(['off']);
    });
});
