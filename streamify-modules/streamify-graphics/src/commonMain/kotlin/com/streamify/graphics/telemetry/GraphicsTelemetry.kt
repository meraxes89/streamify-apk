package com.streamify.graphics.telemetry

import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ThermalStatus
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * Classification of a logged telemetry event (docs/gpu/ARCHITECTURE.md,
 * section 3, axis 3 "Runtime Governor & Thermals"; docs/gpu/PHASED_PLAN.md,
 * Phase 3-B: "promotions, demotions, thermal throttling alerts").
 */
enum class TierEventKind {
    /** The TierGovernor moved up the capability ladder (e.g. Tier 2 -> Tier 1). */
    TIER_PROMOTION,

    /** The TierGovernor moved down the ladder (thermal demotion, P95 demotion). */
    TIER_DEMOTION,

    /** Thermal status reached [ThermalStatus.MODERATE] or worse. */
    THERMAL_THROTTLING_ALERT,
}

/**
 * One logged event of the graphics telemetry engine: a tier transition
 * forwarded from `TierGovernor.onTierChanged`, or a thermal throttling
 * alert raised by [GraphicsTelemetry.onThermalStatusChanged]. Immutable —
 * safe to retain anywhere.
 */
data class TierTransitionEvent(
    /** What happened. */
    val kind: TierEventKind,
    /** Tier before the transition; the live tier for thermal alerts; null before any tier was observed. */
    val fromTier: RenderTier?,
    /** Tier after the transition; null for thermal alerts (they carry no tier change of their own). */
    val toTier: RenderTier?,
    /** Thermal status known to the telemetry at event time. */
    val thermalStatus: ThermalStatus,
    /** Gap-free, 1-based ordinal across all logged events — the ordering key. */
    val sequence: Long,
    /** Monotonic milliseconds since telemetry construction (diagnostic clock, not wall time). */
    val timestampMillis: Long,
)

/**
 * Immutable, self-contained diagnostic view of the graphics telemetry
 * engine — the model diagnostic menus and overlay HUDs consume
 * (docs/gpu/PHASED_PLAN.md, Phase 3-B). Percentiles use the nearest-rank
 * method, matching the arithmetic of the `TierGovernor` P95 demotion gate
 * (rank = ceil(p * n), 1-based, clamped to the sample count). Safe to
 * retain, diff or hand across threads.
 */
data class TelemetrySnapshot(
    /** Frames currently in the rolling window (0 until the window warms up). */
    val framesInWindow: Int,
    /** Configured window capacity. */
    val windowSize: Int,
    /** Total frames observed since construction (or the last reset). */
    val totalFramesObserved: Long,
    /** Nearest-rank P50 of the window's frame render times, milliseconds. */
    val p50FrameTimeMillis: Double,
    /** Nearest-rank P90 of the window's frame render times, milliseconds. */
    val p90FrameTimeMillis: Double,
    /** Nearest-rank P99 of the window's frame render times, milliseconds. */
    val p99FrameTimeMillis: Double,
    /** Arithmetic mean of the window's frame render times, milliseconds. */
    val meanFrameTimeMillis: Double,
    /** Worst (slowest) frame render time currently in the window, milliseconds. */
    val worstFrameTimeMillis: Double,
    /** Tier as last observed through tier events; null before any. */
    val currentTier: RenderTier?,
    /** Latest thermal status reported to the telemetry. */
    val thermalStatus: ThermalStatus,
    /** Total tier transitions (promotions + demotions) since construction or reset. */
    val totalTierTransitions: Long,
    /** Total thermal throttling alerts (MODERATE or worse) since construction or reset. */
    val totalThermalAlerts: Long,
    /** Most recent events, oldest first, bounded by the event-log capacity. */
    val recentTierEvents: List<TierTransitionEvent>,
)

/**
 * Real-time graphics telemetry engine of the Sovereign GPU Engine
 * (docs/gpu/PHASED_PLAN.md, Phase 3-B; docs/gpu/ARCHITECTURE.md, sections
 * 3 and 4): rolling P50 / P90 / P99 frame render times over the last
 * [DEFAULT_WINDOW_SIZE] frames, plus a bounded log of tier transition
 * events (promotions, demotions, thermal throttling alerts) forwarded
 * from the TierGovernor.
 *
 * ## Wiring (engine-side, never composed)
 * ```
 * val telemetry = GraphicsTelemetry()
 * val governor = TierGovernor(probe)
 * governor.onTierChanged = telemetry::onTierTransition
 * frameClock.onFrame = { frameTimeMillis ->
 *     governor.onFrameRendered(frameTimeMillis)
 *     telemetry.onFrameRendered(frameTimeMillis)
 * }
 * thermalListener = telemetry::onThermalStatusChanged
 * ```
 *
 * ## Threading model
 * Every public method is safe from any thread: a tiny test-and-set
 * spinlock guards the ring buffers and the live tier/thermal view.
 * Critical sections are a handful of stores plus at most a
 * 60-element insertion sort (microseconds), so spinning beats a
 * blocking lock on every platform — including single-threaded wasmJs,
 * where the CAS always succeeds immediately. The optional [eventLogger]
 * sink is invoked strictly *outside* the critical section.
 *
 * ## Leak-freedom
 * All retention is fixed-capacity: the frame ring holds [windowSize]
 * doubles, the event log holds [eventLogCapacity] events, and
 * [snapshot] hands out immutable copies. However long playback runs,
 * the engine's memory footprint is constant — total counters grow as
 * Longs only.
 *
 * @param windowSize Rolling percentile window; defaults to
 * [DEFAULT_WINDOW_SIZE], mirroring the governor's 60-frame P95 window
 * (one second at 60 Hz).
 * @param eventLogCapacity Retained diagnostic events before the ring
 * wraps — the bounded tier transition log.
 * @param clock Monotonic time source stamping events; defaults to
 * [TimeSource.Monotonic] (diagnostic clock, injectable for tests).
 * @param eventLogger Optional side-channel sink (logcat / console /
 * Kermit) invoked after each event is recorded. A throwing sink is
 * swallowed — telemetry must never take a render path down.
 */
@OptIn(ExperimentalAtomicApi::class)
class GraphicsTelemetry(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val eventLogCapacity: Int = DEFAULT_EVENT_LOG_CAPACITY,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val eventLogger: ((TierTransitionEvent) -> Unit)? = null,
) {

    init {
        require(windowSize > 0) { "windowSize must be positive (was $windowSize)" }
        require(eventLogCapacity > 0) { "eventLogCapacity must be positive (was $eventLogCapacity)" }
    }

    // ------------------------------------------------------------------
    // Frame ring — frame-thread writes, any-thread snapshot reads, both
    // under the single telemetry lock below.
    // ------------------------------------------------------------------

    /** Ring buffer of the last [windowSize] frame render times (ms). */
    private val frameTimes = DoubleArray(windowSize)
    private var frameHead = 0
    private var frameCount = 0
    private var totalFrames = 0L

    // ------------------------------------------------------------------
    // Event ring — bounded log of tier transitions + thermal alerts.
    // ------------------------------------------------------------------

    private val eventLog = Array<TierTransitionEvent?>(eventLogCapacity) { null }
    private var eventHead = 0
    private var eventLogCount = 0
    private var totalEvents = 0L
    private var totalTierTransitions = 0L
    private var totalThermalAlerts = 0L

    // ------------------------------------------------------------------
    // Live view — the tier/thermal status the snapshot exposes.
    // ------------------------------------------------------------------

    private var currentTier: RenderTier? = null
    private var currentThermalStatus: ThermalStatus = ThermalStatus.NOMINAL

    /** Monotonic origin for the diagnostic event clock. */
    private val timeOrigin = clock.markNow()

    /** Test-and-set spinlock guarding every mutable field above. */
    private val spinlock = AtomicInt(LOCK_FREE)

    /**
     * Reports one rendered frame. Mirrors the governor's frame callback
     * signature — wire it from the same frame-clock callback that feeds
     * `TierGovernor.onFrameRendered`. Safe from any thread (the frame
     * thread in practice).
     */
    fun onFrameRendered(frameTimeMillis: Double) {
        withLock {
            frameTimes[frameHead] = frameTimeMillis
            frameHead = (frameHead + 1) % windowSize
            if (frameCount < windowSize) frameCount++
            totalFrames++
        }
    }

    /**
     * Records a tier transition forwarded from
     * `TierGovernor.onTierChanged` (promotions and demotions alike).
     * Safe from any thread — whichever thread performed the transition.
     */
    fun onTierTransition(from: RenderTier, to: RenderTier) {
        publishEvent(
            kind = if (to > from) TierEventKind.TIER_PROMOTION else TierEventKind.TIER_DEMOTION,
            fromTier = from,
            toTier = to,
            countsAsTierTransition = true,
        )
    }

    /**
     * Reports a thermal status change (forward it from the platform's
     * `addThermalStatusListener` alongside the governor). Statuses at
     * [ThermalStatus.MODERATE] or worse are logged as thermal throttling
     * alerts carrying the live tier; milder reports only update the
     * snapshot's status. Safe from any thread.
     */
    fun onThermalStatusChanged(status: ThermalStatus) {
        val tierAtReport = withLock {
            currentThermalStatus = status
            currentTier
        }
        if (status >= ThermalStatus.MODERATE) {
            publishEvent(
                kind = TierEventKind.THERMAL_THROTTLING_ALERT,
                fromTier = tierAtReport,
                toTier = null,
                countsAsTierTransition = false,
            )
        }
    }

    /**
     * Immutable view of the rolling percentiles, live tier/thermal status
     * and the most recent events. Safe from any thread; allocates one
     * snapshot (plus its event-list copy) per call — the consumer's
     * diagnostic cadence owns that cost, never the frame path.
     */
    fun snapshot(): TelemetrySnapshot = withLock {
        val samples = frameCount
        val active = DoubleArray(samples)
        var sum = 0.0
        for (i in 0 until samples) {
            val value = frameTimes[(frameHead - samples + i + windowSize) % windowSize]
            active[i] = value
            sum += value
        }
        sortAscending(active)
        TelemetrySnapshot(
            framesInWindow = samples,
            windowSize = windowSize,
            totalFramesObserved = totalFrames,
            p50FrameTimeMillis = percentileOf(active, 0.50),
            p90FrameTimeMillis = percentileOf(active, 0.90),
            p99FrameTimeMillis = percentileOf(active, 0.99),
            meanFrameTimeMillis = if (samples == 0) 0.0 else sum / samples,
            worstFrameTimeMillis = if (samples == 0) 0.0 else active[samples - 1],
            currentTier = currentTier,
            thermalStatus = currentThermalStatus,
            totalTierTransitions = totalTierTransitions,
            totalThermalAlerts = totalThermalAlerts,
            recentTierEvents = copyRecentEvents(),
        )
    }

    /** Clears every ring and counter back to factory state. Safe from any thread. */
    fun reset() {
        withLock {
            frameHead = 0
            frameCount = 0
            totalFrames = 0L
            for (i in eventLog.indices) eventLog[i] = null
            eventHead = 0
            eventLogCount = 0
            totalEvents = 0L
            totalTierTransitions = 0L
            totalThermalAlerts = 0L
            currentTier = null
            currentThermalStatus = ThermalStatus.NOMINAL
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun publishEvent(
        kind: TierEventKind,
        fromTier: RenderTier?,
        toTier: RenderTier?,
        countsAsTierTransition: Boolean,
    ): TierTransitionEvent {
        val event = withLock {
            totalEvents++
            if (countsAsTierTransition) totalTierTransitions++
            if (kind == TierEventKind.THERMAL_THROTTLING_ALERT) totalThermalAlerts++
            val recorded = TierTransitionEvent(
                kind = kind,
                fromTier = fromTier,
                toTier = toTier,
                thermalStatus = currentThermalStatus,
                sequence = totalEvents,
                timestampMillis = timeOrigin.elapsedNow().inWholeMilliseconds,
            )
            eventLog[eventHead] = recorded
            eventHead = (eventHead + 1) % eventLogCapacity
            if (eventLogCount < eventLogCapacity) eventLogCount++
            if (toTier != null) currentTier = toTier
            recorded
        }
        // The user-facing sink runs strictly OUTSIDE the critical section:
        // a logging callback must never deadlock against telemetry state,
        // and a throwing sink must never corrupt the event bookkeeping.
        eventLogger?.let { sink -> runCatching { sink(event) } }
        return event
    }

    /**
     * Test-and-set spinlock over [spinlock]. Critical sections are a
     * handful of stores (microseconds), so a plain spin beats a blocking
     * lock on every target — wasmJs included, where the CAS never contends.
     */
    private inline fun <T> withLock(block: () -> T): T {
        while (!spinlock.compareAndSet(LOCK_FREE, LOCK_HELD)) { /* spin */ }
        try {
            return block()
        } finally {
            spinlock.store(LOCK_FREE)
        }
    }

    /**
     * Nearest-rank percentile over an ascending [sorted] window — the
     * same arithmetic as the governor's P95 demotion gate:
     * rank = ceil(p * n), 1-based, clamped to the sample count.
     */
    private fun percentileOf(sorted: DoubleArray, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Copies the retained events, oldest first. Caller must hold the lock. */
    private fun copyRecentEvents(): List<TierTransitionEvent> {
        if (eventLogCount == 0) return emptyList()
        val retained = ArrayList<TierTransitionEvent>(eventLogCount)
        for (i in 0 until eventLogCount) {
            eventLog[(eventHead - eventLogCount + i + eventLogCapacity) % eventLogCapacity]?.let {
                retained.add(it)
            }
        }
        return retained
    }

    /** Insertion sort over the <= [windowSize]-element percentile copy. */
    private fun sortAscending(values: DoubleArray) {
        for (i in 1 until values.size) {
            val key = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > key) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = key
        }
    }

    companion object {
        /**
         * Rolling percentile window — mirrors the governor's 60-frame P95
         * window (one second at 60 Hz).
         */
        const val DEFAULT_WINDOW_SIZE = 60

        /**
         * Retained diagnostic events before the ring wraps — bounded
         * memory: the log never grows with playback duration.
         */
        const val DEFAULT_EVENT_LOG_CAPACITY = 32

        private const val LOCK_FREE = 0
        private const val LOCK_HELD = 1
    }
}
