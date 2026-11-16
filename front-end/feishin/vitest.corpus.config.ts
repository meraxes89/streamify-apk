import path from 'node:path';
import { defineConfig } from 'vitest/config';

/**
 * P5-A — the SABR corpus lane (CI only, scheduled daily; never the sandbox,
 * never `pnpm test`).
 *
 * Live network by design: this is the decision-data job. It runs the
 * de-facto W1.2 corpus through the CANONICAL resolver tree
 * (src/renderer/api/streamify/playback/resolver/ — the single source of
 * truth since the front-end/web archive) against the live pipeline.
 * Mirrors the retired front-end/web corpus lane one-for-one; the runner
 * lives in scripts/ so the default vitest lane (src/** only) can never
 * pick it up.
 */
export default defineConfig({
    resolve: {
        alias: {
            '/@/i18n': path.resolve(__dirname, './src/i18n'),
            '/@/remote': path.resolve(__dirname, './src/remote'),
            '/@/renderer': path.resolve(__dirname, './src/renderer'),
            '/@/shared': path.resolve(__dirname, './src/shared'),
        },
    },
    test: {
        environment: 'node',
        include: ['scripts/sabr-corpus.test.ts'],
        testTimeout: 240_000,
        hookTimeout: 120_000,
    },
});
