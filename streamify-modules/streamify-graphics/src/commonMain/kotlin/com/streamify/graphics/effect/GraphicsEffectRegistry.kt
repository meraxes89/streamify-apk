package com.streamify.graphics.effect

import kotlin.concurrent.Volatile

/**
 * Thread-safe registry for pluggable visual effects in the Sovereign Graphics Engine.
 *
 * Allows downstream modules or plugins to dynamically register new shaders and visual
 * effects without modifying the core graphics module.
 */
object GraphicsEffectRegistry {

    private val registry = mutableMapOf<String, GraphicsEffect>()

    init {
        // Register core built-in effects
        register(LiquidGlassEffect)
        register(VisualizerEffect)
    }

    /**
     * Registers a [GraphicsEffect] into the engine. Overwrites any existing registration
     * with the same [GraphicsEffect.id].
     */
    @Synchronized
    fun register(effect: GraphicsEffect) {
        registry[effect.id] = effect
    }

    /**
     * Retrieves an effect by its identifier.
     */
    @Synchronized
    fun get(id: String): GraphicsEffect? = registry[id]

    /**
     * Returns true if an effect with the given identifier is registered.
     */
    @Synchronized
    fun contains(id: String): Boolean = registry.containsKey(id)

    /**
     * Returns a snapshot list of all currently registered effects.
     */
    @Synchronized
    fun all(): List<GraphicsEffect> = registry.values.toList()

    /**
     * Unregisters an effect by its identifier.
     */
    @Synchronized
    fun unregister(id: String): GraphicsEffect? = registry.remove(id)
}
