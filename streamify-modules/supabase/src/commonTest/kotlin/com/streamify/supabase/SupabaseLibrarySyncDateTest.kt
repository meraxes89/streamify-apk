package com.streamify.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabaseLibrarySyncDateTest {

    @Test
    fun parseHttpDateToEpochMsParsesRfc1123Header() {
        // 2026-11-26T01:30:00Z = 1795656600000 ms
        val parsed = SupabaseLibrarySync.parseHttpDateToEpochMs("Thu, 26 Nov 2026 01:30:00 GMT")
        assertEquals(1_795_656_600_000L, parsed)
    }

    @Test
    fun parseHttpDateToEpochMsReturnsNullForMissingOrInvalidHeaders() {
        assertNull(SupabaseLibrarySync.parseHttpDateToEpochMs(null))
        assertNull(SupabaseLibrarySync.parseHttpDateToEpochMs(""))
        assertNull(SupabaseLibrarySync.parseHttpDateToEpochMs("not-a-date"))
        assertNull(SupabaseLibrarySync.parseHttpDateToEpochMs("Thu, 32 Nov 2026 01:30:00 GMT"))
    }
}
