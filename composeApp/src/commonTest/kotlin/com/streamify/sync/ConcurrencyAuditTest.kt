package com.streamify.sync

import com.streamify.announcements.Announcement
import com.streamify.announcements.AnnouncementRecord
import com.streamify.announcements.AnnouncementStore
import com.streamify.announcements.LocalizedContent
import com.streamify.announcements.PresentationArbiter
import com.streamify.announcements.UiContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyAuditTest {

    @Test
    fun hybridLogicalClockRemainsStrictlyUniqueAndMonotonicUnderConcurrentCalls() = runBlocking {
        HybridLogicalClock.resetForTesting(lastMs = 1_700_000_000_000L, counter = 0, serverTime = 1_700_000_000_000L)

        val workers = 16
        val perWorker = 200
        val allHlcs = (0 until workers).map { workerId ->
            async(Dispatchers.Default) {
                val results = ArrayList<Long>(perWorker)
                var prev = 0L
                for (i in 0 until perWorker) {
                    if (i % 50 == 0) {
                        HybridLogicalClock.updateServerTime(1_700_000_000_000L + workerId)
                    }
                    val next = if (i % 4 == 0) {
                        HybridLogicalClock.merge(
                            remoteHlc = HybridLogicalClock.pack(1_700_000_000_000L, i),
                            physicalMillis = 1_700_000_000_000L,
                        )
                    } else {
                        HybridLogicalClock.now(physicalMillis = 1_700_000_000_000L)
                    }
                    assertTrue(next > prev, "per-worker HLC must increase: $next <= $prev")
                    prev = next
                    results.add(next)
                }
                results
            }
        }.awaitAll().flatten()

        assertEquals(workers * perWorker, allHlcs.toSet().size, "all concurrent HLCs must be unique")
    }

    @Test
    fun presentationArbiterAndAnnouncementStoreHandleConcurrentAccessSafely() = runBlocking {
        val arbiter = PresentationArbiter()
        val store = AnnouncementStore(dataStoreManager = null)
        val idleCtx = UiContext(isPlaying = false, isModalActive = false, isOnboarding = false)
        val busyCtx = UiContext(isPlaying = true, isModalActive = false, isOnboarding = false)

        val jobs = (0 until 20).map { idx ->
            async(Dispatchers.Default) {
                val item = Announcement(
                    id = "ann_$idx",
                    content = mapOf("en" to LocalizedContent(title = "Title $idx", body = "Body $idx")),
                )
                arbiter.tryPresent(item, if (idx % 2 == 0) busyCtx else idleCtx)
                arbiter.onContextChanged(idleCtx)
                arbiter.dismissActive()

                store.saveRecord(AnnouncementRecord(id = "rec_$idx", impressions = idx))
                val loaded = store.getRecord("rec_$idx")
                assertEquals("rec_$idx", loaded.id)
            }
        }
        jobs.awaitAll()
    }
}
