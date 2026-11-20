package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.util.SLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0A0E0A)
private val TermBar = Color(0xFF101510)
private val TermGreen = Color(0xFF4AF626)
private val TermDim = Color(0xFF6B7A6B)

private fun levelColor(level: Char): Color = when (level) {
    'V' -> Color(0xFF8A8F8A)
    'D' -> Color(0xFF58C4DD)
    'I' -> Color(0xFF4AF626)
    'W' -> Color(0xFFFFB74D)
    'E' -> Color(0xFFFF5370)
    'F' -> Color(0xFFFF2E88)
    else -> Color.White
}

/**
 * Diagnostic Terminal — available to every user. Capture is OFF by default and
 * costs nothing until armed; the switch arms a 4MB off-heap ring that auto-
 * disarms after 2 hours. Copy / Download / Share export the entire buffer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTerminalScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var enabled by remember { mutableStateOf(SLog.captureEnabled) }
    var rendered by remember { mutableStateOf(SLog.snapshotLines(1000)) }
    var remainingMin by remember { mutableStateOf((SLog.remainingCaptureMs() / 60000).toInt()) }
    var filterLevels by remember { mutableStateOf(setOf('V', 'D', 'I', 'W', 'E', 'F')) }
    var query by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }

    // Live tail only while armed; poll keeps it simple & allocation-light.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            rendered = SLog.snapshotLines(1000)
            remainingMin = (SLog.remainingCaptureMs() / 60000).toInt()
            delay(500)
        }
    }

    val visible = remember(rendered, filterLevels, query) {
        rendered.filter { e ->
            e.length > 20 && e[20] in filterLevels &&
                (query.isBlank() || e.contains(query, true))
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size, enabled) {
        if (!paused && visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
    }

    Scaffold(
        containerColor = TermBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = TermBar, shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text("< back", color = TermGreen, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "streamify://terminal",
                            color = TermGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp
                        )
                        Text(
                            when {
                                enabled -> "${rendered.size} lines · auto-off ${remainingMin}m"
                                else -> "capture off — flip the switch to start"
                            },
                            color = TermDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                        )
                    }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                SLog.setCaptureEnabled(on)
                                com.streamify.app.data.network.NetworkEngine.setHttpTracing(on)
                                enabled = on
                                if (!on) rendered = emptyList()
                                scope.launch {
                                    snackbar.showSnackbar(if (on) "Logging ON — auto-stops in 2h" else "Logging OFF")
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = TermGreen)
                        )
                    if (enabled) {
                        IconButton(onClick = {
                            val text = SLog.exportAll()
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbar.showSnackbar("Copied ${text.length} chars") }
                        }) { Icon(Icons.Filled.ContentCopy, "Copy log", tint = TermGreen) }
                        IconButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, SLog.exportAll())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share log"))
                        }) { Icon(Icons.Filled.Share, "Share log", tint = TermGreen) }
                        IconButton(onClick = {
                            scope.launch {
                                val r = saveLogToDownloads(context, SLog.exportAll())
                                snackbar.showSnackbar(r)
                            }
                        }) { Icon(Icons.Filled.Download, "Save to Downloads", tint = TermGreen) }
                        IconButton(onClick = {
                            SLog.clearBuffer(); rendered = emptyList()
                            scope.launch { snackbar.showSnackbar("Buffer cleared") }
                        }) { Icon(Icons.Filled.Delete, "Clear", tint = Color(0xFF9AA89A)) }
                    }
                }
            }
        }
    ) { padding ->
        if (!enabled) {
            Box(Modifier.padding(padding).fillMaxSize().background(TermBg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("⏻", color = TermDim, fontSize = 42.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Diagnostic capture is off.\nFlip the switch above to record app activity\n(search → stream resolution, errors, touches).\nAuto-stops after 2 hours.",
                        color = TermDim, fontSize = 13.sp, lineHeight = 19.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize().background(TermBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf('V', 'D', 'I', 'W', 'E', 'F').forEach { lvl ->
                    val active = lvl in filterLevels
                    FilterChip(
                        selected = active,
                        onClick = { filterLevels = if (active) filterLevels - lvl else filterLevels + lvl },
                        label = { Text("$lvl", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TermBar,
                            selectedContainerColor = levelColor(lvl).copy(alpha = 0.25f),
                            labelColor = levelColor(lvl)
                        )
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("grep…", color = Color(0xFF51604F), fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = TermGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.height(16.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = null,
                                    tint = Color(0xFF51604F),
                                    modifier = Modifier.height(14.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermGreen.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color(0xFF22301F)
                    )
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(TermBg).padding(horizontal = 8.dp)
            ) {
                items(visible.size) { i ->
                    val line = visible[i]
                    val lvl = if (line.length > 20) line[20] else 'I'
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF51604F))) { append(line.take(20)) }
                            withStyle(SpanStyle(color = levelColor(lvl), fontWeight = FontWeight.Bold)) {
                                append(line.drop(20).takeWhile { it != '/' } + "/")
                            }
                            val rest = line.dropWhile { it != '/' }.drop(1)
                            val tagEnd = rest.indexOf(':')
                            if (tagEnd > 0) {
                                withStyle(SpanStyle(color = Color(0xFF7FBF7F))) { append(rest.take(tagEnd + 1)) }
                                withStyle(SpanStyle(color = Color(0xFFD7E8D7))) { append(rest.drop(tagEnd + 1)) }
                            } else {
                                withStyle(SpanStyle(color = Color(0xFFD7E8D7))) { append(rest) }
                            }
                        },
                        fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                item {
                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            val last = visible.size - 1
                            if (!listState.layoutInfo.visibleItemsInfo.any { it.index >= last } && !paused) paused = true
                        } else if (paused) {
                            if (listState.layoutInfo.visibleItemsInfo.any { it.index >= visible.size - 1 }) paused = false
                        }
                    }
                    Text("", Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun saveLogToDownloads(context: android.content.Context, text: String): String {
    val fileName = "Streamify-log-${
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    }.log"
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "❌ Could not create Downloads entry"
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return "❌ Failed to open output stream"
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "✅ Saved to Download/$fileName (${text.length} chars)"
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).writeText(text)
            "✅ Saved to Download/$fileName"
        }
    } catch (t: Throwable) {
        "❌ Save failed: ${t.message ?: t.javaClass.simpleName}"
    }
}
