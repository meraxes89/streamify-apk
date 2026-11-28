package com.streamify.sync.v5

import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.sync.v5.planeb.InMemoryPlaybackEventStorage
import com.streamify.sync.v5.planeb.InMemorySearchHistoryStorage
import com.streamify.sync.v5.planeb.PendingPlaybackEvent
import com.streamify.sync.v5.planeb.PlaybackEventPipeline
import com.streamify.sync.v5.planeb.PlaybackFlushOutcome
import com.streamify.sync.v5.planeb.PlaybackQueueState
import com.streamify.sync.v5.planeb.SearchHistoryCoordinator
import com.streamify.sync.v5.planeb.SearchQueueOp
import com.streamify.sync.v5.planeb.WrappedSyncCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaneBV5Test {

    private fun fakeClient(
        handler: suspend (rpcName: String, bodyJson: String) -> FakeRpcResponse,
    ): PlaneBEventClient = PlaneBEventClient(FakeRpcExecutor(handler))

    @Test
    fun coalescesPlaybackCheckpointsAndRequeuesIfAdvancedMidFlight() : Unit = runBlocking {
        val storage = InMemoryPlaybackEventStorage()
        lateinit var pipeline: PlaybackEventPipeline
        val client = fakeClient { rpcName, _ ->
            assertEquals("ingest_playback_events", rpcName)
            // Simulate a 90s checkpoint arriving while the 60s batch is IN_FLIGHT
            pipeline.recordCheckpoint(
                PendingPlaybackEvent(
                    clientEventId = "evt-1",
                    trackId = "trk-1",
                    playedAtIso = "2026-11-28T00:10:00Z",
                    listenedSec = 90,
                )
            )
            FakeRpcResponse(
                httpStatus = 200,
                body = """
                    {
                      "status": "OK",
                      "results": [
                        {
                          "client_event_id": "evt-1",
                          "status": "inserted",
                          "accepted_duration_sec": 60,
                          "play_counted": true,
                          "delta_sec": 60,
                          "delta_plays": 1
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        pipeline = PlaybackEventPipeline(storage, client) { 1000L }

        pipeline.recordCheckpoint(
            PendingPlaybackEvent(
                clientEventId = "evt-1",
                trackId = "trk-1",
                playedAtIso = "2026-11-28T00:10:00Z",
                listenedSec = 30,
            )
        )
        pipeline.recordCheckpoint(
            PendingPlaybackEvent(
                clientEventId = "evt-1",
                trackId = "trk-1",
                playedAtIso = "2026-11-28T00:10:00Z",
                listenedSec = 60,
            )
        )
        assertEquals(60, storage.getById("evt-1")?.listenedSec)

        val outcome = pipeline.flushPending("dev-1")
        assertEquals(PlaybackFlushOutcome.Flushed(acked = 0, requeued = 1, rejected = 0), outcome)
        val after = storage.getById("evt-1")!!
        assertEquals(PlaybackQueueState.PENDING, after.state)
        assertEquals(60, after.ackedSec)
        assertEquals(90, after.listenedSec)
    }

    @Test
    fun searchClearAllDropsPendingOpsAndReconcilesClearEpoch() : Unit = runBlocking {
        val storage = InMemorySearchHistoryStorage()
        val calledRpcs = mutableListOf<String>()
        val client = fakeClient { rpcName, _ ->
            calledRpcs += rpcName
            when (rpcName) {
                "clear_search_history" -> FakeRpcResponse(
                    httpStatus = 200,
                    body = """{"status":"OK","search_cleared_at":"2026-11-28T00:20:00Z","clear_epoch":"3","purged_rows":2}""",
                )
                "fetch_search_history" -> FakeRpcResponse(
                    httpStatus = 200,
                    body = """
                        {
                          "status": "OK",
                          "server_time": "2026-11-28T00:21:00Z",
                          "search_cleared_at": "2026-11-28T00:20:00Z",
                          "clear_epoch": "3",
                          "full_reset_required": true,
                          "active_items": [
                            {
                              "query_norm": "radiohead",
                              "query": "Radiohead",
                              "result_type": "artist",
                              "searched_at": "2026-11-28T00:20:30Z",
                              "updated_at": "2026-11-28T00:20:30Z"
                            }
                          ],
                          "tombstones": []
                        }
                    """.trimIndent(),
                )
                else -> error("Unexpected RPC $rpcName")
            }
        }
        val coordinator = SearchHistoryCoordinator(storage, client)

        coordinator.onSearchSubmitted("  Daft   Punk  ", "artist", "2026-11-28T00:18:00Z")
        assertEquals("daft punk", storage.getCachedItems().single().queryNorm)

        coordinator.onClearAll("2026-11-28T00:20:00Z")
        assertTrue(storage.getCachedItems().isEmpty())
        assertTrue(storage.getQueuedOps().single() is SearchQueueOp.ClearAll)

        assertTrue(coordinator.sync())
        assertEquals(listOf("clear_search_history", "fetch_search_history"), calledRpcs)
        assertEquals(3L, storage.getWatermark().clearEpoch)
        assertEquals("radiohead", storage.getCachedItems().single().queryNorm)
    }

    @Test
    fun wrappedCoordinatorSkipsDuplicateChecksumUploads() : Unit = runBlocking {
        var calls = 0
        val client = fakeClient { rpcName, _ ->
            assertEquals("upsert_wrapped_snapshot", rpcName)
            calls++
            FakeRpcResponse(
                httpStatus = 200,
                body = """{"status":"OK","applied":true,"period_key":"2026"}""",
            )
        }
        val coordinator = WrappedSyncCoordinator(client)
        val summary = buildJsonObject { put("unique_tracks", 42) }

        assertTrue(coordinator.uploadIfChanged("2026", 1, 100, 3600L, "sha256:same", false, summary))
        assertTrue(coordinator.uploadIfChanged("2026", 1, 100, 3600L, "sha256:same", false, summary))
        assertEquals(1, calls)

        assertFalse(calls > 1)
    }

    @Test
    fun playbackPipelineIsolatesPoisonEventOnBatchTerminalError() : Unit = runBlocking {
        val storage = InMemoryPlaybackEventStorage()
        storage.upsert(PendingPlaybackEvent("ev-good", "trk_good", "2026-11-28T00:00:00Z", 60))
        storage.upsert(PendingPlaybackEvent("ev-poison", "trk_poison", "2026-11-28T00:01:00Z", 45))

        var calls = 0
        val client = fakeClient { rpcName, bodyJson ->
            assertEquals("ingest_playback_events", rpcName)
            calls++
            when {
                bodyJson.contains("ev-good") && bodyJson.contains("ev-poison") ->
                    FakeRpcResponse(httpStatus = 400, body = """{"message":"bad batch"}""")
                bodyJson.contains("ev-poison") ->
                    FakeRpcResponse(httpStatus = 400, body = """{"message":"bad item"}""")
                else ->
                    FakeRpcResponse(
                        httpStatus = 200,
                        body = """{"status":"OK","results":[{"client_event_id":"ev-good","status":"inserted","accepted_duration_sec":60}]}""",
                    )
            }
        }
        val pipeline = PlaybackEventPipeline(storage, client)
        val outcome = pipeline.flushPending("dev1", nowMs = 10_000L)

        assertTrue(outcome is PlaybackFlushOutcome.Flushed)
        assertEquals(1, outcome.acked)
        assertEquals(1, outcome.rejected)
        assertEquals(3, calls)
        assertEquals(PlaybackQueueState.ACKED, storage.getById("ev-good")?.state)
        assertEquals(null, storage.getById("ev-poison"))
    }
}
