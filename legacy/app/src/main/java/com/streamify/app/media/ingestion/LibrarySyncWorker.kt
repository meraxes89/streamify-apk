package com.streamify.app.media.ingestion
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.spotify.SpotifyAuthManager
import java.util.concurrent.TimeUnit

class LibrarySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dbPath = applicationContext.getDatabasePath("streamify_universal.db").absolutePath
        val authManager = SpotifyAuthManager(applicationContext)
        val accessToken = authManager.getAccessToken() ?: return Result.success()

        val prefs = applicationContext.getSharedPreferences("streamify_sync_prefs", Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("spotify_last_sync_timestamp", 0L)

        val tracksAdded = NativeBridge.performSpotifyDeltaSync(dbPath, accessToken, lastSync)

        if (tracksAdded >= 0) {
            prefs.edit().putLong("spotify_last_sync_timestamp", System.currentTimeMillis()).apply()
            return Result.success()
        }

        return Result.retry()
    }

    companion object {
        fun schedulePeriodicSync(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(
                    6, TimeUnit.HOURS
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "spotify_delta_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                // Ignore if WorkManager not initialized yet
            }
        }
    }
}
