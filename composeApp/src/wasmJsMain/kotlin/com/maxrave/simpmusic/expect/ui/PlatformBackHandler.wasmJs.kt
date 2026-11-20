package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.browser.window

object WebBackHandlerRegistry {
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
        WebBackHandlerRegistry.register(handler)
        onDispose {
            WebBackHandlerRegistry.unregister(handler)
        }
    }
}

@Composable
internal actual fun BindNavigationBackHandler(
    navController: NavHostController,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val visitedStack = remember { mutableListOf<String>() }
    val stateHolder = remember {
        object {
            var isNavigatingFromPopState = false
            var isHandlingInAppBack = false
        }
    }

    DisposableEffect(navController) {
        try {
            window.history.replaceState(null, "")
        } catch (_: Throwable) {}

        val popStateCallback: (org.w3c.dom.events.Event) -> Unit = {
            if (stateHolder.isHandlingInAppBack) {
                stateHolder.isHandlingInAppBack = false
            } else if (WebBackHandlerRegistry.handleBack()) {
                try {
                    window.history.pushState(null, "")
                } catch (_: Throwable) {}
            } else if (navController.previousBackStackEntry != null) {
                stateHolder.isNavigatingFromPopState = true
                navController.popBackStack()
            }
        }

        window.addEventListener("popstate", popStateCallback)

        onDispose {
            window.removeEventListener("popstate", popStateCallback)
        }
    }

    LaunchedEffect(navBackStackEntry?.id) {
        val entry = navBackStackEntry ?: return@LaunchedEffect
        val entryId = entry.id
        val idx = visitedStack.indexOf(entryId)
        if (idx == -1) {
            // Forward navigation
            visitedStack.add(entryId)
            if (visitedStack.size > 1 && !stateHolder.isNavigatingFromPopState) {
                try {
                    window.history.pushState(null, "")
                } catch (_: Throwable) {}
            }
        } else {
            // Back navigation to an existing entry
            while (visitedStack.size > idx + 1) {
                visitedStack.removeAt(visitedStack.lastIndex)
            }
            if (!stateHolder.isNavigatingFromPopState) {
                // In-app back navigation
                stateHolder.isHandlingInAppBack = true
                try {
                    window.history.back()
                } catch (_: Throwable) {}
            }
        }
        stateHolder.isNavigatingFromPopState = false
    }
}

