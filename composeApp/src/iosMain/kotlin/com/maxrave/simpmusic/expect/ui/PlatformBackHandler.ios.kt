package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavHostController

object IosBackHandlerRegistry {
    private val handlers = mutableListOf<() -> Boolean>()

    fun register(handler: () -> Boolean) {
        handlers.add(handler)
    }

    fun unregister(handler: () -> Boolean) {
        handlers.remove(handler)
    }

    fun handleBack(): Boolean {
        for (i in handlers.indices.reversed()) {
            if (handlers[i].invoke()) {
                return true
            }
        }
        return false
    }
}

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    if (!enabled) return
    DisposableEffect(onBack) {
        val handler: () -> Boolean = {
            onBack()
            true
        }
        IosBackHandlerRegistry.register(handler)
        onDispose {
            IosBackHandlerRegistry.unregister(handler)
        }
    }
}

@Composable
internal actual fun BindNavigationBackHandler(
    navController: NavHostController,
) {
}
