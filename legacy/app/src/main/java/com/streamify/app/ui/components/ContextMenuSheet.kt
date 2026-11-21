package com.streamify.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.ActiveControl
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MenuOrigin { HOME, SEARCH, QUEUE, PLAYLIST, DOWNLOADS, RELATED }

data class ContextMenuState(
    val isOpen: Boolean = false,
    val track: Track? = null,
    val origin: MenuOrigin = MenuOrigin.HOME,
    val playlistId: String? = null
)

class TrackContextMenuController {
    private val _state = MutableStateFlow(ContextMenuState())
    val state: StateFlow<ContextMenuState> = _state.asStateFlow()

    fun show(track: Track, origin: MenuOrigin = MenuOrigin.HOME, playlistId: String? = null) {
        _state.value = ContextMenuState(
            isOpen = true,
            track = track,
            origin = origin,
            playlistId = playlistId
        )
    }

    fun dismiss() {
        _state.update { it.copy(isOpen = false) }
    }
}

val LocalContextMenuController = staticCompositionLocalOf<TrackContextMenuController> {
    TrackContextMenuController()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.trackItemGestures(
    track: Track,
    origin: MenuOrigin = MenuOrigin.HOME,
    playlistId: String? = null,
    controller: TrackContextMenuController,
    onShortClick: () -> Unit
): Modifier = this.then(
    Modifier.combinedClickable(
        onClick = onShortClick,
        onLongClick = {
            com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
            controller.show(track = track, origin = origin, playlistId = playlistId)
        }
    )
)

@Composable
fun GlobalTrackContextMenuHost(
    controller: TrackContextMenuController,
    playerViewModel: com.streamify.app.viewmodel.PlayerViewModel,
    onGoToArtist: ((String) -> Unit)? = null,
    onGoToAlbum: ((String) -> Unit)? = null
) {
    val state by controller.state.collectAsState()
    if (state.isOpen && state.track != null) {
        val track = state.track ?: return
        ContextMenuSheet(
            track = track,
            onDismissRequest = { controller.dismiss() },
            onLikeClick = { playerViewModel.toggleLike(track) },
            onAddToPlaylistClick = {},
            onAddToQueueClick = { playerViewModel.addToQueue(track) },
            onPlayNextClick = { playerViewModel.playNext(track) },
            onStartRadioClick = { playerViewModel.startSongRadio(track) },
            onGoToArtist = onGoToArtist,
            onGoToAlbum = onGoToAlbum,
            onRemoveFromPlaylistClick = if (state.origin == MenuOrigin.PLAYLIST && state.playlistId != null) {
                { state.playlistId?.let { pid -> PlaylistRepository.removeTrackFromPlaylist(pid, track.id) } }
            } else null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    track: Track,
    onDismissRequest: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onPlayNextClick: (() -> Unit)? = null,
    onStartRadioClick: (() -> Unit)? = null,
    onStartJamClick: (() -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onGoToAlbum: ((String) -> Unit)? = null,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEditDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // Phase 3 — wave QR share card dialog.
    var showQrCardDialog by remember { mutableStateOf(false) }
    val playlists by PlaylistRepository.playlists.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = StreamifyColors.BgElevated,
        shape = StreamifyShapes.BottomSheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = StreamifyColors.TextDimmed) }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = StreamifyDimens.SpaceXL) // Navigation bar padding
        ) {
            // Header: Cover Art + Title + Artist
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(StreamifyDimens.SpaceLG),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.coverArtPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(StreamifyShapes.CardShape)
                )
                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = StreamifyType.TitleLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = StreamifyColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = StreamifyColors.Divider, thickness = 1.dp)

            // 1. Play Next
            ContextActionItem(
                icon = Icons.Filled.PlaylistPlay,
                text = "Play Next",
                onClick = {
                    onPlayNextClick?.invoke() ?: onAddToQueueClick()
                    android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )

            // 2. Add to Queue
            ContextActionItem(
                icon = Icons.Filled.QueueMusic,
                text = "Add to Queue",
                onClick = {
                    onAddToQueueClick()
                    android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )

            // 2b. Start Radio
            ContextActionItem(
                icon = Icons.Filled.Radio,
                text = "Start Radio",
                onClick = {
                    onStartRadioClick?.invoke()
                    android.widget.Toast.makeText(context, "Starting radio based on ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )

            // 3. Jam Session Action — v4 JamEngine mesh session (the v2/v3
            // Supabase jam stack was a permanently-null dead path: nothing
            // ever created a Supabase listening_session, so this branch was
            // unreachable and "Add to Jam Queue" never rendered).
            val activeJam by com.streamify.app.jam.JamEngine.activeSession.collectAsState()
            if (activeJam != null) {
                ContextActionItem(
                    icon = Icons.Filled.QueueMusic,
                    text = "Add to Jam Queue",
                    iconTint = ActiveControl,
                    onClick = {
                        if (activeJam != null) {
                            val jamQueue = com.streamify.app.jam.JamEngine.queue.value
                            val isDup = jamQueue.any { com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist) }
                            if (!isDup) {
                                com.streamify.app.jam.JamEngine.addToQueue(
                                    track,
                                    addedByName = com.streamify.app.jam.JamEngine.myDisplayName.ifBlank { "Someone" }
                                )
                            }
                            android.widget.Toast.makeText(context, "Added ${track.title} to Jam Queue", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        onDismissRequest()
                    }
                )
            } else {
                ContextActionItem(
                    icon = Icons.Filled.Radio,
                    text = "Start a Jam Session",
                    onClick = {
                        onStartJamClick?.invoke() ?: run {
                            android.widget.Toast.makeText(context, "Broadcasting ${track.title} to Jam Session", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        onDismissRequest()
                    }
                )
            }

            // 4. Like / Favoriting
            ContextActionItem(
                icon = Icons.Filled.Favorite,
                text = if (track.isLiked) "Remove from Liked Songs" else "Like",
                iconTint = if (track.isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
                onClick = {
                    onLikeClick()
                    onDismissRequest()
                }
            )

            // 4b. Exclude from Taste Profile (Gap #27): steers the algorithm —
            // play events stop feeding the radio seed pool + telemetry.
            run {
                var excludedFromTaste by remember(track) {
                    mutableStateOf(
                        com.streamify.app.data.repository.TrackRepository.isTrackExcludedFromTaste(track)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = StreamifyDimens.SpaceLG,
                            vertical = StreamifyDimens.SpaceSM
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = if (excludedFromTaste) StreamifyColors.Primary else StreamifyColors.TextSub,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceLG))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Exclude from Taste Profile",
                            style = StreamifyType.TitleMedium,
                            color = StreamifyColors.TextMain
                        )
                        Text(
                            text = if (excludedFromTaste) {
                                "Not influencing recommendations"
                            } else {
                                "Plays influence recommendations"
                            },
                            style = StreamifyType.BodySmall,
                            color = StreamifyColors.TextSub
                        )
                    }
                    Switch(
                        checked = excludedFromTaste,
                        onCheckedChange = { checked ->
                            excludedFromTaste = checked
                            com.streamify.app.data.repository.TrackRepository
                                .setTrackExcludedFromTaste(track, checked)
                            android.widget.Toast.makeText(
                                context,
                                if (checked) {
                                    "\"${track.title}\" won't influence your taste profile"
                                } else {
                                    "\"${track.title}\" will influence your taste profile"
                                },
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = StreamifyColors.Primary
                        )
                    )
                }
            }

            // 5. Add to Playlist (with New Playlist creation)
            ContextActionItem(
                icon = Icons.Filled.PlaylistAdd,
                text = "Add to Playlist",
                onClick = {
                    showPlaylistDialog = true
                }
            )

            // 5b. Remove from this playlist
            if (onRemoveFromPlaylistClick != null) {
                ContextActionItem(
                    icon = Icons.Filled.DeleteOutline,
                    text = "Remove from this playlist",
                    iconTint = androidx.compose.ui.graphics.Color(0xFFFF453A),
                    onClick = {
                        onRemoveFromPlaylistClick()
                        onDismissRequest()
                    }
                )
            }

            // 6. Go to Artist
            if (onGoToArtist != null && track.artist.isNotBlank() && track.artist != "Unknown Artist") {
                ContextActionItem(
                    icon = Icons.Filled.Person,
                    text = "Go to Artist",
                    onClick = {
                        onGoToArtist(track.artist)
                        onDismissRequest()
                    }
                )
            }

            // 7. Go to Album
            if (onGoToAlbum != null && track.album.isNotBlank() && track.album != "Single" && track.album != "Local Import") {
                ContextActionItem(
                    icon = Icons.Filled.Album,
                    text = "Go to Album",
                    onClick = {
                        onGoToAlbum(track.album)
                        onDismissRequest()
                    }
                )
            }

            // 8. Download Offline (Phase 3: resumable byte-range worker with
            //    quality ladder + battery/unmetered constraints + retry)
            ContextActionItem(
                icon = Icons.Filled.Download,
                text = "Download",
                onClick = {
                    runCatching {
                        com.streamify.app.data.download.StreamifyDownloadManager.enqueue(
                            context = context,
                            url = if (track.filepath.startsWith("http")) track.filepath else "https://www.youtube.com/watch?v=${track.ytmVideoId ?: track.id}",
                            title = track.title,
                            artist = track.artist,
                            album = track.album.ifBlank { "Streamify" },
                            durationSec = track.durationSec
                        )
                    }.onFailure {
                        // Constraint-less fallback: the legacy quick worker.
                        com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                            context = context,
                            url = if (track.filepath.startsWith("http")) track.filepath else "https://www.youtube.com/watch?v=${track.id}",
                            title = track.title,
                            artist = track.artist,
                            album = track.album.ifBlank { "Streamify" }
                        )
                    }
                    android.widget.Toast.makeText(context, "Download queued: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )

            // 9. Share Track (system sheet)
            ContextActionItem(
                icon = Icons.Filled.Share,
                text = "Share",
                onClick = {
                    com.streamify.app.util.TrackShareCard.shareTrack(context, track)
                    onDismissRequest()
                }
            )

            // 9b. Share Wave QR Card (Phase 3 — Spotify-style visual card)
            ContextActionItem(
                icon = Icons.Filled.QrCode2,
                text = "Share QR Card",
                onClick = {
                    showQrCardDialog = true
                }
            )

            // 10. Edit Info
            ContextActionItem(
                icon = Icons.Filled.Edit,
                text = "Edit Info",
                onClick = {
                    showEditDialog = true
                }
            )
        }
    }

    // ── Phase 3: wave QR share card dialog ────────────────────────────────
    if (showQrCardDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQrCardDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrShareCard(
                kindLabel = "TRACK",
                title = track.title,
                subtitle = track.artist,
                artworkUrl = track.coverArtPath,
                link = com.streamify.app.ui.components.ShareLinkBuilder.trackLink(track),
                modifier = Modifier.padding(16.dp),
                onDismiss = { showQrCardDialog = false }
            )
        }
    }

    if (showEditDialog) {
        var editTitle by remember { mutableStateOf(track.title) }
        var editArtist by remember { mutableStateOf(track.artist) }
        var editAlbum by remember { mutableStateOf(track.album) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Info", color = StreamifyColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                    TextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        label = { Text("Artist") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                    TextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        label = { Text("Album") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = track.copy(title = editTitle, artist = editArtist, album = editAlbum)
                        scope.launch {
                            com.streamify.app.data.repository.TrackRepository.updateTrack(updated)
                        }
                        showEditDialog = false
                        onDismissRequest()
                    }
                ) { Text("Save", color = StreamifyColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("Add to Playlist", color = StreamifyColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPlaylistDialog = false
                                showCreatePlaylistDialog = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Create", tint = StreamifyColors.Primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create New Playlist", color = StreamifyColors.Primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = StreamifyColors.Divider)
                    if (playlists.isEmpty()) {
                        Text("No playlists created yet.", color = StreamifyColors.TextSub)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                Text(
                                    text = playlist.name,
                                    color = StreamifyColors.TextMain,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            PlaylistRepository.addTrackToPlaylist(playlist.id, track.id)
                                            android.widget.Toast.makeText(context, "Added to ${playlist.name}", android.widget.Toast.LENGTH_SHORT).show()
                                            showPlaylistDialog = false
                                            onDismissRequest()
                                        }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create New Playlist", color = StreamifyColors.TextMain) },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StreamifyColors.Primary,
                        unfocusedBorderColor = StreamifyColors.TextDimmed,
                        focusedLabelColor = StreamifyColors.Primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            PlaylistRepository.createPlaylist(playlistName)
                            // Find and add track to the newly created playlist
                            val currentPlaylists = PlaylistRepository.playlists.value
                            val newPl = currentPlaylists.find { it.name == playlistName }
                            if (newPl != null) {
                                PlaylistRepository.addTrackToPlaylist(newPl.id, track.id)
                            }
                            android.widget.Toast.makeText(context, "Created \"$playlistName\" & added track", android.widget.Toast.LENGTH_SHORT).show()
                            showCreatePlaylistDialog = false
                            onDismissRequest()
                        }
                    },
                    enabled = playlistName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary)
                ) { Text("Create & Add", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }
}

@Composable
private fun ContextActionItem(
    icon: ImageVector,
    text: String,
    iconTint: androidx.compose.ui.graphics.Color = StreamifyColors.TextSub,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = StreamifyDimens.SpaceLG,
                vertical = StreamifyDimens.SpaceMD
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceLG))
        Text(
            text = text,
            style = StreamifyType.TitleMedium,
            color = StreamifyColors.TextMain
        )
    }
}
