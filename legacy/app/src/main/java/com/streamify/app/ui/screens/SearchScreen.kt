package com.streamify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.ui.components.*
import com.streamify.app.ui.components.yt.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.streamify.app.ui.components.LocalDockPosition
import com.streamify.app.ui.components.LocalQuantumController
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.*
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    viewModel: SearchViewModel = viewModel(),
    ingestionViewModel: IngestionViewModel = viewModel(),
    initialQuery: String = "",
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    val playerIsPlaying = playerState.isPlaying
    val resolvingTrackUrl by viewModel.resolvingTrackUrl.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()

    // Gap #32 Listen Along deep-links here with the friend's track as a
    // prefilled query so one tap lands on the exact song.
    var query by remember { mutableStateOf(initialQuery) }
    var selectedFilter by remember { mutableStateOf("All") }

    val contextMenuController = LocalContextMenuController.current

    // --- PILLAR 1: Deterministic Search State Unwinding ---
    BackHandler(enabled = query.isNotBlank()) {
        query = ""
    }

    val quantumController = LocalQuantumController.current
    val dockPositionState = LocalDockPosition.current

    // Lifecycle-Aware Native Priority Manager: Drops to power-saving mode when navigating away
    DisposableEffect(Unit) {
        com.streamify.app.data.NativeBridge.setHighPriorityActive(true)
        onDispose {
            com.streamify.app.data.NativeBridge.setHighPriorityActive(false)
        }
    }

    LaunchedEffect(context) {
        viewModel.init(context)
    }

    // 120fps Debounce Engine: Cancels rapid keystroke jobs
    LaunchedEffect(query, selectedFilter) {
        if (query.isNotBlank()) {
            delay(150)
            viewModel.search(query, selectedFilter)
            viewModel.updateSuggestions(query)
        } else {
            viewModel.updateSuggestions("")
        }
    }

    val genres = remember {
        listOf(
            "Chill & Lofi" to Color(0xFF3EA6FF),
            "Energy & Gym" to Color(0xFFFF0000),
            "Deep Focus" to Color(0xFF8D67AB),
            "Indie & Rock" to Color(0xFFE91E63),
            "Hip-Hop" to Color(0xFFFF9800),
            "Electronic" to Color(0xFF00E676),
            "Pop" to Color(0xFFFF4081),
            "Acoustic & Folk" to Color(0xFF8D6E63)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Omnibar & Branding Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YtSearchOmnibar(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.weight(1f)
            )
            if (query.isBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                SireenBrandingBadge()
            }
        }

        // 2. Search Category Filter Chips (When Query is Active)
        if (query.isNotBlank()) {
            YtSearchFilterChips(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it }
            )
        }

        // Suggestions Rail with 1-Tap Quick-Play Action
        if (query.isNotBlank() && searchSuggestions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchSuggestions, key = { it }) { suggestion ->
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            query = suggestion
                            viewModel.search(suggestion)
                            viewModel.updateSuggestions("")
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = suggestion,
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                color = TextMain
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable {
                                        com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                                        query = suggestion
                                        viewModel.search(suggestion)
                                        viewModel.updateSuggestions("")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Quick Play",
                                    tint = Primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Main Viewport
        if (query.isBlank()) {
            // ==========================================
            // STATE A: EXPLORE & BROWSE HUB
            // ==========================================
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // Recent Searches
                if (searchHistory.isNotEmpty()) {
                    item(key = "header_recent") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent searches",
                                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                color = TextMain
                            )
                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text(
                                    text = "Clear",
                                    style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    items(searchHistory.take(5), key = { it }) { pastQuery ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = pastQuery
                                    viewModel.search(pastQuery)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = pastQuery,
                                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                color = TextMain
                            )
                        }
                    }
                }

                // Moods & Genres
                item(key = "header_genres", contentType = "header") {
                    YtSectionHeader(
                        title = "Moods & Genres",
                        kicker = "Explore Categories"
                    )
                }

                item(key = "genre_grid", contentType = "genre_grid") {
                    val screenConfig = LocalScreenConfiguration.current
                    val genreColumns = remember(screenConfig.widthDp) {
                        ((screenConfig.widthDp.value / 158f).toInt()).coerceAtLeast(2)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genres.chunked(genreColumns).forEach { rowGenres ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowGenres.forEach { (genreTitle, accentColor) ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        YtGenreCard(
                                            title = genreTitle,
                                            accentColor = accentColor,
                                            onClick = {
                                                query = genreTitle
                                                viewModel.search(genreTitle)
                                            }
                                        )
                                    }
                                }
                                if (rowGenres.size < genreColumns) {
                                    repeat(genreColumns - rowGenres.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // STATE B: SEARCH RESULTS HUB
            // ==========================================
            when (val state = uiState) {
                is SearchUiState.Idle -> {
                    // Search idle state
                }
                is SearchUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                is SearchUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = state.message,
                            color = Primary,
                            style = LocalAppTypography.current.songArtist
                        )
                    }
                }
                is SearchUiState.Success -> {
                    val localMatches = state.localResults
                    val onlineMatches = state.onlineResults

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        // 1. Library Songs (Compact Preview: Max 2-3 items so online results are never pushed down)
                        if (localMatches.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Songs")) {
                            val previewLocal = localMatches.take(3)
                            item(key = "header_local_songs", contentType = "header") {
                                YtSectionHeader(
                                    title = "From your library",
                                    kicker = if (localMatches.size > 3) "${localMatches.size} Matches • Showing top 3" else "${localMatches.size} Tracks Found"
                                )
                            }
                            items(
                                items = previewLocal,
                                key = { "local_${it.id}" },
                                contentType = { "trackRow" }
                            ) { track ->
                                YtQueueTrackItem(
                                    track = track,
                                    isPlaying = currentTrack?.id == track.id,
                                    isActuallyPlaying = playerIsPlaying,
                                    showDragHandle = false,
                                    onClick = { onTrackClick(track, localMatches) },
                                    onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.SEARCH) }
                                )
                            }
                        }


                        // 3. Online Shimmer Loading Indicator
                        if (state.isOnlineLoading) {
                            item(key = "header_online_loading", contentType = "header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = Primary,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Searching Streamify Cloud...",
                                        style = LocalAppTypography.current.headlineMedium.copy(fontSize = 15.sp),
                                        color = Primary
                                    )
                                }
                            }
                            items(4) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BgSurfaceElevated)
                                )
                            }
                        }

                        // 4. Online Streamify Results
                        if (onlineMatches.isNotEmpty()) {
                            item(key = "header_online_results", contentType = "header") {
                                val categoryHeader = when (selectedFilter) {
                                    "Artists" -> "Artists"
                                    "Albums" -> "Albums & EPs"
                                    "Playlists" -> "Featured & Community Playlists"
                                    "Videos" -> "Music Videos"
                                    "Songs" -> "Studio Tracks"
                                    else -> "Online Results"
                                }
                                YtSectionHeader(
                                    title = categoryHeader,
                                    kicker = "Instant High-Fidelity Stream"
                                )
                            }
                            itemsIndexed(
                                items = onlineMatches,
                                key = { index, it -> "online_${it.url}_$index" },
                                contentType = { _, it -> "trackRow_${it.type.name}" }
                            ) { index, onlineTrack ->
                                when (onlineTrack.type) {
                                    com.streamify.app.viewmodel.SearchResultType.ARTIST -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    query = onlineTrack.title
                                                    selectedFilter = "Songs"
                                                    viewModel.search(onlineTrack.title, "Songs")
                                                }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(54.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                            ) {
                                                YtThumbnail(
                                                    url = onlineTrack.thumbnail,
                                                    size = 54.dp,
                                                    cornerRadius = 27.dp,
                                                    title = onlineTrack.title,
                                                    artist = onlineTrack.uploader
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = onlineTrack.title,
                                                    style = LocalAppTypography.current.songTitle.copy(
                                                        fontSize = 15.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                    ),
                                                    color = TextMain,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Artist • Tap to explore songs",
                                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                    color = Primary,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    com.streamify.app.viewmodel.SearchResultType.ALBUM, com.streamify.app.viewmodel.SearchResultType.PLAYLIST -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    query = "${onlineTrack.title} ${onlineTrack.uploader}".trim()
                                                    selectedFilter = "Songs"
                                                    viewModel.search(query, "Songs")
                                                }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            YtThumbnail(
                                                url = onlineTrack.thumbnail,
                                                size = 54.dp,
                                                cornerRadius = 6.dp,
                                                title = onlineTrack.title,
                                                artist = onlineTrack.uploader
                                            )
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = onlineTrack.title,
                                                    style = LocalAppTypography.current.songTitle.copy(
                                                        fontSize = 15.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                    ),
                                                    color = TextMain,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                val badge = if (onlineTrack.type == com.streamify.app.viewmodel.SearchResultType.ALBUM) "Album" else "Playlist"
                                                Text(
                                                    text = "$badge • ${onlineTrack.uploader}",
                                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        val videoId = YouTubeStreamResolver.extractVideoId(onlineTrack.url, onlineTrack.thumbnail)
                                        val watchUrl = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else onlineTrack.url
                                        val isResolving = resolvingTrackUrl == onlineTrack.url
                                        val trackModel = Track(
                                            id = -(onlineTrack.url.hashCode() and 0x7FFFFFFF),
                                            title = onlineTrack.title,
                                            artist = onlineTrack.uploader,
                                            album = if (onlineTrack.type == com.streamify.app.viewmodel.SearchResultType.VIDEO) "Music Video" else "Streamify Cloud",
                                            filepath = watchUrl,
                                            durationSec = onlineTrack.duration,
                                            bpm = 0f,
                                            coverArtPath = onlineTrack.thumbnail,
                                            lyricsPath = null,
                                            source = "online_stream",
                                            ytmVideoId = videoId
                                        )

                                        var itemWindowPos by remember { mutableStateOf(Offset.Zero) }

                                        YtQueueTrackItem(
                                            track = trackModel,
                                            isPlaying = isResolving || (currentTrack?.filepath == watchUrl || (videoId != null && currentTrack?.ytmVideoId == videoId)),
                                            showDragHandle = false,
                                            modifier = Modifier.onGloballyPositioned { coords ->
                                                if (coords.isAttached) {
                                                    itemWindowPos = coords.positionInWindow()
                                                }
                                            },
                                            onClick = {
                                                val dockPos = dockPositionState.value
                                                val density = context.resources.displayMetrics.density
                                                val screenWidthPx = context.resources.displayMetrics.widthPixels.toFloat()
                                                val screenHeightPx = context.resources.displayMetrics.heightPixels.toFloat()

                                                val tapOrigin = if (itemWindowPos != Offset.Zero) {
                                                    Offset(
                                                        x = (itemWindowPos.x + (screenWidthPx * 0.44f)).coerceIn(50f, screenWidthPx - 50f),
                                                        y = (itemWindowPos.y + (28f * density)).coerceIn(50f, screenHeightPx - 50f)
                                                    )
                                                } else {
                                                    Offset(screenWidthPx / 2f, 300f * density)
                                                }

                                                val target = if (dockPos != Offset.Zero) {
                                                    dockPos
                                                } else {
                                                    Offset(screenWidthPx / 2f, screenHeightPx - (80f * density))
                                                }

                                                quantumController.triggerFlight(
                                                    tapOrigin = tapOrigin,
                                                    dockDestination = target,
                                                    title = trackModel.title,
                                                    artist = trackModel.artist,
                                                    art = trackModel.coverArtPath
                                                )
                                                onTrackClick(trackModel, listOf(trackModel))
                                            },
                                            onMoreClick = { contextMenuController.show(trackModel, origin = MenuOrigin.SEARCH) }
                                        )
                                    }
                                }
                            }
                        }

                        // 5. Empty Result State
                        if (!state.isOnlineLoading && localMatches.isEmpty() && onlineMatches.isEmpty()) {
                            item(key = "empty_results") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "No results found for \"$query\"",
                                            style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                            color = TextMain
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Try searching for a different song or artist",
                                            style = LocalAppTypography.current.songArtist,
                                            color = TextSecondary
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
}
