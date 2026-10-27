package com.streamify.app.data.uniffi

import com.streamify.app.util.SLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StreamifyEngineBridge — high-level Kotlin client wrapper over the UniFFI
 * control plane (Phase 2 canary UFFI-02 / DEC-01; live wiring in Phase 3,
 * UFFI-04).
 *
 * The raw generated UniFFI proxies (package `uniffi.<namespace>`, produced by
 * `generateUniffiKotlinBindings` from `rust/src/streamify.udl`) never leak
 * past this object. Callers get:
 *
 *  • [Result]-returning operations that never throw across the seam —
 *  • an idiomatic [EngineError] sealed hierarchy projecting the Rust
 *    `EngineError` enum, and
 *  • a swappable [EngineBackend] seam with a graceful degraded state so
 *    `./gradlew testDebugUnitTest` runs hermetically with no host `.so`.
 *
 * Lifecycle states ([Availability]):
 *  • [Availability.READY] — engine cdylib linked, generated `uniffi.*`
 *    proxies on the classpath and dispatch wired to the engine handle;
 *  • [Availability.NATIVE_UNAVAILABLE] — engine `.so` not linkable (JVM
 *    unit tests, stubbed devices, split-APK mid-install) or the generated
 *    proxies are not on the classpath yet (bindgen entry point pending on
 *    the rust side): operations fail fast with [EngineError.EngineUnavailable]
 *    instead of crashing;
 *  • [Availability.MOCK] — a test/preview backend is installed via
 *    [installBackend] (see `StreamifyEngineBridgeTest`).
 *
 * Wiring contract with Engineer Alpha (the rust/ subtree boundary): the
 * UFFI-01 `streamify.udl` contract has landed — `NativeEngineBackend`
 * dispatches onto the generated `uniffi.streamify.StreamifyEngineImpl`
 * handle via reflection. Reflection (not compile-time references) is the
 * deliberate seam: every environment that compiles without the generated
 * sources (the hermetic JVM unit shard runs with
 * `-PskipExternalNativeBuild=true`, so `generateUniffiKotlinBindings` is
 * SKIPPED and `uniffi.*` classes never exist at compile time) keeps an
 * identical binary. The moment the crate's `uniffi-bindgen` entry point
 * ships, real builds light the dispatch up with zero further app changes.
 * This file is the single wiring point on the app side of the boundary.
 */
object StreamifyEngineBridge {

    private const val TAG = "StreamifyEngineBridge"

    /** The Rust cdylib backing the UniFFI bindings (rust/Cargo.toml [lib]). */
    private const val ENGINE_LIBRARY = "streamify_core_rs"

    /**
     * Generated proxy class for the UDL `StreamifyEngine` interface
     * (namespace `streamify` → package `uniffi.streamify`). Present on the
     * runtime classpath only when `generateUniffiKotlinBindings` has run.
     */
    private const val ENGINE_PROXY_CLASS = "uniffi.streamify.StreamifyEngineImpl"

    // ═════════════════════════════════════════════════════════════════════
    // Error surface — idiomatic projection of the Rust EngineError enum
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Sealed projection of the Rust `EngineError` enum from
     * `rust/src/streamify.udl` (`NetworkError | CodecError | InvalidState |
     * BufferUnderrun | InternalError` — UFFI-04 maps every variant). A
     * [Throwable] subtype so typed failures ride `Result<T>` natively —
     * callers pattern-match the sealed cases straight out of
     * `exceptionOrNull()`. Every bridge failure is one of these; raw
     * Throwables (including [UnsatisfiedLinkError]) never escape
     * unclassified.
     */
    sealed class EngineError(message: String, cause: Throwable? = null) : Throwable(message, cause) {
        abstract override val message: String

        /** Network transport failure (DNS, TLS, HTTP 5xx…) — Rust `NetworkError`. [retryable] hints at backoff policy. */
        data class Network(override val message: String, val retryable: Boolean = true) : EngineError(message)

        /** Container/codec parse or decode failure — Rust `CodecError`. */
        data class Codec(override val message: String) : EngineError(message)

        /** Operation invoked from the wrong lifecycle state — Rust `InvalidState`. */
        data class InvalidState(override val message: String) : EngineError(message)

        /** Playback starved waiting for buffered media — Rust `BufferUnderrun`. */
        data class BufferUnderrun(override val message: String) : EngineError(message)

        /** Unexpected internal engine fault — Rust `InternalError`. */
        data class Internal(override val message: String) : EngineError(message)

        /** Caller-supplied input violated the contract (blank id, malformed JSON…). */
        data class InvalidInput(override val message: String) : EngineError(message)

        /** Entity looked up by key does not exist (404-equivalent). */
        data class NotFound(override val message: String) : EngineError(message)

        /** Auth/token failure — the engine refused the request. */
        data class Unauthorized(override val message: String) : EngineError(message)

        /**
         * The native engine is not loaded: missing `.so` on the JVM/host,
         * or the UniFFI dispatch has not been wired yet. Degrade, don't crash.
         */
        class EngineUnavailable(override val message: String) : EngineError(message)

        /** The Rust `catch_unwind` shield tripped — a panic never crosses the FFI boundary. */
        class EnginePanicked(override val message: String) : EngineError(message)

        /** Failure mode the contract has not named yet. [cause] rides the standard Throwable slot. */
        class Unexpected(override val message: String, cause: Throwable? = null) : EngineError(message, cause)

        companion object {
            /**
             * Lowers raw failure modes at the seam onto the sealed hierarchy.
             * The generated `uniffi.streamify.EngineError.*` exceptions (from
             * the UDL `[Error]` enum) are recognized by class simple-name —
             * duck-typed on purpose so the mapping works in every compile
             * environment (the generated classes are absent from the
             * hermetic JVM classpath by design; see [NativeEngineBackend]).
             * Reflection dispatch wraps engine exceptions in
             * [java.lang.reflect.InvocationTargetException] — unwrapped here
             * so the FFI cause classifies, not the wrapper.
             */
            fun fromThrowable(t: Throwable): EngineError {
                val cause = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
                return when (cause) {
                    is EngineError -> cause
                    is UnsatisfiedLinkError -> EngineUnavailable(
                        "native engine not linked: ${cause.message}"
                    )
                    else -> fromFfiVariantName(cause)
                        ?: fromJdkFailureMode(cause)
                }
            }

            /**
             * Maps the generated UniFFI exception classes (simple names
             * mirror the Rust `EngineError` variants) onto the sealed
             * hierarchy. Returns null for anything else.
             */
            private fun fromFfiVariantName(t: Throwable): EngineError? =
                when (t.javaClass.simpleName) {
                    "NetworkError" -> Network(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "network error: stream acquisition or transport failed"
                    )
                    "CodecError" -> Codec(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "codec error: parse or decode failed"
                    )
                    "InvalidState" -> InvalidState(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "invalid state: operation not valid for the current lifecycle state"
                    )
                    "BufferUnderrun" -> BufferUnderrun(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "buffer underrun: playback starved waiting for media"
                    )
                    "InternalError" -> Internal(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "internal error: unexpected engine fault"
                    )
                    else -> null
                }

            /** JDK failure modes that have an idiomatic sealed counterpart. */
            private fun fromJdkFailureMode(t: Throwable): EngineError = when (t) {
                is IllegalArgumentException -> InvalidInput(t.message ?: "invalid argument")
                is NoSuchElementException -> NotFound(t.message ?: "not found")
                is SecurityException -> Unauthorized(t.message ?: "unauthorized")
                is IllegalStateException -> EnginePanicked(t.message ?: "engine invariant violated")
                else -> Unexpected(t.message ?: t.javaClass.simpleName, t)
            }
        }
    }

    /** Engine availability as observed through the bridge. */
    enum class Availability { READY, NATIVE_UNAVAILABLE, MOCK }

    /** Resolved CDN stream value (resolver-plumbing canary surface). */
    data class ResolvedStream(val videoId: String, val url: String)

    /**
     * App-side projection of the UDL `PlaybackState` enum
     * (`Idle | Buffering | Playing | Paused | Error`) — PascalCase per
     * Kotlin convention; [NativeEngineBackend] maps the generated
     * SCREAMING_SNAKE constants onto these by name.
     */
    enum class PlaybackState { Idle, Buffering, Playing, Paused, Error }

    /**
     * App-side projection of the UDL `BufferStats` dictionary
     * (`buffered_duration_ms | underrun_count | fill_ratio`). Unsigned
     * widths mirror the contract exactly — no information loss across the
     * seam.
     */
    data class BufferStats(
        /** Media duration currently buffered, in milliseconds. */
        val bufferedDurationMs: ULong,
        /** Cumulative underrun events for the current session. */
        val underrunCount: UInt,
        /** Ring fill ratio in `[0.0, 1.0]`. */
        val fillRatio: Float,
    ) {
        companion object {
            /** Dormant-engine snapshot (matches the Rust `Default`). */
            val EMPTY = BufferStats(bufferedDurationMs = 0uL, underrunCount = 0u, fillRatio = 0f)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Backend seam
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Dispatch seam between the bridge and a concrete engine backend.
     * Implementations must be thread-safe and must not throw — failures are
     * reported as `Result.failure(EngineError)`.
     *
     * UFFI-04: the engine control plane (`initialize` / `set_volume` /
     * `get_playback_state` / `get_buffer_stats` from the UFFI-01 UDL)
     * carries interface defaults so Phase 2 implementations keep compiling
     * untouched — a backend that does not override them reports
     * [EngineError.EngineUnavailable], never a raw Throwable.
     */
    interface EngineBackend {
        val availability: Availability

        /** Canary #1: resolver plumbing — fresh CDN URL for a video id. */
        fun resolveStreamUrl(videoId: String): Result<ResolvedStream>

        /** Canary #2: pure candidate hashing — deterministic digest of a search payload. */
        fun hashCandidates(jsonPayload: String): Result<String>

        /**
         * Arms the engine (UDL `initialize()` — one-way, idempotent
         * `Idle -> Buffering`). Default: not wired in this backend.
         */
        fun initializeEngine(): Result<Unit> = backendOpUnavailable("initializeEngine")

        /** Sets the output volume (UDL `set_volume(f32)`, clamped `[0,1]`). Default: not wired. */
        fun setVolume(volume: Float): Result<Unit> = backendOpUnavailable("setVolume")

        /** Reads the engine-side lifecycle state (UDL `get_playback_state()`). Default: not wired. */
        fun fetchPlaybackState(): Result<PlaybackState> = backendOpUnavailable("fetchPlaybackState")

        /** Reads the engine-side buffering health snapshot (UDL `get_buffer_stats()`). Default: not wired. */
        fun fetchBufferStats(): Result<BufferStats> = backendOpUnavailable("fetchBufferStats")
    }

    /** Shared default for seam operations a backend does not implement. */
    private fun backendOpUnavailable(op: String): Result<Nothing> = Result.failure(
        EngineError.EngineUnavailable("$op: not implemented by ${backendRef.get().availability} backend")
    )

    /**
     * Production backend (UFFI-04 live wiring). Owns the full lifecycle
     * half: [create] probes the cdylib, loads the generated
     * `uniffi.streamify.StreamifyEngineImpl` proxy class reflectively, builds
     * the engine handle and resolves the four UDL `StreamifyEngine` methods
     * once — after construction the backend is immutable and thread-safe.
     *
     * Every probe failure degrades instead of crashing (never a leaked
     * [UnsatisfiedLinkError] out of object init — same house pattern as
     * `NativeBridge`):
     *
     *  • cdylib not linkable (JVM unit tests with
     *    `-PskipExternalNativeBuild=true`, stubbed devices, split APKs
     *    mid-install) → [Availability.NATIVE_UNAVAILABLE];
     *  • cdylib linked but generated proxies absent (the crate's
     *    `uniffi-bindgen` entry point is still pending on the rust side, so
     *    `generateUniffiKotlinBindings` stays SKIPPED) →
     *    [Availability.NATIVE_UNAVAILABLE] with the pending-proxies reason —
     *    the dynamic-reflection fallback until the generated proxies ship;
     *  • both present → [Availability.READY], dispatching to the handle.
     */
    private class NativeEngineBackend private constructor(
        /**
         * The `uniffi.streamify.StreamifyEngine` instance behind the FFI
         * trampolines — the raw handle dispatch rides on. Null while the
         * backend is degraded.
         */
        private val handle: Any?,
        /** Resolved `StreamifyEngine` methods on [handle]; null while degraded. */
        private val methods: EngineMethods?,
        /** Human-readable reason the backend stayed degraded (diagnostics). */
        private val unwiredReason: String?,
    ) : EngineBackend {

        override val availability: Availability
            get() = if (handle != null && methods != null) Availability.READY
                else Availability.NATIVE_UNAVAILABLE

        override fun resolveStreamUrl(videoId: String): Result<ResolvedStream> =
            notInContract("resolveStreamUrl")

        override fun hashCandidates(jsonPayload: String): Result<String> =
            notInContract("hashCandidates")

        override fun initializeEngine(): Result<Unit> {
            val h = handle ?: return engineUnavailable("initializeEngine")
            val m = methods?.initialize ?: return engineUnavailable("initializeEngine")
            return try {
                m.invoke(h)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(EngineError.fromThrowable(t))
            }
        }

        override fun setVolume(volume: Float): Result<Unit> {
            val h = handle ?: return engineUnavailable("setVolume")
            val m = methods?.setVolume ?: return engineUnavailable("setVolume")
            return try {
                m.invoke(h, volume)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(EngineError.fromThrowable(t))
            }
        }

        override fun fetchPlaybackState(): Result<PlaybackState> {
            val h = handle ?: return engineUnavailable("fetchPlaybackState")
            val m = methods?.getPlaybackState ?: return engineUnavailable("fetchPlaybackState")
            return try {
                val raw = m.invoke(h)
                val mapped = PlaybackState.values().firstOrNull {
                    it.name.equals(raw.toString(), ignoreCase = true)
                }
                if (mapped != null) {
                    Result.success(mapped)
                } else {
                    Result.failure(
                        EngineError.Unexpected("unknown engine PlaybackState constant: $raw")
                    )
                }
            } catch (t: Throwable) {
                Result.failure(EngineError.fromThrowable(t))
            }
        }

        override fun fetchBufferStats(): Result<BufferStats> {
            val h = handle ?: return engineUnavailable("fetchBufferStats")
            val m = methods?.getBufferStats ?: return engineUnavailable("fetchBufferStats")
            return try {
                val raw = m.invoke(h)
                val clazz = raw.javaClass
                Result.success(
                    BufferStats(
                        bufferedDurationMs = readStatNumber(raw, clazz, "bufferedDurationMs").toLong().toULong(),
                        underrunCount = readStatNumber(raw, clazz, "underrunCount").toInt().toUInt(),
                        fillRatio = readStatNumber(raw, clazz, "fillRatio").toFloat(),
                    )
                )
            } catch (t: Throwable) {
                Result.failure(EngineError.fromThrowable(t))
            }
        }

        /**
         * Reads one numeric property off a generated record instance: the
         * public getter first (uniffi data classes expose Kotlin properties),
         * then the backing field. `ULong`/`UInt` fields compile to erased
         * `long`/`int` — both access paths box to [Number], so unsigned
         * widths round-trip exactly.
         */
        private fun readStatNumber(target: Any, clazz: Class<*>, property: String): Number {
            val getter = "get" + property.replaceFirstChar { it.uppercase() }
            clazz.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?.let { return it.invoke(target) as Number }
            return clazz.getDeclaredField(property).let { field ->
                field.isAccessible = true
                field.get(target) as Number
            }
        }

        private fun engineUnavailable(op: String): Result<Nothing> = Result.failure(
            EngineError.EngineUnavailable("$op: engine dispatch not wired ($degradedReason)")
        )

        private val degradedReason: String
            get() = unwiredReason ?: "engine dispatch not wired"

        /** Canary surfaces are not part of the UFFI-01 engine contract yet. */
        private fun notInContract(op: String): Result<Nothing> = Result.failure(
            EngineError.EngineUnavailable(
                "$op: not part of the UFFI-01 engine contract yet " +
                    "(resolver/hash canaries await a contract extension; wired=${availability == Availability.READY})"
            )
        )

        /**
         * The four resolved UDL `StreamifyEngine` methods. Method names use
         * the stable UniFFI camelCase convention (`initialize`, `setVolume`,
         * `getPlaybackState`, `getBufferStats`) with a case-insensitive
         * fallback — tolerant to bindgen template drift.
         */
        private class EngineMethods(
            val initialize: java.lang.reflect.Method,
            val setVolume: java.lang.reflect.Method,
            val getPlaybackState: java.lang.reflect.Method,
            val getBufferStats: java.lang.reflect.Method,
        ) {
            companion object {
                fun resolve(clazz: Class<*>): EngineMethods {
                    fun find(name: String, params: Int): java.lang.reflect.Method =
                        clazz.methods.firstOrNull {
                            it.name == name && it.parameterCount == params
                        } ?: clazz.methods.firstOrNull {
                            it.name.equals(name, ignoreCase = true) && it.parameterCount == params
                        } ?: throw NoSuchMethodException("${clazz.name}.$name/$params")

                    return EngineMethods(
                        initialize = find("initialize", 0),
                        setVolume = find("setVolume", 1),
                        getPlaybackState = find("getPlaybackState", 0),
                        getBufferStats = find("getBufferStats", 0),
                    )
                }
            }
        }

        companion object {
            /** Probe chain: cdylib → generated proxies → handle + methods. */
            fun create(): NativeEngineBackend {
                // Gate 1 — the cdylib. JVM unit tests, stubbed devices and
                // split APKs mid-install degrade here instead of crashing.
                val libraryLoaded = try {
                    System.loadLibrary(ENGINE_LIBRARY)
                    true
                } catch (t: Throwable) {
                    SLog.st(TAG, "engine cdylib unavailable; bridge stays degraded", t)
                    false
                }
                if (!libraryLoaded) {
                    return degraded("cdylib $ENGINE_LIBRARY not linkable")
                }

                // Gate 2 — the generated proxies. `uniffi.streamify.*`
                // classes exist only when generateUniffiKotlinBindings has
                // run (bindgen entry point pending on the rust side — the
                // dynamic reflection fallback state).
                val proxyClass = try {
                    Class.forName(ENGINE_PROXY_CLASS)
                } catch (t: Throwable) {
                    SLog.st(TAG, "UniFFI generated proxies not on classpath; bridge stays degraded", t)
                    null
                } ?: return degraded("UniFFI generated proxies pending ($ENGINE_PROXY_CLASS)")

                // Gate 3 — the engine handle and the method table.
                return try {
                    val handle = proxyClass.getDeclaredConstructor().newInstance()
                    NativeEngineBackend(
                        handle = handle,
                        methods = EngineMethods.resolve(proxyClass),
                        unwiredReason = null,
                    )
                } catch (t: Throwable) {
                    SLog.st(TAG, "engine handle construction failed; bridge stays degraded", t)
                    degraded("engine handle construction failed: ${t.javaClass.simpleName}")
                }
            }

            private fun degraded(reason: String) =
                NativeEngineBackend(handle = null, methods = null, unwiredReason = reason)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    private val backendRef = AtomicReference<EngineBackend>(NativeEngineBackend.create())

    private val _availability = MutableStateFlow(backendRef.get().availability)

    /** Live availability — observers (DI graphs, settings UI) react to swaps. */
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    /**
     * Installs a mock/preview backend. JVM unit tests use this to run the
     * full bridge surface hermetically (no host `.so`, no network):
     * availability flips to [Availability.MOCK] and every operation
     * dispatches to [mock] until [reset].
     */
    fun installBackend(mock: EngineBackend) {
        backendRef.set(mock)
        _availability.value = mock.availability
    }

    /** Restores the production native backend (test cleanup / forced reload). */
    fun reset() {
        backendRef.set(NativeEngineBackend.create())
        _availability.value = backendRef.get().availability
    }

    // ═════════════════════════════════════════════════════════════════════
    // Canary operations (Result<T> — never throws)
    // ═════════════════════════════════════════════════════════════════════

    /** Canary #1: resolver plumbing. Fresh CDN stream for [videoId]. */
    fun resolveStreamUrl(videoId: String): Result<ResolvedStream> {
        if (videoId.isBlank()) {
            return Result.failure(EngineError.InvalidInput("videoId must not be blank"))
        }
        return dispatch("resolveStreamUrl") { it.resolveStreamUrl(videoId) }
    }

    /** Canary #2: pure candidate hashing. Deterministic digest of [jsonPayload]. */
    fun hashCandidates(jsonPayload: String): Result<String> {
        if (jsonPayload.isBlank()) {
            return Result.failure(EngineError.InvalidInput("jsonPayload must not be blank"))
        }
        return dispatch("hashCandidates") { it.hashCandidates(jsonPayload) }
    }

    /**
     * Single dispatch funnel: contract validation happens above, backend
     * failures and unexpected [Throwable]s are lowered onto [EngineError]
     * here — callers can never see a raw exception out of the bridge.
     */
    private fun <T> dispatch(op: String, block: (EngineBackend) -> Result<T>): Result<T> =
        try {
            block(backendRef.get())
        } catch (t: Throwable) {
            SLog.st(TAG, "$op failed (backend=${backendRef.get().availability})", t)
            Result.failure(EngineError.fromThrowable(t))
        }
}
