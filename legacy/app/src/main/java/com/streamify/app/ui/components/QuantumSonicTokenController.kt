package com.streamify.app.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.streamify.app.weft.Steward
import com.streamify.app.weft.Weft
import com.streamify.app.weft.compose.ReattachPolicy
import com.streamify.app.weft.compose.WeftHeddle
import kotlinx.coroutines.launch
import kotlin.math.*

val LocalQuantumController = staticCompositionLocalOf { QuantumSonicTokenController() }
val LocalDockPosition = staticCompositionLocalOf<MutableState<Offset>> { mutableStateOf(Offset.Zero) }

enum class TokenStage { IDLE, FLYING, IMPACT, DONE }

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUANTUM SONIC TOKEN CONTROLLER — WEFT CONTINUOUS-STATE PLANE (PRODUCER)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Two-Plane Architecture (Engineering Directive: Real-Device Integration of
 * the Weft Continuous-State Plane):
 *
 *  • Plane 1 (Reactive, < 10 Hz): stage, track metadata, dock readiness —
 *    classic Compose snapshot state, mutated only at discrete flight events.
 *
 *  • Plane 2 (Continuous, 60–120 Hz): the 14-float physics pose, published
 *    every simulation tick into a Weft Triad channel via a single
 *    AtomicReference.getAndSet exchange. The 120 Hz hot state NEVER touches
 *    the Compose snapshot system: the render thread claims the freshest
 *    complete frame in the draw phase (wait-free, < 10 ns) and reads it as
 *    a zero-copy little-endian payload slice.
 *
 *    [frameTick] remains the ONLY reactive seam in the hot path — a single
 *    Long state incremented once per completed simulation step and read
 *    EXCLUSIVELY inside draw/graphicsLayer scopes (a draw-phase read
 *    invalidates drawing only: zero recompositions, zero re-measure,
 *    zero re-layout). The 14 floats of pose data themselves ride the Weft.
 *
 * Hot-path allocation audit (Android Studio Profiler → zero-GC target):
 *  • Producer: reuses the preallocated [physicsBuffer] FloatArray, the
 *    channel's three preallocated ByteBuffers, and one wBegin() cursor view
 *    per publish — 0 KB/s sustained during active flight.
 *  • Consumer (see QuantumSonicTokenOverlay): all brushes, shapes, paints,
 *    strokes and text are cached/remembered OUTSIDE the draw loop; text
 *    measurement happens once per content change in the Compose layout
 *    pass (cached TextLayoutResult), never via Paint.measureText() in draw.
 */
class QuantumSonicTokenController {

    // ── PLANE 2: THE WEFT TRID CHANNEL ────────────────────────────────────
    //
    // Dense pose frame — 14 floats, 56 bytes, little-endian. This is the
    // single source of truth for the 60–120 Hz render path:
    //
    //   float  0  x px            byte  0
    //   float  1  y px            byte  4
    //   float  2  stretchParallel byte  8
    //   float  3  stretchPerp     byte 12
    //   float  4  rotationRad     byte 16
    //   float  5  pitchDeg        byte 20
    //   float  6  rollDeg         byte 24
    //   float  7  impactProgress  byte 28
    //   float  8  isDocked        byte 32
    //   float  9  isReadyToDock   byte 36
    //   float 10  isRenderable    byte 40
    //   float 11  isImpactBloom   byte 44
    //   float 12  flightTimeSec   byte 48
    //   float 13  reserved        byte 52

    /** ViewModel-scoped lifecycle owner for the channel (I6 revoke ordering). */
    private val steward = Steward()

    /** The Triad channel: three buffers + one single-atomic exchange. */
    val tokenWeft: Weft = steward.weftSized(TOKEN_FRAME_BYTES)

    /** Compose binding for the channel (PRESERVE_HELD: survives navigation). */
    val tokenHeddle = WeftHeddle(steward, tokenWeft, ReattachPolicy.PRESERVE_HELD)

    private var publishSeq = 0

    // ── PLANE 1: COLD REACTIVE STATE (< 10 Hz) ─────────────────────────────

    var stage by mutableStateOf(TokenStage.IDLE)
        private set

    // Track metadata (updated once per flight)
    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackArt by mutableStateOf<String?>(null)
        private set
    var telemetryStatus by mutableStateOf("Connecting to Streamify...")
        private set

    /** True whenever the dock UI may compose/enter — gated OFF during flight
     *  so MiniPlayerBar's entrance never collides with impact bloom frames. */
    var dockReadyForUI by mutableStateOf(true)
        private set

    /** Pre-decoded flight artwork (Coil, software config for canvas draw).
     *  Plane-1 swap: written once when decode completes → the overlay's
     *  Image node recomposes exactly once (cold event, off the draw path). */
    var artBitmap by mutableStateOf<Bitmap?>(null)
        private set

    @Volatile private var artBitmapKey: String? = null

    /** Returns and clears the cached artwork when it belongs to [key]. */
    fun consumeArtBitmapIfMatched(key: String?): Bitmap? = synchronized(this) {
        if (key != null && key == artBitmapKey) {
            val b = artBitmap
            artBitmap = null
            b
        } else null
    }

    // ── SHARED METRICS (written once per flight — read by layout & draw) ───

    var cardWidthPx by mutableFloatStateOf(320f)
        private set
    var cardHeightPx by mutableFloatStateOf(160f)
        private set
    var screenWidthPx by mutableFloatStateOf(1080f)
        private set
    var enable3D: Boolean = true
        private set

    private var screenDensity: Float = 3f

    // ── PRODUCER-SIDE RAW PHYSICS REGISTERS ────────────────────────────────
    //
    // Raw High-Performance Primitive Registers (Zero Recomposition Overhead).
    // These plain floats mirror the published Weft frame; they exist for
    // cold-path readers (impact decisioning) and stay OFF the snapshot system.

    var posX: Float = 0f
        private set
    var posY: Float = 0f
        private set
    var stretchParallel: Float = 1f
        private set
    var stretchPerp: Float = 1f
        private set
    var rotationRad: Float = 0f
        private set
    var pitchDeg: Float = 0f
        private set
    var rollDeg: Float = 0f
        private set
    var impactProgress: Float = 0f
        private set

    /** Origin & destination coordinates (written once per flight). */
    var origin = Offset.Zero
        private set
    var destination = Offset.Zero
        private set
    var initialDistance: Float = 1f
        private set

    private var flightTime: Float = 0f

    // ── DRAW-PHASE INVALIDATION CLOCK ──────────────────────────────────────
    //
    // The single reactive seam of the continuous plane. Read ONLY inside
    // draw/graphicsLayer scopes → each increment invalidates drawing alone
    // (no recomposition, no measure, no layout — the deferred-read pattern).
    var frameTick by mutableLongStateOf(0L)
        private set

    // Direct 14-Float Zero-Allocation JNI Buffer (ABI-frozen with the C++ core)
    // 0: x, 1: y, 2: z, 3: vx, 4: vy, 5: vz, 6: stretch_parallel, 7: stretch_perp,
    // 8: rotation_rad, 9: pitch_deg, 10: roll_deg, 11: impact_progress,
    // 12: is_docked, 13: is_ready_to_dock
    private val physicsBuffer = FloatArray(14)

    // Adaptive Fluid Splashing Particles: Scaled dynamically based on
    // hardware capabilities (Plan 25).
    //
    // Deliberately NOT routed through a Weft channel: particles are
    // stateful integrators (positions accumulate over time), which is
    // wrong-fit for latest-wins display semantics — the Triad drops
    // intermediate frames by design. A preallocated pooled FloatArray with
    // single-threaded producer+consumer access delivers the same zero-GC
    // guarantee without misapplying the protocol.
    val particleCount: Int = when {
        Runtime.getRuntime().availableProcessors() >= 8 -> 64
        Runtime.getRuntime().availableProcessors() >= 6 -> 48
        else -> 32
    }
    val particleBuffer = FloatArray(64 * 6)
    private var particlesSpawned = false

    // ── HAPTIC SEAM ────────────────────────────────────────────────────────
    //
    // Pure-Kotlin seam (no android.os.Handler — keeps the controller
    // JVM-unit-testable). The UI host injects the vibrator call; it fires
    // one full frame AFTER the impact state mutation so the haptic never
    // lands on the state-write frame (PERF v2 B3 discipline).
    var impactHaptic: (() -> Unit)? = null
    private var pendingImpactHaptic = false

    val isRenderable: Boolean
        get() = stage == TokenStage.FLYING || stage == TokenStage.IMPACT

    /** Captured once from Activity metrics (replaces BoxWithConstraints). */
    fun initMetrics(widthPx: Float, heightPx: Float, density: Float) {
        screenWidthPx = widthPx
        screenDensity = density
        enable3D = Runtime.getRuntime().availableProcessors() >= 6
    }

    fun triggerFlight(
        tapOrigin: Offset,
        dockDestination: Offset,
        title: String,
        artist: String = "",
        art: String? = null
    ) {
        origin = tapOrigin
        destination = dockDestination
        posX = tapOrigin.x
        posY = tapOrigin.y
        trackTitle = title
        trackArtist = artist
        trackArt = art
        flightTime = 0f
        telemetryStatus = "Connecting to Streamify..."
        particlesSpawned = false

        val dx = dockDestination.x - tapOrigin.x
        val dy = dockDestination.y - tapOrigin.y
        initialDistance = max(1f, sqrt(dx * dx + dy * dy))

        // Initial launch velocity slightly upward and outward
        physicsBuffer[0] = tapOrigin.x
        physicsBuffer[1] = tapOrigin.y
        physicsBuffer[2] = 0f
        physicsBuffer[3] = 0f
        physicsBuffer[4] = -80f // Gentle initial upward pop
        physicsBuffer[5] = 0f
        physicsBuffer[6] = 1f // stretch_parallel
        physicsBuffer[7] = 1f // stretch_perp
        physicsBuffer[8] = 0f // rotation_rad
        physicsBuffer[9] = 0f // pitch_deg
        physicsBuffer[10] = 0f // roll_deg
        physicsBuffer[11] = 0f // impact_progress
        physicsBuffer[12] = 0f // is_docked
        physicsBuffer[13] = 0f // is_ready_to_dock

        stretchParallel = 1f
        stretchPerp = 1f
        rotationRad = 0f
        pitchDeg = 0f
        rollDeg = 0f
        impactProgress = 0f

        // PERF v2: metrics baked ONCE here (no BoxWithConstraints, no
        // AsyncImage cold-start inside the animation envelope).
        cardHeightPx = 60f * screenDensity
        cardWidthPx = ((screenWidthPx * 0.88f)).coerceIn(280f * screenDensity, 560f * screenDensity)

        dockReadyForUI = false
        artBitmap = null
        artBitmapKey = art
        decodeArtAsync(art)

        telemetryStatus = "Connecting to Streamify..."
        stage = TokenStage.FLYING

        // Publish the opening frame so the very first draw pass of the
        // flight renders from channel state (no one-frame-stale pose).
        publishTokenFrame()
        frameTick++
    }

    private fun decodeArtAsync(artUrl: String?) {
        if (artUrl.isNullOrBlank()) return
        val ctx = com.streamify.app.data.repository.TrackRepository.appContext ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val request = coil.request.ImageRequest.Builder(ctx)
                    .data(artUrl)
                    .size(92)
                    .allowHardware(false)   // must survive native-canvas draw
                    .build()
                val result = coil.Coil.imageLoader(ctx).execute(request)
                val bmp = ((result as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bmp != null && artBitmapKey == artUrl) {
                    artBitmap = bmp
                    frameTick++   // swap placeholder ring → bitmap in draw phase
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Called when the audio stream resolution completes and the track begins playing.
     * Triggers the final impact touchdown and bloom animation.
     */
    fun onTrackReady() {
        physicsBuffer[13] = 1f
        telemetryStatus = "Coupling audio pipeline..."
        if (stage == TokenStage.FLYING) {
            val dx = destination.x - posX
            val dy = destination.y - posY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 60f) {
                physicsBuffer[12] = 1f
                physicsBuffer[11] = 0f
            }
        }
    }

    /**
     * Advances simulation by dt seconds (RK4 integration).
     * Dispatches directly to the native C++ engine when loaded, with
     * automatic fallback to the pure-Kotlin RK4 mirror; then PUBLISHES the
     * resulting pose into the Weft Triad channel (the single atomic
     * exchange) for the render thread.
     */
    fun stepSimulation(dt: Float) {
        if (stage == TokenStage.IDLE || stage == TokenStage.DONE) return

        // Haptic fires on the frame FOLLOWING the impact state mutation —
        // never on the state-write frame itself (PERF v2 B3).
        if (pendingImpactHaptic) {
            pendingImpactHaptic = false
            impactHaptic?.invoke()
        }

        val safeDt = dt.coerceIn(0.001f, 0.033f)
        flightTime += safeDt
        // PERF v2 B4: status text is frozen during flight — its mutations used
        // to recompose the card subtree inside the critical early frames.

        try {
            com.streamify.app.data.NativeBridge.stepAirDropPhysics(
                inOutBuffer = physicsBuffer,
                targetX = destination.x,
                targetY = destination.y,
                initialDist = initialDistance,
                dt = safeDt
            )
        } catch (e: Throwable) {
            // Pure Kotlin fallback simulator matching exact C++ RK4 algorithm
            // (also the deterministic path on the JVM unit-test shard, where
            // no native library is loadable).
            stepKotlinRK4(safeDt)
        }

        posX = physicsBuffer[0]
        posY = physicsBuffer[1]
        stretchParallel = physicsBuffer[6]
        stretchPerp = physicsBuffer[7]
        rotationRad = physicsBuffer[8]
        pitchDeg = physicsBuffer[9]
        rollDeg = physicsBuffer[10]
        impactProgress = physicsBuffer[11]

        val isDocked = physicsBuffer[12] > 0.5f
        if (isDocked && stage == TokenStage.FLYING) {
            stage = TokenStage.IMPACT
            telemetryStatus = "Coupled • Ready"
            spawnFluidParticles()
            pendingImpactHaptic = impactHaptic != null
        }

        // B1: dock UI (MiniPlayerBar enter) waits until bloom tail frames.
        if (!dockReadyForUI &&
            (stage == TokenStage.DONE ||
             (stage == TokenStage.IMPACT && impactProgress >= 0.35f))) {
            dockReadyForUI = true
        }

        if (stage == TokenStage.IMPACT) {
            updateFluidParticles(safeDt)
            if (impactProgress >= 1f) {
                stage = TokenStage.DONE
            }
        }

        // ══ THE PUBLISH: pose crosses to the render plane here ══
        publishTokenFrame()
        frameTick++
    }

    /**
     * Writes the dense pose vector into the writer's working buffer and
     * performs the single atomic exchange. Zero payload allocations: the
     * cursor is a zero-copy view over the channel's preallocated buffer.
     */
    private fun publishTokenFrame() {
        val cursor = tokenWeft.wBegin()
        cursor.putFloat(OFF_X, posX)
        cursor.putFloat(OFF_Y, posY)
        cursor.putFloat(OFF_STRETCH_PARALLEL, stretchParallel)
        cursor.putFloat(OFF_STRETCH_PERP, stretchPerp)
        cursor.putFloat(OFF_ROTATION_RAD, rotationRad)
        cursor.putFloat(OFF_PITCH_DEG, pitchDeg)
        cursor.putFloat(OFF_ROLL_DEG, rollDeg)
        cursor.putFloat(OFF_IMPACT_PROGRESS, impactProgress)
        cursor.putFloat(OFF_IS_DOCKED, if (physicsBuffer[12] > 0.5f) 1f else 0f)
        cursor.putFloat(OFF_IS_READY_TO_DOCK, if (physicsBuffer[13] > 0.5f) 1f else 0f)
        cursor.putFloat(OFF_IS_RENDERABLE, if (isRenderable) 1f else 0f)
        cursor.putFloat(OFF_IS_IMPACT_BLOOM, if (stage == TokenStage.IMPACT) 1f else 0f)
        cursor.putFloat(OFF_FLIGHT_TIME_SEC, flightTime)
        cursor.putFloat(OFF_RESERVED, 0f)
        tokenWeft.publish(++publishSeq, TOKEN_FRAME_BYTES)
    }

    private fun spawnFluidParticles() {
        if (particlesSpawned) return
        particlesSpawned = true
        val rand = java.util.Random(System.currentTimeMillis())
        for (i in 0 until particleCount) {
            val base = i * 6
            val angle = (rand.nextFloat() * 2f * PI.toFloat())
            val speed = 120f + rand.nextFloat() * 380f
            particleBuffer[base + 0] = destination.x + (rand.nextFloat() - 0.5f) * 40f // x
            particleBuffer[base + 1] = destination.y + (rand.nextFloat() - 0.5f) * 15f // y
            particleBuffer[base + 2] = cos(angle) * speed // vx
            particleBuffer[base + 3] = sin(angle) * speed * 0.5f - 80f // vy (slight upward boost)
            particleBuffer[base + 4] = 2.5f + rand.nextFloat() * 4.5f // radius
            particleBuffer[base + 5] = 0.95f // alpha
        }
    }

    private fun updateFluidParticles(dt: Float) {
        val gravity = 320f
        for (i in 0 until particleCount) {
            val base = i * 6
            particleBuffer[base + 0] += particleBuffer[base + 2] * dt
            particleBuffer[base + 1] += particleBuffer[base + 3] * dt + 0.5f * gravity * dt * dt
            particleBuffer[base + 3] += gravity * dt
            particleBuffer[base + 5] = (particleBuffer[base + 5] - dt * 2.8f).coerceAtLeast(0f)
        }
    }

    private fun stepKotlinRK4(dt: Float) {
        if (physicsBuffer[12] > 0.5f) {
            if (physicsBuffer[11] < 1f) {
                physicsBuffer[11] = min(1f, physicsBuffer[11] + (dt / 0.220f))
                val t = physicsBuffer[11]
                val squashY = when {
                    t < 0.25f -> 1f - (0.15f * (t / 0.25f))
                    t < 0.60f -> 0.85f + (0.22f * ((t - 0.25f) / 0.35f))
                    else -> 1.07f - (0.07f * ((t - 0.60f) / 0.40f))
                }
                physicsBuffer[7] = squashY
                physicsBuffer[6] = 1f / max(0.01f, squashY)
            }
            return
        }

        var x = physicsBuffer[0]
        var y = physicsBuffer[1]
        var vx = physicsBuffer[3]
        var vy = physicsBuffer[4]
        val isReadyToDock = physicsBuffer[13] > 0.5f

        fun computeAccel(px: Float, py: Float, pvx: Float, pvy: Float): Pair<Float, Float> {
            val dx = destination.x - px
            val dy = destination.y - py
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) return Pair(0f, 0f)

            // Balanced critically-damped spring physics for continuous, organic fluid flight
            val k = 13.5f
            val c = 8.2f

            var fx = k * dx - c * pvx
            var fy = k * dy - c * pvy

            if (initialDistance > 1f) {
                val progress = (1f - (dist / initialDistance)).coerceIn(0f, 1f)
                val liftMag = 45f * sin(progress * PI.toFloat())
                fx += (-dy / dist) * liftMag * 0.35f
                fy += ( dx / dist) * liftMag * 0.35f
            }
            return Pair(fx, fy)
        }

        // k1
        val (ax1, ay1) = computeAccel(x, y, vx, vy)
        val k1_vx = ax1 * dt
        val k1_vy = ay1 * dt
        val k1_x = vx * dt
        val k1_y = vy * dt

        // k2
        val (ax2, ay2) = computeAccel(x + 0.5f * k1_x, y + 0.5f * k1_y, vx + 0.5f * k1_vx, vy + 0.5f * k1_vy)
        val k2_vx = ax2 * dt
        val k2_vy = ay2 * dt
        val k2_x = (vx + 0.5f * k1_vx) * dt
        val k2_y = (vy + 0.5f * k1_vy) * dt

        // k3
        val (ax3, ay3) = computeAccel(x + 0.5f * k2_x, y + 0.5f * k2_y, vx + 0.5f * k2_vx, vy + 0.5f * k2_vy)
        val k3_vx = ax3 * dt
        val k3_vy = ay3 * dt
        val k3_x = (vx + 0.5f * k2_vx) * dt
        val k3_y = (vy + 0.5f * k2_vy) * dt

        // k4
        val (ax4, ay4) = computeAccel(x + k3_x, y + k3_y, vx + k3_vx, vy + k3_vy)
        val k4_vx = ax4 * dt
        val k4_vy = ay4 * dt
        val k4_x = (vx + k3_vx) * dt
        val k4_y = (vy + k3_vy) * dt

        x += (k1_x + 2f * k2_x + 2f * k3_x + k4_x) / 6f
        y += (k1_y + 2f * k2_y + 2f * k3_y + k4_y) / 6f
        vx += (k1_vx + 2f * k2_vx + 2f * k3_vx + k4_vx) / 6f
        vy += (k1_vy + 2f * k2_vy + 2f * k3_vy + k4_vy) / 6f

        val remDist = sqrt((destination.x - x).pow(2) + (destination.y - y).pow(2))
        if (remDist < 20f) {
            physicsBuffer[0] = destination.x
            physicsBuffer[1] = destination.y
            physicsBuffer[3] = 0f
            physicsBuffer[4] = 0f
            if (isReadyToDock) {
                physicsBuffer[12] = 1f
                physicsBuffer[11] = 0f
                return
            }
        }

        val speed = sqrt(vx * vx + vy * vy)
        val stretchPar = 1f + 0.25f * tanh(speed / 800f)
        val stretchPrp = 1f / stretchPar

        physicsBuffer[0] = x
        physicsBuffer[1] = y
        physicsBuffer[3] = vx
        physicsBuffer[4] = vy
        physicsBuffer[6] = stretchPar
        physicsBuffer[7] = stretchPrp
        physicsBuffer[8] = atan2(vy, vx)
        physicsBuffer[9] = (-vy * 0.025f).coerceIn(-12f, 12f)
        physicsBuffer[10] = (vx * 0.025f).coerceIn(-10f, 10f)
    }

    fun reset() {
        stage = TokenStage.IDLE
        dockReadyForUI = true
        artBitmap = null
        // Publish an invisible (null-visibility) frame so the render plane
        // fades the capsule out deterministically on the very next draw.
        publishTokenFrame()
        frameTick++
    }

    /**
     * I6-ordered teardown: revokes the channel BEFORE the references drop,
     * so a late publish becomes a DROPPED_REVOKED no-op instead of a write
     * into a buffer nobody owns. Call from the hosting scope's onDispose.
     */
    fun dispose() {
        steward.releaseAll()
    }

    companion object {
        /** Number of floats in the dense pose frame. */
        const val TOKEN_FLOATS = 14

        /** Payload bytes per frame (14 floats × 4 bytes, little-endian). */
        const val TOKEN_FRAME_BYTES = TOKEN_FLOATS * 4

        // Byte offsets into the Weft payload slice — the frozen reader layout.
        const val OFF_X = 0
        const val OFF_Y = 4
        const val OFF_STRETCH_PARALLEL = 8
        const val OFF_STRETCH_PERP = 12
        const val OFF_ROTATION_RAD = 16
        const val OFF_PITCH_DEG = 20
        const val OFF_ROLL_DEG = 24
        const val OFF_IMPACT_PROGRESS = 28
        const val OFF_IS_DOCKED = 32
        const val OFF_IS_READY_TO_DOCK = 36
        const val OFF_IS_RENDERABLE = 40
        const val OFF_IS_IMPACT_BLOOM = 44
        const val OFF_FLIGHT_TIME_SEC = 48
        const val OFF_RESERVED = 52
    }
}
