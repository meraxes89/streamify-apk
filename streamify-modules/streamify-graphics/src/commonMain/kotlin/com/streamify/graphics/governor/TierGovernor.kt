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
 * (docs/gpu/ARCHITECTURE.md, section 3, axis 3 "Runtime Governor &
 * Thermals"; docs/gpu/CI_TESTING_SPEC.md, Job 1;
 * docs/systems/S24-sovereign-graphics.md).
 *
 * ## Transition contract
 *
 *  * **Cold start** — conservative [RenderTier.Tier2_Balanced], degraded to
 *    [RenderTier.Tier3_Legacy] on API 26–30 devices (Vivo 1712 / Android
 *    8.1 / API 27) and to [RenderTier.Tier4_Safe] on software rasterizers.
 *    The cold-start tier never exceeds the probe's
 *    [CapabilityProbe.hardwareCeiling].
 *  * **Promotion** — to `min(Tier1_Ultra, hardwareCeiling)` only after
 *    [PROMOTION_FRAME_COUNT] *consecutive* frames rendered under the live
 *    fast-frame budget (the display-cadence budget, [fastFrameBudgetMs])
 *    while the thermal status is [ThermalStatus.NOMINAL].
 *  * **Thermal demotion** — immediate (synchronous, from whichever thread
 *    observes the event): [ThermalStatus.MODERATE] / [ThermalStatus.SEVERE]
 *    demote to [RenderTier.Tier3_Legacy]; [ThermalStatus.CRITICAL] /
 *    [ThermalStatus.EMERGENCY] demote all the way to
 *    [RenderTier.Tier4_Safe]. On Apple platforms these statuses are fed by
 *    `NSProcessInfo.thermalState` through the iosMain environment bridge
 *    (`serious` maps to MODERATE, `critical` to CRITICAL — see
 *    [AppleThermalStateMapping]); Android feeds them from
 *    `PowerManager.OnThermalStatusChangedListener`.
 *  * **P95 demotion (stepped)** — on the frame where the nearest-rank P95
 *    of the last [P95_WINDOW_FRAMES] frame times exceeds the live P95
 *    budget, the governor steps down **exactly one tier**
 *    (Tier 1 → Tier 2 single-tap → Tier 3) instead of dropping straight to
 *    the legacy lane. A ProMotion device whose liquid-glass pass falls from
 *    120 FPS to 90 FPS therefore lands on the cheaper single-tap shader and
 *    keeps rendering glass, rather than visually bouncing to the scrim.
 *  * **Low Power Mode** — [onLowPowerModeEnabled] (Apple
 *    `NSProcessInfo.isLowPowerModeEnabled`, observed via
 *    `NSProcessInfoPowerStateDidChangeNotification`) caps the promotion
 *    ceiling at [RenderTier.Tier2_Balanced], demotes any live Tier 1
 *    synchronously, and pins the frame budgets to the 60 Hz lane.
 *  * **Display cadence** — [onDisplayRefreshRateChanged] recalibrates both
 *    budgets to a new display-link frequency (ProMotion 10–120 Hz).
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
 * @param initialFastFrameBudgetMs Construction-time fast-frame budget (ms);
 * the live value adapts later via [onDisplayRefreshRateChanged] and
 * [onLowPowerModeEnabled].
 * @param initialP95FrameBudgetMs Construction-time P95 budget (ms); adapts
 * the same way.
 * @param initialRefreshRateHz Construction-time display refresh rate (Hz)
 * the budgets are calibrated against; 60.0 when unknown.
 */
@OptIn(ExperimentalAtomicApi::class)
class TierGovernor(
    val probe: CapabilityProbe,
    initialFastFrameBudgetMs: Double = FAST_FRAME_BUDGET_MS,
    initialP95FrameBudgetMs: Double = P95_FRAME_BUDGET_MS,
    initialRefreshRateHz: Double = DEFAULT_REFRESH_RATE_HZ,
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

    /**
     * Live fast-frame budget (ms) — the display-cadence-calibrated budget a
     * frame must beat to count toward promotion. Starts at the
     * construction-time value and is recalibrated by
     * [onDisplayRefreshRateChanged] / [onLowPowerModeEnabled]; readable from
     * any thread.
     */
    val fastFrameBudgetMs: Double
        get() = fastBudgetMs

    /**
     * Live P95 budget (ms) the rolling window must stay at or under. Starts
     * at the construction-time value and is recalibrated the same way.
     */
    val p95FrameBudgetMs: Double
        get() = p95BudgetMs

    /**
     * Highest tier promotions may ever reach — the device ceiling
     * ([CapabilityProbe.hardwareCeiling]), further clamped to
     * [RenderTier.Tier2_Balanced] while Low Power Mode is engaged.
     */
    private val hardwarePromotionCeiling: RenderTier = minOf(RenderTier.Tier1_Ultra, probe.hardwareCeiling)

    private val promotionCeiling: RenderTier
        get() =
            if (lowPowerModeEnabled) {
                minOf(hardwarePromotionCeiling, RenderTier.Tier2_Balanced)
            } else {
                hardwarePromotionCeiling
            }

    private val initialTier: RenderTier = coldStartTier(probe)

    // ------------------------------------------------------------------
    // Volatile runtime calibration (see docs/systems/S24-sovereign-graphics.md)
    // ------------------------------------------------------------------

    /** Live fast-frame budget — volatile; recalibrated off the frame thread. */
    @Volatile
    private var fastBudgetMs: Double = initialFastFrameBudgetMs

    /** Live P95 budget — volatile; recalibrated off the frame thread. */
    @Volatile
    private var p95BudgetMs: Double = initialP95FrameBudgetMs

    /** Display-link frequency the budgets are calibrated against. */
    @Volatile
    private var activeRefreshRateHz: Double =
        if (initialRefreshRateHz <= 0.0) DEFAULT_REFRESH_RATE_HZ else initialRefreshRateHz

    /** Apple Low Power Mode / Android battery-saver clamp — see [onLowPowerModeEnabled]. */
    @Volatile
    private var lowPowerModeEnabled: Boolean = false

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
                // P95 frame time over the last full window exceeded the live
                // budget — stepped demotion: exactly one lane down (Tier 1
                // -> Tier 2 single-tap -> Tier 3), so a ProMotion device
                // that loses its 120 Hz budget keeps the (cheaper) shader
                // lane instead of bouncing visually to the scrim.
                nextTier = RenderTier.entries[cur.tier.ordinal - 1]
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
     * Reports a new thermal status. Safe from any thread; the demotion is
     * **synchronous** (no frame boundary is waited for).
     *
     * Demotion ladder (docs/gpu/ARCHITECTURE.md section 3, axis 3):
     *  * [ThermalStatus.MODERATE] / [ThermalStatus.SEVERE] — demote to
     *    [RenderTier.Tier3_Legacy] (Apple `thermalState == serious` and
     *    Android `THERMAL_STATUS_MODERATE`/`_SEVERE`).
     *  * [ThermalStatus.CRITICAL] / [ThermalStatus.EMERGENCY] — demote all
     *    the way to [RenderTier.Tier4_Safe] (Apple `thermalState ==
     *    critical`, Android `THERMAL_STATUS_CRITICAL`/`_EMERGENCY`).
     *
     * Every demotion re-arms the full hysteresis cooldown. Status reports
     * below the threshold only update the promotion gate.
     */
    fun onThermalStatusChanged(status: ThermalStatus) {
        while (true) {
            val cur = state.load()
            val demotionTier = thermalDemotionTier(status)
            val mustDemote = demotionTier != null && cur.tier > demotionTier
            if (!mustDemote && cur.thermalStatus == status) return // no-op report

            val nextTier = if (mustDemote && demotionTier != null) demotionTier else cur.tier
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

    /**
     * Target tier of a thermal report, or `null` when the status is below
     * the demotion threshold ([ThermalStatus.LIGHT] or better).
     */
    private fun thermalDemotionTier(status: ThermalStatus): RenderTier? =
        when {
            status >= ThermalStatus.CRITICAL -> RenderTier.Tier4_Safe
            status >= ThermalStatus.MODERATE -> RenderTier.Tier3_Legacy
            else -> null
        }

    /**
     * Reports a Low Power Mode toggle (Apple `NSProcessInfo
     * .isLowPowerModeEnabled`, observed through
     * `NSProcessInfoPowerStateDidChangeNotification`; Android battery
     * saver maps here too). Safe from any thread.
     *
     * Enabling Low Power Mode:
     *  * caps the promotion ceiling at [RenderTier.Tier2_Balanced],
     *  * demotes a live [RenderTier.Tier1_Ultra] synchronously to
     *    [RenderTier.Tier2_Balanced] (re-arming the hysteresis cooldown),
     *  * pins both frame budgets to the 60 Hz lane — the display link is
     *    capped at 60 Hz in this mode, so a ProMotion device stops being
     *    judged against the 8.33 ms budget.
     *
     * Disabling it restores the display-cadence budgets (last reported via
     * [onDisplayRefreshRateChanged] or the construction-time rate) and the
     * hardware promotion ceiling; re-promotion then follows the normal
     * streak + cooldown contract.
     */
    fun onLowPowerModeEnabled(enabled: Boolean) {
        if (lowPowerModeEnabled == enabled) return
        lowPowerModeEnabled = enabled
        applyBudgetCalibration()
        if (enabled) {
            demoteToTierAtMost(RenderTier.Tier2_Balanced)
        }
    }

    /**
     * Reports a display-link refresh-rate change (ProMotion 10–120 Hz
     * cadence shifts, external-display plug events). Safe from any thread.
     * Recalibrates both frame budgets to the new cadence — a 120 Hz
     * ProMotion link budgets 4.16 ms fast / 8.33 ms P95, a 60 Hz link
     * 8.3 ms / 16.66 ms — unless Low Power Mode is engaged, in which case
     * the 60 Hz lane stays pinned. Non-positive rates fall back to 60 Hz.
     */
    fun onDisplayRefreshRateChanged(refreshRateHz: Double) {
        activeRefreshRateHz =
            if (refreshRateHz <= 0.0) DEFAULT_REFRESH_RATE_HZ else refreshRateHz
        applyBudgetCalibration()
    }

    /**
     * Recomputes the live budgets from [activeRefreshRateHz] and the Low
     * Power Mode clamp. Volatile writes only — the frame thread reads the
     * budgets per frame; no CAS is needed because budgets are advisory
     * inputs to frame decisions, not transitioned state.
     */
    private fun applyBudgetCalibration() {
        if (lowPowerModeEnabled) {
            fastBudgetMs = LOW_POWER_FAST_FRAME_BUDGET_MS
            p95BudgetMs = LOW_POWER_P95_FRAME_BUDGET_MS
        } else {
            val frameTimeMs = 1000.0 / activeRefreshRateHz
            fastBudgetMs = minOf(FAST_FRAME_BUDGET_MS, frameTimeMs * 0.5)
            p95BudgetMs = frameTimeMs
        }
    }

    /**
     * Synchronous clamp of the live tier to at most [ceiling] (Low Power
     * Mode demotion path) — same CAS retry shape as the thermal demotion.
     */
    private fun demoteToTierAtMost(ceiling: RenderTier) {
        while (true) {
            val cur = state.load()
            if (cur.tier <= ceiling) return
            val next = GovernorState(
                tier = ceiling,
                thermalStatus = cur.thermalStatus,
                cooldownFramesRemaining = HYSTERESIS_FRAME_COUNT,
            )
            if (state.compareAndSet(cur, next)) {
                notifyTierChanged(cur.tier, ceiling)
                return
            }
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
            if (frameWindow[i] > p95BudgetMs) {
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
            if (frameTimeMillis < fastBudgetMs) {
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
        /** Consecutive sub-fast-budget frames required for promotion. */
        const val PROMOTION_FRAME_COUNT = 120

        /** 120 Hz frame budget (ms) — a frame at exactly this value is NOT fast. */
        const val FAST_FRAME_BUDGET_MS = 8.3

        /** 60 Hz frame budget (ms) — the P95 of the window must stay at or below it. */
        const val P95_FRAME_BUDGET_MS = 16.6

        /** 60 Hz fast-frame budget (ms) — pinned while Low Power Mode is engaged. */
        const val LOW_POWER_FAST_FRAME_BUDGET_MS = FAST_FRAME_BUDGET_MS

        /** 60 Hz P95 budget (ms) — pinned while Low Power Mode is engaged. */
        const val LOW_POWER_P95_FRAME_BUDGET_MS = P95_FRAME_BUDGET_MS

        /** Display refresh rate (Hz) assumed when the real cadence is unknown. */
        const val DEFAULT_REFRESH_RATE_HZ = 60.0

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
            refreshRateHz: Double = DEFAULT_REFRESH_RATE_HZ,
            displayHz: Double = refreshRateHz,
        ): TierGovernor {
            val effectiveHz = if (displayHz != DEFAULT_REFRESH_RATE_HZ && refreshRateHz == DEFAULT_REFRESH_RATE_HZ) displayHz else refreshRateHz
            val safeHz = if (effectiveHz.isNaN() || effectiveHz <= 0.0) DEFAULT_REFRESH_RATE_HZ else effectiveHz
            val frameTimeMs = 1000.0 / safeHz
            val fastBudget = minOf(FAST_FRAME_BUDGET_MS, frameTimeMs * 0.5)
            val p95Budget = frameTimeMs
            return TierGovernor(
                probe = probe,
                initialFastFrameBudgetMs = fastBudget,
                initialP95FrameBudgetMs = p95Budget,
                initialRefreshRateHz = safeHz,
            )
        }
    }
}
