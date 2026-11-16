import { defineConfig } from 'vitest/config';

/**
 * P2-A: the resolver test configuration.
 *
 * The unit lane (`npm test -w web`): network-free. Every fetch the ladder
 * could issue is intercepted — the fixture suite (resolve.fixtures.json)
 * locks the link solver's mapping behavior so any drift in resolve.ts
 * breaks a fixture, which is the P2-A guardrail ("the link solver must not
 * break"). Existing fixtures are never edited — only appended to.
 *
 * The corpus lane (`npm run test:corpus -w web`, CI-only, scheduled) uses
 * vitest.corpus.config.ts instead — live network by design (SABR hit rate).
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
