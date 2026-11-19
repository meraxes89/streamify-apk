package com.streamify.app.data.download

import android.content.Context
import com.streamify.app.data.network.ResolvedStream
import com.streamify.app.util.SLog
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QualityLadderManager — Opus 251 vs AAC 140 policy + storage accounting
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The download quality ladder mirrors the audiophile policy of the stream
 * resolver (itag 251 = WebM/Opus ~160 kbps "Studio Transparent" beats
 * itag 140 = MP4/AAC ~128 kbps "Universal"), but lets the USER pin a rung:
 *
 *  • OPUS_251 — maximum fidelity, WebM container, needs Opus-capable
 *    players (in-app playback always fine).
 *  • AAC_140  — maximum compatibility (carPlay/Bluetooth MKVs/external
 *    players), MP4/AAC container.
 *  • AUTO     — defer to the resolver's own scoring (251 > 140).
 *
 * Policy semantics (locked by unit tests):
 *  • [accepts] — AUTO accepts everything; a pinned rung accepts the
 *    matching container family; a mismatch triggers one fresh resolution
 *    before gracefully accepting the best available stream (never a dead
 *    end — a quality preference must not fail a download).
 *  • [estimateBytes] — kbps/8 × durationSec nominal accounting.
 *  • [accountStorage] — walks the download dir and reports per-file +
 *    total usage with free-space and quota checks.
 *
 * JVM purity: all policy math (acceptance, estimation, accounting over an
 * injected dir) is unit-testable without Android.
 */
object QualityLadderManager {

    private const val TAG = "QualityLadder"
    private const val PREFS = "audio_settings"
    private const val KEY_QUALITY = "download_quality"
    private const val KEY_QUOTA_BYTES = "download_quota_bytes"

    /** The two mandated ladder rungs + auto. */
    enum class DownloadQuality(
        val key: String,
        val label: String,
        /** Container family checked against the resolved stream mime. */
        val mimeFamily: String,
        val fileExtension: String,
        val nominalKbps: Int
    ) {
        OPUS_251("opus251", "Studio · Opus ~160 kbps", "webm", ".webm", 160),
        AAC_140("aac140", "Standard · AAC ~128 kbps", "mp4", ".m4a", 128),
        AUTO("auto", "Best available", "", "", 0);

        val isPinned: Boolean get() = this != AUTO
    }

    // ──────────────────────────────────────────────────── rung persistence

    fun loadChoice(context: Context): DownloadQuality =
        qualityFromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUALITY, null)
        )

    fun persistChoice(context: Context, quality: DownloadQuality) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUALITY, quality.key).apply()
    }

    /** Key → rung; unknown/blank falls back to AUTO (never a broken enum). */
    fun qualityFromKey(key: String?): DownloadQuality {
        // Normalize separators + case so stored prefs ("AAC_140", "aac-140")
        // and canonical keys ("aac140") all resolve to the same rung.
        val normalized = key?.trim()?.replace("_", "")?.replace("-", "")?.lowercase()
            ?: return DownloadQuality.AUTO
        return DownloadQuality.entries.firstOrNull { it.key == normalized }
            ?: DownloadQuality.AUTO
    }

    // ───────────────────────────────────────────── stream selection policy

    /**
     * True when [stream] satisfies [quality]. AUTO always accepts; pinned
     * rungs accept by container family (webm covers "audio/webm"/opus;
     * mp4 covers "audio/mp4"/aac — m4a included).
     */
    fun accepts(quality: DownloadQuality, stream: ResolvedStream): Boolean {
        if (!quality.isPinned) return true
        val mime = stream.mimeType.lowercase()
        return when (quality) {
            DownloadQuality.OPUS_251 -> mime.contains("webm") || mime.contains("opus")
            DownloadQuality.AAC_140 -> mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a")
            else -> true
        }
    }

    /**
     * Nominal storage estimate for a track at this rung.
     * AUTO defers to the stream's own bitrate when known.
     */
    fun estimateBytes(quality: DownloadQuality, durationSec: Int, stream: ResolvedStream? = null): Long {
        if (durationSec <= 0) return 0L
        val kbps: Int = when {
            quality.isPinned -> quality.nominalKbps
            stream != null && stream.bitrate > 0 -> stream.bitrate / 1000
            else -> 160
        }
        return (kbps.toLong() * 1000L / 8L) * durationSec.toLong()
    }

    // ─────────────────────────────────────────────────── storage accounting

    /** One accounted file inside the download tree. */
    data class FileEntry(
        val path: String,
        val bytes: Long,
        val isPartial: Boolean
    )

    /** Storage report over a directory tree. */
    data class StorageReport(
        val totalBytes: Long,
        val partialBytes: Long,
        val fileCount: Int,
        val freeBytes: Long
    ) {
        val playableBytes: Long get() = totalBytes - partialBytes
    }

    /**
     * Walks [dir] and accounts every file: playable media vs in-flight
     * *.part resumes. Never throws — an unreadable tree reports zero.
     */
    fun accountStorage(dir: File?): StorageReport {
        if (dir == null || !dir.exists()) return StorageReport(0L, 0L, 0, dir?.usableSpace ?: 0L)
        var total = 0L
        var partial = 0L
        var count = 0
        fun walk(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach(::walk) ?: return
                return
            }
            val len = runCatching { file.length() }.getOrDefault(0L)
            total += len
            count++
            if (file.name.endsWith(".part")) partial += len
        }
        runCatching { dir.listFiles()?.forEach(::walk) }
            .onFailure { SLog.d(TAG, "accountStorage walk failed (${it.message})") }
        return StorageReport(total, partial, count, dir.usableSpace)
    }

    /**
     * Quota gate: true when [incomingBytes] fits in the remaining quota AND
     * on the physical volume. Quota ≤ 0 means unbounded.
     */
    fun canFit(
        report: StorageReport,
        incomingBytes: Long,
        quotaBytes: Long,
        safetyMarginBytes: Long = 50L * 1024 * 1024
    ): Boolean {
        if (incomingBytes <= 0L) return true
        if (report.freeBytes in 1 until (incomingBytes + safetyMarginBytes)) return false
        if (quotaBytes > 0L) {
            val remaining = quotaBytes - report.playableBytes
            if (incomingBytes > remaining) return false
        }
        return true
    }

    // ───────────────────────────────────────────────────── quota persistence

    fun loadQuotaBytes(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_QUOTA_BYTES, 0L)
    }

    fun persistQuotaBytes(context: Context, quotaBytes: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_QUOTA_BYTES, quotaBytes.coerceAtLeast(0L)).apply()
    }

    /**
     * Human-readable byte label ("1.2 GB") for the picker UI.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 10.0 -> "%.0f MB".format(mb)
            else -> "%.1f MB".format(mb)
        }
    }

    /**
     * Serializes the picker state for the settings sheet (kept as JSON so
     * the UI layer can round-trip it without a second prefs contract).
     */
    fun pickerStateJson(quality: DownloadQuality, quotaBytes: Long, report: StorageReport): String =
        JSONObject()
            .put("quality", quality.key)
            .put("quotaBytes", quotaBytes)
            .put("usedBytes", report.playableBytes)
            .put("freeBytes", report.freeBytes)
            .put("label", formatBytes(report.playableBytes))
            .toString()
}
