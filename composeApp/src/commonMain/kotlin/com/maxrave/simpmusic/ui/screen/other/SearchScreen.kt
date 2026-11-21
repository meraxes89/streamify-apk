package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.intent.GenericIntent
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.expect.ui.DesktopMiniPlayerClearance
import com.maxrave.simpmusic.expect.ui.PlatformBackHandler
import com.maxrave.simpmusic.expect.ui.SongRecognitionSheet
import com.maxrave.simpmusic.expect.ui.VerticalScrollBar
import com.maxrave.simpmusic.expect.ui.layerBackdrop
import com.maxrave.simpmusic.expect.ui.rememberBackdrop
import com.maxrave.simpmusic.extension.getScreenSizeInfo
import com.maxrave.simpmusic.extension.ultraThinBarStyle
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.AddToPlaylistModalBottomSheet
import com.maxrave.simpmusic.ui.component.CenterLoadingBox
import com.maxrave.simpmusic.ui.component.MoodCategoryCard
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.extension.getStringBlocking
import com.maxrave.simpmusic.extension.toAppDeepLinkOrNull
import com.maxrave.simpmusic.ui.component.ArtistFullWidthItems
import com.maxrave.simpmusic.ui.component.AudioPlayingIndicator
import com.maxrave.simpmusic.ui.component.Chip
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.NowPlayingBottomSheet
import com.maxrave.simpmusic.ui.component.PlaylistFullWidthItems
import com.maxrave.simpmusic.ui.component.ShimmerSearchItem
import com.maxrave.simpmusic.ui.component.SimpMusicChartButton
import com.maxrave.simpmusic.ui.component.SongFullWidthItems
import com.maxrave.simpmusic.ui.component.selection.SelectedSongsBottomSheet
import com.maxrave.simpmusic.ui.component.selection.SongSelectionTopAppBar
import com.maxrave.simpmusic.ui.component.selection.rememberSongSelectionState
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.ArrowOutward
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.GraphicEq
import com.maxrave.simpmusic.ui.icon.History
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.ArtworkShape
import com.maxrave.simpmusic.ui.theme.LocalLiquidGlassEnabled
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SearchScreenUIState
import com.maxrave.simpmusic.viewModel.SearchType
import com.maxrave.simpmusic.viewModel.SearchViewModel
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.SongSelectionViewModel
import com.maxrave.simpmusic.viewModel.toStringRes
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.clear_search_history
import simpmusic.composeapp.generated.resources.error_occurred
import simpmusic.composeapp.generated.resources.everything_you_need
import simpmusic.composeapp.generated.resources.in_search
import simpmusic.composeapp.generated.resources.last_played_from_search
import simpmusic.composeapp.generated.resources.no_results_found
import simpmusic.composeapp.generated.resources.playlists
import simpmusic.composeapp.generated.resources.podcasts
import simpmusic.composeapp.generated.resources.recents
import simpmusic.composeapp.generated.resources.recognize_song
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.search_for
import simpmusic.composeapp.generated.resources.search_for_songs_artists_albums_playlists_and_more
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.videos
import simpmusic.composeapp.generated.resources.what_do_you_want_to_listen_to

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel = koinInject(),
    sharedViewModel: SharedViewModel = koinInject(),
    navController: NavController,
) {
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchScreenState by searchViewModel.searchScreenState.collectAsStateWithLifecycle()
    val uiState by searchViewModel.searchScreenUIState.collectAsStateWithLifecycle()
    val searchHistory by searchViewModel.searchHistory.collectAsStateWithLifecycle()
    val moodAndGenres by searchViewModel.moodAndGenres.collectAsStateWithLifecycle()
    val moodArtwork by searchViewModel.moodArtwork.collectAsStateWithLifecycle()
    val lastPlayedSearchTrack by searchViewModel.lastPlayedSearchTrack.collectAsStateWithLifecycle()

    var searchUIType by rememberSaveable { mutableStateOf(SearchUIType.EMPTY) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var isSearchSubmitted by rememberSaveable { mutableStateOf(false) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    var isFocused by rememberSaveable { mutableStateOf(false) }

    val isSearchPanelVisible =
        searchUIType == SearchUIType.SEARCH_HISTORY || searchUIType == SearchUIType.SEARCH_SUGGESTIONS
    val dismissSearchPanel: () -> Unit = {
        isExpanded = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    PlatformBackHandler(enabled = isSearchPanelVisible, onBack = dismissSearchPanel)

    // The bar floats OVER the content (a Box, not a Column) so there is something behind it to
    // blur — same arrangement HomeScreen uses. Each branch owns a scroll state, hoisted here so
    // the bar can tell whether the branch currently on screen is scrolled away from the top.
    // Two columns only on a phone held upright. Anywhere wider — tablet, landscape, desktop — two
    // columns stretch each tile to half the window, and since the tile keeps a 2:1 ratio it grows
    // absurdly tall with it.
    val screenInfo = getScreenSizeInfo()
    val isMobilePortrait = getPlatform() == Platform.Android && screenInfo.wDP < screenInfo.hDP
    val moodGridColumns = if (isMobilePortrait) 2 else 4

    val hazeState = rememberHazeState()
    // The floating chip row refracts the page scrolling under it, so the page is the glass source and
    // the row its sibling (nesting the row inside the source is the RuntimeShader feedback loop).
    val chipBackdrop = rememberBackdrop(MaterialTheme.colorScheme.background)
    val suggestionsState = rememberLazyListState()
    val historyState = rememberLazyListState()
    val moodGridState = rememberLazyGridState()
    val resultsState = rememberLazyListState()
    val resultsGridState = rememberLazyGridState()
    var searchBarHeightPx by remember { mutableIntStateOf(0) }
    val searchBarHeight = with(LocalDensity.current) { searchBarHeightPx.toDp() }
    val isContentAtTop by remember {
        derivedStateOf {
            when (searchUIType) {
                SearchUIType.EMPTY ->
                    moodGridState.firstVisibleItemIndex == 0 && moodGridState.firstVisibleItemScrollOffset == 0
                SearchUIType.SEARCH_HISTORY ->
                    historyState.firstVisibleItemIndex == 0 && historyState.firstVisibleItemScrollOffset == 0
                SearchUIType.SEARCH_SUGGESTIONS ->
                    suggestionsState.firstVisibleItemIndex == 0 && suggestionsState.firstVisibleItemScrollOffset == 0
                SearchUIType.SEARCH_RESULTS ->
                    resultsState.firstVisibleItemIndex == 0 && resultsState.firstVisibleItemScrollOffset == 0 &&
                        resultsGridState.firstVisibleItemIndex == 0 && resultsGridState.firstVisibleItemScrollOffset == 0
            }
        }
    }

    val searchForString = stringResource(Res.string.search_for)
    val songString = stringResource(Res.string.song).lowercase()
    val artistString = stringResource(Res.string.artists).lowercase()
    val albumString = stringResource(Res.string.albums).lowercase()
    val playlistString = stringResource(Res.string.playlists).lowercase()
    val videoString = stringResource(Res.string.videos).lowercase()
    val podcastString = stringResource(Res.string.podcasts).lowercase()

    // Animated Placeholder
    val placeholderTexts =
        remember {
            listOf(
                "$searchForString $songString...",
                "$searchForString $artistString...",
                "$searchForString $albumString...",
                "$searchForString $playlistString...",
                "$searchForString $videoString...",
                "$searchForString $podcastString...",
            )
        }

    var currentPlaceholderIndex by remember { mutableIntStateOf(0) }

    // Animate placeholder - pause when focused
    LaunchedEffect(isFocused) {
        while (!isFocused) {
            delay(3000) // Change every 3 seconds
            currentPlaceholderIndex = (currentPlaceholderIndex + 1) % placeholderTexts.size
        }
    }

    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }

    val selectionState = rememberSongSelectionState()
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    var showSelectionSheet by rememberSaveable { mutableStateOf(false) }
    var showSelectionAddToPlaylist by rememberSaveable { mutableStateOf(false) }
    var showRecognition by rememberSaveable { mutableStateOf(false) }
    // The sheet asked for by simpmusic://recognize (the Quick Settings tile), opened once and cleared.
    val songRecognitionRequest by sharedViewModel.songRecognitionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(songRecognitionRequest) {
        if (songRecognitionRequest) {
            showRecognition = true
            sharedViewModel.requestSongRecognition(false)
        }
    }
    val currentVideoId by searchViewModel.nowPlayingVideoId.collectAsStateWithLifecycle()
    val chipRowState = rememberScrollState()
    val pullToRefreshState = rememberPullToRefreshState()

    val onMoreClick: (SongEntity) -> Unit = { song ->
        sheetSong = song
        showBottomSheet = true
    }

    LaunchedEffect(searchText) {
        if (isFocused) {
            isSearchSubmitted = false
            isExpanded = true
        }
        if (searchText.isNotEmpty() && isFocused) {
            searchViewModel.suggestQuery(searchText)
        }
    }

    LaunchedEffect(isSearchSubmitted) {
        if (isSearchSubmitted) {
            isExpanded = false
        }
    }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            isExpanded = true
        }
    }

    LaunchedEffect(isExpanded, searchText, isFocused) {
        searchUIType =
            if (searchText.isNotEmpty() && isExpanded) {
                SearchUIType.SEARCH_SUGGESTIONS
            } else if (isFocused && isExpanded) {
                SearchUIType.SEARCH_HISTORY
            } else if (searchText.isEmpty()) {
                SearchUIType.EMPTY
            } else {
                SearchUIType.SEARCH_RESULTS
            }
    }

    //On search icon click while on search screen, open keyboard. Android only feature
    if (getPlatform() == Platform.Android) {
        val reloadDestination by sharedViewModel.reloadDestination.collectAsStateWithLifecycle()
        LaunchedEffect(reloadDestination) {
            if (reloadDestination == SearchDestination::class) {
                if (!selectionState.isActive && searchUIType == SearchUIType.EMPTY) {
                    isExpanded = true
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                sharedViewModel.reloadDestinationDone()
            }
        }
    }

    if (showSelectionSheet) {
        val selectedIds = selectionState.selected.toList()
        SelectedSongsBottomSheet(
            count = selectedIds.size,
            onDismiss = { showSelectionSheet = false },
            onPlayNext = {
                selectionViewModel.playNext(selectedIds)
                selectionState.exit()
            },
            onAddToQueue = {
                selectionViewModel.addToQueue(selectedIds)
                selectionState.exit()
            },
            onAddToPlaylist = { showSelectionAddToPlaylist = true },
            onDownload = {
                selectionViewModel.download(selectedIds)
                selectionState.exit()
            },
            onAddToFavorite = {
                selectionViewModel.addToFavorite(selectedIds)
                selectionState.exit()
            },
        )
    }
    if (showSelectionAddToPlaylist) {
        val selectedIds = selectionState.selected.toList()
        val localPlaylists by selectionViewModel.listLocalPlaylist.collectAsStateWithLifecycle()
        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = true,
            listLocalPlaylist = localPlaylists,
            listYouTubePlaylist = emptyList(),
            onDismiss = { showSelectionAddToPlaylist = false },
            onClick = { playlist ->
                selectionViewModel.addToPlaylist(playlist.id, selectedIds)
                selectionState.exit()
            },
            onYTPlaylistClick = {},
        )
    }
    if (showBottomSheet) {
        NowPlayingBottomSheet(
            onDismiss = {
                showBottomSheet = false
                sheetSong = null
            },
            navController = navController,
            song = sheetSong,
        )
    }
    if (showRecognition) {
        SongRecognitionSheet(
            onFind = { query ->
                searchText = query
                focusManager.clearFocus()
                isSearchSubmitted = true
                searchViewModel.insertSearchHistory(query)
                when (searchScreenState.searchType) {
                    SearchType.ALL -> searchViewModel.searchAll(query)
                    SearchType.SONGS -> searchViewModel.searchSongs(query)
                    SearchType.VIDEOS -> searchViewModel.searchVideos(query)
                    SearchType.ALBUMS -> searchViewModel.searchAlbums(query)
                    SearchType.ARTISTS -> searchViewModel.searchArtists(query)
                    SearchType.PLAYLISTS -> searchViewModel.searchPlaylists(query)
                    SearchType.FEATURED_PLAYLISTS -> searchViewModel.searchFeaturedPlaylist(query)
                    SearchType.PODCASTS -> searchViewModel.searchPodcast(query)
                }
            },
            onDismiss = { showRecognition = false },
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Transparent),
    ) {
        // Content scrolls under the bar (it is the haze source), so it needs top padding
        // equal to the bar's measured height to keep its first item clear of it.
        Crossfade(
            targetState = searchUIType,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "search_state_crossfade",
            modifier = Modifier.fillMaxSize().then(if (LocalLiquidGlassEnabled.current) Modifier.layerBackdrop(chipBackdrop) else Modifier).hazeSource(hazeState),
        ) {
            when (it) {
                SearchUIType.SEARCH_SUGGESTIONS -> {
                    LazyColumn(
                        Modifier.padding(horizontal = 16.dp),
                        state = suggestionsState,
                        contentPadding = PaddingValues(top = searchBarHeight, bottom = 10.dp),
                    ) {
                        items(searchScreenState.suggestYTItems) { item ->
                            SuggestItemRow(
                                searchResult = item,
                                onItemClick = { item ->
                                    when (item) {
                                        is SongsResult, is VideosResult -> {
                                            focusManager.clearFocus()
                                            val firstTrack: Track = (item as? SongsResult)?.toTrack() ?: (item as VideosResult).toTrack()
                                            searchViewModel.setLastPlayedSearchTrack(firstTrack)
                                            searchViewModel.insertSearchHistory(firstTrack.title)
                                            if (searchText.isNotBlank() && searchText.trim() != firstTrack.title) {
                                                searchViewModel.insertSearchHistory(searchText.trim())
                                            }
                                            searchViewModel.setQueueData(
                                                QueueData.Data(
                                                    listTracks = arrayListOf(firstTrack),
                                                    firstPlayedTrack = firstTrack,
                                                    playlistId = "RDAMVM${firstTrack.videoId}",
                                                    playlistName = "\"${searchText}\" ${getStringBlocking(Res.string.in_search)}",
                                                    playlistType = PlaylistType.RADIO,
                                                    continuation = null,
                                                ),
                                            )
                                            searchViewModel.loadMediaItem(firstTrack, type = Config.SONG_CLICK)
                                        }

                                        is ArtistsResult -> {
                                            navController.navigate(
                                                ArtistDestination(item.browseId),
                                            )
                                        }

                                        is AlbumsResult -> {
                                            navController.navigate(
                                                AlbumDestination(item.browseId),
                                            )
                                        }

                                        is PlaylistsResult -> {
                                            navController.navigate(
                                                PlaylistDestination(
                                                    item.browseId,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        items(searchScreenState.suggestQueries) { suggestion ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(),
                                            onClick = {
                                                searchText = suggestion
                                                focusManager.clearFocus()
                                                isSearchSubmitted = true
                                                searchViewModel.insertSearchHistory(suggestion)
                                                when (searchScreenState.searchType) {
                                                    SearchType.ALL -> searchViewModel.searchAll(suggestion)
                                                    SearchType.SONGS -> searchViewModel.searchSongs(suggestion)
                                                    SearchType.VIDEOS -> searchViewModel.searchVideos(suggestion)
                                                    SearchType.ALBUMS -> searchViewModel.searchAlbums(suggestion)
                                                    SearchType.ARTISTS -> searchViewModel.searchArtists(suggestion)
                                                    SearchType.PLAYLISTS -> searchViewModel.searchPlaylists(suggestion)
                                                    SearchType.FEATURED_PLAYLISTS -> searchViewModel.searchFeaturedPlaylist(suggestion)
                                                    SearchType.PODCASTS -> searchViewModel.searchPodcast(suggestion)
                                                }
                                            },
                                        ).padding(horizontal = 12.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = suggestion,
                                    style = typo().bodyMedium,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        searchText = suggestion
                                        focusRequester.requestFocus()
                                    },
                                ) {
                                    Icon(
                                        imageVector = SimpIcons.ArrowOutward,
                                        contentDescription = "Search suggestion",
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                        item {
                            EndOfPage(
                                withoutCredit = true,
                            )
                        }
                    }
                    VerticalScrollBar(
                        scrollState = suggestionsState,
                        modifier = Modifier.padding(top = searchBarHeight, bottom = DesktopMiniPlayerClearance),
                    )
                }

                SearchUIType.SEARCH_HISTORY -> {
                    // Search history state
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                    ) {
                        LazyColumn(
                            state = historyState,
                            contentPadding = PaddingValues(top = searchBarHeight, bottom = 10.dp),
                        ) {
                            lastPlayedSearchTrack?.let { track ->
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.last_played_from_search),
                                            style = typo().titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                                        )
                                        SongFullWidthItems(
                                            track = track,
                                            isPlaying = track.videoId == currentVideoId,
                                            modifier = Modifier,
                                            onMoreClickListener = {
                                                onMoreClick(track.toSongEntity())
                                            },
                                            onLongClick = {
                                                onMoreClick(track.toSongEntity())
                                            },
                                            onClickListener = {
                                                focusManager.clearFocus()
                                                searchViewModel.setQueueData(
                                                    QueueData.Data(
                                                        listTracks = arrayListOf(track),
                                                        firstPlayedTrack = track,
                                                        playlistId = "RDAMVM${track.videoId}",
                                                        playlistName =
                                                            "\"${track.title}\" ${
                                                                getStringBlocking(
                                                                    Res.string.in_search,
                                                                )
                                                            }",
                                                        playlistType = PlaylistType.RADIO,
                                                        continuation = null,
                                                    ),
                                                )
                                                searchViewModel.loadMediaItem(track, Config.SONG_CLICK)
                                            },
                                            onAddToQueue = {
                                                sharedViewModel.addListToQueue(
                                                    arrayListOf(track),
                                                )
                                            },
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        )
                                    }
                                }
                            }
                            stickyHeader {
                                Crossfade(
                                    targetState = searchHistory.isNotEmpty(),
                                ) {
                                    if (it) {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.background)
                                                    .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.recents),
                                                style = typo().titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            TextButton(
                                                onClick = { searchViewModel.deleteSearchHistory() },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(Res.string.clear_search_history),
                                                    style = typo().labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            items(searchHistory) { historyItem ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                searchText = historyItem
                                                focusManager.clearFocus()
                                                isSearchSubmitted = true
                                                searchViewModel.insertSearchHistory(historyItem)
                                                when (searchScreenState.searchType) {
                                                    SearchType.ALL -> searchViewModel.searchAll(historyItem)
                                                    SearchType.SONGS -> searchViewModel.searchSongs(historyItem)
                                                    SearchType.VIDEOS -> searchViewModel.searchVideos(historyItem)
                                                    SearchType.ALBUMS -> searchViewModel.searchAlbums(historyItem)
                                                    SearchType.ARTISTS -> searchViewModel.searchArtists(historyItem)
                                                    SearchType.PLAYLISTS -> searchViewModel.searchPlaylists(historyItem)
                                                    SearchType.FEATURED_PLAYLISTS -> searchViewModel.searchFeaturedPlaylist(historyItem)
                                                    SearchType.PODCASTS -> searchViewModel.searchPodcast(historyItem)
                                                }
                                            }.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = SimpIcons.History,
                                        contentDescription = "Search history",
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.padding(horizontal = 12.dp))
                                    Text(
                                        text = historyItem,
                                        style = typo().bodyMedium,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            searchText = historyItem
                                            focusRequester.requestFocus()
                                        },
                                    ) {
                                        Icon(
                                            imageVector = SimpIcons.ArrowOutward,
                                            contentDescription = "Search suggestion",
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                            item {
                                EndOfPage(
                                    withoutCredit = true,
                                )
                            }
                        }
                    }
                    VerticalScrollBar(
                        scrollState = historyState,
                        modifier = Modifier.padding(top = searchBarHeight, bottom = DesktopMiniPlayerClearance),
                    )
                }

                SearchUIType.EMPTY -> {
                    val mood = moodAndGenres
                    if (mood == null) {
                        // First run only: the repository serves its cached copy before hitting the
                        // network, so this spinner is never seen again after the first fetch.
                        CenterLoadingBox(Modifier.fillMaxSize())
                    } else {
                        // Capped and centred: on a wide desktop window the grid would otherwise
                        // span the whole width, stretching four tiles into long bars. 1100.dp
                        // keeps a tile near 250.dp, which is its natural size.
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(moodGridColumns),
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .widthIn(max = 1100.dp)
                                    .padding(horizontal = 16.dp),
                            state = moodGridState,
                            contentPadding = PaddingValues(top = searchBarHeight),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            lastPlayedSearchTrack?.let { track ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(top = 16.dp, bottom = 4.dp),
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.last_played_from_search),
                                            style = typo().titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                                        )
                                        SongFullWidthItems(
                                            track = track,
                                            isPlaying = track.videoId == currentVideoId,
                                            modifier = Modifier,
                                            onMoreClickListener = {
                                                onMoreClick(track.toSongEntity())
                                            },
                                            onLongClick = {
                                                onMoreClick(track.toSongEntity())
                                            },
                                            onClickListener = {
                                                searchViewModel.setQueueData(
                                                    QueueData.Data(
                                                        listTracks = arrayListOf(track),
                                                        firstPlayedTrack = track,
                                                        playlistId = "RDAMVM${track.videoId}",
                                                        playlistName =
                                                            "\"${track.title}\" ${
                                                                getStringBlocking(
                                                                    Res.string.in_search,
                                                                )
                                                            }",
                                                        playlistType = PlaylistType.RADIO,
                                                        continuation = null,
                                                    ),
                                                )
                                                searchViewModel.loadMediaItem(track, Config.SONG_CLICK)
                                            },
                                            onAddToQueue = {
                                                sharedViewModel.addListToQueue(
                                                    arrayListOf(track),
                                                )
                                            },
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(top = 12.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        )
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            // Breathing room on both sides of this block: above it
                                            // sits the floating search bar, below it the tile grid.
                                            .padding(top = 36.dp, bottom = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.everything_you_need),
                                        style = typo().titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(Res.string.search_for_songs_artists_albums_playlists_and_more),
                                        style = typo().bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    SimpMusicChartButton(
                                        modifier = Modifier.padding(top = 10.dp),
                                    ) {
                                        uriHandler.openUri("https://chart.simpmusic.org")
                                    }
                                }
                            }
                            mood.sections.forEachIndexed { index, section ->
                                // First section runs straight on from the header block above it,
                                // so its own heading would just be a second title in a row.
                                if (index > 0) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            // Section titles come from YouTube already localised,
                                            // so there is no string resource to pick here.
                                            text = section.title,
                                            style = typo().titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                                // Key must include the section: every section lives in this ONE
                                // grid, and "For you" repeats categories that also appear under
                                // Moods or Genres, so params alone collides.
                                items(section.items, key = { "${section.title}/${it.params}" }) { item ->
                                    // LazyVerticalGrid only composes tiles inside the viewport, so
                                    // putting the request here IS the laziness — a category the
                                    // user never scrolls to never costs a browse.
                                    LaunchedEffect(item.params) {
                                        searchViewModel.loadMoodArtwork(item.params)
                                    }
                                    MoodCategoryCard(
                                        title = item.title,
                                        artworkUrl = moodArtwork[item.params],
                                    ) {
                                        navController.navigate(MoodDestination(item.params))
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EndOfPage()
                            }
                        }
                        VerticalScrollBar(
                            scrollState = moodGridState,
                            modifier = Modifier.padding(top = searchBarHeight, bottom = DesktopMiniPlayerClearance),
                        )
                        }
                    }
                }

                SearchUIType.SEARCH_RESULTS -> {
                    // Content area — chips now live in the blurred bar block above.
                    Column(modifier = Modifier.fillMaxSize()) {
                        PullToRefreshBox(
                            modifier = Modifier.fillMaxSize(),
                            state = pullToRefreshState,
                            onRefresh = {
                                val query = searchText.trim()
                                if (query.isNotEmpty()) {
                                    isSearchSubmitted = true
                                    searchViewModel.insertSearchHistory(query)
                                    when (searchScreenState.searchType) {
                                        SearchType.ALL -> searchViewModel.searchAll(query)
                                        SearchType.SONGS -> searchViewModel.searchSongs(query)
                                        SearchType.VIDEOS -> searchViewModel.searchVideos(query)
                                        SearchType.ALBUMS -> searchViewModel.searchAlbums(query)
                                        SearchType.ARTISTS -> searchViewModel.searchArtists(query)
                                        SearchType.PLAYLISTS -> searchViewModel.searchPlaylists(query)
                                        SearchType.FEATURED_PLAYLISTS -> searchViewModel.searchFeaturedPlaylist(query)
                                        SearchType.PODCASTS -> searchViewModel.searchPodcast(query)
                                    }
                                }
                            },
                            isRefreshing = uiState is SearchScreenUIState.Loading,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    state = pullToRefreshState,
                                    isRefreshing = uiState is SearchScreenUIState.Loading,
                                    // Anchored to the top of the box, which now starts under the
                                    // bar — without this offset the spinner sits behind the bar
                                    // and only its top sliver shows.
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = searchBarHeight),
                                    containerColor = PullToRefreshDefaults.indicatorContainerColor,
                                    color = PullToRefreshDefaults.indicatorColor,
                                    maxDistance = PullToRefreshDefaults.PositionalThreshold - 5.dp,
                                )
                            },
                        ) {
                            Crossfade(targetState = uiState) { uiState ->
                                when (uiState) {
                                    is SearchScreenUIState.Loading -> {
                                        // Loading state — same top inset as the results list, or
                                        // the first shimmer row hides behind the bar and chips.
                                        LazyColumn(
                                            contentPadding =
                                                PaddingValues(
                                                    top = searchBarHeight,
                                                    bottom = 10.dp,
                                                ),
                                        ) {
                                            items(10) {
                                                ShimmerSearchItem()
                                            }
                                        }
                                    }

                                    is SearchScreenUIState.Success -> {
                                        // Success state with results
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            // Search Results List
                                            val currentResults =
                                                when (searchScreenState.searchType) {
                                                    SearchType.ALL -> searchScreenState.searchAllResult
                                                    SearchType.SONGS -> searchScreenState.searchSongsResult
                                                    SearchType.VIDEOS -> searchScreenState.searchVideosResult
                                                    SearchType.ALBUMS -> searchScreenState.searchAlbumsResult
                                                    SearchType.ARTISTS -> searchScreenState.searchArtistsResult
                                                    SearchType.PLAYLISTS -> searchScreenState.searchPlaylistsResult
                                                    SearchType.FEATURED_PLAYLISTS -> searchScreenState.searchFeaturedPlaylistsResult
                                                    SearchType.PODCASTS -> searchScreenState.searchPodcastsResult
                                                }

                                            Crossfade(targetState = currentResults.isNotEmpty()) { hasResults ->
                                                if (hasResults) {
                                                    val isVisualGrid = searchScreenState.searchType == SearchType.ALBUMS ||
                                                        searchScreenState.searchType == SearchType.ARTISTS ||
                                                        searchScreenState.searchType == SearchType.PLAYLISTS

                                                    if (isVisualGrid) {
                                                        LazyVerticalGrid(
                                                            columns = GridCells.Fixed(moodGridColumns),
                                                            contentPadding =
                                                                PaddingValues(
                                                                    start = 12.dp,
                                                                    end = 12.dp,
                                                                    top = searchBarHeight + 8.dp,
                                                                    bottom = 150.dp,
                                                                ),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                                            state = resultsGridState,
                                                            modifier = Modifier.fillMaxSize(),
                                                        ) {
                                                            items(currentResults) { result ->
                                                                Box(modifier = Modifier.animateItem()) {
                                                                    when (result) {
                                                                        is AlbumsResult -> {
                                                                            SearchAlbumGridCard(
                                                                                album = result,
                                                                                onClick = {
                                                                                    navController.navigate(
                                                                                        AlbumDestination(result.browseId),
                                                                                    )
                                                                                },
                                                                            )
                                                                        }
                                                                        is ArtistsResult -> {
                                                                            SearchArtistGridCard(
                                                                                artist = result,
                                                                                onClick = {
                                                                                    navController.navigate(
                                                                                        ArtistDestination(result.browseId),
                                                                                    )
                                                                                },
                                                                            )
                                                                        }
                                                                        is PlaylistsResult -> {
                                                                            SearchPlaylistGridCard(
                                                                                playlist = result,
                                                                                onClick = {
                                                                                    if (result.resultType == "Podcast") {
                                                                                        navController.navigate(
                                                                                            PodcastDestination(result.browseId),
                                                                                        )
                                                                                    } else {
                                                                                        navController.navigate(
                                                                                            PlaylistDestination(result.browseId),
                                                                                        )
                                                                                    }
                                                                                },
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        VerticalScrollBar(
                                                            scrollState = resultsGridState,
                                                            modifier = Modifier.padding(top = searchBarHeight, bottom = DesktopMiniPlayerClearance),
                                                        )
                                                    } else {
                                                        val isAllSearch = searchScreenState.searchType == SearchType.ALL
                                                        val topResult = if (isAllSearch) currentResults.firstOrNull() else null
                                                        val remainingResults = if (topResult != null) currentResults.drop(1) else currentResults

                                                        LazyColumn(
                                                            contentPadding =
                                                                PaddingValues(
                                                                    start = 4.dp,
                                                                    end = 4.dp,
                                                                    top = searchBarHeight,
                                                                    bottom = 10.dp,
                                                                ),
                                                            state = resultsState,
                                                        ) {
                                                            if (topResult != null) {
                                                                item(key = "top_result_hero") {
                                                                    TopResultHeroCard(
                                                                        result = topResult,
                                                                        isPlaying =
                                                                            when (topResult) {
                                                                                is SongsResult -> topResult.videoId == currentVideoId
                                                                                is VideosResult -> topResult.videoId == currentVideoId
                                                                                else -> false
                                                                            },
                                                                        onArtistClick = { browseId ->
                                                                            navController.navigate(
                                                                                ArtistDestination(browseId),
                                                                            )
                                                                        },
                                                                        onAlbumClick = { browseId ->
                                                                            navController.navigate(
                                                                                AlbumDestination(browseId),
                                                                            )
                                                                        },
                                                                        onPlaylistClick = { playlist ->
                                                                            if (playlist.resultType == "Podcast") {
                                                                                navController.navigate(
                                                                                    PodcastDestination(playlist.browseId),
                                                                                )
                                                                            } else {
                                                                                navController.navigate(
                                                                                    PlaylistDestination(playlist.browseId),
                                                                                )
                                                                            }
                                                                        },
                                                                        onTrackClick = { firstTrack ->
                                                                            searchViewModel.setLastPlayedSearchTrack(firstTrack)
                                                                            searchViewModel.insertSearchHistory(firstTrack.title)
                                                                            if (searchText.isNotBlank() && searchText.trim() != firstTrack.title) {
                                                                                searchViewModel.insertSearchHistory(searchText.trim())
                                                                            }
                                                                            searchViewModel.setQueueData(
                                                                                QueueData.Data(
                                                                                    listTracks = arrayListOf(firstTrack),
                                                                                    firstPlayedTrack = firstTrack,
                                                                                    playlistId = "RDAMVM${firstTrack.videoId}",
                                                                                    playlistName =
                                                                                        "\"${searchText}\" ${
                                                                                            getStringBlocking(
                                                                                                Res.string.in_search,
                                                                                            )
                                                                                        }",
                                                                                    playlistType = PlaylistType.RADIO,
                                                                                    continuation = null,
                                                                                ),
                                                                            )
                                                                            searchViewModel.loadMediaItem(firstTrack, Config.SONG_CLICK)
                                                                        },
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                                        onLongClick =
                                                                            when (topResult) {
                                                                                is SongsResult -> ({ onMoreClick(topResult.toTrack().toSongEntity()) })
                                                                                is VideosResult -> ({ onMoreClick(topResult.toTrack().toSongEntity()) })
                                                                                else -> null
                                                                            },
                                                                    )
                                                                }
                                                                if (remainingResults.isNotEmpty()) {
                                                                    item(key = "more_results_header") {
                                                                        Text(
                                                                            text = stringResource(Res.string.everything_you_need),
                                                                            style = typo().titleSmall,
                                                                            fontWeight = FontWeight.Bold,
                                                                            color = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp),
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                            items(remainingResults) { result ->
                                                                Box(modifier = Modifier.animateItem()) {
                                                                    when (result) {
                                                                        is SongsResult -> {
                                                                            SongFullWidthItems(
                                                                                track = result.toTrack(),
                                                                                isPlaying = result.videoId == currentVideoId,
                                                                                modifier = Modifier,
                                                                                onMoreClickListener = {
                                                                                    onMoreClick(result.toTrack().toSongEntity())
                                                                                },
                                                                                onClickListener = {
                                                                                    val firstTrack = result.toTrack()
                                                                                    searchViewModel.setLastPlayedSearchTrack(firstTrack)
                                                                                    searchViewModel.insertSearchHistory(firstTrack.title)
                                                                                    if (searchText.isNotBlank() && searchText.trim() != firstTrack.title) {
                                                                                        searchViewModel.insertSearchHistory(searchText.trim())
                                                                                    }
                                                                                    searchViewModel.setQueueData(
                                                                                        QueueData.Data(
                                                                                            listTracks = arrayListOf(firstTrack),
                                                                                            firstPlayedTrack = firstTrack,
                                                                                            playlistId = "RDAMVM${result.videoId}",
                                                                                            playlistName =
                                                                                                "\"${searchText}\" ${
                                                                                                    getStringBlocking(
                                                                                                        Res.string.in_search,
                                                                                                    )
                                                                                                }",
                                                                                            playlistType = PlaylistType.RADIO,
                                                                                            continuation = null,
                                                                                        ),
                                                                                    )
                                                                                    searchViewModel.loadMediaItem(firstTrack, Config.SONG_CLICK)
                                                                                },
                                                                                onAddToQueue = {
                                                                                    sharedViewModel.addListToQueue(
                                                                                        arrayListOf(result.toTrack()),
                                                                                    )
                                                                                },
                                                                                selectionMode = selectionState.isActive,
                                                                                isSelected = selectionState.isSelected(result.videoId),
                                                                                onLongClick =
                                                                                    if (selectionState.isActive) {
                                                                                        { selectionState.toggle(it) }
                                                                                    } else {
                                                                                        { onMoreClick(result.toTrack().toSongEntity()) }
                                                                                    },
                                                                                onSelectToggle = { selectionState.toggle(it) },
                                                                            )
                                                                        }

                                                                        is VideosResult -> {
                                                                            SongFullWidthItems(
                                                                                track = result.toTrack(),
                                                                                isPlaying = result.videoId == currentVideoId,
                                                                                modifier = Modifier,
                                                                                onMoreClickListener = {
                                                                                    onMoreClick(result.toTrack().toSongEntity())
                                                                                },
                                                                                onClickListener = {
                                                                                    val firstTrack = result.toTrack()
                                                                                    searchViewModel.setLastPlayedSearchTrack(firstTrack)
                                                                                    searchViewModel.insertSearchHistory(firstTrack.title)
                                                                                    if (searchText.isNotBlank() && searchText.trim() != firstTrack.title) {
                                                                                        searchViewModel.insertSearchHistory(searchText.trim())
                                                                                    }
                                                                                    searchViewModel.setQueueData(
                                                                                        QueueData.Data(
                                                                                            listTracks = arrayListOf(firstTrack),
                                                                                            firstPlayedTrack = firstTrack,
                                                                                            playlistId = "RDAMVM${result.videoId}",
                                                                                            playlistName =
                                                                                                "\"${searchText}\" ${
                                                                                                    getStringBlocking(
                                                                                                        Res.string.in_search,
                                                                                                    )
                                                                                                }",
                                                                                            playlistType = PlaylistType.RADIO,
                                                                                            continuation = null,
                                                                                        ),
                                                                                    )
                                                                                    searchViewModel.loadMediaItem(firstTrack, Config.VIDEO_CLICK)
                                                                                },
                                                                                onAddToQueue = {
                                                                                    sharedViewModel.addListToQueue(
                                                                                        arrayListOf(result.toTrack()),
                                                                                    )
                                                                                },
                                                                                selectionMode = selectionState.isActive,
                                                                                isSelected = selectionState.isSelected(result.videoId),
                                                                                onLongClick =
                                                                                    if (selectionState.isActive) {
                                                                                        { selectionState.toggle(it) }
                                                                                    } else {
                                                                                        { onMoreClick(result.toTrack().toSongEntity()) }
                                                                                    },
                                                                                onSelectToggle = { selectionState.toggle(it) },
                                                                            )
                                                                        }

                                                                        is AlbumsResult -> {
                                                                            PlaylistFullWidthItems(
                                                                                data = result,
                                                                                onClickListener = {
                                                                                    navController.navigate(
                                                                                        AlbumDestination(
                                                                                            result.browseId,
                                                                                        ),
                                                                                    )
                                                                                },
                                                                            )
                                                                        }

                                                                        is ArtistsResult -> {
                                                                            ArtistFullWidthItems(
                                                                                data = result,
                                                                                onClickListener = {
                                                                                    navController.navigate(
                                                                                        ArtistDestination(
                                                                                            result.browseId,
                                                                                        ),
                                                                                    )
                                                                                },
                                                                            )
                                                                        }

                                                                        is PlaylistsResult -> {
                                                                            PlaylistFullWidthItems(
                                                                                data = result,
                                                                                onClickListener = {
                                                                                    if (result.resultType == "Podcast") {
                                                                                        navController.navigate(
                                                                                            PodcastDestination(
                                                                                                result.browseId,
                                                                                            ),
                                                                                        )
                                                                                    } else {
                                                                                        navController.navigate(
                                                                                            PlaylistDestination(
                                                                                                result.browseId,
                                                                                            ),
                                                                                        )
                                                                                    }
                                                                                },
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            // Space at bottom to account for bottom navigation and mini player
                                                            item { Spacer(modifier = Modifier.height(150.dp)) }
                                                        }
                                                        VerticalScrollBar(
                                                            scrollState = resultsState,
                                                            modifier = Modifier.padding(top = searchBarHeight, bottom = DesktopMiniPlayerClearance),
                                                        )
                                                    }
                                                } else {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = stringResource(Res.string.no_results_found),
                                                            style = typo().titleMedium,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.fillMaxWidth(),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is SearchScreenUIState.Error -> {
                                        Box {
                                            // Error state
                                            Column(
                                                modifier = Modifier.align(Alignment.Center),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Text(
                                                    text = stringResource(Res.string.error_occurred),
                                                    style = typo().titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Button(onClick = {
                                                    if (searchText.isNotEmpty()) {
                                                        searchViewModel.searchAll(searchText)
                                                    }
                                                }) {
                                                    Text(text = stringResource(Res.string.retry))
                                                }
                                            }
                                        }
                                    }

                                    SearchScreenUIState.Empty -> {
                                        // Empty state
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.no_results_found),
                                                style = typo().titleMedium,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { searchBarHeightPx = it.size.height },
        ) {
            // Animate only the background so scrolling keeps the search input and its focus.
            AnimatedVisibility(
                visible = !isContentAtTop,
                modifier = Modifier.matchParentSize(),
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
                label = "search_bar_scrim",
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .hazeBlur(HazeInput.Sources(hazeState), ultraThinBarStyle()),
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(vertical = 10.dp),
            ) {
        AnimatedVisibility(visible = selectionState.isActive) {
            SongSelectionTopAppBar(
                state = selectionState,
                onSelectAll = {
                    val visible =
                        when (searchScreenState.searchType) {
                            SearchType.SONGS -> searchScreenState.searchSongsResult.map { it.videoId }
                            SearchType.VIDEOS -> searchScreenState.searchVideosResult.map { it.videoId }
                            SearchType.ALL ->
                                searchScreenState.searchAllResult.mapNotNull {
                                    (it as? SongsResult)?.videoId ?: (it as? VideosResult)?.videoId
                                }
                            else -> emptyList()
                        }
                    selectionState.toggleSelectAll(visible)
                },
                onOpenActions = { showSelectionSheet = true },
                containerColor = Color.Transparent,
                // Zero here AND on the SearchBar below: the Column that holds them both consumes
                // the status bar once, for the whole stack. Leaving it on either child reserves it
                // a second time — which is the slab of padding this screen used to show.
                windowInsets = WindowInsets(0),
            )
        }
        // Search Bar with Animated Placeholder
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = searchText,
                    onQueryChange = { newText ->
                        searchText = newText
                    },
                    onSearch = { query ->
                        // A pasted YouTube link is a destination, not a query. Translating it into
                        // the app's own deep link hands it to the same intent flow that handles
                        // shared links, so it plays or opens straight away instead of being
                        // searched for as text. Anything else falls through to a normal search.
                        val deepLink = query.toAppDeepLinkOrNull()
                        if (deepLink != null) {
                            focusManager.clearFocus()
                            sharedViewModel.setIntent(GenericIntent(data = deepLink))
                        } else if (query.isNotEmpty()) {
                            isSearchSubmitted = true
                            focusManager.clearFocus()
                            searchViewModel.insertSearchHistory(query)
                            when (searchScreenState.searchType) {
                                SearchType.ALL -> searchViewModel.searchAll(query)
                                SearchType.SONGS -> searchViewModel.searchSongs(query)
                                SearchType.VIDEOS -> searchViewModel.searchVideos(query)
                                SearchType.ALBUMS -> searchViewModel.searchAlbums(query)
                                SearchType.ARTISTS -> searchViewModel.searchArtists(query)
                                SearchType.PLAYLISTS -> searchViewModel.searchPlaylists(query)
                                SearchType.FEATURED_PLAYLISTS -> searchViewModel.searchFeaturedPlaylist(query)
                                SearchType.PODCASTS -> searchViewModel.searchPodcast(query)
                            }
                        }
                    },
                    expanded = false,
                    onExpandedChange = {},
                    enabled = true,
                    placeholder = {
                        // Animated placeholder text
                        AnimatedContent(
                            targetState = currentPlaceholderIndex,
                            transitionSpec = {
                                (
                                    fadeIn(animationSpec = tween(500)) +
                                        slideInVertically { height -> height }
                                ).togetherWith(
                                    fadeOut(animationSpec = tween(500)) +
                                        slideOutVertically { height -> -height },
                                )
                            },
                            label = "placeholder_animation",
                        ) { index ->
                            Text(
                                text = placeholderTexts[index],
                                style = typo().labelMedium,
                            )
                        }
                    },
                    leadingIcon = {
                        Crossfade(
                            targetState = isSearchPanelVisible,
                            modifier = Modifier.size(48.dp),
                            animationSpec = tween(200),
                            label = "search_back_icon",
                        ) { showBack ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (showBack) {
                                    IconButton(
                                        onClick = dismissSearchPanel,
                                        enabled = isSearchPanelVisible,
                                    ) {
                                        Icon(
                                            imageVector = SimpIcons.ArrowBackIosNew,
                                            contentDescription = "Back",
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = SimpIcons.Search,
                                        contentDescription = "Search",
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = {
                        // X button only shows when there's text
                        if (searchText.isNotEmpty()) {
                            IconButton(
                                modifier = Modifier.clip(CircleShape),
                                onClick = {
                                    searchText = ""
                                    isSearchSubmitted = false
                                },
                            ) {
                                Icon(
                                    imageVector = SimpIcons.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        } else if (getPlatform() == Platform.Android) {
                            // An empty field offers song recognition instead (Android only).
                            IconButton(
                                modifier = Modifier.clip(CircleShape),
                                onClick = { showRecognition = true },
                            ) {
                                Icon(
                                    imageVector = SimpIcons.GraphicEq,
                                    contentDescription = stringResource(Res.string.recognize_song),
                                )
                            }
                        }
                    },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        isFocused = it.isFocused
                    }.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            // See the note on SongSelectionTopAppBar above — the Column owns the status-bar inset.
            windowInsets = WindowInsets(0),
            content = {},
        )
                // Filter chips ride along inside the blurred block instead of sitting in the
                // results branch. That way searchBarHeight covers them too, results scroll
                // underneath the whole thing, and the glass has something to blur.
                AnimatedVisibility(visible = searchUIType == SearchUIType.SEARCH_RESULTS) {
                    Row(
                        modifier =
                            Modifier
                                .horizontalScroll(chipRowState)
                                .padding(top = 10.dp)
                                .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchType.entries.forEach { id ->
                            val isSelected = id == searchScreenState.searchType
                            Chip(
                                isAnimated = uiState is SearchScreenUIState.Loading,
                                isSelected = isSelected,
                                text = stringResource(id.toStringRes()),
                                backdrop = chipBackdrop,
                            ) {
                                searchViewModel.setSearchType(id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SuggestItemRow(
    searchResult: SearchResultType,
    onItemClick: (SearchResultType) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onItemClick(searchResult) }
                .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val url =
            when (searchResult) {
                is SongsResult -> {
                    searchResult.thumbnails?.lastOrNull()?.url
                }

                is AlbumsResult -> {
                    searchResult.thumbnails.lastOrNull()?.url
                }

                is ArtistsResult -> {
                    searchResult.thumbnails.lastOrNull()?.url
                }

                is PlaylistsResult -> {
                    searchResult.thumbnails.lastOrNull()?.url
                }

                is VideosResult -> {
                    searchResult.thumbnails?.lastOrNull()?.url
                }

                else -> {
                    null
                }
            }

        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(url)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(url)
                        .crossfade(true)
                        .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(
                            if (searchResult is ArtistsResult) {
                                CircleShape
                            } else {
                                ArtworkShape
                            },
                        ),
            )
        }

        Spacer(modifier = Modifier.padding(horizontal = 12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val title =
                when (searchResult) {
                    is SongsResult -> {
                        searchResult.title
                    }

                    is AlbumsResult -> {
                        searchResult.title
                    }

                    is ArtistsResult -> {
                        searchResult.artist
                    }

                    is PlaylistsResult -> {
                        searchResult.title
                    }

                    is VideosResult -> {
                        searchResult.title
                    }

                    else -> {
                        null
                    }
                } ?: "Unknown"

            Text(
                text = title,
                style = typo().labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))

            val subtitle =
                when (searchResult) {
                    is SongsResult -> searchResult.artists?.map { it.name }?.connectArtists()
                    is AlbumsResult -> searchResult.artists.map { it.name }.connectArtists()
                    is PlaylistsResult -> searchResult.author.ifEmpty { "YouTube Music" }
                    is ArtistsResult -> stringResource(Res.string.artists)
                    is VideosResult -> searchResult.artists?.map { it.name }?.connectArtists()
                    else -> null
                } ?: "Unknown"

            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

enum class SearchUIType {
    EMPTY,
    SEARCH_HISTORY,
    SEARCH_SUGGESTIONS,
    SEARCH_RESULTS,
}

/**
 * TopResultHeroCard — Spotify-style elevated showcase card for the #1 search match.
 *
 * Displays artist avatar with verified badge, or album/track with rich artwork,
 * category pill, and primary play/shuffle action.
 */
@Composable
fun TopResultHeroCard(
    result: SearchResultType,
    isPlaying: Boolean,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (PlaylistsResult) -> Unit,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val thumb: String?
    val title: String
    val subtitle: String
    val categoryTag: String
    val isCircular: Boolean
    val onClick: () -> Unit

    when (result) {
        is ArtistsResult -> {
            thumb = result.thumbnails.lastOrNull()?.url
            title = result.artist
            subtitle = result.category.ifBlank { stringResource(Res.string.artists) }
            categoryTag = "ARTIST"
            isCircular = true
            onClick = { onArtistClick(result.browseId) }
        }
        is AlbumsResult -> {
            thumb = result.thumbnails.lastOrNull()?.url
            title = result.title
            val artistName = result.artists.firstOrNull()?.name ?: ""
            subtitle = if (result.year.isNotBlank()) "$artistName • ${result.year}" else artistName
            categoryTag = "ALBUM"
            isCircular = false
            onClick = { onAlbumClick(result.browseId) }
        }
        is SongsResult -> {
            thumb = result.thumbnails?.lastOrNull()?.url
            title = result.title ?: ""
            subtitle = result.artists?.mapNotNull { it.name }?.joinToString(", ") ?: ""
            categoryTag = "SONG"
            isCircular = false
            onClick = { onTrackClick(result.toTrack()) }
        }
        is VideosResult -> {
            thumb = result.thumbnails?.lastOrNull()?.url
            title = result.title ?: ""
            subtitle = result.artists?.mapNotNull { it.name }?.joinToString(", ") ?: ""
            categoryTag = "VIDEO"
            isCircular = false
            onClick = { onTrackClick(result.toTrack()) }
        }
        is PlaylistsResult -> {
            thumb = result.thumbnails.lastOrNull()?.url
            title = result.title
            subtitle = result.author
            categoryTag = if (result.resultType == "Podcast") "PODCAST" else "PLAYLIST"
            isCircular = false
            onClick = { onPlaylistClick(result) }
        }
        else -> return
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TOP RESULT",
                    style = typo().labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Category Pill Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = categoryTag,
                        style = typo().labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Hero Image / Avatar
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(if (isCircular) CircleShape else RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(thumb)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(thumb)
                            .crossfade(true)
                            .build(),
                        placeholder = rememberHolderPainter(),
                        error = rememberHolderPainter(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AudioPlayingIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Title & Subtitle Info
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = typo().titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = typo().bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Play / Action Floating Button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (result is ArtistsResult) SimpIcons.Shuffle else SimpIcons.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Responsive Visual Grid Card for Album Search Results.
 */
@Composable
fun SearchAlbumGridCard(
    album: AlbumsResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        val thumb = album.thumbnails.lastOrNull()?.url
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumb)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey(thumb)
                    .crossfade(true)
                    .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = typo().titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val artist = album.artists.firstOrNull()?.name ?: ""
        val subtitle = if (album.year.isNotBlank()) "$artist • ${album.year}" else artist
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = typo().bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Responsive Visual Grid Card for Artist Search Results.
 */
@Composable
fun SearchArtistGridCard(
    artist: ArtistsResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val thumb = artist.thumbnails.lastOrNull()?.url
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1f)
                .clip(CircleShape),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumb)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey(thumb)
                    .crossfade(true)
                    .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = artist.artist,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.artist,
            style = typo().titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.artists),
            style = typo().bodySmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Responsive Visual Grid Card for Playlist Search Results.
 */
@Composable
fun SearchPlaylistGridCard(
    playlist: PlaylistsResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        val thumb = playlist.thumbnails.lastOrNull()?.url
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumb)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey(thumb)
                    .crossfade(true)
                    .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = playlist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            style = typo().titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (playlist.author.isNotBlank()) {
            Text(
                text = playlist.author,
                style = typo().bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
