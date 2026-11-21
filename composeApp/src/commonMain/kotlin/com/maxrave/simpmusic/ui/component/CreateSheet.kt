package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kyant.backdrop.highlight.Highlight
import com.maxrave.domain.repository.ListenTogetherRepository
import com.maxrave.simpmusic.expect.ui.PlatformBackdrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.maxrave.simpmusic.data.scraper.ImportStage
import com.maxrave.simpmusic.data.scraper.PlaylistImportManager
import com.maxrave.simpmusic.data.scraper.PlaylistImportProgress
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.Groups
import com.maxrave.simpmusic.ui.icon.PlaylistAdd
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.Settings
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.ui.theme.LocalLiquidGlassEnabled
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.create
import simpmusic.composeapp.generated.resources.create_playlist_description
import simpmusic.composeapp.generated.resources.create_room_description
import simpmusic.composeapp.generated.resources.create_room_live
import simpmusic.composeapp.generated.resources.listen_together
import simpmusic.composeapp.generated.resources.local_playlist
import simpmusic.composeapp.generated.resources.playlist_name
import simpmusic.composeapp.generated.resources.playlist_name_cannot_be_empty
import simpmusic.composeapp.generated.resources.search
import simpmusic.composeapp.generated.resources.settings

/**
 * Unified Header Actions: Milled-Glass Control Island [ Search | Create | Settings ]
 *
 * Encapsulates Search, Create, and Settings into a unified, zero-CPU hardware-accelerated
 * glass capsule with distinct touch sectors and subtle hairline dividers.
 */
@Composable
fun CreateActions(
    backdrop: PlatformBackdrop,
    navController: NavController,
    onSettings: (() -> Unit)? = null,
    sharedViewModel: SharedViewModel = koinInject(),
) {
    val room by koinInject<ListenTogetherRepository>().room.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var showPlaylistName by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onBackground

    val searchButton: @Composable () -> Unit = {
        IconButton(
            onClick = {
                navController.navigate(SearchDestination) { launchSingleTop = true }
            },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                SimpIcons.Search,
                contentDescription = stringResource(Res.string.search),
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }

    val createButton: @Composable () -> Unit = {
        Box(
            modifier = Modifier.size(38.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { showCreate = true },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    SimpIcons.Add,
                    contentDescription = stringResource(Res.string.create),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Listen Together active live-room indicator
            if (room.inRoom) LiveRoomDot()
        }
    }

    val settingsButton: @Composable () -> Unit = {
        if (onSettings != null) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    SimpIcons.Settings,
                    contentDescription = stringResource(Res.string.settings),
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }

    val divider: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .width(0.75.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        )
    }

    val islandContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            searchButton()
            divider()
            createButton()
            if (onSettings != null) {
                divider()
                settingsButton()
            }
        }
    }

    if (LocalLiquidGlassEnabled.current) {
        Row(
            modifier =
                Modifier
                    .height(40.dp)
                    .liquidGlass(
                        backdrop,
                        RoundedCornerShape(20.dp),
                        highlight = if (onSettings == null) Highlight(width = 1.dp) else Highlight.Default,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            islandContent()
        }
    } else {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
            modifier = Modifier.height(40.dp),
        ) {
            islandContent()
        }
    }

    if (showCreate) {
        CreateSheet(
            liveRoom = room.inRoom,
            onDismiss = { showCreate = false },
            onPlaylist = {
                showCreate = false
                showPlaylistName = true
            },
            onListenTogether = {
                showCreate = false
                navController.navigate(ListenTogetherDestination)
            },
        )
    }
    if (showPlaylistName) {
        CreateLocalPlaylistSheet(
            onDismiss = { showPlaylistName = false },
            onCreate = sharedViewModel::createLocalPlaylist,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSheet(
    liveRoom: Boolean,
    onDismiss: () -> Unit,
    onPlaylist: () -> Unit,
    onListenTogether: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                SheetHandle()
                Text(
                    text = stringResource(Res.string.create),
                    style = typo().titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                CreateRow(
                    icon = SimpIcons.PlaylistAdd,
                    title = stringResource(Res.string.local_playlist),
                    subtitle = stringResource(Res.string.create_playlist_description),
                    onClick = onPlaylist,
                )
                CreateRow(
                    icon = SimpIcons.Groups,
                    title = stringResource(Res.string.listen_together),
                    subtitle = stringResource(if (liveRoom) Res.string.create_room_live else Res.string.create_room_description),
                    onClick = onListenTogether,
                )
                EndOfModalBottomSheet()
            }
        }
    }
}

@Composable
private fun CreateRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Column {
            Text(text = title, style = typo().titleSmall)
            Text(text = subtitle, style = typo().bodySmall)
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(5.dp))
            Card(
                modifier =
                    Modifier
                        .width(60.dp)
                        .height(4.dp),
                colors =
                    CardDefaults.cardColors().copy(
                        containerColor = MaterialTheme.colorScheme.outline,
                    ),
                shape = RoundedCornerShape(50),
            ) {}
            Spacer(modifier = Modifier.height(5.dp))
        }
    }
}

/** Name a new local playlist or import from Spotify / Apple Music / YouTube link. Shared by Library's create tile and the Create sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLocalPlaylistSheet(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    sharedViewModel: SharedViewModel = koinInject(),
    playlistImportManager: PlaylistImportManager = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var newTitle by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf<PlaylistImportProgress?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            colors = CardDefaults.cardColors().copy(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
            ) {
                SheetHandle()

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { if (!isImporting) selectedTab = 0 },
                        text = {
                            Text(
                                text = "New Playlist",
                                style = typo().titleSmall,
                                color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { if (!isImporting) selectedTab = 1 },
                        text = {
                            Text(
                                text = "Import Link",
                                style = typo().titleSmall,
                                color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { s -> newTitle = s },
                        label = {
                            Text(text = stringResource(Res.string.playlist_name))
                        },
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (newTitle.isBlank()) {
                                coroutineScope.launch { sharedViewModel.makeToast(getString(Res.string.playlist_name_cannot_be_empty)) }
                            } else {
                                onCreate(newTitle)
                                coroutineScope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    ) {
                        Text(text = stringResource(Res.string.create))
                    }
                } else {
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { s -> importUrl = s },
                        label = {
                            Text(text = "Spotify, YouTube, or Apple Music link")
                        },
                        singleLine = true,
                        enabled = !isImporting,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val progress = importProgress
                    if (progress != null) {
                        val statusText = when (progress.stage) {
                            ImportStage.SCRAPING -> "Scraping playlist metadata..."
                            ImportStage.RESOLVING -> "Matching audio (${progress.resolvedTracks}/${progress.totalTracks}): ${progress.currentTrackTitle}"
                            ImportStage.SAVING -> "Saving playlist to library..."
                            ImportStage.COMPLETED -> "Import complete! (${progress.resolvedTracks} tracks added)"
                            ImportStage.ERROR -> progress.errorMessage ?: "Import failed"
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = statusText,
                                style = typo().bodySmall,
                                color = if (progress.stage == ImportStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    TextButton(
                        onClick = {
                            if (importUrl.isBlank()) {
                                coroutineScope.launch { sharedViewModel.makeToast("Please paste a playlist URL") }
                                return@TextButton
                            }
                            isImporting = true
                            coroutineScope.launch {
                                playlistImportManager.importPlaylistFromUrl(importUrl).collect { update ->
                                    importProgress = update
                                    if (update.stage == ImportStage.COMPLETED) {
                                        isImporting = false
                                        sharedViewModel.makeToast("Playlist imported to library")
                                        delay(1000)
                                        sheetState.hide()
                                        onDismiss()
                                    } else if (update.stage == ImportStage.ERROR) {
                                        isImporting = false
                                    }
                                }
                            }
                        },
                        enabled = !isImporting && importUrl.isNotBlank(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    ) {
                        Text(text = if (isImporting) "Importing..." else "Import to Library")
                    }
                }
                EndOfModalBottomSheet()
            }
        }
    }
}
