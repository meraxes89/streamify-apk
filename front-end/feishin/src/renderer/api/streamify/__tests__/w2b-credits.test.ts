/**
 * W2-B — Song Credits & Liner Notes: the meta bridge's credits lane and
 * the Liner Notes drawer state, network-free.
 *
 * WHAT IS PINNED HERE:
 *   - createMetaSourceFromProvider.getCredits over BOTH W1 shapes: the
 *     MetadataResult envelope (W1-A) and the direct null|payload form
 *     (W1-B) — the rebase-order bridge;
 *   - the honest empty: not-found / rate-limited / parse-error envelopes,
 *     a provider without getCredits (the pre-W2B-provider state), and a
 *     provider module that does not exist yet (null source) all resolve
 *     null — never fabricated role groups;
 *   - Liner Notes grouping: the CreditsPayload normalizer keeps only
 *     structurally valid role groups (named credits, titled sections),
 *     drops empty/malformed groups, and rejects payloads without any
 *     survive-able group;
 *   - the drawer store's open/close lifecycle (the context-menu action's
 *     seam).
 *
 * The 7-day Edge KV TTL itself is enforced relay-side (W1-A's
 * ytm-meta.test.ts pins ttlForEndpoint('credits') === 7 * DAY); this lane
 * pins the consumer contract that inherits it.
 */

import { describe, expect, it } from 'vitest';

import { createMetaSourceFromProvider } from '../meta-bridge';

import {
    resetStreamifyCreditsStoreForTests,
    useStreamifyCreditsStoreBase,
} from '/@/renderer/features/streamify/credits/credits-store';

/** The canonical CreditsPayload (W1-A's relay schema). */
const CANONICAL_CREDITS = {
    hl: 'en',
    kind: 'credits',
    roleGroups: [
        {
            credits: [
                { name: 'Kendrick Lamar', subtitle: 'Main Artist' },
                { name: 'SZA', subtitle: 'Featured Artist' },
            ],
            title: 'Performed by',
        },
        {
            credits: [{ name: 'Kendrick Lamar', subtitle: null }],
            title: 'Written by',
        },
        {
            credits: [
                { name: 'Jack Antonoff', subtitle: 'Producer' },
                { name: 'Sounwave', subtitle: 'Producer' },
            ],
            title: 'Produced by',
        },
        {
            credits: [{ name: 'Aftermath Records', subtitle: null }],
            title: 'Source / Label',
        },
    ],
    videoId: 'dQw4w9WgXcQ',
};

describe('w2b meta bridge — the credits lane', () => {
    it('unwraps the W1-A MetadataResult envelope into the flat DTO', async () => {
        const provider = {
            getCredits: async () => ({ kind: 'ok', payload: CANONICAL_CREDITS, source: 'edge' }),
        };
        const source = createMetaSourceFromProvider(provider);
        const credits = await source.getCredits('dQw4w9WgXcQ', 'en');
        expect(credits).not.toBeNull();
        expect(credits?.videoId).toBe('dQw4w9WgXcQ');
        expect(credits?.hl).toBe('en');
        expect(credits?.roleGroups.map((g) => g.title)).toEqual([
            'Performed by',
            'Written by',
            'Produced by',
            'Source / Label',
        ]);
    });

    it('consumes the W1-B direct payload form (null | payload)', async () => {
        const provider = { getCredits: async () => CANONICAL_CREDITS };
        const source = createMetaSourceFromProvider(provider);
        const credits = await source.getCredits('dQw4w9WgXcQ');
        expect(credits?.roleGroups).toHaveLength(4);
    });

    it('resolves the honest null on every failure envelope', async () => {
        for (const payload of [
            { kind: 'not-found' },
            { detail: '429 after backoff', kind: 'rate-limited' },
            { detail: 'schema mismatch', kind: 'parse-error' },
            null,
        ]) {
            const provider = { getCredits: async () => payload };
            const source = createMetaSourceFromProvider(provider);
            expect(await source.getCredits('any')).toBeNull();
        }
    });

    it('resolves null while the provider predates getCredits (the pre-rebase state)', async () => {
        const provider = { getUpNext: async () => null };
        const source = createMetaSourceFromProvider(provider);
        expect(await source.getCredits('any')).toBeNull();
    });

    it('resolves null when the call throws (network)', async () => {
        const provider = {
            getCredits: async () => {
                throw new Error('network down');
            },
        };
        const source = createMetaSourceFromProvider(provider);
        expect(await source.getCredits('any')).toBeNull();
    });
});

describe('w2b Liner Notes grouping — the payload normalizer', () => {
    it('drops credits entries without a name and groups that end up empty', async () => {
        const payload = {
            hl: 'en',
            kind: 'credits',
            roleGroups: [
                {
                    credits: [
                        { name: 'Real Person', subtitle: 'Producer' },
                        { subtitle: 'no name' },
                        42,
                        null,
                    ],
                    title: 'Produced by',
                },
                { credits: [{ subtitle: 'only subtitle' }], title: 'Written by' },
                { credits: [], title: 'Empty Section' },
            ],
            videoId: 'v1',
        };
        const source = createMetaSourceFromProvider({ getCredits: async () => payload });
        const credits = await source.getCredits('v1');
        expect(credits).not.toBeNull();
        expect(credits?.roleGroups).toHaveLength(1);
        expect(credits?.roleGroups[0]?.title).toBe('Produced by');
        expect(credits?.roleGroups[0]?.credits).toEqual([
            { name: 'Real Person', subtitle: 'Producer' },
        ]);
    });

    it('rejects payloads with no survivable role group (the honest empty)', async () => {
        for (const payload of [
            { hl: 'en', kind: 'credits', roleGroups: [], videoId: 'v1' },
            { hl: 'en', kind: 'credits', videoId: 'v1' },
            { hl: 'en', kind: 'credits', roleGroups: 'nope', videoId: 'v1' },
            null,
            42,
        ]) {
            const source = createMetaSourceFromProvider({ getCredits: async () => payload });
            expect(await source.getCredits('v1')).toBeNull();
        }
    });

    it('keeps untitled groups (YTM occasionally ships anonymous sections)', async () => {
        const payload = {
            hl: 'en',
            kind: 'credits',
            roleGroups: [{ credits: [{ name: 'Someone', subtitle: null }], title: '' }],
            videoId: 'v1',
        };
        const source = createMetaSourceFromProvider({ getCredits: async () => payload });
        const credits = await source.getCredits('v1');
        expect(credits?.roleGroups).toHaveLength(1);
        expect(credits?.roleGroups[0]?.title).toBe('');
    });

    it('defaults hl to en when the payload omits it', async () => {
        const payload = {
            kind: 'credits',
            roleGroups: [{ credits: [{ name: 'A', subtitle: null }], title: 'T' }],
            videoId: 'v1',
        };
        const source = createMetaSourceFromProvider({ getCredits: async () => payload });
        const credits = await source.getCredits('v1');
        expect(credits?.hl).toBe('en');
    });
});

describe('w2b Liner Notes drawer store', () => {
    it('opens with the song facts and closes cleanly', () => {
        resetStreamifyCreditsStoreForTests();
        expect(useStreamifyCreditsStoreBase.getState().request).toBeNull();

        useStreamifyCreditsStoreBase.getState().open({
            artist: 'Kendrick Lamar',
            title: 'Luther',
            videoId: 'dQw4w9WgXcQ',
        });
        expect(useStreamifyCreditsStoreBase.getState().request).toEqual({
            artist: 'Kendrick Lamar',
            title: 'Luther',
            videoId: 'dQw4w9WgXcQ',
        });

        useStreamifyCreditsStoreBase.getState().close();
        expect(useStreamifyCreditsStoreBase.getState().request).toBeNull();
        resetStreamifyCreditsStoreForTests();
    });
});
