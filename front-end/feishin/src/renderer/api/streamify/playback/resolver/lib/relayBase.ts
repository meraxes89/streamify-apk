/**
 * playback/resolver/lib/relayBase.ts — the fork-side relay knob.
 *
 * FORK-AUTHORED — the one non-vendored file inside the resolver tree (the
 * other ten are the canonical vendored stack since the P5-A archive;
 * integrity is enforced by scripts/check_feishin_resolver.py in ci.yml).
 * It replaces the retired web app's `lib/engine.ts#relayBaseCandidate` —
 * the wasm bridge module must not vendor (it drags `src/wasm/*` into the
 * graph). Priority shape:
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
 * P5-A: every source is filtered through the unexpanded-placeholder guard
 * (isUnexpandedPlaceholder below) — a `${STREAMIFY_RELAY_BASE}` literal left
 * by a missing envsubst variable reads as unset, never as a live URL.
 * (Priority 0 is URL-validated by its own reader, so placeholders die
 * there too.)
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

/**
 * P5-A: the unexpanded-placeholder guard. Deployments that never define a
 * variable leave the literal `${STREAMIFY_RELAY_BASE}` behind in settings.js
 * (nginx envsubst only substitutes defined variables — the same reason
 * upstream's env-settings parser and our session.ts filter placeholders).
 * Without this guard a missing variable would leak the literal placeholder
 * as a live relay URL. Generalized from session.ts's `${STREAMIFY_…}`
 * filter to every source this knob reads: a relay base can never start
 * with `${`, so the rejection has no false positives.
 */
/** Exported for the P5-A guard test (fork-authored file — no mirror constraint). */
export function isUnexpandedPlaceholder(value: string): boolean {
    return value.startsWith('${') && value.endsWith('}');
}

/** Trim + placeholder-filter a candidate ('' when it must not be used). */
function usableCandidate(raw: string): string {
    const trimmed = raw.trim();
    if (trimmed === '' || isUnexpandedPlaceholder(trimmed)) {
        return '';
    }
    return trimmed;
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

/** Option 3: Read user session cookie for stream authentication. Node-safe. */
export function userCookieCandidate(): string {
    try {
        if (typeof localStorage === 'undefined') return '';
        const raw = localStorage.getItem('streamify.settings.v1');
        if (raw) {
            const parsed = JSON.parse(raw) as {
                settings?: { userCookie?: unknown };
                v?: number;
            };
            if (parsed.v === 1 && typeof parsed.settings?.userCookie === 'string') {
                const cookie = parsed.settings.userCookie.trim();
                if (cookie) return cookie;
            }
        }
        return localStorage.getItem('streamify.user_cookie')?.trim() || '';
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
                if (typeof runtime === 'string') {
                    value = usableCandidate(runtime);
                }
            } catch {
                /* unreachable by construction; defensive */
            }
        }
        if (value === '' && typeof window !== 'undefined') {
            const param = new URLSearchParams(window.location.search).get('relay');
            if (param !== null) {
                value = usableCandidate(param);
            }
        }
        if (value === '') {
            const buildEnv = (import.meta.env.VITE_RELAY_BASE_URL as string | undefined) ?? '';
            value = usableCandidate(buildEnv);
        }
        if (value === '' && import.meta.env.DEV) {
            value = 'http://localhost:8787';
        }
        candidate = value;
    }
    return candidate;
}
