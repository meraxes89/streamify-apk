package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

@Composable
internal expect fun BindNavigationBackHandler(
    navController: NavHostController,
)

