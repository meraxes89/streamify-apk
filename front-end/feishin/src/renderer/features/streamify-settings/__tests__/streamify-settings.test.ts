/**
 * Streamify settings tests (P4B): the pure validator (clamps, defaults,
 * codec enum), relay URL validation, the persistence round-trip through a
 * memory localStorage, the relay override reader (the resolver's lane),
 * and the relay health state machine.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import {
    clampCrossfade,
    DEFAULT_STREAMIFY_SETTINGS,
    readPersistedRelayOverride,
    resetStreamifySettingsStoreForTests,
    STREAMIFY_SETTINGS_STORAGE_KEY,
    useStreamifySettingsStoreBase,
    validateRelayBaseUrl,
    validateStreamifySettings,
} from '/@/renderer/store/streamify-settings.store';

const installMemoryLocalStorage = (): Map<string, string> => {
    const store = new Map<string, string>();
    (globalThis as { window?: unknown }).window = {
        localStorage: {
            getItem: (key: string) => store.get(key) ?? null,
            removeItem: (key: string) => void store.delete(key),
            setItem: (key: string, value: string) => void store.set(key, value),
        },
    };
    return store;
};

describe('streamify-settings: validation', () => {
    it('defaults everything sensible', () => {
        expect(DEFAULT_STREAMIFY_SETTINGS).toEqual({
            brandThemeEnabled: true,
            codecCeiling: 'auto',
            crossfadeDurationSec: 0,
            gapless: true,
            jamHostOnlySkipSeek: false,
            jamOpenQueue: true,
            relayBaseOverride: '',
            scrobbleEnabled: true,
            // W2-B (Smart Sync): on by default; the save-data opt-in is
            // ONLY ever set by an explicit user toggle.
            smartSyncEnabled: true,
            smartSyncSaveDataOverride: false,
        });
    });

    it('normalizes garbage into defaults and clamps crossfade 0-12', () => {
        expect(validateStreamifySettings(null)).toEqual(DEFAULT_STREAMIFY_SETTINGS);
        expect(validateStreamifySettings({ codecCeiling: 'mp3' }).codecCeiling).toBe('auto');
        expect(validateStreamifySettings({ codecCeiling: 'opus' }).codecCeiling).toBe('opus');
        expect(validateStreamifySettings({ crossfadeDurationSec: 99 }).crossfadeDurationSec).toBe(
            12,
        );
        expect(validateStreamifySettings({ crossfadeDurationSec: -4 }).crossfadeDurationSec).toBe(
            0,
        );
        expect(clampCrossfade(5.6)).toBe(6);
        expect(validateStreamifySettings({ gapless: 'nope' }).gapless).toBe(true);
        expect(validateStreamifySettings({ scrobbleEnabled: false }).scrobbleEnabled).toBe(false);
        // W2-B: Smart Sync defaults on; the save-data override requires the
        // explicit true (never implied by garbage).
        expect(validateStreamifySettings(null).smartSyncEnabled).toBe(true);
        expect(validateStreamifySettings({ smartSyncEnabled: false }).smartSyncEnabled).toBe(false);
        expect(
            validateStreamifySettings({ smartSyncSaveDataOverride: 1 }).smartSyncSaveDataOverride,
        ).toBe(false);
        expect(
            validateStreamifySettings({ smartSyncSaveDataOverride: true })
                .smartSyncSaveDataOverride,
        ).toBe(true);
    });

    it('validates relay URLs', () => {
        expect(validateRelayBaseUrl('')).toEqual({ ok: true });
        expect(validateRelayBaseUrl('https://relay.example.workers.dev').ok).toBe(true);
        expect(validateRelayBaseUrl('http://localhost:8787').ok).toBe(true);
        expect(validateRelayBaseUrl('ftp://relay.example').ok).toBe(false);
        expect(validateRelayBaseUrl('not a url').ok).toBe(false);
        expect(validateRelayBaseUrl('https://user:pass@relay.example').ok).toBe(false);
        // invalid overrides fall back to the default (empty)
        expect(validateStreamifySettings({ relayBaseOverride: '::::' }).relayBaseOverride).toBe('');
        expect(
            validateStreamifySettings({ relayBaseOverride: 'https://my.relay/' }).relayBaseOverride,
        ).toBe('https://my.relay/');
    });
});

describe('streamify-settings: persistence', () => {
    let storage: Map<string, string>;

    beforeEach(() => {
        storage = installMemoryLocalStorage();
        resetStreamifySettingsStoreForTests();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('round-trips through localStorage', () => {
        useStreamifySettingsStoreBase.getState().set({
            codecCeiling: 'opus',
            crossfadeDurationSec: 6,
            relayBaseOverride: 'https://relay.example/',
            scrobbleEnabled: false,
        });
        const raw = storage.get(STREAMIFY_SETTINGS_STORAGE_KEY);
        expect(raw).toBeTruthy();

        // Simulate a fresh boot: state starts at defaults, storage wins.
        useStreamifySettingsStoreBase.setState({
            settings: { ...DEFAULT_STREAMIFY_SETTINGS },
        });
        const loaded = useStreamifySettingsStoreBase.getState().load();
        expect(loaded.codecCeiling).toBe('opus');
        expect(loaded.crossfadeDurationSec).toBe(6);
        expect(loaded.relayBaseOverride).toBe('https://relay.example/');
        expect(loaded.scrobbleEnabled).toBe(false);
        expect(loaded.jamOpenQueue).toBe(true); // untouched default
    });

    it('sanitizes a hand-edited blob on load', () => {
        storage.set(
            STREAMIFY_SETTINGS_STORAGE_KEY,
            JSON.stringify({
                settings: { codecCeiling: 'wav', crossfadeDurationSec: 999 },
                v: 1,
            }),
        );
        const loaded = useStreamifySettingsStoreBase.getState().load();
        expect(loaded.codecCeiling).toBe('auto');
        expect(loaded.crossfadeDurationSec).toBe(12);
    });

    it('exposes the relay override for the resolver lane (plain blob read)', () => {
        expect(readPersistedRelayOverride()).toBe('');
        useStreamifySettingsStoreBase.getState().set({
            relayBaseOverride: 'https://relay.example',
        });
        expect(readPersistedRelayOverride()).toBe('https://relay.example');
        // invalid persisted values read back as empty
        storage.set(
            STREAMIFY_SETTINGS_STORAGE_KEY,
            JSON.stringify({ settings: { relayBaseOverride: 'garbage' }, v: 1 }),
        );
        expect(readPersistedRelayOverride()).toBe('');
    });

    it('runs the relay health state machine', async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
        vi.stubGlobal('fetch', fetchMock);
        vi.useFakeTimers();

        const health = await useStreamifySettingsStoreBase
            .getState()
            .checkRelayHealth('https://relay.example');
        // Any HTTP answer proves reachability.
        expect(health.status).toBe('ok');
        expect(health.latencyMs).toBeGreaterThanOrEqual(0);
        expect(fetchMock).toHaveBeenCalledWith(
            'https://relay.example',
            expect.objectContaining({ method: 'HEAD' }),
        );

        fetchMock.mockRejectedValueOnce(new Error('dns'));
        const unreachable = await useStreamifySettingsStoreBase
            .getState()
            .checkRelayHealth('https://relay.example');
        expect(unreachable.status).toBe('unreachable');
        expect(unreachable.error).toBe('dns');

        const invalid = await useStreamifySettingsStoreBase
            .getState()
            .checkRelayHealth('not-a-url');
        expect(invalid.status).toBe('unreachable');
        expect(invalid.error).toBe('invalid-url');

        vi.unstubAllGlobals();
    });
});
