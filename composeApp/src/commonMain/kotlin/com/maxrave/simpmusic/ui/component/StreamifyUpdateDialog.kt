package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxrave.simpmusic.data.update.ResolvedPlatformBinary
import com.maxrave.simpmusic.data.update.UpdateDownloadState
import com.maxrave.simpmusic.data.update.UpdateUrgency
import com.maxrave.simpmusic.data.update.UpdateSanitizer
import com.maxrave.simpmusic.ui.theme.fontFamily
import com.maxrave.simpmusic.ui.theme.typo
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun StreamifyUpdateDialog(
    targetVersion: String,
    currentVersion: String,
    releaseDate: String?,
    changelog: String?,
    binary: ResolvedPlatformBinary,
    isBreaking: Boolean = false,
    urgency: UpdateUrgency = if (isBreaking) UpdateUrgency.ENFORCED else UpdateUrgency.SOFT,
    remainingHours: Int = 48,
    downloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    onRetryDownload: () -> Unit = {},
    onConfirmUpdate: () -> Unit,
    onSkipLaunch: () -> Unit,
) {
    val isEnforced = urgency == UpdateUrgency.ENFORCED
    var countdownSeconds by remember(urgency) { mutableIntStateOf(urgency.skipCooldownSeconds) }

    LaunchedEffect(urgency) {
        while (countdownSeconds > 0) {
            delay(1000L)
            countdownSeconds--
        }
    }

    val formattedDate: String? =
        remember(releaseDate) {
            releaseDate?.let { input ->
                try {
                    val instant = Instant.parse(input)
                    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
                    val month = dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                    "${dt.dayOfMonth} $month ${dt.year}"
                } catch (_: Exception) {
                    input.take(10)
                }
            }
        }

    Dialog(
        onDismissRequest = { if (!isEnforced) onSkipLaunch() },
        properties =
            DialogProperties(
                dismissOnBackPress = !isEnforced,
                dismissOnClickOutside = !isEnforced && countdownSeconds == 0,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(20.dp),
                    ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            ) {
                // 1. Header: Clear App Tag & Published Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "STREAMIFY UPDATE",
                        modifier = Modifier.semantics { heading() },
                        style =
                            typo().labelSmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                            ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (formattedDate != null) {
                        Text(
                            text = formattedDate,
                            style = typo().bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Build Transition: v1.0.2 -> v1.0.3
                val fromLabel = currentVersion.ifBlank { "Current" }
                Row(
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Update from $fromLabel to $targetVersion"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = fromLabel,
                        style =
                            typo().titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                    Text(
                        text = "→",
                        modifier = Modifier.clearAndSetSemantics {},
                        style =
                            typo().titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            ),
                    )
                    Text(
                        text = targetVersion,
                        style =
                            typo().titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Hardware / Architecture Verification Chip
                if (binary.architectureName.isNotBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Verified Target: ${binary.architectureName}",
                            style = typo().labelSmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 3. Sanitized Technical Markdown Changelog (Bounded Scroll)
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 220.dp)
                            .padding(vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (!changelog.isNullOrBlank()) {
                        Markdown(
                            content = UpdateSanitizer.sanitizeChangelog(changelog),
                            typography =
                                markdownTypography(
                                    h1 = typo().labelLarge,
                                    h2 = typo().labelMedium,
                                    h3 = typo().labelSmall,
                                    text = typo().bodySmall,
                                    bullet = typo().bodySmall,
                                    paragraph = typo().bodySmall,
                                    textLink =
                                        TextLinkStyles(
                                            SpanStyle(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Normal,
                                                fontFamily = fontFamily(),
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                        ),
                                ),
                        )
                    } else {
                        Text(
                            text = "Routine performance enhancements and system stability updates.",
                            style = typo().bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                )

                Spacer(modifier = Modifier.height(14.dp))

                // In-place download progress & integrity status (live region for screen readers)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    AnimatedContent(
                        targetState = downloadState,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "UpdateDownloadState",
                    ) { state ->
                        when (state) {
                            is UpdateDownloadState.Downloading -> {
                                val mbDone = (state.bytesDownloaded / (1024f * 1024f))
                                val mbTotal = (state.totalBytes / (1024f * 1024f))
                                val progressLabel = if (state.totalBytes > 0L) {
                                    "${(mbDone * 10).toInt() / 10f} MB / ${(mbTotal * 10).toInt() / 10f} MB"
                                } else {
                                    "${(mbDone * 10).toInt() / 10f} MB"
                                }
                                val animatedProgress by animateFloatAsState(
                                    targetValue = state.progress,
                                    label = "UpdateProgress",
                                )
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "Downloading update...",
                                            style = typo().labelSmall.copy(fontSize = 12.sp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = progressLabel,
                                            style =
                                                typo().labelSmall.copy(
                                                    fontSize = 12.sp,
                                                    fontFeatureSettings = "tnum",
                                                ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (state.totalBytes > 0L) {
                                        LinearProgressIndicator(
                                            progress = { animatedProgress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                            is UpdateDownloadState.Verifying -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Verifying package integrity...",
                                        style = typo().labelSmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                            is UpdateDownloadState.Error -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Connection interrupted. Your progress is saved — tap Resume Download.",
                                        style = typo().labelSmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            else -> {
                                if (urgency == UpdateUrgency.URGENT_10S) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "Update required in ${remainingHours}h to keep streaming uninterrupted.",
                                            style = typo().labelSmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                } else if (urgency == UpdateUrgency.ENFORCED) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "Update required to continue online streaming. Offline downloads remain accessible.",
                                            style = typo().labelSmall.copy(fontSize = 12.sp),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Action Bar (Optical Alignment & Psychological Choice Architecture)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Secondary Action: Skip (hidden when ENFORCED)
                    if (!isEnforced) {
                        TextButton(
                            onClick = onSkipLaunch,
                            enabled = countdownSeconds == 0,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Text(
                                text = if (countdownSeconds > 0) "Skip in ${countdownSeconds}s" else "Skip this launch",
                                style = typo().bodySmall.copy(fontFeatureSettings = "tnum"),
                                color =
                                    if (countdownSeconds == 0) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                            )
                        }
                    }

                    // Primary Action: 1-Click Install / Download / Retry
                    val primaryLabel = when (downloadState) {
                        is UpdateDownloadState.Ready -> "Install Now"
                        is UpdateDownloadState.Downloading -> "Downloading..."
                        is UpdateDownloadState.Verifying -> "Verifying..."
                        is UpdateDownloadState.Error -> "Resume Download"
                        is UpdateDownloadState.Idle -> binary.buttonLabel.ifBlank { "Update Now" }
                    }
                    Button(
                        onClick = {
                            if (downloadState is UpdateDownloadState.Error) {
                                onRetryDownload()
                            } else {
                                onConfirmUpdate()
                            }
                        },
                        enabled = downloadState !is UpdateDownloadState.Downloading && downloadState !is UpdateDownloadState.Verifying,
                        shape = RoundedCornerShape(10.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = primaryLabel,
                            style = typo().labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}
