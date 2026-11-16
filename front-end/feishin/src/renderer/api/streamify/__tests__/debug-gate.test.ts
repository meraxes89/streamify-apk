/**
 * P4-A — the /debug access gate (pure ruling, directly tested).
 *
 * Standard users never see raw internals: no DEV flag, no persisted
 * developer-mode grant, and ?debug=1 without an authenticated admin
 * session → denied (clean redirect to / in the route).
 */

import { describe, expect, it } from 'vitest';

import {
    debugGateAllowed,
    type DebugGateInputs,
} from '../../../features/streamify-debug/hooks/debug-gate-ruling';

const base: DebugGateInputs = {
    dev: false,
    developerMode: false,
    isAdmin: null,
    urlFlag: false,
};

describe('P4-A /debug gate', () => {
    it('blocks default users (no flags at all)', () => {
        expect(debugGateAllowed(base)).toBe(false);
    });

    it('blocks ?debug=1 for anonymous users', () => {
        expect(debugGateAllowed({ ...base, isAdmin: null, urlFlag: true })).toBe(false);
    });

    it('blocks ?debug=1 for signed-in non-admins', () => {
        expect(debugGateAllowed({ ...base, isAdmin: false, urlFlag: true })).toBe(false);
    });

    it('allows ?debug=1 for authenticated admins', () => {
        expect(debugGateAllowed({ ...base, isAdmin: true, urlFlag: true })).toBe(true);
    });

    it('allows the persisted developer-mode grant', () => {
        expect(debugGateAllowed({ ...base, developerMode: true })).toBe(true);
    });

    it('allows vite dev mode unconditionally', () => {
        expect(debugGateAllowed({ ...base, dev: true })).toBe(true);
        expect(
            debugGateAllowed({ dev: true, developerMode: false, isAdmin: null, urlFlag: false }),
        ).toBe(true);
    });
});
