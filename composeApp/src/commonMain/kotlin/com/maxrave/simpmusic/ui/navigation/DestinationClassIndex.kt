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
import kotlin.reflect.KClass

/**
 * Route-string → destination-class index for chrome derivation in App.kt.
 *
 * navigation 2.9.2 exposes a route's class only through KType/serialization, which
 * is heavyweight to do per recomposition; this table is built once. The exhaustive
 * test keeps it honest: every destination type in the five graphs must be here.
 */
object DestinationClassIndex {
    private val index: Map<String, KClass<*>> = buildMap {
        put(HomeDestination::class.qualifiedName!!, HomeDestination::class)
        put(SearchDestination::class.qualifiedName!!, SearchDestination::class)
        put(LibraryDestination::class.qualifiedName!!, LibraryDestination::class)
        put(MixForYouDestination::class.qualifiedName!!, MixForYouDestination::class)
        put(AnalyticsDestination::class.qualifiedName!!, AnalyticsDestination::class)
        put(WrappedDestination::class.qualifiedName!!, WrappedDestination::class)
        put(FullscreenDestination::class.qualifiedName!!, FullscreenDestination::class)
        put(SettingsDestination::class.qualifiedName!!, SettingsDestination::class)
        put(NotificationDestination::class.qualifiedName!!, NotificationDestination::class)
        put(CreditDestination::class.qualifiedName!!, CreditDestination::class)
        put(AppLogDestination::class.qualifiedName!!, AppLogDestination::class)
        put(AdminDashboardDestination::class.qualifiedName!!, AdminDashboardDestination::class)
        put(AnnouncementInboxDestination::class.qualifiedName!!, AnnouncementInboxDestination::class)
        put(MoodDestination::class.qualifiedName!!, MoodDestination::class)
        put(ListenTogetherDestination::class.qualifiedName!!, ListenTogetherDestination::class)
        put(ListenTogetherSettingsDestination::class.qualifiedName!!, ListenTogetherSettingsDestination::class)
        put(LibraryDynamicPlaylistDestination::class.qualifiedName!!, LibraryDynamicPlaylistDestination::class)
        put(AlbumDestination::class.qualifiedName!!, AlbumDestination::class)
        put(ArtistDestination::class.qualifiedName!!, ArtistDestination::class)
        put(PlaylistDestination::class.qualifiedName!!, PlaylistDestination::class)
        put(LocalPlaylistDestination::class.qualifiedName!!, LocalPlaylistDestination::class)
        put(PodcastDestination::class.qualifiedName!!, PodcastDestination::class)
        put(BrowseDestination::class.qualifiedName!!, BrowseDestination::class)
        put(MoreAlbumsDestination::class.qualifiedName!!, MoreAlbumsDestination::class)
        put(LoginDestination::class.qualifiedName!!, LoginDestination::class)
        put(SpotifyLoginDestination::class.qualifiedName!!, SpotifyLoginDestination::class)
        put(DiscordLoginDestination::class.qualifiedName!!, DiscordLoginDestination::class)
        put(LastfmLoginDestination::class.qualifiedName!!, LastfmLoginDestination::class)
    }

    /** The class for a route string (`destination.route` is the qualified name for type-safe routes). */
    fun classFor(route: String): KClass<*>? = index[route.substringBefore("?").substringBefore("/")]

    /** Every indexed class — the coverage test asserts this equals the registry's key set. */
    val all: Set<KClass<*>> = index.values.toSet()
}
