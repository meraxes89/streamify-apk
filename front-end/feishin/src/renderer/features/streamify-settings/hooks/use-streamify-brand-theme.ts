/**
 * features/streamify-settings/hooks/use-streamify-brand-theme.ts - default
 * new Streamify sessions to the brand theme (P4B).
 *
 * The rule (additive; upstream theme selection untouched):
 *
 *   - when a Streamify session becomes active AND the brand-theme toggle is
 *     on AND the user has not pinned a theme choice, the stock default
 *     theme is switched to `streamifyBrand` (the exact three calls the
 *     upstream picker makes: setSettings, setColorScheme, electron
 *     localSettings.themeSet);
 *   - any later explicit theme change by the user (outside our own apply
 *     window) pins the choice - we never fight the user again;
 *   - every upstream theme stays selectable at all times.
 */

import isElectron from 'is-electron';
import { useEffect, useRef } from 'react';

import {
    ensureStreamifyThemeRegistered,
    STREAMIFY_THEME_ID,
    watchStreamifyThemeRegistration,
} from '/@/renderer/features/streamify-settings/theme/streamify-theme';
import { useAuthStore } from '/@/renderer/store';
import { useSettingsStore } from '/@/renderer/store/settings.store';
import { useStreamifySettingsStoreBase } from '/@/renderer/store/streamify-settings.store';
import { useSetColorScheme } from '/@/renderer/themes/use-app-theme';
import { logger } from '/@/renderer/utils/logger';
import { getAppTheme } from '/@/shared/themes/app-theme';
import { AppTheme } from '/@/shared/themes/app-theme-types';
import { ServerType } from '/@/shared/types/types';

const PINNED_KEY = 'streamify.theme.pinned';
const DEFAULTED_KEY = 'streamify.theme.defaulted';
const SELF_APPLY_WINDOW_MS = 250;

const readFlag = (key: string): boolean => {
    try {
        return window.localStorage.getItem(key) === '1';
    } catch {
        return false;
    }
};

const writeFlag = (key: string, value: boolean): void => {
    try {
        window.localStorage.setItem(key, value ? '1' : '0');
    } catch {
        // storage unavailable - falls back to per-session behavior
    }
};

export const useStreamifyBrandTheme = (): void => {
    const isStreamify = useAuthStore((state) => state.currentServer?.type) === ServerType.STREAMIFY;
    const currentTheme = useSettingsStore((state) => state.general.theme);
    const { setColorScheme } = useSetColorScheme();
    const selfApplyAtRef = useRef(0);

    // Register the preset + keep it registered (electron file-watch safety).
    useEffect(() => {
        void ensureStreamifyThemeRegistered();
        const stop = watchStreamifyThemeRegistration();
        return stop;
    }, []);

    // Pin detection: an explicit change away from the brand theme (outside
    // our own apply window) is the user's choice - respect it forever.
    useEffect(() => {
        if (readFlag(PINNED_KEY)) return;
        if (
            currentTheme !== STREAMIFY_THEME_ID &&
            Date.now() - selfApplyAtRef.current > SELF_APPLY_WINDOW_MS
        ) {
            // Only pin when the user moved away AFTER we had applied ours
            // (a stock session that never defaulted keeps its theme alone).
            if (readFlag(DEFAULTED_KEY)) {
                writeFlag(PINNED_KEY, true);
            }
        }
    }, [currentTheme]);

    // Default-on-activation.
    useEffect(() => {
        if (!isStreamify) return;
        const { brandThemeEnabled } = useStreamifySettingsStoreBase.getState().settings;
        if (!brandThemeEnabled) return;
        if (readFlag(PINNED_KEY) || readFlag(DEFAULTED_KEY)) return;

        const theme = useSettingsStore.getState().general.theme;
        const isStockDefault =
            theme === AppTheme.DEFAULT_DARK ||
            theme === AppTheme.DEFAULT_LIGHT ||
            theme === 'defaultDark' ||
            theme === 'defaultLight';
        if (!isStockDefault) {
            // The user arrived with a deliberate theme - pin it.
            writeFlag(DEFAULTED_KEY, true);
            return;
        }

        selfApplyAtRef.current = Date.now();
        useSettingsStore.getState().actions.setSettings({
            general: { theme: STREAMIFY_THEME_ID },
        });
        const colorScheme = getAppTheme(STREAMIFY_THEME_ID).mode ?? 'dark';
        setColorScheme(colorScheme);
        if (isElectron()) {
            const localSettings = (
                window as unknown as {
                    api?: { localSettings?: { themeSet?: (scheme: string) => void } };
                }
            ).api?.localSettings;
            localSettings?.themeSet?.(colorScheme);
        }
        writeFlag(DEFAULTED_KEY, true);
        logger.debug('Streamify brand theme applied for streamify session');
    }, [isStreamify, setColorScheme]);
};
