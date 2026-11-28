package com.streamify.sync.v5.planeb

import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.SearchQueryInputDto

data class CachedSearchEntry(
    val queryNorm: String,
    val query: String,
    val resultType: String = "all",
    val searchedAtIso: String,
    val updatedAtIso: String = searchedAtIso,
)

sealed interface SearchQueueOp {
    val opTimeIso: String

    data class Upsert(
        val queryNorm: String,
        val query: String,
        val resultType: String,
        override val opTimeIso: String,
    ) : SearchQueueOp

    data class Delete(
        val queryNorm: String,
        val rawQuery: String,
        override val opTimeIso: String,
    ) : SearchQueueOp

    data class ClearAll(
        override val opTimeIso: String,
    ) : SearchQueueOp
}

data class SearchSyncWatermark(
    val clearEpoch: Long = 0L,
    val clearedAtIso: String = "-infinity",
    val lastSyncIso: String? = null,
)

interface SearchHistoryStorage {
    suspend fun getWatermark(): SearchSyncWatermark
    suspend fun saveWatermark(watermark: SearchSyncWatermark)
    suspend fun getCachedItems(): List<CachedSearchEntry>
    suspend fun replaceCache(items: List<CachedSearchEntry>)
    suspend fun upsertCacheItem(item: CachedSearchEntry)
    suspend fun removeCacheItem(queryNorm: String)
    suspend fun getQueuedOps(): List<SearchQueueOp>
    suspend fun replaceQueuedOps(ops: List<SearchQueueOp>)
}

class InMemorySearchHistoryStorage : SearchHistoryStorage {
    private var watermark = SearchSyncWatermark()
    private val cache = linkedMapOf<String, CachedSearchEntry>()
    private val queue = mutableListOf<SearchQueueOp>()

    override suspend fun getWatermark(): SearchSyncWatermark = watermark

    override suspend fun saveWatermark(watermark: SearchSyncWatermark) {
        this.watermark = watermark
    }

    override suspend fun getCachedItems(): List<CachedSearchEntry> =
        cache.values.sortedByDescending { it.searchedAtIso }

    override suspend fun replaceCache(items: List<CachedSearchEntry>) {
        cache.clear()
        for (item in items) cache[item.queryNorm] = item
    }

    override suspend fun upsertCacheItem(item: CachedSearchEntry) {
        cache[item.queryNorm] = item
    }

    override suspend fun removeCacheItem(queryNorm: String) {
        cache.remove(queryNorm)
    }

    override suspend fun getQueuedOps(): List<SearchQueueOp> = queue.toList()

    override suspend fun replaceQueuedOps(ops: List<SearchQueueOp>) {
        queue.clear()
        queue.addAll(ops)
    }
}

/**
 * Cross-device Search History coordinator using `clear_epoch` watermarks and per-query tombstones.
 */
class SearchHistoryCoordinator(
    private val storage: SearchHistoryStorage,
    private val client: PlaneBEventClient,
) {
    suspend fun onSearchSubmitted(
        query: String,
        resultType: String = "all",
        searchedAtIso: String,
    ) {
        val norm = normalizeSearchQuery(query)
        if (norm.isEmpty()) return

        storage.upsertCacheItem(
            CachedSearchEntry(
                queryNorm = norm,
                query = query.trim().take(256),
                resultType = resultType,
                searchedAtIso = searchedAtIso,
            )
        )
        val filtered = storage.getQueuedOps().filterNot {
            (it is SearchQueueOp.Upsert && it.queryNorm == norm) ||
                (it is SearchQueueOp.Delete && it.queryNorm == norm)
        }
        storage.replaceQueuedOps(
            filtered + SearchQueueOp.Upsert(
                queryNorm = norm,
                query = query.trim().take(256),
                resultType = resultType,
                opTimeIso = searchedAtIso,
            )
        )
    }

    suspend fun onDeleteQuery(query: String, deletedAtIso: String) {
        val norm = normalizeSearchQuery(query)
        if (norm.isEmpty()) return

        storage.removeCacheItem(norm)
        val filtered = storage.getQueuedOps().filterNot {
            (it is SearchQueueOp.Upsert && it.queryNorm == norm) ||
                (it is SearchQueueOp.Delete && it.queryNorm == norm)
        }
        storage.replaceQueuedOps(
            filtered + SearchQueueOp.Delete(
                queryNorm = norm,
                rawQuery = query.trim().take(256),
                opTimeIso = deletedAtIso,
            )
        )
    }

    suspend fun onClearAll(clearedAtIso: String) {
        storage.replaceCache(emptyList())
        // Drop all prior pending UPSERT/DELETE ops; only CLEAR_ALL survives.
        storage.replaceQueuedOps(listOf(SearchQueueOp.ClearAll(clearedAtIso)))
    }

    suspend fun sync(limit: Int = 100): Boolean {
        val ops = storage.getQueuedOps()
        val remainingOps = mutableListOf<SearchQueueOp>()
        var pushFailed = false

        // Drain CLEAR_ALL / DELETE / UPSERT in chronological order.
        val pendingUpserts = mutableListOf<SearchQueueOp.Upsert>()

        suspend fun flushUpserts(): Boolean {
            if (pendingUpserts.isEmpty()) return true
            val dtos = pendingUpserts.map {
                SearchQueryInputDto(it.query, it.resultType, it.opTimeIso)
            }
            return when (val res = client.upsertSearchQuery(dtos)) {
                is RpcResult.Ok -> {
                    pendingUpserts.clear()
                    true
                }
                else -> false
            }
        }

        for (op in ops) {
            if (pushFailed) {
                remainingOps += op
                continue
            }
            when (op) {
                is SearchQueueOp.Upsert -> pendingUpserts += op
                is SearchQueueOp.Delete -> {
                    if (!flushUpserts()) {
                        pushFailed = true
                        remainingOps.addAll(pendingUpserts)
                        pendingUpserts.clear()
                        remainingOps += op
                        continue
                    }
                    if (client.deleteSearchQuery(op.rawQuery, op.opTimeIso) !is RpcResult.Ok) {
                        pushFailed = true
                        remainingOps += op
                    }
                }
                is SearchQueueOp.ClearAll -> {
                    pendingUpserts.clear()
                    if (client.clearSearchHistory(op.opTimeIso) !is RpcResult.Ok) {
                        pushFailed = true
                        remainingOps += op
                    }
                }
            }
        }
        if (!pushFailed && !flushUpserts()) {
            remainingOps.addAll(pendingUpserts)
        }
        storage.replaceQueuedOps(remainingOps)
        if (remainingOps.isNotEmpty()) return false

        val wm = storage.getWatermark()
        val pullRes = client.fetchSearchHistory(
            sinceUpdatedAt = wm.lastSyncIso,
            knownClearEpoch = wm.clearEpoch,
            limit = limit,
        )
        if (pullRes !is RpcResult.Ok) return false
        val data = pullRes.data

        if (data.fullResetRequired) {
            val serverItems = data.activeItems.map {
                CachedSearchEntry(
                    queryNorm = it.queryNorm,
                    query = it.query,
                    resultType = it.resultType,
                    searchedAtIso = it.searchedAt,
                    updatedAtIso = it.updatedAt,
                )
            }
            storage.replaceCache(serverItems)
        } else {
            for (item in data.activeItems) {
                storage.upsertCacheItem(
                    CachedSearchEntry(
                        queryNorm = item.queryNorm,
                        query = item.query,
                        resultType = item.resultType,
                        searchedAtIso = item.searchedAt,
                        updatedAtIso = item.updatedAt,
                    )
                )
            }
            val currentByNorm = storage.getCachedItems().associateBy { it.queryNorm }
            for (tomb in data.tombstones) {
                val local = currentByNorm[tomb.queryNorm]
                if (local != null && local.searchedAtIso <= tomb.deletedAt) {
                    storage.removeCacheItem(tomb.queryNorm)
                }
            }
        }

        storage.saveWatermark(
            SearchSyncWatermark(
                clearEpoch = data.clearEpoch,
                clearedAtIso = data.searchClearedAt ?: wm.clearedAtIso,
                lastSyncIso = data.serverTime ?: wm.lastSyncIso,
            )
        )
        return true
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun normalizeSearchQuery(query: String): String =
            query.trim().replace(WHITESPACE_REGEX, " ").lowercase().take(256)
    }
}
