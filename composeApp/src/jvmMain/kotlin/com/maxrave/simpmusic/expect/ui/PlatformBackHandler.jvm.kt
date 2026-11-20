package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // Desktop uses the on-screen back button.
}

@Composable
internal actual fun BindNavigationBackHandler(
    navController: NavHostController,
) {
}

