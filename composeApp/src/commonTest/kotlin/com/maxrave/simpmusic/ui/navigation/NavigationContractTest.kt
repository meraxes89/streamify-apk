package com.maxrave.simpmusic.ui.navigation

import com.maxrave.common.LibraryChipType
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.BottomNavScreen
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.player.FullscreenDestination
import com.maxrave.simpmusic.utils.isAppLinkAvailable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationContractTest {

    @Test
    fun appLinkAvailabilityMatchesRouterPaths() {
        assertTrue(isAppLinkAvailable("notification", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("watch", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("playlist", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("channel", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("album", null, localTrackingOn = false, youTubeLoggedIn = false))

        assertFalse(isAppLinkAvailable("analytics", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("analytics", null, localTrackingOn = true, youTubeLoggedIn = false))

        assertFalse(isAppLinkAvailable("taste", null, localTrackingOn = false, youTubeLoggedIn = false))
        assertTrue(isAppLinkAvailable("taste", null, localTrackingOn = true, youTubeLoggedIn = false))

        assertFalse(
            isAppLinkAvailable(
                "library",
                LibraryChipType.WRAPPED,
                localTrackingOn = false,
                youTubeLoggedIn = false,
            ),
        )
        assertTrue(
            isAppLinkAvailable(
                "library",
                LibraryChipType.WRAPPED,
                localTrackingOn = true,
                youTubeLoggedIn = false,
            ),
        )

        assertFalse(
            isAppLinkAvailable(
                "library",
                LibraryChipType.YOUTUBE_MUSIC_PLAYLIST,
                localTrackingOn = false,
                youTubeLoggedIn = false,
            ),
        )
        assertTrue(
            isAppLinkAvailable(
                "library",
                LibraryChipType.YOUTUBE_MUSIC_PLAYLIST,
                localTrackingOn = false,
                youTubeLoggedIn = true,
            ),
        )

        assertEquals(
            getPlatform() == Platform.Android,
            isAppLinkAvailable("recognize", null, localTrackingOn = false, youTubeLoggedIn = false),
        )

        assertFalse(isAppLinkAvailable("nonexistent", null, localTrackingOn = true, youTubeLoggedIn = true))
    }

    @Test
    fun bottomNavScreensHaveUniqueDestinations() {
        val screens = listOf(
            BottomNavScreen.Home,
            BottomNavScreen.MixForYou,
            BottomNavScreen.Analytics,
            BottomNavScreen.Library,
            BottomNavScreen.Search,
        )
        val destinations = screens.map { it.destination }
        assertEquals(destinations.size, destinations.distinct().size)
    }

    @Test
    fun typedDestinationsSerializeAndDeserializeCleanly() {
        val playlist = PlaylistDestination(playlistId = "VLPL123")
        val decodedPlaylist = Json.decodeFromString<PlaylistDestination>(Json.encodeToString(playlist))
        assertEquals(playlist, decodedPlaylist)

        val album = AlbumDestination(browseId = "MPREb_123")
        val decodedAlbum = Json.decodeFromString<AlbumDestination>(Json.encodeToString(album))
        assertEquals(album, decodedAlbum)

        val artist = ArtistDestination(channelId = "UC123456")
        val decodedArtist = Json.decodeFromString<ArtistDestination>(Json.encodeToString(artist))
        assertEquals(artist, decodedArtist)

        val fullscreen = FullscreenDestination(videoId = "dQw4w9WgXcQ", isVideo = true)
        val decodedFullscreen = Json.decodeFromString<FullscreenDestination>(Json.encodeToString(fullscreen))
        assertEquals(fullscreen, decodedFullscreen)

        assertEquals(HomeDestination, Json.decodeFromString<HomeDestination>(Json.encodeToString(HomeDestination)))
        assertEquals(LibraryDestination, Json.decodeFromString<LibraryDestination>(Json.encodeToString(LibraryDestination)))
        assertEquals(NotificationDestination, Json.decodeFromString<NotificationDestination>(Json.encodeToString(NotificationDestination)))
    }
}
