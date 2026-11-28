package com.maxrave.simpmusic.ui.navigation.graph

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.maxrave.simpmusic.ui.navigation.appComposable
import com.maxrave.simpmusic.ui.navigation.destination.login.DiscordLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LastfmLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.SpotifyLoginDestination
import com.maxrave.simpmusic.ui.screen.login.DiscordLoginScreen
import com.maxrave.simpmusic.ui.screen.login.DiscordOAuthLoginScreen
import com.maxrave.simpmusic.ui.screen.login.LastfmLoginScreen
import com.maxrave.simpmusic.ui.screen.login.LoginScreen
import com.maxrave.simpmusic.ui.screen.login.SpotifyLoginScreen
import org.simpmusic.discord.isDiscordOAuthAvailable

/**
 * The login screens no longer take hide/show chrome callbacks: their
 * [com.maxrave.simpmusic.ui.navigation.ChromePolicy] (bar and mini player
 * hidden) is derived by App.kt from the current back stack entry, so chrome can
 * never desync when a back gesture is cancelled mid-flight.
 */
fun NavGraphBuilder.loginScreenGraph(
    innerPadding: PaddingValues,
    navController: NavController,
    spatialEnabled: Boolean = false,
) {
    appComposable<LoginDestination>(spatialEnabled) {
        LoginScreen(
            innerPadding = innerPadding,
            navController = navController,
        )
    }

    appComposable<SpotifyLoginDestination>(spatialEnabled) {
        SpotifyLoginScreen(
            innerPadding = innerPadding,
            navController = navController,
        )
    }

    appComposable<DiscordLoginDestination>(spatialEnabled) {
        // A Full build carries a Discord application and logs in through Discord's own page; a FOSS
        // build keeps the WebView login whose token kizzy uses.
        if (isDiscordOAuthAvailable()) {
            DiscordOAuthLoginScreen(
                innerPadding = innerPadding,
                navController = navController,
            )
        } else {
            DiscordLoginScreen(
                innerPadding = innerPadding,
                navController = navController,
            )
        }
    }

    appComposable<LastfmLoginDestination>(spatialEnabled) {
        LastfmLoginScreen(
            innerPadding = innerPadding,
            navController = navController,
        )
    }
}
