/**
 * features/streamify-debug/hooks/debug-gate-ruling.ts — P4-A: the /debug
 * access gate's pure ruling (no React, no stores — directly unit-tested).
 *
 * THE RULES (decisions/p4/P4A-downloads-wrapped-debug.md):
 *   1. import.meta.env.DEV — vite dev mode always passes;
 *   2. the persisted developer-mode grant (opt-in from a previously
 *      gated session — the route's "remember" switch);
 *   3. ?debug=1 — allowed ONLY for an authenticated ADMIN session
 *      (session.isAdmin; anonymous or non-admin → denied).
 *
 * Everything else → clean redirect to / — standard users never see raw
 * internals (resolver rungs, queue depths, CRDT state).
 */

export interface DebugGateInputs {
    dev: boolean;
    developerMode: boolean;
    isAdmin: boolean | null;
    urlFlag: boolean;
}

/** The pure ruling (unit-tested directly). */
export function debugGateAllowed(inputs: DebugGateInputs): boolean {
    if (inputs.dev) return true;
    if (inputs.developerMode) return true;
    return inputs.urlFlag && inputs.isAdmin === true;
}
