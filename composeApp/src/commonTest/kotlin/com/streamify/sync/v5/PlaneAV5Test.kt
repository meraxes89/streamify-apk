package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaneAV5Test {

    @Test
    fun fractionalIndexMaintainsStrictBytewiseOrderAndNoTrailingZero() {
        val a = FractionalIndex.between(null, null) { 7 }
        val b = FractionalIndex.between(a, null) { 19 }
        val mid = FractionalIndex.between(a, b) { 31 }
        assertTrue(a < mid && mid < b, "Expected $a < $mid < $b")
        assertTrue(FractionalIndex.isValid(a))
        assertTrue(FractionalIndex.isValid(mid))
        assertTrue(FractionalIndex.isValid(b))

        val rebalanced = FractionalIndex.rebalance(25) { 13 }
        assertEquals(25, rebalanced.size)
        for (i in 0 until rebalanced.size - 1) {
            assertTrue(rebalanced[i] < rebalanced[i + 1], "Expected ${rebalanced[i]} < ${rebalanced[i + 1]}")
        }
    }

    @Test
    fun outboxCoalescesPendingOnlyAndIsolatesDeadLetters() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        store.enqueueOrCoalesce(
            SyncOutboxRow("m1", "user_like", "trk_1", "upsert", 100L, "dev1", """{"track_id":"trk_1"}"""),
        )
        store.enqueueOrCoalesce(
            SyncOutboxRow("m2", "user_like", "trk_1", "delete", 200L, "dev1", """{"track_id":"trk_1"}"""),
        )
        assertEquals(1, store.pendingCount())

        val batch = store.claimPendingBatch(10)
        assertEquals(listOf("m2"), batch.map { it.mutationId })

        store.moveToDeadLetter("m2", "INVALID_PAYLOAD", "bad", 250L)
        assertEquals(0, store.pendingCount())
        assertTrue(store.claimPendingBatch(10).isEmpty())
        assertEquals(1, store.listDeadLetters().size)
    }

    @Test
    fun sessionLifecycleWipesStoreOnAccountSwitchAndFencesBackup() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        val guard = createPlatformSyncWriteGuard()
        val lifecycle = DefaultSyncSessionLifecycle(store, guard)

        lifecycle.onUserAuthenticated("user-a")
        store.enqueueOrCoalesce(
            SyncOutboxRow("m1", "user_like", "trk_1", "upsert", 100L, "dev1", """{"track_id":"trk_1"}"""),
        )
        assertEquals(1, store.pendingCount())

        lifecycle.onUserAuthenticated("user-b")
        assertEquals(0, store.pendingCount())
        assertNull(store.getCheckpoint("user-a"))

        var insideRemoteApply = false
        lifecycle.withBackupRestoreFence {
            insideRemoteApply = guard.isRemoteApplying()
        }
        assertTrue(insideRemoteApply)
        assertFalse(guard.isRemoteApplying())
        assertEquals(0L, HybridLogicalClock.unpackCounter(HybridLogicalClock.pack(1000L, 0)))
    }
}
