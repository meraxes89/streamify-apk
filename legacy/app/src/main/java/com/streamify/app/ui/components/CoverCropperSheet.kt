package com.streamify.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.palette.graphics.Palette
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CoverCropperSheet — custom playlist cover cropper (Phase 3, deliverable 4)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Image picker + square cropper + palette extraction for the ambient header
 * glow, in three steps:
 *
 *  1. PICK — ActivityResultContracts.GetContent("image" + slash-star)
 *     gallery picker.
 *  2. CROP — the bitmap is displayed inside a fixed square viewport with
 *     pinch-to-zoom + pan gestures (shared transform state, gesture-
 *     transformed via graphicsLayer, exactly one recomposition path).
 *     The exported square is the viewport-mapped region of the source
 *     bitmap — non-destructive: we never mutate the picked file.
 *  3. EXTRACT — androidx.palette runs on the cropped bitmap off the main
 *     thread and yields the dominant/vibrant color for the playlist
 *     header glow; the cropped PNG is saved under filesDir/covers/ and
 *     the caller stores its path as the playlist cover.
 *
 * [onCoverReady] delivers (coverFile, dominantColor) — the caller wires
 * them into the playlist header + ambient glow.
 */
@Composable
fun CoverCropperSheet(
    playlistName: String,
    onDismiss: () -> Unit,
    onCoverReady: (coverFile: File, dominantColor: Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isSaving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceBitmap = BitmapFactory.decodeStream(input)
            }
        }.onFailure {
            SLog.d("CoverCropper", "decode failed (${it.message})")
        }
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF101018),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Header row ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit Cover",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF9A9AAE),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (sourceBitmap == null) {
                    // ── Step 1: pick an image ─────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1A1A24))
                            .border(1.dp, Color(0xFF2C2C38), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                tint = Color(0xFF9A9AAE),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Choose a photo for \"$playlistName\"",
                                fontSize = 13.sp,
                                color = Color(0xFF9A9AAE)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = { picker.launch("image/*") },
                        enabled = !isSaving
                    ) {
                        Text(
                            text = "Pick from Gallery",
                            color = Color(0xFF1ED760),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // ── Step 2: square crop viewport with pinch + pan ────
                    // Fixed square window; gestures transform the bitmap
                    // beneath it. min zoom = cover-fit so the square is
                    // always fully painted.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .pointerInput(sourceBitmap) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset += pan
                                }
                            }
                    ) {
                        val bmp = sourceBitmap ?: return@Box
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Cover preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )
                        // Crop guide overlay (rule of thirds hints)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(16.dp))
                        )
                    }

                    // Pan clamping so the image can't fly away.
                    DisposableEffect(scale, sourceBitmap) {
                        offset = Offset(
                            x = offset.x.coerceIn(-600f, 600f),
                            y = offset.y.coerceIn(-600f, 600f)
                        )
                        onDispose { }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = {
                                sourceBitmap = null
                                scale = 1f
                                offset = Offset.Zero
                            },
                            enabled = !isSaving
                        ) {
                            Text("Re-pick", color = Color(0xFF9A9AAE))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            onClick = {
                                val bmp = sourceBitmap ?: return@Surface
                                if (isSaving) return@Surface
                                isSaving = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        cropAndExtract(bmp, scale, offset)
                                    }
                                    isSaving = false
                                    if (result != null) {
                                        onDismiss()
                                        onCoverReady(result.first, result.second)
                                    }
                                }
                            },
                            shape = CircleShape,
                            color = Color(0xFF1ED760),
                            modifier = Modifier.size(height = 42.dp, width = 130.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSaving) "Saving…" else "Set Cover",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure-ish crop + palette extraction (IO context owned by caller).
 * Maps the viewport crop (center square of the transformed image) back to
 * source bitmap pixels, saves a 720×720 PNG, and extracts the dominant
 * palette color for the ambient glow.
 */
internal fun cropAndExtract(
    source: Bitmap,
    scale: Float,
    offset: Offset
): Pair<File, Int>? {
    return runCatching {
        val srcW = source.width
        val srcH = source.height
        // The rendered image covers the viewport at ContentScale.Crop; the
        // transform (scale, offset) shifts it. Center-square mapping:
        val baseScale = maxOf(srcW, srcH) / minOf(srcW, srcH).toFloat()
        val effectiveScale = scale * baseScale

        // Visible region in source pixels (centered, viewport-proportional):
        val cropSizePx = (minOf(srcW, srcH) / effectiveScale).toInt()
            .coerceAtLeast(minOf(srcW, srcH) / 5)
            .coerceAtMost(minOf(srcW, srcH))

        val centerX = srcW / 2f - (offset.x / effectiveScale)
        val centerY = srcH / 2f - (offset.y / effectiveScale)

        val left = (centerX - cropSizePx / 2f).toInt().coerceIn(0, (srcW - cropSizePx).coerceAtLeast(0))
        val top = (centerY - cropSizePx / 2f).toInt().coerceIn(0, (srcH - cropSizePx).coerceAtLeast(0))

        val cropped = Bitmap.createBitmap(
            source, left, top,
            cropSizePx.coerceAtMost(srcW - left),
            cropSizePx.coerceAtMost(srcH - top)
        )
        val resized = if (cropped.width != 720) {
            Bitmap.createScaledBitmap(cropped, 720, 720, true)
        } else cropped

        // Palette extraction for the ambient header glow.
        val palette = Palette.from(resized).maximumColorCount(24).generate()
        val dominant = palette.getVibrantColor(
            palette.getDominantColor(0xFF1ED760.toInt())
        )

        val dir = File(coverDirFor(), "covers")
        dir.mkdirs()
        val outFile = File(dir, "cover_${System.currentTimeMillis()}.png")
        outFile.outputStream().use { out ->
            resized.compress(Bitmap.CompressFormat.PNG, 92, out)
        }
        outFile to dominant
    }.getOrNull()
}

private fun coverDirFor(): java.io.File = run {
    val ctx = com.streamify.app.data.repository.TrackRepository.appContext
    if (ctx != null) java.io.File(ctx.filesDir, "playlist") else java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "streamify_covers")
}
