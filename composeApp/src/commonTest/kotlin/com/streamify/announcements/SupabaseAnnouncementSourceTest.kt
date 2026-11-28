package com.streamify.announcements

import com.streamify.supabase.SupabaseConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupabaseAnnouncementSourceTest {

    @Test
    fun testUnauthenticatedSessionSkipsRpcAndReturnsEmpty() = runBlocking {
        var rpcCalled = false
        val source = SupabaseAnnouncementSource(
            dataStoreManager = null,
            sessionProvider = { null to null },
            rpcFetcher = {
                rpcCalled = true
                "[]"
            },
        )
        val result = source.fetch()
        assertTrue(result.isEmpty())
        assertEquals(false, rpcCalled)

        // Anon key alone is also treated as unauthenticated
        val anonSource = SupabaseAnnouncementSource(
            dataStoreManager = null,
            sessionProvider = { SupabaseConfig.ANON_KEY to "some-uid" },
            rpcFetcher = {
                rpcCalled = true
                "[]"
            },
        )
        assertTrue(anonSource.fetch().isEmpty())
        assertEquals(false, rpcCalled)
    }

    @Test
    fun testRpcParsingEnforcesRevokedIdsAndDeepLinkAllowlist() = runBlocking {
        val payload = """
            [
              {
                "id": "valid_1on1",
                "schemaVersion": 1,
                "priority": 200,
                "presentation": "MODAL",
                "content": {
                  "en": {
                    "title": "Your Wrapped is Ready",
                    "body": "Check out your stats."
                  }
                },
                "action": {
                  "type": "com.streamify.announcements.AnnouncementAction.DeepLink",
                  "url": "https://github.com/meraxes89/streamify-apk"
                }
              },
              {
                "id": "revoked_by_signed_manifest",
                "schemaVersion": 1,
                "priority": 300,
                "presentation": "MODAL",
                "content": {
                  "en": {
                    "title": "Revoked",
                    "body": "Should be filtered"
                  }
                }
              },
              {
                "id": "phishing_deeplink",
                "schemaVersion": 1,
                "priority": 400,
                "presentation": "MODAL",
                "content": {
                  "en": {
                    "title": "Untrusted",
                    "body": "Should be blocked by DeepLinkAllowlist"
                  }
                },
                "action": {
                  "type": "com.streamify.announcements.AnnouncementAction.DeepLink",
                  "url": "https://evil.example.com/steal"
                }
              }
            ]
        """.trimIndent()

        val source = SupabaseAnnouncementSource(
            dataStoreManager = null,
            sessionProvider = { "valid-jwt-token" to "user-uuid-1" },
            rpcFetcher = { payload },
        )

        val result = source.fetch(
            revokedIds = setOf("revoked_by_signed_manifest"),
            allowedExtraDomains = emptyList(),
        )

        assertEquals(1, result.size)
        assertEquals("valid_1on1", result[0].id)
    }

    @Test
    fun testRpcFailureFailsSafeToEmpty() = runBlocking {
        val source = SupabaseAnnouncementSource(
            dataStoreManager = null,
            sessionProvider = { "valid-jwt-token" to "user-uuid-1" },
            rpcFetcher = { error("simulated network error") },
        )
        val result = source.fetch()
        assertTrue(result.isEmpty())
    }
}
