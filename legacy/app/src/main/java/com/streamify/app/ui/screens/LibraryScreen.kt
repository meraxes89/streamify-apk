package com.streamify.app.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.supabase.SupabaseClient
import com.streamify.app.ui.components.*
import com.streamify.app.ui.components.yt.*
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    playerViewModel: PlayerViewModel,
    viewModel: LibraryViewModel = viewModel(),
    ingestionViewModel: IngestionViewModel = viewModel(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val downloadTasks by ingestionViewModel.downloadTasks.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    val currentTrack = playerState.currentTrack
    val playlists by PlaylistRepository.playlists.collectAsState()
    val user by SupabaseClient.currentUser.collectAsState()

    // ── Phase 3: Power Library — folders, pins, multi-criteria sort ──────
    val pinnedIds by com.streamify.app.data.repository.PlaylistFolderStore.pinnedIds.collectAsState()
    val libraryFolders by com.streamify.app.data.repository.PlaylistFolderStore.folders.collectAsState()
    var librarySort by remember {
        mutableStateOf(com.streamify.app.data.repository.LibraryOrganizer.LibrarySort.RECENTLY_ADDED)
    }
    var expandedFolderIds by remember { mutableStateOf(setOf<String>()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var playlistForFolderMove by remember { mutableStateOf<com.streamify.app.data.repository.Playlist?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        com.streamify.app.data.repository.PlaylistFolderStore.init(context)
    }

    var selectedFilter by remember { mutableStateOf("Playlists") }
    var isGridView by remember { mutableStateOf(false) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    var showSpotifyImportDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<com.streamify.app.data.repository.Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<com.streamify.app.data.repository.Playlist?>(null) }
    var playlistForOptions by remember { mutableStateOf<com.streamify.app.data.repository.Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var isScraping by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<com.streamify.app.data.youtube.ImportProgress?>(null) }
    val contextMenuController = LocalContextMenuController.current
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }

    // --- PILLAR 1: Deterministic Hierarchical Back Trapping ---
    BackHandler(enabled = showCreatePlaylistDialog) {
        showCreatePlaylistDialog = false
    }
    BackHandler(enabled = playlistToDelete != null) {
        playlistToDelete = null
    }
    BackHandler(enabled = playlistForOptions != null) {
        playlistForOptions = null
    }
    BackHandler(enabled = playlistToRename != null) {
        playlistToRename = null
    }
    BackHandler(enabled = showSpotifyImportDialog) {
        showSpotifyImportDialog = false
        isScraping = false
        importProgress = null
    }
    BackHandler(enabled = selectedPlaylistId != null) {
        selectedPlaylistId = null
    }
    BackHandler(enabled = selectedAlbumName != null) {
        selectedAlbumName = null
    }
    BackHandler(enabled = selectedFolder != null) {
        selectedFolder = null
    }

    // Spotify-Grade Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        var newPlaylistDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        label = { Text("Playlist Name") },
                        placeholder = { Text("e.g. Acoustic Vibes") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderChip,
                            cursorColor = Primary,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPlaylistDesc,
                        onValueChange = { newPlaylistDesc = it },
                        maxLines = 2,
                        label = { Text("Description (Optional)") },
                        placeholder = { Text("Describe your playlist...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderChip,
                            cursorColor = Primary,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            val created = PlaylistRepository.createPlaylist(newPlaylistName, newPlaylistDesc)
                            viewModel.loadLibrary()
                            selectedPlaylistId = created.id
                        }
                        showCreatePlaylistDialog = false
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create", color = if (newPlaylistName.isNotBlank()) Primary else TextSecondary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename Playlist Dialog
    if (playlistToRename != null) {
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
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
                    val pl = playlistToRename
                    if (pl != null && renameText.isNotBlank()) {
                        PlaylistRepository.renamePlaylist(pl.id, renameText)
                        viewModel.loadLibrary()
                    }
                    playlistToRename = null
                }) {
                    Text("Save", color = Primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete Playlist Confirmation Dialog
    if (playlistToDelete != null) {
        val pl = playlistToDelete ?: return
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist?", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete \"${pl.name}\"? Songs will remain in your local library.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistRepository.deletePlaylist(pl.id)
                    viewModel.loadLibrary()
                    if (selectedPlaylistId == pl.id) {
                        selectedPlaylistId = null
                    }
                    playlistToDelete = null
                }) {
                    Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFFF453A), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // ── Phase 3: Create Folder dialog ────────────────────────────────────
    if (showCreateFolderDialog) {
        BackHandler(enabled = true) { showCreateFolderDialog = false }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("New Folder", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Workout Mixes") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderChip,
                        cursorColor = Primary,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            com.streamify.app.data.repository.PlaylistFolderStore.createFolder(newFolderName)
                        }
                        newFolderName = ""
                        showCreateFolderDialog = false
                    },
                    enabled = newFolderName.isNotBlank()
                ) {
                    Text(
                        "Create",
                        color = if (newFolderName.isNotBlank()) Primary else TextSecondary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newFolderName = ""
                    showCreateFolderDialog = false
                }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // ── Phase 3: Move Playlist To Folder dialog ───────────────────────────
    if (playlistForFolderMove != null) {
        val moving = playlistForFolderMove ?: return
        BackHandler(enabled = true) { playlistForFolderMove = null }
        AlertDialog(
            onDismissRequest = { playlistForFolderMove = null },
            title = { Text("Move \"${moving.name}\"", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Choose a folder (playlists keep their songs):",
                        color = TextSecondary, fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Root option
                    Text(
                        text = "• No folder (root)",
                        color = Primary,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                com.streamify.app.data.repository.PlaylistFolderStore.movePlaylistToFolder(moving.id, null)
                                playlistForFolderMove = null
                            }
                            .padding(vertical = 8.dp)
                    )
                    libraryFolders.forEach { folder ->
                        Text(
                            text = "• ${folder.name}",
                            color = TextMain,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    com.streamify.app.data.repository.PlaylistFolderStore.movePlaylistToFolder(moving.id, folder.id)
                                    playlistForFolderMove = null
                                }
                                .padding(vertical = 8.dp)
                        )
                    }
                    if (libraryFolders.isEmpty()) {
                        Text(
                            "No folders yet — create one from the Playlists tab.",
                            color = TextTertiary, fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistForFolderMove = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showSpotifyImportDialog) {
        YtImportPlaylistSheet(
            importProgress = importProgress,
            isScraping = isScraping,
            onImportClick = { url ->
                coroutineScope.launch {
                    isScraping = true
                    try {
                        val scraped = com.streamify.app.data.youtube.PlaylistLinkScraper.scrapePlaylist(url)
                        isScraping = false
                        com.streamify.app.data.youtube.BatchTrackResolver.resolveAndImportPlaylist(scraped, context).collect { progress ->
                            importProgress = progress
                            if (progress.isComplete) {
                                viewModel.loadLibrary()
                            }
                        }
                    } catch (e: Exception) {
                        isScraping = false
                        importProgress = com.streamify.app.data.youtube.ImportProgress(
                            total = 0,
                            completed = 0,
                            currentTrackTitle = "",
                            isComplete = false,
                            errorMessage = e.message ?: "Failed to scrape playlist"
                        )
                    }
                }
            },
            onPlayPlaylist = { plId ->
                selectedPlaylistId = plId
                showSpotifyImportDialog = false
                importProgress = null
                viewModel.loadLibrary()
            },
            onDismiss = {
                showSpotifyImportDialog = false
                importProgress = null
                isScraping = false
            }
        )
    }

    // Detail Views (Album or Playlist opened)
    if (selectedAlbumName != null) {
        val allTracks = (uiState as? LibraryUiState.Success)?.tracks ?: emptyList()
        AlbumScreen(
            albumName = selectedAlbumName ?: return,
            allTracks = allTracks,
            playerViewModel = playerViewModel,
            onBack = { selectedAlbumName = null },
            onTrackClick = onTrackClick
        )
        return
    }

    if (selectedPlaylistId != null) {
        val allTracks = (uiState as? LibraryUiState.Success)?.tracks ?: emptyList()
        val likedTracks = (uiState as? LibraryUiState.Success)?.likedTracks ?: emptyList()
        val currentPlaylist = playlists.find { it.id == selectedPlaylistId }

        // O(n) membership via HashSet — the previous List.contains inside a
        // per-composition filter was O(n·m): 5k library x 300-song playlist =
        // 1.5M boxed equals on EVERY recomposition of this scope.
        val playlistTrackIds = remember(selectedPlaylistId, playlists) {
            currentPlaylist?.trackIds?.toHashSet()
        }
        val playlistTracks = when {
            selectedPlaylistId == "liked_songs" -> likedTracks
            playlistTrackIds != null -> allTracks.filter { it.id in playlistTrackIds }
            else -> emptyList()
        }

        PlaylistDetailScreen(
            playlistId = selectedPlaylistId ?: return,
            playlistName = if (selectedPlaylistId == "liked_songs") "Liked Music" else (currentPlaylist?.name ?: "Playlist"),
            playlistDescription = currentPlaylist?.description ?: "",
            playlistTracks = playlistTracks,
            playerViewModel = playerViewModel,
            onBack = { selectedPlaylistId = null },
            onTrackClick = onTrackClick
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Library Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Library",
                    style = LocalAppTypography.current.headlineLarge.copy(fontSize = 24.sp),
                    color = TextMain
                )
                Spacer(modifier = Modifier.width(10.dp))
                SireenBrandingBadge()
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { showCreatePlaylistDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = BgSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderChip),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "New Playlist",
                            tint = TextMain,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "New Playlist",
                            style = LocalAppTypography.current.chipText.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = TextMain
                        )
                    }
                }
                Surface(
                    onClick = { showSpotifyImportDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = Primary.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = "Import Playlist",
                            tint = Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import Playlist",
                            style = LocalAppTypography.current.chipText.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = Primary
                        )
                    }
                }
                IconButton(onClick = { enqueueMediaScan(context) }) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Rescan Storage",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 2. YouTube Music Filter Chips Rail
        YtLibraryFilterChips(
            selectedFilter = selectedFilter,
            onFilterSelected = {
                selectedFilter = it
                selectedFolder = null
                selectedPlaylistId = null
            }
        )

        // 3. Sort & View Mode Selector — Phase 3: real multi-criteria sort menu
        Box {
            YtSortFilterBar(
                sortLabel = librarySort.label,
                isGridView = isGridView,
                onSortClick = { showSortMenu = !showSortMenu },
                onToggleView = { isGridView = !isGridView }
            )
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
                modifier = Modifier.background(BgSurfaceElevated)
            ) {
                com.streamify.app.data.repository.LibraryOrganizer.LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                color = if (librarySort == option) Primary else TextMain,
                                fontWeight = if (librarySort == option) androidx.compose.ui.text.font.FontWeight.Bold
                                else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            if (librarySort == option) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            librarySort = option
                            showSortMenu = false
                        }
                    )
                }
            }
        }

        when (val state = uiState) {
            is LibraryUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }
            is LibraryUiState.Error -> {
                EmptyStateView(
                    title = "Something went wrong",
                    subtitle = state.message,
                    actionText = "Retry",
                    onActionClick = { viewModel.loadLibrary() }
                )
            }
            is LibraryUiState.Success -> {
                val allTracks = state.tracks
                val likedTracks = state.likedTracks

                // ── Phase 3: Power Library organization (hoisted — LazyListScope
                // is not composable, so all remember{} calls live here) ─────────
                val visiblePlaylists = remember(playlists) { playlists.filter { !it.isDeleted } }
                val playlistById = remember(visiblePlaylists) {
                    visiblePlaylists.associateBy { it.id }
                }
                val folderOfPlaylist = remember(libraryFolders) {
                    val map = HashMap<String, com.streamify.app.data.repository.PlaylistFolderStore.Folder>()
                    libraryFolders.forEach { folder ->
                        folder.playlistIds.forEach { pid -> map[pid] = folder }
                    }
                    map
                }
                // Organizer: pinned float first (pin recency), then sort key.
                val organizedPlaylists = remember(
                    visiblePlaylists, pinnedIds, librarySort
                ) {
                    val organized = com.streamify.app.data.repository.LibraryOrganizer.organize(
                        items = visiblePlaylists.map { pl ->
                            com.streamify.app.data.repository.LibraryOrganizer.Organizable(
                                id = pl.id,
                                name = pl.name,
                                creator = pl.description,
                                createdAtMs = pl.createdAt,
                                isPlaylist = true
                            )
                        },
                        filter = com.streamify.app.data.repository.LibraryOrganizer.LibraryFilter.PLAYLISTS,
                        sort = librarySort,
                        pinnedIds = pinnedIds
                    )
                    organized.mapNotNull { playlistById[it.id] }
                }
                val rootPlaylists = remember(organizedPlaylists, folderOfPlaylist) {
                    organizedPlaylists.filter { folderOfPlaylist[it.id] == null }
                }
                val pinnedPlaylists = remember(organizedPlaylists, pinnedIds) {
                    val pinnedSet = pinnedIds.toHashSet()
                    organizedPlaylists.filter { it.id in pinnedSet }
                }
                val rootUnpinnedPlaylists = remember(rootPlaylists, pinnedIds) {
                    val pinnedSet = pinnedIds.toHashSet()
                    rootPlaylists.filter { it.id !in pinnedSet }
                }

                val albums = remember(allTracks) {
                    allTracks.groupBy { it.album.ifBlank { "Unknown Album" } }
                }
                val artists = remember(allTracks) {
                    allTracks.groupBy { it.artist.ifBlank { "Unknown Artist" } }
                }
                val downloaded = remember(allTracks) {
                    allTracks.filter { it.filepath.isNotBlank() && it.source.contains("download", ignoreCase = true) }
                }
                val folders = remember(allTracks) {
                    allTracks.filter { it.filepath.isNotBlank() }.groupBy {
                        val path = it.filepath
                        val lastSlash = path.lastIndexOf('/')
                        if (lastSlash != -1) path.substring(0, lastSlash) else "Unknown Folder"
                    }
                }

                // Hoisted out of LazyColumn scopes: allocating these inside the
                // builder re-ran them (and re-triggered item diffing) on every
                // parent recomposition.
                val albumKeys = remember(albums) { albums.keys.toList() }
                val artistKeys = remember(artists) { artists.keys.toList() }
                val folderKeys = remember(folders) { folders.keys.toList() }

                val screenConfig = LocalScreenConfiguration.current
                val gridColumns = remember(screenConfig.widthDp) {
                    ((screenConfig.widthDp.value / 158f).toInt()).coerceAtLeast(2)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    when (selectedFilter) {
                        "Playlists" -> {
                            // Pinned Liked Music Hero Card
                            item(key = "pinned_liked_music", contentType = "likedPin") {
                                YtLikedMusicCard(
                                    trackCount = likedTracks.size,
                                    onClick = {
                                        selectedPlaylistId = "liked_songs"
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            // Create Playlist Quick Row
                            item(key = "create_new_playlist_row", contentType = "createPlaylistRow") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clickable { showCreatePlaylistDialog = true }
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "New Playlist",
                                            tint = Primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "New playlist",
                                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                            color = Primary
                                        )
                                        Text(
                                            text = "Create custom playlist",
                                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            if (!isGridView) {
                                // ── Phase 3: pinned-first + folder-grouped list ──
                                // (all derives are hoisted to the composable body)

                                // Pinned section (across folders — pin always floats)
                                if (pinnedPlaylists.isNotEmpty()) {
                                    item(key = "pinned_section_header", contentType = "sectionHeader") {
                                        Text(
                                            text = "PINNED",
                                            style = LocalAppTypography.current.songArtist.copy(
                                                fontSize = 11.sp,
                                                letterSpacing = 0.6.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            ),
                                            color = Primary,
                                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(
                                        items = pinnedPlaylists,
                                        key = { "pinned_playlist_${it.id}" },
                                        contentType = { "playlistRow" }
                                    ) { playlist ->
                                        PlaylistRow(
                                            playlist = playlist,
                                            isPinned = true,
                                            showPin = true,
                                            onOpen = { selectedPlaylistId = playlist.id },
                                            onPinToggle = {
                                                com.streamify.app.data.repository.PlaylistFolderStore.togglePin(playlist.id)
                                            },
                                            onRename = {
                                                renameText = playlist.name
                                                playlistToRename = playlist
                                            },
                                            onOptions = { playlistForOptions = playlist },
                                            onMoveToFolder = { playlistForFolderMove = playlist }
                                        )
                                    }
                                }

                                // Folder sections (expandable, playlists inside)
                                libraryFolders.forEach { folder ->
                                    val folderPlaylists = folder.playlistIds
                                        .mapNotNull { playlistById[it] }
                                        .sortedBy { pl ->
                                            organizedPlaylists.indexOfFirst { it.id == pl.id }.let { idx ->
                                                if (idx >= 0) idx else Int.MAX_VALUE
                                            }
                                        }
                                    item(key = "folder_${folder.id}", contentType = "folderRow") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedFolderIds = if (folder.id in expandedFolderIds) {
                                                        expandedFolderIds - folder.id
                                                    } else {
                                                        expandedFolderIds + folder.id
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (folder.id in expandedFolderIds) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                                                contentDescription = "Folder",
                                                tint = Primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = folder.name,
                                                style = LocalAppTypography.current.songTitle.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                ),
                                                color = TextMain
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${folderPlaylists.size}",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            IconButton(
                                                onClick = {
                                                    com.streamify.app.data.repository.PlaylistFolderStore.deleteFolder(folder.id)
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Delete folder",
                                                    tint = TextTertiary.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Icon(
                                                imageVector = if (folder.id in expandedFolderIds) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (folder.id in expandedFolderIds) "Collapse" else "Expand",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    if (folder.id in expandedFolderIds) {
                                        items(
                                            items = folderPlaylists,
                                            key = { "folder_playlist_${folder.id}_${it.id}" },
                                            contentType = { "playlistRow" }
                                        ) { playlist ->
                                            Box(modifier = Modifier.padding(start = 24.dp)) {
                                                PlaylistRow(
                                                    playlist = playlist,
                                                    isPinned = playlist.id in pinnedIds,
                                                    showPin = false,
                                                    onOpen = { selectedPlaylistId = playlist.id },
                                                    onPinToggle = {
                                                        com.streamify.app.data.repository.PlaylistFolderStore.togglePin(playlist.id)
                                                    },
                                                    onRename = {
                                                        renameText = playlist.name
                                                        playlistToRename = playlist
                                                    },
                                                    onOptions = { playlistForOptions = playlist },
                                                    onMoveToFolder = { playlistForFolderMove = playlist }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Create Folder row
                                item(key = "create_folder_row", contentType = "createFolderRow") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clickable { showCreateFolderDialog = true }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CreateNewFolder,
                                            contentDescription = "New Folder",
                                            tint = Primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(
                                            text = "New folder",
                                            style = LocalAppTypography.current.songTitle.copy(
                                                fontSize = 14.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                            ),
                                            color = Primary
                                        )
                                    }
                                }

                                // Root-level playlists (not inside any folder)
                                items(
                                    items = rootUnpinnedPlaylists,
                                    key = { "playlist_${it.id}" },
                                    contentType = { "playlistRow" }
                                ) { playlist ->
                                    PlaylistRow(
                                        playlist = playlist,
                                        isPinned = false,
                                        showPin = true,
                                        onOpen = { selectedPlaylistId = playlist.id },
                                        onPinToggle = {
                                            com.streamify.app.data.repository.PlaylistFolderStore.togglePin(playlist.id)
                                        },
                                        onRename = {
                                            renameText = playlist.name
                                            playlistToRename = playlist
                                        },
                                        onOptions = { playlistForOptions = playlist },
                                        onMoveToFolder = { playlistForFolderMove = playlist }
                                    )
                                }
                            }
                        }

                        "Songs" -> {
                            if (allTracks.isEmpty()) {
                                item {
                                    EmptyStateView(
                                        title = "No songs found",
                                        subtitle = "Scan your storage or import music to see your library.",
                                        actionText = "Rescan Storage",
                                        onActionClick = { enqueueMediaScan(context) }
                                    )
                                }
                            } else {
                                items(
                                    items = allTracks,
                                    key = { "lib_track_${it.id}" },
                                    contentType = { "trackRow" }
                                ) { track ->
                                    YtQueueTrackItem(
                                        track = track,
                                        isPlaying = currentTrack?.id == track.id,
                                        isActuallyPlaying = playerState.isPlaying,
                                        isBuffering = playerState.isBuffering,
                                        showDragHandle = false,
                                        onClick = { onTrackClick(track, allTracks) },
                                        onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.PLAYLIST, playlistId = "liked_songs") }
                                    )
                                }
                            }
                        }

                        "Albums" -> {
                            if (albums.isEmpty()) {
                                item {
                                    EmptyStateView(
                                        title = "No albums found",
                                        subtitle = "Albums will automatically show up when tagged music is added.",
                                        actionText = "Rescan",
                                        onActionClick = { viewModel.loadLibrary() }
                                    )
                                }
                            } else {
                                items(
                                    items = albumKeys,
                                    key = { "album_$it" },
                                    contentType = { "albumRow" }
                                ) { albumName ->
                                    val albumTrackList = albums[albumName] ?: emptyList()
                                    val firstTrack = albumTrackList.firstOrNull()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedAlbumName = albumName }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        YtThumbnail(
                                            url = firstTrack?.coverArtPath,
                                            size = 56.dp,
                                            cornerRadius = 4.dp
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = albumName,
                                                style = LocalAppTypography.current.songTitle.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                ),
                                                color = TextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "Album • ${firstTrack?.artist ?: "Unknown Artist"} • ${albumTrackList.size} songs",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        "Artists" -> {
                            if (artists.isEmpty()) {
                                item {
                                    EmptyStateView(
                                        title = "No artists found",
                                        subtitle = "Artists will appear automatically from your scanned tracks.",
                                        actionText = "Rescan",
                                        onActionClick = { viewModel.loadLibrary() }
                                    )
                                }
                            } else {
                                items(
                                    items = artistKeys,
                                    key = { "artist_$it" },
                                    contentType = { "artistRow" }
                                ) { artistName ->
                                    val artistTrackList = artists[artistName] ?: emptyList()
                                    val firstTrack = artistTrackList.firstOrNull()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { /* Select artist */ }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        YtThumbnail(
                                            url = firstTrack?.coverArtPath,
                                            size = 56.dp,
                                            cornerRadius = 28.dp // Circular avatar
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = artistName,
                                                style = LocalAppTypography.current.songTitle.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                ),
                                                color = TextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "Artist • ${artistTrackList.size} songs",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        "Downloaded" -> {
                            if (downloaded.isEmpty()) {
                                item {
                                    EmptyStateView(
                                        title = "No downloads yet",
                                        subtitle = "Downloaded tracks for offline playback will appear here.",
                                        actionText = "Discover Music",
                                        onActionClick = { /* Navigate to explore */ }
                                    )
                                }
                            } else {
                                items(
                                    items = downloaded,
                                    key = { "dl_${it.id}" },
                                    contentType = { "trackRow" }
                                ) { track ->
                                    YtQueueTrackItem(
                                        track = track,
                                        isPlaying = currentTrack?.id == track.id,
                                        isActuallyPlaying = playerState.isPlaying,
                                        isBuffering = playerState.isBuffering,
                                        showDragHandle = false,
                                        onClick = { onTrackClick(track, downloaded) },
                                        onMoreClick = { selectedOptionsTrack = track }
                                    )
                                }
                            }
                        }

                        "Folders" -> {
                            if (folders.isEmpty()) {
                                item {
                                    EmptyStateView(
                                        title = "No folders found",
                                        subtitle = "Audio directory folders will appear here.",
                                        actionText = "Rescan Storage",
                                        onActionClick = { enqueueMediaScan(context) }
                                    )
                                }
                            } else {
                                items(
                                    items = folderKeys,
                                    key = { "folder_$it" },
                                    contentType = { "folderRow" }
                                ) { folderPath ->
                                    val folderTracks = folders[folderPath] ?: emptyList()
                                    val folderName = folderPath.substringAfterLast('/')
                                    val count = folderTracks.size
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedFolder = folderPath }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(LocalAppShapes.current.thumbnailSmall)
                                                .background(BgCard),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Folder,
                                                contentDescription = "Folder",
                                                tint = Primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = folderName,
                                                style = LocalAppTypography.current.songTitle.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                ),
                                                color = TextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "$count songs • $folderPath",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary,
                                                maxLines = 1
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

    // Playlist Context Options Sheet (Spotify-Grade Menu)
    playlistForOptions?.let { pl ->
        val isSystem = pl.isSystem || pl.name.equals("Liked Music", ignoreCase = true)
        ModalBottomSheet(
            onDismissRequest = { playlistForOptions = null },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                // Header: Playlist Thumbnail + Title + Count
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(BgCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pl.name,
                            style = LocalAppTypography.current.songTitle.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = TextMain,
                            maxLines = 1
                        )
                        Text(
                            text = "${pl.trackIds.size} songs",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp),
                            color = TextSecondary
                        )
                    }
                }

                HorizontalDivider(color = BorderChip, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                // Exclude from Taste Profile (Gap #27) — playlist-level steer.
                run {
                    var playlistExcluded by remember(pl.id) {
                        mutableStateOf(
                            com.streamify.app.data.repository.TrackRepository
                                .isPlaylistExcludedFromTaste(pl.name)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = if (playlistExcluded) Primary else TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Exclude from Taste Profile",
                                style = LocalAppTypography.current.songTitle,
                                color = TextMain
                            )
                            Text(
                                if (playlistExcluded) "Not influencing recommendations"
                                else "Plays influence recommendations",
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = playlistExcluded,
                            onCheckedChange = { checked ->
                                playlistExcluded = checked
                                com.streamify.app.data.repository.TrackRepository
                                    .setPlaylistExcludedFromTaste(pl.name, checked)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                        )
                    }
                }

                // Rename Playlist
                if (!isSystem) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                renameText = pl.name
                                playlistToRename = pl
                                playlistForOptions = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = TextMain, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Rename playlist", style = LocalAppTypography.current.songTitle, color = TextMain)
                    }
                }

                // Export to M3U8
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val allTracks = (uiState as? LibraryUiState.Success)?.tracks ?: emptyList()
                            coroutineScope.launch {
                                val file = PlaylistRepository.exportPlaylistToM3U8(pl.id, allTracks, context)
                                if (file != null) {
                                    Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                                }
                            }
                            playlistForOptions = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, tint = TextMain, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Export to M3U8", style = LocalAppTypography.current.songTitle, color = TextMain)
                }

                if (!isSystem) {
                    // Rename
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playlistToRename = pl
                                renameText = pl.name
                                playlistForOptions = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = TextMain, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Rename playlist", style = LocalAppTypography.current.songTitle, color = TextMain)
                    }

                    // Delete
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playlistToDelete = pl
                                playlistForOptions = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFF453A), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete playlist", style = LocalAppTypography.current.songTitle, color = androidx.compose.ui.graphics.Color(0xFFFF453A))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun enqueueMediaScan(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val scanRequest = androidx.work.OneTimeWorkRequestBuilder<com.streamify.app.media.ingestion.IngestionWorker>()
        .addTag("ingestion_worker")
        .build()
    workManager.enqueueUniqueWork("media_scan", androidx.work.ExistingWorkPolicy.REPLACE, scanRequest)
}

/**
 * Phase 3 — one playlist row with pin / rename / options / move-to-folder.
 * Pin = PlaylistFolderStore toggle; pinned rows render with a filled pin
 * and a "Pinned" label.
 */
@Composable
private fun PlaylistRow(
    playlist: com.streamify.app.data.repository.Playlist,
    isPinned: Boolean,
    showPin: Boolean,
    onOpen: () -> Unit,
    onPinToggle: () -> Unit,
    onRename: () -> Unit,
    onOptions: () -> Unit,
    onMoveToFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.QueueMusic,
                contentDescription = "Playlist",
                tint = if (isPinned) Primary else StreamifyColors.TextSub,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                color = TextMain,
                maxLines = 1
            )
            Text(
                text = if (isPinned) "Pinned • ${playlist.trackIds.size} songs"
                else "Playlist • ${playlist.trackIds.size} songs",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = if (isPinned) Primary else TextSecondary,
                maxLines = 1
            )
        }
        if (showPin) {
            IconButton(onClick = onPinToggle, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin to top",
                    tint = if (isPinned) Primary else TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        IconButton(
            onClick = onRename,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Rename",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onOptions,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Options",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
