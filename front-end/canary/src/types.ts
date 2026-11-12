// types.ts — W0.1b residential canary data contracts.
//
// ProbeRow   = one line of the 10-client × 2-video matrix (tools/probe-clients.sh).
// RCondition = one R-C condition result (R1–R4, translated from tools/probe-search-resolver.sh).
// ProbeReport = everything a single canary run produces, posted to Supabase canary_results.

export interface ProbeRow {
  /** matrix client label, e.g. 'ANDROID' (verbatim from tools/probe-clients.sh) */
  client: string;
  /** videoId probed */
  video: string;
  /** innertube player endpoint the row hit (https://<host>/youtubei/v1/player) */
  endpoint: string;
  /** HTTP status as returned through the relay (0 = fetch failed / never reached) */
  status: number;
  /** status===200 && playability==='OK' */
  ok: boolean;
  /** playabilityStatus.status, default 'NO-RESPONSE' */
  playability: string;
  /** count of adaptiveFormats with itag 140 or 251 */
  audioFmts: number;
  /** any adaptiveFormats entry with itag===251 */
  fmt251: boolean;
  /** total adaptiveFormats count */
  totalFmts: number;
  /** count of formats carrying signatureCipher */
  ciphered: number;
  /** playabilityStatus.reason, '-' when absent */
  reason: string;
  /** relay round-trip latency in ms */
  latencyMs: number;
  /** UA-forwarding deviation note — constant on every row */
  notes: string;
}

export type RCondResult = 'pass' | 'fail' | 'unknown' | 'n/a';

export interface RCondition {
  id: 'R1' | 'R2' | 'R3' | 'R4';
  name: string;
  result: RCondResult;
  detail: string;
}

export interface ProbeReport {
  rows: ProbeRow[];
  rConditions: RCondition[];
  startedAt: string;
  finishedAt: string;
  userAgent: string;
  timezone: string;
  pageUrl: string;
  relayBase: string;
}
