/**
 * store/streamify-settings.store.ts - the Streamify user preferences (P4B).
 *
 * Audio & playback (codec ceiling, crossfade, gapless), network & relay
 * (custom STREAMIFY_RELAY_BASE override + reachability health), jam & privacy
 * (default room permissions, scrobble tracking), and the brand-theme toggle.
 *
 * Hand-rolled localStorage persistence ('streamify.settings.v1') so:
 *   - the module stays importable in the node test environment,
 *   - the relay knob (playback/resolver/lib/relayBase.ts) can read the
 *     override from a plain, versioned blob without importing a store,
 *   - hand-edited/legacy blobs get normalized through one validator.
 */

import { create } from 'zustand';

import { resetMetaBaseCache } from '/@/renderer/api/streamify/metadata/metaBase';
import { resetRelayBaseCache } from '/@/renderer/api/streamify/playback/resolver/lib/relayBase';

export const STREAMIFY_SETTINGS_STORAGE_KEY = 'streamify.settings.v1';

export type StreamifyCodecCeiling = 'aac' | 'auto' | 'opus';

export interface StreamifySettings {
    brandThemeEnabled: boolean;
    /** Preferred codec ceiling (forward preference; the resolver ladder
     *  ranks internally today - see the P4B ADR). */
    codecCeiling: StreamifyCodecCeiling;
    /** Crossfade duration, seconds, 0..12. */
    crossfadeDurationSec: number;
    /** Gapless transitions (vs crossfade). */
    gapless: boolean;
    /** Default permissions for rooms I host. */
    jamHostOnlySkipSeek: boolean;
    jamOpenQueue: boolean;
    /** Custom relay base override ('' = follow deployment configuration). */
    relayBaseOverride: string;
    /** Streamify scrobble tracking. */
    scrobbleEnabled: boolean;
    /** W2-B (Smart Sync): the 500 MB OPFS predictive pre-fetcher. */
    smartSyncEnabled: boolean;
    /** W2-B: the user EXPLICITLY re-enabled Smart Sync — pre-fetch even on
     * metered/save-data connections (the task's opt-in escape hatch). */
    smartSyncSaveDataOverride: boolean;
}

export const DEFAULT_STREAMIFY_SETTINGS: StreamifySettings = {
    brandThemeEnabled: true,
    codecCeiling: 'auto',
    crossfadeDurationSec: 0,
    gapless: true,
    jamHostOnlySkipSeek: false,
    jamOpenQueue: true,
    relayBaseOverride: '',
    scrobbleEnabled: true,
    smartSyncEnabled: true,
    smartSyncSaveDataOverride: false,
};

export const MAX_CROSSFADE_SEC = 12;

export interface RelayHealth {
    checkedAtMs: number;
    error: null | string;
    latencyMs: null | number;
    status: 'checking' | 'ok' | 'unreachable';
}

export interface StreamifySettingsActions {
    checkRelayHealth: (base: string) => Promise<RelayHealth>;
    load: () => StreamifySettings;
    set: (patch: Partial<StreamifySettings>) => void;
    setRelayHealth: (health: null | RelayHealth) => void;
}

export interface StreamifySettingsState {
    relayHealth: null | RelayHealth;
    settings: StreamifySettings;
}

// ---------------------------------------------------------------------------
// Pure validation (tests pin every clamp)
// ---------------------------------------------------------------------------

const CODEC_VALUES: ReadonlySet<string> = new Set(['aac', 'auto', 'opus']);

/** Clamp + default one crossfade value. */
export const clampCrossfade = (seconds: number): number =>
    Math.min(MAX_CROSSFADE_SEC, Math.max(0, Math.round(seconds)));

interface PersistedStreamifySettings {
    settings: StreamifySettings;
    v: 1;
}

/** Validate a custom relay base URL (must be an absolute http(s) URL). */
export function validateRelayBaseUrl(input: string): { ok: boolean; reason?: string } {
    const trimmed = input.trim();
    if (trimmed === '') {
        return { ok: true };
    }
    let url: URL;
    try {
        url = new URL(trimmed);
    } catch {
        return { ok: false, reason: 'invalid-url' };
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
        return { ok: false, reason: 'invalid-scheme' };
    }
    if (url.username !== '' || url.password !== '') {
        return { ok: false, reason: 'credentials-not-supported' };
    }
    return { ok: true };
}

// ---------------------------------------------------------------------------
// Persistence (plain, versioned blob)
// ---------------------------------------------------------------------------

/** Normalize untrusted persisted data into a safe settings object. */
export function validateStreamifySettings(raw: unknown): StreamifySettings {
    const source = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
    const codec = source.codecCeiling;
    const crossfade = Number(source.crossfadeDurationSec);
    const relay = source.relayBaseOverride;
    return {
        brandThemeEnabled: source.brandThemeEnabled !== false,
        codecCeiling:
            typeof codec === 'string' && CODEC_VALUES.has(codec)
                ? (codec as StreamifyCodecCeiling)
                : 'auto',
        crossfadeDurationSec: Number.isFinite(crossfade)
            ? clampCrossfade(crossfade)
            : DEFAULT_STREAMIFY_SETTINGS.crossfadeDurationSec,
        gapless: source.gapless !== false,
        jamHostOnlySkipSeek: source.jamHostOnlySkipSeek === true,
        jamOpenQueue: source.jamOpenQueue !== false,
        relayBaseOverride:
            typeof relay === 'string' && validateRelayBaseUrl(relay).ok
                ? relay.trim()
                : DEFAULT_STREAMIFY_SETTINGS.relayBaseOverride,
        scrobbleEnabled: source.scrobbleEnabled !== false,
        smartSyncEnabled: source.smartSyncEnabled !== false,
        smartSyncSaveDataOverride: source.smartSyncSaveDataOverride === true,
    };
}

const readStorage = (): null | PersistedStreamifySettings => {
    try {
        const raw = window.localStorage.getItem(STREAMIFY_SETTINGS_STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw) as Partial<PersistedStreamifySettings>;
        if (parsed.v !== 1) return null;
        return { settings: validateStreamifySettings(parsed.settings), v: 1 };
    } catch {
        return null;
    }
};

const writeStorage = (settings: StreamifySettings): void => {
    try {
        const blob: PersistedStreamifySettings = { settings, v: 1 };
        window.localStorage.setItem(STREAMIFY_SETTINGS_STORAGE_KEY, JSON.stringify(blob));
    } catch {
        // Storage unavailable - state stays in-memory.
    }
};

/**
 * The relay override as relayBase.ts reads it: from the persisted blob only,
 * never from live store state (the resolver module must not import stores).
 * Returns '' when unset/invalid.
 */
export function readPersistedRelayOverride(): string {
    const persisted = readStorage();
    const value = persisted?.settings.relayBaseOverride ?? '';
    return validateRelayBaseUrl(value).ok ? value.trim() : '';
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

export const useStreamifySettingsStoreBase = create<
    StreamifySettingsActions & StreamifySettingsState
>()((set, get) => ({
    checkRelayHealth: async (base) => {
        const trimmed = base.trim();
        if (trimmed === '' || !validateRelayBaseUrl(trimmed).ok) {
            const health: RelayHealth = {
                checkedAtMs: Date.now(),
                error: 'invalid-url',
                latencyMs: null,
                status: 'unreachable',
            };
            set({ relayHealth: health });
            return health;
        }
        set({
            relayHealth: {
                checkedAtMs: Date.now(),
                error: null,
                latencyMs: null,
                status: 'checking',
            },
        });
        const startedAt = Date.now();
        try {
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 5_000);
            try {
                // Any HTTP answer proves reachability (the relay worker
                // has no dedicated /health route; 4xx/5xx still means
                // "the worker is up").
                await fetch(trimmed, { method: 'HEAD', signal: controller.signal });
            } finally {
                clearTimeout(timeout);
            }
            const health: RelayHealth = {
                checkedAtMs: Date.now(),
                error: null,
                latencyMs: Date.now() - startedAt,
                status: 'ok',
            };
            set({ relayHealth: health });
            return health;
        } catch (error) {
            const health: RelayHealth = {
                checkedAtMs: Date.now(),
                error: error instanceof Error ? error.message : String(error),
                latencyMs: null,
                status: 'unreachable',
            };
            set({ relayHealth: health });
            return health;
        }
    },
    load: () => {
        const persisted = readStorage();
        const settings = persisted?.settings ?? { ...DEFAULT_STREAMIFY_SETTINGS };
        set({ settings });
        return settings;
    },
    relayHealth: null,
    set: (patch) => {
        const previous = get().settings.relayBaseOverride;
        const next = validateStreamifySettings({ ...get().settings, ...patch });
        set({ settings: next });
        writeStorage(next);
        if (previous !== next.relayBaseOverride) {
            // Bust the resolver's memoized relay candidate so the new
            // override applies to the next resolution. W2-A: the metadata
            // base memoizes ON TOP of the relay candidate (metaBase.ts
            // priority 2), so it busts with it.
            resetRelayBaseCache();
            resetMetaBaseCache();
        }
    },
    setRelayHealth: (relayHealth) => set({ relayHealth }),
    settings: { ...DEFAULT_STREAMIFY_SETTINGS },
}));

export const useStreamifySettingsStore = useStreamifySettingsStoreBase;

/** Test-only: reset state and drop the persisted blob. */
export const resetStreamifySettingsStoreForTests = (): void => {
    useStreamifySettingsStoreBase.setState({
        relayHealth: null,
        settings: { ...DEFAULT_STREAMIFY_SETTINGS },
    });
    try {
        window.localStorage.removeItem(STREAMIFY_SETTINGS_STORAGE_KEY);
    } catch {
        // node environment
    }
};
