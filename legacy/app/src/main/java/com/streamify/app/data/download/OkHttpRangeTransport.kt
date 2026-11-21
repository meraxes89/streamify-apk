package com.streamify.app.data.download

import com.streamify.app.data.network.NetworkEngine
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * Production [ResumableDownloadEngine.RangeTransport] over the shared
 * OkHttp client. Honors byte-range resumption via `Range: bytes=<n>-` and
 * parses the total size from Content-Range (206) or Content-Length (200).
 */
class OkHttpRangeTransport : ResumableDownloadEngine.RangeTransport {

    override fun open(url: String, offsetBytes: Long): ResumableDownloadEngine.RangeResponse? {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (offsetBytes > 0L) {
                    header("Range", "bytes=$offsetBytes-")
                }
            }
            .get()
            .build()

        val response = NetworkEngine.client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            // 416 = unsatisfiable range — the .part file is already complete
            // or stale; signal restart-by-refusal so the engine retries and
            // the caller can verify bytes.
            if (code == 416) return null
            throw IOException("HTTP $code opening $url")
        }

        val body = response.body ?: run {
            response.close()
            throw IOException("empty body")
        }

        val partial = response.code == 206
        val totalBytes = parseTotalBytes(response.header("Content-Range"), body.contentLength(), partial)
        // Note: closing the response would close body's stream — the engine
        // owns stream lifetime; response GC-release is handled by OkHttp when
        // the stream is fully consumed/closed.
        return ResumableDownloadEngine.RangeResponse(
            stream = body.byteStream(),
            totalBytes = totalBytes,
            partial = partial
        )
    }

    /**
     * Content-Range: "bytes 1000-4999/5000" → 5000 (total).
     * 200 path: Content-Length is the total; 206 with unknown total ("-")
     * falls back to contentLength + offset as a lower bound estimate.
     */
    private fun parseTotalBytes(contentRange: String?, contentLength: Long, partial: Boolean): Long {
        if (!contentRange.isNullOrBlank()) {
            val total = contentRange.substringAfterLast('/').trim()
            if (total != "*") {
                total.toLongOrNull()?.let { return it }
            }
            // Unknown total ("*"): estimate = offset + remaining length.
            val start = contentRange.substringAfter("bytes").substringBefore('-').trim().toLongOrNull()
            if (start != null && contentLength >= 0) return start + contentLength
        }
        if (contentLength >= 0) {
            return if (partial) {
                // No Content-Range header on a 206 (defensive): contentLength
                // here is the REMAINING length; offset unknown → under-estimate
                // is fine, verification treats <=0 as unknown-but-accept.
                -1L
            } else {
                contentLength
            }
        }
        return -1L
    }
}
