package com.streamify.sync

/**
 * Snapshot emitted by [PlaybackSessionTracker] when an active listening session
 * meets eligibility (`>= 30s` active playback, or `>= 50%` for tracks under 60s).
 */
data class PlaybackHistoryFlush(
    val clientEventId: String,
    val trackId: String,
    val title: String,
    val artistName: String,
    val albumId: String,
    val albumName: String,
    val durationSec: Int,
    val listenedSec: Int,
    val startedAtMs: Long,
    val isInitialInsert: Boolean,
)

/**
 * Pure, wall-clock playback session accumulator immune to seeking and metadata gaps.
 *
 * Accumulates active playback time (`<= 2_000 ms` per tick) and flushes idempotently
 * using a single [clientEventId] per listening session across 30s checkpoints, pauses,
 * queue endings, and track transitions.
 */
class PlaybackSessionTracker(
    private val idGenerator: () -> String = { HybridLogicalClock.randomUuid() },
) {
    private data class ActiveSession(
        val clientEventId: String,
        val trackId: String,
        val title: String,
        val artistName: String,
        val albumId: String,
        val albumName: String,
        val durationSec: Int,
        val startedAtMs: Long,
        var lastTickMs: Long,
        var accumulatedMs: Long = 0L,
        var lastFlushedSec: Int = 0,
        var hasInserted: Boolean = false,
    )

    private var active: ActiveSession? = null

    companion object {
        const val MIN_ELIGIBLE_MS = 30_000L
        const val MAX_TICK_DELTA_MS = 2_000L
        const val CHECKPOINT_INTERVAL_SEC = 30
    }

    fun onTrackChanged(
        trackId: String,
        title: String,
        artistName: String,
        albumId: String,
        albumName: String,
        durationSec: Int,
        nowMs: Long,
    ): PlaybackHistoryFlush? {
        val current = active
        if (current != null && current.trackId == trackId) {
            return null
        }
        val flushed = flushIfEligible(current, force = true)
        active = if (trackId.isNotBlank() && !trackId.startsWith("trk_")) {
            ActiveSession(
                clientEventId = idGenerator(),
                trackId = trackId,
                title = title,
                artistName = artistName,
                albumId = albumId,
                albumName = albumName,
                durationSec = durationSec.coerceAtLeast(0),
                startedAtMs = nowMs,
                lastTickMs = nowMs,
            )
        } else {
            null
        }
        return flushed
    }

    fun onProgressTick(
        trackId: String,
        title: String,
        artistName: String,
        albumId: String,
        albumName: String,
        durationSec: Int,
        nowMs: Long,
    ): PlaybackHistoryFlush? {
        var session = active
        if (session == null || session.trackId != trackId) {
            // Started mid-stream or missed transition: open session and return any prior flush
            val prior = flushIfEligible(session, force = true)
            if (trackId.isNotBlank() && !trackId.startsWith("trk_")) {
                active = ActiveSession(
                    clientEventId = idGenerator(),
                    trackId = trackId,
                    title = title,
                    artistName = artistName,
                    albumId = albumId,
                    albumName = albumName,
                    durationSec = durationSec.coerceAtLeast(0),
                    startedAtMs = nowMs,
                    lastTickMs = nowMs,
                )
            } else {
                active = null
            }
            return prior
        }

        val delta = (nowMs - session.lastTickMs).coerceIn(0L, MAX_TICK_DELTA_MS)
        session.lastTickMs = nowMs
        session.accumulatedMs += delta

        return flushIfEligible(session, force = false)
    }

    fun onPausedOrEnded(): PlaybackHistoryFlush? {
        return flushIfEligible(active, force = true)
    }

    private fun isEligible(session: ActiveSession): Boolean {
        val thresholdMs = if (session.durationSec in 1..59) {
            (session.durationSec * 500L).coerceAtLeast(10_000L)
        } else {
            MIN_ELIGIBLE_MS
        }
        return session.accumulatedMs >= thresholdMs
    }

    private fun flushIfEligible(session: ActiveSession?, force: Boolean): PlaybackHistoryFlush? {
        if (session == null || !isEligible(session)) return null
        val listenedSec = (session.accumulatedMs / 1000L).toInt().coerceAtLeast(1)
        val deltaSinceFlush = listenedSec - session.lastFlushedSec
        if (deltaSinceFlush <= 0) return null
        if (!force && session.hasInserted && deltaSinceFlush < CHECKPOINT_INTERVAL_SEC) return null

        val isInitial = !session.hasInserted
        session.hasInserted = true
        session.lastFlushedSec = listenedSec
        return PlaybackHistoryFlush(
            clientEventId = session.clientEventId,
            trackId = session.trackId,
            title = session.title,
            artistName = session.artistName,
            albumId = session.albumId,
            albumName = session.albumName,
            durationSec = session.durationSec,
            listenedSec = listenedSec,
            startedAtMs = session.startedAtMs,
            isInitialInsert = isInitial,
        )
    }
}
