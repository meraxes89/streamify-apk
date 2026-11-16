// Reference: https://github.com/i18next/i18next-parser#options

module.exports = {
    contextSeparator: '_',
    createOldCatalogs: true,
    customValueTemplate: null,
    defaultNamespace: 'translation',
    defaultValue: function (locale, namespace, key) {
        return key;
    },
    failOnUpdate: false,
    failOnWarnings: false,
    i18nextOptions: null,
    indentation: 4,
    input: [
        '../renderer/components/**/*.{js,jsx,ts,tsx}',
        '../renderer/features/**/*.{js,jsx,ts,tsx}',
        '../renderer/layouts/**/*.{js,jsx,ts,tsx}',
        '!../src/node_modules/**',
        '!../src/**/*.prod.js',
    ],
    keepRemoved: false,
    keySeparator: '.',
    lexers: {
        default: ['JavascriptLexer'],
        handlebars: ['HandlebarsLexer'],
        hbs: ['HandlebarsLexer'],
        htm: ['HTMLLexer'],
        html: ['HTMLLexer'],
        js: ['JavascriptLexer'],
        jsx: ['JsxLexer'],
        mjs: ['JavascriptLexer'],
        ts: ['JavascriptLexer'],
        tsx: ['JsxLexer'],
    },
    lineEnding: 'auto',
    locales: ['en'],
    namespaceSeparator: false,
    // i18next-parser resolves config-relative paths against this file's
    // directory (src/i18n/), so './locales/' is the live catalog directory
    // src/i18n/locales/ — the vendored upstream pointed at the long-gone
    // src/renderer/i18n/locales/ tree.
    output: './locales/$LOCALE.json',
    pluralSeparator: '_',
    resetDefaultValueLocale: 'en',
    sort: true,
    verbose: false,
};
