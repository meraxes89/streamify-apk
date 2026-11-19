package com.streamify.app.media.cache
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object AudioCacheManager {
    @Volatile
    private var simpleCache: SimpleCache? = null
    @Volatile
    private var evictor: PriorityWeightedEvictor? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheDir = File(context.cacheDir, "streamify_audio_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val dynamicLimit = ElasticStorageAllocator.getDynamicCacheLimitBytes(context)
            val weightedEvictor = PriorityWeightedEvictor(dynamicLimit)
            evictor = weightedEvictor
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            simpleCache = SimpleCache(cacheDir, weightedEvictor, databaseProvider)
        }
        return requireNotNull(simpleCache) { "SimpleCache initialization failed" }
    }

    fun markStickyTrack(streamUrl: String) {
        evictor?.addStickyKey(streamUrl)
    }

    fun updateStickyKeys(keys: Collection<String>) {
        evictor?.setStickyKeys(keys)
    }
}
