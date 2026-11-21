package com.streamify.app.data.network

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ConnectionWarmer {
    suspend fun preWarmCDN(cdnUrl: String) = withContext(Dispatchers.IO) {
        // Safe DNS resolution trigger without sending malformed HTTP probes
        if (cdnUrl.isBlank() || !cdnUrl.startsWith("http")) return@withContext
        try {
            val uri = android.net.Uri.parse(cdnUrl)
            val host = uri.host ?: return@withContext
            InetAddress.getByName(host)
        } catch (_: Throwable) {
            // Fail silently; ExoPlayer will resolve normally
        }
    }
}

object NetworkEngine {

    /**
     * Terminal-visible HTTP logging: one line per request + one per response,
     * routed into SLog (redaction for auth headers happens inside SLog).
     */
    private val httpLogger = okhttp3.logging.HttpLoggingInterceptor { msg ->
        com.streamify.app.util.SLog.d("HTTP", msg)
    }.apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.NONE }

    /** Diagnostic-capture toggle flips HTTP tracing without rebuilding clients. */
    fun setHttpTracing(on: Boolean) {
        httpLogger.level =
            if (on) okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
            else okhttp3.logging.HttpLoggingInterceptor.Level.NONE
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(6000, TimeUnit.MILLISECONDS)
            .writeTimeout(5000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val exoPlayerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(10000, TimeUnit.MILLISECONDS)
            .readTimeout(15000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

object StreamEdgeCache {
    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 4 // 4 Hours
    private val audioCache = LruCache<String, CachedStream>(64)
    private val videoCache = LruCache<String, CachedStream>(64)
    private val expiryRegex = Regex("[?&]expire=([0-9]+)")

    data class CachedStream(
        val stream: ResolvedStream,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun isNearExpiry(url: String, safetyMarginSec: Long = 7200L): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return true
        val expireEpoch = expiryRegex.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec >= (expireEpoch - safetyMarginSec) // 2-hour early safety buffer
    }

    fun getStream(videoId: String): ResolvedStream? {
        val entry = audioCache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS || isNearExpiry(entry.stream.streamUrl)) {
            audioCache.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun putStream(videoId: String, stream: ResolvedStream) {
        audioCache.put(videoId, CachedStream(stream))
    }

    fun getVideoStream(videoId: String): ResolvedStream? {
        val entry = videoCache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS || isNearExpiry(entry.stream.streamUrl)) {
            videoCache.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun evictStream(videoId: String) {
        audioCache.remove(videoId)
        videoCache.remove(videoId)
    }

    fun putVideoStream(videoId: String, stream: ResolvedStream) {
        videoCache.put(videoId, CachedStream(stream))
    }

    fun clear() {
        audioCache.evictAll()
        videoCache.evictAll()
    }
}
