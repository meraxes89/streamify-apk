/**
 * features/streamify-settings/connection-diagnostics.ts — the W3-A
 * 3-plane probe + redaction core (PURE — the network-free test lane
 * injects every fetch; the P5-B observability-guard rules apply).
 *
 * THE THREE PLANES:
 *   1. Stream Solver — STREAMIFY_RELAY_BASE (script.google.com Apps
 *      Script solver OR a Cloudflare worker relay). The probe is
 *      USER-TRIGGERED ONLY (settings mount + the Run button — never a
 *      background loop) and strictly lightweight:
 *        • an Apps-Script-shaped base → ONE ?ping=1 GET (the gsfLane
 *          health probe — the script's own cheapest answer; no UrlFetch
 *          upstream, no quota burn);
 *        • a worker-shaped base → ONE GET / (the capability echo — a
 *          static JSON answer, zero upstream);
 *        • unset → Unconfigured (no request at all).
 *   2. Edge Metadata Cache — STREAMIFY_META_BASE via metaBase.ts (the
 *      W2-A isolation knob): ONE GET /ytm/meta?endpoint=chapters&id=<the
 *      licensed canary> — the FIRST probe fills the 7-day KV entry, every
 *      later probe answers from cache; the response's cache.state maps to
 *      the Edge KV badge (fill = MISS, fresh/stale = HIT, uncached =
 *      BYPASS). Zero Apps Script involvement by construction.
 *   3. Cloud Sync & Realtime — the Supabase session + one throwaway
 *      channel subscribe/unsubscribe (websocket readiness).
 *
 * THE REDACTION RULE (P5-B, strictly enforced + tested): the diagnostic
 * state and the copyable report NEVER carry raw URLs, signed-stream
 * material, tokens, or deployment paths — only the plane label, a
 * classifier, a MASKED hostname (every label before the last two is
 * dropped), the HTTP status, latency ms, and boolean reachability.
 */

import { metaBaseCandidate } from '/@/renderer/api/streamify/metadata/metaBase';
import { relayBaseCandidate } from '/@/renderer/api/streamify/playback/resolver/lib/relayBase';

// ---------------------------------------------------------------------------
// The report model
// ---------------------------------------------------------------------------

export interface DiagnosticReport {
    planes: PlaneResult[];
    ranAtMs: number;
}
export type EdgeCacheState = 'bypass' | 'hit' | 'miss' | null;
export type PlaneId = 'cloud' | 'edge' | 'solver';

export interface PlaneResult {
    /** The Edge KV plane only: the observed cache state. */
    cacheState: EdgeCacheState;
    latencyMs: null | number;
    /** The masked endpoint target (redactHostname's output). */
    maskedTarget: null | string;
    /** The derived badge. */
    overall: PlaneStatus;
    /** The plane's human label (stable ids for i18n). */
    plane: PlaneId;
    /** HTTP status when the probe got an answer. */
    status: null | number;
    statusDetail:
        | 'auth-live'
        | 'channel-error'
        | 'http-error'
        | 'network-error'
        | 'not-configured'
        | 'ok'
        | 'timeout';
}

export type PlaneStatus = 'degraded' | 'healthy' | 'unconfigured';

// ---------------------------------------------------------------------------
// Redaction
// ---------------------------------------------------------------------------

export interface ProbeDeps {
    /** The supabase plane check (session + one channel subscribe). */
    cloudCheck?: () => Promise<{
        latencyMs: number;
        ok: boolean;
        statusDetail: PlaneResult['statusDetail'];
    }>;
    fetchImpl?: typeof fetch;
    /** The probe clock (latency measurement). */
    now?: () => number;
    /** The solver ping fetch (Apps Script bases — the gsfLane ?ping=1). */
    solverPing?: (
        base: string,
    ) => Promise<{ latencyMs: number; ok: boolean; status: null | number }>;
    timeoutMs?: number;
}

/** The plane classifier (carries the semantic identity the mask removes). */
export function classifySolverBase(base: string): 'apps-script' | 'relay' | 'unset' {
    if (base === '') return 'unset';
    try {
        const url = new URL(base);
        if (url.hostname === 'script.google.com' && url.pathname.startsWith('/macros/s/')) {
            return 'apps-script';
        }
    } catch {
        // fall through
    }
    return 'relay';
}

/**
 * Mask a hostname: keep ONLY the last two labels (the public suffix +
 * registrable domain); every deployment-identifying label before them is
 * dropped. `script.google.com` → `…google.com`;
 * `streamify-relay.zephyr4289.workers.dev` → `…workers.dev`.
 * A bare hostname with ≤2 labels stays as-is (nothing to mask).
 */
export function redactHostname(hostname: string): string {
    const clean = hostname.trim().toLowerCase().replace(/\.$/, '');
    if (clean === '') return '';
    const labels = clean.split('.');
    if (labels.length <= 2) return clean;
    return `…${labels.slice(-2).join('.')}`;
}

// ---------------------------------------------------------------------------
// The probes (every network touch injectable)
// ---------------------------------------------------------------------------

/** The full URL redaction: masked hostname ONLY — no protocol, no path,
 *  no query (a deployment path like /macros/s/AKfycb…/exec or a signed
 *  query can never survive). Returns null for unparseable input. */
export function redactUrlToHost(raw: string): null | string {
    const trimmed = raw.trim();
    if (trimmed === '') return null;
    try {
        const url = new URL(trimmed);
        return redactHostname(url.hostname);
    } catch {
        return null;
    }
}

const PROBE_TIMEOUT_MS = 6_000;

/** The licensed canary (the GSF probe's own sample — repo-constant). */
export const DIAG_EDGE_PROBE_VIDEO_ID = '4NRXx6U8ABQ';

interface TimedOutcome {
    detail: PlaneResult['statusDetail'];
    latencyMs: number;
    ok: boolean;
    status: null | number;
}

/**
 * The copyable redacted report — PLAIN TEXT, leak-free by construction:
 * plane labels + masked hosts + statuses + latencies. The w3a test suite
 * pins the format against the P5-B leak patterns (absolute URLs, tokens,
 * signed-stream material, script.google.com paths).
 */
export function formatDiagnosticReport(report: DiagnosticReport): string {
    const lines = [
        'Streamify connection diagnostics',
        `ranAt: ${new Date(report.ranAtMs).toISOString()}`,
        '',
    ];
    for (const plane of report.planes) {
        lines.push(
            `plane: ${planeLabel(plane)}`,
            `  target: ${plane.maskedTarget ?? '—'} (${classifyDetail(plane)})`,
            `  status: ${plane.overall}${plane.status !== null ? ` (HTTP ${plane.status})` : ''}`,
            `  latency: ${plane.latencyMs !== null ? `${plane.latencyMs}ms` : '—'}`,
            plane.plane === 'edge' && plane.cacheState !== null
                ? `  edgeCache: ${plane.cacheState.toUpperCase()}`
                : '',
        );
    }
    return lines.filter((line) => line !== '').join('\n');
}

export async function probeCloudSync(deps: ProbeDeps = {}): Promise<PlaneResult> {
    if (deps.cloudCheck !== undefined) {
        const result = await deps.cloudCheck();
        return {
            cacheState: null,
            latencyMs: result.latencyMs,
            maskedTarget: '…supabase.co',
            overall: result.ok ? 'healthy' : 'degraded',
            plane: 'cloud',
            status: result.ok ? 200 : null,
            statusDetail: result.statusDetail,
        };
    }
    // The production check lives in the panel (it needs the supabase-js
    // client — the pure lane injects cloudCheck instead).
    throw new Error('probeCloudSync requires an injected cloudCheck');
}

// ---------------------------------------------------------------------------
// The shared timed fetch
// ---------------------------------------------------------------------------

export async function probeEdgeMetaCache(deps: ProbeDeps = {}): Promise<PlaneResult> {
    const base = metaBaseCandidate();
    if (base === '') {
        return {
            cacheState: null,
            latencyMs: null,
            maskedTarget: null,
            overall: 'unconfigured',
            plane: 'edge',
            status: null,
            statusDetail: 'not-configured',
        };
    }
    const masked = redactUrlToHost(base);
    // The chapters probe against the canary: the first run fills the
    // 7-day KV entry; every later run is a cache answer. NO Apps Script
    // involvement by construction (the W2-A isolation invariant).
    const url = `${base.replace(/\/+$/, '')}/ytm/meta?endpoint=chapters&id=${DIAG_EDGE_PROBE_VIDEO_ID}`;
    const result = (await timedFetch(deps, url, 'json')) as
        | {
              cacheState: EdgeCacheState;
              latencyMs: number;
              ok: true;
              status: number;
          }
        | {
              detail: 'http-error' | 'network-error' | 'timeout';
              latencyMs: number;
              ok: false;
              status: null | number;
          };
    if (!result.ok) {
        return {
            cacheState: null,
            latencyMs: result.latencyMs,
            maskedTarget: masked,
            overall: 'degraded',
            plane: 'edge',
            status: result.status,
            statusDetail: result.detail,
        };
    }
    return {
        cacheState: result.cacheState,
        latencyMs: result.latencyMs,
        maskedTarget: masked,
        overall: 'healthy',
        plane: 'edge',
        status: result.status,
        statusDetail: 'ok',
    };
}

export async function probeStreamSolver(deps: ProbeDeps = {}): Promise<PlaneResult> {
    const base = relayBaseCandidate();
    if (base === '') {
        return {
            cacheState: null,
            latencyMs: null,
            maskedTarget: null,
            overall: 'unconfigured',
            plane: 'solver',
            status: null,
            statusDetail: 'not-configured',
        };
    }
    const masked = redactUrlToHost(base);
    const shape = classifySolverBase(base);

    // The Apps Script lane: the script's OWN ?ping=1 (no upstream fetch —
    // the cheapest possible reachability answer, the gsfLane probe).
    if (shape === 'apps-script') {
        if (deps.solverPing !== undefined) {
            const result = await deps.solverPing(base);
            return {
                cacheState: null,
                latencyMs: result.latencyMs,
                maskedTarget: masked,
                overall: result.ok ? 'healthy' : 'degraded',
                plane: 'solver',
                status: result.status,
                statusDetail: result.ok ? 'ok' : 'network-error',
            };
        }
        // The default ping (a direct fetch against the exec URL).
        const result = await timedFetch(deps, `${base}${base.includes('?') ? '&' : '?'}ping=1`);
        return {
            cacheState: null,
            latencyMs: result.latencyMs,
            maskedTarget: masked,
            overall: result.ok ? 'healthy' : 'degraded',
            plane: 'solver',
            status: result.status,
            statusDetail: result.ok ? 'ok' : result.detail,
        };
    }

    // The worker lane: the capability echo (GET / — a static answer).
    const root = `${base.replace(/\/+$/, '')}/`;
    const result = await timedFetch(deps, root);
    return {
        cacheState: null,
        latencyMs: result.latencyMs,
        maskedTarget: masked,
        overall: result.ok ? 'healthy' : 'degraded',
        plane: 'solver',
        status: result.status,
        statusDetail: result.ok ? 'ok' : result.detail,
    };
}

// ---------------------------------------------------------------------------
// The copyable report (the P5-B surface)
// ---------------------------------------------------------------------------

function classifyDetail(plane: PlaneResult): string {
    switch (plane.statusDetail) {
        case 'auth-live':
            return 'auth-live';
        case 'channel-error':
            return 'channel-error';
        case 'http-error':
            return 'http-error';
        case 'network-error':
            return 'network-error';
        case 'not-configured':
            return 'not-configured';
        case 'ok':
            return plane.plane === 'cloud' ? 'reachable' : 'reachable';
        case 'timeout':
            return 'timeout';
    }
}

function planeLabel(plane: PlaneResult): string {
    switch (plane.plane) {
        case 'cloud':
            return 'cloud-sync-realtime';
        case 'edge':
            return 'edge-metadata-cache';
        case 'solver':
            return 'stream-solver';
    }
}

async function timedFetch(
    deps: ProbeDeps,
    url: string,
    mode: 'json' | 'status' = 'status',
): Promise<
    | (TimedOutcome & { cacheState?: EdgeCacheState; ok: true; status: number })
    | (TimedOutcome & { ok: false })
> {
    const now = deps.now ?? Date.now;
    const fetchImpl = deps.fetchImpl ?? fetch;
    const timeoutMs = deps.timeoutMs ?? PROBE_TIMEOUT_MS;
    const startedAt = now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const res = await fetchImpl(url, { method: 'GET', signal: controller.signal });
        const latencyMs = now() - startedAt;
        if (!res.ok) {
            return { detail: 'http-error', latencyMs, ok: false, status: res.status };
        }
        if (mode === 'json') {
            const body = (await res.json().catch(() => null)) as null | {
                cache?: { state?: string; uncached?: boolean };
            };
            const state = body?.cache?.state;
            const cacheState: EdgeCacheState =
                state === 'fill'
                    ? 'miss'
                    : state === 'fresh' || state === 'stale'
                      ? 'hit'
                      : body?.cache?.uncached === true
                        ? 'bypass'
                        : null;
            return { cacheState, detail: 'ok', latencyMs, ok: true, status: res.status };
        }
        return { detail: 'ok', latencyMs, ok: true, status: res.status };
    } catch {
        const latencyMs = now() - startedAt;
        const timedOut = latencyMs >= timeoutMs;
        return {
            detail: timedOut ? 'timeout' : 'network-error',
            latencyMs,
            ok: false,
            status: null,
        };
    } finally {
        clearTimeout(timer);
    }
}
