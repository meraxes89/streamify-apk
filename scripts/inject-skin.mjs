#!/usr/bin/env node
/**
 * scripts/inject-skin.mjs — wire the monorepo-owned mobile skin into a built
 * Feishin web bundle WITHOUT touching a single file under front-end/feishin/.
 *
 * WHY THIS EXISTS
 *   front-end/feishin/ is vendored from meraxes89/streamify-feishin and merged
 *   from jeffvli/feishin release tags. Any edit inside that tree becomes a
 *   conflict surface recorded in PATCHES.md. The skin lives in front-end/skin/
 *   instead; this script is the only bridge, and it edits GENERATED output
 *   (settings.js + a copied skin/ directory) that is never committed upstream.
 *
 * USAGE
 *   node scripts/inject-skin.mjs <built-web-dir> [--repo-root <repo root>]
 *
 *   <built-web-dir>  e.g. front-end/feishin/out/web, or server/public locally.
 *   --repo-root      checkout that contains front-end/skin/ and this script.
 *                    Defaults to process.cwd(). Needed in preview-pages.yml,
 *                    which checks out main into prod/ and this branch into
 *                    preview/ — invoke `preview/scripts/inject-skin.mjs` with
 *                    --repo-root preview to skin both fused trees.
 *
 * WHAT IT DOES (idempotent — safe to run twice)
 *   1. Replaces the marker-delimited loader block at the end of settings.js
 *      with the current contents of front-end/skin/loader.js.
 *   2. Copies ytm.css / ytm.js into <built-web-dir>/skin/.
 *   3. Guards against upstream renames: asserts every critical fs-* CSS-module
 *      class the skin targets still exists in the built CSS. Fails loudly if
 *      Feishin renamed a component, instead of silently de-skinning the app.
 */

import fs from 'node:fs';
import path from 'node:path';

const START = '/* STREAMIFY_SKIN_LOADER:start */';
const END = '/* STREAMIFY_SKIN_LOADER:end */';

// Keep in sync with the selectors used by front-end/skin/ytm.css and ytm.js.
// Vite's generateScopedName is 'fs-[name]-[local]' (stable, not hashed).
const REQUIRED_CLASSES = [
    'fs-mobile-layout-module-layout',
    'fs-mobile-layout-module-main-content',
    'fs-mobile-layout-module-drawer-button',
    'fs-mobile-layout-module-search-button',
    'fs-mobile-layout-module-full-screen-player-overlay',
    'fs-mobile-playerbar-module-container',
    'fs-mobile-playerbar-module-image',
    'fs-mobile-playerbar-module-controls-wrapper',
    'fs-player-button-module-main',
    'fs-mobile-fullscreen-player-module-container',
    'fs-mobile-fullscreen-player-module-header',
    'fs-item-card-module-container',
    'fs-mobile-sidebar-module-container',
];

function die(message) {
    console.error(`[inject-skin] ERROR: ${message}`);
    process.exit(1);
}

function parseArgs(argv) {
    const args = [...argv];
    const options = { repoRoot: process.cwd(), target: null, verify: true };
    while (args.length) {
        const arg = args.shift();
        if (arg === '--repo-root') {
            options.repoRoot = args.shift();
            if (!options.repoRoot) die('--repo-root requires a path');
        } else if (arg === '--no-verify') {
            // Only for preview-pages.yml's main-tree pass: that tree is built
            // from origin/main (possibly older than this branch) and is just
            // the root half of the fused preview artifact. Our own preview
            // tree is always verified strictly.
            options.verify = false;
        } else if (!options.target) {
            options.target = arg;
        } else {
            die(`unexpected argument: ${arg}`);
        }
    }
    if (!options.target) {
        die('usage: node scripts/inject-skin.mjs <built-web-dir> [--repo-root <path>] [--no-verify]');
    }
    return options;
}

function appendLoader(settingsPath, loaderSource) {
    if (!fs.existsSync(settingsPath)) die(`missing ${settingsPath} — build Feishin first`);

    const settings = fs.readFileSync(settingsPath, 'utf8');
    const block = `${START}\n${loaderSource.trimEnd()}\n${END}\n`;
    const startAt = settings.indexOf(START);
    const endAt = settings.indexOf(END);

    let next;
    if (startAt !== -1 && endAt !== -1 && endAt > startAt) {
        // Replace an existing block so edits to loader.js propagate.
        next = settings.slice(0, startAt) + block + settings.slice(endAt + END.length + 1);
    } else if (startAt !== -1 || endAt !== -1) {
        die(`${settingsPath} has a half-written skin loader block`);
    } else {
        next = `${settings.trimEnd()}\n\n${block}`;
    }

    fs.writeFileSync(settingsPath, next);
    console.log(`[inject-skin] loader installed in ${settingsPath}`);
}

function copyAssets(skinDir, target) {
    const dest = path.join(target, 'skin');
    fs.mkdirSync(dest, { recursive: true });
    for (const file of ['ytm.css', 'ytm.js']) {
        const source = path.join(skinDir, file);
        if (!fs.existsSync(source)) die(`missing skin asset: ${source}`);
        fs.copyFileSync(source, path.join(dest, file));
    }
    console.log(`[inject-skin] assets copied to ${dest}`);
}

function verifySelectors(target) {
    const assetsDir = path.join(target, 'assets');
    if (!fs.existsSync(assetsDir)) die(`missing ${assetsDir} — cannot verify skin selectors`);

    const css = fs
        .readdirSync(assetsDir)
        .filter((file) => file.endsWith('.css'))
        .map((file) => fs.readFileSync(path.join(assetsDir, file), 'utf8'))
        .join('\n');
    if (!css) die(`no CSS found in ${assetsDir}`);

    const missing = REQUIRED_CLASSES.filter((name) => !css.includes(name));
    if (missing.length) {
        die(
            `Feishin class names changed — skin would silently break. Missing: ${missing.join(', ')}.\n` +
                `Update front-end/skin/ytm.css + ytm.js AND REQUIRED_CLASSES above.`,
        );
    }
    console.log(`[inject-skin] verified ${REQUIRED_CLASSES.length} fs-* selectors in built CSS`);
}

const { repoRoot, target, verify } = parseArgs(process.argv.slice(2));
const skinDir = path.join(repoRoot, 'front-end', 'skin');
const loaderPath = path.join(skinDir, 'loader.js');

if (!fs.existsSync(loaderPath)) die(`missing ${loaderPath} (--repo-root wrong?)`);

appendLoader(path.join(target, 'settings.js'), fs.readFileSync(loaderPath, 'utf8'));
copyAssets(skinDir, target);
if (verify) {
    verifySelectors(target);
} else {
    console.log('[inject-skin] selector verification skipped (--no-verify)');
}
console.log('[inject-skin] done.');
