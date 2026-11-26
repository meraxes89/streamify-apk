package com.streamify.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncDeadLetterStoreTest {

    @Test
    fun deadLetterStoreRoundTripsIdsSafely() {
        val ids = setOf("chg_1", "chg_2")
        val encoded = SyncDeadLetterStore.encodeIds(ids)
        assertEquals(ids, SyncDeadLetterStore.decodeIds(encoded))
        assertEquals(emptySet(), SyncDeadLetterStore.decodeIds(null))
        assertEquals(emptySet(), SyncDeadLetterStore.decodeIds("corrupt-json"))
    }

    @Test
    fun terminalClientRejectionClassifies4xxExcept401And429() {
        assertTrue(StreamifySyncEngine.isTerminalClientRejection(Exception("sync_push failed HTTP 400: bad op")))
        assertTrue(StreamifySyncEngine.isTerminalClientRejection(Exception("sync_push failed HTTP 422: unprocessable")))
        assertFalse(StreamifySyncEngine.isTerminalClientRejection(Exception("sync_push failed HTTP 401: unauthorized")))
        assertFalse(StreamifySyncEngine.isTerminalClientRejection(Exception("sync_push failed HTTP 429: rate limit")))
        assertFalse(StreamifySyncEngine.isTerminalClientRejection(Exception("sync_push failed HTTP 503: unavailable")))
        assertFalse(StreamifySyncEngine.isTerminalClientRejection(Exception("network timeout")))
    }

    @Test
    fun normalizeToHlcConvertsPlainEpochMillisAndPreservesPackedHlc() {
        val epochMs = 1_760_000_000_000L
        val normalized = StreamifySyncEngine.normalizeToHlc(epochMs)
        assertEquals(HybridLogicalClock.pack(epochMs, 0), normalized)
        val packed = HybridLogicalClock.pack(epochMs, 42)
        assertEquals(packed, StreamifySyncEngine.normalizeToHlc(packed))
        assertEquals(0L, StreamifySyncEngine.normalizeToHlc(0L))
    }

    @Test
    fun hlcKeyBreaksEqualHlcTiesDeterministicallyByDeviceId() {
        val hlc = HybridLogicalClock.pack(1_760_000_000_000L, 5)
        val devA = HybridLogicalClock.HlcKey(hlc, "dev_a")
        val devB = HybridLogicalClock.HlcKey(hlc, "dev_b")
        assertTrue(devB > devA)
    }
}
