/*
 * STREAMIFY SKIN — settings.js bootstrap (monorepo-owned)
 *
 * Appended to generated settings.js by scripts/inject-skin.mjs. Keep this
 * snippet small and dependency-free: settings.js is a classic <script> that
 * runs while <head> is still parsing, before Feishin's React bundle.
 *
 * The base for skin assets is the URL of settings.js itself. That makes the
 * same relative path work at all three deployment shapes:
 *   http://localhost:3000/settings.js                 -> /skin/ytm.css
 *   /streamify-apk/settings.js                        -> /streamify-apk/skin/ytm.css
 *   /streamify-apk/preview/settings.js                -> /streamify-apk/preview/skin/ytm.css
 */
(function () {
    var base = 'skin/';
    try {
        var self = document.currentScript && document.currentScript.src;
        if (self) {
            base = new URL('skin/', self).href;
        }
    } catch (e) {
        /* new URL unsupported or currentScript unavailable — relative fallback. */
    }

    function install() {
        if (document.getElementById('streamify-skin-css')) return;

        // Appending at DOMContentLoaded puts our <link> AFTER Vite's bundle
        // CSS link in <head>, so equal-specificity rules in ytm.css win.
        var link = document.createElement('link');
        link.id = 'streamify-skin-css';
        link.rel = 'stylesheet';
        link.href = base + 'ytm.css';
        document.head.appendChild(link);

        var script = document.createElement('script');
        script.id = 'streamify-skin-js';
        script.src = base + 'ytm.js';
        document.head.appendChild(script);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', install, { once: true });
    } else {
        install();
    }
})();
