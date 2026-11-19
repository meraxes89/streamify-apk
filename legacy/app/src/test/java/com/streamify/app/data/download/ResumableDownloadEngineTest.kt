package com.streamify.app.data.download

import com.streamify.app.data.network.ResolvedStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * ResumableDownloadEngine JVM unit suite (Phase 3 — downloader state
 * machine: pause / resume / retry / byte verification).
 *
 * Uses an in-memory fake [ResumableDownloadEngine.RangeTransport] — zero
 * sockets, zero mock-web-server.
 */
class ResumableDownloadEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Scriptable fake transport: serves a payload with pluggable failures. */
    private class FakeTransport(
        private val totalBytes: Long,
        /** Simulates a mid-stream dropout after N delivered bytes (per attempt). */
        private val dropoutAfterBytes: Int = Int.MAX_VALUE,
        /** Simulates transport open failures for the first N calls. */
        var refuseFirstNCalls: Int = 0,
        /** When false, open() ignores Range and always returns the full body (200). */
        private val honorRange: Boolean = true
    ) : ResumableDownloadEngine.RangeTransport {

        var openCalls = 0
        var lastRequestedOffset = 0L

        override fun open(url: String, offsetBytes: Long): ResumableDownloadEngine.RangeResponse? {
            openCalls++
            if (refuseFirstNCalls > 0) {
                refuseFirstNCalls--
                return null
            }
            lastRequestedOffset = offsetBytes

            val payload = ByteArray(totalBytes.toInt()) { (it % 251).toByte() }
            val effectiveOffset = if (honorRange) offsetBytes.coerceIn(0, totalBytes) else 0L
            val remaining = (totalBytes - effectiveOffset).toInt()
            // Deliver at most dropoutAfterBytes bytes then dropout.
            val served = remaining.coerceAtMost(dropoutAfterBytes)

            val stream = object : InputStream() {
                private var pos = 0
                override fun read(): Int {
                    if (pos >= served) {
                        if (pos >= remaining) return -1 // clean EOF
                        // Dropout mid-stream: signal as IOException (socket reset).
                        throw IOException("simulated connection reset")
                    }
                    val b = payload[effectiveOffset.toInt() + pos]
                    pos++
                    return b.toInt() and 0xFF
                }
            }
            return ResumableDownloadEngine.RangeResponse(
                stream = stream,
                totalBytes = totalBytes,
                partial = honorRange && offsetBytes > 0
            )
        }
    }

    private fun dest(): File = tmp.newFile("track.part")

    // ─────────────────────────────────────────────────────────── happy path

    @Test
    fun `complete download verifies byte count`() {
        val dest = dest() // ONE file per test: TemporaryFolder.newFile throws on re-creation
        val engine = ResumableDownloadEngine(FakeTransport(totalBytes = 10_000), maxAttempts = 3)
        val result = engine.download("https://cdn.example.com/a.webm", dest)
        assertEquals(ResumableDownloadEngine.Outcome.COMPLETED, result.outcome)
        assertEquals(10_000L, result.bytesOnDisk)
        assertEquals(10_000L, result.totalBytes)
        assertEquals(1, result.attempts)
        assertEquals(10_000L, dest.length())
    }

    // ─────────────────────────────────────────────────────── pause/resume

    @Test
    fun `cooperative pause parks as PAUSED with bytes retained`() {
        // Pause after ~4KB have been written.
        var pauseGate = false
        val dest = dest()
        val engine = ResumableDownloadEngine(FakeTransport(totalBytes = 10_000), maxAttempts = 3)
        val result = engine.download(
            "https://cdn.example.com/a.webm",
            dest,
            progress = { bytes, _ -> if (bytes >= 4_000) pauseGate = true },
            shouldContinue = { !pauseGate }
        )
        assertEquals(ResumableDownloadEngine.Outcome.PAUSED, result.outcome)
        assertTrue(result.bytesOnDisk >= 4_000L)
        assertTrue(dest.length() >= 4_000L)

        // ── RESUME: a second engine continues from the on-disk offset ──
        val resumeTransport = FakeTransport(totalBytes = 10_000)
        val resumeEngine = ResumableDownloadEngine(resumeTransport, maxAttempts = 3)
        val resumed = resumeEngine.download("https://cdn.example.com/a.webm", dest)
        assertEquals(ResumableDownloadEngine.Outcome.COMPLETED, resumed.outcome)
        assertEquals(10_000L, dest.length())
        // The resumed open() MUST have requested the survived byte offset.
        assertTrue(resumeTransport.lastRequestedOffset >= 4_000L)
    }

    // ────────────────────────────────────────────────────────── auto-retry

    @Test
    fun `mid-stream dropout retries and resumes to completion`() {
        // Dropout after 6KB on the first attempt; second attempt succeeds.
        val dest = dest()
        var useDropout = true
        val good = FakeTransport(totalBytes = 10_000)
        val dropout = FakeTransport(totalBytes = 10_000, dropoutAfterBytes = 6_000)
        val transport = ResumableDownloadEngine.RangeTransport { url, offset ->
            if (useDropout) {
                useDropout = false
                dropout.open(url, offset)
            } else {
                good.open(url, offset)
            }
        }
        val engine = ResumableDownloadEngine(transport, maxAttempts = 3)
        val result = engine.download("https://cdn.example.com/a.webm", dest)
        assertEquals(ResumableDownloadEngine.Outcome.COMPLETED, result.outcome)
        assertEquals(2, result.attempts)
        assertEquals(10_000L, dest.length())
    }

    @Test
    fun `transport refusal exhausts attempts into retriable failure`() {
        val engine = ResumableDownloadEngine(FakeTransport(totalBytes = 100).also { it.refuseFirstNCalls = 99 }, maxAttempts = 3)
        val result = engine.download("https://cdn.example.com/a.webm", dest())
        assertEquals(ResumableDownloadEngine.Outcome.RETRIABLE_FAILURE, result.outcome)
        assertEquals(3, result.attempts)
    }

    // ─────────────────────────────────────────────── byte verification

    @Test
    fun `short server total triggers verification retry then completes`() {
        // First open serves a truncated stream that ends CLEANLY at 6KB
        // while advertising a 10KB total → verification failure → retry.
        var phase = 0
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val transport = ResumableDownloadEngine.RangeTransport { _, offset ->
            if (phase == 0) {
                phase = 1
                // Clean EOF at 6KB — no exception, just short.
                val cut = 6_000 - offset.toInt()
                ResumableDownloadEngine.RangeResponse(
                    stream = ByteArrayInputStream(payload, offset.toInt(), cut),
                    totalBytes = 10_000,
                    partial = offset > 0
                )
            } else {
                ResumableDownloadEngine.RangeResponse(
                    stream = ByteArrayInputStream(payload, offset.toInt(), (10_000 - offset).toInt()),
                    totalBytes = 10_000,
                    partial = offset > 0
                )
            }
        }
        val dest = dest()
        val engine = ResumableDownloadEngine(transport, maxAttempts = 3)
        val result = engine.download("https://cdn.example.com/a.webm", dest)
        assertEquals(ResumableDownloadEngine.Outcome.COMPLETED, result.outcome)
        assertEquals(2, result.attempts)
        assertEquals(10_000L, dest.length())
    }

    // ───────────────────────────────────────── range-ignoring servers

    @Test
    fun `server ignoring range restarts from zero without duplication`() {
        // Pre-seed 3KB of stale partial bytes; server always returns 200-full.
        val dest = dest()
        dest.writeBytes(ByteArray(3_000) { 0x7F })
        val transport = FakeTransport(totalBytes = 8_000, honorRange = false)
        val engine = ResumableDownloadEngine(transport, maxAttempts = 3)
        val result = engine.download("https://cdn.example.com/a.webm", dest)
        assertEquals(ResumableDownloadEngine.Outcome.COMPLETED, result.outcome)
        assertEquals(8_000L, dest.length())
        // Truncated restart: first byte must be the server's, not the stale tail.
        assertEquals(0, dest.inputStream().use { stream -> stream.read() })
    }

    // ───────────────────────────────────────────── QualityLadderManager

    @Test
    fun `quality ladder acceptance and estimates`() {
        val opusStream = ResolvedStream("https://x", "audio/webm; codecs=opus", 160_000, 200)
        val aacStream = ResolvedStream("https://x", "audio/mp4; codecs=mp4a", 128_000, 200)

        assertTrue(QualityLadderManager.accepts(QualityLadderManager.DownloadQuality.AUTO, opusStream))
        assertTrue(QualityLadderManager.accepts(QualityLadderManager.DownloadQuality.OPUS_251, opusStream))
        assertTrue(!QualityLadderManager.accepts(QualityLadderManager.DownloadQuality.OPUS_251, aacStream))
        assertTrue(QualityLadderManager.accepts(QualityLadderManager.DownloadQuality.AAC_140, aacStream))
        assertTrue(!QualityLadderManager.accepts(QualityLadderManager.DownloadQuality.AAC_140, opusStream))

        // 160 kbps × 180 s / 8 = 3.6 MB
        assertEquals(3_600_000L, QualityLadderManager.estimateBytes(QualityLadderManager.DownloadQuality.OPUS_251, 180))
        assertEquals(2_880_000L, QualityLadderManager.estimateBytes(QualityLadderManager.DownloadQuality.AAC_140, 180))

        assertEquals(QualityLadderManager.DownloadQuality.AUTO, QualityLadderManager.qualityFromKey("junk"))
        assertEquals(QualityLadderManager.DownloadQuality.OPUS_251, QualityLadderManager.qualityFromKey("opus251"))
        assertEquals(QualityLadderManager.DownloadQuality.AAC_140, QualityLadderManager.qualityFromKey("AAC_140"))
    }

    @Test
    fun `storage accounting separates playable from in-flight partials`() {
        val dir = tmp.newFolder("downloads")
        File(dir, "a.m4a").writeBytes(ByteArray(2_000))
        File(dir, "b.webm").writeBytes(ByteArray(1_500))
        File(dir, "c.part").writeBytes(ByteArray(500))

        val report = QualityLadderManager.accountStorage(dir)
        assertEquals(4_000L, report.totalBytes)
        assertEquals(500L, report.partialBytes)
        assertEquals(3_500L, report.playableBytes)
        assertEquals(3, report.fileCount)

        // Quota gate (byte units): 3,500B used, quota 4,200B → 200B fits, 2KB does not.
        assertTrue(QualityLadderManager.canFit(report, 200L, quotaBytes = 4_200L))
        assertTrue(!QualityLadderManager.canFit(report, 2_000L, quotaBytes = 4_200L))
        // Unbounded quota → fits when free space allows.
        assertTrue(QualityLadderManager.canFit(report, 100L, quotaBytes = 0L))
        // Missing dir → zeroed report, never throws.
        val empty = QualityLadderManager.accountStorage(File(dir, "nope"))
        assertEquals(0L, empty.totalBytes)
    }

    @Test
    fun `formatBytes renders human labels`() {
        assertEquals("0 MB", QualityLadderManager.formatBytes(0))
        assertEquals("1.0 MB", QualityLadderManager.formatBytes(1_048_576))
        assertEquals("1.2 GB", QualityLadderManager.formatBytes(1_290_240_000))
    }
}
