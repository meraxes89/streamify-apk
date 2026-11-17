/**
 * W3-B — the custom 10-band EQ preset manager: share-codec round-trips
 * (base64url + liberal raw-JSON input), the [-12, +12] dB clamps on BOTH
 * encode and decode, typed rejection of malformed payloads, the curated
 * Spatial Air built-in, and the store's import/rename/delete persistence
 * through a memory localStorage. Network-free.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

const h = vi.hoisted(() => ({
    equalizer: {
        bands: [
            { freq: 31, gain: 0 },
            { freq: 62, gain: 0 },
            { freq: 125, gain: 0 },
            { freq: 250, gain: 0 },
            { freq: 500, gain: 0 },
            { freq: 1000, gain: 0 },
            { freq: 2000, gain: 0 },
            { freq: 4000, gain: 0 },
            { freq: 8000, gain: 0 },
            { freq: 16000, gain: 0 },
        ],
        enabled: false,
        preamp: 0,
    },
    setCalls: [] as unknown[],
}));

vi.mock('/@/renderer/store/settings.store', () => {
    const applyPatch = (patch: unknown) => {
        h.setCalls.push(patch);
        const typed = patch as { playback?: { equalizer?: typeof h.equalizer } };
        if (typed.playback?.equalizer) {
            h.equalizer = { ...typed.playback.equalizer };
        }
    };
    const fakeStore = {
        getState: () => ({
            actions: { setSettings: applyPatch },
            playback: { equalizer: h.equalizer },
        }),
    };
    return { useSettingsStore: fakeStore };
});

import {
    BUILTIN_EQ_PRESETS,
    EQ_BAND_FREQS,
    type EqPreset,
    getBuiltinEqPreset,
} from '/@/renderer/features/streamify-eq/utils/eq-math';
import {
    decodeEqPresetShareCode,
    encodeEqPresetShareCode,
    eqPresetSlugId,
    sanitizeEqPresetName,
} from '/@/renderer/features/streamify/eq-presets/preset-codec';
import {
    EQ_STORAGE_KEY,
    resetStreamifyEqStoreForTests,
    selectAllEqPresets,
    useStreamifyEqStoreBase,
} from '/@/renderer/store/streamify-eq.store';

const installMemoryLocalStorage = () => {
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

const flatPreset = (name = 'Flat'): EqPreset => ({
    bands: EQ_BAND_FREQS.map((freq) => ({ freq, gain: 0 })),
    id: `custom:${name.toLowerCase().replace(/\s+/g, '-')}`,
    name,
    preamp: 0,
});

beforeEach(() => {
    installMemoryLocalStorage();
    resetStreamifyEqStoreForTests();
    h.setCalls.length = 0;
});

// ---------------------------------------------------------------------------
// Round-trips
// ---------------------------------------------------------------------------

describe('w3b eq presets — share-codec round-trips', () => {
    it('encodes to URL-safe base64 and decodes back losslessly', () => {
        const preset: EqPreset = {
            bands: EQ_BAND_FREQS.map((freq, i) => ({ freq, gain: (i - 4.5) as number })),
            id: 'custom:tilt',
            name: 'My Tilt',
            preamp: -3.5,
        };
        const code = encodeEqPresetShareCode(preset);
        expect(code).not.toMatch(/[+/=]/); // URL-safe alphabet, no padding
        expect(code).toMatch(/^[A-Za-z0-9_-]+$/);

        const decoded = decodeEqPresetShareCode(code);
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;
        expect(decoded.preset.name).toBe('My Tilt');
        expect(decoded.preset.preamp).toBe(-3.5);
        expect(decoded.preset.bands.map((band) => band.gain)).toEqual(
            EQ_BAND_FREQS.map((_f, i) => i - 4.5),
        );
        expect(decoded.preset.bands.map((band) => band.freq)).toEqual([...EQ_BAND_FREQS]);

        // The canonical idiom: encode(decode(encode(p))) === encode(p).
        expect(encodeEqPresetShareCode(decoded.preset)).toBe(code);
    });

    it('accepts the raw JSON form liberally (leading brace)', () => {
        const json = JSON.stringify({
            bands: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
            name: 'From JSON',
            preampDb: 2,
            v: 1,
        });
        const decoded = decodeEqPresetShareCode(json);
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;
        expect(decoded.preset.bands[9]?.gain).toBe(10);
        expect(decoded.preset.id).toBe('custom:from-json');
    });

    it('resamples a 12-band preset onto the 10 ISO centers on encode', () => {
        // The upstream default leaks 12 bands (adds 3k + 6.3k + 10k).
        const twelve = [
            ...EQ_BAND_FREQS.slice(0, 5),
            3000,
            ...EQ_BAND_FREQS.slice(5, 9),
            6300,
            10000,
            16000,
        ];
        const preset: EqPreset = {
            bands: twelve.map((freq, i) => ({ freq, gain: i % 5 })),
            id: 'custom:twelve',
            name: 'Twelve',
            preamp: 0,
        };
        const decoded = decodeEqPresetShareCode(encodeEqPresetShareCode(preset));
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;
        expect(decoded.preset.bands).toHaveLength(10);
        expect(decoded.preset.bands.map((band) => band.freq)).toEqual([...EQ_BAND_FREQS]);
    });
});

// ---------------------------------------------------------------------------
// Clamping (the P4-B safety invariant)
// ---------------------------------------------------------------------------

describe('w3b eq presets — [-12, +12] dB clamps on both directions', () => {
    it('clamps hostile gains on decode (never reaches the DSP chain)', () => {
        const payload = JSON.stringify({
            bands: [99, -99, 13, -13, 0, 0.5, 11.9, -11.9, 12.01, -12.01],
            name: 'Hostile',
            preampDb: 40,
            v: 1,
        });
        const decoded = decodeEqPresetShareCode(payload);
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;
        const gains = decoded.preset.bands.map((band) => band.gain);
        expect(gains).toEqual([12, -12, 12, -12, 0, 0.5, 11.9, -11.9, 12, -12]);
        expect(decoded.preset.preamp).toBe(12);
    });

    it('clamps on encode too (a corrupted store preset cannot escape)', () => {
        const preset: EqPreset = {
            bands: EQ_BAND_FREQS.map((freq) => ({ freq, gain: 999 })),
            id: 'custom:hot',
            name: 'Hot',
            preamp: -999,
        };
        const decoded = decodeEqPresetShareCode(encodeEqPresetShareCode(preset));
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;
        expect(decoded.preset.bands.every((band) => band.gain === 12)).toBe(true);
        expect(decoded.preset.preamp).toBe(-12);
    });
});

// ---------------------------------------------------------------------------
// Typed rejection of malformed payloads
// ---------------------------------------------------------------------------

describe('w3b eq presets — malformed payloads are rejected with typed errors', () => {
    it('rejects empty, bad-base64, bad-json, bad-schema and bad-bands', () => {
        expect(decodeEqPresetShareCode('   ')).toMatchObject({ error: { code: 'empty' } });
        expect(decodeEqPresetShareCode('!!!not-base64!!!')).toMatchObject({
            error: { code: 'bad-base64' },
        });
        // Valid base64url of non-JSON bytes.
        const garbage =
            typeof btoa === 'function'
                ? btoa('not json at all')
                : Buffer.from('not json at all').toString('base64');
        expect(
            decodeEqPresetShareCode(garbage.replace(/\+/g, '-').replace(/\//g, '_')),
        ).toMatchObject({ error: { code: 'bad-json' } });
        // Wrong version.
        expect(
            decodeEqPresetShareCode(
                JSON.stringify({ bands: new Array(10).fill(0), name: 'x', preampDb: 0, v: 2 }),
            ),
        ).toMatchObject({ error: { code: 'bad-schema' } });
        // Non-numeric gain.
        expect(
            decodeEqPresetShareCode(
                JSON.stringify({
                    bands: ['a', 1, 1, 1, 1, 1, 1, 1, 1, 1],
                    name: 'x',
                    preampDb: 0,
                    v: 1,
                }),
            ),
        ).toMatchObject({ error: { code: 'bad-schema' } });
        // 9 bands and 11 bands.
        expect(
            decodeEqPresetShareCode(
                JSON.stringify({ bands: new Array(9).fill(0), name: 'x', preampDb: 0, v: 1 }),
            ),
        ).toMatchObject({ error: { code: 'bad-bands' } });
        expect(
            decodeEqPresetShareCode(
                JSON.stringify({ bands: new Array(11).fill(0), name: 'x', preampDb: 0, v: 1 }),
            ),
        ).toMatchObject({ error: { code: 'bad-bands' } });
        // Non-object root (a JSON array, base64-wrapped) and an empty object.
        const arrayJson =
            typeof btoa === 'function'
                ? btoa('[1,2,3]')
                : Buffer.from('[1,2,3]').toString('base64');
        expect(decodeEqPresetShareCode(arrayJson)).toMatchObject({
            error: { code: 'bad-schema' },
        });
        expect(decodeEqPresetShareCode(JSON.stringify({}))).toMatchObject({
            error: { code: 'bad-schema' },
        });
    });

    it('sanitizes names (control chars stripped, capped, honest fallback)', () => {
        expect(sanitizeEqPresetName('  Rock\u0007\n ')).toBe('Rock');
        expect(sanitizeEqPresetName('x'.repeat(80))).toHaveLength(40);
        expect(sanitizeEqPresetName(42)).toBe('Imported preset');
        expect(sanitizeEqPresetName('   ')).toBe('Imported preset');
        expect(eqPresetSlugId('Late Night Mix!')).toBe('custom:late-night-mix');
    });
});

// ---------------------------------------------------------------------------
// The curated bank + the store's import lane
// ---------------------------------------------------------------------------

describe('w3b eq presets — curated bank + store import/persistence', () => {
    it('ships Spatial Air with an airy top end (and every built-in stays clamped)', () => {
        const air = getBuiltinEqPreset('spatial-air');
        expect(air).not.toBeNull();
        expect(air?.bands).toHaveLength(10);
        expect(air?.bands[9]?.gain).toBe(4.5); // 16 kHz lift
        expect(air?.bands[0]?.gain).toBe(-2); // gentle low tame
        expect(air?.name).toBe('Spatial Air');
        for (const preset of BUILTIN_EQ_PRESETS) {
            for (const band of preset.bands) {
                expect(Math.abs(band.gain)).toBeLessThanOrEqual(12);
            }
        }
    });

    it('imports a decoded preset: id-deduped, applied, persisted', () => {
        const decoded = decodeEqPresetShareCode(
            encodeEqPresetShareCode({ ...flatPreset('Club'), name: 'Club', preamp: 1 }),
        );
        expect(decoded.ok).toBe(true);
        if (!decoded.ok) return;

        const imported = useStreamifyEqStoreBase.getState().importCustomPreset(decoded.preset);
        expect(imported.id).toBe('custom:club');
        expect(useStreamifyEqStoreBase.getState().selectedPresetId).toBe('custom:club');
        // Applied: the write-through reached settings.playback.equalizer.
        expect(h.setCalls).toHaveLength(1);
        const patch = h.setCalls[0] as { playback: { equalizer: typeof h.equalizer } };
        expect(patch.playback.equalizer.enabled).toBe(true);

        // A second import of the same code dedupes the id (never overwrites).
        const again = useStreamifyEqStoreBase.getState().importCustomPreset(decoded.preset);
        expect(again.id).toBe('custom:club-2');
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(2);

        // Persisted for the next load; listed with the 7 built-ins.
        useStreamifyEqStoreBase.getState().load();
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(2);
        expect(selectAllEqPresets(useStreamifyEqStoreBase.getState())).toHaveLength(9);

        // Rename + delete round-trip.
        useStreamifyEqStoreBase.getState().renameCustomPreset('custom:club', 'Big Club');
        expect(
            useStreamifyEqStoreBase.getState().customPresets.find((p) => p.id === 'custom:club')
                ?.name,
        ).toBe('Big Club');
        useStreamifyEqStoreBase.getState().deleteCustomPreset('custom:club');
        useStreamifyEqStoreBase.getState().deleteCustomPreset('custom:club-2');
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(0);
    });

    it('persists the bank through the storage blob (v1)', () => {
        const storage = installMemoryLocalStorage();
        const decoded = decodeEqPresetShareCode(
            encodeEqPresetShareCode({ ...flatPreset('Solo'), name: 'Solo' }),
        );
        if (!decoded.ok) return;
        useStreamifyEqStoreBase.getState().importCustomPreset(decoded.preset);
        const raw = storage.get(EQ_STORAGE_KEY) as string;
        const blob = JSON.parse(raw) as { customPresets: EqPreset[]; v: number };
        expect(blob.v).toBe(1);
        expect(blob.customPresets[0]?.name).toBe('Solo');
    });
});
