/**
 * features/streamify/eq-presets/preset-codec.ts — W3-B: the strict
 * share-codec for custom 10-band EQ presets.
 *
 * THE SHARE FORMAT (a frozen wire contract, ADR'd in
 * decisions/waves/W3B-wrapped-jam-eq-presets.md):
 *
 *   { "v": 1, "name": "<string>", "preampDb": <number>, "bands": [<number> × 10] }
 *
 * serialized as compact JSON, UTF-8 encoded, then wrapped in URL-safe
 * base64 (alphabet A–Z a–z 0–9 - _, no padding) — a paste-anywhere share
 * code. The decoder accepts either the base64 form or the raw JSON
 * (leading '{').
 *
 * SAFETY (the P4-B invariant, restated): every gain is clamped to
 * [-12, +12] dB and the preamp to [-12, +12] dB BEFORE the preset ever
 * exists as an EqPreset — the only shapes that reach the store, the
 * settings write-through and the DSP chain are pre-clamped, so importing
 * a hostile code can never clip audio or blow up the AudioContext (the
 * P4-B limiter stays in the chain regardless). Malformed payloads are
 * rejected with a TYPED error — never a thrown parse exception.
 */

import { base64ToBytes, bytesToBase64 } from '/@/renderer/api/streamify/playlist-sync/wire';
import {
    clampBandGain,
    clampPreamp,
    EQ_BAND_FREQS,
    type EqBand,
    type EqPreset,
} from '/@/renderer/features/streamify-eq/utils/eq-math';

export const EQ_PRESET_SHARE_VERSION = 1;
export const EQ_PRESET_NAME_MAX = 40;
export const EQ_PRESET_NAME_FALLBACK = 'Imported preset';

export type EqPresetDecodeResult =
    | { error: EqPresetShareError; ok: false }
    | { ok: true; preset: EqPreset };

export type EqPresetShareError =
    | { code: 'bad-bands' }
    | { code: 'bad-base64' }
    | { code: 'bad-json' }
    | { code: 'bad-schema' }
    | { code: 'empty' };

/** The wire payload (band gains are positional — EQ_BAND_FREQS order). */
export interface EqPresetSharePayload {
    bands: number[];
    name: string;
    preampDb: number;
    v: typeof EQ_PRESET_SHARE_VERSION;
}

// ---------------------------------------------------------------------------
// URL-safe base64 (padding-free)
// ---------------------------------------------------------------------------

export function decodeEqPresetShareCode(code: string): EqPresetDecodeResult {
    const trimmed = code.trim();
    if (trimmed === '') {
        return { error: { code: 'empty' }, ok: false };
    }

    // Liberal input: raw JSON is accepted alongside the base64 form.
    let jsonText: null | string = null;
    if (trimmed.startsWith('{')) {
        jsonText = trimmed;
    } else {
        const bytes = fromBase64Url(trimmed);
        if (bytes === null) {
            return { error: { code: 'bad-base64' }, ok: false };
        }
        try {
            jsonText = new TextDecoder().decode(bytes);
        } catch {
            return { error: { code: 'bad-base64' }, ok: false };
        }
    }

    let parsed: unknown;
    try {
        parsed = JSON.parse(jsonText);
    } catch {
        return { error: { code: 'bad-json' }, ok: false };
    }

    if (typeof parsed !== 'object' || parsed === null) {
        return { error: { code: 'bad-schema' }, ok: false };
    }
    const raw = parsed as Partial<EqPresetSharePayload>;
    if (raw.v !== EQ_PRESET_SHARE_VERSION) {
        return { error: { code: 'bad-schema' }, ok: false };
    }
    if (!Array.isArray(raw.bands) || raw.bands.length !== EQ_BAND_FREQS.length) {
        return { error: { code: 'bad-bands' }, ok: false };
    }
    const bands: EqBand[] = [];
    for (let i = 0; i < raw.bands.length; i += 1) {
        const gain = raw.bands[i];
        const value = Number(gain);
        if (typeof gain === 'boolean' || !Number.isFinite(value)) {
            return { error: { code: 'bad-schema' }, ok: false };
        }
        bands.push({ freq: EQ_BAND_FREQS[i], gain: clampBandGain(value) });
    }
    const preampDb = Number(raw.preampDb);
    if (typeof raw.preampDb === 'boolean' || !Number.isFinite(preampDb)) {
        return { error: { code: 'bad-schema' }, ok: false };
    }
    const name = sanitizeEqPresetName(raw.name);
    return {
        ok: true,
        preset: {
            bands,
            id: eqPresetSlugId(name),
            name,
            preamp: clampPreamp(preampDb),
        },
    };
}

export function encodeEqPresetShareCode(preset: EqPreset): string {
    const payload: EqPresetSharePayload = {
        bands: isoBandGains(preset.bands).map((gain) => clampBandGain(gain)),
        name: sanitizeEqPresetName(preset.name),
        preampDb: clampPreamp(preset.preamp),
        v: EQ_PRESET_SHARE_VERSION,
    };
    return toBase64Url(new TextEncoder().encode(JSON.stringify(payload)));
}

// ---------------------------------------------------------------------------
// Name sanitization + ids (shared with the store's save lane)
// ---------------------------------------------------------------------------

/** The stable custom-preset id ('custom:<slug>'; matches the store's bank). */
export function eqPresetSlugId(name: string): string {
    return `custom:${name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 40)}`;
}

/** Strip control chars, cap the length, fall back when unusable. */
export function sanitizeEqPresetName(name: unknown): string {
    if (typeof name !== 'string') return EQ_PRESET_NAME_FALLBACK;

    const cleaned = name
        // eslint-disable-next-line no-control-regex -- stripping control chars IS the point
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .trim()
        .slice(0, EQ_PRESET_NAME_MAX);
    return cleaned === '' ? EQ_PRESET_NAME_FALLBACK : cleaned;
}

// ---------------------------------------------------------------------------
// Encode (EqPreset → share code)
// ---------------------------------------------------------------------------

function fromBase64Url(code: string): null | Uint8Array {
    const base64 = code.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    return base64ToBytes(padded);
}

/**
 * Map a preset's bands onto the 10 ISO centers by nearest frequency — the
 * honest resampler for the upstream 12-band default leaking in through
 * saveCustomPreset (positions stay the 10-band contract on the wire).
 */
function isoBandGains(bands: EqBand[]): number[] {
    return EQ_BAND_FREQS.map(
        (freq) =>
            bands.reduce(
                (best, band) =>
                    Math.abs(band.freq - freq) < Math.abs(best.freq - freq) ? band : best,
                bands[0] ?? { freq, gain: 0 },
            ).gain,
    );
}

// ---------------------------------------------------------------------------
// Decode (share code → EqPreset, typed errors, never throws)
// ---------------------------------------------------------------------------

function toBase64Url(bytes: Uint8Array): string {
    return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
