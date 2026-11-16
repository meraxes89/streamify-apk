/**
 * P5-A — the SABR corpus runner integrity lock.
 *
 * The daily corpus job (resolver-canary.yml → `pnpm run test:corpus`) moved
 * out of the archived front-end/web tree into this fork, and now exercises
 * the CANONICAL resolver tree. This network-free suite locks the wiring so
 * the daily job can never silently drift back to the archive or lose its
 * runner:
 *
 *   1. the runner exists and imports getStreamUrl + recentResolveLog from
 *      the canonical path (…/playback/resolver/audio/get-stream-url) —
 *      never from legacy/front-end-web;
 *   2. the canonical module graph loads under plain node (the exact
 *      environment the corpus lane runs in) and exports the contract the
 *      runner consumes;
 *   3. the corpus lane is ISOLATED from the default test lane —
 *      vitest.corpus.config.ts includes only the runner, and package.json's
 *      test:corpus points at that config, so `pnpm test` can never fire the
 *      live-network corpus locally or in CI.
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const FEISHIN_ROOT = path.resolve(__dirname, '../../../../..');
const RUNNER = path.join(FEISHIN_ROOT, 'scripts/sabr-corpus.test.ts');
const CORPUS_CONFIG = path.join(FEISHIN_ROOT, 'vitest.corpus.config.ts');
/** Relative to THIS test file (dynamic import resolves from the importer). */
const CANONICAL_REL = '../playback/resolver/audio/get-stream-url';

describe('P5-A sabr-corpus runner (canonical resolver)', () => {
    it('exists and imports the canonical get-stream-url — never the archive', () => {
        const source = readFileSync(RUNNER, 'utf-8');
        expect(source).toContain(
            "from '../src/renderer/api/streamify/playback/resolver/audio/get-stream-url'",
        );
        expect(source).toContain('getStreamUrl');
        expect(source).toContain('recentResolveLog');
        // The archive is read-only history: nothing may IMPORT from it
        // (prose mentions of the archive in comments are fine).
        expect(source).not.toMatch(/from\s+['"][^'"]*legacy/);
        expect(source).not.toMatch(/import\(\s*['"][^'"]*legacy/);
        expect(source).not.toMatch(/from\s+['"]\.\.\/\.\.\/web/);
    });

    it('loads the canonical resolver module graph under node (the corpus env)', async () => {
        // Same environment + same import shape the corpus runner uses; a
        // broken import (an alias, a DOM-only top-level, a missing dep)
        // fails here network-free instead of at 06:30 UTC in CI.
        const mod = await import(CANONICAL_REL);
        expect(typeof mod.getStreamUrl).toBe('function');
        expect(typeof mod.recentResolveLog).toBe('function');
        // The instrumentation ring the report reads (starts empty, no logs).
        expect(Array.isArray(mod.recentResolveLog())).toBe(true);
    });

    it('keeps the live corpus lane out of the default test lane', () => {
        const config = readFileSync(CORPUS_CONFIG, 'utf-8');
        expect(config).toContain("include: ['scripts/sabr-corpus.test.ts']");

        const pkg = JSON.parse(
            readFileSync(path.join(FEISHIN_ROOT, 'package.json'), 'utf-8'),
        ) as { scripts?: Record<string, string> };
        expect(pkg.scripts?.['test:corpus']).toBe(
            'vitest run --config vitest.corpus.config.ts',
        );
        // The default lane must NOT reach into scripts/ (live network).
        expect(pkg.scripts?.test).toBe('vitest run');
    });
});
