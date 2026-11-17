/**
 * features/streamify/media-session/media-session-bridge.ts — the W3-A OS
 * lock-screen bridge (PURE — the network-free test lane drives it with an
 * injected navigator double).
 *
 * WHAT UPSTREAM ALREADY OWNS: the action handlers (play/pause/prev/next/
 * seekto/stop/seekbackward/seekforward) are registered by the stock
 * `use-media-session.ts` hook for every server type — W3-A does NOT
 * re-register them (a re-registration race between two components could
 * wipe the stock registrations; the ADR records this ruling). What W3-A
 * COMPLETES, additively, for Streamify sessions:
 *
 *   • setPositionState({ duration, playbackRate, position }) — the OS
 *     lock-screen scrub bar (upstream never sets it); clamped + guarded
 *     against the InvalidStateError the spec throws on bad values;
 *   • multi-resolution artwork — the YTM thumbnail ladder (default/
 *     mq/hq/sd/maxres) so the OS picks the right size;
 *   • chapter-aware metadata — the active chapter title rides the album
 *     slot while chapters exist (re-evaluated as playback advances);
 *   • playbackState 'none' when playback fully stops.
 *
 * Every entry is feature-detected: an environment without
 * navigator.mediaSession (older browsers, JSDOM) is a clean no-op, never
 * a throw.
 */

export interface BridgeTrackMeta {
    album: null | string;
    artist: null | string;
    title: null | string;
    /** The YTM videoId — drives the multi-resolution artwork ladder. */
    videoId: null | string;
}

export interface MediaSessionLike {
    metadata: null | unknown;
    playbackState: 'none' | 'paused' | 'playing';
    setActionHandler?: (action: string, handler: ((details?: unknown) => void) | null) => void;
    setPositionState?: (state: {
        duration?: number;
        playbackRate?: number;
        position?: number;
    }) => void;
}

/** The navigator shape the bridge accepts — `unknown` so BOTH the DOM
 *  Navigator and test doubles pass structurally (the narrowing happens
 *  inside getMediaSession). */
export interface NavigatorLike {
    mediaSession?: unknown;
}

// ---------------------------------------------------------------------------
// The metadata payload
// ---------------------------------------------------------------------------

export interface PositionStateInput {
    durationSec: null | number;
    /** The player's playback rate (1 = normal). */
    playbackRate: number;
    positionSec: number;
}

/**
 * Apply one bridge tick: metadata (when the init changed), positionState
 * (clamped), playbackState. Returns what was applied (diagnostics/tests).
 */
export function applyBridgeTick(
    navigatorLike: NavigatorLike,
    tick: {
        metadataInit: null | {
            album: string;
            artist: string;
            artwork: Array<{ sizes: string; src: string; type: string }>;
            title: string;
        };
        playbackState: 'none' | 'paused' | 'playing';
        positionState: null | PositionStateInput;
    },
    MediaMetadataCtor?: (init: unknown) => unknown,
): { metadata: boolean; playbackState: boolean; positionState: boolean } {
    const session = getMediaSession(navigatorLike);
    const applied = { metadata: false, playbackState: false, positionState: false };
    if (session === null) return applied;

    if (tick.metadataInit !== null) {
        try {
            if (typeof MediaMetadataCtor === 'function') {
                session.metadata = MediaMetadataCtor(tick.metadataInit);
            } else if (typeof MediaMetadata !== 'undefined') {
                session.metadata = new MediaMetadata(tick.metadataInit);
            } else {
                // Environments with mediaSession but no MediaMetadata ctor
                // (rare embedders): assign the plain shape.
                session.metadata = tick.metadataInit;
            }
            applied.metadata = true;
        } catch {
            // a broken assignment must never break playback
        }
    }

    try {
        session.playbackState = tick.playbackState;
        applied.playbackState = true;
    } catch {
        // read-only in some embedders — best-effort
    }

    if (tick.positionState !== null && typeof session.setPositionState === 'function') {
        const state = buildPositionState(tick.positionState);
        if (state !== null) {
            try {
                session.setPositionState(state);
                applied.positionState = true;
            } catch {
                // spec throws on edge shapes (rate 0 etc.) — never fatal
            }
        }
    }

    return applied;
}

/** The YTM thumbnail ladder (the OS picks the size it needs). */
export function artworkLadderForVideo(
    videoId: string,
): Array<{ sizes: string; src: string; type: string }> {
    const base = `https://i.ytimg.com/vi/${videoId}`;
    return [
        { sizes: '120x120', src: `${base}/default.jpg`, type: 'image/jpeg' },
        { sizes: '320x180', src: `${base}/mqdefault.jpg`, type: 'image/jpeg' },
        { sizes: '480x360', src: `${base}/hqdefault.jpg`, type: 'image/jpeg' },
        { sizes: '640x480', src: `${base}/sddefault.jpg`, type: 'image/jpeg' },
        { sizes: '1280x720', src: `${base}/maxresdefault.jpg`, type: 'image/jpeg' },
    ];
}

/** Build the MediaMetadata-init shape (the caller assigns it). */
export function buildMediaMetadataInit(
    meta: BridgeTrackMeta,
    activeChapterTitle: null | string,
): {
    album: string;
    artist: string;
    artwork: Array<{ sizes: string; src: string; type: string }>;
    title: string;
} {
    const artwork =
        meta.videoId !== null && meta.videoId !== '' ? artworkLadderForVideo(meta.videoId) : [];
    return {
        album: chapterAwareAlbum(meta, activeChapterTitle) ?? '',
        artist: meta.artist ?? '',
        artwork,
        title: meta.title ?? '',
    };
}

// ---------------------------------------------------------------------------
// The position state
// ---------------------------------------------------------------------------

/**
 * The clamped position state, or null when the spec would throw (no
 * duration / non-finite / position beyond duration). The OS scrub bar
 * only ever receives a valid shape.
 */
export function buildPositionState(
    input: PositionStateInput,
): null | { duration: number; playbackRate: number; position: number } {
    const { durationSec, playbackRate, positionSec } = input;
    if (
        durationSec === null ||
        !Number.isFinite(durationSec) ||
        durationSec <= 0 ||
        !Number.isFinite(positionSec) ||
        positionSec < 0
    ) {
        return null;
    }
    const rate = Number.isFinite(playbackRate) && playbackRate > 0 ? playbackRate : 1;
    return {
        duration: durationSec,
        playbackRate: rate,
        position: Math.min(positionSec, durationSec),
    };
}

/**
 * The chapter-aware album slot: while an active chapter exists, the OS
 * overlay shows "Title — Chapter" (the chapter rides the album line; the
 * artist line stays the artist).
 */
export function chapterAwareAlbum(
    meta: BridgeTrackMeta,
    activeChapterTitle: null | string,
): null | string {
    const chapter = activeChapterTitle?.trim();
    if (chapter === undefined || chapter === '') return meta.album;
    if (meta.album === null || meta.album === '') return chapter;
    return `${meta.album} — ${chapter}`;
}

/** Feature detection — the safe gate every caller passes through. */
export function getMediaSession(navigatorLike: NavigatorLike): MediaSessionLike | null {
    const session = navigatorLike.mediaSession;
    if (session === null || session === undefined) return null;
    if (typeof session !== 'object') return null;
    return session as MediaSessionLike;
}
