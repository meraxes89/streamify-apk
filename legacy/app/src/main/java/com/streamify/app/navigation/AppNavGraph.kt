package com.streamify.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.ui.screens.*
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

@Composable
fun AppNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    dominantColor: androidx.compose.ui.graphics.Color = com.streamify.app.ui.theme.StreamifyColors.BgBase
) {
    val jamViewModel: JamViewModel = viewModel()
    val communityViewModel: CommunityViewModel = viewModel()

    // Route transitions land in the admin terminal (SLog).
    androidx.compose.runtime.DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, dest, args ->
            com.streamify.app.util.SLog.i("NAV", "→ ${dest.route} args=${args?.toString() ?: "{}"}")
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(400))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 2 },
                animationSpec = tween(400)
            ) + fadeOut(animationSpec = tween(400))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 2 },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(400))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(400)
            ) + fadeOut(animationSpec = tween(400))
        }
    ) {
        composable("home") {
            HomeScreen(
                playerViewModel = playerViewModel,
                jamViewModel = jamViewModel,
                communityViewModel = communityViewModel,
                dominantColor = dominantColor,
                onTrackClick = { track, _ ->
                    playerViewModel.playSingleTrack(track)
                },
                onSearchClick = {
                    navController.navigate("search")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onNavigateToJam = {
                    navController.navigate("jam")
                },
                onNavigateToCommunity = {
                    navController.navigate("community")
                },
                onNavigateToProfile = {
                    navController.navigate("profile")
                },
                // Gap #32: Home friends rail one-tap actions.
                onListenAlong = { title, artist ->
                    navController.navigate(
                        "search?query=" + android.net.Uri.encode("$title $artist")
                    )
                },
                onJoinJam = { navController.navigate("jam") },
                onBlend = { name, seeds ->
                    navController.navigate(
                        "blend?friendName=" + android.net.Uri.encode(name) +
                            "&seeds=" + android.net.Uri.encode(seeds)
                    )
                },
                onViewProfile = { userId, name, avatar ->
                    navController.navigate(
                        "user_profile/" + android.net.Uri.encode(userId) +
                            "/" + android.net.Uri.encode(name) +
                            "/" + android.net.Uri.encode(avatar)
                    )
                },
                // Phase 3 — "Your Updates" hub (followed-artist releases).
                onOpenUpdatesHub = { navController.navigate("updates_hub") }
            )
        }

        composable(
            route = "search?query={query}",
            arguments = listOf(
                androidx.navigation.navArgument("query") { defaultValue = "" }
            )
        ) { entry ->
            SearchScreen(
                playerViewModel = playerViewModel,
                initialQuery = entry.arguments?.getString("query") ?: "",
                onTrackClick = { track, _ ->
                    playerViewModel.playSingleTrack(track)
                }
            )
        }

        // ── Gap #25: Blend playlist screen (shared taste merge) ────────────
        composable(
            route = "blend?friendName={friendName}&seeds={seeds}",
            arguments = listOf(
                androidx.navigation.navArgument("friendName") { defaultValue = "Friend" },
                androidx.navigation.navArgument("seeds") { defaultValue = "" }
            )
        ) { entry ->
            val friendName = entry.arguments?.getString("friendName") ?: "Friend"
            val friendSeeds = (entry.arguments?.getString("seeds") ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            BlendScreen(
                playerViewModel = playerViewModel,
                friendName = friendName,
                friendSeeds = friendSeeds,
                onBack = { navController.popBackStack() },
                onTrackClick = { track, _ ->
                    playerViewModel.playSingleTrack(track)
                }
            )
        }
        composable("library") {
            LibraryScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { track, _ ->
                    playerViewModel.playSingleTrack(track)
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            "queue",
            enterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            QueueScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { trackId ->
                    val track = playerViewModel.playerState.value.queue.find { it.id == trackId }
                    if (track != null) {
                        playerViewModel.playTrack(track, playerViewModel.playerState.value.queue)
                    }
                },
                onClose = { navController.popBackStack() }
            )
        }
        composable(
            "lyrics",
            enterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            val playerState = playerViewModel.playerState.collectAsState().value
            val context = androidx.compose.ui.platform.LocalContext.current
            var lyricsLines by androidx.compose.runtime.remember(playerState.currentTrack) {
                androidx.compose.runtime.mutableStateOf<List<com.streamify.app.data.models.LyricsLine>>(emptyList())
            }

            androidx.compose.runtime.LaunchedEffect(playerState.currentTrack) {
                val track = playerState.currentTrack
                if (track != null) {
                    // Cache-only load: PlayerViewModel is the single network fetch owner.
                    // When it lands lyrics it updates currentTrack, which re-fires this
                    // effect and hydrates the freshly written file.
                    lyricsLines = com.streamify.app.data.lyrics.LyricsCacheManager.getOrFetchLyrics(context, track, allowNetwork = false)
                } else {
                    lyricsLines = emptyList()
                }
            }

            LyricsScreen(
                track = playerState.currentTrack,
                lyrics = lyricsLines,
                positionFlow = playerViewModel.positionMs,
                dominantColor = dominantColor,
                onSeek = { ms -> playerViewModel.seekTo(ms) },
                onClose = { navController.popBackStack() }
            )
        }
        composable("downloads") {
            DownloadScreen()
        }
        composable("settings") {
            SettingsScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToEq = { navController.navigate("eq") },
                onNavigateToAdmin = { navController.navigate("admin") },
                onNavigateToProfile = { navController.navigate("profile") },
                onNavigateToWrapped = { navController.navigate("wrapped") },
                onNavigateToCommunity = { navController.navigate("community") },
                onNavigateToProfileSelection = { navController.navigate("profile_selection") },
                onNavigateToTerminal = { navController.navigate("admin_terminal") }
            )
        }
        composable("admin_terminal") {
            com.streamify.app.ui.screens.AdminTerminalScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("profile_selection") {
            ProfileSelectionScreen(
                onProfileConfigured = { mode ->
                    navController.navigate("home") {
                        popUpTo("profile_selection") { inclusive = true }
                    }
                }
            )
        }
        composable("jam") {
            JamSessionScreen(
                jamViewModel = jamViewModel,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("community") {
            CommunityHubScreen(
                communityViewModel = communityViewModel,
                onBack = { navController.popBackStack() },
                onPlaylistClick = { playlist ->
                    // Open community playlist
                },
                // Gap #31/#32: friend-feed one-tap actions.
                onListenAlong = { title, artist ->
                    navController.navigate(
                        "search?query=" + android.net.Uri.encode("$title $artist")
                    )
                },
                onJoinJam = { navController.navigate("jam") },
                onViewProfile = { userId, name, avatar ->
                    navController.navigate(
                        "user_profile/" + android.net.Uri.encode(userId) +
                            "/" + android.net.Uri.encode(name) +
                            "/" + android.net.Uri.encode(avatar)
                    )
                },
                onBlend = { name, seeds ->
                    navController.navigate(
                        "blend?friendName=" + android.net.Uri.encode(name) +
                            "&seeds=" + android.net.Uri.encode(seeds)
                    )
                }
            )
        }

        // Gap #31: another listener's public profile (Follow + live count).
        composable("user_profile/{userId}/{displayName}/{avatarUrl}") { backStackEntry ->
            UserProfileScreen(
                onBack = { navController.popBackStack() },
                onNavigateToWrapped = { navController.navigate("wrapped") },
                onNavigateToAdmin = { navController.navigate("admin") },
                onNavigateToSettings = { navController.navigate("settings") },
                profileUserId = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("userId") ?: "", "UTF-8"
                ),
                profileDisplayName = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("displayName") ?: "", "UTF-8"
                ),
                profileAvatarUrl = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("avatarUrl") ?: "", "UTF-8"
                )
            )
        }
        composable("profile") {
            UserProfileScreen(
                onBack = { navController.popBackStack() },
                onNavigateToWrapped = { navController.navigate("wrapped") },
                onNavigateToAdmin = { navController.navigate("admin") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("wrapped") {
            StatsWrappedScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("admin") {
            AdminDashboardScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("eq") {
            EqualizerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("artist/{artistName}") { backStackEntry ->
            val artistName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("artistName") ?: "", "UTF-8")
            val libraryState by com.streamify.app.data.repository.TrackRepository.trackFlow.collectAsState(initial = emptyList())
            ArtistScreen(
                artistName = artistName,
                allTracks = libraryState,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onTrackClick = { track, _ -> playerViewModel.playSingleTrack(track) }
            )
        }
        composable("album/{albumName}") { backStackEntry ->
            val albumName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("albumName") ?: "", "UTF-8")
            val libraryState by com.streamify.app.data.repository.TrackRepository.trackFlow.collectAsState(initial = emptyList())
            AlbumScreen(
                albumName = albumName,
                allTracks = libraryState,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onTrackClick = { track, list -> playerViewModel.playCollection(list, list.indexOf(track).coerceAtLeast(0)) }
            )
        }
        // Phase 3 — "Your Updates" hub: new releases + alerts from followed artists.
        composable("updates_hub") {
            UpdatesHubScreen(
                onBack = { navController.popBackStack() },
                onArtistClick = { artistName ->
                    navController.navigate("artist/" + android.net.Uri.encode(artistName))
                }
            )
        }
    }
}
