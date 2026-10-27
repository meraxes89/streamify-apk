package com.streamify.app.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 4a (MP-HAL07) — the USE_RUST_AUDIO routing ladder, driven across its
 * full input matrix against the PURE decision function
 * ([AudioRoutePolicy.resolveWith] — the same one production [ ]
 * feeds with BuildConfig + edge probes).
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AudioRoutePolicyTest {

    private fun edges(
        create: Boolean = true,
        write: Boolean = true,
        f32: Boolean = true,
        flush: Boolean = true,
        destroy: Boolean = true,
        direct: Boolean = true,
        stats: Boolean = true,
    ) = RustAudioSinkJni.NativeSinkEdges(
        createSink = create,
        writeFloat = write,
        readFramesF32 = f32,
        readDirectPcm16 = direct,
        stats = stats,
        flush = flush,
        destroy = destroy,
    )

    @Test
    fun `flag off stays legacy regardless of native edges`() {
        val decision = AudioRoutePolicy.resolveWith(
            compileFlag = false,
            debugBuild = true,
            runtimeOverride = false,
            edges = edges(),
        )
        assertEquals(AudioRoutePolicy.Route.LEGACY_MEDIA3, decision.route)
        assertTrue(decision.reason.contains("flag off"))
    }

    @Test
    fun `flag on with full edges engages the dogfood pull route`() {
        val decision = AudioRoutePolicy.resolveWith(
            compileFlag = true,
            debugBuild = false,
            runtimeOverride = false,
            edges = edges(),
        )
        assertEquals(AudioRoutePolicy.Route.RUST_AUDIO_PULL, decision.route)
        assertTrue(decision.nativeEdges)
    }

    @Test
    fun `missing f32 pull edge degrades to legacy with a named reason`() {
        val decision = AudioRoutePolicy.resolveWith(
            compileFlag = true,
            debugBuild = false,
            runtimeOverride = false,
            edges = edges(f32 = false),
        )
        assertEquals(AudioRoutePolicy.Route.LEGACY_MEDIA3, decision.route)
        assertFalse(decision.nativeEdges)
        assertTrue(
            "reason should name the missing edge: ${decision.reason}",
            decision.reason.contains("f32=false"),
        )
    }

    @Test
    fun `missing create edge degrades to legacy`() {
        val decision = AudioRoutePolicy.resolveWith(
            compileFlag = true,
            debugBuild = false,
            runtimeOverride = false,
            edges = edges(create = false),
        )
        assertEquals(AudioRoutePolicy.Route.LEGACY_MEDIA3, decision.route)
        assertTrue(decision.reason.contains("create=false"))
    }

    @Test
    fun `runtime override is honored only in debug builds`() {
        // Debug + override + edges → dogfood.
        assertEquals(
            AudioRoutePolicy.Route.RUST_AUDIO_PULL,
            AudioRoutePolicy.resolveWith(
                compileFlag = false, debugBuild = true, runtimeOverride = true, edges = edges(),
            ).route,
        )
        // Release + override → IGNORED (the toggle can never smuggle the
        // dogfood path into production — MP-HAL07).
        assertEquals(
            AudioRoutePolicy.Route.LEGACY_MEDIA3,
            AudioRoutePolicy.resolveWith(
                compileFlag = false, debugBuild = false, runtimeOverride = true, edges = edges(),
            ).route,
        )
    }

    @Test
    fun `compile flag beats a debug-off build`() {
        assertEquals(
            AudioRoutePolicy.Route.RUST_AUDIO_PULL,
            AudioRoutePolicy.resolveWith(
                compileFlag = true, debugBuild = false, runtimeOverride = false, edges = edges(),
            ).route,
        )
    }

    @Test
    fun `legacy flag with absent edges reports both facts`() {
        val decision = AudioRoutePolicy.resolveWith(
            compileFlag = false,
            debugBuild = false,
            runtimeOverride = false,
            edges = RustAudioSinkJni.NO_EDGES,
        )
        assertEquals(AudioRoutePolicy.Route.LEGACY_MEDIA3, decision.route)
        assertFalse(decision.nativeEdges)
        assertFalse(decision.compileFlag)
        assertFalse(decision.runtimeOverride)
    }

    @Test
    fun `runtime override state flows without a store binding`() {
        // RustAudioSettings without initialize(context) (hermetic JVM):
        // the StateFlow default is false and setRuntimeOverride must not
        // crash on the absent SharedPreferences — the persisted arm is
        // release/debug device territory, covered by the ladder above.
        assertEquals(false, RustAudioSettings.runtimeOverride.value)
        RustAudioSettings.setRuntimeOverride(true)
        assertEquals(true, RustAudioSettings.runtimeOverride.value)
        RustAudioSettings.setRuntimeOverride(false)
        assertEquals(false, RustAudioSettings.runtimeOverride.value)
    }
}
