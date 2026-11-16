import path from 'node:path';
import { defineConfig } from 'vitest/config';

/**
 * P2-A (app stream): the fork's first test lane.
 *
 * Network-free by construction: the resolver mirror is vi.mock'ed in the
 * tests (the walk/stale/seek logic is what is locked here — the resolver
 * side is exercised live by the daily SABR corpus lane,
 * vitest.corpus.config.ts). The '/@/' aliases mirror web.vite.config.ts so
 * store/logger imports resolve.
 */
export default defineConfig({
    resolve: {
        alias: {
            '/@/i18n': path.resolve(__dirname, './src/i18n'),
            '/@/remote': path.resolve(__dirname, './src/remote'),
            '/@/renderer': path.resolve(__dirname, './src/renderer'),
            '/@/shared': path.resolve(__dirname, './src/shared'),
            // P5-C: the canary console is VENDORED in-tree
            // (src/renderer/features/streamify-canary/) — same source the
            // web build bundles; the outside-the-root react pinning is gone
            // with the sibling dependency.
            '@streamify/canary': path.resolve(
                __dirname,
                './src/renderer/features/streamify-canary',
            ),
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts'],
    },
});
