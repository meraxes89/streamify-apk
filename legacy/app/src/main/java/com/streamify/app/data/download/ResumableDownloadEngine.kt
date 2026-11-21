package com.streamify.app.data.download

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ResumableDownloadEngine — byte-range state machine (Phase 3, deliverable 3)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * A JVM-pure, transport-injected resumable downloader. All network access
 * flows through the [RangeTransport] interface, so the full state machine —
 * pause / resume / retry / byte verification — is unit-testable with an
 * in-memory fake (no socket, no mock-web-server dependency).
 *
 * State machine (per [download] invocation):
 *
 *   ┌────────────── resume offset = dest.length() ───────────────┐
 *   │                                                             │
 *   ▼                                                             │
 *  open(url, offset) ──transport fail──▶ attempt++ ──▶ retry (≤3) │
 *   │                                                             │
 *   ├─ 206 Partial ─▶ APPEND from offset                          │
 *   ├─ 200 Full    ─▶ TRUNCATE + restart from 0 (no range support)│
 *   ▼                                                             │
 *  copy loop (8KiB) ──IOException──▶ attempt++ ──▶ resume retry ──┘
 *   │
 *   ├─ shouldContinue()==false ─▶ PAUSED (bytes kept on disk)
 *   ▼
 *  verify dest.length() == totalBytes ──mismatch──▶ attempt++ ──▶ retry
 *   │
 *   ▼
 *  COMPLETED
 *
 * Byte verification: the final on-disk length must equal the server's
 * advertised total (Content-Range total / Content-Length). A truncated tail
 * from a dropped connection is caught here and re-driven, never surfaced as
 * a corrupt playable file.
 */
class ResumableDownloadEngine(
    private val transport: RangeTransport,
    private val maxAttempts: Int = 3
) {

    // ─────────────────────────────────────────────────────── transport SPI

    /** One opened range request. */
    data class RangeResponse(
        val stream: InputStream,
        /** Total resource size in bytes (from Content-Range/Content-Length). */
        val totalBytes: Long,
        /** True when the server honored the offset (206 Partial). */
        val partial: Boolean
    )

    /** Injectable HTTP transport — see OkHttpRangeTransport for production. */
    fun interface RangeTransport {
        /** Opens [url] at [offsetBytes]; null signals a transport failure. */
        fun open(url: String, offsetBytes: Long): RangeResponse?
    }

    // ───────────────────────────────────────────────────────── result model

    enum class Outcome { COMPLETED, PAUSED, RETRIABLE_FAILURE, FATAL_FAILURE }

    data class DownloadResult(
        val outcome: Outcome,
        /** Bytes on disk when the engine returned (resume offset included). */
        val bytesOnDisk: Long,
        /** Server-advertised total; -1 when unknown. */
        val totalBytes: Long,
        val attempts: Int,
        val lastError: String? = null
    ) {
        val isComplete: Boolean get() = outcome == Outcome.COMPLETED
    }

    /** Internal per-attempt verdict. */
    private sealed class Attempt {
        data class Done(val result: DownloadResult) : Attempt()
        data class Retry(val error: String?) : Attempt()
    }

    // ──────────────────────────────────────────────────────────── the state

    /**
     * Drives the download of [url] into [dest] (the .part file — the caller
     * promotes it to the final name on COMPLETED).
     *
     * @param progress invoked after every chunk with (bytesOnDisk, totalBytes)
     * @param shouldContinue cooperative pause flag — false parks the machine
     *        as PAUSED with all bytes retained for a later resume.
     */
    fun download(
        url: String,
        dest: File,
        progress: (bytesOnDisk: Long, totalBytes: Long) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true }
    ): DownloadResult {
        var attempts = 0
        var lastError: String? = null

        while (attempts < maxAttempts) {
            attempts++
            when (val verdict = runAttempt(url, dest, attempts, progress, shouldContinue)) {
                is Attempt.Done -> return verdict.result
                is Attempt.Retry -> lastError = verdict.error
            }
        }

        return DownloadResult(
            Outcome.RETRIABLE_FAILURE,
            if (dest.exists()) dest.length() else 0L,
            -1L,
            attempts,
            lastError
        )
    }

    /** One full pass: open → copy → verify. Retry verdicts drive re-entry. */
    private fun runAttempt(
        url: String,
        dest: File,
        attempt: Int,
        progress: (bytesOnDisk: Long, totalBytes: Long) -> Unit,
        shouldContinue: () -> Boolean
    ): Attempt {
        // ── 1. resume offset = whatever survived on disk ───────────────────
        val offset = if (dest.exists()) dest.length() else 0L

        // ── 2. cooperative pause check ────────────────────────────────────
        if (!shouldContinue()) {
            return Attempt.Done(
                DownloadResult(Outcome.PAUSED, offset, -1L, attempt, "paused by caller")
            )
        }

        // ── 3. open the range request ─────────────────────────────────────
        val response = try {
            transport.open(url, offset)
        } catch (e: IOException) {
            return Attempt.Retry(e.message ?: "io error on open")
        } ?: return Attempt.Retry("transport refused connection")

        var pausedResult: DownloadResult? = null
        var midStreamError: String? = null

        response.stream.use { input ->
            // 200 (full) on a resume attempt → server ignored the range;
            // restart from zero so bytes are never duplicated.
            val writeOffset = if (response.partial) offset else 0L
            if (!response.partial && dest.exists()) {
                runCatching { dest.delete() }
            }

            dest.parentFile?.mkdirs()
            try {
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.setLength(writeOffset)
                    raf.seek(writeOffset)
                    val buffer = ByteArray(CHUNK_BYTES)
                    copyLoop@ while (shouldContinue()) {
                        val read = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            midStreamError = e.message ?: "io error mid-stream"
                            break@copyLoop
                        }
                        if (read < 0) break@copyLoop
                        raf.write(buffer, 0, read)
                        progress(raf.length(), response.totalBytes)
                    }
                    if (!shouldContinue() && midStreamError == null) {
                        pausedResult = DownloadResult(
                            Outcome.PAUSED, raf.length(), response.totalBytes, attempt, "paused by caller"
                        )
                    }
                }
            } catch (e: IOException) {
                return Attempt.Retry(e.message ?: "io error on write")
            }
        }

        pausedResult?.let { return Attempt.Done(it) }
        midStreamError?.let { return Attempt.Retry(it) }

        // ── 4. byte verification ──────────────────────────────────────────
        val finalLen = dest.length()
        val total = response.totalBytes
        if (total <= 0L || finalLen == total) {
            return Attempt.Done(DownloadResult(Outcome.COMPLETED, finalLen, total, attempt))
        }
        // Short read: the tail is missing → next attempt resumes the gap.
        progress(finalLen, total)
        return Attempt.Retry("byte verification failed ($finalLen/$total)")
    }

    private companion object {
        const val CHUNK_BYTES = 8 * 1024
    }
}
