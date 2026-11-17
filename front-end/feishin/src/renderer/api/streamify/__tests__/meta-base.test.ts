/**
 * W2-A — the metaBase.ts hard-isolation suite.
 *
 * The locked invariant (the task's own acceptance line): a deployment
 * with STREAMIFY_RELAY_BASE="https://script.google.com/macros/s/abc/exec"
 * (the Apps Script stream-link solver) keeps relayBase() INTACT (the
 * 6-rung audio resolver rides it unchanged) while metaBase() returns
 * null — /ytm/meta is NEVER appended to script.google.com — unless
 * STREAMIFY_META_BASE is explicitly set (priority 1) or the relay is
 * worker-shaped (priority 2).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

const RELAY_KEY = 'STREAMIFY_RELAY_BASE' as const;
const META_KEY = 'STREAMIFY_META_BASE' as const;

const SOLVER_URL = 'https://script.google.com/macros/s/AKfycbabc/exec';
const WORKER_URL = 'https://streamify-relay.example.workers.dev';
const META_WORKER_URL = 'https://streamify-meta.example.workers.dev';

/** Fresh module per test: the candidate is memoized at module scope. */
async function fresh(): Promise<typeof import('../metadata/metaBase')> {
    vi.resetModules();
    return import('../metadata/metaBase');
}

async function freshRelay(): Promise<typeof import('../playback/resolver/lib/relayBase')> {
    vi.resetModules();
    return import('../playback/resolver/lib/relayBase');
}

/** Point import.meta.env at production semantics (vitest defaults DEV to true). */
function productionEnv(): () => void {
    const env = import.meta.env as unknown as Record<string, unknown>;
    const hadDev = Object.prototype.hasOwnProperty.call(env, 'DEV');
    const oldDev = env.DEV;
    env.DEV = false;
    env.VITE_RELAY_BASE_URL = '';
    return () => {
        env.DEV = oldDev;
        if (hadDev) return;
        delete env.DEV;
    };
}

function setGlobal(key: string, value: string | undefined): void {
    const holder = globalThis as Record<string, unknown>;
    if (value === undefined) {
        delete holder[key];
    } else {
        holder[key] = value;
    }
}

describe('W2-A metaBase hard isolation', () => {
    afterEach(() => {
        setGlobal(RELAY_KEY, undefined);
        setGlobal(META_KEY, undefined);
        vi.unstubAllEnvs();
        vi.resetModules();
    });

    it('isAppsScriptUrl matches exactly the Apps Script deployment shape', async () => {
        const mod = await fresh();
        expect(mod.isAppsScriptUrl(SOLVER_URL)).toBe(true);
        expect(mod.isAppsScriptUrl('https://script.google.com/macros/s/x/y/exec')).toBe(true);
        // Not the solver shape:
        expect(mod.isAppsScriptUrl(WORKER_URL)).toBe(false);
        expect(mod.isAppsScriptUrl('https://script.google.com/home')).toBe(false);
        expect(mod.isAppsScriptUrl('http://script.google.com/macros/s/abc/exec')).toBe(false); // http, not https
        expect(mod.isAppsScriptUrl('')).toBe(false);
        expect(mod.isAppsScriptUrl('not a url')).toBe(false);
    });

    it('THE isolation case: Apps-Script relay + unset META_BASE → relayBase intact, metaBase null', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, SOLVER_URL);
            const meta = await fresh();
            const relay = await freshRelay();
            // The 6-rung audio resolver's knob is untouched.
            expect(relay.relayBaseCandidate()).toBe(SOLVER_URL);
            // The metadata seam stays OFF — never /ytm/meta on script.google.com.
            expect(meta.metaBaseCandidate()).toBe('');
        } finally {
            restore();
        }
    });

    it('priority 1: an explicit STREAMIFY_META_BASE wins over the Apps-Script relay', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, SOLVER_URL);
            setGlobal(META_KEY, META_WORKER_URL);
            const meta = await fresh();
            expect(meta.metaBaseCandidate()).toBe(META_WORKER_URL);
        } finally {
            restore();
        }
    });

    it('priority 1 validation: placeholders, non-http(s) and garbage read as unset', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, SOLVER_URL);
            // Unexpanded placeholder (a missing envsubst variable)
            setGlobal(META_KEY, '${STREAMIFY_META_BASE}');
            expect((await fresh()).metaBaseCandidate()).toBe('');
            // Whitespace + placeholder-ish tail
            setGlobal(META_KEY, '  ${STREAMIFY_META_BASE}  ');
            expect((await fresh()).metaBaseCandidate()).toBe('');
            // Non-http protocol
            setGlobal(META_KEY, 'ftp://meta.example.dev');
            expect((await fresh()).metaBaseCandidate()).toBe('');
            // Not a URL at all
            setGlobal(META_KEY, 'streamify-meta');
            expect((await fresh()).metaBaseCandidate()).toBe('');
            // Empty = unset
            setGlobal(META_KEY, '');
            expect((await fresh()).metaBaseCandidate()).toBe('');
        } finally {
            restore();
        }
    });

    it('priority 2: a worker-shaped relay is reused when META_BASE is unset', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, WORKER_URL);
            const meta = await fresh();
            expect(meta.metaBaseCandidate()).toBe(WORKER_URL);
        } finally {
            restore();
        }
    });

    it('priority 2 loses to priority 1: an explicit META_BASE beats the worker relay', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, WORKER_URL);
            setGlobal(META_KEY, META_WORKER_URL);
            const meta = await fresh();
            expect(meta.metaBaseCandidate()).toBe(META_WORKER_URL);
        } finally {
            restore();
        }
    });

    it('no relay, no meta → empty (edge-off; the provider falls back)', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, '');
            setGlobal(META_KEY, '');
            const meta = await fresh();
            expect(meta.metaBaseCandidate()).toBe('');
        } finally {
            restore();
        }
    });

    it('resetMetaBaseCache re-resolves after the knobs change', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, SOLVER_URL);
            const meta = await fresh();
            expect(meta.metaBaseCandidate()).toBe('');
            // The deployment stamps the meta worker at runtime (settings.js
            // reload) — the reset must pick it up.
            setGlobal(META_KEY, META_WORKER_URL);
            expect(meta.metaBaseCandidate()).toBe(''); // memoized
            meta.resetMetaBaseCache();
            expect(meta.metaBaseCandidate()).toBe(META_WORKER_URL);
        } finally {
            restore();
        }
    });

    it('metaBase never returns a candidate containing an unexpanded placeholder', async () => {
        const restore = productionEnv();
        try {
            setGlobal(RELAY_KEY, SOLVER_URL);
            setGlobal(META_KEY, '${STREAMIFY_META_BASE}');
            const meta = await fresh();
            const value = meta.metaBaseCandidate();
            expect(value.startsWith('${')).toBe(false);
            expect(value).not.toContain('STREAMIFY_');
        } finally {
            restore();
        }
    });
});
