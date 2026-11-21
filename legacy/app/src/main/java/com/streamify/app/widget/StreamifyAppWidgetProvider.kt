package com.streamify.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.streamify.app.MainActivity
import com.streamify.app.R
import com.streamify.app.media.playback.PlaybackService
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * StreamifyAppWidgetProvider (Gap #42) — Compact 2×2 Now Playing widget.
 *
 * Glance-backed home/lock-screen surface: dynamic album artwork, transport
 * buttons and a like button, plus the quick "Join Jam" shortcut on the
 * expanded variant. Taps talk to the shared Media3 session directly, so
 * the widget works with no activity alive.
 */
class StreamifyAppWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreamifyCompactWidget
}

/** Expanded 4×2 variant: adds title/artist lines + Join Jam shortcut. */
class StreamifyExpandedWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreamifyExpandedWidget
}

/** Compact 2×2: artwork, play/pause, next. */
object StreamifyCompactWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = NowPlayingWidgetStateStore.state
        val artwork = state.artworkUrl?.let { loadArtwork(context, it) }
        provideContent {
            CompactWidgetContent(state, artwork)
        }
    }
}

/** Expanded 4×2: artwork, title/artist, full transport, like, Join Jam. */
object StreamifyExpandedWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = NowPlayingWidgetStateStore.state
        val artwork = state.artworkUrl?.let { loadArtwork(context, it) }
        provideContent {
            ExpandedWidgetContent(state, artwork)
        }
    }
}

/** Installs the Glance fan-out once at app boot. */
class GlanceWidgetUpdater : WidgetUpdater {
    override suspend fun refreshAll(context: Context?) {
        val ctx = context ?: return
        val manager = GlanceAppWidgetManager(ctx)
        val compact = manager.getGlanceIds(StreamifyCompactWidget::class.java)
        compact.forEach { StreamifyCompactWidget.update(ctx, it) }
        val expanded = manager.getGlanceIds(StreamifyExpandedWidget::class.java)
        expanded.forEach { StreamifyExpandedWidget.update(ctx, it) }
    }
}

private val WidgetBg = Color(0xF00F0F0F)
private val WidgetTextMain = Color(0xFFFFFFFF)
private val WidgetTextDim = Color(0xFFAAAAAA)
private val WidgetAccent = Color(0xFF3EA6FF)

@Composable
private fun CompactWidgetContent(state: WidgetNowPlaying, artwork: Bitmap?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBg)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(artwork, sizeDp = 96)
            Spacer(GlanceModifier.height(10.dp))
            TransportRow(state, compact = true)
        }
    }
}

@Composable
private fun ExpandedWidgetContent(state: WidgetNowPlaying, artwork: Bitmap?) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBg)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(artwork, sizeDp = 84)
        Spacer(GlanceModifier.width(12.dp))
        Column(
            modifier = GlanceModifier
                .fillMaxHeight()
                .defaultWeight()
        ) {
            Text(
                text = state.title.ifBlank { "Nothing playing" },
                style = TextStyle(
                    color = ColorProvider(WidgetTextMain),
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Text(
                text = state.artist.ifBlank { "Streamify" },
                style = TextStyle(color = ColorProvider(WidgetTextDim)),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(8.dp))
            TransportRow(state, compact = false)
            if (state.jamActive) {
                Spacer(GlanceModifier.height(6.dp))
                JamShortcut()
            }
        }
    }
}

@Composable
private fun TransportRow(state: WidgetNowPlaying, compact: Boolean) {
    Row(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            ActionIcon(
                resId = android.R.drawable.ic_media_previous,
                description = "Previous",
                action = actionRunCallback<SkipPreviousAction>()
            )
            Spacer(GlanceModifier.width(12.dp))
        }
        ActionIcon(
            resId = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            description = if (state.isPlaying) "Pause" else "Play",
            action = actionRunCallback<PlayPauseAction>()
        )
        Spacer(GlanceModifier.width(12.dp))
        ActionIcon(
            resId = android.R.drawable.ic_media_next,
            description = "Next",
            action = actionRunCallback<SkipNextAction>()
        )
        if (!compact) {
            Spacer(GlanceModifier.width(12.dp))
            ActionIcon(
                resId = android.R.drawable.btn_star_big_on,
                description = if (state.isLiked) "Liked" else "Like",
                action = actionRunCallback<LikeAction>()
            )
        }
    }
}

@Composable
private fun JamShortcut() {
    Text(
        text = "Join Jam",
        style = TextStyle(
            color = ColorProvider(WidgetAccent),
            fontWeight = FontWeight.Medium
        ),
        modifier = GlanceModifier.clickable(actionRunCallback<JoinJamAction>())
    )
}

@Composable
private fun Artwork(artwork: Bitmap?, sizeDp: Int) {
    val boxModifier = GlanceModifier
        .width(sizeDp.dp)
        .height(sizeDp.dp)
        .cornerRadius(12.dp)
    if (artwork != null) {
        Image(
            provider = ImageProvider(artwork),
            contentDescription = "Album art",
            modifier = boxModifier
        )
    } else {
        Image(
            provider = ImageProvider(R.drawable.logo),
            contentDescription = "Album art",
            modifier = boxModifier
        )
    }
}

@Composable
private fun ActionIcon(resId: Int, description: String, action: Action) {
    Image(
        provider = ImageProvider(resId),
        contentDescription = description,
        modifier = GlanceModifier
            .width(28.dp)
            .height(28.dp)
            .clickable(action)
    )
}

// ── Widget actions ─────────────────────────────────────────────────────

/** Shared controller session builder for widget taps. */
private suspend fun withSessionController(
    context: Context,
    block: (MediaController) -> Unit
) = withContext(Dispatchers.IO) {
    runCatching {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        val controller = future.await()
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }.onFailure { t ->
        SLog.st("WidgetAction", "controller action failed", t)
    }
}

/** Play/pause tap. */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withSessionController(context) { controller ->
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        refreshAfterTap(context)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withSessionController(context) { controller ->
            controller.seekToNextMediaItem()
        }
        refreshAfterTap(context)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withSessionController(context) { controller ->
            controller.seekToPreviousMediaItem()
        }
        refreshAfterTap(context)
    }
}

/**
 * Like tap: the session has no like command, so the tap first tries the
 * in-process router (installed by the live activity); when the app is not
 * running it opens the player instead of silently dropping the tap.
 */
class LikeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val routed = WidgetActionRouter.onToggleLike?.invoke()
        if (routed != true) openMainActivity(context)
        refreshAfterTap(context)
    }
}

/** Join Jam shortcut: rides the existing streamify://jam deep link. */
class JoinJamAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        openMainActivity(context)
    }
}

private fun openMainActivity(context: Context) {
    runCatching {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = Uri.parse("streamify://jam")
        }
        context.startActivity(intent)
    }.onFailure { t ->
        SLog.st("WidgetAction", "openMainActivity failed", t)
    }
}

private suspend fun refreshAfterTap(context: Context) {
    runCatching { GlanceWidgetUpdater().refreshAll(context) }
        .onFailure { t -> SLog.st("WidgetAction", "refresh failed", t) }
}

/** In-process routing seam for actions the session cannot express. */
object WidgetActionRouter {
    @Volatile
    var onToggleLike: (() -> Boolean)? = null
}

/** Defensive artwork decode via Coil's engine (network + disk cache). */
private suspend fun loadArtwork(context: Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val loader = coil.Coil.imageLoader(context)
            val request = coil.request.ImageRequest.Builder(context)
                .data(url)
                .size(192)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            (result as? coil.request.SuccessResult)?.drawable?.toBitmapOrNull()
        }.getOrNull()
    }

private fun Drawable.toBitmapOrNull(): Bitmap? = runCatching {
    val bmp = Bitmap.createBitmap(
        intrinsicWidth.coerceAtLeast(1),
        intrinsicHeight.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bmp
}.getOrNull()
