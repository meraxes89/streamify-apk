package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.supabase.AdminCommentItem
import com.streamify.app.data.supabase.AdminJamSession
import com.streamify.app.data.supabase.AdminTelemetry
import com.streamify.app.data.supabase.SupabaseClient
import com.streamify.app.data.supabase.UserProfile
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.uppercase(), style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = StreamifyType.HeadlineLarge, color = StreamifyColors.TextMain)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, style = StreamifyType.Caption, color = accentColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val user by SupabaseClient.currentUser.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var telemetry by remember { mutableStateOf<AdminTelemetry?>(null) }
    var jamSessions by remember { mutableStateOf<List<AdminJamSession>>(emptyList()) }
    var recentComments by remember { mutableStateOf<List<AdminCommentItem>>(emptyList()) }
    var edgeStats by remember { mutableStateOf<com.streamify.app.data.supabase.AdminEdgeMeshStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedUserForDetail by remember { mutableStateOf<UserProfile?>(null) }
    
    var broadcastMsg by remember { mutableStateOf("") }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    // Live CDC records arriving before first load completes are buffered so
    // they can never be silently dropped (A2).
    var pendingLiveRecords by remember { mutableStateOf(listOf<JSONObject>()) }

    fun applyProfileRecord(
        base: com.streamify.app.data.supabase.AdminTelemetry,
        record: JSONObject
    ): com.streamify.app.data.supabase.AdminTelemetry {
        val uId = record.optString("id", "")
        if (uId.isBlank()) return base
        val users = base.userList.toMutableList()
        val idx = users.indexOfFirst { it.id == uId }
        val row = UserProfile(
            id = uId,
            email = record.optString("email", ""),
            displayName = record.optString("display_name", "Listener ${uId.take(6)}"),
            avatarUrl = record.optString("avatar_url", ""),
            bio = record.optString("bio", ""),
            totalPlays = record.optInt("total_plays", 0),
            listeningSeconds = record.optLong("listening_seconds", 0L),
            favoriteGenre = record.optString("favorite_genre", "All"),
            topTrack = record.optString("top_track", ""),
            lastActiveAt = record.optString("last_active_at", "")
        )
        return if (idx >= 0) {
            users[idx] = users[idx].copy(
                email = if (row.email.isNotBlank()) row.email else users[idx].email,
                displayName = row.displayName,
                avatarUrl = row.avatarUrl.ifBlank { users[idx].avatarUrl },
                bio = row.bio.ifBlank { users[idx].bio },
                totalPlays = row.totalPlays,
                listeningSeconds = row.listeningSeconds,
                favoriteGenre = row.favoriteGenre.ifBlank { users[idx].favoriteGenre },
                topTrack = row.topTrack.ifBlank { users[idx].topTrack },
                lastActiveAt = row.lastActiveAt.ifBlank { users[idx].lastActiveAt }
            )
            base.copy(userList = users)
        } else base.copy(userList = users + row)   // new listeners appear live too
    }

    fun refreshAll(silent: Boolean = false) {
        scope.launch {
            if (!silent) isLoading = true
            val baseTelem = SupabaseClient.getAdminTelemetry().getOrNull()
            if (baseTelem != null) {
                // fold instead of a closure-mutated var: a var captured by a
                // changing closure never smart-casts to non-null at call sites.
                val mergedTelem: com.streamify.app.data.supabase.AdminTelemetry =
                    pendingLiveRecords.fold(baseTelem) { acc, record ->
                        applyProfileRecord(acc, record)
                    }
                pendingLiveRecords = emptyList()
                telemetry = mergedTelem
            }

            jamSessions = SupabaseClient.getAdminJamSessions().getOrDefault(emptyList())
            recentComments = SupabaseClient.getAdminRecentComments(50).getOrDefault(emptyList())
            edgeStats = SupabaseClient.getAdminEdgeComputeStats().getOrNull()

            if (!silent) isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()

        launch {
            SupabaseClient.liveProfileUpdates.collect { record: JSONObject ->
                val base = telemetry
                if (base != null) telemetry = applyProfileRecord(base, record)
                else pendingLiveRecords = pendingLiveRecords + record
            }
        }

        // 30s auto-refresh: dashboard can never go permanently stale.
        while (true) {
            kotlinx.coroutines.delay(30_000)
            refreshAll(silent = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Admin Portal", style = StreamifyType.HeadlineMedium, color = StreamifyColors.TextMain)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = StreamifyColors.Primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "PROD ROOT",
                                style = StreamifyType.Caption,
                                color = StreamifyColors.Primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(user?.email ?: "sireenyadav@gmail.com", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                }
            }

            IconButton(onClick = { refreshAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = StreamifyColors.Primary)
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        // Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = StreamifyColors.BgBase,
            contentColor = StreamifyColors.Primary,
            edgePadding = StreamifyDimens.SpaceLG,
            divider = {}
        ) {
            val tabs = listOf("Telemetry", "Top Songs", "Edge Mesh", "Users", "Jam Rooms", "Comments", "Broadcasts")
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            style = StreamifyType.TitleSmall,
                            color = if (selectedTab == index) StreamifyColors.Primary else StreamifyColors.TextSub
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        telemetry?.let { t ->
            if (t.serverStatus.startsWith("RPC ERROR")) {
                Surface(
                    color = Color(0xFFEF4444).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = StreamifyDimens.SpaceLG)
                ) {
                    Text(
                        "⚠ Dashboard RPC failed (${t.serverStatus.removePrefix("RPC ERROR — ")}) — apply stats_overhaul migration & verify is_admin",
                        color = Color(0xFFEF4444),
                        style = StreamifyType.Caption,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
            }
        }

        when (selectedTab) {
            // TAB 1: GLOBAL TOP SONGS (cross-user leaderboard from user_track_plays)
            1 -> AdminTopSongsPanel()

            // TAB 0: TELEMETRY & SYSTEM HEALTH
            0 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgElevated),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(StreamifyColors.Primary)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Database & Vector Engine", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                        Text(telemetry?.engineMode ?: "PostgreSQL 15 + pgvector", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    }
                                }
                                Text("${telemetry?.latencyMs ?: 24} ms", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Users",
                                value = "${telemetry?.totalUsers ?: 0}",
                                subtitle = "${telemetry?.dau24h ?: 0} Active 24h",
                                icon = Icons.Filled.People,
                                accentColor = Color(0xFF1DB954),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Jam Rooms",
                                value = "${telemetry?.activeJamSessions ?: 0}",
                                subtitle = "Live Rooms",
                                icon = Icons.Filled.Podcasts,
                                accentColor = Color(0xFF7358FF),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Cloud Songs",
                                value = "${telemetry?.totalTracks ?: 0}",
                                subtitle = "Indexed Vectors",
                                icon = Icons.Filled.MusicNote,
                                accentColor = Color(0xFFE8115B),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Playlists",
                                value = "${telemetry?.totalPlaylists ?: 0}",
                                subtitle = "Public & Collab",
                                icon = Icons.Filled.QueueMusic,
                                accentColor = Color(0xFFBC5900),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Comments",
                                value = "${telemetry?.totalComments ?: 0}",
                                subtitle = "Reactions",
                                icon = Icons.Filled.Comment,
                                accentColor = Color(0xFF2E77D0),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Total Plays",
                                value = "${telemetry?.totalPlays ?: 0}",
                                subtitle = "Streams",
                                icon = Icons.Filled.PlayArrow,
                                accentColor = Color(0xFFE91E63),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Admin Quick Actions
                    item {
                        Text("Admin Quick Actions", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            Button(
                                onClick = { showBroadcastDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Broadcast", style = StreamifyType.BodyMedium)
                            }

                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edge Mesh", style = StreamifyType.BodyMedium)
                            }
                        }
                    }
                }
            }

            // TAB 1: EDGE COMPUTE MESH
            1 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                        ) {
                            MetricCard(
                                title = "Active Nodes",
                                value = "${edgeStats?.activeNodesCount ?: 0}",
                                subtitle = "Computing Right Now",
                                icon = Icons.Filled.Memory,
                                accentColor = Color(0xFF00E5FF),
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                title = "Mesh Pipeline",
                                value = "${edgeStats?.completedTasksCount ?: 0}",
                                subtitle = "of ${edgeStats?.totalTasksCount ?: 0} Verified",
                                icon = Icons.Filled.CheckCircle,
                                accentColor = StreamifyColors.Primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        MetricCard(
                            title = "Bandwidth Inverted",
                            value = "${String.format("%.1f", edgeStats?.totalBandwidthSavedMb ?: 0.0)} MB",
                            subtitle = "Saved via Local-First Edge Caches ($0 Cloud Bill)",
                            icon = Icons.Filled.Speed,
                            accentColor = Color(0xFFFF9800),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Section 1: Active Nodes Right Now
                    item {
                        Text("Live Edge Nodes (${edgeStats?.activeNodes?.size ?: 0})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.activeNodes.isNullOrEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No nodes computing right now (Workers run overnight when charging)", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                }
                            }
                        }
                    } else {
                        items(edgeStats?.activeNodes.orEmpty(), key = { it.deviceId }) { node ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (node.status == "COMPUTING") Color(0xFF00E5FF) else StreamifyColors.Primary)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(node.displayName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("(${node.deviceId})", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                        }
                                        if (node.currentTrackTitle.isNotBlank()) {
                                            Text("Active: ${node.currentTrackTitle}", style = StreamifyType.Caption, color = Color(0xFF00E5FF), maxLines = 1)
                                        } else {
                                            Text("Status: ${node.status}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${node.totalContributions} Solved", style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Text("${String.format("%.1f", node.bandwidthSavedMb)} MB saved", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Top Contributors Leaderboard
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Top Mesh Contributors 🏆", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.topContributors != null) {
                        items(edgeStats?.topContributors.orEmpty().take(10), key = { it.userId }) { contributor ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgElevated),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            contributor.userEmail.take(2).uppercase().ifBlank { "ME" },
                                            style = StreamifyType.TitleSmall,
                                            color = Color(0xFF00E5FF),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contributor.displayName.ifBlank { contributor.userEmail.ifBlank { "Sovereign Node" } }, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                        Text(contributor.userEmail, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${contributor.totalContributions} Tracks", style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Text("${String.format("%.1f", contributor.bandwidthSavedMb)} MB", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                }
                            }
                        }
                    }

                    // Section 3: Database Tables & Storage Telemetry
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Database Table Telemetry & Row Counts", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (edgeStats?.tableStats != null) {
                        items(edgeStats?.tableStats.orEmpty(), key = { it.tableName }) { tbl ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("public.${tbl.tableName}", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                    Text("${tbl.rowCount} rows", style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.Primary)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: USERS EXPLORER & ROLES
            2 -> {
                val filteredUsers = remember(searchQuery, telemetry?.userList) {
                    val list = telemetry?.userList ?: emptyList()
                    if (searchQuery.isBlank()) list
                    else list.filter {
                        it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.email.contains(searchQuery, ignoreCase = true)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search users by name or email...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = StreamifyColors.TextSub) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = StreamifyColors.BgElevated,
                                unfocusedContainerColor = StreamifyColors.BgElevated,
                                focusedBorderColor = StreamifyColors.Primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text("Registered Profiles (${filteredUsers.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    items(filteredUsers, key = { it.id }) { profile ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedUserForDetail = profile }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (profile.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = profile.avatarUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(StreamifyColors.BgElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = profile.displayName.take(1).uppercase(),
                                            style = StreamifyType.TitleMedium,
                                            color = StreamifyColors.Primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    // SEC-09: the OWNER heuristic (email/display-name
                                    // contains "sireen") is gone — the badge reflects the
                                    // server-verified profiles.is_admin flag only.
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(profile.displayName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                        if (profile.isAdmin) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = StreamifyColors.Primary.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "ADMIN",
                                                    style = StreamifyType.Caption,
                                                    color = StreamifyColors.Primary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(profile.email, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                    val mins = profile.listeningSeconds / 60
                                    val genreText = if (profile.favoriteGenre.isNotBlank() && profile.favoriteGenre != "All") " • ${profile.favoriteGenre}" else ""
                                    Text("${profile.totalPlays} plays • $mins mins$genreText", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    if (profile.topTrack.isNotBlank()) {
                                        Text("🎧 Top: ${profile.topTrack}", style = StreamifyType.Caption.copy(fontSize = 10.sp), color = StreamifyColors.Primary, maxLines = 1)
                                    } else {
                                        Text("🎧 No tracks played yet", style = StreamifyType.Caption.copy(fontSize = 10.sp), color = StreamifyColors.TextSub, maxLines = 1)
                                    }
                                }

                                // SEC-09: role toggling is protected by the server-side
                                // flag; the only client-side carve-out is "never toggle
                                // your own role". The name-based owner tier is gone.
                                val isSelf = profile.id == user?.id

                                if (!isSelf) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            val newRole = !profile.isAdmin
                                            val res = SupabaseClient.setUserAdminRole(profile.id, newRole)
                                            if (res.isSuccess) {
                                                Toast.makeText(context, "Updated role for ${profile.displayName}", Toast.LENGTH_SHORT).show()
                                                refreshAll()
                                            } else {
                                                Toast.makeText(context, "Failed to update role", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (profile.isAdmin) Icons.Filled.AdminPanelSettings else Icons.Filled.PersonAddAlt1,
                                            contentDescription = "Toggle Admin",
                                            tint = if (profile.isAdmin) StreamifyColors.Primary else StreamifyColors.TextSub
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: JAM ROOMS MANAGEMENT
            3 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Text("Active Listening Rooms (${jamSessions.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (jamSessions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Podcasts, contentDescription = null, tint = StreamifyColors.TextDimmed, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No active Jam rooms currently open", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                }
                            }
                        }
                    }

                    items(jamSessions, key = { it.id }) { session ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = Color(0xFF7358FF).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                clipboardManager.setText(AnnotatedString(session.sessionCode))
                                                Toast.makeText(context, "PIN ${session.sessionCode} copied!", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(
                                                text = session.sessionCode,
                                                style = StreamifyType.TitleSmall,
                                                color = Color(0xFF7358FF),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(session.hostName, style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                                    }

                                    IconButton(onClick = {
                                        scope.launch {
                                            val res = SupabaseClient.terminateJamSessionAdmin(session.id)
                                            if (res.isSuccess) {
                                                Toast.makeText(context, "Jam room closed", Toast.LENGTH_SHORT).show()
                                                refreshAll()
                                            } else {
                                                Toast.makeText(context, "Failed to terminate room", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Terminate", tint = StreamifyColors.ErrorRed)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("🎵 ${session.currentTrackTitle} • ${session.currentTrackArtist}", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("👥 ${session.participantCount} connected listener(s) • ${if (session.isPlaying) "▶ Playing" else "⏸ Paused"}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                        }
                    }
                }
            }

            // TAB 4: COMMENTS MODERATION FEED
            4 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Text("Live Song Comments (${recentComments.size})", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                    }

                    if (recentComments.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No recent comments to moderate", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                            }
                        }
                    }

                    items(recentComments, key = { it.id }) { comment ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(comment.userName, style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("on ${comment.trackTitle}", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("@ ${(comment.timestampMs / 1000)}s", style = StreamifyType.Caption, color = StreamifyColors.TextDimmed)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(comment.commentText, style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                }

                                IconButton(onClick = {
                                    scope.launch {
                                        val res = SupabaseClient.deleteCommentAdmin(comment.id)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Comment removed", Toast.LENGTH_SHORT).show()
                                            refreshAll()
                                        } else {
                                            Toast.makeText(context, "Failed to delete comment", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = StreamifyColors.ErrorRed)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 5: BROADCASTS
            5 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = StreamifyDimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("New Global Announcement", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Broadcast an instant announcement banner to all connected Streamify apps worldwide.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                Spacer(modifier = Modifier.height(14.dp))

                                OutlinedTextField(
                                    value = broadcastMsg,
                                    onValueChange = { broadcastMsg = it },
                                    placeholder = { Text("Enter announcement message...") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = StreamifyColors.BgElevated,
                                        unfocusedContainerColor = StreamifyColors.BgElevated,
                                        focusedBorderColor = StreamifyColors.Primary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        if (broadcastMsg.isNotBlank()) {
                                            scope.launch {
                                                val res = SupabaseClient.postAdminBroadcast(broadcastMsg)
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "Broadcast sent successfully!", Toast.LENGTH_SHORT).show()
                                                    broadcastMsg = ""
                                                    refreshAll()
                                                } else {
                                                    Toast.makeText(context, "Failed to post broadcast", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Publish Global Banner", style = StreamifyType.TitleSmall, color = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBroadcastDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            title = { Text("Global System Announcement", color = StreamifyColors.TextMain) },
            text = {
                Column {
                    Text("Broadcast an instant banner message to all live connected Streamify devices.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = broadcastMsg,
                        onValueChange = { broadcastMsg = it },
                        placeholder = { Text("Enter announcement...") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = StreamifyColors.BgElevated,
                            unfocusedContainerColor = StreamifyColors.BgElevated
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (broadcastMsg.isNotBlank()) {
                            scope.launch {
                                SupabaseClient.postAdminBroadcast(broadcastMsg)
                                Toast.makeText(context, "Announcement broadcasted!", Toast.LENGTH_SHORT).show()
                                showBroadcastDialog = false
                                broadcastMsg = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary)
                ) {
                    Text("Send Broadcast", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBroadcastDialog = false }) {
                    Text("Cancel", color = StreamifyColors.TextSub)
                }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    // USER INTELLIGENCE & TELEMETRY DEEP-DIVE SHEET
    selectedUserForDetail?.let { selectedUser ->
        UserTelemetrySheet(
            user = selectedUser,
            onDismiss = { selectedUserForDetail = null },
            onToggleAdmin = { newStatus ->
                scope.launch {
                    val res = SupabaseClient.setUserAdminRole(selectedUser.id, newStatus)
                    if (res.isSuccess) {
                        Toast.makeText(context, "Updated role for ${selectedUser.displayName}", Toast.LENGTH_SHORT).show()
                        selectedUserForDetail = selectedUser.copy(isAdmin = newStatus)
                        refreshAll()
                    } else {
                        Toast.makeText(context, "Failed to update role", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserTelemetrySheet(
    user: UserProfile,
    onDismiss: () -> Unit,
    onToggleAdmin: (Boolean) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StreamifyColors.BgElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Identity Header
            if (user.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(StreamifyColors.Primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.displayName.take(1).uppercase(),
                        style = StreamifyType.HeadlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                        color = StreamifyColors.Primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = StreamifyType.HeadlineMedium.copy(fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = StreamifyColors.TextMain
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    // SEC-09: server-verified flag only — no OWNER tier is
                    // derivable client-side.
                    color = if (user.isAdmin) Color(0xFF7358FF).copy(alpha = 0.25f) else StreamifyColors.BgCard,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (user.isAdmin) "ADMIN 🛡️" else "USER",
                        style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = if (user.isAdmin) Color(0xFF00E5FF) else StreamifyColors.TextSub,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = user.email,
                style = StreamifyType.Caption,
                color = StreamifyColors.TextSub
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Real-Time Streaming Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = StreamifyColors.BgCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("LISTENING TIME", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalMins = user.listeningSeconds / 60
                        val hrs = totalMins / 60
                        val mins = totalMins % 60
                        Text(
                            text = if (hrs > 0) "${hrs}h ${mins}m" else "$totalMins mins",
                            style = StreamifyType.TitleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = StreamifyColors.Primary
                        )
                    }
                }

                Surface(
                    color = StreamifyColors.BgCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("TOTAL STREAMS", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${user.totalPlays} plays",
                            style = StreamifyType.TitleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var livePersona by remember { mutableStateOf<com.streamify.app.data.network.LivePersona?>(null) }
            LaunchedEffect(user.id) {
                try {
                    livePersona = com.streamify.app.data.network.PersonaEngine.generateLivePersona()
                } catch (e: Exception) {}
            }

            // 3. Audio DNA & AI Persona Card
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AUDIO DNA & AI PERSONA", style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.TextSub)
                        Surface(color = StreamifyColors.Primary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                            Text("⚡ GLM-4", style = StreamifyType.Caption.copy(fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.Primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Persona Archetype", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                        Text(livePersona?.title ?: user.bio.ifBlank { "Sonic Explorer 🎧" }, style = StreamifyType.BodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.Primary)
                    }

                    livePersona?.takeIf { it.description.isNotBlank() }?.let { persona ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = persona.description,
                            style = StreamifyType.Caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = StreamifyColors.TextMain.copy(alpha = 0.85f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Top Genre Signature", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                        Text(livePersona?.topAcousticTrait ?: user.favoriteGenre.ifBlank { "All Genres" }, style = StreamifyType.BodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color(0xFF00E5FF))
                    }

                    if (user.topTrack.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Top Listened Song", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                            Text(user.topTrack, style = StreamifyType.BodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = StreamifyColors.Primary, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Admin Action Buttons
            // SEC-09: the OWNER-tier heuristic is gone; the only protection is
            // "never toggle your own role" (same carve-out as the list view).
            val isSelf = SupabaseClient.currentUser.value?.id == user.id
            if (!isSelf) {
                Button(
                    onClick = { onToggleAdmin(!user.isAdmin) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (user.isAdmin) Color(0xFFE91E63) else StreamifyColors.Primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = if (user.isAdmin) "Revoke Admin Privileges" else "Promote to Admin",
                        style = StreamifyType.TitleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = if (user.isAdmin) Color.White else Color.Black
                    )
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════
// TAB: GLOBAL TOP SONGS — cross-user leaderboard over user_track_plays
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun AdminTopSongsPanel() {
    var rows by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        SupabaseClient.fetchAdminTopTracks(20)
            .onSuccess { rows = it }
            .onFailure { errorMsg = it.message }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = StreamifyDimens.SpaceLG)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
        Text(
            "GLOBAL TOP SONGS · ALL LISTENERS",
            style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = StreamifyColors.Primary
        )
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        val tableRows = rows
        when {
            errorMsg != null -> Text(
                "⚠ $errorMsg\nApply the stats_overhaul migration to enable this panel.",
                color = Color(0xFFEF4444),
                style = StreamifyType.BodySmall
            )
            tableRows == null -> CircularProgressIndicator(color = StreamifyColors.Primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            else -> {
                val list = buildList {
                    for (i in 0 until tableRows.length()) tableRows.optJSONObject(i)?.let { add(it) }
                }
                if (list.isEmpty()) {
                    Text(
                        "No plays recorded yet. Apply the stats_overhaul migration; counts stream in from updated clients.",
                        color = StreamifyColors.TextSub,
                        style = StreamifyType.BodySmall
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(list, key = { i, row -> "${row.optString("track_sig")}_$i" }) { index, row ->
                            val snap = row.optJSONObject("snapshot")
                            val title = snap?.optString("title", "")?.ifBlank { null }
                                ?: row.optString("track_sig").substringBefore("_").ifBlank { "Unknown" }
                            val artist = snap?.optString("artist", "") ?: ""
                            val cover = snap?.optString("coverArtPath", "")?.takeIf { it.isNotBlank() }
                            val plays = row.optInt("plays", 0)
                            val mins = row.optLong("seconds", 0L) / 60L
                            val listeners = row.optInt("listeners", 0)

                            Surface(
                                color = if (index < 3) StreamifyColors.Primary.copy(alpha = 0.08f) else StreamifyColors.BgElevated,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "#${index + 1}",
                                        style = StreamifyType.TitleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                        color = if (index < 3) StreamifyColors.Primary else StreamifyColors.TextSub,
                                        modifier = Modifier.width(44.dp)
                                    )
                                    if (!cover.isNullOrBlank()) {
                                        AsyncImage(
                                            model = cover,
                                            contentDescription = title,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(StreamifyColors.BgSurfaceElevated),
                                            contentAlignment = Alignment.Center
                                        ) { Text("♪", color = StreamifyColors.TextSub) }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = StreamifyType.BodyMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = StreamifyColors.TextMain,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "$artist · $listeners listener${if (listeners == 1) "" else "s"}",
                                            style = StreamifyType.Caption,
                                            color = StreamifyColors.TextSub,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "$plays",
                                            style = StreamifyType.TitleMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                            color = StreamifyColors.Primary
                                        )
                                        Text("$mins min", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
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