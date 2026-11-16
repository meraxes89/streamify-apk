/**
 * P5-A — the relayBase.ts unexpanded-placeholder guard.
 *
 * A missing envsubst variable leaves the literal `${STREAMIFY_RELAY_BASE}`
 * in settings.js; before P5-A that leaked as a live relay URL (the relay
 * lane would POST to a URL whose host is literally
 * `${STREAMIFY_RELAY_BASE}`). The guard must reject every placeholder
 * source while accepting valid runtime URLs. Mirrors the session.ts
 * window-knob filter (same envsubst failure mode).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
    isUnexpandedPlaceholder,
} from '../playback/resolver/lib/relayBase';

const KEY = 'STREAMIFY_RELAY_BASE' as const;

/** Fresh module per test: the candidate is memoized at module scope. */
async function fresh(): Promise<typeof import('../playback/resolver/lib/relayBase')> {
    vi.resetModules();
    return import('../playback/resolver/lib/relayBase');
}

/** Point import.meta.env at production semantics (vitest defaults DEV to true). */
function productionEnv(): () => void {
    const env = import.meta.env as unknown as Record<string, unknown>;
    const hadDev = Object.prototype.hasOwnProperty.call(env, 'DEV');
    const oldDev = env.DEV;
    const oldBase = env.VITE_RELAY_BASE_URL;
    env.DEV = false;
    env.VITE_RELAY_BASE_URL = '';
    return () => {
        env.DEV = oldDev;
        if (hadDev) return;
        delete env.DEV;
        void oldBase;
    };
}

describe('P5-A relayBase placeholder guard', () => {
    afterEach(() => {
        delete (globalThis as Record<string, unknown>)[KEY];
        vi.unstubAllEnvs();
        vi.resetModules();
    });

    it('flags unexpanded ${...} placeholders (and only those)', () => {
        expect(isUnexpandedPlaceholder('${STREAMIFY_RELAY_BASE}')).toBe(true);
        expect(isUnexpandedPlaceholder('${VITE_RELAY_BASE_URL}')).toBe(true);
        expect(isUnexpandedPlaceholder('${RELAY}')).toBe(true);
        // Not placeholders: real URLs, partial template tails, empty strings.
        expect(isUnexpandedPlaceholder('https://relay.example.workers.dev')).toBe(false);
        expect(isUnexpandedPlaceholder('http://localhost:8787')).toBe(false);
        expect(isUnexpandedPlaceholder('')).toBe(false);
        expect(isUnexpandedPlaceholder('  ${STREAMIFY_RELAY_BASE}  ')).toBe(false);
    });

    it('rejects a ${STREAMIFY_RELAY_BASE} window knob — relay off, never the literal', async () => {
        const restore = productionEnv();
        (globalThis as Record<string, unknown>)[KEY] = '${STREAMIFY_RELAY_BASE}';
        try {
            const mod = await fresh();
            expect(mod.relayBaseCandidate()).toBe('');
        } finally {
            restore();
        }
    });

    it('rejects placeholders from the ?relay= param and the build env', async () => {
        const restore = productionEnv();
        try {
            // ?relay= — the param branch only runs under a window; stub one.
            (globalThis as { window?: unknown }).window = {
                location: { search: '?relay=${STREAMIFY_RELAY_BASE}' },
            };
            try {
                const mod = await fresh();
                expect(mod.relayBaseCandidate()).toBe('');
            } finally {
                delete (globalThis as { window?: unknown }).window;
            }

            // build env (VITE_RELAY_BASE_URL)
            vi.resetModules();
            vi.stubEnv('VITE_RELAY_BASE_URL', '${VITE_RELAY_BASE_URL}');
            const mod = await import('../playback/resolver/lib/relayBase');
            expect(mod.relayBaseCandidate()).toBe('');
        } finally {
            restore();
        }
    });

    it('accepts valid runtime URLs (deployment knob, param, build env)', async () => {
        const restore = productionEnv();
        try {
            // 1. deployment knob (window/globalThis) — trimmed through
            (globalThis as Record<string, unknown>)[KEY] =
                '  https://streamify-relay.zephyr4289.workers.dev  ';
            const viaRuntime = await fresh();
            expect(viaRuntime.relayBaseCandidate()).toBe(
                'https://streamify-relay.zephyr4289.workers.dev',
            );
            delete (globalThis as Record<string, unknown>)[KEY];

            // 2. ?relay= param
            (globalThis as { window?: unknown }).window = {
                location: { search: '?relay=http://localhost:9999' },
            };
            try {
                const viaParam = await fresh();
                expect(viaParam.relayBaseCandidate()).toBe('http://localhost:9999');
            } finally {
                delete (globalThis as { window?: unknown }).window;
            }

            // 3. build env
            vi.resetModules();
            vi.stubEnv('VITE_RELAY_BASE_URL', 'https://build-env-relay.example.dev');
            const viaBuild = await import('../playback/resolver/lib/relayBase');
            expect(viaBuild.relayBaseCandidate()).toBe('https://build-env-relay.example.dev');
        } finally {
            restore();
        }
    });

    it('never returns a candidate containing an unexpanded placeholder', async () => {
        const restore = productionEnv();
        (globalThis as Record<string, unknown>)[KEY] = '${STREAMIFY_RELAY_BASE}';
        try {
            const mod = await fresh();
            const value = mod.relayBaseCandidate();
            expect(value.startsWith('${')).toBe(false);
            expect(value).not.toContain('STREAMIFY_RELAY_BASE}');
        } finally {
            restore();
        }
    });
});
