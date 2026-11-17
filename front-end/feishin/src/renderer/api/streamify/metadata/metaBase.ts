/**
 * streamify/metadata/metaBase.ts — W2-A: the metadata-base knob, HARD
 * ISOLATED from the script.google.com solver.
 *
 * WHY THIS EXISTS (the W2-A invariant —
 * front-end/decisions/waves/W2A-video-chapters-meta-isolation.md):
 *
 *   STREAMIFY_RELAY_BASE may legally point at a deployed Apps Script
 *   stream-link solver (script.google.com/macros/s/…/exec — the GSF lane's
 *   egress surface; gsfLane.ts normalizes exactly that shape). Appending
 *   `/ytm/meta` to such a base would mint nonsense URLs against the Apps
 *   Script host (404 HTML pages the edge client would then try to parse as
 *   the KV envelope). The metadata seam therefore resolves its base through
 *   THIS module exclusively — never relayBaseCandidate() directly.
 *
 * THE PRIORITY SHAPE (locked by the W2-A task):
 *
 *   1. window.STREAMIFY_META_BASE — the explicit deployment knob for the
 *      Cloudflare Worker that carries /ytm/meta (…workers.dev). Wired
 *      through settings.js.template / Dockerfile / docker-compose.yaml /
 *      feishin-pages.yml, all defaulting to ''.
 *   2. relayBaseCandidate() — reused ONLY when it is set AND its hostname
 *      is NOT script.google.com (a worker-shaped relay carries /ytm/meta
 *      by construction — the W1-A deployments).
 *   3. Apps Script protection — relayBase() points at script.google.com
 *      and STREAMIFY_META_BASE is unset → NULL. Never append /ytm/meta to
 *      script.google.com.
 *
 * The same unexpanded-placeholder guard relayBase.ts carries (P5-A) filters
 * every source: a literal `${STREAMIFY_META_BASE}` left by a missing
 * envsubst variable reads as unset, never as a live URL.
 */

import { relayBaseCandidate } from '../playback/resolver/lib/relayBase';

let candidate: null | string = null;

/**
 * True when the URL is an Apps Script web-app deployment — the exact shape
 * gsfLane.ts's normalizeGsfUrl accepts (https + script.google.com +
 * /macros/s/ prefix). Exported for the meta-base test lane.
 */
export function isAppsScriptUrl(value: string): boolean {
    if (value === '') return false;
    try {
        const url = new URL(value);
        return (
            url.protocol === 'https:' &&
            url.hostname === 'script.google.com' &&
            url.pathname.startsWith('/macros/s/')
        );
    } catch {
        return false;
    }
}

/** The unexpanded-placeholder guard — same rule as relayBase.ts (P5-A). */
export function isUnexpandedPlaceholder(value: string): boolean {
    return value.startsWith('${') && value.endsWith('}');
}

/**
 * The metadata base — the origin /ytm/meta is resolved against.
 * '' when no safe base exists (the caller treats it as "edge unavailable"
 * and falls back to the client-side InnerTube lane — the W1-A contract).
 */
export function metaBaseCandidate(): string {
    if (candidate === null) {
        // Priority 1: the explicit deployment knob.
        let value = '';
        try {
            const runtime = (globalThis as { STREAMIFY_META_BASE?: string }).STREAMIFY_META_BASE;
            if (typeof runtime === 'string') {
                value = usableCandidate(runtime);
            }
        } catch {
            /* unreachable by construction; defensive */
        }

        // Priority 2 + 3: reuse the relay base ONLY when it is set and NOT
        // an Apps Script solver URL (never append /ytm/meta to
        // script.google.com).
        if (value === '') {
            const relay = relayBaseCandidate();
            if (relay !== '' && !isAppsScriptUrl(relay)) {
                value = usableCandidate(relay);
            }
        }

        candidate = value;
    }
    return candidate;
}

/** Drop the memoized candidate (the settings seam calls this when the
 * deployment knobs change, mirroring resetRelayBaseCache). */
export function resetMetaBaseCache(): void {
    candidate = null;
}

/** Trim + placeholder-filter + URL-validate a candidate ('' when unusable). */
function usableCandidate(raw: string): string {
    const trimmed = raw.trim();
    if (trimmed === '' || isUnexpandedPlaceholder(trimmed)) {
        return '';
    }
    try {
        const url = new URL(trimmed);
        if (url.protocol !== 'https:' && url.protocol !== 'http:') {
            return '';
        }
        return trimmed;
    } catch {
        return '';
    }
}
