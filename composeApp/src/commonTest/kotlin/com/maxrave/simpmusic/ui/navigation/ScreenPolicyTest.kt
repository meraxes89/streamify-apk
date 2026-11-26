package com.maxrave.simpmusic.ui.navigation

import com.maxrave.simpmusic.ui.navigation.destination.home.AdminDashboardDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AnnouncementInboxDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AppLogDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.CreditDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherSettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.WrappedDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.MixForYouDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.BrowseDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.MoreAlbumsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.DiscordLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LastfmLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.SpotifyLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.player.FullscreenDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * RFC-003 coverage: every destination across the 5 graphs has a policy, the chrome
 * contract holds per destination family, and transition resolution picks the target's
 * axis. A new destination added to a graph without a registry entry fails
 * [everyDestinationHasAPolicy] here first.
 */
class ScreenPolicyTest {
    /** Every *Destination type mounted by the 5 graphs (exhaustive; extend when adding a graph entry). */
    private val allGraphDestinations: List<kotlin.reflect.KClass<*>> = listOf(
        HomeDestination::class, SearchDestination::class, LibraryDestination::class,
        MixForYouDestination::class, AnalyticsDestination::class, WrappedDestination::class,
        FullscreenDestination::class, SettingsDestination::class, NotificationDestination::class,
        CreditDestination::class, AppLogDestination::class, AdminDashboardDestination::class,
        AnnouncementInboxDestination::class, MoodDestination::class, ListenTogetherDestination::class,
        ListenTogetherSettingsDestination::class, LibraryDynamicPlaylistDestination::class,
        AlbumDestination::class, ArtistDestination::class, PlaylistDestination::class,
        LocalPlaylistDestination::class, PodcastDestination::class, BrowseDestination::class,
        MoreAlbumsDestination::class, LoginDestination::class, SpotifyLoginDestination::class,
        DiscordLoginDestination::class, LastfmLoginDestination::class,
    )

    @Test
    fun everyDestinationHasAPolicy() {
        val missing = allGraphDestinations.filter { it !in ScreenPolicyRegistry.registeredDestinations }
        assertEquals(emptyList(), missing, "destinations without a ScreenPolicy: \$missing")
    }

    @Test
    fun registryAndIndexAgree() {
        assertEquals(ScreenPolicyRegistry.registeredDestinations, DestinationClassIndex.all,
            "DestinationClassIndex must index exactly the registry's destinations")
    }

    @Test
    fun tabsCrossfadeWithFullChrome() {
        listOf(HomeDestination::class, SearchDestination::class, LibraryDestination::class,
            MixForYouDestination::class, AnalyticsDestination::class).forEach { k ->
            val p = ScreenPolicyRegistry.policyFor(k)
            assertEquals(TransitionKind.Fade, p.transition, "\$k should crossfade")
            assertTrue(p.chrome.showNavBar, "\$k keeps the bar")
            assertTrue(p.chrome.showMiniPlayer, "\$k keeps the mini player")
            assertFalse(p.chrome.forceDarkContent, "\$k follows the ambient theme")
            assertFalse(p.chrome.immersive, "\$k is not immersive")
        }
    }

    @Test
    fun takeoversAreImmersiveDarkSlides() {
        listOf(WrappedDestination::class, FullscreenDestination::class).forEach { k ->
            val p = ScreenPolicyRegistry.policyFor(k)
            assertEquals(TransitionKind.SlideVertical, p.transition, "\$k slides vertically")
            assertFalse(p.chrome.showNavBar, "\$k hides the bar")
            assertFalse(p.chrome.showMiniPlayer, "\$k hides the mini player")
            assertTrue(p.chrome.forceDarkContent, "\$k forces dark content")
            assertTrue(p.chrome.immersive, "\$k is immersive")
        }
    }

    @Test
    fun loginScreensHideChrome() {
        listOf(LoginDestination::class, SpotifyLoginDestination::class,
            DiscordLoginDestination::class, LastfmLoginDestination::class).forEach { k ->
            val p = ScreenPolicyRegistry.policyFor(k)
            assertFalse(p.chrome.showNavBar, "\$k hides the bar")
            assertFalse(p.chrome.showMiniPlayer, "\$k hides the mini player")
            assertEquals(TransitionKind.SharedAxisZ, p.transition, "\$k opens like a modal")
        }
    }

    @Test
    fun detailScreensDriftOnXWithChrome() {
        listOf(AlbumDestination::class, ArtistDestination::class, PlaylistDestination::class,
            LocalPlaylistDestination::class, PodcastDestination::class, BrowseDestination::class,
            MoreAlbumsDestination::class, LibraryDynamicPlaylistDestination::class).forEach { k ->
            val p = ScreenPolicyRegistry.policyFor(k)
            assertEquals(TransitionKind.SharedAxisX, p.transition, "\$k drifts on X")
            assertTrue(p.chrome.showNavBar, "\$k keeps the bar")
        }
    }

    @Test
    fun unknownDestinationFallsBackToSafeDefault() {
        val p = ScreenPolicyRegistry.policyFor(Any::class)
        assertEquals(TransitionKind.SharedAxisX, p.transition)
        assertTrue(p.chrome.showNavBar)
        assertTrue(p.chrome.showMiniPlayer)
    }

    @Test
    fun transitionResolverPicksTheTargetsAxis() {
        val immersive = ScreenPolicyRegistry.policyFor(WrappedDestination::class)
        val tab = ScreenPolicyRegistry.policyFor(HomeDestination::class)
        assertEquals(TransitionKind.SlideVertical, TransitionResolver.resolve(tab, immersive).kind)
        assertEquals(TransitionKind.Fade, TransitionResolver.resolve(immersive, tab).kind)
    }

    @Test
    fun routeIndexResolvesQualifiedNames() {
        assertEquals(WrappedDestination::class, DestinationClassIndex.classFor("com.maxrave.simpmusic.ui.navigation.destination.home.WrappedDestination"))
        assertEquals(AlbumDestination::class, DestinationClassIndex.classFor("com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination?browseId=MPREb_1"))
        assertEquals(null, DestinationClassIndex.classFor("not.a.Route"))
    }

    @Test
    fun heroKeysAreOriginDisambiguated() {
        val card = SharedKeys.hero(SharedKeys.Origin.ALBUM, "MPREb_1")
        val header = SharedKeys.hero(SharedKeys.Origin.ALBUM, "MPREb_1")
        val elsewhere = SharedKeys.hero(SharedKeys.Origin.PLAYLIST, "MPREb_1")
        assertEquals(card, header, "same origin + id must be the same key")
        assertTrue(card != elsewhere, "same id under another origin must differ — the same item can appear in multiple carousels")
    }

    @Test
    fun heroesGateOnBetaReducedMotionAndTier4() {
        assertFalse(SpatialCapability.heroesAllowed(betaEnabled = false, reduceMotion = false))
        assertFalse(SpatialCapability.heroesAllowed(betaEnabled = true, reduceMotion = true))
        assertFalse(SpatialCapability.heroesAllowed(betaEnabled = true, reduceMotion = false) && SpatialCapability.isSoftwareRasterizer() && getPlatform() == com.maxrave.simpmusic.Platform.Web)
    }
}
