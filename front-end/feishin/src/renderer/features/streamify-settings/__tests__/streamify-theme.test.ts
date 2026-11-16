/**
 * Brand theme tests (P4B): the additive registration contract - upstream
 * theme definitions stay untouched, the preset registers through the
 * runtime custom-theme seam, the picker lists it, and the default-selection
 * logic only fires for stock-default themes on Streamify sessions.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('is-electron', () => ({ default: () => false }));

const registryWrites: Array<Record<string, unknown>> = [];

vi.mock('/@/shared/themes/app-theme', () => ({
    setCustomThemeRegistry: (registry: Record<string, unknown>) => {
        registryWrites.push(registry);
    },
}));

import {
    ensureStreamifyThemeRegistered,
    STREAMIFY_THEME_ID,
    STREAMIFY_THEME_LABEL,
    streamifyBrandTheme,
    streamifyThemeRegistryEntry,
    watchStreamifyThemeRegistration,
} from '/@/renderer/features/streamify-settings/theme/streamify-theme';
import { useCustomThemesStore } from '/@/renderer/store/custom-themes.store';
import { AppTheme } from '/@/shared/themes/app-theme-types';

describe('streamify-theme: additive registration', () => {
    beforeEach(() => {
        registryWrites.length = 0;
        useCustomThemesStore.setState({ themes: [] });
    });

    it('registers through the runtime seam without touching the upstream map', async () => {
        await ensureStreamifyThemeRegistered();

        expect(registryWrites).toHaveLength(1);
        const registry = registryWrites[0];
        expect(registry[STREAMIFY_THEME_ID]).toBeDefined();

        // The upstream built-in map is NOT mutated (the registry write is a
        // separate object; appTheme itself never gains our key).
        const upstreamKeys = Object.values(AppTheme);
        expect(upstreamKeys).not.toContain(STREAMIFY_THEME_ID);

        // The picker listing gained exactly one meta.
        const metas = useCustomThemesStore.getState().themes;
        expect(metas).toHaveLength(1);
        expect(metas[0]).toEqual({
            id: STREAMIFY_THEME_ID,
            label: STREAMIFY_THEME_LABEL,
            mode: 'dark',
        });
    });

    it('re-registers idempotently (no duplicate metas)', async () => {
        await ensureStreamifyThemeRegistered();
        await ensureStreamifyThemeRegistered();
        expect(useCustomThemesStore.getState().themes).toHaveLength(1);
        expect(registryWrites).toHaveLength(2);
    });

    it('watch re-adds the preset after an external registry wipe', async () => {
        await ensureStreamifyThemeRegistered();
        const stop = watchStreamifyThemeRegistration();

        // A file-watch update replaces the store metas (dropping ours).
        useCustomThemesStore.setState({ themes: [] });

        // The watcher schedules re-registration asynchronously.
        await vi.waitFor(() => {
            expect(
                useCustomThemesStore.getState().themes.some((m) => m.id === STREAMIFY_THEME_ID),
            ).toBe(true);
        });
        stop();
    });

    it('the theme configuration is the OLED/brand-red palette', () => {
        expect(streamifyBrandTheme.mode).toBe('dark');
        expect(streamifyBrandTheme.colors?.background).toBe('rgb(3, 3, 3)');
        expect(streamifyBrandTheme.colors?.primary).toBe('rgb(255, 0, 51)');
        expect(streamifyBrandTheme.mantineOverride?.primaryColor).toBe('primary');
        expect(streamifyBrandTheme.mantineOverride?.colors?.primary).toHaveLength(10);
        expect(streamifyThemeRegistryEntry()[STREAMIFY_THEME_ID]).toBe(streamifyBrandTheme);
    });

    it('feeds the stock getAppTheme resolution path', () => {
        // The registry write is exactly what getAppTheme consults for ids
        // outside the built-in enum; here we assert the entry shape.
        const registry = streamifyThemeRegistryEntry();
        expect(Object.keys(registry)).toEqual([STREAMIFY_THEME_ID]);
    });
});
