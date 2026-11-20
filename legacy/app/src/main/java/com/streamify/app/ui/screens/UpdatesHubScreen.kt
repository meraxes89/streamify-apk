package com.streamify.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.network.ReleaseWatcherApi
import com.streamify.app.data.social.FollowGraphStore
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * UpdatesHubScreen — "Your Updates" hub (Phase 3, deliverable 5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Feed of new releases and alerts from followed artists:
 *
 *  • Aggregates ReleaseWatcherApi results across every artist the user
 *    follows (FollowGraphStore), sorted by ETA — soonest countdown first,
 *    live releases ("Out now") after.
 *  • Live countdown chips tick every second while the screen is open.
 *  • Pre-Save buttons toggle PreSaveStore intents and persist across
 *    restarts — pre-saved rows float to the top of the feed.
 *  • Back-off discipline: artists are scraped sequentially on Dispatchers.IO
 *    (never a parallel storm), failures are swallowed per-artist.
 */
@Composable
fun UpdatesHubScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var watches by remember {
        mutableStateOf<List<ReleaseWatcherApi.ReleaseWatch>>(emptyList())
    }
    var loading by remember { mutableStateOf(true) }
    var preSavedIds by remember { mutableStateOf(setOf<String>()) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // One-shot harvest across followed artists (sequential, IO).
    LaunchedEffect(Unit) {
        FollowGraphStore.initialize(context)
        preSavedIds = com.streamify.app.data.network.PreSaveStore.loadAll()
            .map { it.releaseId }.toSet()
        val artists = FollowGraphStore.followedArtists()
        if (artists.isEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        val collected = ArrayList<ReleaseWatcherApi.ReleaseWatch>(artists.size)
        for (artist in artists.take(12)) { // bound the harvest: 12 artists max
            val watch = withContext(Dispatchers.IO) {
                runCatching { ReleaseWatcherApi.watchArtist(artist) }.getOrNull()
            }
            if (watch != null && watch.releases.isNotEmpty()) {
                collected.add(watch)
            }
        }
        watches = collected
        loading = false
    }

    // Flattened feed: upcoming first (soonest ETA), then live.
    val feed = remember(watches) {
        val rows = watches.flatMap { w -> w.releases.map { w.artistName to it } }
        val upcoming = rows.filter { !it.second.isLive }
            .sortedBy { it.second.expectedAtMs ?: Long.MAX_VALUE }
        val live = rows.filter { it.second.isLive }
            .sortedByDescending { it.second.expectedAtMs ?: 0L }
        upcoming + live
    }
    // Pre-saved rows float to the top within their group.
    val orderedFeed = remember(feed, preSavedIds) {
        val pinned = feed.filter { it.second.releaseId in preSavedIds }
        val rest = feed.filter { it.second.releaseId !in preSavedIds }
        pinned + rest
    }

    // Ticking clock so countdown chips stay live.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = Color(0xFF1ED760),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Your Updates",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${feed.size} releases",
                fontSize = 12.sp,
                color = Color(0xFF9A9AAE),
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color(0xFF1ED760),
                        strokeWidth = 3.dp
                    )
                }
            }
            feed.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Album,
                            contentDescription = null,
                            tint = Color(0xFF666676),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No updates yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Follow artists to see their new releases and countdowns here.",
                            fontSize = 13.sp,
                            color = Color(0xFF9A9AAE)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 120.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = orderedFeed,
                        key = { "${it.second.releaseId}_${it.first}" }
                    ) { (artistName, release) ->
                        ReleaseRow(
                            artistName = artistName,
                            release = release,
                            nowMs = nowMs,
                            isPreSaved = release.releaseId in preSavedIds,
                            onTogglePreSave = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (release.releaseId in preSavedIds) {
                                            com.streamify.app.data.network.PreSaveStore.removePreSave(release.releaseId)
                                        } else {
                                            com.streamify.app.data.network.PreSaveStore.addPreSave(
                                                com.streamify.app.data.network.PreSaveStore.PreSave(
                                                    releaseId = release.releaseId,
                                                    artistName = artistName,
                                                    title = release.title,
                                                    expectedAtMs = release.expectedAtMs
                                                )
                                            )
                                        }
                                    }
                                    preSavedIds = com.streamify.app.data.network.PreSaveStore.loadAll()
                                        .map { it.releaseId }.toSet()
                                }
                            },
                            onArtistClick = { onArtistClick(artistName) }
                        )
                    }
                }
            }
        }
    }
}

/** One release row: art, title, live countdown chip, Pre-Save toggle. */
@Composable
private fun ReleaseRow(
    artistName: String,
    release: ReleaseWatcherApi.ReleaseCandidate,
    nowMs: Long,
    isPreSaved: Boolean,
    onTogglePreSave: () -> Unit,
    onArtistClick: () -> Unit
) {
    Surface(
        color = Color(0xFF16161E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            // Cover art (artist-tinted fallback plate)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1ED760).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (release.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = release.coverUrl,
                        contentDescription = release.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        tint = Color(0xFF1ED760),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = release.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artistName,
                    fontSize = 12.sp,
                    color = Color(0xFF9A9AAE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                CountdownChip(release = release, nowMs = nowMs)
            }

            // Pre-Save toggle
            Surface(
                onClick = onTogglePreSave,
                shape = RoundedCornerShape(18.dp),
                color = if (isPreSaved) Color(0xFF1ED760).copy(alpha = 0.16f) else Color(0xFF23232E),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isPreSaved) Color(0xFF1ED760).copy(alpha = 0.5f) else Color(0xFF2C2C38)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = if (isPreSaved) "Pre-saved" else "Pre-save",
                        tint = if (isPreSaved) Color(0xFF1ED760) else Color(0xFF9A9AAE),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isPreSaved) "Saved" else "Pre-Save",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPreSaved) Color(0xFF1ED760) else Color(0xFF9A9AAE)
                    )
                }
            }
        }
    }
}

/** Live countdown chip — pulses while the release is upcoming. */
@Composable
private fun CountdownChip(release: ReleaseWatcherApi.ReleaseCandidate, nowMs: Long) {
    val remaining = release.millisRemaining(nowMs)
    val isLive = remaining <= 0L
    val pulse by animateFloatAsState(
        targetValue = if (isLive) 1f else 0.75f + 0.25f * ((nowMs / 1000L) % 2L),
        animationSpec = tween(600),
        label = "countdownPulse"
    )
    Surface(
        color = if (isLive) Color(0xFF1ED760).copy(alpha = 0.15f) else Color(0xFF23232E),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = if (isLive) "OUT NOW"
            else release.countdownLabel(nowMs).let { "IN $it" },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = if (isLive) Color(0xFF1ED760) else Color.White.copy(alpha = pulse),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
