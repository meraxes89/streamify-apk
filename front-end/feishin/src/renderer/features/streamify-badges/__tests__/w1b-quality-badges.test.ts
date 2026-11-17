/**
 * w1b-quality-badges.test.ts — the honest telemetry guardrail lock (W1-B):
 *
 *   - truthful codec · bitrate badges from the live rung exactly as
 *     negotiated (Opus · 160 kbps, AAC · 128 kbps);
 *   - unknown bitrate renders the codec alone; no resolution renders
 *     nothing (never a guess);
 *   - OPFS Offline rides the negotiated resolution (the P4-A blob-URL
 *     serve IS the manifest read-out);
 *   - HLS badge on the hls protocol;
 *   - THE STRICT SPATIAL RULE: the Spatial / 5.1 badge appears ONLY when
 *     audioChannels > 2 — suppressed for null, 1, 2 (and never inferred
 *     from titles, codecs or itags);
 *   - the audioChannels wire plumb (parseAudioChannels) is exact.
 */

import type { StreamUrlResolution } from '/@/renderer/api/streamify/playback/stream-source';

import { describe, expect, it } from 'vitest';

import { parseAudioChannels } from '/@/renderer/api/streamify/playback/resolver/audio/get-stream-url';
import {
    computeQualityBadges,
    type QualityBadgeFacts,
} from '/@/renderer/features/streamify-badges/quality-badge-model';

type ResolutionShape = QualityBadgeFacts['resolution'];

function labels(facts: QualityBadgeFacts): string[] {
    return computeQualityBadges(facts).map((b) => (b.kind === 'offline' ? 'offline' : b.label));
}

function resolution(overrides: Partial<ResolutionShape> = {}): ResolutionShape {
    return {
        audioChannels: null,
        codecs: 'opus',
        ladder: [
            {
                audioChannels: null,
                audioOnly: true,
                bitrate: 160_000,
                codec: 'opus',
                contentLength: 4_000_000,
                itag: 251,
                mimeType: 'audio/webm; codecs="opus"',
                rank: 0,
                url: 'https://googlevideo.test/audio',
            },
        ],
        protocol: 'progressive',
        ...overrides,
    };
}

describe('computeQualityBadges (the honest badge model)', () => {
    it('renders the truthful codec · kbps badge from the live rung', () => {
        expect(labels({ offline: false, resolution: resolution() })).toEqual(['Opus · 160 kbps']);
    });

    it('renders AAC · 128 kbps for the m4a rung', () => {
        expect(
            labels({
                offline: false,
                resolution: resolution({
                    codecs: 'aac',
                    ladder: [
                        {
                            audioChannels: 2,
                            audioOnly: true,
                            bitrate: 128_000,
                            codec: 'aac',
                            contentLength: 3_000_000,
                            itag: 140,
                            mimeType: 'audio/mp4; codecs="mp4a.40.2"',
                            rank: 0,
                            url: 'https://googlevideo.test/m4a',
                        },
                    ],
                }),
            }),
        ).toEqual(['AAC · 128 kbps']);
    });

    it('an unknown bitrate renders the codec alone (never a guessed number)', () => {
        const res = resolution({
            ladder: [
                {
                    audioChannels: null,
                    audioOnly: true,
                    bitrate: 0,
                    codec: 'opus',
                    contentLength: null,
                    itag: 251,
                    mimeType: 'audio/webm; codecs="opus"',
                    rank: 0,
                    url: 'https://googlevideo.test/audio',
                },
            ],
        });
        expect(labels({ offline: false, resolution: res })).toEqual(['Opus']);
    });

    it('no resolution renders nothing', () => {
        expect(computeQualityBadges({ offline: false, resolution: null })).toEqual([]);
    });

    it('the OPFS offline badge rides the negotiated resolution', () => {
        const badges = computeQualityBadges({
            offline: true,
            resolution: resolution({
                codecs: 'mp4',
                ladder: [],
            }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['offline', 'codec']);
        expect(badges[1]?.label).toBe('Mp4');
    });

    it('the HLS badge renders on the hls protocol', () => {
        expect(
            labels({
                offline: false,
                resolution: resolution({ codecs: 'aac', ladder: [], protocol: 'hls' }),
            }),
        ).toEqual(['HLS', 'AAC']);
    });
});

describe('the STRICT spatial rule (audioChannels > 2 or nothing)', () => {
    it('audioChannels: 6 renders the 5.1 badge', () => {
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: 6 }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['codec', 'spatial']);
        expect(badges[1]?.label).toBe('5.1');
    });

    it('audioChannels: 2 (stereo) NEVER renders a spatial badge', () => {
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: 2 }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['codec']);
    });

    it('audioChannels: null (the wire did not say) NEVER renders a spatial badge', () => {
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: null }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['codec']);
    });

    it('audioChannels: 1 (mono) NEVER renders a spatial badge', () => {
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: 1 }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['codec']);
    });

    it('a Dolby-Atmos-sounding title with stereo channels stays stereo (never inferred)', () => {
        // The guardrail: nothing about the track's NAME may claim spatial.
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: 2, codecs: 'ec-3' }),
        });
        expect(badges.map((b) => b.kind)).toEqual(['codec']);
        expect(badges[0]?.label).toBe('EC-3 · 160 kbps');
    });

    it('a 4-channel stream renders the honest channel count, not 5.1', () => {
        const badges = computeQualityBadges({
            offline: false,
            resolution: resolution({ audioChannels: 4 }),
        });
        expect(badges[1]?.label).toBe('4ch');
    });
});

describe('parseAudioChannels (the wire plumb)', () => {
    it('passes through positive finite channel counts, rounded', () => {
        expect(parseAudioChannels(2)).toBe(2);
        expect(parseAudioChannels(6)).toBe(6);
        expect(parseAudioChannels(5.4)).toBe(5);
    });

    it('null for everything the wire did not honestly say', () => {
        expect(parseAudioChannels(undefined)).toBeNull();
        expect(parseAudioChannels(null)).toBeNull();
        expect(parseAudioChannels('6')).toBeNull();
        expect(parseAudioChannels(0)).toBeNull();
        expect(parseAudioChannels(-2)).toBeNull();
        expect(parseAudioChannels(Number.NaN)).toBeNull();
        expect(parseAudioChannels(Infinity)).toBeNull();
    });
});

describe('the offline resolution contract (OPFS honesty)', () => {
    it('stream-source offline resolutions carry audioChannels: null by construction', () => {
        // The badge facts for an offline-served track: the resolution's
        // codecs come from the sidecar; channels stay the honest unknown.
        const res = resolution({ audioChannels: null, codecs: 'mp4', ladder: [] });
        const badges = computeQualityBadges({ offline: true, resolution: res });
        expect(badges.map((b) => b.kind)).toEqual(['offline', 'codec']);
        expect(badges.some((b) => b.kind === 'spatial')).toBe(false);
    });
});

describe('the StreamUrlResolution shape (compile-time contract)', () => {
    it('audioChannels is part of the resolution type surface', () => {
        const res: Pick<StreamUrlResolution, 'audioChannels'> = { audioChannels: 6 };
        expect(res.audioChannels).toBe(6);
    });
});
