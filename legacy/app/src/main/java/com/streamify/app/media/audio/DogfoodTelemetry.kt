package com.streamify.app.media.audio

import com.streamify.app.data.uniffi.DacPresentationClock
import com.streamify.app.util.SLog
import com.streamify.shared.model.DacClockSample
import com.streamify.shared.model.dacDriftPpm
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DogfoodTelemetry — MP-CUT01 cutover observability (Phase 5, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Android dogfood protocol's measurement surface: route decisions,
 * underrun rate, DAC drift and cold-start latency for the USE_RUST_AUDIO
 * window, logged locally (SLog) and exposed reactively (one conflated
 * StateFlow) for the developer-settings dogfood panel
 * [com.streamify.shared.android.dogfood.RustAudioDogfoodPanel] — the shared
 * CMP component renders whatever lands here.
 *
 * DESIGN (hermetic-JVM-testable by construction):
 *  • Pure Kotlin + StateFlow — zero android.* API beyond [SLog] (JVM-safe
 *    via its stdout sink), so the app's JVM unit shard drives the full
 *    protocol without a device, an .so or a debug build.
 *  • The DERIVED metrics reuse the SHARED presentation math
 *    ([dacDriftPpm] from :shared-android — MP-UI03's single-source
 *    derivation contract, consumed on Android for real).
 *  • Push-only: collectors attach via [attachPipeline]; nothing polls.
 *  • [DacPresentationClock] is the app-side EngineContract mirror (pure
 *    data class — NOT the generated uniffi type), so the hermetic JVM
 *    shard compiles and runs this object unchanged.
 *
 * MP-DEC-03 discipline: DAC drift compares successive HARDWARE clock
 * samples (frame counters over their own monotonic timestamps) — no
 * wall-clock interpolation ever enters the math.
 */
object DogfoodTelemetry {

    /**
     * One conflated dogfood measurement snapshot — the exact image the
     * shared panel renders.
     */
    data class DogfoodSnapshot(
        val route: String,
        val routeReason: String,
        /** Feeder underrun rate as a percentage of total bursts; null pre-pipeline. */
        val underrunRatePercent: Float?,
        /** DAC drift between the latest successive hardware samples, ppm; null < 2 samples. */
        val dacDriftPpm: Float?,
        /** Route resolution -> first hardware DAC clock sample, ms; null until measured. */
        val coldStartMs: Long?,
        val framesPulled: Long,
        val dacClockSamples: Long,
        val pipelineLive: Boolean,
        val legacyFallbacks: Long,
    )

    private val _snapshot = MutableStateFlow(
        DogfoodSnapshot(
            route = "not-yet-resolved",
            routeReason = "not-yet-resolved",
            underrunRatePercent = null,
            dacDriftPpm = null,
            coldStartMs = null,
            framesPulled = 0L,
            dacClockSamples = 0L,
            pipelineLive = false,
            legacyFallbacks = 0L,
        )
    )

    /** Latest dogfood measurements (conflated; push-only). */
    val snapshot: StateFlow<DogfoodSnapshot> = _snapshot.asStateFlow()

    // ── Recording state ────────────────────────────────────────────────────

    private val legacyFallbacks = AtomicLong(0L)
    private val dacClockSamples = AtomicLong(0L)

    /** Wall timestamp of the route resolution that armed the dogfood lane. */
    @Volatile private var dogfoodArmedAtMs: Long = -1L

    /** The two latest hardware DAC clock samples (drift pair). */
    @Volatile private var previousClock: DacPresentationClock? = null
    @Volatile private var latestClock: DacPresentationClock? = null

    /** Latest pipeline telemetry (frames/underruns/live). */
    @Volatile private var latestPipeline: RustAudioPipeline.PipelineTelemetry? = null

    @Volatile private var routeName: String = "not-yet-resolved"
    @Volatile private var routeReason: String = "not-yet-resolved"
    @Volatile private var coldStartMs: Long? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null

    // ── Recording API ──────────────────────────────────────────────────────

    /**
     * Records one route resolution ([AudioRoutePolicy.resolve] calls this;
     * the JVM shard drives it directly). Arming the dogfood lane starts
     * the cold-start stopwatch.
     */
    fun recordRouteDecision(decision: AudioRoutePolicy.RouteDecision) {
        routeName = decision.route.name
        routeReason = decision.reason
        if (decision.route == AudioRoutePolicy.Route.RUST_AUDIO_PULL) {
            dogfoodArmedAtMs = System.currentTimeMillis()
        } else {
            dogfoodArmedAtMs = -1L
        }
        SLog.i(
            TAG,
            "route=${decision.route}: ${decision.reason} " +
                "(compile=${decision.compileFlag} override=${decision.runtimeOverride} " +
                "edges=${decision.nativeEdges})"
        )
        publish()
    }

    /**
     * Records one pipeline telemetry push (automatic while attached —
     * [attachPipeline]; the JVM shard drives it directly).
     */
    fun recordPipelineTelemetry(telemetry: RustAudioPipeline.PipelineTelemetry) {
        latestPipeline = telemetry
        publish()
    }

    /**
     * Records one hardware DAC clock sample (automatic while attached).
     * The FIRST sample after the dogfood lane armed completes the
     * cold-start measurement.
     */
    fun recordDacClockSample(clock: DacPresentationClock) {
        previousClock = latestClock
        latestClock = clock
        dacClockSamples.incrementAndGet()

        if (coldStartMs == null && dogfoodArmedAtMs > 0L) {
            coldStartMs = System.currentTimeMillis() - dogfoodArmedAtMs
            SLog.i(TAG, "cold start: ${coldStartMs}ms (route resolution -> first DAC sample)")
        }
        publish()
    }

    /**
     * Records one automatic zero-crash fallback to the legacy Media3
     * pipeline (the MP-CUT01 guarantee engaging). Each fallback also logs
     * its reason for the cutover review.
     */
    fun recordLegacyFallback(reason: String) {
        val count = legacyFallbacks.incrementAndGet()
        SLog.w(TAG, "legacy fallback #$count: $reason (zero-crash guarantee engaged)")
        publish()
    }

    /**
     * Attaches the live pipeline's push flows (telemetry + DAC clock) to
     * the recorder. One attach per pipeline generation; [detachPipeline]
     * before re-attaching. Purely passive collectors — zero polling.
     */
    fun attachPipeline(pipeline: RustAudioPipeline) {
        detachPipeline()
        pipelineJob = scope.launch {
            // Both sources are conflated StateFlows — one emission per
            // change; the collector cost is a no-op while idle.
            launch {
                pipeline.telemetry.collect { recordPipelineTelemetry(it) }
            }
            launch {
                pipeline.dacClock.collect { clock -> clock?.let { recordDacClockSample(it) } }
            }
        }
    }

    /** Detaches the pipeline collectors (idempotent). */
    fun detachPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
    }

    /** Resets all measurements (test isolation). */
    fun reset() {
        detachPipeline()
        legacyFallbacks.set(0L)
        dacClockSamples.set(0L)
        dogfoodArmedAtMs = -1L
        previousClock = null
        latestClock = null
        latestPipeline = null
        routeName = "not-yet-resolved"
        routeReason = "not-yet-resolved"
        coldStartMs = null
        publish()
    }

    // ── Derivation + publish ───────────────────────────────────────────────

    private fun publish() {
        val pipeline = latestPipeline
        val underrunRate: Float? = pipeline?.let { t ->
            val bursts = t.feederUnderruns + t.fullBursts
            if (bursts > 0L) {
                t.feederUnderruns.toFloat() / bursts.toFloat() * 100f
            } else {
                null
            }
        }

        val driftPpm: Float? = run {
            val older = previousClock ?: return@run null
            val newer = latestClock ?: return@run null
            dacDriftPpm(
                older = DacClockSample(
                    framePosition = older.dacFramePosition.toLong(),
                    sampleRateHz = older.sampleRate.toInt(),
                    monotonicNs = older.monotonicNs.toLong(),
                ),
                newer = DacClockSample(
                    framePosition = newer.dacFramePosition.toLong(),
                    sampleRateHz = newer.sampleRate.toInt(),
                    monotonicNs = newer.monotonicNs.toLong(),
                ),
            )
        }

        _snapshot.value = DogfoodSnapshot(
            route = routeName,
            routeReason = routeReason,
            underrunRatePercent = underrunRate,
            dacDriftPpm = driftPpm,
            coldStartMs = coldStartMs,
            framesPulled = pipeline?.framesPulled ?: 0L,
            dacClockSamples = dacClockSamples.get(),
            pipelineLive = pipeline?.playing ?: false,
            legacyFallbacks = legacyFallbacks.get(),
        )
    }

    private const val TAG = "DogfoodTelemetry"
}
