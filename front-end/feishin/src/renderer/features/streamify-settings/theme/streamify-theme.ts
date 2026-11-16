/**
 * features/streamify-settings/theme/streamify-theme.ts - the Streamify
 * brand theme, registered additively (P4B).
 *
 * UPSTREAM ISOLATION (the hygiene contract): no upstream theme file is
 * touched. The brand preset rides the runtime seam upstream built for
 * third-party themes - setCustomThemeRegistry + the custom-themes store
 * metas - so:
 *
 *   - getAppTheme('streamifyBrand') resolves through the normal registry
 *     path and the stock theme picker lists it next to the built-ins
 *     (upstream themes stay fully selectable);
 *   - in web builds the custom registry is exclusively ours (no themes
 *     folder exists), so registration cannot clobber anything;
 *   - in electron builds disk-loaded customs are re-fetched and composed
 *     WITH ours before the registry swap, and a store subscription
 *     re-registers after any file-watch update.
 *
 * The palette is the StreamifyTheme port (front-end/web theme/tokens.css):
 * YouTube-Music OLED true-dark graphite surfaces + the brand red accent,
 * dark-only.
 */

import type { AppThemeConfiguration } from '/@/shared/themes/app-theme-types';
import type { MantineColorsTuple } from '@mantine/core';

import { generateColors } from '@mantine/colors-generator';
import isElectron from 'is-electron';

import { useCustomThemesStore } from '/@/renderer/store/custom-themes.store';
import { logger } from '/@/renderer/utils/logger';
import { setCustomThemeRegistry } from '/@/shared/themes/app-theme';

export const STREAMIFY_THEME_ID = 'streamifyBrand';
export const STREAMIFY_THEME_LABEL = 'Streamify';

/** The Mantine color tokens: OLED dark ramp + the brand red primary. */
const streamifyDarkRamp: MantineColorsTuple = [
    '#0a0a0a',
    '#0f0f0f',
    '#141414',
    '#181818',
    '#212121',
    '#282828',
    '#383838',
    '#484848',
    '#5c5c5c',
    '#787878',
];

export const streamifyBrandTheme: AppThemeConfiguration = {
    app: {
        'scrollbar-handle-background': 'rgb(40, 40, 40)',
        'scrollbar-handle-hover-background': 'rgb(64, 64, 64)',
        'scrollbar-track-background': 'rgb(15, 15, 15)',
    },
    colors: {
        background: 'rgb(3, 3, 3)',
        'background-alternate': 'rgb(15, 15, 15)',
        black: 'rgb(3, 3, 3)',
        foreground: 'rgb(255, 255, 255)',
        'foreground-muted': 'rgb(170, 170, 170)',
        primary: 'rgb(255, 0, 51)',
        'state-error': 'rgb(255, 77, 77)',
        'state-info': 'rgb(62, 166, 255)',
        'state-success': 'rgb(74, 222, 128)',
        'state-warning': 'rgb(255, 187, 0)',
        surface: 'rgb(15, 15, 15)',
        'surface-foreground': 'rgb(255, 255, 255)',
        white: 'rgb(255, 255, 255)',
    },
    mantineOverride: {
        black: '#030303',
        colors: {
            dark: streamifyDarkRamp,
            primary: generateColors('#ff0033'),
        },
        fontFamily: "'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif",
        headings: { fontFamily: "'Inter', 'Segoe UI', system-ui, sans-serif" },
        primaryColor: 'primary',
        primaryShade: 5,
        white: '#ffffff',
    },
    mode: 'dark',
};

/** Picker metadata (the custom-themes store shape). */
export const STREAMIFY_THEME_META = {
    id: STREAMIFY_THEME_ID,
    label: STREAMIFY_THEME_LABEL,
    mode: 'dark' as const,
};

/** The registry entry upstream's getAppTheme resolves. */
export const streamifyThemeRegistryEntry = (): Record<string, AppThemeConfiguration> => ({
    [STREAMIFY_THEME_ID]: streamifyBrandTheme,
});

type RawDiskTheme = {
    app?: Record<string, unknown>;
    colors?: Record<string, unknown>;
    error?: string;
    id: string;
    mantineOverride?: Record<string, unknown>;
    mode: 'dark' | 'light';
    stylesheetContents?: string[];
};

/**
 * Register the brand theme additively. Safe to call repeatedly; composes
 * with disk-loaded custom themes (electron) instead of replacing them.
 */
export const ensureStreamifyThemeRegistered = async (): Promise<void> => {
    let registry: Record<string, AppThemeConfiguration> = streamifyThemeRegistryEntry();

    if (isElectron()) {
        try {
            const api = (
                window as unknown as {
                    api?: { customThemes?: { get?: () => Promise<RawDiskTheme[]> } };
                }
            ).api;
            const rawThemes = (await api?.customThemes?.get?.()) ?? [];
            for (const raw of rawThemes) {
                if (raw.error || !raw.id) continue;
                registry = {
                    ...registry,
                    [raw.id]: {
                        app: raw.app as AppThemeConfiguration['app'],
                        colors: raw.colors as AppThemeConfiguration['colors'],
                        mantineOverride:
                            raw.mantineOverride as AppThemeConfiguration['mantineOverride'],
                        mode: raw.mode,
                        stylesheets: raw.stylesheetContents,
                    },
                };
            }
        } catch (error) {
            logger.warn('Streamify theme registration could not read disk themes', {
                error: String(error),
            });
        }
    }

    setCustomThemeRegistry(registry);

    // Keep the picker listing in sync (idempotent merge).
    const metas = useCustomThemesStore.getState().themes;
    const withoutOurs = metas.filter((meta) => meta.id !== STREAMIFY_THEME_ID);
    useCustomThemesStore.setState({
        themes: [...withoutOurs, STREAMIFY_THEME_META],
    });
};

/**
 * Watch for registry-replacing events (electron file-watch updates call
 * setCustomThemeRegistry directly) and re-add ours after any of them.
 */
export const watchStreamifyThemeRegistration = (): (() => void) => {
    return useCustomThemesStore.subscribe((state) => {
        if (!state.themes.some((meta) => meta.id === STREAMIFY_THEME_ID)) {
            void ensureStreamifyThemeRegistered();
        }
    });
};
