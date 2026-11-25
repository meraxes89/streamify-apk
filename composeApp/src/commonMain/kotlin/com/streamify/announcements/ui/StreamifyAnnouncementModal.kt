package com.streamify.announcements.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.ui.theme.typo
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.streamify.announcements.Announcement
import com.streamify.announcements.AnnouncementAction
import com.streamify.announcements.DeepLinkAllowlist
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.later

/**
 * Modal renderer for Streamify in-app announcements with rich Markdown rendering
 * and responsive scrollable viewport management.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StreamifyAnnouncementModal(
    announcement: Announcement,
    language: String = "en",
    onDismiss: (snooze: Boolean) -> Unit,
    onAction: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val normalizedLang = language.trim().lowercase()
    val baseLang = normalizedLang.substringBefore('-')
    val content = announcement.content[normalizedLang]
        ?: announcement.content[baseLang]
        ?: announcement.content["en"]
        ?: announcement.content.values.firstOrNull()
        ?: return

    AlertDialog(
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        ),
        onDismissRequest = { onDismiss(true) },
        confirmButton = {
            if (announcement.action !is AnnouncementAction.None) {
                TextButton(onClick = {
                    onAction()
                    when (val act = announcement.action) {
                        is AnnouncementAction.DeepLink -> {
                            if (DeepLinkAllowlist.isAllowed(act.url)) {
                                uriHandler.openUri(act.url)
                            }
                        }
                        else -> {}
                    }
                }) {
                    Text(
                        text = content.actionLabel ?: "Learn More",
                        style = typo().bodySmall
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(true) }) {
                Text(
                    text = stringResource(Res.string.later),
                    style = typo().bodySmall
                )
            }
        },
        title = {
            Text(
                text = content.title,
                style = typo().titleMedium
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content.bannerUrl?.let { banner ->
                    AsyncImage(
                        model = banner,
                        contentDescription = content.contentDescription,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Markdown(
                    content = content.body,
                    typography = markdownTypography(
                        h1 = typo().labelLarge,
                        h2 = typo().labelMedium,
                        h3 = typo().labelSmall,
                        text = typo().bodySmall.copy(textAlign = TextAlign.Center),
                        paragraph = typo().bodySmall.copy(textAlign = TextAlign.Center),
                        bullet = typo().bodySmall
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
