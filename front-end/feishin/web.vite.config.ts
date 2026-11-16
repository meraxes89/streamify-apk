import path from 'path';
import { defineConfig, normalizePath } from 'vite';
import { ViteEjsPlugin } from 'vite-plugin-ejs';
import { VitePWA } from 'vite-plugin-pwa';

import { kuromojiDictionaryPlugin } from './vite.kuromoji-plugin';
import { createReactPlugin } from './vite.react-plugin';

export default defineConfig({
    base: './',
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
        sourcemap: true,
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
        VitePWA({
            devOptions: {
                // The PWA will not be shown during development
                enabled: false,
            },
            filename: 'assets/sw.js',
            injectRegister: 'inline',
            manifest: {
                background_color: '#FFDCB5',
                display: 'standalone',
                icons: [
                    {
                        sizes: '32x32',
                        src: '32x32.png',
                        type: 'image/png',
                    },
                    {
                        sizes: '64x64',
                        src: '64x64.png',
                        type: 'image/png',
                    },
                    {
                        sizes: '128x128',
                        src: '128x128.png',
                        type: 'image/png',
                    },
                    {
                        sizes: '256x256',
                        src: '256x256.png',
                        type: 'image/png',
                    },
                    {
                        purpose: 'any',
                        sizes: '512x512',
                        src: '512x512.png',
                        type: 'image/png',
                    },
                    {
                        sizes: '1024x1024',
                        src: '1024x1024.png',
                        type: 'image/png',
                    },
                ],
                name: 'Feishin',
                orientation: 'portrait',
                screenshots: [
                    {
                        form_factor: 'wide',
                        label: 'Full screen player showing music player and lyrics',
                        sizes: '720x450',
                        src: 'preview_full_screen_player.webp',
                        type: 'image/webp',
                    },
                ],
                short_name: 'Feishin',
                start_url: '/',
                theme_color: '#1E003D',
            },
            manifestFilename: 'assets/manifest.webmanifest',
            outDir: path.resolve(__dirname, './out/web/'),
            registerType: 'autoUpdate',
            scope: '/assets/',
            workbox: {
                cleanupOutdatedCaches: true,
                clientsClaim: true,
                globIgnores: ['**/kuromoji/**'],
                maximumFileSizeToCacheInBytes: 1000000 * 10, // 10 MB
                skipWaiting: true,
            },
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
            // P5-A: the residential canary console (front-end/canary) mounts
            // inside the gated /debug route — resolve it straight from the
            // workspace source (no package link, no legacy canary.html).
            '@streamify/canary': path.resolve(__dirname, '../canary/src'),
            path: path.resolve(__dirname, './src/renderer/shims/path.ts'),
            // The canary source sits OUTSIDE the feishin root; rollup's
            // walk-up resolution cannot find react (or its subpath exports)
            // from there (CI has no front-end/node_modules — feishin's pnpm
            // tree is the only one). Pin every react entry point to
            // feishin's own copy: the exact files the package's exports map
            // points at, so the whole app keeps bundling one single react
            // (the same goal resolve.dedupe serves, but deterministic for
            // outside-the-root importers). ORDER MATTERS: aliases match by
            // exact-or-prefix, so the subpaths must stay above the bare
            // 'react' entry (which would otherwise rewrite them).
            'react/compiler-runtime': path.resolve(
                __dirname,
                './node_modules/react/compiler-runtime.js',
            ),
            'react/jsx-dev-runtime': path.resolve(
                __dirname,
                './node_modules/react/jsx-dev-runtime.js',
            ),
            'react/jsx-runtime': path.resolve(__dirname, './node_modules/react/jsx-runtime.js'),
            // eslint-disable-next-line perfectionist/sort-objects -- MUST stay last: prefix matching would rewrite the subpath specifiers above.
            react: path.resolve(__dirname, './node_modules/react/index.js'),
        },
    },
    root: path.resolve(__dirname, './src/renderer'),
});
