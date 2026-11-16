/**
 * api/streamify/downloads/opfs.ts — P4-A: the OPFS-backed storage layer.
 *
 * A narrow interface over one OPFS directory
 * (navigator.storage.getDirectory() → 'streamify-downloads') so the
 * download manager and the offline serve path never touch OPFS APIs
 * directly — vitest drives the exact same core against an in-memory
 * implementation (including scripted QuotaExceededError and abort
 * behavior). This is feishin's first OPFS consumer; the P1 covers
 * deferral (decisions/p1/facade-gaps.md) does not apply here — the audio
 * fetch path is ours, not a synchronous upstream component contract.
 */

export interface DownloadStorage {
    /** Best-effort quota report (null when the browser refuses). */
    estimate(): Promise<null | { quota: number; usage: number }>;
    /** All file names under the download directory. */
    names(): Promise<string[]>;
    /** Streaming write to `name`; the file must not be read mid-sink. */
    openSink(name: string): Promise<StorageSink>;
    /** Full read; null when the file does not exist. */
    read(name: string): Promise<null | Uint8Array>;
    /** Delete one file; missing files are a no-op. */
    remove(name: string): Promise<void>;
}

export interface StorageSink {
    /** Discard the partial file (abort / failure cleanup). */
    abort(): Promise<void>;
    /** Finish the file (make it visible to readers). */
    close(): Promise<void>;
    /** Append bytes to the file being written. */
    write(bytes: Uint8Array): Promise<void>;
}

export const OPFS_DIR = 'streamify-downloads';

export interface MemoryStorageOptions {
    /** Called after each sink write (tests script failures mid-stream). */
    onWrite?: (name: string, totalBytes: number) => void;
    /** Fail writes (throw QuotaExceededError) once total bytes exceed this. */
    quotaBudget?: number;
}

/** A quota error (shared shape test — real OPFS and the test fake both throw it). */
export function isQuotaError(err: unknown): boolean {
    return (
        typeof err === 'object' &&
        err !== null &&
        (err as { name?: string }).name === 'QuotaExceededError'
    );
}

/**
 * The in-memory implementation (tests): same contract, plus quota
 * scripting. Sinks are atomic — a sink's bytes only appear in `read`
 * after close(), and abort() discards them, mirroring OPFS semantics.
 */
export function memoryStorage(options: MemoryStorageOptions = {}): DownloadStorage {
    const files = new Map<string, Uint8Array>();
    const budget = options.quotaBudget ?? Number.POSITIVE_INFINITY;
    return {
        estimate: async () => {
            let usage = 0;
            for (const b of files.values()) usage += b.length;
            return { quota: budget === Number.POSITIVE_INFINITY ? 0 : budget, usage };
        },
        names: async () => [...files.keys()],
        openSink: async (name) => {
            let parts: Uint8Array[] = [];
            let closed = false;
            let dead = false;
            const liveBytes = () => {
                let total = 0;
                for (const b of files.values()) total += b.length;
                for (const p of parts) total += p.length;
                return total;
            };
            return {
                abort: async () => {
                    if (closed || dead) return;
                    dead = true;
                    parts = [];
                },
                close: async () => {
                    if (dead || closed) return;
                    closed = true;
                    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
                    let off = 0;
                    for (const p of parts) {
                        out.set(p, off);
                        off += p.length;
                    }
                    files.set(name, out);
                    parts = [];
                },
                write: async (bytes) => {
                    if (closed || dead) throw new Error('streamify dl: sink already closed');
                    parts.push(bytes);
                    options.onWrite?.(name, liveBytes());
                    const total = liveBytes();
                    if (total > budget) {
                        parts = [];
                        dead = true;
                        throw new DOMException('QuotaExceededError', 'QuotaExceededError');
                    }
                },
            };
        },
        read: async (name) => files.get(name)?.slice() ?? null,
        remove: async (name) => {
            files.delete(name);
        },
    };
}

/** The production adapter (browser OPFS). Throws when OPFS is unavailable. */
export function opfsStorage(): DownloadStorage {
    if (typeof navigator === 'undefined' || navigator.storage?.getDirectory === undefined) {
        throw new Error('streamify downloads: OPFS is not available in this environment');
    }
    const getDir = async (): Promise<FileSystemDirectoryHandle> => {
        const root = await navigator.storage.getDirectory();
        return root.getDirectoryHandle(OPFS_DIR, { create: true });
    };
    return {
        estimate: async () => {
            const est = await navigator.storage.estimate();
            if (est === undefined) return null;
            return { quota: est.quota ?? 0, usage: est.usage ?? 0 };
        },
        names: async () => {
            const dir = await getDir();
            const out: string[] = [];

            for await (const [name] of dir as unknown as AsyncIterable<readonly [string]>) {
                out.push(name);
            }
            return out;
        },
        openSink: async (name) => {
            const dir = await getDir();
            const handle = await dir.getFileHandle(name, { create: true });
            const writable = await handle.createWritable();
            let closed = false;
            return {
                abort: async () => {
                    if (closed) return;
                    closed = true;
                    await writable.abort();
                    await storageRemoveBestEffort(dir, name);
                },
                close: async () => {
                    if (closed) return;
                    closed = true;
                    await writable.close();
                },
                write: async (bytes) => {
                    if (closed) throw new Error('streamify dl: sink already closed');
                    // copy into a fresh ArrayBuffer-backed view: the OPFS
                    // write chunk type wants BufferSource, and callers may
                    // pass subarray views.
                    await writable.write(new Uint8Array(bytes));
                },
            };
        },
        read: async (name) => {
            try {
                const dir = await getDir();
                const handle = await dir.getFileHandle(name);
                const file = await handle.getFile();
                return new Uint8Array(await file.arrayBuffer());
            } catch {
                return null;
            }
        },
        remove: async (name) => {
            const dir = await getDir();
            await storageRemoveBestEffort(dir, name);
        },
    };
}

async function storageRemoveBestEffort(dir: FileSystemDirectoryHandle, name: string) {
    try {
        await dir.removeEntry(name);
    } catch {
        // missing entries are the no-op contract; anything else surfaces at
        // the next read (the orphan sweep is idempotent).
    }
}
