/*
 * ============================================================================
 * STREAMIFY SKIN — mobile shell behaviour layer
 * ============================================================================
 *
 * WHAT THIS IS
 *   Companion to ytm.css. Adds a YouTube-Music-style bottom tab bar on top of
 *   Feishin's stock mobile layout. It does NOT import React, does NOT call any
 *   Feishin store, and does NOT edit any file under front-end/feishin/.
 *
 * HOW IT NAVIGATES
 *   Feishin uses react-router's HashRouter (app-router.tsx). Every route lives
 *   in location.hash, so assigning `location.hash = '#/library/songs'` is a
 *   first-class navigation the same way clicking a sidebar link is. This is
 *   the whole reason the shell can exist without an upstream code seam.
 *
 * ROUTES (from front-end/feishin/src/renderer/router/routes.ts)
 *   Home      #/
 *   Search    #/search/song        (AppRoute.SEARCH needs an itemType param)
 *   Library   #/library/songs
 *   Downloads #/streamify-downloads  (fork-owned route)
 *   Settings  #/streamify-settings   (fork-owned route)
 *
 * SAFETY / IDEMPOTENCE
 *   - Runs only on coarse-pointer + <=768px viewports (same gate as ytm.css).
 *   - Waits for #mobile-layout so the login/splash gate is left untouched.
 *   - Re-entrant: if the nav already exists, only active state is refreshed.
 *   - Uses a MutationObserver to detect the fullscreen player overlay and set
 *     body.skin-player-open so CSS can hide the nav while the player is up.
 * ============================================================================
 */
(function () {
    'use strict';

    var NAV_ID = 'streamify-skin-nav';
    var OVERLAY_CLASS = 'fs-mobile-layout-module-full-screen-player-overlay';
    var TABS = [
        { id: 'home', label: 'Home', route: '#/', match: /^#\/?$/ },
        { id: 'search', label: 'Search', route: '#/search/song', match: /^#\/search/ },
        {
            id: 'library',
            label: 'Library',
            route: '#/library/songs',
            match: /^#\/(library|playlists|favorites)/,
        },
        {
            id: 'downloads',
            label: 'Downloads',
            route: '#/streamify-downloads',
            match: /^#\/streamify-downloads/,
        },
        {
            id: 'settings',
            label: 'Settings',
            route: '#/streamify-settings',
            match: /^#\/(streamify-settings|settings)/,
        },
    ];

    // Minimal outline icons (no external icon font / network dependency).
    var ICONS = {
        home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M9.5 21v-6.5h5V21"/>',
        search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>',
        library: '<path d="M4 4h4v16H4z"/><path d="M10 4h4v16h-4z"/><path d="m17 5 3 .8-3.5 14.2-3-.8z"/>',
        downloads: '<path d="M12 3v12"/><path d="m7 11 5 5 5-5"/><path d="M4 19h16"/>',
        settings:
            '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 1.55V21H9v-.05A1.7 1.7 0 0 0 8 19.4a1.7 1.7 0 0 0-1.87.33l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 3.6 15a1.7 1.7 0 0 0-1.55-1H2v-4h.05A1.7 1.7 0 0 0 3.6 9a1.7 1.7 0 0 0-.33-1.87l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 8 4.6a1.7 1.7 0 0 0 1-1.55V3h5v.05A1.7 1.7 0 0 0 15 4.6a1.7 1.7 0 0 0 1.87-.33l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.2.6.76 1 1.4 1h.15v4H21a1.7 1.7 0 0 0-1.6 1z"/>',
    };

    function isMobile() {
        try {
            return window.matchMedia('(max-width: 768px) and (pointer: coarse)').matches;
        } catch (e) {
            return false;
        }
    }

    function svg(paths) {
        // SVG assembled from a fixed, in-file string table — never from user data.
        return (
            '<svg class="skin-nav-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
            paths +
            '</svg>'
        );
    }

    function syncActiveState() {
        var nav = document.getElementById(NAV_ID);
        if (!nav) return;
        var hash = window.location.hash || '#/';
        for (var i = 0; i < TABS.length; i += 1) {
            var tab = TABS[i];
            var button = nav.querySelector('[data-skin-tab="' + tab.id + '"]');
            if (!button) continue;
            if (tab.match.test(hash)) {
                button.setAttribute('aria-current', 'page');
            } else {
                button.removeAttribute('aria-current');
            }
        }
    }

    function createNav() {
        var nav = document.createElement('nav');
        nav.id = NAV_ID;
        nav.setAttribute('aria-label', 'Streamify player navigation');

        for (var i = 0; i < TABS.length; i += 1) {
            (function (tab) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'skin-nav-item';
                button.setAttribute('data-skin-tab', tab.id);
                button.setAttribute('aria-label', tab.label);
                button.innerHTML = svg(ICONS[tab.id]) + '<span class="skin-nav-label">' + tab.label + '</span>';
                button.addEventListener('click', function () {
                    // HashRouter listens to hashchange; no React internals needed.
                    if (window.location.hash !== tab.route) {
                        window.location.hash = tab.route;
                    }
                    syncActiveState();
                });
                nav.appendChild(button);
            })(TABS[i]);
        }

        document.body.appendChild(nav);
        syncActiveState();
    }

    function syncPlayerOverlay() {
        var overlay = document.querySelector('[class*="' + OVERLAY_CLASS + '"]');
        document.body.classList.toggle('skin-player-open', Boolean(overlay));
    }

    function start() {
        if (!isMobile() || document.getElementById(NAV_ID)) {
            syncPlayerOverlay();
            return;
        }

        var layout = document.getElementById('mobile-layout');
        if (!layout) return; // auth/splash route — stay out of the way.

        createNav();
        syncPlayerOverlay();
        window.addEventListener('hashchange', syncActiveState);
        window.addEventListener('popstate', syncActiveState);

        // The fullscreen player is an AnimatePresence overlay, not a route.
        // Watch for it so the nav can fade out while it is mounted.
        var observer = new MutationObserver(syncPlayerOverlay);
        observer.observe(document.body, { childList: true, subtree: true });
    }

    // settings.js is a classic <script> in <head>, so this file normally loads
    // before React mounts. Poll via MutationObserver until #mobile-layout shows
    // up, then wire everything once.
    function boot() {
        if (!isMobile()) return;
        if (document.getElementById('mobile-layout')) {
            start();
            return;
        }
        var bootObserver = new MutationObserver(function () {
            if (document.getElementById('mobile-layout')) {
                bootObserver.disconnect();
                start();
            }
        });
        bootObserver.observe(document.documentElement, { childList: true, subtree: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot, { once: true });
    } else {
        boot();
    }
})();
