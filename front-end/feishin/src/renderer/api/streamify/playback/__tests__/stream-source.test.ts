/**
 * P2-A (app stream) — the engine wiring lock.
 *
 * The DoD test lives here: "force the top rung to fail, playback continues
 * on rung two." Zero resolution logic is tested — the resolver mirror is
 * vi.mock'ed; what is locked is the ENGINE wiring: the claim hook, the
 * cached-ladder walk (no pipeline hit), the stale-403 re-resolve (and its
 * mid-crossfade deferral), and the seek strategy per rangeSupport.
 *
 * NOTE: every fixture uses a UNIQUE host — the session urlIndex is module
 * state shared across tests, so identical URL strings across fixtures
 * would resolve to the first registrant's session.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
    return {
        calls: { forced: [] as string[], invalidated: [] as string[] },
        probes: new Map<string, { ok: boolean; status?: number }>(),
        resolutions: new Map<
            string,
            {
                protocol: 'hls' | 'progressive' | 'sabr';
                rangeSupport: boolean | null;
                throwOnForce?: boolean;
                urls: string[];
            }
        >(),
        store: {
            player: {
                player: {
                    crossfadeDuration: 5,
                    status: 'PLAYING',
                    transitionType: 'GAPLESS',
                },
            },
            timestamp: 0,
        },
    };
});

vi.mock('/@/renderer/store', () => ({
    usePlayerStoreBase: { getState: () => h.store.player },
}));

vi.mock('/@/renderer/store/timestamp.store', () => ({
    useTimestampStoreBase: { getState: () => ({ timestamp: h.store.timestamp }) },
}));

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: () => undefined, error: () => undefined, warn: () => undefined },
}));

vi.mock('../resolver/lib/relayBase', () => ({
    relayBaseCandidate: () => 'https://relay.test',
}));

vi.mock('../resolver/audio/get-stream-url', () => ({
    getStreamUrl: vi.fn(async (videoId: string, opts: { forceRefresh?: boolean } = {}) => {
        const fixture = h.resolutions.get(videoId);
        if (fixture === undefined) throw new Error(`no fixture for ${videoId}`);
        const fresh = opts.forceRefresh === true;
        if (fresh) {
            h.calls.forced.push(videoId);
            if (fixture.throwOnForce === true) {
                throw new Error('no rung resolved (fixture: pipeline refuses)');
            }
        }
        const url0 = fixture.urls[0] as string;
        return {
            artist: 'Fixture Artist',
            codecs: 'opus',
            contentLength: 3456789,
            durationMs: 213000,
            expiresAt: 3,
            expirySource: 'wire',
            isSabr: false,
            ladder: fixture.urls.map((url, rank) => ({
                audioOnly: true,
                bitrate: 130000,
                codec: 'opus',
                contentLength: 3456789,
                itag: 251 - rank * 111,
                mimeType: 'audio/webm; codecs="opus"',
                rank,
                url: fresh ? `${url}&fresh=1` : url,
            })),
            protocol: fixture.protocol,
            rangeSupport: fixture.rangeSupport,
            resolvedAt: fresh ? 2 : 1,
            rung: 'visionos-direct',
            title: 'Fixture',
            trackId: videoId,
            url: fresh ? `${url0}&fresh=1` : url0,
        };
    }),
    invalidateStreamUrl: vi.fn((videoId: string, reason: string) => {
        h.calls.invalidated.push(`${videoId}:${reason}`);
        return true;
    }),
    peekStreamUrl: vi.fn(() => null),
    probeStreamUrl: vi.fn(async (url: string) => {
        const probe = h.probes.get(url);
        if (probe === undefined || probe.ok) {
            return { acceptsRanges: true, contentLength: 1, ok: true, resolvedUrl: url };
        }
        return { error: 'probe failed', ok: false, status: probe.status ?? null };
    }),
}));

import {
    applyStreamifySeek,
    onStreamifySourceError,
    resolveStreamifyTrack,
    StreamifySabrError,
} from '../stream-source';

/** A minimal HTMLMediaElement stand-in (listener-aware). */
function fakeAudio(url: string): HTMLMediaElement {
    const listeners = new Map<string, Set<() => void>>();
    const state = { playing: false };
    const el = {
        addEventListener(name: string, fn: () => void) {
            if (!listeners.has(name)) listeners.set(name, new Set());
            listeners.get(name)?.add(fn);
        },
        currentSrc: url,
        currentTime: 42,
        duration: 213,
        emit(name: string) {
            for (const fn of [...(listeners.get(name) ?? [])]) fn();
        },
        load() {
            // A new source starts at position 0 (element behavior).
            el.currentTime = 0;
            el.emit('loadedmetadata');
        },
        muted: false,
        pause() {
            state.playing = false;
        },
        play() {
            state.playing = true;
            if (el.playbackRate > 1) {
                // Fast-forward race: position advances by the rate per
                // timeupdate tick, until the duration.
                while (el.currentTime < 213) {
                    el.currentTime += el.playbackRate;
                    el.emit('timeupdate');
                }
            }
            return Promise.resolve();
        },
        playbackRate: 1,
        removeEventListener(name: string, fn: () => void) {
            listeners.get(name)?.delete(fn);
        },
        src: url,
    };
    return el as unknown as HTMLMediaElement;
}

async function pipelineHits(): Promise<number> {
    const mod = await vi.importMock<typeof import('../resolver/audio/get-stream-url')>(
        '../resolver/audio/get-stream-url',
    );
    return mod.getStreamUrl.mock.calls.length;
}

beforeEach(() => {
    h.calls.forced = [];
    h.calls.invalidated = [];
    h.probes.clear();
    h.resolutions.clear();
    h.store = {
        player: {
            player: { crossfadeDuration: 5, status: 'playing', transitionType: 'GAPLESS' },
        },
        timestamp: 0,
    };
    vi.clearAllMocks();
});

describe('stream-source — the engine wiring lock', () => {
    it('resolveStreamifyTrack registers the session and returns the live URL', async () => {
        h.resolutions.set('FIX_STD', {
            protocol: 'progressive',
            rangeSupport: true,
            urls: [
                'https://rr-std.test/videoplayback?itag=251',
                'https://rr-std.test/videoplayback?itag=140',
                'https://rr-std.test/videoplayback?itag=18',
            ],
        });
        const resolution = await resolveStreamifyTrack('FIX_STD');
        expect(resolution.url).toBe('https://rr-std.test/videoplayback?itag=251');
        // The reverse index claimed all ladder URLs.
        expect(
            onStreamifySourceError(
                { label: 'DECODE' },
                fakeAudio('https://rr-std.test/videoplayback?itag=140'),
            ),
        ).toBe(true);
    });

    it('SABR resolves surface the honest unplayable state', async () => {
        h.resolutions.set('FIX_SABRONLY', {
            protocol: 'sabr',
            rangeSupport: null,
            urls: ['https://sabr.test/x'],
        });
        await expect(resolveStreamifyTrack('FIX_SABRONLY')).rejects.toBeInstanceOf(
            StreamifySabrError,
        );
    });

    it('DECODE ERROR → the walk continues on rung two — the cached ladder, ZERO pipeline hits', async () => {
        // The DoD test: the top rung fails, playback continues on rung two.
        const u251 = 'https://rr-walk.test/videoplayback?itag=251';
        const u140 = 'https://rr-walk.test/videoplayback?itag=140';
        const u18 = 'https://rr-walk.test/videoplayback?itag=18';
        h.resolutions.set('FIX_WALK', {
            protocol: 'progressive',
            rangeSupport: true,
            urls: [u251, u140, u18],
        });
        await resolveStreamifyTrack('FIX_WALK');
        const before = await pipelineHits();

        // The failing URL is healthy (probe 206) → a decode/codec error.
        const audio = fakeAudio(u251);
        expect(onStreamifySourceError({ code: 3, label: 'DECODE' }, audio)).toBe(true);

        // The async recovery swaps to rung two at the same position.
        await vi.waitFor(() => expect(audio.src).toBe(u140));
        expect(audio.currentTime).toBe(42);
        // The contract: retries hit the cache — never the pipeline.
        expect(await pipelineHits()).toBe(before);
        // The walk continues on the NEXT error (rung three).
        (audio as unknown as { currentSrc: string }).currentSrc = audio.src;
        expect(onStreamifySourceError({ label: 'DECODE' }, audio)).toBe(true);
        await vi.waitFor(() => expect(audio.src).toBe(u18));
        expect(await pipelineHits()).toBe(before);
    });

    it('STALE (403) → invalidate + forceRefresh re-resolve; fresh top rung in place', async () => {
        const staleUrl = 'https://rr-stale.test/videoplayback?itag=251';
        h.resolutions.set('FIX_STALE', {
            protocol: 'progressive',
            rangeSupport: true,
            urls: [staleUrl, 'https://rr-stale.test/videoplayback?itag=140'],
        });
        await resolveStreamifyTrack('FIX_STALE');
        h.probes.set(staleUrl, { ok: false, status: 403 });

        const audio = fakeAudio(staleUrl);
        expect(onStreamifySourceError({ code: 2, label: 'NETWORK' }, audio)).toBe(true);
        await vi.waitFor(() => expect(h.calls.forced).toContain('FIX_STALE'));
        expect(h.calls.invalidated[0]).toContain('FIX_STALE');
        expect(h.calls.invalidated[0]).toContain('403');
        // The swap went to the FRESH top rung (not the stale ladder).
        await vi.waitFor(() => expect(audio.src).toBe(`${staleUrl}&fresh=1`));
    });

    it('STALE (403) mid-crossfade → the walk, never the pipeline (the contract rule)', async () => {
        const staleUrl = 'https://rr-xfade.test/videoplayback?itag=251';
        h.resolutions.set('FIX_XFADE', {
            protocol: 'progressive',
            rangeSupport: true,
            urls: [staleUrl, 'https://rr-xfade.test/videoplayback?itag=140'],
        });
        await resolveStreamifyTrack('FIX_XFADE');
        h.probes.set(staleUrl, { ok: false, status: 403 });
        // Inside the crossfade window: duration 213 s, position 209 s, 5 s fade.
        // PlayerStyle.CROSSFADE's enum VALUE is the lowercase 'crossfade'.
        (h.store.player.player as { transitionType: string }).transitionType = 'crossfade';
        h.store.timestamp = 209;

        const audio = fakeAudio(staleUrl);
        expect(onStreamifySourceError({ label: 'NETWORK' }, audio)).toBe(true);
        await vi.waitFor(() =>
            expect(audio.src).toBe('https://rr-xfade.test/videoplayback?itag=140'),
        );
        // Deferred: the invalidation is recorded, the re-resolve is NOT issued.
        expect(h.calls.invalidated[0]).toContain('deferred');
        expect(h.calls.forced).toEqual([]);
    });

    it('STALE (403) with a refusing pipeline → the cached walk is the fallback', async () => {
        const staleUrl = 'https://rr-fallback.test/videoplayback?itag=251';
        h.resolutions.set('FIX_FALLBACK', {
            protocol: 'progressive',
            rangeSupport: true,
            throwOnForce: true,
            urls: [staleUrl, 'https://rr-fallback.test/videoplayback?itag=140'],
        });
        await resolveStreamifyTrack('FIX_FALLBACK');
        h.probes.set(staleUrl, { ok: false, status: 410 });

        const audio = fakeAudio(staleUrl);
        expect(onStreamifySourceError({ label: 'NETWORK' }, audio)).toBe(true);
        await vi.waitFor(() =>
            expect(audio.src).toBe('https://rr-fallback.test/videoplayback?itag=140'),
        );
        expect(h.calls.forced).toContain('FIX_FALLBACK');
    });

    it('a foreign source is never claimed (the stock path keeps it)', () => {
        const audio = fakeAudio('https://navidrome.example/rest/stream.view?id=42');
        expect(onStreamifySourceError({ label: 'DECODE' }, audio)).toBe(false);
    });

    it('seek strategy: range-capable and HLS fall through; non-range reloads + fast-forwards', async () => {
        const noRangeUrl = 'https://rr-seek.test/videoplayback?itag=251';
        h.resolutions.set('FIX_SEEK', {
            protocol: 'progressive',
            rangeSupport: false,
            urls: [noRangeUrl, 'https://rr-seek.test/videoplayback?itag=140'],
        });
        const resolution = await resolveStreamifyTrack('FIX_SEEK');
        expect(resolution.rangeSupport).toBe(false);

        const audio = fakeAudio(noRangeUrl);
        // Claimed: the reload-from-zero + silent fast-forward strategy.
        expect(applyStreamifySeek(audio, 120)).toBe(true);
        // The fake fires loadedmetadata synchronously on load(); the
        // fast-forward race then pushes currentTime up to the target.
        await vi.waitFor(() => expect(audio.currentTime).toBe(120));
        expect(audio.muted).toBe(false); // restored after the silent race
        expect(audio.playbackRate).toBe(1);

        // Range-capable sources are NOT claimed (stock in-place seek).
        const rangeUrl = 'https://rr-range.test/videoplayback?range=1';
        h.resolutions.set('FIX_RANGE', {
            protocol: 'progressive',
            rangeSupport: true,
            urls: [rangeUrl],
        });
        await resolveStreamifyTrack('FIX_RANGE');
        const ranged = fakeAudio(rangeUrl);
        expect(applyStreamifySeek(ranged, 120)).toBe(false);

        // Sub-second seeks are never claimed (the stock 'seconds' path).
        expect(applyStreamifySeek(ranged, 0.5)).toBe(false);
    });
});
