package com.streamify.app.data.uniffi

import com.streamify.app.data.NativeBridge
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
    // Jam control plane (UFFI-04 — Kotlin CRDT amputation)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * One sealed jam CRDT mutation — field-for-field mirror of the frozen
     * 48-byte wire envelope (`opId | sender | type | policy | cadId | frac |
     * target`, little-endian). The bridge owns its own projection so the
     * seam never depends on the `jam` package: [applyJamDelta] receives
     * peer deltas off the network, [applyJamLocalOp] seals locally
     * generated UI mutations — the Rust core folds both.
     */
    data class JamDeltaOp(
        val opId: Long,
        val sender: Long,
        val type: Int,
        val policy: Int,
        val cadId: Long,
        val frac: Double,
        val target: Long,
    )

    /**
     * One element of the authoritative jam queue fold:
     * `(fracBits | addOpId | cadId)` triplets, `Double.fromBits` for the
     * fractional index.
     */
    data class JamQueueSlot(
        val fracBits: Long,
        val addOpId: Long,
        val cadId: Long,
    )

    /**
     * Post-apply receipt: the authoritative queue fold as of the applied
     * op. An empty [queue] means the fold is not observable right now
     * (engine quiescent) — the caller keeps its last view, exactly like
     * the pre-amputation `jamCrdtFold()`-null early return.
     */
    data class JamDeltaReceipt(val queue: List<JamQueueSlot>)

    /**
     * Death-pivot extrapolation decision from the native governor
     * (`jamExtrapolatePivot`): `code` 0 = OK (pivot to [positionMs]),
     * 1 = BEYOND_END (advance the room to the next queue head),
     * 2 = TRACK_MISMATCH (re-handshake).
     */
    data class JamPivotDecision(val code: Long, val positionMs: Long) {
        val isBeyondEnd: Boolean get() = code == CODE_BEYOND_END
        val isTrackMismatch: Boolean get() = code == CODE_TRACK_MISMATCH

        companion object {
            const val CODE_OK = 0L
            const val CODE_BEYOND_END = 1L
            const val CODE_TRACK_MISMATCH = 2L
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

        // ── Jam control plane (UFFI-04) ──────────────────────────────────────
        // The UFFI-01 UDL does not carry the jam plane yet (Alpha's UFFI-03
        // ledger item). Until the contract extension lands, the production
        // backend dispatches these through the FROZEN JNI contract
        // (rust jam_crdt / jam_governor) — the same Rust core the UDL will
        // expose. Backends that do not implement them report
        // [EngineError.EngineUnavailable], never a raw Throwable.

        /** Applies a locally sealed jam CRDT op (UI mutation). Default: not wired. */
        fun applyJamLocalOp(op: JamDeltaOp): Result<JamDeltaReceipt> =
            backendOpUnavailable("applyJamLocalOp")

        /** Applies a jam CRDT delta received from a peer. Default: not wired. */
        fun applyJamDelta(op: JamDeltaOp): Result<JamDeltaReceipt> =
            backendOpUnavailable("applyJamDelta")

        /** Folds the authoritative jam queue. Default: not wired. */
        fun foldJamQueue(): Result<List<JamQueueSlot>> =
            backendOpUnavailable("foldJamQueue")

        /** Queries the engine-side jam CRDT state hash. Default: not wired. */
        fun queryJamStateHash(): Result<String> =
            backendOpUnavailable("queryJamStateHash")

        /** Deterministic successor election (native governor). Default: not wired. */
        fun electJamSuccessor(
            participants: List<String>,
            hostNonce: String,
            leaseExpired: Boolean,
        ): Result<String?> = backendOpUnavailable("electJamSuccessor")

        /** Death-pivot playhead extrapolation (native governor). Default: not wired. */
        fun extrapolateJamPivot(
            lastPosMs: Long,
            lastTickSyncedNanos: Long,
            nowSyncedNanos: Long,
            durationMs: Long,
            trackMatches: Boolean,
        ): Result<JamPivotDecision> = backendOpUnavailable("extrapolateJamPivot")

        /** Partition-heal arbitration (native governor advisory). Default: not wired. */
        fun isAdvisoryJamSuccessor(
            participants: List<String>,
            hostNonce: String,
            selfNonce: String,
            recentlySeen: Boolean,
        ): Result<Boolean> = backendOpUnavailable("isAdvisoryJamSuccessor")

        /**
         * Clean engine teardown hook (strict architecture firewall, Phase
         * 4). Default: not wired — [StreamifyEngineBridge.release] still
         * resets the telemetry surface and stays idempotent.
         */
        fun releaseEngine(): Result<Unit> = backendOpUnavailable("releaseEngine")
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

        // ── Jam control plane (UFFI-04, interim JNI dispatch) ────────────────
        //
        // The jam plane is not part of the UFFI-01 UDL yet (Alpha's UFFI-03
        // ledger item: election, CRDT op apply, Merkle reconciliation →
        // UniFFI). Until the contract extension lands, the production
        // backend dispatches jam ops through the FROZEN JNI contract — the
        // same Rust core (jam_crdt / jam_governor) the UDL will expose. This
        // is the single wiring point: when the contract ships, this block
        // flips to the generated proxies with zero changes above the seam.
        //
        // Hermetic note: every NativeBridge wrapper degrades to a refusal
        // (false / null / empty) when the cdylib is absent, so the JVM unit
        // shard observes [EngineError.EngineUnavailable] and callers fall
        // back to their legacy snapshot paths — identical pre-amputation
        // behavior.

        /** Wire ops carry no checksum — the 48-byte envelope is sealed by structure. */
        private val JAM_WIRE_CHECKSUM_NONE = -1L

        override fun applyJamLocalOp(op: JamDeltaOp): Result<JamDeltaReceipt> =
            applyJamOpViaJni(op, sealLocally = true)

        override fun applyJamDelta(op: JamDeltaOp): Result<JamDeltaReceipt> =
            applyJamOpViaJni(op, sealLocally = false)

        private fun applyJamOpViaJni(op: JamDeltaOp, sealLocally: Boolean): Result<JamDeltaReceipt> {
            val accepted = if (sealLocally) {
                NativeBridge.jamCrdtApplyLocalOp(
                    op.opId, op.sender, op.type, op.policy, op.cadId, op.frac, op.target
                )
            } else {
                NativeBridge.jamCrdtApplyWireOp(
                    op.opId, op.sender, op.type, op.policy, op.cadId, op.frac, op.target,
                    checksum = JAM_WIRE_CHECKSUM_NONE
                )
            }
            if (!accepted) {
                // The JNI wrapper conflates "engine unavailable" (no cdylib —
                // hermetic JVM, stubbed devices) with "op refused by the
                // state machine" into one false. Both degrade the same way
                // upstream (legacy snapshot path), so they share the code.
                return Result.failure(
                    EngineError.EngineUnavailable(
                        "jam CRDT ${if (sealLocally) "local op" else "delta"} not applied " +
                            "(engine unavailable or refused)"
                    )
                )
            }
            return Result.success(JamDeltaReceipt(queue = foldJamQueueViaJni()))
        }

        override fun foldJamQueue(): Result<List<JamQueueSlot>> =
            Result.success(foldJamQueueViaJni())

        private fun foldJamQueueViaJni(): List<JamQueueSlot> {
            val fold = NativeBridge.jamCrdtFold() ?: return emptyList()
            val queue = fold.first
            val slots = ArrayList<JamQueueSlot>(queue.size / 3 + 1)
            var i = 0
            while (i + 2 < queue.size) {
                slots.add(
                    JamQueueSlot(fracBits = queue[i], addOpId = queue[i + 1], cadId = queue[i + 2])
                )
                i += 3
            }
            return slots
        }

        override fun queryJamStateHash(): Result<String> = Result.failure(
            EngineError.EngineUnavailable(
                "queryJamStateHash: the jam state-hash surface awaits the UFFI-03 contract " +
                    "extension — the engine-side hash is the sole authority since the Kotlin " +
                    "Merkle amputation (UFFI-04)"
            )
        )

        override fun electJamSuccessor(
            participants: List<String>,
            hostNonce: String,
            leaseExpired: Boolean,
        ): Result<String?> = Result.success(
            NativeBridge.jamElectSuccessor(participants, hostNonce, leaseExpired)
        )

        override fun extrapolateJamPivot(
            lastPosMs: Long,
            lastTickSyncedNanos: Long,
            nowSyncedNanos: Long,
            durationMs: Long,
            trackMatches: Boolean,
        ): Result<JamPivotDecision> {
            // The JNI governor speaks the millisecond domain; the bridge API
            // speaks the app's canonical synced-nanosecond domain.
            val out = NativeBridge.jamExtrapolatePivot(
                lastPosMs = lastPosMs,
                lastTickMonoMs = lastTickSyncedNanos / 1_000_000L,
                currentSyncedMonoMs = nowSyncedNanos / 1_000_000L,
                durationMs = durationMs,
                trackMatches = trackMatches,
            )
            return Result.success(JamPivotDecision(code = out[0], positionMs = out[1]))
        }

        override fun isAdvisoryJamSuccessor(
            participants: List<String>,
            hostNonce: String,
            selfNonce: String,
            recentlySeen: Boolean,
        ): Result<Boolean> = Result.success(
            NativeBridge.jamIsAdvisorySuccessor(participants, hostNonce, selfNonce, recentlySeen)
        )

        override fun releaseEngine(): Result<Unit> {
            val h = handle ?: return Result.success(Unit) // degraded — nothing held
            // The UFFI-01 contract carries no destructor yet; generated
            // uniffi handles grow `destroy()`/`close()` when one lands —
            // probe reflectively so the teardown lights up with zero app
            // changes when the contract grows it.
            val teardown = h.javaClass.methods.firstOrNull {
                it.name == "destroy" || it.name == "close" || it.name == "release"
            }
            return try {
                teardown?.invoke(h)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(EngineError.fromThrowable(t))
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
        synchronized(releaseLock) { released = false }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Clean teardown (strict architecture firewall, Phase 4)
    // ═════════════════════════════════════════════════════════════════════

    /** Guards the one-shot release latch below. */
    private val releaseLock = Any()

    /** One-shot latch — [release] is idempotent across service recreations. */
    @Volatile private var released = false

    /**
     * Clean engine teardown for a dying playback service (strict
     * architecture firewall, Phase 4):
     *
     *  • dispatches [EngineBackend.releaseEngine] so a wired backend frees
     *    its engine-side allocations (the reflection handle probes for a
     *    uniffi `destroy()`/`close()` when the contract grows one —
     *    degraded backends hold nothing);
     *  • returns the telemetry surface to the dormant session state
     *    (`Idle` + `EMPTY` stats — the session-scoped underrun tally dies
     *    with the session, never leaking into the next one);
     *  • is IDEMPOTENT — a latch makes repeated calls (service recreate
     *    races, double onDestroy) harmless, and [reset] re-arms it for
     *    the next session.
     *
     * The singleton itself stays re-usable: the next service publish →
     * initializeEngine re-arms the engine exactly as a cold start would.
     */
    fun release() {
        synchronized(releaseLock) {
            if (released) return
            released = true
        }
        dispatch("release") { it.releaseEngine() }
        resetTelemetry()
        SLog.i(TAG, "released: backend teardown dispatched, telemetry dormant")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Telemetry surface (MEDIA-02 / UI-01)
    // ═════════════════════════════════════════════════════════════════════

    /** Guards the telemetry state pair below (listener callbacks + refresh). */
    private val telemetryLock = Any()

    private val _playbackState = MutableStateFlow(PlaybackState.Idle)

    /**
     * App-observed playback lifecycle — PlaybackService mirrors every
     * Media3 transition here (MEDIA-02). This is the app-side mirror: the
     * Rust-side view (UDL `get_playback_state`) advances via Rust-internal
     * producers — the UFFI-01 contract exposes no state setter across the
     * FFI; [enginePlaybackState] reads the engine-side view for
     * diagnostics.
     */
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _bufferStats = MutableStateFlow(BufferStats.EMPTY)

    /**
     * Live buffering-health telemetry for UI consumption (UI-01). Fed by
     * two publishers: app-side rebuffer detection in [recordPlaybackState]
     * (a `Playing -> Buffering` transition starved the ring) and the
     * engine-authoritative snapshot pulled by [refreshBufferStats].
     */
    val bufferStats: StateFlow<BufferStats> = _bufferStats.asStateFlow()

    /**
     * Returns the telemetry surface to the dormant session state
     * (`Idle` + `EMPTY` stats). Deterministic-isolation hook for the JVM
     * unit shard (the bridge is process-wide; without this, test-order
     * could leak a session tally between test methods). Production callers
     * manage sessions through [recordPlaybackState] — entering
     * [PlaybackState.Idle] carries the same reset semantics for the
     * underrun tally.
     */
    fun resetTelemetry() {
        synchronized(telemetryLock) {
            _playbackState.value = PlaybackState.Idle
            _bufferStats.value = BufferStats.EMPTY
        }
    }

    /**
     * Records an app-observed playback state transition (MEDIA-02 hook —
     * PlaybackService's Player.Listener is the sole production caller).
     * Never throws and never dispatches across the FFI: the app-side
     * mirror is pure Kotlin state.
     *
     * Underrun accounting: a `Playing -> Buffering` transition is a
     * rebuffer (the ring starved mid-playback) and bumps
     * [bufferStats].underrunCount; a transition into [PlaybackState.Idle]
     * ends the session and resets the tally. Initial buffering out of
     * Idle/Paused is a warm-up, not an underrun.
     */
    fun recordPlaybackState(state: PlaybackState) {
        synchronized(telemetryLock) {
            val previous = _playbackState.value
            _playbackState.value = state
            when {
                state == PlaybackState.Buffering && previous == PlaybackState.Playing -> {
                    val stats = _bufferStats.value
                    _bufferStats.value = stats.copy(underrunCount = stats.underrunCount + 1u)
                }
                state == PlaybackState.Idle && previous != PlaybackState.Idle -> {
                    _bufferStats.value = _bufferStats.value.copy(underrunCount = 0u)
                }
            }
        }
    }

    /**
     * Arms the Rust core engine (UDL `initialize()` — one-way, idempotent
     * `Idle -> Buffering`). Called by SharedPlaybackEngine.publish when a
     * Media3 session goes live. Degrades silently on unwired backends.
     */
    fun initializeEngine(): Result<Unit> = dispatch("initializeEngine") { it.initializeEngine() }

    /**
     * Sets the engine output volume (UDL `set_volume(f32)`); clamped to
     * `[0, 1]` before dispatch (Rust clamps again behind the FFI).
     */
    fun setVolume(volume: Float): Result<Unit> {
        if (volume.isNaN()) {
            return Result.failure(EngineError.InvalidInput("volume must not be NaN"))
        }
        return dispatch("setVolume") { it.setVolume(volume.coerceIn(0f, 1f)) }
    }

    /**
     * Reads the engine-side lifecycle state (UDL `get_playback_state`) —
     * the Rust view, distinct from the app-observed [playbackState] mirror.
     * Diagnostics surface; unwired backends report [EngineError.EngineUnavailable].
     */
    fun enginePlaybackState(): Result<PlaybackState> =
        dispatch("enginePlaybackState") { it.fetchPlaybackState() }

    /**
     * Pulls the engine-side buffering-health snapshot (UDL
     * `get_buffer_stats`) and publishes it to [bufferStats]. The returned
     * value is the raw engine sample; the flow carries the sample fused
     * with the app-side underrun tally (`max`) — Rust-side producers are
     * not wired yet (`record_buffer_stats` is engine-internal), so the
     * app-detected count rides along until the engine supersedes it.
     */
    fun refreshBufferStats(): Result<BufferStats> =
        dispatch("refreshBufferStats") { it.fetchBufferStats() }
            .onSuccess { snapshot ->
                synchronized(telemetryLock) {
                    val app = _bufferStats.value
                    _bufferStats.value = snapshot.copy(
                        underrunCount = maxOf(snapshot.underrunCount, app.underrunCount)
                    )
                }
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

    // ═════════════════════════════════════════════════════════════════════
    // Jam control plane (UFFI-04 — Result<T>, never throws)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Applies a locally generated jam CRDT op (sealed by the engine).
     * On success the receipt carries the authoritative post-apply queue
     * fold; on failure the caller degrades to its legacy snapshot path.
     */
    fun applyJamLocalOp(op: JamDeltaOp): Result<JamDeltaReceipt> {
        validateJamOp(op)?.let { return Result.failure(it) }
        return dispatch("applyJamLocalOp") { it.applyJamLocalOp(op) }
    }

    /**
     * Applies a jam CRDT delta received from a peer over the network —
     * the single ingress funnel for remote mutations (UFFI-04). On
     * success the UI queue state updates from the receipt's fold via the
     * existing StateFlow collectors.
     */
    fun applyJamDelta(op: JamDeltaOp): Result<JamDeltaReceipt> {
        validateJamOp(op)?.let { return Result.failure(it) }
        return dispatch("applyJamDelta") { it.applyJamDelta(op) }
    }

    /** Folds the authoritative jam queue from the engine. */
    fun foldJamQueue(): Result<List<JamQueueSlot>> =
        dispatch("foldJamQueue") { it.foldJamQueue() }

    /**
     * Queries the engine-side jam CRDT state hash. Surface for the
     * upcoming reconciliation contract; unwired backends report
     * [EngineError.EngineUnavailable].
     */
    fun queryJamStateHash(): Result<String> =
        dispatch("queryJamStateHash") { it.queryJamStateHash() }

    /**
     * Deterministic successor election over the live participant set
     * (native governor). Null result = no successor / room should end.
     */
    fun electJamSuccessor(
        participants: List<String>,
        hostNonce: String,
        leaseExpired: Boolean,
    ): Result<String?> = dispatch("electJamSuccessor") {
        it.electJamSuccessor(participants, hostNonce, leaseExpired)
    }

    /** Death-pivot playhead extrapolation (native governor). */
    fun extrapolateJamPivot(
        lastPosMs: Long,
        lastTickSyncedNanos: Long,
        nowSyncedNanos: Long,
        durationMs: Long,
        trackMatches: Boolean,
    ): Result<JamPivotDecision> = dispatch("extrapolateJamPivot") {
        it.extrapolateJamPivot(lastPosMs, lastTickSyncedNanos, nowSyncedNanos, durationMs, trackMatches)
    }

    /** Partition-heal arbitration: should [selfNonce] yield to [hostNonce]? */
    fun isAdvisoryJamSuccessor(
        participants: List<String>,
        hostNonce: String,
        selfNonce: String,
        recentlySeen: Boolean,
    ): Result<Boolean> = dispatch("isAdvisoryJamSuccessor") {
        it.isAdvisoryJamSuccessor(participants, hostNonce, selfNonce, recentlySeen)
    }

    /**
     * Contract validation shared by both apply funnels — mirrors the frozen
     * 48-byte envelope's field ranges (JamOpWire): type `1..3`, policy
     * `0..1`, finite fractional index. Returns the typed failure or null
     * when the op is well-formed.
     */
    private fun validateJamOp(op: JamDeltaOp): EngineError? = when {
        op.type !in JAM_OP_ADD..JAM_OP_REORDER ->
            EngineError.InvalidInput("jam op type ${op.type} outside the frozen 1..3 range")
        op.policy !in 0..1 ->
            EngineError.InvalidInput("jam op policy ${op.policy} outside the frozen 0..1 range")
        !op.frac.isFinite() ->
            EngineError.InvalidInput("jam op fractional index must be finite")
        else -> null
    }

    private const val JAM_OP_ADD = 1
    private const val JAM_OP_REORDER = 3

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
