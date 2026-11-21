package com.streamify.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.models.OrchestratorStatus
import com.streamify.app.ui.components.EmptyStateView
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.IngestionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DownloadScreen(
    viewModel: IngestionViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tasks by viewModel.downloadTasks.collectAsState()
    
    var orchestratorStatus by remember { mutableStateOf(OrchestratorStatus()) }

    LaunchedEffect(Unit) {
        viewModel.observeDownloads(context)
        // Lightweight polling (1500ms) with zero UI lock or GC overhead
        while (isActive) {
            try {
                val nativeStatus = NativeBridge.getOrchestratorStatus()
                if (nativeStatus != null) {
                    orchestratorStatus = OrchestratorStatus(
                        state = nativeStatus.state,
                        currentAction = nativeStatus.currentAction,
                        activeAiTasks = nativeStatus.activeAiTasks,
                        completedAiTasks = nativeStatus.completedAiTasks,
                        totalAiTasks = nativeStatus.totalAiTasks,
                        cpuCoreBudget = nativeStatus.cpuCoreBudget,
                        activeThreads = nativeStatus.activeThreads,
                        isThrottled = nativeStatus.isThrottled,
                        cpuTemp = nativeStatus.cpuTemp,
                        isThermallyThrottled = nativeStatus.isThermallyThrottled,
                        isBatterySaver = nativeStatus.isBatterySaver
                    )
                }
            } catch (e: Exception) {
                // Ignore if native bridge not ready
            }
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(top = StreamifyDimens.SpaceGiant)
    ) {
        Text(
            text = "Downloads & Engine",
            style = StreamifyType.HeadlineLarge,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
        )

        // ── Phase 3: Quality Ladder picker + storage accounting card ──────
        DownloadQualityCard()

        // Zero-Overhead C++ Core Task Orchestrator Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceSM)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(StreamifyColors.Primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = null,
                                tint = StreamifyColors.Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "C++ Project Prometheus",
                                style = StreamifyType.TitleSmall,
                                color = StreamifyColors.TextMain
                            )
                            val statusColor by animateColorAsState(
                                targetValue = when {
                                    orchestratorStatus.isThermallyThrottled -> Color(0xFFFF5722)
                                    orchestratorStatus.isThrottled -> Color(0xFFFFB74D)
                                    else -> StreamifyColors.Primary
                                },
                                label = "statusColor"
                            )
                            Text(
                                text = when {
                                    orchestratorStatus.isThermallyThrottled -> "Thermal Throttling (${orchestratorStatus.cpuTemp}°C)"
                                    orchestratorStatus.activeAiTasks > 0 && orchestratorStatus.isThrottled -> "Throttled (UI Priority)"
                                    orchestratorStatus.activeAiTasks > 0 -> "AI Ingestion Active (${orchestratorStatus.activeAiTasks} tasks)"
                                    else -> "Idle (Power Optimized)"
                                },
                                style = StreamifyType.Caption,
                                color = statusColor
                            )
                        }
                    }

                    // CPU Temperature & Core Budget Badges
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = if (orchestratorStatus.cpuTemp >= 46) Color(0xFFFF5722).copy(alpha = 0.2f) else StreamifyColors.BgElevated,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${orchestratorStatus.cpuTemp}°C",
                                    style = StreamifyType.Caption.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    color = if (orchestratorStatus.cpuTemp >= 46) Color(0xFFFF5722) else StreamifyColors.TextSub
                                )
                            }
                        }

                        Surface(
                            color = StreamifyColors.BgElevated,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Speed,
                                    contentDescription = null,
                                    tint = StreamifyColors.TextSub,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${orchestratorStatus.cpuCoreBudget}%",
                                    style = StreamifyType.Caption.copy(fontSize = 11.sp),
                                    color = StreamifyColors.TextSub
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = orchestratorStatus.currentAction,
                    style = StreamifyType.BodyMedium,
                    color = StreamifyColors.TextSub,
                    maxLines = 1
                )

                if (orchestratorStatus.totalAiTasks > 0 && orchestratorStatus.activeAiTasks > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { orchestratorStatus.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = StreamifyColors.Primary,
                        trackColor = StreamifyColors.BgElevated
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Indexed ${orchestratorStatus.completedAiTasks} / ${orchestratorStatus.totalAiTasks} neural embeddings",
                        style = StreamifyType.Caption.copy(fontSize = 11.sp),
                        color = StreamifyColors.TextDimmed
                    )
                }
            }
        }

        // Project Titan: Sovereign Edge Mesh Contributor Card
        val edgeRepo = remember { com.streamify.app.data.repository.EdgeMeshRepository.getInstance(context) }
        val edgeState by edgeRepo.meshState.collectAsState()

        Card(
            colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceSM)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Edge Mesh Contributor ⚡",
                                style = StreamifyType.TitleSmall,
                                color = StreamifyColors.TextMain
                            )
                            val meshColor = when (edgeState.currentStatus) {
                                "COMPUTING" -> Color(0xFF00E5FF)
                                "SYNCED" -> StreamifyColors.Primary
                                else -> StreamifyColors.TextSub
                            }
                            Text(
                                text = when (edgeState.currentStatus) {
                                    "COMPUTING" -> "Analyzing: ${edgeState.currentTrackTitle.ifBlank { "Acoustic Stream" }}"
                                    "SYNCED" -> "Batch Synced to Cloud"
                                    else -> "Idle (Runs while charging overnight on Wi-Fi)"
                                },
                                style = StreamifyType.Caption,
                                color = meshColor,
                                maxLines = 1
                            )
                        }
                    }

                    // Total Contributions Badge
                    Surface(
                        color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${edgeState.totalContributions} Solved",
                            style = StreamifyType.Caption.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bandwidth Saved: ${String.format("%.1f", edgeState.bandwidthSavedMb)} MB",
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextDimmed
                    )

                    TextButton(
                        onClick = { edgeRepo.scheduleImmediateCompute(context) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Analyze Batch Now",
                            style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

        if (tasks.isEmpty()) {
            EmptyStateView(
                title = "No active downloads",
                subtitle = "Songs you download will appear here"
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
            ) {
                items(tasks, key = { it.id }) { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = StreamifyType.TitleMedium,
                                color = StreamifyColors.TextMain
                            )
                            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXS))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = task.progress,
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.Primary
                                )
                                if (task.speed.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
                                    Text(
                                        text = task.speed,
                                        style = StreamifyType.Caption,
                                        color = StreamifyColors.TextSub
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { viewModel.cancelDownload(context, task.id) }) {
                            Icon(
                                imageVector = Icons.Filled.Cancel,
                                contentDescription = "Cancel",
                                tint = StreamifyColors.TextSub
                            )
                        }
                    }
                    Divider(color = StreamifyColors.Divider, thickness = StreamifyDimens.DividerThickness)
                }
            }
        }
    }
}

/**
 * Phase 3 — Download Quality Ladder card:
 *  • Opus 251 (Studio ~160k) vs AAC 140 (Standard ~128k) vs Best available
 *  • Live storage accounting over the Streamify download tree
 *  • Unmetered-only toggle (battery constraint is always on by design)
 */
@Composable
private fun DownloadQualityCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedQuality by remember {
        mutableStateOf(com.streamify.app.data.download.QualityLadderManager.loadChoice(context))
    }
    var unmeteredOnly by remember {
        mutableStateOf(com.streamify.app.data.download.StreamifyDownloadManager.isUnmeteredOnly(context))
    }
    var storageReport by remember {
        mutableStateOf(com.streamify.app.data.download.QualityLadderManager.StorageReport(0L, 0L, 0, 0L))
    }

    // Refresh the accounting when the screen is visible (downloads mutate it).
    LaunchedEffect(Unit) {
        while (isActive) {
            storageReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val musicDir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                    "Streamify"
                )
                com.streamify.app.data.download.QualityLadderManager.accountStorage(musicDir)
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceSM)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Download Quality",
                style = StreamifyType.TitleSmall,
                color = StreamifyColors.TextMain
            )
            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

            com.streamify.app.data.download.QualityLadderManager.DownloadQuality.entries.forEach { quality ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedQuality == quality,
                        onClick = {
                            selectedQuality = quality
                            com.streamify.app.data.download.QualityLadderManager.persistChoice(context, quality)
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = StreamifyColors.Primary,
                            unselectedColor = StreamifyColors.TextSub
                        )
                    )
                    Column {
                        Text(
                            text = quality.label,
                            style = StreamifyType.BodyMedium,
                            color = if (selectedQuality == quality) StreamifyColors.TextMain else StreamifyColors.TextSub
                        )
                        Text(
                            text = when (quality) {
                                com.streamify.app.data.download.QualityLadderManager.DownloadQuality.OPUS_251 ->
                                    "WebM · Opus 251 · maximum fidelity"
                                com.streamify.app.data.download.QualityLadderManager.DownloadQuality.AAC_140 ->
                                    "MP4 · AAC 140 · maximum compatibility"
                                else -> "Resolver's own scoring (251 > 140)"
                            },
                            style = StreamifyType.Caption,
                            color = StreamifyColors.TextDimmed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
            Divider(color = StreamifyColors.Divider, thickness = StreamifyDimens.DividerThickness)
            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Storage used",
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextSub
                    )
                    Text(
                        text = com.streamify.app.data.download.QualityLadderManager.formatBytes(storageReport.playableBytes) +
                                " · ${storageReport.fileCount} files · free " +
                                com.streamify.app.data.download.QualityLadderManager.formatBytes(storageReport.freeBytes),
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextMain
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Wi-Fi only",
                        style = StreamifyType.Caption,
                        color = if (unmeteredOnly) StreamifyColors.Primary else StreamifyColors.TextSub
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = unmeteredOnly,
                        onCheckedChange = {
                            unmeteredOnly = it
                            com.streamify.app.data.download.StreamifyDownloadManager.setUnmeteredOnly(context, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = StreamifyColors.Primary,
                            checkedThumbColor = Color.White
                        )
                    )
                }
            }
        }
    }
}
