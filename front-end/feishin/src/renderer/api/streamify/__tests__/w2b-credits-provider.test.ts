/**
 * W2-B — getCredits on the unified W1 provider seam: the typed engine's
 * credits pipeline (edge serve / not-found / malformed→parse-error), the
 * W1-B adapter arm (null | YtmCredits), and the round-trip through the
 * W2-B meta bridge (the Liner Notes consumer). Network-free: the edge
 * tier is a scripted fake (the metadata-provider.test.ts idiom).
 */

import { describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('../playback/resolver/lib/ytSession', () => ({
    getYt: vi.fn(),
}));

// Delegating edge mock: every call runs the REAL edgeMetaCall against the
// caller's (injected) fetch — the typed-engine tests keep their scripted
// lanes — while the adapter test can override single answers for the
// production singleton (the singleton's engine resolves the mocked
// binding, so a Once override exercises the REAL adaptProvider arm).
vi.mock('../metadata/edge', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../metadata/edge')>();
    return { ...actual, edgeMetaCall: vi.fn(actual.edgeMetaCall) };
});

import type { CreditsPayload } from '../metadata/types';

import { createMetaSourceFromProvider } from '../meta-bridge';
import { type EdgeFetch, edgeMetaCall } from '../metadata/edge';
import {
    createMetadataProviderForTests,
    resetMetadataProviderForTests,
} from '../metadata/provider';
import { resetMetadataSnapshotForTests } from '../metadata/snapshot';

const CREDITS: CreditsPayload = {
    hl: 'en',
    kind: 'credits',
    roleGroups: [
        {
            credits: [
                { name: 'Performer One', subtitle: 'Main Artist' },
                { name: 'Performer Two', subtitle: 'Featured Artist' },
            ],
            title: 'Performed by',
        },
        { credits: [{ name: 'Writer One', subtitle: null }], title: 'Written by' },
        { credits: [{ name: 'Producer One', subtitle: 'Producer' }], title: 'Produced by' },
        { credits: [{ name: 'A Label', subtitle: null }], title: 'Source / Label' },
    ],
    videoId: 'credVid1',
};

function edgeServing(payload: unknown, missing = false): EdgeFetch {
    return (async () => ({
        header: () => null,
        json: () =>
            Promise.resolve(
                missing
                    ? { cache: { state: 'fill' }, missing: true }
                    : { cache: { state: 'fill' }, payload },
            ),
        status: 200,
    })) as EdgeFetch;
}

describe('w2b getCredits — the typed engine', () => {
    it('serves the normalized role groups from the edge lane', async () => {
        resetMetadataProviderForTests();
        resetMetadataSnapshotForTests();
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeServing(CREDITS),
        });
        const result = await provider.getCredits('credVid1', 'en');
        expect(result).toMatchObject({ kind: 'ok', source: 'edge' });
        if (result?.kind === 'ok') {
            const payload = result.payload as CreditsPayload;
            expect(payload.roleGroups.map((g) => g.title)).toEqual([
                'Performed by',
                'Written by',
                'Produced by',
                'Source / Label',
            ]);
        }
    });

    it('maps the edge miss to the honest not-found (zero retries)', async () => {
        resetMetadataProviderForTests();
        resetMetadataSnapshotForTests();
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeServing(null, true),
        });
        const result = await provider.getCredits('missingVid', 'en');
        expect(result).toMatchObject({ kind: 'not-found' });
    });

    it('degrades a malformed edge payload through the null fallback to parse-error (never a throw)', async () => {
        resetMetadataProviderForTests();
        resetMetadataSnapshotForTests();
        const provider = createMetadataProviderForTests({
            edgeFetch: edgeServing({ kind: 'credits', roleGroups: 'garbage' }),
        });
        const result = await provider.getCredits('badVid', 'en');
        // The edge ANSWERED but failed validation and the fallback lane is
        // the documented null — the honest terminal is parse-error (the
        // W1-A failure separation), which the adapter + bridge map to
        // their own honest nulls for the UI.
        expect(result).toMatchObject({ kind: 'parse-error' });
        const source = createMetaSourceFromProvider(provider as unknown as Record<string, unknown>);
        expect(await source.getCredits('badVid', 'en')).toBeNull();
    });
});

describe('w2b getCredits — the W1-B adapter + the bridge round-trip', () => {
    it('the production singleton serves credits through the REAL adapter into the bridge', async () => {
        resetMetadataProviderForTests();
        resetMetadataSnapshotForTests();
        // One scripted edge answer for the singleton's engine (the
        // metadataProvider module binding resolves the mocked edgeMetaCall).
        vi.mocked(edgeMetaCall).mockResolvedValueOnce({
            kind: 'ok',
            missing: false,
            payload: CREDITS,
            state: 'fill',
        });
        const providerModule = await import('../metadata/provider');
        try {
            const source = createMetaSourceFromProvider(
                providerModule.getMetadataProvider() as unknown as Record<string, unknown>,
            );
            const credits = await source.getCredits('credVid1', 'en');
            expect(credits).not.toBeNull();
            // The real adaptProvider arm: { roleGroups } off the typed payload.
            expect(credits?.roleGroups).toHaveLength(4);
            expect(credits?.roleGroups[0]?.credits[0]?.name).toBe('Performer One');
            expect(credits?.roleGroups[0]?.title).toBe('Performed by');
        } finally {
            providerModule.setMetadataProviderForTests(null);
            vi.mocked(edgeMetaCall).mockClear();
        }
    });

    it('a not-found edge answer unwraps to the honest null end to end', async () => {
        resetMetadataProviderForTests();
        resetMetadataSnapshotForTests();
        vi.mocked(edgeMetaCall).mockResolvedValueOnce({ kind: 'not-found' });
        const providerModule = await import('../metadata/provider');
        try {
            const source = createMetaSourceFromProvider(
                providerModule.getMetadataProvider() as unknown as Record<string, unknown>,
            );
            expect(await source.getCredits('goneVid', 'en')).toBeNull();
        } finally {
            providerModule.setMetadataProviderForTests(null);
            vi.mocked(edgeMetaCall).mockClear();
        }
    });
});
