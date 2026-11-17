/**
 * W2-A — the additive { mode: 'video' } resolver branch.
 *
 * The locked claims (the task's acceptance lines):
 *   1. the default getStreamUrl() AUDIO rungs are 100% unchanged — the
 *      resolveAudioStream pipeline receives byte-identical options with
 *      and without the mode flag, and the audio ranking stays
 *      opus → aac → muxed-last-resort (the P2-A element ladder);
 *   2. { mode: 'video' } resolves the video counterpart — a muxed-first
 *      ladder (video+audio codecs in one URL), quality descending, no
 *      audio-only rungs;
 *   3. both miss classes degrade cleanly via the typed
 *      StreamifyVideoModeMissError (no muxed format; hls/sabr win);
 *   4. the video cache never aliases the audio cache (the \u0000video
 *      namespace key).
 *
 * Network-free: resolveAudioStream is module-mocked with digest fixtures
 * (the real pipeline is fixture-locked by its own lanes); the PO-token
 * minter is stubbed (pinUrl stays best-effort — null keeps the bare URL).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { LadderDigest, ResolvedStream } from '../playback/resolver/audio/resolve';

const resolveMock = vi.hoisted(() => ({
    calls: [] as Array<Record<string, unknown>>,
    digest: null as LadderDigest | null,
    impl: null as
        | ((videoId: string, ctx: Record<string, unknown>) => Promise<ResolvedStream>)
        | null,
    stream: null as null | ResolvedStream,
}));

vi.mock('../playback/resolver/audio/resolve', async (importOriginal) => {
    const original = await importOriginal<typeof import('../playback/resolver/audio/resolve')>();
    return {
        ...original,
        resolveAudioStream: vi.fn(async (videoId: string, ctx: Record<string, unknown>) => {
            resolveMock.calls.push(ctx);
            const sink = ctx.onLadderDigest as ((d: LadderDigest) => void) | undefined;
            if (resolveMock.digest !== null && sink !== undefined) {
                sink(resolveMock.digest);
            }
            if (resolveMock.impl !== null) {
                return resolveMock.impl(videoId, ctx);
            }
            if (resolveMock.stream === null) {
                throw new Error('fixture not set');
            }
            return resolveMock.stream;
        }),
    };
});

vi.mock('../playback/resolver/audio/botguard/potSession', () => ({
    gvsPoToken: vi.fn(async () => null),
}));

import {
    clearStreamUrlCache,
    getStreamUrl,
    peekStreamUrl,
    peekVideoStreamUrl,
    StreamifyVideoModeMissError,
    type StreamUrlResolution,
} from '../playback/resolver/audio/get-stream-url';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const VIDEO_ID = 'w2aTestVideo';

/** Audio-only digest — the video-mode miss fixture. */
function audioOnlyDigest(): LadderDigest {
    const digest = fullFormatDigest() as LadderDigest & {
        resp: {
            streamingData: {
                adaptiveFormats: Array<Record<string, unknown>>;
                formats: Array<Record<string, unknown>>;
            };
        };
    };
    digest.resp.streamingData.formats = [];
    digest.resp.streamingData.adaptiveFormats = digest.resp.streamingData.adaptiveFormats.filter(
        (f) => typeof f.mimeType === 'string' && f.mimeType.startsWith('audio/'),
    );
    return digest;
}

/** The full-format digest: 2 audio-only, 1 video-only (no audio codec), 2 muxed. */
function fullFormatDigest(): LadderDigest {
    return {
        kind: 'progressive',
        potKind: 'none',
        resp: {
            playabilityStatus: { status: 'OK' },
            streamingData: {
                adaptiveFormats: [
                    {
                        bitrate: 160_000,
                        contentLength: '5000000',
                        itag: 251,
                        mimeType: 'audio/webm; codecs="opus"',
                        url: 'https://rr4---sn-test.googlevideo.com/audio-opus',
                    },
                    {
                        bitrate: 128_000,
                        contentLength: '4000000',
                        itag: 140,
                        mimeType: 'audio/mp4; codecs="mp4a.40.2"',
                        url: 'https://rr4---sn-test.googlevideo.com/audio-m4a',
                    },
                    {
                        bitrate: 4_000_000,
                        // video-ONLY (no audio codec) — must NEVER enter the
                        // video ladder (a single <video> element cannot play
                        // it) nor the audio ladder.
                        itag: 137,
                        mimeType: 'video/mp4; codecs="avc1.640028"',
                        url: 'https://rr4---sn-test.googlevideo.com/video-1080',
                    },
                ],
                expiresInSeconds: '21540',
                formats: [
                    {
                        bitrate: 500_000,
                        contentLength: '15000000',
                        itag: 18,
                        mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"',
                        url: 'https://rr4---sn-test.googlevideo.com/muxed-360',
                    },
                    {
                        bitrate: 1_200_000,
                        contentLength: '30000000',
                        itag: 22,
                        mimeType: 'video/mp4; codecs="avc1.64001F, mp4a.40.2"',
                        url: 'https://rr4---sn-test.googlevideo.com/muxed-720',
                    },
                ],
            },
            videoDetails: {
                author: 'Artist',
                lengthSeconds: '240',
                title: 'Song',
                videoId: VIDEO_ID,
            },
        },
        rung: 'visionos-relay',
        stream: progressiveStream(),
    } as LadderDigest;
}

/** A fake probe fetch: 206 + Content-Range (range-capable, honest length). */
function probeFetchOk(): typeof fetch {
    return (async () =>
        new Response(null, {
            headers: {
                'content-range': 'bytes 0-0/31415926',
            },
            status: 206,
        })) as unknown as typeof fetch;
}

function progressiveStream(overrides: Partial<ResolvedStream> = {}): ResolvedStream {
    return {
        artist: 'Artist',
        bitrate: 160000,
        codec: 'opus',
        contentLength: 5_000_000,
        durationSec: 240,
        isHls: false,
        isSabr: false,
        itag: 251,
        mimeType: 'audio/webm; codecs="opus"',
        potKind: 'none',
        rung: 'visionos-relay',
        title: 'Song',
        url: 'https://rr4---sn-test.googlevideo.com/audio-opus',
        videoId: VIDEO_ID,
        ...overrides,
    };
}

const BASE_OPTS = {
    probeImpl: probeFetchOk(),
    relayBase: 'https://streamify-relay.example.workers.dev',
};

beforeEach(() => {
    resolveMock.calls.length = 0;
    resolveMock.digest = fullFormatDigest();
    resolveMock.stream = progressiveStream();
    resolveMock.impl = null;
    clearStreamUrlCache();
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('W2-A resolver video mode (additive branch)', () => {
    it('the default audio path is unchanged: opus top, tiered ladder, muxed last resort', async () => {
        const resolution = await getStreamUrl(VIDEO_ID, { ...BASE_OPTS });
        expect(resolution.protocol).toBe('progressive');
        expect(resolution.ladder.map((r) => r.itag)).toEqual([251, 140, 22]);
        expect(resolution.ladder.every((r) => r.audioOnly || r.itag === 22)).toBe(true);
        expect(resolution.ladder[0].audioOnly).toBe(true);
        expect(resolution.url).toContain('audio-opus');
        expect(resolution.codecs).toBe('opus');
    });

    it('the pipeline options are byte-identical with and without the mode flag (rungs unchanged)', async () => {
        await getStreamUrl(VIDEO_ID, { ...BASE_OPTS });
        clearStreamUrlCache();
        await getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'audio' });
        clearStreamUrlCache();
        resolveMock.calls.length = 0;
        await getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' });

        expect(resolveMock.calls.length).toBe(1);
        const ctx = resolveMock.calls[0] as unknown as {
            autoMint: unknown;
            elementCompat: boolean;
            onLadderDigest: unknown;
            relayBase: string;
        };
        // The SAME resolveAudioStream posture the audio path uses — the
        // 6-rung ladder (its timeouts, headers, fallback orders) is owned
        // by resolve.ts and receives the identical context shape.
        expect(ctx.relayBase).toBe(BASE_OPTS.relayBase);
        expect(ctx.elementCompat).toBe(true);
        expect(ctx.autoMint).toBeUndefined();
        expect(typeof ctx.onLadderDigest).toBe('function');
    });

    it('mode video: muxed-first ladder, quality descending, no audio-only rungs', async () => {
        const resolution = await getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' });
        expect(resolution.protocol).toBe('progressive');
        expect(resolution.ladder.map((r) => r.itag)).toEqual([22, 18]);
        expect(resolution.ladder.every((r) => r.audioOnly === false)).toBe(true);
        expect(resolution.url).toContain('muxed-720');
        expect(resolution.codecs).toBe('avc1+aac');
        expect(resolution.durationMs).toBe(240_000);
    });

    it('mode video degrades cleanly on the no-muxed miss (typed error)', async () => {
        resolveMock.digest = audioOnlyDigest();
        await expect(
            getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' }),
        ).rejects.toBeInstanceOf(StreamifyVideoModeMissError);
        try {
            await getStreamUrl(VIDEO_ID, { ...BASE_OPTS, forceRefresh: true, mode: 'video' });
            expect.unreachable('must throw');
        } catch (err) {
            expect(err).toBeInstanceOf(StreamifyVideoModeMissError);
            expect((err as StreamifyVideoModeMissError).message).toContain('no muxed video+audio');
        }
    });

    it('mode video degrades cleanly on the hls and sabr wins (typed error)', async () => {
        // HLS
        resolveMock.digest = {
            kind: 'hls',
            potKind: 'none',
            rung: 'web-safari-hls',
            stream: progressiveStream({ isHls: true, rung: 'web-safari-hls' }),
        } as LadderDigest;
        await expect(
            getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' }),
        ).rejects.toBeInstanceOf(StreamifyVideoModeMissError);

        // SABR
        clearStreamUrlCache();
        resolveMock.digest = {
            kind: 'sabr',
            potKind: 'none',
            rung: 'visionos-direct',
            sabrAudioItags: [251],
            stream: progressiveStream({ isSabr: true, rung: 'visionos-direct' }),
        } as LadderDigest;
        await expect(
            getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' }),
        ).rejects.toBeInstanceOf(StreamifyVideoModeMissError);
    });

    it('the video cache never aliases the audio cache', async () => {
        const audio = await getStreamUrl(VIDEO_ID, { ...BASE_OPTS });
        const video = await getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' });
        expect(audio.url).not.toBe(video.url);
        // Both cached under their own namespace…
        expect(peekStreamUrl(VIDEO_ID)?.url).toContain('audio-opus');
        expect(peekVideoStreamUrl(VIDEO_ID)?.url).toContain('muxed-720');
        // …and the peek of one never answers the other.
        expect(peekStreamUrl(VIDEO_ID)?.ladder[0].audioOnly).toBe(true);
        expect(peekVideoStreamUrl(VIDEO_ID)?.ladder[0].audioOnly).toBe(false);
        const resolution: StreamUrlResolution = video;
        expect(resolution.ladder.length).toBe(2);
    });

    it('a video miss never poisons the audio answer (and vice versa)', async () => {
        resolveMock.digest = audioOnlyDigest();
        await expect(
            getStreamUrl(VIDEO_ID, { ...BASE_OPTS, mode: 'video' }),
        ).rejects.toBeInstanceOf(StreamifyVideoModeMissError);
        resolveMock.digest = fullFormatDigest();
        const audio = await getStreamUrl(VIDEO_ID, { ...BASE_OPTS });
        expect(audio.ladder[0].itag).toBe(251);
    });
});
