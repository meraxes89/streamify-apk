package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.MonthlyRecapItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.utils.LocalResource
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.ui.DesktopMiniPlayerClearance
import com.maxrave.simpmusic.expect.ui.VerticalScrollBar
import com.maxrave.simpmusic.extension.angledGradientBackground
import com.maxrave.simpmusic.extension.isScrollingUp
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.FormatListBulleted
import com.maxrave.simpmusic.ui.icon.GridView
import com.maxrave.simpmusic.ui.icon.LibraryMusic
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Sort
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.screen.library.LibraryDynamicPlaylistType
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.create
import kotlin.math.roundToInt

/**
 * Columns for every grid built on [GridLibraryPlaylist] — the Library tabs, Wrapped and Mix for you.
 *
 * The count is whichever brings the artwork closest to 150dp, so every cell on a page is the same
 * size and a wider window adds columns instead of stretching them. `Adaptive(minSize = 120.dp)`
 * treated 120 as a floor instead, which on a 1404dp window meant eleven columns of ~108dp artwork.
 *
 * Never fewer than three: the column count must follow the window, not the device's density, and a
 * 360–412dp phone would otherwise round down to two. Below 480dp this gives exactly the three columns
 * `Adaptive(120.dp)` did, so phones keep the grid they have.
 */
internal object LibraryGridCells : GridCells {
    // The artwork plus the 10dp start + 10dp end padding every item in these grids carries.
    private val TargetCellWidth = 150.dp + 20.dp

    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val target = TargetCellWidth.roundToPx() + spacing
        val count = ((availableSize + spacing).toFloat() / target).roundToInt().coerceAtLeast(3)
        // The same split GridCells.Fixed makes: leftover pixels go one each to the first columns.
        val usable = availableSize - spacing * (count - 1)
        return List(count) { usable / count + if (it < usable % count) 1 else 0 }
    }
}

internal enum class LibrarySortOption {
    DEFAULT,
    TITLE_AZ,
    TITLE_ZA,
}

internal fun extractLibraryItemTitle(item: Any?): String = when (item) {
    is AlbumEntity -> item.title
    is PlaylistEntity -> item.title
    is LocalPlaylistEntity -> item.title
    is PlaylistsResult -> item.title
    is PodcastsEntity -> item.title
    is ChartItem -> item.name
    is MonthlyRecapItem -> "Recap ${item.year}-${item.month}"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal inline fun <reified T> GridLibraryPlaylist(
    navController: NavController,
    contentPadding: PaddingValues,
    data: LocalResource<List<T>>,
    emptyText: StringResource,
    // Hoisted so a caller can read the scroll position itself — Mix for you derives its
    // top-bar frost from `index == 0 && offset == 0`, which the coarse onScrolling below
    // cannot say.
    state: LazyGridState = rememberLazyGridState(),
    noinline onScrolling: (onTop: Boolean) -> Unit = { _ -> },
    // A full-width block above the tiles, as a real grid item spanning every column — the same
    // mechanism the create tile and the chart button below already use. A caller drawing it in a
    // Box over the grid instead has to reserve its height in contentPadding and translate it by
    // the scroll offset by hand, which is what left a screen-tall hole above the Wrapped tab.
    noinline header: (@Composable () -> Unit)? = null,
    noinline createNewPlaylist: (() -> Unit)? = null,
    noinline onReload: () -> Unit,
) {
    Logger.w("GridLibraryPlaylist", "Generic Type: ${T::class.simpleName}")
    val isScrollingUp by state.isScrollingUp()

    var isGridView by rememberSaveable { mutableStateOf(true) }
    var sortOption by rememberSaveable { mutableStateOf(LibrarySortOption.DEFAULT) }

    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .collect {
                if (it <= 1) {
                    onScrolling.invoke(true)
                } else {
                    onScrolling.invoke(isScrollingUp)
                }
            }
    }
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        onRefresh = onReload,
        isRefreshing = data is LocalResource.Loading,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = data is LocalResource.Loading,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = contentPadding.calculateTopPadding(),
                        ),
                containerColor = PullToRefreshDefaults.indicatorContainerColor,
                color = PullToRefreshDefaults.indicatorColor,
                maxDistance = PullToRefreshDefaults.PositionalThreshold,
            )
        },
    ) {
        Crossfade(targetState = data) { data ->
            val rawList = (data as? LocalResource.Success)?.data ?: emptyList()
            val list = remember(rawList, sortOption) {
                when (sortOption) {
                    LibrarySortOption.DEFAULT -> rawList
                    LibrarySortOption.TITLE_AZ -> rawList.sortedBy { extractLibraryItemTitle(it).lowercase() }
                    LibrarySortOption.TITLE_ZA -> rawList.sortedByDescending { extractLibraryItemTitle(it).lowercase() }
                }
            }
            // A header counts as content: a tab whose list is empty but whose header is the
            // point of the tab must not fall through to the empty text and hide it.
            if ((data is LocalResource.Success && list.isNotEmpty()) || createNewPlaylist != null || header != null) {
                val onItemClick: (Any) -> Unit = { item ->
                    when (item) {
                        is ChartItem -> {
                            navController.navigate(
                                PlaylistDestination(
                                    playlistId = item.ytPlaylistId,
                                    isYourYouTubePlaylist = false,
                                ),
                            )
                        }

                        is LocalPlaylistEntity -> {
                            navController.navigate(
                                LocalPlaylistDestination(
                                    item.id,
                                ),
                            )
                        }

                        is PlaylistsResult -> {
                            navController.navigate(
                                PlaylistDestination(
                                    item.browseId,
                                    isYourYouTubePlaylist = true,
                                ),
                            )
                        }

                        is AlbumEntity -> {
                            navController.navigate(
                                AlbumDestination(
                                    item.browseId,
                                ),
                            )
                        }

                        is PlaylistEntity -> {
                            navController.navigate(
                                PlaylistDestination(
                                    item.id,
                                ),
                            )
                        }

                        is PodcastsEntity -> {
                            navController.navigate(
                                PodcastDestination(
                                    podcastId = item.podcastId,
                                ),
                            )
                        }

                        // The recap is not a stored playlist — it is a query over
                        // `playback_event` — so it opens the dynamic playlist screen,
                        // which rebuilds it from the year and month carried here.
                        is MonthlyRecapItem -> {
                            navController.navigate(
                                LibraryDynamicPlaylistDestination(
                                    type =
                                        LibraryDynamicPlaylistType
                                            .MonthlyRecap(
                                                year = item.year,
                                                month = item.month,
                                            ).toStringParams(),
                                ),
                            )
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = if (isGridView) LibraryGridCells else GridCells.Fixed(1),
                    contentPadding = contentPadding,
                    state = state,
                ) {
                    if (header != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            header()
                        }
                    }

                    if (list.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                            .border(
                                                width = 0.75.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(20.dp),
                                            )
                                            .clickable {
                                                sortOption = when (sortOption) {
                                                    LibrarySortOption.DEFAULT -> LibrarySortOption.TITLE_AZ
                                                    LibrarySortOption.TITLE_AZ -> LibrarySortOption.TITLE_ZA
                                                    LibrarySortOption.TITLE_ZA -> LibrarySortOption.DEFAULT
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = SimpIcons.Sort,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint =
                                            if (sortOption != LibrarySortOption.DEFAULT) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Text(
                                        text =
                                            when (sortOption) {
                                                LibrarySortOption.DEFAULT -> "${list.size} items"
                                                LibrarySortOption.TITLE_AZ -> "A → Z"
                                                LibrarySortOption.TITLE_ZA -> "Z → A"
                                            },
                                        style = typo().labelMedium,
                                        color =
                                            if (sortOption != LibrarySortOption.DEFAULT) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }

                                Box(
                                    modifier =
                                        Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                            .border(
                                                width = 0.75.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                                shape = CircleShape,
                                            )
                                            .clickable {
                                                isGridView = !isGridView
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isGridView) SimpIcons.FormatListBulleted else SimpIcons.GridView,
                                        contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (createNewPlaylist != null) {
                        item(span = { if (isGridView) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
                            if (isGridView) {
                                Box(
                                    modifier =
                                        Modifier.clickable {
                                            createNewPlaylist()
                                        },
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .padding(10.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                                                    shape = RoundedCornerShape(12.dp),
                                                ),
                                            Alignment.Center,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    modifier = Modifier.size(28.dp),
                                                    imageVector = SimpIcons.Add,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    contentDescription = null,
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(Res.string.create),
                                            style = typo().titleSmall,
                                            // Sits on the page background, not on the tile, so it has
                                            // to follow the theme — hard-coded white vanished in light
                                            // theme while every other tile label stayed readable.
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .wrapContentHeight(align = Alignment.CenterVertically)
                                                    .padding(top = 8.dp)
                                                    .basicMarquee(
                                                        iterations = Int.MAX_VALUE,
                                                        animationMode = MarqueeAnimationMode.Immediately,
                                                    ).focusable(),
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { createNewPlaylist() }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(52.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .border(
                                                    width = 0.75.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                                                    shape = RoundedCornerShape(10.dp),
                                                ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                modifier = Modifier.size(22.dp),
                                                imageVector = SimpIcons.Add,
                                                tint = MaterialTheme.colorScheme.primary,
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(Res.string.create),
                                        style = typo().titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    items(list) { item ->
                        if (item !is PlaylistType) {
                            return@items
                        }
                        if (isGridView) {
                            HomeItemContentPlaylist(
                                onClick = { onItemClick(item) },
                                data = item,
                                thumbSize = 132.dp,
                                // The cell decides the width now, so the artwork follows it instead of
                                // being pinned to thumbSize and cropped by a narrower cell.
                                fillMaxWidth = true,
                            )
                        } else {
                            PlaylistFullWidthItems(
                                data = item,
                                onClickListener = { onItemClick(item) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val uriHandler = LocalUriHandler.current
                        SimpMusicChartButton(
                            modifier =
                                Modifier.wrapContentWidth().padding(
                                    vertical = 16.dp,
                                ),
                            onClick = {
                                uriHandler.openUri("https://chart.simpmusic.org")
                            },
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EndOfPage()
                    }
                }
                VerticalScrollBar(
                    scrollState = state,
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding(), bottom = DesktopMiniPlayerClearance),
                )
            } else if (data is LocalResource.Loading) {
                CenterLoadingBox(
                    Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = SimpIcons.LibraryMusic,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            )
                        }
                        Text(
                            text = stringResource(emptyText),
                            style = typo().titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Playlists and items you save will appear here for quick access",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}