package com.streamify.app.media.audio

import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUDIOPHILE DSP PREFERENCES (BEHIND.md Gaps #39 + #41, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The calibrated, serializable user-facing audio quality contract:
 *
 *   ┌──────────────────────────┬──────────────────────────────────────────┐
 *   │ Loudness normalization   │ on/off + target LUFS −23.0 … −11.0       │
 *   │                          │ (Spotify default −14; EBU R128 −23;      │
 *   │                          │  loud-party ceiling −11)                 │
 *   ├──────────────────────────┼──────────────────────────────────────────┤
 *   │ Gapless playback         │ zero-silence track transitions           │
 *   ├──────────────────────────┼──────────────────────────────────────────┤
 *   │ Crossfade                │ 0 s … 12 s constant-energy curve         │
 *   ├──────────────────────────┼──────────────────────────────────────────┤
 *   │ Mono audio               │ accessibility downmix L+R                │
 *   ├──────────────────────────┼──────────────────────────────────────────┤
 *   │ Stereo balance           │ −100 % (full left) … +100 % (full right) │
 *   ├──────────────────────────┼──────────────────────────────────────────┤
 *   │ True-peak limiter        │ −1.0 dBFS ceiling (EU volume-law         │
 *   │                          │  compliance guard)                       │
 *   └──────────────────────────┴──────────────────────────────────────────┘
 *
 * DESIGN CONTRACT:
 *  • PURE Kotlin + org.json only — the full persistence round-trip, range
 *    clamping and migration semantics are provable in JVM unit tests.
 *  • [apply] pushes the values into the LIVE audio path
 *    (StreamifyAudioProcessor / CrossfadeAudioProcessor / native DSP via
 *    NativeBridge) — one call, idempotent, safe before playback starts.
 *  • Serialization is forward-compatible: unknown keys are ignored, missing
 *    keys fall back to defaults, hostile values are clamped on read. A
 *    corrupted prefs blob can never brick the audio path.
 */
data class DspPreferences(
    val normalizeEnabled: Boolean = true,
    val targetLufs: Float = DEFAULT_TARGET_LUFS,
    val gaplessEnabled: Boolean = true,
    val crossfadeSeconds: Float = 0f,
    val monoEnabled: Boolean = false,
    val stereoBalancePercent: Int = 0,
    val truePeakLimiterEnabled: Boolean = false
) {

    // ── Derived, audio-path-ready values ───────────────────────────────────

    /** Linear gain scalar implied by the balance slider (1.0 = centred). */
    val balanceIsCentered: Boolean get() = stereoBalancePercent == 0

    /** Crossfade in the unit the audio processor consumes. */
    val crossfadeDurationMs: Long get() = (clamp(crossfadeSeconds, 0f, MAX_CROSSFADE_SECONDS) * 1000f).toLong()

    /** True when crossfade is effectively off (0 s). */
    val crossfadeActive: Boolean get() = crossfadeDurationMs > 0L

    // ── Serialization ──────────────────────────────────────────────────────

    fun toJson(): String {
        val o = JSONObject()
        o.put(KEY_NORMALIZE, normalizeEnabled)
        o.put(KEY_TARGET_LUFS, targetLufs.toDouble())
        o.put(KEY_GAPLESS, gaplessEnabled)
        o.put(KEY_CROSSFADE, crossfadeSeconds.toDouble())
        o.put(KEY_MONO, monoEnabled)
        o.put(KEY_BALANCE, stereoBalancePercent)
        o.put(KEY_LIMITER, truePeakLimiterEnabled)
        o.put(KEY_SCHEMA, SCHEMA_VERSION)
        return o.toString()
    }

    companion object {
        const val DEFAULT_TARGET_LUFS: Float = -14.0f
        const val MIN_TARGET_LUFS: Float = -23.0f
        const val MAX_TARGET_LUFS: Float = -11.0f

        const val MAX_CROSSFADE_SECONDS: Float = 12f

        /** EU volume-law ceiling (EN 50332-2 style true-peak guard). */
        const val TRUE_PEAK_CEILING_DBFS: Float = -1.0f

        private const val KEY_SCHEMA = "schema"
        private const val KEY_NORMALIZE = "normalize_enabled"
        private const val KEY_TARGET_LUFS = "target_lufs"
        private const val KEY_GAPLESS = "gapless_enabled"
        private const val KEY_CROSSFADE = "crossfade_seconds"
        private const val KEY_MONO = "mono_enabled"
        private const val KEY_BALANCE = "stereo_balance_percent"
        private const val KEY_LIMITER = "true_peak_limiter_enabled"

        private const val SCHEMA_VERSION = 1

        /**
         * Total parser: never throws, never returns null. Missing keys →
         * defaults; out-of-range values → clamped; hostile types → ignored
         * (opt* family). Corrupted blobs degrade to defaults, never to a
         * broken audio pipeline.
         */
        fun fromJson(raw: String?): DspPreferences {
            if (raw.isNullOrBlank()) return DspPreferences()
            return try {
                val o = JSONObject(raw)
                DspPreferences(
                    normalizeEnabled = o.optBoolean(KEY_NORMALIZE, true),
                    targetLufs = clamp(
                        o.optDouble(KEY_TARGET_LUFS, DEFAULT_TARGET_LUFS.toDouble()).toFloat(),
                        MIN_TARGET_LUFS, MAX_TARGET_LUFS
                    ),
                    gaplessEnabled = o.optBoolean(KEY_GAPLESS, true),
                    crossfadeSeconds = clamp(
                        o.optDouble(KEY_CROSSFADE, 0.0).toFloat(),
                        0f, MAX_CROSSFADE_SECONDS
                    ),
                    monoEnabled = o.optBoolean(KEY_MONO, false),
                    stereoBalancePercent = clamp(
                        o.optInt(KEY_BALANCE, 0),
                        -100, 100
                    ),
                    truePeakLimiterEnabled = o.optBoolean(KEY_LIMITER, false)
                )
            } catch (_: Throwable) {
                DspPreferences()
            }
        }

        /**
         * Loads from SharedPreferences (JSON blob under [PREFS_KEY]),
         * falling back to the legacy scalar crossfade pref when the blob is
         * absent — existing users keep their configured crossfade.
         */
        fun load(prefs: android.content.SharedPreferences?): DspPreferences {
            if (prefs == null) return DspPreferences()
            val blob = prefs.getString(PREFS_KEY, null)
            if (blob != null) return fromJson(blob)
            val legacyCrossfade = prefs.getFloat("crossfade_val", -1f)
            return if (legacyCrossfade >= 0f) {
                DspPreferences(crossfadeSeconds = clamp(legacyCrossfade, 0f, MAX_CROSSFADE_SECONDS))
            } else {
                DspPreferences()
            }
        }

        /** Persists as a JSON blob (caller supplies the editor's apply()). */
        fun save(prefs: android.content.SharedPreferences, value: DspPreferences) {
            prefs.edit().putString(PREFS_KEY, value.toJson()).apply()
        }

        const val PREFS_KEY = "dsp_preferences_blob"
        const val PREFS_NAME = "audio_settings"

        private fun clamp(v: Float, lo: Float, hi: Float): Float = v.coerceIn(lo, hi)
        private fun clamp(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)
    }

    // ── Live audio-path application ────────────────────────────────────────

    /**
     * Pushes every preference into the LIVE render path. Idempotent and
     * order-independent; safe to call from any thread before or during
     * playback (each consumer reads its volatile fields on the audio
     * callback, lock-free).
     *
     * The DSP chain (native via frozen JNI where implemented, Kotlin
     * processors otherwise):
     *   normalize  → StreamifyAudioProcessor pre-gain policy + native
     *                LufsNormalizer target
     *   gapless    → PredictivePreBufferManager JIT pre-hydration flag
     *   crossfade  → CrossfadeAudioProcessor.crossfadeDurationMs
     *   mono       → StreamifyAudioProcessor channel policy
     *   balance    → StreamifyAudioProcessor stereo gains
     *   limiter    → StreamifyAudioProcessor.limiterEnabled +
     *                SoftKneeLimiter ceiling at TRUE_PEAK_CEILING_DBFS
     */
    fun apply() {
        // Crossfade duration (0 = off → processors bypass the curve entirely).
        CrossfadeAudioProcessor.crossfadeDurationMs = crossfadeDurationMs

        // True-peak limiter: the soft-knee safety net at −1 dBFS.
        StreamifyAudioProcessor.limiterEnabled = truePeakLimiterEnabled
        StreamifyAudioProcessor.limiterCeilingDbfs = TRUE_PEAK_CEILING_DBFS

        // Mono downmix + stereo balance, applied in the float render path.
        StreamifyAudioProcessor.monoDownmixEnabled = monoEnabled
        StreamifyAudioProcessor.stereoBalancePercent = stereoBalancePercent

        // Loudness normalization target (LUFS) — consumed by the pre-gain /
        // native normalizer path.
        StreamifyAudioProcessor.normalizeTargetLufs = if (normalizeEnabled) targetLufs else null

        // Gapless: JIT pre-hydration continues; crossfade of 0 s means the
        // transition is a hard splice with zero inserted silence.
        StreamifyAudioProcessor.gaplessEnabled = gaplessEnabled || !crossfadeActive
    }
}
