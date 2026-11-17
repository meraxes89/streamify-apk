/**
 * features/streamify-eq/utils/eq-math.ts - the pure DSP math behind the
 * Streamify equalizer surface (P4B).
 *
 * Everything here is standard Web Audio math, factored pure so the UI, the
 * graph manager, and the tests share one implementation:
 *
 *   - the 10-band ISO graphic-EQ layout (31 Hz .. 16 kHz) with RBJ
 *     peaking-filter coefficients (biquad transfer function evaluated on
 *     the unit circle for the frequency-response curve);
 *   - gain clamping (band +/-12 dB, preamp +/-12 dB) - the write-through
 *     into settings.playback.equalizer is clamped BEFORE persistence so a
 *     stale/hand-edited localStorage blob can never blow up the DSP chain;
 *   - the built-in preset bank (Flat / Bass Boost / Vocal Clarity /
 *     Acoustic / Electronic / Late Night / Spatial Air — W3-B adds the
 *     seventh and gives the bank display names) + custom-preset
 *     normalization.
 */

export const EQ_MAX_GAIN_DB = 12;
export const EQ_MAX_PREAMP_DB = 12;
export const EQ_DEFAULT_Q = 1.41;

/** The 10-band ISO center frequencies (Hz). */
export const EQ_BAND_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000] as const;

/** One band as the UI + presets model it. */
export interface EqBand {
    freq: number;
    gain: number;
    /** Bandwidth quality; defaults to EQ_DEFAULT_Q when omitted. */
    q?: number;
}

/** A named EQ curve (built-in or user-saved). */
export interface EqPreset {
    bands: EqBand[];
    /** Stable id ('flat' | 'bass-boost' | ... | 'custom:<slug>'). */
    id: string;
    name: string;
    preamp: number;
}

export const clampBandGain = (gain: number): number =>
    Math.min(EQ_MAX_GAIN_DB, Math.max(-EQ_MAX_GAIN_DB, gain));

export const clampPreamp = (preamp: number): number =>
    Math.min(EQ_MAX_PREAMP_DB, Math.max(-EQ_MAX_PREAMP_DB, preamp));

const preset = (id: string, name: string, gains: readonly number[], preamp: number): EqPreset => ({
    bands: EQ_BAND_FREQS.map((freq, i) => ({ freq, gain: clampBandGain(gains[i] ?? 0) })),
    id,
    name,
    preamp: clampPreamp(preamp),
});

/** The built-in preset bank (ids are stable - settings reference them). */
export const BUILTIN_EQ_PRESETS: readonly EqPreset[] = [
    preset('flat', 'Flat', [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], 0),
    preset('bass-boost', 'Bass Boost', [6, 5.5, 4.5, 2.5, 0, 0, 0, 0, 0, 0], -2),
    preset('vocal', 'Vocal Clarity', [-2, -1.5, 0, 2, 4, 4.5, 3.5, 1.5, 0, -1], -1),
    preset('acoustic', 'Acoustic', [2.5, 2, 1, 0.5, 1.5, 1.5, 2.5, 3, 2, 1.5], -1),
    preset('electronic', 'Electronic', [4.5, 4, 1.5, 0, -1.5, -2, 1, 2.5, 4, 4.5], -1.5),
    preset('late-night', 'Late Night', [-4, -3, -1, 1.5, 3, 3.5, 3, 1.5, -0.5, -3], -1.5),
    preset('spatial-air', 'Spatial Air', [-2, -1.5, -1, 0, 0, 0.5, 1, 2, 3.5, 4.5], -1),
];

export const getBuiltinEqPreset = (id: string): EqPreset | null =>
    BUILTIN_EQ_PRESETS.find((p) => p.id === id) ?? null;

export interface BiquadCoefficients {
    a0: number;
    a1: number;
    a2: number;
    b0: number;
    b1: number;
    b2: number;
}

// ---------------------------------------------------------------------------
// Biquad math (RBJ audio EQ cookbook, peaking filter)
// ---------------------------------------------------------------------------

/** |H(e^jw)| in dB for one biquad at frequency f. */
export function biquadMagnitudeDb(
    coef: BiquadCoefficients,
    f: number,
    sampleRate = 48_000,
): number {
    const w = (2 * Math.PI * f) / sampleRate;
    const sinW = Math.sin(w);
    // z^-1 = e^-jw; evaluate the two quadratics as complex numbers.
    const re = (a: number, b: number, c: number) => a + b * Math.cos(w) + c * Math.cos(2 * w);
    const im = (_a: number, b: number, c: number) => -(b * sinW + c * Math.sin(2 * w));
    const numRe = re(coef.b0, coef.b1, coef.b2);
    const numIm = im(coef.b0, coef.b1, coef.b2);
    const denRe = re(coef.a0, coef.a1, coef.a2);
    const denIm = im(coef.a0, coef.a1, coef.a2);
    const num = Math.hypot(numRe, numIm);
    const den = Math.hypot(denRe, denIm);
    if (den === 0 || !Number.isFinite(num) || !Number.isFinite(den)) {
        return 0;
    }
    return 20 * Math.log10(num / den);
}

/**
 * The graph-manager view of a preset: per-band (freq, gain, q) with the
 * clamps applied - the only shape that ever reaches a BiquadFilterNode.
 */
export function eqBandsForEngine(preset: EqPreset | null): EqBand[] {
    if (!preset) {
        return EQ_BAND_FREQS.map((freq) => ({ freq, gain: 0 }));
    }
    return preset.bands.map((band) => ({
        freq: band.freq,
        gain: clampBandGain(band.gain),
        q: band.q ?? EQ_DEFAULT_Q,
    }));
}

/**
 * The settings write-through view: settings.playback.equalizer stores
 * {freq, gain} per band (the upstream EqSettingsSchema shape), so the
 * upstream graph builder and the upstream EQ settings panel stay coherent
 * with ours.
 */
export function eqBandsForSettings(preset: EqPreset | null): Array<{ freq: number; gain: number }> {
    return eqBandsForEngine(preset).map(({ freq, gain }) => ({ freq, gain }));
}

/**
 * The total frequency response (dB) across bands + preamp, sampled at
 * `points` log-spaced frequencies in [fMin, fMax].
 */
export function eqFrequencyResponse(
    bands: EqBand[],
    preamp: number,
    options: { fMax?: number; fMin?: number; points?: number; sampleRate?: number } = {},
): Array<{ db: number; freq: number }> {
    const { fMax = 20_000, fMin = 20, points = 96, sampleRate = 48_000 } = options;
    const coefs = bands.map((band) =>
        peakingCoefficients(band.freq, band.q ?? EQ_DEFAULT_Q, band.gain, sampleRate),
    );
    const out: Array<{ db: number; freq: number }> = [];
    for (let i = 0; i < points; i += 1) {
        const freq = fMin * Math.pow(fMax / fMin, i / (points - 1));
        let db = clampPreamp(preamp);
        for (const coef of coefs) {
            db += biquadMagnitudeDb(coef, freq, sampleRate);
        }
        out.push({ db: Number.isFinite(db) ? db : 0, freq });
    }
    return out;
}

/** Normalize arbitrary (untrusted) preset data into a safe preset. */
export function normalizeEqPreset(raw: {
    bands?: Array<{ freq?: unknown; gain?: unknown; q?: unknown }>;
    id?: unknown;
    name?: unknown;
    preamp?: unknown;
}): EqPreset | null {
    if (typeof raw.id !== 'string' || raw.id === '') {
        return null;
    }
    if (!Array.isArray(raw.bands) || raw.bands.length === 0) {
        return null;
    }
    const bands: EqBand[] = [];
    for (const band of raw.bands) {
        const freq = Number(band?.freq);
        const gain = Number(band?.gain);
        if (!Number.isFinite(freq) || freq <= 0 || !Number.isFinite(gain)) {
            return null;
        }
        const q = Number(band?.q);
        bands.push({
            freq,
            gain: clampBandGain(gain),
            q: Number.isFinite(q) && q > 0 ? q : undefined,
        });
    }
    const preamp = Number(raw.preamp);
    return {
        bands,
        id: raw.id,
        name: typeof raw.name === 'string' && raw.name !== '' ? raw.name : raw.id,
        preamp: clampPreamp(Number.isFinite(preamp) ? preamp : 0),
    };
}

/** Peaking-EQ coefficients at f0/Q/gainDb for a given sample rate. */
export function peakingCoefficients(
    f0: number,
    q: number,
    gainDb: number,
    sampleRate = 48_000,
): BiquadCoefficients {
    const A = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * Math.min(f0, sampleRate / 2)) / sampleRate;
    const cosW0 = Math.cos(w0);
    const alpha = Math.sin(w0) / (2 * Math.max(q, 0.01));
    return {
        a0: 1 + alpha / A,
        a1: -2 * cosW0,
        a2: 1 - alpha / A,
        b0: 1 + alpha * A,
        b1: -2 * cosW0,
        b2: 1 - alpha * A,
    };
}
