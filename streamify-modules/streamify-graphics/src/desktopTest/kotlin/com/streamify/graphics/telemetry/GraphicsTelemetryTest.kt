package com.streamify.graphics.telemetry

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.streamify.graphics.api.streamifyVisualizer
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ThermalStatus
import com.streamify.graphics.governor.TierGovernor
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Phase 3, Track B — telemetry engine + draw-phase confinement gate
 * (docs/gpu/PHASED_PLAN.md Phase 3, Merge Gate 3: "Recomposition assertion
 * test confirms 0 recompositions during 60 seconds of simulated playback";
 * docs/gpu/ARCHITECTURE.md sections 3-5; docs/gpu/CI_TESTING_SPEC.md).
 *
 * Pins every contract of [GraphicsTelemetry]:
 *  * **Percentile accuracy.** Rolling nearest-rank P50 / P90 / P99 over
 *    the 60-frame window (the same arithmetic as the governor's P95 gate:
 *    rank = ceil(p * n), 1-based, clamped) — exact-value assertions on
 *    full, partial, custom-sized, shuffled and spike-laden windows, plus
 *    the eviction semantics once the ring wraps.
 *  * **Tier transition logging without memory leaks.** Promotions,
 *    demotions and thermal throttling alerts land in a bounded,
 *    fixed-capacity event log whose retention never grows with playback
 *    duration; the optional sink is delivered every event strictly
 *    outside the telemetry lock and can never take a render path down.
 *  * **Zero recompositions during playback.** The
 *    `Modifier.streamifyVisualizer` facade is pinned twice: once at the
 *    snapshot-system level (composition-time state reads = 0) and once
 *    against a live `Recomposer` — 3 600 consecutive FFT publishes over
 *    60 simulated seconds leave the composition count at exactly the
 *    initial composition. A control test proves the harness *can* detect
 *    recompositions, so the zero assertion has teeth.
 *
 * All deterministic tests are single-threaded; the one multi-threaded test
 * asserts a post-join fixed point, so the suite is flake-free by
 * construction (same policy as `TierGovernorTest`).
 */
class GraphicsTelemetryTest {

    // ------------------------------------------------------------------
    // 1. Rolling percentiles ("P50, P90, P99 frame render times over the
    //    last 60 frames", docs/gpu/PHASED_PLAN.md Phase 3-B)
    // ------------------------------------------------------------------

    @Test
    fun emptyTelemetryReportsAZeroedSnapshot() {
        val snapshot = GraphicsTelemetry().snapshot()
        assertEquals(0, snapshot.framesInWindow)
        assertEquals(GraphicsTelemetry.DEFAULT_WINDOW_SIZE, snapshot.windowSize)
        assertEquals(0L, snapshot.totalFramesObserved)
        assertEquals(0.0, snapshot.p50FrameTimeMillis)
        assertEquals(0.0, snapshot.p90FrameTimeMillis)
        assertEquals(0.0, snapshot.p99FrameTimeMillis)
        assertEquals(0.0, snapshot.meanFrameTimeMillis)
        assertEquals(0.0, snapshot.worstFrameTimeMillis)
        assertNull(snapshot.currentTier)
        assertEquals(ThermalStatus.NOMINAL, snapshot.thermalStatus)
        assertEquals(0L, snapshot.totalTierTransitions)
        assertEquals(0L, snapshot.totalThermalAlerts)
        assertTrue(snapshot.recentTierEvents.isEmpty())
    }

    @Test
    fun percentilesOfAFullSixtyFrameWindowMatchNearestRank() {
        val telemetry = GraphicsTelemetry()
        // Frames 1..60 ms — sum 1 830, mean 30.5; the classic steady lane.
        repeat(60) { telemetry.onFrameRendered((it + 1).toDouble()) }
        val snapshot = telemetry.snapshot()
        assertEquals(60, snapshot.framesInWindow)
        assertEquals(60L, snapshot.totalFramesObserved)
        // rank = ceil(p * n), 1-based: P50 -> 30th, P90 -> 54th, P99 -> 60th.
        assertEquals(30.0, snapshot.p50FrameTimeMillis)
        assertEquals(54.0, snapshot.p90FrameTimeMillis)
        assertEquals(60.0, snapshot.p99FrameTimeMillis)
        assertEquals(30.5, snapshot.meanFrameTimeMillis)
        assertEquals(60.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun percentilesOfAPartialWindowDuringWarmUp() {
        val telemetry = GraphicsTelemetry()
        // 10 frames 1..10 ms — the window is not yet full.
        repeat(10) { telemetry.onFrameRendered((it + 1).toDouble()) }
        val snapshot = telemetry.snapshot()
        assertEquals(10, snapshot.framesInWindow)
        assertEquals(GraphicsTelemetry.DEFAULT_WINDOW_SIZE, snapshot.windowSize)
        assertEquals(10L, snapshot.totalFramesObserved)
        assertEquals(5.0, snapshot.p50FrameTimeMillis) // ceil(0.5 * 10) = 5th
        assertEquals(9.0, snapshot.p90FrameTimeMillis) // ceil(0.9 * 10) = 9th
        assertEquals(10.0, snapshot.p99FrameTimeMillis) // ceil(0.99 * 10) = 10th
        assertEquals(5.5, snapshot.meanFrameTimeMillis)
        assertEquals(10.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun percentilesOfACustomOddWindowClampRankAndSurviveDuplicates() {
        // windowSize 7 with 8 reported frames: the first (3 ms) is evicted,
        // the window holds [1, 4, 1, 5, 9, 2, 6] -> sorted [1, 1, 2, 4, 5, 6, 9].
        val telemetry = GraphicsTelemetry(windowSize = 7)
        for (frame in listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0)) {
            telemetry.onFrameRendered(frame)
        }
        val snapshot = telemetry.snapshot()
        assertEquals(7, snapshot.framesInWindow)
        assertEquals(7, snapshot.windowSize)
        assertEquals(8L, snapshot.totalFramesObserved)
        // ceil(0.5 * 7) = 4th smallest; ceil(0.9 * 7) = ceil(6.3) = 7th; the
        // P99 rank clamps to n. Duplicated values (two 1s) never shift a rank.
        assertEquals(4.0, snapshot.p50FrameTimeMillis)
        assertEquals(9.0, snapshot.p90FrameTimeMillis)
        assertEquals(9.0, snapshot.p99FrameTimeMillis)
        assertEquals(4.0, snapshot.meanFrameTimeMillis)
        assertEquals(9.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun percentilesAreIndependentOfArrivalOrder() {
        val telemetry = GraphicsTelemetry()
        // The same 1..60 ms samples fed newest-first: insertion order must
        // never leak into a nearest-rank statistic.
        var frame = 60
        while (frame >= 1) {
            telemetry.onFrameRendered(frame.toDouble())
            frame--
        }
        val snapshot = telemetry.snapshot()
        assertEquals(30.0, snapshot.p50FrameTimeMillis)
        assertEquals(54.0, snapshot.p90FrameTimeMillis)
        assertEquals(60.0, snapshot.p99FrameTimeMillis)
        assertEquals(30.5, snapshot.meanFrameTimeMillis)
        assertEquals(60.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun p99IsSensitiveToASingleSpikeFrame() {
        val telemetry = GraphicsTelemetry()
        repeat(59) { telemetry.onFrameRendered(8.0) }
        telemetry.onFrameRendered(500.0) // one dropped frame in the window
        val snapshot = telemetry.snapshot()
        // 59 of 60 frames at 8 ms leave P50 and P90 untouched, but the P99
        // rank (60th of 60) catches the spike — the diagnostic HUD's
        // tail-latency story exactly.
        assertEquals(8.0, snapshot.p50FrameTimeMillis)
        assertEquals(8.0, snapshot.p90FrameTimeMillis)
        assertEquals(500.0, snapshot.p99FrameTimeMillis)
        assertEquals((59 * 8.0 + 500.0) / 60.0, snapshot.meanFrameTimeMillis)
        assertEquals(500.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun rollingWindowSlidesOnceCapacityIsExceeded() {
        val telemetry = GraphicsTelemetry()
        // 61 frames of 1..61 ms: frame 1 falls out, the window becomes 2..61.
        repeat(61) { telemetry.onFrameRendered((it + 1).toDouble()) }
        val snapshot = telemetry.snapshot()
        assertEquals(60, snapshot.framesInWindow)
        assertEquals(61L, snapshot.totalFramesObserved)
        assertEquals(31.0, snapshot.p50FrameTimeMillis) // 30th smallest of 2..61
        assertEquals(55.0, snapshot.p90FrameTimeMillis) // 54th smallest
        assertEquals(61.0, snapshot.p99FrameTimeMillis)
        assertEquals(31.5, snapshot.meanFrameTimeMillis)
        assertEquals(61.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun thousandFrameStormKeepsTheWindowBounded() {
        val telemetry = GraphicsTelemetry()
        repeat(1_000) { telemetry.onFrameRendered((it + 1).toDouble()) }
        val snapshot = telemetry.snapshot()
        // Whatever the playback duration, the engine's footprint is
        // constant: the ring retains exactly the last 60 frames (941..1000).
        assertEquals(60, snapshot.framesInWindow)
        assertEquals(1_000L, snapshot.totalFramesObserved)
        assertEquals(970.0, snapshot.p50FrameTimeMillis)
        assertEquals(994.0, snapshot.p90FrameTimeMillis)
        assertEquals(1_000.0, snapshot.p99FrameTimeMillis)
        assertEquals(970.5, snapshot.meanFrameTimeMillis)
        assertEquals(1_000.0, snapshot.worstFrameTimeMillis)
    }

    // ------------------------------------------------------------------
    // 2. Tier transition + thermal events ("logs tier transition events:
    //    promotions, demotions, thermal throttling alerts")
    // ------------------------------------------------------------------

    @Test
    fun promotionAndDemotionTransitionsAreLoggedWithKinds() {
        val telemetry = GraphicsTelemetry()
        telemetry.onTierTransition(RenderTier.Tier2_Balanced, RenderTier.Tier1_Ultra)
        telemetry.onTierTransition(RenderTier.Tier1_Ultra, RenderTier.Tier3_Legacy)
        val snapshot = telemetry.snapshot()
        assertEquals(2, snapshot.totalTierTransitions)
        assertEquals(RenderTier.Tier3_Legacy, snapshot.currentTier)
        val (promotion, demotion) = snapshot.recentTierEvents
        assertEquals(TierEventKind.TIER_PROMOTION, promotion.kind)
        assertEquals(RenderTier.Tier2_Balanced, promotion.fromTier)
        assertEquals(RenderTier.Tier1_Ultra, promotion.toTier)
        assertEquals(TierEventKind.TIER_DEMOTION, demotion.kind)
        assertEquals(RenderTier.Tier1_Ultra, demotion.fromTier)
        assertEquals(RenderTier.Tier3_Legacy, demotion.toTier)
    }

    @Test
    fun thermalAlertsAreLoggedOnlyAtModerateOrWorse() {
        val telemetry = GraphicsTelemetry()
        telemetry.onThermalStatusChanged(ThermalStatus.LIGHT) // warm, below threshold
        var snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.recentTierEvents.size) // silent — status only
        assertEquals(ThermalStatus.LIGHT, snapshot.thermalStatus)
        assertEquals(0L, snapshot.totalThermalAlerts)

        telemetry.onThermalStatusChanged(ThermalStatus.NOMINAL) // silent
        snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.recentTierEvents.size)
        assertEquals(ThermalStatus.NOMINAL, snapshot.thermalStatus)

        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE) // alert #1
        telemetry.onThermalStatusChanged(ThermalStatus.SEVERE) // alert #2
        telemetry.onThermalStatusChanged(ThermalStatus.EMERGENCY) // alert #3
        snapshot = telemetry.snapshot()
        assertEquals(3L, snapshot.totalThermalAlerts)
        assertEquals(3, snapshot.recentTierEvents.size)
        for (event in snapshot.recentTierEvents) {
            assertEquals(TierEventKind.THERMAL_THROTTLING_ALERT, event.kind)
        }
        // Alerts are not tier transitions — the demotion counter never moves.
        assertEquals(0L, snapshot.totalTierTransitions)
        assertEquals(ThermalStatus.EMERGENCY, snapshot.thermalStatus)
    }

    @Test
    fun thermalAlertsCarryTheLiveTierAndNoTierChangeOfTheirOwn() {
        val telemetry = GraphicsTelemetry()
        telemetry.onTierTransition(RenderTier.Tier4_Safe, RenderTier.Tier2_Balanced)
        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE)
        val snapshot = telemetry.snapshot()
        val alert = snapshot.recentTierEvents.single { it.kind == TierEventKind.THERMAL_THROTTLING_ALERT }
        assertEquals(RenderTier.Tier2_Balanced, alert.fromTier)
        assertNull(alert.toTier)
        assertEquals(ThermalStatus.MODERATE, alert.thermalStatus)
        // The alert itself never moves the live tier.
        assertEquals(RenderTier.Tier2_Balanced, snapshot.currentTier)
    }

    @Test
    fun eventSequencesAreGapFreeAndTimestampsNeverGoBackwards() {
        val telemetry = GraphicsTelemetry()
        telemetry.onFrameRendered(8.0)
        telemetry.onTierTransition(RenderTier.Tier3_Legacy, RenderTier.Tier2_Balanced)
        telemetry.onFrameRendered(8.0)
        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE)
        telemetry.onFrameRendered(8.0)
        telemetry.onTierTransition(RenderTier.Tier2_Balanced, RenderTier.Tier3_Legacy)
        val snapshot = telemetry.snapshot()
        // Frame reports never log events; the three published events share
        // a gap-free 1-based ordinal — the ordering key diagnostic menus
        // rely on — and the diagnostic clock never runs backwards.
        assertEquals(3, snapshot.recentTierEvents.size)
        assertEquals(listOf(1L, 2L, 3L), snapshot.recentTierEvents.map { it.sequence })
        assertTrue(
            snapshot.recentTierEvents.zipWithNext().all { (older, newer) ->
                older.timestampMillis <= newer.timestampMillis
            },
            "event timestamps must be non-decreasing",
        )
        assertEquals(3L, snapshot.totalFramesObserved)
    }

    @Test
    fun eventLogRetentionIsBoundedWithoutMemoryLeaks() {
        // A tiny log (capacity 8) hammered with 200 transitions: retention
        // stays fixed while the counters keep the full story. However long
        // playback runs, the engine's footprint is constant — the leak-
        // freedom contract of the event log.
        val telemetry = GraphicsTelemetry(eventLogCapacity = 8)
        repeat(200) { i ->
            if (i % 2 == 0) {
                telemetry.onTierTransition(RenderTier.Tier3_Legacy, RenderTier.Tier2_Balanced)
            } else {
                telemetry.onTierTransition(RenderTier.Tier2_Balanced, RenderTier.Tier3_Legacy)
            }
        }
        val snapshot = telemetry.snapshot()
        assertEquals(8, snapshot.recentTierEvents.size)
        assertEquals(200L, snapshot.totalTierTransitions)
        // The oldest 192 events were evicted; the ring kept the last 8,
        // oldest first, sequence-gap-free.
        assertEquals((193L..200L).toList(), snapshot.recentTierEvents.map { it.sequence })
        // The retained tail still alternates promotion / demotion exactly as
        // published: event #193 (i = 192, even) was a promotion and #200
        // (i = 199, odd) the closing demotion — the eviction dropped whole
        // events, never reordered or merged them.
        assertEquals(
            listOf(
                TierEventKind.TIER_PROMOTION,
                TierEventKind.TIER_DEMOTION,
                TierEventKind.TIER_PROMOTION,
                TierEventKind.TIER_DEMOTION,
                TierEventKind.TIER_PROMOTION,
                TierEventKind.TIER_DEMOTION,
                TierEventKind.TIER_PROMOTION,
                TierEventKind.TIER_DEMOTION,
            ),
            snapshot.recentTierEvents.map { it.kind },
        )
    }

    @Test
    fun resetClearsEveryRingCounterAndEvent() {
        val telemetry = GraphicsTelemetry()
        telemetry.onFrameRendered(8.0)
        telemetry.onTierTransition(RenderTier.Tier4_Safe, RenderTier.Tier2_Balanced)
        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE)
        telemetry.reset()
        val snapshot = telemetry.snapshot()
        assertEquals(0, snapshot.framesInWindow)
        assertEquals(0L, snapshot.totalFramesObserved)
        assertEquals(0.0, snapshot.p50FrameTimeMillis)
        assertEquals(0.0, snapshot.worstFrameTimeMillis)
        assertNull(snapshot.currentTier)
        assertEquals(ThermalStatus.NOMINAL, snapshot.thermalStatus)
        assertEquals(0L, snapshot.totalTierTransitions)
        assertEquals(0L, snapshot.totalThermalAlerts)
        assertTrue(snapshot.recentTierEvents.isEmpty())
        // ...and the engine keeps working after the factory reset.
        telemetry.onFrameRendered(8.0)
        assertEquals(1L, telemetry.snapshot().totalFramesObserved)
    }

    @Test
    fun eventLoggerSinkReceivesEveryEventInPublishOrder() {
        val sinkKinds = mutableListOf<TierEventKind>()
        val sinkSequences = mutableListOf<Long>()
        val telemetry = GraphicsTelemetry(
            eventLogger = { event ->
                sinkKinds.add(event.kind)
                sinkSequences.add(event.sequence)
            },
        )
        telemetry.onTierTransition(RenderTier.Tier4_Safe, RenderTier.Tier2_Balanced)
        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE)
        telemetry.onTierTransition(RenderTier.Tier2_Balanced, RenderTier.Tier4_Safe)
        assertEquals(
            listOf(
                TierEventKind.TIER_PROMOTION,
                TierEventKind.THERMAL_THROTTLING_ALERT,
                TierEventKind.TIER_DEMOTION,
            ),
            sinkKinds,
        )
        assertEquals(listOf(1L, 2L, 3L), sinkSequences)
    }

    @Test
    fun aThrowingEventSinkIsSwallowedAndBookkeepingSurvives() {
        val telemetry = GraphicsTelemetry(
            eventLogger = { throw IllegalStateException("sink exploded") },
        )
        telemetry.onTierTransition(RenderTier.Tier3_Legacy, RenderTier.Tier2_Balanced)
        telemetry.onThermalStatusChanged(ThermalStatus.SEVERE)
        // Telemetry must never take a render path down: the throwing sink
        // was swallowed and both events still landed in the log.
        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.totalTierTransitions)
        assertEquals(1L, snapshot.totalThermalAlerts)
        assertEquals(2, snapshot.recentTierEvents.size)
    }

    @Test
    fun eventSinkRunsStrictlyOutsideTheTelemetryLock() {
        val holder = arrayOfNulls<GraphicsTelemetry>(1)
        var reentrantSnapshot: TelemetrySnapshot? = null
        val telemetry = GraphicsTelemetry(
            eventLogger = { _ ->
                // If the sink were invoked inside the telemetry spinlock,
                // this re-entrant snapshot() would deadlock (the lock is not
                // re-entrant) and the test would hang. Completing with data
                // pins the outside-the-critical-section contract.
                reentrantSnapshot = holder[0]!!.snapshot()
            },
        )
        holder[0] = telemetry
        telemetry.onTierTransition(RenderTier.Tier4_Safe, RenderTier.Tier2_Balanced)
        assertNotNull(reentrantSnapshot)
        assertEquals(1L, reentrantSnapshot!!.totalTierTransitions)
        assertEquals(1, telemetry.snapshot().recentTierEvents.size)
    }

    // ------------------------------------------------------------------
    // 3. Immutable snapshot model ("exposes an immutable snapshot model for
    //    diagnostic menus and overlay HUDs")
    // ------------------------------------------------------------------

    @Test
    fun snapshotsAreImmutableSelfContainedCopies() {
        val telemetry = GraphicsTelemetry()
        repeat(GraphicsTelemetry.DEFAULT_WINDOW_SIZE) { telemetry.onFrameRendered((it + 1).toDouble()) }
        telemetry.onTierTransition(RenderTier.Tier4_Safe, RenderTier.Tier2_Balanced)

        val retained = telemetry.snapshot()
        // No interleaved activity: two snapshots are equal data-class copies.
        assertEquals(retained, telemetry.snapshot())

        // Later activity mutates nothing that was already handed out: HUDs
        // and diagnostic menus can retain a snapshot across frames safely.
        telemetry.onFrameRendered(500.0)
        val fresh = telemetry.snapshot()
        assertEquals(60, retained.framesInWindow)
        assertEquals(30.5, retained.meanFrameTimeMillis)
        assertEquals(60.0, retained.worstFrameTimeMillis)
        assertEquals(1L, retained.totalTierTransitions)
        assertEquals(61L, fresh.totalFramesObserved)
        assertEquals(60, fresh.framesInWindow)
        assertEquals(500.0, fresh.worstFrameTimeMillis)
    }

    // ------------------------------------------------------------------
    // 4. Governor wiring (the engine-side wiring of GraphicsTelemetry's
    //    kdoc: frame callback feeds BOTH, tier callback feeds the log)
    // ------------------------------------------------------------------

    @Test
    fun wiredGovernorLogsRealTransitionsDuringSimulatedPlayback() {
        val telemetry = GraphicsTelemetry()
        val governor = TierGovernor(tier1CapableProbe())
        governor.onTierChanged = telemetry::onTierTransition

        // 60 seconds of flawless 120 Hz frames: the promotion fires at the
        // 120th consecutive fast frame and never again afterwards.
        repeat(PLAYBACK_SECONDS * 60) {
            val frameTimeMillis = 8.0
            governor.onFrameRendered(frameTimeMillis)
            telemetry.onFrameRendered(frameTimeMillis)
        }
        val snapshot = telemetry.snapshot()
        assertEquals(RenderTier.Tier1_Ultra, snapshot.currentTier)
        assertEquals(1L, snapshot.totalTierTransitions)
        val promotion = snapshot.recentTierEvents.single()
        assertEquals(TierEventKind.TIER_PROMOTION, promotion.kind)
        assertEquals(RenderTier.Tier2_Balanced, promotion.fromTier)
        assertEquals(RenderTier.Tier1_Ultra, promotion.toTier)
        assertEquals(ThermalStatus.NOMINAL, promotion.thermalStatus)
        // Rolling window over the last 60 frames, every one of them 8 ms.
        assertEquals(60, snapshot.framesInWindow)
        assertEquals(3_600L, snapshot.totalFramesObserved)
        assertEquals(8.0, snapshot.p50FrameTimeMillis)
        assertEquals(8.0, snapshot.p90FrameTimeMillis)
        assertEquals(8.0, snapshot.p99FrameTimeMillis)
        assertEquals(8.0, snapshot.meanFrameTimeMillis)
        assertEquals(8.0, snapshot.worstFrameTimeMillis)
    }

    @Test
    fun wiredThermalDemotionLogsTheAlertThenTheDemotion() {
        val telemetry = GraphicsTelemetry()
        val governor = TierGovernor(tier1CapableProbe())
        governor.onTierChanged = telemetry::onTierTransition
        repeat(120) {
            governor.onFrameRendered(8.0)
            telemetry.onFrameRendered(8.0)
        } // event #1: Tier 2 -> Tier 1 promotion
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)

        // The thermal listener reports BOTH the telemetry and the governor:
        // the telemetry's alert carries the live tier, then the governor's
        // synchronous demotion lands as event #3.
        telemetry.onThermalStatusChanged(ThermalStatus.MODERATE) // event #2: alert
        governor.onThermalStatusChanged(ThermalStatus.MODERATE) // event #3: Tier 1 -> 3

        val snapshot = telemetry.snapshot()
        assertEquals(2L, snapshot.totalTierTransitions)
        assertEquals(1L, snapshot.totalThermalAlerts)
        assertEquals(3, snapshot.recentTierEvents.size)
        val (promotion, alert, demotion) = snapshot.recentTierEvents
        assertEquals(TierEventKind.TIER_PROMOTION, promotion.kind)
        assertEquals(TierEventKind.THERMAL_THROTTLING_ALERT, alert.kind)
        assertEquals(RenderTier.Tier1_Ultra, alert.fromTier)
        assertNull(alert.toTier)
        assertEquals(ThermalStatus.MODERATE, alert.thermalStatus)
        assertEquals(TierEventKind.TIER_DEMOTION, demotion.kind)
        assertEquals(RenderTier.Tier1_Ultra, demotion.fromTier)
        assertEquals(RenderTier.Tier3_Legacy, demotion.toTier)
        assertEquals(ThermalStatus.MODERATE, demotion.thermalStatus)
        assertEquals(RenderTier.Tier3_Legacy, snapshot.currentTier)
        assertEquals(ThermalStatus.MODERATE, snapshot.thermalStatus)
    }

    // ------------------------------------------------------------------
    // 5. Race-freedom (PR invariant: any-thread safety, flake-free by
    //    post-join fixed point — same policy as TierGovernorTest)
    // ------------------------------------------------------------------

    @Test
    fun concurrentReportingConvergesToExactTotals() {
        val telemetry = GraphicsTelemetry() // defaults: window 60, log 32
        val workers = List(WORKER_THREADS) {
            thread {
                repeat(500) { telemetry.onFrameRendered(8.0) }
                repeat(50) { telemetry.onTierTransition(RenderTier.Tier3_Legacy, RenderTier.Tier2_Balanced) }
                telemetry.snapshot() // concurrent read — never corrupts
            }
        }
        workers.forEach { it.join() }

        val snapshot = telemetry.snapshot()
        assertEquals((WORKER_THREADS * 500).toLong(), snapshot.totalFramesObserved)
        assertEquals(60, snapshot.framesInWindow)
        assertEquals((WORKER_THREADS * 50).toLong(), snapshot.totalTierTransitions)
        assertEquals(RenderTier.Tier2_Balanced, snapshot.currentTier)
        // Retention stays fixed-capacity under contention: 200 events, the
        // last 32 retained, sequence-gap-free and oldest first.
        assertEquals(32, snapshot.recentTierEvents.size)
        assertEquals((169L..200L).toList(), snapshot.recentTierEvents.map { it.sequence })
        // Every frame in the storm was 8 ms — percentiles are exact.
        assertEquals(8.0, snapshot.p50FrameTimeMillis)
        assertEquals(8.0, snapshot.p90FrameTimeMillis)
        assertEquals(8.0, snapshot.p99FrameTimeMillis)
        assertEquals(8.0, snapshot.meanFrameTimeMillis)
        assertEquals(8.0, snapshot.worstFrameTimeMillis)
    }

    // ------------------------------------------------------------------
    // 6. Draw-phase confinement ("recomposition count = 0 during playback",
    //    docs/gpu/ARCHITECTURE.md section 4, Merge Gate 3)
    // ------------------------------------------------------------------

    @Test
    fun visualizerFacadeReadsNoSnapshotStateWhileComposing() {
        val fftState = mutableStateOf(syntheticSpectrum(frame = 0))
        val fftProducer: () -> FloatArray = { fftState.value }

        // Recompositions are scheduled exclusively by snapshot-state reads
        // performed while composing. Observing the exact call a composable
        // body executes must therefore yield ZERO recorded reads.
        val compositionTimeReads = mutableListOf<Any>()
        Snapshot.observe(
            readObserver = { compositionTimeReads += it },
            writeObserver = null,
        ) {
            checkNotNull(
                Modifier.streamifyVisualizer(
                    fftSource = fftProducer,
                    primaryColor = Color(red = 0.13f, green = 0.83f, blue = 0.75f),
                    accentColor = Color(red = 0.96f, green = 0.45f, blue = 0.11f),
                ),
            )
        }
        assertTrue(
            compositionTimeReads.isEmpty(),
            "state read during composition: $compositionTimeReads",
        )

        // Control: the very same producer, invoked exactly as the draw
        // phase invokes it, performs exactly one observable read — the
        // observer rig works, so the zero above is the facade's
        // confinement, not a broken rig.
        val drawPhaseReads = mutableListOf<Any>()
        Snapshot.observe(
            readObserver = { drawPhaseReads += it },
            writeObserver = null,
        ) {
            assertEquals(FFT_BINS, fftProducer().size)
        }
        assertEquals(1, drawPhaseReads.size)
    }

    @Test
    fun zeroRecompositionsDuringSixtySecondsOfSimulatedPlayback() = runBlocking {
        val fftState = mutableStateOf(syntheticSpectrum(frame = 0))
        val compositions = CompositionCounter()

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val runner = launch(Dispatchers.Unconfined + frameClock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            composition.setContent {
                compositions.count++
                // The composition's ONLY contact with the FFT state is
                // *capturing* the producer lambda — never reading it.
                // Reading fftState.value here instead would re-introduce
                // the recomposition dependency this invariant forbids.
                checkNotNull(Modifier.streamifyVisualizer(fftSource = { fftState.value }))
            }
            // The initial composition runs synchronously inside setContent;
            // if this read anything but 1, the final assertion below would
            // be vacuously green.
            assertEquals(1, compositions.count)

            // 60 seconds of playback at 60 Hz: 3 600 consecutive FFT
            // publishes, each a fresh array identity — a real state change
            // under the structural-equality snapshot policy.
            repeat(PLAYBACK_SECONDS * 60) { frame ->
                fftState.value = syntheticSpectrum(frame)
                if (frame == 60) {
                    // One simulated second in: still exactly the initial
                    // composition — the draw phase is absorbing every
                    // update on its own.
                    assertEquals(1, compositions.count, "after 1 s of playback")
                }
            }
            Snapshot.sendApplyNotifications()
            drain(frameClock, recomposer)

            // Hard invariant (docs/gpu/ARCHITECTURE.md section 4): the
            // recomposition count stays strictly 0 during active playback —
            // the composition counter never moved past the initial one.
            assertEquals(1, compositions.count, "recompositions during 60 s of playback")
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }

    @Test
    fun controlReadingFftStateWhileComposingRecomposesExactlyOnce() = runBlocking {
        // Rig-validity control for the test above: the same harness, a
        // composable that DOES read the FFT state while composing. One
        // publish must drive exactly one recomposition — proving the
        // harness detects recompositions, so the zero assertion has teeth.
        val fftState = mutableStateOf(syntheticSpectrum(frame = 0))
        val compositions = CompositionCounter()

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val runner = launch(Dispatchers.Unconfined + frameClock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            composition.setContent {
                compositions.count++
                val binCount = fftState.value.size // the forbidden composition-time read
                check(binCount > 0)
            }
            assertEquals(1, compositions.count)

            fftState.value = syntheticSpectrum(frame = 1) // invalidates the reading scope
            Snapshot.sendApplyNotifications()
            drain(frameClock, recomposer)

            assertEquals(2, compositions.count)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }

    // ------------------------------------------------------------------
    // Harness (draw-phase confinement tests)
    // ------------------------------------------------------------------

    /** Plain (non-snapshot) counter of composition body executions. */
    private class CompositionCounter {
        var count = 0
    }

    /**
     * Applier backing the bare-[Recomposer] composition harness: the
     * visualizer content emits no UI nodes, so every mutation op below is
     * unreachable in practice. It exists to satisfy [Applier] for
     * [Composition].
     */
    private class EmptyApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }

    /**
     * Gives the recomposer every opportunity to drain pending work: a few
     * driven frames (each resuming any frame awaiter), yields for
     * Unconfined-dispatched continuations, and an explicit idle wait.
     */
    private suspend fun drain(frameClock: BroadcastFrameClock, recomposer: Recomposer) {
        repeat(DRAIN_ROUNDS) { step ->
            frameClock.sendFrame((step + 1L) * FRAME_INTERVAL_NANOS)
            yield()
        }
        recomposer.awaitIdle()
    }

    /**
     * Deterministic 1024-bin magnitude spectrum for simulated frame
     * [frame]: a fresh array identity per call, so every publish is a
     * genuine state change under the structural-equality snapshot policy.
     */
    private fun syntheticSpectrum(frame: Int): FloatArray = FloatArray(FFT_BINS) { bin ->
        ((frame + bin) % FFT_BINS) / FFT_BINS.toFloat()
    }

    /** API 34 flagship GPU with a validated AGSL probe — full Tier 1 capable. */
    private fun tier1CapableProbe() = CapabilityProbe(
        apiLevel = 34,
        rendererDescription = "Adreno (TM) 750",
        runtimeShaderCompileSupported = true,
    )

    private companion object {
        /** FFT bins per simulated spectrum frame (docs/gpu/ARCHITECTURE.md section 4). */
        private const val FFT_BINS = 1_024

        /** Simulated playback duration driven through the composition harness. */
        private const val PLAYBACK_SECONDS = 60

        /** Frames driven per drain round — recomposer quiescence headroom. */
        private const val DRAIN_ROUNDS = 3

        /** 60 Hz frame interval, nanoseconds. */
        private const val FRAME_INTERVAL_NANOS = 16_666_666L

        /** Worker threads in the race-freedom fixed point test. */
        private const val WORKER_THREADS = 4
    }
}
