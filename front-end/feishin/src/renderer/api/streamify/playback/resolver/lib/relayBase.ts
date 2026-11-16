/**
 * playback/resolver/lib/relayBase.ts — the fork-side relay knob.
 *
 * FORK-AUTHORED — the one non-vendored file inside the resolver mirror
 * (the sync check enforces byte-identity for the other ten; this one has
 * no upstream twin). It replaces the web app's
 * `lib/engine.ts#relayBaseCandidate` — the wasm bridge module must not
 * vendor (it drags `src/wasm/*` into the graph). Same priority shape as
 * the web app's, plus the fork's own deployment knob and the P4B user
 * override:
 *
 *   0. the user's custom relay override (Streamify settings panel, P4B —
 *      persisted as the plain 'streamify.settings.v1' blob; an explicit
 *      user setting wins over deployment defaults, empty = follow them)
 *   1. window.STREAMIFY_RELAY_BASE (settings.js → nginx envsubst — the
 *      deployment configuration; authoritative for production)
 *   2. ?relay= query param (the dev escape hatch, same as the web app)
 *   3. VITE_RELAY_BASE_URL (build-time)
 *   4. dev default http://localhost:8787, '' in production builds
 *
 * Secrets live in deployment configuration, never in the tree (house rule).
 */

let candidate: null | string = null;

/**
 * P4B: drop the memoized candidate (the settings panel calls this whenever
 * the user override changes so the next resolution picks it up).
 */
export function resetRelayBaseCache(): void {
    candidate = null;
}

/** The persisted user override ('' when unset/invalid). Node-safe. */
function userRelayOverride(): string {
    try {
        const raw =
            typeof localStorage !== 'undefined'
                ? localStorage.getItem('streamify.settings.v1')
                : null;
        if (!raw) return '';
        const parsed = JSON.parse(raw) as {
            settings?: { relayBaseOverride?: unknown };
            v?: number;
        };
        if (parsed.v !== 1) return '';
        const value = parsed.settings?.relayBaseOverride;
        if (typeof value !== 'string') return '';
        const trimmed = value.trim();
        if (trimmed === '') return '';
        try {
            const url = new URL(trimmed);
            if (url.protocol !== 'https:' && url.protocol !== 'http:') return '';
        } catch {
            return '';
        }
        return trimmed;
    } catch {
        return '';
    }
}

export function relayBaseCandidate(): string {
    if (candidate === null) {
        let value = userRelayOverride();
        if (value === '') {
            try {
                const runtime = (globalThis as { STREAMIFY_RELAY_BASE?: string })
                    .STREAMIFY_RELAY_BASE;
                if (typeof runtime === 'string' && runtime.trim() !== '') {
                    value = runtime.trim();
                }
            } catch {
                /* unreachable by construction; defensive */
            }
        }
        if (value === '' && typeof window !== 'undefined') {
            const param = new URLSearchParams(window.location.search).get('relay');
            if (param !== null && param.trim() !== '') {
                value = param.trim();
            }
        }
        if (value === '') {
            const buildEnv = (import.meta.env.VITE_RELAY_BASE_URL as string | undefined) ?? '';
            if (buildEnv.trim() !== '') {
                value = buildEnv.trim();
            }
        }
        if (value === '' && import.meta.env.DEV) {
            value = 'http://localhost:8787';
        }
        candidate = value;
    }
    return candidate;
}
