package com.streamify.app.widget

import android.content.Context
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NowPlayingWidgetState (Gap #42) — value store backing BOTH widget sizes.
 *
 * The playback service pushes discrete transitions into [update]; the
 * store throttles Glance re-renders (widget updates are expensive IPC) and
 * fans the state out to every placed widget instance. State itself is a
 * dumb value object so the JVM suite can assert mapping + throttle rules
 * without any Android framework class.
 */
data class WidgetNowPlaying(
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    val jamActive: Boolean = false,
    val updatedAtMs: Long = 0L
) {
    val hasTrack: Boolean get() = title.isNotBlank()
}

/** Widget refresh fan-out seam (Glance manager in production). */
interface WidgetUpdater {
    suspend fun refreshAll(context: Context?)
}

/** Pure mapping: playback transition fields → widget state. */
fun widgetStateOf(
    title: String?,
    artist: String?,
    artwork: String?,
    isPlaying: Boolean,
    isLiked: Boolean = false,
    jamActive: Boolean = false,
    nowMs: Long = System.currentTimeMillis()
): WidgetNowPlaying = WidgetNowPlaying(
    title = title?.takeIf { it.isNotBlank() } ?: "",
    artist = artist ?: "",
    artworkUrl = artwork?.takeIf { it.isNotBlank() },
    isPlaying = isPlaying,
    isLiked = isLiked,
    jamActive = jamActive,
    updatedAtMs = nowMs
)

/**
 * Process-wide store. Deliberately tiny: one @Volatile value + a throttled
 * push loop. The PlaybackService listener is the only writer.
 */
object NowPlayingWidgetStateStore {

    @Volatile
    var state: WidgetNowPlaying = WidgetNowPlaying()
        private set

    /** Injectable updater — the Glance fan-out installs itself at boot. */
    @Volatile
    var updater: WidgetUpdater? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushMutex = Mutex()

    @Volatile
    private var lastPushMs: Long = 0L

    /** Minimum spacing between widget re-renders. */
    const val THROTTLE_MS: Long = 900L

    /** Test seam: collapse the debounce window for deterministic tests. */
    @Volatile
    var throttleMsForTest: Long = THROTTLE_MS

    /** Test seam: reset singleton state between test cases. */
    fun resetForTest() {
        state = WidgetNowPlaying()
        updater = null
        lastPushMs = 0L
        throttleMsForTest = THROTTLE_MS
    }

    /**
     * Playback transition writer. Re-renders only when the visible fields
     * actually changed; pushes are debounced so a burst collapses into
     * spaced renders whose LAST one always lands the newest state
     * (trailing-edge guaranteed — queued pushes serialize behind the mutex
     * and each waits out the remainder of the window).
     */
    fun update(
        context: Context?,
        title: String?,
        artist: String?,
        artwork: String?,
        isPlaying: Boolean,
        isLiked: Boolean = state.isLiked,
        jamActive: Boolean = state.jamActive
    ) {
        val next = widgetStateOf(title, artist, artwork, isPlaying, isLiked, jamActive)
        if (!isVisibleChange(state, next)) {
            state = next
            return
        }
        state = next
        scope.launch {
            pushMutex.withLock {
                val wait = (lastPushMs + throttleMsForTest) - System.currentTimeMillis()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                lastPushMs = System.currentTimeMillis()
                runCatching { updater?.refreshAll(context) }
                    .onFailure { t -> SLog.st("WidgetState", "widget refresh failed", t) }
            }
        }
    }

    /** Only fields the widgets render may trigger a re-render. */
    fun isVisibleChange(old: WidgetNowPlaying, new: WidgetNowPlaying): Boolean =
        old.title != new.title ||
            old.artist != new.artist ||
            old.artworkUrl != new.artworkUrl ||
            old.isPlaying != new.isPlaying ||
            old.isLiked != new.isLiked ||
            old.jamActive != new.jamActive
}
