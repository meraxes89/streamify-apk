package com.streamify.app.data.uniffi

import com.streamify.app.util.SLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StreamifyEngineBridge — high-level Kotlin client wrapper over the UniFFI
 * control plane (Phase 2 canary, UFFI-02 / DEC-01).
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
 *  • [Availability.READY] — engine cdylib linked and dispatch wired;
 *  • [Availability.NATIVE_UNAVAILABLE] — engine `.so` not linkable (JVM
 *    unit tests, stubbed devices, split-APK mid-install): operations fail
 *    fast with [EngineError.EngineUnavailable] instead of crashing;
 *  • [Availability.MOCK] — a test/preview backend is installed via
 *    [installBackend] (see `StreamifyEngineBridgeTest`).
 *
 * Wiring contract with Engineer Alpha (the rust/ subtree boundary): when the
 * UFFI-01 `streamify.udl` contract publishes generated `uniffi.*` symbols,
 * the raw calls land inside [NativeEngineBackend] — and only there. This
 * file is the single wiring point on the app side of the boundary.
 */
object StreamifyEngineBridge {

    private const val TAG = "StreamifyEngineBridge"

    /** The Rust cdylib backing the UniFFI bindings (rust/Cargo.toml [lib]). */
    private const val ENGINE_LIBRARY = "streamify_core_rs"

    // ═════════════════════════════════════════════════════════════════════
    // Error surface — idiomatic projection of the Rust EngineError enum
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Sealed projection of the Rust `EngineError` enum from
     * `rust/src/streamify.udl`. A [Throwable] subtype so typed failures ride
     * `Result<T>` natively — callers pattern-match the sealed cases straight
     * out of `exceptionOrNull()`. Every bridge failure is one of these; raw
     * Throwables (including [UnsatisfiedLinkError]) never escape unclassified.
     */
    sealed class EngineError(message: String, cause: Throwable? = null) : Throwable(message, cause) {
        abstract override val message: String

        /** Network transport failure (DNS, TLS, HTTP 5xx…). [retryable] hints at backoff policy. */
        data class Network(override val message: String, val retryable: Boolean = true) : EngineError(message)

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
             * When Alpha's contract lands, the generated `uniffi.*.EngineError`
             * maps onto these cases in [NativeEngineBackend] before ever
             * reaching a caller.
             */
            fun fromThrowable(t: Throwable): EngineError = when (t) {
                is EngineError -> t
                is UnsatisfiedLinkError -> EngineUnavailable(
                    "native engine not linked: ${t.message}"
                )
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

    // ═════════════════════════════════════════════════════════════════════
    // Backend seam
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Dispatch seam between the bridge and a concrete engine backend.
     * Implementations must be thread-safe and must not throw — failures are
     * reported as `Result.failure(EngineError)`.
     */
    interface EngineBackend {
        val availability: Availability

        /** Canary #1: resolver plumbing — fresh CDN URL for a video id. */
        fun resolveStreamUrl(videoId: String): Result<ResolvedStream>

        /** Canary #2: pure candidate hashing — deterministic digest of a search payload. */
        fun hashCandidates(jsonPayload: String): Result<String>
    }

    /**
     * Production backend. Owns exactly the lifecycle half today: it probes
     * the cdylib once (never leaking [UnsatisfiedLinkError] out of object
     * init — same house pattern as `NativeBridge`) and, until Alpha's
     * UFFI-01 contract publishes generated `uniffi.*` symbols, truthfully
     * reports [Availability.NATIVE_UNAVAILABLE] with
     * [EngineError.EngineUnavailable] failures. Raw generated calls land
     * here — and only here — once the bindings exist.
     */
    private class NativeEngineBackend : EngineBackend {

        /** One-shot probe: true when the engine cdylib linked cleanly. */
        private val libraryLoaded: Boolean = try {
            System.loadLibrary(ENGINE_LIBRARY)
            true
        } catch (t: Throwable) {
            // JVM unit tests (no host .so), stubbed devices, split APKs
            // mid-install: every environment degrades instead of crashing.
            SLog.st(TAG, "engine cdylib unavailable; bridge stays degraded", t)
            false
        }

        override val availability: Availability
            get() = if (libraryLoaded) Availability.READY else Availability.NATIVE_UNAVAILABLE

        override fun resolveStreamUrl(videoId: String): Result<ResolvedStream> =
            unavailable("resolveStreamUrl")

        override fun hashCandidates(jsonPayload: String): Result<String> =
            unavailable("hashCandidates")

        private fun unavailable(op: String): Result<Nothing> = Result.failure(
            EngineError.EngineUnavailable(
                "$op: UniFFI dispatch not wired yet " +
                    "(awaiting UFFI-01 contract; engine lib linked=$libraryLoaded)"
            )
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    private val backendRef = AtomicReference<EngineBackend>(NativeEngineBackend())

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
        backendRef.set(NativeEngineBackend())
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
