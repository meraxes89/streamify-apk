package com.streamify.sync.v5

import com.streamify.supabase.SyncPullResult
import com.streamify.supabase.SyncPushResult
import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.planea.PushOutcome
import com.streamify.sync.v5.planea.SyncOutboxProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

        val insideRemoteApply = lifecycle.withBackupRestoreFence {
            guard.isRemoteApplying()
        }
        assertTrue(insideRemoteApply)
        assertFalse(guard.isRemoteApplying())
        assertEquals(0L, HybridLogicalClock.unpackCounter(HybridLogicalClock.pack(1000L, 0)))
    }

    @Test
    fun dataStoreSyncV5StorePersistsAcrossRestartsWith64BitHlc(): Unit = runBlocking {
        var persisted = ""
        val largeHlc = 7_558_000_000_000_000_123L // > 2^53 (WasmJS-unsafe if serialized as number)
        val writer = DataStoreSyncV5Store(
            readSnapshot = { persisted },
            writeSnapshot = { persisted = it },
        )
        writer.enqueueOrCoalesce(
            SyncOutboxRow("m-persist", "user_like", "yt:abc", "upsert", largeHlc, "dev-1", """{"track_id":"yt:abc"}"""),
        )
        writer.saveCheckpoint(SyncCheckpointRow(userId = "u1", deviceId = "dev-1", pullCursorRev = 42L, lastHlc = largeHlc, bootstrapComplete = true))
        assertTrue(persisted.isNotBlank())

        // Simulate process restart with a fresh DataStoreSyncV5Store instance
        val reader = DataStoreSyncV5Store(
            readSnapshot = { persisted },
            writeSnapshot = { persisted = it },
        )
        assertEquals(1, reader.pendingCount())
        val claimed = reader.claimPendingBatch(10)
        assertEquals(largeHlc, claimed.single().hlc)
        val cp = reader.getCheckpoint("u1")!!
        assertEquals(42L, cp.pullCursorRev)
        assertEquals(largeHlc, cp.lastHlc)
        assertTrue(cp.bootstrapComplete)
    }

    @Test
    fun v4ShimResponsesDecodeIntoLegacySyncPushAndPullModels() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val pushJson = """
            {
              "status": "OK",
              "applied": 2,
              "rejected": 0,
              "cursor": 14,
              "results": [
                {"key": "yt:abc", "entity": "like", "status": "applied", "error": null},
                {"key": "theme_mode", "entity": "settings", "status": "applied", "error": null}
              ]
            }
        """.trimIndent()
        val push = json.decodeFromString<SyncPushResult>(pushJson)
        assertEquals(2, push.applied)
        assertEquals(0, push.rejected)
        assertEquals(14L, push.cursor)
        assertEquals(2, push.results.size)

        val pullJson = """
            {
              "status": "OK",
              "cursor": 14,
              "head_cursor": 14,
              "has_more": false,
              "ops": [
                {"seq": 13, "entity": "like", "op": "U", "key": "yt:abc", "hlc": 7558000000000000000, "data": {"track_id": "yt:abc"}},
                {"seq": 14, "entity": "settings", "op": "U", "key": "theme_mode", "hlc": 7558000000000000001, "data": {"settings": {"theme_mode": "DARK"}}}
              ]
            }
        """.trimIndent()
        val pull = json.decodeFromString<SyncPullResult>(pullJson)
        assertEquals(14L, pull.cursor)
        assertEquals(14L, pull.headCursor)
        assertFalse(pull.hasMore)
        assertEquals(2, pull.ops.size)
        assertEquals("like", pull.ops[0].entity)
        assertEquals("settings", pull.ops[1].entity)
    }

    @Test
    fun outboxProcessorIsolatesPoisonMutationOnBatchTerminalError() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        store.enqueueOrCoalesce(
            SyncOutboxRow("m-good", "user_like", "trk_good", "upsert", 100L, "dev1", """{"track_id":"trk_good"}"""),
        )
        store.enqueueOrCoalesce(
            SyncOutboxRow("m-poison", "user_like", "trk_poison", "upsert", 200L, "dev1", """{"track_id":"trk_poison"}"""),
        )

        var calls = 0
        val client = PlaneAStateSyncClient(
            FakeRpcExecutor { rpcName, bodyJson ->
                assertEquals("sync_push_v5", rpcName)
                calls++
                when {
                    bodyJson.contains("m-good") && bodyJson.contains("m-poison") ->
                        FakeRpcResponse(httpStatus = 400, body = """{"message":"bad batch"}""")
                    bodyJson.contains("m-poison") ->
                        FakeRpcResponse(httpStatus = 400, body = """{"message":"bad item"}""")
                    else ->
                        FakeRpcResponse(
                            httpStatus = 200,
                            body = """{"protocol_version":5,"status":"OK","head_rev":"5","results":[{"mutation_id":"m-good","status":"applied","rev":"5"}]}""",
                        )
                }
            }
        )

        val acked = mutableListOf<String>()
        val processor = SyncOutboxProcessor(store, client, onMutationsAcked = { acked.addAll(it) })
        val outcome = processor.pushPending("u1", "dev1", 0L)

        assertTrue(outcome is PushOutcome.Completed)
        assertEquals(1, outcome.pushedCount)
        assertEquals(3, calls)
        assertEquals(listOf("m-good"), acked)
        val dl = store.getDeadLetterSummary()
        assertEquals(1, dl.count)
    }
}
