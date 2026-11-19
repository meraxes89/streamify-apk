package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.ui.components.StreamifyPullToRefreshContainer
import com.streamify.app.ui.components.yt.YtPlaylistHeroHeader
import com.streamify.app.ui.components.yt.YtQueueTrackItem
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    playlistDescription: String,
    playlistTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    StreamifyPullToRefreshContainer(
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                try {
                    // PlaylistRepository.refresh parses playlists.json — arbitrary-size
                    // JSON parsing must stay off the main thread.
                    withContext(Dispatchers.IO) { PlaylistRepository.refresh() }
                } catch (e: Exception) {
                    com.streamify.app.util.SLog.st("AlbumScreen", "pull-to-refresh playlist reload failed", e)
                }
                delay(400)
                isRefreshing = false
            }
        }
    ) {
        AlbumScreen(
            albumName = playlistName,
            allTracks = playlistTracks,
            playerViewModel = playerViewModel,
            onBack = onBack,
            onTrackClick = onTrackClick,
            explicitTracks = playlistTracks,
            headerTitle = playlistName,
            headerSubtitle = if (playlistDescription.isNotBlank()) playlistDescription else "${playlistTracks.size} songs",
            playlistId = playlistId
        )
    }
}

@Composable
fun AlbumScreen(
    albumName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    explicitTracks: List<Track>? = null,
    headerTitle: String? = null,
    headerSubtitle: String? = null,
    playlistId: String? = null
) {
    val context = LocalContext.current
    val albumTracks = remember(albumName, allTracks, explicitTracks) {
        explicitTracks ?: allTracks.filter { it.album.equals(albumName, ignoreCase = true) }
    }
    val displayTitle = headerTitle ?: albumName
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val contextMenuController = LocalContextMenuController.current
    var isRadioDiscoveryMode by rememberSaveable { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(displayTitle) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val firstTrack = albumTracks.firstOrNull()

    // ── Phase 3: in-playlist bulk edit + custom cover cropper ─────────────
    var bulkEditMode by rememberSaveable { mutableStateOf(false) }
    // Copy-on-write set: mutating a MutableSet inside mutableStateOf would
    // never invalidate readers — always assign a NEW set instead.
    var selectedTrackIds by remember { mutableStateOf(setOf<Int>()) }
    var showCoverCropper by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    // Phase 3 — wave QR share card for playlist/album.
    var showQrShareCard by remember { mutableStateOf(false) }
    val allPlaylists by PlaylistRepository.playlists.collectAsState()

    fun exitBulkEdit() {
        bulkEditMode = false
        selectedTrackIds = emptySet()
    }

    fun toggleSelected(trackId: Int) {
        selectedTrackIds = if (trackId in selectedTrackIds) {
            selectedTrackIds - trackId
        } else {
            selectedTrackIds + trackId
        }
    }

    androidx.activity.compose.BackHandler(enabled = bulkEditMode) {
        exitBulkEdit()
    }

    // Phase 3 — playlist/album wave QR share card.
    if (showQrShareCard) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQrShareCard = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val isPlaylist = explicitTracks != null
            com.streamify.app.ui.components.QrShareCard(
                kindLabel = if (isPlaylist) "PLAYLIST" else "ALBUM",
                title = displayTitle,
                subtitle = if (isPlaylist) "${albumTracks.size} songs" else (firstTrack?.artist ?: "Unknown Artist"),
                artworkUrl = firstTrack?.coverArtPath,
                link = if (isPlaylist && playlistId != null) {
                    com.streamify.app.ui.components.ShareLinkBuilder.playlistLink(playlistId)
                } else {
                    com.streamify.app.ui.components.ShareLinkBuilder.albumLink(displayTitle)
                },
                modifier = Modifier.padding(16.dp),
                onDismiss = { showQrShareCard = false }
            )
        }
    }

    if (showCoverCropper && playlistId != null) {
        com.streamify.app.ui.components.CoverCropperSheet(
            playlistName = displayTitle,
            onDismiss = { showCoverCropper = false },
            onCoverReady = { coverFile, _ ->
                PlaylistRepository.updatePlaylistCover(playlistId, coverFile.absolutePath)
                showCoverCropper = false
            }
        )
    }

    if (showAddToPlaylistDialog && playlistId != null && selectedTrackIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialog = false },
            title = { Text("Add ${selectedTrackIds.size} songs to…", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    allPlaylists.filter { !it.isDeleted && it.id != playlistId }.forEach { target ->
                        Text(
                            text = "• ${target.name} (${target.trackIds.size} songs)",
                            color = TextMain,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTrackIds.forEach { tid ->
                                        PlaylistRepository.addTrackToPlaylist(target.id, tid)
                                    }
                                    showAddToPlaylistDialog = false
                                    exitBulkEdit()
                                    android.widget.Toast.makeText(context, "Added to ${target.name}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp)
                        )
                    }
                    if (allPlaylists.all { it.isDeleted }) {
                        Text("No other playlists yet.", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddToPlaylistDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 120fps GPU Parallax Calculator
    val scrollOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f // Fully collapsed
            }
        }
    }

    if (showRenameDialog && playlistId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Playlist Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderChip,
                        cursorColor = Primary,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        PlaylistRepository.renamePlaylist(playlistId, renameText)
                    }
                    showRenameDialog = false
                }) {
                    Text("Save", color = Primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDeleteConfirmDialog && playlistId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Playlist?", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete \"$displayTitle\"? Songs will remain in your library.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistRepository.deletePlaylist(playlistId)
                    showDeleteConfirmDialog = false
                    onBack()
                }) {
                    Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFFF453A), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (explicitTracks != null) "Playlist" else "Album",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            if (playlistId != null && playlistId != "liked_songs") {
                Box {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Playlist Options",
                            tint = TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        modifier = Modifier.background(BgSurfaceElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename Playlist", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                renameText = displayTitle
                                showRenameDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = TextMain) }
                        )
                        // Phase 3 — custom square cover cropper w/ palette glow
                        DropdownMenuItem(
                            text = { Text("Edit Cover", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                showCoverCropper = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null, tint = TextMain) }
                        )
                        // Phase 3 — in-playlist bulk edit entry
                        DropdownMenuItem(
                            text = { Text("Select Songs", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                bulkEditMode = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null, tint = TextMain) }
                        )
                        // Phase 3 — wave QR share card
                        DropdownMenuItem(
                            text = { Text("Share QR Card", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                showQrShareCard = true
                            },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null, tint = TextMain) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to M3U8", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                coroutineScope.launch {
                                    val file = PlaylistRepository.exportPlaylistToM3U8(playlistId, albumTracks, context)
                                    if (file != null) {
                                        android.widget.Toast.makeText(context, "Exported: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null, tint = TextMain) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Playlist", color = androidx.compose.ui.graphics.Color(0xFFFF453A)) },
                            onClick = {
                                showOptionsMenu = false
                                showDeleteConfirmDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFF453A)) }
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protect docked player
        ) {
            // 1. Parallax Collapsing Hero Header
            item(key = "album_hero_header", contentType = "heroHeader") {
                val displaySubtitle = headerSubtitle ?: "Album • ${firstTrack?.artist ?: "Unknown Artist"} • ${albumTracks.size} songs"
                YtPlaylistHeroHeader(
                    title = displayTitle,
                    subtitle = displaySubtitle,
                    artworkUrl = firstTrack?.coverArtPath,
                    scrollOffset = scrollOffset,
                    isRadioDiscoveryMode = isRadioDiscoveryMode,
                    onToggleRadioDiscoveryMode = { isRadioDiscoveryMode = !isRadioDiscoveryMode },
                    onPlay = {
                        if (albumTracks.isNotEmpty()) {
                            if (isRadioDiscoveryMode) {
                                playerViewModel.playSingleTrack(albumTracks.first())
                            } else {
                                onTrackClick(albumTracks.first(), albumTracks)
                            }
                        }
                    },
                    onShuffle = {
                        if (albumTracks.isNotEmpty()) {
                            val shuffled = albumTracks.shuffled()
                            playerViewModel.playCollection(shuffled, 0)
                        }
                    },
                    onExportM3u = {
                        CoroutineScope(Dispatchers.Main).launch {
                            val file = PlaylistRepository.exportPlaylistToM3U8("album_$albumName", albumTracks, context)
                            if (file != null) {
                                android.widget.Toast.makeText(context, "Exported: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            // 2. Tracklist Items
            items(
                items = albumTracks,
                key = { "album_track_${it.id}" },
                contentType = { "trackRow" }
            ) { track ->
                if (bulkEditMode && playlistId != null) {
                    // ── Phase 3: bulk-selection row ──────────────────────
                    val isSelected = track.id in selectedTrackIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggleSelected(track.id) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { _ -> toggleSelected(track.id) },
                            colors = CheckboxDefaults.colors(checkedColor = Primary)
                        )
                        YtQueueTrackItem(
                            track = track,
                            isPlaying = false,
                            showDragHandle = false,
                            dimmed = true,
                            onClick = { toggleSelected(track.id) },
                            onMoreClick = { }
                        )
                    }
                } else {
                    YtQueueTrackItem(
                        track = track,
                        isPlaying = currentTrack?.id == track.id,
                        showDragHandle = false,
                        onClick = {
                            if (isRadioDiscoveryMode) {
                                playerViewModel.playSingleTrack(track)
                            } else {
                                onTrackClick(track, albumTracks)
                            }
                        },
                        onMoreClick = {
                            contextMenuController.show(
                                track = track,
                                origin = if (playlistId != null) com.streamify.app.ui.components.MenuOrigin.PLAYLIST else com.streamify.app.ui.components.MenuOrigin.HOME,
                                playlistId = playlistId
                            )
                        }
                    )
                }
            }
        }

        // ── Phase 3: bulk-edit bottom action bar ───────────────────────────
        if (bulkEditMode && playlistId != null) {
            Surface(
                color = BgSurfaceElevated,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedTrackIds.size} selected",
                        style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            PlaylistRepository.removeTracksFromPlaylist(playlistId, selectedTrackIds.toList())
                            exitBulkEdit()
                            android.widget.Toast.makeText(context, "Removed ${selectedTrackIds.size} songs", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        enabled = selectedTrackIds.isNotEmpty()
                    ) {
                        Text(
                            "Remove",
                            color = if (selectedTrackIds.isNotEmpty()) androidx.compose.ui.graphics.Color(0xFFFF453A) else TextTertiary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = { showAddToPlaylistDialog = true },
                        enabled = selectedTrackIds.isNotEmpty()
                    ) {
                        Text(
                            "Add to Playlist",
                            color = if (selectedTrackIds.isNotEmpty()) Primary else TextTertiary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    TextButton(onClick = { exitBulkEdit() }) {
                        Text("Done", color = TextSecondary)
                    }
                }
            }
        }
    }
}
