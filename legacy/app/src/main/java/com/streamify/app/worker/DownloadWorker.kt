package com.streamify.app.worker

import android.app.Notification
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
import com.streamify.app.data.native.NativeMetadataTagger
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.network.ParallelStreamDownloader
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.media.cache.LosslessRemuxer
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.streamify.app.util.SLog

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        // Hard-cap background DSP across all DownloadWorkers to 1 thread to protect audio decoding
        private val dspDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    private val notificationId = 12345

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(inputData.getString("title") ?: "Downloading Track")
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val channelId = "download_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText("Downloading from source...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            SLog.st("DownloadWorker", "DownloadWorker.doWork failed", e)
        }

        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: "Unknown"
        val artist = inputData.getString("artist") ?: "Unknown"
        var album = inputData.getString("album") ?: "Streamify"
        if (album.isBlank() || album == "Unknown" || album == "Downloads") {
            album = "Streamify"
        }
        val quality = inputData.getString("quality") ?: "320"

        val musicDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Streamify")
        if (!musicDir.exists()) {
            try { musicDir.mkdirs() } catch (e: Exception) { SLog.st("DownloadWorker", "DownloadWorker.doWork mkdirs failed", e) }
        }
        val outputDir = if (musicDir.exists()) musicDir else File(applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Streamify")
        if (!outputDir.exists()) outputDir.mkdirs()

        // -------------------------------------------------------------
        // FAST-PATH: Pure Kotlin Native Hermes Downloader (<3s, Zero Python)
        // -------------------------------------------------------------
        var fastPathSuccess = false
        try {
            val videoId = if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") else url.substringAfterLast("/")
            val streamResult = YouTubeStreamResolver.resolveStreamUrl(videoId)

            if (streamResult != null && streamResult.streamUrl.isNotBlank()) {
                val tempRaw = File(applicationContext.cacheDir, "hermes_${System.currentTimeMillis()}.raw")
                val downloader = ParallelStreamDownloader()
                
                val downloadOk = downloader.download(streamResult.streamUrl, tempRaw) { percent, speed, eta ->
                    setProgressAsync(workDataOf(
                        "progress" to percent,
                        "speed" to speed,
                        "eta" to eta
                    ))
                }

                if (downloadOk && tempRaw.exists() && tempRaw.length() > 0) {
                    val targetFile = LosslessRemuxer.prepareTargetFile(outputDir, title, artist, streamResult.mimeType)
                    if (LosslessRemuxer.remuxLossless(tempRaw, targetFile)) {
                        // Tag with iTunes 1400x1400 Retina art and lyrics
                        val taggedAssets = NativeMetadataTagger.tagAndExtractAssets(targetFile, title, artist)

                        val trackId = NativeBridge.insertTrack(
                            filepath = targetFile.absolutePath,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSec = 0,
                            bpm = 120.0f
                        ).toInt()

                        if (trackId > 0) {
                            if (taggedAssets.coverArtPath.isNotBlank()) {
                                NativeBridge.updateTrackCoverArt(trackId, taggedAssets.coverArtPath)
                            }
                            
                            // Background AI Feature extraction (Throttled single-core execution with buffer gate)
                            withContext(dspDispatcher) {
                                try {
                                    while (com.streamify.app.media.playback.PlaybackService.isBuffering.value) {
                                        kotlinx.coroutines.delay(200)
                                    }
                                    NativeBridge.processAudioFile(trackId, targetFile.absolutePath)
                                } catch (e: Exception) {}
                            }

                            // Add to Streamify playlist
                            try {
                                PlaylistRepository.init(applicationContext)
                                var playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                                if (playlist == null) {
                                    PlaylistRepository.createPlaylist("Streamify", "Downloaded songs on Streamify")
                                    playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                                }
                                if (playlist != null) {
                                    PlaylistRepository.addTrackToPlaylist(playlist.id, trackId)
                                }
                            } catch (e: Exception) {}

                            // Scan MediaStore
                            try {
                                android.media.MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(targetFile.absolutePath),
                                    null,
                                    null
                                )
                            } catch (e: Exception) {}

                            TrackRepository.refresh()
                            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Saved Lossless Track ($title)"))
                            fastPathSuccess = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            fastPathSuccess = false
        }

        if (fastPathSuccess) {
            return@withContext Result.success()
        }

        Result.failure()
    }
}

interface DownloadCallback {
    fun onProgress(percent: String, speed: String, eta: String)
    fun onFinished(filepath: String)
    fun onError(error: String)
}
