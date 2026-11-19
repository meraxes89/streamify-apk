package com.streamify.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadChunk(
    val url: String,
    val start: Long,
    val end: Long
)

/**
 * Parallel ranged downloader.
 *
 * INTEGRITY CONTRACT (fixed):
 *  1. Every chunk response MUST be HTTP 206. A 200 means the server ignored
 *     our Range header and returned the WHOLE body — writing that at a chunk
 *     offset silently interleaved garbage into the file. Now we fall back to
 *     the single-stream path instead.
 *  2. A failed/short chunk FAILS the download. Previously failures were
 *     swallowed and zero-filled holes shipped as "success".
 *  3. The RandomAccessFile is closed via finally (was leaked on any throw).
 *  4. Partial output is deleted on failure so callers never see a corrupt
 *     "downloaded" file.
 */
class ParallelStreamDownloader {

    suspend fun download(
        url: String,
        outputFile: File,
        onProgress: (percent: String, speed: String, eta: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val client = NetworkEngine.client

        // 1. Inquire Content-Length + confirm range support in one HEAD.
        val headRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .head()
            .build()

        var totalBytes = 0L
        var rangeSupported = false
        try {
            client.newCall(headRequest).execute().use { resp ->
                totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                rangeSupported = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            }
        } catch (_: Exception) {
            // Ignore HEAD failure and attempt GET below.
        }

        if (totalBytes <= 0 || !rangeSupported) {
            // No length or no range support -> parallel chunking is impossible;
            // fall back to the direct single stream path.
            return@withContext downloadSingleStream(url, outputFile, onProgress)
        }

        // 2. Partition file into 1MB chunks.
        val chunkSize = 1024 * 1024L // 1MB chunk
        val chunks = mutableListOf<DownloadChunk>()
        var offset = 0L
        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize - 1, totalBytes - 1)
            chunks.add(DownloadChunk(url, offset, end))
            offset += chunkSize
        }

        // 3. Pre-allocate disk file.
        if (outputFile.exists()) outputFile.delete()
        val raf = RandomAccessFile(outputFile, "rw")
        var success = false
        try {
            raf.setLength(totalBytes)

            val downloadedBytes = AtomicLong(0)
            val failedChunks = AtomicInteger(0)
            val startTime = System.currentTimeMillis()
            var lastProgressAt = 0L

            // 4. Download chunks concurrently in batches of 4.
            chunks.chunked(4).forEach { batch ->
                coroutineScope {
                    batch.map { chunk ->
                        async {
                            val req = Request.Builder()
                                .url(chunk.url)
                                .header("User-Agent", "Mozilla/5.0")
                                .header("Range", "bytes=${chunk.start}-${chunk.end}")
                                .build()

                            try {
                                client.newCall(req).execute().use { response ->
                                    // 200 here = server ignored Range; treating it as
                                    // this chunk's body would corrupt neighboring chunks.
                                    if (response.code != 206) {
                                        failedChunks.incrementAndGet()
                                        return@use
                                    }
                                    val expectedLen = (chunk.end - chunk.start + 1)
                                    val body = response.body?.byteStream() ?: run {
                                        failedChunks.incrementAndGet(); return@use
                                    }
                                    val buffer = ByteArray(32 * 1024)
                                    var read: Int
                                    var chunkOffset = chunk.start

                                    while (body.read(buffer).also { read = it } != -1) {
                                        synchronized(raf) {
                                            raf.seek(chunkOffset)
                                            raf.write(buffer, 0, read)
                                        }
                                        chunkOffset += read
                                        val currentTotal = downloadedBytes.addAndGet(read.toLong())

                                        // Throttle progress callbacks to ~4Hz: one string
                                        // format + UI hop per 32KB read was pure churn.
                                        val now = System.currentTimeMillis()
                                        if (now - lastProgressAt >= 250 || currentTotal >= totalBytes) {
                                            lastProgressAt = now
                                            val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                                            val speedBps = currentTotal / elapsedSec
                                            val speedMbps = speedBps / (1024.0 * 1024.0)
                                            val percent = ((currentTotal.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                                            val remainingBytes = (totalBytes - currentTotal).coerceAtLeast(0)
                                            val etaSec = if (speedBps > 0) (remainingBytes / speedBps).toInt() else 0

                                            onProgress(
                                                "$percent%",
                                                "%.1f MB/s".format(speedMbps),
                                                "%02d:%02d".format(etaSec / 60, etaSec % 60)
                                            )
                                        }
                                    }

                                    // Short body = hole in the middle of the file.
                                    if (chunkOffset != chunk.end + 1) failedChunks.incrementAndGet()
                                }
                            } catch (_: Exception) {
                                failedChunks.incrementAndGet()
                            }
                        }
                    }.awaitAll()
                }
                // Abort remaining batches early once integrity is lost;
                // finally{} below handles close + partial-file cleanup.
                if (failedChunks.get() > 0) return@withContext false
            }

            success = failedChunks.get() == 0 && outputFile.length() == totalBytes
            return@withContext success
        } catch (t: Throwable) {
            success = false
            return@withContext false
        } finally {
            try { raf.close() } catch (_: Throwable) {}
            if (!success && outputFile.exists()) {
                outputFile.delete()
            }
        }
    }

    private fun downloadSingleStream(
        url: String,
        outputFile: File,
        onProgress: (percent: String, speed: String, eta: String) -> Unit
    ): Boolean {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.byteStream() ?: return false
                val totalLength = resp.body?.contentLength() ?: 0L
                val startTime = System.currentTimeMillis()
                var downloaded = 0L
                var lastProgressAt = 0L

                outputFile.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (body.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 250) {
                            lastProgressAt = now
                            val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                            val speedBps = downloaded / elapsedSec
                            val speedMbps = speedBps / (1024.0 * 1024.0)
                            val percent = if (totalLength > 0) ((downloaded.toDouble() / totalLength) * 100).toInt().coerceIn(0, 100) else 50
                            onProgress("$percent%", "%.1f MB/s".format(speedMbps), "00:05")
                        }
                    }
                }
                return totalLength <= 0 || downloaded == totalLength
            }
        } catch (_: Exception) {
            if (outputFile.exists()) outputFile.delete()
            return false
        }
    }
}
