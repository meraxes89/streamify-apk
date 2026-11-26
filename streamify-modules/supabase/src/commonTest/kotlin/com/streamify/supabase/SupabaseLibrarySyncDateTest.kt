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

    @Test
    fun decodeCloudUserLikesHandlesResponseWithoutUserId() {
        val raw = """[{"track_id":"dQw4w9WgXcQ","created_at":"2026-11-26T01:30:00Z"}]"""
        val decoded = SupabaseLibrarySync.decodeCloudUserLikes(raw)
        assertEquals(1, decoded.size)
        assertEquals("dQw4w9WgXcQ", decoded.first().track_id)
        assertEquals("2026-11-26T01:30:00Z", decoded.first().created_at)
        assertEquals("", decoded.first().user_id)
    }

    @Test
    fun setSessionPreservesRefreshTokenAcrossImplicitSessionInit() {
        val profile = UserProfile(
            id = "user-123",
            email = "test@example.com",
            displayName = "Test",
        )
        try {
            SupabaseClient.setSession("access-1", profile, "refresh-1")
            assertEquals("access-1", SupabaseClient.getAuthToken())
            assertEquals("refresh-1", SupabaseClient.refreshToken.value)
        } finally {
            SupabaseClient.clearSession()
        }
    }
}
