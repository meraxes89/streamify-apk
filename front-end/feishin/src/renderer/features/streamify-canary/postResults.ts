// postResults.ts — Supabase persistence for the canary report.
//
// This module MAY use import.meta.env (it is always bundled by the consuming Vite
// build). The reads go through a defensive cast because this framework-free package
// cannot assume ambient vite/client types are declared in the consuming tsconfig —
// at runtime Vite always injects import.meta.env, and with vite/client types loaded
// the cast is still transparent.
//
// DB shape: migrations/001_canary_results.sql (public.canary_results) — anon INSERT
// only, no anon SELECT (service role reads). buildRows flattens a ProbeReport into
// 20 matrix rows + 4 rcondition rows.

import type { ProbeReport } from './types';

/** exactly the columns of public.canary_results (minus id/created_at) */
export interface CanaryResultRow {
    audio_fmts: null | number;
    ciphered: null | number;
    client: string;
    endpoint: null | string;
    fmt_251: boolean | null;
    kind: 'matrix' | 'rcondition';
    latency_ms: null | number;
    notes: null | string;
    ok: boolean;
    page_url: string;
    r_detail: null | string;
    r_id: null | string;
    r_name: null | string;
    r_result: null | string;
    reason: null | string;
    status: null | number;
    timezone: string;
    total_fmts: null | number;
    user_agent: string;
    video: null | string;
}

export interface PostResultsOutcome {
    error?: string;
    posted: boolean;
    rowCount: number;
}

export interface SupaEnv {
    anonKey: string;
    url: string;
}

interface CanaryEnvShim {
    DEV?: boolean;
    VITE_RELAY_BASE_URL?: string;
    VITE_SUPABASE_ANON_KEY?: string;
    VITE_SUPABASE_URL?: string;
}

/** Flatten a report into DB rows: one per matrix ProbeRow, one per RCondition. */
export function buildRows(report: ProbeReport): CanaryResultRow[] {
    const rows: CanaryResultRow[] = [];

    for (const r of report.rows) {
        rows.push({
            audio_fmts: r.audioFmts,
            ciphered: r.ciphered,
            client: r.client,
            endpoint: r.endpoint,
            fmt_251: r.fmt251,
            kind: 'matrix',
            latency_ms: r.latencyMs,
            notes: r.notes,
            ok: r.ok,
            page_url: report.pageUrl,
            r_detail: null,
            r_id: null,
            r_name: null,
            r_result: null,
            reason: r.reason,
            status: r.status,
            timezone: report.timezone,
            total_fmts: r.totalFmts,
            user_agent: report.userAgent,
            video: r.video,
        });
    }

    for (const c of report.rConditions) {
        rows.push({
            audio_fmts: null,
            ciphered: null,
            client: 'R-C',
            endpoint: null,
            fmt_251: null,
            kind: 'rcondition',
            latency_ms: null,
            notes: null,
            ok: c.result === 'pass',
            page_url: report.pageUrl,
            r_detail: c.detail,
            r_id: c.id,
            r_name: c.name,
            r_result: c.result,
            reason: null,
            status: null,
            timezone: report.timezone,
            total_fmts: null,
            user_agent: report.userAgent,
            video: null,
        });
    }

    return rows;
}

export function canaryEnv(): CanaryEnvShim {
    return ((import.meta as any).env ?? {}) as CanaryEnvShim;
}

/**
 * Post a report to Supabase (`${url}/rest/v1/canary_results`,
 * `Prefer: return=minimal`). `override` (P5-A: the feishin /debug mount)
 * supplies the host app's live Supabase config — the active session's URL
 * + anon key — and wins over the build-time env reads.
 */
export async function postResults(
    report: ProbeReport,
    override?: SupaEnv,
): Promise<PostResultsOutcome> {
    const env = override ?? supabaseEnv();
    if (!env) {
        return {
            error: 'Supabase not configured (VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY)',
            posted: false,
            rowCount: 0,
        };
    }
    const rows = buildRows(report);
    // Same trailing-slash normalization supabaseEnv() applies — the override
    // comes from the host session and may carry one.
    const url = env.url.replace(/\/+$/, '');
    try {
        const res = await fetch(`${url}/rest/v1/canary_results`, {
            body: JSON.stringify(rows),
            headers: {
                apikey: env.anonKey,
                Authorization: `Bearer ${env.anonKey}`,
                'Content-Type': 'application/json',
                Prefer: 'return=minimal',
            },
            method: 'POST',
        });
        if (!res.ok) {
            const text = await res.text().catch(() => '');
            return {
                error: `HTTP ${res.status} ${text.slice(0, 300)}`,
                posted: false,
                rowCount: 0,
            };
        }
        return { posted: true, rowCount: rows.length };
    } catch (e) {
        return {
            error: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
            posted: false,
            rowCount: 0,
        };
    }
}

export function supabaseEnv(): null | SupaEnv {
    const url = canaryEnv().VITE_SUPABASE_URL;
    const anonKey = canaryEnv().VITE_SUPABASE_ANON_KEY;
    if (url && anonKey) {
        return { anonKey, url: url.replace(/\/+$/, '') };
    }
    return null;
}
