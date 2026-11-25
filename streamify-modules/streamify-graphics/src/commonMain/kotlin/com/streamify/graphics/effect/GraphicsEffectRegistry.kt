package com.streamify.graphics.effect

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Thread-safe registry for pluggable visual effects in the Sovereign Graphics Engine.
 *
 * Allows downstream modules or plugins to dynamically register new shaders and visual
 * effects without modifying the core graphics module.
 *
 * Locking note: `@Synchronized` is JVM-only and does not resolve in common
 * code, so the registry guards the mutable map with a tiny test-and-set
 * spinlock (the same pattern GraphicsTelemetry uses) — every public method
 * is safe from any thread on every KMP target, commonMain included.
 */
@OptIn(ExperimentalAtomicApi::class)
object GraphicsEffectRegistry {

    private val registry = mutableMapOf<String, GraphicsEffect>()

    private val spinlock = AtomicInt(LOCK_FREE)

    init {
        // Register core built-in effects
        register(LiquidGlassEffect)
        register(VisualizerEffect)
    }

    /**
     * Registers a [GraphicsEffect] into the engine. Overwrites any existing registration
     * with the same [GraphicsEffect.id].
     */
    fun register(effect: GraphicsEffect) = withLock {
        registry[effect.id] = effect
    }

    /**
     * Retrieves an effect by its identifier.
     */
    fun get(id: String): GraphicsEffect? = withLock { registry[id] }

    /**
     * Returns true if an effect with the given identifier is registered.
     */
    fun contains(id: String): Boolean = withLock { registry.containsKey(id) }

    /**
     * Returns a snapshot list of all currently registered effects.
     */
    fun all(): List<GraphicsEffect> = withLock { registry.values.toList() }

    /**
     * Unregisters an effect by its identifier.
     */
    fun unregister(id: String): GraphicsEffect? = withLock { registry.remove(id) }

    /** Test-and-set spinlock; critical sections are a handful of stores. */
    private inline fun <T> withLock(block: () -> T): T {
        while (!spinlock.compareAndSet(LOCK_FREE, LOCK_HELD)) { /* spin */ }
        try {
            return block()
        } finally {
            spinlock.store(LOCK_FREE)
        }
    }

    private const val LOCK_FREE = 0
    private const val LOCK_HELD = 1
}
