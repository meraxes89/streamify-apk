/**
 * Streamify Web — W0.1b canary run posting (WS-W P0).
 *
 * Posts ONE row per canary run to the Supabase `canary_results` table
 * (front-end/canary/migrations/001_canary_results.sql — INSERT-only anon
 * policy, one-way anonymous telemetry). Deliberately degrades gracefully:
 * when VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY are unset (the PO sends
 * them later) the run stays local-only and the page says so. Never throws —
 * a telemetry failure must never eat the results the visitor just waited
 * ~20 probes for.
 */
import { createClient } from "@supabase/supabase-js";
import type { CanaryRunResult } from "./probeMatrix";

export interface PostOutcome {
  /** True when one row landed in canary_results. */
  posted: boolean;
  /** False when the env vars are unset (nothing was even attempted). */
  configured: boolean;
  /** Human-readable failure reason, null on success. */
  error: string | null;
}

const NOT_CONFIGURED =
  "VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY not configured — run recorded locally only (PO provides keys)";

export async function postCanaryRun(
  payload: CanaryRunResult,
): Promise<PostOutcome> {
  const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
  const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
  if (!url || !anonKey) {
    return { posted: false, configured: false, error: NOT_CONFIGURED };
  }
  try {
    const supabase = createClient(url, anonKey);
    const { error } = await supabase.from("canary_results").insert({
      results: payload.matrix,
      rc_conditions: payload.rc,
      user_agent: navigator.userAgent,
      page_origin: location.origin,
      notes: payload.note,
    });
    if (error) {
      return { posted: false, configured: true, error: error.message };
    }
    return { posted: true, configured: true, error: null };
  } catch (err) {
    return {
      posted: false,
      configured: true,
      error: err instanceof Error ? err.message : String(err),
    };
  }
}
