package com.streamify.app.media.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 (Gaps #39 & #41) — DspPreferences serialization contract.
 *
 * The audiophile settings page persists as one JSON blob. These tests pin:
 *
 *   1. Round-trip fidelity — every one of the seven knobs survives
 *      toJson → fromJson byte-for-byte.
 *   2. The calibration anchors — default target is exactly −14.0 LUFS,
 *      the slider range is −23…−11, crossfade caps at 12 s, the true-peak
 *      ceiling is −1.0 dBFS (EU volume-law guard).
 *   3. Total-parser hardening — corrupt blobs, hostile types, out-of-range
 *      values: never an exception, never a broken audio pipeline.
 */
class DspPreferencesTest {

    // ── Calibration anchors ────────────────────────────────────────────────

    @Test
    fun `default target loudness is minus 14 LUFS`() {
        assertEquals(-14.0f, DspPreferences.DEFAULT_TARGET_LUFS)
        assertEquals(-14.0f, DspPreferences().targetLufs)
    }

    @Test
    fun `target loudness slider bounds are minus 23 to minus 11 LUFS`() {
        assertEquals(-23.0f, DspPreferences.MIN_TARGET_LUFS)
        assertEquals(-11.0f, DspPreferences.MAX_TARGET_LUFS)
        assertTrue(DspPreferences.MIN_TARGET_LUFS < DspPreferences.DEFAULT_TARGET_LUFS)
        assertTrue(DspPreferences.DEFAULT_TARGET_LUFS < DspPreferences.MAX_TARGET_LUFS)
    }

    @Test
    fun `crossfade caps at twelve seconds`() {
        assertEquals(12f, DspPreferences.MAX_CROSSFADE_SECONDS)
    }

    @Test
    fun `true peak ceiling is minus 1 dBFS`() {
        assertEquals(-1.0f, DspPreferences.TRUE_PEAK_CEILING_DBFS)
    }

    @Test
    fun `factory defaults match the audiophile contract`() {
        val p = DspPreferences()
        assertTrue(p.normalizeEnabled)       // loudness on by default
        assertTrue(p.gaplessEnabled)         // zero-silence transitions on
        assertFalse(p.crossfadeActive)       // crossfade off by default
        assertFalse(p.monoEnabled)
        assertTrue(p.balanceIsCentered)
        assertFalse(p.truePeakLimiterEnabled)
    }

    // ── Round-trip fidelity ────────────────────────────────────────────────

    @Test
    fun `every knob survives a json round trip`() {
        val original = DspPreferences(
            normalizeEnabled = false,
            targetLufs = -19.5f,
            gaplessEnabled = false,
            crossfadeSeconds = 7.5f,
            monoEnabled = true,
            stereoBalancePercent = -42,
            truePeakLimiterEnabled = true
        )
        val restored = DspPreferences.fromJson(original.toJson())

        assertEquals(original.normalizeEnabled, restored.normalizeEnabled)
        assertEquals(original.targetLufs, restored.targetLufs)
        assertEquals(original.gaplessEnabled, restored.gaplessEnabled)
        assertEquals(original.crossfadeSeconds, restored.crossfadeSeconds)
        assertEquals(original.monoEnabled, restored.monoEnabled)
        assertEquals(original.stereoBalancePercent, restored.stereoBalancePercent)
        assertEquals(original.truePeakLimiterEnabled, restored.truePeakLimiterEnabled)
        assertEquals(original, restored)
    }

    @Test
    fun `default preferences round trip to defaults`() {
        assertEquals(DspPreferences(), DspPreferences.fromJson(DspPreferences().toJson()))
    }

    @Test
    fun `blob carries schema version one`() {
        val o = JSONObject(DspPreferences().toJson())
        assertEquals(1, o.getInt("schema"))
    }

    // ── Total-parser hardening ─────────────────────────────────────────────

    @Test
    fun `null and blank blobs degrade to defaults`() {
        assertEquals(DspPreferences(), DspPreferences.fromJson(null))
        assertEquals(DspPreferences(), DspPreferences.fromJson(""))
        assertEquals(DspPreferences(), DspPreferences.fromJson("   "))
    }

    @Test
    fun `corrupt blobs degrade to defaults without throwing`() {
        assertEquals(DspPreferences(), DspPreferences.fromJson("{"))
        assertEquals(DspPreferences(), DspPreferences.fromJson("not json at all"))
        assertEquals(DspPreferences(), DspPreferences.fromJson("{\"schema\":"))
        assertEquals(DspPreferences(), DspPreferences.fromJson("[1,2,3]"))
    }

    @Test
    fun `missing keys fall back to defaults`() {
        val restored = DspPreferences.fromJson("{}")
        assertEquals(DspPreferences(), restored)
    }

    @Test
    fun `out-of-range values are clamped into the legal bands`() {
        val hot = JSONObject().apply {
            put("target_lufs", -40.0)          // below −23
            put("crossfade_seconds", 99.0)     // above 12
            put("stereo_balance_percent", 250) // above +100
        }.toString()
        assertEquals(-23.0f, DspPreferences.fromJson(hot).targetLufs)
        assertEquals(12f, DspPreferences.fromJson(hot).crossfadeSeconds)
        assertEquals(100, DspPreferences.fromJson(hot).stereoBalancePercent)

        val cold = JSONObject().apply {
            put("target_lufs", 0.0)               // above −11
            put("crossfade_seconds", -5.0)        // below 0
            put("stereo_balance_percent", -250)   // below −100
        }.toString()
        assertEquals(-11.0f, DspPreferences.fromJson(cold).targetLufs)
        assertEquals(0f, DspPreferences.fromJson(cold).crossfadeSeconds)
        assertEquals(-100, DspPreferences.fromJson(cold).stereoBalancePercent)
    }

    @Test
    fun `hostile types are ignored in favour of defaults`() {
        val hostile = JSONObject().apply {
            put("normalize_enabled", "yes")       // string where bool expected
            put("target_lufs", "loud")            // string where number expected
            put("mono_enabled", 1)                // number where bool expected
            put("stereo_balance_percent", "left") // string where int expected
        }.toString()
        val p = DspPreferences.fromJson(hostile)
        // opt* family: malformed entries are skipped, defaults survive.
        assertTrue(p.normalizeEnabled)
        assertEquals(DspPreferences.DEFAULT_TARGET_LUFS, p.targetLufs)
        assertFalse(p.monoEnabled)
        assertEquals(0, p.stereoBalancePercent)
    }

    // ── Derived playback semantics ─────────────────────────────────────────

    @Test
    fun `crossfade duration derives milliseconds and active flag`() {
        val off = DspPreferences(crossfadeSeconds = 0f)
        assertEquals(0L, off.crossfadeDurationMs)
        assertFalse(off.crossfadeActive)

        val on = DspPreferences(crossfadeSeconds = 6.5f)
        assertEquals(6500L, on.crossfadeDurationMs)
        assertTrue(on.crossfadeActive)
    }

    @Test
    fun `balance centred only at exact zero`() {
        assertTrue(DspPreferences(stereoBalancePercent = 0).balanceIsCentered)
        assertFalse(DspPreferences(stereoBalancePercent = 1).balanceIsCentered)
        assertFalse(DspPreferences(stereoBalancePercent = -1).balanceIsCentered)
    }

    // ── Live render-path application (frozen JNI contract) ─────────────────

    @Test
    fun `apply pushes every knob into the live processor state`() {
        val p = DspPreferences(
            normalizeEnabled = true,
            targetLufs = -16f,
            gaplessEnabled = true,
            crossfadeSeconds = 4f,
            monoEnabled = true,
            stereoBalancePercent = -25,
            truePeakLimiterEnabled = true
        )
        p.apply()

        assertEquals(-16f, StreamifyAudioProcessor.normalizeTargetLufs)
        assertTrue(StreamifyAudioProcessor.limiterEnabled)
        assertEquals(DspPreferences.TRUE_PEAK_CEILING_DBFS, StreamifyAudioProcessor.limiterCeilingDbfs)
        assertEquals(true, StreamifyAudioProcessor.monoDownmixEnabled)
        assertEquals(-25, StreamifyAudioProcessor.stereoBalancePercent)
        assertTrue(StreamifyAudioProcessor.gaplessEnabled)
        assertEquals(4000L, CrossfadeAudioProcessor.crossfadeDurationMs)

        // Restore a neutral state so other tests are unaffected. NOTE: the
        // factory default keeps loudness normalization ON at −14 LUFS — the
        // neutral target is the default value, not null.
        DspPreferences().apply()
        assertEquals(DspPreferences.DEFAULT_TARGET_LUFS, StreamifyAudioProcessor.normalizeTargetLufs)
        assertFalse(StreamifyAudioProcessor.limiterEnabled)
        assertEquals(DspPreferences.TRUE_PEAK_CEILING_DBFS, StreamifyAudioProcessor.limiterCeilingDbfs)
        assertFalse(StreamifyAudioProcessor.monoDownmixEnabled)
        assertEquals(0, StreamifyAudioProcessor.stereoBalancePercent)
        assertTrue(StreamifyAudioProcessor.gaplessEnabled)
        assertEquals(0L, CrossfadeAudioProcessor.crossfadeDurationMs)
    }

    @Test
    fun `disabled normalization clears the lufs target`() {
        DspPreferences(normalizeEnabled = false, targetLufs = -14f).apply()
        assertNull(StreamifyAudioProcessor.normalizeTargetLufs)
        DspPreferences().apply()
    }

    @Test
    fun `crossfade replaces the gapless splice while zero-crossfade keeps it`() {
        // Semantics of DspPreferences.apply(): gaplessEnabled is derived as
        // `gaplessEnabled || crossfade inactive` — an ACTIVE crossfade
        // replaces the zero-silence splice, so an explicit gapless-off with
        // crossfade > 0 s reports gapless OFF.
        DspPreferences(gaplessEnabled = false, crossfadeSeconds = 3f).apply()
        assertFalse(StreamifyAudioProcessor.gaplessEnabled)

        // Crossfade at 0 s with gapless off: the transition is a hard splice
        // with zero inserted silence — the gapless contract still holds.
        DspPreferences(gaplessEnabled = false, crossfadeSeconds = 0f).apply()
        assertTrue(StreamifyAudioProcessor.gaplessEnabled)

        DspPreferences().apply()
    }
}
