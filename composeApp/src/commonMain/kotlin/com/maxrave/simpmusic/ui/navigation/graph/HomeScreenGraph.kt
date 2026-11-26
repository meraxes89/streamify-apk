package com.maxrave.simpmusic.ui.navigation.graph

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.maxrave.simpmusic.ui.navigation.appComposable
import com.maxrave.simpmusic.ui.navigation.destination.home.AdminDashboardDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AppLogDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.CreditDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherSettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AnnouncementInboxDestination
import com.maxrave.simpmusic.ui.screen.home.ListenTogetherScreen
import com.maxrave.simpmusic.ui.screen.home.ListenTogetherSettingsScreen
import com.maxrave.simpmusic.ui.screen.home.MoodScreen
import com.maxrave.simpmusic.ui.screen.home.NotificationScreen
import com.maxrave.simpmusic.ui.screen.home.SettingScreen
import com.maxrave.simpmusic.ui.screen.other.AdminDashboardScreen
import com.maxrave.simpmusic.ui.screen.other.AppLogScreen
import com.maxrave.simpmusic.ui.screen.other.CreditScreen

fun NavGraphBuilder.homeScreenGraph(
    innerPadding: PaddingValues,
    navController: NavController,
    spatialEnabled: Boolean = false,
) {
    appComposable<AdminDashboardDestination>(spatialEnabled) {
        AdminDashboardScreen(
            paddingValues = innerPadding,
            navController = navController,
        )
    }
    appComposable<CreditDestination>(spatialEnabled) {
        CreditScreen(
            paddingValues = innerPadding,
            navController = navController,
        )
    }
    appComposable<AnnouncementInboxDestination>(spatialEnabled) {
        com.streamify.announcements.ui.AnnouncementInboxScreen(
            navController = navController
        )
    }
    appComposable<AppLogDestination>(spatialEnabled) {
        AppLogScreen(
            paddingValues = innerPadding,
            navController = navController,
        )
    }
    appComposable<MoodDestination>(spatialEnabled) { entry ->
        val params = entry.toRoute<MoodDestination>().params
        MoodScreen(
            navController = navController,
            params = params,
        )
    }
    appComposable<ListenTogetherDestination>(spatialEnabled) {
        ListenTogetherScreen(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
    appComposable<ListenTogetherSettingsDestination>(spatialEnabled) {
        ListenTogetherSettingsScreen(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
    appComposable<NotificationDestination>(spatialEnabled) {
        NotificationScreen(
            navController = navController,
        )
    }
    appComposable<SettingsDestination>(spatialEnabled) {
        SettingScreen(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
}
