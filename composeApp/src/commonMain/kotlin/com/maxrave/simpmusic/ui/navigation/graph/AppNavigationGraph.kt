package com.maxrave.simpmusic.ui.navigation.graph

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.maxrave.simpmusic.ui.navigation.SpatialTransitions
import com.maxrave.simpmusic.ui.navigation.LegacyTransitions
import com.maxrave.simpmusic.ui.navigation.appComposable
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.WrappedDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.MixForYouDestination
import com.maxrave.simpmusic.ui.navigation.destination.player.FullscreenDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.screen.home.HomeScreen
import com.maxrave.simpmusic.ui.screen.home.analytics.AnalyticsScreen
import com.maxrave.simpmusic.ui.screen.home.wrapped.WrappedScreen
import com.maxrave.simpmusic.ui.screen.library.LibraryScreen
import com.maxrave.simpmusic.ui.screen.library.MixForYouScreen
import com.maxrave.simpmusic.ui.screen.other.SearchScreen
import com.maxrave.simpmusic.ui.screen.player.FullscreenPlayer

/**
 * The ONE NavHost of the app.
 *
 * The spatial navigation beta toggles behaviour inside this tree (per-route
 * transition lambdas via [appComposable], hero wiring via the CompositionLocals
 * provided around it) — it never swaps or rewraps the tree itself, so flipping
 * the setting in Settings cannot reset the back stack.
 */
@Composable
@ExperimentalMaterial3Api
@ExperimentalFoundationApi
fun AppNavigationGraph(
    innerPadding: PaddingValues,
    navController: NavHostController,
    startDestination: Any = HomeDestination,
    spatialEnabled: Boolean = false,
    showNowPlayingSheet: () -> Unit = {},
    onScrolling: (onTop: Boolean) -> Unit = {},
) {
    NavHost(
        navController,
        startDestination = startDestination,
        // Defaults for any destination NOT built by appComposable (none today, but
        // the NavHost-level lambdas are also what a nested graph's root inherits):
        // the legacy slide when the beta is off, the shared-axis default on.
        enterTransition = {
            if (spatialEnabled) SpatialTransitions.enter(com.maxrave.simpmusic.ui.navigation.TransitionKind.SharedAxisX) else LegacyTransitions.enter()
        },
        exitTransition = {
            if (spatialEnabled) SpatialTransitions.exit(com.maxrave.simpmusic.ui.navigation.TransitionKind.SharedAxisX) else LegacyTransitions.exit()
        },
        popEnterTransition = {
            if (spatialEnabled) SpatialTransitions.popEnter(com.maxrave.simpmusic.ui.navigation.TransitionKind.SharedAxisX) else LegacyTransitions.popEnter()
        },
        popExitTransition = {
            if (spatialEnabled) SpatialTransitions.popExit(com.maxrave.simpmusic.ui.navigation.TransitionKind.SharedAxisX) else LegacyTransitions.popExit()
        },
    ) {
        // Bottom bar destinations
        appComposable<HomeDestination>(spatialEnabled) {
            HomeScreen(
                onScrolling = onScrolling,
                navController = navController,
            )
        }
        appComposable<SearchDestination>(spatialEnabled) {
            SearchScreen(
                navController = navController,
            )
        }
        appComposable<LibraryDestination>(spatialEnabled) {
            LibraryScreen(
                innerPadding = innerPadding,
                navController = navController,
                onScrolling = onScrolling,
            )
        }
        // Only reachable as a tab while signed in to YouTube
        appComposable<MixForYouDestination>(spatialEnabled) {
            MixForYouScreen(
                innerPadding = innerPadding,
                navController = navController,
                onScrolling = onScrolling,
            )
        }
        // Only reachable as a tab while local tracking is enabled.
        // Follows the app's ambient theme mode (light/dark/custom).
        appComposable<AnalyticsDestination>(spatialEnabled) {
            AnalyticsScreen(
                navController = navController,
                innerPadding = innerPadding,
            )
        }
        // Reached only from the Analytics screen's entry banner, so it inherits that screen's
        // gate on local tracking. ForceDarkContent comes from the ScreenPolicy registry now
        // (appComposable reads it), for the same reason as before: the reel is drawn on its own
        // near-black ground whatever the user's theme is, because it is an event and because
        // every card is also a share image that has to survive leaving the app.
        //
        // Chrome (bar/mini player hidden) is NOT set from here anymore: App.kt derives it
        // reactively from this entry's ScreenPolicy, so a cancelled predictive back gesture
        // cannot desync it.
        appComposable<WrappedDestination>(spatialEnabled) {
            WrappedScreen(
                navController = navController,
            )
        }
        appComposable<FullscreenDestination>(spatialEnabled) {
            FullscreenPlayer(
                navController,
                showNowPlayingSheet = {
                    showNowPlayingSheet.invoke()
                },
            )
        }
        // Home screen graph
        homeScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
            spatialEnabled = spatialEnabled,
        )
        // Library screen graph
        libraryScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
            spatialEnabled = spatialEnabled,
        )
        // List screen graph
        listScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
            spatialEnabled = spatialEnabled,
        )
        // Login screen graph
        loginScreenGraph(
            innerPadding = innerPadding,
            navController = navController,
            spatialEnabled = spatialEnabled,
        )
    }
}
