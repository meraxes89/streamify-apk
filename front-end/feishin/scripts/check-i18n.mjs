/**
 * scripts/check-i18n.mjs - the P5-B i18n integrity gate.
 *
 * Policy (see front-end/decisions/p5/P5B-i18n-hardening-release-notes.md):
 * Streamify keys live ONLY in src/i18n/locales/en.json. The other 38
 * Weblate-managed catalogs are carried untouched and fall back to English
 * via fallbackLng: 'en' - so this gate never fails on their coverage,
 * it only reports parity stats non-destructively.
 *
 * Fails (exit 1) on: a catalog that is not valid UTF-8 JSON, an empty
 * Streamify value in en.json, a Streamify key appearing in a non-English
 * catalog, or a t('...') reference in the streamify surfaces that is not
 * backed by en.json. Run from front-end/feishin: node scripts/check-i18n.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const localesDir = path.join(root, 'src/i18n/locales');
const STREAMIFY_KEY = /streamify/i;
const TEST_FILE = /(?:__tests__|\.test\.[cm]?[jt]sx?$)/;

// 1) All catalogs parse as UTF-8 JSON; Streamify keys stay en.json-only and non-empty.
const catalogs = {};
for (const file of fs
    .readdirSync(localesDir)
    .filter((f) => f.endsWith('.json'))
    .sort()) {
    const locale = file.slice(0, -5);
    let data;
    try {
        data = JSON.parse(fs.readFileSync(path.join(localesDir, file), 'utf8'));
    } catch (error) {
        console.error(`FAIL ${file} is not valid JSON: ${error.message}`);
        process.exit(1);
    }
    const flat = {};
    (function walk(node, prefix) {
        for (const [k, v] of Object.entries(node)) {
            const key = prefix ? `${prefix}.${k}` : k;
            if (v && typeof v === 'object') walk(v, key);
            else flat[key] = v;
        }
    })(data, '');
    catalogs[locale] = flat;
    if (locale !== 'en' && Object.keys(flat).some((k) => STREAMIFY_KEY.test(k))) {
        console.error(`FAIL ${file} contains Streamify keys - they belong in en.json only`);
        process.exit(1);
    }
    for (const [key, value] of Object.entries(flat)) {
        if (STREAMIFY_KEY.test(key) && value === '') {
            console.error(`FAIL ${file} has an empty value on Streamify key ${key}`);
            process.exit(1);
        }
    }
}

// 2) Every t('...') literal in the streamify surfaces resolves through en.json.
const enKeys = new Set(Object.keys(catalogs.en));
const surfaces = ['src/renderer/features', 'src/renderer/api/streamify']
    .map((dir) => path.join(root, dir))
    .flatMap((dir) =>
        fs
            .readdirSync(dir, { withFileTypes: true })
            .filter((e) => e.name.startsWith('streamify-') || dir.endsWith('api/streamify'))
            .map((e) => path.join(dir, e.name)),
    );
const refs = new Map();
for (const surface of surfaces) {
    const stack = fs.statSync(surface).isDirectory() ? [surface] : [];
    while (stack.length) {
        const dir = stack.pop();
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const file = path.join(dir, entry.name);
            if (entry.isDirectory()) stack.push(file);
            else if (/\.[cm]?[jt]sx?$/.test(entry.name) && !TEST_FILE.test(file)) {
                const source = fs.readFileSync(file, 'utf8');
                for (const m of source.matchAll(
                    /(?<![A-Za-z0-9_$.])t\(\s*(['"`])((?:\\.|(?!\1).)*)\1/g,
                )) {
                    if (m[1] === '`' && m[2].includes('${')) continue; // dynamic: prefix-checked below
                    const key = m[2].replace(/\\'/g, "'");
                    if (!enKeys.has(key)) refs.set(key, (refs.get(key) ?? []).concat(file));
                }
                for (const m of source.matchAll(/(?<![A-Za-z0-9_$.])t\(\s*`([^${`]*)\$\{/g)) {
                    const prefix = m[1];
                    if (prefix && ![...enKeys].some((k) => k.startsWith(prefix))) {
                        refs.set(`${prefix}*`, (refs.get(`${prefix}*`) ?? []).concat(file));
                    }
                }
            }
        }
    }
}
if (refs.size) {
    for (const [key, files] of refs)
        console.error(`FAIL t('${key}') not in en.json <- ${files[0]}`);
    process.exit(1);
}

// 3) Non-destructive parity report for the 38 Weblate catalogs.
// CLDR plural suffixes English lacks, locale-appropriate _one omissions
// (languages with no singular/plural split carry none), and Streamify's
// en.json-only keys are whitelisted out of the missing count.
const NO_PLURAL_ONE = new Set([
    'bg',
    'id',
    'ja',
    'ko',
    'lv',
    'ro',
    'th',
    'tl',
    'zh-Hans',
    'zh-Hant',
]);
const CLDR = /_(zero|two|few|many)$/;
let report = '';
for (const [locale, flat] of Object.entries(catalogs)) {
    if (locale === 'en') continue;
    const extraCldr = Object.keys(flat).filter((k) => !enKeys.has(k) && CLDR.test(k)).length;
    const extraOther = Object.keys(flat).filter((k) => !enKeys.has(k) && !CLDR.test(k)).length;
    const missing = [...enKeys].filter(
        (k) =>
            !(k in flat) &&
            !STREAMIFY_KEY.test(k) &&
            !(NO_PLURAL_ONE.has(locale) && k.endsWith('_one')),
    ).length;
    const pct = ((Object.keys(flat).length / enKeys.size) * 100).toFixed(1);
    report += `  ${locale.padEnd(7)} ${String(Object.keys(flat).length).padStart(4)}/${enKeys.size} keys (${pct}%)  +${extraCldr} CLDR  +${extraOther} stale  -${missing} untranslated\n`;
}
console.log(
    `i18n check: ${Object.keys(catalogs).length} catalogs parse, en.json ${enKeys.size} keys (0 empty Streamify values), all streamify surface t() references resolve.`,
);
console.log('Parity vs en.json (informational - Weblate owns these catalogs):');
console.log(report.trimEnd());
