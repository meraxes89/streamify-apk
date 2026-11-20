package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberLocalNetworkPermission(): LocalNetworkPermissionRequester =
    remember {
        object : LocalNetworkPermissionRequester {
            override fun requestIfNeeded(url: String) = Unit
        }
    }
