package com.streamify.app.ui.components

import androidx.compose.ui.geometry.Offset
import com.streamify.app.weft.FrameCursor
import com.streamify.app.weft.PubResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUANTUM SONIC TOKEN × WEFT CONTINUOUS-STATE PLANE (producer integration)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * End-to-end contract test for the two-plane integration: the physics
 * producer publishes the 14-float dense pose through the Triad channel's
 * single atomic exchange, and a reader claims it fresh, complete, and
 * bit-identical to the producer registers.
 *
 * Runs on the pure-JVM shard: the native C++ engine is unavailable here, so
 * the controller exercises its deterministic Kotlin RK4 fallback — the same
 * code path a device takes whenever the native bridge is degraded.
 */
class QuantumTokenWeftPlaneTest {

    private fun newController(): QuantumSonicTokenController {
        val c = QuantumSonicTokenController()
        c.initMetrics(widthPx = 1080f, heightPx = 2400f, density = 3f)
        return c
    }

    @Test
    fun `triggerFlight publishes a visible opening frame at the tap origin`() {
        val c = newController()
        c.triggerFlight(
            tapOrigin = Offset(100f, 200f),
            dockDestination = Offset(900f, 2200f),
            title = "Track",
            artist = "Artist"
        )

        val claim = FrameCursor().claim(c.tokenWeft)
        assertEquals(1f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_RENDERABLE), 0f)
        assertEquals(100f, claim.buf.getFloat(QuantumSonicTokenController.OFF_X), 0.0001f)
        assertEquals(200f, claim.buf.getFloat(QuantumSonicTokenController.OFF_Y), 0.0001f)
        assertEquals(1f, claim.buf.getFloat(QuantumSonicTokenController.OFF_STRETCH_PARALLEL), 0f)
        assertEquals(1f, claim.buf.getFloat(QuantumSonicTokenController.OFF_STRETCH_PERP), 0f)
        assertEquals(0f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_DOCKED), 0f)
        assertEquals(0f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_IMPACT_BLOOM), 0f)
        assertEquals(0f, claim.buf.getFloat(QuantumSonicTokenController.OFF_FLIGHT_TIME_SEC), 0f)
    }

    @Test
    fun `stepSimulation publishes exactly one frame per step with rising seq`() {
        val c = newController()
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "Track", "Artist")

        val cursor = FrameCursor()
        val first = cursor.claim(c.tokenWeft)   // opening frame: seq 1
        assertEquals(1, first.seq)

        repeat(30) { c.stepSimulation(0.016f) }

        val claim = cursor.claim(c.tokenWeft)
        assertEquals(31, claim.seq)              // 1 opening + 30 steps
        // This cursor claimed twice but 31 frames were published: the 29
        // intermediate steps were legitimately dropped by latest-wins —
        // exactly the display semantics the Triad is designed for.
        assertEquals(29, claim.framesBehind)
    }

    @Test
    fun `dense pose in the channel is bit-identical to producer registers`() {
        val c = newController()
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "Track", "Artist")
        repeat(25) { c.stepSimulation(0.016f) }

        val claim = FrameCursor().claim(c.tokenWeft)
        val buf = claim.buf
        assertEquals(c.posX, buf.getFloat(QuantumSonicTokenController.OFF_X), 0f)
        assertEquals(c.posY, buf.getFloat(QuantumSonicTokenController.OFF_Y), 0f)
        assertEquals(c.stretchParallel, buf.getFloat(QuantumSonicTokenController.OFF_STRETCH_PARALLEL), 0f)
        assertEquals(c.stretchPerp, buf.getFloat(QuantumSonicTokenController.OFF_STRETCH_PERP), 0f)
        assertEquals(c.rotationRad, buf.getFloat(QuantumSonicTokenController.OFF_ROTATION_RAD), 0f)
        assertEquals(c.pitchDeg, buf.getFloat(QuantumSonicTokenController.OFF_PITCH_DEG), 0f)
        assertEquals(c.rollDeg, buf.getFloat(QuantumSonicTokenController.OFF_ROLL_DEG), 0f)
        assertEquals(c.impactProgress, buf.getFloat(QuantumSonicTokenController.OFF_IMPACT_PROGRESS), 0f)
    }

    @Test
    fun `flight advances the pose toward the dock destination`() {
        val c = newController()
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "Track", "Artist")

        val startX = c.posX
        val startY = c.posY
        repeat(45) { c.stepSimulation(0.016f) }

        val movedX = kotlin.math.abs(c.posX - startX)
        val movedY = kotlin.math.abs(c.posY - startY)
        assertTrue("token must travel along x, moved=$movedX", movedX > 50f)
        assertTrue("token must travel along y, moved=$movedY", movedY > 50f)
        assertEquals(TokenStage.FLYING, c.stage)
    }

    @Test
    fun `docked impact and bloom flags flow through the channel`() {
        val c = newController()
        // Short hop: the token starts inside the 60 px dock capture radius.
        c.triggerFlight(Offset(500f, 500f), Offset(500f, 520f), "Track", "Artist")
        c.onTrackReady()

        var sawDocked = false
        var sawBloom = false
        var lastVisibility = -1f
        val cursor = FrameCursor()
        for (i in 0 until 600) {
            c.stepSimulation(0.016f)
            val claim = cursor.claim(c.tokenWeft)
            if (claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_DOCKED) > 0.5f) sawDocked = true
            if (claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_IMPACT_BLOOM) > 0.5f) sawBloom = true
            lastVisibility = claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_RENDERABLE)
            if (c.stage == TokenStage.DONE) break
        }

        assertTrue("docked flag must reach the render plane", sawDocked)
        assertTrue("bloom flag must reach the render plane", sawBloom)
        assertEquals(TokenStage.DONE, c.stage)

        // The frame published by the DONE step is no longer renderable.
        // (Assert on the in-loop claim: a fresh claim with no intervening
        // publish would ping-pong back to the older held frame — the
        // latest-wins exchange semantics.)
        assertEquals(0f, lastVisibility, 0f)
    }

    @Test
    fun `impact haptic fires one frame after the impact mutation`() {
        val c = newController()
        var fired = 0
        c.impactHaptic = { fired++ }
        c.triggerFlight(Offset(500f, 500f), Offset(500f, 520f), "Track", "Artist")
        c.onTrackReady()

        var impactStep = -1
        for (i in 0 until 600) {
            c.stepSimulation(0.016f)
            if (c.stage == TokenStage.IMPACT && impactStep == -1) {
                impactStep = i
                // PERF v2 B3: the haptic must NOT land on the state-write frame.
                assertEquals(0, fired)
            }
            if (impactStep != -1 && fired > 0) break
        }

        assertTrue("impact must occur", impactStep != -1)
        assertTrue("haptic must fire on a subsequent frame", fired > 0)
    }

    @Test
    fun `reset publishes an invisible null-visibility frame`() {
        val c = newController()
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "Track", "Artist")
        c.stepSimulation(0.016f)

        c.reset()

        val claim = FrameCursor().claim(c.tokenWeft)
        assertEquals(0f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_RENDERABLE), 0f)
        assertEquals(0f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_IMPACT_BLOOM), 0f)
        assertEquals(TokenStage.IDLE, c.stage)
    }

    @Test
    fun `second flight reuses the channel with a fresh opening frame`() {
        val c = newController()
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "One", "A")
        repeat(10) { c.stepSimulation(0.016f) }
        c.reset()

        c.triggerFlight(Offset(300f, 400f), Offset(800f, 2000f), "Two", "B")
        val claim = FrameCursor().claim(c.tokenWeft)
        assertEquals(1f, claim.buf.getFloat(QuantumSonicTokenController.OFF_IS_RENDERABLE), 0f)
        assertEquals(300f, claim.buf.getFloat(QuantumSonicTokenController.OFF_X), 0.0001f)
        assertEquals(400f, claim.buf.getFloat(QuantumSonicTokenController.OFF_Y), 0.0001f)
    }

    @Test
    fun `dispose enforces the I6 revocation ordering`() {
        val c = newController()
        val weft = c.tokenWeft
        c.triggerFlight(Offset(100f, 200f), Offset(900f, 2200f), "Track", "Artist")

        c.dispose()

        assertTrue(weft.isRevoked())
        // A late producer publish after scope exit is a counted no-op —
        // never a write into a buffer nobody owns.
        assertEquals(PubResult.DROPPED_REVOKED, weft.publish(999, 56))
        assertEquals(1L, weft.tDropCount())
    }

    @Test
    fun `channel payload is sized for the dense pose vector`() {
        val c = newController()
        assertEquals(
            QuantumSonicTokenController.TOKEN_FRAME_BYTES,
            c.tokenWeft.payloadMax
        )
        assertEquals(14, QuantumSonicTokenController.TOKEN_FLOATS)
        assertEquals(56, QuantumSonicTokenController.TOKEN_FRAME_BYTES)
    }
}
