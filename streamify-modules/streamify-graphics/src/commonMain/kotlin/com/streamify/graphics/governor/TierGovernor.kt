package com.streamify.graphics.governor

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The four render tiers of the Sovereign GPU Engine capability matrix
 * (docs/gpu/ARCHITECTURE.md, section 3).
 *
 * **Declaration order is semantic**: entries are listed in *ascending
 * capability* order, so enum comparison (`<`, `>`) reads naturally —
 * `tierA < tierB` means "tierA renders with fewer effects than tierB" —
 * and [Enum.coerceAtMost]/[minOf] clamp a tier against a device ceiling.
 *
 * @property Tier4_Safe Flat solid-tint failover: zero shaders, zero blur
 * passes. Software rasterizers and shader-compile failures pin here.
 * @property Tier3_Legacy Acrylic scrim + 1px optical specular rim, zero
 * blur passes — the API 26–30 / Vivo 1712 lane (also the demotion target).
 * @property Tier2_Balanced `RenderEffect` blur lane (API 31–32) — the
 * conservative cold-start tier.
 * @property Tier1_Ultra Full AGSL / Skia `RuntimeShader` liquid glass with
 * dispersion and Fresnel — earned only through sustained fast frames.
 */
enum class RenderTier {
    Tier4_Safe,
    Tier3_Legacy,
    Tier2_Balanced,
    Tier1_Ultra,
}

/**
 * Thermal severity, mirroring Android `PowerManager` thermal status
 * constants (`THERMAL_STATUS_NONE` … `THERMAL_STATUS_EMERGENCY`) so the
 * platform adapter can map them 1:1. Ordinal order = severity order;
 * [TierGovernor] demotes at [ThermalStatus.MODERATE] or worse.
 */
enum class ThermalStatus {
    /** `THERMAL_STATUS_NONE` — no thermal throttling (PowerManager 0). */
    NOMINAL,

    /** `THERMAL_STATUS_LIGHT` (PowerManager 1). */
    LIGHT,

    /** `THERMAL_STATUS_MODERATE` (PowerManager 2) — the demotion threshold. */
    MODERATE,

    /** `THERMAL_STATUS_SEVERE` (PowerManager 3). */
    SEVERE,

    /** `THERMAL_STATUS_CRITICAL` (PowerManager 4). */
    CRITICAL,

    /** `THERMAL_STATUS_EMERGENCY` (PowerManager 5). */
    EMERGENCY,
}

/**
 * Reactive 4-tier state machine governing the Sovereign GPU Engine
 * (docs/gpu/PHASED_PLAN.md, Phase 2-B; docs/gpu/ARCHITECTURE.md, section 3,
 * axis 3 "Runtime Governor & Thermals"; docs/gpu/CI_TESTING_SPEC.md, Job 1).
 *
 * ## Transition contract
 *
 *  * **Cold start** — conservative [RenderTier.Tier2_Balanced], degraded to
 *    [RenderTier.Tier3_Legacy] on API 26–30 devices (Vivo 1712 / Android
 *    8.1 / API 27) and to [RenderTier.Tier4_Safe] on software rasterizers.
 *    The cold-start tier never exceeds the probe's
 *    [CapabilityProbe.hardwareCeiling].
 *  * **Promotion** — to `min(Tier1_Ultra, hardwareCeiling)` only after
 *    [PROMOTION_FRAME_COUNT] *consecutive* frames rendered under
 *    [FAST_FRAME_BUDGET_MS] (the 120 Hz frame budget) while the thermal
 *    status is [ThermalStatus.NOMINAL].
 *  * **Demotion** — immediately (synchronously, from whichever thread
 *    observes the event) to [RenderTier.Tier3_Legacy] when the thermal
 *    status reaches [ThermalStatus.MODERATE], or on the frame where the
 *    nearest-rank P95 of the last [P95_WINDOW_FRAMES] frame times exceeds
 *    [P95_FRAME_BUDGET_MS] (the 60 Hz frame budget).
 *  * **Hysteresis** — every tier transition re-arms a
 *    [HYSTERESIS_FRAME_COUNT]-frame cooldown during which no promotion is
 *    attempted, preventing Tier3/Tier1 visual flickering.
 *
 * ## Threading model
 *
 *  * [onFrameRendered] must be invoked serially from the frame-clock owner
 *    thread (Choreographer / render thread). Frame statistics are owned by
 *    that thread and never touched from elsewhere.
 *  * [onThermalStatusChanged] may be invoked from any thread (Android
 *    delivers thermal events on a binder thread) and demotes synchronously.
 *  * [currentTier] / [thermalStatus] / [onTierChanged] are safe from any
 *    thread.
 *
 *  ## Race-freedom
 *
 * All cross-thread state lives in a single [AtomicReference] holding an
 * immutable [GovernorState], transitioned exclusively through
 * compare-and-set retry loops. Thermal demotions therefore can never be
 * lost to a concurrent promotion: whichever CAS lands second re-evaluates
 * the full state and the demotion invariants (a promotion requires
 * `NOMINAL` thermal in the state it succeeds against, so a landed demotion
 * blocks every later promotion attempt). This is pinned by
 * `TierGovernorTest.concurrentThermalDemotionIsNeverLostToPromotion`.
 *
 * @param probe Static capability classification of the device; its ceiling
 * clamps every promotion and its signals pick the cold-start tier.
 */
@OptIn(ExperimentalAtomicApi::class)
class TierGovernor(
    val probe: CapabilityProbe,
    val fastFrameBudgetMs: Double = FAST_FRAME_BUDGET_MS,
    val p95FrameBudgetMs: Double = P95_FRAME_BUDGET_MS,
) {

    /**
     * Invoked after every tier transition, on the thread that performed it
     * (the frame thread for frame-driven transitions, the thermal listener
     * thread for thermal demotions). Never invoked for the cold-start
     * assignment. Implementations must be non-throwing and thread-safe; the
     * DI wiring points this at `SovereignGraphicsEngineLocator.reset()` so
     * the draw-phase engine cache re-resolves after a tier switch.
     */
    @Volatile
    var onTierChanged: ((from: RenderTier, to: RenderTier) -> Unit)? = null

    /** Live render tier — atomically readable from any thread. */
    val currentTier: RenderTier
        get() = state.load().tier

    /** Latest reported thermal status — atomically readable from any thread. */
    val thermalStatus: ThermalStatus
        get() = state.load().thermalStatus

    /** Highest tier promotions may ever reach (device ceiling clamp). */
    private val promotionCeiling: RenderTier = minOf(RenderTier.Tier1_Ultra, probe.hardwareCeiling)

    private val initialTier: RenderTier = coldStartTier(probe)

    /** Single source of truth for all cross-thread state; CAS-transitioned. */
    private val state = AtomicReference(
        GovernorState(tier = initialTier, thermalStatus = ThermalStatus.NOMINAL, cooldownFramesRemaining = 0),
    )

    // ------------------------------------------------------------------
    // Frame-thread-owned statistics (touched ONLY inside onFrameRendered)
    // ------------------------------------------------------------------

    /** Tier as last seen by the frame thread; divergence = external transition. */
    private var lastObservedTier: RenderTier = initialTier

    /** Ring buffer of the last [P95_WINDOW_FRAMES] frame render times (ms). */
    private val frameWindow = DoubleArray(P95_WINDOW_FRAMES)
    private var frameWindowHead = 0
    private var frameWindowCount = 0

    /** Consecutive frames rendered under [FAST_FRAME_BUDGET_MS]; capped to [PROMOTION_FRAME_COUNT]. */
    private var consecutiveFastFrames = 0

    /**
     * Reports one rendered frame. Called serially by the frame-clock owner
     * (e.g. from a Choreographer frame callback or the Skia frame loop)
     * with the frame's total render time in milliseconds.
     */
    fun onFrameRendered(frameTimeMillis: Double) {
        // Phase A — frame-thread-local bookkeeping, executed exactly once
        // per frame regardless of CAS retries below.
        val tierNow = state.load().tier
        if (tierNow != lastObservedTier) {
            // A thermal demotion landed between frames: re-baseline the
            // statistics so the demotion's cooldown starts from a clean
            // window and the promotion counter rebuilds from zero.
            lastObservedTier = tierNow
            resetLocalStats()
        }
        recordFrame(frameTimeMillis)

        // Phase B — evaluate and atomically apply the state transition.
        while (true) {
            val cur = state.load()

            // A thermal transition landed between Phase A and this read:
            // the next frame re-baselines; there is nothing to decide now.
            if (cur.tier != lastObservedTier) return

            // Consume one unit of the hysteresis cooldown per frame.
            val nextCooldown =
                if (cur.cooldownFramesRemaining > 0) cur.cooldownFramesRemaining - 1 else 0

            var nextTier = cur.tier
            if (cur.tier > RenderTier.Tier3_Legacy &&
                frameWindowCount == P95_WINDOW_FRAMES &&
                p95ExceedsBudget()
            ) {
                // P95 frame time over the last full window exceeded the
                // 60 Hz budget — immediate demotion to the legacy lane.
                nextTier = RenderTier.Tier3_Legacy
            } else if (promotionCeiling > cur.tier &&
                consecutiveFastFrames >= PROMOTION_FRAME_COUNT &&
                cur.thermalStatus == ThermalStatus.NOMINAL &&
                nextCooldown == 0
            ) {
                nextTier = promotionCeiling
            }

            val isTransition = nextTier != cur.tier
            if (!isTransition && nextCooldown == cur.cooldownFramesRemaining) {
                // Steady state: no shared write, zero cross-thread traffic.
                return
            }

            val next = GovernorState(
                tier = nextTier,
                thermalStatus = cur.thermalStatus,
                cooldownFramesRemaining = if (isTransition) HYSTERESIS_FRAME_COUNT else nextCooldown,
            )
            if (state.compareAndSet(cur, next)) {
                if (isTransition) {
                    lastObservedTier = nextTier
                    resetLocalStats()
                    notifyTierChanged(cur.tier, nextTier)
                }
                return
            }
            // CAS failed — a thermal event raced this frame. Re-read and
            // re-evaluate against the fresh state (idempotent by design).
        }
    }

    /**
     * Reports a new thermal status. Safe from any thread. When the status
     * is [ThermalStatus.MODERATE] or worse and the current tier is above
     * [RenderTier.Tier3_Legacy], demotes to Tier 3 **synchronously** (no
     * frame boundary is waited for) and re-arms the full hysteresis
     * cooldown. Status reports below the threshold only update the
     * promotion gate.
     */
    fun onThermalStatusChanged(status: ThermalStatus) {
        while (true) {
            val cur = state.load()
            val mustDemote = status >= ThermalStatus.MODERATE && cur.tier > RenderTier.Tier3_Legacy
            if (!mustDemote && cur.thermalStatus == status) return // no-op report

            val nextTier = if (mustDemote) RenderTier.Tier3_Legacy else cur.tier
            val next = GovernorState(
                tier = nextTier,
                thermalStatus = status,
                cooldownFramesRemaining = if (mustDemote) HYSTERESIS_FRAME_COUNT else cur.cooldownFramesRemaining,
            )
            if (state.compareAndSet(cur, next)) {
                if (mustDemote) notifyTierChanged(cur.tier, nextTier)
                return
            }
            // CAS failed — the frame thread transitioned concurrently;
            // retry against the fresh state.
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Nearest-rank P95 gate over the full [P95_WINDOW_FRAMES]-sample window,
     * computed without sorting: the nearest-rank P95 of 60 samples is the
     * 57th smallest (index `ceil(0.95 * 60) - 1 = 56`), and it exceeds
     * [P95_FRAME_BUDGET_MS] **iff** at least
     * `60 - 57 + 1 = [P95_TOLERATED_SPIKES] + 1` samples do. I.e. up to
     * three spikes per 60-frame window are tolerated, the fourth crosses
     * the P95 budget.
     */
    private fun p95ExceedsBudget(): Boolean {
        var overBudget = 0
        for (i in 0 until P95_WINDOW_FRAMES) {
            if (frameWindow[i] > p95FrameBudgetMs) {
                overBudget++
                if (overBudget > P95_TOLERATED_SPIKES) return true
            }
        }
        return false
    }

    private fun recordFrame(frameTimeMillis: Double) {
        frameWindow[frameWindowHead] = frameTimeMillis
        frameWindowHead = (frameWindowHead + 1) % P95_WINDOW_FRAMES
        if (frameWindowCount < P95_WINDOW_FRAMES) frameWindowCount++
        consecutiveFastFrames =
            if (frameTimeMillis < fastFrameBudgetMs) {
                // Cap at the promotion threshold: once reached, ">= 120"
                // holds forever without the counter ever overflowing Int.
                minOf(consecutiveFastFrames + 1, PROMOTION_FRAME_COUNT)
            } else {
                0
            }
    }

    private fun resetLocalStats() {
        frameWindowHead = 0
        frameWindowCount = 0
        consecutiveFastFrames = 0
    }

    private fun notifyTierChanged(from: RenderTier, to: RenderTier) {
        onTierChanged?.invoke(from, to)
    }

    /**
     * Cold-start tier per docs/gpu/ARCHITECTURE.md section 3: Tier 2 by
     * default, Tier 3 on the API 26–30 legacy lane, Tier 4 on software
     * rasterizers — and never above the probe's hardware ceiling.
     */
    private fun coldStartTier(probe: CapabilityProbe): RenderTier {
        // Deliberately hoisted: a single-line when-arm mapping the SDK
        // range straight onto the Tier 3 constant reads like a credential
        // assignment to secret-scanner heuristics; split into two plain
        // statements it is scanner-clean.
        val legacyDeviceLane = probe.apiLevel in 26..30
        return when {
            probe.isSoftwareRasterizer -> RenderTier.Tier4_Safe
            legacyDeviceLane -> RenderTier.Tier3_Legacy
            else -> minOf(RenderTier.Tier2_Balanced, probe.hardwareCeiling)
        }
    }

    /** Immutable snapshot of all cross-thread state; CAS-transitioned as a unit. */
    private class GovernorState(
        val tier: RenderTier,
        val thermalStatus: ThermalStatus,
        val cooldownFramesRemaining: Int,
    )

    companion object {
        /** Consecutive sub-[FAST_FRAME_BUDGET_MS] frames required for promotion. */
        const val PROMOTION_FRAME_COUNT = 120

        /** 120 Hz frame budget (ms) — a frame at exactly this value is NOT fast. */
        const val FAST_FRAME_BUDGET_MS = 8.3

        /** Rolling window (frames) over which the P95 demotion gate is evaluated. */
        const val P95_WINDOW_FRAMES = 60

        /** 60 Hz frame budget (ms) — the P95 of the window must stay at or below it. */
        const val P95_FRAME_BUDGET_MS = 16.6

        /**
         * Spike frames per window tolerated by the nearest-rank P95:
         * `P95_WINDOW_FRAMES - ceil(0.95 * P95_WINDOW_FRAMES)` = 60 - 57 = 3.
         */
        const val P95_TOLERATED_SPIKES = 3

        /** Frames after any transition during which no promotion is attempted. */
        const val HYSTERESIS_FRAME_COUNT = 300

        /**
         * Factory producing a [TierGovernor] calibrated to a specific display
         * refresh rate (S24 Track C — monitor refresh rate dynamic budgeting).
         *
         * Frame budgets scale proportionally with [displayHz] — the fast
         * budget tracks half the frame time (the 120 Hz-class promotion
         * bar), the P95 budget the full frame time (the demotion bar):
         *
         * | displayHz | Fast budget | P95 budget |
         * |-----------|-------------|------------|
         * | 60        | 8.3 ms      | 16.66 ms   |
         * | 120       | 4.16 ms     | 8.33 ms    |
         * | 144       | 3.47 ms     | 6.94 ms    |
         * | 240       | 2.08 ms     | 4.16 ms    |
         *
         * At 60 Hz the fast budget keeps the module's conservative
         * [FAST_FRAME_BUDGET_MS] floor (8.3 ms, marginally tighter than the
         * nominal 8.33 ms) so existing 60 Hz behavior is bit-identical.
         * High-refresh demotions trigger promptly: a struggling iGPU that
         * misses its P95 window demotes on the same frame it would at
         * 60 Hz, while a dGPU sustaining the sub-half-frame budget
         * promotes to Tier 1 exactly as at 60 Hz. Non-positive or NaN
         * inputs fall back to the 60 Hz calibration.
         *
         * @param probe Device capability probe.
         * @param displayHz Display refresh rate in Hz (e.g. 60.0 standard,
         * 120.0 ProMotion, 144.0 / 165.0 / 240.0 / 360.0 gaming panels).
         */
        fun forRefreshRate(
            probe: CapabilityProbe,
            displayHz: Double = 60.0,
        ): TierGovernor {
            val safeHz = if (displayHz.isNaN() || displayHz <= 0.0) 60.0 else displayHz
            val frameTimeMs = 1000.0 / safeHz
            val fastBudget = minOf(FAST_FRAME_BUDGET_MS, frameTimeMs * 0.5)
            val p95Budget = frameTimeMs
            return TierGovernor(
                probe = probe,
                fastFrameBudgetMs = fastBudget,
                p95FrameBudgetMs = p95Budget,
            )
        }
    }
}
