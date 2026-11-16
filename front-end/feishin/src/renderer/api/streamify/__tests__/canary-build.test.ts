/**
 * P5-C — the canary-alias build lock.
 *
 * The canary console source is VENDORED in-tree
 * (src/renderer/features/streamify-canary/) and bundled through the
 * '@streamify/canary' alias in web.vite.config.ts. The P5-A wiring
 * pointed at the front-end/canary workspace sibling — which exists only
 * in the monorepo, breaking the fork repo's build and the Docker image
 * (both ship front-end/feishin alone). The lock's purpose is unchanged:
 * this suite builds the canary console through the REAL
 * web.vite.config.ts (loadConfigFromFile — all plugins, one virtual
 * entry, write: false) so the alias always resolves inside the feishin
 * root, network-free, instead of failing at CI or image-build time.
 */

import { build, loadConfigFromFile } from 'vite';
import { describe, expect, it } from 'vitest';

describe('P5-C canary alias builds through the real web config (in-tree)', () => {
    it('resolves react + all compiler-emitted react subpaths', async () => {
        const loaded = await loadConfigFromFile(
            { command: 'build', mode: 'production' },
            new URL('../../../../../web.vite.config.ts', import.meta.url).pathname,
        );
        if (loaded === null) {
            throw new Error('web.vite.config.ts failed to load');
        }
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
