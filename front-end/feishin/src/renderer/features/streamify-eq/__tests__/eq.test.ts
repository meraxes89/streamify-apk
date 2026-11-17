/**
 * EQ tests (P4B) - the pure DSP math (clamps, presets, biquad response),
 * the preset store (apply/save/load/persistence round-trip through a
 * memory localStorage), and the watchdog decision function.
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
    biquadMagnitudeDb,
    BUILTIN_EQ_PRESETS,
    clampBandGain,
    clampPreamp,
    eqBandsForEngine,
    eqBandsForSettings,
    eqFrequencyResponse,
    type EqPreset,
    getBuiltinEqPreset,
    normalizeEqPreset,
    peakingCoefficients,
} from '/@/renderer/features/streamify-eq/utils/eq-math';
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

describe('eq-math: clamps and presets', () => {
    it('clamps band gains and preamp to +/-12 dB', () => {
        expect(clampBandGain(99)).toBe(12);
        expect(clampBandGain(-99)).toBe(-12);
        expect(clampBandGain(4.5)).toBe(4.5);
        expect(clampPreamp(20)).toBe(12);
        expect(clampPreamp(-20)).toBe(-12);
        expect(clampPreamp(-3)).toBe(-3);
    });

    it('ships the seven curated built-ins (W3-B adds Spatial Air) with 10 ISO bands each', () => {
        expect(BUILTIN_EQ_PRESETS.map((preset) => preset.id)).toEqual([
            'flat',
            'bass-boost',
            'vocal',
            'acoustic',
            'electronic',
            'late-night',
            'spatial-air',
        ]);
        for (const preset of BUILTIN_EQ_PRESETS) {
            expect(preset.bands).toHaveLength(10);
            expect(preset.bands.map((band) => band.freq)).toEqual([
                31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
            ]);
            for (const band of preset.bands) {
                expect(Math.abs(band.gain)).toBeLessThanOrEqual(12);
            }
            expect(Math.abs(preset.preamp)).toBeLessThanOrEqual(12);
            expect(preset.name).not.toBe(preset.id); // W3-B: display names
        }
        expect(getBuiltinEqPreset('vocal')?.bands[4].gain).toBe(4);
        expect(getBuiltinEqPreset('nope')).toBeNull();
    });

    it('normalizes untrusted preset data (clamps + rejects)', () => {
        const normalized = normalizeEqPreset({
            bands: [
                { freq: 100, gain: 99 },
                { freq: 200, gain: -99, q: 0.5 },
            ],
            id: 'x',
            name: 42,
            preamp: 99,
        });
        expect(normalized?.bands[0].gain).toBe(12);
        expect(normalized?.bands[1].gain).toBe(-12);
        expect(normalized?.bands[1].q).toBe(0.5);
        expect(normalized?.preamp).toBe(12);
        expect(normalized?.name).toBe('x');

        expect(normalizeEqPreset({ bands: [], id: 'x' })).toBeNull();
        expect(normalizeEqPreset({ bands: [{ freq: -1, gain: 0 }], id: 'x' })).toBeNull();
        expect(normalizeEqPreset({ bands: [{ freq: 1, gain: 0 }] })).toBeNull();
    });
});

describe('eq-math: biquad response', () => {
    it('peaking coefficients are stable and centered', () => {
        const coef = peakingCoefficients(1000, 1.41, 6);
        for (const value of [coef.a0, coef.a1, coef.a2, coef.b0, coef.b1, coef.b2]) {
            expect(Number.isFinite(value)).toBe(true);
        }
        // Flat gain at DC and Nyquist for a peaking filter.
        expect(biquadMagnitudeDb(coef, 1)).toBeCloseTo(0, 5);
        expect(biquadMagnitudeDb(coef, 20_000)).toBeCloseTo(0, 2);
        // Peak boost at the center frequency.
        expect(biquadMagnitudeDb(coef, 1000)).toBeCloseTo(6, 3);
    });

    it('flat preset yields a flat response; preamp shifts it', () => {
        const flat = getBuiltinEqPreset('flat')!;
        const response = eqFrequencyResponse(flat.bands, 0);
        for (const point of response) {
            expect(point.db).toBeCloseTo(0, 6);
        }
        const lifted = eqFrequencyResponse(flat.bands, 6);
        expect(lifted[10].db).toBeCloseTo(6, 6);
    });

    it('bass boost raises the low end relative to the high end', () => {
        const bass = getBuiltinEqPreset('bass-boost')!;
        const response = eqFrequencyResponse(bass.bands, bass.preamp);
        const at40 = response.find((p) => p.freq > 40)!.db;
        const at8k = response.find((p) => p.freq > 8_000)!.db;
        expect(at40 - at8k).toBeGreaterThan(6);
    });

    it('engine and settings views stay clamped', () => {
        const engine = eqBandsForEngine({
            bands: [{ freq: 100, gain: 99, q: 3 }],
            id: 'x',
            name: 'x',
            preamp: 0,
        });
        expect(engine[0].gain).toBe(12);
        expect(engine[0].q).toBe(3);
        const settingsView = eqBandsForSettings({
            bands: [{ freq: 100, gain: 99 }],
            id: 'x',
            name: 'x',
            preamp: 0,
        } as EqPreset);
        expect(settingsView[0]).toEqual({ freq: 100, gain: 12 });
    });
});

describe('streamify-eq store', () => {
    let storage: Map<string, string>;

    beforeEach(() => {
        storage = installMemoryLocalStorage();
        resetStreamifyEqStoreForTests();
        h.setCalls.length = 0;
        h.equalizer = {
            bands: BUILTIN_EQ_PRESETS[0].bands.map((band) => ({ ...band })),
            enabled: false,
            preamp: 0,
        };
    });

    it('loads persisted custom presets (and drops invalid blobs)', () => {
        storage.set(
            EQ_STORAGE_KEY,
            JSON.stringify({
                customPresets: [
                    { bands: [{ freq: 100, gain: 3 }], id: 'custom:mine', name: 'Mine', preamp: 1 },
                    { bands: [], id: 'custom:broken', name: 'Broken', preamp: 0 },
                ],
                selectedPresetId: 'custom:mine',
                v: 1,
            }),
        );
        useStreamifyEqStoreBase.getState().load();
        const state = useStreamifyEqStoreBase.getState();
        expect(state.customPresets).toHaveLength(1);
        expect(state.customPresets[0].id).toBe('custom:mine');
        expect(state.selectedPresetId).toBe('custom:mine');

        // Invalid version resets.
        storage.set(EQ_STORAGE_KEY, JSON.stringify({ v: 2 }));
        useStreamifyEqStoreBase.getState().load();
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(0);
    });

    it('drops a selectedPresetId that no longer resolves', () => {
        storage.set(
            EQ_STORAGE_KEY,
            JSON.stringify({ customPresets: [], selectedPresetId: 'custom:ghost', v: 1 }),
        );
        useStreamifyEqStoreBase.getState().load();
        expect(useStreamifyEqStoreBase.getState().selectedPresetId).toBeNull();
    });

    it('applies a preset: write-through to settings + selection tracked', () => {
        useStreamifyEqStoreBase.getState().applyPreset('bass-boost');
        expect(h.setCalls).toHaveLength(1);
        const patch = h.setCalls[0] as { playback: { equalizer: typeof h.equalizer } };
        expect(patch.playback.equalizer.bands).toHaveLength(10);
        expect(patch.playback.equalizer.bands[0].gain).toBe(6);
        expect(patch.playback.equalizer.preamp).toBe(-2);
        expect(useStreamifyEqStoreBase.getState().selectedPresetId).toBe('bass-boost');
    });

    it('band edits clamp, deselect the preset, and persist the manual state', () => {
        useStreamifyEqStoreBase.getState().applyPreset('flat');
        h.setCalls.length = 0;

        useStreamifyEqStoreBase.getState().setBandGain(0, 99);
        const patch = h.setCalls[0] as { playback: { equalizer: typeof h.equalizer } };
        expect(patch.playback.equalizer.bands[0].gain).toBe(12);
        expect(useStreamifyEqStoreBase.getState().selectedPresetId).toBeNull();

        useStreamifyEqStoreBase.getState().setPreamp(-50);
        const patch2 = h.setCalls[1] as { playback: { equalizer: typeof h.equalizer } };
        expect(patch2.playback.equalizer.preamp).toBe(-12);
    });

    it('saves and deletes custom presets with persistence', () => {
        useStreamifyEqStoreBase.getState().applyPreset('vocal');
        const saved = useStreamifyEqStoreBase.getState().saveCustomPreset('  My Tone  ');
        expect(saved?.id).toBe('custom:my-tone');
        expect(saved?.name).toBe('My Tone');
        expect(saved?.bands[4].gain).toBe(4); // inherited from vocal

        // Persisted for the next load.
        useStreamifyEqStoreBase.getState().load();
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(1);

        // Listed with built-ins (7 since W3-B); deletable.
        expect(selectAllEqPresets(useStreamifyEqStoreBase.getState())).toHaveLength(8);
        useStreamifyEqStoreBase.getState().deleteCustomPreset('custom:my-tone');
        expect(useStreamifyEqStoreBase.getState().customPresets).toHaveLength(0);
        expect(useStreamifyEqStoreBase.getState().selectedPresetId).toBeNull();
    });

    it('rejects empty preset names', () => {
        expect(useStreamifyEqStoreBase.getState().saveCustomPreset('   ')).toBeNull();
    });

    it('tracks the runtime bypass state', () => {
        useStreamifyEqStoreBase.getState().setBypassed(true, 'silent-output');
        expect(useStreamifyEqStoreBase.getState().bypassed).toBe(true);
        expect(useStreamifyEqStoreBase.getState().bypassReason).toBe('silent-output');
        useStreamifyEqStoreBase.getState().setBypassed(false);
        expect(useStreamifyEqStoreBase.getState().bypassReason).toBeNull();
    });
});
