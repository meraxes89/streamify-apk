/**
 * api/streamify/downloads/download-manager.ts — P4-A: the orchestration
 * core for OPFS + wasm chunk-cipher downloads.
 *
 * ZERO-EGRESS PIPELINE (download side):
 *   resolveOnline(videoId) → getStreamUrl (progressive rung only — SABR
 *   and HLS are honest failures: no fetchable byte stream)
 *   → fetch the resolved URL → read the body in 64 KiB plaintext chunks
 *   → seal each chunk (XChaCha20-Poly1305, fresh nonce, index as AAD)
 *   → stream header + records into OPFS (streaming when Content-Length
 *   is present; in-memory fallback otherwise)
 *   → sidecar meta.json flips to status:'complete'.
 *
 * FAILURE & QUOTA HYGIENE:
 *   - user cancel / network abort / QuotaExceededError / size mismatch →
 *     the partial container AND its sidecar are removed (the orphan sweep
 *     is idempotent, so a crash mid-download also cleans up on next boot);
 *   - corruption is detected at SERVE time (we sealed it ourselves) and
 *     quarantined there (meta status 'corrupt', container deleted, online
 *     fallback) — the integrity failure never blocks playback.
 *
 * The manager is a factory: tests build it over the in-memory storage +
 * a fake resolver/fetch; production binds OPFS + the vendored cipher wasm
 * + the resolver mirror (instance.ts).
 */

import { cipherChunkSize, type CipherWasmExports, mintKey, mintNonce, sealChunk } from './cipher';
import {
    chunkCountFor,
    containerSize,
    type DlHeader,
    encodeHeader,
    encodeRecord,
    openContainer,
    sealContainer,
} from './format';
import { dataNameFor, decodeKey, type DownloadMeta, encodeKey, type MetaCodec } from './meta';
import { type DownloadStorage, isQuotaError } from './opfs';

import {
    getStreamifyDownloadsState,
    setStreamifyDownloadsState,
} from '/@/renderer/store/streamify-downloads.store';
import { logger } from '/@/renderer/utils/logger';

export interface DownloadManager {
    /** Abort a live job (quiet cleanup, no error surface). */
    cancel(videoId: string): void;
    /** All sidecars (the downloads manager view). */
    listEntries(): Promise<DownloadMeta[]>;
    /** Mark an entry corrupt (container deleted, sidecar kept for the UI). */
    quarantine(videoId: string, reason: string): Promise<void>;
    /** Delete one entry (data + sidecar + served blob URL). */
    remove(videoId: string): Promise<void>;
    /** Read + verify + decrypt → a seekable Blob URL; null = not ours. */
    serveOffline(videoId: string): Promise<null | OfflineServeResult>;
    /** Queue a download (idempotent: no-op when live or already complete). */
    startDownload(spec: DownloadSpec, signal?: AbortSignal): Promise<void>;
    /** Drop crash orphans (sidecars stuck at 'downloading' with no live job). */
    sweepOrphans(): Promise<void>;
    /** The wasm chunk-cipher self-test (the /debug panel). */
    wasmSelfTest(): number;
}

export interface DownloadManagerOptions {
    fetchImpl: FetchLike;
    meta: MetaCodec;
    resolveOnline: OnlineResolver;
    storage: DownloadStorage;
    wasm: CipherWasmExports;
}

/** What the caller knows about a track (from the library snapshot row). */
export interface DownloadSpec {
    album: null | string;
    artist: string;
    durationSec: null | number;
    songId: string;
    title: string;
    videoId: string;
}

export type FetchLike = (url: string, init?: { signal?: AbortSignal }) => Promise<Response>;

export interface OfflineServeResult {
    artist: string;
    codecs: string;
    /** Plaintext bytes (the offline contentLength). */
    contentLength: number;
    durationMs: null | number;
    mimeType: string;
    /** The rung that served the download (recorded at download time). */
    rung: null | string;
    title: string;
    /** Blob URL (seekable in place — rangeSupport semantics). */
    url: string;
    videoId: string;
}

/** The minimal resolution surface the manager needs (stream-source's). */
export interface OnlineResolution {
    codecs: string;
    durationMs: null | number;
    protocol: 'hls' | 'progressive' | 'sabr';
    rung: null | string;
    url: string;
}

export type OnlineResolver = (videoId: string) => Promise<OnlineResolution>;

/** Live per-download bookkeeping (private to the manager instance). */
interface LiveJob {
    controller: AbortController;
}

export class StreamifyDownloadError extends Error {
    constructor(
        public readonly videoId: string,
        message: string,
    ) {
        super(`streamify download ${videoId}: ${message}`);
        this.name = 'StreamifyDownloadError';
    }
}

export function createDownloadManager(options: DownloadManagerOptions): DownloadManager {
    const { fetchImpl, meta, resolveOnline, storage, wasm } = options;
    const chunkSize = cipherChunkSize(wasm);
    const live = new Map<string, LiveJob>();
    /** Blob URLs handed out (revoked on re-serve; nothing leaks per track). */
    const served = new Map<string, string>();

    // -----------------------------------------------------------------
    // Store plumbing
    // -----------------------------------------------------------------

    function publishJob(videoId: string, patch: Record<string, unknown>) {
        const { jobs } = getStreamifyDownloadsState();
        const current = jobs[videoId];
        setStreamifyDownloadsState({
            jobs: { ...jobs, [videoId]: { ...current, ...patch, videoId } },
        });
    }

    function dropJob(videoId: string) {
        const { jobs } = getStreamifyDownloadsState();
        const next = { ...jobs };
        delete next[videoId];
        setStreamifyDownloadsState({ jobs: next });
    }

    function failJob(videoId: string, message: string) {
        const { lastError } = getStreamifyDownloadsState();
        setStreamifyDownloadsState({ lastError: { ...lastError, [videoId]: message } });
        logger.warn(`streamify dl: ${message} (${videoId})`);
    }

    function publishEntry(m: DownloadMeta) {
        const { entries } = getStreamifyDownloadsState();
        setStreamifyDownloadsState({
            entries: {
                ...entries,
                [m.videoId]: {
                    album: m.album,
                    artist: m.artist,
                    chunkCount: m.chunkCount,
                    ciphertextBytes: m.ciphertextBytes,
                    codecs: m.codecs,
                    completedAt: m.completedAt,
                    durationSec: m.durationSec,
                    integrity: m.status,
                    mimeType: m.mimeType,
                    rung: m.rung,
                    songId: m.songId,
                    title: m.title,
                    totalPlainLen: m.totalPlainLen,
                    videoId: m.videoId,
                },
            },
        });
    }

    // -----------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------

    async function startDownload(spec: DownloadSpec, signal?: AbortSignal): Promise<void> {
        const { videoId } = spec;
        if (live.has(videoId)) return;
        const existing = await meta.read(videoId);
        if (existing !== null && existing.status !== 'corrupt') return;

        const controller = new AbortController();
        signal?.addEventListener('abort', () => controller.abort(), { once: true });
        live.set(videoId, { controller });
        publishJob(videoId, {
            artist: spec.artist,
            receivedBytes: 0,
            songId: spec.songId,
            startedAt: Date.now(),
            title: spec.title,
            totalBytes: null,
        });
        try {
            await runDownload(spec, controller);
        } catch (err) {
            await cleanupFailed(videoId, err, controller);
        } finally {
            live.delete(videoId);
        }
    }

    async function runDownload(spec: DownloadSpec, controller: AbortController): Promise<void> {
        const { videoId } = spec;
        const resolution = await resolveOnline(videoId);
        if (resolution.protocol !== 'progressive' || resolution.url === '') {
            throw new StreamifyDownloadError(
                videoId,
                `cannot download a ${resolution.protocol} resolution (no fetchable byte stream)`,
            );
        }

        const res = await fetchImpl(resolution.url, { signal: controller.signal });
        if (!res.ok) throw new StreamifyDownloadError(videoId, `upstream responded ${res.status}`);
        if (res.body === null) {
            throw new StreamifyDownloadError(videoId, 'response carries no body stream');
        }

        const contentLengthHeader = res.headers.get('content-length');
        const declaredTotal =
            contentLengthHeader !== null && /^\d+$/.test(contentLengthHeader)
                ? Number(contentLengthHeader)
                : null;

        const key = mintKey();
        const baseMeta: DownloadMeta = {
            album: spec.album,
            artist: spec.artist,
            chunkCount: 0,
            chunkSize,
            ciphertextBytes: 0,
            codecs: resolution.codecs ?? null,
            completedAt: null,
            createdAt: new Date().toISOString(),
            durationSec: spec.durationSec,
            key: encodeKey(key),
            mimeType: res.headers.get('content-type') ?? 'audio/mp4',
            rung: resolution.rung,
            songId: spec.songId,
            status: 'downloading',
            title: spec.title,
            totalPlainLen: 0,
            videoId,
        };

        if (declaredTotal === null) {
            await runInMemory(baseMeta, key, res, spec);
            return;
        }
        if (declaredTotal === 0) {
            throw new StreamifyDownloadError(videoId, 'Content-Length is zero (not audio)');
        }

        const header: DlHeader = {
            chunkCount: chunkCountFor(declaredTotal, chunkSize),
            chunkSize,
            totalPlainLen: declaredTotal,
            version: 1,
        };
        const sink = await storage.openSink(dataNameFor(videoId));
        let received = 0;
        let index = 0;
        try {
            await sink.write(encodeHeader(header));
            const reader = res.body.getReader();
            const carry: Uint8Array[] = [];
            let carryLen = 0;

            /** Consume exactly `n` bytes from the carry buffer (n <= carryLen). */
            const take = (n: number): Uint8Array => {
                const out = new Uint8Array(n);
                let off = 0;
                let left = n;
                while (left > 0 && carry.length > 0) {
                    const head = carry[0];
                    if (head.length <= left) {
                        out.set(head, off);
                        off += head.length;
                        left -= head.length;
                        carry.shift();
                    } else {
                        out.set(head.subarray(0, left), off);
                        carry[0] = head.subarray(left);
                        left = 0;
                    }
                }
                carryLen -= n;
                return out;
            };

            /** Seal + append one record (the caller owns index/remainder math). */
            const sealAndWrite = async (plain: Uint8Array) => {
                const nonce = mintNonce();
                const { ct, tag } = sealChunk(wasm, key, nonce, index, plain);
                await sink.write(encodeRecord(ct, nonce, tag));
                index += 1;
            };

            for (;;) {
                const { done, value } = await reader.read();
                if (done === true || value === undefined) break;
                received += value.length;
                carry.push(value);
                carryLen += value.length;
                // Full chunks only — a read may deliver MORE than one chunk's
                // worth (network segments are not chunk-sized); the seal is
                // bounded at exactly chunkSize, the remainder stays in carry.
                while (carryLen >= chunkSize) await sealAndWrite(take(chunkSize));
                publishJob(videoId, { receivedBytes: received, totalBytes: declaredTotal });
            }
            if (carryLen > 0) await sealAndWrite(take(carryLen)); // the short last record

            if (received !== declaredTotal) {
                throw new StreamifyDownloadError(
                    videoId,
                    `size mismatch: declared ${declaredTotal}, received ${received}`,
                );
            }
            if (index !== header.chunkCount) {
                throw new StreamifyDownloadError(
                    videoId,
                    `chunk count mismatch: sealed ${index}, header says ${header.chunkCount}`,
                );
            }
            await sink.close();
        } catch (err) {
            await sink.abort().catch(() => undefined);
            throw err;
        }

        const finished: DownloadMeta = {
            ...baseMeta,
            chunkCount: header.chunkCount,
            ciphertextBytes: containerSize(header),
            completedAt: new Date().toISOString(),
            status: 'complete',
            totalPlainLen: declaredTotal,
        };
        await meta.write(videoId, finished);
        dropJob(videoId);
        publishEntry(finished);
    }

    /** No-Content-Length fallback: accumulate, one in-memory seal, one write. */
    async function runInMemory(
        baseMeta: DownloadMeta,
        key: Uint8Array,
        res: Response,
        spec: DownloadSpec,
    ): Promise<void> {
        const { videoId } = spec;
        const reader = res.body!.getReader();
        const parts: Uint8Array[] = [];
        let received = 0;
        for (;;) {
            const { done, value } = await reader.read();
            if (done === true || value === undefined) break;
            parts.push(value);
            received += value.length;
            publishJob(videoId, { receivedBytes: received, totalBytes: null });
        }
        if (received === 0) throw new StreamifyDownloadError(videoId, 'empty body (not audio)');

        const plain = concatParts(parts);
        const { bytes, header } = sealContainer(wasm, key, plain, mintNonce);
        const sink = await storage.openSink(dataNameFor(videoId));
        try {
            await sink.write(bytes);
            await sink.close();
        } catch (err) {
            await sink.abort().catch(() => undefined);
            throw err;
        }
        const finished: DownloadMeta = {
            ...baseMeta,
            chunkCount: header.chunkCount,
            ciphertextBytes: bytes.length,
            completedAt: new Date().toISOString(),
            status: 'complete',
            totalPlainLen: header.totalPlainLen,
        };
        await meta.write(videoId, finished);
        dropJob(videoId);
        publishEntry(finished);
    }

    function concatParts(parts: Uint8Array[]): Uint8Array {
        let total = 0;
        for (const p of parts) total += p.length;
        const out = new Uint8Array(total);
        let off = 0;
        for (const p of parts) {
            out.set(p, off);
            off += p.length;
        }
        return out;
    }

    async function cleanupFailed(videoId: string, err: unknown, controller: AbortController) {
        const aborted = controller.signal.aborted;
        await storage.remove(dataNameFor(videoId)).catch(() => undefined);
        await meta.remove(videoId).catch(() => undefined);
        if (aborted) {
            dropJob(videoId); // user cancel / upstream abort: quiet cleanup
            return;
        }
        if (isQuotaError(err)) {
            failJob(videoId, 'quota exceeded — remove some downloads and retry');
            return;
        }
        failJob(videoId, err instanceof Error ? err.message : String(err));
    }

    // -----------------------------------------------------------------
    // Offline serve (the zero-egress playback path)
    // -----------------------------------------------------------------

    async function serveOffline(videoId: string): Promise<null | OfflineServeResult> {
        const m = await meta.read(videoId);
        if (m === null || m.status !== 'complete') return null;
        const bytes = await storage.read(dataNameFor(videoId));
        if (bytes === null) return null;
        if (bytes.length !== m.ciphertextBytes) {
            await quarantine(
                videoId,
                `container size ${bytes.length} ≠ recorded ${m.ciphertextBytes}`,
            );
            return null;
        }
        const key = decodeKey(m.key);
        let plain: Uint8Array;
        try {
            plain = openContainer(wasm, key, bytes).plain;
        } catch (err) {
            await quarantine(
                videoId,
                err instanceof Error ? err.message : 'chunk authentication failed',
            );
            return null;
        }
        const previous = served.get(videoId);
        if (previous !== undefined) URL.revokeObjectURL(previous);
        // plain is a fresh full-buffer view (openContainer's concat), so its
        // backing ArrayBuffer is exactly the file — no copy needed for Blob.
        const url = URL.createObjectURL(
            new Blob([plain.buffer as ArrayBuffer], { type: m.mimeType }),
        );
        served.set(videoId, url);
        return {
            artist: m.artist,
            codecs: m.codecs ?? m.mimeType,
            contentLength: m.totalPlainLen,
            durationMs: m.durationSec !== null ? Math.round(m.durationSec * 1000) : null,
            mimeType: m.mimeType,
            rung: m.rung,
            title: m.title,
            url,
            videoId,
        };
    }

    async function quarantine(videoId: string, reason: string): Promise<void> {
        logger.warn(`streamify dl: quarantined ${videoId} — ${reason}`);
        const m = await meta.read(videoId);
        await storage.remove(dataNameFor(videoId)).catch(() => undefined);
        if (m !== null) {
            const marked: DownloadMeta = { ...m, status: 'corrupt' };
            await meta.write(videoId, marked);
            publishEntry(marked);
        }
    }

    // -----------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------

    async function remove(videoId: string): Promise<void> {
        live.get(videoId)?.controller.abort();
        await storage.remove(dataNameFor(videoId)).catch(() => undefined);
        await meta.remove(videoId).catch(() => undefined);
        const { entries, lastError } = getStreamifyDownloadsState();
        const nextEntries = { ...entries };
        delete nextEntries[videoId];
        const nextErrors = { ...lastError };
        delete nextErrors[videoId];
        setStreamifyDownloadsState({ entries: nextEntries, lastError: nextErrors });
        const url = served.get(videoId);
        if (url !== undefined) {
            URL.revokeObjectURL(url);
            served.delete(videoId);
        }
    }

    function cancel(videoId: string): void {
        live.get(videoId)?.controller.abort();
    }

    async function listEntries(): Promise<DownloadMeta[]> {
        const names = await storage.names();
        const out: DownloadMeta[] = [];
        for (const name of names) {
            if (!name.endsWith('.meta.json')) continue;
            const m = await meta.read(name.slice(0, -'.meta.json'.length));
            if (m !== null) out.push(m);
        }
        return out;
    }

    async function sweepOrphans(): Promise<void> {
        const names = await storage.names();
        for (const name of names) {
            if (!name.endsWith('.meta.json')) continue;
            const id = name.slice(0, -'.meta.json'.length);
            if (live.has(id)) continue;
            const m = await meta.read(id);
            if (m === null || m.status === 'downloading') {
                // crash orphan: never completed, no live job owns it now
                await storage.remove(dataNameFor(id)).catch(() => undefined);
                await meta.remove(id).catch(() => undefined);
                continue;
            }
            publishEntry(m);
        }
    }

    function wasmSelfTest(): number {
        return wasm.dl_self_test();
    }

    return {
        cancel,
        listEntries,
        quarantine,
        remove,
        serveOffline,
        startDownload,
        sweepOrphans,
        wasmSelfTest,
    };
}
