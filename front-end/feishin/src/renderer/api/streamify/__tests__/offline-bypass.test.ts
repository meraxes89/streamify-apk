/**
 * P4-A — the stream-source zero-egress bypass, driven through the REAL
 * stream-source module (the resolver mirror vi.mock'ed, the downloads
 * instance replaced by a fake via the module's own test seam):
 *
 *   - a downloaded track resolves to a LOCAL blob URL and the resolver
 *     is never touched (no getStreamUrl, no relay);
 *   - a miss falls through to the online ladder;
 *   - an element error on an offline-served source quarantines the entry
 *     and re-resolves ONLINE (forceRefresh) — integrity failures never
 *     block playback.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
    return {
        forced: [] as string[],
        onlineCalls: [] as string[],
        quarantined: [] as string[],
        resolutions: new Map<string, { url: string }>(),
        served: new Map<string, null | { url: string }>(),
    };
});

vi.mock('/@/renderer/store', () => ({
    usePlayerStoreBase: {
        getState: () => ({
            player: { crossfadeDuration: 5, status: 'PLAYING', transitionType: 'GAPLESS' },
        }),
    },
}));

vi.mock('/@/renderer/store/timestamp.store', () => ({
    useTimestampStoreBase: { getState: () => ({ timestamp: 0 }) },
}));

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: () => undefined, error: () => undefined, warn: () => undefined },
}));

vi.mock('../playback/resolver/lib/relayBase', () => ({
    relayBaseCandidate: () => 'https://relay.test',
}));

vi.mock('../playback/resolver/audio/get-stream-url', () => ({
    getStreamUrl: vi.fn(async (videoId: string, opts: { forceRefresh?: boolean } = {}) => {
        h.onlineCalls.push(videoId);
        if (opts.forceRefresh === true) h.forced.push(videoId);
        const fixture = h.resolutions.get(videoId);
        if (fixture === undefined) throw new Error(`no fixture for ${videoId}`);
        return {
            artist: 'Fixture Artist',
            codecs: 'opus',
            contentLength: 3456789,
            durationMs: 213000,
            expiresAt: 3,
            expirySource: 'wire',
            isSabr: false,
            ladder: [],
            protocol: 'progressive',
            rangeSupport: true,
            resolvedAt: 1,
            rung: 'visionos-direct',
            title: 'Fixture',
            trackId: videoId,
            url: opts.forceRefresh === true ? `${fixture.url}&fresh=1` : fixture.url,
        };
    }),
    invalidateStreamUrl: vi.fn(() => true),
    peekStreamUrl: vi.fn(() => null),
    probeStreamUrl: vi.fn(async (url: string) => ({
        acceptsRanges: true,
        contentLength: 1,
        ok: true,
        resolvedUrl: url,
    })),
}));

import type { DownloadManager } from '../downloads/download-manager';

import { setStreamifyDownloadManagerForTests } from '../downloads/instance';
import { onStreamifySourceError, resolveStreamifyTrack } from '../playback/stream-source';

function fakeAudio(url: string): HTMLMediaElement {
    const el = {
        currentSrc: url,
        currentTime: 42,
        load() {
            this.currentTime = 0;
        },
        muted: false,
        play() {
            return Promise.resolve();
        },
        playbackRate: 1,
        src: url,
    };
    return el as unknown as HTMLMediaElement;
}

/** The fake manager: serveOffline serves ONE fixture track; quarantine records. */
function installFakeManager(): void {
    const manager = {
        quarantine: async (videoId: string, reason: string) => {
            h.quarantined.push(`${videoId}:${reason}`);
        },
        serveOffline: async (videoId: string) => {
            const served = h.served.get(videoId);
            if (served === undefined || served === null) return null;
            return {
                artist: 'Offline Artist',
                codecs: 'mp4',
                contentLength: 12345,
                durationMs: 123000,
                mimeType: 'audio/mp4',
                rung: 'visionos-relay',
                title: 'Offline Title',
                url: served.url,
                videoId,
            };
        },
    } as unknown as DownloadManager;
    setStreamifyDownloadManagerForTests(manager);
}

beforeEach(() => {
    h.forced.length = 0;
    h.onlineCalls.length = 0;
    h.quarantined.length = 0;
    h.resolutions.clear();
    h.served.clear();
    installFakeManager();
});

afterEach(() => {
    setStreamifyDownloadManagerForTests(null);
});

describe('P4-A stream-source zero-egress bypass', () => {
    it('serves a downloaded track locally — resolver never called', async () => {
        h.served.set('off1', { url: 'blob:nymock/7' });
        const resolution = await resolveStreamifyTrack('off1');
        expect(resolution.url).toBe('blob:nymock/7');
        expect(resolution.protocol).toBe('progressive');
        expect(resolution.rangeSupport).toBe(true); // seekable in place
        expect(resolution.rung).toBe('visionos-relay'); // honest provenance
        expect(resolution.title).toBe('Offline Title');
        expect(h.onlineCalls).toEqual([]); // ZERO resolver/relay traffic
    });

    it('falls through to the online ladder on a miss', async () => {
        h.resolutions.set('miss1', { url: 'https://googlevideo.test/m1' });
        const resolution = await resolveStreamifyTrack('miss1');
        expect(resolution.url).toBe('https://googlevideo.test/m1');
        expect(h.onlineCalls).toEqual(['miss1']);
    });

    it('prefers offline even when an online fixture exists', async () => {
        h.served.set('both1', { url: 'blob:nymock/8' });
        h.resolutions.set('both1', { url: 'https://googlevideo.test/b1' });
        const resolution = await resolveStreamifyTrack('both1');
        expect(resolution.url).toBe('blob:nymock/8');
        expect(h.onlineCalls).toEqual([]);
    });

    it('element error on an offline source: quarantine + online re-resolve', async () => {
        h.served.set('off2', { url: 'blob:nymock/9' });
        h.resolutions.set('off2', { url: 'https://googlevideo.test/o2' });
        await resolveStreamifyTrack('off2');
        expect(h.onlineCalls).toEqual([]);

        const audio = fakeAudio('blob:nymock/9');
        const claimed = onStreamifySourceError(
            { code: 4, label: 'MEDIA_ERR_SRC_NOT_SUPPORTED' },
            audio,
        );
        expect(claimed).toBe(true);

        // The offline branch: quarantine, then a FORCED online re-resolve
        // that swaps the element's source — recovery, never a dead end.
        await new Promise((r) => setTimeout(r, 0));
        expect(h.quarantined[0]).toMatch(/^off2:/);
        expect(h.forced).toEqual(['off2']);
        expect(audio.src).toContain('https://googlevideo.test/o2');
        expect(audio.src).toContain('fresh=1');
    });

    it('element error on an ONLINE source keeps the stock walk (no quarantine)', async () => {
        h.resolutions.set('onl1', { url: 'https://googlevideo.test/on1' });
        await resolveStreamifyTrack('onl1');
        const audio = fakeAudio('https://googlevideo.test/on1');
        const claimed = onStreamifySourceError({ code: 4, label: 'DECODE' }, audio);
        expect(claimed).toBe(true);
        await new Promise((r) => setTimeout(r, 0));
        expect(h.quarantined).toEqual([]); // quarantine is offline-only
    });
});
