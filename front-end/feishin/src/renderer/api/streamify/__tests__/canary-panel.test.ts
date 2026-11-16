/**
 * P5-A — CanaryConsole inside the gated /debug console.
 *
 * The residential canary console (front-end/canary) moved off its retired
 * web-app page (canary.html — archived) into the /debug diagnostics route.
 * This suite locks the mount:
 *
 *   1. the panel renders the console network-free (SSR smoke — the same
 *      component the vite build bundles through the @streamify/canary
 *      alias) and degrades cleanly without a session;
 *   2. the console's host-injection seam works (supabase/defaultRelayBase
 *      props beat the env fallbacks — what wires Feishin's active session
 *      into "Post results to Supabase");
 *   3. THE GATE: the panel is mounted ONLY by the /debug route, which
 *      redirects denied users to / before any panel renders. The pure
 *      ruling (debug-gate-ruling) denies default users; the route source
 *      must keep the canary mount on the far side of that early return.
 */

import type { ProbeReport, ProbeRow, RCondition } from '@streamify/canary/types';
import type { CanaryConsoleProps } from '@streamify/canary/ui/CanaryConsole';

import CanaryConsole from '@streamify/canary/ui/CanaryConsole';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { StreamifyCanaryPanel } from '../../../features/streamify-debug/components/canary-panel';
import { debugGateAllowed } from '../../../features/streamify-debug/hooks/debug-gate-ruling';

const ROUTE_SOURCE = path.resolve(
    __dirname,
    '../../../features/streamify-debug/routes/streamify-debug-route.tsx',
);

/** Minimal valid report for the postResults seam (1 matrix row + 1 R-C). */
function minimalReport(): ProbeReport {
    const row: ProbeRow = {
        audioFmts: 2,
        ciphered: 1,
        client: 'TEST',
        endpoint: 'player',
        fmt251: true,
        latencyMs: 100,
        notes: 'network-free stub row',
        ok: true,
        playability: 'OK',
        reason: '-',
        status: 200,
        totalFmts: 5,
        video: 'dQw4w9WgXcQ',
    };
    const rcond: RCondition = {
        detail: 'network-free stub',
        id: 'R1',
        name: 'test condition',
        result: 'pass',
    };
    return {
        finishedAt: '2026-11-16T00:00:00.000Z',
        pageUrl: 'http://localhost/debug',
        rConditions: [rcond],
        relayBase: 'https://injected-relay.example.workers.dev',
        rows: [row],
        startedAt: '2026-11-16T00:00:00.000Z',
        timezone: 'UTC',
        userAgent: 'vitest',
    };
}

describe('P5-A CanaryConsole mount (network-free)', () => {
    it('renders the console through the feishin panel (SSR smoke)', () => {
        const html = renderToStaticMarkup(React.createElement(StreamifyCanaryPanel));
        // The console's own header + controls (canary-*.css classes travel
        // with the component; the input is editable regardless of defaults).
        expect(html).toContain('residential canary');
        expect(html).toContain('Run probe matrix');
        expect(html).toContain('canary-root');
    });

    it('degrades cleanly without a session (copy-rows fallback stays available)', () => {
        // No active server → supabase undefined → the console keeps its
        // env fallbacks; the run controls still render.
        const html = renderToStaticMarkup(React.createElement(StreamifyCanaryPanel));
        expect(html).toContain('Run probe matrix');
    });

    it('wires the host injection seam: props beat env fallbacks', async () => {
        const html = renderToStaticMarkup(
            React.createElement(CanaryConsole as React.FunctionComponent<CanaryConsoleProps>, {
                defaultRelayBase: 'https://injected-relay.example.workers.dev',
                supabase: {
                    anonKey: 'anon-key-for-active-session',
                    url: 'https://session.supabase.co',
                },
            }),
        );
        // The injected relay becomes the editable input's starting value.
        expect(html).toContain('https://injected-relay.example.workers.dev');

        // The injected Supabase target is what "Post results" uses: the
        // seam is behavioral, not markup (the post section renders only
        // after a run). Stub fetch and post a minimal report through it.
        const calls: Array<{ body: string; headers: Record<string, string>; url: string }> = [];
        const originalFetch = globalThis.fetch;
        globalThis.fetch = (async (url: string | URL, init?: RequestInit) => {
            calls.push({
                body: String(init?.body ?? ''),
                headers: (init?.headers ?? {}) as Record<string, string>,
                url: String(url),
            });
            return new Response(null, { status: 201 });
        }) as typeof fetch;
        try {
            const { postResults } = await import('@streamify/canary/postResults');
            const outcome = await postResults(minimalReport(), {
                anonKey: 'anon-key-for-active-session',
                url: 'https://session.supabase.co/',
            });
            expect(outcome.posted).toBe(true);
            expect(outcome.rowCount).toBeGreaterThan(0);
            expect(calls).toHaveLength(1);
            expect(calls[0]?.url).toBe('https://session.supabase.co/rest/v1/canary_results');
            expect(calls[0]?.headers.apikey).toBe('anon-key-for-active-session');
            expect(calls[0]?.headers.Authorization).toBe('Bearer anon-key-for-active-session');
        } finally {
            globalThis.fetch = originalFetch;
        }
    });

    it('renders the console standalone without any props (legacy shape)', () => {
        const html = renderToStaticMarkup(React.createElement(CanaryConsole));
        expect(html).toContain('residential canary');
        expect(html).toContain('Run probe matrix');
    });
});

describe('P5-A CanaryConsole respects the /debug access gate', () => {
    it('the pure ruling still denies default users (the gate the route mounts under)', () => {
        expect(
            debugGateAllowed({ dev: false, developerMode: false, isAdmin: null, urlFlag: false }),
        ).toBe(false);
        expect(
            debugGateAllowed({ dev: false, developerMode: false, isAdmin: false, urlFlag: true }),
        ).toBe(false);
        // The grants that DO open /debug (and with it the canary panel).
        expect(
            debugGateAllowed({ dev: false, developerMode: true, isAdmin: null, urlFlag: false }),
        ).toBe(true);
        expect(
            debugGateAllowed({ dev: false, developerMode: false, isAdmin: true, urlFlag: true }),
        ).toBe(true);
    });

    it('the route mounts the canary panel only on the gated side of the redirect', () => {
        const source = readFileSync(ROUTE_SOURCE, 'utf-8');
        // The gate's early return: denied users are redirected BEFORE any
        // panel (canary included) can render.
        const redirectAt = source.indexOf('if (!gate.allowed)');
        const navigateAt = source.indexOf('<Navigate replace to="/" />');
        const canaryAt = source.indexOf('<StreamifyCanaryPanel />');
        expect(redirectAt).toBeGreaterThan(-1);
        expect(navigateAt).toBeGreaterThan(redirectAt);
        expect(canaryAt).toBeGreaterThan(navigateAt);
        // And the panel lives in the streamify-debug feature (this tree),
        // imported by the gated route — never registered as a public route.
        expect(source).toContain("from '../components/canary-panel'");
    });
});
