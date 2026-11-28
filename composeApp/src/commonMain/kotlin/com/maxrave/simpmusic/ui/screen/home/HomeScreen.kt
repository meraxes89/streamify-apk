package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.eygraber.uri.Uri
import com.kmpalette.loader.rememberNetworkLoader
import com.kmpalette.rememberDominantColorState
import com.maxrave.common.CHART_SUPPORTED_COUNTRY
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.intent.GenericIntent
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.simpmusic.utils.isQuickPicksShelf
import com.maxrave.simpmusic.utils.pinQuickPicksToTop
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.data.RemoteGreetingManager
import com.maxrave.simpmusic.expect.openUrl
import com.maxrave.simpmusic.expect.ui.DesktopMiniPlayerClearance
import com.maxrave.simpmusic.expect.ui.HorizontalScrollBar
import com.maxrave.simpmusic.expect.ui.VerticalScrollBar
import com.kyant.backdrop.highlight.Highlight
import com.maxrave.simpmusic.expect.ui.PlatformBackdrop
import com.maxrave.simpmusic.expect.ui.layerBackdrop
import com.maxrave.simpmusic.expect.ui.rememberBackdrop
import com.maxrave.simpmusic.extension.angledGradientBackground
import com.maxrave.simpmusic.extension.artworkScrimBrush
import com.maxrave.simpmusic.extension.getScreenSizeInfo
import com.maxrave.simpmusic.extension.isScrollingUp
import com.maxrave.simpmusic.extension.mouseDragScroll
import com.maxrave.simpmusic.extension.rgbFactor
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.CenterLoadingBox
import com.maxrave.simpmusic.ui.component.Chip
import com.maxrave.simpmusic.ui.component.CreateActions
import com.maxrave.simpmusic.ui.component.DropdownButton
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.ExpandableChip
import com.maxrave.simpmusic.ui.component.HomeDiscoverSection
import com.maxrave.simpmusic.ui.component.HomeItem
import com.maxrave.simpmusic.ui.component.HomeItemContentPlaylist
import com.maxrave.simpmusic.ui.component.HomeShimmer
import com.maxrave.simpmusic.ui.component.ItemArtistChart
import com.maxrave.simpmusic.ui.component.LocalAppDialogOpen
import com.maxrave.simpmusic.ui.component.LocalNowPlayingOpen
import com.maxrave.simpmusic.ui.component.SireenBrandingBadge
import com.maxrave.simpmusic.ui.component.liquidGlass
import com.maxrave.simpmusic.ui.component.LocalUserStarted
import com.maxrave.simpmusic.ui.component.MoodMomentAndGenreHomeItem
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.OfflineErrorState
import com.maxrave.simpmusic.ui.component.PromoBanner
import com.maxrave.simpmusic.ui.component.PromoBannerData
import com.maxrave.simpmusic.ui.component.QuickPicksItem
import com.maxrave.simpmusic.ui.component.ReviewDialog
import com.maxrave.simpmusic.ui.component.SectionHeaderButton
import com.maxrave.simpmusic.ui.component.ShareSavedLyricsDialog
import com.maxrave.simpmusic.ui.component.homeCardAspectRatio
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.Notifications
import com.maxrave.simpmusic.ui.icon.Person
import com.maxrave.simpmusic.ui.icon.Sensors
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.screen.library.LibraryDynamicPlaylistType
import com.maxrave.simpmusic.ui.theme.ArtworkShape
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.LocalLiquidGlassEnabled
import com.maxrave.simpmusic.ui.theme.desktopPanelDark
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.utils.VersionManager
import com.maxrave.simpmusic.utils.eligiblePromos
import com.maxrave.simpmusic.utils.osRequirementOf
import com.maxrave.simpmusic.viewModel.HomeViewModel
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_COMMUTE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_ENERGIZE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_FEEL_GOOD
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_FOCUS
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_PARTY
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_RELAX
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_ROMANCE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_SAD
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_SLEEP
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_WORKOUT
import com.maxrave.simpmusic.viewModel.ListState
import com.maxrave.simpmusic.viewModel.SharedViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.all
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.chart
import simpmusic.composeapp.generated.resources.commute
import simpmusic.composeapp.generated.resources.energize
import simpmusic.composeapp.generated.resources.feel_good
import simpmusic.composeapp.generated.resources.focus
import simpmusic.composeapp.generated.resources.guest
import simpmusic.composeapp.generated.resources.let_s_pick_a_playlist_for_you
import simpmusic.composeapp.generated.resources.let_s_start_with_a_radio
import simpmusic.composeapp.generated.resources.log_in_to_YouTube
import simpmusic.composeapp.generated.resources.mood
import simpmusic.composeapp.generated.resources.notification
import simpmusic.composeapp.generated.resources.party
import simpmusic.composeapp.generated.resources.play_all
import simpmusic.composeapp.generated.resources.quick_picks
import simpmusic.composeapp.generated.resources.relax
import simpmusic.composeapp.generated.resources.romance
import simpmusic.composeapp.generated.resources.sad
import simpmusic.composeapp.generated.resources.sleep
import simpmusic.composeapp.generated.resources.top_artists
import simpmusic.composeapp.generated.resources.warning
import simpmusic.composeapp.generated.resources.what_is_best_choice_today
import simpmusic.composeapp.generated.resources.workout

// Launch banner timing: how long Home's own launch dialogs get to settle, and how long the screen must
// stay free of other dialogs before the banner draws.
private const val PROMO_SETTLE_MS = 1_000L
private const val PROMO_CLEAR_MS = 400L

// The height the first item lost with the old app bar (64dp + the 50dp chip row, now one 56dp row) and
// the Welcome back row (81dp), which Home's top glow still has to cover.
private val HomeGlowOverflow = 140.dp

// The moods behind the Mood chip, each with the Home params it loads.
private val homeMoods =
    listOf(
        Res.string.relax to HOME_PARAMS_RELAX,
        Res.string.sleep to HOME_PARAMS_SLEEP,
        Res.string.energize to HOME_PARAMS_ENERGIZE,
        Res.string.sad to HOME_PARAMS_SAD,
        Res.string.romance to HOME_PARAMS_ROMANCE,
        Res.string.feel_good to HOME_PARAMS_FEEL_GOOD,
        Res.string.workout to HOME_PARAMS_WORKOUT,
        Res.string.party to HOME_PARAMS_PARTY,
        Res.string.commute to HOME_PARAMS_COMMUTE,
        Res.string.focus to HOME_PARAMS_FOCUS,
    )

@OptIn(ExperimentalMaterial3Api::class)
@ExperimentalFoundationApi
@Composable
fun HomeScreen(
    onScrolling: (onTop: Boolean) -> Unit = {},
    viewModel: HomeViewModel =
        koinViewModel(),
    sharedViewModel: SharedViewModel =
        koinInject(),
    navController: NavController,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val isScrollingUp = scrollState.isScrollingUp()
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val homeData by viewModel.homeItemList.collectAsStateWithLifecycle()
    val quickPicksTitle = stringResource(Res.string.quick_picks)
    // Any shelf YouTube heads with the listener's own avatar over its title (Listen again is one of
    // several). Home's header already shows the avatar, so those shelves drop it and keep their name
    // and title. Matched on the name rather than on any shelf title, the way the old Welcome back row did.
    // Quick picks is pinned to index 0 so that it does not swap places or drop below large album
    // carousels on refresh.
    val homeShelves =
        remember(homeData, accountInfo, quickPicksTitle) {
            val accountName = accountInfo?.first
            val mapped =
                if (accountName.isNullOrBlank()) {
                    homeData
                } else {
                    homeData.map {
                        if (it.subtitle.equals(accountName, ignoreCase = true)) it.copy(thumbnail = null) else it
                    }
                }
            mapped.pinQuickPicksToTop(quickPicksTitle)
        }
    val newRelease by viewModel.newRelease.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val moodMomentAndGenre by viewModel.exploreMoodItem.collectAsStateWithLifecycle()
    val chartLoading by viewModel.loadingChart.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val regionChart by viewModel.regionCodeChart.collectAsStateWithLifecycle()
    val reloadDestination by sharedViewModel.reloadDestination.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val chipRowState = rememberScrollState()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val homeListState by viewModel.homeListState.collectAsStateWithLifecycle()
    val continuation by viewModel.continuation.collectAsStateWithLifecycle()

    val openAppTime by sharedViewModel.openAppTime.collectAsStateWithLifecycle()
    val shareLyricsPermissions by sharedViewModel.shareSavedLyrics.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.showSnackBarErrorState.collect { errorMsg ->
            if (errorMsg.isNotBlank()) {
                viewModel.makeToast(errorMsg)
            }
        }
    }

    // Launch banner inputs. App.kt's own dialogs, its Now Playing sheet and whether the user has
    // started using the app come down from App.kt; Home's dialogs are read where the banner is drawn.
    val appDialogOpen = LocalAppDialogOpen.current
    val nowPlayingOpen = LocalNowPlayingOpen.current
    val userStarted by rememberUpdatedState(LocalUserStarted.current)
    // Home's own lifecycle: RESUMED only while it is the screen in front (not mid-transition to another
    // tab, not behind a system prompt). On Desktop STARTED means its window is showing.
    val homeLifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // The language part of the BCP 47 tag: Android's Locale.language still reports the legacy "in"
    // for Indonesian and "iw" for Hebrew, while the config is keyed "id" and "he".
    val language = Locale.current.toLanguageTag().substringBefore('-')

    val backgroundColor = MaterialTheme.colorScheme.background
    val isLightTheme = backgroundColor.luminance() > 0.5f
    // What is ACTUALLY painted behind this screen. The desktop shell wraps content in a rounded
    // panel (App.kt: surfaceContainer on light, desktopPanelDark on dark) — deliberately not
    // colorScheme.background — so a gradient tail aimed at colorScheme.background ends on the
    // wrong colour and draws a seam where the first item stops. Same light check as App.kt's
    // isLightScheme.
    val pageBackground =
        if (getPlatform() == Platform.Desktop) {
            if (isLightTheme) MaterialTheme.colorScheme.surfaceContainer else desktopPanelDark
        } else {
            backgroundColor
        }
    var topHeaderColor by remember {
        mutableStateOf(backgroundColor)
    }
    val animatedColor by animateColorAsState(topHeaderColor, tween(500))
    val mainHomeThumbnail by viewModel.mainHomeThumbnail.collectAsStateWithLifecycle()
    // YouTube's Home backdrop, drawn over the tint taken from the first card's artwork. A response
    // that carries none keeps the tint alone, and a failed load drops the image and its scrim,
    // leaving the tint as it was.
    val headerImage by viewModel.homeBackground.collectAsStateWithLifecycle()
    var headerImageFailed by remember(headerImage) { mutableStateOf(false) }
    val httpClient = remember { HttpClient(CIO) }
    val networkLoader = rememberNetworkLoader(httpClient)
    val dominantColorState =
        rememberDominantColorState(
            defaultColor = backgroundColor,
            defaultOnColor = backgroundColor,
            loader = networkLoader,
        )

    LaunchedEffect(mainHomeThumbnail) {
        mainHomeThumbnail?.let {
            dominantColorState.updateFrom(Url(it))
        }
    }

    LaunchedEffect(dominantColorState, isLightTheme) {
        snapshotFlow { dominantColorState.color }.collect {
            // Light theme: pull the artwork color toward white for a soft pastel header;
            // dark theme keeps the original darkened tone.
            topHeaderColor = if (isLightTheme) lerp(it, Color.White, 0.85f) else it.rgbFactor(0.3f)
        }
    }

    var showReviewDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRequestShareLyricsPermissions by rememberSaveable {
        mutableStateOf(false)
    }
    val announcementEngine: com.streamify.announcements.AnnouncementEngine = koinInject()
    val activeAnnouncement by announcementEngine.activeAnnouncement.collectAsStateWithLifecycle()
    val currentSupabaseUser by com.streamify.supabase.SupabaseClient.currentUser.collectAsStateWithLifecycle()
    // Decided once per launch (saveable, so a rotation does not count as a new launch).
    var promoLaunchDecided by rememberSaveable {
        mutableStateOf(false)
    }
    var promoBanner by remember {
        mutableStateOf<PromoBannerData?>(null)
    }
    // The banner that has gone a moment with nothing keeping it off screen; see where it is drawn.
    var promoClearFor by remember {
        mutableStateOf<PromoBannerData?>(null)
    }

    var topAppBarHeightPx by rememberSaveable {
        mutableIntStateOf(0)
    }

    var moodExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    // The floating chip row refracts the page scrolling under it, so the page is the glass source and
    // the row its sibling (nesting the row inside the source is the RuntimeShader feedback loop).
    val chipBackdrop = rememberBackdrop(MaterialTheme.colorScheme.background)

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.firstVisibleItemIndex }
            .collect {
                if (it <= 1) {
                    onScrolling.invoke(true)
                } else {
                    onScrolling.invoke(isScrollingUp.value)
                }
            }
    }

    val onRefresh: () -> Unit = {
        isRefreshing = true
        viewModel.getHomeItemList(params)
        Logger.w("HomeScreen", "onRefresh")
    }
    LaunchedEffect(key1 = reloadDestination) {
        if (reloadDestination == HomeDestination::class) {
            if (scrollState.firstVisibleItemIndex > 1) {
                Logger.w("HomeScreen", "scrollState.firstVisibleItemIndex: ${scrollState.firstVisibleItemIndex}")
                scrollState.animateScrollToItem(0)
                sharedViewModel.reloadDestinationDone()
            } else {
                Logger.w("HomeScreen", "scrollState.firstVisibleItemIndex: ${scrollState.firstVisibleItemIndex}")
                onRefresh.invoke()
            }
        }
    }
    LaunchedEffect(key1 = loading) {
        if (!loading) {
            isRefreshing = false
            sharedViewModel.reloadDestinationDone()
            coroutineScope.launch {
                pullToRefreshState.animateToHidden()
            }
        }
    }
    LaunchedEffect(openAppTime, shareLyricsPermissions) {
        Logger.w("HomeScreen", "openAppTime: $openAppTime, shareLyricsPermissions: $shareLyricsPermissions")
        if (openAppTime >= 10 && openAppTime % 10 == 0 && openAppTime <= 50) {
            showReviewDialog = true
        } else if ((openAppTime == 1 || openAppTime % 15 == 0) && openAppTime <= 60 && !shareLyricsPermissions) {
            showRequestShareLyricsPermissions = true
        } else {
            showReviewDialog = false
            showRequestShareLyricsPermissions = false
        }
    }


    val otherDialogOpen =
        appDialogOpen || showReviewDialog || showRequestShareLyricsPermissions || activeAnnouncement != null

    LaunchedEffect(openAppTime, otherDialogOpen) {
        val platformName = when (getPlatform()) {
            Platform.Desktop -> "desktop"
            else -> "android"
        }
        val uiContext = com.streamify.announcements.UiContext(
            isPlaying = sharedViewModel.controllerState.value.isPlaying,
            isModalActive = otherDialogOpen,
            isOnboarding = false
        )
        val profileContext = com.streamify.announcements.UserProfileContext(
            userId = currentSupabaseUser?.id?.takeIf { it.isNotBlank() },
            displayName = currentSupabaseUser?.displayName?.takeIf { it.isNotBlank() },
            isAdmin = currentSupabaseUser?.isAdmin == true,
            totalPlays = currentSupabaseUser?.totalPlays ?: 0,
            listeningSeconds = currentSupabaseUser?.listeningSeconds ?: 0L,
            favoriteGenre = currentSupabaseUser?.favoriteGenre,
            topTrack = currentSupabaseUser?.topTrack,
            createdAtEpochMs = currentSupabaseUser?.createdAt?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { kotlin.time.Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
            },
            lastActiveAtEpochMs = currentSupabaseUser?.lastActiveAt?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { kotlin.time.Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
            },
            isAuthenticated = com.streamify.supabase.SupabaseClient.hasValidUserSession(),
        )
        announcementEngine.evaluateCandidates(
            currentVersion = VersionManager.getVersionName(),
            currentPlatform = platformName,
            currentLocale = language,
            uiContext = uiContext,
            profile = profileContext,
        )
    }

    val shouldStartPaginate =
        remember {
            derivedStateOf {
                homeListState != ListState.PAGINATION_EXHAUST &&
                    (
                        scrollState.layoutInfo.visibleItemsInfo
                            .lastOrNull()
                            ?.index ?: -9
                    ) >= (scrollState.layoutInfo.totalItemsCount - 1)
            }
        }

    LaunchedEffect(key1 = shouldStartPaginate.value) {
        Logger.d("HomeScreen", "shouldStartPaginate: ${shouldStartPaginate.value}")
        Logger.d("HomeScreen", "homeListState: $homeListState")
        Logger.d("HomeScreen", "Continuation: $continuation")
        if (shouldStartPaginate.value && homeListState == ListState.IDLE) {
            viewModel.getContinueHomeItem(
                continuation,
            )
        }
    }

//    if (shouldShowGetDataSyncIdBottomSheet) {
//        GetDataSyncIdBottomSheet(
//            cookie = youTubeCookie,
//            onDismissRequest = {
//                shouldShowGetDataSyncIdBottomSheet = false
//            },
//        )
//    }

    if (!appDialogOpen) {
        if (showReviewDialog) {
            ReviewDialog(
                onDismissRequest = {
                    sharedViewModel.onDoneReview(
                        isDismissOnly = true,
                    )
                    showReviewDialog = false
                },
                onDoneReview = {
                    sharedViewModel.onDoneReview(
                        isDismissOnly = false,
                    )
                    showReviewDialog = false
                },
            )
        } else if (showRequestShareLyricsPermissions) {
            ShareSavedLyricsDialog(
                onDismissRequest = {
                    showRequestShareLyricsPermissions = false
                },
                onConfirm = { contributor ->
                    sharedViewModel.onDoneRequestingShareLyrics(
                        contributor,
                    )
                },
            )
        } else if (activeAnnouncement != null) {
            // Sovereign In-App Message Engine — Modal Renderer (comm19)
            val announcement = activeAnnouncement!!
            com.streamify.announcements.ui.StreamifyAnnouncementModal(
                announcement = announcement,
                language = language,
                onDismiss = { snooze ->
                    announcementEngine.onDismiss(announcement, snooze = snooze)
                },
                onAction = {
                    announcementEngine.onAction(announcement)
                }
            )
        }
    }

    // The launch banner, decided once per launch. It waits for Home to settle on its own dialogs (the
    // open count and the lyrics-sharing flag load just after the first frame) and for the update check
    // started at launch, however long that takes, so an update dialog comes first. Then it takes the
    // first banner this version has not shown yet.
    LaunchedEffect(Unit) {
        if (promoLaunchDecided) return@LaunchedEffect
        // Marked at the start, not the end: leaving Home cancels this for good (by then the user is
        // busy elsewhere), and a value written while Home is being left would not be saved.
        promoLaunchDecided = true
        snapshotFlow { openAppTime }.first { it > 0 }
        delay(PROMO_SETTLE_MS)
        sharedViewModel.isCheckingUpdate.first { !it }
        if (userStarted) {
            Logger.i("Promo", "Skipped: the user started before the banner's turn")
            return@LaunchedEffect
        }
        val localTracking = sharedViewModel.getLocalTrackingEnabled().first() == TRUE
        val youTubeLoggedIn = sharedViewModel.getYouTubeLoggedIn().first() == TRUE
        val shown = sharedViewModel.shownPromos()
        val promos = sharedViewModel.cachedPromos().first()
        val os = osRequirementOf(getPlatform().osName())
        val eligible = eligiblePromos(promos, VersionManager.getVersionName(), language, localTracking, youTubeLoggedIn, os)
        val banner =
            eligible
                .firstOrNull { (promo, _) -> promo.id.orEmpty() !in shown }
                ?.let { (promo, url) -> PromoBannerData(id = promo.id.orEmpty(), imageUrl = url, link = promo.link.orEmpty()) }
        when {
            banner == null ->
                Logger.i(
                    "Promo",
                    "Nothing to show: ${eligible.size} of ${promos.size} banners usable here " +
                        "(local tracking ${if (localTracking) "on" else "off"}, " +
                        "YouTube ${if (youTubeLoggedIn) "signed in" else "signed out"}), " +
                        "already shown in ${VersionManager.getVersionName()}: $shown",
                )

            userStarted -> Logger.i("Promo", "Skipped ${banner.id}: the user started before its turn")

            else -> {
                Logger.i("Promo", "Picked ${banner.id}, waiting for its turn")
                promoBanner = banner
            }
        }
    }
    // Everything that keeps the banner off screen: any other dialog (it waits its turn behind them),
    // Now Playing, and Home not being in front. On Desktop that only asks for a showing window: one
    // opened from a terminal or an IDE may start without focus, and the click that focuses it would
    // count as the user starting, so the banner would never get its turn.
    val homeInFront =
        homeLifecycle.isAtLeast(
            if (getPlatform() == Platform.Desktop) Lifecycle.State.STARTED else Lifecycle.State.RESUMED,
        )
    val promoBlocked = otherDialogOpen || nowPlayingOpen || !homeInFront
    // It draws only once the banner has gone a moment unblocked since it was decided. The wait belongs
    // to that banner, so nothing left over from before it arrived can let it through early: it never
    // flashes in the gap between two dialogs, and the update dialog, raised a frame after its check
    // ends, still gets there first.
    LaunchedEffect(promoBlocked, promoBanner) {
        val banner = promoBanner
        promoClearFor = null
        if (!promoBlocked && banner != null) {
            delay(PROMO_CLEAR_MS)
            promoClearFor = banner
        }
    }
    // Whatever the user starts before the banner's turn puts it off to a later launch, uncounted.
    LaunchedEffect(userStarted) {
        val pending = promoBanner
        if (userStarted && pending != null && promoClearFor == null) {
            Logger.i("Promo", "Put off ${pending.id}: the user started before its turn")
            promoBanner = null
        }
    }
    promoBanner?.takeIf { it == promoClearFor && !promoBlocked }?.let { banner ->
        LaunchedEffect(banner.id) { Logger.i("Promo", "On screen: ${banner.id}") }
        PromoBanner(
            banner = banner,
            onOpen = {
                Logger.i("Promo", "Opened ${banner.id}: ${banner.link}")
                promoBanner = null
                sharedViewModel.markPromoShown(banner.id)
                val link = banner.link
                when {
                    // The same router as links from the web and the widgets.
                    link.startsWith("simpmusic://") -> {
                        runCatching { Uri.parse(link) }.getOrNull()?.let {
                            sharedViewModel.setIntent(GenericIntent(data = it))
                        }
                    }

                    // openUrl rather than LocalUriHandler, which throws where no browser answers.
                    link.startsWith("https://", ignoreCase = true) -> {
                        runCatching { openUrl(link) }
                    }
                    // Anything else is a mistake in the config: ignoring it beats crashing on a tap.
                }
            },
            onClose = {
                Logger.i("Promo", "Closed ${banner.id}")
                promoBanner = null
                sharedViewModel.markPromoShown(banner.id)
            },
            // No picture, no banner: closed without counting, so a later launch tries again.
            onFailed = {
                Logger.w("Promo", "Picture failed to load for ${banner.id}; closed without counting it")
                promoBanner = null
            },
        )
    }

    Box {
        PullToRefreshBox(
            modifier =
                Modifier
                    .then(if (LocalLiquidGlassEnabled.current) Modifier.layerBackdrop(chipBackdrop) else Modifier),
            state = pullToRefreshState,
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top =
                                    with(LocalDensity.current) {
                                        topAppBarHeightPx.toDp()
                                    },
                            ),
                    containerColor = PullToRefreshDefaults.indicatorContainerColor,
                    color = PullToRefreshDefaults.indicatorColor,
                    maxDistance = PullToRefreshDefaults.PositionalThreshold,
                )
            },
        ) {
            Crossfade(
                targetState = loading,
                animationSpec = tween(350),
                label = "Home Shimmer",
            ) { loading ->
                if (!loading) {
                    if (homeData.isEmpty()) {
                        OfflineErrorState(
                            onRetry = onRefresh,
                            onOpenDownloaded = {
                                navController.navigate(
                                    LibraryDynamicPlaylistDestination(
                                        type = LibraryDynamicPlaylistType.Downloaded.toStringParams(),
                                    ),
                                )
                            },
                        )
                        return@Crossfade
                    }
                    LazyColumn(
                        state = scrollState,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(homeShelves, key = { _, item ->
                            "shelf_${item.title}_${item.channelId ?: item.moreEndpoint?.browseId ?: item.hashCode()}"
                        }) { index, item ->
                            Box {
                                if (index == 0) {
                                    val glowGradient = remember(animatedColor, pageBackground) {
                                        listOf(animatedColor, pageBackground)
                                    }
                                    val headerOverlayColors = remember(pageBackground) {
                                        listOf(pageBackground.copy(alpha = 0.6f), pageBackground)
                                    }
                                    val scrimBrush = remember(pageBackground) { artworkScrimBrush(pageBackground) }
                                    Box(
                                        modifier =
                                            Modifier
                                                // matchParentSize, not height(300.dp): 300 is the
                                                // height of this shelf ON A PHONE. On a desktop
                                                // window the first item is taller, the gradient
                                                // stopped mid-item and everything below it fell
                                                // back to the flat background — a hard colour seam
                                                // straight across Home. Sized by the item, the
                                                // bottom scrim always lands on the item's edge.
                                                .matchParentSize()
                                                // The glow used to reach down past the old app bar and
                                                // the Welcome back row, both gone with the Spotify
                                                // header. It keeps that reach by running on below the
                                                // first item instead of making the item taller; what
                                                // runs over is the scrim's tail, already faded out.
                                                .layout { measurable, constraints ->
                                                    val height = constraints.maxHeight + HomeGlowOverflow.roundToPx()
                                                    val placeable =
                                                        measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                                                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                                                }.angledGradientBackground(glowGradient, 25f),
                                    ) {
                                        if (headerImage != null && !headerImageFailed) {
                                            val context = LocalPlatformContext.current
                                            val headerImageRequest = remember(headerImage, context) {
                                                ImageRequest
                                                    .Builder(context)
                                                    .data(headerImage)
                                                    .crossfade(550)
                                                    .build()
                                            }
                                            AsyncImage(
                                                model = headerImageRequest,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                onError = { headerImageFailed = true },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            // Always over the image, which can be very bright: a layer of the
                                            // page's own colour tones it down in either theme and fades it
                                            // into the page along the tint's own 25° line, not in a flat
                                            // band at the bottom.
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .angledGradientBackground(headerOverlayColors, 25f),
                                            )
                                        }
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    // A share of the glow, not a fixed 180dp: on a glow
                                                    // ~560dp tall, 180dp made a long flat field that
                                                    // dropped off in one short step.
                                                    .fillMaxHeight(0.6f)
                                                    .align(Alignment.BottomCenter)
                                                    .background(scrimBrush),
                                        )
                                    }
                                }
                                Column(
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 15.dp),
                                ) {
                                    if (index == 0) {
                                        // The header's own height plus 8dp, so the first section does not
                                        // sit right under the chips.
                                        Spacer(
                                            Modifier.height(
                                                with(LocalDensity.current) { topAppBarHeightPx.toDp() } + 8.dp,
                                            ),
                                        )
                                    }
                                    // Android drops the 8dp over each section's title: the list's own 8dp
                                    // spacing already separates it from the shelf above.
                                    if (getPlatform() != Platform.Android) Spacer(modifier = Modifier.height(8.dp))
                                    if (item.isQuickPicksShelf(quickPicksTitle)) {
                                        QuickPicks(
                                            homeItem =
                                                item.let { content ->
                                                    content.copy(
                                                        contents =
                                                            content.contents.mapNotNull { ct ->
                                                                ct?.copy(
                                                                    artists =
                                                                        ct.artists?.let { art ->
                                                                            if (art.size > 1) {
                                                                                art.dropLast(1)
                                                                            } else {
                                                                                art
                                                                            }
                                                                        },
                                                                )
                                                            },
                                                    )
                                                },
                                            navController = navController,
                                            viewModel = viewModel,
                                        )
                                    } else if (item.isSongCardShelf()) {
                                        HomeDiscoverSection(
                                            data = item,
                                            navController = navController,
                                            homeViewModel = viewModel,
                                        )
                                    } else {
                                        HomeItem(
                                            navController = navController,
                                            data = item,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            AnimatedVisibility(
                                homeListState == ListState.PAGINATING,
                                enter = expandVertically() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                CenterLoadingBox(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(200.dp),
                                )
                            }
                        }
                        if (homeListState == ListState.PAGINATION_EXHAUST) {
                            itemsIndexed(newRelease, key = { idx, it -> "new_release_${it.title}_${it.channelId ?: it.moreEndpoint?.browseId ?: idx}" }) { _, it ->
                                AnimatedVisibility(
                                    visible = newRelease.isNotEmpty(),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(horizontal = 15.dp),
                                    ) {
                                        HomeItem(
                                            navController = navController,
                                            data = it,
                                        )
                                    }
                                }
                            }
                            item {
                                AnimatedVisibility(
                                    visible = moodMomentAndGenre != null,
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(horizontal = 15.dp),
                                    ) {
                                        moodMomentAndGenre?.let {
                                            MoodMomentAndGenre(
                                                mood = it,
                                                navController = navController,
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Column(
                                    Modifier
                                        .padding(vertical = 10.dp)
                                        .padding(horizontal = 15.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    ChartTitle()
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Crossfade(targetState = regionChart) {
                                        if (it != null) {
                                            DropdownButton(
                                                items = CHART_SUPPORTED_COUNTRY.itemsData.toList(),
                                                defaultSelected =
                                                    CHART_SUPPORTED_COUNTRY.itemsData.getOrNull(
                                                        CHART_SUPPORTED_COUNTRY.items.indexOf(it),
                                                    )
                                                        ?: CHART_SUPPORTED_COUNTRY.itemsData[1],
                                            ) {
                                                viewModel.exploreChart(
                                                    CHART_SUPPORTED_COUNTRY.items[
                                                        CHART_SUPPORTED_COUNTRY.itemsData.indexOf(
                                                            it,
                                                        ),
                                                    ],
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Crossfade(
                                        targetState = chartLoading,
                                        label = "Chart",
                                    ) { loading ->
                                        if (!loading) {
                                            chart?.let {
                                                ChartData(
                                                    chart = it,
                                                    navController = navController,
                                                )
                                            }
                                        } else {
                                            CenterLoadingBox(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(400.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            EndOfPage()
                        }
                    }
                    VerticalScrollBar(
                        scrollState = scrollState,
                        modifier =
                            Modifier.padding(
                                top = with(LocalDensity.current) { topAppBarHeightPx.toDp() },
                                bottom = DesktopMiniPlayerClearance,
                            ),
                    )
                } else {
                    Column {
                        Spacer(
                            Modifier.height(
                                with(LocalDensity.current) {
                                    topAppBarHeightPx.toDp()
                                } + 8.dp,
                            ),
                        )
                        HomeShimmer()
                    }
                }
            }
        }
        // Spotify / Apple Music flagship translucent top bar:
        // Pure transparent when resting at the top to let hero artwork/glow shine through;
        // transitions smoothly to a hardware-accelerated specular frosted scrim as content scrolls under.
        val density = LocalDensity.current
        val scrimFadeDistancePx = with(density) { 96.dp.toPx() }
        val scrimProgress by remember(scrimFadeDistancePx) {
            derivedStateOf {
                if (scrollState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (scrollState.firstVisibleItemScrollOffset.toFloat() / scrimFadeDistancePx).coerceIn(0f, 1f)
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .drawBehind {
                        val progress = scrimProgress
                        if (progress > 0.005f) {
                            // 1. Hardware-accelerated specular translucent gradient scrim matching page background
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        pageBackground.copy(alpha = (if (isLightTheme) 0.88f else 0.84f) * progress),
                                        pageBackground.copy(alpha = (if (isLightTheme) 0.72f else 0.68f) * progress),
                                    ),
                                ),
                            )
                            // 2. Micro-hairline bottom specular rim (0.5dp)
                            val rimWidth = 0.5.dp.toPx()
                            val rimColor =
                                if (isLightTheme) {
                                    Color.Black.copy(alpha = 0.06f * progress)
                                } else {
                                    Color.White.copy(alpha = 0.08f * progress)
                                }
                            drawLine(
                                color = rimColor,
                                start = Offset(0f, size.height - rimWidth / 2f),
                                end = Offset(size.width, size.height - rimWidth / 2f),
                                strokeWidth = rimWidth,
                            )
                        }
                    }.onGloballyPositioned { coordinates ->
                        topAppBarHeightPx = coordinates.size.height
                    },
        ) {
                Spacer(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.statusBars,
                            ),
                )
                // The avatar stays put and the chips scroll only to its right, clipped there instead of
                // passing over it, each end faded while chips wait past that edge. On a phone held
                // upright Create rides at the end of the chip row: the row is at least as wide as its
                // strip, so at rest Create sits at the right edge, and a Mood that opens wide pushes it
                // along instead of sliding under it. On Desktop and in landscape Create stays put at the
                // right edge, like the avatar.
                val windowSizeClass = com.maxrave.simpmusic.ui.theme.LocalAppWindowSizeClass.current
                val createPinned = getPlatform() == Platform.Desktop || !windowSizeClass.isCompact
                val chipHeadFade by animateFloatAsState(if (chipRowState.canScrollBackward) 1f else 0f)
                val chipTailFade by animateFloatAsState(if (chipRowState.canScrollForward) 1f else 0f)
                val isCompactScreen = windowSizeClass.isCompact
                var badgeExpanded by remember { mutableStateOf(false) }
                // Notifications moved into the avatar menu, Listen Together into the Create sheet.
                val createActions: @Composable () -> Unit = {
                    CreateActions(
                        backdrop = chipBackdrop,
                        navController = navController,
                        onSettings = { navController.navigate(SettingsDestination) },
                    )
                }
                // Single-Row Slim Spotify/YouTube Header (56dp)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeAccountButton(
                            accountName = accountInfo?.first,
                            thumbnailUrl = accountInfo?.second,
                            navController = navController,
                            backdrop = chipBackdrop,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SireenBrandingBadge(
                            compact = isCompactScreen && !badgeExpanded,
                            backdrop = chipBackdrop,
                            onBadgeClick = {
                                if (isCompactScreen) {
                                    badgeExpanded = !badgeExpanded
                                }
                            },
                        )
                    }
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .chipStripEdgeFades(32.dp, start = { chipHeadFade }, end = { chipTailFade })
                                    .mouseDragScroll(chipRowState)
                                    .horizontalScroll(chipRowState)
                                    .widthIn(min = maxWidth)
                                    .padding(end = if (createPinned) 0.dp else 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Chip(
                                    isAnimated = loading,
                                    isSelected = params == null && !moodExpanded,
                                    text = stringResource(Res.string.all),
                                    backdrop = chipBackdrop,
                                ) {
                                    moodExpanded = false
                                    viewModel.setParams(null)
                                }
                                ExpandableChip(
                                    text = stringResource(Res.string.mood),
                                    options = homeMoods.map { stringResource(it.first) },
                                    selectedIndex = homeMoods.indexOfFirst { it.second == params }.takeIf { it >= 0 },
                                    expanded = moodExpanded,
                                    onExpandedChange = { moodExpanded = it },
                                    onSelect = { index ->
                                        moodExpanded = false
                                        viewModel.setParams(homeMoods[index].second)
                                    },
                                    isAnimated = loading,
                                    backdrop = chipBackdrop,
                                )
                            }
                            if (!createPinned) {
                                Box(Modifier.padding(start = 8.dp)) { createActions() }
                            }
                        }
                    }
                    if (createPinned) {
                        Box(Modifier.padding(start = 8.dp, end = 16.dp)) { createActions() }
                    }
                }
            }
    }
}

/**
 * Fades each end of the chip strip out (a DstIn mask) while chips wait past that edge, so the row
 * reads as running on rather than cut off. [start] and [end] ease each fade in and out and are read
 * at draw time. Smoothstep, like every fade in the app: a linear ramp shows a line where it begins.
 */
internal fun Modifier.chipStripEdgeFades(
    width: Dp,
    start: () -> Float,
    end: () -> Float,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = width.toPx().coerceAtMost(size.width / 2f)
            val startFade = start()
            if (startFade > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(colorStops = chipFadeStops(startFade, fadingOut = false), startX = 0f, endX = fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
            val endFade = end()
            if (endFade > 0f) {
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colorStops = chipFadeStops(endFade, fadingOut = true),
                            startX = size.width - fadePx,
                            endX = size.width,
                        ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

// Opaque → transparent left to right when [fadingOut], the other way round otherwise; [fade] scales
// how far towards transparent the far end goes.
private fun chipFadeStops(
    fade: Float,
    fadingOut: Boolean,
): Array<Pair<Float, Color>> =
    Array(CHIP_FADE_STEPS + 1) { i ->
        val t = i / CHIP_FADE_STEPS.toFloat()
        val ramp = t * t * (3f - 2f * t)
        t to Color.Black.copy(alpha = 1f - fade * (if (fadingOut) ramp else 1f - ramp))
    }

private const val CHIP_FADE_STEPS = 24

/**
 * The avatar at the start of Home's header. It opens a menu showing who is signed in and the
 * greeting the old app bar used to show. Signed out, the avatar is a person glyph and the menu
 * offers the log-in.
 */
@Composable
private fun HomeAccountButton(
    accountName: String?,
    thumbnailUrl: String?,
    navController: NavController,
    backdrop: PlatformBackdrop? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Sign-out blanks the stored account name, so a blank name is the signed-out state.
    val signedIn = !accountName.isNullOrBlank()
    val displayName = if (signedIn) accountName.orEmpty() else stringResource(Res.string.guest)
    val hour = now().time.hour
    val greeting = RemoteGreetingManager.getGreeting(hour)
    Box {
        AccountAvatar(
            signedIn = signedIn,
            thumbnailUrl = thumbnailUrl,
            // 40dp aligns with the CreateActions island height on the other side of the header
            size = 40.dp,
            contentDescription = displayName,
            backdrop = backdrop,
            modifier = Modifier.clickable { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // The draft's 280dp (also Material's widest menu item) instead of wrapping its content,
            // which left the menu as narrow as its longest row. Capped, not fixed: a window narrower
            // than that gets a menu as wide as the window instead of one that runs off it.
            modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountAvatar(
                    signedIn = signedIn,
                    thumbnailUrl = thumbnailUrl,
                    size = 40.dp,
                    contentDescription = null,
                )
                Column {
                    Text(text = displayName, style = typo().titleSmall, maxLines = 1)
                    Text(text = greeting, style = typo().bodySmall)
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.notification), style = typo().bodyMedium) },
                leadingIcon = { Icon(SimpIcons.Notifications, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    navController.navigate(NotificationDestination)
                },
            )
            if (!signedIn) {
                Button(
                    onClick = {
                        menuOpen = false
                        navController.navigate(LoginDestination)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(Res.string.log_in_to_YouTube), color = LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
private fun AccountAvatar(
    signedIn: Boolean,
    thumbnailUrl: String?,
    size: Dp,
    contentDescription: String?,
    backdrop: PlatformBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val colorScheme = MaterialTheme.colorScheme
    val glassOn = LocalLiquidGlassEnabled.current && backdrop != null
    val avatarShape = CircleShape

    // Adaptive border and container colors matching mood pills (ChipGroup)
    val borderColor =
        if (isDark) {
            if (glassOn) Color.White.copy(alpha = 0.12f) else colorScheme.outlineVariant
        } else {
            Color.Black.copy(alpha = 0.14f)
        }

    val containerColor =
        if (isDark) {
            if (glassOn) Color.Transparent else colorScheme.surfaceContainer.copy(alpha = 0.5f)
        } else {
            Color.Black.copy(alpha = 0.04f)
        }

    val shapeModifier =
        if (glassOn && backdrop != null) {
            Modifier.liquidGlass(
                backdrop = backdrop,
                shape = avatarShape,
                highlight = Highlight(width = 1.dp),
            )
        } else {
            Modifier.clip(avatarShape)
        }

    Box(
        modifier =
            Modifier
                .size(size)
                .then(shapeModifier)
                .background(containerColor, avatarShape)
                .border(1.dp, borderColor, avatarShape)
                .clip(avatarShape)
                .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (signedIn && !thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(thumbnailUrl)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(avatarShape),
            )
        } else {
            Icon(
                imageVector = SimpIcons.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

// Portrait fills the width with one column (the next one peeking in). On a landscape window that one
// column stretched across the whole screen, so every row there is capped instead.
private val LandscapeGridItemMaxWidth = 400.dp

// A card shelf of songs only that leads to no page of its own ("Your daily discover"). List shelves
// (Quick picks, Heard in Shorts) carry itemsPerColumn and keep their own look; a shelf with a More
// page (Listen again can be all songs) keeps its cards; videos draw as wide cards, so never match.
private fun HomeItem.isSongCardShelf(): Boolean =
    itemsPerColumn == null &&
        moreEndpoint == null &&
        contents.isNotEmpty() &&
        contents.all { it != null && !it.videoId.isNullOrEmpty() && it.homeCardAspectRatio() == 1f }

@ExperimentalFoundationApi
@Composable
fun QuickPicks(
    homeItem: HomeItem,
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val lazyListState = rememberLazyGridState()
    val snapperFlingBehavior = rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = lazyListState, snapPosition = SnapPosition.Start))
    val isPortrait = com.maxrave.simpmusic.ui.theme.LocalAppWindowSizeClass.current.isCompact
    val density = LocalDensity.current
    var widthDp by remember {
        mutableStateOf(0.dp)
    }
    var bottomSheetShow by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<Track?>(null) }

    if (bottomSheetShow) {
        NowPlayingBottomSheet(
            onDismiss = { bottomSheetShow = false },
            song = track?.toSongEntity(),
            navController = navController,
        )
    }

    // Quick picks draws its own header, and its 8dp around the block and 5dp around the title are
    // gaps no other shelf has: on Android they stood it 8dp further off the shelf above than the rest.
    val compact = getPlatform() == Platform.Android
    Column(
        Modifier
            .padding(vertical = if (compact) 0.dp else 8.dp)
            .onGloballyPositioned { coordinates ->
                with(density) {
                    widthDp = (coordinates.size.width).toDp()
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = SimpIcons.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(Res.string.let_s_start_with_a_radio),
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = homeItem.title.ifBlank { stringResource(Res.string.quick_picks) },
                    style = typo().headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compact) 0.dp else 4.dp),
                )
            }
            if (homeItem.playAllEndpoint != null) {
                SectionHeaderButton(
                    text = stringResource(Res.string.play_all),
                    onClick = { viewModel.playAll(homeItem) },
                )
            }
        }
        LazyHorizontalGrid(
            rows = GridCells.Adaptive(minSize = if (compact) 52.dp else 56.dp),
            // Adaptive rows (~56dp each; compact on Android).
            modifier =
                Modifier
                    .height(if (compact) 224.dp else 256.dp)
                    .mouseDragScroll(lazyListState, snapperFlingBehavior),
            state = lazyListState,
            flingBehavior = snapperFlingBehavior,
        ) {
            itemsIndexed(homeItem.contents, key = { idx, it -> it?.videoId ?: it?.browseId ?: "qp_${idx}_${it?.title}" }) { _, it ->
                if (it != null) {
                    QuickPicksItem(
                        onClick = {
                            val firstQueue: Track = it.toTrack()
                            viewModel.setQueueData(
                                QueueData.Data(
                                    listTracks = arrayListOf(firstQueue),
                                    firstPlayedTrack = firstQueue,
                                    playlistId = "RDAMVM${it.videoId}",
                                    playlistName = "\"${it.title}\" Radio",
                                    playlistType = PlaylistType.RADIO,
                                    continuation = null,
                                ),
                            )
                            viewModel.loadMediaItem(
                                firstQueue,
                                type = Config.SONG_CLICK,
                            )
                        },
                        onLongClick = {
                            track = it.toTrack()
                            bottomSheetShow = true
                        },
                        data = it,
                        widthDp = if (isPortrait) (widthDp * 0.84f).coerceAtMost(320.dp) else minOf(widthDp, LandscapeGridItemMaxWidth + 30.dp),
                    )
                }
            }
        }
        HorizontalScrollBar(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            scrollState = lazyListState,
            flingBehavior = snapperFlingBehavior,
        )
    }
}

@Composable
fun MoodMomentAndGenre(
    mood: Mood,
    navController: NavController,
) {
    Column(
        Modifier
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(Res.string.let_s_pick_a_playlist_for_you),
            style = typo().bodyMedium,
        )
        // One block per section YouTube returned, headed by ITS OWN title. Hard-coding
        // "Moods & moment" / "Genre" here (and reading mood.moodsMoments / mood.genres by
        // index) mislabelled every row as soon as a signed-in account got an extra
        // "For you" section, and hid the real Genres section altogether.
        mood.sections.forEach { section ->
            val gridState = rememberLazyGridState()
            val flingBehavior = rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = gridState))
            Text(
                text = section.title,
                style = typo().headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
            )
            LazyHorizontalGrid(
                rows = GridCells.Adaptive(minSize = 64.dp),
                modifier = Modifier.height(210.dp).mouseDragScroll(gridState, flingBehavior),
                state = gridState,
                flingBehavior = flingBehavior,
            ) {
                items(section.items, key = { it.params }) { item ->
                    MoodMomentAndGenreHomeItem(
                        title = item.title,
                        stripeColor = item.stripeColor,
                    ) {
                        navController.navigate(
                            MoodDestination(
                                item.params,
                            ),
                        )
                    }
                }
            }
            HorizontalScrollBar(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                scrollState = gridState,
                flingBehavior = flingBehavior,
            )
        }
    }
}

@Composable
fun ChartTitle() {
    Column {
        Text(
            text = stringResource(Res.string.what_is_best_choice_today),
            style = typo().bodyMedium,
        )
        Text(
            text = stringResource(Res.string.chart),
            style = typo().headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
        )
    }
}

@Composable
fun ChartData(
    chart: Chart,
    navController: NavController,
) {
    var gridWidthDp by remember {
        mutableStateOf(0.dp)
    }
    val density = LocalDensity.current

    val lazyListState2 = rememberLazyGridState()
    // Start, not the default Center: a centred column leaves the one before it cut through its rank.
    val snapperFlingBehavior2 =
        rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = lazyListState2, snapPosition = SnapPosition.Start))
    val isPortrait = com.maxrave.simpmusic.ui.theme.LocalAppWindowSizeClass.current.isCompact

    Column(
        Modifier.onGloballyPositioned { coordinates ->
            with(density) {
                gridWidthDp = (coordinates.size.width).toDp()
            }
        },
    ) {
        chart.listChartItem.forEach { item ->
            Text(
                text = item.title,
                style = typo().headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
            )
            val lazyListState = rememberLazyListState()
            val snapperFlingBehavior = rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyListState = lazyListState))
            LazyRow(
                modifier = Modifier.mouseDragScroll(lazyListState, snapperFlingBehavior),
                state = lazyListState,
                flingBehavior = snapperFlingBehavior,
            ) {
                items(item.playlists.size, key = { index ->
                    val data = item.playlists[index]
                    data.id.ifEmpty { "${data.title}_$index" }
                }) {
                    HomeItemContentPlaylist(
                        onClick = {
                            navController.navigate(
                                PlaylistDestination(
                                    playlistId = item.playlists[it].id,
                                    isYourYouTubePlaylist = false,
                                ),
                            )
                        },
                        data = item.playlists[it],
                    )
                }
            }
        }
        Text(
            text = stringResource(Res.string.top_artists),
            style = typo().headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
        )
        LazyHorizontalGrid(
            rows = GridCells.Adaptive(minSize = 72.dp),
            modifier = Modifier.height(240.dp).mouseDragScroll(lazyListState2, snapperFlingBehavior2),
            state = lazyListState2,
            flingBehavior = snapperFlingBehavior2,
        ) {
            items(chart.artists.itemArtists.size, key = { index ->
                val item = chart.artists.itemArtists[index]
                item.browseId.ifEmpty { "${item.title}_$index" }
            }) {
                val data = chart.artists.itemArtists[it]
                ItemArtistChart(
                    onClick = {
                        navController.navigate(
                            ArtistDestination(
                                channelId = data.browseId,
                            ),
                        )
                    },
                    data = data,
                    widthDp = if (isPortrait) gridWidthDp else minOf(gridWidthDp, LandscapeGridItemMaxWidth),
                )
            }
        }
        HorizontalScrollBar(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            scrollState = lazyListState2,
            flingBehavior = snapperFlingBehavior2,
        )
        // Ranked podcast shows, laid out exactly like the artist chart above.
        chart.podcasts?.let { podcasts ->
            Text(
                text = podcasts.title,
                style = typo().headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
            )
            val podcastGridState = rememberLazyGridState()
            val podcastFlingBehavior =
                rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = podcastGridState, snapPosition = SnapPosition.Start))
            LazyHorizontalGrid(
                rows = GridCells.Adaptive(minSize = 72.dp),
                modifier = Modifier.height(240.dp).mouseDragScroll(podcastGridState, podcastFlingBehavior),
                state = podcastGridState,
                flingBehavior = podcastFlingBehavior,
            ) {
                items(podcasts.shows.size, key = { index -> podcasts.shows[index].browseId.ifEmpty { "podcast_$index" } }) {
                    val data = podcasts.shows[it]
                    ItemArtistChart(
                        onClick = { navController.navigate(PodcastDestination(podcastId = data.browseId)) },
                        data = data,
                        widthDp = if (isPortrait) gridWidthDp else minOf(gridWidthDp, LandscapeGridItemMaxWidth),
                        thumbnailShape = ArtworkShape,
                        subtitle = data.subscribers,
                    )
                }
            }
            HorizontalScrollBar(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                scrollState = podcastGridState,
                flingBehavior = podcastFlingBehavior,
            )
        }
    }
}