package com.streamify.sync.v5.planeb

import com.streamify.supabase.v5.FetchListeningHistoryResponse
import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.PlaybackEventInputDto
import com.streamify.supabase.v5.RpcResult
import kotlin.math.max
import kotlin.math.min

enum class PlaybackQueueState {
    PENDING,
    IN_FLIGHT,
    ACKED,
}

data class PendingPlaybackEvent(
    val clientEventId: String,
    val trackId: String,
    val playedAtIso: String,
    val listenedSec: Int,
    val ackedSec: Int = 0,
    val trackDurationSec: Int? = null,
    val sourceContext: String = "unknown",
    val sourceId: String? = null,
    val trackTitle: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val artworkUrl: String? = null,
    val state: PlaybackQueueState = PlaybackQueueState.PENDING,
    val retryCount: Int = 0,
    val nextRetryAtMs: Long = 0L,
)

interface PlaybackEventStorage {
    suspend fun getById(clientEventId: String): PendingPlaybackEvent?
    suspend fun upsert(event: PendingPlaybackEvent)
    suspend fun getPendingBatch(limit: Int, nowMs: Long): List<PendingPlaybackEvent>
    suspend fun delete(clientEventId: String)
    suspend fun pendingCount(): Int
}

class InMemoryPlaybackEventStorage : PlaybackEventStorage {
    private val map = linkedMapOf<String, PendingPlaybackEvent>()

    override suspend fun getById(clientEventId: String): PendingPlaybackEvent? = map[clientEventId]

    override suspend fun upsert(event: PendingPlaybackEvent) {
        map[event.clientEventId] = event
    }

    override suspend fun getPendingBatch(limit: Int, nowMs: Long): List<PendingPlaybackEvent> =
        map.values
            .filter { it.state == PlaybackQueueState.PENDING && it.nextRetryAtMs <= nowMs }
            .take(limit)

    override suspend fun delete(clientEventId: String) {
        map.remove(clientEventId)
    }

    override suspend fun pendingCount(): Int =
        map.values.count { it.state == PlaybackQueueState.PENDING }

    fun snapshot(): List<PendingPlaybackEvent> = map.values.toList()
}

sealed interface PlaybackFlushOutcome {
    data class Flushed(val acked: Int, val requeued: Int, val rejected: Int) : PlaybackFlushOutcome
    data object Idle : PlaybackFlushOutcome
    data class Blocked(val reason: String) : PlaybackFlushOutcome
    data class RetryScheduled(val delayMs: Long) : PlaybackFlushOutcome
}

/**
 * Plane B playback event pipeline with checkpoint coalescing by `clientEventId`.
 */
class PlaybackEventPipeline(
    private val storage: PlaybackEventStorage,
    private val client: PlaneBEventClient,
    private val nowMs: () -> Long,
) {
    /**
     * Coalesces 30s/60s/pause/track-change checkpoints into one row per `clientEventId`.
     * Preserves immutable `playedAtIso` and handles `IN_FLIGHT` mid-flight updates safely.
     */
    suspend fun recordCheckpoint(incoming: PendingPlaybackEvent) {
        val existing = storage.getById(incoming.clientEventId)
        if (existing == null) {
            storage.upsert(incoming.copy(state = PlaybackQueueState.PENDING))
            return
        }
        val mergedSec = max(existing.listenedSec, incoming.listenedSec)
        val nextState = when (existing.state) {
            PlaybackQueueState.ACKED ->
                if (mergedSec > existing.ackedSec) PlaybackQueueState.PENDING else PlaybackQueueState.ACKED
            PlaybackQueueState.IN_FLIGHT -> PlaybackQueueState.IN_FLIGHT
            PlaybackQueueState.PENDING -> PlaybackQueueState.PENDING
        }
        storage.upsert(
            existing.copy(
                listenedSec = mergedSec,
                trackDurationSec = incoming.trackDurationSec ?: existing.trackDurationSec,
                trackTitle = incoming.trackTitle ?: existing.trackTitle,
                artistName = incoming.artistName ?: existing.artistName,
                albumName = incoming.albumName ?: existing.albumName,
                artworkUrl = incoming.artworkUrl ?: existing.artworkUrl,
                state = nextState,
            )
        )
    }

    suspend fun shouldFlushImmediately(): Boolean = storage.pendingCount() >= FLUSH_THRESHOLD

    suspend fun flushPending(deviceId: String): PlaybackFlushOutcome {
        val now = nowMs()
        val batch = storage.getPendingBatch(MAX_BATCH_SIZE, now)
        if (batch.isEmpty()) return PlaybackFlushOutcome.Idle

        for (item in batch) {
            storage.upsert(item.copy(state = PlaybackQueueState.IN_FLIGHT))
        }

        val batchPairs = batch.map { item ->
            item to PlaybackEventInputDto(
                clientEventId = item.clientEventId,
                trackId = item.trackId,
                playedAt = item.playedAtIso,
                listenedDurationSec = item.listenedSec,
                trackDurationSec = item.trackDurationSec,
                sourceContext = item.sourceContext,
                sourceId = item.sourceId,
                trackTitle = item.trackTitle,
                artistName = item.artistName,
                albumName = item.albumName,
                artworkUrl = item.artworkUrl,
            )
        }
        val dtos = batchPairs.map { it.second }

        return when (val res = client.ingestPlaybackEvents(deviceId, dtos)) {
            is RpcResult.Ok -> {
                var acked = 0
                var requeued = 0
                var rejected = 0
                val byId = res.data.results.associateBy { it.clientEventId }
                for (sent in batch) {
                    val itemRes = byId[sent.clientEventId]
                    val outcome = applyPlaybackItemOutcome(sent, itemRes?.status, itemRes?.acceptedDurationSec)
                    acked += outcome.first
                    requeued += outcome.second
                    rejected += outcome.third
                }
                PlaybackFlushOutcome.Flushed(acked = acked, requeued = requeued, rejected = rejected)
            }
            is RpcResult.DeviceRevoked -> {
                revertToPending(batch, 0L)
                PlaybackFlushOutcome.Blocked("DEVICE_REVOKED")
            }
            is RpcResult.Unauthorized -> {
                revertToPending(batch, 0L)
                PlaybackFlushOutcome.Blocked("UNAUTHORIZED")
            }
            is RpcResult.RateLimited -> {
                revertToPending(batch, now + res.retryAfterMs)
                PlaybackFlushOutcome.RetryScheduled(res.retryAfterMs)
            }
            is RpcResult.TransientFailure -> {
                val delayMs = computeBackoffMs(batch.maxOf { it.retryCount } + 1)
                revertToPending(batch, now + delayMs, incrementRetry = true)
                PlaybackFlushOutcome.RetryScheduled(delayMs)
            }
            is RpcResult.TerminalError -> {
                if (batchPairs.size == 1) {
                    storage.delete(batchPairs[0].first.clientEventId)
                    return PlaybackFlushOutcome.Flushed(acked = 0, requeued = 0, rejected = 1)
                }
                var acked = 0
                var requeued = 0
                var rejected = 0
                for (idx in batchPairs.indices) {
                    val (sent, dto) = batchPairs[idx]
                    when (val singleRes = client.ingestPlaybackEvents(deviceId, listOf(dto))) {
                        is RpcResult.Ok -> {
                            val itemRes = singleRes.data.results.firstOrNull { it.clientEventId == sent.clientEventId }
                            val outcome = applyPlaybackItemOutcome(sent, itemRes?.status, itemRes?.acceptedDurationSec)
                            acked += outcome.first
                            requeued += outcome.second
                            rejected += outcome.third
                        }
                        is RpcResult.TerminalError -> {
                            storage.delete(sent.clientEventId)
                            rejected++
                        }
                        is RpcResult.DeviceRevoked -> {
                            revertToPending(batchPairs.drop(idx).map { it.first }, 0L)
                            return PlaybackFlushOutcome.Blocked("DEVICE_REVOKED")
                        }
                        is RpcResult.Unauthorized -> {
                            revertToPending(batchPairs.drop(idx).map { it.first }, 0L)
                            return PlaybackFlushOutcome.Blocked("UNAUTHORIZED")
                        }
                        is RpcResult.RateLimited -> {
                            revertToPending(batchPairs.drop(idx).map { it.first }, now + singleRes.retryAfterMs)
                            return PlaybackFlushOutcome.RetryScheduled(singleRes.retryAfterMs)
                        }
                        is RpcResult.TransientFailure -> {
                            val remaining = batchPairs.drop(idx).map { it.first }
                            val delayMs = computeBackoffMs(remaining.maxOf { it.retryCount } + 1)
                            revertToPending(remaining, now + delayMs, incrementRetry = true)
                            return PlaybackFlushOutcome.RetryScheduled(delayMs)
                        }
                        is RpcResult.CursorExpired, is RpcResult.ClockSkew -> {
                            revertToPending(batchPairs.drop(idx).map { it.first }, now + 1000L)
                            return PlaybackFlushOutcome.RetryScheduled(1000L)
                        }
                    }
                }
                PlaybackFlushOutcome.Flushed(acked = acked, requeued = requeued, rejected = rejected)
            }
            is RpcResult.CursorExpired, is RpcResult.ClockSkew -> {
                revertToPending(batch, now + 1000L)
                PlaybackFlushOutcome.RetryScheduled(1000L)
            }
        }
    }

    private suspend fun applyPlaybackItemOutcome(
        sent: PendingPlaybackEvent,
        status: String?,
        acceptedDurationSec: Int?,
    ): Triple<Int, Int, Int> {
        val latest = storage.getById(sent.clientEventId) ?: return Triple(0, 0, 0)
        if (status == null) {
            storage.upsert(latest.copy(state = PlaybackQueueState.PENDING))
            return Triple(0, 1, 0)
        }
        return when (status) {
            "inserted", "updated", "noop" -> {
                val acceptedSec = acceptedDurationSec ?: sent.listenedSec
                val newAcked = max(latest.ackedSec, acceptedSec)
                if (latest.listenedSec > newAcked) {
                    storage.upsert(
                        latest.copy(
                            ackedSec = newAcked,
                            state = PlaybackQueueState.PENDING,
                            retryCount = 0,
                            nextRetryAtMs = 0L,
                        )
                    )
                    Triple(0, 1, 0)
                } else {
                    storage.upsert(
                        latest.copy(
                            ackedSec = newAcked,
                            state = PlaybackQueueState.ACKED,
                            retryCount = 0,
                            nextRetryAtMs = 0L,
                        )
                    )
                    Triple(1, 0, 0)
                }
            }
            "rejected" -> {
                storage.delete(sent.clientEventId)
                Triple(0, 0, 1)
            }
            else -> {
                storage.upsert(latest.copy(state = PlaybackQueueState.PENDING))
                Triple(0, 1, 0)
            }
        }
    }

    suspend fun fetchRecentPage(
        cursorPlayedAt: String? = null,
        cursorEventId: String? = null,
        limit: Int = 50,
        since: String? = null,
    ): RpcResult<FetchListeningHistoryResponse> =
        client.fetchListeningHistory(cursorPlayedAt, cursorEventId, limit, since)

    private suspend fun revertToPending(
        batch: List<PendingPlaybackEvent>,
        nextRetryAtMs: Long,
        incrementRetry: Boolean = false,
    ) {
        for (sent in batch) {
            val latest = storage.getById(sent.clientEventId) ?: continue
            storage.upsert(
                latest.copy(
                    state = PlaybackQueueState.PENDING,
                    retryCount = if (incrementRetry) latest.retryCount + 1 else latest.retryCount,
                    nextRetryAtMs = nextRetryAtMs,
                )
            )
        }
    }

    private fun computeBackoffMs(attempt: Int): Long {
        val exp = 1L shl min(attempt, 6)
        return min(exp * 1000L, 60_000L)
    }

    companion object {
        const val FLUSH_THRESHOLD = 25
        const val MAX_BATCH_SIZE = 100
    }
}
