package com.streamify.app.media.cache
import android.content.Context
import android.os.StatFs

object ElasticStorageAllocator {

    fun getDynamicCacheLimitBytes(context: Context): Long {
        try {
            val cacheDir = context.cacheDir
            val stat = StatFs(cacheDir.path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong

            return when {
                // Device critically full (< 2GB free) -> 100MB cache
                freeBytes < 2L * 1024 * 1024 * 1024 -> 100L * 1024 * 1024
                // Low storage (< 8GB free) -> 250MB cache
                freeBytes < 8L * 1024 * 1024 * 1024 -> 250L * 1024 * 1024
                // Medium storage (< 32GB free) -> 600MB cache
                freeBytes < 32L * 1024 * 1024 * 1024 -> 600L * 1024 * 1024
                // Abundant storage (>= 32GB free) -> 2GB cache
                else -> 2L * 1024 * 1024 * 1024
            }
        } catch (e: Exception) {
            return 300L * 1024 * 1024 // 300MB fallback
        }
    }
}
