package com.streamify.app.di

import android.content.Context
import com.streamify.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide dependency graph — the single wiring point for Streamify's
 * service-layer singletons.
 *
 * Historically Application.onCreate issued a dozen imperative
 * `X.init(this)` calls inline, with no single place documenting the init
 * order contract (SLog first, then fleet config, then repositories, then
 * engines). AppGraph owns that contract and the process-wide
 * [applicationScope] so subsystems never hand-roll their own root scope.
 *
 * Repository access is exposed through interface types ([trackRepository])
 * so ViewModels depend on abstractions while the object singletons remain
 * the production implementation.
 */
object AppGraph {

    /** Root scope for work that must outlive any Activity/ViewModel/Service. */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Injectable dispatcher set for testability. */
    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()

    /** Track catalog access, by abstraction. */
    val trackRepository: com.streamify.app.data.repository.TrackRepositoryApi =
        com.streamify.app.data.repository.TrackRepository

    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * Wires the service-layer graph. Idempotent; the Application is the
     * only intended caller. Init order is load-bearing:
     * 0) SLog (everything after it logs through the facade),
     * 1) fleet config + HTTP tracing,
     * 2) repositories + telemetry,
     * 3) native DB (async), vector store + ONNX (async, off cold-start path),
     * 4) device/remote services,
     * 5) authenticated YouTube resolution.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        // 0. SLog FIRST — every subsequent subsystem logs through it.
        //    Installs the crash hook and starts the disk spool.
        com.streamify.app.util.SLog.initialize(context)
        com.streamify.app.util.SLog.logBootBanner(BuildConfig.VERSION_NAME)

        val appContext = context.applicationContext
        com.streamify.app.data.network.YouTubeStreamResolver.appContext = appContext

        // 1. Remote fleet adaptation: pull release-free client overrides (2KB JSON).
        com.streamify.app.util.FleetConfig.initialize(appContext)

        // 1b. Scraper stale-while-revalidate persistence (Phase 2 — Gaps
        //     #15/#20/#26): radio / daylist / blend payloads survive offline
        //     opens. Disk-backed, size-capped, corruption-tolerant.
        com.streamify.app.data.network.YouTubeMusicRadioApi.cacheDir =
            java.io.File(appContext.cacheDir, "scraper_swr")
        com.streamify.app.data.discovery.DaylistScheduler.cacheDir =
            java.io.File(appContext.cacheDir, "scraper_swr")

        // HTTP wire tracing follows the user's diagnostic-logging toggle.
        com.streamify.app.data.network.NetworkEngine.setHttpTracing(
            com.streamify.app.util.SLog.captureEnabled
        )

        // 2. Ensure TrackRepository application context and Telemetry Engine are bound.
        com.streamify.app.data.repository.TrackRepository.appContext = appContext

        // 2b. Taste Profile exclusion store (Gap #27) — loads before any
        //     telemetry / seed-pool path can fire.
        com.streamify.app.data.discovery.TasteProfileGuard.initialize(appContext)
        com.streamify.app.data.telemetry.YtStatsTelemetryEngine.initFromContext(appContext)

        // 3. Ensure database directory exists and initialize asynchronously off the main thread.
        try {
            val dbFile = appContext.getDatabasePath("streamify.db")
            dbFile.parentFile?.mkdirs()
            applicationScope.launch(Dispatchers.IO) {
                com.streamify.app.data.persistence.DatabaseInitializer.startInitialization(dbFile.absolutePath)
            }
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to schedule NativeBridge Database init", e)
        }

        // 3+4. COLD-START BUDGET: vector-store mmap and the multi-MB ONNX
        // asset copy + native session creation are NOT needed for the first
        // frame. Deferring them off the main thread removes 0.5–3s of frozen
        // window on eMMC-class devices.
        applicationScope.launch {
            // 3. Ensure files directory exists for VectorStore
            try {
                val vectorBinFile = java.io.File(appContext.filesDir, "vectors.bin")
                vectorBinFile.parentFile?.mkdirs()
                com.streamify.app.data.NativeBridge.initVectorStore(vectorBinFile.absolutePath)
            } catch (e: Throwable) {
                com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize NativeBridge VectorStore", e)
            }

            // 4. Copy ONNX model from assets if present
            try {
                val modelFile = java.io.File(appContext.filesDir, "clap_int8.onnx")
                if (!modelFile.exists()) {
                    try {
                        appContext.assets.open("models/clap_int8.onnx").use { input ->
                            java.io.FileOutputStream(modelFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Throwable) {
                        com.streamify.app.util.SLog.w("StreamifyApp", "CLAP ONNX model not found in assets, skipping")
                    }
                }
                if (modelFile.exists()) {
                    com.streamify.app.data.NativeBridge.initAudioPipeline(modelFile.absolutePath)
                }
            } catch (e: Throwable) {
                com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize AudioPipeline", e)
            }
        }

        // 5. Initialize device & remote services safely.
        try {
            com.streamify.app.media.audio.AudioDeviceManager.init(appContext)
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize AudioDeviceManager", e)
        }

        try {
            com.streamify.app.data.supabase.SupabaseClient.init(appContext)
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize SupabaseClient", e)
        }

        try {
            com.streamify.app.media.playback.OnlineTrackProcessor.init(appContext)
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize OnlineTrackProcessor", e)
        }

        try {
            com.streamify.app.util.StreamifyHapticEngine.init(appContext)
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize StreamifyHapticEngine", e)
        }

        // Phase 5 — centralized multi-sensory haptic engine (throttle-aware,
        // actuator-seamed for JVM tests).
        try {
            com.streamify.app.ui.util.HapticFeedbackManager.instance =
                com.streamify.app.ui.util.HapticFeedbackManager(
                    actuator = com.streamify.app.ui.util.AndroidHapticActuator(appContext),
                    clock = com.streamify.app.ui.util.UptimeHapticClock()
                )
        } catch (e: Throwable) {
            com.streamify.app.util.SLog.e("StreamifyApp", "Failed to initialize HapticFeedbackManager", e)
        }

        try {
            com.streamify.app.media.ingestion.LibrarySyncWorker.schedulePeriodicSync(appContext)
        } catch (e: Throwable) {
            // Non-blocking
        }

        try {
            com.streamify.app.media.sync.ThermalGovernorManager.init(appContext)
        } catch (e: Throwable) {
            // Non-blocking
        }

        // 6. Authenticated YouTube resolution: expose the harvested session to
        // the stream resolver (SAPISIDHASH + cookies past the 2026 bot-wall).
        com.streamify.app.data.network.YouTubeStreamResolver.ytSessionProvider = {
            val m = com.streamify.app.data.spotify.SpotifyAuthManager(appContext)
            (m.getYtAuthHeader() ?: "") to (m.getYtRawCookies() ?: "")
        }
    }
}
