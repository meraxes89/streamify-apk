import path from 'path';
import { defineConfig, normalizePath } from 'vite';
import { ViteEjsPlugin } from 'vite-plugin-ejs';

import { kuromojiDictionaryPlugin } from './vite.kuromoji-plugin';
import { createReactPlugin } from './vite.react-plugin';

export default defineConfig({
    // GitHub Pages serves the fork under /streamify-apk/ (project site);
    // Docker serves it at /. The CLI --base flag wins over this value in
    // CI, so the default stays relative for Docker/electron parity.
    base: process.env.VITE_BASE_PATH || './',
    build: {
        emptyOutDir: true,
        outDir: path.resolve(__dirname, './out/web'),
        rollupOptions: {
            input: {
                '32x32': normalizePath(path.resolve(__dirname, './assets/icons/32x32.png')),
                '64x64': normalizePath(path.resolve(__dirname, './assets/icons/64x64.png')),
                '128x128': normalizePath(path.resolve(__dirname, './assets/icons/128x128.png')),
                '256x256': normalizePath(path.resolve(__dirname, './assets/icons/256x256.png')),
                '512x512': normalizePath(path.resolve(__dirname, './assets/icons/512x512.png')),
                '1024x1024': normalizePath(path.resolve(__dirname, './assets/icons/1024x1024.png')),
                favicon: normalizePath(path.resolve(__dirname, './assets/icons/favicon.ico')),
                index: normalizePath(path.resolve(__dirname, './src/renderer/index.html')),
                preview_full_screen_player: normalizePath(
                    path.resolve(__dirname, './media/preview_full_screen_player.webp'),
                ),
            },
            output: {
                assetFileNames: (assetInfo) => {
                    const stableNames = [
                        '32x32.png',
                        '64x64.png',
                        '128x128.png',
                        '256x256.png',
                        '512x512.png',
                        '1024x1024.png',
                        'favicon.ico',
                        'preview_full_screen_player.webp',
                    ];

                    if (assetInfo.names.length === 1 && stableNames.includes(assetInfo.names[0])) {
                        return 'assets/[name][extname]';
                    }

                    return 'assets/[name]-[hash][extname]';
                },
                sourcemapExcludeSources: false,
            },
        },
        sourcemap: false,
    },
    css: {
        modules: {
            generateScopedName: 'fs-[name]-[local]',
            localsConvention: 'camelCase',
        },
    },
    optimizeDeps: {
        exclude: [
            '@atlaskit/pragmatic-drag-and-drop',
            '@atlaskit/pragmatic-drag-and-drop-auto-scroll',
            '@atlaskit/pragmatic-drag-and-drop-hitbox',
            '@tanstack/react-query-persist-client',
            'idb-keyval',
        ],
    },
    plugins: [
        createReactPlugin(),
        kuromojiDictionaryPlugin(),
        ViteEjsPlugin({
            root: normalizePath(path.resolve(__dirname, './src/renderer')),
            web: true,
        }),
    ],
    resolve: {
        alias: {
            '/@/i18n': path.resolve(__dirname, './src/i18n'),
            '/@/lyrics-conversion-api': path.resolve(
                __dirname,
                './src/main/features/core/lyrics/furigana.ts',
            ),
            '/@/remote': path.resolve(__dirname, './src/remote'),
            '/@/renderer': path.resolve(__dirname, './src/renderer'),
            '/@/shared': path.resolve(__dirname, './src/shared'),
            // P5-C: the residential canary console is VENDORED in-tree
            // (src/renderer/features/streamify-canary/) — it must resolve
            // from inside the feishin root so the fork repo and the Docker
            // build context (both of which ship feishin alone, without the
            // monorepo's front-end/canary sibling) see the same source.
            '@streamify/canary': path.resolve(
                __dirname,
                './src/renderer/features/streamify-canary',
            ),
            path: path.resolve(__dirname, './src/renderer/shims/path.ts'),
            // P5-C: the canary source is in-tree now, so the outside-the-root
            // react pinning entries below are no longer needed — vite's
            // ordinary resolution serves every importer from feishin's own
            // node_modules. Keep the plain react alias for determinism.
        },
    },
    root: path.resolve(__dirname, './src/renderer'),
});
