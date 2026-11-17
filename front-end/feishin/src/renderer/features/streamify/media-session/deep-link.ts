/**
 * features/streamify/media-session/deep-link.ts — the W3-A deep-link /
 * share-target entry (PURE).
 *
 * THE PARAMS (read off window.location.search — the app is a HashRouter,
 * so ?play=/?import= live in the real query, before the #):
 *   • ?play=<videoId>          → resolve + start playing immediately
 *   • ?import=<ytm url|id>     → open the W1-A YTM Importer pre-populated
 *   • ?text=<url> | ?url=<url> → the PWA share-target forms (method GET
 *     params: { text: 'text' }) — routed through the SAME importer parse
 *     when the shared payload is a YouTube/YTM link, ignored otherwise.
 *
 * After consumption the params are scrubbed with history.replaceState so
 * a refresh never re-triggers (the scrubOAuthParams discipline).
 */

export type DeepLinkTarget = { kind: 'import'; value: string } | { kind: 'play'; videoId: string };

export interface ParsedDeepLink {
    /** The remaining params (never dropped — only the consumed key goes). */
    consumedKeys: string[];
    target: DeepLinkTarget;
}

const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;

/**
 * The cleaned URL for history.replaceState: the consumed deep-link keys
 * removed, everything else (including the hash) preserved verbatim.
 */
export function cleanDeepLinkUrl(url: URL, consumedKeys: string[]): string {
    const cleaned = new URL(url.toString());
    for (const key of consumedKeys) {
        cleaned.searchParams.delete(key);
    }
    const search = cleaned.searchParams.toString();
    const hash = cleaned.hash || '';
    // Rebuild manually: searchParams preserves order but we want the exact
    // pathname + (search ? `?${search}` : '') + hash shape.
    return `${cleaned.pathname}${search === '' ? '' : `?${search}`}${hash}`;
}

/**
 * Parse a URL's deep-link params (the first match wins; precedence:
 * play > import > the share-target forms).
 */
export function parseDeepLink(url: URL): null | ParsedDeepLink {
    const play = url.searchParams.get('play');
    if (play !== null && VIDEO_ID_RE.test(play.trim())) {
        return { consumedKeys: ['play'], target: { kind: 'play', videoId: play.trim() } };
    }

    const explicitImport = url.searchParams.get('import');
    if (explicitImport !== null && explicitImport.trim() !== '') {
        return {
            consumedKeys: ['import'],
            target: { kind: 'import', value: explicitImport.trim() },
        };
    }

    // The PWA share-target forms: the shared text/url is an arbitrary
    // string — only a YouTube-ish payload routes into the importer.
    for (const key of ['text', 'url'] as const) {
        const shared = url.searchParams.get(key);
        if (shared === null || shared.trim() === '') continue;
        if (looksLikeYoutubeLink(shared.trim())) {
            return { consumedKeys: [key], target: { kind: 'import', value: shared.trim() } };
        }
    }

    return null;
}

/** A share payload worth routing into the importer (YouTube hosts or a
 *  bare playlist/album id form). */
function looksLikeYoutubeLink(value: string): boolean {
    if (/^https?:\/\//i.test(value)) {
        try {
            const url = new URL(value);
            return (
                url.hostname === 'youtube.com' ||
                url.hostname === 'www.youtube.com' ||
                url.hostname === 'music.youtube.com' ||
                url.hostname === 'youtu.be' ||
                url.hostname === 'm.youtube.com'
            );
        } catch {
            return false;
        }
    }
    // The bare YTM list id forms (the importer's url-parser accepts these).
    return /^(PL[A-Za-z0-9_-]{10,}|OLAK5uy_[A-Za-z0-9_-]+|RDCLAK5uy_[A-Za-z0-9_-]+|VL[A-Za-z0-9_-]{10,})$/.test(
        value,
    );
}
