package com.maxrave.simpmusic.ui.navigation.graph

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.maxrave.simpmusic.ui.navigation.appComposable
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.BrowseDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.MoreAlbumsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.screen.library.LocalPlaylistScreen
import com.maxrave.simpmusic.ui.screen.other.AlbumScreen
import com.maxrave.simpmusic.ui.screen.other.ArtistScreen
import com.maxrave.simpmusic.ui.screen.other.BrowseScreen
import com.maxrave.simpmusic.ui.screen.other.MoreAlbumsScreen
import com.maxrave.simpmusic.ui.screen.other.PlaylistScreen
import com.maxrave.simpmusic.ui.screen.other.PodcastScreen

@ExperimentalMaterial3Api
@ExperimentalFoundationApi
fun NavGraphBuilder.listScreenGraph(
    innerPadding: PaddingValues,
    navController: NavController,
    spatialEnabled: Boolean = false,
) {
    appComposable<AlbumDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<AlbumDestination>()
        AlbumScreen(
            browseId = data.browseId,
            navController = navController,
        )
    }
    appComposable<ArtistDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<ArtistDestination>()
        ArtistScreen(
            channelId = data.channelId,
            navController = navController,
        )
    }
    appComposable<LocalPlaylistDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<LocalPlaylistDestination>()
        LocalPlaylistScreen(
            id = data.id,
            navController = navController,
        )
    }
    appComposable<MoreAlbumsDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<MoreAlbumsDestination>()
        MoreAlbumsScreen(
            innerPadding = innerPadding,
            navController = navController,
            type = data.type,
            id = data.id,
        )
    }
    appComposable<BrowseDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<BrowseDestination>()
        BrowseScreen(
            innerPadding = innerPadding,
            navController = navController,
            browseId = data.browseId,
            params = data.params,
            title = data.title,
        )
    }
    appComposable<PlaylistDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<PlaylistDestination>()
        PlaylistScreen(
            playlistId = data.playlistId,
            isYourYouTubePlaylist = data.isYourYouTubePlaylist,
            navController = navController,
        )
    }
    appComposable<PodcastDestination>(spatialEnabled) { entry ->
        val data = entry.toRoute<PodcastDestination>()
        PodcastScreen(
            podcastId = data.podcastId,
            navController = navController,
        )
    }
}
