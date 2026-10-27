package com.streamify.app.auto

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.streamify.app.jam.JamEngine
import com.streamify.app.media.playback.SharedPlaybackEngine
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime

/**
 * StreamifyAutoMediaBrowserService (Gap #54) — Android Auto integration.
 *
 * A Media3 [MediaLibraryService] exposing the driving-safe tree built by
 * [AutoRootMediaTree]: Favorites, Daily Mixes, Jam Quick-Join and Downloaded
 * Offline. It hosts NO player of its own — it binds a MediaLibrarySession
 * over the engine published by the main PlaybackService so head-unit and
 * phone always share one queue.
 *
 * Distraction-free guarantees:
 *  • onGetSession never blocks: it returns the existing session or null.
 *  • Tree building, repository reads and stream resolution all run on
 *    Dispatchers.IO behind ListenableFutures — the main thread only ever
 *    assembles value objects.
 *  • Browse levels are capped by the tree ([AutoRootMediaTree.MAX_CHILDREN]).
 *
 * Hardware steering-wheel controls (skip / play / pause) and voice search
 * hooks arrive through the standard Media3 session command path — no extra
 * wiring, no UI involvement.
 */
class StreamifyAutoMediaBrowserService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tree = AutoRootMediaTree(LiveAutoContentSource(this))

    /**
     * MEDIA-01: shared-engine lifecycle latch. The old read-once check in
     * onCreate silently missed every publication that landed between the
     * check and the next Auto bind (cold-start race). The listener fires
     * the moment the engine publishes — or immediately at registration
     * when it is already up — so the session is built exactly once per
     * engine generation, and dropped synchronously when the engine is
     * withdrawn, before its player is released under us.
     */
    private val engineListener = object : SharedPlaybackEngine.EngineListener {
        override fun onEnginePublished(player: Player) {
            if (mediaSession == null) {
                mediaSession = MediaLibrarySession.Builder(
                    this@StreamifyAutoMediaBrowserService,
                    player,
                    AutoLibraryCallback()
                ).build()
                SLog.i("AutoMedia", "engine published; Auto session latched")
            }
        }

        override fun onEngineWithdrawn() {
            // The underlying engine is about to be released — drop our
            // session wrapper NOW so the head unit never commands a dead
            // player. The latch stays registered: the next publication
            // (service restart) re-fires onEnginePublished and rebuilds.
            mediaSession?.release()
            mediaSession = null
            SLog.w("AutoMedia", "engine withdrawn; Auto session released, awaiting next publication")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Cold-start best effort: if the app process was spawned by the Auto
        // bind itself, ask the main service to boot the engine once.
        SharedPlaybackEngine.ensureEngine(this)
        // MEDIA-01: latch FIRST — covers both the engine-already-up case
        // (listener fires synchronously) and the publication-still-in-flight
        // case (listener fires when PlaybackService finishes onCreate).
        SharedPlaybackEngine.addEngineListener(engineListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // The latch in onCreate builds the session for every publication
        // timing — including ones that raced this service's own startup —
        // so a plain read is now complete.
        return mediaSession
    }

    override fun onDestroy() {
        SharedPlaybackEngine.removeEngineListener(engineListener)
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class AutoLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Auto controllers get the default command set; the seek-safe
            // forwarding player already advertises the extended commands.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root().toMedia3(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val all = tree.childrenOf(parentId).map { it.toMedia3() }
                val window = if (pageSize > 0 && page >= 0) {
                    val from = (page * pageSize).coerceAtMost(all.size)
                    val to = (from + pageSize).coerceAtMost(all.size)
                    all.subList(from, to)
                } else {
                    all
                }
                future.set(LibraryResult.ofItemList(window, params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                val node = tree.itemFor(mediaId)
                if (node == null) {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                } else {
                    future.set(LibraryResult.ofItem(node.toMedia3(), null))
                }
            }
            return future
        }

        /**
         * Auto tapped a playable leaf: resolve real playback URIs OFF the
         * main thread and hand the completed items to the shared engine.
         * Playback keys are either YouTube video ids (remote) or absolute
         * file paths (downloaded).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                val resolved = mediaItems.mapNotNull { item -> resolveForPlayback(item) }
                future.set(resolved)
            }
            return future
        }
    }

    private suspend fun resolveForPlayback(item: MediaItem): MediaItem? = runCatching {
        val key = playbackKeyOf(item.mediaId) ?: return@runCatching null
        val metadata = MediaMetadata.Builder()
            .setTitle(item.mediaMetadata.title)
            .setArtist(item.mediaMetadata.artist)
            .setArtworkUri(item.mediaMetadata.artworkUri)
            .build()

        if (key.startsWith("http") || key.startsWith("/")) {
            // Downloaded / local file: direct playback, zero network.
            val uri = if (key.startsWith("/")) Uri.fromFile(File(key)) else Uri.parse(key)
            return@runCatching item.buildUpon()
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        }

        // Remote track: fresh CDN URL on the IO thread (JIT token).
        val resolved = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamUrl(key)
        if (resolved == null || resolved.streamUrl.isBlank()) {
            return@runCatching null
        }
        item.buildUpon()
            .setUri(Uri.parse(resolved.streamUrl))
            .setMediaMetadata(metadata)
            .build()
    }.getOrElse { t ->
        SLog.st("AutoMedia", "resolve failed id=${item.mediaId}", t)
        null
    }

    /** Leaf id ("fav-<key>") → the raw playback key embedded by the tree. */
    private fun playbackKeyOf(mediaId: String): String? {
        val prefixes = listOf(
            AutoRootMediaTree.FAVORITE_PREFIX,
            AutoRootMediaTree.DAILY_MIX_PREFIX,
            AutoRootMediaTree.DOWNLOADED_PREFIX,
            AutoRootMediaTree.JAM_PREFIX
        )
        for (p in prefixes) {
            if (mediaId.startsWith(p) && mediaId.length > p.length) {
                return mediaId.removePrefix(p)
            }
        }
        return null
    }

    private fun AutoMediaNode.toMedia3(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .apply { artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }
}

/**
 * Live repository-backed tree source. Every read is a suspend call executed
 * on the service's IO scope — favorites come off a hot StateFlow, the
 * Daylist generator honors its own SWR cache, downloads are a cheap
 * directory listing.
 */
private class LiveAutoContentSource(private val context: Context) : AutoContentSource {

    override suspend fun favoriteTracks(): List<AutoMediaNode> = runCatching {
        com.streamify.app.data.repository.TrackRepository.getLikedTracks()
            .map { it.toAutoNode(AutoRootMediaTree::favoriteId) }
    }.getOrDefault(emptyList())

    override suspend fun dailyMixTracks(): List<AutoMediaNode> = runCatching {
        val now = LocalDateTime.now()
        val topArtists = com.streamify.app.data.repository.TrackRepository.getTopPlayedTracks(20)
            .map { it.artist }
            .filter { it.isNotBlank() && it != "Unknown Artist" }
            .map { it.trim().lowercase() }
            .distinct()
            .take(3)
        val daylist = com.streamify.app.data.discovery.DaylistScheduler.currentDaylist(
            hour = now.hour,
            dayOfWeek = now.dayOfWeek,
            topArtists = topArtists
        )
        daylist.tracks.map { t ->
            AutoMediaNode(
                mediaId = AutoRootMediaTree.dailyMixId(t.videoId),
                title = t.title,
                subtitle = t.artist,
                artworkUri = t.thumbnailUrl.takeIf { it.isNotBlank() },
                playbackKey = t.videoId
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun jamQuickJoin(): AutoMediaNode? = runCatching {
        val session = JamEngine.activeSession() ?: return null
        val members = JamEngine.members.value.size
        AutoMediaNode(
            mediaId = AutoRootMediaTree.jamId(session.sessionCode),
            title = "Join Jam",
            subtitle = "$members listening together",
            browsable = false,
            playable = false
        )
    }.getOrNull()

    override suspend fun downloadedTracks(): List<AutoMediaNode> = runCatching {
        val dir = File(context.filesDir, "downloads")
        if (!dir.isDirectory) return emptyList()
        dir.listFiles { f -> f.isFile && f.extension.equals("m4a", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                AutoMediaNode(
                    mediaId = AutoRootMediaTree.downloadedId(f.absolutePath),
                    title = f.nameWithoutExtension,
                    subtitle = "Offline",
                    playbackKey = f.absolutePath
                )
            }
            ?.take(AutoRootMediaTree.MAX_CHILDREN)
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun com.streamify.app.data.models.Track.toAutoNode(
        idFor: (String) -> String
    ): AutoMediaNode = AutoMediaNode(
        mediaId = idFor(ytmVideoId ?: filepath.ifBlank { id.toString() }),
        title = title,
        subtitle = artist,
        artworkUri = coverArtPath,
        playbackKey = ytmVideoId ?: filepath
    )
}
