package com.streamify.app.media.ingestion
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.util.MediaStoreScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.streamify.app.util.SLog

class IngestionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = 54321

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Indexing Local Music", 0, 1)
    }

    private fun createForegroundInfo(title: String, current: Int, total: Int): ForegroundInfo {
        val channelId = "ingestion_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Media Scanner"
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val progressText = if (total > 0) "Indexed $current of $total tracks" else "Scanning device..."
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            try {
                setForeground(createForegroundInfo("Indexing Local Music", 0, 0))
            } catch (e: Throwable) {
                // Background scan continues even if foreground notification is restricted
            }

            // 1. Direct MediaStore projection query (Zero IPC Binder bottlenecks)
            val localFiles = MediaStoreScanner.scanLocalMusic(applicationContext)
            val existingTracks = NativeBridge.getAllTracks()
            val existingPaths = existingTracks.map { it.filepath }.toSet()

            val newFiles = localFiles.filter { !existingPaths.contains(it.dataPath) }
            var insertedCount = 0

            NativeBridge.setTotalAiTasks(newFiles.size)

            // 2. Fast Non-Blocking Bulk Insertion (Zero startup DSP decoding)
            newFiles.forEachIndexed { index, file ->
                val finalAlbum = if (file.album.isNotBlank() && !file.album.equals("<unknown>", ignoreCase = true)) {
                    file.album
                } else if (file.dataPath.lowercase().contains("streamify")) {
                    "Streamify"
                } else {
                    "Local Storage"
                }

                val trackId = NativeBridge.insertTrack(
                    filepath = file.dataPath,
                    title = file.title,
                    artist = file.artist,
                    album = finalAlbum,
                    durationSec = (file.durationMs / 1000).toInt(),
                    bpm = 0.0f
                ).toInt()

                if (trackId > 0) {
                    insertedCount++
                }

                if (index % 50 == 0 || index == newFiles.size - 1) {
                    val progress = (index + 1).toFloat() / newFiles.size.toFloat()
                    setProgress(workDataOf("PROGRESS" to progress))
                }
            }

            // 3. Single Atomic Refresh at the end of the scan (Zero redundant DB queries)
            if (insertedCount > 0 || existingTracks.isEmpty()) {
                TrackRepository.refresh()
            }

            Result.success()
        } catch (e: Exception) {
            SLog.st("IngestionWorker", "IngestionWorker.doWork failed", e)
            Result.failure()
        }
    }
}
