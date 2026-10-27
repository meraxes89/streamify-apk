package com.streamify.app.media.audio

import android.content.Context
import android.content.SharedPreferences
import com.streamify.app.BuildConfig
import com.streamify.app.util.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AudioRoutePolicy — the USE_RUST_AUDIO dogfood toggle & routing ladder
 * (Phase 4a / MP-HAL07, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Parallel testing of the new Rust/native audio sink WITHOUT breaking
 * production Android playback. The effective route resolves through a
 * strict three-step ladder:
 *
 *   1. COMPILE-TIME FLAG — `BuildConfig.USE_RUST_AUDIO`, sourced from
 *      the Gradle property `streamify.useRustAudio` (default FALSE in
 *      every build type — release ships the legacy Media3 pipeline):
 *
 *        ./gradlew :app:assembleDebug -Pstreamify.useRustAudio=true
 *
 *   2. DEBUG-ONLY RUNTIME OVERRIDE — a developer-settings toggle
 *      persisted by [RustAudioSettings]. Release builds IGNORE it
 *      ([BuildConfig.DEBUG] gate): a toggle can never smuggle the
 *      dogfood path into production.
 *
 *   3. NATIVE EDGE PROBE — [NativeAudioSink.probeEdges]: even with the
 *      flag on, the route engages ONLY when the frozen JNI edges
 *      (create/write/f32-read/flush/destroy) actually resolved in this
 *      process. A device without Alpha's `jni_bridge_audio_sinks`
 *      symbols, a hermetic JVM shard, a stripped split-APK — all degrade
 *      GRACEFULLY to the legacy DefaultAudioSink path with one logged
 *      reason (never a crash, never silence).
 *
 * SWITCHING SEMANTICS (clean + memory-safe by construction):
 *  • The route is resolved when the playback stack builds its audio
 *    sink (`PlaybackService.buildAudioSink`) and stays fixed for that
 *    player's lifetime — Media3 cannot hot-swap an AudioSink mid-flight,
 *    and refusing to try is exactly what makes the switch leak-free.
 *  • A toggle flip applies at the NEXT session (service restart /
 *    next play). The pipeline itself additionally tolerates
 *    start/stop/re-start idempotently (RustAudioPipeline).
 */
object AudioRoutePolicy {

    /** The resolved audio route for one playback-stack generation. */
    enum class Route {
        /** Legacy Media3 DefaultAudioSink → AudioTrack (production). */
        LEGACY_MEDIA3,

        /** Rust f32 pull pipeline: Media3 → ring → feeder → PCM_FLOAT track. */
        RUST_AUDIO_PULL,
    }

    /** Why a route resolved the way it did (dogfood diagnostics). */
    data class RouteDecision(
        val route: Route,
        val compileFlag: Boolean,
        val runtimeOverride: Boolean,
        val nativeEdges: Boolean,
        val reason: String,
    )

    private val _lastDecision = MutableStateFlow(
        RouteDecision(Route.LEGACY_MEDIA3, false, false, false, "not-yet-resolved")
    )

    /** Latest route decision (reactive; observers log/alert on changes). */
    val lastDecision: StateFlow<RouteDecision> = _lastDecision.asStateFlow()

    /**
     * Resolves the effective route ONCE per call site (the playback
     * service invokes this while building its renderers factory).
     */
    fun resolve(): RouteDecision {
        val decision = resolveWith(
            compileFlag = BuildConfig.USE_RUST_AUDIO,
            debugBuild = BuildConfig.DEBUG,
            runtimeOverride = RustAudioSettings.isRuntimeOverrideEnabled(),
            edges = RustAudioSinkJni.probeEdges(),
        )
        _lastDecision.value = decision
        return decision
    }

    /**
     * The pure decision ladder over injected inputs (the JVM unit shard
     * drives the full matrix without a device, an .so, or a debug build —
     * the same function the production [resolve] feeds).
     */
    fun resolveWith(
        compileFlag: Boolean,
        debugBuild: Boolean,
        runtimeOverride: Boolean,
        edges: RustAudioSinkJni.NativeSinkEdges,
    ): RouteDecision {
        val overrideHonored = debugBuild && runtimeOverride
        val wanted = compileFlag || overrideHonored
        val edgesPresent = edges.supportsFloatPullPath

        val decision = when {
            !wanted -> RouteDecision(
                route = Route.LEGACY_MEDIA3,
                compileFlag = compileFlag,
                runtimeOverride = overrideHonored,
                nativeEdges = edgesPresent,
                reason = "flag off (compile=$compileFlag runtimeOverride=$overrideHonored)",
            )
            !edgesPresent -> RouteDecision(
                route = Route.LEGACY_MEDIA3,
                compileFlag = compileFlag,
                runtimeOverride = overrideHonored,
                nativeEdges = false,
                reason = "flag on but native sink edges unresolved " +
                    "(create=${edges.createSink} write=${edges.writeFloat} " +
                    "f32=${edges.readFramesF32} flush=${edges.flush} " +
                    "destroy=${edges.destroy}) — awaiting Alpha's " +
                    "jni_bridge_audio_sinks.cpp; degrading to Media3",
            )
            else -> RouteDecision(
                route = Route.RUST_AUDIO_PULL,
                compileFlag = compileFlag,
                runtimeOverride = overrideHonored,
                nativeEdges = true,
                reason = "dogfood engaged (compile=$compileFlag " +
                    "runtimeOverride=$overrideHonored)",
            )
        }
        SLog.i(TAG, "route=${decision.route}: ${decision.reason}")
        return decision
    }

    private const val TAG = "AudioRoutePolicy"
}

/**
 * Debug-build developer-settings persistence for the runtime dogfood
 * override (the "controlled via developer settings" arm of MP-HAL07).
 *
 * Pure [SharedPreferences] behind a StateFlow — release builds are gated
 * at [AudioRoutePolicy.resolve] and never read this store.
 */
object RustAudioSettings {

    private const val PREFS_NAME = "rust_audio_dogfood"
    private const val KEY_RUNTIME_OVERRIDE = "use_rust_audio_runtime"

    private val _runtimeOverride = MutableStateFlow(false)

    /** Live runtime-override state (debug builds only, by contract). */
    val runtimeOverride: StateFlow<Boolean> = _runtimeOverride.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** Binds the backing store; idempotent; call from AppGraph step 0c. */
    fun initialize(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _runtimeOverride.value = store.getBoolean(KEY_RUNTIME_OVERRIDE, false)
        SLog.i(
            TAG,
            "initialized (debug=${BuildConfig.DEBUG}, " +
                "runtimeOverride=${_runtimeOverride.value})"
        )
    }

    /** Reads the override without a store binding (false when unbound). */
    fun isRuntimeOverrideEnabled(): Boolean = _runtimeOverride.value

    /**
     * Sets the runtime override and persists it. Takes effect at the next
     * playback session (documented switching semantics — see
     * [AudioRoutePolicy]).
     */
    fun setRuntimeOverride(enabled: Boolean) {
        _runtimeOverride.value = enabled
        val store = prefs
        store?.edit()?.putBoolean(KEY_RUNTIME_OVERRIDE, enabled)?.apply()
        SLog.i(TAG, "runtime override -> $enabled (applies next session)")
    }

    private const val TAG = "RustAudioSettings"
}
