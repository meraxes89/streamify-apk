package com.streamify.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.streamify.app.util.SLog
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * StreamifyDownloadManager — WorkManager enqueue surface (Phase 3, area 3)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The resumable-download orchestration front door:
 *
 *  • Constraints — unmetered network (user-preference, default ON for
 *    quality runs; metered allowed when the user opts into data) and
 *    battery-not-low, exactly the "battery/unmetered" contract.
 *  • Unique work names per (url+title) → re-enqueues REPLACE, so a
 *    pause→resume cycle is naturally deduplicated while the on-disk .part
 *    byte-range state carries the actual progress.
 *  • Exponential backoff → the worker's Result.retry() re-drives the
 *    engine, which resumes from the .part offset (auto-retry on dropouts).
 *  • Progress observations flow through the shared "download_worker" tag
 *    so the existing DownloadScreen/IngestionViewModel feeds keep working.
 */
object StreamifyDownloadManager {

    private const val TAG = "StreamifyDownloadManager"

    /** Shared tag — the legacy quick-download feed observes this too. */
    const val TAG_ACTIVE = "download_worker"
    private const val PREFS = "audio_settings"
    private const val KEY_UNMETERED = "download_unmetered_only"

    fun uniqueNameFor(url: String, title: String): String =
        "resumable_dl_${(url + "|" + title).hashCode() and 0x7FFFFFFF}"

    // ──────────────────────────────────────────────────── user preferences

    fun isUnmeteredOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNMETERED, true)

    fun setUnmeteredOnly(context: Context, unmeteredOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_UNMETERED, unmeteredOnly).apply()
    }

    // ───────────────────────────────────────────────────────── enqueue API

    /**
     * Enqueues a resumable quality-ladder download.
     *
     * @param qualityKey one of QualityLadderManager.DownloadQuality.key
     * @return the WorkRequest UUID (pause handle).
     */
    fun enqueue(
        context: Context,
        url: String,
        title: String,
        artist: String,
        album: String,
        qualityKey: String? = null,
        durationSec: Int = 0
    ): UUID {
        val quality = QualityLadderManager.qualityFromKey(
            qualityKey ?: QualityLadderManager.loadChoice(context).key
        )
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, url)
            .putString(DownloadWorker.KEY_TITLE, title)
            .putString(DownloadWorker.KEY_ARTIST, artist)
            .putString(DownloadWorker.KEY_ALBUM, album)
            .putString(DownloadWorker.KEY_QUALITY, quality.key)
            .putInt(DownloadWorker.KEY_DURATION_SEC, durationSec)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (isUnmeteredOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(TAG_ACTIVE)
            .addTag("TITLE:$title")
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueNameFor(url, title), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /**
     * Pause = cancel the in-flight worker. The engine's on-disk .part bytes
     * survive (they ARE the resume state); nothing else is needed.
     */
    fun pause(context: Context, workId: UUID) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelWorkById(workId)
        }.onFailure {
            SLog.d(TAG, "pause failed (${it.message})")
        }
    }

    /**
     * Resume = re-enqueue with the same identity. The worker picks the .part
     * file back up via the byte-range engine and continues from its length.
     */
    fun resume(
        context: Context,
        url: String,
        title: String,
        artist: String,
        album: String,
        qualityKey: String? = null,
        durationSec: Int = 0
    ): UUID = enqueue(context, url, title, artist, album, qualityKey, durationSec)

    // ─────────────────────────────────────────────── progress observation

    /** Progress payload keys published by [DownloadWorker]. */
    const val PROGRESS_BYTES = "bytes"
    const val PROGRESS_TOTAL = "total"

    fun progressData(bytes: Long, total: Long): Data = workDataOf(
        PROGRESS_BYTES to bytes,
        PROGRESS_TOTAL to total,
        "progress" to if (total > 0) "${(bytes * 100 / total)}%" else "Downloading…"
    )

    // ──────────────────────────────────────────────────── notification util

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                DownloadWorker.CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Resumable track downloads"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        title: String,
        text: String,
        progressFraction: Int? = null
    ): android.app.Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressFraction != null) {
            builder.setProgress(100, progressFraction.coerceIn(0, 100), progressFraction < 0)
        }
        return builder.build()
    }

    fun foregroundInfo(
        context: Context,
        title: String,
        progressFraction: Int?
    ): androidx.work.ForegroundInfo {
        val notification = buildNotification(
            context, title, "Downloading with resume protection…", progressFraction
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(
                DownloadWorker.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(DownloadWorker.NOTIFICATION_ID, notification)
        }
    }
}
