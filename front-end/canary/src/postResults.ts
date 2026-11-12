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

export interface SupaEnv {
  url: string;
  anonKey: string;
}

/** exactly the columns of public.canary_results (minus id/created_at) */
export interface CanaryResultRow {
  kind: 'matrix' | 'rcondition';
  client: string;
  video: string | null;
  endpoint: string | null;
  status: number | null;
  ok: boolean;
  audio_fmts: number | null;
  fmt_251: boolean | null;
  total_fmts: number | null;
  ciphered: number | null;
  reason: string | null;
  latency_ms: number | null;
  notes: string | null;
  r_id: string | null;
  r_name: string | null;
  r_result: string | null;
  r_detail: string | null;
  user_agent: string;
  timezone: string;
  page_url: string;
}

interface CanaryEnvShim {
  VITE_SUPABASE_URL?: string;
  VITE_SUPABASE_ANON_KEY?: string;
  VITE_RELAY_BASE_URL?: string;
  DEV?: boolean;
}

export function canaryEnv(): CanaryEnvShim {
  return ((import.meta as any).env ?? {}) as CanaryEnvShim;
}

export function supabaseEnv(): SupaEnv | null {
  const url = canaryEnv().VITE_SUPABASE_URL;
  const anonKey = canaryEnv().VITE_SUPABASE_ANON_KEY;
  if (url && anonKey) {
    return { url: url.replace(/\/+$/, ''), anonKey };
  }
  return null;
}

/** Flatten a report into DB rows: one per matrix ProbeRow, one per RCondition. */
export function buildRows(report: ProbeReport): CanaryResultRow[] {
  const rows: CanaryResultRow[] = [];

  for (const r of report.rows) {
    rows.push({
      kind: 'matrix',
      client: r.client,
      video: r.video,
      endpoint: r.endpoint,
      status: r.status,
      ok: r.ok,
      audio_fmts: r.audioFmts,
      fmt_251: r.fmt251,
      total_fmts: r.totalFmts,
      ciphered: r.ciphered,
      reason: r.reason,
      latency_ms: r.latencyMs,
      notes: r.notes,
      r_id: null,
      r_name: null,
      r_result: null,
      r_detail: null,
      user_agent: report.userAgent,
      timezone: report.timezone,
      page_url: report.pageUrl
    });
  }

  for (const c of report.rConditions) {
    rows.push({
      kind: 'rcondition',
      client: 'R-C',
      video: null,
      endpoint: null,
      status: null,
      ok: c.result === 'pass',
      audio_fmts: null,
      fmt_251: null,
      total_fmts: null,
      ciphered: null,
      reason: null,
      latency_ms: null,
      notes: null,
      r_id: c.id,
      r_name: c.name,
      r_result: c.result,
      r_detail: c.detail,
      user_agent: report.userAgent,
      timezone: report.timezone,
      page_url: report.pageUrl
    });
  }

  return rows;
}

export interface PostResultsOutcome {
  posted: boolean;
  rowCount: number;
  error?: string;
}

/** POST the flattened rows to ${url}/rest/v1/canary_results (Prefer: return=minimal). */
export async function postResults(report: ProbeReport): Promise<PostResultsOutcome> {
  const env = supabaseEnv();
  if (!env) {
    return {
      posted: false,
      rowCount: 0,
      error: 'Supabase not configured (VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY)'
    };
  }
  const rows = buildRows(report);
  try {
    const res = await fetch(`${env.url}/rest/v1/canary_results`, {
      method: 'POST',
      headers: {
        apikey: env.anonKey,
        Authorization: `Bearer ${env.anonKey}`,
        'Content-Type': 'application/json',
        Prefer: 'return=minimal'
      },
      body: JSON.stringify(rows)
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      return { posted: false, rowCount: 0, error: `HTTP ${res.status} ${text.slice(0, 300)}` };
    }
    return { posted: true, rowCount: rows.length };
  } catch (e) {
    return {
      posted: false,
      rowCount: 0,
      error: e instanceof Error ? `${e.name}: ${e.message}` : String(e)
    };
  }
}
