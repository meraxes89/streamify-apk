package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberStartupPermissionRequester(): StartupPermissionRequester =
    remember {
        object : StartupPermissionRequester {
            override fun hasAllRequiredPermissions(): Boolean = true

            override fun requestMissingPermissions() {}
        }
    }
