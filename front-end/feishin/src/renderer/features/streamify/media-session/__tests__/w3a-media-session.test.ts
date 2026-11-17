/**
 * W3-A — the OS media-session + deep-link suite (network-free).
 *
 * Locks, per the wave contract:
 *   • navigator.mediaSession metadata / positionState wiring — through an
 *     injected navigator double (the pure bridge module);
 *   • the SAFE NO-OP when mediaSession is absent (feature detection
 *     never throws — JSDOM/older browsers);
 *   • the ?play= / ?import= deep-link parsing + the URL cleanup
 *     (history.replaceState shape: consumed keys scrubbed, everything
 *     else preserved — the scrubOAuthParams discipline).
 */

import { describe, expect, it } from 'vitest';

import {
    cleanDeepLinkUrl,
    parseDeepLink,
} from '/@/renderer/features/streamify/media-session/deep-link';
import {
    applyBridgeTick,
    artworkLadderForVideo,
    buildMediaMetadataInit,
    buildPositionState,
    chapterAwareAlbum,
    getMediaSession,
    type MediaSessionLike,
    type NavigatorLike,
} from '/@/renderer/features/streamify/media-session/media-session-bridge';

// ---------------------------------------------------------------------------
// The media-session bridge (injected navigator double)
// ---------------------------------------------------------------------------

/** A recording navigator double. */
function fakeNavigator(withSession = true): {
    calls: { playbackState: string[]; positionStates: unknown[] };
    navigator: NavigatorLike;
    session: MediaSessionLike | null;
} {
    const calls = { playbackState: [] as string[], positionStates: [] as unknown[] };
    const session: MediaSessionLike | null = withSession
        ? {
              metadata: null,
              playbackState: 'none',
              setPositionState: (state: {
                  duration?: number;
                  playbackRate?: number;
                  position?: number;
              }) => {
                  calls.positionStates.push(state);
              },
          }
        : null;
    const navigator: NavigatorLike = withSession ? { mediaSession: session ?? undefined } : {};
    return { calls, navigator, session };
}

describe('W3-A media session: metadata + positionState wiring', () => {
    it('feature detection: absent mediaSession is null (never a throw)', () => {
        expect(getMediaSession({})).toBeNull();
        expect(getMediaSession({ mediaSession: undefined })).toBeNull();
        expect(getMediaSession({ mediaSession: null as never })).toBeNull();
    });

    it('the artwork ladder: five YTM resolutions off the videoId', () => {
        const ladder = artworkLadderForVideo('dQw4w9WgXcQ');
        expect(ladder).toHaveLength(5);
        expect(ladder[0]?.src).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg');
        expect(ladder[4]?.src).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg');
        expect(ladder.every((entry) => entry.type === 'image/jpeg')).toBe(true);
    });

    it('chapter-aware metadata: the chapter rides the album slot', () => {
        const meta = { album: 'Album', artist: 'Artist', title: 'Title', videoId: 'vid' };
        expect(chapterAwareAlbum(meta, 'Intro')).toBe('Album — Intro');
        expect(chapterAwareAlbum({ ...meta, album: null }, 'Intro')).toBe('Intro');
        expect(chapterAwareAlbum(meta, null)).toBe('Album');
        expect(chapterAwareAlbum(meta, '   ')).toBe('Album');

        const init = buildMediaMetadataInit(meta, 'Intro');
        expect(init.album).toBe('Album — Intro');
        expect(init.artist).toBe('Artist');
        expect(init.title).toBe('Title');
        expect(init.artwork).toHaveLength(5);
        // no videoId → no artwork (a stock server's song has no ladder)
        expect(buildMediaMetadataInit({ ...meta, videoId: null }, null).artwork).toEqual([]);
    });

    it('buildPositionState clamps and nulls the invalid shapes (the spec never throws)', () => {
        expect(
            buildPositionState({ durationSec: 213, playbackRate: 1, positionSec: 41.7 }),
        ).toEqual({
            duration: 213,
            playbackRate: 1,
            position: 41.7,
        });
        // position beyond duration → clamped
        expect(
            buildPositionState({ durationSec: 213, playbackRate: 1, positionSec: 999 })?.position,
        ).toBe(213);
        // bad rate → 1
        expect(
            buildPositionState({ durationSec: 213, playbackRate: 0, positionSec: 1 })?.playbackRate,
        ).toBe(1);
        // no duration / non-finite → null (setPositionState is skipped)
        expect(
            buildPositionState({ durationSec: null, playbackRate: 1, positionSec: 1 }),
        ).toBeNull();
        expect(buildPositionState({ durationSec: 0, playbackRate: 1, positionSec: 1 })).toBeNull();
        expect(
            buildPositionState({ durationSec: 213, playbackRate: 1, positionSec: Number.NaN }),
        ).toBeNull();
        expect(
            buildPositionState({ durationSec: 213, playbackRate: 1, positionSec: -5 }),
        ).toBeNull();
    });

    it('applyBridgeTick applies metadata + playbackState + positionState to a live session', () => {
        const { calls, navigator, session } = fakeNavigator(true);
        const applied = applyBridgeTick(
            navigator,
            {
                metadataInit: buildMediaMetadataInit(
                    { album: 'A', artist: 'B', title: 'T', videoId: 'vid' },
                    'Chapter',
                ),
                playbackState: 'playing',
                positionState: { durationSec: 213, playbackRate: 1, positionSec: 12 },
            },
            (init) => ({ init, kind: 'fake-media-metadata' }),
        );
        expect(applied).toEqual({ metadata: true, playbackState: true, positionState: true });
        expect(session?.playbackState).toBe('playing');
        expect(calls.positionStates).toEqual([{ duration: 213, playbackRate: 1, position: 12 }]);
        expect((session?.metadata as { init: { album: string } }).init.album).toBe('A — Chapter');

        // A metadata-less tick (position-only) keeps the old metadata.
        applyBridgeTick(navigator, {
            metadataInit: null,
            playbackState: 'paused',
            positionState: { durationSec: 213, playbackRate: 1, positionSec: 13 },
        });
        expect(calls.positionStates).toHaveLength(2);
        expect((session?.metadata as { init: { album: string } }).init.album).toBe('A — Chapter');
    });

    it('SAFE NO-OP: an absent mediaSession applies nothing and never throws', () => {
        const { navigator } = fakeNavigator(false);
        expect(() =>
            applyBridgeTick(navigator, {
                metadataInit: buildMediaMetadataInit(
                    { album: 'A', artist: 'B', title: 'T', videoId: 'vid' },
                    null,
                ),
                playbackState: 'playing',
                positionState: { durationSec: 213, playbackRate: 1, positionSec: 1 },
            }),
        ).not.toThrow();
        // The return is the all-false record (the caller can observe the no-op).
        const applied = applyBridgeTick(navigator, {
            metadataInit: null,
            playbackState: 'paused',
            positionState: null,
        });
        expect(applied).toEqual({ metadata: false, playbackState: false, positionState: false });
    });

    it('a session without setPositionState (older embedders) skips position silently', () => {
        const session: MediaSessionLike = { metadata: null, playbackState: 'none' };
        const applied = applyBridgeTick(
            { mediaSession: session },
            {
                metadataInit: null,
                playbackState: 'playing',
                positionState: { durationSec: 10, playbackRate: 1, positionSec: 1 },
            },
        );
        expect(applied.positionState).toBe(false);
        expect(applied.playbackState).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// Deep links
// ---------------------------------------------------------------------------

describe('W3-A deep links: ?play= / ?import= parsing + cleanup', () => {
    it('?play=<videoId> parses and validates the 11-char id shape', () => {
        const parsed = parseDeepLink(new URL('https://app.example/?play=dQw4w9WgXcQ#/home'));
        expect(parsed).toEqual({
            consumedKeys: ['play'],
            target: { kind: 'play', videoId: 'dQw4w9WgXcQ' },
        });
        // Malformed ids are NOT play links (an 8-char id falls through to
        // the other params, then null).
        expect(parseDeepLink(new URL('https://app.example/?play=shortid'))).toBeNull();
        // whitespace trims
        expect(
            parseDeepLink(new URL('https://app.example/?play=%20dQw4w9WgXcQ%20'))?.target,
        ).toEqual({
            kind: 'play',
            videoId: 'dQw4w9WgXcQ',
        });
    });

    it('?import=<ytm url> parses (encoded or raw)', () => {
        const url = 'https://music.youtube.com/playlist?list=PLabc1234567890';
        const parsed = parseDeepLink(
            new URL(`https://app.example/?import=${encodeURIComponent(url)}`),
        );
        expect(parsed).toEqual({
            consumedKeys: ['import'],
            target: { kind: 'import', value: url },
        });
        expect(
            parseDeepLink(new URL('https://app.example/?import=OLAK5uy_abcDEF123'))?.target,
        ).toEqual({
            kind: 'import',
            value: 'OLAK5uy_abcDEF123',
        });
    });

    it('the PWA share-target forms (?text= / ?url=) route YouTube payloads into the importer', () => {
        const shared = 'https://music.youtube.com/watch?v=abc&list=PLxyz0987654321';
        expect(
            parseDeepLink(new URL(`https://app.example/?text=${encodeURIComponent(shared)}`)),
        ).toEqual({
            consumedKeys: ['text'],
            target: { kind: 'import', value: shared },
        });
        expect(
            parseDeepLink(new URL(`https://app.example/?url=${encodeURIComponent(shared)}`))
                ?.target,
        ).toEqual({
            kind: 'import',
            value: shared,
        });
        // A bare PL id shared as text also routes in.
        expect(
            parseDeepLink(new URL('https://app.example/?text=PLabcdefghijklmnopqrst'))?.target
                ?.kind,
        ).toBe('import');
        // A non-YouTube share is NOT an import (never hijacked).
        expect(
            parseDeepLink(new URL('https://app.example/?text=https%3A%2F%2Fexample.com%2Fx')),
        ).toBeNull();
        expect(parseDeepLink(new URL('https://app.example/?text=hello%20world'))).toBeNull();
    });

    it('?play= wins over ?import= (the documented precedence)', () => {
        const parsed = parseDeepLink(
            new URL('https://app.example/?import=PLxyz0987654321&play=dQw4w9WgXcQ'),
        );
        expect(parsed?.target).toEqual({ kind: 'play', videoId: 'dQw4w9WgXcQ' });
    });

    it('cleanDeepLinkUrl scrubs ONLY the consumed keys — hash and other params preserved (the relative form history.replaceState takes)', () => {
        const url = new URL(
            'https://app.example/?play=dQw4w9WgXcQ&other=keep#/now-playing?inner=1',
        );
        expect(cleanDeepLinkUrl(url, ['play'])).toBe('/?other=keep#/now-playing?inner=1');
        // A sole consumed param leaves no dangling '?'
        const sole = new URL('https://app.example/?import=PLxyz0987654321#/home');
        expect(cleanDeepLinkUrl(sole, ['import'])).toBe('/#/home');
        // Nothing consumed → the path is byte-identical
        const untouched = new URL('https://app.example/?a=1#/route');
        expect(cleanDeepLinkUrl(untouched, [])).toBe('/?a=1#/route');
    });

    it('no params → null (boot never re-triggers)', () => {
        expect(parseDeepLink(new URL('https://app.example/#/home'))).toBeNull();
        expect(parseDeepLink(new URL('https://app.example/'))).toBeNull();
    });
});
