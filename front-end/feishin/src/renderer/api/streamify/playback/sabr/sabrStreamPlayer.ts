/**
 * playback/sabr/sabrStreamPlayer.ts — Client-side SABR playback lane (MSE audio).
 *
 * HOW IT PLAYS:
 * googlevideo's SabrStream (LuanRT) speaks YouTube's Server-Adaptive Bitrate
 * (SABR) protocol in the browser: it POSTs requests through the relay / GSF,
 * negotiates protection with the PO token, and emits sequential fMP4 bytes.
 * This module pipes that stream into a MediaSource SourceBuffer and returns
 * a same-origin `blob:` URL for <audio> / ReactPlayer.
 */

import { SabrStream } from 'googlevideo/sabr-stream';
import type { SabrFormat } from 'googlevideo/shared-types';
import { EnabledTrackTypes } from 'googlevideo/utils';

import { relayUrl } from '../resolver/audio/botguard/protocol';
import type { SabrPayload } from '../resolver/audio/resolve';
import { gsfAlive, gsfFetch } from '../resolver/lib/gsfLane';

async function sabrBodyFor(
    body: BodyInit | null | undefined,
): Promise<string | Uint8Array | ArrayBuffer | undefined> {
    if (body === undefined || body === null) return undefined;
    if (typeof body === 'string') return body;
    if (body instanceof Uint8Array) return body;
    if (body instanceof ArrayBuffer) return body;
    if (body instanceof Blob) return await body.arrayBuffer();
    return String(body);
}

export interface SabrSessionHandle {
    abort: () => void;
    done: Promise<'finished'>;
    objectUrl: string;
    onFail: (callback: (message: string) => void) => void;
}

const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';
const FIRST_MEDIA_BUDGET_MS = 8_000;
const NO_MEDIA_BREAKER_LIMIT = 6;

export function mseAudioAvailable(): boolean {
    return (
        typeof window !== 'undefined' &&
        typeof window.MediaSource !== 'undefined' &&
        MediaSource.isTypeSupported(AAC_MIME)
    );
}

function pickAudioFormat(
    formats: SabrFormat[],
    preferAac: boolean,
): SabrFormat | undefined {
    const isAudio = (f: SabrFormat): boolean =>
        typeof f.mimeType === 'string' && f.mimeType.startsWith('audio/');
    const audio = formats.filter(isAudio);
    if (audio.length === 0) return undefined;
    const byMime = (prefix: string): SabrFormat | undefined =>
        audio.find(
            (f) => typeof f.mimeType === 'string' && f.mimeType.startsWith(prefix),
        );
    const aac = byMime('audio/mp4');
    const opus = byMime('audio/webm');
    if (preferAac) return aac ?? opus;
    return opus ?? aac;
}

export async function startSabrAudio(
    payload: SabrPayload,
    preferAac = true,
    relayBase = '',
): Promise<SabrSessionHandle> {
    if (!mseAudioAvailable()) {
        throw new Error('MediaSource audio unavailable (SABR lane needs MSE)');
    }
    const format = pickAudioFormat(payload.formats as SabrFormat[], preferAac);
    if (format === undefined) {
        throw new Error('no audio format in the SABR descriptors');
    }
    const mimeType =
        typeof format.mimeType === 'string' && format.mimeType.length > 0
            ? format.mimeType
            : AAC_MIME;
    if (!MediaSource.isTypeSupported(mimeType)) {
        throw new Error(`MSE cannot decode ${mimeType}`);
    }

    const mediaSource = new MediaSource();
    const objectUrl = URL.createObjectURL(mediaSource);

    let failListeners: Array<(message: string) => void> = [];
    let failed = false;
    let sabr: SabrStream | null = null;

    let mediaBytesSeen = 0;
    let bytesAtLastProtectionEvent = -1;
    let noMediaStreak = 0;
    let firstMediaTimer: ReturnType<typeof setTimeout> | null = null;

    const fail = (message: string): void => {
        if (failed) return;
        failed = true;
        if (firstMediaTimer !== null) {
            clearTimeout(firstMediaTimer);
            firstMediaTimer = null;
        }
        try {
            if (mediaSource.readyState === 'open') mediaSource.endOfStream();
        } catch {
            /* already ended/closed */
        }
        try {
            sabr?.abort();
        } catch {
            /* best-effort teardown */
        }
        for (const cb of failListeners) cb(message);
        failListeners = [];
    };

    const done = new Promise<'finished'>((resolve, reject) => {
        let sourceBuffer: SourceBuffer | null = null;
        const queue: Uint8Array[] = [];
        let appending = false;
        let downloadFinished = false;
        let resolved = false;

        const maybeEnd = (): void => {
            if (
                !resolved &&
                !failed &&
                downloadFinished &&
                queue.length === 0 &&
                !appending &&
                sourceBuffer !== null &&
                mediaSource.readyState === 'open'
            ) {
                resolved = true;
                try {
                    mediaSource.endOfStream();
                } catch {
                    /* raced a teardown */
                }
                resolve('finished');
            }
        };

        const drain = (): void => {
            if (appending || sourceBuffer === null || queue.length === 0) {
                maybeEnd();
                return;
            }
            const chunk = queue.shift();
            if (chunk === undefined) return;
            appending = true;
            try {
                sourceBuffer.appendBuffer(chunk as Uint8Array<ArrayBuffer>);
            } catch (err) {
                appending = false;
                queue.length = 0;
                fail(
                    `MSE append failed: ${err instanceof Error ? err.message : String(err)}`,
                );
                reject(new Error('MSE append failed'));
            }
        };

        mediaSource.addEventListener('sourceopen', () => {
            try {
                sourceBuffer = mediaSource.addSourceBuffer(mimeType);
            } catch (err) {
                fail(
                    `addSourceBuffer failed: ${err instanceof Error ? err.message : String(err)}`,
                );
                reject(err instanceof Error ? err : new Error('addSourceBuffer failed'));
                return;
            }
            sourceBuffer.addEventListener('updateend', () => {
                appending = false;
                drain();
            });
            sourceBuffer.addEventListener('error', () => {
                fail('SourceBuffer error');
                reject(new Error('SourceBuffer error'));
            });
            drain();
        });

        const run = async (): Promise<void> => {
            sabr = new SabrStream({
                clientInfo: payload.clientInfo,
                durationMs: payload.durationMs > 0 ? payload.durationMs : undefined,
                fetch: async (input: RequestInfo | URL, init?: RequestInit) => {
                    const url =
                        input instanceof URL
                            ? input.toString()
                            : typeof input === 'string'
                              ? input
                              : input.url;
                    if (gsfAlive()) {
                        try {
                            const headers: Record<string, string> = {};
                            new Headers(init?.headers).forEach((value, name) => {
                                headers[name] = value;
                            });
                            const body = await sabrBodyFor(init?.body);
                            return await gsfFetch(url, {
                                ...(init?.method !== undefined ? { method: init.method } : {}),
                                ...(Object.keys(headers).length > 0 ? { headers } : {}),
                                ...(body !== undefined ? { body } : {}),
                                ...(init?.signal !== undefined && init?.signal !== null
                                    ? { signal: init.signal }
                                    : {}),
                            });
                        } catch {
                            // Lane-level fallback to relay
                        }
                    }
                    const target = relayBase !== '' ? relayUrl(relayBase, url) : url;
                    return fetch(target, init);
                },
                formats: payload.formats as SabrFormat[],
                poToken: payload.poToken ?? undefined,
                serverAbrStreamingUrl: payload.serverAbrStreamingUrl,
                videoPlaybackUstreamerConfig: payload.ustreamerConfig ?? undefined,
            });

            sabr.on(
                'streamProtectionStatusUpdate',
                (status: { maxRetries?: number; status?: number }) => {
                    const s = typeof status?.status === 'number' ? status.status : 0;
                    if (s >= 3) {
                        fail(`SABR protection status ${s}`);
                        reject(new Error(`SABR protection status ${s}`));
                        return;
                    }
                    if (s >= 2) {
                        if (mediaBytesSeen === bytesAtLastProtectionEvent) {
                            noMediaStreak += 1;
                            if (noMediaStreak >= NO_MEDIA_BREAKER_LIMIT) {
                                fail(
                                    `SABR protection wall — ${noMediaStreak} consecutive responses with zero media (status ${s}, ${mediaBytesSeen} B total)`,
                                );
                                reject(
                                    new Error(`SABR protection wall (status ${s}, no media)`),
                                );
                                return;
                            }
                        } else {
                            noMediaStreak = 0;
                        }
                        bytesAtLastProtectionEvent = mediaBytesSeen;
                    }
                },
            );

            sabr.on('reloadPlayerResponse', () => {
                fail('SABR demanded a player reload (stale session)');
                reject(new Error('SABR player reload demanded'));
            });

            const { audioStream } = await sabr.start({
                audioFormat: format,
                enabledTrackTypes: EnabledTrackTypes.AUDIO_ONLY,
                preferMP4: preferAac,
                preferOpus: !preferAac,
            });

            firstMediaTimer = setTimeout(() => {
                if (!failed && mediaBytesSeen === 0) {
                    fail('SABR first-media budget exceeded (8 s, zero bytes)');
                    reject(new Error('SABR first-media budget exceeded'));
                }
            }, FIRST_MEDIA_BUDGET_MS);

            const reader = audioStream.getReader();
            for (;;) {
                const { done: streamDone, value } = await reader.read();
                if (streamDone) break;
                if (value !== undefined) {
                    if (value.byteLength > 0) {
                        mediaBytesSeen += value.byteLength;
                        if (firstMediaTimer !== null) {
                            clearTimeout(firstMediaTimer);
                            firstMediaTimer = null;
                        }
                    }
                    queue.push(value);
                    drain();
                }
            }
            downloadFinished = true;
            drain();
        };

        run().catch((err: unknown) => {
            const message = `SABR stream failed: ${err instanceof Error ? err.message : String(err)}`;
            fail(message);
            reject(new Error(message));
        });
    });

    return {
        abort: () => {
            fail('aborted by caller');
        },
        done,
        objectUrl,
        onFail: (callback) => {
            failListeners.push(callback);
        },
    };
}
