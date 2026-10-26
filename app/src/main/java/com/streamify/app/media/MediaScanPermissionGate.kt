package com.streamify.app.media

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.streamify.app.media.ingestion.IngestionWorker
import com.streamify.app.util.PermissionHelper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * MediaScanPermissionGate — single-responsibility composable for the local
 * media ingestion entry (sprint P2, god-object decomposition).
 *
 * Owns the "enable_local_audio" preference read, the runtime permission
 * launcher and the post-grant media-scan enqueue. Extracted verbatim from
 * MainActivity.setContent: behavior identical, the activity composition root
 * is now one line.
 */
@Composable
fun MediaScanPermissionGate(activity: ComponentActivity) {
    val audioPrefs = remember { activity.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE) }
    val isLocalEnabled = remember { audioPrefs.getBoolean("enable_local_audio", false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it } && isLocalEnabled) {
            enqueueMediaScan(activity)
        }
    }

    LaunchedEffect(Unit) {
        if (isLocalEnabled) {
            if (!PermissionHelper.hasPermissions(activity)) {
                permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
            } else {
                enqueueMediaScan(activity)
            }
        }
    }
}

private fun enqueueMediaScan(context: Context) {
    val workManager = WorkManager.getInstance(context)
    val scanRequest = OneTimeWorkRequestBuilder<IngestionWorker>()
        .addTag("ingestion_worker")
        .build()
    workManager.enqueueUniqueWork("media_scan", ExistingWorkPolicy.KEEP, scanRequest)
}
