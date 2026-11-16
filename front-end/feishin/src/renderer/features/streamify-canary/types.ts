// types.ts — W0.1b residential canary data contracts.
//
// ProbeRow   = one line of the 10-client × 2-video matrix (tools/probe-clients.sh).
// RCondition = one R-C condition result (R1–R4, translated from tools/probe-search-resolver.sh).
// ProbeReport = everything a single canary run produces, posted to Supabase canary_results.

export interface ProbeReport {
    finishedAt: string;
    pageUrl: string;
    rConditions: RCondition[];
    relayBase: string;
    rows: ProbeRow[];
    startedAt: string;
    timezone: string;
    userAgent: string;
}

export interface ProbeRow {
    /** count of adaptiveFormats with itag 140 or 251 */
    audioFmts: number;
    /** count of formats carrying signatureCipher */
    ciphered: number;
    /** matrix client label, e.g. 'ANDROID' (verbatim from tools/probe-clients.sh) */
    client: string;
    /** innertube player endpoint the row hit (https://<host>/youtubei/v1/player) */
    endpoint: string;
    /** any adaptiveFormats entry with itag===251 */
    fmt251: boolean;
    /** relay round-trip latency in ms */
    latencyMs: number;
    /** UA-forwarding deviation note — constant on every row */
    notes: string;
    /** status===200 && playability==='OK' */
    ok: boolean;
    /** playabilityStatus.status, default 'NO-RESPONSE' */
    playability: string;
    /** playabilityStatus.reason, '-' when absent */
    reason: string;
    /** HTTP status as returned through the relay (0 = fetch failed / never reached) */
    status: number;
    /** total adaptiveFormats count */
    totalFmts: number;
    /** videoId probed */
    video: string;
}

export interface RCondition {
    detail: string;
    id: 'R1' | 'R2' | 'R3' | 'R4';
    name: string;
    result: RCondResult;
}

export type RCondResult = 'fail' | 'n/a' | 'pass' | 'unknown';
