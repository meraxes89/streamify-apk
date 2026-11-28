package com.streamify.sync.v5

import com.streamify.sync.v5.planef.DefaultPeerSyncBridge
import com.streamify.sync.v5.planef.PeerLibraryPayload
import com.streamify.sync.v5.planef.PeerLocalStorage
import com.streamify.sync.v5.planef.PeerPlaylistItem
import com.streamify.sync.v5.planef.PeerTransferMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaneFV5Test {

    private class FakePeerLocalStorage : PeerLocalStorage {
        val likes = linkedSetOf<String>()
        val library = linkedSetOf<String>()
        val playlists = linkedMapOf<String, String>()
        val playlistTracks = mutableListOf<Triple<String, String, String>>()
        val appliedSettings = mutableMapOf<String, String>()

        override suspend fun existingLikedIds(): Set<String> = likes.toSet()
        override suspend fun existingLibraryIds(): Set<String> = library.toSet()
        override suspend fun insertLike(trackId: String) { likes += trackId }
        override suspend fun insertLibraryTrack(trackId: String) { library += trackId }
        override suspend fun insertPlaylist(id: String, name: String, description: String?) { playlists[id] = name }
        override suspend fun insertPlaylistTrack(playlistId: String, trackId: String, sortKey: String) {
            playlistTracks += Triple(playlistId, trackId, sortKey)
        }
        override suspend fun applyAllowlistedSettings(settings: Map<String, String>) {
            appliedSettings.putAll(settings)
        }
    }

    @Test
    fun sameAccountHydrationProducesZeroOutboxWrites() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        val guard = createPlatformSyncWriteGuard()
        val lifecycle = DefaultSyncSessionLifecycle(store, guard)
        val local = FakePeerLocalStorage()
        val bridge = DefaultPeerSyncBridge(
            lifecycle = lifecycle,
            writeGuard = guard,
            store = store,
            localStorage = local,
            currentUserIdProvider = { "user-1" },
            localDeviceIdProvider = { "dev-receiver" },
        )

        val report = bridge.importLibraryTransfer(
            PeerLibraryPayload(
                senderUserId = "user-1",
                senderDeviceId = "dev-sender",
                likedTrackIds = listOf("yt:a", "yt:b"),
                playlists = listOf(PeerPlaylistItem("pl-orig", "Mix", null, listOf("yt:a"))),
            )
        )
        assertEquals(PeerTransferMode.SAME_ACCOUNT_HYDRATION, report.mode)
        assertEquals(0, report.outboxMutationsEnqueued)
        assertEquals(0, report.playlistsRemapped)
        assertTrue("pl-orig" in local.playlists)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun crossAccountCloneRemapsPlaylistUuidsAndStripsIdentityKeys() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        val guard = createPlatformSyncWriteGuard()
        val lifecycle = DefaultSyncSessionLifecycle(store, guard)
        val local = FakePeerLocalStorage()
        val bridge = DefaultPeerSyncBridge(
            lifecycle = lifecycle,
            writeGuard = guard,
            store = store,
            localStorage = local,
            currentUserIdProvider = { "user-2" },
            localDeviceIdProvider = { "dev-receiver" },
        )

        bridge.importLoginTransfer(
            authTransfer = { "user-2" },
            allowlistedSettings = mapOf("device_id" to "stolen", "theme_mode" to "DARK"),
        )
        assertFalse("device_id" in local.appliedSettings)
        assertEquals("DARK", local.appliedSettings["theme_mode"])

        val report = bridge.importLibraryTransfer(
            PeerLibraryPayload(
                senderUserId = "user-1",
                senderDeviceId = "dev-sender",
                likedTrackIds = listOf("yt:a"),
                playlists = listOf(PeerPlaylistItem("pl-user1", "Mix", null, listOf("yt:a", "yt:b"))),
            )
        )
        assertEquals(PeerTransferMode.CROSS_ACCOUNT_CLONE, report.mode)
        assertEquals(1, report.playlistsRemapped)
        assertEquals(4, report.outboxMutationsEnqueued) // 1 like + 1 playlist + 2 playlist_tracks
        assertFalse("pl-user1" in local.playlists)
        assertNotEquals("pl-user1", local.playlists.keys.single())
        assertEquals(4, store.pendingCount())
    }
}
