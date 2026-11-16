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
            // P5-A: the canary console panel test — same alias as
            // web.vite.config.ts (@streamify/canary → front-end/canary/src).
            '@streamify/canary': path.resolve(__dirname, '../canary/src'),
        },
        // P5-A: the canary console imports react from OUTSIDE the project
        // root (front-end/canary/src) — without dedupe, vite resolves a
        // phantom <root>/react module for those files and the externalized
        // CJS require chain dies ("Cannot find module
        // './cjs/react.development.js'"). One react, one react-dom.
        dedupe: ['react', 'react-dom'],
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts'],
    },
});
