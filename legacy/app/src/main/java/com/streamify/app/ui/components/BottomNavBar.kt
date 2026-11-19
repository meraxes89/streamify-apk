package com.streamify.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.streamify.app.ui.components.yt.YtBottomNavBar

enum class BottomTab { HOME, SEARCH, LIBRARY, DOWNLOADS }

@Composable
fun BottomNavBar(
    currentTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val currentRoute = when (currentTab) {
        BottomTab.HOME -> "home"
        BottomTab.SEARCH -> "search"
        BottomTab.LIBRARY -> "library"
        BottomTab.DOWNLOADS -> "downloads"
    }

    YtBottomNavBar(
        currentRoute = currentRoute,
        onNavigate = { route ->
            val tab = when (route) {
                "home" -> BottomTab.HOME
                "search" -> BottomTab.SEARCH
                "library" -> BottomTab.LIBRARY
                "downloads" -> BottomTab.DOWNLOADS
                else -> BottomTab.HOME
            }
            onTabSelected(tab)
        },
        alpha = alpha,
        modifier = modifier
    )
}
