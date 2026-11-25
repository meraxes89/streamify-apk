package com.streamify.announcements.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.streamify.announcements.Announcement
import com.streamify.announcements.AnnouncementAction
import com.streamify.announcements.AnnouncementInboxRepository
import com.streamify.announcements.DeepLinkAllowlist
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * "What's New / Announcements Inbox" screen:
 * Enables users to discover and review persistent announcements and updates on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementInboxScreen(
    navController: NavController,
    inboxRepository: AnnouncementInboxRepository = koinInject()
) {
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var announcements by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    var readIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        announcements = inboxRepository.getAllInboxAnnouncements()
        readIds = inboxRepository.getReadAnnouncementIds()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What's New", style = typo().titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = SimpIcons.ArrowBackIosNew,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (announcements.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No announcements yet.",
                    style = typo().bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(announcements, key = { it.id }) { item ->
                    val content = item.content["en"] ?: item.content.values.firstOrNull()
                    val isRead = item.id in readIds

                    if (content != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    if (!isRead) {
                                        coroutineScope.launch {
                                            inboxRepository.markAsRead(item.id)
                                            readIds = readIds + item.id
                                        }
                                    }
                                    when (val act = item.action) {
                                        is AnnouncementAction.DeepLink -> {
                                            if (DeepLinkAllowlist.isAllowed(act.url)) {
                                                uriHandler.openUri(act.url)
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = content.title,
                                    style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!isRead) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }

                            content.bannerUrl?.let { url ->
                                Spacer(Modifier.height(12.dp))
                                AsyncImage(
                                    model = url,
                                    contentDescription = content.contentDescription,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            Markdown(
                                content = content.body,
                                typography = markdownTypography(
                                    text = typo().bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)),
                                    paragraph = typo().bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)),
                                    bullet = typo().bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (item.action !is AnnouncementAction.None) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = content.actionLabel ?: "Learn More",
                                    style = typo().labelMedium.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
