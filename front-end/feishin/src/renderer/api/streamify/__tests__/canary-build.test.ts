/**
 * P5-A — the canary-alias build lock.
 *
 * The canary console source lives OUTSIDE the feishin root
 * (front-end/canary/src) and is bundled through the '@streamify/canary'
 * alias in web.vite.config.ts. Rollup's walk-up resolution cannot find
 * react or its subpath exports from there (the CI runner has no
 * front-end/node_modules — feishin's pnpm tree is the only one), which
 * broke the Feishin CI web build twice (react/jsx-runtime, then
 * react/compiler-runtime — both compiler-emitted imports). The config now
 * pins every react entry point; this suite builds the canary console
 * through the REAL web.vite.config.ts (loadConfigFromFile — all plugins,
 * one virtual entry, write: false) so the resolution contract is locked
 * network-free instead of failing at CI time.
 */

import { build, loadConfigFromFile } from 'vite';
import { describe, expect, it } from 'vitest';

describe('P5-A canary alias builds through the real web config', () => {
    it('resolves react + all compiler-emitted react subpaths', async () => {
        const loaded = await loadConfigFromFile(
            { command: 'build', mode: 'production' },
            new URL('../../../../../web.vite.config.ts', import.meta.url).pathname,
        );
        expect(loaded).not.toBeNull();
        const config = loaded.config;
        config.build = {
            ...config.build,
            emptyOutDir: true,
            outDir: '/tmp/p5a-canary-build-lock',
            rollupOptions: { input: { index: '/virtual-entry.tsx' } },
            write: false,
        };
        config.plugins = [
            ...(config.plugins ?? []),
            {
                load(id) {
                    if (id !== '/virtual-entry.tsx') return null;
                    return `import CanaryConsole from '@streamify/canary/ui/CanaryConsole';\nexport default CanaryConsole;`;
                },
                name: 'p5a-virtual-entry',
                resolveId(id) {
                    return id === '/virtual-entry.tsx' ? id : null;
                },
            },
        ];
        await build(config);
        expect(true).toBe(true);
    });
});
