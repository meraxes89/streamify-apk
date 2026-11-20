package com.maxrave.simpmusic.expect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
internal actual fun BindNavigationBackHandler(
    navController: NavHostController,
) {
}

