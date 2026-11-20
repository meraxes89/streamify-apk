package com.streamify.app.media.cache
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

class PriorityWeightedEvictor(private val maxBytes: Long) : CacheEvictor, Comparator<CacheSpan> {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(this)
    private var currentBytes: Long = 0
    private val stickyKeys = ConcurrentHashMap.newKeySet<String>()

    fun setStickyKeys(keys: Collection<String>) {
        stickyKeys.clear()
        stickyKeys.addAll(keys)
    }

    fun addStickyKey(key: String) {
        stickyKeys.add(key)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        // Ready
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length < 0) return
        evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentBytes += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    override fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
        if (lhs.lastTouchTimestamp - rhs.lastTouchTimestamp == 0L) {
            return lhs.compareTo(rhs)
        }
        return if (lhs.lastTouchTimestamp < rhs.lastTouchTimestamp) -1 else 1
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        // 1. First pass: Evict disposable non-sticky spans
        while (currentBytes + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val nonStickySpan = leastRecentlyUsed.firstOrNull { !stickyKeys.contains(it.key) }
            if (nonStickySpan != null) {
                cache.removeSpan(nonStickySpan)
            } else {
                break
            }
        }

        // 2. Second pass: Only if non-sticky spans are totally exhausted, evict oldest sticky span
        while (currentBytes + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val oldestSpan = leastRecentlyUsed.first()
            cache.removeSpan(oldestSpan)
        }
    }
}
