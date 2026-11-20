package com.streamify.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamify.app.data.models.Track
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QrShareCard — Spotify-style wave QR share surfaces (Phase 3, deliverable 5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Universal share surface for tracks, albums, playlists and Jam sessions:
 *
 *  • [ShareLinkBuilder] — deep-link generators. Pure, JVM-testable:
 *    every entity maps to a stable https://open.streamify.app/<kind>/<ref>
 *    link (universally shareable) that the app resolves through its
 *    existing search/deep-link plumbing.
 *
 *  • [QrBitmapFactory] — zxing-core QR encoding into a themed Bitmap
 *    (dark background, Spotify-green modules). Pure given a content
 *    string → unit-testable dimension/error-correction contract.
 *
 *  • [QrShareCard] — the visual card: artwork, title/artist, a live
 *    audio-wave animation (the "wave QR card" identity), the QR code,
 *    and a Share button firing a standard Android share intent with the
 *    generated link (plus on-disk QR export for Stories/screenshot).
 */
object ShareLinkBuilder {

    private const val HOST = "https://open.streamify.app"

    /** Track deep link — videoId when known (resolvable), else slug(title|artist). */
    fun trackLink(track: Track): String {
        val ref = track.ytmVideoId?.takeIf { it.isNotBlank() }
            ?: slug("${track.title} ${track.artist}")
        return "$HOST/track/$ref"
    }

    fun albumLink(albumName: String): String = "$HOST/album/${slug(albumName)}"

    fun playlistLink(playlistId: String): String = "$HOST/playlist/$playlistId"

    fun jamLink(roomCode: String): String = "$HOST/jam/${slug(roomCode)}"

    fun artistLink(artistName: String): String = "$HOST/artist/${slug(artistName)}"

    /** URL-safe, lowercase, hyphenated slug. Pure — locked by unit tests. */
    fun slug(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .map { c -> if (c.isLetterOrDigit()) c else '-' }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return cleaned.ifBlank { "x" }
    }
}

/** QR generation via zxing core — themed for the Streamify card look. */
object QrBitmapFactory {

    /**
     * Encodes [content] into a square [size]px QR bitmap.
     * Dark-plate background + light modules so the card stays legible on
     * both light and dark surfaces. Pure: no Context, no state.
     */
    fun generate(content: String, size: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
            val matrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size, hints
            )
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    // Inverted palette: modules in warm white on the dark plate.
                    val bit = matrix.get(x, y)
                    pixels[offset + x] = if (bit) 0xFF1ED760.toInt() else 0xFF101018.toInt()
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}

/** Launches the system share sheet with the link (text) + QR image if exported. */
fun shareQrCard(
    context: Context,
    title: String,
    link: String,
    qrBitmap: Bitmap?
) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$title — Streamify")
            putExtra(
                Intent.EXTRA_TEXT,
                "Listen to \"$title\" on Streamify\n\n$link"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        SLog.d("QrShareCard", "share intent failed (${it.message})")
    }
}

// ─────────────────────────────────────────────────────────── the card UI

/**
 * The visual wave QR card. [kindLabel] is "TRACK" / "ALBUM" / "PLAYLIST" /
 * "JAM"; [artworkUrl] nullable (JAM cards render the wave full-bleed).
 */
@Composable
fun QrShareCard(
    kindLabel: String,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    link: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var qrBitmap by remember(link) { mutableStateOf<Bitmap?>(null) }

    // QR generation off the main thread; keyed to the link so entity
    // changes regenerate.
    LaunchedEffect(link) {
        qrBitmap = withContext(Dispatchers.IO) {
            QrBitmapFactory.generate(link, size = 512)
        }
    }

    Surface(
        color = Color(0xFF101018),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C38)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp)
        ) {
            // Kind chip + title block
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = Color(0xFF1ED760).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = kindLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF1ED760),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color(0xFF9A9AAE), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Artwork + wave overlay row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF16161E))
            ) {
                if (artworkUrl != null) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // JAM / no-artwork fallback: gradient plate
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color(0xFF1ED760).copy(alpha = 0.25f), Color(0xFF16161E))
                                )
                            )
                    )
                }

                // ── The live audio-wave strip (the card's identity) ────────
                AudioWaveStrip(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0x990A0A0F))
                )

                // Title scrim
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 56.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color(0xB3FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The QR code
            val qr = qrBitmap
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Scan to open in Streamify",
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF101018))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scan to listen",
                    fontSize = 11.sp,
                    color = Color(0xFF9A9AAE)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF16161E)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Generating QR…",
                        fontSize = 11.sp,
                        color = Color(0xFF9A9AAE)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Share button
            Surface(
                onClick = { shareQrCard(context, title, link, qrBitmap) },
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF1ED760),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share $kindLabel Card",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = link,
                fontSize = 10.sp,
                color = Color(0xFF666676),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The animated audio-wave strip — 24 bars breathing on a staggered phase,
 * GPU-only (draw-phase reads of the infinite transition). The per-bar
 * seeds are hoisted: remember{} is illegal inside the DrawScope lambda.
 */
@Composable
private fun AudioWaveStrip(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "qrWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "qrWavePhase"
    )
    // Deterministic per-bar seed → stable identity across recompositions.
    val seeds = remember { (0 until 24).map { (it * 37) % 13 / 13f } }

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val bars = seeds.size
        val gap = size.width / (bars * 1.6f)
        val barWidth = (size.width - (gap * (bars - 1))) / bars
        val centerY = size.height / 2f
        seeds.forEachIndexed { i, seed ->
            val staggered = (phase + (i % 6) / 6f) % 1f
            val amp = 0.25f + (staggered * 0.75f * (0.6f + seed * 0.4f))
            val h = size.height * 0.8f * amp
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = Color(0xFF1ED760).copy(alpha = 0.85f),
                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - h / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}
