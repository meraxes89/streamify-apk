package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // iOS uses on-screen navigation buttons and Compose gesture navigation
}

@Composable
internal actual fun BindNavigationBackHandler(
    navController: NavHostController,
) {
}
