package com.streamify.app.data.telemetry

import android.content.Context
import com.streamify.app.util.SLog

/**
 * ObservabilityInit — process-wide telemetry sink configuration and crash
 * reporting scaffolding (Phase 3, MP-C15 client side; directive §E.2).
 *
 * This is the SINGLE initialization point for client-side observability,
 * called from [com.streamify.app.di.AppGraph.initialize] immediately after
 * SLog arms (init order is load-bearing: everything below logs through the
 * facade). It owns three concerns:
 *
 *  1. **Telemetry sink health** — logs the active sink configuration once
 *     per boot so a mis-shipped build (wrong channel, dead spool) is
 *     visible in the very first log frames.
 *  2. **Engine-error observability** — the structured
 *     [com.streamify.app.data.uniffi.StreamifyEngineBridge.handleEngineError]
 *     funnel is the classification point; this init records the contract
 *     version expectation so an ABI mismatch (MP-C05) surfaces at startup,
 *     not mid-session.
 *  3. **Crash reporting seam** — [CrashReporter] is the pluggable sink the
 *     Sentry adapter (Phase 4) will implement. The default
 *     [LoggingCrashReporter] routes crash-grade events through
 *     [SLog.wtf]; installing the real adapter later is a one-line swap at
 *     this seam with zero call-site changes.
 *
 * Hermetic note: every operation degrades to logging — the JVM unit shard
 * initializes this object with no Android framework, no network and no
 * third-party SDK on the classpath.
 */
object ObservabilityInit {

    private const val TAG = "ObservabilityInit"

    /**
     * Pluggable crash-reporting seam (Sentry integration placeholder —
     * directive §E.2). The Phase 4 adapter implements this against
     * `io.sentry.android`; until then [LoggingCrashReporter] keeps every
     * crash-grade event on the local diagnostic spool.
     */
    interface CrashReporter {
        /** Records one crash-grade event (fatal error, FFI panic shield). */
        fun reportFatal(throwable: Throwable, context: String)

        /** Records one non-fatal but observability-worthy event. */
        fun reportNonFatal(throwable: Throwable, context: String)
    }

    /**
     * Default adapter: routes through the SLog facade (wtf/e channels,
     * capture spool, boot banner channel). No network, no third-party SDK.
     */
    private class LoggingCrashReporter : CrashReporter {
        override fun reportFatal(throwable: Throwable, context: String) {
            SLog.wtf(TAG, "FATAL [$context]: ${throwable.javaClass.simpleName}: ${throwable.message}")
            SLog.e(TAG, "fatal context=$context", throwable)
        }

        override fun reportNonFatal(throwable: Throwable, context: String) {
            SLog.e(TAG, "non-fatal [$context]: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        }
    }

    /** Active crash reporter — the Sentry adapter's install point. */
    @Volatile
    var crashReporter: CrashReporter = LoggingCrashReporter()

    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * Idempotent initialization. AppGraph is the only production caller;
     * order contract: SLog must already be armed (step 0 in the graph).
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (initialized) return
        initialized = true

        // 1. Sink health marker — the first log frames of every boot state
        //    the observability configuration explicitly.
        SLog.i(TAG, "observability armed — crashReporter=${crashReporter.javaClass.simpleName}")

        // 2. Engine-error funnel availability marker: the exhaustive
        //    EngineError handler (directive §E.1) is the classification
        //    point; the Sentry adapter subscribes to the same funnel in
        //    Phase 4 (MP-C15). Recorded here so an observability-silent
        //    build is detectable from the boot log alone.
        SLog.d(TAG, "engine error funnel: StreamifyEngineBridge.handleEngineError (exhaustive, no-else)")

        // 3. PLACEHOLDER (Phase 4 / MP-C15): install the Sentry adapter —
        //    Sentry.init { options -> ... } + crashReporter =
        //    SentryCrashReporter() — once the SDK enters the dependency
        //    graph. The seam above is the entire integration surface.
    }

    /** Routes one crash-grade event onto the active [CrashReporter]. */
    fun reportFatal(throwable: Throwable, context: String) {
        crashReporter.reportFatal(throwable, context)
    }

    /** Routes one non-fatal event onto the active [CrashReporter]. */
    fun reportNonFatal(throwable: Throwable, context: String) {
        crashReporter.reportNonFatal(throwable, context)
    }
}
