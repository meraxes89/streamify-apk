package com.streamify.app.data.supabase

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-09 regression tests: admin must be a server-side claim only.
 *
 * Pre-SEC-09, `SupabaseClient.isAdmin` granted the Admin Dashboard to any
 * account whose email or *display name* contained "sireen" — a user who
 * named themselves "SireenRules" (or "Sireen fan") got every admin surface,
 * including role toggling and the moderation RPCs.
 *
 * Hermetic JVM shard discipline: `isAdmin` is a pure function of the
 * in-memory session profile (which, in production, is populated exclusively
 * from the server-verified `profiles.is_admin` value — see
 * `fetchServerIsAdmin`, fail-closed). No device, no network.
 */
class SupabaseAdminBypassTest {

    @After
    fun resetSessionState() {
        SupabaseClient._currentUser.value = null
    }

    @Test
    fun displayNameContainingSireenGrantsZeroAdminSurfaces() {
        // The exact attack from the lead plan: is_admin=false on the server,
        // display name chosen to match the old heuristic.
        SupabaseClient._currentUser.value = UserProfile(
            id = "u-1",
            email = "taylor@example.com",
            displayName = "SireenRules",
            avatarUrl = "",
            isAdmin = false
        )
        assertFalse(SupabaseClient.isAdmin)
    }

    @Test
    fun maintainerEmailAloneGrantsNothingWithoutServerFlag() {
        SupabaseClient._currentUser.value = UserProfile(
            id = "u-2",
            email = "sireenyadav@gmail.com",
            displayName = "Sireen",
            avatarUrl = "",
            isAdmin = false
        )
        assertFalse(SupabaseClient.isAdmin)
    }

    @Test
    fun serverFlagIsTheOnlyGrantPath() {
        SupabaseClient._currentUser.value = UserProfile(
            id = "u-3",
            email = "taylor@example.com",
            displayName = "Taylor",
            avatarUrl = "",
            isAdmin = true
        )
        assertTrue(SupabaseClient.isAdmin)
    }

    @Test
    fun signedOutSessionIsNeverAdmin() {
        SupabaseClient._currentUser.value = null
        assertFalse(SupabaseClient.isAdmin)
    }
}
