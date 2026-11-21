package com.streamify.app.media.cache
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.ConnectionWarmer
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class PredictivePreBufferManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePreCacheJobs = ConcurrentHashMap<String, Job>()

    // 512KB Head-Chunk Matrix (~25-30s of 160kbps Opus audio container header & stream)
    private val HEAD_CHUNK_BYTES = 512 * 1024L

    fun preBufferUpcomingTracks(upcomingTracks: List<Track>) {
        val targets = upcomingTracks.take(2)

        targets.forEach { track ->
            val trackKey = if (track.id != 0) track.id.toString() else track.title
            if (activePreCacheJobs[trackKey]?.isActive == true) return@forEach

            val job = scope.launch {
                com.streamify.app.data.NativeBridge.pinThreadToLittleCores()
                runCatching {
                    val streamUrl = if (track.filepath.startsWith("http")) {
                        track.filepath
                    } else {
                        val vid = track.ytmVideoId ?: YouTubeStreamResolver.extractVideoId(track.filepath)
                        if (vid != null) YouTubeStreamResolver.resolveStreamUrl(vid)?.streamUrl else null
                    } ?: return@launch

                    val cache = AudioCacheManager.getCache(context)

                    // If already cached on disk/RAM, avoid redundant network I/O
                    if (cache.isCached(streamUrl, 0L, HEAD_CHUNK_BYTES)) {
                        ConnectionWarmer.preWarmCDN(streamUrl)
                        return@launch
                    }

                    // Use shared ExoPlayer OkHttp client (reuses warm connection pool & DNS cache)
                    val upstreamFactory = OkHttpDataSource.Factory(NetworkEngine.exoPlayerClient)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

                    val cacheDataSourceFactory = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                    val uri = Uri.parse(streamUrl)
                    val dataSpec = DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(0)
                        .setLength(HEAD_CHUNK_BYTES)
                        .setKey(streamUrl)
                        .build()

                    val cacheWriter = CacheWriter(
                        cacheDataSourceFactory.createDataSource(),
                        dataSpec,
                        null,
                        null
                    )

                    // Write 512KB head-chunk directly into Media3 SimpleCache
                    cacheWriter.cache()

                    // Pre-warm socket for bytes beyond 512KB
                    ConnectionWarmer.preWarmCDN(streamUrl)
                }
            }

            activePreCacheJobs[trackKey] = job
        }
    }

    fun cancelAll() {
        activePreCacheJobs.values.forEach { it.cancel() }
        activePreCacheJobs.clear()
    }

    fun release() {
        cancelAll()
        scope.cancel()
    }

    /**
     * JAM PHASE-1 (P3): process-wide shadow pre-buffer for host NEXT_IS intents.
     *
     * The guest resolves + caches the announced next track's head chunk BEFORE
     * TRACK_CHANGE lands, so the handoff reads warm bytes out of SimpleCache —
     * zero dead air, zero resolve latency. Installed lazily with the app
     * context; safe to call from any thread.
     */
    object JamPreBuffer {
        @Volatile private var delegate: PredictivePreBufferManager? = null

        fun install(manager: PredictivePreBufferManager) {
            delegate = manager
        }

        fun notifyNextIs(track: Track) {
            val mgr = delegate ?: return
            com.streamify.app.util.SLog.d("JamShadow", "NEXT_IS → shadow pre-buffer '${track.title}'")
            // Reuses the 512KB head-chunk matrix; dedupe via activePreCacheJobs.
            mgr.preBufferUpcomingTracks(listOf(track))
        }

        /** Invalidate on TRACK_CHANGE / session end so stale jobs don't linger. */
        fun reset() {
            delegate?.cancelAll()
        }
    }
}

