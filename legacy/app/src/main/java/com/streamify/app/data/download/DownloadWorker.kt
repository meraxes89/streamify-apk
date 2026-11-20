package com.streamify.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.native.NativeMetadataTagger
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.media.cache.LosslessRemuxer
import com.streamify.app.util.SLog
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DownloadWorker — resumable background download worker (Phase 3, area 3)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * WorkManager CoroutineWorker implementing the full directive contract:
 *
 *  • Resumable byte-range downloads — [ResumableDownloadEngine] over
 *    [OkHttpRangeTransport]; a re-run (resume tap, backoff retry, or
 *    constraint re-satisfaction) continues from the .part file's length.
 *  • Foreground progress notifications — determinate % while the total is
 *    known; bytes/total published through WorkManager progress for the
 *    DownloadScreen feed.
 *  • Battery / unmetered constraints — set by [StreamifyDownloadManager]
 *    at enqueue time.
 *  • Auto-retry on dropouts — engine-level attempts + Result.retry()
 *    with exponential backoff for constraint-level failures.
 *  • Quality ladder — Opus 251 vs AAC 140 preference: resolve, check the
 *    rung, one fresh re-resolution on mismatch, graceful accept after.
 *  • Storage accounting — pre-flight quota/free-space gate via
 *    [QualityLadderManager]; fail fast with a clear signal when full.
 *
 * On completion it reuses the proven Phase 1 import pipeline:
 * remux → tag (art + lyrics) → register in native DB → "Streamify"
 * playlist → MediaStore scan → repo refresh.
 */
class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_QUALITY = "quality"
        const val KEY_DURATION_SEC = "durationSec"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 41200
        private const val TAG = "DownloadWorker"
        private const val MAX_WORKER_RETRIES = 4
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo =
        StreamifyDownloadManager.foregroundInfo(
            applicationContext,
            inputData.getString(KEY_TITLE) ?: "Downloading Track",
            progressFraction = -1 // indeterminate until first byte chunk
        )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown"
        var album = inputData.getString(KEY_ALBUM) ?: "Streamify"
        if (album.isBlank() || album == "Unknown" || album == "Downloads") album = "Streamify"
        val quality = QualityLadderManager.qualityFromKey(inputData.getString(KEY_QUALITY))
        val durationSec = inputData.getInt(KEY_DURATION_SEC, 0)

        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { SLog.d(TAG, "setForeground unavailable (${it.message}) — continuing headless") }

        // ── 1. Resolve the stream through the quality ladder ───────────────
        val videoId = extractVideoId(url)
        val resolved = resolveWithLadder(videoId, quality)
            ?: return@withContext if (runAttemptCount < MAX_WORKER_RETRIES) {
                Result.retry()
            } else {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not resolve a stream for $title"))
                Result.failure()
            }

        // ── 2. Pre-flight storage accounting gate ─────────────────────────
        val outputDir = resolveOutputDir()
        val incoming = QualityLadderManager.estimateBytes(quality, durationSec, resolved)
        val report = QualityLadderManager.accountStorage(outputDir)
        val quota = QualityLadderManager.loadQuotaBytes(applicationContext)
        if (!QualityLadderManager.canFit(report, incoming, quota)) {
            UiEventBus.emitEvent(
                UiEvent.ShowSnackbar("Storage full — ${QualityLadderManager.formatBytes(report.playableBytes)} used")
            )
            return@withContext Result.failure()
        }

        // ── 3. Resumable byte-range download into the .part file ───────────
        val partFile = File(applicationContext.cacheDir, "resumable_${videoId ?: title.hashCode()}.part")
        val engine = ResumableDownloadEngine(OkHttpRangeTransport(), maxAttempts = 3)
        val result = engine.download(
            url = resolved.streamUrl,
            dest = partFile,
            progress = { bytes, total ->
                runCatching {
                    setProgressAsync(
                        StreamifyDownloadManager.progressData(bytes, total)
                    )
                }
            },
            shouldContinue = { !isStopped } // graceful pause: park as PAUSED, bytes retained
        )

        when (result.outcome) {
            ResumableDownloadEngine.Outcome.COMPLETED -> {
                importCompletedTrack(partFile, resolved, title, artist, album)
                    ?: return@withContext Result.failure()
                Result.success()
            }
            ResumableDownloadEngine.Outcome.PAUSED -> {
                // Cooperative stop — bytes retained; treat like retry so the
                // constraint scheduler re-drives it when conditions allow.
                Result.retry()
            }
            ResumableDownloadEngine.Outcome.RETRIABLE_FAILURE -> {
                if (runAttemptCount < MAX_WORKER_RETRIES) Result.retry() else {
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar("Download failed: ${result.lastError ?: "network dropout"}"))
                    Result.failure()
                }
            }
            ResumableDownloadEngine.Outcome.FATAL_FAILURE -> Result.failure()
        }
    }

    // ─────────────────────────────────────────────── ladder-aware resolving

    /**
     * Resolve → ladder check → one fresh re-resolution on mismatch →
     * graceful accept. A quality preference must never fail a download.
     */
    private suspend fun resolveWithLadder(
        videoId: String?,
        quality: QualityLadderManager.DownloadQuality
    ): com.streamify.app.data.network.ResolvedStream? {
        if (videoId == null) return null

        val first = runCatching {
            YouTubeStreamResolver.resolveStreamUrl(videoId)
        }.getOrNull() ?: return null

        if (QualityLadderManager.accepts(quality, first)) return first

        // Pinned rung mismatch (e.g. AAC_140 asked, Opus 251 served):
        // one fresh resolution, then accept the best available.
        val second = runCatching {
            YouTubeStreamResolver.resolveStreamUrl(videoId, forceFresh = true)
        }.getOrNull()
        return second ?: first
    }

    private fun extractVideoId(url: String): String? {
        return if (url.contains("v=")) url.substringAfter("v=").substringBefore("&")
        else url.substringAfterLast("/").takeIf { it.isNotBlank() }
    }

    private fun resolveOutputDir(): File {
        val musicDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "Streamify"
        )
        runCatching { musicDir.mkdirs() }
        return if (musicDir.exists()) musicDir
        else File(applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Streamify")
            .also { runCatching { it.mkdirs() } }
    }

    // ─────────────────────────────────────────────────── proven import path

    private suspend fun importCompletedTrack(
        partFile: File,
        resolved: com.streamify.app.data.network.ResolvedStream,
        title: String,
        artist: String,
        album: String
    ): Int? = withContext(Dispatchers.IO) {
        if (!partFile.exists() || partFile.length() == 0L) return@withContext null

        val outputDir = resolveOutputDir()
        val targetFile = LosslessRemuxer.prepareTargetFile(outputDir, title, artist, resolved.mimeType)
        if (!LosslessRemuxer.remuxLossless(partFile, targetFile)) {
            runCatching { partFile.delete() }
            return@withContext null
        }
        runCatching { partFile.delete() } // consumed by the remux

        // Tag with iTunes 1400x1400 Retina art + lyrics (best-effort).
        val taggedAssets = runCatching {
            NativeMetadataTagger.tagAndExtractAssets(targetFile, title, artist)
        }.getOrNull()

        val trackId = runCatching {
            NativeBridge.insertTrack(
                filepath = targetFile.absolutePath,
                title = title,
                artist = artist,
                album = album,
                durationSec = resolved.durationSec,
                bpm = 120.0f
            ).toInt()
        }.getOrDefault(-1)

        if (trackId <= 0) {
            runCatching { targetFile.delete() }
            return@withContext null
        }

        if (taggedAssets != null && taggedAssets.coverArtPath.isNotBlank()) {
            runCatching { NativeBridge.updateTrackCoverArt(trackId, taggedAssets.coverArtPath) }
        }

        // Add to the Streamify playlist (same contract as the quick worker).
        runCatching {
            PlaylistRepository.init(applicationContext)
            var playlist = PlaylistRepository.playlists.value.find {
                it.name.equals("Streamify", ignoreCase = true)
            }
            if (playlist == null) {
                PlaylistRepository.createPlaylist("Streamify", "Downloaded songs on Streamify")
                playlist = PlaylistRepository.playlists.value.find {
                    it.name.equals("Streamify", ignoreCase = true)
                }
            }
            playlist?.let { PlaylistRepository.addTrackToPlaylist(it.id, trackId) }
        }

        // MediaStore visibility for other apps.
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                applicationContext, arrayOf(targetFile.absolutePath), null, null
            )
        }

        TrackRepository.refresh()
        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Saved $title (resumable download ✓)"))
        trackId
    }
}
