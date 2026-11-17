/**
 * W3-A — the 3-plane diagnostics suite (network-free).
 *
 * Locks, per the wave contract:
 *   • the state transitions Healthy / Degraded / Unconfigured across all
 *     three planes (solver / edge KV / cloud sync) with INJECTED fetches;
 *   • the P5-B redaction contract: the diagnostic report NEVER carries a
 *     raw URL, a token, signed-stream material, or a script.google.com
 *     deployment path — even though the probes themselves saw the full
 *     deployment URLs. Masked hostnames, status codes, latencies only.
 */

import { describe, expect, it, vi } from 'vitest';

import {
    classifySolverBase,
    DIAG_EDGE_PROBE_VIDEO_ID,
    type DiagnosticReport,
    formatDiagnosticReport,
    type PlaneResult,
    redactHostname,
    redactUrlToHost,
} from '/@/renderer/features/streamify-settings/connection-diagnostics';

const SOLVER_APPS_SCRIPT = 'https://script.google.com/macros/s/AKfycbSECRETDEPLOYMENT123/exec';
const SOLVER_WORKER = 'https://streamify-relay.zephyr4289.workers.dev';
const META_WORKER = 'https://streamify-meta.zephyr4289.workers.dev';

/** A fetch double: canned responses per URL prefix. */
function fetchWith(
    routes: Array<{ body?: unknown; match: (url: string) => boolean; status: number }>,
) {
    const seen: string[] = [];
    const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
        const url =
            typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
        seen.push(url);
        void init;
        const route = routes.find((r) => r.match(url));
        if (route === undefined) {
            return new Response('not found', { status: 404 });
        }
        const body = route.body === undefined ? 'ok' : JSON.stringify(route.body);
        return new Response(body, { status: route.status });
    }) as typeof fetch;
    return { impl, seen };
}

/**
 * The relay/metaBase knobs are memoized at their own module scopes — each
 * case re-imports the probe module fresh (vi.resetModules) with the
 * runtime knobs injected + production env semantics (the relay-base test
 * pattern: vitest defaults import.meta.env.DEV to true, which would fall
 * the relay candidate back to localhost).
 */
async function freshWithBases(
    relayBase: string,
    metaBase: string,
): Promise<{
    mod: typeof import('/@/renderer/features/streamify-settings/connection-diagnostics');
    restore: () => void;
}> {
    const env = import.meta.env as unknown as Record<string, unknown>;
    const hadDev = Object.prototype.hasOwnProperty.call(env, 'DEV');
    const oldDev = env.DEV;
    env.DEV = false;
    (env as { VITE_RELAY_BASE_URL?: string }).VITE_RELAY_BASE_URL = '';
    const holder = globalThis as Record<string, unknown>;
    const hadRelay = holder.STREAMIFY_RELAY_BASE;
    const hadMeta = holder.STREAMIFY_META_BASE;
    holder.STREAMIFY_RELAY_BASE = relayBase;
    holder.STREAMIFY_META_BASE = metaBase;
    vi.resetModules();
    const mod = await import('/@/renderer/features/streamify-settings/connection-diagnostics');
    return {
        mod,
        restore: () => {
            env.DEV = oldDev;
            if (!hadDev) delete env.DEV;
            if (hadRelay === undefined) delete holder.STREAMIFY_RELAY_BASE;
            else holder.STREAMIFY_RELAY_BASE = hadRelay;
            if (hadMeta === undefined) delete holder.STREAMIFY_META_BASE;
            else holder.STREAMIFY_META_BASE = hadMeta;
            vi.resetModules();
        },
    };
}

// The P5-B leak patterns (the observability-guard's own scanner).
const LEAK_PATTERNS: [name: string, re: RegExp][] = [
    ['an absolute URL', /https?:\/\//i],
    ['an auth token', /access_token|refresh_token|bearer\s|authorization/i],
    ['signed-stream material', /pot=|signaturecipher|ustreamerconfig/i],
    ['an Apps Script deployment path', /\/macros\/s\/|AKfycb/i],
    ['a JWT shape', /eyJ[A-Za-z0-9_-]+\./],
];

function expectNoLeaks(blob: string, surface: string): void {
    for (const [name, re] of LEAK_PATTERNS) {
        expect(re.test(blob), `${surface} must not leak ${name}`).toBe(false);
    }
}

// ---------------------------------------------------------------------------
// Redaction primitives
// ---------------------------------------------------------------------------

describe('W3-A diagnostics: the redaction primitives', () => {
    it('redactHostname keeps only the last two labels', () => {
        expect(redactHostname('script.google.com')).toBe('…google.com');
        expect(redactHostname('streamify-relay.zephyr4289.workers.dev')).toBe('…workers.dev');
        expect(redactHostname('abcdefghijklmnop.supabase.co')).toBe('…supabase.co');
        expect(redactHostname('example.com')).toBe('example.com');
        expect(redactHostname('localhost')).toBe('localhost');
        expect(redactHostname('')).toBe('');
        expect(redactHostname('Foo.Bar.BAZ.')).toBe('…bar.baz'); // trailing dot stripped, 3 labels → last two
    });

    it('redactUrlToHost drops protocol, path and query entirely', () => {
        expect(redactUrlToHost(SOLVER_APPS_SCRIPT)).toBe('…google.com');
        expect(redactUrlToHost(`${META_WORKER}/ytm/meta?endpoint=chapters&id=x&token=SECRET`)).toBe(
            '…workers.dev',
        );
        expect(redactUrlToHost('not a url')).toBeNull();
        expect(redactUrlToHost('')).toBeNull();
    });

    it('classifySolverBase splits the Apps Script / worker / unset shapes', () => {
        expect(classifySolverBase(SOLVER_APPS_SCRIPT)).toBe('apps-script');
        expect(classifySolverBase(SOLVER_WORKER)).toBe('relay');
        expect(classifySolverBase('')).toBe('unset');
        expect(classifySolverBase('https://not-script.google.com/other')).toBe('relay');
    });
});

// ---------------------------------------------------------------------------
// The plane probes (state transitions)
// ---------------------------------------------------------------------------

describe('W3-A diagnostics: the 3-plane state transitions', () => {
    it('solver plane: unset → Unconfigured with ZERO requests', async () => {
        const { mod, restore } = await freshWithBases('', '');
        try {
            const fetcher = fetchWith([]);
            const result = await mod.probeStreamSolver({ fetchImpl: fetcher.impl });
            expect(result.overall).toBe('unconfigured');
            expect(result.statusDetail).toBe('not-configured');
            expect(result.maskedTarget).toBeNull();
            expect(fetcher.seen).toHaveLength(0);
        } finally {
            restore();
        }
    });

    it('solver plane: an Apps Script base probes ?ping=1 (one request, quota-safe)', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_APPS_SCRIPT, '');
        try {
            const { impl, seen } = fetchWith([
                { match: (url) => url.includes('ping=1'), status: 200 },
            ]);
            const result = await mod.probeStreamSolver({ fetchImpl: impl });
            expect(result.overall).toBe('healthy');
            expect(result.statusDetail).toBe('ok');
            expect(result.maskedTarget).toBe('…google.com');
            // EXACTLY ONE ping — the Apps Script quota rule.
            expect(seen).toHaveLength(1);
            expect(seen[0]?.startsWith(`${SOLVER_APPS_SCRIPT}?ping=1`)).toBe(true);
        } finally {
            restore();
        }
    });

    it('solver plane: a worker base probes the capability echo (GET /)', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_WORKER, '');
        try {
            const { impl, seen } = fetchWith([
                {
                    body: { capabilities: ['ytm-meta', 'neg-cache'] },
                    match: (url) => url === `${SOLVER_WORKER}/`,
                    status: 200,
                },
            ]);
            const result = await mod.probeStreamSolver({ fetchImpl: impl });
            expect(result.overall).toBe('healthy');
            expect(result.maskedTarget).toBe('…workers.dev');
            expect(seen).toEqual([`${SOLVER_WORKER}/`]);
        } finally {
            restore();
        }
    });

    it('solver plane: a network failure → Degraded (network-error)', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_WORKER, '');
        try {
            const failing = (async () => {
                throw new TypeError('fetch failed');
            }) as typeof fetch;
            const result = await mod.probeStreamSolver({ fetchImpl: failing });
            expect(result.overall).toBe('degraded');
            expect(result.statusDetail).toBe('network-error');
        } finally {
            restore();
        }
    });

    it('edge plane: cache.state mapping (fill=MISS, fresh=HIT, uncached=BYPASS) + unconfigured', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_APPS_SCRIPT, META_WORKER);
        try {
            const cacheCase = async (cache: { state?: string; uncached?: boolean }) => {
                const { impl, seen } = fetchWith([
                    {
                        body: { cache, payload: { kind: 'chapters' } },
                        match: (url) => url.includes('/ytm/meta'),
                        status: 200,
                    },
                ]);
                const result = await mod.probeEdgeMetaCache({ fetchImpl: impl });
                expect(seen).toHaveLength(1);
                expect(seen[0]?.includes(`id=${DIAG_EDGE_PROBE_VIDEO_ID}`)).toBe(true);
                return result;
            };

            const miss = await cacheCase({ state: 'fill' });
            expect(miss.overall).toBe('healthy');
            expect(miss.cacheState).toBe('miss');
            expect((await cacheCase({ state: 'fresh' })).cacheState).toBe('hit');
            expect((await cacheCase({ state: 'stale' })).cacheState).toBe('hit');
            expect((await cacheCase({ uncached: true })).cacheState).toBe('bypass');
            expect((await cacheCase({})).cacheState).toBeNull();
        } finally {
            restore();
        }

        // Unconfigured: an Apps-Script-shaped relay + unset META_BASE →
        // metaBase returns null (the W2-A isolation) → ZERO requests.
        const fresh2 = await freshWithBases(SOLVER_APPS_SCRIPT, '');
        try {
            const fetcher = fetchWith([]);
            const result = await fresh2.mod.probeEdgeMetaCache({ fetchImpl: fetcher.impl });
            expect(result.overall).toBe('unconfigured');
            expect(fetcher.seen).toHaveLength(0);
        } finally {
            fresh2.restore();
        }
    });

    it('cloud plane: the injected check drives Healthy / Degraded', async () => {
        const { mod, restore } = await freshWithBases('', '');
        try {
            const healthy = await mod.probeCloudSync({
                cloudCheck: async () => ({ latencyMs: 120, ok: true, statusDetail: 'auth-live' }),
            });
            expect(healthy.overall).toBe('healthy');
            expect(healthy.statusDetail).toBe('auth-live');
            expect(healthy.maskedTarget).toBe('…supabase.co');

            const degraded = await mod.probeCloudSync({
                cloudCheck: async () => ({
                    latencyMs: 0,
                    ok: false,
                    statusDetail: 'channel-error',
                }),
            });
            expect(degraded.overall).toBe('degraded');
            expect(degraded.statusDetail).toBe('channel-error');

            await expect(mod.probeCloudSync({})).rejects.toThrow('injected cloudCheck');
        } finally {
            restore();
        }
    });

    it('an HTTP error status → Degraded (http-error) with the status recorded', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_WORKER, '');
        try {
            const { impl } = fetchWith([{ match: () => true, status: 503 }]);
            const result = await mod.probeStreamSolver({ fetchImpl: impl });
            expect(result.overall).toBe('degraded');
            expect(result.statusDetail).toBe('http-error');
            expect(result.status).toBe(503);
        } finally {
            restore();
        }
    });
});

// ---------------------------------------------------------------------------
// The copyable report — the P5-B leak-free contract
// ---------------------------------------------------------------------------

describe('W3-A diagnostics: the redacted report (P5-B)', () => {
    it('the report carries masked hosts, statuses and latencies — ZERO leaks', async () => {
        const { mod, restore } = await freshWithBases(SOLVER_APPS_SCRIPT, META_WORKER);
        try {
            const { impl } = fetchWith([
                { match: (url) => url.includes('ping=1'), status: 200 },
                {
                    body: { cache: { state: 'fresh' } },
                    match: (url) => url.includes('/ytm/meta'),
                    status: 200,
                },
            ]);
            const solver = await mod.probeStreamSolver({ fetchImpl: impl });
            const edge = await mod.probeEdgeMetaCache({ fetchImpl: impl });
            const cloud: PlaneResult = {
                cacheState: null,
                latencyMs: 87,
                maskedTarget: '…supabase.co',
                overall: 'healthy',
                plane: 'cloud',
                status: 200,
                statusDetail: 'auth-live',
            };
            const report: DiagnosticReport = {
                planes: [solver, edge, cloud],
                ranAtMs: 1_700_000_000_000,
            };
            const text = mod.formatDiagnosticReport(report);

            // The leak scanner — the P5-B patterns plus the deployment
            // specifics the probes themselves saw on the wire.
            expectNoLeaks(text, 'the diagnostic report');
            expect(text).not.toContain('script.google.com');
            expect(text).not.toContain('zephyr4289');
            expect(text).not.toContain('streamify-relay');
            expect(text).not.toContain('streamify-meta');
            // The MASKED forms ARE present (the useful half of the contract).
            expect(text).toContain('…google.com');
            expect(text).toContain('…workers.dev');
            expect(text).toContain('…supabase.co');
            expect(text).toContain('stream-solver');
            expect(text).toContain('edge-metadata-cache');
            expect(text).toContain('cloud-sync-realtime');
            expect(text).toContain('edgeCache: HIT');
            expect(text).toContain('HTTP 200');
            expect(text).toMatch(/\d+ms/);
        } finally {
            restore();
        }
    });

    it('an unconfigured plane renders the honest dash form', () => {
        const text = formatDiagnosticReport({
            planes: [
                {
                    cacheState: null,
                    latencyMs: null,
                    maskedTarget: null,
                    overall: 'unconfigured',
                    plane: 'solver',
                    status: null,
                    statusDetail: 'not-configured',
                },
            ],
            ranAtMs: 0,
        });
        expectNoLeaks(text, 'the unconfigured report');
        expect(text).toContain('not-configured');
        expect(text).toContain('target: —');
        expect(text).toContain('latency: —');
    });
});
