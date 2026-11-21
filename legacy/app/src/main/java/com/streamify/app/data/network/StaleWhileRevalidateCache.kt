package com.streamify.app.data.network

import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * SwrCache — stale-while-revalidate local cache for scraper payloads
 * (Daylist, Daily Mixes, radio pages, Blend results).
 *
 * Gap #20/#26 resilience contract: if the user opens the app offline or on
 * high-packet-loss mobile data, the last successful shelf is served
 * immediately while a revalidation fetch runs in the background. A failed
 * revalidation NEVER evicts the stale entry — the cache only degrades in
 * freshness, never in availability.
 *
 * Design notes:
 *  - File-per-key JSON persistence under [dir]; a null dir degrades to
 *    memory-only (still correct, just not restart-stable).
 *  - [clock] is injectable so JVM unit tests can time-travel.
 *  - All reads/writes are mutex-guarded; corruption on disk (partial write,
 *    hostile content) is treated as a miss, never as a crash.
 */
class SwrCache(
    private val dir: File?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    data class Entry(val value: String, val storedAtMs: Long)

    private val memory = HashMap<String, Entry>()
    private val mutex = Mutex()

    /** Reads a cache entry; disk is consulted only on a memory miss. */
    suspend fun read(key: String): Entry? = mutex.withLock {
        memory[key]?.let { return it }
        val file = fileFor(key) ?: return null
        val onDisk = runCatching {
            if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            Entry(
                value = root.getString("v"),
                storedAtMs = root.getLong("t")
            )
        }.getOrNull()
        if (onDisk != null) memory[key] = onDisk
        onDisk
    }

    /** Writes through memory + disk. Disk failure is logged and swallowed. */
    suspend fun write(key: String, value: String) {
        val entry = Entry(value, clock())
        mutex.withLock {
            memory[key] = entry
            val file = fileFor(key) ?: return
            runCatching {
                dir!!.mkdirs()
                val tmp = File(dir, "${file.name}.tmp")
                tmp.writeText(
                    JSONObject().put("v", value).put("t", entry.storedAtMs).toString(),
                    Charsets.UTF_8
                )
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { SLog.w(TAG, "cache write failed for $key: ${it.message}") }
        }
    }

    /**
     * Stale-while-revalidate fetch.
     *
     * @param ttlMs      freshness window.
     * @param fetch      network fetch producing the canonical payload.
     * @param onRefresh  invoked on the caller's thread after a successful
     *                   background revalidation (e.g. to publish a new shelf).
     * @return cached payload when one exists (fresh OR stale), else the
     *         direct fetch result (or null when the network also failed and
     *         no cache exists — callers fall back to their local shelf).
     */
    suspend fun swr(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> String,
        onRefresh: suspend (String) -> Unit = {}
    ): String? {
        val cached = read(key)
        if (cached != null) {
            val age = clock() - cached.storedAtMs
            if (age in 0 until ttlMs) return cached.value
            // Stale: serve immediately, revalidate in the background.
            scope.launch {
                runCatching { fetch() }.onSuccess { fresh ->
                    if (fresh.isNotBlank()) {
                        write(key, fresh)
                        runCatching { onRefresh(fresh) }
                    }
                }
            }
            return cached.value
        }
        // Cold cache: must hit the network once, synchronously.
        return runCatching { fetch() }.getOrNull()?.takeIf { it.isNotBlank() }?.also {
            write(key, it)
        }
    }

    private fun fileFor(key: String): File? {
        if (dir == null) return null
        val safe = key.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
            .joinToString("").take(80).ifEmpty { "key" }
        return File(dir, "$safe.json")
    }

    private companion object {
        const val TAG = "SwrCache"
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    }
}
