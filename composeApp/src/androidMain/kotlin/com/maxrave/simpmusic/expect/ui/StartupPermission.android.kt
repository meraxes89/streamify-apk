package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberStartupPermissionRequester(): StartupPermissionRequester {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // App functions with or without these permissions; individual features handle denials.
        }

    return remember(context, launcher) {
        object : StartupPermissionRequester {
            private fun missingPermissions(): Array<String> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyArray()
                val required =
                    listOf(
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.READ_MEDIA_AUDIO,
                    )
                return required
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    .toTypedArray()
            }

            override fun hasAllRequiredPermissions(): Boolean = missingPermissions().isEmpty()

            override fun requestMissingPermissions() {
                val missing = missingPermissions()
                if (missing.isNotEmpty()) {
                    launcher.launch(missing)
                }
            }
        }
    }
}
