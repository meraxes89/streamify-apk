package com.streamify.app.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction over coroutine dispatchers.
 *
 * Every hard-coded `Dispatchers.IO / .Default / .Main` reference inside
 * business logic makes that code untestable with a deterministic
 * (virtual-clock) scheduler. Injecting a [DispatcherProvider] keeps the
 * production wiring identical while giving tests a seam to control time.
 *
 * Adopt incrementally: new code and refactored paths should take a
 * [DispatcherProvider]; legacy singletons may keep direct Dispatchers
 * references until they are touched.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

/** Production dispatcher set. */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
