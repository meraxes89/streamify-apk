import { defineConfig } from 'vitest/config';

/**
 * P2-A — the SABR corpus lane (CI only, scheduled daily; never the sandbox).
 *
 * Live network by design: this is the decision-data job. Every resolve is
 * instrumented (protocol + format); the SABR hit rate over the corpus is
 * the number that decides the custom-engine checkpoint. A SABR-heavy day
 * is DATA, not a failure — the only fatal case is zero resolves at all
 * (infra).
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['scripts/sabr-corpus.test.ts'],
    testTimeout: 240_000,
    hookTimeout: 120_000,
  },
});
