/**
 * store/streamify-eq.store.ts - the Streamify EQ state (P4B).
 *
 * Owns the PRESET layer and the runtime bypass state; the live band values
 * live in settings.playback.equalizer (the upstream schema) so the upstream
 * graph builder restores them on cold start and the upstream EQ panel stays
 * coherent with ours. Custom presets persist in localStorage
 * ('streamify.eq.v1') - hand-rolled (not zustand/persist) so the module
 * stays importable in the node test environment and relayBase-style
 * consumers can read a plain, versioned blob.
 */

import { create } from 'zustand';

import {
    BUILTIN_EQ_PRESETS,
    clampBandGain,
    clampPreamp,
    eqBandsForSettings,
    type EqPreset,
    getBuiltinEqPreset,
    normalizeEqPreset,
} from '/@/renderer/features/streamify-eq/utils/eq-math';
import { eqPresetSlugId } from '/@/renderer/features/streamify/eq-presets/preset-codec';
import { useSettingsStore } from '/@/renderer/store/settings.store';

export const EQ_STORAGE_KEY = 'streamify.eq.v1';

export interface StreamifyEqActions {
    /** Apply a preset: write-through to settings.playback.equalizer. */
    applyPreset: (id: string) => void;
    deleteCustomPreset: (id: string) => void;
    /** W3-B: add a decoded share-code preset (id-deduped) and apply it. */
    importCustomPreset: (preset: EqPreset) => EqPreset;
    load: () => void;
    renameCustomPreset: (id: string, name: string) => void;
    /** Capture the current bands/preamp as a named custom preset. */
    saveCustomPreset: (name: string) => EqPreset | null;
    /** One band slider moved: write-through (clamped) + preset deselected. */
    setBandGain: (index: number, gain: number) => void;
    setBypassed: (bypassed: boolean, reason?: null | string) => void;
    /** Mark the current band state as manual (deselects any preset). */
    setCustom: () => void;
    setGraphActive: (active: boolean) => void;
    setPreamp: (preamp: number) => void;
}

export interface StreamifyEqState {
    /** Runtime: the graph manager latched a hard bypass (CORS watchdog). */
    bypassed: boolean;
    bypassReason: null | string;
    customPresets: EqPreset[];
    /** Runtime: a rebuilt DSP chain is attached to the live context. */
    graphActive: boolean;
    selectedPresetId: null | string;
}

interface PersistedEqState {
    customPresets: EqPreset[];
    /** null = custom/manual band state; otherwise a preset id. */
    selectedPresetId: null | string;
    v: 1;
}

const persist = (state: StreamifyEqState) => {
    try {
        const blob: PersistedEqState = {
            customPresets: state.customPresets,
            selectedPresetId: state.selectedPresetId,
            v: 1,
        };
        window.localStorage.setItem(EQ_STORAGE_KEY, JSON.stringify(blob));
    } catch {
        // Storage unavailable (private mode / node) - state stays in-memory.
    }
};

const loadPersisted = (): Pick<StreamifyEqState, 'customPresets' | 'selectedPresetId'> => {
    try {
        const raw = window.localStorage.getItem(EQ_STORAGE_KEY);
        if (!raw) {
            return { customPresets: [], selectedPresetId: null };
        }
        const parsed = JSON.parse(raw) as Partial<PersistedEqState>;
        if (parsed.v !== 1) {
            return { customPresets: [], selectedPresetId: null };
        }
        const customPresets = Array.isArray(parsed.customPresets)
            ? parsed.customPresets
                  .map((raw2) => normalizeEqPreset(raw2 as never))
                  .filter((preset): preset is EqPreset => preset !== null)
            : [];
        const selected =
            typeof parsed.selectedPresetId === 'string' ? parsed.selectedPresetId : null;
        const valid =
            selected === null ||
            getBuiltinEqPreset(selected) !== null ||
            customPresets.some((preset) => preset.id === selected);
        return {
            customPresets,
            selectedPresetId: valid ? selected : null,
        };
    } catch {
        return { customPresets: [], selectedPresetId: null };
    }
};

const slugify = (name: string): string => eqPresetSlugId(name);

export const useStreamifyEqStoreBase = create<StreamifyEqActions & StreamifyEqState>()(
    (set, get) => ({
        applyPreset: (id) => {
            const preset =
                getBuiltinEqPreset(id) ??
                get().customPresets.find((candidate) => candidate.id === id) ??
                null;
            if (!preset) return;
            set({ selectedPresetId: preset.id });
            persist(get());
            writeEqualizerSettings(preset.bands, preset.preamp, true);
        },
        bypassed: false,
        bypassReason: null,
        customPresets: [],
        deleteCustomPreset: (id) => {
            set((state) => ({
                customPresets: state.customPresets.filter((preset) => preset.id !== id),
                selectedPresetId: state.selectedPresetId === id ? null : state.selectedPresetId,
            }));
            persist(get());
        },
        graphActive: false,
        importCustomPreset: (preset) => {
            // Id-dedupe against the existing bank: an imported "Rock" next
            // to a saved "Rock" becomes custom:rock-2 (never an overwrite).
            const base =
                preset.id.startsWith('custom:') && preset.id !== 'custom:'
                    ? preset.id
                    : slugify(preset.name);
            let id = base;
            let n = 2;
            while (
                get().customPresets.some((candidate) => candidate.id === id) ||
                getBuiltinEqPreset(id) !== null
            ) {
                id = `${base}-${n}`;
                n += 1;
            }
            const imported: EqPreset = {
                bands: preset.bands.map((band) => ({
                    freq: band.freq,
                    gain: clampBandGain(band.gain),
                    q: band.q,
                })),
                id,
                name: preset.name,
                preamp: clampPreamp(preset.preamp),
            };
            set((state) => ({
                customPresets: [...state.customPresets, imported],
                selectedPresetId: id,
            }));
            persist(get());
            writeEqualizerSettings(imported.bands, imported.preamp, true);
            return imported;
        },
        load: () => {
            set(loadPersisted());
        },
        renameCustomPreset: (id, name) => {
            const trimmed = name.trim();
            if (!trimmed) return;
            set((state) => ({
                customPresets: state.customPresets.map((preset) =>
                    preset.id === id ? { ...preset, name: trimmed } : preset,
                ),
            }));
            persist(get());
        },
        saveCustomPreset: (name) => {
            const trimmed = name.trim();
            if (!trimmed) return null;
            const { playback } = useSettingsStore.getState();
            const id = slugify(trimmed);
            const preset: EqPreset = {
                bands: playback.equalizer.bands.map((band) => ({
                    freq: band.freq,
                    gain: clampBandGain(band.gain),
                })),
                id,
                name: trimmed,
                preamp: clampPreamp(playback.equalizer.preamp),
            };
            if (preset.bands.length === 0) return null;
            set((state) => ({
                customPresets: [
                    ...state.customPresets.filter((candidate) => candidate.id !== id),
                    preset,
                ],
                selectedPresetId: id,
            }));
            persist(get());
            return preset;
        },
        selectedPresetId: null,
        setBandGain: (index, gain) => {
            const { playback } = useSettingsStore.getState();
            const bands = playback.equalizer.bands.map((band, i) =>
                i === index ? { freq: band.freq, gain: clampBandGain(gain) } : { ...band },
            );
            if (bands.length === 0) return;
            writeEqualizerSettings(bands, playback.equalizer.preamp, playback.equalizer.enabled);
            get().setCustom();
        },
        setBypassed: (bypassed, reason = null) => {
            set({ bypassed, bypassReason: bypassed ? reason : null });
        },
        setCustom: () => {
            if (get().selectedPresetId === null) return;
            set({ selectedPresetId: null });
            persist(get());
        },
        setGraphActive: (graphActive) => {
            set({ graphActive });
        },
        setPreamp: (preamp) => {
            const { playback } = useSettingsStore.getState();
            writeEqualizerSettings(
                playback.equalizer.bands,
                clampPreamp(preamp),
                playback.equalizer.enabled,
            );
            get().setCustom();
        },
    }),
);

/** The write-through into the upstream persistence shape. */
function writeEqualizerSettings(
    bands: Array<{ freq: number; gain: number }>,
    preamp: number,
    enabled: boolean,
): void {
    if (bands.length === 0) return;
    useSettingsStore.getState().actions.setSettings({
        playback: {
            equalizer: {
                bands: eqBandsForSettings({ bands, preamp } as EqPreset),
                enabled,
                preamp,
            },
        },
    });
}

export const useStreamifyEqStore = useStreamifyEqStoreBase;

/** Test-only: reset state + drop persisted custom presets. */
export const resetStreamifyEqStoreForTests = (): void => {
    useStreamifyEqStoreBase.setState({
        bypassed: false,
        bypassReason: null,
        customPresets: [],
        graphActive: false,
        selectedPresetId: null,
    });
    try {
        window.localStorage.removeItem(EQ_STORAGE_KEY);
    } catch {
        // node environment
    }
};

/** All selectable presets (built-ins first, then customs). */
export const selectAllEqPresets = (state: StreamifyEqState): EqPreset[] => [
    ...BUILTIN_EQ_PRESETS,
    ...state.customPresets,
];
